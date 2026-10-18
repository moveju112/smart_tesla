import unittest

from replay.make_route import sample


class RouteSamplingTest(unittest.TestCase):
    # 잘못된 속도는 경로 유무와 관계없이 거절해 무한 반복·비정상 JSON을 막는다.
    def test_invalid_speed_is_rejected(self):
        for speed in (0, -1, float("nan"), float("inf"), -float("inf")):
            with self.subTest(speed=speed), self.assertRaises(ValueError):
                sample([], speed)

    # 105m 직선에서 초당 10m씩 진행하면 시작점을 포함해 11개 표본이다.
    def test_positive_speed_keeps_deterministic_sampling(self):
        line = [[127.0, 37.0], [127.0, 37.0 + 105.0 / 111195.0]]
        samples = sample(line, 36.0)
        self.assertEqual(11, len(samples))
        self.assertTrue(all(point["speedMps"] == 10.0 for point in samples))
        self.assertEqual(samples, sample(line, 36.0))


if __name__ == "__main__":
    unittest.main()
