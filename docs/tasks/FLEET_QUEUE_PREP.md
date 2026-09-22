# Fleet 서버 대기열 계약 사전 구현

## 현재 상태

- 서버: `https://tesla.choondoggy.com`, 사용자별 Bearer API 토큰.
- 서버 실접속·실차 제어·토큰 파일 조회는 하지 않았다.
- `FleetQueuedClient` / `FleetHttpsTransport` / `FleetTokenStore`는 구현했지만 **아직 AppContainer·설정 UI·MacroService에 연결하지 않았다**.
- 현재 앱의 Fleet 사용 토글은 기존 준비 중 상태를 유지한다. 새 코드를 넣었다고 실제 원격 명령이 활성화되지 않는다.
- 같은 작업공간에서 다른 안전운전 작업이 설정·서비스·컨테이너·빌드 파일을 수정 중이다. 해당 변경은 건드리거나 릴리스에 섞지 않는다.

## 계약과 동작

- GET `/v1/vehicles`: 사용자 차량 목록.
- POST `/v1/vehicles/<VIN>/commands`: `type`, `parameters`, `expiresInSeconds`.
- `Idempotency-Key`: 새 실행마다 UUID. 현재 클라이언트는 POST 자체를 자동 재전송하지 않는다.
- 현재 지원: door_lock / auto_conditioning_start / auto_conditioning_stop / charge_start / charge_stop / set_temps / set_charge_limit.
- 보닛·트렁크·문 잠금 해제·깨우기는 미지원으로 **토큰 조회·HTTP 이전에 차단**한다.
- POST 전에 기존 `CommandDeadline`의 남은 시간을 내림해 5~300초로 제한한다. 5초 미만은 연장하지 않고 거부한다.
- 서버 TTL은 서버 접수 시점부터의 상대 기한이다. 네트워크 전송 지연까지 포함한 앱 수신 시점의 엄밀한 절대 기한 보장에는 별도 서버 계약이 필요하다.
- 202는 queued/running뿐 아니라 기존 종료 결과일 수도 있다. 그대로 상태를 해석한다.
- GET `/v1/commands/<id>`만 1초 간격으로 조회한다. 최대 60초 관찰 후에도 미완료면 unknown이며 서버 expired와 구별한다.
- succeeded만 효과음 콜백을 한 번 호출한다. 조회만 다시 하는 `refresh()`는 효과음을 반복하지 않는다.
- 응답 유실·unknown·JSON/식별자 불일치는 결과 미확인이다. 새 명령을 만들지 않는다.
- 관찰 중단/타임아웃은 서버 명령 취소가 아니다. 원래 ID가 있다면 `refresh()`로 결과만 재조회할 수 있다.
- 리다이렉트 금지, 고정 HTTPS 주소/경로만 허용, 응답 크기 상한·연결/읽기 타임아웃·취소 시 disconnect.
- Bearer 토큰/응답 원문/차량 식별값을 오류 로그에 넣지 않는다.
- 토큰은 Android Keystore AES-GCM 키와 noBackupFilesDir의 원자적 암호문 저장을 사용한다. 실제 Keystore·단말 검증은 별도로 필요하다.

## 후속 앱 연결 지점

1. `AppContainer`에서 `FleetTokenStore`와 `FleetHttpsTransport`로 새 클라이언트를 조립한다. 토큰 읽기·쓰기는 IO 디스패처에서 처리한다.
2. 설정 화면에서 토큰 입력/삭제와 차량 조회·선택을 제공한다. 토큰은 rememberSaveable·DataStore·백업·진단 덤프에 넣지 않는다.
3. `MacroService`는 현 서버 경로에서 기존 `FleetCommandClient`의 wake/online 루프를 **사용하지 않는다**. POST 뒤 클라이언트가 임의로 깨우거나 다른 전송으로 대체하지 않는다.
4. `beforeSubmit`에 기존 `QuickActionRequests`의 원자적 전송 진입 콜백을 연결한다. 그 전까지만 취소 가능하다.
5. POST 진입 후 UI는 **결과 확인 중단**이어야 한다. 202를 못 받았어도 취소 완료라고 쓰지 않는다. 서비스 외부 제한시간도 서버 명령 취소와 구별한다.
6. 결과 확인 중단 시 코루틴만 취소하고 원격 명령 취소 요청을 만들지 않는다. 알려진 명령 ID를 UI 상태에 보존한다.
7. `CommandFeedback.confirmed`를 성공 콜백으로 재사용한다. 기존 BLE는 수정하지 않는다.

## 보닛·트렁크 서버 확장 수신 시

- 확정된 `type`/`parameters`/차량 모델별 지원을 `fleetCommandBody()`에 추가한다. 추정한 `actuate_trunk` 등의 이름은 넣지 않는다.
- 서버의 깨우기/취소/기한 보장 동작이 바뀌면 그 계약부터 반영한다. 깨우기 중 취소를 기존 UI 문구만으로 흉내 내지 않는다.
- 기존 BLE의 P단 확인 등 개폐 안전 조건을 서버에서도 보장하는지 확인한다.
- 동일 멱등키 재전송이 필요해지면 원래 키·본문을 함께 보존해 같은 요청으로 재전송한다. unknown 이후 새 키 자동 발급은 금지한다.

## 검증

- 오프라인 가짜 전송으로 7명령 매핑·범위·queued/running/terminal·응답 유실·미지원 개폐·TTL·취소·식별자 검증.
- JVM AES-GCM 왕복·무작위 IV·변조·다른 키 거부 테스트.
- 기존 `CommandDeadline`과 `VehicleCommand` 재사용. `OpenMeteoClient.httpGet`은 인증/POST/취소/리다이렉트 제어가 없어 그대로 재사용하지 않았다.
- 테스트: `./gradlew :app:testDebugUnitTest --tests '*FleetQueuedClientTest' --tests '*FleetTokenCipherTest'`.
- 0.9.61 격리 소스 전체 검증: app debug/release 각각 430개, BLE debug/release 각각 30개, 실패 0. 신규 Fleet 테스트 17개 포함.
- `test verifyPaparazziDebug :app:assembleRelease` 및 lintVital 통과. UI는 변경하지 않았다.
- 실서버·실차·Android Keystore 단말 동작·스피커 출력은 검증하지 않았다.
