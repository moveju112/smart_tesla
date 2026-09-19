# 스텔스 충전 단위 테스트

## 요약

스텔스 충전 간격·전류 범위, 최대 전류 저장 범위, 남은 시간 표시의 단위 테스트 8건이 모두 통과했다.

<!-- UNIT_COUNTS PASS=8 FAIL=0 SKIP=0 -->

## 대상

- `app/src/main/java/com/wemade/teslamacro/data/charge/StealthChargeController.kt:138` 사용자·차량 전류 상한 적용.
- `app/src/main/java/com/wemade/teslamacro/data/charge/StealthChargeController.kt:165` 다음 전환 남은 시간 발행.
- `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt:94` 최대 전류 기본값.
- `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt:239` 최대 전류 저장 범위 제한.
- `app/src/main/java/com/wemade/teslamacro/feature/settings/SettingsScreen.kt:329` 남은 시간 문구 변환.
- `app/src/main/java/com/wemade/teslamacro/service/MacroService.kt:560` 활성 충전 중 CPU wake lock 유지.

## 케이스 표

| type | # | angle | input | expected | actual | verdict |
|---|---:|---|---|---|---|---|
| UNIT | 1 | happy path | 16A 상한, 난수 500회 | 12~16A | 모두 12~16A | PASS |
| UNIT | 2 | ordering·determinism | seed 7, 500회 | 평균 14A 이상·3값 이상 | 조건 충족 | PASS |
| UNIT | 3 | boundary | 차량 상한 10A | 8~10A | 모두 8~10A | PASS |
| UNIT | 4 | boundary | 랜덤 간격 200회 | 60~300초 | 모두 60~300초 | PASS |
| UNIT | 5 | boundary | 자정 교차·종일 시간대 | 기대 구간과 일치 | 5개 판정 일치 | PASS |
| UNIT | 6 | boundary | 최소·최대 5A | 5A | 5A | PASS |
| UNIT | 7 | empty·boundary | 기본값, 3A, 60A 저장 | 48A, 5A, 48A | 48A, 5A, 48A | PASS |
| UNIT | 8 | boundary·error path | 134초, 59초, -1초 | 2분 14초, 59초, 0초 | 기대값과 일치 | PASS |

## 실패 상세

실패 없음.

## 미커버 위험

화면 잠금 중 실제 Tesla BLE 연결과 Android 제조사별 절전 정책은 로컬 단위 테스트로 검증할 수 없다.

활성 실행 상태와 wake lock 연결은 정적 검사와 빌드로 확인했으며, 실차 진단 로그 검증은 미확인 상태다.

Android Lint는 이번 변경과 무관한 기존 오류 4건 때문에 전체 작업이 실패했다.

이번에 추가한 wake lock 경고와 timeout 경고는 수정 후 Lint 결과에서 사라졌다.

## 지원 검사

- STATIC: Impeccable detector에서 변경 화면 이슈 없음.
- STATIC: `git diff --check` 통과.
- STATIC: Android Lint 기존 오류 4건, 이번 변경 관련 wake lock 경고 0건.
- BUILD: `./gradlew test verifyPaparazziDebug :app:assembleDebug` 통과.
- BUILD: 휴대 낮·밤, 가로, 글자 확대 Paparazzi 기준 이미지 검증 통과.

## 환경

- 날짜: 2026-09-19.
- Java: OpenJDK 17.0.20.
- 집중 테스트: `./gradlew :app:testDebugUnitTest --tests 'com.wemade.teslamacro.data.settings.StealthChargeSettingsTest' --tests 'com.wemade.teslamacro.feature.settings.StealthChargeDisplayTest' --tests 'com.wemade.teslamacro.data.charge.StealthChargePlanTest' --console=plain`.
- 전체 테스트 소유 명령: `./gradlew test verifyPaparazziDebug :app:assembleDebug --console=plain`.
