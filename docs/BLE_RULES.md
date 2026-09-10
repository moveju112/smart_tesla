# BLE RULES (연결 · 등록 · 실차 사실)

코드로 알 수 없는 실차/프로토콜 지식. BLE 계층을 만지기 전에 읽는다.

## 연결 정책

- **[MUST]** 차량 연결의 정본은 **저장된 MAC 직행 + `connectGatt(autoConnect=true)`**. 스캔은 보조 수단이다
  - why: 개발자 실기기(삼성 폰)에서 앱 스캔이 차 광고를 아예 못 받음 — nRF Connect/TePilot은 잡는데 우리 스캔 콜백엔 안 옴(미해결). autoConnect=true는 OS가 기기 출현 순간 붙여줘서 이 문제를 우회함. 2025 Model Y 실차에서 이 방식만 재현 성공
  - ✅ `app/src/main/java/com/wemade/teslamacro/data/gateway/BleVehicleGateway.kt:156` (direct), `:179` (saved)
- **[NEVER]** ScanFilter에 serviceUuid와 deviceName을 함께 걸지 않는다 — 아예 필터 없이 스캔하고 코드에서 거른다
  - why: UUID는 ADV 패킷, 이름은 SCAN_RSP 패킷에 나뉘어 옴. 한 필터에 묶으면 한 패킷 안 동시 매칭을 요구해 영영 못 잡음
  - ✅ `tesla-ble/src/main/java/com/wemade/teslable/TeslaBleScanner.kt` `startScan(emptyList(), ...)`
- **[MUST]** 테파일럿처럼 한 번에 스캔 하나만 연다: 무필터·BALANCED로 legacy 7.5초 후 extended 7.5초
  - why: 같은 갤럭시에서 테파일럿은 발견했지만 기존 3중 동시 스캔은 결과가 0건이었다. 이식 후 실차 재확인 필요 (`TeslaBleScanner.kt`)
- **[MUST]** Android 12+에서도 `BLUETOOTH_SCAN`·`BLUETOOTH_CONNECT`와 정확한·대략적 위치를 런타임에 함께 요청한다
  - why: `neverForLocation`을 쓰지 않아 위치 권한 선언만으로는 스캔 결과가 보장되지 않는다. 테파일럿도 네 권한을 함께 요청하며, Smart Tesla는 위치를 선언만 하고 12+에서 요청하지 않아 동일 스캔 설정으로도 0건이었다
  - ✅ `app/src/main/java/com/wemade/teslamacro/MainActivity.kt` `runtimePermissionsFor()`
- 광고 이름 후보: `"S" + SHA1(VIN)[:8].hex + [C/D/R/P]` (18자) — `tesla-ble/src/main/java/com/wemade/teslable/TeslaBleSpec.kt`
- **[NEVER]** 페어링 목록의 `Tesla Model …` 주소로 키 연결을 시도하지 않는다. 110B/110E/111E UUID는 음악·통화용 클래식 BT다
- 차량 닉네임(대소문자 그대로)은 `getBondedDevices()`에서 온다 — 스캔 불필요, BLUETOOTH_CONNECT만 필요 (`TeslaBleScanner.kt:59`)
- **[MUST]** 사용 모드별로 인증 GATT 수명을 가른다 (`StatePoller.decideVehicleConnection`)
  - 휴대 모드: 앱 화면·직접 명령이 연결 사유다. 안심운전 자동 실행이 켜져 있으면 전원 상승 뒤
    최대 60초 동안 VCSEC 탑승 상태만 확인한다(연결 최대 2회, 확인 간격 2초).
    첫 미탑승·미확인 응답이나 일시 오류 뒤에도 남은 시간 안에서 확인하고,
    탑승 확인·시간 만료·전원 해제·사용자 연결 해제 시 다시 끊는다.
    그 외 충전 전원·자동 매크로·보호 해제 저장값은 무시한다
  - 거치 모드: 자동 안심운전이 켜져 있으면 전원 상승 뒤 60초 확인 창 안에서 착석을 재확인한다
    (연결 최대 2회, 목표 확인 간격 2초). 첫 미탑승·미확인으로 종료하지 않으며 기존 매크로 조회는 유지한다.
    위치·예보 조회 뒤에도 만료·전원 해제를 재검사해 늦은 탑승 응답을 버린다.
    동기 예보 HTTP가 진행 중이면 실제 연결 해제는 해당 IO 반환까지 늦어질 수 있다.
    자동 안심운전이 꺼져 있으면 기존처럼 첫 VCSEC 확인으로 끝낸다.
    확인 종료 후에는 탑승이 확인된 동안만 전원을 연결 사유로 쓴다.
    빈 차는 앱 전면·직접 명령·실행 중 매크로·스텔스 충전 1회가 없으면 연결을 끊는다.
    스텔스 충전 1회는 대기·실행·전류 원복 동안만 보호를 미루고 끝나면 기존 설정으로 돌아간다
  - `지금 연결 끊기`는 실행 중 매크로를 멈추고, 다음 앱 열기·직접 명령·차량 전원 상승까지 재연결을 막는다
  - why: 앱 키가 빈 차에 계속 연결된 상태와 공식 휴대폰 키의 이탈 잠금 실패가 함께 관찰됐다.
    인과관계는 아직 실차 A/B 미확인이므로 직접 잠금 명령 버그로 단정하지 않는다.
