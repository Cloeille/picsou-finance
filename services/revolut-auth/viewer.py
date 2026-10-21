"""
Remote view of the sidecar's virtual display, up only while a human has to act in the browser.

When Revolut asks for a captcha the sidecar must not solve it (no solver service, no AI), so it
keeps the headful Camoufox window open and lets the user work in it:

    Xvfb :99 (already running)  ->  x11vnc (RFB, loopback only)  ->  websockify (WebSocket + the
    noVNC web client, one published port)  ->  the user's browser

Nothing listens on the published port outside a hand-off. Each hand-off gets a fresh random
password; it is written to a 0600 file that x11vnc deletes as it reads it, so it never shows in a
process list, and it leaves the sidecar only through `/progress/{member}` (API-key protected).

One display means one viewer: a second hand-off while one is open is refused, and the caller
falls back to the plain HUMAN_VERIFICATION_REQUIRED error.
"""

import asyncio
import logging
import os
import secrets
import shutil
import string
import sys
import tempfile
from typing import List, Optional

log = logging.getLogger("revolut-auth")

PORT = 6080          # published by docker-compose: serves the noVNC client and its WebSocket
RFB_PORT = 5900      # x11vnc, bound to the loopback interface only
WEB_DIR = "/usr/share/novnc"
START_TIMEOUT_S = 10
STOP_TIMEOUT_S = 5
# VNC password authentication only ever reads the first 8 characters.
_PASSWORD_ALPHABET = string.ascii_letters + string.digits
_PASSWORD_LENGTH = 8


class ViewerUnavailable(RuntimeError):
    """The remote view could not be started (missing binaries/display, port taken, crash)."""


class Session:
    """An open remote view: an x11vnc + websockify pair and its one-time password."""

    def __init__(self, password: str):
        self.password = password
        self.processes: List[asyncio.subprocess.Process] = []

    async def stop(self) -> None:
        """Close the view. Idempotent."""
        global _active
        if _active is self:
            _active = None
        for process in self.processes:
            if process.returncode is None:
                process.terminate()
        for process in self.processes:
            try:
                await asyncio.wait_for(process.wait(), STOP_TIMEOUT_S)
            except asyncio.TimeoutError:
                process.kill()
                await process.wait()
        self.processes = []


_active: Optional[Session] = None


async def _listening(port: int, process: asyncio.subprocess.Process, name: str) -> None:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + START_TIMEOUT_S
    while loop.time() < deadline:
        if process.returncode is not None:
            raise ViewerUnavailable(f"{name} exited with status {process.returncode}")
        try:
            _, writer = await asyncio.open_connection("127.0.0.1", port)
        except OSError:
            await asyncio.sleep(0.1)
            continue
        writer.close()
        return
    raise ViewerUnavailable(f"{name} was not listening on port {port} after {START_TIMEOUT_S}s")


async def start() -> Session:
    """Open the remote view of the current DISPLAY; raises ViewerUnavailable when it cannot."""
    global _active
    if _active is not None:
        raise ViewerUnavailable("the remote view is already open for another login")
    display = os.environ.get("DISPLAY")
    if not display:
        raise ViewerUnavailable("no DISPLAY to share")
    if not os.path.isfile(os.path.join(WEB_DIR, "vnc.html")):
        raise ViewerUnavailable(f"noVNC is not installed in {WEB_DIR}")

    session = _active = Session("".join(secrets.choice(_PASSWORD_ALPHABET) for _ in range(_PASSWORD_LENGTH)))
    tmp = tempfile.mkdtemp(prefix="viewer-")  # mode 0700
    try:
        password_file = os.path.join(tmp, "password")
        fd = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(session.password)
        # stdout/stderr stay on the container's own streams, so `docker logs` shows connections.
        vnc = await asyncio.create_subprocess_exec(
            "x11vnc", "-display", display, "-localhost", "-rfbport", str(RFB_PORT),
            "-passwdfile", f"rm:{password_file}", "-forever", "-shared", "-quiet")
        session.processes.append(vnc)
        await _listening(RFB_PORT, vnc, "x11vnc")
        web = await asyncio.create_subprocess_exec(
            sys.executable, "-m", "websockify", "--web", WEB_DIR, str(PORT), f"127.0.0.1:{RFB_PORT}")
        session.processes.append(web)
        await _listening(PORT, web, "websockify")
        return session
    except OSError as exc:  # a binary is missing or not executable
        await session.stop()
        raise ViewerUnavailable(str(exc)) from exc
    except BaseException:
        # ViewerUnavailable, and cancellation too: never leave a half-open view or port behind.
        await session.stop()
        raise
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
