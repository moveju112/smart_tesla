# Fleet 서버 대기열 계약과 앱 연결

## 현재 상태

- 서버: `https://tesla.choondoggy.com`, 사용자별 Bearer API 토큰.
- 서버 실접속·실차 제어·토큰 파일 조회는 하지 않았다.
- `AppContainer`가 `FleetQueuedClient` / `FleetHttpsTransport` / `FleetTokenStore`를 조립하고 `MacroService`의 음성/바로가기 단일 명령에 연결한다.
- 설정 → 자동화 → Fleet API에서 사용자 API 토큰을 입력·암호화 저장·삭제할 수 있다. 연결 확인은 GET 차량 목록만 조회하며 실차 명령이나 깨우기를 보내지 않는다.
- 앱에 이미 등록된 VIN을 사용한다. 연결 확인은 해당 차량의 접근 가능 여부만 확인하며 BLE 페어링의 VIN/MAC을 변경하지 않는다.
- 토큰 저장 후 Fleet API를 켜면 다음 음성/바로가기 요청부터 추가 확인 없이 전송한다. 토큰 삭제는 Fleet를 끄지만 이미 접수된 명령을 취소하지 않는다.
- 같은 작업공간에서 다른 안전운전 작업이 설정·서비스·컨테이너·빌드 파일을 수정 중이다. 해당 변경은 건드리거나 릴리스에 섞지 않는다.

## 계약과 동작

- GET `/v1/vehicles`: 사용자 차량 목록.
- POST `/v1/vehicles/<VIN>/commands`: `type`, `parameters`, `expiresInSeconds`.
- `Idempotency-Key`: 새 실행마다 UUID. 현재 클라이언트는 POST 자체를 자동 재전송하지 않는다.
- 현재 지원: door_lock / auto_conditioning_start / auto_conditioning_stop / charge_start / charge_stop / set_temps / set_charge_limit.
- 0.9.62: 보닛 열기는 `actuate_trunk` + `which_trunk: front`, 뒤 트렁크 열기/닫기는 둘 다 `which_trunk: rear`로 인코딩한다.
- rear는 현재 차량 상태에 따른 작동이다. 사용자가 이 동작을 확인하고 양쪽 명령의 동일 매핑을 승인했다. 방향/무조건 닫기를 보장하지 않는다.
- 개폐의 `expiresInSeconds`는 남은 기한을 늘리지 않으면서 최대 15초로 제한한다.
- 문 잠금 해제·깨우기는 미지원으로 **토큰 조회·HTTP 이전에 차단**한다.
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

## 앱 연결

1. `AppContainer`에서 기존 저장소·전송기를 재사용한다. 토큰 읽기·쓰기는 IO 디스패처에서 처리한다.
2. 설정에서 토큰 입력/저장/삭제와 현재 등록 차량 연결 확인을 제공한다. 토큰 입력은 마스킹하고 제출 즉시 비운다. rememberSaveable·DataStore·백업·진단 덤프에는 넣지 않는다. 별도 Fleet 차량 선택은 제공하지 않는다.
3. `MacroService`는 현 서버 경로에서 기존 `FleetCommandClient`의 wake/online 루프를 **사용하지 않는다**. POST 뒤 클라이언트가 임의로 깨우거나 다른 전송으로 대체하지 않는다.
4. `beforeSubmit`에 기존 `QuickActionRequests`의 원자적 전송 진입 콜백을 연결한다. 그 전까지만 취소 가능하다.
5. POST 진입 중에는 취소를 막고, 접수 응답의 queued/running부터 **결과 확인 중단**을 제공한다. 202를 못 받았어도 취소 완료라고 쓰지 않는다. 서비스 외부 제한시간도 서버 명령 취소와 구별한다.
6. 결과 확인 중단 시 코루틴만 취소하고 원격 명령 취소 요청을 만들지 않는다. 알려진 명령 ID는 최근 요청의 메모리 상태에 보존한다. 앱 재시작 복원·사용자 수동 재조회 버튼은 아직 없으며 `refresh()`는 통신부 API로만 제공한다.
7. `CommandFeedback.confirmed`를 성공 콜백으로 재사용한다. 기존 BLE는 수정하지 않는다.

