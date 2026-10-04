import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import camera_directions


class CameraDirectionTextTest(unittest.TestCase):
    # 실제 생성 진입점에 가상 원문을 넣고 지명·지도 검증으로 넘긴 출발/도착 순서를 확인한다.
    def generate(self, place, map_result=(90, "through")):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw, bundle, names, output = [root / name for name in ("raw.json", "bundle.json", "names.json", "output.json")]
            raw.write_text(json.dumps([{"INSTT_CODE": "fixture", "MNLSS_REGLT_CAMERA_MANAGE_NO": "camera", "ITLPC": place}]))
            bundle.write_text(json.dumps({"cameras": [{"id": "fixture:camera", "latitude": 37.0, "longitude": 127.0}]}))
            names.write_text("{}")
            with patch.object(camera_directions, "find", return_value=(100, 37.0, 127.0, "가상동")) as find, \
                    patch.object(camera_directions, "direction", return_value=map_result) as direction, \
                    patch("sys.argv", ["camera_directions.py", str(raw), str(bundle), str(names), "http://unused", str(output)]), \
                    contextlib.redirect_stdout(io.StringIO()):
                camera_directions.main()
            return json.loads(output.read_text()), [call.args[0] for call in find.call_args_list], direction.call_count

    # ASCII와 유니코드 화살표는 동일한 지도 검증 절차에 같은 순서로 전달해야 한다.
    def test_ascii_arrow_uses_existing_direction_verification(self):
        for arrow in ("->", "→"):
            with self.subTest(arrow=arrow):
                result, places, count = self.generate(f"지하차도 전 200m( 가상서동 {arrow} 가상동동 )")
                self.assertEqual({"fixture:camera": 90}, result)
                self.assertEqual(["가상서동", "가상동동"], places)
                self.assertEqual(1, count)

    # 출발·도착을 바꾸면 호출 순서도 바뀌며 화살표를 무조건 같은 방위로 치환하지 않는다.
    def test_reverse_places_preserve_order(self):
        _, places, _ = self.generate("가상동동->가상서동")
        self.assertEqual(["가상동동", "가상서동"], places)

    # 방향 없는 장소나 양방향·다중 화살표는 한쪽 단속으로 추정하지 않는다.
    def test_ambiguous_or_missing_direction_is_rejected(self):
        for place in ("가상동 앞", "가상서동<->가상동동", "가상서동↔가상동동", "가상서동->가상중동->가상동동", "가상서동→가상중동→가상동동", "(->가상동동)", "(가상서동-> )"):
            with self.subTest(place=place):
                result, places, count = self.generate(place)
                self.assertEqual({}, result)
                self.assertEqual([], places)
                self.assertEqual(0, count)

    # 방향 글자를 읽어도 지도 검증에 실패하면 번들에 방향을 생성하지 않는다.
    def test_map_rejection_still_drops_direction(self):
        result, places, count = self.generate("가상서동->가상동동", map_result=(None, "conflict"))
        self.assertEqual({}, result)
        self.assertEqual(["가상서동", "가상동동"], places)
        self.assertEqual(1, count)


if __name__ == "__main__":
    unittest.main()
