---
name: Smart Tesla
description: 휴대폰 우선 차량 자동화 앱 · 0.9.42
colors:
  light-void: "#F5F6F8"
  light-carbon: "#FFFFFF"
  light-graphite: "#FFFFFF"
  light-slate: "#ECEEF2"
  light-hairline: "#D8DCE3"
  light-ink: "#20242B"
  light-inkMuted: "#555E6B"
  light-inkFaint: "#646D7A"
  light-electric: "#3569B7"
  light-electricPressed: "#285393"
  light-electricFaint: "#E8EFFA"
  light-cool: "#1F5C8C"
  light-heat: "#B3411F"
  light-warn: "#A1601A"
  light-warnText: "#7E4712"
  light-warnFaint: "#EDE4D2"
  light-danger: "#C8321E"
  light-onDanger: "#F2F0E9"
  light-ok: "#3569B7"
  light-okText: "#3569B7"
  dark-void: "#15171B"
  dark-carbon: "#202329"
  dark-graphite: "#202329"
  dark-slate: "#2C3038"
  dark-hairline: "#424852"
  dark-ink: "#EFF1F5"
  dark-inkMuted: "#BDC3CD"
  dark-inkFaint: "#AAB2BF"
  dark-electric: "#91B4E8"
  dark-electricPressed: "#B1CCF2"
  dark-electricFaint: "#293A53"
  dark-cool: "#6FB6E0"
  dark-heat: "#E08A5A"
  dark-warn: "#D9A441"
  dark-warnText: "#D9A441"
  dark-warnFaint: "#2B2718"
  dark-danger: "#E8624E"
  dark-onDanger: "#101619"
  dark-ok: "#91B4E8"
  dark-okText: "#91B4E8"
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
  titleMedium:
    fontFamily: "system-ui"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "24sp"
  titleSmall:
    fontFamily: "system-ui"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "21sp"
  bodyMedium:
    fontFamily: "system-ui"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "23sp"
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
    textColor: "{colors.light-void}"
    rounded: "{rounded.button}"
    height: "52dp"
  card:
    backgroundColor: "{colors.light-carbon}"
    rounded: "{rounded.card}"
    padding: "{spacing.md}"
---
# Smart Tesla 디자인 시스템

## Overview

휴대폰에서 매크로와 설정을 빠르게 읽고 조작하는 화면이 기준이다.
밝은 쿨 뉴트럴 바탕, 블루 강조색, 둥근 콘텐츠 카드로 정보 묶음과 다음 동작을 구별한다.
밤에는 차콜 면과 밝은 블루 강조색을 사용한다.
사용자의 전면 리디자인 요청에 따라 기존 정비 도면·0dp 모서리·카드 금지·Material 금지·태블릿 우선 규칙을 명시적으로 대체한다.
기존 `Draft*` 이름과 차량 선도 구현은 호환되는 코드 자산이며, 새 화면을 도면처럼 만들라는 지침이 아니다.

## Colors

정확한 낮/밤 팔레트는 위 토큰과 `ui/theme/Color.kt`에 기록한다.
`Void`는 화면 배경, `Carbon/Graphite`는 콘텐츠 면, `Slate`는 보조 면, `Hairline`은 경계다.
`Electric`은 주요 동작과 선택 상태, `ElectricFaint`는 선택 배경이다.
`Cool/Heat`는 냉각·난방, `Warn`은 주의, `Danger`는 오류, `Ok`는 정상 상태에 사용한다.
정상 상태의 강조색과 선택 강조를 허용하며, 예전 적·청 두 색 제한을 적용하지 않는다.
색만으로 상태를 전달하지 않고 글자·선택 상태·아이콘을 함께 사용한다.
설정 → 기기 → 화면 모드에서 자동·라이트·다크를 선택하며 선택값을 저장한다.
기본값인 자동은 07시부터 19시 전까지 라이트, 나머지는 다크이며 시각을 10분마다 재확인한다.
라이트·다크는 시각과 무관하게 고정되며 시스템 테마를 따르지 않는다.

