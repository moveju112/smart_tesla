# PITFALLS (증상 → 원인 → 처방)

## BLE

- **Symptom:** 앱 스캔에 차가 안 잡힘 — nRF Connect/TePilot에서는 바로 보임
  - Cause: 3중 동시 스캔을 단일 스캔으로 바꾼 뒤에도 0건. 테파일럿은 Android 12+에서 BLE 두 권한과 위치 두 권한을 함께 요청하지만 Smart Tesla는 위치를 manifest에 선언만 하고 런타임 요청에서 빠뜨림
  - Fix: 테파일럿과 같은 네 권한을 함께 요청하고 단일 무필터 BALANCED 스캔을 legacy → extended 순서로 실행. 이식 후 실차 재확인 필요 — [BLE_RULES.md](BLE_RULES.md) 연결 정책

- **Symptom:** 스캔 0대 뒤 `Expected at least one element matching the predicate`로 연결 실패
  - Cause: 빈 스캔 Flow가 정상 종료됐는데 `first {}`가 예외를 던져 후보 없음 처리까지 못 감
  - Fix: `firstOrNull {}`로 받아 후보 0대 안내와 정상 실패 흐름을 계속 탄다 — `BleVehicleGateway.kt`

- **Symptom:** VCSEC 명령(잠금 등)이 계속 "응답 시간 초과"
  - Cause: VCSEC 응답에 request_uuid가 비어 와서 엄격 매칭이 전부 버림
  - Fix: `tesla-ble/src/main/java/com/wemade/teslable/TeslaClient.kt:271` 빈 uuid 허용 폴백 유지 + 요청 직렬화(requestLock) 절대 깨지 말 것

- **Symptom:** 등록 실패했는데 설정 화면에 VIN이 남아 있음
  - Cause: VIN 저장을 등록 완료로 오인 — VIN은 첫 단계에서 저장됨
  - Fix: isPaired(VIN)와 isEnrolled(키 등록) 분리 유지 — `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt:29`

- **Symptom:** 카드키 태그 안내가 안 뜨고 등록이 그냥 넘어감
  - Cause: "등록 요청 전송 성공"을 완료로 처리 (과거 실버그)
  - Fix: verifyEnrollment 폴링으로 태그 대기 — `app/src/main/java/com/wemade/teslamacro/feature/pairing/PairingViewModel.kt:227`

- **Symptom:** "GATT 끊김" 후 "읽기 실패: 연결되어 있지 않다"가 몇 분씩 반복되고 재연결을 안 함
  - Cause: 링크가 끊겨도 linkState가 Ready로 남아 폴러가 재연결 분기에 못 들어감
  - Fix: 읽기/전송 길목의 `ensureLinked()`가 링크 생사를 확인해 상태를 내린다 — `app/src/main/java/com/wemade/teslamacro/data/gateway/BleVehicleGateway.kt`

- **Symptom:** VIN 없이도 "연결 성공"이 떠서 등록이 통과됨
  - Cause: `useRealVehicle()` 미호출 — 시뮬레이터 게이트웨이가 가짜 성공 반환
  - Fix: 등록 시 실차 게이트웨이 교체 — `app/src/main/java/com/wemade/teslamacro/di/AppContainer.kt:74`

- **Symptom:** 아침에 타보니 데이터 갱신이 멈춰 있고 매크로도 침묵 — VIN 재등록해야 복구 (실차 2026-08-13)
  - Cause: 좀비 GATT — 밤새 BT 절전 후 GATT는 "연결됨"인데 차는 무응답. `ensureLinked`는 isConnected만 봐서 못 잡고, 재등록은 게이트웨이를 새로 만들어서 우연히 고쳐진 것
  - Fix: 읽기가 사이클 통째로 3연속 전멸하면 강제 disconnect → 재연결 — `app/src/main/java/com/wemade/teslamacro/data/poll/StatePoller.kt` 워치독 (0.8.2, 실차 미확인)

