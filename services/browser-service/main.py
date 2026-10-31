"""One fresh Chromium process per session, spoken to over a pipe, never a TCP port.

Playwright is used only to locate the pinned Chromium binary. The process is
launched with --remote-debugging-pipe (fd 3 reads, fd 4 writes, NUL-delimited
CDP). A single-use token plus the sidecar key gate the WebSocket that relays
those frames. Tokens, keys, visited URLs and CDP payloads are never logged.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import secrets
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import JSONResponse, Response


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("browser-service")

SIDECAR_API_KEY = os.environ.get("APP_SIDECAR_API_KEY", "")
CHALLENGE = "Picsou-Sidecar-Key"
ALLOWED_ARGS = frozenset({"--disable-blink-features=AutomationControlled"})
WS_POLICY_VIOLATION = 1008
_PROBE = b'{"id":1,"method":"Browser.getVersion","params":{}}\0'
_PROBE_TIMEOUT_S = 20.0
_KILL_WAIT_S = 5.0
_HEADLESS_ARGS = (
    "--headless",
    "--hide-scrollbars",
    "--mute-audio",
    "--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4",
)


def _positive_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(f"{name} must be an integer") from exc
    if value < 1:
        raise RuntimeError(f"{name} must be positive")
    return value


MAX_SESSIONS = _positive_int("BROWSER_MAX_SESSIONS", 4)
TTL_SECONDS = _positive_int("BROWSER_SESSION_TTL_SECONDS", 300)
CLAIM_TIMEOUT_SECONDS = _positive_int("BROWSER_CLAIM_TIMEOUT_SECONDS", 30)

_sessions: dict[str, "Session"] = {}
_sessions_lock = asyncio.Lock()
_executable: str | None = None
_switches: list[str] | None = None


def _prefix(session_id: str) -> str:
    return session_id[:8]


def _same(expected: str, supplied: str) -> bool:
    if not expected or not expected.strip():
        return False
    try:
        return secrets.compare_digest(supplied.encode("latin-1"), expected.encode("utf-8"))
    except Exception:
        return False


def _playwright_root() -> Path:
    import playwright

    location = playwright.__file__
    if not location:
        raise RuntimeError("playwright package has no file location")
    return Path(location).resolve().parent


def chromium_switches() -> list[str]:
    """Read Playwright 1.52's Chromium switch list from the installed driver."""
    global _switches
    if _switches is not None:
        return list(_switches)
    root = _playwright_root()
    switches_js = (root / "driver/package/lib/server/chromium/chromiumSwitches.js").read_text(
        encoding="utf-8"
    )
    chromium_js = (root / "driver/package/lib/server/chromium/chromium.js").read_text(encoding="utf-8")
    for arg in (*_HEADLESS_ARGS, "--no-sandbox", "--remote-debugging-pipe", "--no-startup-window"):
        if arg not in chromium_js:
            raise RuntimeError("installed Playwright chromium.js is missing an expected switch")
    features_block = switches_js.split("const disabledFeatures = [", 1)[1].split("];", 1)[0]
    features = re.findall(r'"([A-Za-z0-9]+)"', features_block)
    switches_block = switches_js.split("const chromiumSwitches = [", 1)[1].split("];", 1)[0]
    parsed: list[str] = []
    for item in re.findall(r'"([^"]*)"', switches_block):
        if item == "--disable-features=":
            parsed.append("--disable-features=" + ",".join(features))
        elif item.startswith("--"):
            parsed.append(item)
    if "--disable-dev-shm-usage" not in parsed or "--enable-automation" not in parsed:
        raise RuntimeError("could not read Playwright chromium switches")
    if not any(item.startswith("--disable-features=AcceptCHFrame") for item in parsed):
        raise RuntimeError("could not read Playwright disabled features")
    _switches = parsed
    return list(parsed)


def launch_argv(executable: str, user_data_dir: str, extra: list[str]) -> list[str]:
    """Match Playwright 1.52's non-persistent headless argv, with the pipe instead of a port."""
    return [
        executable,
        *chromium_switches(),
        *_HEADLESS_ARGS,
        "--no-sandbox",
        *extra,
        f"--user-data-dir={user_data_dir}",
        "--remote-debugging-pipe",
        "--no-startup-window",
    ]