## Typography

시스템 기본 서체로 한국어 제목과 본문을 표시한다.
제목은 28/36sp 또는 22/30sp, 본문은 15/23sp 또는 13/20sp로 구분한다.
계측값에는 고정폭과 `tnum`을 유지한다.
`HeroValueStyle`의 96/100sp는 기존 계측 화면의 기본값이며 일반 화면 제목 크기가 아니다.
시스템 글자 확대에서 제목·라벨·버튼이 잘리지 않는지 실제 렌더링으로 확인한다.

### 글자 배율

글자 크기는 실제 렌더링 폭을 재서 맞추며 배율 상한이나 서체 advance를 추정하지 않는다.
기입 치수의 `fitInscribedSp`는 측정 폭과 사용 가능한 폭으로 축소량을 정한다.
`InscribedSizeTest`의 순수 함수 검증과 `WideFontScaleTest`의 1.3배·값 있는 상태 렌더링을 함께 유지한다.
일반 본문을 강제로 줄이거나 시스템 글자 배율을 전역 제한하지 않는다.
휴대폰 글자 확대는 추가 검증 권장 항목이며 이번 검증 결과로 기록하지 않는다.

## Layout

휴대폰 세로의 본문은 한 열 흐름을 기본으로 설계한다.
매크로 목록은 이름 중심의 2열 카드로 밀도를 높이며, 글자 배율 1.3 이상에서는 `LocalPane`의 기본 열 수로 돌아가 읽을 폭을 확보한다.
매크로 카드 간격은 8dp다. 상단은 제목·추가·더보기 한 줄이며 폴더 안에서는 뒤로가기와 폴더명을 표시한다. 전체 개수 요약은 표시하지 않는다.
상단 아이콘 버튼은 배경·테두리 없이 48dp 터치 영역을 유지한다. 폴더 만들기·이름 변경은 더보기 메뉴로 모으고, 폴더 카드에는 블루 윤곽 폴더 아이콘을 붙여 일반 매크로와 구분한다.
목록에는 수동 실행 버튼과 최근 실행 기록을 표시하지 않는다. 자동 실행 토글·편집·복제·삭제와 실행 중단은 유지한다.
루트가 전달하는 `LocalPane`을 쓰며 세로는 기기 폭과 관계없이 Compact로 분류한다.
가로에서만 Compact는 600dp 미만, Medium은 600dp 이상 900dp 미만, Expanded는 900dp 이상이다.
키보드로 줄어든 높이가 아닌 OS 화면 방향을 기준으로 하며, 세로는 하단 탭, 가로는 112dp 좌측 탐색 영역을 사용한다.
실제 탐색 항목은 매크로와 설정 두 개이며 제어 경로는 호환용으로 남는다.
매크로 편집은 언제·조건·동작·마무리의 48dp 최소 높이 탭과 하단 고정 저장 동작으로 구성한다. 중복 단계 숫자·탭 부제는 없애고 기존 매크로는 탭 이동과 저장만 제공한다. 신규 생성에만 이전·다음 안내를 둔다.
편집과 설정 탭은 공용 `SectionTabs`의 텍스트·선택 밑줄로 탐색을 표시한다. 탭 높이는 최소 48dp이고 긴 이름은 줄바꿈한다. 좌석 동작은 `좌석`과 `작동 단계`를 별도 라벨·24dp 구역 간격으로 구분한다. 좌석은 윤곽선 선택, 작동 값은 채움 선택으로 역할을 다르게 보이며 값은 `끄기·1단·2단·3단`으로 명시한다.
편집 선택지는 공용 ChoiceGrid로 폭·높이·8dp 간격·가운데 정렬을 통일한다. 좌석·조건·대기·옵션은 기본 2열, 짧은 강도는 4열, 비교는 3열이며 글자 배율 1.3 이상에서는 최대 2열로 펼친다. 마지막 줄의 빈 열도 유지해 버튼 폭이 바뀌지 않는다.
요일도 4열(큰 글씨 2열)로 나눠 7개 버튼을 한 줄에 압축하지 않는다. 동작 정렬·삭제 아이콘은 24dp 표시·48dp 터치 영역을 공유한다.
언제·조건·동작 탭은 처음에는 요약 목록으로 보이며, 누른 항목 하나만 펼쳐 상세를 편집한다. 순서·삭제 버튼도 펼친 항목에서만 표시한다. 요약 제목은 긴 이름·값을 줄바꿈하고, 동작 순번과 대기 상한도 보존한다.
새 항목은 바로 펼쳐 편집하며, 탭 전환·화면 회전에서도 편집 위치를 유지한다. 이동은 같은 항목을 따라가고 삭제는 해당 편집기를 닫는다. 펼친 제목이 스크롤 밖으로 밀리지 않도록 배치 후 화면 안으로 이동한다. 요약 행은 공용 `DisclosureHeader`를 쓰며 최소 48dp와 접근성 펼침/접힘 상태·동작을 공유한다.
상태 변화 트리거는 발생 사건을 제목에, `주행 조건 · 주행 전/후 · P단`을 보조 줄에 분리한다. 펼치면 발생 방향과 주행 조건을 별도 구역에서 편집한다. 주행 조건은 `제한 없음·주행 전 P단·주행 후 P단`이며 프리셋과 직접 추가 모두 같은 선택기를 쓴다. 주행 이력은 앱에서 관측한 값이며, 이 조건을 전체 조건 탭으로 옮겨 다른 OR 트리거까지 제한하지 않는다.
동작 목록의 추가 버튼은 하나다. 시간 대기·조건 대기·지도 안내·스텔스 충전은 동작 선택창의 `대기 · 기타`에서 고르며, 차량 명령은 기존 분류를 유지한다. 실행 규칙·저장 형식은 바꾸지 않는다.
시스템 뒤로가기는 선택창이 열려 있으면 그 창만 닫고, 편집 탭에서는 단계 순회 없이 한 번에 목록으로 복귀한다. 탭은 탐색 이력이 아니다.
매크로 목록은 1단 폴더를 지원한다. 폴더 만들기·폴더 안 이름 변경·매크로 더보기의 폴더 이동을 제공하며 뒤로 가기로 상위 목록에 돌아온다.
기본 통풍 6개·열선 2개는 최초 한 번 각 폴더에 묶고 공통 하차 종료는 밖에 둔다. 빈 폴더와 사용자 이동은 재시작 후에도 보존한다.
폴더는 표시 분류만 바꾸며 자동 실행에는 영향을 주지 않는다. 폴더 안에서 추가하거나 복제한 매크로는 저장 후 해당 폴더에 넣는다.
설정 순서는 `자동화·주행·차량·기기`다. 자동화는 매크로→충전→음성 명령→Fleet API 순서로 읽으며 넓은 화면에서는 매크로·충전을 왼쪽, 음성·Fleet을 오른쪽에 둔다. Fleet 토큰 저장 후 입력칸과 저장 버튼은 숨기고 확인·삭제만 남긴다.
스텔스 충전 그래프는 최근 24시간 내 실제 충전 전류가 흐른 기록과 표시할 양수 막대가 있을 때만 표시한다. 0A 관측만 있으면 차트 전체를 숨긴다.
차량 연결 안전 카드에는 자동 보호 설정만 남기고 수동 연결 끊기 버튼과 설명은 표시하지 않는다.
설정은 주제별 카드로 나누고 값 선택은 공용 `ChoiceGrid`를 써 탐색 탭과 구별한다. 충전 상세는 실제 전류 범위·시간대, 명령 유효시간은 현재 초를 접힌 요약에 남긴다. 안심운전 실행 점검과 단속 데이터 출처·범위는 필요할 때 펼친다. 권한 요청, 도로 표지 우선 경고, 점검 결과, 비밀값·백업 주의는 숨기지 않는다. 스마트싱스는 권한→명령 관리→유효시간 순서이며 명령 편집 시트를 유지한다.
긴 내용은 스크롤하며 중요 저장 동작은 접근 가능한 위치에 둔다.
태블릿도 지원하되 휴대폰의 글자 크기·밀도·동작 흐름이 우선이다.

