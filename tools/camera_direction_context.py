#!/usr/bin/env python3
"""원문의 명시적 방위와 유일한 지도 선분을 대조해 재검증 가능한 단속 방향 근거를 만든다.

입력은 저장된 원자료·번들·도로 선형이다. 네트워크를 호출하거나 앱 번들을 덮어쓰지 않는다.
지명→지명, 도로 축만 있는 자료, 교차로·평행도로의 모호한 연결은 보류한다.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path

from camera_directions import bearing, diff, direction_places, meters

CARDINALS = {"북": 0, "북동": 45, "동": 90, "남동": 135,
             "남": 180, "남서": 225, "서": 270, "북서": 315}


# 지명 일부를 방위로 읽지 않고 서로 반대인 두 방위가 명시된 화살표만 인정한다.
def explicit_direction(installation):
    places = direction_places(installation)
    if not places or any(place not in CARDINALS for place in places):
        return None
    start, end = (CARDINALS[place] for place in places)
    return end if diff(start, end) == 180 else None


# 카메라와 지도 선분의 최단 거리를 구해 선분 연장선상의 다른 도로를 붙이지 않는다.
def segment_gap(camera, coordinates):
    start, end = coordinates
    scale = math.cos(math.radians(camera["latitude"]))
    east = (start[0] - camera["longitude"]) * scale
    north = start[1] - camera["latitude"]
    dx, dy = (end[0] - start[0]) * scale, end[1] - start[1]
    length = dx * dx + dy * dy
    ratio = max(0, min(1, -(east * dx + north * dy) / length)) if length else 0
    return math.hypot(east + ratio * dx, north + ratio * dy) * 111195


# 생성기에서도 원문·진행 순서·지도 거리·방위 일치를 다시 검사한다. 축에서 방향을 만들지 않는다.
def validate_evidence(camera, installation, evidence):
    nominal = explicit_direction(installation)
    coordinates = evidence.get("coordinates")
    nodes = evidence.get("approachNodes")
    fingerprint = evidence.get("shapeSnapshotSha256", "")
    if nominal is None or evidence.get("kind") != "cardinal-arrow" or \
            len(fingerprint) != 64 or any(char not in "0123456789abcdef" for char in fingerprint):
        raise ValueError("명시적 방향 또는 지도 스냅샷 근거 없음")
    if type(evidence.get("osmWayId")) is not int or evidence["osmWayId"] <= 0 or \
            not isinstance(nodes, list) or len(nodes) != 2 or \
            any(type(node) is not int or not 0 < node < 2 ** 53 for node in nodes) or nodes[0] == nodes[1]:
        raise ValueError("잘못된 도로 구간 식별자")
    if not isinstance(coordinates, list) or len(coordinates) != 2 or any(
            not isinstance(point, list) or len(point) != 2 or
            any(type(value) not in (int, float) or not math.isfinite(value) for value in point) or
            not (124 <= point[0] <= 132 and 33 <= point[1] <= 39) for point in coordinates):
        raise ValueError("잘못된 도로 선분 좌표")
    start, end = [(point[1], point[0]) for point in coordinates]
    heading = bearing(start, end)
    if not 5 <= meters(*start, *end) <= 250 or segment_gap(camera, coordinates) > 20 or diff(heading, nominal) > 30:
        raise ValueError("단속 방향과 도로 선분 근거 불일치")
    axis = camera.get("roadAxisDegrees")
    if axis is not None and (type(axis) not in (int, float) or not 0 <= axis < 180 or
                             abs((heading - axis + 90) % 180 - 90) > 20):
        raise ValueError("기존 도로 축과 단속 방향 근거 불일치")
    return round(heading) % 360


# 같은 이름의 모든 근접 선분을 먼저 검사해 평행 구간을 방향 각도로 임의 선택하지 않는다.
def linked_evidence(camera, installation, ways, snapshot):
    nominal = explicit_direction(installation)
    if nominal is None:
        return None, "no_explicit_direction"
    nearby = {}
    for way in ways:
        for index, points in enumerate(zip(way["coordinates"], way["coordinates"][1:])):
            if segment_gap(camera, points) > 20:
                continue
            nodes = way["nodes"][index:index + 2]
            key = tuple(sorted(nodes))
            # 동일 구간 중복도 선형이 다르면 무결성을 확정할 수 없다.
            if key in nearby and nearby[key]["coordinates"] not in (list(points), list(reversed(points))):
                return None, "conflicting_geometry"
            coordinates = list(points)
            if diff(bearing(tuple(reversed(points[0])), tuple(reversed(points[1]))), nominal) > 90:
                nodes, coordinates = nodes[::-1], coordinates[::-1]
            nearby[key] = {"kind": "cardinal-arrow", "shapeSnapshotSha256": snapshot,
                           "osmWayId": way["id"], "approachNodes": nodes, "coordinates": coordinates}
    if len(nearby) != 1:
        return None, "ambiguous_link" if nearby else "no_nearby_link"
    evidence = next(iter(nearby.values()))
    try:
        direction = validate_evidence(camera, installation, evidence)
    except ValueError:
        return None, "direction_geometry_conflict"
    return (direction, evidence), "verified"


# 기존 방향은 보존하고 원자료가 정확히 일치하는 방향 미상 카메라만 보강 후보로 만든다.
def generate_context(rows, cameras, ways, snapshot):
    from update_safety_cameras import comparable_road_name, convert
    sources = collections.defaultdict(list)
    for row in rows:
        source = convert(row)
        if source:
            sources[source["id"]].append((source, str(row.get("ITLPC") or "")))
    named = collections.defaultdict(list)
    for way in ways:
        if len(way["nodes"]) != len(way["coordinates"]) or len(way["nodes"]) < 2:
            raise ValueError("불완전한 도로 선형")
        name = comparable_road_name(way["name"])
        if name:
            named[name].append(way)
    counts = collections.Counter(camera["id"] for camera in cameras)
    contexts, report = {}, {}
    for camera in cameras:
        key = camera["id"]
        if camera.get("direction") is not None:
            report[key] = "existing_direction"
            continue
        if len(sources[key]) != 1 or counts[key] != 1:
            report[key] = "ambiguous_source"
            continue
        source, installation = sources[key][0]
        if any(camera.get(field) != value for field, value in source.items() if field != "roadName"):
            report[key] = "source_changed"
            continue
        name = comparable_road_name(source["roadName"])
        current_name = comparable_road_name(camera.get("roadName"))
        if not name or current_name not in (None, name):
            report[key] = "road_name_conflict"
            continue
        result, report[key] = linked_evidence(camera, installation, named[name], snapshot)
        if result is not None:
            direction, evidence = result
            contexts[key] = {"source": {field: value for field, value in source.items() if field != "id"},
                             "installation": installation, "roadName": source["roadName"],
                             "direction": direction, "evidence": evidence}
    return contexts, report


# 캐시 입력만 사용해 검토 가능한 근거와 보류 사유를 별도 파일로 출력한다.
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("raw", "bundle", "shapes", "output", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    paths = [args.raw, args.bundle, args.shapes, args.output, args.report]
    if len({path.resolve() for path in paths}) != len(paths):
        parser.error("입력과 출력은 서로 다른 경로여야 합니다")
    shape_bytes = args.shapes.read_bytes()
    contexts, report = generate_context(json.loads(args.raw.read_text()),
        json.loads(args.bundle.read_text())["cameras"],
        [json.loads(line) for line in shape_bytes.decode().splitlines() if line.strip()],
        hashlib.sha256(shape_bytes).hexdigest())
    args.output.write_text(json.dumps(contexts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(dict(collections.Counter(report.values())))


if __name__ == "__main__":
    main()
