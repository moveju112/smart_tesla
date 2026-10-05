# 네이버 안심주행 실험

설정 → 주행 → **네이버 안심주행 · 실험**. Android 12 이상, 네이버지도 설치·초기 설정·위치 및 음량 설정 필요.

1. Wi-Fi 연결 상태에서 개발자 옵션 → 무선 디버깅을 켠다.
2. 분할 화면으로 앱과 설정을 열고, ‘페어링 코드로 기기 페어링’에 나온 포트·6자리 코드를 입력한다.
3. 페어링이 끝나면 **무선 디버깅 첫 화면**의 연결 포트를 입력한다. 페어링 포트와 다르다.
4. 네이버지도의 기존 실행을 종료한 뒤 ‘10초 뒤 테스트’를 누르고 화면을 잠근다.
5. 실제 안내 음성을 확인하고 ‘종료’를 누른다. 자동 실행은 차량 오디오를 직접 선택한 후 별도로 켠다.

앱은 휴대폰 잠금을 해제하지 않는다. 셸 권한으로 별도 디스플레이 그룹에 가상 화면을 만들고 기존 네이버 navigation URI를 전달한다. 네이버 응답·음성 성공을 확인하는 API가 없으므로 실행 요청 완료를 음성 성공으로 표시하지 않는다. 화면은 표시용 버퍼만 소비하며 파일·영상으로 저장하지 않는다.

자동 실행은 인증 BLE나 Fleet 호출 없이 기존 A2DP 상태를 사용한다. 해제 30초 후 STOP 명령을 전송하고 완료 응답을 기다린다. 부모 연결이 끊겨도 EOF·15초 심박 만료로 정리한다. 시작 전 네이버 프로세스가 있으면 거절한다. 실험 중 직접 시작한 네이버 길안내도 종료 때 함께 강제 종료된다.

초기 페어링은 수동이다. 재부팅·Wi-Fi 변경으로 무선 디버깅이 꺼지거나 포트가 바뀌면 다시 설정해야 한다. 무선 디버깅 자동 켜기·끄기나 재부팅 후 복구는 구현하지 않았다. 제조사별 잠금 화면·음성·절전 동작 및 실제 네이버지도 호환성은 실기기 확인이 필요하다.

페어링 코드는 저장·로그 출력하지 않는다. RSA 키는 앱 전용 noBackupFilesDir에 저장하고 연결 호스트는 127.0.0.1로 고정한다. 자동 실행 선택은 일반 설정 백업에 포함하지 않는다. 의존성 고지는 APK의 assets/licenses/wireless-navigation.txt, LGPL 소스는 릴리스의 wireless-navigation-sources.zip에서 제공한다.

## 로컬 회귀 검증

- `./gradlew :app:testDebugUnitTest --tests '*LocalAdbIdentityTest' --tests '*RemovedSafetySettingsTest' --tests '*RoadDeviceIdentityTest'`
- 에뮬레이터 APK: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PhistorySmokeEmulator=true -PsmokeRunner=com.wemade.teslamacro.nav.WirelessNavigationSmokeInstrumentation`
- 지도 대체 APK: `python3 tools/nav-smoke/build_fixture.py`. 실제 네이버지도 대신 가상 화면 진입만 기록한다.
- **별도 설치·포트 변경 승인을 받은 읽기 전용 로컬 에뮬레이터에만** 대체 APK·앱·계측 APK를 설치하고 ADB 5555를 임시 사용한다. 실제 휴대폰에 대체 APK를 설치하지 않는다.
- 실행기: `com.wemade.teslamacro.test/com.wemade.teslamacro.nav.WirelessNavigationSmokeInstrumentation`. 키 복원, 앱 내부 ADB, 오디오 재연결 유예, 30초 해제 후 종료 완료 응답을 검사한다.
- 별도 확인: PIN 잠금 유지, 기본 화면과 다른 display ID, 종료 뒤 대체 앱 프로세스 없음, 심박 중단 정리, 기존 실행 보호.
- 이 검증은 실제 네이버지도 음성 출력이나 TLS 페어링 성공의 증거가 아니다. 실기기 결과는 설정 → 기기 → 진단 로그 → 공유로 받는다.

카메라 전용 데이터·실행 코드는 제거했다. Settings/Backup의 과거 필드는 구버전 파일 호환용으로만 남고 활성화 값은 항상 false다. DeviceApiClient와 기기 인증 키는 목적지 전송에서도 쓰므로 유지한다. 과거 카메라 개발 도구·로그는 현재 앱 동작에 사용하지 않는다.

## 2026-10-05 확인 결과

- 비이미지 테스트: debug/release 각각 앱 480건·BLE 30건 통과, 앱 13건은 기존 조건부 건너뛰기.
- Android 14 로컬 에뮬레이터: PIN 잠금 유지 상태의 별도 화면 실행, 실제 앱 내부 ADB 연결, 키 저장 복원, 재연결 유예, 30초 해제 뒤 종료 완료 응답 및 대체 지도 프로세스 소멸 확인.
- 심박 중단 자동 정리, 실행 중인 대체 지도 프로세스 보존 확인. 최초 기존 실행 보호 테스트는 시작 완료 대기가 없어 실패했으며, 시작 완료 확인 후 통과.
- 최초 자동 종료는 상태만 종료되고 프로세스가 남아 실패했다. STOP 완료 응답 대기와 HUP 처리 수정 후 실제 프로세스 소멸까지 재검증했다.
- R8 릴리스 APK의 셸 진입점도 잠금 상태 실행·종료 통과. 실제 네이버지도 음성·TLS 페어링·제조사별 절전은 미확인.