## Elevation & Depth

공용 카드와 버튼은 그림자 대신 배경 면, 여백, 필요한 테두리로 계층을 만든다.
`TCard`는 기본적으로 테두리 없는 콘텐츠 면이며 `outlined`일 때 강조색 1dp 테두리를 사용한다.
시트는 별도 편집 흐름을 담으며 카드 안에 긴 입력 폼을 항상 펼치지 않는다.

## Shapes

버튼·입력칸·세그먼트는 12dp, 카드는 20dp, 타일은 16dp, 히어로는 24dp 반경이다.
`pill` 999dp는 배지 등 둥근 형태에 사용한다.
보조선 0.5dp, 경계 1dp, 강조선 2dp를 유지하되 도면 스타일을 강제하지 않는다.

## Components

- `TButton`: 기본 최소 높이 52dp, 소형 48dp, 누를 때 0.97배와 160ms 전환을 사용한다.
  Primary는 `Electric` 면과 `Void` 글자, Secondary는 `Slate` 면과 `Ink` 글자, Danger는 보조 면과 오류색 글자다.
- `TCard`: 20dp 반경, `Carbon` 면, 16dp 내부 여백으로 관련 내용을 묶는다.
- `DraftToggle`: 이름은 유지하되 Material Switch를 사용하고 행의 최소 높이는 48dp다.
  매크로 목록은 별도 켜짐·꺼짐 문구 없이 스위치로 상태를 표시하고, 접근성 이름은 매크로 이름과 자동 실행 용도를 유지한다.
