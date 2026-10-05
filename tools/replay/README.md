# 주행 재현 (집에서 단속 안내 검증)

실차 없이 실제 도로를 따라가는 1초 간격 가상 GPS를 앱의 실제 안내 로직(`SafeDriveGuide`)에 넣는다.
카메라 후보·음성 문구·경고음 구간을 초 단위 보고서로 남긴다.
경로는 생활 동선일 수 있어 Git 제외 폴더 `local-replay/`에만 둔다. 공개 저장소에 올리지 않는다.

## 1. 경로 만들기 (매칭 서버의 개발용 경로 계산기 사용)

```bash
# 서버에서 경로 계산기를 잠시 띄운다(운영 매칭 서비스 gps-map과 별개, 끝나면 종료)
ssh -n oracle_tokyo 'cd project/osrm && LD_LIBRARY_PATH=.build/install/lib nohup nice -n 15 .build/install/bin/osrm-routed --algorithm mld -i 127.0.0.1 -p 5055 -t 1 .data/south-korea.osrm > /dev/null 2>&1 < /dev/null &'
ssh -f -o ExitOnForwardFailure=yes -N -L 15055:127.0.0.1:5055 -L 18091:127.0.0.1:8091 oracle_tokyo < /dev/null   # 경로 계산·매칭 터널
# 경유지는 "경도,위도" 순서. 속도는 km/h
python3 tools/replay/make_route.py http://127.0.0.1:15055 local-replay/<이름>.json 50 <출발> <경유...> <도착>
```

- 지번 주소는 무료 지오코더에서 잘 안 잡힌다. 지도 앱에서 길게 눌러 복사한 좌표를 쓰는 편이 정확하다.
- 경로는 최단 시간 기준이다. 실제로 다니는 길과 다르면 그 길 위의 점을 경유지로 추가한다.
- 끝나면 서버에서 경로 계산기를 끈다: `ssh -n oracle_tokyo 'pkill -x osrm-routed'` (`pkill -f "<명령줄>"`은 원격 셸 자신도 맞아 종료 코드 144로 끊긴다)
- 백그라운드 `ssh ... nohup ... &`는 세션이 안 닫힐 수 있다. 터널은 `ssh -f -o ExitOnForwardFailure=yes -N -L 15055:127.0.0.1:5055 -L 18091:127.0.0.1:8091 oracle_tokyo < /dev/null`로 띄운다.

## 2. 재현 실행

```bash
# 매칭 터널(18091)은 1단계에서 함께 연다. 캐시에 있는 경로만 다시 돌릴 때는 필요 없다.
SAFETY_REPLAY_DIR=$PWD/local-replay SAFETY_REPLAY_MATCH_URL=http://127.0.0.1:18091 \
SAFETY_REPLAY_TOKEN=$(grep '^roadMatchToken=' local.properties | cut -d= -f2-) \
./gradlew :app:testDebugUnitTest --tests '*SafeDriveReplayTest' --rerun
```

- `--rerun`이 없으면 Gradle이 이전 결과를 재사용해 경로를 바꿔도 다시 돌지 않는다.
- 매칭 응답은 `local-replay/match-cache.json`에 쌓인다. 같은 경로를 다시 돌릴 때는 서버 없이 결정적으로 재생된다.
- `SAFETY_REPLAY_DIR`이 없으면 테스트를 건너뛴다. 일반 `./gradlew test`는 영향이 없다.

## 3. 결과

- `local-replay/summary.txt`: 경로별 `매칭`/`오프라인` 모드의 음성 횟수(제한속도별)와 경고음 횟수·초.
- `local-replay/report-<경로>-<모드>.txt`: 초 단위 후보 변화(카메라 ID·카메라 도로·매칭 도로·위치)·음성·경고음.
- 한계: GPS 흔들림은 횡 3m·방향 3° 난수 근사다. 매칭 응답이 즉시 도착한다고 가정한다. 실제 소리 출력·화면은 확인하지 않는다.

## 0.9.125 도로 근거와 검증 범위

