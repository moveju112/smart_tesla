package com.tesla.share;

/** CLI 덤프가 못 읽는 Compose 대화상자를 외부 APK의 Android 접근성 API로 확인한다. */
public final class SelectionInstrumentation extends android.app.Instrumentation {
    /** 일회용 에뮬레이터에서만 실제 선택창 텍스트를 관측한다. */
    @Override public void onCreate(android.os.Bundle arguments) { super.onCreate(arguments); start(); }

    /** 앱 코드·난독화 이름에 의존하지 않고 제목과 비활성 공유를 확인한다. */
    @Override public void onStart() {
        android.os.Bundle result = new android.os.Bundle();
        int status = android.app.Activity.RESULT_OK;
        try {
            if (!android.os.Build.MODEL.contains("sdk")) throw new IllegalStateException("emulator only");
            android.app.UiAutomation automation = getUiAutomation();
            android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            automation.setServiceInfo(info);
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            String text = "";
            boolean confirmed = false;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                StringBuilder texts = new StringBuilder();
                boolean[] disabledShare = {false};
                for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                    android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                    if (root != null) { collect(root, texts, disabledShare, new int[]{0}, 0, false); root.recycle(); }
                    window.recycle();
                }
                text = texts.toString();
                if (text.contains("테슬라 목적지 확인") && text.contains("북지길 13") && disabledShare[0]) { confirmed = true; break; }
                android.os.SystemClock.sleep(100);
            }
            if (!confirmed) throw new IllegalStateException("selection not observed: " + text);
            result.putString("result", "PASS actual address selection dialog, bare road query, share disabled before selection");
        } catch (Throwable error) {
            status = android.app.Activity.RESULT_CANCELED;
            result.putString("error", error.toString());
        }
        finish(status, result);
    }

    /** 한정된 노드에서 텍스트·버튼 상태만 읽고 입력이나 공유는 실행하지 않는다. */
    private static void collect(android.view.accessibility.AccessibilityNodeInfo node, StringBuilder texts,
            boolean[] disabledShare, int[] visited, int depth, boolean disabledAncestor) {
        if (depth > 24 || visited[0]++ > 300) return;
        boolean disabled = disabledAncestor || !node.isEnabled();
        CharSequence text = node.getText();
        if (text != null) {
            texts.append(text).append('\n');
            if ("공유".contentEquals(text) && disabled) disabledShare[0] = true;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) { collect(child, texts, disabledShare, visited, depth + 1, disabled); child.recycle(); }
        }
    }
}