def _clean_env() -> dict[str, str]:
    """Chromium does not need the sidecar key, and must not inherit it."""
    return {
        "HOME": os.environ.get("HOME", "/tmp"),
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LANG": os.environ.get("LANG", "C.UTF-8"),
        "TMPDIR": tempfile.gettempdir(),
    }


def _pipe_exec_wrapper() -> str:
    """Runs in the child, after close_fds, so fds 3 and 4 survive exec.

    subprocess close_fds runs after preexec_fn and would close a pipe we had
    just dup'd onto fd 4. Remap here, then replace this interpreter with Chromium.
    """
    return (
        "import ctypes,os,signal,sys\n"
        "read_fd=int(os.environ.pop('PICSOU_READ_FD'))\n"
        "write_fd=int(os.environ.pop('PICSOU_WRITE_FD'))\n"
        "libc=ctypes.CDLL(None,use_errno=True)\n"
        "libc.prctl.argtypes=[ctypes.c_int,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_ulong]\n"
        "libc.prctl.restype=ctypes.c_int\n"
        "if libc.prctl(1,signal.SIGKILL,0,0,0)!=0: os._exit(111)\n"
        "if os.getppid()==1: os._exit(112)\n"
        "saved_read=os.dup(read_fd)\n"
        "saved_write=os.dup(write_fd)\n"
        "os.dup2(saved_read,3,inheritable=True)\n"
        "os.dup2(saved_write,4,inheritable=True)\n"
        "os.set_inheritable(3,True)\n"
        "os.set_inheritable(4,True)\n"
        "for fd in (read_fd,write_fd,saved_read,saved_write):\n"
        "    if fd>4:\n"
        "        os.close(fd)\n"
        "os.execv(sys.argv[1],sys.argv[1:])\n"
    )


# Every session's Chromium runs as the same user, so its profile dir is readable
# by the others. A web page cannot open file://, but a CDP client can: by
# navigating there, by handing a local path to a file input or a drag event, or
# by rewriting an intercepted request. Those commands are checked here. Downloads
# would write into the shared filesystem, so a download-behaviour command is
# forwarded rewritten to "deny": Playwright sends one on every connect_over_cdp
# and fails if it is refused. Frames are otherwise relayed verbatim.
_URL_COMMANDS = frozenset({"Page.navigate", "Target.createTarget", "Fetch.continueRequest"})
_DOWNLOAD_COMMANDS = frozenset({"Browser.setDownloadBehavior", "Page.setDownloadBehavior"})
_ALLOWED_SCHEMES = frozenset({"http", "https", "about"})
# Never sent by Playwright. sendMessageToTarget carries a nested command string
# this filter would not inspect, and exposeDevToolsProtocol hands a page a CDP
# binding Chromium serves without this relay; loadNetworkResource fetches an
# arbitrary URL.
_DENIED_COMMANDS = frozenset({
    "Target.sendMessageToTarget",
    "Target.exposeDevToolsProtocol",
    "Network.loadNetworkResource",
    "DOM.setFileInputFiles",
    "Page.handleFileChooser",
    "Extensions.loadUnpacked",
})


def deny_downloads(text: str) -> str:
    """Rewrite a download-behaviour command to deny; any other frame is returned unchanged."""
    try:
        message = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return text
    if not isinstance(message, dict) or message.get("method") not in _DOWNLOAD_COMMANDS:
        return text
    params = message.get("params") if isinstance(message.get("params"), dict) else {}
    kept = {key: params[key] for key in ("browserContextId", "eventsEnabled") if key in params}
    message["params"] = {**kept, "behavior": "deny"}
    return json.dumps(message)


def refuse_command(text: str) -> str | None:
    """Return a CDP error frame for a refused client command, else None.

    Fail-closed: the pipe splits messages on NUL, so a frame carrying one would
    reach Chromium as several commands this filter never parsed. Such frames, and
    anything that is not one JSON object, are refused.
    """
    if "\0" in text:
        return _refusal(None, None)
    try:
        message = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return _refusal(None, None)
    if not isinstance(message, dict):
        return _refusal(None, None)
    method = message.get("method")
    params = message.get("params")
    refused = method in _DENIED_COMMANDS
    if method == "Input.dispatchDragEvent" and isinstance(params, dict):
        data = params.get("data")
        refused = isinstance(data, dict) and bool(data.get("files"))
    elif method in _URL_COMMANDS:
        url = params.get("url") if isinstance(params, dict) else None
        if method == "Fetch.continueRequest" and url is None:
            # Continuing unchanged keeps the URL the page already asked for.
            refused = False
        elif url == "":
            # Chromium opens about:blank for an empty target URL.
            refused = False
        else:
            scheme = url.split(":", 1)[0].strip().lower() if isinstance(url, str) and ":" in url else ""
            refused = scheme not in _ALLOWED_SCHEMES
    if not refused:
        return None
    return _refusal(message.get("id"), message.get("sessionId"))


