"""합성 기기와 임시 DB로 검색어 전송·기존 좌표 요청의 호환성을 검증한다."""
import json
import tempfile
import unittest
import uuid
from pathlib import Path

from destination_relay import DestinationRelay, RelayError


class DestinationSearchTests(unittest.TestCase):
    # 운영 데이터와 분리된 임시 수신함에 합성 폰·태블릿만 연결한다.
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.now = 1_800_000_000_000
        self.relay = DestinationRelay(Path(self.directory.name) / "relay.sqlite3", lambda: self.now)
        code = self.call("tablet", "pairCode", name="차량 태블릿")["code"]
        self.call("phone", "pair", code=code)

    # HTTP 인증 이후 호출되는 실제 중계 경로에 JSON을 전달한다.
    def call(self, device, operation, **fields):
        return self.relay.dispatch("test-owner", device, json.dumps({"operation": operation, **fields}))

    # 검색어 전송에 좌표를 만들어 넣지 않고 동일한 요청 ID를 재사용할 수 있게 한다.
    def send(self, destination=None, request_id=None, self_test=False):
        return self.call("phone", "send", requestId=request_id or str(uuid.uuid4()),
                         destination=destination if destination is not None else {"name": "서울역"},
                         validityMinutes=10, selfTest=self_test)["request"]

    # 이름만 전송한 요청이 실제 연결된 태블릿에서 한 번 인계·완료된다.
    def test_search_handoff_and_completion(self):
        sent = self.send({"name": " 서울시청 & 주차장/#? "})
        self.assertEqual(sent["destination"], {"name": "서울시청 & 주차장/#?"})
        inbox = self.call("tablet", "inbox", selfTest=False)["request"]
        self.assertEqual(inbox, sent)
        claimed = self.call("tablet", "claim", requestId=sent["id"], selfTest=False)["request"]
        self.assertEqual(claimed["status"], "claimed")
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=sent["id"], selfTest=False)
        self.call("tablet", "complete", requestId=sent["id"], delivered=True)
        self.assertEqual(self.call("phone", "status")["request"]["status"], "delivered")

    # 이전 앱의 정상 좌표형은 그대로 받으며 기존 범위 검증도 유지한다.
    def test_legacy_coordinates(self):
        place = {"name": "서울시청", "address": "서울 중구 세종대로 110", "latitude": 37.5663, "longitude": 126.9779}
        self.assertEqual(self.send(place)["destination"], place)
        for fields in ({"latitude": float("nan")}, {"longitude": 0}, {"latitude": True}, {"address": ""}):
            with self.subTest(fields=fields), self.assertRaises(RelayError):
                self.send({**place, **fields})

    # 빈 값·제어문자·길이 초과·혼합 형식은 요청 저장 전에 거부한다.
    def test_invalid_search_and_partial_coordinates(self):
        invalid = [None, [], {}, {"name": None}, {"name": ""}, {"name": " "}, {"name": "가" * 121},
                   {"name": "서울\n시청"}, {"name": "장소\x00"}, {"name": "회사", "latitude": 37.5},
                   {"name": "회사", "query": "다른 곳"}, {"name": "회사", "address": None}]
        for value in invalid:
            with self.subTest(value=value), self.assertRaises(RelayError) as caught:
                self.relay.destination(value)
            self.assertEqual(caught.exception.status, 400)
        self.assertEqual(self.relay.destination({"name": "집"}), {"name": "집"})
        self.assertEqual(self.relay.destination({"name": "가" * 120}), {"name": "가" * 120})

    # 재전송은 같은 요청으로 응답하고 같은 ID의 검색어 변경은 거부한다.
    def test_idempotence_and_payload_conflict(self):
        sent = self.send()
        self.assertEqual(self.send(request_id=sent["id"]), sent)
        with self.assertRaises(RelayError) as caught:
            self.send({"name": "서울시청"}, request_id=sent["id"])
        self.assertEqual(caught.exception.status, 409)

    # 검색어를 다시 보내거나 취소·만료해도 오래된 검색이 뒤늦게 열리지 않는다.
    def test_replacement_cancel_and_expiry(self):
        first = self.send()
        second = self.send({"name": "서울시청"})
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=first["id"], selfTest=False)
        self.call("phone", "cancel", requestId=second["id"])
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])
        self.send()
        self.now += 600_000
        self.assertIsNone(self.call("tablet", "inbox", selfTest=False)["request"])
        self.assertEqual(self.call("phone", "status")["request"]["status"], "expired")

    # 양쪽에서 연결을 확인하고 수신 기기 해제도 대기 요청·코드를 폐기한다.
    def test_receiver_disconnect_and_status(self):
        self.assertEqual(self.call("tablet", "status")["senderCount"], 1)
        self.assertEqual(self.call("phone", "status")["receiverName"], "차량 태블릿")
        sent = self.send()
        code = self.call("tablet", "pairCode", name="차량 태블릿")["code"]
        self.call("tablet", "disconnect")
        self.assertEqual(self.call("tablet", "status")["senderCount"], 0)
        self.assertIsNone(self.call("phone", "status")["receiverName"])
        self.assertEqual(self.call("phone", "status")["request"]["status"], "cancelled")
        with self.assertRaises(RelayError):
            self.call("phone", "pair", code=code)
        with self.assertRaises(RelayError):
            self.call("tablet", "claim", requestId=sent["id"])

    # 현재 앱은 시험용 필드 없이 정상 전송·수신·인계한다.
    def test_current_contract(self):
        sent = self.call("phone", "send", requestId=str(uuid.uuid4()),
                         destination={"name": "서울역"}, validityMinutes=10)["request"]
        self.assertEqual(self.call("tablet", "inbox")["request"], sent)
        self.assertEqual(self.call("tablet", "claim", requestId=sent["id"])["request"]["status"], "claimed")

    # 구버전의 자기 수신 진입을 차단한다.
    def test_removed_mode_rejected(self):
        with self.assertRaises(RelayError):
            self.send(self_test=True)
        with self.assertRaises(RelayError):
            self.call("phone", "inbox", selfTest=True)

    # 다른 소유자의 연결과 이미 인계된 요청은 연결 해제 대상이 아니다.
    def test_disconnect_scope(self):
        sent = self.send()
        self.call("tablet", "claim", requestId=sent["id"])
        code = self.call("other-tablet", "pairCode", name="다른 태블릿")["code"]
        self.call("other-phone", "pair", code=code)
        self.call("phone", "disconnect")
        self.assertEqual(self.call("phone", "status")["request"]["status"], "claimed")
        self.assertEqual(self.call("other-phone", "status")["receiverName"], "다른 태블릿")

    # 다른 기기가 검색어 요청을 훔쳐 인계할 수 없도록 기존 소유권 검사를 유지한다.
    def test_unlinked_device_cannot_claim(self):
        sent = self.send()
        with self.assertRaises(RelayError) as caught:
            self.call("other-device", "claim", requestId=sent["id"], selfTest=False)
        self.assertEqual(caught.exception.status, 404)


if __name__ == "__main__":
    unittest.main()
