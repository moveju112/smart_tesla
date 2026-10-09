"""기기 서명 인증 뒤에만 사용하는 목적지 보관·연결·단일 인계 저장소."""
import hashlib
import json
import math
import re
import secrets
import sqlite3
import threading
import time
from contextlib import closing


class RelayError(Exception):
    # 고정 오류만 응답하고 목적지·연결 코드를 로그로 내보내지 않는다.
    def __init__(self, status, code):
        self.status, self.code = status, code
        super().__init__(code)


class DestinationRelay:
    # 서버 재시작에도 요청과 인계 기록을 보존하며 데이터 파일은 서비스 전용 디렉터리에 둔다.
    def __init__(self, path, clock=lambda: int(time.time() * 1000)):
        self.path, self.clock = str(path), clock
        self.lock = threading.Lock()
        with closing(sqlite3.connect(self.path)) as database:
            database.executescript("""
                CREATE TABLE IF NOT EXISTS receivers (
                    owner TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL,
                    code_hash TEXT, code_expires INTEGER, PRIMARY KEY(owner, device));
                CREATE UNIQUE INDEX IF NOT EXISTS pairing_code ON receivers(code_hash);
                CREATE TABLE IF NOT EXISTS links (
                    owner TEXT NOT NULL, sender TEXT NOT NULL, receiver TEXT NOT NULL,
                    PRIMARY KEY(owner, sender));
                CREATE TABLE IF NOT EXISTS requests (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, sender TEXT NOT NULL,
                    receiver TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL,
                    expires_at INTEGER NOT NULL, status TEXT NOT NULL, self_test INTEGER NOT NULL);
                CREATE INDEX IF NOT EXISTS inbox ON requests(owner, receiver, created_at);
                CREATE INDEX IF NOT EXISTS outbox ON requests(owner, sender, created_at);
            """)
            database.commit()

    # 전송·교체·취소·인계를 같은 트랜잭션에 넣어 취소 성공 뒤의 실행을 막는다.
    def dispatch(self, owner, device, body):
        try:
            data = json.loads(body)
        except (ValueError, UnicodeError):
            raise RelayError(400, "invalid_request") from None
        if not isinstance(data, dict) or not isinstance(data.get("operation"), str):
            raise RelayError(400, "invalid_request")
        with self.lock, closing(sqlite3.connect(self.path, timeout=1)) as database:
            database.row_factory = sqlite3.Row
            database.execute("BEGIN IMMEDIATE")
            now = self.clock()
            database.execute("DELETE FROM requests WHERE created_at < ?", (now - 86400000,))
            database.execute("UPDATE requests SET status='expired' WHERE status='pending' AND expires_at<=?", (now,))
            result = self.perform(database, owner, device, data, now)
            database.commit()
            return {"serverNow": now, **result}

    # 허용된 동작마다 입력 필드와 발신·수신 기기의 소유권을 확인한다.
    def perform(self, database, owner, device, data, now):
        operation = data["operation"]
        if operation == "pairCode":
            self.fields(data, {"name"})
            name = self.label(data["name"], 40)
            code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(10))
            database.execute("""INSERT INTO receivers VALUES(?,?,?,?,?)
                ON CONFLICT(owner,device) DO UPDATE SET name=excluded.name,
                code_hash=excluded.code_hash,code_expires=excluded.code_expires""",
                (owner, device, name, hashlib.sha256(code.encode()).hexdigest(), now + 600000))
            return {"code": code, "expiresAt": now + 600000}
        if operation == "pair":
            self.fields(data, {"code"})
            code = data["code"]
            if not isinstance(code, str) or not re.fullmatch(r"[A-Z2-9]{10}", code):
                raise RelayError(400, "invalid_pairing_code")
            receiver = database.execute("SELECT * FROM receivers WHERE owner=? AND code_hash=? AND code_expires>?",
                (owner, hashlib.sha256(code.encode()).hexdigest(), now)).fetchone()
            if receiver is None or receiver["device"] == device:
                raise RelayError(400, "invalid_pairing_code")
            self.unlink(database, owner, device)
            database.execute("INSERT INTO links VALUES(?,?,?)", (owner, device, receiver["device"]))
            database.execute("UPDATE receivers SET code_hash=NULL,code_expires=NULL WHERE owner=? AND device=?",
                (owner, receiver["device"]))
            return {"receiverName": receiver["name"]}
        if operation == "unlink":
            self.fields(data, set())
            self.unlink(database, owner, device)
            return {}
        if operation == "disconnect":
            self.fields(data, set())
            self.unlink(database, owner, device)
            database.execute("DELETE FROM links WHERE owner=? AND receiver=?", (owner, device))
            database.execute("UPDATE requests SET status='cancelled' WHERE owner=? AND receiver=? AND status='pending'",
                (owner, device))
            database.execute("UPDATE receivers SET code_hash=NULL,code_expires=NULL WHERE owner=? AND device=?",
                (owner, device))
            return {}
        if operation == "status":
            self.fields(data, set())
            target = database.execute("""SELECT r.name FROM links l JOIN receivers r
                ON r.owner=l.owner AND r.device=l.receiver WHERE l.owner=? AND l.sender=?""", (owner, device)).fetchone()
            sent = database.execute("SELECT * FROM requests WHERE owner=? AND sender=? ORDER BY created_at DESC,rowid DESC LIMIT 1",
                (owner, device)).fetchone()
            senders = database.execute("SELECT COUNT(*) FROM links WHERE owner=? AND receiver=?", (owner, device)).fetchone()[0]
            return {"receiverName": target["name"] if target else None, "senderCount": senders, "request": self.record(sent)}
        if operation == "send":
            self.fields(data, {"requestId", "destination", "validityMinutes"})
            request_id = self.identifier(data["requestId"])
            destination = self.destination(data["destination"])
            minutes = data["validityMinutes"]
            if type(minutes) is not int or not 1 <= minutes <= 120:
                raise RelayError(400, "invalid_request")
            target = database.execute("SELECT receiver FROM links WHERE owner=? AND sender=?", (owner, device)).fetchone()
            receiver = target["receiver"] if target else None
            if receiver is None:
                raise RelayError(409, "receiver_not_paired")
            payload = json.dumps(destination, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
            existing = database.execute("SELECT * FROM requests WHERE id=?", (request_id,)).fetchone()
            if existing:
                if (existing["owner"], existing["sender"], existing["receiver"], existing["payload"], existing["self_test"],
                    existing["expires_at"] - existing["created_at"]) != (owner, device, receiver, payload, 0, minutes * 60000):
                    raise RelayError(409, "request_conflict")
                return {"request": self.record(existing)}
            database.execute("UPDATE requests SET status='replaced' WHERE owner=? AND receiver=? AND self_test=? AND status='pending'",
                (owner, receiver, 0))
            database.execute("INSERT INTO requests VALUES(?,?,?,?,?,?,?,?,?)",
                (request_id, owner, device, receiver, payload, now, now + minutes * 60000, "pending", 0))
            return {"request": self.record(database.execute("SELECT * FROM requests WHERE id=?", (request_id,)).fetchone())}
        if operation == "inbox":
            self.fields(data, set())
            request = database.execute("""SELECT * FROM requests WHERE owner=? AND receiver=? AND self_test=?
                AND status='pending' ORDER BY created_at DESC,rowid DESC LIMIT 1""", (owner, device, 0)).fetchone()
            return {"request": self.record(request)}
        if operation in ("cancel", "claim", "complete"):
            expected = {"requestId"} | ({"delivered"} if operation == "complete" else set())
            self.fields(data, expected)
            request_id = self.identifier(data["requestId"])
            request = database.execute("SELECT * FROM requests WHERE id=? AND owner=?", (request_id, owner)).fetchone()
            actor = "sender" if operation == "cancel" else "receiver"
            if request is None or request[actor] != device:
                raise RelayError(404, "request_not_found")
            if operation == "cancel":
                if request["status"] not in ("pending", "cancelled"):
                    raise RelayError(409, "request_not_pending")
                status = "cancelled"
            elif operation == "claim":
                if request["self_test"] != 0:
                    raise RelayError(400, "invalid_request")
                if request["status"] != "pending" or request["expires_at"] <= now:
                    raise RelayError(409, "request_not_pending")
                status = "claimed"
            else:
                if type(data["delivered"]) is not bool:
                    raise RelayError(400, "invalid_request")
                status = "delivered" if data["delivered"] else "failed"
                if request["status"] not in ("claimed", status):
                    raise RelayError(409, "request_conflict")
            database.execute("UPDATE requests SET status=? WHERE id=?", (status, request_id))
            return {"request": self.record(database.execute("SELECT * FROM requests WHERE id=?", (request_id,)).fetchone())}
        raise RelayError(400, "invalid_operation")

    # 연결 해제 시 아직 인계되지 않은 이 발신자의 요청만 철회한다.
    def unlink(self, database, owner, device):
        database.execute("DELETE FROM links WHERE owner=? AND sender=?", (owner, device))
        database.execute("UPDATE requests SET status='cancelled' WHERE owner=? AND sender=? AND status='pending' AND self_test=0",
            (owner, device))

    # 내부 기기 식별자는 앱 응답에서 제외한다.
    def record(self, row):
        if row is None:
            return None
        return {"id": row["id"], "destination": json.loads(row["payload"]), "createdAt": row["created_at"],
            "expiresAt": row["expires_at"], "status": row["status"], "selfTest": bool(row["self_test"])}

    # 정의되지 않은 필드는 목적지 API를 다른 데이터 보관 통로로 쓰지 못하게 거부한다.
    def fields(self, data, expected):
        # 이전 앱의 일반 전송만 호환하고 자기 수신 요청은 더 이상 허용하지 않는다.
        if data.get("operation") in ("send", "inbox", "claim") and "selfTest" in data:
            if data["selfTest"] is not False:
                raise RelayError(400, "invalid_request")
            expected = expected | {"selfTest"}
        if set(data) != expected | {"operation"}:
            raise RelayError(400, "invalid_request")

    # 사용자 문구의 길이와 제어문자를 제한한다.
    def label(self, value, limit):
        if not isinstance(value, str) or not 1 <= len(value.strip()) <= limit or any(ord(char) < 32 for char in value):
            raise RelayError(400, "invalid_request")
        return value.strip()

    # 클라이언트가 만든 요청 ID로 응답 유실 뒤에도 같은 요청만 재확인한다.
    def identifier(self, value):
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", value):
            raise RelayError(400, "invalid_request")
        return value

    # 이름만 담은 검색어와 기존 좌표형만 허용하고 불완전한 좌표는 검색으로 바꾸지 않는다.
    def destination(self, value):
        if not isinstance(value, dict):
            raise RelayError(400, "invalid_destination")
        if set(value) == {"name"}:
            return {"name": self.label(value["name"], 120)}
        if set(value) != {"name", "address", "latitude", "longitude"}:
            raise RelayError(400, "invalid_destination")
        for key, lower, upper in (("latitude", 31.43, 44.35), ("longitude", 122.37, 132.00)):
            number = value[key]
            if type(number) not in (int, float) or not math.isfinite(number) or not lower <= number <= upper:
                raise RelayError(400, "invalid_destination")
        return {**value, "name": self.label(value["name"], 120), "address": self.label(value["address"], 300)}