def _refusal(message_id, session_id) -> str:
    reply: dict = {"id": message_id, "error": {"code": -32000, "message": "REFUSED_BY_BROWSER_SERVICE"}}
    if session_id is not None:
        reply["sessionId"] = session_id
    return json.dumps(reply)


def _signal_group(pgid: int, sig: int) -> None:
    try:
        os.killpg(pgid, sig)
    except (ProcessLookupError, PermissionError):
        return


def _group_pids(pgid: int) -> list[int]:
    found = []
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            stat = open(f"/proc/{entry}/stat", encoding="utf-8").read()
        except OSError:
            continue
        fields = stat[stat.rfind(")") + 2 :].split()
        if len(fields) > 2 and int(fields[2]) == pgid:
            found.append(int(entry))
    return found


class LaunchError(Exception):
    """Chromium did not become ready. The message is an exit code or a reason word, never a payload."""


class Session:
    def __init__(self, session_id: str, token: str) -> None:
        self.id = session_id
        self.token = token
        self.prefix = _prefix(session_id)
        self.proc: subprocess.Popen[bytes] | None = None
        self.pgid: int | None = None
        self.data_dir: str | None = None
        self.read_fd = -1
        self.write_fd = -1
        self.websocket: WebSocket | None = None
        self.claimed = False
        self.ready = False
        self.created_mono = time.monotonic()
        self.ready_mono: float | None = None
        self.expires_mono = 0.0
        self.claim_deadline = 0.0
        self.expires_at = ""
        self._closed = False
        self._close_lock = asyncio.Lock()
        self._cleanup: asyncio.Task[None] | None = None
        self._buf = bytearray()
        self._early: list[str] = []
        self._inbox: asyncio.Queue[str | None] = asyncio.Queue()
        self._reader_on = False
        self._write_lock = threading.Lock()
        self._exit_scheduled = False

    def _write_all(self, data: bytes) -> None:
        with self._write_lock:
            view = memoryview(data)
            while view:
                written = os.write(self.write_fd, view)
                view = view[written:]

    def _read_one_blocking(self, timeout: float) -> bytes | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._closed or (self.proc is not None and self.proc.poll() is not None):
                return None
            remaining = deadline - time.monotonic()
            readable, _, _ = select.select([self.read_fd], [], [], min(0.2, max(0.0, remaining)))
            if not readable:
                continue
            chunk = os.read(self.read_fd, 65536)
            if not chunk:
                return None
            self._buf.extend(chunk)
            end = self._buf.find(b"\0")
            if end >= 0:
                message = bytes(self._buf[:end])
                del self._buf[: end + 1]
                return message
        return None

    def _probe(self) -> None:
        self._write_all(_PROBE)
        deadline = time.monotonic() + _PROBE_TIMEOUT_S
        while time.monotonic() < deadline:
            if self._closed:
                raise LaunchError("closed")
            if self.proc is not None and self.proc.poll() is not None:
                raise LaunchError(f"exit={self.proc.returncode}")
            message = self._read_one_blocking(0.5)
            if message is None:
                continue
            # Parsed only to tell the probe response from an early event. Not logged.
            try:
                parsed = json.loads(message)
            except json.JSONDecodeError:
                parsed = None
            if isinstance(parsed, dict) and parsed.get("id") == 1:
                return
            self._early.append(message.decode("utf-8"))
        raise LaunchError("timeout")

    def _on_readable(self) -> None:
        while True:
            try:
                chunk = os.read(self.read_fd, 65536)
            except BlockingIOError:
                return
            except OSError:
                self._schedule_exit()
                return
            if not chunk:
                self._schedule_exit()
                return
            self._buf.extend(chunk)
            while True:
                end = self._buf.find(b"\0")
                if end < 0:
                    break
                message = bytes(self._buf[:end])
                del self._buf[: end + 1]
                try:
                    self._inbox.put_nowait(message.decode("utf-8"))
                except Exception:
                    self._schedule_exit()
                    return

    def _schedule_exit(self) -> None:
        if self._exit_scheduled or self._closed:
            return
        self._exit_scheduled = True
        asyncio.get_running_loop().create_task(self.close("browser-exit"))

    def _start_reader(self) -> None:
        os.set_blocking(self.read_fd, False)
        asyncio.get_running_loop().add_reader(self.read_fd, self._on_readable)
        self._reader_on = True

    def _stop_io(self) -> None:
        if self._reader_on and self.read_fd >= 0:
            with suppress(Exception):
                asyncio.get_running_loop().remove_reader(self.read_fd)
            self._reader_on = False
        for attr in ("read_fd", "write_fd"):
            fd = getattr(self, attr)
            if fd >= 0:
                with suppress(OSError):
                    os.close(fd)
                setattr(self, attr, -1)
        with suppress(Exception):
            self._inbox.put_nowait(None)

    async def launch(self, extra: list[str]) -> None:
        executable = await chromium_executable()
        # The reaper may have closed this session during the lookup, and its
        # cleanup has then already run. From here to the probe nothing awaits,
        # so a cleanup started later sees every resource created below.
        if self._closed:
            raise LaunchError("closed")
        self.data_dir = tempfile.mkdtemp(prefix="picsou-browser-")
        os.chmod(self.data_dir, 0o700)
        child_read, parent_write = os.pipe()
        parent_read, child_write = os.pipe()
        self.read_fd = parent_read
        self.write_fd = parent_write
        env = _clean_env()
        env["PICSOU_READ_FD"] = str(child_read)
        env["PICSOU_WRITE_FD"] = str(child_write)
        command = [sys.executable, "-c", _pipe_exec_wrapper(), executable, *launch_argv(executable, self.data_dir, extra)[1:]]
        try:
            self.proc = subprocess.Popen(
                command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                # Chromium stderr can carry URLs and paths. Not kept.
                stderr=subprocess.DEVNULL,
                pass_fds=(child_read, child_write),
                start_new_session=True,
                close_fds=True,
                env=env,
            )
        except Exception as exc:
            self._stop_io()
            raise LaunchError(type(exc).__name__) from exc
        finally:
            for fd in (child_read, child_write):
                with suppress(OSError):
                    os.close(fd)
        self.pgid = self.proc.pid
        try:
            await asyncio.to_thread(self._probe)
        except LaunchError:
            raise
        except Exception as exc:
            raise LaunchError(type(exc).__name__) from exc
        for message in self._early:
            self._inbox.put_nowait(message)
        self._early.clear()
        # The probe read may have pulled in complete frames after its own answer.
        while (end := self._buf.find(b"\0")) >= 0:
            self._inbox.put_nowait(bytes(self._buf[:end]).decode("utf-8"))
            del self._buf[: end + 1]
        self._start_reader()
        now = time.monotonic()
        self.ready = True
        self.ready_mono = now
        self.expires_mono = now + TTL_SECONDS
        self.claim_deadline = now + CLAIM_TIMEOUT_SECONDS
        self.expires_at = (datetime.now(timezone.utc) + timedelta(seconds=TTL_SECONDS)).isoformat()
        log.info("session %s ready", self.prefix)
        asyncio.create_task(self._watch())

    async def _watch(self) -> None:
        proc = self.proc
        while proc is not None and not self._closed:
            if proc.poll() is not None:
                await self.close("browser-exit")
                return
            await asyncio.sleep(0.2)

    async def relay(self, websocket: WebSocket) -> None:
        sender = asyncio.create_task(self._send_to_ws(websocket))
        reader = asyncio.create_task(self._read_ws(websocket))
        done, pending = await asyncio.wait({sender, reader}, return_when=asyncio.FIRST_COMPLETED)
        for task in pending:
            task.cancel()
        for task in pending:
            with suppress(asyncio.CancelledError, Exception):
                await task
        for task in done:
            with suppress(asyncio.CancelledError, Exception):
                task.result()

    async def _send_to_ws(self, websocket: WebSocket) -> None:
        while not self._closed:
            message = await self._inbox.get()
            if message is None or self._closed:
                return
            await websocket.send_text(message)

    async def _read_ws(self, websocket: WebSocket) -> None:
        while not self._closed:
            incoming = await websocket.receive()
            if incoming["type"] == "websocket.disconnect":
                return
            text = incoming.get("text")
            if text is None:
                return
            refusal = refuse_command(text)
            if refusal is not None:
                await websocket.send_text(refusal)
                continue
            text = deny_downloads(text)
            await asyncio.to_thread(self._write_all, text.encode("utf-8") + b"\0")

    async def close(self, reason: str) -> None:
        async with self._close_lock:
            if reason == "claim-timeout" and self.claimed and not self._closed:
                return
            if not self._closed:
                self._closed = True
                self.token = ""
                log.info("session %s closing reason=%s", self.prefix, reason)
                # Its own task: cancelling a caller (the reaper at shutdown) must
                # not stop the kill, the wipe or the registry removal halfway.
                self._cleanup = asyncio.get_running_loop().create_task(self._cleanup_resources())
                _cleanups.add(self._cleanup)
                self._cleanup.add_done_callback(_cleanups.discard)
            cleanup = self._cleanup
        if cleanup is not None:
            await asyncio.shield(cleanup)

    async def _cleanup_resources(self) -> None:
        await self._terminate()
        self._stop_io()
        await asyncio.to_thread(self._wipe)
        async with _sessions_lock:
            _sessions.pop(self.id, None)
        websocket = self.websocket
        self.websocket = None
        if websocket is not None:
            with suppress(Exception):
                await asyncio.wait_for(websocket.close(code=1001), timeout=1)

    async def _terminate(self) -> None:
        proc = self.proc
        pgid = self.pgid
        if proc is None:
            return
        if pgid is not None:
            _signal_group(pgid, signal.SIGTERM)
        deadline = time.monotonic() + _KILL_WAIT_S
        while time.monotonic() < deadline and proc.poll() is None:
            await asyncio.sleep(0.1)
        # Always, even when the leader is gone: a child that ignored SIGTERM
        # keeps the process group alive after its parent exits.
        if pgid is not None:
            _signal_group(pgid, signal.SIGKILL)
            for pid in _group_pids(pgid):
                with suppress(ProcessLookupError, PermissionError):
                    os.kill(pid, signal.SIGKILL)
        with suppress(Exception):
            await asyncio.to_thread(proc.wait, 2)

    def _wipe(self) -> None:
        path = self.data_dir
        self.data_dir = None
        if path and not _remove_dir(path):
            # Never forgotten: the reaper retries until the cookies are gone.
            _pending_wipes.add(path)
            log.warning("session %s data dir cleanup deferred", self.prefix)


