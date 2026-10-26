"""browser-service contract tests. Real Chromium, no TCP debug port, stdlib unittest."""

import asyncio
import json
import os
import signal
import socket
import struct
import subprocess
import tempfile
import threading
import time
import unittest
from base64 import b64encode
from unittest.mock import patch

from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

import main


KEY = "browser-service-test-key"
CHALLENGE = "Picsou-Sidecar-Key"
ALLOWED = "--disable-blink-features=AutomationControlled"


class SidecarAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.key_patch = patch.object(main, "SIDECAR_API_KEY", KEY)
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.client = TestClient(main.app)

    def test_unauthenticated_and_invalid_key_requests_are_challenged_before_routing(self):
        invalid_headers = ({}, {"X-Picsou-Sidecar-Key": "wrong-key"}, {"X-Picsou-Sidecar-Key": ""})
        with self.client as client:
            response = client.get("/not-a-route")
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)
            for headers in invalid_headers:
                with self.subTest(headers=headers):
                    response = client.post("/sessions", headers=headers, json={"args": ["--no-sandbox"]})
                    self.assertEqual(response.status_code, 401)
                    self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)
                    self.assertEqual(main._sessions, {})

    def test_health_is_accessible_without_a_key_and_lists_no_session(self):
        with patch.object(main, "MAX_SESSIONS", 4):
            with self.client as client:
                response = client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok", "sessions": 0, "max": 4})
        self.assertNotIn("token", response.text)
        self.assertNotIn("sessionId", response.text)

    def test_configured_key_is_compared_as_utf8_bytes(self):
        compare_digest = main.secrets.compare_digest
        with patch.object(main.secrets, "compare_digest", wraps=compare_digest) as compare:
            with TestClient(main.app) as client:
                response = client.get("/docs", headers={"X-Picsou-Sidecar-Key": KEY})
        self.assertEqual(response.status_code, 200)
        compare.assert_called_once_with(KEY.encode("utf-8"), KEY.encode("utf-8"))

    def test_non_ascii_key_authenticates_as_utf8_wire_bytes(self):
        with patch.object(main, "SIDECAR_API_KEY", "clé"):
            with TestClient(main.app) as client:
                response = client.get(
                    "/docs",
                    headers=[(b"X-Picsou-Sidecar-Key", "clé".encode("utf-8"))],
                )
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_startup_refuses_a_missing_or_blank_key(self):
        for missing_key in ("", "   "):
            with self.subTest(key=repr(missing_key)), patch.object(main, "SIDECAR_API_KEY", missing_key):
                with self.assertRaisesRegex(RuntimeError, "APP_SIDECAR_API_KEY"):
                    with TestClient(main.app):
                        self.fail("startup should refuse a missing key")

    def test_args_outside_the_allowlist_are_rejected_without_a_session(self):
        rejected = (
            ["--remote-debugging-port=9222"],
            ["--proxy-server=http://127.0.0.1:9"],
            ["--user-data-dir=/tmp/evil"],
            ["--disable-blink-features=AutomationControlled,SomethingElse"],
            ["--no-sandbox"],
            [ALLOWED, "--enable-logging"],
        )
        with self.client as client:
            for args in rejected:
                with self.subTest(args=args):
                    response = client.post(
                        "/sessions",
                        headers={"X-Picsou-Sidecar-Key": KEY},
                        json={"args": args},
                    )
                    self.assertEqual(response.status_code, 400)
                    self.assertEqual(response.json(), {"detail": "UNSUPPORTED_ARG"})
                    self.assertEqual(main._sessions, {})


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    try:
        stat = open(f"/proc/{pid}/stat", encoding="utf-8").read()
    except OSError:
        # Reaped between kill(0) and the read: ESRCH or ENOENT, both mean gone.
        return False
    return stat[stat.rfind(")") + 2 :].split()[0] != "Z"


def _cmdline(pid: int) -> list[str]:
    try:
        raw = open(f"/proc/{pid}/cmdline", "rb").read()
    except OSError:
        return []
    return [part.decode(errors="replace") for part in raw.split(b"\0") if part]


