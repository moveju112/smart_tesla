package com.wemade.teslamacro.data.settings

/** 위젯은 앱 실행 없이도 같은 색을 유지하도록 고정 테마만 제공한다. */
enum class DestinationWidgetTheme(val label: String) {
    LIGHT("밝게"), DARK("어둡게"), BLUE("블루")
}

/** 투명한 배경에서는 배경화면에 맞는 글자색을 직접 고를 수 있다. */
enum class DestinationWidgetText(val label: String) {
    AUTO("테마에 맞게"), LIGHT("밝은색"), DARK("어두운색")
}

/** 기존 위젯은 밝은 불투명 배경을 유지하고 기기의 모든 목적지 위젯에 함께 적용한다. */
data class DestinationWidgetAppearance(
    val theme: DestinationWidgetTheme = DestinationWidgetTheme.LIGHT,
    val transparency: Int = 0,
    val text: DestinationWidgetText = DestinationWidgetText.AUTO,
    val showTitle: Boolean = true,
)
