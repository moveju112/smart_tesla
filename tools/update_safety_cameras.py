#!/usr/bin/env python3
"""공공데이터포털의 공개 다운로드를 앱 내 오프라인 카메라 목록으로 변환한다."""
import argparse
import datetime
import json
import math
import re
from pathlib import Path
import urllib.parse
import urllib.request

BASE_URL = "https://www.data.go.kr"
SOURCE_URL = BASE_URL + "/data/15028200/standard.do"
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/safety_cameras.json"


# 공식 다운로드 화면과 동일한 읽기 요청만 사용한다.
def read_json(path, parameters):
    url = BASE_URL + path + "?" + urllib.parse.urlencode(parameters, doseq=True)
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


# 잘못된 좌표·속도와 속도 단속이 아닌 시설은 안내 후보에서 제외한다.
def convert(row):
    try:
        latitude = float(row["LATITUDE"])
        longitude = float(row["LONGITUDE"])
        limit = int(row["LMTT_VE"])
        # 소수 속도를 정수로 잘라 잘못된 제한속도로 배포하지 않는다.
        if isinstance(row["LMTT_VE"], float) and row["LMTT_VE"] != limit:
            return None
        category = str(row["REGLT_SE"]).strip()
        if not (33 <= latitude <= 39 and 124 <= longitude <= 132 and 10 <= limit <= 130):
            return None
        if category not in ("1", "01", "3", "03"):
            return None
        return {
            "id": str(row.get("INSTT_CODE", "")) + ":" + str(row["MNLSS_REGLT_CAMERA_MANAGE_NO"]),
            "latitude": latitude,
            "longitude": longitude,
            "speedLimitKph": limit,
            "section": str(row.get("REGLT_SCTN_LC_SE", "")).strip() in ("1", "2", "01", "02"),
            "referenceDate": str(row.get("REFERENCE_DATE", "")),
            # 도로 매칭 도로명과 대조해 옆 도로·교차 골목 카메라를 거르는 근거로 남긴다.
            "roadName": str(row.get("ROAD_ROUTE_NM") or "").strip(),
        }
    except (ValueError, TypeError, KeyError, OverflowError):
        return None


# 도로명 대조에 쓰는 이름과 같은 표기 정규화(앱 comparableRoadName과 동일)를 적용한다.
def comparable_road_name(name):
    name = re.sub(r"\s+", "", re.sub(r"\([^)]*\)", "", name or ""))
    return name if len(name) >= 2 and name.endswith(("로", "길", "지하차도", "고가차도")) else None


# 매칭 서버 지도에서 카메라 주변 실제 도로명으로 확인된 이름만 남긴다.
# 자료와 지도 이름이 다르면 비교할 때 실제 카메라를 거를 수 있어 비운다.
def keep_verified_road_names(cameras, probe_path):
    nearby = {}
    for line in open(probe_path, encoding="utf-8"):
        latitude, longitude, names = json.loads(line)
        nearby[(latitude, longitude)] = {comparable_road_name(name) for name in names}
    cleared = 0
    for camera in cameras:
        name = comparable_road_name(camera["roadName"])
        if camera["roadName"] and (name is None or name not in nearby.get((camera["latitude"], camera["longitude"]), set())):
            camera["roadName"] = ""
            cleared += 1
    print(f"지도 확인 안 된 도로명 {cleared}건 비움", flush=True)


# 공식 다운로드를 페이지 단위로 읽고 전체 건수가 맞을 때만 돌려준다.
def download():
    header = read_json("/download/columList.json", {"pk": "15028200", "ext": "csv"})
    total = int(header["totalCount"])
    rows = []
    for page in range(1, math.ceil(total / 10000) + 1):
        batch = read_json("/download/standard.json", {
            "publicDataPk": "15028200",
            "colNmList": header["tableVO"]["colNmList"],
            "svcTableNm": header["tableVO"]["svcTableNm"],
            "totalCount": total, "perPage": 10000, "page": page,
        })
        if not isinstance(batch, list) or not batch:
            raise RuntimeError("빈 페이지 또는 잘못된 응답: " + str(page))
        rows.extend(batch)
        print(f"공공데이터 읽기 {len(rows)}/{total}", flush=True)
    if len(rows) != total:
        raise RuntimeError("전체 건수가 달라 기존 파일을 유지합니다")
    return rows


