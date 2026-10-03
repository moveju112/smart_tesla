# 기능 목록 밀도 조정 — 0.9.136

## 변경
- 공유받은 참고 화면처럼 아이콘·기능명·오른쪽 요약이 이어지는 목록으로 변경.
- 기능별 큰 카드와 반복 선택 안내 제거. 긴 설명은 기존 상세 화면에 유지.
- 오른쪽에 짧은 설명과 켜짐·꺼짐·설정 필요 상태 표시. 좁은 큰 글씨에서는 이름 아래로 배치.
- 행 전체가 최소 64dp 버튼이며 아이콘은 장식으로 처리. 기존 설정 유도·화면 복원·실행 콜백 유지.

## 재사용
- `ui/component/Drafting.kt:281`의 DraftMark와 기존 테마 T/Space/Radius 재사용. 같은 Material Rounded 계열의 속도·알림 아이콘만 추가.
- `ui/component/Pickers.kt:126`의 PickerRow는 아이콘과 오른쪽 요약 배치를 지원하지 않아 그대로 쓰지 않음.
- `feature/macro/MacroListScreen.kt:245`의 탐색 행 패턴을 참고해 기능 목록에만 작은 FeatureRow 추가. 공통 선택창·설정 행은 변경하지 않음.

## 시각 검증
- `recordPaparazziDebug` / `verifyPaparazziDebug -PallowSnapshots=true`: FeaturesScreenshotTest·TabletPortraitScreenshotTest 28개 통과.
- 폰 360dp·글자 1/2배, 가로 태블릿·1.3배, 세로 태블릿·1/1.3배, 낮/밤을 렌더해 실제 이미지 비교.
- 기본 폰에서 다섯 기능이 한 화면에 표시됨. 준비 완료/권한 부족 상태 모두 요약과 상태 읽기 확인.
- 기존 목록 기준 이미지 4개 갱신, 준비 완료·세로 태블릿 기준 이미지 8개 추가.
- 검증 범위는 로컬 렌더·테스트·빌드. 이번 변경의 실기기 스모크는 별도 수행하지 않음.

## 빌드
- `./gradlew test :app:assembleRelease` 성공.
- app Debug: 523 통과, 14 제외
- app Release: 523 통과, 14 제외
- tesla-ble Debug: 30 통과, 0 제외
- tesla-ble Release: 30 통과, 0 제외