def _tree(pid: int) -> set[int]:
    found = {pid}
    pending = [pid]
    while pending:
        parent = pending.pop()
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            try:
                stat = open(f"/proc/{entry}/stat", encoding="utf-8").read()
            except OSError:
                continue
            fields = stat[stat.rfind(")") + 2 :].split()
            if len(fields) > 1 and int(fields[1]) == parent:
                child = int(entry)
                if child not in found:
                    found.add(child)
                    pending.append(child)
    return found


def _listening(pids: set[int]) -> list[str]:
    inodes = set()
    for pid in pids:
        fd_dir = f"/proc/{pid}/fd"
        try:
            names = os.listdir(fd_dir)
        except OSError:
            continue
        for name in names:
            try:
                target = os.readlink(f"{fd_dir}/{name}")
            except OSError:
                continue
            if target.startswith("socket:[") and target.endswith("]"):
                inodes.add(target[8:-1])
    hits = []
    for net in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            lines = open(net, encoding="utf-8").read().splitlines()[1:]
        except OSError:
            continue
        for line in lines:
            parts = line.split()
            if len(parts) > 9 and parts[3] == "0A" and parts[9] in inodes:
                hits.append(parts[1])
    return hits


class _Ws:
    def __init__(self) -> None:
        self.sock: socket.socket | None = None
        self.buf = b""

    def connect(self, port: int, path: str, headers: dict[str, str], timeout: float = 10) -> None:
        self.sock = socket.create_connection(("127.0.0.1", port), timeout)
        self.sock.settimeout(timeout)
        key = b64encode(os.urandom(16)).decode()
        lines = [
            f"GET {path} HTTP/1.1",
            f"Host: 127.0.0.1:{port}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {key}",
            "Sec-WebSocket-Version: 13",
        ]
        for name, value in headers.items():
            lines.append(f"{name}: {value}")
        assert self.sock is not None
        self.sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("latin-1"))
        while b"\r\n\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("handshake closed")
            self.buf += chunk
        header, rest = self.buf.split(b"\r\n\r\n", 1)
        self.buf = rest
        if b" 101 " not in header.split(b"\r\n", 1)[0]:
            raise ConnectionError(header.split(b"\r\n", 1)[0].decode("latin-1", "replace"))

    def read_frame(self) -> tuple[int, bytes]:
        assert self.sock is not None
        while len(self.buf) < 2:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("eof")
            self.buf += chunk
        first, second = self.buf[0], self.buf[1]
        self.buf = self.buf[2:]
        opcode = first & 0x0F
        length = second & 0x7F
        if length == 126:
            while len(self.buf) < 2:
                self.buf += self.sock.recv(4096)
            length = struct.unpack("!H", self.buf[:2])[0]
            self.buf = self.buf[2:]
        elif length == 127:
            while len(self.buf) < 8:
                self.buf += self.sock.recv(4096)
            length = struct.unpack("!Q", self.buf[:8])[0]
            self.buf = self.buf[8:]
        while len(self.buf) < length:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("eof")
            self.buf += chunk
        payload = self.buf[:length]
        self.buf = self.buf[length:]
        return opcode, payload

    def close_code(self) -> int:
        while True:
            opcode, payload = self.read_frame()
            if opcode == 0x8:
                if len(payload) < 2:
                    return 1005
                return struct.unpack("!H", payload[:2])[0]
            if opcode == 0x9:
                self.send_frame(0xA, payload)

    def send_frame(self, opcode: int, payload: bytes = b"") -> None:
        assert self.sock is not None
        mask = os.urandom(4)
        header = bytes([0x80 | opcode])
        length = len(payload)
        if length < 126:
            header += bytes([0x80 | length])
        else:
            header += bytes([0x80 | 126]) + struct.pack("!H", length)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.send_frame(0x8, struct.pack("!H", 1000))
            except OSError:
                pass
            self.sock.close()
            self.sock = None


def _start_server() -> tuple[object, threading.Thread, int]:
    import uvicorn

    port = _free_port()
    config = uvicorn.Config(
        main.app,
        host="127.0.0.1",
        port=port,
        log_level="warning",
        access_log=False,
        lifespan="on",
    )
    server = uvicorn.Server(config)
    server.install_signal_handlers = lambda: None  # type: ignore[method-assign]
    thread = threading.Thread(target=server.run, name="browser-service-test", daemon=True)
    thread.start()
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if getattr(server, "started", False):
            return server, thread, port
        if not thread.is_alive():
            raise RuntimeError("browser-service test server exited")
        time.sleep(0.05)
    raise RuntimeError("browser-service test server did not start")


