package com.wemade.teslamacro.data.voice

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/** 음성 모델 설치 상태 */
sealed interface VoiceModelState {
    data object NotInstalled : VoiceModelState
    data class Installing(val megabytes: Long) : VoiceModelState
    data object Installed : VoiceModelState
    data class Failed(val reason: String) : VoiceModelState
}

/**
 * 오프라인 음성 모델(약 250MB)을 기기에 풀어둔다.
 *
 * APK에 넣지 않는 이유는 두 가지다.
 * 앱이 인터넷을 쓰지 않는다는 약속을 지켜야 하므로 앱이 직접 내려받지 않고,
 * 매번 250MB짜리 APK를 주고받는 것도 현실적이지 않다.
 * 그래서 사용자가 받아둔 zip을 골라주면 그걸 푼다.
 */
class VoiceModelStore(private val context: Context) {

    /** 압축을 푸는 자리. Vosk는 실제 파일 경로가 있어야 모델을 연다 */
    val modelDir: File get() = File(context.filesDir, "vosk-ko")

    /** 압축이 끝까지 풀렸다는 표시. 중간에 끊긴 폴더를 정상으로 오인하면 안 된다 */
    private val marker: File get() = File(modelDir, ".ready")

    val isInstalled: Boolean get() = marker.exists()

    private val _state = MutableStateFlow<VoiceModelState>(
        if (isInstalled) VoiceModelState.Installed else VoiceModelState.NotInstalled
    )
    val state: StateFlow<VoiceModelState> = _state.asStateFlow()

    /** 사용자가 고른 zip을 푼다. 실패하면 반쪽짜리 폴더를 남기지 않고 지운다 */
    suspend fun installFromZip(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. 이전 설치본을 먼저 비운다
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            var written = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: error("파일을 열 수 없어요")

            input.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // 2. zip 안 최상위 폴더 한 겹은 벗겨낸다 (vosk-model-small-ko-0.22/…)
                        val relative = entry.name.substringAfter('/', "")
                        if (relative.isBlank()) continue

                        val target = File(modelDir, relative)
                        // 3. ../ 로 폴더 밖에 쓰려는 zip은 거른다
                        if (!target.canonicalPath.startsWith(modelDir.canonicalPath + File.separator)) {
                            error("압축 파일 경로가 올바르지 않아요")
                        }

                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { out ->
                                written += zip.copyTo(out)
                            }
                            _state.value = VoiceModelState.Installing(written / 1_000_000)
                        }
                        zip.closeEntry()
                    }
                }
            }

            // 4. 모델이 제대로 들어왔는지 핵심 파일로 확인한다
            REQUIRED.forEach { path ->
                if (!File(modelDir, path).exists()) error("음성 모델 파일이 아니에요")
            }
            marker.writeText("ok")
            _state.value = VoiceModelState.Installed
        }.onFailure { error ->
            modelDir.deleteRecursively()
            _state.value = VoiceModelState.Failed(error.message ?: "설치에 실패했어요")
        }
    }

    fun remove() {
        modelDir.deleteRecursively()
        _state.value = VoiceModelState.NotInstalled
    }

    private companion object {
        /** Vosk 모델이라면 반드시 있는 파일들 */
        val REQUIRED = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst")
    }
}
