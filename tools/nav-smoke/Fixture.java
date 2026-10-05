package com.nhn.android.nmap;
public final class Fixture extends android.app.Activity {
    /** 가상 화면 실행 위치만 기록하며 실제 지도나 음성 성공을 대신하지 않는다. */
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.util.Log.i("NavFixture", "DISPLAY=" + getDisplay().getDisplayId());
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText("Navigation session fixture");
        setContentView(text);
    }
}