- `OfflineCamera.roadAxisDegrees`는 여러 가상 접근이 일치한 **양방향 도로 축**(0~179도)이다. 단속 방향 `direction`을 대신하거나 반대 차로의 단속 여부를 확정하지 않는다.
- 확인된 도로명이 일치하고 카메라 20m 안에서 끝나는 신뢰도 0.8 이상의 지도 매칭만 쓴다. 최소 3개 가상 접근의 축이 20도 안에서 일치해야 보강하며, 모호한 교차로·곡선·도로명 누락은 보강하지 않는다.
- 0.9.125 번들은 기존 12,012건과 원자료 필드를 유지하고 2,132건에 축만 추가했다. 이 중 방향 미상 1,311건이 새 교차도로 필터 대상이다. 나머지는 근거 부족으로 추정하지 않는다.
- 단속 방향을 모르는 카메라만 도로 축과 현재 진행방향의 양방향 차이가 60도를 넘으면 제외한다. 최초 후보와 연속 카메라 안내에 같은 기준을 쓴다. 기존 격자 검색에 상수 시간 비교만 추가하며 주행 중 네트워크 요청은 늘리지 않는다.
- 가까운 매칭이라도 GPS 진행방향과 60도 넘게 상충하면 좌표 보정과 도로명을 함께 버린다. 회전 전 도로명이나 반대 차로가 현재 도로의 실제 카메라를 가리지 않게 한다.
- 도로명 대기는 최대 6초지만, `30m + 현재 속도로 3초 이동할 거리` 안에서는 대기하지 않는다. 교차로 뒤 바로 나타난 실제 카메라를 이름 확인 중 지나치는 누락을 막고 기존 연속 접근 확인은 유지한다.
- 서로 다른 것으로 확인된 도로명·도로 축은 30m 안 같은 속도여도 한 카메라로 묶지 않는다.
- 같은 도로의 반대 차로, 도로명·축이 모두 없는 카메라, 좌표가 겹치는 고가·지하의 단속 여부까지 확정할 수는 없다. 지도 축은 현장 표지·실차 검증을 대체하지 않는다.

### 기대 후보·제외 후보 검증

경로 JSON에 다음 선택 필드를 넣으면 후보와 실제 음성 요청을 대조한다. 원본 ID가 동일 좌표 묶음에 들어 있어도 개별 ID로 비교한다.

- `expectedCameraIds`, `excludedCameraIds`: 두 모드 공통.
- `expectedMatchedCameraIds`, `excludedMatchedCameraIds`: 매칭을 활성화한 실행.
- `expectedOfflineCameraIds`, `excludedOfflineCameraIds`: 오프라인 실행.

생성기 옵션도 동일하게 `--expected-camera-id ID`, `--excluded-camera-id ID`, `--expected-matched-camera-id ID` 등이며 여러 번 지정할 수 있다.
기대값 없는 기존 경로는 관찰 전용이다. 기대값 있는 경로의 HTTP 실패·서버 없는 캐시 누락·확정 매칭 응답 부재는 검증 실패다.
실서버 캐시 미스 호출은 최소 125ms 간격이다. `SAFETY_REPLAY_REQUIRE_MATCH=true`는 관찰 전용 경로에서도 서버 없는 캐시 누락을 실패시킨다.
`SAFETY_REPLAY_DATASET=/절대/경로/번들.json`으로 보강 전·후 번들을 같은 경로에서 비교할 수 있다.

`expectedSilentCameraIds`, `expectedSilentMatchedCameraIds`, `expectedSilentOfflineCameraIds`는 화면에 반드시 나타나되 음성·경고음은 없어야 하는 후보를 검사한다.
기존 `expected*CameraIds`는 계속 화면과 음성을 모두 요구하고, `excluded*CameraIds`는 화면과 음성을 모두 금지한다.
소리 보류 정책으로 기대값을 바꿀 때는 매칭 시점의 도로 근거를 확인하고 기존 기대값·보고서를 비공개 캐시에 보존한다.