def _stop_server(server, thread: threading.Thread) -> None:
    server.should_exit = True
    thread.join(timeout=20)
    if thread.is_alive():
        server.force_exit = True
        thread.join(timeout=5)


def _request(port: int, method: str, path: str, body: bytes | None = None, headers: dict | None = None):
    import http.client

    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
    conn.request(method, path, body=body, headers=headers or {})
    response = conn.getresponse()
    payload = response.read()
    status = response.status
    conn.close()
    return status, payload


def _create(port: int, args: list[str] | None = None) -> dict:
    status, payload = _request(
        port,
        "POST",
        "/sessions",
        body=json.dumps({"args": args or [ALLOWED]}).encode(),
        headers={"X-Picsou-Sidecar-Key": KEY, "Content-Type": "application/json"},
    )
    if status != 201:
        raise AssertionError(f"session create failed: {status} {payload[:200]!r}")
    return json.loads(payload)


class LiveChromiumTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._key = patch.object(main, "SIDECAR_API_KEY", KEY)
        cls._max = patch.object(main, "MAX_SESSIONS", 1)
        cls._ttl = patch.object(main, "TTL_SECONDS", 300)
        cls._claim = patch.object(main, "CLAIM_TIMEOUT_SECONDS", 30)
        cls._key.start()
        cls._max.start()
        cls._ttl.start()
        cls._claim.start()
        cls.server, cls.thread, cls.port = _start_server()

    @classmethod
    def tearDownClass(cls):
        _stop_server(cls.server, cls.thread)
        cls._claim.stop()
        cls._ttl.stop()
        cls._max.stop()
        cls._key.stop()

    def tearDown(self):
        for session_id in list(main._sessions):
            _request(self.port, "DELETE", f"/sessions/{session_id}", headers={"X-Picsou-Sidecar-Key": KEY})
        deadline = time.monotonic() + 8
        while main._sessions and time.monotonic() < deadline:
            time.sleep(0.1)

    def _session(self, body: dict) -> main.Session:
        return main._sessions[body["sessionId"]]

    def test_pipe_launch_opens_no_tcp_listen_socket(self):
        body = _create(self.port)
        session = self._session(body)
        pid = session.proc.pid
        argv = _cmdline(pid)
        self.assertIn("--remote-debugging-pipe", argv)
        self.assertIn("--no-sandbox", argv)
        self.assertIn("--headless", argv)
        self.assertIn("--no-startup-window", argv)
        self.assertIn(ALLOWED, argv)
        self.assertFalse(any(arg.startswith("--remote-debugging-port") for arg in argv))
        self.assertIn(f"--user-data-dir={session.data_dir}", argv)
        self.assertEqual(os.stat(session.data_dir).st_mode & 0o777, 0o700)
        self.assertFalse(_listening(_tree(pid)))
        self.assertNotIn(body["token"], " ".join(argv))

    def test_wrong_token_missing_key_and_second_claim_are_policy_violations(self):
        body = _create(self.port)
        path = body["wsPath"]
        wrong = _Ws()
        wrong.connect(self.port, path, {"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": "not-the-token"})
        self.assertEqual(wrong.close_code(), 1008)
        wrong.close()

        missing = _Ws()
        missing.connect(self.port, path, {"X-Picsou-Browser-Token": body["token"]})
        self.assertEqual(missing.close_code(), 1008)
        missing.close()

        bad_key = _Ws()
        bad_key.connect(
            self.port,
            path,
            {"X-Picsou-Sidecar-Key": "wrong", "X-Picsou-Browser-Token": body["token"]},
        )
        self.assertEqual(bad_key.close_code(), 1008)
        bad_key.close()

        # A rejected key must not consume the token.
        held = _Ws()
        held.connect(
            self.port,
            path,
            {"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": body["token"]},
        )
        time.sleep(0.3)
        again = _Ws()
        again.connect(
            self.port,
            path,
            {"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": body["token"]},
        )
        self.assertEqual(again.close_code(), 1008)
        again.close()
        held.close()

    def test_capacity_frees_the_slot_after_delete_and_after_ws_close(self):
        first = _create(self.port)
        status, payload = _request(
            self.port,
            "POST",
            "/sessions",
            body=b"{}",
            headers={"X-Picsou-Sidecar-Key": KEY, "Content-Type": "application/json"},
        )
        self.assertEqual(status, 503)
        self.assertEqual(json.loads(payload), {"detail": "BROWSER_CAPACITY"})

        status, _ = _request(
            self.port,
            "DELETE",
            f"/sessions/{first['sessionId']}",
            headers={"X-Picsou-Sidecar-Key": KEY},
        )
        self.assertEqual(status, 204)
        self._wait_until(lambda: first["sessionId"] not in main._sessions)
        second = _create(self.port)
        held = _Ws()
        held.connect(
            self.port,
            second["wsPath"],
            {"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": second["token"]},
        )
        held.close()
        self._wait_until(lambda: second["sessionId"] not in main._sessions)
        third = _create(self.port)
        self.assertNotEqual(third["sessionId"], second["sessionId"])
        status, _ = _request(self.port, "DELETE", "/sessions/does-not-exist", headers={"X-Picsou-Sidecar-Key": KEY})
        self.assertEqual(status, 204)

    def test_claim_timeout_kills_an_unclaimed_session(self):
        with patch.object(main, "CLAIM_TIMEOUT_SECONDS", 1):
            body = _create(self.port)
            session = self._session(body)
            pid = session.proc.pid
            data_dir = session.data_dir
            self._wait_until(lambda: not _alive(pid) and body["sessionId"] not in main._sessions, timeout=8)
        self.assertFalse(os.path.exists(data_dir))

    def test_ttl_kills_a_claimed_session(self):
        with patch.object(main, "TTL_SECONDS", 2):
            body = _create(self.port)
            session = self._session(body)
            pid = session.proc.pid
            data_dir = session.data_dir
            held = _Ws()
            held.connect(
                self.port,
                body["wsPath"],
                {"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": body["token"]},
            )
            self._wait_until(
                lambda: not _alive(pid) and body["sessionId"] not in main._sessions, timeout=8
            )
            held.close()
        self.assertFalse(os.path.exists(data_dir))

    def test_delete_removes_the_process_and_the_data_dir(self):
        body = _create(self.port)
        session = self._session(body)
        pid = session.proc.pid
        data_dir = session.data_dir
        self.assertTrue(os.path.isdir(data_dir))
        status, _ = _request(
            self.port, "DELETE", f"/sessions/{body['sessionId']}", headers={"X-Picsou-Sidecar-Key": KEY}
        )
        self.assertEqual(status, 204)
        self._wait_until(lambda: not _alive(pid))
        self.assertFalse(os.path.exists(data_dir))
        self.assertNotIn(body["sessionId"], main._sessions)

    def test_chromium_crash_frees_the_slot(self):
        body = _create(self.port)
        session = self._session(body)
        pid = session.proc.pid
        os.kill(pid, 9)
        self._wait_until(lambda: body["sessionId"] not in main._sessions, timeout=8)
        status, _ = _request(self.port, "GET", "/health")
        self.assertEqual(json.loads(_), {"status": "ok", "sessions": 0, "max": 1})
        replacement = _create(self.port)
        self.assertNotEqual(replacement["sessionId"], body["sessionId"])

    def _wait_until(self, predicate, timeout: float = 8) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.1)
        self.fail("condition was not met")


