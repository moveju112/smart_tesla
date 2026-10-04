package com.wemade.teslamacro.feature.features

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.wemade.teslamacro.data.settings.AppSettings
import com.wemade.teslamacro.data.settings.DeviceMode
import com.wemade.teslamacro.feature.settings.NavigationControls
import com.wemade.teslamacro.feature.settings.SafeDrivePanel
import com.wemade.teslamacro.feature.settings.SmartThingsControls
import com.wemade.teslamacro.feature.settings.SmartThingsPanel
import com.wemade.teslamacro.feature.settings.StealthChargePanel
import com.wemade.teslamacro.ui.component.HelpTitle
import com.wemade.teslamacro.ui.component.SettingRow
import com.wemade.teslamacro.ui.component.ButtonTone
import com.wemade.teslamacro.ui.component.DraftMark
import com.wemade.teslamacro.ui.component.TButton
import com.wemade.teslamacro.ui.component.TCard
import com.wemade.teslamacro.ui.theme.Space
import com.wemade.teslamacro.ui.theme.Radius
import com.wemade.teslamacro.ui.theme.T

/** 실행 화면과 설정의 연결을 한 곳에서 정의한다. */
enum class AppFeature(val label: String, val description: String, val summary: String) {
    MACROS("매크로", "차량 동작을 만들고 자동으로 실행해요", "조건 · 동작"),
    DESTINATION("목적지 전송", "주소나 검색어를 차량 태블릿으로 보내요", "검색어 보내기"),
    SAFE_DRIVE("단속 안내", "주행 중 단속 카메라와 과속을 알려줘요", "카메라 · 과속"),
    STEALTH_CHARGE("스텔스 충전", "한 번의 충전 동안 전류를 자동으로 조절해요", "전류 자동 조절"),
    SMARTTHINGS("스마트싱스", "스마트싱스 알림으로 차량 명령을 실행해요", "알림으로 명령"),
}

/** 부족한 준비만 설정으로 연결하고 기본값으로 사용 가능한 기능은 막지 않는다. */
internal fun requiredFeatureSettings(
    feature: AppFeature,
    settings: AppSettings,
    locationPermitted: Boolean,
    activityPermitted: Boolean,
    notificationAccessGranted: Boolean,
): FeatureSettings? = when {
    feature in listOf(AppFeature.STEALTH_CHARGE, AppFeature.SMARTTHINGS) && !settings.isReady -> FeatureSettings.VEHICLE
    feature == AppFeature.SAFE_DRIVE && (!locationPermitted ||
        (settings.safeDriveSound && settings.deviceMode == DeviceMode.MOUNTED && !activityPermitted)) -> FeatureSettings.SAFE_DRIVE
    feature == AppFeature.SMARTTHINGS && (!notificationAccessGranted ||
        settings.smartThingsCommandTexts.values.none { it.isNotBlank() }) -> FeatureSettings.SMARTTHINGS
    else -> null
}

/** 기능에서 넘어온 설정은 필요한 항목에 바로 진입한다. */
enum class FeatureSettings(val label: String) {
    VEHICLE("차량"), DESTINATION("목적지 전송"), SAFE_DRIVE("단속 안내"),
    STEALTH_CHARGE("스텔스 충전"), SMARTTHINGS("스마트싱스"),
}

