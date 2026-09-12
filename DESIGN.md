---
name: Smart Tesla
description: 휴대폰 우선 차량 자동화 앱 · 0.9.41
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
07시부터 19시 전까지 낮 팔레트이며, 기본 테마는 시각을 10분마다 재확인한다.

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

휴대폰 세로의 한 열 흐름을 먼저 설계한다.
루트가 전달하는 `LocalPane`을 쓰며 Compact는 600dp 미만, Medium은 600dp 이상 900dp 미만, Expanded는 900dp 이상이다.
휴대폰은 하단 탭, 넓은 화면은 112dp 좌측 탐색 영역을 사용한다.
실제 탐색 항목은 매크로와 설정 두 개이며 제어 경로는 호환용으로 남는다.
매크로 편집은 네 단계의 구역과 하단 고정 저장 동작으로 구성한다.
설정은 주제별 카드, 스마트싱스는 연결 요약·등록 목록과 별도 명령 편집 시트로 나눈다.
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
- `DraftField`: 라벨과 둥근 `Slate` 입력 면, 포커스 경계로 입력 위치를 구별한다.
- 선택 칩: 선택 배경과 전경은 현재 팔레트에서 함께 구한다.
  밤의 밝은 블루 면 위에 밝은 흰색 글자를 고정하지 않는다.
- 탐색: 아이콘과 기능 이름을 함께 표시하며 선택 항목은 `ElectricFaint` 면과 `Electric` 전경이다.
- 스마트싱스 명령: 목록에서 선택한 항목을 별도 시트로 편집하며 알림 문구와 차량 동작을 연결한다.

## Do's and Don'ts

- Do 휴대폰 낮/밤 스냅샷을 우선 확인하고 태블릿 기본·글자 확대 회귀도 확인한다.
- Do 공용 토큰과 기존 프리미티브를 재사용한다.
- Do 모든 조작 타깃을 최소 48dp로 제공한다.
- Do 아직 읽지 못한 값은 `--`로 표시하고 실제 차량 결과와 UI 표시 검증을 구별한다.
- Don't 도면 금지 규칙을 되살려 둥근 카드·Material Switch·정상 상태 강조색을 제거하지 않는다.
- Don't 주행 중 편집이나 조작을 유도하지 않는다.
- Don't 그라데이션·글로우를 새 장식으로 추가하지 않는다.

소스 정본: `app/src/main/java/com/wemade/teslamacro/ui/theme/{Color,Theme,Type}.kt`, `ui/component/{Drafting,Primitives,Pickers}.kt`, `ui/nav/Navigation.kt`, 매크로·설정 화면.
Android 단위(dp/sp)는 웹 px와 동일한 실측 단위로 해석하지 않는다.
