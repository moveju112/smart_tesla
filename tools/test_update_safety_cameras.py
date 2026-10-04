import copy
import json
from pathlib import Path
import tempfile
import unittest

from update_safety_cameras import apply_verified_context, convert


class VerifiedCameraContextTest(unittest.TestCase):
    # 원문과 지도 이름이 다른 가상 카메라의 검증 자료를 준비한다.
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "verified.json"
        self.row = {"INSTT_CODE": "fixture", "MNLSS_REGLT_CAMERA_MANAGE_NO": "camera",
                    "LATITUDE": "37.0", "LONGITUDE": "127.0", "LMTT_VE": "60", "REGLT_SE": "1",
                    "ROAD_ROUTE_NM": "옛이름로", "REFERENCE_DATE": "2026-01-01", "ITLPC": "가상서동->가상동동"}
        source = convert(self.row)
        self.context = {source["id"]: {"source": {k: v for k, v in source.items() if k != "id"},
                                     "installation": self.row["ITLPC"], "roadName": "현재이름로", "direction": 90}}
        self.path.write_text(json.dumps(self.context))

    # 기존 검증 과정에서 이름이 비워져도 원문이 일치하는 한 카메라만 복원한다.
    def test_verified_camera_restores_name_and_direction(self):
        camera = convert(self.row)
        camera["roadName"] = ""
        other = dict(camera, id="fixture:other")
        before = copy.deepcopy(other)
        self.assertEqual(1, apply_verified_context([camera, other], [self.row], self.path))
        self.assertEqual("현재이름로", camera["roadName"])
        self.assertEqual(90, camera["direction"])
        self.assertEqual(before, other)

    # 위치·속도·단속 종류·기준일·원도로명·설치 방향이 달라지면 과거 근거를 재사용하지 않는다.
    def test_changed_source_is_not_overridden(self):
        for key, value in {"LATITUDE": "37.001", "LONGITUDE": "127.001", "LMTT_VE": "50",
                           "REGLT_SCTN_LC_SE": "1", "REFERENCE_DATE": "2026-02-01",
                           "ROAD_ROUTE_NM": "다른로", "ITLPC": "가상동동->가상서동"}.items():
            with self.subTest(key=key):
                row = dict(self.row, **{key: value})
                camera = convert(row)
                before = copy.deepcopy(camera)
                self.assertEqual(0, apply_verified_context([camera], [row], self.path))
                self.assertEqual(before, camera)

    # 동일 ID가 중복돼 어느 원문이 맞는지 모르면 보강하지 않는다.
    def test_duplicate_source_is_not_overridden(self):
        camera = convert(self.row)
        self.assertEqual(0, apply_verified_context([camera], [self.row, self.row], self.path))
        self.assertNotIn("direction", camera)

    # 다른 번들의 좌표에 원문 검증을 잘못 붙이지 않는다.
    def test_mismatched_bundle_is_not_overridden(self):
        camera = dict(convert(self.row), longitude=127.01)
        self.assertEqual(0, apply_verified_context([camera], [self.row], self.path))

    # 범위를 벗어난 검증 방향은 조용히 배포하지 않고 생성을 실패시킨다.
    def test_invalid_verified_direction_fails(self):
        self.context["fixture:camera"]["direction"] = 360
        self.path.write_text(json.dumps(self.context))
        with self.assertRaises(ValueError):
            apply_verified_context([convert(self.row)], [self.row], self.path)


if __name__ == "__main__":
    unittest.main()
