package com.wemade.teslamacro.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.T

/** 앱 초기화의 진행 상태와 제품명을 표시한다. */
@Composable
fun AppSplash(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(DraftMark.Automation, contentDescription = null, tint = T.Electric, modifier = Modifier.size(64.dp))

        Spacer(Modifier.height(Space.lg))
        Text(
            text = "SMART TESLA",
            style = MaterialTheme.typography.titleMedium,
            color = T.Ink,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            text = "차량 연결 준비 중",
            style = MaterialTheme.typography.bodySmall,
            color = T.InkFaint,
        )

        Spacer(Modifier.height(Space.xl))
        Box(modifier = Modifier.width(160.dp)) { IndeterminateBar() }
    }
}

/** 준비 실패는 무한 로딩과 구별하고, 작은 화면·큰 글씨에서도 재시도 동작에 닿게 한다. */
@Composable
fun AppStartupFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = "앱을 준비하지 못했어요",
            description = message,
            actionLabel = "다시 시도",
            onAction = onRetry,
        )
    }
}


