package com.wemade.teslamacro.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wemade.teslamacro.TeslaMacroApplication
import com.wemade.teslamacro.data.nav.NavigationPairingService
import com.wemade.teslamacro.data.nav.NavigationSetup
import com.wemade.teslamacro.data.update.AppUpdater
import com.wemade.teslamacro.runtimePermissionsFor
import com.wemade.teslamacro.service.MacroService
import com.wemade.teslamacro.service.TeslaNavigationAccessibilityService
import com.wemade.teslamacro.ui.component.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 특수 권한은 OS 설정으로, 일반 권한은 묶음 요청으로 안내한다. */
internal enum class PermissionCheck(val label: String) {
    BLUETOOTH("블루투스"), LOCATION("정확한 위치"), ACTIVITY("신체 활동"), NOTIFICATIONS("앱 알림"),
    OVERLAY("다른 앱 위에 표시"), LISTENER("알림 접근"), ACCESSIBILITY("내비 목적지 읽기"),
    BATTERY("배터리 절전 제외"), INSTALL("앱 업데이트 설치"),
}

/** 위치 정밀도를 올릴 때 대략 위치도 함께 요청하고 이미 허용된 나머지는 건너뛴다. */
internal fun permissionCheckRequests(sdk: Int, granted: Set<String>): List<String> {
    val requested = runtimePermissionsFor(sdk).toMutableList()
    if (sdk >= Build.VERSION_CODES.Q) requested += Manifest.permission.ACTIVITY_RECOGNITION
    return requested.filter { it !in granted || it == Manifest.permission.ACCESS_COARSE_LOCATION &&
        Manifest.permission.ACCESS_FINE_LOCATION !in granted }
}

/** 설정 복귀 때 시스템의 실제 승인 상태를 읽고 ADB 인증과 일반 권한을 구분한다. */
internal fun grantedPermissionChecks(context: Context): Set<PermissionCheck> = buildSet {
    val sdk = Build.VERSION.SDK_INT
    val bluetooth = sdk < Build.VERSION_CODES.S || listOf(Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (bluetooth) add(PermissionCheck.BLUETOOTH)
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) add(PermissionCheck.LOCATION)
    if (sdk < Build.VERSION_CODES.Q || context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) add(PermissionCheck.ACTIVITY)
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) add(PermissionCheck.NOTIFICATIONS)
    if (Settings.canDrawOverlays(context)) add(PermissionCheck.OVERLAY)
    if (NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) add(PermissionCheck.LISTENER)
    if (TeslaNavigationAccessibilityService.enabled(context)) add(PermissionCheck.ACCESSIBILITY)
    if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true) add(PermissionCheck.BATTERY)
    if (AppUpdater.canInstallPackages(context)) add(PermissionCheck.INSTALL)
}

