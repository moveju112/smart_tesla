"""차량·운영 서버 없이 전송 수명과 기기 격리를 검증한다."""
import json
import tempfile
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from destination_relay import DestinationRelay, RelayError


class DestinationRelayTest(unittest.TestCase):
    # 운영 데이터와 분리된 DB·시계·기기만 사용한다.
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "relay.sqlite3"
        self.now = 1000000
        self.relay = DestinationRelay(self.path, lambda: self.now)
        self.destination = {"name": "서울시청", "address": "서울 중구 세종대로 110", "latitude": 37.5663, "longitude": 126.9779}
        code = self.call("tablet", "pairCode", name="차량 태블릿")["code"]
        self.call("phone", "pair", code=code)

    # 모든 동작을 실제 JSON 경계로 전달한다.
    def call(self, device, operation, owner="owner", **fields):
        return self.relay.dispatch(owner, device, json.dumps({"operation": operation, **fields}).encode())

    # 재시도는 동일 ID를 재사용하고 새 전송은 새 ID를 만든다.
    def send(self, device="phone", **overrides):
        fields = dict(requestId=str(uuid.uuid4()), destination=self.destination, validityMinutes=10, selfTest=False)
        fields.update(overrides)
        return self.call(device, "send", **fields)["request"]

    # 절전·프로세스 종료 동안에도 최신 요청이 남고 한 번만 인계된다.
    def test_sleep_restart_and_single_claim(self):
        request = self.send()
        self.relay = DestinationRelay(self.path, lambda: self.now)
        self.now += 9 * 60000
        self.assertEqual(request["id"], self.call("tablet", "inbox", selfTest=False)["request"]["id"])
        self.call("tablet", "claim", requestId=request["id"], selfTest=False)
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=request["id"], selfTest=False)
        self.call("tablet", "complete", requestId=request["id"], delivered=True)
        self.assertEqual("delivered", self.call("phone", "status")["request"]["status"])

    # 전송 응답 유실로 재시도해도 유효시간을 다시 시작하거나 목적지를 중복 생성하지 않는다.
    def test_send_retry_keeps_original_deadline(self):
        request = self.send()
        self.now += 60000
        retried = self.send(requestId=request["id"])
        self.assertEqual(request, retried)
        with self.assertRaises(RelayError):
            self.send(requestId=request["id"], validityMinutes=20)

    # 경계 시각부터는 복귀·재시도 순서와 무관하게 실행할 수 없다.
    def test_expiry_at_exact_boundary(self):
        request = self.send()
        self.now = request["expiresAt"]
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])
        self.assertEqual("expired", self.call("phone", "status")["request"]["status"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=request["id"], selfTest=False)

    # 잠든 동안 교체·취소한 목적지는 깨어난 뒤 재생하지 않는다.
    def test_replace_and_cancel_while_offline(self):
        first = self.send()
        second = self.send()
        self.assertEqual(second["id"], self.call("tablet", "inbox", selfTest=False)["request"]["id"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=first["id"], selfTest=False)
        self.call("phone", "cancel", requestId=second["id"])
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])

    # 같은 가입 토큰 소유자라도 연결하지 않은 기기는 인계·취소·결과 변경을 못 한다.
    def test_unpaired_device_and_other_owner_are_isolated(self):
        request = self.send()
        for device, owner, operation, extra in [
            ("other", "owner", "claim", {"selfTest": False}),
            ("other", "owner", "cancel", {}),
            ("tablet", "different", "complete", {"delivered": True}),
        ]:
            with self.subTest(operation=operation), self.assertRaises(RelayError):
                self.call(device, operation, owner=owner, requestId=request["id"], **extra)
        with self.assertRaises(RelayError):
            self.send(device="other")

    # 연결 코드는 한 번만 사용하고 만료되거나 다른 소유자에게 노출되면 연결되지 않는다.
    def test_pair_code_single_use_owner_and_expiry(self):
        code = self.call("tablet", "pairCode", name="차량 태블릿")["code"]
        with self.assertRaises(RelayError):
            self.call("other", "pair", owner="different", code=code)
        self.call("phone", "pair", code=code)
        with self.assertRaises(RelayError):
            self.call("other", "pair", code=code)
        code = self.call("tablet", "pairCode", name="차량 태블릿")["code"]
        self.now += 600000
        with self.assertRaises(RelayError):
            self.call("phone", "pair", code=code)

    # 폰 한 대 테스트는 자기 요청만 가져오며 자동 수신 경로와 섞이지 않는다.
    def test_one_phone_test_is_explicit_and_keeps_pairing(self):
        request = self.send(selfTest=True)
        self.assertIsNone(self.call("phone", "inbox", selfTest=False)["request"])
        self.assertEqual(request["id"], self.call("phone", "inbox", selfTest=True)["request"]["id"])
        with self.assertRaises(RelayError):
            self.call("phone", "claim", requestId=request["id"], selfTest=False)
        self.call("phone", "claim", requestId=request["id"], selfTest=True)
        self.assertEqual("차량 태블릿", self.call("phone", "status")["receiverName"])

    # 중복 이벤트가 동시에 도착해도 한 호출만 목적지를 인계받는다.
    def test_concurrent_claim_only_one_wins(self):
        request = self.send()

        # 경쟁에서 패배한 인계는 성공으로 보고하지 않는다.
        def claim(_):
            try:
                self.call("tablet", "claim", requestId=request["id"], selfTest=False)
                return True
            except RelayError:
                return False

        with ThreadPoolExecutor(max_workers=4) as executor:
            self.assertEqual(1, sum(executor.map(claim, range(4))))

    # 취소와 인계 중 먼저 확정된 동작만 유효하다.
    def test_cancel_after_claim_reports_too_late(self):
        request = self.send()
        self.call("tablet", "claim", requestId=request["id"], selfTest=False)
        with self.assertRaises(RelayError):
            self.call("phone", "cancel", requestId=request["id"])
        self.assertEqual("claimed", self.call("phone", "status")["request"]["status"])

    # 기기 연결을 끊으면 아직 실행하지 않은 요청도 철회한다.
    def test_unlink_cancels_pending(self):
        self.send()
        self.call("phone", "unlink")
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])
        self.assertIsNone(self.call("phone", "status")["receiverName"])

    # 범위 밖 좌표·불명 필드·잘못된 타입을 저장하지 않는다.
    def test_invalid_input_rejected(self):
        for update in ({"latitude": float("nan")}, {"longitude": 200}, {"latitude": True}, {"name": "\ninvalid"}):
            with self.subTest(update=update), self.assertRaises(RelayError):
                self.send(destination={**self.destination, **update})
        for minutes in (0, 121, True, "10"):
            with self.subTest(minutes=minutes), self.assertRaises(RelayError):
                self.send(validityMinutes=minutes)
        with self.assertRaises(RelayError):
            self.call("phone", "status", extra="unexpected")

    # 오래된 장소 정보는 24시간 뒤 다음 요청에서 제거한다.
    def test_retention_removes_old_destination(self):
        self.send()
        self.now += 86400001
        self.assertIsNone(self.call("phone", "status")["request"])

    # 같은 태블릿의 임시 테스트 전송은 실제 폰에서 보낸 목적지를 교체하지 않는다.
    def test_tablet_self_test_preserves_real_inbox(self):
        real = self.send()
        test = self.send(device="tablet", selfTest=True)
        self.assertEqual(real["id"], self.call("tablet", "inbox", selfTest=False)["request"]["id"])
        self.assertEqual(test["id"], self.call("tablet", "inbox", selfTest=True)["request"]["id"])


if __name__ == "__main__":
    unittest.main()
