package com.wemade.teslamacro.data.nav;

import android.content.Context;
import android.app.ActivityManager;
import android.media.ImageReader;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.net.LocalServerSocket;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/** 앱과 연결된 동안만 잠금 화면과 분리된 선택 내비 실행 화면을 유지한다. */
public final class NaverDisplaySession {
    public static final String DIAGNOSTIC_PREFIX = "NAVER_DIAG ";
    private static final String OWNER = "com.wemade.teslamacro";
    private static DisplayManager manager;
    private static Context shell;


    /** 셸 권한으로만 실행하며 연결 단절·종료 요청에는 지도와 가상 화면을 함께 정리한다. */
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31 || args.length != 2) return;
        NavigatorApp app = NavigatorApp.Companion.ofSafeDriveCommand(args[1]);
        if (app != null) run(Integer.parseInt(args[0]), app, System.in, System.out);
    }

    /** 시스템 문맥과 화면 관리자는 프로세스에서 한 번만 만들어 반복 실행 때 재초기화하지 않는다. */
    public static synchronized void initialize() throws Exception {
        if (manager != null) return;
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        shell = system.createPackageContext("com.android.shell", 0);
        java.lang.reflect.Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        manager = constructor.newInstance(shell);
    }

    /** 서버의 공급자·수신기 호출에는 셸 패키지 문맥만 사용한다. */
    public static Context shellContext() { return shell; }

    /** 통신 소켓 수명과 지도 수명만 묶어 ADB 연결 종료의 영향을 받지 않게 한다. */
    public static void run(int user, NavigatorApp app, InputStream input, PrintStream output) {
        AtomicLong heartbeat = new AtomicLong(System.nanoTime());
        AtomicBoolean stopping = new AtomicBoolean();
        VirtualDisplay display = null;
        Surface surface = null;
        ImageReader images = null;
        HandlerThread frames = null;
        LocalServerSocket lock = null;
        boolean launched = false;
        String target = null;
        try {
            if (user < 0 || !app.getSupportsSafeDrive()) return;
            lock = new LocalServerSocket("smart_tesla_naver_" + user);
            initialize();
            target = installedPackage(app);
            diagnostic(output, "셸 환경 · " + NaverNavigatorKt.navigationLaunchEnvironment(shell, target));
            // 종료 후 남은 캐시 프로세스는 허용하되 기존 화면·주행 서비스는 종료 대상에 섞지 않는다.
            if (hasExistingNavigation(target, user, output)) {
                output.println("NAVER_BUSY");
                return;
            }
            Thread watcher = new Thread(() -> watchInput(input, heartbeat, stopping), "nav-session-input");
            watcher.setDaemon(true);
            watcher.start();
            frames = new HandlerThread("nav-display-frames");
            frames.start();
            images = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
            images.setOnImageAvailableListener(reader -> {
                try (android.media.Image image = reader.acquireLatestImage()) { }
            }, new Handler(frames.getLooper()));
            surface = images.getSurface();
            // 기본 화면은 유지하고 별도 그룹의 신뢰 화면에서만 잠금 상태 실행을 허용한다.
            int flags = 1 | 8 | 256 | 1024 | 2048 | 4096;
            display = manager.createVirtualDisplay("Smart Tesla Navigation", 720, 1280, 240, surface, flags);
            if (display == null || display.getDisplay().getDisplayId() == 0) throw new IllegalStateException();
            int displayId = display.getDisplay().getDisplayId();
            diagnostic(output, "가상 화면 생성 · 요청display=" + displayId + " · 화면상태=" + display.getDisplay().getState());
            launched = true;
            launchSafeDrive(app, target, user, displayId, output);
            output.println("NAVER_READY");
            output.flush();
            long observationStart = System.nanoTime();
            long nextObservation = observationStart;
            String previousState = null;
            int observations = 0;
            while (!stopping.get() && System.nanoTime() - heartbeat.get() < TimeUnit.SECONDS.toNanos(15)) {
                long now = System.nanoTime();
                // 최초 5초에 바뀐 화면만 최대 8회 기록해 장시간 주행의 로그·조회 비용을 제한한다.
                if (observations < 8 && now >= nextObservation && now - observationStart < TimeUnit.SECONDS.toNanos(5)) {
                    String state = navigationState(target, user);
                    if (!state.equals(previousState)) {
                        diagnostic(output, "화면 관측 · 실행후=" + TimeUnit.NANOSECONDS.toSeconds(now - observationStart) +
                            "초 · 요청display=" + displayId + " · " + state);
                        previousState = state;
                        observations++;
                    }
                    nextObservation = now + TimeUnit.SECONDS.toNanos(1);
                }
                Thread.sleep(250);
            }
        } catch (Exception error) {
            // 실행 환경 예외 이름만 남겨 경로·페어링 비밀값은 출력하지 않는다.
            output.println("NAVER_ERROR " + error.getClass().getSimpleName());
        } finally {
            if (launched) {
                try { command("am", "force-stop", "--user", Integer.toString(user), target); }
                catch (Exception error) { output.println("NAVER_STOP_FAILED"); }
            }
            if (display != null) display.release();
            if (surface != null) surface.release();
            if (images != null) images.close();
            if (frames != null) frames.quitSafely();
            if (lock != null) try { lock.close(); } catch (Exception ignored) { }
            output.println("NAVER_CLOSED");
            output.flush();
        }
    }

    /** 같은 사용자의 살아 있는 지도 작업·전경 서비스만 보호하고 조회 실패는 시작 실패로 넘긴다. */
    private static boolean hasExistingNavigation(String target, int user, PrintStream output) throws Exception {
        ActivityManager activityManager = shell.getSystemService(ActivityManager.class);
        // 셸 권한으로 전체 작업을 확인해야 배경 지도나 다른 화면의 지도도 보호할 수 있다.
        java.lang.reflect.Field taskUser = ActivityManager.RunningTaskInfo.class.getField("userId");
        for (ActivityManager.RunningTaskInfo task : activityManager.getRunningTasks(Integer.MAX_VALUE)) {
            if (taskUser.getInt(task) != user || task.numActivities <= 0) continue;
            if ((task.baseActivity != null && target.equals(task.baseActivity.getPackageName())) ||
                (task.topActivity != null && target.equals(task.topActivity.getPackageName()))) {
                diagnostic(output, "기존 실행 보호 · " + taskDetails(task));
                return true;
            }
        }
        // 화면 없이 음성 안내 중일 수 있으므로 실제 전경 서비스도 함께 보호한다.
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.uid / 100000 == user && service.foreground && service.pid > 0 &&
                service.service != null && target.equals(service.service.getPackageName())) {
                diagnostic(output, "기존 실행 보호 · 전경서비스=" + service.service.flattenToShortString());
                return true;
            }
        }
        return false;
    }

    /** 셸 문맥에 설치된 첫 패키지만 대상으로 삼는다. 티맵처럼 패키지가 둘인 앱이 있다. */
    private static String installedPackage(NavigatorApp app) {
        for (String candidate : app.getPackages()) {
            try {
                shell.getPackageManager().getPackageInfo(candidate, 0);
                return candidate;
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException();
    }

    /** 앱 내부 실행과 같은 진입 후보를 순서대로 쓰되, 시작을 거절한 응답에서만 다음 후보로 넘어가 중복 실행을 막는다. */
    private static void launchSafeDrive(NavigatorApp app, String target, int user, int displayId, PrintStream output) throws Exception {
        String uri = String.valueOf(app.safeDriveUri(OWNER));
        java.util.List<String[]> candidates = new java.util.ArrayList<>();
        // 카카오 위젯은 명시 진입점이 필요하고, 티맵은 런처 extra보다 URI 전달을 우선한다.
        if (app == NavigatorApp.KAKAO) {
            candidates.add(new String[] {"-a", "android.intent.action.VIEW", "-d", uri, "-n", target + "/" + NavigatorApp.KAKAO_DEEP_LINK_ACTIVITY});
        } else if (app == NavigatorApp.TMAP) {
            candidates.add(new String[] {"-a", "android.intent.action.VIEW", "-d", uri, "-p", target});
            // am은 DEFAULT 카테고리 없는 런처 화면을 패키지만으로 찾지 못해 컴포넌트를 직접 지정한다.
            android.content.Intent launcher = shell.getPackageManager().getLaunchIntentForPackage(target);
            if (launcher != null && launcher.getComponent() != null) {
                candidates.add(new String[] {"-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                    "-n", launcher.getComponent().flattenToString(), "--es", "url", uri});
            }
        }
        if (app != NavigatorApp.TMAP) {
            candidates.add(new String[] {"-a", "android.intent.action.VIEW", "-d", uri, "-p", target});
        }
        for (String[] intent : candidates) {
            java.util.List<String> arguments = new java.util.ArrayList<>(java.util.Arrays.asList(
                "am", "start", "--user", Integer.toString(user), "--display", Integer.toString(displayId), "-W"));
            arguments.addAll(java.util.Arrays.asList(intent));
            diagnostic(output, "요청 · 요청display=" + displayId + " · intent=" + String.join(" ", intent));
            String result = command(arguments.toArray(new String[0]));
            diagnostic(output, "응답 · 요청display=" + displayId + " · " + launchResultDetails(result));
            if (result.contains("Status: ok") && !result.contains("Error")) return;
            // 시작 여부가 불명확한 응답은 다른 진입점으로 다시 보내지 않는다.
            if (!result.contains("Error")) break;
        }
        throw new IllegalStateException();
    }

    /** 셸 진단은 기존 연결에만 보내며 줄바꿈·길이를 제한해 제어 응답과 분리한다. */
    private static void diagnostic(PrintStream output, String message) {
        String safe = message.replace('\n', ' ').replace('\r', ' ');
        output.println(DIAGNOSTIC_PREFIX + safe.substring(0, Math.min(safe.length(), 1200)));
        output.flush();
    }

    /** 시스템 응답에서 상태·컴포넌트만 골라 인텐트 전체나 임의 오류 본문을 기록하지 않는다. */
    static String launchResultDetails(String result) {
        String component = "미확인";
        for (String line : result.split("\\r?\\n")) {
            if (!line.trim().startsWith("Activity:")) continue;
            String value = line.trim().substring("Activity:".length()).trim();
            if (value.length() <= 256 && value.matches("[A-Za-z0-9_.$/]+")) component = value;
        }
        String status = result.contains("Error") ? "거절" : result.contains("Status: ok") ? "접수" : "불명";
        return "상태=" + status + " · 진입=" + component;
    }

    /** 선택한 앱의 화면·전경 서비스만 관측하고 조회 실패는 실험 실행을 중단하지 않는다. */
    private static String navigationState(String target, int user) {
        try {
            ActivityManager activityManager = shell.getSystemService(ActivityManager.class);
            java.lang.reflect.Field taskUser = ActivityManager.RunningTaskInfo.class.getField("userId");
            java.util.SortedSet<String> records = new java.util.TreeSet<>();
            for (ActivityManager.RunningTaskInfo task : activityManager.getRunningTasks(Integer.MAX_VALUE)) {
                if (taskUser.getInt(task) != user || task.numActivities <= 0) continue;
                if ((task.baseActivity != null && target.equals(task.baseActivity.getPackageName())) ||
                    (task.topActivity != null && target.equals(task.topActivity.getPackageName()))) {
                    records.add(taskDetails(task));
                    if (records.size() >= 3) break;
                }
            }
            for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.uid / 100000 == user && service.foreground && service.pid > 0 &&
                    service.service != null && target.equals(service.service.getPackageName())) {
                    records.add("전경서비스=" + service.service.flattenToShortString());
                    if (records.size() >= 6) break;
                }
            }
            return records.isEmpty() ? "화면·전경서비스 없음" : String.join(" | ", records);
        } catch (Exception error) {
            return "화면 조회 실패=" + error.getClass().getSimpleName();
        }
    }

    /** 실제 작업의 기본·상단 화면과 배치된 디스플레이를 표시하며 화면 내용은 수집하지 않는다. */
    private static String taskDetails(ActivityManager.RunningTaskInfo task) {
        return "기본=" + (task.baseActivity == null ? "없음" : task.baseActivity.flattenToShortString()) +
            " · 상단=" + (task.topActivity == null ? "없음" : task.topActivity.flattenToShortString()) +
            " · 실제display=" + taskField(task, "displayId") + " · 표시=" + taskField(task, "isVisible") +
            " · 화면수=" + task.numActivities;
    }

    /** 제조사·Android 버전에서 공개되지 않은 작업 필드는 미확인으로 남기고 나머지 조회를 유지한다. */
    private static String taskField(ActivityManager.RunningTaskInfo task, String field) {
        try { return String.valueOf(ActivityManager.RunningTaskInfo.class.getField(field).get(task)); }
        catch (Exception ignored) { return "미확인"; }
    }

    /** 부모 앱이 살아 있음을 확인하고 EOF나 명시적 종료는 즉시 정리 신호로 바꾼다. */
    private static void watchInput(InputStream source, AtomicLong heartbeat, AtomicBoolean stopping) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(source));
            String line;
            while ((line = input.readLine()) != null) {
                if ("STOP".equals(line)) break;
                if ("PING".equals(line)) heartbeat.set(System.nanoTime());
            }
        } catch (Exception ignored) { }
        stopping.set(true);
    }

    /** 고정된 Android 명령을 셸 문자열 결합 없이 실행하고 응답 대기는 제한한다. */
    static String command(String... arguments) throws Exception {
        java.lang.Process child = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        if (!child.waitFor(10, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new IllegalStateException();
        }
        // 실행 거절 응답은 호출자가 문구로 판별하므로 종료 코드만으로 버리지 않는다.
        boolean reportsOutput = "pidof".equals(arguments[0]) || ("am".equals(arguments[0]) && "start".equals(arguments[1]));
        if (child.exitValue() != 0 && !reportsOutput) throw new IllegalStateException();
        byte[] bytes = new byte[8192];
        int count = child.getInputStream().read(bytes);
        return count <= 0 ? "" : new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8);
    }
}