## 서버 개폐 계약 반영 및 후속 연결

- 확정된 `actuate_trunk`와 front/rear 매핑은 기존 `fleetCommandBody()`에 반영했다. 앞 트렁크 닫기는 지원하지 않는다.
- 토큰 입력 UI·AppContainer·MacroService 연결을 완료했다. 기존 wake 기반 `FleetCommandClient`는 실제 앱 경로에서 사용하지 않는다.
- 서버의 깨우기/취소/기한 보장 동작이 바뀌면 그 계약부터 반영한다. 깨우기 중 취소를 기존 UI 문구만으로 흉내 내지 않는다.
- 기존 BLE의 P단 확인 등 개폐 안전 조건을 서버에서도 보장하는지 확인한다.
- 동일 멱등키 재전송이 필요해지면 원래 키·본문을 함께 보존해 같은 요청으로 재전송한다. unknown 이후 새 키 자동 발급은 금지한다.

## 검증

- 오프라인 가짜 전송으로 기존 7명령 및 개폐 매핑·범위·queued/running/terminal·응답 유실·미지원 명령·TTL·취소·식별자 검증.
- 개폐별 front/rear 본문·15초 상한·열기/닫기 본문 동일·단일 POST/GET·성공 응답에서만 효과음 콜백을 검증한다.
- JVM AES-GCM 왕복·무작위 IV·변조·다른 키 거부 테스트.
- 기존 `CommandDeadline`과 `VehicleCommand` 재사용. `OpenMeteoClient.httpGet`은 인증/POST/취소/리다이렉트 제어가 없어 그대로 재사용하지 않았다.
- 테스트: `./gradlew :app:testDebugUnitTest --tests '*Fleet*Test' --tests '*QuickActionRequestsTest'`.
- `FleetTokenStoreTest`: 암호문 파일 재생성·교체·삭제·입력 검증·변조 실패. 테스트 키를 사용하며 실제 Android Keystore 검증을 대체하지 않는다.
- `FleetQuickActionFlowTest`: 큐 클라이언트와 실제 요청 추적기를 연결해 전송 전 취소·접수 후 관찰 중단·ID 보존·중단 후 효과음 없음·전송 중 취소 차단을 검증한다.
- 0.9.61 격리 소스 전체 검증: app debug/release 각각 430개, BLE debug/release 각각 30개, 실패 0. 신규 Fleet 테스트 17개 포함.
- `test verifyPaparazziDebug :app:assembleRelease` 및 lintVital 통과. UI는 변경하지 않았다.
- 앱 연결 변경의 격리 검증: app debug/release 각각 450개, BLE debug/release 각각 30개, 실패 0. 전체 `test verifyPaparazziDebug :app:assembleRelease`와 lintVital 통과.
- Fleet 입력/저장/관찰 중단 화면의 휴대폰 낮·밤/태블릿 큰 글자 기준 이미지 12개를 추가하고 기존 Fleet 기준 이미지 4개만 갱신했다. 자동 회귀 검증이며 실제 화면 육안 확인은 하지 않았다.
- 사용자가 오프라인 단속 안내 작업과의 통합 릴리스를 승인했다. 0.9.63/176 통합 소스에서 app debug/release 각 460개·BLE debug/release 각 30개, 전체 Paparazzi 회귀·lintVital·release 빌드가 통과했다.
- 버전 충돌은 해소되었으며 사용자가 두 기능의 통합 배포를 명시적으로 승인했다. 공공데이터 이용조건 조회 결과와 남은 불확실성은 `OFFLINE_SAFETY.md`에 기록한다.
- 실서버·실차·Android Keystore 단말 동작·스피커 출력은 검증하지 않았다.
