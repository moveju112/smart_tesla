package com.wemade.teslamacro.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 화면에 돌아올 때마다 값을 다시 계산한다.
 *
 * 권한 상태처럼 컴포즈 밖에서 바뀌는 값용이다 —
 * 한 번만 읽으면 설정 앱에서 허용하고 돌아와도 "권한 없음" 화면이 그대로 남는다.
 */
@Composable
fun <T> rememberOnResume(compute: () -> T): T {
    var value by remember { mutableStateOf(compute()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = compute()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}
