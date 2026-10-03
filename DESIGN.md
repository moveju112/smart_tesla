---
name: Smart Tesla
description: 휴대폰 우선 차량 자동화 앱 · 기본 Android 조작과 요약·시트 편집
colors:
  light-void: "#F3F6FA"
  light-carbon: "#FFFFFF"
  light-graphite: "#FFFFFF"
  light-slate: "#E8EEF5"
  light-hairline: "#D1DAE5"
  light-ink: "#1C2B3A"
  light-inkMuted: "#46596B"
  light-inkFaint: "#52677B"
  light-electric: "#1565C0"
  light-electricPressed: "#0D47A1"
  light-electricFaint: "#E3F2FD"
  light-cool: "#0277BD"
  light-heat: "#BF360C"
  light-warn: "#8D5700"
  light-warnText: "#795000"
  light-warnFaint: "#FFF3E0"
  light-danger: "#C62828"
  light-onDanger: "#FFFFFF"
  light-ok: "#2E7D32"
  light-okText: "#256629"
  dark-void: "#141C24"
  dark-carbon: "#1E2935"
  dark-graphite: "#24313F"
  dark-slate: "#2C3B4C"
  dark-hairline: "#43576B"
  dark-ink: "#EFF5FC"
  dark-inkMuted: "#C1CFDF"
  dark-inkFaint: "#AABCD0"
  dark-electric: "#90CAF9"
  dark-electricPressed: "#BBDEFB"
  dark-electricFaint: "#213B55"
  dark-cool: "#81D4FA"
  dark-heat: "#FFAB91"
  dark-warn: "#FFCC80"
  dark-warnText: "#FFCC80"
  dark-warnFaint: "#3E3020"
  dark-danger: "#FFAB91"
  dark-onDanger: "#29130F"
  dark-ok: "#A5D6A7"
  dark-okText: "#A5D6A7"
typography:
  headlineLarge:
    fontFamily: "system-ui"
    fontSize: "28sp"
    fontWeight: 600
    lineHeight: "36sp"
  headlineMedium:
    fontFamily: "system-ui"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "30sp"
  titleLarge:
    fontFamily: "system-ui"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "30sp"
  titleMedium:
    fontFamily: "system-ui"
    fontSize: "17sp"
    fontWeight: 600
    lineHeight: "25sp"
  titleSmall:
    fontFamily: "system-ui"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "21sp"
  bodyMedium:
    fontFamily: "system-ui"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  bodySmall:
    fontFamily: "system-ui"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "20sp"
  labelLarge:
    fontFamily: "system-ui"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
  labelMedium:
    fontFamily: "system-ui"
    fontSize: "13sp"
    fontWeight: 600
    lineHeight: "18sp"
  labelSmall:
    fontFamily: "system-ui"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "18sp"
  CalloutNumberStyle:
    fontFamily: "monospace"
    fontSize: "11sp"
    fontWeight: 500
    lineHeight: "14sp"
  MetricTextStyle:
    fontFamily: "monospace"
    fontSize: "26sp"
    fontWeight: 500
    lineHeight: "32sp"
  TileValueStyle:
    fontFamily: "monospace"
    fontSize: "20sp"
    fontWeight: 500
    lineHeight: "26sp"
  TileValueStyleLarge:
    fontFamily: "monospace"
    fontSize: "26sp"
    fontWeight: 500
    lineHeight: "32sp"
  HeroValueStyle:
    fontFamily: "monospace"
    fontSize: "96sp"
    fontWeight: 500
    lineHeight: "100sp"
rounded:
  button: "12dp"
  card: "20dp"
  hero: "24dp"
  pill: "999dp"
  segment: "12dp"
  tile: "16dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "16dp"
  lg: "24dp"
  xl: "32dp"
  xxl: "48dp"
components:
  button-primary:
    backgroundColor: "{colors.light-electric}"
    textColor: "{colors.light-carbon}"
    rounded: "{rounded.button}"
    height: "40dp (compact 36dp), touch 48dp"
  card:
    backgroundColor: "{colors.light-carbon}"
    rounded: "{rounded.card}"
    padding: "{spacing.md}"
---
# Smart Tesla 디자인 시스템

## Overview

