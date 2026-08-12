# ARCHITECTURE (모듈맵 · 테스트 배치)

설계 원칙·계층 다이어그램·확장 지점·프로토콜 정답 벡터·폴링 전략은 루트 [ARCHITECTURE.md](../ARCHITECTURE.md)가 정본이다.
이 문서는 거기 없는 것만 담는다: 모듈맵, 서비스, 스토어, 테스트 배치, 빌드 산출물.

## 모듈 2개

| 모듈 | namespace | 역할 |
|---|---|---|
| `:tesla-ble` | `com.wemade.teslable` | BLE 전송 계층. GATT/프레이밍/암호 세션/protobuf만 안다 |
| `:app` | `com.wemade.teslamacro` | domain + data + UI. `tesla-ble`를 씀 |

## app 패키지 지도

```
di/AppContainer.kt      유일한 조립 지점 (수동 DI, Hilt 없음)
domain/                 Android 의존 0 — command/ gateway/ macro/ model/ voice/
data/
  gateway/              BleVehicleGateway, SimulatedVehicleGateway, SwitchingVehicleGateway,
                        CommandEncoder(명령→protobuf), SnapshotDecoder(응답→VehicleSnapshot)
  poll/StatePoller.kt   주기 읽기 + 매크로 판정 트리거
  settings/             SettingsStore(설정), SeatStore(좌석 통풍/열선 클라 저장)
  macro/RuleStore.kt    매크로 JSON 파일 저장 (DataStore 아님 — filesDir/macros.json)
  voice/                VoiceRecognizer, HotwordListener, VoiceModelStore (Vosk 오프라인)
feature/<화면>/         XxxScreen.kt + XxxViewModel.kt 쌍 (dashboard, macro, pairing, settings)
service/                MacroService(FGS connectedDevice), VoiceService(FGS microphone), BootReceiver
ui/                     ViewModelFactory, component/, layout/(Pane 반응형), nav/, theme/(토큰)
```

## 데이터 흐름

```
Screen → ViewModel(container 주입) → SwitchingVehicleGateway
                                       ├ BleVehicleGateway ── TeslaBleLink ── TeslaClient ── GATT
                                       └ SimulatedVehicleGateway (VIN 미등록 시)
StatePoller ↔ MacroRunner: latestReading(StateFlow) 공유 — `app/src/main/java/com/wemade/teslamacro/di/AppContainer.kt:42`
```

- 등록 완료 시 `useRealVehicle()`로 실차 게이트웨이 교체 필수 — `app/src/main/java/com/wemade/teslamacro/di/AppContainer.kt:74`
- 음성: `VoiceService`(상시)와 대시보드 버튼(1회) 둘 다 `VoiceCommandParser`로 합류

## 스토어 3+1

| 스토어 | 저장소 | 내용 |
|---|---|---|
| `app/src/main/java/com/wemade/teslamacro/data/settings/SettingsStore.kt` | DataStore "settings" | VIN, isPaired/isEnrolled, 폴링 주기, 음성 상시 |
| `app/src/main/java/com/wemade/teslamacro/data/settings/SeatStore.kt` | DataStore "seat_climate" | 좌석별 통풍/열선 모드+단계 (차에서 못 읽어서 클라 저장) |
| `app/src/main/java/com/wemade/teslamacro/data/voice/VoiceModelStore.kt` | filesDir | Vosk 모델 zip 설치 (APK 미포함, 사용자가 파일 선택) |
| `app/src/main/java/com/wemade/teslamacro/data/macro/RuleStore.kt` | filesDir/macros.json | 매크로 룰 (kotlinx.serialization) |

## 테스트 배치

| 위치 | 종류 | 수 |
|---|---|---|
| `tesla-ble/src/test/java/com/wemade/teslable/TeslaBleSpecTest.kt` | VIN→BLE 이름 공식 벡터 + 프레이밍 | 7 |
| `tesla-ble/src/test/java/com/wemade/teslable/crypto/ProtocolVectorTest.kt` | ECDH/AES-GCM/TLV 공식 벡터 | 10 |
| `app/src/test/java/com/wemade/teslamacro/` 하위 domain/macro · domain/voice · data/gateway · data/settings | 단위 | 64 |
| `app/src/test/java/com/wemade/teslamacro/ScreenshotTest.kt` | Paparazzi PIXEL_C 가로 | 10컷 |
| `app/src/test/java/com/wemade/teslamacro/PhoneScreenshotTest.kt` | Paparazzi PIXEL_6 세로 | 6컷 |

- 스냅샷 공용 래퍼: `app/src/test/java/com/wemade/teslamacro/AppFrame.kt` (테마+레일 포함 실배치)
- 스냅샷 PNG: `app/src/test/snapshots/images/` (파일명 한글)

## 빌드 산출물

- ABI split: arm64-v8a + armeabi-v7a, universal APK 없음 (Vosk 네이티브 10MB/ABI) — `app/build.gradle.kts:24`
- 실기기 배포는 arm64 APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- 버전: `app/build.gradle.kts:17` (versionCode / versionName)
