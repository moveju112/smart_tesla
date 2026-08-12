# Smart Tesla — 아키텍처

## 왜 이렇게 나눴나

기능이 계속 붙을 프로젝트다.
"새 기능 = 파일 하나 + 분기 하나"가 되도록 확장 지점을 **3개로 못 박았다.**

## 모듈

```
:tesla-ble   BLE 전송 계층. 안드로이드 GATT만 알고 테슬라 의미는 모른다
:app         도메인 + 데이터 + UI
```

모듈을 2개만 뒀다.
지금 규모에서 9개로 쪼개면 Gradle 설정만 늘고 얻는 게 없다.
`domain` 패키지는 안드로이드 의존이 없게 유지한다.
나중에 모듈로 뽑아도 파일 이동뿐이다.

## 계층

```
UI (Compose)
  └─ ViewModel        화면이 쓸 UiState로 변환
       └─ Domain      VehicleCommand / MacroRule / MacroEngine  ← 안드로이드 의존 0
            └─ Data   VehicleGateway 구현 · RuleStore · StatePoller
                 └─ :tesla-ble
```

**의존 방향은 항상 아래로만.**
`domain`은 `data`를 모른다. 게이트웨이는 인터페이스만 `domain`에 두고 구현은 `data`에 있다.

## 확장 지점 3개

| # | 무엇을 늘릴 때 | 어디를 고치나 |
|---|---|---|
| 1 | **새 조건** (주행거리, 실내습도…) | `domain/model/Signal.kt`에 enum 항목 1개 |
| 2 | **새 명령** (미디어, 발렛…) | `VehicleCommand`에 타입 1개 + `CommandEncoder` 분기 1개 + `CommandCatalog`에 1줄 |
| 3 | **새 조건 종류** (지오펜스, 날씨…) | `Trigger` 하위 타입 1개 + `MacroEngine` 분기 1개 |

편집 화면은 `Signal.entries`와 `CommandCatalog.all`을 그대로 나열한다.
**1번은 화면 수정이 아예 없고, 2번은 카탈로그 한 줄이 곧 UI다.**

실제로 확장 지점 3번을 써서 `Trigger.AtTime`(시각 조건)을 추가했다.
`MacroEngine`·`describe`·편집 화면에 분기가 각각 하나씩만 늘었다.

## 전송 수단 교체

`VehicleGateway`가 유일한 창구다.

```
VehicleGateway (interface)
├─ BleVehicleGateway         지금 쓰는 것
├─ SimulatedVehicleGateway   차 없이 UI·매크로 검증
└─ FleetApiVehicleGateway    나중에 클라우드가 필요하면 여기만 추가
```

화면과 매크로는 어느 구현인지 모른다.

## 폴링 전략 (성패를 가르는 부분)

```
평상시   → body-controller-state 만, 30초         VCSEC라 차가 자도 응답
사건감지 → 룰이 요구하는 카테고리 전부, 2초, 5분간   인포테인먼트 깨움
```

항상 고빈도로 전체를 읽으면 **차가 잠들지 못해 방전된다.**
주기는 코드에 박지 않고 설정으로 뺐다 — 실차에서 계속 만질 값이다.

`StatePoller.requiredCategories()`가 켜진 매크로의 `Signal`을 역추적해
**실제로 필요한 카테고리만** 읽는다. 안 쓰는 데이터를 읽지 않는다.

## 디자인 시스템

`refactory/weis_go/DESIGN.md`(Tesla 디자인 시스템)를 Compose로 옮기고 **다크로 뒤집었다.**
차 안 태블릿은 야간 사용이 기본이라 흰 배경을 안 쓴다.

원본에서 그대로 가져온 규칙:

- 그림자 **0**. 깊이는 명도 한 단계 + hairline 테두리로만
- 액센트 1색 (`#3E6AE1`). 의미색은 상태 표시 전용, 버튼에 안 씀
- 폰트 굵기 **400/500 두 개만**
- 반경: 기본 0, 버튼 4dp, 카드 12dp
- 8dp 그리드
- 모션 330ms `cubic-bezier(0.5, 0, 0, 0.75)` 통일