_pending_wipes: set[str] = set()
# In-flight Session cleanups, including those whose caller was cancelled.
_cleanups: set[asyncio.Task[None]] = set()


def _remove_dir(path: str) -> bool:
    for _ in range(20):
        if not os.path.exists(path):
            return True
        try:
            shutil.rmtree(path)
        except OSError:
            time.sleep(0.1)
    return not os.path.exists(path)


async def chromium_executable() -> str:
    global _executable
    if _executable:
        return _executable
    from playwright.async_api import async_playwright

    playwright = await async_playwright().start()
    try:
        path = playwright.chromium.executable_path
    finally:
        await playwright.stop()
    if not path or not os.path.isfile(path):
        raise LaunchError("missing")
    _executable = path
    return path


async def _reap_once() -> None:
    now = time.monotonic()
    victims: list[tuple[Session, str]] = []
    async with _sessions_lock:
        for session in list(_sessions.values()):
            if not session.ready:
                # launch() bounds itself with the probe timeout; anything older
                # is a launch that was abandoned without its cleanup running.
                if now >= session.created_mono + _PROBE_TIMEOUT_S + CLAIM_TIMEOUT_SECONDS:
                    victims.append((session, "launch-stuck"))
                continue
            if now >= session.expires_mono:
                victims.append((session, "ttl"))
            elif not session.claimed and now >= session.claim_deadline:
                victims.append((session, "claim-timeout"))
    for session, reason in victims:
        await session.close(reason)
    for path in list(_pending_wipes):
        if await asyncio.to_thread(_remove_dir, path):
            _pending_wipes.discard(path)