/** 기능 목록과 실행 화면을 구분하고 세부 옵션은 설정 탭으로 연결한다. */
@Composable
fun FeaturesScreen(
    settings: AppSettings,
    selected: AppFeature?,
    navigation: NavigationControls,
    smartThings: SmartThingsControls,
    onSelect: (AppFeature?) -> Unit,
    onSettings: (FeatureSettings) -> Unit,
    onStealthChange: (Boolean) -> Unit,
    stealthSecondsUntilNextChange: Int? = null,
    macroContent: @Composable () -> Unit = {},
    destinationContent: @Composable () -> Unit = {},
) {
    BackHandler(enabled = selected != null) { onSelect(null) }
    if (selected == AppFeature.DESTINATION) {
        destinationContent()
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (selected != null) {
            TButton("기능 목록", ButtonTone.Ghost, icon = DraftMark.ArrowLeft, fillWidth = false,
                modifier = Modifier.padding(horizontal = Space.md), onClick = { onSelect(null) })
        }
        if (selected == AppFeature.MACROS) {
            macroContent()
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs)) {
            if (selected == null) {
                Text("기능", style = MaterialTheme.typography.headlineSmall, color = T.Ink)
            } else {
                HelpTitle(selected.label, selected.description, style = MaterialTheme.typography.headlineSmall)
            }
            if (selected == null) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    AppFeature.entries.forEach { feature ->
                        if (feature != AppFeature.SAFE_DRIVE || navigation.safeDriveAvailable) {
                            val required = requiredFeatureSettings(feature, settings, navigation.locationPermitted,
                                navigation.activityPermitted, smartThings.notificationAccessGranted)
                            val status = when {
                                required != null -> "${required.label} 설정 필요"
                                feature == AppFeature.SAFE_DRIVE -> if (settings.safeDrive) "켜짐" else "꺼짐"
                                feature == AppFeature.STEALTH_CHARGE -> if (settings.stealthCharging) "켜짐" else "꺼짐"
                                feature == AppFeature.SMARTTHINGS -> if (settings.smartThingsEnabled) "켜짐" else "꺼짐"
                                else -> null
                            }
                            FeatureRow(feature, status, onClick = {
                                onSelect(feature)
                                if (required != null) onSettings(required)
                            })
                        }
                    }
                }
            } else {
                // 권한이 실행 화면에서 회수돼도 재실행 전에 같은 준비 경로를 거친다. 끄기는 항상 허용한다.
                val required = requiredFeatureSettings(selected, settings, navigation.locationPermitted,
                    navigation.activityPermitted, smartThings.notificationAccessGranted)
                TCard {
                    when (selected) {
                        AppFeature.SAFE_DRIVE -> SafeDrivePanel(settings, navigation.copy(onSafeDriveChange = { enabled ->
                            if (enabled && required != null) onSettings(required) else navigation.onSafeDriveChange(enabled)
                        }), executionOnly = true)
                        AppFeature.SMARTTHINGS -> SmartThingsPanel(settings, smartThings.copy(onEnabledChange = { enabled ->
                            if (enabled && required != null) onSettings(required) else smartThings.onEnabledChange(enabled)
                        }), executionOnly = true)
                        AppFeature.STEALTH_CHARGE -> StealthChargePanel(settings, stealthSecondsUntilNextChange,
                            onEnabledChange = { enabled ->
                                if (enabled && required != null) onSettings(required) else onStealthChange(enabled)
                            }, executionOnly = true)
                        else -> Unit
                    }
                }
                TCard {
                    SettingRow("설정", onClick = { onSettings(when (selected) {
                        AppFeature.SAFE_DRIVE -> FeatureSettings.SAFE_DRIVE
                        AppFeature.STEALTH_CHARGE -> FeatureSettings.STEALTH_CHARGE
                        else -> FeatureSettings.SMARTTHINGS
                    }) })
                }
                Spacer(Modifier.height(Space.sm))
            }
        }
    }
}

/** 아이콘·이름·짧은 요약을 한 행에 두고, 좁은 큰 글씨에서만 설명을 이름 아래로 옮긴다. */
@Composable
private fun FeatureRow(feature: AppFeature, status: String?, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < Space.xxl * 6 ||
            (maxWidth < Space.xxl * 10 && LocalDensity.current.fontScale >= 1.3f)
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(Radius.button))
                .clickable(role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = Space.xxl + Space.md)
                .padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(Space.xl + Space.sm)
                    .background(T.Carbon, RoundedCornerShape(Radius.button)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (feature) {
                        AppFeature.MACROS -> DraftMark.Automation
                        AppFeature.DESTINATION -> DraftMark.Location
                        AppFeature.SAFE_DRIVE -> DraftMark.Speed
                        AppFeature.STEALTH_CHARGE -> DraftMark.Charge
                        AppFeature.SMARTTHINGS -> DraftMark.Notifications
                    },
                    contentDescription = null, tint = T.Electric, modifier = Modifier.size(Space.lg),
                )
            }
            Spacer(Modifier.width(Space.md))
            if (stacked) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(feature.label, style = MaterialTheme.typography.titleMedium, color = T.Ink)
                    Text(feature.summary, style = MaterialTheme.typography.bodySmall, color = T.InkMuted)
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = T.InkMuted) }
                }
            } else {
                Text(feature.label, style = MaterialTheme.typography.titleMedium, color = T.Ink,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(feature.summary, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                        textAlign = TextAlign.End)
                    status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = T.InkMuted,
                            textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}
