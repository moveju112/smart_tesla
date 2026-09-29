#!/usr/bin/env python3
"""검증된 번들 도로명에만 양방향 도로 축을 더한다 (단속 방향이 아님).

사용: python3 tools/camera_road_context.py --source local-replay/artifacts/safety_cameras.before_road_axis.json \
    --output app/src/main/assets/safety_cameras.json \
    --axes local-replay/artifacts/camera_road_axes.json
원격 개발용 match_worker는 project/osrm 안의 지도 데이터를 읽는다. 운영 API를 호출하지 않는다.
"""
import argparse
import collections
import json
import math
import subprocess
import time
from pathlib import Path

from update_safety_cameras import comparable_road_name

WORKER = ("ssh", "-T", "-o", "BatchMode=yes", "choondoggy",
          "cd project/osrm && LD_LIBRARY_PATH=.build/install/lib "
          ".build/install/bin/match_worker .data/south-korea.osrm")


# 가까운 두 지도점의 거리와 진행 방위를 계산한다.
def segment(a, b):
    latitude = math.radians((a[1] + b[1]) / 2)
    north = (b[1] - a[1]) * 111195
    east = (b[0] - a[0]) * 111195 * math.cos(latitude)
    return math.hypot(north, east), (math.degrees(math.atan2(east, north)) + 360) % 360


# 양방향 도로 축끼리의 최소 각도 차를 구한다.
def axis_difference(a, b):
    return abs((a - b + 90) % 180 - 90)


# 카메라까지 들어오는 가상 표본 세 개를 만든다. 실제 단속 방향을 뜻하지 않는다.
def synthetic_trace(camera, degree, now):
    latitude, longitude = camera["latitude"], camera["longitude"]
    angle = math.radians(degree)
    points = []
    for step, seconds in ((2, -4), (1, -2), (0, 0)):
        distance = 30 * step
        lon = longitude - distance * math.sin(angle) / (111195 * math.cos(math.radians(latitude)))
        lat = latitude - distance * math.cos(angle) / 111195
        points.append(f"{lon:.8f} {lat:.8f} {now + seconds} 10")
    return "3 " + " ".join(points) + "\n"


# 성공적으로 같은 도로에 매칭된 끝점 근처 마지막 긴 선분에서 도로 축을 읽는다.
def matched_axis(camera, result):
    if result.get("code") != "Ok":
        return None
    matches, tracepoints = result.get("matchings") or [], result.get("tracepoints") or []
    if len(matches) != 1 or len(tracepoints) != 3 or any(point is None for point in tracepoints):
        return None
    match = matches[0]
    confidence = match.get("confidence")
    if not isinstance(confidence, (int, float)) or not 0.8 <= confidence <= 1:
        return None
    if comparable_road_name(tracepoints[-1].get("name")) != comparable_road_name(camera["roadName"]):
        return None
    coordinates = match.get("geometry", {}).get("coordinates") or []
    if len(coordinates) < 2:
        return None
    camera_point = (camera["longitude"], camera["latitude"])
    if segment(camera_point, tracepoints[-1]["location"])[0] > 20:
        return None
    if segment(camera_point, coordinates[-1])[0] > 20:
        return None
    for a, b in zip(reversed(coordinates[:-1]), reversed(coordinates[1:])):
        length, heading = segment(a, b)
        if length >= 5:
            # 마지막으로 충분히 긴 선분도 카메라 주변 도로여야 한다.
            if segment(camera_point, b)[0] > 20:
                return None
            return heading % 180
    return None


# 일치하지 않는 접근을 버려 합의하는 대신 모든 유효 접근의 도로 축을 대조한다.
def consensus(votes):
    if len(votes) < 3:
        return None
    if any(axis_difference(a, b) > 20 for index, a in enumerate(votes) for b in votes[index + 1:]):
        return None
    sine = sum(math.sin(math.radians(2 * angle)) for angle in votes)
    cosine = sum(math.cos(math.radians(2 * angle)) for angle in votes)
    return round(math.degrees(math.atan2(sine, cosine)) / 2) % 180


# 단속 방향과 도로 축 사이의 양방향 차이만 검사한다 (방향을 역추론하지 않는다).
def known_conflict(camera, axis):
    direction = camera.get("direction")
    return direction is not None and axis_difference(direction, axis) > 60


# 워커 장애 시 기존 번들을 유지하고 완료한 경우에만 입력의 필드를 보존해 출력한다.
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="원본 번들 백업")
    parser.add_argument("--output", type=Path, required=True, help="보강할 번들")
    parser.add_argument("--axes", type=Path, required=True, help="차후 원본 갱신에 재사용할 id→도로축 JSON")
    args = parser.parse_args()
    if args.source.resolve() == args.output.resolve():
        parser.error("원본과 출력은 서로 달라야 합니다")
    bundle = json.loads(args.source.read_text(encoding="utf-8"))
    cameras = bundle["cameras"]
    if any("roadAxisDegrees" in camera for camera in cameras):
        parser.error("원본에 이미 도로 축이 있습니다; 보강 전 백업을 지정하세요")
    stats = collections.Counter()
    axes = {}
    process = subprocess.Popen(WORKER, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, bufsize=1)
    try:
        if process.stdout.readline().strip() != "READY":
            raise RuntimeError("개발용 match_worker 시작 실패")
        now = int(time.time())
        for camera in cameras:
            stats["total"] += 1
            if not comparable_road_name(camera.get("roadName")):
                stats["no_verified_road"] += 1
                continue
            stats["named"] += 1
            votes = []
            for degree in range(0, 360, 30):
                process.stdin.write(synthetic_trace(camera, degree, now))
                process.stdin.flush()
                response = process.stdout.readline()
                if not response:
                    raise RuntimeError("매칭 워커가 조기 종료했습니다")
                axis = matched_axis(camera, json.loads(response))
                if axis is not None:
                    votes.append(axis)
            stats["valid_approaches"] += len(votes)
            if len(votes) < 3:
                stats["insufficient"] += 1
                continue
            axis = consensus(votes)
            if axis is None:
                stats["disputed"] += 1
                continue
            if camera.get("direction") is not None:
                stats["known_consensus"] += 1
            if known_conflict(camera, axis):
                stats["known_conflict"] += 1
                continue
            camera["roadAxisDegrees"] = axis
            axes[camera["id"]] = {"latitude": camera["latitude"], "longitude": camera["longitude"],
                                  "roadName": camera["roadName"], "roadAxisDegrees": axis}
            stats["known_applied" if camera.get("direction") is not None else "unknown_applied"] += 1
    finally:
        process.stdin.close()
        process.stdout.close()
        process.wait(timeout=30)
    if stats["total"] != len(cameras) or stats["named"] != stats["insufficient"] + stats["disputed"] + stats["known_conflict"] + stats["known_applied"] + stats["unknown_applied"]:
        raise RuntimeError("집계 오류: 번들 변경 안 함")
    args.axes.parent.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.axes.write_text(json.dumps(axes, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    temp = args.output.with_suffix(".json.tmp")
    temp.write_text(json.dumps(bundle, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    temp.replace(args.output)
    print(json.dumps(dict(stats), ensure_ascii=False, sort_keys=True), flush=True)
    print(f"bundle_bytes={args.output.stat().st_size} axes_bytes={args.axes.stat().st_size}", flush=True)


if __name__ == "__main__":
    main()