바꾼 것:

- 배경 화이트 → 다크 4단계 (`Void`/`Carbon`/`Graphite`/`Slate`)
- 하단 탭 → **좌측 레일** (태블릿 가로 화면)
- 온도용 56sp 계측 스타일 추가 (흘깃 보고 읽어야 함)

## 테스트

로직은 **순수 함수**로 뽑아 안드로이드 없이 검증한다.

| 대상 | 개수 | 파일 |
|---|---|---|
| VIN → BLE 이름 (공식 정답 벡터) | 3 | `tesla-ble/.../TeslaBleSpecTest.kt` |
| 청크 프레이밍/재조립 | 4 | 같은 파일 `BleFramingTest` |
| **프로토콜 암호 (공식 정답 벡터)** | 10 | `tesla-ble/.../ProtocolVectorTest.kt` |
| **명령 인코딩 (공식 정답 벡터 포함)** | 8 | `app/.../CommandEncoderTest.kt` |
| 매크로 판정 (엣지·시각·쿨다운·null 안전) | 19 | `app/.../MacroEngineTest.kt` |
| 편집 초안 왕복·저장 가드·카탈로그 | 12 | `app/.../MacroDraftTest.kt` |

`protocol.md`에 실린 값은 전부 그대로 넣어 검증했다.

- ECDH 공유키 `K = 1b2fce19967b79db696f909cff89ea9a`
- 세션 파생키 `fceb679e…`
- 세션 정보 HMAC 태그 `996c1fe3…`
- 메타데이터 TLV 3종
- 공조 켜기 명령 `120452020801`

**이 값들은 자체 판단으로 바꾸면 안 된다.** 틀리면 실차에서 인증이 조용히 깨진다.

## 프로토콜 구현 지도

| 규격 절 | 구현 |
|---|---|
| Metadata serialization | `crypto/Metadata.kt` |
| Key Agreement / AES-GCM | `crypto/SessionCrypto.kt` |
| 클라이언트 키 보관 | `crypto/ClientKeyStore.kt` (API 31+ Keystore, 이하 소프트웨어) |
| Handshake / 세션 상태 | `session/DomainSession.kt` |
| 메시지 라우팅·오류 복구 | `TeslaClient.kt` |
| 명령 → protobuf | `app/.../CommandEncoder.kt` |
| 응답 → 스냅샷 | `app/.../SnapshotDecoder.kt` |

## 진행 단계

| | 내용 | 상태 |
|---|---|---|
| M0 | 프로젝트·디자인 시스템·화면 4개·매크로 엔진 | ✅ |
| M1 | VIN 스캔 · GATT 연결 · 프레이밍 | ✅ |
| M2 | 서명 세션 (ECDH + AES-GCM) · protobuf · 키 등록 | ✅ |
| M3 | 명령/상태 읽기 실배선 | ✅ |
| M4 | 매크로 편집 화면 · 시각 조건 · 명령 카탈로그 · 시뮬레이터 조작판 | ✅ |
| M5 | 실차 검증 | ⬜ |

**M5가 남았다.** 코드는 규격을 통과하지만 실차에 붙여본 적이 없다.

## 차 없이 확인하는 법

차량을 등록하지 않고 앱을 켜면 시뮬레이터가 붙는다.

1. 설정 → **가상 차량** 패널에서 실내 온도를 31℃로 올린다
2. **탑승 재현**을 누른다 (문 열림 + 탑승 엣지가 발생한다)
3. 매크로 탭에서 "여름 탑승 쿨링"이 실행되고 로그가 쌓이는지 본다
4. 제어 탭에서 통풍 단계가 실제로 바뀌었는지 확인한다

시각 조건은 편집 화면에서 지금 시각 +1분으로 맞춰두면 바로 확인된다.
