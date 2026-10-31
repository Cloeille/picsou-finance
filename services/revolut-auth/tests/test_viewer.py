"""The captcha remote view (viewer.py) against the real x11vnc and websockify, on a throwaway X display.

Skipped where Xvfb, x11vnc or the noVNC client are missing (a bare checkout); the sidecar image has all
three, so the CI job that runs the suite in the image exercises it.
"""

import asyncio
import base64
import os
import shutil
import socket
import subprocess
import urllib.request

import pytest

import viewer

NEEDS = ("Xvfb", "x11vnc")
pytestmark = pytest.mark.skipif(
    any(shutil.which(b) is None for b in NEEDS) or not os.path.isfile(os.path.join(viewer.WEB_DIR, "vnc.html")),
    reason="needs Xvfb, x11vnc and noVNC in " + viewer.WEB_DIR)


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _listening(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


@pytest.fixture
def display(monkeypatch):
    number = 200 + os.getpid() % 700
    xvfb = subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", "640x480x24", "-nolisten", "tcp"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    socket_path = f"/tmp/.X11-unix/X{number}"
    for _ in range(50):
        if os.path.exists(socket_path):
            break
        subprocess.run(["sleep", "0.1"])
    monkeypatch.setenv("DISPLAY", f":{number}")
    monkeypatch.setattr(viewer, "PORT", _free_port())
    monkeypatch.setattr(viewer, "RFB_PORT", _free_port())
    yield
    xvfb.terminate()
    xvfb.wait()


async def _rfb_banner_over_websocket(port):
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    key = base64.b64encode(os.urandom(16)).decode()
    writer.write((f"GET /websockify HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                  f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                  "Sec-WebSocket-Protocol: binary\r\n\r\n").encode())
    await writer.drain()
    received = b""
    try:
        while b"RFB" not in received:
            chunk = await asyncio.wait_for(reader.read(512), 3)
            if not chunk:
                break
            received += chunk
    finally:
        writer.close()
    return received


async def test_the_view_serves_the_client_and_the_display_over_websocket_then_closes_cleanly(display):
    session = await viewer.start()
    try:
        assert len(session.password) == 8 and session.password.isalnum()
        page = urllib.request.urlopen(f"http://127.0.0.1:{viewer.PORT}/vnc.html", timeout=3)
        assert page.status == 200
        assert b"RFB" in await _rfb_banner_over_websocket(viewer.PORT), "the display is reachable through the WebSocket"
        processes = subprocess.run(["ps", "-eo", "args"], capture_output=True, text=True).stdout
        assert session.password not in processes, "the password must not show in a process list"
    finally:
        await session.stop()
        await session.stop()  # idempotent

    assert not _listening(viewer.PORT) and not _listening(viewer.RFB_PORT)


async def test_a_second_view_is_refused_while_one_is_open_and_allowed_after(display):
    first = await viewer.start()
    try:
        with pytest.raises(viewer.ViewerUnavailable, match="already open"):
            await viewer.start()
    finally:
        await first.stop()

    again = await viewer.start()
    assert again.password != first.password, "every hand-off has its own password"
    await again.stop()


async def test_each_view_gets_a_fresh_password_and_leaves_no_password_file(display):
    before = {d for d in os.listdir(os.environ.get("TMPDIR", "/tmp")) if d.startswith("viewer-")}
    first = await viewer.start()
    await first.stop()
    second = await viewer.start()
    await second.stop()

    assert first.password != second.password
    assert {d for d in os.listdir(os.environ.get("TMPDIR", "/tmp")) if d.startswith("viewer-")} == before


async def test_without_a_display_or_binary_it_reports_unavailable_and_stays_closed(monkeypatch, display):
    monkeypatch.delenv("DISPLAY")
    with pytest.raises(viewer.ViewerUnavailable, match="DISPLAY"):
        await viewer.start()

    monkeypatch.setenv("DISPLAY", ":1")
    monkeypatch.setenv("PATH", "/nonexistent")
    with pytest.raises(viewer.ViewerUnavailable):
        await viewer.start()
    assert viewer._active is None, "a failed start must not block the next hand-off"


async def test_a_display_that_is_gone_makes_the_view_unavailable_and_leaves_nothing_running(monkeypatch, display):
    monkeypatch.setenv("DISPLAY", ":999")  # no X server there: x11vnc exits at once

    with pytest.raises(viewer.ViewerUnavailable, match="x11vnc"):
        await viewer.start()

    assert viewer._active is None
    assert not _listening(viewer.PORT) and not _listening(viewer.RFB_PORT)
