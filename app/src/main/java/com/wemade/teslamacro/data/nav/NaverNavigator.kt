package com.wemade.teslamacro.data.nav

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.wemade.teslamacro.domain.macro.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * 네이버 지도 길안내를 시작한다.
 *
 * 주소 → 좌표는 안드로이드 내장 지오코더로 푼다 — 네이버 API 키가 필요 없다.
 * 백그라운드에서 다른 앱(지도)을 띄우려면 "다른 앱 위에 표시" 권한이 필수다 (안드로이드 제약).
 */
/** 시스템이 실제 보이는 오버레이로 인식할 때까지 기다리는 시간 */
private const val WINDOW_ATTACH_MILLIS = 500L

/** 화면 전환 판정이 끝날 때까지 실행 창을 유지하는 시간 */
private const val WINDOW_KEEP_MILLIS = 1_000L

/** 실행 요청 뒤 홈 전환까지의 고정 대기 — 안심운전 시작 완료를 확인하는 시간은 아니다. */
private const val HOME_RETURN_DELAY_MILLIS = 3_000L

/** 진단 때 같은 내비 URI를 전달하는 서로 다른 Android 실행 통로 */
internal enum class BackgroundLaunchMethod(val logLabel: String) {
    PENDING_INTENT("PendingIntent"),
    DIRECT_ACTIVITY("직접 startActivity"),
}

/** 안심운전 실행 통로. 전체 진단 뒤에는 각 통로를 단독으로 재현해 원인을 가린다. */
enum class SafeDriveLaunchMode(val settingValue: String, val label: String) {
    DEFAULT("DEFAULT", "기본"),
    ALL("ALL", "전체 진단"),
    DIRECT_ACTIVITY("DIRECT_ACTIVITY", "직접 실행");

    companion object {
        fun of(value: String?): SafeDriveLaunchMode =
            entries.firstOrNull { it.settingValue == value } ?: DEFAULT
    }
}

/** 실제 탑승 자동 실행은 한 통로만 써서 사용자가 끈 안심운전을 다시 열지 않는다. */
internal fun SafeDriveLaunchMode.forAutomaticStart(): SafeDriveLaunchMode =
    if (this == SafeDriveLaunchMode.ALL) SafeDriveLaunchMode.DEFAULT else this

/** Android 버전과 고른 진단 단계에 맞는 실행 통로. 전체 진단은 14+에서 둘을 모두 기록한다. */
internal fun backgroundLaunchMethods(
    sdkInt: Int,
    mode: SafeDriveLaunchMode,
): List<BackgroundLaunchMethod> {
    val normal = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        BackgroundLaunchMethod.PENDING_INTENT
    } else {
        BackgroundLaunchMethod.DIRECT_ACTIVITY
    }
    return when (mode) {
        SafeDriveLaunchMode.DEFAULT -> listOf(normal)
        SafeDriveLaunchMode.ALL -> if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(BackgroundLaunchMethod.PENDING_INTENT, BackgroundLaunchMethod.DIRECT_ACTIVITY)
        } else {
            listOf(normal)
        }

        SafeDriveLaunchMode.DIRECT_ACTIVITY -> listOf(BackgroundLaunchMethod.DIRECT_ACTIVITY)
    }
}

/** 주소 → 좌표 캐시 저장소 이름 */
private const val GEOCODE_CACHE = "geocode_cache"

/** 카카오내비가 자체 안전운전 위젯에서도 사용하는 외부 딥링크 진입점 */
private const val KAKAO_DEEP_LINK_ACTIVITY = "com.locnall.KimGiSa.Engine.SMS.CremoteActivity"

/** 진단 실행 사이에 네이버 지도가 전면으로 올 시간을 남긴다. */
private const val DIAGNOSTIC_ATTEMPT_GAP_MILLIS = 2_000L

class NaverNavigator(private val context: Context) {

    // 전체 진단 중 자동 탑승 요청이 끼어들면 성공 통로를 구분할 수 없다.
    private val safeDriveLaunchMutex = Mutex()

