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

    /** 별도 실행기를 명시했을 때만 릴리스 검증을 시작한다. */
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        stateOnly = arguments != null && "true".equals(arguments.getString("stateOnly"));
        start();
    }

    /** PIN 잠금 아래 반복 실행·종료·연결 단절·심박 만료를 실제 프로세스로 검사한다. */
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
            if (!stateOnly) require(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked());
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
            output.write("START\nPING\n".getBytes()); output.flush();
            InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
            StringBuilder received = new StringBuilder();
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