# 방향 판정(tools/camera_directions.py)이 없는 카메라는 방향을 넣지 않아 앱이 방향 무관 판정을 유지한다.
def apply_directions(cameras, directions_path):
    directions = json.load(open(directions_path, encoding="utf-8"))
    applied = 0
    for camera in cameras:
        value = directions.get(camera["id"])
        # 반올림으로 360이 나올 수 있어 범위 검사 전에 0~359로 접는다.
        if isinstance(value, (int, float)) and math.isfinite(value):
            camera["direction"] = int(round(value)) % 360
            applied += 1
    print(f"단속 방향 {applied}건 적용", flush=True)



# 동일한 카메라 좌표와 지도 확인 도로명에 대해서만 이전에 검증한 축을 재사용한다.
def apply_road_axes(cameras, axes_path):
    axes = json.load(open(axes_path, encoding="utf-8"))
    applied = 0
    for camera in cameras:
        record = axes.get(camera["id"])
        if not isinstance(record, dict):
            continue
        axis = record.get("roadAxisDegrees")
        if (not isinstance(axis, int) or isinstance(axis, bool) or not 0 <= axis < 180
                or not comparable_road_name(camera["roadName"])
                or record.get("roadName") != camera["roadName"]
                or record.get("latitude") != camera["latitude"]
                or record.get("longitude") != camera["longitude"]):
            continue
        direction = camera.get("direction")
        if direction is not None and abs((direction - axis + 90) % 180 - 90) > 60:
            continue
        camera["roadAxisDegrees"] = axis
        applied += 1
    print(f"검증된 도로 축 {applied}건 적용", flush=True)


# 전체 페이지 수가 맞을 때만 교체하여 부분 다운로드를 전국 데이터로 배포하지 않는다.
def main():
    parser = argparse.ArgumentParser(description="공공데이터 → 앱 번들")
    parser.add_argument("--raw", help="이미 받은 원본 json을 재사용(없으면 공식 다운로드)")
    parser.add_argument("--save-raw", help="받은 원본 json 저장 경로(방향 판정 입력)")
    parser.add_argument("--road-names", help="tools/probe_camera_road_names.py 결과 jsonl")
    parser.add_argument("--directions", help="tools/camera_directions.py 결과 json")
    parser.add_argument("--road-axes", help="tools/camera_road_context.py 결과 id→카메라 위치·도로명·축 JSON")
    arguments = parser.parse_args()
    rows = json.load(open(arguments.raw, encoding="utf-8")) if arguments.raw else download()
    if arguments.save_raw:
        Path(arguments.save_raw).write_text(json.dumps(rows, ensure_ascii=False))
    total = len(rows)
    cameras = [camera for row in rows if (camera := convert(row)) is not None]
    if not cameras:
        raise RuntimeError("유효한 과속 카메라가 없습니다")
    # 도로명 대조는 매칭 서버 지도로 확인한 결과가 있어야 안전하므로 없으면 모두 비운다.
    if arguments.road_names:
        keep_verified_road_names(cameras, arguments.road_names)
    else:
        for camera in cameras:
            camera["roadName"] = ""
        print("지도 이름 목록 없음 → 도로명 대조 비활성", flush=True)
    if arguments.directions:
        apply_directions(cameras, arguments.directions)
    if arguments.road_axes:
        apply_road_axes(cameras, arguments.road_axes)
    data = {
        "schemaVersion": 1,
        "source": SOURCE_URL,
        "attribution": "경찰청·지방자치단체 / 공공데이터포털 전국무인교통단속카메라표준데이터",
        "retrievedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "sourceCount": total,
        "cameras": cameras,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n")
    temporary.replace(OUTPUT)
    print(f"저장: {OUTPUT} · {len(cameras)}개 · {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