- **[MUST]** 보호 모드에서 10분 이상 전원이 끊겼다 복귀하면 첫 신선한 VCSEC 응답을 새 탑승
  세션으로 판정한다. 10분 미만은 기존 세션을 이어가 탑승 중 안심운전 재실행을 막는다.

## 도메인 · 요청 규율

- 도메인 2개: **VCSEC**(잠금/개폐 — 차가 자는 중에도 동작) / **INFOTAINMENT**(공조/시트 — 차가 깨어 있어야) — `tesla-ble/src/main/java/com/wemade/teslable/TeslaClient.kt:100`
- **[NEVER]** BLE 요청을 병렬로 보내지 않는다 — requestLock 직렬화가 응답 매칭의 유일한 안전장치
  - why: VCSEC 응답엔 request_uuid가 비어 옴. `matchesRequest`가 빈 uuid를 무조건 매칭으로 처리하므로(`TeslaClient.kt:271`), in-flight 요청이 둘이면 응답이 뒤바뀐다
- **[NEVER]** 프로토콜 정답 벡터(루트 `ARCHITECTURE.md` 고정 목록)와 `ProtocolVectorTest.kt` 기대값을 바꾸지 않는다
  - why: 테슬라 공식 규격 벡터. 틀리면 코드가 틀린 것

## 등록(카드키) 흐름

- **[MUST]** 등록 완료 판정은 "요청 전송 성공"이 아니라 **카드 태그 후 VCSEC 핸드셰이크 성공**(verifyEnrollment 폴링, 3초×30회=90초 창)
  - why: 전송 성공을 완료로 치면 태그 안내 전에 화면이 넘어가는 버그가 실제로 있었음
  - ✅ `app/src/main/java/com/wemade/teslamacro/feature/pairing/PairingViewModel.kt:227`
- **[MUST]** VIN 저장(isPaired)과 키 등록 완료(isEnrolled)는 별개 플래그 — VIN은 등록 첫 단계에서 저장된다
  - ✅ `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt:29`

## 실차 사실 (2025 Model Y Juniper 검증)

| 항목 | 상태 |
|---|---|
| 직행 연결 + 카드키 등록 + 시트 제어(INFOTAINMENT) | ✅ 실차 확인 |
| 온도/배터리 읽기 값 표시 | ⚠️ 0.3.9 수정(matchesRequest 완화 + 접속 시 전체 읽기) 후 실차 미확인 |
| 앱 자체 스캔으로 차 발견 | ❌ 미해결 — 직행 연결로 우회 중 |
| 좀비 GATT 워치독(3연속 전멸 시 강제 재연결, 0.8.2) | ⚠️ 실차 미확인 — 밤샘 후 아침 탑승 시나리오로 검증 필요 |
| 거치 모드 전원 상승 후 최대 60초 착석 재확인(0.9.33) | ⚠️ 실차 미확인 — 앱을 열지 않은 상태에서 늦은 착석·네이버 자동 안내 확인 필요 |
| 사용 모드별 휴대폰 키 간섭 방지(빈 차 확인 후 GATT 종료) | ⚠️ 실차 미확인 — 거치/휴대 모드 설정 후 이탈 잠금 A/B 필요 |

- BLE 전용이므로 **차 근처(~수십 m)에서만** 동작. Fleet API는 안 쓴다(정책). 원격이 필요해지면 차내 태블릿 릴레이 방식으로 간다
- 좌석 통풍/열선 상태는 차에서 읽을 수 없다 → `SeatStore`에 클라 저장, 통풍/열선은 상호배타 토글(반대 모드 끄는 명령 동시 전송), 운전석·동승석은 통합 컨트롤 — `app/src/main/java/com/wemade/teslamacro/feature/dashboard/DashboardViewModel.kt:152`

## 민감정보

- **[NEVER]** 사용자 실 VIN을 코드·테스트·문서·로그 샘플에 넣지 않는다
  - why: VIN은 차량 식별 개인정보. 저장은 기기 내 DataStore에만
  - ✅ 테스트 더미는 `5YJS0000000000000` (`tesla-ble/src/test/java/com/wemade/teslable/TeslaBleSpecTest.kt:15`)
