# 사용자 동작 점검 — 0.9.171

## 결과

확정 버그 5건 수정. 로컬 검증 PASS, 실차·실기기 검증 PARTIAL.
제품 의도(PRODUCT.md)와 활성 화면의 호출 경로를 기준으로 점검했다.
모든 기기·동작 조합의 무결함을 보장하는 전수 E2E 검증은 아니다.

| 문제와 재현 | 수정 |
|---|---|
| 월요일 23:59 예약을 화요일 00:01에 읽으면 누락하거나 화요일 밤 예약을 미리 실행 | MacroEngine의 소급 창 안 예약 발생일로 요일 판정 |
| 스텔스 충전으로 전류 변경 중 백업 복원 → 원래 전류 정보 삭제 | 실행은 끄되 이 기기의 원복 표식은 복구 완료까지 보존 |
| 스텔스 충전 OFF 직후 ON → 변경된 낮은 전류를 원래 값으로 오인 | 원복 대기 중 재활성화는 기존 기준 전류 유지 |
| 차량 등록 해제·변경 → 이전 차의 충전 예약·원복 상태가 새 차에 적용 가능 | VIN 변경 시 차량 종속 충전 상태 제거, 동일 VIN 재저장은 보존 |
| 무선 안심주행 종료 중 자동 실행 ON 또는 오디오 재연결 → 시작 요청 유실 | 취소 정리 완료 후 요청 1회 실행; OFF·하차·서비스 종료 시 폐기 |

## 코드·재사용 근거

- `app/src/main/java/com/wemade/teslamacro/domain/macro/MacroEngine.kt:165`: 기존 `crossedInWindow` 재사용. 호출은 StatePoller의 매크로 평가 경로.
- `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt:245`: 기존 `setVinInPreferences`에서 차량 종속 상태를 원자적으로 제거.
- 같은 파일 `:307`, `:441`: 기존 `setStealthCharging(false)`의 원복 대기 보존 규칙 적용. `StealthChargeController.handleGate`와 `stealthChargeAction`을 그대로 사용.
- `app/src/main/java/com/wemade/teslamacro/data/nav/WirelessNavigation.kt:304`: 기존 prepare/pair의 `invokeOnCompletion → scope.launch` 패턴 재사용. 요청 식별·취소만 작은 로컬 `NavigationRestartRequest`로 분리.

## 실행한 검증

`./gradlew test :app:assembleRelease` — BUILD SUCCESSFUL.
`git diff --check` — PASS.

| 대상 | Debug | Release |
|---|---:|---:|
| app | 499 통과 / 13 건너뜀 | 499 통과 / 13 건너뜀 |
| tesla-ble | 30 통과 | 30 통과 |

실패·오류 0. 중복 변형을 제외하면 529개 통과, 13개 건너뜀.
건너뜀은 기존 PortableBoardingPollTest의 비활성 `NAVIGATOR_SAFE_DRIVE` 조건에 따른 결과다.
BLE의 변경 없는 테스트는 Gradle의 기존 성공 결과를 재사용했다.

추가 회귀 테스트 12개:
- MacroEngineTest 3개: 7개 요일 경계·다음 날 예약 오발동·자정 정각.
- StealthChargeSettingsTest 5개: 복원 중 전류 보존·미변경 정리·빠른 재활성·동일 차량/등록 해제·새 차량 연결.
- NavigationRestartRequestTest 4개: 취소 정리 대기/중복 병합·불필요한 재시작 차단·요청 세대 분리·예약 콜백 취소.

관련 세 클래스 전체 50개 통과(Debug/Release 각각).
DataStore 상태 전이와 실제 코루틴 취소/완료 경계는 로컬 테스트에서 실행했다.
레이아웃 변경이 없어 스크린샷은 생성·검토하지 않았다.

## 남은 피드백·검증 한계

- 백업에는 MacroFolder가 없어 다른 기기로 복원하면 사용자 폴더 구성이 전달되지 않는다. 매크로 자체는 보존된다. `BackupFile.kt:23`, `SettingsViewModel.kt:320`, `:351`.
- 구형 DashboardViewModel은 좌석 명령 실패에도 먼저 저장한 표시값을 유지하지만, 현재 앱 호출 경로가 없어 이번 수정에서 제외했다. 화면을 다시 노출할 때 정리할 대상이다.
- 목적지 수신의 실행 직전 claim·만료/착석 재확인·불확실한 ADB 결과 중복 차단은 기존 구현과 로컬 테스트를 확인했다. 실제 서버·제조사별 기기 동작을 새로 검증한 것은 아니다.
- 실제 차량의 충전 전류 원복과 네이버지도 종료/재실행은 실차·실기기 미확인이다. 차량 등록 해제 전에 이미 전류가 바뀌었다면 이전 차량 전류를 자동 복구한다고 보장하지 않는다.
- 작업 시작 전부터 존재하던 문서·도구 변경은 이번 릴리스에서 제외했다.
