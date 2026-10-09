package com.wemade.teslamacro.service;

import android.app.Activity;
import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** R8으로 축소된 구버전 APK에서도 Android 기본 API만으로 고정 상태를 준비한다. */
public final class QuickActionLegacyPinInstrumentation extends Instrumentation {
    private boolean verifyUpdated;
    /** 기존 Java 릴리스 실행기처럼 Kotlin 런타임과 앱 내부 클래스명에 의존하지 않는다. */
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        verifyUpdated = arguments != null && "true".equals(arguments.getString("verifyUpdated"));
        start();
    }

    /** 실제 런처 확인 버튼으로 정적 아이콘을 홈에 고정하고 보존할 테스트 데이터를 남긴다. */
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("generic"));
            if (verifyUpdated) {
                verifyReleaseUpgrade();
                result.putString("result", "PASS: release upgrade with immutable pin; new dynamic IDs; macro and app data preserved");
                finish(code, result);
                return;
            }
            require(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).getLongVersionCode() == 298);
            ShortcutManager manager = getTargetContext().getSystemService(ShortcutManager.class);
            require(manager.getManifestShortcuts().stream().anyMatch(shortcut -> shortcut.getId().equals("open_frunk") && shortcut.isImmutable()));
            getUiAutomation().setServiceInfo(configureAccessibility());
            runOnMainSync(() -> getTargetContext().startActivity(new Intent()
                .setClassName(getTargetContext(), "com.wemade.teslamacro.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            waitForIdleSync();
            getUiAutomation().executeAndWaitForEvent(
                () -> runOnMainSync(() -> require(manager.requestPinShortcut(new ShortcutInfo.Builder(getTargetContext(), "open_frunk").build(), null))),
                event -> "com.android.launcher3.dragndrop.AddItemActivity".contentEquals(String.valueOf(event.getClassName())), 10000).recycle();
            long buttonDeadline = SystemClock.uptimeMillis() + 5000;
            while (findAddButton() == null) {
                require(SystemClock.uptimeMillis() < buttonDeadline);
                getUiAutomation().clearCache();
                SystemClock.sleep(50);
            }
            AccessibilityNodeInfo button = findAddButton();
            require(button != null);
            while (!button.isClickable()) { button = button.getParent(); require(button != null); }
            require(button.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (manager.getPinnedShortcuts().stream().noneMatch(shortcut -> shortcut.getId().equals("open_frunk") && shortcut.isImmutable())) {
                require(SystemClock.uptimeMillis() < deadline);
                SystemClock.sleep(50);
            }
            File macros = new File(getTargetContext().getFilesDir(), "macros.json");
            require(macros.isFile());
            JSONArray rules = new JSONArray(new String(Files.readAllBytes(macros.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            JSONObject fixture = new JSONObject("{\"id\":\"shortcut-upgrade-preserved\",\"name\":\"! 업데이트 보존\",\"enabled\":false,\"triggers\":[{\"type\":\"manual\"}],\"actions\":[{\"type\":\"run\",\"command\":{\"type\":\"lock\"}}]}");
            rules.put(fixture);
            Files.write(macros.toPath(), rules.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            require(getTargetContext().getSharedPreferences("shortcut-upgrade-fixture", 0).edit().putInt("marker", 77).commit());
            result.putString("result", "PASS: legacy immutable shortcut pinned through launcher; app data and macro fixture saved");
        } catch (Throwable error) {
            code = Activity.RESULT_CANCELED;
            String message = String.valueOf(error.getMessage());
            result.putString("error", error.getClass().getSimpleName() + ": " + message.substring(0, Math.min(200, message.length()))
                + "; windows=" + getUiAutomation().getWindows().size()
                + "; at=" + error.getStackTrace()[Math.min(1, error.getStackTrace().length - 1)]);
        }
        finish(code, result);
    }

    /** 구버전 고정 상태에서 릴리스 APK로 직접 업데이트해 R8 적용 후 실제 발행도 검사한다. */
    private void verifyReleaseUpgrade() throws Exception {
        require(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0).getLongVersionCode() == 300);
        ShortcutManager manager = getTargetContext().getSystemService(ShortcutManager.class);
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (manager.getDynamicShortcuts().stream().noneMatch(shortcut -> shortcut.getId().equals("quick-v2-open_frunk"))) {
            require(SystemClock.uptimeMillis() < deadline);
            SystemClock.sleep(50);
        }
        require(manager.getPinnedShortcuts().stream().anyMatch(shortcut -> shortcut.getId().equals("open_frunk") && shortcut.isImmutable() && !shortcut.isEnabled()));
        for (String action : new String[] {"open_frunk", "open_trunk", "vent_windows"}) {
            ShortcutInfo shortcut = manager.getDynamicShortcuts().stream()
                .filter(item -> item.getId().equals("quick-v2-" + action)).findFirst().orElseThrow(IllegalStateException::new);
            Intent intent = shortcut.getIntent();
            require(intent != null && action.equals(intent.getStringExtra("action")));
            require(intent.getStringExtra("shortcut_token") != null && intent.getStringExtra("shortcut_token").matches("[0-9a-f]{64}"));
        }
        require(manager.getDynamicShortcuts().stream().anyMatch(shortcut -> shortcut.getId().equals("macro-shortcut-upgrade-preserved") && "! 업데이트 보존".contentEquals(shortcut.getShortLabel())));
        require(getTargetContext().getSharedPreferences("shortcut-upgrade-fixture", 0).getInt("marker", 0) == 77);
        JSONArray rules = new JSONArray(new String(Files.readAllBytes(new File(getTargetContext().getFilesDir(), "macros.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        boolean preserved = false;
        for (int index = 0; index < rules.length(); index++) {
            JSONObject rule = rules.getJSONObject(index);
            if ("shortcut-upgrade-preserved".equals(rule.getString("id"))) {
                preserved = "! 업데이트 보존".equals(rule.getString("name")) && !rule.getBoolean("enabled");
            }
        }
        require(preserved);
    }

    /** Activity와 런처 대화상자의 접근성 창을 함께 조회한다. */
    private AccessibilityServiceInfo configureAccessibility() {
        AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        return info;
    }

    /** 실제 런처의 홈 화면 추가 버튼을 찾아 사용자와 같은 확인 경로를 사용한다. */
    private AccessibilityNodeInfo findAddButton() {
        for (AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            AccessibilityNodeInfo found = findAddButton(window.getRoot());
            if (found != null) return found;
        }
        return null;
    }

    /** 런처의 일반 View 노드에서 추가 버튼을 직접 순회한다. */
    private AccessibilityNodeInfo findAddButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String text = String.valueOf(node.getText());
        if ("Add automatically".equalsIgnoreCase(text) || "Add to home screen".equalsIgnoreCase(text)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = findAddButton(node.getChild(index));
            if (found != null) return found;
        }
        return null;
    }

    /** 준비 실패를 성공으로 넘기지 않고 실행 결과에 표시한다. */
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("구버전 고정 상태 준비 실패");
    }
}
