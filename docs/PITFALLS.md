# PITFALLS (증상 → 원인 → 처방)

## BLE

- **Symptom:** 앱 스캔에 차가 안 잡힘 — nRF Connect/TePilot에서는 바로 보임
  - Cause: 일부 폰(개발자 삼성 실기기)에서 앱 스캔 콜백에 차 광고가 아예 안 옴. 원인 미해명
  - Fix: 스캔에 매달리지 말 것. 저장 MAC 직행 + autoConnect=true가 정본 — [BLE_RULES.md](BLE_RULES.md) 연결 정책

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
