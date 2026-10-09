package com.tesla.share;

/** 실제 계정·차량 없이 공식 앱 공유 인텐트의 전달 횟수와 본문만 검증한다. */
public final class ShareActivity extends android.app.Activity {
    /** 목적지 요청 수신을 기록하고 즉시 종료해 원래 화면으로 돌아간다. */
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.util.Log.i("TeslaShareFixture", "RECEIVED=" + getIntent().getStringExtra(android.content.Intent.EXTRA_TEXT));
        finish();
    }
}
