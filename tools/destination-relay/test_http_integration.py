"""검토한 패치 기준 원본에 HTTP 경계·인증·재기동 수신을 로컬에서 확인한다."""
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import threading
import time
import unittest
import uuid
from unittest.mock import patch

from prepare_patch import prepare

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/build/destination-relay-source"


@unittest.skipUnless((SOURCE / "map_server.py").exists(), "검토한 서버 원본을 로컬에 준비해야 함")
class RelayHttpTest(unittest.TestCase):
    # 운영과 다른 토큰·서명키·임시 DB를 써 실제 HTTP 서버를 시작한다.
    def setUp(self):
        prepared = prepare(ROOT)
        sys.path.insert(0, str(SOURCE))
        self.addCleanup(lambda: sys.path.remove(str(SOURCE)))
        specification = importlib.util.spec_from_file_location("patched_map_server", prepared / "map_server.py")
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        self.bootstrap = secrets.token_hex(32)
        (directory / "users.json").write_text(json.dumps({"test": hashlib.sha256(self.bootstrap.encode()).hexdigest()}))
        (directory / "signing.key").write_text(secrets.token_hex(32))
        with patch.dict(os.environ, {"MAP_DESTINATION_DB": str(directory / "relay.sqlite3")}):
            self.app = module.Application(directory / "users.json", None, directory / "signing.key")
        self.app.load_users()
        self.module = module
        self.server = module.Server(self.app, 0)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.stop)
        self.phone = self.token("a")
        self.tablet = self.token("b")

    # 서버가 만든 서명 포맷으로 서로 다른 기기 권한을 만든다.
    def token(self, character):
        return self.app.device_auth.seal("test", "rm1", {"owner": "test", "device": character * 64, "expiresAt": int(time.time()) + 1800})

    # 테스트 종료 때 서버 소켓과 스레드를 정상 종료한다.
    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    # 본문 프레이밍과 실제 HTTP 인증을 우회하지 않는다.
    def post(self, token, operation, **fields):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request("POST", "/v1/destinations", json.dumps({"operation": operation, **fields}),
                {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    # APK 가입 토큰과 서명 위조는 목적지 수신함을 열 수 없다.
    def test_signature_is_required(self):
        self.assertEqual(401, self.post(self.bootstrap, "status")[0])
        self.assertEqual(401, self.post(self.phone + "forged", "status")[0])
        self.assertEqual(200, self.post(self.phone, "status")[0])

    # 현재 앱의 검색어 전송은 selfTest 없이도 재기동 뒤 수신·인계·완료된다.
    def test_real_http_send_restart_receive(self):
        code = self.post(self.tablet, "pairCode", name="테스트 태블릿")[1]["code"]
        self.assertEqual(200, self.post(self.phone, "pair", code=code)[0])
        identifier = str(uuid.uuid4())
        status, _ = self.post(self.phone, "send", requestId=identifier, validityMinutes=1,
            destination={"name": "서울시청"})
        self.assertEqual(200, status)
        self.app.destination_relay = self.module.DestinationRelay(self.app.destination_relay.path)
        self.assertIsNone(self.post(self.phone, "inbox")[1]["request"])
        self.assertEqual(identifier, self.post(self.tablet, "inbox")[1]["request"]["id"])
        self.assertEqual(200, self.post(self.tablet, "claim", requestId=identifier)[0])
        self.assertEqual(409, self.post(self.tablet, "claim", requestId=identifier)[0])
        self.assertEqual(200, self.post(self.tablet, "complete", requestId=identifier, delivered=True)[0])
        self.assertEqual("delivered", self.post(self.phone, "status")[1]["request"]["status"])