보고서의 확정 매칭 응답 수는 **해당 카메라 안내 순간에 매칭이 적용됐다는 뜻이 아니다**. 불확실 응답·방향 상충·오래된 응답은 실제 안내기에서 오프라인으로 복귀한다.
전국 지도 경로의 기대 ID는 도로명·선형에 따른 양성 보존 검사이고, 교차도로 오탐 억제는 `CameraIndexTest`·`RoadNameGuideTest`의 정답이 고정된 양성/음성 쌍으로 별도 검사한다.
양성 재현 성공만으로 실도로 전체 오탐률을 계산하지 않는다.

2026-09-30 검증 입력: 수도권·강원·충청·호남·영남·제주 12개 양방향 지도 경로와 교차도로 2개 경로를 각 모드로 실행한다.
교차도로 경로는 대상 카메라의 도로명을 지나지 않는 지도 선형, 카메라 25m 이내 통과, 70도 이상 교차하는 진행축을 독립 근거로 제외 ID를 지정했다.
`서동대로` 카메라를 `신령로`·`중앙대학로` 경로에서 읽는 오탐이 축 보강 전 매칭 활성·오프라인 모두 재현됐다. 동일 카메라의 본래 도로 양방향 경로는 양성 기대값으로 함께 검사한다.
축 보강 후 14개 경로×2모드에서 지정한 필수 후보·음성이 유지되고 제외 후보·음성은 없었다. 위 교차도로 오탐은 두 모드 모두 후보·음성 각 1회에서 0회로 줄었다.
기존 수원 9개 경로×2모드도 캐시 누락 없이 재실행했다. 이는 총 46개 가상 주행 조건의 결과이며 실차 오탐률 측정은 아니다.

### 0.9.153 매칭 공백 뒤 도로명 재확인

- 도로명 대조로 후보가 제외된 뒤에는 해당 후보의 도로명 대기도 초기화한다. 이후 매칭이 잠깐 끊겨 같은 후보가 돌아와도 이전에 만료된 대기를 재사용하지 않는다.
- 대기는 기존과 같이 최대 6초이며, 가까운 후보·이미 안내 중인 후보의 예외와 매칭 미사용 시 동작은 유지한다. 네트워크 요청 주기와 Fleet API 사용은 변경하지 않는다.
- `RoadNameGuideTest.rejectedSideStreetWaitsAgainDuringMatchingGap`은 제외 → 매칭 공백 → 도로명 복구 사이의 옆길 음성을 검사한다. `rejectedCameraStillFallsBackWhenMatchingDoesNotRecover`는 복구되지 않을 때 6초 대기 뒤 접근 확인을 거쳐 안내하는지 검사한다. 두 테스트 모두 수정 전 실패를 확인했다.
- 2026-10-04 수원 캐시 재현에서 `kwanggyo-outbound`의 옆길 30km/h 음성은 1회 → 0회, 경고음은 3초 → 0초이며 기존 50km/h 음성 1회는 유지됐다. 이 경로에는 매칭 모드의 제외·필수 ID를 고정했다. 나머지 17개 수원 주행 조건의 요약 결과는 동일했다.
- 관련 단위 테스트 53개와 수원 18개·전국 28개 가상 주행 조건을 확인했다. 전국 경로의 지정 필수 후보·음성 보존 및 제외 후보·음성 차단이 통과했고, 모든 재현은 서버 호출·캐시 누락 없이 완료했다. 이는 실차 검증이 아니다.
- 이 수정은 도로 구간 ID 연결이나 평행도로·고가/지하의 완전한 구분을 구현한 것이 아니다. 도로 근거가 끝내 없으면 기존 GPS 판정으로 복귀하므로 오탐 가능성은 남는다.

### 0.9.154 방향 미상 카메라의 소리 근거

