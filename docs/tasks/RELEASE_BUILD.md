# RELEASE_BUILD — 실기기 배포 빌드

사용자 실기기(태블릿/폰) 테스트용 APK를 뽑는 절차. 매 배포마다 반복한다.

1. **버전 올리기** — `app/build.gradle.kts:17` versionCode +1, versionName 갱신 (예: 26/"0.4.3" → 27/"0.4.4")
   - versionCode를 안 올리면 기기에서 덮어쓰기 설치가 거부될 수 있다
2. **테스트 + 스냅샷 + 빌드** — 한 번에:
   ```bash
   ./gradlew test recordPaparazziDebug :app:assembleDebug
   ```
   - `test`에는 Paparazzi가 포함되지 않으므로 UI 변경 시 record를 같이 돌린다 ([PITFALLS.md](../PITFALLS.md))
   - UI를 안 건드렸으면 record 대신 `verifyPaparazziDebug`로 회귀 검증
3. **산출물 확인** — `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
   - universal APK는 없다 (ABI split, `app/build.gradle.kts:24`). 실기기는 arm64
4. **전달** — APK 파일명을 `SmartTesla-<versionName>-arm64.apk`로 바꿔 사용자에게 전달
5. **실차 검증 대기** — BLE 기능 변경이면 사용자 실차 테스트 결과(DiagLog 덤프)를 받기 전까지 "미확인"으로 취급 ([BLE_RULES.md](../BLE_RULES.md) 실차 사실 표 갱신)