- 화면 모드: `ChoiceRow`의 자동·라이트·다크 세 선택지로 구성하며 현재 선택을 강조한다.
- `DraftField`: 라벨과 둥근 `Slate` 입력 면, 포커스 경계로 입력 위치를 구별한다.
- 선택 칩: 선택 배경과 전경은 현재 팔레트에서 함께 구한다.
  밤의 밝은 블루 면 위에 밝은 흰색 글자를 고정하지 않는다.
- 탐색: 아이콘과 기능 이름을 함께 표시하며 선택 항목은 `ElectricFaint` 면과 `Electric` 전경이다.
- 스마트싱스 명령: 목록에서 선택한 항목을 별도 시트로 편집하며 알림 문구와 차량 동작을 연결한다.

## Do's and Don'ts

- Do use non-image tests, source inspection, and builds for CLI verification. NEVER generate/open screenshots, recommend visual checks, ask image permission, or require snapshots for release unless explicitly requested; only then cover phone day/night and tablet/font-scale cases.
- Do 공용 토큰과 기존 프리미티브를 재사용한다.
- Do 모든 조작 타깃을 최소 48dp로 제공한다.
- Do 아직 읽지 못한 값은 `--`로 표시하고 실제 차량 결과와 UI 표시 검증을 구별한다.
- Don't 도면 금지 규칙을 되살려 둥근 카드·Material Switch·정상 상태 강조색을 제거하지 않는다.
- Don't 주행 중 편집이나 조작을 유도하지 않는다.
- Don't 그라데이션·글로우를 새 장식으로 추가하지 않는다.

소스 정본: `app/src/main/java/com/wemade/teslamacro/ui/theme/{Color,Theme,Type}.kt`, `ui/component/{Drafting,Primitives,Pickers}.kt`, `ui/nav/Navigation.kt`, 매크로·설정 화면.
Android 단위(dp/sp)는 웹 px와 동일한 실측 단위로 해석하지 않는다.
