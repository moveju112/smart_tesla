package com.wemade.teslamacro.data.nav;

import android.content.Context;
import android.content.Intent;
import android.app.KeyguardManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.net.LocalServerSocket;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** 준비 때 얻은 셸 권한을 유지하며 같은 앱 UID의 지도 요청만 받는다. */
public final class NaverControlServer {
    private static final AtomicBoolean active = new AtomicBoolean();
    private static LocalServerSocket lock;

    /** 준비된 동안 Binder에서 대기하며 살아 있는 소유 앱에만 제어 객체를 전달한다. */
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || Build.VERSION.SDK_INT < 31 || args.length != 1) return;
        int uid = Integer.parseInt(args[0]);
        if (uid % 100000 < 10000) return;
        lock = new LocalServerSocket("smart_tesla_nav_v2_" + uid);
        NaverDisplaySession.initialize();
        Context context = NaverDisplaySession.shellContext();
        Binder bridge = new Binder() {
            /** 다른 앱 UID는 고정 명령조차 실행할 수 없게 검사한다. */
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (Binder.getCallingUid() != uid) throw new SecurityException("Owner only");
                data.enforceInterface(NavigationBridgeProvider.DESCRIPTOR);
                try {
                    if (code == 1) {
                        reply.writeNoException(); reply.writeString(active.get() ? "ACTIVE" : "AVAILABLE");
                    } else if (code == 2) {
                        ParcelFileDescriptor[] pair = ParcelFileDescriptor.createSocketPair();
                        Thread worker = new Thread(() -> handle(pair[0], uid), "nav-control");
                        worker.setDaemon(true); worker.start();
                        reply.writeNoException();
                        reply.writeParcelable(pair[1], android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                        pair[1].close();
                    } else if (code == 3 || code == 4) {
                        String packageName = data.readString();
                        String address = data.readString();
                        Intent intent = destinationIntent(packageName, address);
                        boolean available = intent != null && !active.get() && destinationUnlocked(context) &&
                            context.getPackageManager().resolveActivity(intent, 0) != null;
                        reply.writeNoException();
                        if (code == 3) reply.writeInt(available ? 1 : 0);
                        else reply.writeString(available ? launchDestination(context, uid / 100000, packageName, address) : "NOT_STARTED");
                    } else { return false; }
                    return true;
                } catch (Exception error) { reply.writeException(error); return true; }
            }
        };
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            private int deliveredPid;
            /** 살아 있는 앱 프로세스가 바뀔 때만 전달하고 강제 종료된 앱은 깨우지 않는다. */
            @Override public void run() {
                try {
                    android.app.ActivityManager manager = context.getSystemService(android.app.ActivityManager.class);
                    java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                    if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                        if (process.uid == uid && "com.wemade.teslamacro".equals(process.processName) && process.pid != deliveredPid) {
                            if (deliver(context, bridge, uid / 100000)) deliveredPid = process.pid;
                            break;
                        }
                    }
                } catch (Exception ignored) { }
                handler.postDelayed(this, 2000);
            }
        });
        android.os.Looper.loop();
    }

    /** 수신 문자열은 셸 문법으로 해석하지 않고 허용된 지도 인텐트만 구성한다. */
    private static Intent destinationIntent(String packageName, String address) {
        if (address == null) return null;
        Uri uri = Uri.parse(address);
        if (!NavigatorApp.Companion.acceptsDestination(packageName, uri)) return null;
        return new Intent(Intent.ACTION_VIEW, uri).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** 일반 목적지는 잠금 인증을 통과한 기본 화면에서만 실행한다. */
    private static boolean destinationUnlocked(Context context) {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    /** 기본 화면에 한 번 전달하고 종료 명령은 보내지 않아 기존 길안내를 보존한다. */
    private static String launchDestination(Context context, int user, String packageName, String address) {
        if (!active.compareAndSet(false, true)) return "NOT_STARTED";
        try {
            if (!destinationUnlocked(context)) return "NOT_STARTED";
            String result = NaverDisplaySession.command("am", "start", "--user", Integer.toString(user),
                "--display", "0", "-W", "-a", Intent.ACTION_VIEW, "-d", address, "-p", packageName);
            return result.contains("Status: ok") && !result.contains("Error") ? "DELIVERED" : "UNKNOWN";
        } catch (Exception error) {
            // 명령이 전달됐을 수 있으므로 응답 유실을 재실행 허가로 취급하지 않는다.
            return "UNKNOWN";
        } finally { active.set(false); }
    }

    /** 시작과 앱 재접속 때만 Binder를 고정 공급자에 전달하며 비밀값은 출력하지 않는다. */
    private static boolean deliver(Context context, Binder bridge, int user) {
        Bundle extras = new Bundle(); extras.putBinder("bridge", bridge);
        String authority = "com.wemade.teslamacro.navigation";
        Object activityManager = null;
        Object holder = null;
        try {
            // 일반 앱 스레드가 없는 셸에서는 외부 공급자 계약으로 Binder를 전달한다.
            activityManager = android.app.ActivityManager.class.getMethod("getService").invoke(null);
            Class<?> managerClass = Class.forName("android.app.IActivityManager");
            holder = managerClass.getMethod("getContentProviderExternal", String.class, int.class, android.os.IBinder.class, String.class)
                .invoke(activityManager, authority, user, null, authority);
            if (holder == null) return false;
            Object provider = holder.getClass().getField("provider").get(holder);
            android.content.AttributionSource source = new android.content.AttributionSource.Builder(2000)
                .setPackageName("com.android.shell").build();
            Class.forName("android.content.IContentProvider")
                .getMethod("call", android.content.AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                .invoke(provider, source, authority, "attach", null, extras);
            return true;
        } catch (Exception error) {
            System.err.println("NAV_CONTROL_ERROR " + error.getClass().getSimpleName());
            return false;
        } finally {
            if (holder != null) try {
                Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternal", String.class, android.os.IBinder.class)
                    .invoke(activityManager, authority, null);
            } catch (Exception ignored) { }
        }
    }

    /** 소유 채널 하나만 지도 세션을 실행하고 EOF에는 해당 세션을 정리한다. */
    private static void handle(ParcelFileDescriptor descriptor, int uid) {
        boolean ownsSession = false;
        try (ParcelFileDescriptor connection = descriptor;
             PrintStream output = new PrintStream(new ParcelFileDescriptor.AutoCloseOutputStream(connection), true, "UTF-8")) {
            java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(connection);
            // 버퍼를 미리 읽지 않아 뒤따르는 첫 심박·종료 요청을 버리지 않는다.
            StringBuilder command = new StringBuilder();
            for (int value; command.length() < 16 && (value = input.read()) != -1 && value != '\n';) command.append((char) value);
            if ("START".contentEquals(command)) {
                if (!active.compareAndSet(false, true)) { output.println("NAVER_BUSY"); return; }
                ownsSession = true;
                NaverDisplaySession.run(uid / 100000, "nmap://navigation?&appname=com.wemade.teslamacro", input, output);
            }
        } catch (Exception ignored) { }
        finally { if (ownsSession) active.set(false); }
    }
}
