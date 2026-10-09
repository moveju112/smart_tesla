# 목적지 수신 복구 검증 — 2026-10-09

앱 0.9.185 / versionCode 298. 아래 2026-10-03 기록은 당시 검증이며 이번 작업은 원격 변경 없이 수행했다.

## 수정 범위

- 휴대·거치 수신함 조회와 착석 실행 조건 분리. 대기 목적지에만 차체를 최대 20초 확인하고 기존 BLE 연결 보호로 복귀. 수동 해제 유지.
- 실제 관측 시작 시각을 전달하고 잠금 인증·실험 종료 후 착석·유효시간 재검증.
- 기존 실험의 종료 응답과 준비 서버의 실행 상태 확인 후 새 실행. 인증 화면을 거치는 경로에도 같은 순서 적용. 차량 오디오 세션이 끝나면 전환 소유권 해제.
- EMPTY / WAITING_FOR_CONDITIONS / DISPATCHED / FAILED / UNKNOWN 분리. 서버 조회 결과가 없으면 탑승 8초 이후 목적지 없는 안심운전 허용. 명확한 전달 전 실패만 최대 두 번, 탑승 요청은 90초 유지.
- claim 전 기록과 실행 직전 기록 구분. 완료 응답 유실은 재동기화만 수행. 불명확한 전달은 새 요청을 막고 수신기의 상태 새로고침에서 사용자 확인으로 완료하며 이전 요청은 재실행하지 않음.
- 네트워크 검증 상실과 회복을 구별하고 회복 때 재시도 대기 초기화. 요청 ID의 짧은 식별자와 단계별 진단 로그 추가.
- 로컬 배포용 릴레이·검색 테스트를 읽기 전용으로 확인한 운영 소스에 동기화. selfTest 없는 현재 JSON과 검색어 목적지를 지원.

## 관측한 검증

- 필수 `./gradlew test :app:assembleRelease` 성공. 후속 종료 상태·네트워크 복구·기록 직렬화 보완 후 영향 범위 `:app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleRelease` 재검증 성공.
- 앱 debug/release 각각 582개 중 569개 통과, 기존 13개 기본 제외. BLE debug/release 각각 30개 통과. 실패 0개.
- DestinationReceiverTest 25개, PortableBoardingPollTest 29개 실행/13개 제외, DestinationNavigationTransitionTest 8개 통과.
- 휴대 모드 착석 확인 후 40초·90초 경과, 짧은 재연결과 해제, 수동 연결 해제, 연결 시간 초과를 실제 StatePoller와 테스트 Gateway로 실행.
- 인증 대기 후 재확인, 종료 정리 완료 전 대기/종료 실패/15초 시간 초과, 오프라인 대기 예산, 제한 재시도, claim·complete 응답 유실, 불명확한 기록과 구버전 JSON 복구를 검증.
- Python 릴레이 27개 모두 통과. GitHub osrm main의 패치 전 기준 원본을 일시 준비해 로컬 HTTP 서명 인증·이름만 있는 send/inbox/claim/complete·저장소 재생성을 실행. 실제 운영 API에 쓰기 요청 없음. 임시 원본·생성 파일은 검증 후 제거.
- `git diff --check` 성공. 화면 캡처·실기기 설치·실차 재현은 수행하지 않음.

## 재사용과 제외

- StatePoller.kt:728,1134의 차체 조회와 기존 연결 정책, NaverNavigator.kt:105의 실행 mutex·잠금 인증·백그라운드 전달을 사용.
- WirelessNavigation.kt:591의 NonCancellable 종료 정리와 DestinationTransfer.kt:122,133의 noBackup AtomicFile을 확장. 별도 실행·인증·저장소 구현을 만들지 않음.
- 상시 BLE 유지, 착석 유효시간 확대, 상시 WakeLock, 신규 푸시 인프라, 공통 HTTP 타임아웃 변경은 근거 부족·불필요한 범위 확대로 제외.
- 운영 목적지 API는 현재 앱 계약을 이미 지원해 원격 파일·서비스·DB를 변경하지 않음. osrm main과 운영 소스의 차이는 남아 있으므로 운영 저장소 정식 반영은 별도 승인 범위.
- 실제 제조사 절전·Android 화면 표시·차량 BLE·안내 시작 여부는 미확인. 실차 확인은 설정 → 기기 → 진단 로그 → 공유.

---

# 목적지 전송 검증 — 2026-10-03

로컬 구현·검증과 오라클 도쿄 서버 적용 완료. 앱 릴리스 대상은 v0.9.127이다.
앱 버전 0.9.127(240), arm64 릴리스 APK 생성.

## 관측한 결과

