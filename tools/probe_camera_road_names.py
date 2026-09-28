#!/usr/bin/env python3
# 매칭 서버에서 실행: python3 probe_camera_road_names.py <match_worker> <south-korea.osrm> <좌표 파일: 위도 경도/줄> <출력 jsonl>
# 카메라 좌표로 끝나는 12방향 가상 궤적을 매칭해 카메라 주변 실제 지도 도로명을 모은다.
import json, math, subprocess, sys, time
worker, data, source, target = sys.argv[1:5]
process = subprocess.Popen([worker, data], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
assert process.stdout.readline().strip() == "READY"
now = int(time.time())
out = open(target, "w")
for line in open(source):
    latitude, longitude = map(float, line.split())
    names = set()
    for degree in range(0, 360, 30):
        radians = math.radians(degree)
        parts = []
        for step, seconds in ((2, -4), (1, -2), (0, 0)):
            meters = 30 * step
            parts.append(f"{longitude - meters * math.sin(radians) / (111195 * math.cos(math.radians(latitude)))} "
                         f"{latitude - meters * math.cos(radians) / 111195} {now + seconds} 10")
        process.stdin.write("3 " + " ".join(parts) + "\n")
        result = json.loads(process.stdout.readline())
        matchings, tracepoints = result.get("matchings") or [], result.get("tracepoints") or []
        if (result.get("code") == "Ok" and len(matchings) == 1 and all(tracepoints) and
                0.8 <= (matchings[0].get("confidence") or 0) <= 1):
            name = tracepoints[-1].get("name")
            if name:
                names.add(name.strip())
    out.write(json.dumps([latitude, longitude, sorted(names)], ensure_ascii=False) + "\n")
out.close()
