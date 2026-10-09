package com.wemade.teslamacro.feature.settings

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.TButton

/** 기능별 필수 권한만 검사하며 업데이트 설치 권한은 설치 동작에서만 요구한다. */
internal enum class PermissionFeature(val label: String) {
    MACROS("매크로"), HISTORY("주행 기록"), STEALTH_CHARGE("스텔스 충전"), SMARTTHINGS("스마트싱스"),
    DESTINATION("목적지 자동 수신"), HUD("실시간 속도 표시"), SAFE_DRIVE("안심운전 자동 실행"),
    TESLA_SHARE("테슬라 내비 연동"), WIRELESS("안심주행 · 실험"), UPDATE("앱 업데이트 설치"),
}

/** 기능 모달에서 중앙 권한 점검으로 이동하며 설정을 돌아와도 자동으로 ON하지 않는다. */
internal val LocalOpenPermissionCheck = staticCompositionLocalOf<() -> Unit> { {} }

/** 차량 감시와 위치·알림 읽기·오버레이의 실제 의존성을 기능별로 묶는다. */
internal fun requiredFeaturePermissions(feature: PermissionFeature, sdk: Int): Set<PermissionCheck> {
    val vehicle = setOf(PermissionCheck.BLUETOOTH, PermissionCheck.NOTIFICATIONS) +
        if (sdk < Build.VERSION_CODES.S) setOf(PermissionCheck.LOCATION) else emptySet()
    return when (feature) {
        PermissionFeature.MACROS, PermissionFeature.HISTORY, PermissionFeature.STEALTH_CHARGE,
        PermissionFeature.WIRELESS -> vehicle
        PermissionFeature.SMARTTHINGS -> vehicle + PermissionCheck.LISTENER
        PermissionFeature.DESTINATION, PermissionFeature.SAFE_DRIVE -> vehicle + PermissionCheck.OVERLAY
        PermissionFeature.HUD -> setOf(PermissionCheck.LOCATION, PermissionCheck.OVERLAY, PermissionCheck.NOTIFICATIONS)
        PermissionFeature.TESLA_SHARE -> setOf(PermissionCheck.LISTENER, PermissionCheck.ACCESSIBILITY,
            PermissionCheck.OVERLAY, PermissionCheck.NOTIFICATIONS)
        PermissionFeature.UPDATE -> setOf(PermissionCheck.INSTALL)
    }
}

/** ADB 전용 기능은 일반 권한 허용과 실제 인증·연결 준비를 별도로 확인한다. */
internal fun missingFeaturePermissions(feature: PermissionFeature, sdk: Int, granted: Set<PermissionCheck>, adbPrepared: Boolean): List<String> =
    (requiredFeaturePermissions(feature, sdk) - granted).map { it.label } +
        if (feature == PermissionFeature.WIRELESS && !adbPrepared) listOf("무선 ADB 연결 준비") else emptyList()

/** ON 직전 실제 권한을 다시 읽고 부족하면 저장 대신 모달을 띄우며 OFF는 항상 허용한다. */
@Composable
internal fun rememberPermissionGuard(feature: PermissionFeature): (Boolean, () -> Unit) -> Unit {
    val context = LocalContext.current
    val openPermissions = LocalOpenPermissionCheck.current
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    if (missing.isNotEmpty()) AlertDialog(
        onDismissRequest = { missing = emptyList() },
        title = { Text("필수 권한 확인") },
        text = { Text("${feature.label}에 필요한 다음 항목을 모두 허용해 주세요.\n\n${missing.joinToString("\n")}") },
        confirmButton = { TButton("권한 점검", fillWidth = false, onClick = {
            missing = emptyList()
            openPermissions()
        }) },
        dismissButton = { TButton("취소", tone = ButtonTone.Ghost, fillWidth = false,
            onClick = { missing = emptyList() }) },
    )
    return { enabled, action ->
        val denied = if (enabled) missingFeaturePermissions(feature, Build.VERSION.SDK_INT,
            grantedPermissionChecks(context),
            (context.applicationContext as? TeslaMacroApplication)?.container?.wirelessNavigation?.state?.value?.prepared == true)
            else emptyList()
        if (denied.isEmpty()) action() else missing = denied
    }
}

/** 기존 토글 계약을 유지해 기능·설정 화면이 같은 ON 차단을 사용한다. */
@Composable
internal fun rememberPermissionToggle(feature: PermissionFeature, onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val guard = rememberPermissionGuard(feature)
    return { enabled -> guard(enabled) { onChange(enabled) } }
}
