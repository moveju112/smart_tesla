# ADD_COMMAND — 새 차량 명령 추가

한 명령이 4곳에 걸친다. 하나라도 빼면 컴파일 에러(3)거나 커버리지 테스트 실패(4)로 잡힌다.

1. **명령 타입 선언** — `app/src/main/java/com/wemade/teslamacro/domain/command/VehicleCommand.kt:23` sealed interface에 data object/class 추가. 기존 항목(`:40 SetTemperature`) 형태를 따른다
2. **protobuf 인코딩** — `app/src/main/java/com/wemade/teslamacro/data/gateway/CommandEncoder.kt:31` `encode()` when에 분기 추가. 도메인 선택 주의: 잠금/개폐=VCSEC, 공조/시트=INFOTAINMENT ([BLE_RULES.md](../BLE_RULES.md))
3. **카탈로그 등록** — `app/src/main/java/com/wemade/teslamacro/domain/command/CommandCatalog.kt:76` `all` 리스트에 CommandTemplate 추가 (매크로 편집 UI에 노출되는 목록)
4. **테스트** — `./gradlew :app:test`
   - `app/src/test/java/com/wemade/teslamacro/data/gateway/CommandCoverageTest.kt:18` "카탈로그의 모든 명령이 실제로 인코딩된다"가 3↔2 누락을 자동 검출
   - 인코딩 바이트 검증이 필요하면 `CommandEncoderTest.kt`에 케이스 추가
5. **(선택) 대시보드 노출** — UI에 올릴 거면 `DashboardScreen.kt` + Paparazzi 갱신(`recordPaparazziDebug`)

주의: 프로토콜 정답 벡터 테스트(`tesla-ble/src/test/java/com/wemade/teslable/crypto/ProtocolVectorTest.kt`)는 절대 수정 대상이 아니다.
