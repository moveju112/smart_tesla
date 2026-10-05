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
import java.util.concurrent.TimeUnit;

/** 앱과 연결된 동안만 잠금 화면과 분리된 네이버지도 실행 화면을 유지한다. */
public final class NaverDisplaySession {
    private static final String NAVER = "com.nhn.android.nmap";
    private static volatile long heartbeat = System.nanoTime();
    private static volatile boolean stopping;

    /** 셸 권한으로만 실행하며 연결 단절·종료 요청에는 지도와 가상 화면을 함께 정리한다. */
    public static void main(String[] args) {
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31 || args.length != 2) return;
        VirtualDisplay display = null;
        Surface surface = null;
        ImageReader images = null;
        HandlerThread frames = null;
        LocalServerSocket lock = null;
        boolean launched = false;
        int user = -1;
        try {
            user = Integer.parseInt(args[0]);
            if (user < 0 || !args[1].startsWith("nmap://navigation?&appname=com.wemade.teslamacro")) return;
            lock = new LocalServerSocket("smart_tesla_naver_" + user);
            // 기존 지도 작업은 실험 종료 때 닫지 않도록 시작 자체를 거절한다.
            if (!command("pidof", NAVER).trim().isEmpty()) {
                System.out.println("NAVER_BUSY");
                return;
            }
            Thread input = new Thread(NaverDisplaySession::watchInput, "nav-session-input");
            input.setDaemon(true);
            input.start();
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("systemMain").invoke(null);
            Context system = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
            Context shell = system.createPackageContext("com.android.shell", 0);
            java.lang.reflect.Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true);
            DisplayManager manager = constructor.newInstance(shell);
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
                "-W", "-a", "android.intent.action.VIEW", "-d", args[1], "-p", NAVER);
            if (result.contains("Error") || !result.contains("Status: ok")) throw new IllegalStateException();
            System.out.println("NAVER_READY");
            System.out.flush();
            while (!stopping && System.nanoTime() - heartbeat < TimeUnit.SECONDS.toNanos(15)) Thread.sleep(250);
        } catch (Exception error) {
            // 실행 환경 예외 이름만 남겨 경로·페어링 비밀값은 출력하지 않는다.
            System.out.println("NAVER_ERROR " + error.getClass().getSimpleName());
        } finally {
            if (launched) {
                try { command("am", "force-stop", "--user", Integer.toString(user), NAVER); }
                catch (Exception error) { System.out.println("NAVER_STOP_FAILED"); }
            }
            if (display != null) display.release();
            if (surface != null) surface.release();
            if (images != null) images.close();
            if (frames != null) frames.quitSafely();
            if (lock != null) try { lock.close(); } catch (Exception ignored) { }
            System.out.println("NAVER_CLOSED");
            System.out.flush();
        }
    }

    /** 부모 앱이 살아 있음을 확인하고 EOF나 명시적 종료는 즉시 정리 신호로 바꾼다. */
    private static void watchInput() {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                if ("STOP".equals(line)) break;
                if ("PING".equals(line)) heartbeat = System.nanoTime();
            }
        } catch (Exception ignored) { }
        stopping = true;
    }

    /** 고정된 Android 명령을 셸 문자열 결합 없이 실행하고 응답 대기는 제한한다. */
    private static String command(String... arguments) throws Exception {
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
