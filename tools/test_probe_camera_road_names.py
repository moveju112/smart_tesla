import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import Mock, patch


class CameraRoadNamesTest(unittest.TestCase):
    # 실제 스크립트에 가짜 워커 응답을 넣어 원격 접속 없이 도로명 수집을 검증한다.
    def probe(self, tracepoints):
        response = {"code": "Ok", "matchings": [{"confidence": 0.9}], "tracepoints": tracepoints}
        process = Mock(stdin=io.StringIO(),
                       stdout=io.StringIO("READY\n" + (json.dumps(response) + "\n") * 12))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "coordinates.txt"
            target = Path(directory) / "names.jsonl"
            source.write_text("37.0 127.0\n", encoding="utf-8")
            with patch("subprocess.Popen", return_value=process), \
                    patch("sys.argv", ["probe_camera_road_names.py", "unused-worker", "unused-map",
                                       str(source), str(target)]):
                runpy.run_path(str(Path(__file__).with_name("probe_camera_road_names.py")),
                               run_name="__main__")
            return json.loads(target.read_text(encoding="utf-8"))[2]

    # 입력 궤적 세 점이 모두 매칭된 경우에만 끝점의 도로명을 신뢰한다.
    def test_complete_trace_preserves_road_name(self):
        self.assertEqual(["가상로"], self.probe([{"name": "가상로"}] * 3))

    # 성공 코드라도 누락·부분 응답은 크래시나 잘못된 도로명 보강으로 이어지면 안 된다.
    def test_incomplete_trace_is_ignored(self):
        for points in ([], [{"name": "가상로"}], [{"name": "가상로"}, None, {"name": "가상로"}]):
            with self.subTest(points=points):
                self.assertEqual([], self.probe(points))


if __name__ == "__main__":
    unittest.main()
