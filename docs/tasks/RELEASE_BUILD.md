# RELEASE_BUILD — 실기기 배포 빌드

사용자 실기기(태블릿/폰) 테스트용 APK를 뽑는 절차.
사용자가 코드 변경을 승인하면 검증 통과 후 별도 확인 없이 이 절차 전체를 자동으로 완료한다.
사용자가 릴리즈 제외를 명시했거나 검증 실패·실 VIN/비밀값·무관한 변경 혼입이 있으면 중단한다.

1. **버전 올리기** — `app/build.gradle.kts`의 versionCode +1, versionName을 다음 미사용 패치 버전으로 갱신 (예: 26/"0.4.3" → 27/"0.4.4")
   - versionCode를 안 올리면 기기에서 덮어쓰기 설치가 거부될 수 있다
2. **테스트 + 스냅샷 + 빌드** — 한 번에:
   ```bash
   ./gradlew test verifyPaparazziDebug :app:assembleDebug
   ```
   - `test`에는 Paparazzi가 포함되지 않는다 ([PITFALLS.md](../PITFALLS.md))
   - UI를 바꿨으면 먼저 `recordPaparazziDebug`로 관련 기준 이미지만 갱신하고 휴대폰·`WideScreenshotTest`를 눈으로 확인한 뒤 전체 `verifyPaparazziDebug`를 통과시킨다
3. **산출물 확인** — `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
   - universal APK는 없다 (ABI split, `app/build.gradle.kts:24`). 실기기는 arm64
4. **전달** — APK 파일명을 `SmartTesla-<versionName>-arm64.apk`로 바꿔 사용자에게 전달
5. **커밋 + 푸시 + 릴리즈** — 검증을 통과한 코드 변경은 자동으로 GitHub Release까지 완료한다 (사용자 상시 허가, 2026-09-03)
   ```bash
   git add <이번 작업 관련 파일만> && git commit -m "<versionName> — <한 줄 변경 요약>"
   git push <현재 추적 브랜치>
   git tag -a v<versionName> -m "Smart Tesla <versionName>" && git push origin v<versionName>
   ```
   - 시작 전에 같은 태그·GitHub Release가 없는지, 로컬 HEAD와 원격 추적 브랜치가 같은지 확인한다
   - 원격: https://github.com/moveju112/smart_tesla.git · 실 VIN/비밀값과 무관한 파일이 diff에 없는지 커밋 전 확인
   - `git add -A` 금지 — 사용자 파일과 untracked 파일을 릴리즈에 섞지 않는다
   - APK는 저장소가 아니라 **릴리스에 첨부**한다:
     `gh release create v<versionName> SmartTesla-<versionName>-arm64.apk --title "<versionName>" --notes "<요약>"`
   - 완료 후 로컬/원격 HEAD, 태그 커밋, 업로드 APK SHA-256이 모두 같은지 확인한다
6. **실차 검증 대기** — BLE 기능 변경이면 사용자 실차 테스트 결과(DiagLog 덤프)를 받기 전까지 "미확인"으로 취급 ([BLE_RULES.md](../BLE_RULES.md) 실차 사실 표 갱신)

## 실기기 로그 받기 — `adb`를 쓰지 않는다

실기기는 개발 PC에 물려 있지 않은 **차내 태블릿**이다. `adb logcat -s SmartTesla`는
개발 중 에뮬레이터에서만 쓸 수 있고, 사용자에게 안내하면 안 된다.

사용자에게 부탁할 절차는 이것 하나다:

> **설정 → 기기 → 진단 로그 → 공유**

- 공유는 카카오톡 메시지에서 바로 읽을 수 있도록 **텍스트 본문**으로 나간다.
  Intent 크기 제한을 피하려고 설정 덤프와 최근 로그를 합쳐 최대 32,000자만 담는다.
- 전체 로그는 `filesDir/diag/`에 계속 보관되고, 화면의 복사 버튼으로 가져올 수 있다.
- 로그는 **앱이 재시작해도 남는다.** 파일 두 세대(각 512KB)를 굴리며,
  `──────── 앱 시작 ────────` 줄이 실행 경계를 표시한다.
- 좌표 원문은 남기지 않는다. 공유되는 통로이기 때문이다.
