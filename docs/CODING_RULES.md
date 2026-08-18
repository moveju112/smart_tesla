# CODING RULES

## 주석

- **[MUST]** 주석은 한국어, "무엇"이 아니라 "왜" — 안 한 선택과 전환 조건까지 적는다
  - why: 코드가 못 담는 결정 근거를 남기는 게 이 프로젝트 주석의 존재 이유
  - ✅ `app/src/main/java/com/wemade/teslamacro/di/AppContainer.kt:20` "객체가 스무 개도 안 되는 규모라 DI 프레임워크를 쓰지 않는다. …그때 Hilt로 옮긴다"
  - ✅ `app/src/main/java/com/wemade/teslamacro/di/AppContainer.kt:72` "이걸 안 하면 시뮬레이터가 가짜로 '연결 성공'을 돌려줘 등록이 그냥 통과해버린다"
- **[MUST]** 새 함수 위에는 하는 일을 설명하는 주석(또는 KDoc)을 단다. 흐름 주석은 `// 1. 장비 캐시 조회` 식 단계형
  - why: 전역 사용자 규칙. 긴 문단 주석 금지

## UI / 디자인

- **[NEVER]** 그라데이션·글로우를 쓰지 않는다. 토스/네이버식 미니멀이 확정 방향
  - why: 사용자가 "AI가 짠 것 같다"며 다크+그라데이션 시안을 두 번 리젝함 (0.4.x에서 전면 교체)
  - ❌ 루트 `ARCHITECTURE.md`의 디자인 절(#3E6AE1 등) — 구버전 서술, 코드가 정본
- **다크는 0.8.22부터 허용**. 단 "다크 테마"가 아니라 **시계 기준 낮/밤 자동 전환**이다
  - why: 차내 상주 태블릿이라 밤 운전에 흰 화면이 눈부시다. 시스템 다크가 꺼져 있어도 밤엔 어두워져야 해서 `isSystemInDarkTheme()`이 아니라 시계를 본다 (`Theme.kt` `DAY_START_HOUR`/`DAY_END_HOUR`)
  - 순검정 금지 — 따뜻한 그래파이트(`#121316`). 야간 눈부심·잔상 때문
  - 색은 `T.Xxx`로 꺼내되 **@Composable 안에서만** 된다. 상태 클래스·enum 등 밖에서는 `ColorRole`을 쓴다
- **제어 화면의 색 규칙**: 정상 = 무채색. `TileTone.Cool/Warm` = 차가 실제로 일하는 중, `TileTone.Alert` = 사람이 봐야 함(면 전체가 물듦)
  - why: 색이 곧 "이걸 봐라"라는 신호다. 평소에 색이 깔려 있으면 진짜 경보가 안 보인다
  - 반복 애니메이션은 `BreathingBar` 하나뿐 — 움직임 자체가 "작동 중"의 뜻이라 다른 데 쓰면 의미가 흐려진다
- 스냅샷 테스트는 `TeslaMacroTheme(dark = …)`로 낮/밤을 **못 박는다**. 자동 판정에 맡기면 실행 시각에 따라 결과가 바뀐다
  - ✅ `app/src/main/java/com/wemade/teslamacro/ui/theme/Color.kt:20` `Void = 0xFFF2F4F6` (토스 배경 그레이) + 흰 카드
- **[MUST]** 색·간격·반경·모션은 토큰(`T.*`, `Space`, `Radius`, `Motion`)만 경유한다. raw dp/hex 금지
  - why: 화면마다 값이 갈라지면 리디자인이 전수 수정이 됨
  - ✅ `app/src/main/java/com/wemade/teslamacro/ui/theme/Theme.kt:12` "화면에서 raw dp를 쓰지 말고 여기서 꺼내 쓴다"
- **[MUST]** 화면 폭 분기는 루트에서 1회 잰 `LocalPane`으로만. 화면에서 재측정 금지
  - ✅ `MainActivity.kt:84` `CompositionLocalProvider(LocalPane provides Pane.of(maxWidth))`
- **[PREFER]** 화면 전용 컴포저블은 같은 파일의 `private fun`, 공용은 `app/src/main/java/com/wemade/teslamacro/ui/component/`로. T- 접두사는 Material 대체 프리미티브(TButton, TCard)에만
  - ✅ `app/src/main/java/com/wemade/teslamacro/ui/component/Primitives.kt:70`
- **[MUST]** `animate*AsState`에는 label을 붙인다
  - ✅ `app/src/main/java/com/wemade/teslamacro/ui/component/ClimateControls.kt:104` `label = "levelBackground"`

## ViewModel / 상태

- **[MUST]** 파생 상태는 `combine(...).stateIn(viewModelScope, WhileSubscribed(5_000), initialState())`, 소유 상태는 `MutableStateFlow` + `_uiState.update { it.copy(...) }`
  - ✅ 파생: `app/src/main/java/com/wemade/teslamacro/feature/dashboard/DashboardViewModel.kt:83` / 소유: `app/src/main/java/com/wemade/teslamacro/feature/pairing/PairingViewModel.kt:16`
- **[MUST]** combine 인자가 5개를 넘으면 부수 상태를 private data class로 묶어 중첩 combine한다
  - why: Kotlin combine은 5-arg까지만 타입 안전 오버로드 제공
  - ✅ `app/src/main/java/com/wemade/teslamacro/feature/dashboard/DashboardViewModel.kt:62` `private data class Aux(pending, error, voice, seats)`
- **[PREFER]** UiState data class는 ViewModel이 아니라 Screen 파일 하단에 선언
  - ✅ `app/src/main/java/com/wemade/teslamacro/feature/dashboard/DashboardScreen.kt:499`
- **[MUST]** VM 생성자는 `(private val container: AppContainer)` 하나. 새 VM은 `app/src/main/java/com/wemade/teslamacro/ui/ViewModelFactory.kt`의 when에 등록
  - why: 수동 DI라 등록을 빼먹으면 런타임 error()

## 데이터 / 에러

- **[MUST]** 새 DataStore는 파일 최상단 `private val Context.xxxDataStore by preferencesDataStore("고유이름")`, 키는 companion에 `Key*`(PascalCase)/snake_case 문자열, 노출은 `Flow` + map 안 elvis 기본값
  - ✅ `app/src/main/java/com/wemade/teslamacro/data/settings/SeatStore.kt:17`
- **[MUST]** 실패는 던지지 말고 `runCatching{}.getOrNull()` + DiagLog 기록 후 흐름 계속
  - ✅ `app/src/main/java/com/wemade/teslamacro/data/macro/RuleStore.kt:35`
- **[MUST]** 실차 관련 진단은 `android.util.Log`가 아니라 `DiagLog.add("한국어 서사")` — 사용자가 화면에서 복사해 전달하는 채널이다
  - why: 실차 문제는 개발자가 현장에 없을 때 터진다 (`tesla-ble/src/main/java/com/wemade/teslable/DiagLog.kt:11`)
- **[NEVER]** 프레임워크를 선제 도입하지 않는다 (Hilt/Room/WindowSizeClass 전부 의도적 미채택, 전환 조건은 주석에)
  - ✅ `app/src/main/java/com/wemade/teslamacro/ui/layout/WindowSize.kt:9` "기준이 두 개뿐이라 의존성을 늘릴 이유가 없다"