    /** 권한이 이미 있는가. 편집 화면이 안내 문구를 띄울지 판단할 때 쓴다 */
    val hasOverlayPermission: Boolean get() = Settings.canDrawOverlays(context)

    // 1. 권한 확인 → 2. 주소를 좌표로 → 3. 고른 내비 앱에 길안내 인텐트
    suspend fun navigate(
        name: String,
        address: String,
        app: NavigatorApp = NavigatorApp.Default,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasOverlayPermission) {
                error("'다른 앱 위에 표시' 권한이 없어요.\n매크로 편집에서 허용해 주세요")
            }
            if (address.isBlank()) error("주소가 비어 있어요")

            // 좌표는 캐시를 먼저 본다. 지오코딩은 인터넷을 타서 실측 400~500ms가 걸리고,
            // 지하주차장처럼 망이 없으면 아예 실패한다 — 탑승 순간에 둘 다 치명적이다.
            // 캐시 키가 주소 문자열이라 주소를 고치면 자동으로 다시 푼다
            //   (이사·오타 수정이 먹어야 한다는 원래 의도 유지)
            val point = cachedPoint(address)
                ?: geocode(address)?.also { cachePoint(address, it) }
                ?: error("주소를 좌표로 못 바꿨어요: $address")
            val label = name.ifBlank { address }