class ShutdownKillsSessionsTest(unittest.TestCase):
    def test_lifespan_shutdown_kills_every_session(self):
        with patch.object(main, "SIDECAR_API_KEY", KEY), patch.object(main, "MAX_SESSIONS", 1):
            server, thread, port = _start_server()
            body = _create(port)
            pid = main._sessions[body["sessionId"]].proc.pid
            data_dir = main._sessions[body["sessionId"]].data_dir
            self.assertTrue(_alive(pid))
            _stop_server(server, thread)
            deadline = time.monotonic() + 8
            while _alive(pid) and time.monotonic() < deadline:
                time.sleep(0.1)
            self.assertFalse(_alive(pid))
            self.assertFalse(os.path.exists(data_dir))
            self.assertEqual(main._sessions, {})


class ReaperTest(unittest.IsolatedAsyncioTestCase):
    async def test_stuck_launch_is_closed_and_failed_wipe_is_retried(self):
        session = main.Session("stuck-session-id", "t")
        session.created_mono = time.monotonic() - main._PROBE_TIMEOUT_S - main.CLAIM_TIMEOUT_SECONDS - 1
        closed = []

        async def fake_close(reason):
            closed.append(reason)
        session.close = fake_close
        main._sessions[session.id] = session
        leftover = __import__("tempfile").mkdtemp(prefix="picsou-browser-test-")
        main._pending_wipes.add(leftover)
        try:
            await main._reap_once()
        finally:
            main._sessions.pop(session.id, None)
        self.assertEqual(closed, ["launch-stuck"])
        self.assertFalse(os.path.exists(leftover))
        self.assertNotIn(leftover, main._pending_wipes)


