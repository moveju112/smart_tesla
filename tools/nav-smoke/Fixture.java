package com.nhn.android.nmap;
public final class Fixture extends android.app.Activity {
    /** 가상 화면 실행 위치만 기록하며 실제 지도나 음성 성공을 대신하지 않는다. */
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("finish", false)) { finishAndRemoveTask(); return; }
        android.util.Log.i("NavFixture", "DISPLAY=" + getDisplay().getDisplayId());
        String destination = getIntent().getStringExtra("destination");
        if (destination != null) {
            android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
            fields.setOrientation(android.widget.LinearLayout.VERTICAL);
            fields.setId(getResources().getIdentifier("route_search_bar", "id", getPackageName()));
            android.widget.TextView origin = new android.widget.TextView(this);
            origin.setText("현재 위치"); fields.addView(origin);
            android.widget.TextView target = new android.widget.TextView(this);
            target.setText(destination); fields.addView(target);
            setContentView(fields);
            return;
        }
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("Navigation session fixture");
        setContentView(text);
    }

    /** 화면 종료 뒤 남은 일반 서비스와 화면 없는 주행 전경 서비스를 구분해 재현한다. */
    public static final class NavigationService extends android.app.Service {
        /** 전경 요청 때만 알림을 올려 단순 잔여 프로세스와 같은 PID로 비교한다. */
        @Override public int onStartCommand(android.content.Intent intent, int flags, int startId) {
            if (intent != null && intent.getBooleanExtra("foreground", false)) {
                String channel = "navigation_fixture";
                getSystemService(android.app.NotificationManager.class).createNotificationChannel(
                    new android.app.NotificationChannel(channel, "Navigation fixture", android.app.NotificationManager.IMPORTANCE_LOW));
                boolean guidance = intent.getBooleanExtra("guidance", false);
                startForeground(1, new android.app.Notification.Builder(this, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(guidance ? "네이버 지도" : "Navigation fixture")
                    .setContentText(guidance ? "내비게이션 - 안내 중" : "").build());
            }
            return START_NOT_STICKY;
        }

        /** 바인딩을 사용하지 않아 서비스 생존 여부만 검증한다. */
        @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
    }
}