- **Symptom:** 페어링된 기기 목록의 테슬라 주소로 직접 연결하면 30초 타임아웃만 반복 (실차 확인 2026-08-13)
  - Cause: 블루투스 페어링 목록의 테슬라는 **음악·통화용 클래식 BT 주소**다 (uuids에 110B/111E 등 오디오 프로파일). 키 연결용 BLE(VCSEC) 주소는 별개이고 페어링 목록에 안 나온다
  - Fix: 기존 기기 앱의 설정 → 차량 → "BLE 주소 (키 연결용)"를 옮겨 적는다. 새 기기뿐이면 nRF Connect로 `S<VIN해시>C` 이름을 찾는다

## 빌드 / 테스트

- **Symptom:** `./gradlew test` 전부 통과인데 UI가 깨져서 나감
  - Cause: Paparazzi record/verify는 `test` 태스크에 포함되지 않음
  - Fix: UI 변경 시 `recordPaparazziDebug`(갱신) 또는 `verifyPaparazziDebug`(검증) 별도 실행 — [tasks/RELEASE_BUILD.md](tasks/RELEASE_BUILD.md)

- **Symptom:** combine에 flow를 하나 더 넣었더니 컴파일 에러
  - Cause: Kotlin combine 타입 안전 오버로드는 5-arg까지
  - Fix: 부수 상태를 private data class로 묶어 중첩 combine — `app/src/main/java/com/wemade/teslamacro/feature/dashboard/DashboardViewModel.kt:62`

- **Symptom:** 실기기에 APK가 설치 안 되거나 universal APK를 못 찾음
  - Cause: ABI split만 있고 universal 미생성 (Vosk 네이티브 크기)
  - Fix: `app-arm64-v8a-debug.apk`를 쓴다 — `app/build.gradle.kts:24`

## 런타임

- **Symptom:** 빅스비 루틴의 "앱을 열거나 앱 동작 바로 실행"에 `Smart Tesla 열기`만 보임
  - Cause: APK의 정적 바로가기는 삼성 루틴 목록에 앱 동작으로 수집되지 않음 (갤럭시 실기기 2026-09-02)
  - Fix: `MacroShortcutPublisher`가 저장 매크로를 런타임 동적 바로가기로 발행한다. 시스템 슬롯이 적으면 애프터블로우와 수동 매크로를 우선한다 (0.9.7, 실기기 미확인)

- **Symptom:** 빅스비 루틴에서 보닛 열기를 수동 실행해도 차량과 진단 로그가 모두 조용함
  - Cause: `Theme.NoDisplay`인 `QuickActionActivity`가 BLE 연결이 끝날 때까지 `finish()`를 미뤄 Android가 `onResume`에서 강제 종료함. 직접 명령은 수신·결과 로그도 없었음
  - Fix: 숨은 화면은 요청을 `MacroService`에 넘기고 즉시 종료한다. 서비스가 명시적 사용자 요청으로 연결·명령을 처리하며 수신/성공/실패를 모두 진단 로그에 남긴다

- **Symptom:** 빅스비 보닛 열기 요청은 수신됐지만 `기어=null`로 차단됨
  - Cause: 보닛 안전검사가 잠든 차량의 인포테인먼트에 곧바로 DRIVE 상태를 요청해, 차량을 깨우기도 전에 조회 실패를 P단 아님으로 처리함
  - Fix: 기존 `sendInfotainmentAwake()` 경로로 DRIVE 상태를 읽는다. 첫 조회가 실패하면 VCSEC으로 차량을 깨우고 재조회하며, 끝까지 P단을 확인하지 못했을 때만 차단한다

- **Symptom:** 음성 상시 대기를 켜도 서비스가 안 올라옴
  - Cause: 마이크 포그라운드 서비스는 앱이 화면에 떠 있을 때만 시작 가능 (Android 제약)
  - Fix: 앱 실행 중 토글 — `app/src/main/java/com/wemade/teslamacro/MainActivity.kt:130` 주석 참조

- **Symptom:** 온도/배터리가 "--"로 남음 (제어는 됨)
  - Cause: 0.3.9 이전엔 유휴 폴링이 VCSEC만 읽음 + 응답 매칭이 INFOTAINMENT 읽기를 버림
  - Fix: 접속 시 전체 읽기 + matchesRequest 완화로 수정됨. 실차 재발 시 DiagLog 덤프부터 — 실차 미확인 항목([BLE_RULES.md](BLE_RULES.md) 표)