휴대폰에서 매크로와 설정을 빠르게 읽고 조작하는 화면이 기준이다.
Material Blue는 주요 동작과 선택에만 사용한다. 중립 목록·주제별 설정 묶음·요약에서 여는 상세 시트로 화면을 구성한다.
밤에는 차콜 면과 Blue 200을 사용하며, 종류별로 임의 색상을 칠한 타일은 두지 않는다.
사용자의 전면 리디자인 요청에 따라 기존 정비 도면·0dp 모서리·카드 금지·Material 금지·태블릿 우선 규칙을 명시적으로 대체한다.
기존 `Draft*` 이름과 차량 선도 구현은 호환되는 코드 자산이며, 새 화면을 도면처럼 만들라는 지침이 아니다.

참고한 실앱 흐름은 [Samsung 모드 및 루틴](https://www.samsung.com/us/support/answer/ANS10002538/)의 조건·동작 분리와 설정 묶음, [Google Home 자동화](https://support.google.com/googlehome/answer/16214649?hl=en)의 요약→상세→저장이다.
버튼은 [Android 공식 Compose 버튼](https://developer.android.com/develop/ui/compose/components/button)의 강조 계층과 실제 Material 3 컴포넌트를 사용한다. 색상표만 빌린 자체 버튼이나 웹 랜딩페이지 장식을 만들지 않는다.

## Colors

정확한 낮/밤 팔레트는 위 토큰과 `ui/theme/Color.kt`에 기록한다.
`Void`는 화면 배경, `Carbon/Graphite`는 콘텐츠 면, `Slate`는 보조 면, `Hairline`은 경계다.
`Electric`은 주요 동작과 선택 상태, `ElectricFaint`는 선택 배경이다.
강조색은 [Material 색상표](https://m2.material.io/design/color/the-color-system.html)를 기준으로 선택하고, Compose Material 3의 색상 역할에 맞춰 배경과 전경을 함께 지정한다.
매크로 종류는 기능 아이콘과 이름으로 구분한다. 분류를 위해 쓰던 `TileBlue/TileTeal/TilePurple/TileAmber/TileRose`는 제거했다.
본문·보조 문구·주요 동작은 낮/밤 모두 4.5:1 대비를 `PaletteContrastTest`로 검증한다.
`Cool/Heat`는 냉각·난방, `Warn`은 주의, `Danger`는 오류, `Ok`는 정상 상태에 사용한다.
정상 상태의 강조색과 선택 강조를 허용하며, 예전 적·청 두 색 제한을 적용하지 않는다.
색만으로 상태를 전달하지 않고 글자·선택 상태·아이콘을 함께 사용한다.
설정 → 기기 → 화면 모드에서 자동·라이트·다크를 선택하며 선택값을 저장한다.
기본값인 자동은 07시부터 19시 전까지 라이트, 나머지는 다크이며 시각을 10분마다 재확인한다.
라이트·다크는 시각과 무관하게 고정되며 시스템 테마를 따르지 않는다.

## Typography

시스템 기본 서체로 한국어 제목과 본문을 표시한다.
제목은 28/36sp 또는 22/30sp, 중간 제목은 17/25sp, 본문은 16/24sp 또는 13/20sp로 구분한다.
계측값에는 고정폭과 `tnum`을 유지한다.
`HeroValueStyle`의 96/100sp는 기존 계측 화면의 기본값이며 일반 화면 제목 크기가 아니다.
시스템 글자 확대에서 제목·라벨·버튼이 잘리지 않는지 실제 렌더링으로 확인한다.

### 글자 배율

글자 크기는 실제 렌더링 폭을 재서 맞추며 배율 상한이나 서체 advance를 추정하지 않는다.
기입 치수의 `fitInscribedSp`는 측정 폭과 사용 가능한 폭으로 축소량을 정한다.
`InscribedSizeTest`의 순수 함수 검증과 `WideFontScaleTest`의 1.3배·값 있는 상태 렌더링을 함께 유지한다.
일반 본문을 강제로 줄이거나 시스템 글자 배율을 전역 제한하지 않는다.
휴대폰의 Fleet 최초 등록과 백업 실패 안내도 글자 배율 1.3·낮/밤으로 렌더링해 입력·복구 동작이 잘리지 않는지 확인한다.

## Layout

휴대폰 세로의 본문은 한 열 흐름을 기본으로 설계한다.
매크로 목록은 휴대폰에서 중립 1열이다. 가로 태블릿은 `LocalPane` 열 수를 따르고, 글자 배율 1.3 이상이면 기기와 무관하게 한 열로 전환한다.
폴더를 먼저 보여 주고 폴더 밖 매크로만 아래에 표시한다. 폴더에 넣은 매크로는 홈에 중복 노출하지 않으며 폴더 안에서만 보인다.
상단은 제목·추가·더보기이며 폴더 안에서는 뒤로가기와 폴더명을 표시한다. 검색창은 두지 않고 폴더 만들기·이름 변경은 더보기 메뉴로 모은다. 아이콘 버튼은 최소 48dp다.
목록에는 수동 실행 버튼과 최근 실행 기록을 표시하지 않는다. 자동 실행 토글·편집·복제·삭제와 실행 중단은 유지한다.
폴더는 `Slate` 탐색 행, 개별 매크로는 `Carbon` 카드와 중립 1dp 테두리로 구분한다. 제목·요약·실행 상태를 읽기 순서대로 두고, 편집·더보기·자동 실행 토글의 터치 영역을 분리한다.
루트가 전달하는 `LocalPane`을 쓰며 세로는 기기 폭과 관계없이 Compact로 분류한다.
가로에서만 Compact는 600dp 미만, Medium은 600dp 이상 900dp 미만, Expanded는 900dp 이상이다.
키보드로 줄어든 높이가 아닌 OS 화면 방향을 기준으로 하며, 세로는 하단 탭, 가로는 112dp 좌측 탐색 영역을 사용한다.
실제 탐색 항목은 매크로와 설정 두 개이며 제어 경로는 호환용으로 남는다.
매크로 편집은 목록 위 실제 모달 시트다. 이름·실행 시점·선택 조건·실행 순서·자동 실행과 재발동을 한 스크롤 흐름에서 보여 주며 신규·기존 매크로에 같은 구조를 쓴다.
항목을 누르면 해당 상세로 전환하고 돌아가면 요약을 보여 준다. 하단 취소/돌아가기와 저장은 고정하며 검증 안내·저장 실패를 같은 위치에 남긴다. 단계 숫자나 이전/다음 마법사는 없다.
설정의 상위 탭은 `SectionTabs`의 텍스트와 선택 밑줄로 구분한다. 상세로 들어가는 요약 행은 오른쪽 화살표이며, 내용을 바로 펼치는 아래 화살표와 혼용하지 않는다.
편집 선택지는 공용 `ChoiceGrid`와 Material `FilterChip`을 사용한다. 좌석은 아이콘·좌석명·선택 체크가 있는 행이다. 긴 선택지는 2열, 짧은 단계는 4열이며 글자 배율 1.3 이상에서는 최대 2열로 펼친다.
선택 칩은 내용 높이를 사용하며 `IntrinsicSize.Min`과 `fillMaxHeight`로 높이를 강제하지 않는다. 짧은 선택창이 남은 화면 높이까지 늘어나거나 화면 아래에서 잘리는 것을 금지한다.
요일도 4열(큰 글씨 2열)로 나눠 7개 버튼을 한 줄에 압축하지 않는다. 동작 정렬·삭제 아이콘은 24dp 표시·48dp 터치 영역을 공유한다.
새 항목은 바로 상세를 편집한다. 회전에서도 편집 위치를 유지하고, 이동은 같은 항목을 따라가며 삭제는 해당 상세를 닫는다. 긴 이름·값은 줄바꿈하고 동작 순번과 대기 상한은 보존한다.
상태 변화 트리거는 발생 사건과 주행 조건을 구분한다. 주행 조건은 `제한 없음·주행 전 P단·주행 후 P단`이며 프리셋과 직접 추가 모두 같은 선택기를 쓴다. 주행 이력은 앱에서 관측한 값이며 다른 OR 트리거까지 제한하지 않는다.
동작 목록의 추가 버튼은 하나다. 시간 대기·조건 대기·지도 안내·스텔스 충전은 동작 선택창의 `대기 · 기타`에서 고르며, 차량 명령은 기존 분류를 유지한다. 실행 규칙·저장 형식은 바꾸지 않는다.
시스템 뒤로가기는 열린 선택창을 먼저 닫고, 매크로 상세에서는 요약으로 돌아가며 요약에서는 목록으로 복귀한다.
매크로 목록은 1단 폴더를 지원한다. 폴더 만들기·폴더 안 이름 변경·매크로 더보기의 폴더 이동을 제공하며 뒤로 가기로 상위 목록에 돌아온다.
기본 통풍 6개·열선 2개는 최초 한 번 각 폴더에 묶고 공통 하차 종료는 밖에 둔다. 빈 폴더와 사용자 이동은 재시작 후에도 보존한다.
폴더는 표시 분류만 바꾸며 자동 실행에는 영향을 주지 않는다. 폴더 안에서 추가하거나 복제한 매크로는 저장 후 해당 폴더에 넣는다.
설정 순서는 `자동화·주행·차량·기기`다. 매크로 자동 실행은 항상 켜져 있어 설정에 스위치가 없다. 자동화는 음성 명령→Fleet API→충전 순서로 한 열에 쌓고 스텔스 충전을 맨 아래에 둔다. 기기 칸만 넓은 화면에서 좌우 2단이다. Fleet 토큰 저장 후 입력칸과 저장 버튼은 숨기고 확인·삭제만 남긴다.
주행 칸의 네이버 지도 안심운전 자동 실행과 실시간 속도 표시는 재개발 전까지 `FeatureAvailability`로 숨기며, 저장값은 남기되 읽을 때 꺼진 것으로 처리해 몰래 동작하지 않게 한다. 차량 칸의 등록 카드는 `차량 등록`·VIN·등록 해제(등록하기)를 한 줄에 두고 좁으면 VIN만 줄인다.
설정 화면은 `SettingsTypography`(본문 14sp, 보조 문구 13sp, 최소 라벨 12sp)와 `LocalCompactButtons`로 밀도를 맞춘다. 최소 터치 높이 48dp와 시스템 글자 배율은 유지한다.
단속 안내의 `경고음 종류`는 모달 목록에서 고르며, 누를 때마다 실제 경고음을 들려주고 창은 열어 둔 채 여러 소리를 비교하게 한다. 경고음 크기 선택도 누르면 미리 들려준다.
스텔스 충전 그래프는 최근 24시간 내 실제 충전 전류가 흐른 기록과 표시할 양수 막대가 있을 때만 표시한다. 0A 관측만 있으면 차트 전체를 숨긴다.
차량 연결 안전 카드에는 자동 보호 설정만 남기고 수동 연결 끊기 버튼과 설명은 표시하지 않는다.
설정은 단속 안내를 포함해 주제별 중립 면으로 묶고 실제 스위치와 상세 진입 행을 구분한다. 현재 값은 목록에 남기며 자세한 입력은 시트에서 바꾼다. 온라인 GPS 전송 고지·권한 요청·비밀값·백업 주의·점검 결과는 기존 표시·확인 경로를 보존한다. 권한 경고와 해결 버튼은 세로로 배치하고 보조 버튼 묶음은 폭에 따라 줄바꿈한다.
`ExpandableToggle`의 이름은 유지하되 긴 폼을 인라인으로 펼치지 않는다. 제목과 오른쪽 화살표는 상세 시트, 오른쪽 스위치는 켜짐/꺼짐만 조작한다. 설정은 즉시 저장하므로 시트에 가짜 저장·취소 버튼을 추가하지 않는다. 권한 부족·명령 취소 불가 주의·충전 진행 시간·음성 엔진 상태는 목록에서도 보인다.
Fleet 최초 토큰은 스위치를 켜기 전에 상세에서 등록한다. 저장된 토큰 원문을 다시 표시하지 않으며 실패 안내는 상세 진입 여부와 무관하게 유지한다.
백업 버튼은 큰 글씨에서 줄바꿈하며 결과 알림을 명시적으로 닫을 수 있다. 차량 검색 실패는 VIN 수정과 다시 찾기를 함께 제공하고, 나중에 선택하면 진행 중인 검색·등록 확인을 중지한다.
매크로 저장 실패는 편집 내용·저장 버튼과 함께 표시한다. 앱 초기화 실패는 무한 로딩 대신 데이터 보존 안내와 재시도 버튼을 보여준다.
긴 내용은 스크롤하며 중요 저장 동작은 접근 가능한 위치에 둔다.
태블릿도 지원하되 휴대폰의 글자 크기·밀도·동작 흐름이 우선이다.

## Elevation & Depth

공용 카드와 버튼은 그림자 대신 배경 면, 여백, 필요한 테두리로 계층을 만든다.
`TCard`는 기본적으로 테두리 없는 콘텐츠 면이며 `outlined`일 때 강조색 1dp 테두리를 사용한다.
시트는 별도 편집 흐름을 담으며 카드 안에 긴 입력 폼을 항상 펼치지 않는다.

## Shapes

실행 버튼·입력칸·선택 칩은 12dp, 일반 카드는 20dp, 매크로 목록 카드는 12dp, 시트는 20dp 반경이다.
`pill` 999dp는 배지 등 둥근 형태에 사용한다.
보조선 0.5dp, 경계 1dp, 강조선 2dp를 유지하되 도면 스타일을 강제하지 않는다.

## Components

- `TButton`: Material `Button`·`OutlinedButton`·`TextButton`을 사용한다. 시각적 면은 기본 40dp·소형 36dp이고 Material의 48dp 터치 영역은 유지한다. 큰 글씨는 고정 높이로 자르지 않고 내용에 맞춰 늘어난다.
  Primary는 주요 저장/실행의 채운 버튼, Secondary는 대안 동작의 윤곽 버튼, Ghost는 추가·취소 등 텍스트 동작, Danger는 파괴적 동작의 오류색 텍스트다.
- `TCard`: 20dp 반경, `Carbon` 면, 16dp 내부 여백으로 관련 내용을 묶는다.
- `DraftToggle`: Material Switch의 그림만 0.85배로 줄이며 행 전체의 최소 48dp 터치 영역과 상태어를 유지한다.
  매크로는 상태 문구와 스위치를 함께 표시하고 접근성 이름에 매크로 이름과 자동 실행 용도를 남긴다.
- 화면 모드: `ChoiceGrid`의 자동·라이트·다크 세 선택지로 구성하며 현재 선택을 강조한다.
- `DraftField`: 실제 Material `OutlinedTextField`로 라벨·포커스·키보드·비밀값 가림·오류 안내를 공유한다.
- 선택 칩: 실제 Material `FilterChip`의 선택 체크와 옅은 강조 면을 함께 사용한다. 공조 단계는 `SingleChoiceSegmentedButtonRow`로 단일 선택을 표현한다.
  배경과 글자색을 함께 지정하고 밤에 흰 글자를 고정하지 않는다.
- 탐색: Material `NavigationBar/NavigationRail`에 아이콘과 기능 이름을 함께 표시하며 선택 항목은 `ElectricFaint` 면과 `Electric` 전경이다.
- 스마트싱스 명령: 목록에서 선택한 항목을 별도 시트로 편집하며 알림 문구와 차량 동작을 연결한다.
- `PickerSheet`: 실제 `Dialog`로 배경 조작을 막는다. 휴대폰 하단·가로 태블릿 중앙에 배치하며 최대 높이는 가용 높이 90%다. 짧은 설정은 내용 높이에 맞추고 긴 매크로 편집은 본문 스크롤과 고정 저장 영역을 쓴다. 본문 여백은 16dp다. 전체 화면 창의 시스템 바·키보드 inset을 바깥에서 소비하고 닫기·시스템 뒤로가기를 제공한다. 에뮬레이터에서 글자 2배·소프트 키보드가 열린 상태의 저장 버튼 접근을 확인했다.
- `NumberStepper`: 증감 버튼과 값 직접 입력을 함께 제공한다. 시각은 `HH:mm` 입력이며 취소는 원래 값을 보존하고 적용 시 유한수·허용 범위·시/분 범위를 검사한다.

## Do's and Don'ts

- Do UI 변경은 `-PallowSnapshots=true`로 실제 휴대폰·태블릿 렌더링을 열어 확인한다. 낮/밤과 필요한 글자 확대를 포함하고 CLI 사용자에게는 텍스트 결과만 보고한다. 일반 `test`는 비렌더링 상태를 유지한다.
- Do 공용 토큰과 기존 프리미티브를 재사용한다.
- Do 모든 조작 타깃을 최소 48dp로 제공한다.
- Do 아직 읽지 못한 값은 `--`로 표시하고 실제 차량 결과와 UI 표시 검증을 구별한다.
- Don't 도면 금지 규칙을 되살려 둥근 카드·Material Switch·정상 상태 강조색을 제거하지 않는다.
- Don't 주행 중 편집이나 조작을 유도하지 않는다.
- Don't 그라데이션·글로우를 새 장식으로 추가하지 않는다.
- Don't 종류별 다색 타일, 의미 없는 번호·배지·가짜 탭, 모든 동작의 채운 버튼화, 장식용 시트 손잡이를 추가하지 않는다.

소스 정본: `app/src/main/java/com/wemade/teslamacro/ui/theme/{Color,Theme,Type}.kt`, `ui/component/{Drafting,Primitives,Pickers}.kt`, `ui/nav/Navigation.kt`, 매크로·설정 화면.
Android 단위(dp/sp)는 웹 px와 동일한 실측 단위로 해석하지 않는다.
