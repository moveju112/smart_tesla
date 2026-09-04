# Smart Tesla

**📥 다운로드: [최신 APK (arm64)](https://github.com/moveju112/smart_tesla/releases/latest)**

차량 내 안드로이드 태블릿에서 **BLE로 테슬라를 직접 제어**하는 매크로 앱.

탑승을 감지해 통풍시트를 켜는 식의 조건부 자동화가 목적이다.
클라우드(Fleet API)를 쓰지 않으므로 인터넷·개발자 등록·과금이 없다.
**2025 Model Y (Juniper) 실차에서 연결·키 등록·제어 검증 완료.**

## 지금 되는 것

- 저장 주소 직행 + autoConnect BLE 연결, **ECDH 서명 세션** (공식 테스트 벡터 통과)
- **카드키 태그 키 등록**
- 통풍·열선·공조·잠금·창문·트렁크·충전 명령 + 상태 읽기 (배터리·온도·시트 동기화)
- 매크로 자동화 — 위저드 편집, 시각/시간대/탑승 조건, 애프터블로우 프리셋
- **네이버 지도 자동 길안내** — 탑승 시간대별 목적지 안내 (매크로 동작)
- 보닛·트렁크는 **P단에서만** 동작하는 안전 잠금
- 차 없이 전체 흐름을 돌려보는 **시뮬레이터 + 조작판**

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