- 방향 미상 후보는 화면의 접근 확인과 별도로 유효한 같은 도로명 아래 연속 접근이 확인돼야 음성·경고음을 허용한다. 기존 `comparableRoadName`, `CameraApproachTracker`를 재사용하며 지도 요청 주기·Fleet API 사용은 변경하지 않는다.
- 도로 축만 일치하거나 도로명이 없으면 같은 도로로 추정하지 않는다. 시간 경과·근거리·인증 실패·매칭 미사용으로 소리 보류가 해제되지 않는다. 방향을 아는 카메라는 기존 안내와 접근 복구를 유지한다.
- 유효 도로명이 만료되면 반복음과 준비·재생 중 카메라 음성을 취소한다. 복구 후 접근 근거를 다시 확인하며, 합성·딩동 대기 후 재생 직전에도 소리 허용 여부를 검사한다.
- 다른 도로로 확인된 후보는 화면에서도 제외한다. 도로 근거가 없는 후보는 화면에 남길 수 있어, 매칭을 사용 중이라는 이유만으로 화면 제외를 기대하지 않는다. 연속 단속 예고에도 뒤쪽 카메라 자체의 소리 근거를 요구한다.
- 2026-10-04 수원 재현의 옆길 30km/h 음성은 오프라인 1회 → 0회, 경고음 13초 → 0초다. 같은 경로의 기존 50km/h 음성은 두 모드에서 각 1회 유지됐다. 매칭 모드는 옆길 소리 0회를 유지하고 근거 공백 중 화면 후보만 남는다.
- 전국 양방향 경로 12개의 방향 미상 필수 후보는 오프라인에서 화면을 유지하며 음성·경고음을 보류한다. 매칭 모드에서는 11개 경로의 필수 음성을 유지한다. 나머지 충청 정방향은 후보가 49~52초에 보이고 도로명은 58초에야 확인돼 화면 전용으로 바뀐다. 이는 승인된 정확도 우선 정책의 실제 카메라 음성 누락 사례이며, 오탐 제거 성공으로 계산하지 않는다. 반대 방향의 동일 카메라는 매칭 음성을 유지한다.
- 교차도로 2개 경로는 기존 화면·음성 제외 기대값을 유지한다. 실차의 GPS 품질·실제 소리 출력·동일 이름의 평행도로 구분은 이 시뮬레이션으로 보장하지 않는다.
- 방향 미상 오프라인·도로 축 단독 근거 검사 2개는 변경 전 실패를 확인했다. 변경 후 관련 단위 검사 55개, 수원 18개·전국 28개 캐시 주행 조건, 전체 비이미지 테스트와 release 빌드가 통과했다. 재현 중 서버 호출·캐시 누락은 없었다.

### 방향 표기 누락과 개별 카메라 근거 복구

- `tools/camera_directions.py`는 단일 `->`와 `→`를 같은 진행 화살표로 읽는다. 양방향·다중 화살표·빈 출발/도착은 추정하지 않으며, 기존 지명 조회와 지도 방향 검증을 통과해야 결과를 만든다.
- `tools/verified_camera_context.json`은 원자료와 지도 선형을 개별 대조한 카메라만 담는다. 생성기에서 ID·위치·속도·구간 종류·기준일·원도로명·설치 방향 원문이 일치할 때 적용하고, 같은 ID가 중복되거나 원자료가 바뀌면 건너뛴다. 도로 전체의 이름을 동치로 취급하지 않는다.
- `1320000:G2310` 한 건은 원문 `이의동->구성동`과 지도 동행 선형을 대조해 방향 90도·지도 도로명 `석성로`를 보강했다. 나머지 번들 레코드와 앱의 후보·음성 허용 조건은 변경하지 않았다. 공공 카메라 근거만 공개하고 재현 경로·지도 캐시·보고서는 `local-replay/`에 둔다.
- 2026-10-05 지도 선형 기반 66km/h 재현에서 해당 카메라의 동행 음성은 매칭·오프라인 모두 0→2회, 경고음은 0→26초/24초로 복구됐다. 역방향은 두 모드 모두 후보·음성·경고음 0회다.
- `1320000:H3410` 고속도로 카메라는 교차하는 일반도로 재현에서 두 모드 모두 음성·경고음 0회를 유지한다. 화면 후보까지 제거한 것은 아니다. 총 3경로×2모드 검증이며 실차 소리 출력 검증은 아니다.
- 지도 원본 읽기 전용 도구로 캐시를 만든 뒤 재현했다. 재현 중 운영 API·Fleet API 호출과 캐시 누락은 없었으며, 앱의 지도 요청 주기도 그대로다.
- 고정 회귀: `python3 -B -m unittest discover -s tools -p 'test_camera_directions.py'`, `python3 -B -m unittest discover -s tools -p 'test_update_safety_cameras.py'`, `CameraIndexTest.verifiedBundleContextRestoresOnlySupportedDirection`. 방향 추출·번들 회귀는 변경 전 실패를 확인했다.