class ShutdownDuringExpiryCloseTest(unittest.IsolatedAsyncioTestCase):
    async def test_shutdown_finishes_a_close_the_reaper_had_started(self):
        signals = []
        in_grace_wait = asyncio.Event()

        class FakeProcess:
            pid = 424242
            returncode = None

            def poll(self):
                return self.returncode

            def wait(self, timeout=None):
                return self.returncode

        proc = FakeProcess()

        def fake_signal_group(pgid, sig):
            signals.append(sig)
            if sig == signal.SIGTERM:
                in_grace_wait.set()
            else:
                proc.returncode = -sig

        session = main.Session("expiring-session-id", "t")
        session.proc = proc
        session.pgid = proc.pid
        session.data_dir = tempfile.mkdtemp(prefix="picsou-browser-test-")
        session.ready = True
        session.claimed = True
        session.expires_mono = time.monotonic() - 1
        main._sessions[session.id] = session
        try:
            with patch.object(main, "SIDECAR_API_KEY", KEY), \
                    patch.object(main, "_signal_group", fake_signal_group), \
                    patch.object(main, "_group_pids", lambda pgid: []), \
                    patch.object(main, "_KILL_WAIT_S", 0.5):
                async with main.lifespan(main.app):
                    await asyncio.wait_for(in_grace_wait.wait(), timeout=5)
                    # The reaper is now inside close() -> _terminate(): shutdown cancels it there.
        finally:
            leftover = main._sessions.pop(session.id, None)
            data_dir_left = os.path.exists(session.data_dir or "")

        self.assertEqual(signals, [signal.SIGTERM, signal.SIGKILL])
        self.assertIsNone(leftover)
        self.assertIsNone(session.data_dir)
        self.assertFalse(data_dir_left)
        self.assertEqual(session.read_fd, -1)
        cleanup = session._cleanup
        self.assertIsNotNone(cleanup)
        assert cleanup is not None
        self.assertTrue(cleanup.done())
        self.assertEqual(main._cleanups, set())


