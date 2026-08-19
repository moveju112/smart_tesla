package com.wemade.teslamacro.ui.component

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 기기에서 애니메이션을 꺼 뒀는지.
 *
 * 개발자 옵션의 "애니메이션 배율 사용 안함"이나 접근성의 모션 제거를 켜면 0이 된다.
 * 웹의 `prefers-reduced-motion`에 해당하는 안드로이드 신호다.
 *
 * 이 앱은 반복 애니메이션이 "차가 지금 일하는 중"이라는 뜻이라 그냥 지우면 정보가 사라진다.
 * 그래서 움직임만 멈추고 표시는 남긴다 — 끄는 게 아니라 정지 상태로 보여준다.
 */
@Composable
fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