/** 기기 권한 한곳에서 상태를 확인하고 빠진 항목부터 허용 화면을 연다. */
@Composable
internal fun PermissionCheckPanel(battery: BatteryControls?, navigation: NavigationControls?, onRequestInstallPermission: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val granted = key(refresh) { rememberOnResume { grantedPermissionChecks(context) } }
    val prerequisites = rememberOnResume { NavigationSetup.read(context) }
    var preparingAdb by remember { mutableStateOf(false) }
    val items = PermissionCheck.entries.filter { it != PermissionCheck.ACTIVITY || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q }
    val runtimeRequest = if (LocalActivityResultRegistryOwner.current == null) null else rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh++
        val current = grantedPermissionChecks(context)
        val app = context.applicationContext as? TeslaMacroApplication
        if (app?.ready?.value == true && PermissionCheck.LOCATION in current) app.container.notifyLocationPermissionChanged()
        if (PermissionCheck.BLUETOOTH in current && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || PermissionCheck.LOCATION in current)) {
            runCatching { MacroService.start(context) }
        }
        runCatching { MacroService.refreshActivityPermission(context) }
        if (PermissionCheck.entries.any { it !in current }) Toast.makeText(context,
            "남은 항목을 눌러 허용해 주세요 · 재요청이 안 되면 앱 권한 설정에서 변경해 주세요", Toast.LENGTH_LONG).show()
    }
    val openSettings: (Intent) -> Unit = { intent ->
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "설정 화면을 열지 못했어요 · 기기 설정에서 Smart Tesla를 찾아 주세요", Toast.LENGTH_LONG).show()
        }
    }
    val requestRuntime: () -> Unit = {
        val all = runtimePermissionsFor(Build.VERSION.SDK_INT) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()
        val permissions = permissionCheckRequests(Build.VERSION.SDK_INT, all.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }.toSet())
        if (permissions.isNotEmpty() && runtimeRequest != null) runtimeRequest.launch(permissions.toTypedArray())
        else openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    val request: (PermissionCheck) -> Unit = { item ->
        when (item) {
            PermissionCheck.BLUETOOTH, PermissionCheck.LOCATION, PermissionCheck.ACTIVITY, PermissionCheck.NOTIFICATIONS -> requestRuntime()
            PermissionCheck.OVERLAY -> openOverlayPermissionSettings(context)
            PermissionCheck.LISTENER -> openNotificationListenerSettings(context)
            PermissionCheck.ACCESSIBILITY -> openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            PermissionCheck.BATTERY -> battery?.onOpenSettings?.invoke()
            PermissionCheck.INSTALL -> onRequestInstallPermission()
        }
    }
    SectionHeader("권한")
    TCard {
        SettingsDetails("권한 점검", "${items.count { it in granted }}/${items.size} 허용됨") {
            HelpTitle("권한 허용", "일반 권한은 묶어서 요청하고, 특수 권한은 각 항목에서 직접 허용해요. " +
                "부족한 권한 준비를 누르면 다음 미허용 항목을 열어요. " +
                "접근성이 제한되면 앱 정보의 메뉴에서 제한된 설정 허용 후 다시 시도해 주세요. " +
                "무선 ADB 연결은 별도 인증이며 Wi-Fi에서 페어링 코드 6자리를 직접 입력해야 해요. " +
                "권한 허용은 기능의 자동 실행을 켜지 않아요.")
            TButton("부족한 권한 준비", enabled = items.any { it !in granted },
                onClick = { items.firstOrNull { it !in granted }?.let(request) })
            items.forEach { item ->
                SettingRow(item.label, if (item in granted) "허용됨" else "허용 필요", onClick = {
                    if (item in granted && item.ordinal <= PermissionCheck.NOTIFICATIONS.ordinal)
                        openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    else request(item)
                })
            }
            SettingRow("앱 권한 설정", onClick = {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && navigation != null) {
                SettingRow("USB 디버깅", if (prerequisites.usb) "켜짐" else "꺼짐", onClick = {
                    openSettings(NavigationSetup.intent(context, NavigationSetup.Step.USB))
                })
                SettingRow("무선 ADB 연결", if (navigation.wirelessState.prepared) "연결 준비 완료"
                    else if (navigation.wirelessState.busy || preparingAdb) "준비 중…" else "연결 설정",
                    enabled = !preparingAdb && !navigation.wirelessState.busy && !navigation.wirelessState.running,
                    onClick = { scope.launch {
                        preparingAdb = true
                        try {
                            val step = NavigationSetup.resolve(context)
                            if (step != NavigationSetup.Step.READY) {
                                owner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                                Toast.makeText(context, step.message, Toast.LENGTH_LONG).show()
                                if (step == NavigationSetup.Step.PAIR) NavigationPairingService.begin(context)
                                else openSettings(NavigationSetup.intent(context, step))
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Toast.makeText(context, "ADB 연결 설정을 확인하지 못했어요 · 다시 눌러 주세요", Toast.LENGTH_LONG).show()
                        } finally { preparingAdb = false }
                    } })
            }
        }
    }
}