### 명시적 방위와 실제 도로 구간을 함께 보강

- `tools/camera_direction_context.py`는 저장된 원자료·번들·지도 선형만 읽는다. `남→북`처럼 서로 반대인 방위가 명시된 경우에만 방향 후보를 만들며, 지명→지명·양방향 표기·도로 축에서 단속 방향을 추정하지 않는다.
- 같은 도로명으로 카메라 20m 안에 있는 **모든** 선분을 먼저 조사한다. 유일한 노드 쌍만 허용하고, 교차·평행 구간 중 각도가 맞는 것만 선택하지 않는다. 길이 5~250m, 원문 방위와 지도 진행방위 차이 30° 이하, 기존 축과 차이 20° 이하를 요구한다. 인접 선분이 겹치는 교차로도 보수적으로 보류한다.
- 결과는 기존 `verified_camera_context.json` 형식이다. 원자료 지문·설치 원문에 더해 지도 way ID, **진행 순서가 있는** 노드 쌍·선분 좌표·입력 선형 파일 SHA-256을 남긴다. 기존 방향은 덮어쓰지 않는다. 생성기의 `apply_verified_context`는 자동 근거의 원문과 선분을 다시 계산해 검증한다.
- SHA-256은 조사한 입력의 추적용이다. 실행 중 서버 지도 버전을 자동 확인하는 기능이나 지도 갱신에 따른 자동 재검증은 아니다. 새 지도에서는 다시 생성·검토해야 한다. 앱에는 기존 `direction`·`roadName` 필드로 적용하므로 추가 서버 호출이 없다.
- 2026-10-05 전체 원자료 43,724건과 번들 12,012건을 비교한 결과, 명시적 방위·유일한 지도 구간까지 검증한 `1320000:H7586`(신흥로, 50km/h)에 북행 0°를 추가했다. 원문 `내동사거리(남→북)`, 도로 이격 약 4.4m, 지도 진행방위 약 359.8°. 나머지 12,011건과 기존 앱 알림 정책은 그대로다. 지명 해석·추가 조사 없이 일반 지명 화살표 581건을 일괄 승격하지 않는다.
- 새 번들 고정 회귀는 변경 전 실패를 확인했다. 지도 기반 북행·남행 및 기존 기흥 정·역방향/고속도로 교차 재현 5경로×2모드가 통과했다. 북행 음성 2회, 남행 후보·음성 0회, 교차하는 일반도로의 고속도로110 음성 0회. 실제 차량의 출력 검증은 아니다.
- 재사용: `tools/camera_directions.py:22`의 `direction_places`와 `bearing/diff/meters`, `tools/update_safety_cameras.py:26`의 `convert`와 `comparable_road_name/apply_verified_context`, `tools/replay/make_route.py:43`의 `sample`, `SafeDriveReplayTest`. 기존 `refine_camera_road_axes`는 양방향 도로 축만 검증하므로 단속 방향 생성에는 사용하지 않았다.

```bash
python3 -B tools/camera_direction_context.py \
  --raw local-replay/artifacts/directed_camera_context/source-rows.json \
  --bundle local-replay/artifacts/directed_camera_context/dataset-before.json \
  --shapes local-replay/artifacts/directed_camera_context/road-shapes.jsonl \
  --output local-replay/artifacts/directed_camera_context/candidate.json \
  --report local-replay/artifacts/directed_camera_context/candidate-report.json
python3 -B -m unittest discover -s tools -p 'test_camera_direction*.py'
python3 -B -m unittest discover -s tools -p 'test_update_safety_cameras.py'
```