            val installed = installedPackage(app)
                ?: error("${app.label} 앱이 설치되어 있지 않아요")
            launchAny(app, installed, point.latitude, point.longitude, label)
            // startActivity는 화면이 안 떠도 예외를 안 던진다 — 실제로 증명한 것은 요청까지다
            com.wemade.teslable.DiagLog.add("${app.label} 안내 실행 요청 → $label")
        }.recoverCatching { throwable ->
            // 취소는 실패가 아니다. runCatching이 삼키면 매크로 중단이 "안내 실패"로
            // 잘못 기록되고, 취소가 상위로 전파되지 않는다
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            throw throwable
        }.map { }
    }

    // 1. 권한·설치 확인 → 2. 앱별 안심운전 인텐트 생성 → 3. 화면 전환
    suspend fun startSafeDrive(
        app: NavigatorApp,
        launchMode: SafeDriveLaunchMode = SafeDriveLaunchMode.DEFAULT,
        returnHomeAfterStart: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        safeDriveLaunchMutex.withLock {
            runCatching {
                logSafeDriveState("실행 시작", app, launchMode)
                if (!hasOverlayPermission) {
                    error("'다른 앱 위에 표시' 권한이 없어요.\n설정 → 주행에서 허용해 주세요")
                }
                val packageName = installedPackage(app)
                    ?: error("${app.label} 앱이 설치되어 있지 않아요")
                val uri = app.safeDriveUri(context.packageName)
                    ?: error("${app.label}는 안심운전 자동 실행을 지원하지 않아요")

                val intents = safeDriveIntents(app, packageName, uri)
                if (launchMode == SafeDriveLaunchMode.ALL) {
                    launchSafeDriveForDiagnostics(app.label, intents)
                } else {
                    launchFirst(
                        app.label,
                        intents,
                        backgroundLaunchMethods(Build.VERSION.SDK_INT, launchMode).single(),
                        returnHomeAfterStart,
                    )
                }
                com.wemade.teslable.DiagLog.add("${app.label} 안심운전 실행 요청")
            }.recoverCatching { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                throw throwable
            }.map { }
        }
    }

    /** 예약과 실제 전달 때 같은 상태를 기록해야 잠금 조건별 성공 통로를 비교할 수 있다. */
    fun logSafeDriveState(stage: String, app: NavigatorApp, launchMode: SafeDriveLaunchMode) {
        com.wemade.teslable.DiagLog.add(
            "${app.label} 안심운전 $stage — 방식=${launchMode.label} · " +
                launchEnvironment(installedPackage(app)),
        )
    }

    /** 상태 조회 실패가 지도 실행을 막지 않도록 진단 정보만 안전하게 수집한다. */
    private fun launchEnvironment(packageName: String?): String = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val version = packageName?.let { context.packageManager.getPackageInfo(it, 0) }
        "Android=${Build.VERSION.SDK_INT} · 화면켜짐=${power?.isInteractive} · " +
            "키가드잠금=${keyguard?.isKeyguardLocked} · 기기잠금=${keyguard?.isDeviceLocked} · " +
            "오버레이권한=$hasOverlayPermission · 대상=${packageName ?: "미설치"} · " +
            "버전=${version?.versionName ?: "미확인"}"
    }.getOrElse { "실행 상태 조회 실패=${it.message}" }

    /** 설치된 패키지 중 첫 번째. 티맵처럼 패키지가 둘인 앱이 있다 */
    private fun installedPackage(app: NavigatorApp): String? = app.packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * URI 후보를 순서대로 던진다.
     *
     * 같은 앱도 버전에 따라 받는 스킴이 갈린다 — 하나만 박아두면 앱이 조용히 안 뜨고
     * 로그에는 성공으로 남는다. 대상 패키지를 지정해 다른 앱이 가로채는 것도 막는다.
     */
    private suspend fun launchAny(
        app: NavigatorApp,
        packageName: String,
        latitude: Double,
        longitude: Double,
        label: String,
    ) {
        val candidates = app.uris(latitude, longitude, label, context.packageName).map { uri ->
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchFirst(
            app.label,
            candidates,
            backgroundLaunchMethods(Build.VERSION.SDK_INT, SafeDriveLaunchMode.DEFAULT).single(),
        )
    }

    /** 앱이 실제로 쓰는 안심운전 진입 형태를 우선하고, 공개 스킴을 대체 경로로 둔다 */
    private fun safeDriveIntents(
        app: NavigatorApp,
        packageName: String,
        uri: Uri,
    ): List<Intent> {
        val schemeIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return when (app) {
            // 카카오내비 안전운전 위젯의 내부 URI는 매니페스트 필터에 없어 명시 진입점이 필요하다
            NavigatorApp.KAKAO -> listOf(
                Intent(Intent.ACTION_VIEW, uri)
                    .setComponent(ComponentName(packageName, KAKAO_DEEP_LINK_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                schemeIntent,
            )

            // 티맵 자체 블루투스 자동 실행도 런처 인텐트의 url extra로 tmap://navi를 넘긴다
            NavigatorApp.TMAP -> listOfNotNull(
                context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    putExtra("url", uri.toString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                schemeIntent,
            )

            NavigatorApp.NAVER -> listOf(schemeIntent)
            NavigatorApp.GOOGLE -> emptyList()
        }
    }

    /** 준비된 인텐트 후보를 순서대로 같은 실행 통로에 전달한다. */
    private suspend fun launchFirst(
        appLabel: String,
        candidates: List<Intent>,
        method: BackgroundLaunchMethod,
        returnHomeAfterStart: Boolean = false,
    ) {
        var lastFailure: Throwable? = null
        candidates.forEach { intent ->
            val handled = runCatching {
                launchFromBackground(intent, appLabel, method, returnHomeAfterStart)
            }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    lastFailure = it
                }
                .isSuccess
            if (handled) return
        }
        throw lastFailure ?: IllegalStateException("$appLabel 앱을 열 수 없어요")
    }

    /**
     * 1. 실행 통로마다 같은 URI 전달 → 2. 시도 순서 기록 → 3. 다음 탑승에서 단독 재현할 근거 제공.
     *
     * 화면 표시 콜백은 외부 앱이 주지 않는다. 그래서 전달 성공을 화면 성공으로 부르지 않고,
     * 진단 로그의 시각·순서와 실제 화면 관찰을 함께 보게 한다.
     */
    private suspend fun launchSafeDriveForDiagnostics(appLabel: String, candidates: List<Intent>) {
        val traceId = System.currentTimeMillis().toString(36)
        val methods = backgroundLaunchMethods(Build.VERSION.SDK_INT, SafeDriveLaunchMode.ALL)
        var delivered = false
        var lastFailure: Throwable? = null

        com.wemade.teslable.DiagLog.add(
            "$appLabel 안심운전 진단 [$traceId] 시작 — ${methods.joinToString { it.logLabel }}"
        )
        methods.forEachIndexed { index, method ->
            val attempt = "${index + 1}/${methods.size} ${method.logLabel}"
            com.wemade.teslable.DiagLog.add("$appLabel 안심운전 진단 [$traceId] $attempt 시도")
            runCatching { launchFirst(appLabel, candidates, method) }
                .onSuccess {
                    delivered = true
                    com.wemade.teslable.DiagLog.add(
                        "$appLabel 안심운전 진단 [$traceId] $attempt 전달 완료 — 화면 표시는 별도 확인"
                    )
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    lastFailure = error
                    com.wemade.teslable.DiagLog.add(
                        "$appLabel 안심운전 진단 [$traceId] $attempt 실패 — ${error.message}"
                    )
                }
            if (index < methods.lastIndex) delay(DIAGNOSTIC_ATTEMPT_GAP_MILLIS)
        }
        if (!delivered) throw lastFailure ?: IllegalStateException("$appLabel 앱을 열 수 없어요")
        com.wemade.teslable.DiagLog.add("$appLabel 안심운전 진단 [$traceId] 통로별 시도 종료 — 실제 시작은 화면·음성으로 확인")
    }

    /**
     * 배경에서 다른 앱 화면을 띄운다.
     *
     * 안드로이드 14부터 "다른 앱 위에 표시" 권한을 **가지고 있는 것만으로는** 부족하다.
     * 실제로 떠 있는 창이 있어야 배경 실행 예외가 열린다 —
     * 권한만 믿고 startActivity를 부르면 예외도 안 나고 그냥 무시된다(로그엔 성공으로 남는다).
     *
     * 1×1 투명 창은 addView가 성공해도 배경 화면 전환 예외를 안정적으로 열지 못했다(0.9.5 실차).
     * 그래서 작은 안내 창을 실제로 보여 주고, 시스템 판정이 끝날 때까지 유지한다.
     * 창 조작은 메인 스레드에서만 한다.
     *
     * 실차 증거(0.8.31): 앱을 최근에 켠 뒤에는 떴고, 배경에 55분 있었을 땐 안 떴다 —
     * 포그라운드 유예 시간에만 통하고 있었다는 뜻이다.
     */
    private suspend fun launchFromBackground(
        intent: Intent,
        appLabel: String,
        method: BackgroundLaunchMethod,
        returnHomeAfterStart: Boolean = false,
    ) =
        withContext(Dispatchers.Main) {
            val manager = context.getSystemService(WindowManager::class.java)
            val anchor = createLaunchAnchor(appLabel)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (24 * context.resources.displayMetrics.density).toInt()
            }
            val attached = runCatching { manager.addView(anchor, params) }.isSuccess
            if (!attached) {
                error("지도 실행 창을 올리지 못했어요")
            }
            try {
                delay(WINDOW_ATTACH_MILLIS)
                if (!anchor.isAttachedToWindow || !anchor.isShown) {
                    error("지도 실행 창이 화면에 붙지 않았어요")
                }
                launchExternalActivity(intent, appLabel, method)
                delay(
                    if (returnHomeAfterStart) HOME_RETURN_DELAY_MILLIS
                    else WINDOW_KEEP_MILLIS
                )
                if (returnHomeAfterStart) requestHomeScreen()
            } finally {
                runCatching { manager.removeView(anchor) }
            }
        }

    /** 지도 실행 요청과 분리해 홈 화면 전환은 실패해도 진단만 남긴다. */
    private fun requestHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            launchExternalActivity(
                homeIntent,
                "홈 화면",
                BackgroundLaunchMethod.DIRECT_ACTIVITY,
            )
        }.onSuccess {
            com.wemade.teslable.DiagLog.add(
                "안심운전 후 홈 화면 전환 요청 — 화면 전환·안심운전 지속 여부는 별도 확인"
            )
        }.onFailure { error ->
            com.wemade.teslable.DiagLog.add("안심운전 후 홈 화면 전환 실패 — ${error.message}")
        }
    }

    /** Android 14+가 요구하는 백그라운드 Activity 시작 허용을 명시해 외부 내비에 전달한다. */
    private fun launchExternalActivity(
        intent: Intent,
        appLabel: String,
        method: BackgroundLaunchMethod,
    ) {
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?: error("$appLabel 앱이 안전운전 주소를 받지 않아요")
        val target = resolved.activityInfo?.name ?: "알 수 없는 화면"
        com.wemade.teslable.DiagLog.add(
            "$appLabel 전달 직전 — 방식=${method.logLabel} · " +
                launchEnvironment(resolved.activityInfo?.packageName),
        )

        if (method == BackgroundLaunchMethod.DIRECT_ACTIVITY) {
            context.startActivity(intent)
            com.wemade.teslable.DiagLog.add("$appLabel 시스템 전달 완료 — $target · ${method.logLabel}")
            return
        }

        // sender 권한은 PendingIntent 생성 때 넣으면 Android 14+가 예외로 거부한다.
        val senderOptions = ActivityOptions.makeBasic().apply {
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
        val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
        } else null
        val pendingIntent = PendingIntent.getActivity(
            context,
            intent.dataString?.hashCode() ?: intent.hashCode(),
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions?.toBundle(),
        )
        pendingIntent.send(context, 0, null, null, null, null, senderOptions.toBundle())
        // 시스템이 인텐트를 받았다는 뜻일 뿐 화면 표시 성공으로 과장하지 않는다
        com.wemade.teslable.DiagLog.add("$appLabel 시스템 전달 완료 — $target · ${method.logLabel}")
    }

    /** 백그라운드 화면 전환 예외를 열기 위한 실제 보이는 일회성 창 */
    private fun createLaunchAnchor(appLabel: String): TextView {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return TextView(context).apply {
            text = "$appLabel 여는 중"
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#1A1A17"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2F0E9"))
                cornerRadius = 0f
                setStroke(maxOf(1, dp(1)), Color.parseColor("#1A1A17"))
            }
        }
    }

    // 주소 → 좌표 캐시. 목적지가 한둘이라 SharedPreferences 하나로 충분하다
    private val geocodeCache
        get() = context.getSharedPreferences(GEOCODE_CACHE, Context.MODE_PRIVATE)

    /** 캐시에 있으면 인터넷을 안 탄다 */
    private fun cachedPoint(address: String): android.location.Address? {
        val raw = geocodeCache.getString(address, null) ?: return null
        val parts = raw.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return android.location.Address(Locale.KOREA).apply {
            latitude = lat
            longitude = lng
        }
    }

    private fun cachePoint(address: String, resolved: android.location.Address) {
        geocodeCache.edit()
            .putString(address, "${resolved.latitude},${resolved.longitude}")
            .apply()
    }

    /** 주소를 좌표로. "출발지 근처" 조건이 주소 입력으로 위치를 찍을 때도 쓴다 */
    suspend fun geocodePoint(address: String): GeoPoint? = withContext(Dispatchers.IO) {
        geocode(address)?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** 좌표를 사람이 읽는 주소로. 저장한 출발지가 어디인지 확인시켜줄 때 쓴다 */
    suspend fun addressOf(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.KOREA)
                .getFromLocation(point.latitude, point.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }

    // 최신 API(콜백식)는 33+ 전용이라, 모든 버전에서 도는 동기식을 그대로 쓴다
    @Suppress("DEPRECATION")
    private fun geocode(address: String): android.location.Address? = runCatching {
        Geocoder(context, Locale.KOREA).getFromLocationName(address, 1)?.firstOrNull()
    }.getOrNull()
}