async def _reap_forever() -> None:
    while True:
        try:
            await asyncio.sleep(0.25)
            await _reap_once()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.warning("session reaper tick failed (%s)", type(exc).__name__)


async def _shutdown_sessions() -> None:
    async with _sessions_lock:
        sessions = list(_sessions.values())
    await asyncio.gather(*(session.close("shutdown") for session in sessions), return_exceptions=True)
    # A cleanup whose session already left the registry can still be closing its socket.
    await asyncio.gather(*list(_cleanups), return_exceptions=True)


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not SIDECAR_API_KEY.strip():
        raise RuntimeError("APP_SIDECAR_API_KEY must be configured and non-blank")
    reaper = asyncio.create_task(_reap_forever())
    try:
        yield
    finally:
        reaper.cancel()
        with suppress(asyncio.CancelledError):
            await reaper
        await _shutdown_sessions()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def authenticate_sidecar_request(request: Request, call_next):
    if request.url.path != "/health":
        supplied = request.headers.get("X-Picsou-Sidecar-Key", "")
        if not _same(SIDECAR_API_KEY, supplied):
            return JSONResponse(
                status_code=401,
                content={"detail": "UNAUTHORIZED"},
                headers={"WWW-Authenticate": CHALLENGE},
            )
    return await call_next(request)


