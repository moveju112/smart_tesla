package com.wemade.teslamacro.nav;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.hardware.display.DisplayManager;
import android.os.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/** 축소된 앱의 Kotlin 클래스명에 의존하지 않고 실제 Binder 계약만 검사한다. */
public final class NavigationReleaseSmokeInstrumentation extends Instrumentation {
    private boolean stateOnly;
    private boolean diagnosticsOnly;
    private boolean applicationOnly;
    private boolean launcherFallback;

    /** 별도 실행기를 명시했을 때만 릴리스 검증을 시작한다. */
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        stateOnly = arguments != null && "true".equals(arguments.getString("stateOnly"));
        diagnosticsOnly = arguments != null && "true".equals(arguments.getString("diagnosticsOnly"));
        applicationOnly = arguments != null && "true".equals(arguments.getString("applicationOnly"));
        launcherFallback = arguments != null && "true".equals(arguments.getString("launcherFallback"));
        start();
    }

    /** PIN 잠금 아래 반복 실행·종료·연결 단절·심박 만료를 실제 프로세스로 검사한다. */
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
            if (!stateOnly && !diagnosticsOnly && !applicationOnly) require(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked());
            ProviderInfo[] providers = getTargetContext().getPackageManager().getPackageInfo(
                getTargetContext().getPackageName(), PackageManager.GET_PROVIDERS).providers;
            Field found = null;
            for (ProviderInfo info : providers) if (info.authority.equals("com.wemade.teslamacro.navigation")) {
                Class<?> provider = Class.forName(info.name, true, getTargetContext().getClassLoader());
                for (Field field : provider.getDeclaredFields()) if (field.getType() == IBinder.class) found = field;
            }
            require(found != null);
            final Field field = found;
            field.setAccessible(true);
            await(10000, () -> field.get(null) != null && ((IBinder) field.get(null)).isBinderAlive());
            IBinder bridge = (IBinder) field.get(null);
            if (applicationOnly) {
                verifyApplicationDiagnostics();
                result.putString("result", "PASS: five-second reservation, production UTF-8 diagnostics into shared DiagLog, five-second observations, normal STOP cleanup");
                finish(code, result);
                return;
            }
            if (diagnosticsOnly) {
                verifyLaunchDiagnostics(bridge);
                result.putString("result", "PASS: TMAP and Kakao versions, intent and response, actual screen changes, virtual/default displays, busy protection, STOP cleanup");
                finish(code, result);
                return;
            }
            if (stateOnly) {
                verifyNavigationState(bridge);
                result.putString("result", "PASS: live task and foreground service preserved, closed task with idle process allowed, second session blocked, STOP and restart");
                finish(code, result);
                return;
            }
            for (int mode = 0; mode < 3; mode++) {
                Parcel request = Parcel.obtain();
                Parcel response = Parcel.obtain();
                ParcelFileDescriptor descriptor;
                try {
                    request.writeInterfaceToken("com.wemade.teslamacro.NavigationControl.v2");
                    require(bridge.transact(2, request, response, 0));
                    response.readException();
                    descriptor = response.readParcelable(ParcelFileDescriptor.class.getClassLoader());
                    require(descriptor != null);
                } finally { request.recycle(); response.recycle(); }
                try (ParcelFileDescriptor connection = descriptor) {
                    InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
                    OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(connection);
                    output.write("START\nPING\n".getBytes()); output.flush();
                    StringBuilder received = new StringBuilder();
                    await(12000, () -> {
                        read(input, received);
                        require(received.indexOf("NAVER_ERROR") < 0 && received.indexOf("NAVER_BUSY") < 0);
                        return received.indexOf("NAVER_READY") >= 0;
                    });
                    require(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked());
                    require(hasDisplay());
                    if (mode == 1) connection.close();
                    else {
                        if (mode == 0) { output.write("STOP\n".getBytes()); output.flush(); }
                        await(20000, () -> {
                            read(input, received);
                            require(received.indexOf("NAVER_STOP_FAILED") < 0);
                            return received.indexOf("NAVER_CLOSED") >= 0;
                        });
                    }
                }
                await(5000, () -> !hasDisplay());
            }
            result.putString("result", "PASS: R8 Binder helper, PIN stays locked, repeated start, STOP, EOF, heartbeat expiry, display cleanup");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage() +
                "; " + error.getStackTrace()[Math.min(1, error.getStackTrace().length - 1)]);
        }
        finish(code, result);
    }

    /** 앱의 실제 실행·수신 경로를 사용해 셸 진단이 사용자 공유 로그까지 도착하는지 검사한다. */
    private void verifyApplicationDiagnostics() throws Exception {
        com.wemade.teslamacro.data.nav.WirelessNavigation navigation =
            new com.wemade.teslamacro.data.nav.WirelessNavigation(getTargetContext());
        try {
            // 일반 앱 실행 경로도 동일한 URI 우선 순서를 사용하는지 검사한다.
            java.lang.reflect.Method intents = com.wemade.teslamacro.data.nav.NaverNavigator.class.getDeclaredMethod(
                "safeDriveIntents", com.wemade.teslamacro.data.nav.NavigatorApp.class, String.class, android.net.Uri.class);
            intents.setAccessible(true);
            java.util.List<?> candidates = (java.util.List<?>) intents.invoke(
                new com.wemade.teslamacro.data.nav.NaverNavigator(getTargetContext(), null),
                com.wemade.teslamacro.data.nav.NavigatorApp.TMAP, "com.skt.tmap.ku", android.net.Uri.parse("tmap://navi"));
            require(candidates.size() == 2);
            android.content.Intent first = (android.content.Intent) candidates.get(0);
            android.content.Intent second = (android.content.Intent) candidates.get(1);
            require(android.content.Intent.ACTION_VIEW.equals(first.getAction()) && "tmap://navi".equals(first.getDataString()));
            require(android.content.Intent.ACTION_MAIN.equals(second.getAction()) && "tmap://navi".equals(second.getStringExtra("url")));
            navigation.setApp("TMAP");
            long reservedAt = System.nanoTime();
            navigation.start(true);
            Thread.sleep(1000);
            require(!navigation.getState().getValue().getRunning() && !hasDisplay());
            await(15000, () -> navigation.getState().getValue().getRunning());
            long runningAt = System.nanoTime();
            long elapsed = System.nanoTime() - reservedAt;
            require(elapsed >= java.util.concurrent.TimeUnit.SECONDS.toNanos(5) &&
                elapsed < java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
            await(7000, () -> String.join("\n", com.wemade.teslable.DiagLog.INSTANCE.getLines().getValue())
                .contains("상단=com.skt.tmap.ku/.FollowUp"));
            String logs = String.join("\n", com.wemade.teslable.DiagLog.INSTANCE.getLines().getValue());
            require(logs.contains("티맵 전달 직전"));
            require(logs.contains("버전=diagnostic-fixture"));
            require(logs.contains("기기="));
            require(logs.contains("5초 뒤 실행"));
            require(logs.contains("intent=-a android.intent.action.VIEW -d tmap://navi -p com.skt.tmap.ku"));
            require(logs.contains("intent=-a android.intent.action.MAIN") == launcherFallback);
            require(logs.contains("상태=거절") == launcherFallback);
            require(logs.contains("상태=접수"));
            require(logs.contains("실제display="));
            require(!java.util.regex.Pattern.compile("실행후=([5-9]|[1-9][0-9]+)초").matcher(logs).find());
            // 관측 종료 뒤 실제 화면을 이동해 30초 관측으로 되돌아가는 회귀를 잡는다.
            await(7000, () -> System.nanoTime() - runningAt >= java.util.concurrent.TimeUnit.SECONDS.toNanos(6));
            String before = String.join("\n", com.wemade.teslable.DiagLog.INSTANCE.getLines().getValue());
            shell("am start -W --display 0 -n com.skt.tmap.ku/.FollowUp -f 0x10000000");
            Thread.sleep(2200);
            String after = String.join("\n", com.wemade.teslable.DiagLog.INSTANCE.getLines().getValue());
            require(before.lines().filter(line -> line.contains("화면 관측 ·")).count() ==
                after.lines().filter(line -> line.contains("화면 관측 ·")).count());
        } finally {
            navigation.stop();
            await(7000, () -> !navigation.getState().getValue().getBusy() && !navigation.getState().getValue().getRunning() && !hasDisplay());
        }
    }

    /** 두 내비 대체 앱에서 실제 화면 전환·디스플레이 이동과 기존 실행 보호 진단을 검사한다. */
    private void verifyLaunchDiagnostics(IBinder bridge) throws Exception {
        String[] targets = { "com.skt.tmap.ku", "com.locnall.KimGiSa" };
        String[] commands = { "START TMAP", "START KAKAO" };
        for (int index = 0; index < targets.length; index++) {
            final String target = targets[index];
            final String command = commands[index];
            require("diagnostic-fixture".equals(getTargetContext().getPackageManager().getPackageInfo(target, 0).versionName));
            try {
                shell("am force-stop " + target);
                StringBuilder received = new StringBuilder();
                try (ParcelFileDescriptor connection = startSession(bridge, "NAVER_READY", command, received)) {
                    InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
                    await(7000, () -> {
                        read(input, received);
                        return received.indexOf("상단=" + target + "/.FollowUp") >= 0;
                    });
                    require(received.indexOf("버전=diagnostic-fixture") >= 0);
                    require(received.indexOf("API " + Build.VERSION.SDK_INT) >= 0);
                    require(received.indexOf("NAVER_DIAG 요청 ·") >= 0);
                    require(received.indexOf("상태=접수") >= 0);
                    require(java.util.regex.Pattern.compile("실제display=[1-9][0-9]*").matcher(received).find());
                    require(received.indexOf(index == 0 ? "-d tmap://navi" : "kakaonavi://widget?action=SafetyDrive") >= 0);
                    require(hasDisplay());
                    shell("am start -W --display 0 -n " + target + "/.FollowUp -f 0x10000000");
                    await(5000, () -> {
                        read(input, received);
                        return received.indexOf("실제display=0") >= 0;
                    });
                    stopSession(connection);
                }
                await(5000, () -> !hasDisplay() && shell("pidof " + target).trim().isEmpty());
                shell("am start -W -n " + target + "/.Fixture");
                StringBuilder denied = new StringBuilder();
                try (ParcelFileDescriptor connection = startSession(bridge, "NAVER_BUSY", command, denied)) { }
                require(denied.indexOf("기존 실행 보호") >= 0);
                require(denied.indexOf("NAVER_DIAG 요청 ·") < 0);
            } finally { shell("am force-stop " + target); }
        }
    }

    /** 실제 작업·전경 서비스는 보존하고 사용자가 화면을 닫은 뒤 남은 프로세스는 허용한다. */
    private void verifyNavigationState(IBinder bridge) throws Exception {
        String target = "com.nhn.android.nmap";
        String component = target + "/.Fixture$NavigationService";
        try {
            shell("am force-stop " + target);
            shell("am start -W -n " + target + "/.Fixture");
            String taskProcess = shell("pidof " + target).trim();
            require(!taskProcess.isEmpty());
            try (ParcelFileDescriptor denied = startSession(bridge, "NAVER_BUSY")) { }
            require(taskProcess.equals(shell("pidof " + target).trim()));
            require(shell("dumpsys activity activities").contains(target + "/.Fixture"));

            // 배경에서 프로세스만 회수되고 최근 앱 작업이 남은 경우는 실행을 허용한다.
            shell("input keyevent KEYCODE_HOME");
            await(5000, () -> {
                shell("am kill " + target);
                return shell("pidof " + target).trim().isEmpty();
            });
            require(shell("dumpsys activity activities").contains(target + "/.Fixture"));
            try (ParcelFileDescriptor connection = startSession(bridge, "NAVER_READY")) {
                require(hasDisplay());
                stopSession(connection);
            }
            await(5000, () -> !hasDisplay() && shell("pidof " + target).trim().isEmpty());
            shell("am start -W -n " + target + "/.Fixture");
            taskProcess = shell("pidof " + target).trim();
            require(!taskProcess.isEmpty());

            shell("am startservice -n " + component);
            shell("am start -W -n " + target + "/.Fixture --ez finish true -f 0x10008000");
            await(5000, () -> !shell("dumpsys activity activities").contains(" " + target + "/.Fixture t"));
            require(taskProcess.equals(shell("pidof " + target).trim()));
            try (ParcelFileDescriptor connection = startSession(bridge, "NAVER_READY")) {
                require(taskProcess.equals(shell("pidof " + target).trim()));
                require(hasDisplay());
                try (ParcelFileDescriptor denied = startSession(bridge, "NAVER_BUSY")) { }
                require(hasDisplay());
                stopSession(connection);
            }
            await(5000, () -> !hasDisplay() && shell("pidof " + target).trim().isEmpty());

            shell("am start-foreground-service -n " + component + " --ez foreground true");
            await(5000, () -> shell("dumpsys activity services " + target).contains("isForeground=true"));
            String serviceProcess = shell("pidof " + target).trim();
            require(!serviceProcess.isEmpty());
            try (ParcelFileDescriptor denied = startSession(bridge, "NAVER_BUSY")) { }
            require(serviceProcess.equals(shell("pidof " + target).trim()));
            require(shell("dumpsys activity services " + target).contains("isForeground=true"));
            shell("am force-stop " + target);
            try (ParcelFileDescriptor connection = startSession(bridge, "NAVER_READY")) { stopSession(connection); }
            await(5000, () -> !hasDisplay());
        } finally { shell("am force-stop " + target); }
    }

    /** 기존 Binder·가상 화면 계약으로 거절 응답과 실제 준비 응답을 분리해 검사한다. */
    private ParcelFileDescriptor startSession(IBinder bridge, String expected) throws Exception {
        return startSession(bridge, expected, "START", new StringBuilder());
    }

    /** 같은 Binder 채널로 앱별 고정 명령을 보내고 제어 응답과 진단을 함께 수집한다. */
    private ParcelFileDescriptor startSession(IBinder bridge, String expected, String command, StringBuilder received) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        ParcelFileDescriptor connection;
        try {
            request.writeInterfaceToken("com.wemade.teslamacro.NavigationControl.v2");
            require(bridge.transact(2, request, response, 0));
            response.readException();
            connection = response.readParcelable(getClass().getClassLoader());
            require(connection != null);
        } finally { request.recycle(); response.recycle(); }
        try {
            OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(connection);
            output.write((command + "\nPING\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush();
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
            await(12000, () -> {
                read(input, received);
                if (received.indexOf("NAVER_ERROR") >= 0 ||
                    ("NAVER_READY".equals(expected) && received.indexOf("NAVER_BUSY") >= 0))
                    throw new IllegalStateException("Unexpected session response: " + received);
                return received.indexOf(expected) >= 0;
            });
            return connection;
        } catch (Exception error) { connection.close(); throw error; }
    }

    /** 종료 완료 응답까지 기다려 다음 실행과 정리가 겹치지 않게 한다. */
    private void stopSession(ParcelFileDescriptor connection) throws Exception {
        OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(connection);
        output.write("STOP\n".getBytes()); output.flush();
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
        StringBuilder received = new StringBuilder();
        await(12000, () -> {
            read(input, received);
            require(received.indexOf("NAVER_STOP_FAILED") < 0);
            return received.indexOf("NAVER_CLOSED") >= 0;
        });
    }

    /** 에뮬레이터 안에서만 고정된 테스트 명령을 실행하고 진단 응답을 읽는다. */
    private String shell(String command) throws Exception {
        ParcelFileDescriptor[] descriptors = getUiAutomation().executeShellCommandRwe(command);
        try (ParcelFileDescriptor stdin = descriptors[1];
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]);
             InputStream errors = new ParcelFileDescriptor.AutoCloseInputStream(descriptors[2])) {
            stdin.close();
            String result = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) +
                new String(errors.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (result.contains("Exception occurred") || result.contains("Error:")) throw new IllegalStateException(result);
            return result;
        }
    }

    /** 이미 도착한 응답만 읽어 종료 신호 대기를 제한한다. */
    private static void read(InputStream input, StringBuilder received) throws Exception {
        byte[] buffer = new byte[1024];
        if (input.available() > 0) {
            int count = input.read(buffer);
            if (count > 0) received.append(new String(buffer, 0, count));
        }
    }

    /** 기본 화면과 구분된 실험 화면의 실제 생성·해제를 확인한다. */
    private boolean hasDisplay() {
        for (android.view.Display display : getTargetContext().getSystemService(DisplayManager.class).getDisplays()) {
            if (display.getDisplayId() != 0 && display.getName().equals("Smart Tesla Navigation")) return true;
        }
        return false;
    }

    /** 관측 조건을 만족하지 못하면 계측을 실패시킨다. */
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("Check failed"); }

    private interface Condition {
        /** 실제 상태 조회에서 발생한 오류도 테스트 실패로 전달한다. */
        boolean ready() throws Exception;
    }

    /** 상태가 바뀌면 즉시 진행하고 기한을 넘긴 검사는 실패시킨다. */
    private static void await(long timeout, Condition condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready()) {
            if (SystemClock.elapsedRealtime() >= until) throw new IllegalStateException("State timed out");
            Thread.sleep(150);
        }
    }
}
