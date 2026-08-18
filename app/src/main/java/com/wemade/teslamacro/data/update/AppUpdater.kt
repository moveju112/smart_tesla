package com.wemade.teslamacro.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.wemade.teslable.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 업데이트 확인·설치가 어디까지 갔는지 */
sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** 무엇이 왜 안 됐는지 그대로 보여준다 */
    data class Failed(val message: String) : UpdateState

    /** "알 수 없는 앱 설치"가 꺼져 있다. 설정에서 켜야 넘어간다 */
    data object NeedsInstallPermission : UpdateState

    /** 새 버전이 있다. [apkUrl]이 null이면 릴리스에 APK가 안 붙은 것 */
    data class Available(val version: String, val apkUrl: String?) : UpdateState

    /** 내려받는 중. [percent]는 0~100 */
    data class Downloading(val version: String, val percent: Int) : UpdateState

    /** 설치를 시스템에 넘겼다. 확인 화면이 뜰 수도, 조용히 끝날 수도 있다 */
    data class Installing(val version: String) : UpdateState
}

/**
 * GitHub 릴리스에서 새 버전을 가져와 스스로 갈아끼운다.
 *
 * 안드로이드는 앱이 저 혼자 설치되는 것을 막아 둔다. 다만 "이미 이 앱이 설치한 앱"을
 * 같은 서명으로 다시 설치하는 경우는 확인 화면을 건너뛸 수 있다(안드로이드 12+).
 * 그래서 처음 한 번만 확인 화면이 뜨고, 그 뒤로는 조용히 끝난다.
 *
 * 설치 결과를 [InstallResultReceiver]가 받아 상태를 갱신하므로, ViewModel이 아니라
 * 싱글턴으로 둔다 — 리시버는 ViewModel 인스턴스에 닿을 수 없다.
 */
object AppUpdater {

    private const val OWNER_REPO = "moveju112/smart_tesla"
    private const val RELEASE_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"

    /** null이면 아직 확인해 본 적이 없다는 뜻 */
    val state = MutableStateFlow<UpdateState?>(null)

    /** 설치 확인 화면을 취소하고 돌아왔을 때 "설치" 버튼을 되살릴 대상 */
    private var lastAvailable: UpdateState.Available? = null

    /** 최신 릴리스를 조회해 지금 버전과 견준다 */
    suspend fun check(currentVersion: String) {
        state.value = UpdateState.Checking
        state.value = withContext(Dispatchers.IO) {
            runCatching {
                // 응답이 안 오면 "확인 중"에 영원히 매달린다 — 연결·읽기 5초씩에 끊는다
                val connection = URL(RELEASE_API).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = Json.parseToJsonElement(body) as JsonObject

                val latest = (json["tag_name"] as? JsonPrimitive)?.content.orEmpty().removePrefix("v")
                val apkUrl = (json["assets"] as? JsonArray)?.firstNotNullOfOrNull { asset ->
                    ((asset as JsonObject)["browser_download_url"] as? JsonPrimitive)
                        ?.content?.takeIf { it.endsWith(".apk") }
                }

                // "다르면 새 버전"이 아니라 실제로 높은지 본다.
                // 릴리스보다 앞선 로컬 빌드에서 옛 APK를 새 버전이라고 안내하는 사고를 막는다
                if (isNewer(latest, currentVersion)) UpdateState.Available(latest, apkUrl)
                else UpdateState.UpToDate
            }.getOrElse { UpdateState.Failed("새 버전을 확인하지 못했어요.\n인터넷 연결을 봐주세요.") }
        }
        lastAvailable = state.value as? UpdateState.Available
    }

    /** 설치 확인 화면에서 취소한 뒤 다시 누를 수 있게 되돌린다. 리시버가 부른다 */
    internal fun restoreAvailable() {
        state.value = lastAvailable
    }

    /** 받아둔 APK */
    private fun pendingApk(context: Context): File? =
        File(context.cacheDir, "update.apk").takeIf { it.length() > 0 }

    /**
     * 원클릭 업데이트: APK를 내려받아 곧바로 시스템 설치기에 넘긴다.
     * 확인 화면이 뜰 수 있으므로 앱이 화면에 있을 때만 부른다.
     */
    suspend fun downloadAndInstall(context: Context) {
        val target = state.value as? UpdateState.Available ?: return
        val apkUrl = target.apkUrl
        if (apkUrl == null) {
            state.value = UpdateState.Failed("릴리스에 APK가 없어요.")
            return
        }
        // 권한이 없으면 시스템이 설치를 아예 시작하지 않는다.
        // 수십 MB를 받은 뒤에 막히지 않도록 내려받기 전에 먼저 본다
        if (!canInstallPackages(context)) {
            state.value = UpdateState.NeedsInstallPermission
            return
        }

        val apk = download(context, target.version, apkUrl)
        if (apk == null) {
            state.value = UpdateState.Failed("새 버전을 내려받지 못했어요.\n인터넷 연결을 봐주세요.")
            DiagLog.add("업데이트 · 내려받지 못함")
            return
        }

        state.value = UpdateState.Installing(target.version)
        withContext(Dispatchers.IO) { runCatching { handToInstaller(context, apk) } }
            .onFailure {
                state.value = UpdateState.Failed("설치를 시작하지 못했어요 · ${it.message ?: it::class.simpleName}")
                DiagLog.add("업데이트 설치 시작 실패 · ${it.message}")
            }
    }

    /** APK를 캐시로 내려받는다. 실패하면 받다 만 파일을 지우고 null */
    private suspend fun download(context: Context, version: String, apkUrl: String): File? =
        withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "update.apk")
            runCatching {
                state.value = UpdateState.Downloading(version, 0)
                val connection = URL(apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = true
                val total = connection.contentLengthLong

                var read = 0L
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            // 길이를 모르면(리다이렉트로 -1) 진행률은 0에 머문다
                            if (total > 0) {
                                state.value =
                                    UpdateState.Downloading(version, (read * 100 / total).toInt())
                            }
                        }
                    }
                }
                file
            }.getOrElse {
                // 받다 만 파일을 남기면 다음에 그 반쪽을 설치하려 든다
                file.delete()
                null
            }?.takeIf { it.length() > 0 }
        }

    /** "알 수 없는 앱 설치"가 이 앱에 허용돼 있는지 */
    fun canInstallPackages(context: Context): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 세션에 APK 바이트를 부어 넣고 커밋한다. 결과는 리시버로 돌아온다 */
    private fun handToInstaller(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // 이 앱이 이 앱을 갈아끼우는 것이므로, 조건이 맞으면 확인 화면 없이 끝난다
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                android.app.PendingIntent.FLAG_MUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }

    /** 설치 성공 후 캐시 정리용. 리시버가 부른다 */
    internal fun clearPendingApk(context: Context) {
        pendingApk(context)?.delete()
    }

    /** 점으로 나뉜 숫자 버전 비교. "-beta" 같은 꼬리표는 무시하고 자리별 숫자로 본다 */
    fun isNewer(latest: String, current: String): Boolean {
        if (latest.isBlank()) return false
        val a = latest.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { i ->
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
