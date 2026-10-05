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
    /** 별도 실행기를 명시했을 때만 릴리스 검증을 시작한다. */
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    /** PIN 잠금 아래 반복 실행·종료·연결 단절·심박 만료를 실제 프로세스로 검사한다. */
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"));
            require(getTargetContext().getSystemService(KeyguardManager.class).isDeviceLocked());
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
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        finish(code, result);
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