- 서버 목적지 저장소·HTTP 통합: 15개 통과. 재시작/만료 경계/교체/취소/동시 인계/기기 격리/서명 인증/폰 1대 수신함 격리.
- 기존 서버 원본에 패치 적용 후 ValidationTests/HTTPTests/DeviceAuthTests: 23개 통과. 운영 데이터·실제 토큰 사용 없음.
- `./gradlew test :app:assembleRelease`: 최종 소스에서 성공.
- 앱 debug/release 각각 523개 중 509개 실행 성공, 14개 렌더링 관련 기본 제외. BLE debug/release 각각 30개 성공.
- `DestinationReceiverTest` 10개: 동시 수신 1회, 완료 응답 유실·재생성, 결과 불명확 인계, 잠금 대기 중 만료/하차, 취소 경합, 지도 실행 실패, 테스트 격리, 착석 신선도, 서버 시계 기준.
- `recordPaparazziDebug`·`verifyPaparazziDebug`: 목적지 화면 16개 통과. 폰/태블릿, 낮/밤, 글자 1.3배, 빈 상태/수신 대기/설정/하단 테스트 오류.
- 실제 PNG 내부 열람. 별도 finish reviewer가 하단 오류 안내 1건을 지적해 고정 Snackbar로 수정했고, 같은 16개 재검토에서 해당 수정 resolved/ship.
- 기존 디자인 문서 대조: 일반 확장으로 문서 유지. DESIGN.md의 옛 스냅샷 금지 문구는 현재 AGENTS 정책보다 우선하지 않으며 무관 문서를 수정하지 않음.
- `git diff --check` 성공.

## 재사용 지점

- `data/safety/DeviceApiClient.kt:33`: 기존 RoadMatcher 인증·HTTPS 전송 추출, 기존 인증 회귀 테스트 통과.
- `data/nav/NaverNavigator.kt:103`: 기존 지도 실행 mutex·잠금 해제·실행 경로와 최종 인계 연결.
- `data/nav/NaverNavigator.kt:131`: 기존 Android Geocoder로 다중 후보 검색.
- `data/poll/StatePoller.kt:491`: 신선한 착석 응답 재사용.
- `ui/component/Primitives.kt:61`, `:173`: 기존 버튼·카드 재사용.

## 확인 한계·배포 경계

폰/태블릿 실기기, BLE 착석 복귀, 제조사 절전 복구, 네이버지도 실제 표시 여부는 아직 확인하지 않았다.
서버 적용 완료 후 폰 한 대 버튼으로 사용자 기기에서 확인할 수 있다.
이전 조회 대상 `choondoggy`는 구 서버였다. 사용자 정정 후 `oracle_tokyo`에서 다시 확인했다.
현재 운영 서버는 `/home/ubuntu/project/osrm`, gps-map.service active/running·enabled, 127.0.0.1:8091 리스너와 nginx의 gps-map.choondoggy.com 프록시가 정상 설정돼 있다.
앱 공개 URL은 바뀌지 않아 APK 수정·재빌드는 필요하지 않다. 도쿄 서버 원본으로 패치를 다시 생성하고 도쿄 원본을 사용한 목적지 테스트 15개·기존 회귀 23개를 다시 통과했다.
공개 `/v1/session`에 빈 인증 자료를 보낸 요청은 예상한 HTTP 401을 반환했다. 변경한 도로 축 도구의 `--help` 로컬 실행과 `git diff --check`도 통과했다.
사용자가 도쿄 서버 대상 패치·목적지 DB 생성·서비스 재시작·nginx 반영을 승인했고 해당 범위를 적용했다.
기존 규칙·문서의 사용자 변경은 보존하고, 이번 요청 범위인 서버 대상 참조만 AI_RULES.md(AGENTS/CLAUDE의 정본)·주행 재현 문서·도로 축 도구에서 교정했다. 기존 규칙 재구성 변경은 릴리스 커밋에 섞지 않으며 작업 파일만 포함한다.

로컬 서버 원본·변환본·임시 로그는 검증 후 제거한다. 재현 방법과 필요한 읽기 전용 원본 경로는 README에 기재했다.

## 운영 적용 결과

- 백업: `oracle_tokyo:/home/ubuntu/project/osrm/backups/destination-relay-20261003`.
- 패치 dry-run·Python 문법·nginx -t 통과 후 systemd daemon-reload, gps-map restart, nginx reload 완료.
- gps-map과 nginx 모두 active. gps-map enabled 유지. 목적지 DB 0600 mapmatch:mapmatch, 전용 디렉터리 0700.
- 공개 HTTPS에서 실제 P256 공개키 등록·인증서/nonce 서명·세션 발급을 거쳐 폰 한 대 수신과 별도 수신 기기 연결을 확인.
- 전송/인계/완료 상태 조회, 목적지 교체/취소, 다른 기기 인계 404, 중복 인계 409, 가입 토큰 직접 접근 401 확인.
- 기존 `/v1/match`는 도로 표본에서 HTTP 200 / matched 반환. 첫 짧은 표본은 정상적인 failed 응답으로 보수적 미매칭 처리됨.
- 일회용 테스트 기기 3개에 속한 연결·목적지 행만 정리. `PRAGMA integrity_check` 결과 ok.
- 최초 Python 기본 User-Agent 요청은 Cloudflare 403. Android User-Agent로 원인을 구분해 검증했고 앱·WAF 설정은 변경하지 않음.
- 인증 키·빌드 토큰·기기 인증서 원문은 로그·파일·커밋에 남기지 않음.
- APK SHA-256: `bd0afcb95d57166e57acd315fd4b696d7b5e8dca1615d0c445492db24bfb120d`.
