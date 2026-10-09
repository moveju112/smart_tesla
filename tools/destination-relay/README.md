# 목적지 전송 및 수신

폰: 설정 → 주행 → 차로 보내기 → 검색어 입력 → 유효시간(1~120분, 기본 10분) → 전송.
최신 목적지가 이전 대기를 교체한다. 서버 접수 시점부터 유효시간을 계산한다.
수신기: 기기 연결·수신 → 자동 받기 → 연결 코드 발급 → 보내는 폰에서 코드 입력. 휴대·거치 모드 모두 지원.
차량 등록과 실행 중인 Smart Tesla 서비스, 인터넷, 네이버지도, 다른 앱 위 표시 권한이 필요하다.
잠금이 있으면 기존 잠금 해제 화면을 거친다. 기기 강제 종료·제조사 실행 차단은 앱만으로 복구를 보장하지 않는다.

검색어 요청은 네이버 검색 화면을 열며 기존 좌표형 요청만 길안내 URI를 사용한다.
‘전달됨’은 Android에 네이버 실행 요청을 보냈다는 뜻이다. 네이버의 실제 경로 안내 시작 확인은 아니다.

## 수신 정책

- 신선한 차량 착석 응답(30초 이내) + 수신 켬 + 인터넷이 실행 조건이다.
- 전원·네트워크·잠금 해제·설정 변경·신선한 착석 응답은 재확인 계기다. 전원 연결 순서에 의존하지 않는다.
- 인터넷 연결 중 착석과 무관하게 기존 수신 루프에서 5초 주기로 조회한다. 통신 실패 시 10/20/40/80초로 늘리고 실제 네트워크 복구 때 초기화한다.
- 목적지가 있고 착석이 오래됐으면 차체만 최대 20초 재확인하고 기존 연결 보호 정책으로 복귀한다. 확인 실패는 30초 간격으로 제한한다. 수동 BLE 해제는 존중한다.
- 인터넷 없음은 복구 이벤트를 기다린다. 목적지 없는 안심운전은 조회 결과가 없으면 탑승 8초 이후 실행을 허용한다. 탑승 요청은 최대 90초 유지하고 명확한 전달 전 실패만 두 번까지 시도한다.
- 인계 직전에 서버가 취소·교체·만료를 다시 검사한다. 잠금 대기 중 하차·만료도 실행하지 않는다.
- 기존 실험 종료 확인 → 인증·착석·유효시간 재검증 → 로컬 기록 → 서버 원자적 claim → 실행 순서를 인증 여부와 무관하게 사용한다.
- 인계 후 결과 유실은 자동 재실행하지 않는다. 받는 기기의 ‘상태 새로고침’에서 실제 지도 상태를 확인해 이전 요청을 완료 처리한다.
- 사용자에게 성공한 취소를 표시한 요청은 나중에 실행되지 않는다. 이미 인계된 요청의 취소는 거절한다.
- 목적지 대기·전달·결과 불명확 상태는 기존 안심운전 자동 시작보다 우선한다. 레거시 selfTest=true는 거절한다.

## 계약 동기화 — 2026-10-09

앱 저장소의 릴레이 복사본과 검색 회귀 테스트를 읽기 전용으로 확인한 운영 소스에 맞췄다.
현재 앱의 selfTest 없는 JSON과 이름만 있는 목적지로 send/inbox/claim/complete를 로컬 HTTP에서 검증한다.
이번 작업은 원격 파일·서비스·DB를 변경하지 않았다. osrm main에는 목적지 경로가 아직 없어 이 저장소의 패치 기준 원본과 실제 운영 구현을 혼동하지 않는다.

## 서버 적용 완료 — 2026-10-03

대상: 오라클 도쿄, SSH `oracle_tokyo`(호스트명 `oracle-tokyo`), `/home/ubuntu/project/osrm`.
공개 API는 `https://gps-map.choondoggy.com` 그대로다. 도메인 이름으로 SSH 대상을 추정하지 않는다.
2026-10-03 도쿄 서버에서 `gps-map.service` active/running·enabled, `127.0.0.1:8091` 리스너와 nginx 프록시를 확인했다. 이전 `choondoggy`의 중지 상태는 운영 상태가 아니다.
기존 기기 서명 인증을 사용하는 `POST /v1/destinations`를 추가한다.
`destination_relay.py` 신규 파일과 `server.patch`를 적용한다. 패치는 검토한 원본에만 적용하며 새 인증 키는 만들지 않는다.

