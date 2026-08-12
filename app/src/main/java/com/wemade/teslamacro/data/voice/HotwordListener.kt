package com.wemade.teslamacro.data.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * 상시 대기 음성 인식. 기기 안에서만 돌고 인터넷을 쓰지 않는다.
 *
 * 구글 인식기를 반복해서 켜는 방식과 다른 점이 두 가지다.
 * 시작할 때마다 나는 "삑" 소리가 없고, 재시작 사이의 공백도 없다.
 *
 * 낱말 목록을 넘기면 그 낱말만 듣는다. 어휘가 마흔 개뿐이라
 * 잡담을 명령으로 잘못 알아들을 확률이 크게 줄어든다.
 */
class HotwordListener(private val modelStore: VoiceModelStore) {

    val isReady: Boolean get() = modelStore.isInstalled

    /**
     * 멈출 때까지 계속 듣는다. 수집을 그만두면 마이크도 닫힌다.
     * 호출 전에 RECORD_AUDIO 권한을 확보해야 한다.
     */
    fun listen(vocabulary: List<String> = emptyList()): Flow<VoiceEvent> = callbackFlow {
        if (!modelStore.isInstalled) {
            trySend(VoiceEvent.Failed("음성 모델이 설치되지 않았어요", retryable = false))
            close()
            return@callbackFlow
        }

        LibVosk.setLogLevel(LogLevel.WARNINGS)

        val model = runCatching { Model(modelStore.modelDir.absolutePath) }.getOrElse { error ->
            trySend(VoiceEvent.Failed("음성 모델을 열지 못했어요 (${error.message})", retryable = false))
            close()
            return@callbackFlow
        }

        // 낱말 목록으로 만들다 실패하면 자유 인식으로 내려간다.
        // 모델 사전에 없는 낱말이 하나라도 있으면 목록 방식이 통째로 거부된다
        val recognizer = runCatching {
            if (vocabulary.isEmpty()) Recognizer(model, SAMPLE_RATE)
            else Recognizer(model, SAMPLE_RATE, grammarOf(vocabulary))
        }.recoverCatching {
            Recognizer(model, SAMPLE_RATE)
        }.getOrElse { error ->
            model.close()
            trySend(VoiceEvent.Failed("인식기를 만들지 못했어요 (${error.message})", retryable = false))
            close()
            return@callbackFlow
        }

        // 낱말별 신뢰도를 받는다. 환청으로 끼어든 한 글자를 걸러내는 근거가 된다
        runCatching { recognizer.setWords(true) }

        val speech = runCatching { SpeechService(recognizer, SAMPLE_RATE) }.getOrElse { error ->
            recognizer.close()
            model.close()
            trySend(VoiceEvent.Failed("마이크를 열지 못했어요 (${error.message})", retryable = true))
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                textOf(hypothesis, "partial")?.let { trySend(VoiceEvent.PartialText(it)) }
            }

            override fun onResult(hypothesis: String?) {
                // 1순위: 신뢰도 낮은 낱말을 걷어낸 문장. 2순위: 원문 그대로.
                // 파서가 앞에서부터 시도하므로 확실한 해석이 먼저 잡힌다
                val raw = textOf(hypothesis, "text")
                val confident = confidentTextOf(hypothesis)
                val candidates = listOfNotNull(confident, raw).distinct()
                if (candidates.isNotEmpty()) trySend(VoiceEvent.Heard(candidates))
            }

            override fun onFinalResult(hypothesis: String?) = Unit

            override fun onError(exception: Exception?) {
                trySend(VoiceEvent.Failed(exception?.message ?: "음성 인식 오류", retryable = true))
            }

            override fun onTimeout() = Unit
        }

        speech.startListening(listener)
        trySend(VoiceEvent.Ready)

        awaitClose {
            runCatching {
                speech.stop()
                speech.shutdown()
                recognizer.close()
                model.close()
            }
        }
    }.flowOn(Dispatchers.IO)   // 모델 적재는 수백 밀리초가 걸린다. 화면을 붙잡으면 안 된다

    /** Vosk 문법은 낱말 배열이다. `[unk]`가 있어야 목록 밖의 말을 억지로 끼워맞추지 않는다 */
    private fun grammarOf(vocabulary: List<String>): String =
        (vocabulary + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

    /** `{"text":"테슬라 트렁크 열어"}` 에서 알맹이만 꺼낸다 */
    private fun textOf(json: String?, key: String): String? = runCatching {
        Json.parseToJsonElement(json ?: return null)
            .jsonObject[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * 낱말 신뢰도가 기준 미만인 것을 걷어낸 문장.
     * 잡음이 "꺼" 같은 한 글자로 잘못 붙어 엉뚱한 명령이 되는 사고를 줄인다.
     */
    private fun confidentTextOf(json: String?): String? = runCatching {
        Json.parseToJsonElement(json ?: return null)
            .jsonObject["result"]?.jsonArray
            ?.mapNotNull { element ->
                val word = element.jsonObject
                val conf = word["conf"]?.jsonPrimitive?.doubleOrNull ?: 1.0
                word["word"]?.jsonPrimitive?.content?.takeIf { conf >= MIN_WORD_CONF }
            }
            ?.filter { it != "[unk]" }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val SAMPLE_RATE = 16000.0f

        // 이보다 자신 없는 낱말은 명령 판정에 쓰지 않는다 (실측으로 조정한 값 아님, 시작점)
        const val MIN_WORD_CONF = 0.7
    }
}
