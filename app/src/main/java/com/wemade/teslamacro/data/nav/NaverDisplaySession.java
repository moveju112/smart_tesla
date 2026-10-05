package com.wemade.teslamacro.data.nav;

import android.content.Context;
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

/** 앱과 연결된 동안만 잠금 화면과 분리된 네이버지도 실행 화면을 유지한다. */
public final class NaverDisplaySession {
    private static final String NAVER = "com.nhn.android.nmap";
    private static DisplayManager manager;
    private static Context shell;


    /** 셸 권한으로만 실행하며 연결 단절·종료 요청에는 지도와 가상 화면을 함께 정리한다. */
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31 || args.length != 2) return;
        run(Integer.parseInt(args[0]), args[1], System.in, System.out);
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
    public static void run(int user, String uri, InputStream input, PrintStream output) {
        AtomicLong heartbeat = new AtomicLong(System.nanoTime());
        AtomicBoolean stopping = new AtomicBoolean();
        VirtualDisplay display = null;
        Surface surface = null;
        ImageReader images = null;
        HandlerThread frames = null;
        LocalServerSocket lock = null;
        boolean launched = false;
        try {
            if (user < 0 || !uri.equals("nmap://navigation?&appname=com.wemade.teslamacro")) return;
            lock = new LocalServerSocket("smart_tesla_naver_" + user);
            // 기존 지도 작업은 실험 종료 때 닫지 않도록 시작 자체를 거절한다.
            if (!command("pidof", NAVER).trim().isEmpty()) {
                output.println("NAVER_BUSY");
                return;
            }
            Thread watcher = new Thread(() -> watchInput(input, heartbeat, stopping), "nav-session-input");
            watcher.setDaemon(true);
            watcher.start();
            initialize();
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
            String result = command("am", "start", "--user", Integer.toString(user), "--display", Integer.toString(displayId),
                "-W", "-a", "android.intent.action.VIEW", "-d", uri, "-p", NAVER);
            if (result.contains("Error") || !result.contains("Status: ok")) throw new IllegalStateException();
            output.println("NAVER_READY");
            output.flush();
            while (!stopping.get() && System.nanoTime() - heartbeat.get() < TimeUnit.SECONDS.toNanos(15)) Thread.sleep(250);
        } catch (Exception error) {
            // 실행 환경 예외 이름만 남겨 경로·페어링 비밀값은 출력하지 않는다.
            output.println("NAVER_ERROR " + error.getClass().getSimpleName());
        } finally {
            if (launched) {
                try { command("am", "force-stop", "--user", Integer.toString(user), NAVER); }
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
        if (child.exitValue() != 0 && !"pidof".equals(arguments[0])) throw new IllegalStateException();
        byte[] bytes = new byte[8192];
        int count = child.getInputStream().read(bytes);
        return count <= 0 ? "" : new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8);
    }
}
