# Smart Tesla

차량 내 안드로이드 태블릿에서 **BLE로 테슬라를 직접 제어**하는 매크로 앱.

탑승을 감지해 통풍시트를 켜는 식의 조건부 자동화가 목적이다.
클라우드(Fleet API)를 쓰지 않으므로 인터넷·개발자 등록·과금이 없다.

## 지금 되는 것

- VIN → BLE 광고 이름 계산 → 차량 스캔 → GATT 연결
- **ECDH 서명 세션** + AES-GCM 명령 암호화 (공식 테스트 벡터 통과)
- **카드키 태그 키 등록** 요청
- 통풍·열선·공조·잠금·창문·충전 명령
- 공조/충전/차체 상태 읽기
- 매크로 판정 엔진 (조건 조합 · 엣지 감지 · **시각 조건** · 쿨다운)
- **매크로 편집 화면** — 조건·동작·순서·쿨다운을 UI에서 직접 만든다
- 화면 4개 (등록 / 제어 / 매크로 / 설정)
- 차 없이 전체 흐름을 돌려보는 **시뮬레이터 + 조작판**

## 아직 안 된 것

**실차 검증.** 규격 벡터는 통과했지만 실제 차량에 붙여본 적이 없다.

차량을 등록하지 않고 앱을 켜면 시뮬레이터가 붙는다.
설정 탭의 **가상 차량** 패널에서 온도를 올리고 "탑승 재현"을 누르면
매크로가 실제로 발동하는지 끝까지 확인할 수 있다.

## 빌드

```bash
# 최초 1회 — Android SDK 구성 요소
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

./gradlew :app:assembleDebug
./gradlew test          # 로직 단위 테스트
adb install app/build/outputs/apk/debug/app-debug.apk
```

`ANDROID_HOME`이 필요하다. `local.properties`에 `sdk.dir=`를 적어도 된다.

## 문서

- [ARCHITECTURE.md](ARCHITECTURE.md) — 계층, 확장 지점, 폴링 전략, 디자인 시스템

## 참고 규격

- [teslamotors/vehicle-command](https://github.com/teslamotors/vehicle-command) — 프로토콜 정본
- `pkg/protocol/protocol.md` — BLE 광고 이름, 핸드셰이크, 도메인
- `pkg/protocol/protobuf/car_server.proto` — 명령 정의
- `pkg/protocol/protobuf/vehicle.proto` — 상태 필드 정의