- **Symptom:** 배터리·온도·시트가 실제 차량 값과 다르게 멈춰 있음 (예: 실제 92%인데 94% 표시)
  - Cause: 평상시 폴링이 VCSEC(차체)만 읽음 — 접속 때 한 번 읽은 값이 계속 표시됨
  - Fix: 탑승 중(isUserPresent)에는 CLIMATE·CHARGE도 갱신 — `app/src/main/java/com/wemade/teslamacro/data/poll/StatePoller.kt` 카테고리 선택. 빈 차는 여전히 VCSEC만 (차 재우기)

## 스텔스 충전

- **Symptom:** 스텔스 충전을 끄거나 충전이 멈췄는데 전류를 계속 흔듦 / 조건이 다시 참이 돼도 재개 안 함
  - Cause: `runLoop()`이 무한 `delay`에 갇혀 있어, 평범한 `collect`면 on/off 신호의 다음 값을 못 받는다
  - Fix: `collectLatest`로 수집해 조건이 false로 바뀌면 실행 중이던 `runLoop`을 취소 — `app/src/main/java/com/wemade/teslamacro/data/charge/StealthChargeController.kt:46`
- **Symptom:** 전류가 한쪽(상한 또는 하한)에 눌러앉거나 정확히 일정 주기로만 바뀜 → 위장 효과 없음
  - Cause: 평균 복원(MEAN_REVERSION)·간격 난수(randomInterval)를 건드려 파라미터가 깨짐
  - Fix: 다음 전류는 순수함수 `StealthChargePlan.next()`가 정한다 — 파라미터 변경 시 `StealthChargePlanTest`로 검증 (`app/src/main/java/com/wemade/teslamacro/data/charge/StealthChargePlan.kt`)

## 문서 드리프트

- **Symptom:** 루트 `ARCHITECTURE.md` 디자인 절(다크 4단계, #3E6AE1, 그림자 0)이 코드와 다름
  - Cause: 0.4.x 토스 라이트 리디자인이 문서에 미반영
  - Fix: 디자인은 `app/src/main/java/com/wemade/teslamacro/ui/theme/Color.kt`가 정본 — [CODING_RULES.md](CODING_RULES.md) UI 절

## 배경에서 다른 앱 화면이 안 뜬다 (지도 안내가 조용히 실패)

**증상**: 매크로가 정상 발동하고 진단 로그에 `네이버 지도 안내 실행 요청 → …`까지 찍히는데 지도가 안 뜬다.
앱을 한 번 열어보면 그때는 뜬다.

**원인**: 안드로이드 14부터 `SYSTEM_ALERT_WINDOW` 권한을 **가지고만 있는 것으로는** 배경 실행 예외가 안 열린다.
실제로 떠 있는 창이 있어야 한다. 권한만 믿고 `startActivity`를 부르면
**예외도 안 나고 그냥 무시된다** — `runCatching`이 성공으로 판정해 로그가 거짓말을 한다.

**증거 (0.8.31 실차)**: 앱을 최근에 켠 뒤(업데이트 설치 직후·설정 만진 직후)에는 떴고,
배경에 55분 있었을 때는 안 떴다. 포그라운드 유예 시간에만 통하고 있었다.

**0.8.32 대응의 한계 (0.9.5 실차)**: 1×1 투명 창은 `addView`가 성공해도 배경 화면 전환 예외를
안정적으로 열지 못했다. 저녁 로그에서 서비스·매크로·인텐트 요청이 모두 정상인데 화면만 안 떴다.

**대응 (0.9.6)**: `NaverNavigator.launchFromBackground()` — 실제 보이는 작은 안내 창을 올리고
500ms 뒤 인텐트를 던진다. 화면 전환 판정이 끝나도록 1초 더 유지한 뒤 내린다.
`startActivity` 성공은 화면 표시 증명이 아니므로 로그도 `안내 실행 요청`으로 정확히 적는다.

**교훈**: `startActivity`가 예외를 안 던졌다고 화면이 떴다는 뜻이 아니다.
배경 실행은 조용히 무시되는 게 기본값이다.
