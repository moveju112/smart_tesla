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
        fields = dict(requestId=str(uuid.uuid4()), destination=self.destination, validityMinutes=10)
        fields.update(overrides)
        return self.call(device, "send", **fields)["request"]

    # 절전·프로세스 종료 동안에도 최신 요청이 남고 한 번만 인계된다.
    def test_sleep_restart_and_single_claim(self):
        request = self.send()
        self.relay = DestinationRelay(self.path, lambda: self.now)
        self.now += 9 * 60000
        self.assertEqual(request["id"], self.call("tablet", "inbox")["request"]["id"])
        self.call("tablet", "claim", requestId=request["id"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=request["id"])
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
        self.assertIsNone(self.call("tablet", "inbox")["request"])
        self.assertEqual("expired", self.call("phone", "status")["request"]["status"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=request["id"])

    # 잠든 동안 교체·취소한 목적지는 깨어난 뒤 재생하지 않는다.
    def test_replace_and_cancel_while_offline(self):
        first = self.send()
        second = self.send()
        self.assertEqual(second["id"], self.call("tablet", "inbox")["request"]["id"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=first["id"])
        self.call("phone", "cancel", requestId=second["id"])
        self.assertIsNone(self.call("tablet", "inbox")["request"])

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

    # 삭제된 자기 수신 시험은 거부하고 기존 일반 전송의 false만 호환한다.
    def test_legacy_self_test_is_rejected_and_pairing_is_preserved(self):
        with self.assertRaises(RelayError):
            self.send(selfTest=True)
        request = self.send(selfTest=False)
        self.assertEqual(request["id"], self.call("tablet", "inbox")["request"]["id"])
        self.assertEqual("차량 태블릿", self.call("phone", "status")["receiverName"])

    # 중복 이벤트가 동시에 도착해도 한 호출만 목적지를 인계받는다.
    def test_concurrent_claim_only_one_wins(self):
        request = self.send()

        # 경쟁에서 패배한 인계는 성공으로 보고하지 않는다.
        def claim(_):
            try:
                self.call("tablet", "claim", requestId=request["id"])
                return True
            except RelayError:
                return False

        with ThreadPoolExecutor(max_workers=4) as executor:
            self.assertEqual(1, sum(executor.map(claim, range(4))))

    # 취소와 인계 중 먼저 확정된 동작만 유효하다.
    def test_cancel_after_claim_reports_too_late(self):
        request = self.send()
        self.call("tablet", "claim", requestId=request["id"])
        with self.assertRaises(RelayError):
            self.call("phone", "cancel", requestId=request["id"])
        self.assertEqual("claimed", self.call("phone", "status")["request"]["status"])

    # 기기 연결을 끊으면 아직 실행하지 않은 요청도 철회한다.
    def test_unlink_cancels_pending(self):
        self.send()
        self.call("phone", "unlink")
        self.assertIsNone(self.call("tablet", "inbox")["request"])
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

    # 자기 수신 시도로 실제 폰의 대기 목적지를 교체할 수 없다.
    def test_rejected_self_test_preserves_real_inbox(self):
        real = self.send()
        with self.assertRaises(RelayError):
            self.send(device="tablet", selfTest=True)
        self.assertEqual(real["id"], self.call("tablet", "inbox")["request"]["id"])

    # 현재 앱이 보내는 검색어 JSON을 전송부터 완료까지 같은 서버에서 처리한다.
    def test_current_app_json_contract(self):
        request = self.send(destination={"name": "서울역"})
        self.assertEqual(request["id"], self.call("tablet", "inbox")["request"]["id"])
        self.call("tablet", "claim", requestId=request["id"])
        self.call("tablet", "complete", requestId=request["id"], delivered=True)
        self.assertEqual("delivered", self.call("phone", "status")["request"]["status"])

    # claimed는 유효시간 경과로 pending에 되돌려 중복 실행하지 않는다.
    def test_claimed_request_does_not_reenter_inbox_after_expiry(self):
        request = self.send(destination={"name": "서울역"})
        self.call("tablet", "claim", requestId=request["id"])
        self.now = request["expiresAt"] + 1
        self.assertIsNone(self.call("tablet", "inbox")["request"])
        self.assertEqual("claimed", self.call("phone", "status")["request"]["status"])

    # 실행 전 중단과 지연 claim은 어느 순서로 처리돼도 미실행 terminal 상태로 수렴한다.
    def test_abort_and_delayed_claim_in_both_orders(self):
        for claim_first in (False, True):
            with self.subTest(claim_first=claim_first):
                request = self.send()
                if claim_first:
                    self.call("tablet", "claim", requestId=request["id"])
                reply = self.call("tablet", "complete", requestId=request["id"], delivered=False)
                self.assertEqual("failed", reply["request"]["status"])
                with self.assertRaises(RelayError) as rejected:
                    self.call("tablet", "claim", requestId=request["id"])
                self.assertEqual(409, rejected.exception.status)
                self.assertIsNone(self.call("tablet", "inbox")["request"])
                self.assertEqual("failed", self.call("phone", "status")["request"]["status"])

    # 성공 complete는 pending을 선점하지 않으며 확정된 결과는 중단 요청으로 바뀌지 않는다.
    def test_completion_does_not_invent_or_overwrite_delivery(self):
        request = self.send()
        with self.assertRaises(RelayError):
            self.call("tablet", "complete", requestId=request["id"], delivered=True)
        self.call("tablet", "claim", requestId=request["id"])
        self.call("tablet", "complete", requestId=request["id"], delivered=True)
        for delivered in (False, True):
            reply = self.call("tablet", "complete", requestId=request["id"], delivered=delivered)
            self.assertEqual("delivered", reply["request"]["status"])

    # 취소·교체·만료 응답도 요청별 종료 확인을 제공하며 원래 terminal 상태는 유지한다.
    def test_aborting_terminal_requests_preserves_state(self):
        cancelled = self.send()
        self.call("phone", "cancel", requestId=cancelled["id"])
        replaced = self.send()
        expired = self.send()
        self.now = expired["expiresAt"]
        for request, expected in ((cancelled, "cancelled"), (replaced, "replaced"), (expired, "expired")):
            reply = self.call("tablet", "complete", requestId=request["id"], delivered=False)
            self.assertEqual(expected, reply["request"]["status"])

    # 중단 확정도 실제 수신 기기·소유자 검증을 우회하지 않는다.
    def test_abort_requires_receiver_and_owner(self):
        request = self.send()
        for device, owner in (("phone", "owner"), ("other", "owner"), ("tablet", "different")):
            with self.subTest(device=device, owner=owner), self.assertRaises(RelayError):
                self.call(device, "complete", owner=owner, requestId=request["id"], delivered=False)
        self.assertEqual("pending", self.call("phone", "status")["request"]["status"])


if __name__ == "__main__":
    unittest.main()