사용자 승인 후 다음 범위를 적용했다:
- 프로젝트의 `map_server.py`, `destination_relay.py`, `deploy/gps-map.service`, `deploy/nginx-gps-map.conf` 수정.
- `/etc/systemd/system/gps-map.service.d/destinations.conf` 신규 drop-in에 기존 설정을 보존하면서 `StateDirectory=gps-map-destinations`, `StateDirectoryMode=0700`, `MAP_DESTINATION_DB=/var/lib/gps-map-destinations/relay.sqlite3` 추가.
- 서비스 시작 시 별도 SQLite DB 및 스키마 생성. 기존 도로 매칭 데이터는 변경하지 않는다.
- `/etc/nginx/sites-available/gps-map-https.conf`의 허용 경로에 `destinations` 추가.
- 설정 검사 후 systemd daemon-reload, 실행 중인 gps-map 서비스 재시작, nginx reload. 이미 enabled이므로 자동 시작 설정 변경은 필요 없다. 재시작 동안 지도 매칭 API가 일시 중단될 수 있다.

지도 데이터는 MLD 부속 파일 묶음으로 존재한다. 단일 .osrm 파일 존재 여부만으로 유효성을 판정하지 않는다.
적용 전 설정을 재확인하고 `/home/ubuntu/project/osrm/backups/destination-relay-20261003`에 변경 전 파일을 백업했다.
패치 dry-run, Python 문법 검사, nginx -t가 통과했다.
공개 HTTPS에서 실제 기기 등록·서명 인증·전송·수신·교체·취소·다른 기기 차단·중복 인계 차단을 확인했다.
기존 지도 매칭 API도 도로 표본에서 HTTP 200 / matched를 반환했다. 서비스 active/enabled와 DB integrity_check=ok를 확인했고, 일회용 테스트 행만 제거했다.
Cloudflare가 Python 기본 User-Agent를 403으로 거절하므로 API 진단은 실제 앱과 같은 Android User-Agent를 사용했다. 앱 코드는 바꾸지 않았다.
문제 발생 시 백업 파일·기존 설정을 복원하고 서비스만 재시작한다. 생성된 DB를 자동 삭제하지 않는다.

DB는 기기 연결과 목적지를 보관한다. 요청은 생성 24시간 뒤 다음 API 요청에서 정리한다.
연결 정보는 연결 해제 또는 교체 전까지 유지한다. 로그에 목적지·연결 코드·기기 토큰을 기록하지 않는다.
기존 가입 토큰으로는 수신함 접근을 허용하지 않고 기기 서명이 검증된 세션만 허용한다.

## 로컬 확인

- `python3 -B -m unittest discover -s tools/destination-relay -p 'test_*.py' -v`
- HTTP 통합 검증에는 패치 전 기준 원본인 `moveju112/osrm` main의 map_server.py·device_auth.py·deploy/gps-map.service·deploy/nginx-gps-map.conf를 각각 `app/build/destination-relay-source/{map_server.py,device_auth.py,deploy-gps-map.service,deploy-nginx-gps-map.conf}`에 준비한다. 이미 패치된 운영 파일은 기준 원본으로 쓰지 않는다.
- `prepare_patch.py`는 이 원본을 확인해 로컬 패치와 테스트용 변환 파일만 생성한다. 서버에 접속하지 않는다.
- 앱: `DestinationReceiverTest`, 기존 `RoadDeviceIdentityTest`, `RoadMatcherTest`, `PortableBoardingPollTest`, `NavigatorAppTest`.
- 화면: `DestinationScreenshotTest`의 폰/태블릿·낮/밤·글자 1.3배, 빈 상태/대기/수신 설정/임시 버튼.

## 재사용 근거

- `RoadMatcher`의 인증·HTTPS 경로 → `DeviceApiClient`로 추출, 도로 매칭과 목적지에서 공유. 기존 인증 회귀 테스트 유지.
- `NaverNavigator`의 Geocoder·네이버 URI·실행 mutex·잠금 해제·백그라운드 실행 경로 재사용.
- `StatePoller.freshPresence`와 기존 거치 전원 확인 주기 재사용. 저장된 착석값만으로 실행하지 않음.
- `TCard`, `TButton`, `DraftField`, `DraftToggle`, `LocalPane`와 기존 Paparazzi 앱 프레임 재사용.