@app.get("/health")
async def health() -> dict:
    async with _sessions_lock:
        count = len(_sessions)
    return {"status": "ok", "sessions": count, "max": MAX_SESSIONS}


def _reject_args(args: list[str]) -> JSONResponse | None:
    if any(arg not in ALLOWED_ARGS for arg in args):
        return JSONResponse(status_code=400, content={"detail": "UNSUPPORTED_ARG"})
    return None


@app.post("/sessions", status_code=201)
async def create_session(request: Request):
    raw = await request.body()
    if raw.strip():
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            return JSONResponse(status_code=400, content={"detail": "INVALID_DATA"})
        if not isinstance(payload, dict):
            return JSONResponse(status_code=400, content={"detail": "INVALID_DATA"})
        args = payload.get("args", [])
        if not isinstance(args, list) or not all(isinstance(arg, str) for arg in args):
            return JSONResponse(status_code=400, content={"detail": "INVALID_DATA"})
    else:
        args = []
    rejected = _reject_args(args)
    if rejected is not None:
        return rejected

    session_id = secrets.token_urlsafe(16)
    token = secrets.token_urlsafe(32)
    session = Session(session_id, token)
    async with _sessions_lock:
        if len(_sessions) >= MAX_SESSIONS:
            return JSONResponse(status_code=503, content={"detail": "BROWSER_CAPACITY"})
        _sessions[session_id] = session
    try:
        await session.launch(args)
    except Exception as exc:
        log.warning("session %s launch failed (%s)", session.prefix, type(exc).__name__)
        await session.close("launch-failed")
        return JSONResponse(status_code=502, content={"detail": "BROWSER_LAUNCH_FAILED"})
    except BaseException:
        # Client gone or shutdown mid-launch: the process must not outlive it.
        await asyncio.shield(session.close("launch-cancelled"))
        raise
    return JSONResponse(
        status_code=201,
        content={
            "sessionId": session_id,
            "wsPath": f"/sessions/{session_id}/cdp",
            "token": token,
            "expiresAt": session.expires_at,
        },
    )


@app.delete("/sessions/{session_id}", status_code=204)
async def delete_session(session_id: str):
    async with _sessions_lock:
        session = _sessions.get(session_id)
    if session is not None:
        await session.close("delete")
    return Response(status_code=204)


@app.websocket("/sessions/{session_id}/cdp")
async def cdp_socket(websocket: WebSocket, session_id: str) -> None:
    supplied_key = websocket.headers.get("x-picsou-sidecar-key", "")
    supplied_token = websocket.headers.get("x-picsou-browser-token", "")
    async with _sessions_lock:
        session = _sessions.get(session_id)
    if session is not None:
        async with session._close_lock:
            if (
                session._closed
                or not session.ready
                or session.claimed
                or not _same(SIDECAR_API_KEY, supplied_key)
                or not session.token
                or not _same(session.token, supplied_token)
            ):
                session = None
            else:
                session.claimed = True
                session.token = ""
    if session is None:
        log.info("ws rejected")
        await websocket.accept()
        await websocket.close(code=WS_POLICY_VIOLATION)
        return
    try:
        await websocket.accept()
        session.websocket = websocket
        log.info("session %s claimed", session.prefix)
        await session.relay(websocket)
    finally:
        await session.close("ws-disconnect")
