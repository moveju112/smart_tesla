# 주행·충전 기록

## 사용

기능 → 주행 기록 → 5초 주행·충전 기록을 켠다. 등록 차량만 기록하며 시뮬레이터 값은 저장하지 않는다.
앱 화면·직접 명령으로 연결한 뒤 기록이 켜져 있고 D/R을 확인하면 화면 밖에서도 주행 기록을 이어간다.
주차·충전·사용자 일시정지·기록 해제 또는 D/R 확인 후 90초 미수신이면 기록용 연결을 놓는다. 빈 차를 깨워 새 주행을 찾지는 않는다.
기록 간격은 5초 슬롯 기준이며 BLE 읽기 지연·실패는 공백이다. 정확한 5초 실시간 수신을 보장하지 않는다.

## 화면

- 기록 설정 진입점·설명 전용 시트는 두지 않는다. 수집 스위치는 목록에 유지한다.
- 상세는 거리/충전량과 시간을 먼저 보여주고, 배터리 변화·계산 가능한 효율·추정 전력량은 이름/값 행으로 정렬한다.
- 표본 수·전력 관측 시간·계산 한계·시작/종료 미확인 설명은 상세 제목의 도움말에만 둔다.
- 시간은 초·분·시간으로 표시하고 반올림된 `-0.0`은 `0.0`으로 표시한다. 저장된 원본은 바꾸지 않는다.

## 저장

- 앱 전용 `databases/vehicle_history.db`. Room 등 신규 프레임워크 없이 Android SQLite 사용.
- `sessions`: 차량별 주행/충전/정차 구간의 요약. `blocks`: `(session_id, bucket)`마다 5분 표본 JSON을 GZIP으로 압축한 BLOB.
- 차량 구분은 VIN의 SHA-256 값. DB·진단 로그에 VIN 원문과 경로 좌표를 추가하지 않는다.
- 5초 슬롯마다 최신 묶음을 트랜잭션으로 저장한다. 종료된 묶음은 다시 압축하지 않는다.
- 소수 좌표·측정값을 반올림하거나 표본을 제거하지 않는다. 알 수 없는 값은 null이다.
- 스키마는 DB 버전으로, 표본 항목 추가는 nullable 기본값과 JSON `ignoreUnknownKeys`로 관리한다. 미지원 DB 버전은 삭제·초기화하지 않는다.
- 기간 목록은 요약만 읽고 선택한 주행의 압축 원본만 해제한다. 최신 50개 이후는 더 보기로 조회한다.
- 앱 재시작 후 마지막 표본을 복구한다. 2분 초과 공백·시간 역행·종류 변경·명시적 중지는 구간을 분리한다. 15초 초과 공백은 같은 세션에서도 경로·전력 적산에서 제외하며 누락 표시를 남긴다.
- 같은 5초 슬롯 안에서도 상태 전환은 생략하지 않는다. 상태 전환 표본은 이전 구간의 최종 거리/충전량과 새 구간의 시작 근거로 양쪽에 보관할 수 있다.
- SQLite WAL과 인덱스도 저장 용량에 포함한다. 지도 WebView 캐시는 별도다.
- 자동 보존기간 삭제 없음. 저장 실패 시 기존 데이터를 지키며 UI 오류 표시와 좌표 없는 진단을 남긴다.
- Android 자동 백업은 기존처럼 꺼져 있다. 설정·매크로 백업에도 기록 DB는 포함되지 않는다. 앱 삭제·데이터 초기화로 기록이 사라진다.

## 표본과 지표

시각, 카테고리별 실제 응답 수신 시각, 차량 위경도, 누적거리(0.01 mile 정수), 속도, 기어, 탑승,
주행 전력, 배터리 %, 주행 가능 거리, 충전 여부/추가량/전력/실제 전류/전압/한도,
실내외 온도, 공조 상태, 타이어별 공기압을 저장한다. 매 표본에서 모든 카테고리가 수신되는 것은 아니다.
장기 기록에는 `StatePoller`의 병합 캐시가 아니라 이번 응답을 사용한다.

