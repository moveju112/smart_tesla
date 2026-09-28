# 주행 재현 (집에서 단속 안내 검증)

실차 없이 실제 도로를 따라가는 1초 간격 가상 GPS를 앱의 실제 안내 로직(`SafeDriveGuide`)에 넣는다.
카메라 후보·음성 문구·경고음 구간을 초 단위 보고서로 남긴다.
경로는 생활 동선일 수 있어 Git 제외 폴더 `local-replay/`에만 둔다. 공개 저장소에 올리지 않는다.

## 1. 경로 만들기 (매칭 서버의 개발용 경로 계산기 사용)

```bash
# 서버에서 경로 계산기를 잠시 띄운다(운영 매칭 서비스 gps-map과 별개, 끝나면 종료)
ssh -n choondoggy 'cd project/osrm && LD_LIBRARY_PATH=.build/install/lib nohup nice -n 15 .build/install/bin/osrm-routed --algorithm mld -i 127.0.0.1 -p 5055 -t 1 .data/south-korea.osrm > /dev/null 2>&1 < /dev/null &'
ssh -f -o ExitOnForwardFailure=yes -N -L 15055:127.0.0.1:5055 -L 18091:127.0.0.1:8091 choondoggy < /dev/null   # 경로 계산·매칭 터널
# 경유지는 "경도,위도" 순서. 속도는 km/h
python3 tools/replay/make_route.py http://127.0.0.1:15055 local-replay/<이름>.json 50 <출발> <경유...> <도착>
```

- 지번 주소는 무료 지오코더에서 잘 안 잡힌다. 지도 앱에서 길게 눌러 복사한 좌표를 쓰는 편이 정확하다.
- 경로는 최단 시간 기준이다. 실제로 다니는 길과 다르면 그 길 위의 점을 경유지로 추가한다.
- 끝나면 서버에서 경로 계산기를 끈다: `ssh -n choondoggy 'pkill -x osrm-routed'` (`pkill -f "<명령줄>"`은 원격 셸 자신도 맞아 종료 코드 144로 끊긴다)
- 백그라운드 `ssh ... nohup ... &`는 세션이 안 닫힐 수 있다. 터널은 `ssh -f -o ExitOnForwardFailure=yes -N -L 15055:127.0.0.1:5055 -L 18091:127.0.0.1:8091 choondoggy < /dev/null`로 띄운다.

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
