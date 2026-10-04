#!/usr/bin/env python3
"""설치 장소의 (A→B) 방향 글자로 카메라별 단속 방향(진행방위, 도)을 만든다.

사용: python3 camera_directions.py <공공데이터 원본 json> <번들 json> <OSM 이름 json> <osrm 주소> <출력 json>
- OSM 이름 json: tools/extract_osm_names.py 결과(서버 .data/south-korea.osm.pbf).
- osrm 주소: 매칭 서버의 개발용 osrm-routed(터널). 외부 지오코더를 쓰지 않는다.
판정 못 한 카메라는 출력하지 않는다. 앱의 방향 미상 후보·소리 근거 정책을 유지한다.
"""
import collections
import json
import math
from pathlib import Path
import re
import sys
import urllib.request

R = "http://127.0.0.1:15055"
names = {}
arrow = re.compile(r"([^()→]+)→([^()→]+)")

# 단일 진행 화살표의 표기만 통일하고 양방향·경유지 나열은 단속 방향으로 추정하지 않는다.
def direction_places(text):
    normalized = text.replace("->", "→")
    if normalized.count("→") != 1 or any(marker in normalized for marker in ("<", ">", "←", "↔")):
        return None
    match = arrow.search(normalized)
    if match is None:
        return None
    places = tuple(part.strip() for part in match.groups())
    return places if all(places) else None

# 수백 m~수십 km 거리 판정용 평면 근사 거리(m).

def meters(a, b, c, d):
    return math.hypot((a - c) * 111195, (b - d) * 111195 * math.cos(math.radians(a)))
# 초교→초등학교, 네거리↔사거리, 짧은 지명+시/군/읍 등 표기 변형을 순서대로 만든다.

def variants(text):
    base = re.sub(r"\s+", "", text)
    base = re.sub(r"(방향|방면|쪽|앞|부근|전방\d*M?|후방\d*M?|\d+m전|\d+M전)$", "", base)
    out = [base]
    rules = [("초교$", "초등학교"), ("초$", "초등학교"), ("중교$", "중학교"), ("여중$", "여자중학교"), ("중$", "중학교"),
             ("여고$", "여자고등학교"), ("고교$", "고등학교"), ("고$", "고등학교"), ("네거리$", "사거리"), ("사거리$", "네거리"),
             ("교차로$", "사거리"), ("사거리$", "교차로"), ("IC$", "나들목"), ("IC$", "IC"), ("JC$", "분기점"), ("TG$", "요금소")]
    for pattern, replacement in rules:
        if re.search(pattern, base):
            out.append(re.sub(pattern, replacement, base))
    if len(base) <= 4 and not re.search(r"(시|군|구|읍|면|동|리|역)$", base):
        out += [base + s for s in ("시", "군", "읍", "면", "동", "리", "역")]
    return out
# 카메라에서 100m~40km 안의 같은 이름 중 가장 가까운 지점을 고른다.

def find(text, lat, lon, limit=40000):
    best = None
    for name in variants(text):
        for plat, plon, kind in names.get(name, []):
            d = meters(lat, lon, plat, plon)
            if 100 <= d <= limit and (best is None or d < best[0]):
                best = (d, plat, plon, name)
        if best: break
    return best

# 카메라→B, A→카메라 경로의 카메라 부근 진행방향을 단속 방향으로 삼는다. 두 근거가 어긋나거나 우회가 의심되면 판정하지 않는다.
# 두 좌표의 진행방위(도, 북=0).

def bearing(a, b):  # a,b = (lat, lon)
    n = (b[0]-a[0])*111195; e = (b[1]-a[1])*111195*math.cos(math.radians(a[0]))
    return (math.degrees(math.atan2(e, n)) + 360) % 360
# 두 방위의 최소 각도 차.

def diff(a, b): return abs((a - b + 180) % 360 - 180)
# 개발용 osrm-routed로 경로 선형(위도,경도 목록)을 받는다. 실패는 None.

