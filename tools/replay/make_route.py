#!/usr/bin/env python3
"""OSRM 경로를 1초 간격 가상 GPS로 바꿔 JVM 주행 재현 테스트(SafeDriveReplayTest) 입력을 만든다.

사용: python3 make_route.py <osrm 주소> <출력 json> <속도 km/h> <경도,위도> <경도,위도> [...]
경로 좌표는 실제 생활 동선일 수 있어 공개 저장소에 올리지 않는다(local-replay/ 는 Git 제외).
"""
import json
import math
import random
import sys
import urllib.request


# 두 좌표 사이 거리(m)와 진행방향(도)을 평면 근사로 구한다. 수 m 간격이라 오차가 무시할 수준이다.
def step(a, b):
    north = (b[1] - a[1]) * 111195.0
    east = (b[0] - a[0]) * 111195.0 * math.cos(math.radians((a[1] + b[1]) / 2))
    return math.hypot(north, east), (math.degrees(math.atan2(east, north)) + 360) % 360


# 1. 경유지 순서대로 OSRM 경로 선형과 구간별 도로명을 받는다.
def route(server, points):
    path = ";".join(points)
    url = f"{server}/route/v1/driving/{path}?overview=full&geometries=geojson&steps=true"
    with urllib.request.urlopen(url, timeout=30) as response:
        result = json.load(response)
    if result.get("code") != "Ok":
        raise RuntimeError("경로 없음: " + str(result.get("code")))
    names = [step_info.get("name") or "" for leg in result["routes"][0]["legs"] for step_info in leg["steps"]]
    return result["routes"][0]["geometry"]["coordinates"], names


# 2. 선형을 일정 속도로 따라가며 1초마다 위치를 찍고, 정해진 난수로 GPS 흔들림을 넣는다.
def sample(line, speed_kph, seed=7):
    random_source = random.Random(seed)
    speed = speed_kph / 3.6
    samples, carried = [], 0.0
    for a, b in zip(line, line[1:]):
        length, bearing = step(a, b)
        if length < 0.5:
            continue
        position = carried
        while position <= length:
            ratio = position / length
            longitude = a[0] + (b[0] - a[0]) * ratio
            latitude = a[1] + (b[1] - a[1]) * ratio
            # 횡방향 3m·방향 3도 수준의 흔들림으로 실제 GPS처럼 옆 도로 경계에서 흔들리게 한다.
            offset = random_source.gauss(0, 3.0)
            normal = math.radians(bearing + 90)
            latitude += offset * math.cos(normal) / 111195.0
            longitude += offset * math.sin(normal) / (111195.0 * math.cos(math.radians(latitude)))
            samples.append({
                "latitude": round(latitude, 7), "longitude": round(longitude, 7),
                "bearing": round((bearing + random_source.gauss(0, 3.0)) % 360, 1),
                "speedMps": round(speed, 2), "accuracy": 8.0,
            })
            position += speed
        carried = position - length
    return samples


def main():
    server, output, speed = sys.argv[1], sys.argv[2], float(sys.argv[3])
    line, names = route(server, sys.argv[4:])
    samples = sample(line, speed)
    with open(output, "w", encoding="utf-8") as file:
        json.dump({"speedKph": speed, "waypoints": sys.argv[4:], "roadNames": names, "samples": samples},
                  file, ensure_ascii=False)
    print(f"{output}: {len(samples)}초, 경유 도로 {len(set(names))}개")


if __name__ == "__main__":
    main()
