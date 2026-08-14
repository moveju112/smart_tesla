package com.wemade.teslamacro.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 인식기가 올려보내는 사건 */
sealed interface VoiceEvent {
    data object Ready : VoiceEvent
    data class PartialText(val text: String) : VoiceEvent
    data class Heard(val candidates: List<String>) : VoiceEvent
    data class Failed(val reason: String, val retryable: Boolean) : VoiceEvent
    data object Ended : VoiceEvent
}

/**
 * 안드로이드 음성 인식 래퍼.
 *
 * 상시 대기(웨이크워드)는 쓰지 않는다 — 배터리를 먹고, 차 안 대화를 계속 듣는 건
 * 원하는 동작이 아니다. 버튼을 누른 동안만 듣는다.
 *
 * 가능하면 **오프라인 인식**을 요청한다. 지하주차장에서 망이 없어도 동작해야 한다.
 */
class VoiceRecognizer(private val context: Context) {

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** 한 번 듣고 결과를 흘린다. 수집을 멈추면 인식도 멈춘다 */
    fun listenOnce(): Flow<VoiceEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(VoiceEvent.Failed("이 기기에서 음성 인식을 쓸 수 없어요", retryable = false))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(VoiceEvent.Ready)
            }

            override fun onPartialResults(partialResults: Bundle) {
                // 말하는 도중 화면에 글자를 띄워주면 "듣고 있다"가 즉시 전달된다
                partialResults.texts().firstOrNull()
                    ?.let { trySend(VoiceEvent.PartialText(it)) }
            }

            override fun onResults(results: Bundle) {
                trySend(VoiceEvent.Heard(results.texts()))
                trySend(VoiceEvent.Ended)
                close()
            }

            override fun onError(error: Int) {
                trySend(VoiceEvent.Failed(errorMessage(error), retryable = isRetryable(error)))
                trySend(VoiceEvent.Ended)
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(buildIntent())

        awaitClose {
            runCatching {
                recognizer.stopListening()
                recognizer.destroy()
            }
        }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // 후보를 여러 개 받아 파서가 순서대로 시도한다
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // 오프라인을 강제하지 않는다 — 차에 인터넷이 있어 온라인 인식의 정확도를 쓴다.
        // 망이 없으면 기기가 알아서 오프라인 팩으로 내려간다 (없는 기기는 에러 → vosk 단독)
    }

    private fun Bundle.texts(): List<String> =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "못 알아들었어요.\n다시 말해 주세요"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "아무 말도 안 들렸어요"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없어요"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "네트워크 인식에 실패했어요.\n오프라인 한국어 인식을 설치해 주세요"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 바빠요.\n잠시 후 다시 시도해 주세요"
        else -> "음성 인식 실패 (code=$error)"
    }

    /** 다시 눌러보면 되는 오류인지. 권한 문제는 재시도해도 소용없다 */
    private fun isRetryable(error: Int): Boolean =
        error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
}