def route(p, q):
    url = f"{R}/route/v1/driving/{p[1]},{p[0]};{q[1]},{q[0]}?overview=full&geometries=geojson"
    try:
        r = json.load(urllib.request.urlopen(url, timeout=10))
    except Exception:
        return None
    if r.get("code") != "Ok": return None
    return [(c[1], c[0]) for c in r["routes"][0]["geometry"]["coordinates"]]
# 선형을 따라 지정 거리만큼 간 지점. reverse면 끝에서 거꾸로 잰다.

def along(line, meters_needed, reverse=False):
    pts = list(reversed(line)) if reverse else line
    total = 0
    for a, b in zip(pts, pts[1:]):
        total += meters(a[0], a[1], b[0], b[1])
        if total >= meters_needed: return b
    return None

def through(cam, a, b):
    # A→B 경로가 카메라 40m 안을 지나면 그 지점의 진행방향을 쓴다. 출발 차로 오인(우회)을 피한다.
    line = route((a[1], a[2]), (b[1], b[2]))
    if not line: return None
    best = min(range(len(line)), key=lambda i: meters(cam[0], cam[1], line[i][0], line[i][1]))
    if meters(cam[0], cam[1], line[best][0], line[best][1]) > 40: return None
    before = along(line[:best + 1], 30, reverse=True) or line[max(best - 1, 0)]
    after = along(line[best:], 30) or line[min(best + 1, len(line) - 1)]
    return bearing(before, after) if before != after else None

def direction(cam, a, b):
    if a and b:
        d = through(cam, a, b)
        if d is not None: return round(d), "through"
    votes = []
    if b:
        line = route(cam, (b[1], b[2]))
        if line and meters(cam[0], cam[1], line[0][0], line[0][1]) <= 40:
            p = along(line, 60)
            if p:
                d = bearing(line[0], p)
                if diff(d, bearing(cam, (b[1], b[2]))) <= 90: votes.append(d)
                else: return None, "detour"
    if a:
        line = route((a[1], a[2]), cam)
        if line and meters(cam[0], cam[1], line[-1][0], line[-1][1]) <= 40:
            p = along(line, 60, reverse=True)
            if p:
                d = bearing(p, line[-1])
                if diff(d, bearing((a[1], a[2]), cam)) <= 90: votes.append(d)
                else: return None, "detour"
    if not votes: return None, "noroute"
    if len(votes) == 2 and diff(votes[0], votes[1]) > 60: return None, "conflict"
    x = sum(math.sin(math.radians(v)) for v in votes); y = sum(math.cos(math.radians(v)) for v in votes)
    return round((math.degrees(math.atan2(x, y)) + 360) % 360), "ok%d" % len(votes)

# 1. 원본의 방향 글자 → 지명 좌표 → 경로 방향. 2. 번들 카메라 id별 방향을 저장한다.

def main():
    global R, names
    raw_path, bundle_path, names_path, R, output = sys.argv[1:6]
    names = json.loads(Path(names_path).read_text(encoding="utf-8"))
    rows = json.loads(Path(raw_path).read_text(encoding="utf-8"))
    raw = {str(r.get("INSTT_CODE", "")) + ":" + str(r["MNLSS_REGLT_CAMERA_MANAGE_NO"]): r for r in rows}
    cameras = json.loads(Path(bundle_path).read_text(encoding="utf-8"))["cameras"]
    result, stats = {}, collections.Counter()
    for camera in cameras:
        places = direction_places(str(raw.get(camera["id"], {}).get("ITLPC", "")))
        if not places:
            stats["noarrow"] += 1
            continue
        point = (camera["latitude"], camera["longitude"])
        start, end = find(places[0], *point), find(places[1], *point)
        value, reason = direction(point, start, end) if (start or end) else (None, "nogeo")
        stats[reason] += 1
        if value is not None:
            result[camera["id"]] = value
    Path(output).write_text(json.dumps(result), encoding="utf-8")
    print(dict(stats), f"방향 {len(result)}/{len(cameras)}")


if __name__ == "__main__":
    main()
