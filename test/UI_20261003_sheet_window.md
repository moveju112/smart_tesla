# 선택창 하단 잘림 회귀 검증

- 대상: 0.9.133, 공통 `PickerSheet`와 설정·매크로·입력 선택창.
- 원인: Compose UI 1.7.6의 `usePlatformDefaultWidth=false`가 실제 창 제약 대신 `screenHeightDp`로 높이를 재측정한다. Android 15에서 시스템 바가 포함된 높이를 사용하면서 하단 내용이 창 밖으로 밀렸다.
- 수정: 기본 측정 경로를 유지하고 Dialog 창 자체를 `MATCH_PARENT`로 확장한다. `safeDrawingPadding()`으로 시스템 바·화면 잘림·키보드 영역을 함께 제외한다.
- 재사용: `ui/component/Pickers.kt`의 `PickerSheet`·`ChoiceGrid`, 기존 `SelectionSheetScreenshotTest` 및 폰·태블릿 편집 스냅샷.

## 관측 결과

| 확인 | 결과 |
|---|---|
| Android 15, 1080×2400, 수정 전 화면 모드 | 선택지 글자 y=2396~2400: 4px만 표시되어 잘림 재현 |
| 같은 기기·설정, 수정 후 | 선택지 글자 y=2143~2195: 시스템 탐색 바 위에 전부 표시 |
| Android 15 기기 사용 방식 | 두 선택지 표시, 선택·다시 열기·뒤로가기 통과 |
| Android 15 제스처 탐색 + 글자 2배 | 두 선택지 y=2183~2282: 하단 겹침 없음, 실제 캡처 확인 |
| Android 15 매크로 편집 + 키보드 | 저장 글자 하단 1442px < 키보드 시작 1499px; 실제 창·캡처 확인 |
| Android 14 호환성 | 화면 모드 3개 y=2206~2258, 뒤로가기 통과 |
| 최종 소스 Paparazzi 비교 | 23건 통과: 선택창 12, 폰 편집 7, 태블릿 편집 4 |
| 비이미지 테스트 | 앱 518 + BLE 30 통과, 앱 기존 14건 건너뜀; debug/release 각각 동일 |
| 릴리스 | `./gradlew test :app:assembleRelease` 통과, 기존 APK와 서명 인증서 일치 |

## 재검증

```bash
./gradlew :app:verifyPaparazziDebug -PallowSnapshots=true --tests '*SelectionSheetScreenshotTest*'
```

실행 검증은 등록하지 않은 격리 에뮬레이터에서 `나중에 → 설정 → 기기 → 화면 모드`, `설정 → 차량 → 기기 사용 방식`으로 진입한다.
Android 15에서 제스처/3버튼 탐색, 글자 1배/2배, 매크로 이름 입력 중 저장 버튼을 확인한다.
Paparazzi는 실제 시스템 바를 합성하지 않으므로 이 OS 창 경계 검증을 대체하지 않는다.
네이티브 검증 APK는 0.9.131 기준에 동일한 PickerSheet 수정을 적용한 x86_64 빌드다. 최종 0.9.133은 0.9.132 변경을 포함해 비이미지 테스트·관련 스냅샷·ARM 릴리스 빌드를 다시 검증했다.
사용자 실기기·제조사별 창 동작은 미확인이다. 임시 에뮬레이터는 검증 후 종료했다.