class RefuseCommandTest(unittest.TestCase):
    def test_file_and_other_non_web_urls_are_refused(self):
        for method in ("Page.navigate", "Target.createTarget"):
            for url in ("file:///tmp/picsou-browser-x/Default/Cookies", "FILE:///etc/passwd",
                        " file:///etc/passwd", "chrome://settings", "devtools://x", "data:text/html,x",
                        "javascript:alert(1)", "/relative", None, 3):
                with self.subTest(method=method, url=url):
                    reply = main.refuse_command(json.dumps(
                        {"id": 7, "sessionId": "S", "method": method, "params": {"url": url}}))
                    self.assertIsNotNone(reply)
                    parsed = json.loads(reply or "")
                    self.assertEqual((parsed["id"], parsed["sessionId"]), (7, "S"))
                    self.assertEqual(parsed["error"]["message"], "REFUSED_BY_BROWSER_SERVICE")

    def test_nested_and_raw_fetch_commands_are_refused(self):
        nested = json.dumps({"id": 9, "method": "Page.navigate", "params": {"url": "file:///etc/passwd"}})
        for frame in ({"id": 1, "method": "Target.sendMessageToTarget",
                       "params": {"targetId": "T", "message": nested}},
                      {"id": 2, "method": "Network.loadNetworkResource",
                       "params": {"frameId": "F", "url": "file:///etc/passwd", "options": {}}}):
            with self.subTest(method=frame["method"]):
                self.assertIsNotNone(main.refuse_command(json.dumps(frame)))

    def test_local_file_handoffs_are_refused(self):
        for frame in ({"id": 1, "method": "DOM.setFileInputFiles",
                       "params": {"files": ["/tmp/picsou-browser-x/Default/Cookies"], "backendNodeId": 1}},
                      {"id": 2, "method": "Page.handleFileChooser", "params": {"action": "accept", "files": ["/etc/passwd"]}},
                      {"id": 3, "method": "Input.dispatchDragEvent",
                       "params": {"type": "drop", "x": 1, "y": 1, "data": {"items": [], "files": ["/etc/passwd"], "dragOperationsMask": 1}}},
                      {"id": 4, "method": "Fetch.continueRequest", "params": {"requestId": "R", "url": "file:///etc/passwd"}}):
            with self.subTest(method=frame["method"]):
                self.assertIsNotNone(main.refuse_command(json.dumps(frame)))

    def test_harmless_variants_of_checked_commands_pass(self):
        for frame in ({"id": 1, "method": "Input.dispatchDragEvent",
                       "params": {"type": "dragEnter", "x": 1, "y": 1, "data": {"items": [], "dragOperationsMask": 1}}},
                      {"id": 2, "method": "Fetch.continueRequest", "params": {"requestId": "R"}},
                      {"id": 3, "method": "Fetch.continueRequest", "params": {"requestId": "R", "url": "https://client.corum.fr/x"}}):
            with self.subTest(method=frame["method"]):
                self.assertIsNone(main.refuse_command(json.dumps(frame)))

    def test_downloads_are_rewritten_to_deny(self):
        for method in ("Browser.setDownloadBehavior", "Page.setDownloadBehavior"):
            with self.subTest(method=method):
                frame = json.dumps({"id": 1, "sessionId": "S", "method": method, "params": {
                    "behavior": "allowAndName", "downloadPath": "/tmp", "browserContextId": "C",
                    "eventsEnabled": True}})
                self.assertIsNone(main.refuse_command(frame))
                rewritten = json.loads(main.deny_downloads(frame))
                self.assertEqual(rewritten["params"],
                                 {"behavior": "deny", "browserContextId": "C", "eventsEnabled": True})
                self.assertEqual((rewritten["id"], rewritten["sessionId"]), (1, "S"))
        other = json.dumps({"id": 2, "method": "Runtime.evaluate", "params": {}})
        self.assertEqual(main.deny_downloads(other), other)

    def test_web_urls_and_other_commands_pass_through(self):
        for frame in ({"id": 1, "method": "Page.navigate", "params": {"url": "https://client.corum.fr/"}},
                      {"id": 2, "method": "Target.createTarget", "params": {"url": "about:blank"}},
                      {"id": 3, "method": "Target.createTarget", "params": {"url": ""}},
                      {"id": 4, "method": "Runtime.evaluate", "params": {"expression": "1"}}):
            with self.subTest(frame=frame):
                self.assertIsNone(main.refuse_command(json.dumps(frame)))

    def test_unparsed_or_nul_split_frames_are_refused(self):
        smuggled = (json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": "1"}})
                    + "\0" + json.dumps({"id": 2, "method": "Page.navigate",
                                          "params": {"url": "file:///tmp/picsou-browser-x/Default/Cookies"}}))
        for frame in ("not json", "[1]", "3", smuggled):
            with self.subTest(frame=frame[:40]):
                reply = main.refuse_command(frame)
                self.assertIsNotNone(reply)
                self.assertEqual(json.loads(reply or "")["error"]["message"], "REFUSED_BY_BROWSER_SERVICE")


class TestClientWebsocketCloseCodeTest(unittest.TestCase):
    """Starlette's in-process client must also surface 1008, not only a raw socket."""

    def test_unknown_session_is_a_policy_violation(self):
        with patch.object(main, "SIDECAR_API_KEY", KEY):
            with TestClient(main.app) as client:
                with self.assertRaises(WebSocketDisconnect) as caught:
                    with client.websocket_connect(
                        "/sessions/missing/cdp",
                        headers={"X-Picsou-Sidecar-Key": KEY, "X-Picsou-Browser-Token": "nope"},
                    ) as ws:
                        ws.receive_text()
        self.assertEqual(caught.exception.code, 1008)


if __name__ == "__main__":
    unittest.main()
