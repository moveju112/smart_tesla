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
            // 종료 후 남은 캐시 프로세스는 허용하되 기존 화면·주행 서비스는 종료 대상에 섞지 않는다.
            if (hasExistingNavigation(target, user)) {
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
            launched = true;
            launchSafeDrive(app, target, user, displayId);
            output.println("NAVER_READY");
            output.flush();
            while (!stopping.get() && System.nanoTime() - heartbeat.get() < TimeUnit.SECONDS.toNanos(15)) Thread.sleep(250);
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
    private static boolean hasExistingNavigation(String target, int user) throws Exception {
        ActivityManager activityManager = shell.getSystemService(ActivityManager.class);
        // 셸 권한으로 전체 작업을 확인해야 배경 지도나 다른 화면의 지도도 보호할 수 있다.
        java.lang.reflect.Field taskUser = ActivityManager.RunningTaskInfo.class.getField("userId");
        for (ActivityManager.RunningTaskInfo task : activityManager.getRunningTasks(Integer.MAX_VALUE)) {
            if (taskUser.getInt(task) != user || task.numActivities <= 0) continue;
            if ((task.baseActivity != null && target.equals(task.baseActivity.getPackageName())) ||
                (task.topActivity != null && target.equals(task.topActivity.getPackageName()))) return true;
        }
        // 화면 없이 음성 안내 중일 수 있으므로 실제 전경 서비스도 함께 보호한다.
        for (ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.uid / 100000 == user && service.foreground && service.pid > 0 &&
                service.service != null && target.equals(service.service.getPackageName())) return true;
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
    private static void launchSafeDrive(NavigatorApp app, String target, int user, int displayId) throws Exception {
        String uri = String.valueOf(app.safeDriveUri(OWNER));
        java.util.List<String[]> candidates = new java.util.ArrayList<>();
        // 카카오 위젯 URI는 매니페스트 필터에 없고, 티맵은 런처 인텐트의 url extra로 안심운전을 연다.
        if (app == NavigatorApp.KAKAO) {
            candidates.add(new String[] {"-a", "android.intent.action.VIEW", "-d", uri, "-n", target + "/" + NavigatorApp.KAKAO_DEEP_LINK_ACTIVITY});
        } else if (app == NavigatorApp.TMAP) {
            // am은 DEFAULT 카테고리 없는 런처 화면을 패키지만으로 찾지 못해 컴포넌트를 직접 지정한다.
            android.content.Intent launcher = shell.getPackageManager().getLaunchIntentForPackage(target);
            if (launcher != null && launcher.getComponent() != null) {
                candidates.add(new String[] {"-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                    "-n", launcher.getComponent().flattenToString(), "--es", "url", uri});
            }
        }
        candidates.add(new String[] {"-a", "android.intent.action.VIEW", "-d", uri, "-p", target});
        for (String[] intent : candidates) {
            java.util.List<String> arguments = new java.util.ArrayList<>(java.util.Arrays.asList(
                "am", "start", "--user", Integer.toString(user), "--display", Integer.toString(displayId), "-W"));
            arguments.addAll(java.util.Arrays.asList(intent));
            String result = command(arguments.toArray(new String[0]));
            if (result.contains("Status: ok") && !result.contains("Error")) return;
            // 시작 여부가 불명확한 응답은 다른 진입점으로 다시 보내지 않는다.
            if (!result.contains("Error")) break;
        }
        throw new IllegalStateException();
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
