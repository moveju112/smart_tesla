import copy
import json
from pathlib import Path
import tempfile
import unittest

from camera_direction_context import explicit_direction, generate_context, linked_evidence, validate_evidence
from update_safety_cameras import apply_verified_context, convert


class DirectedCameraContextTest(unittest.TestCase):
    # 실주행 좌표 없이 직선·평행·교차 사례를 검증하는 가상 원자료와 지도 구간을 준비한다.
    def setUp(self):
        self.row = {"INSTT_CODE": "fixture", "MNLSS_REGLT_CAMERA_MANAGE_NO": "camera",
                    "LATITUDE": "37.0", "LONGITUDE": "127.0", "LMTT_VE": "50", "REGLT_SE": "1",
                    "ROAD_ROUTE_NM": "가상로", "REFERENCE_DATE": "2026-01-01", "ITLPC": "교차로(남->북)"}
        self.camera = convert(self.row)
        self.way = {"id": 1, "name": "가상로", "nodes": [1, 2],
                    "coordinates": [[127.0, 36.9995], [127.0, 37.0005]]}
        self.snapshot = "a" * 64

    # 자료를 생성한 뒤 실제 번들 생성기의 재검증을 거치는 전체 적용 경로를 검사한다.
    def test_generated_context_applies_with_reversed_osm_node_order(self):
        for reverse in (False, True):
            with self.subTest(reverse=reverse):
                way = copy.deepcopy(self.way)
                if reverse:
                    way["nodes"].reverse()
                    way["coordinates"].reverse()
                contexts, report = generate_context([self.row], [self.camera], [way], self.snapshot)
                context = contexts[self.camera["id"]]
                self.assertEqual("verified", report[self.camera["id"]])
                self.assertEqual(0, context["direction"])
                self.assertEqual([1, 2], context["evidence"]["approachNodes"])
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "context.json"
                    path.write_text(json.dumps(contexts))
                    camera = dict(self.camera, roadName="")
                    self.assertEqual(1, apply_verified_context([camera], [self.row], path))
                    self.assertEqual(0, camera["direction"])

    # 도로 축·일반 지명·양방향 화살표·직각 이동에서 단속 방향을 추정하지 않는다.
    def test_only_explicit_opposite_cardinals_are_accepted(self):
        for text in ("남동->북서", "남→북", "동->서"):
            self.assertIsNotNone(explicit_direction(text))
        for text in ("남동마을->북서마을", "남<->북", "남→북→동", "남->동", "북쪽 방면", "가상로"):
            self.assertIsNone(explicit_direction(text))

    # 방향에 맞는 도로 하나만 골라 평행도로·교차로의 모호함을 숨기지 않는다.
    def test_parallel_and_intersection_segments_are_rejected(self):
        for coordinates in ([[127.0001, 36.9995], [127.0001, 37.0005]],
                            [[126.9995, 37.0], [127.0005, 37.0]]):
            other = dict(self.way, id=2, nodes=[3, 4], coordinates=coordinates)
            result, reason = linked_evidence(self.camera, self.row["ITLPC"], [self.way, other], self.snapshot)
            self.assertIsNone(result)
            self.assertEqual("ambiguous_link", reason)

    # 원문 북행과 동서 도로 축이 어긋나면 가까운 선분이라도 방향을 만들지 않는다.
    def test_crossing_axis_and_far_segment_are_rejected(self):
        for coordinates in ([[126.9995, 37.0], [127.0005, 37.0]],
                            [[127.001, 36.9995], [127.001, 37.0005]]):
            result, _ = linked_evidence(self.camera, self.row["ITLPC"],
                                        [dict(self.way, coordinates=coordinates)], self.snapshot)
            self.assertIsNone(result)

    # 기존 단속 방향은 덮어쓰지 않고 방향 없는 도로 축만으로도 근거를 만들지 않는다.
    def test_existing_direction_and_unknown_source_stay_unchanged(self):
        contexts, _ = generate_context([self.row], [dict(self.camera, direction=180)], [self.way], self.snapshot)
        self.assertEqual({}, contexts)
        contexts, _ = generate_context([dict(self.row, ITLPC="가상로")],
                                      [dict(self.camera, roadAxisDegrees=0)], [self.way], self.snapshot)
        self.assertEqual({}, contexts)

    # 재적용 시 다른 절차에서 이미 구한 방향·축이 바뀌었다면 자동 근거로 덮어쓰지 않는다.
    def test_application_rejects_conflicting_existing_direction_or_axis(self):
        contexts, _ = generate_context([self.row], [self.camera], [self.way], self.snapshot)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "context.json"
            path.write_text(json.dumps(contexts))
            for changed in (dict(self.camera, direction=180), dict(self.camera, roadAxisDegrees=90)):
                with self.subTest(camera=changed), self.assertRaises(ValueError):
                    apply_verified_context([changed], [self.row], path)

    # 원자료 갱신·중복·도로명 상충에는 과거 지도 근거를 붙이지 않는다.
    def test_changed_duplicate_or_conflicting_source_is_rejected(self):
        for rows, cameras in (([self.row, self.row], [self.camera]),
                              ([self.row], [self.camera, self.camera]),
                              ([dict(self.row, LATITUDE="37.001")], [self.camera]),
                              ([self.row], [dict(self.camera, roadName="다른로")])):
            contexts, _ = generate_context(rows, cameras, [self.way], self.snapshot)
            self.assertEqual({}, contexts)

    # 기록된 방향 숫자·선분 순서·지문을 고치면 재적용을 실패시켜 잘못된 자료 배포를 막는다.
    def test_tampered_evidence_fails_at_application(self):
        original, _ = generate_context([self.row], [self.camera], [self.way], self.snapshot)
        for field in ("direction", "coordinates", "shapeSnapshotSha256", "source"):
            contexts = copy.deepcopy(original)
            context = contexts[self.camera["id"]]
            if field == "direction":
                context[field] = 180
            elif field == "coordinates":
                context["evidence"][field].reverse()
            elif field == "source":
                del context[field]["referenceDate"]
            else:
                context["evidence"][field] = "invalid"
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "context.json"
                path.write_text(json.dumps(contexts))
                with self.assertRaises(ValueError):
                    apply_verified_context([copy.deepcopy(self.camera)], [self.row], path)

    # 빈 선분·잘못된 노드·비정상 좌표는 조용히 각도 값으로 바꾸지 않는다.
    def test_invalid_geometry_is_rejected(self):
        result, _ = linked_evidence(self.camera, self.row["ITLPC"], [self.way], self.snapshot)
        _, original = result
        for field, value in (("approachNodes", [1, 1]), ("coordinates", [[127, 37], [127, 37]]),
                             ("coordinates", [[float("nan"), 37], [127, 37]])):
            evidence = dict(original, **{field: value})
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate_evidence(self.camera, self.row["ITLPC"], evidence)


if __name__ == "__main__":
    unittest.main()