- 주행거리: 관측 끝·시작 누적거리 차이 × 0.01609344 km. 카운터 역행 시 미확인.
- 배터리 감소: 시작 % − 끝 %. 중간의 % 반등·하락을 소비량으로 합산하지 않는다.
- km/%: 주행거리 ÷ 양수 배터리 감소 %. 정수 % 오차 때문에 짧은 주행에 부정확하다.
- 수동 배터리 용량 설정과 해당 입력 기반 kWh·km/kWh 추정은 제공하지 않는다.
- 주행 전력 적산: 인접한 유효 표본(15초 이내)의 사다리꼴 적산. 회생 부호 보존. 관측 시간 함께 표시.
- 충전량: 같은 관측 구간의 `charge_energy_added` 차이. 중간 카운터 초기화는 미확인. 충전 중간부터 기록한 경우 이미 충전된 양은 포함하지 않는다.
- 충전 그래프: 차량 보고 kW 정수. 실제 전류·전압도 원본에 보관하지만 단상/삼상 구분 없이 V×A를 확정 전력으로 사용하지 않는다.
- 끝을 관측해도 시작 이전의 이동·충전을 복구할 수 없다. UI는 관측 구간임을 표시한다.

## 지도

로컬 HTML/WebView에 숫자 좌표만 주입한다. 외부 JavaScript·네이티브 JS 브리지를 사용하지 않는다.
현재 보이는 OpenStreetMap 타일만 HTTPS로 요청하고 고유 User-Agent, 출처 링크, WebView 기본 HTTP 캐시를 사용한다.
기록 전체 업로드·타일 선다운로드 없음. 지도 조회 시 제공자에 IP와 표시 영역이 전달된다.
동일 기록의 갱신은 기존 WebView를 유지하며, 좌표 하나뿐이면 이동선 부족 안내를 표시한다.
연결 공백은 선으로 연결하지 않으며 배경 타일 실패 시 저장 경로와 안내 문구를 표시한다.
[타일 정책](https://operations.osmfoundation.org/policies/tiles/)을 따른다.

## 검증과 한계

`VehicleHistoryTest`: 압축 무손실 복원/손상 감지, SOC 반등, 0 감소량, 시간 역행/공백,
회생 적산, 충전·거리 카운터 초기화, 관측 시각, 종류 전환.
`SnapshotDecoderHistoryTest`: 추가 BLE 필드의 protobuf 해석·overlay 보존·미수신 null.
`HistorySettingsTest`: 같은 차량의 선택 유지, 차량 변경 때 수집 동의 초기화.

SQLite는 Paparazzi/Layoutlib에서 StatFs·journal 조회가 지원되지 않아 실제 Android 검증을 별도로 둔다.
`HistorySmokeInstrumentation`은 앱의 실제 DB에 접근하지 않고 임시 DB로 저장·재조회·5초 중복 방지·종료 경계·차량 분리·명시적 중지를 검증한다.
2026-10-04 로컬 `tesla_tablet` Android 34 에뮬레이터에서 PASS. 새 외부 테스트 의존성은 없다.
로컬 에뮬레이터의 설치·실행·검증은 상시 허가되어 별도 확인 없이 진행한다(2026-10-04 사용자 지시). 실제 기기 설치는 별도 승인 대상이다.

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PhistorySmokeEmulator=true
# 임시 로컬 에뮬레이터의 실제 식별자를 확인한 뒤 설치·실행
adb -s emulator-5580 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w com.wemade.teslamacro.test/com.wemade.teslamacro.history.HistorySmokeInstrumentation
```

합성 표본 60개 압축 시험은 JSON 13,296 B → GZIP 1,056 B였으며 원문 값 복원이 일치했다.
실제 차량 필드·SQLite 인덱스/WAL·지도 캐시는 포함하지 않은 표본 압축률이므로 연간 저장 용량으로 단정하지 않는다.
지도는 브라우저에서 확대·전체 보기·관측 공백 분리·타일 실패 안내를 텍스트로 검증했다.
`HistoryMapSmokeInstrumentation`은 빈 로컬 에뮬레이터에서 실제 ViewModel·Compose·WebView의 경로 DOM과 갱신 중 뷰 유지를 검증한다.
지도 실행기는 `-PsmokeRunner=com.wemade.teslamacro.history.HistoryMapSmokeInstrumentation`으로 빌드한다.
실차의 거리·전력·충전 추가량 지원과 차량 응답 속도, 사용자 기기의 WebView·백그라운드 동작은 별도 확인 대상이다.
