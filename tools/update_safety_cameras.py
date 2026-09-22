#!/usr/bin/env python3
"""공공데이터포털의 공개 다운로드를 앱 내 오프라인 카메라 목록으로 변환한다."""
import datetime
import json
import math
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
        }
    except (ValueError, TypeError, KeyError, OverflowError):
        return None


# 전체 페이지 수가 맞을 때만 교체하여 부분 다운로드를 전국 데이터로 배포하지 않는다.
def main():
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
    cameras = [camera for row in rows if (camera := convert(row)) is not None]
    if not cameras:
        raise RuntimeError("유효한 과속 카메라가 없습니다")
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
