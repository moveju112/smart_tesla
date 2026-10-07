# LTE 내비 복구·태블릿 목적지 수신 후속 수정

## 원인과 수정

- 휴대폰의 LTE 인터넷 연결과 로컬 ADB 연결은 별개다. 기존 Binder가 없어지면 무선 디버깅 포트 탐색에만 의존해 30초 후 실패했다. 기존 Binder → 현재 시스템 TCP 포트의 설치별 키 인증 → Wi-Fi 최초 준비 순으로 연결한다. 최초 무선 인증 뒤 `tcpip:<임시 빈 포트>` 전환과 인증 재접속을 확인한다.
- Binder 실행·심박·종료·목적지 전달은 Wi-Fi 디버깅 활성화를 요구하지 않는다. 인계 뒤 불확실한 결과에서 다른 경로로 재전송하지 않는 기존 계약을 보존한다.
- USB 디버깅과 인증 유효시간은 자동 변경하지 않는다. TCP 포트가 사라진 재부팅·디버깅 해제 상태에서는 Wi-Fi 준비를 안내한다. 접속 주소 `127.0.0.1`은 ADB 데몬의 외부 인터페이스 수신 제한을 의미하지 않는다. 기존 ADB 키 인증을 사용하며 인증 해제는 하지 않는다.
- 태블릿은 Bluetooth가 아직 꺼져 있으면 차량 접속을 시작하지 않았는데도 2회 예산을 소진했다. 해당 실패만 typed 오류로 구별해 예산을 돌려주고 원래 60초 마감 안에서 2초마다 확인한다. 실제 차량 실패 2회, 수동 해제·전원 해제·취소 규칙은 유지한다.
- 10월 8일 후속: 거치 모드·목적지 수신 켜짐·차량 전원 연결·수동 해제 아님 조건에서 확인 창 종료 후 최소 60초 휴지 뒤 자동 재확인한다. 각 창은 기존 최대 60초·실제 연결 2회 제한을 유지한다. 휴대 모드는 기존 정책을 유지한다.
- 사람이 운전석에 앉아 있어도 앱이 신선한 VCSEC 착석 응답을 못 받으면 수신 허가가 나지 않는다. 제공 로그에는 목적지 요청 자체의 기록이 없어 서버 종단 원인까지 확정하지 않는다. 착석 대기·인터넷 대기·수신 확인 상태 전환과 통신 예외를 진단 로그에 추가했다.

## 재사용

- `WirelessNavigation.manager`, `LocalAdbIdentity`, `LocalAdbDiscovery.port`, `NavigationBridgeProvider.request`, `NavigationChannel`: 기존 설치별 인증과 명령 통신 유지.
- 기존 `validPort` 검증 사용. NSD는 현재 TCP 데몬 포트를 제공하지 않으므로 `localAdbTcpPort` 파서만 추가.
- `VehiclePowerWakeCheck`, `StatePoller.sleep`, `PortableBoardingPollTest.Fixture`: 기존 마감·취소와 실제 폴러 테스트 재사용.

## 검증

- `PortableBoardingPollTest.destination*`: 신규 6개 실행 통과, skip 0. Bluetooth 지연/계속 꺼짐/실제 실패 2회/수동 해제/전원 해제/느린 연결 60초 확인.
- 10월 8일 회귀 5개 통과: 60초 이후 Bluetooth 복구, 실제 연결 실패 뒤 복구, 수신·전원 해제 중단, 수동 해제 중단, 미착석 응답의 수신 차단.
- `LocalAdbTcpPortTest`: 현재 포트 우선, 명시적 비활성 우선, 영구 포트 fallback, 범위·문자열 오류 2개 통과.
- 전체 `test :app:assembleRelease`: Debug·Release 각각 앱 562개 중 549개 통과·13개 기존 비활성 기능 skip, BLE 30개 통과. 합계 1,158회 통과, 26회 skip.

- API 34 일회용 read-only 에뮬레이터 `tcpOnly`: Wi-Fi와 무선 디버깅 off 상태에서 동일 Binder 재사용 → 현재 UID helper 종료 → TCP로 새 helper 생성 및 현재 버전/AVAILABLE 확인 → 네이버 대체 앱 실행/PING/STOP 통과.
- `unavailableOnly`: TCP 데몬과 Wi-Fi가 모두 없는 상태에서 8초 안에 준비 불가 안내, busy 해제, prepared=false 통과.
- 최초 pairOnly 계측은 에뮬레이터의 개발자 설정 비활성 컴포넌트로 두 번 시간 초과됐다. 일회용 환경을 보정한 뒤 실제 페어링 알림 입력까지 진행했지만 `tcpip` 데몬 재시작이 host 계측 연결을 끊어 최종 성공을 관측하지 못했다. 위 TCP 복구 테스트는 알려진 에뮬레이터 TCP 포트로 분리 실행했다.
- 에뮬레이터 `ro.adb.secure=0`이므로 잘못된 키 거부와 실제 제조사 기기의 최초 TLS→TCP 인증 전환 완료는 미확인이다. 제품의 설치별 RSA 키·`setThrowOnUnauthorised(true)`는 보존한다. 실차 목적지 수신·삼성 절전 복귀도 미확인이다.
- 최종 release APK 서명 검증과 `git diff --check` 통과. 이번 변경에 화면 캡처는 사용하지 않았다.

## 산출물

- `/home/ubuntu/SmartTesla-0.9.183-arm64.apk`, 5,997,308 bytes.
- SHA-256 `b17cc13b6fd4911743791b07b5a6ada5e90b0eaa98604af53d8721b6c37b9656`.
- 기존 감사 보고서의 APK 정보보다 이 후속 빌드가 최신이다.

## 배포 경계

기존 전수감사의 Lint `WrongConstant` 6건·`ProtectedPermissions` 1건은 미해결 상태다. 사용자의 “좋아 그러면” 응답으로 앞서 요청한 이 7건의 배포 예외를 승인받았다. 예외는 이 기존 진단에만 적용하며 suppression·baseline·검사 해제는 추가하지 않는다. 서버·물리 기기·DB·GitHub Actions 변경 없음.
