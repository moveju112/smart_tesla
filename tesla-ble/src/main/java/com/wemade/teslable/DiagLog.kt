package com.wemade.teslable

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 사용자가 복사해서 전달할 수 있는 진단 로그.
 *
 * 실차 문제는 개발자가 현장에 없을 때 터진다.
 * 화면에 로그를 그대로 보여주고 복사 버튼을 달아,
 * 사용자가 붙여넣기 한 번으로 상황을 통째로 전달하게 한다.
 */
object DiagLog {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        // 최근 것만 남긴다. 오래 켜둬도 메모리를 먹지 않게
        _lines.update { (it + "$time $message").takeLast(MAX_LINES) }
        // 화면 버퍼에만 있으면 개발 중에 adb로 볼 길이 없다. 같은 줄을 logcat에도 흘린다 —
        // 태그 하나로 필터되고, 실기기에서도 붙여서 볼 수 있다
        android.util.Log.i(TAG, message)
    }

    /** 복사용 전체 덤프 */
    fun dump(): String = _lines.value.joinToString("\n")

    fun clear() {
        _lines.value = emptyList()
    }

    private const val MAX_LINES = 300

    /** logcat 필터용 태그. `adb logcat -s SmartTesla` 하나면 앱의 진단 로그만 본다 */
    private const val TAG = "SmartTesla"
}
