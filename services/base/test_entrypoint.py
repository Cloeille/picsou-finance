"""Contract tests for the shared Camoufox/Xvfb entrypoint.

The fake Xvfb process binds a real Unix socket, which exercises the readiness
and stale-socket paths without starting Firefox or contacting a bank site.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import socket
import re
import shlex
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest


ENTRYPOINT = Path(__file__).with_name("entrypoint.sh")
X11_SOCKET_DIR = Path("/tmp/.X11-unix")
SERVICES = Path(__file__).resolve().parents[1]
RUNTIME_PARAMETERS = {
    "amex-auth": {"SIDECAR_PORT": "8001", "XVFB_DISPLAY": ":98", "XVFB_SCREEN": "1366x768x24"},
    "revolut-auth": {"SIDECAR_PORT": "8002", "XVFB_DISPLAY": ":99", "XVFB_SCREEN": "1280x1024x24"},
}


def dockerfile_env(service: str) -> dict[str, str]:
    """Return the ENV values a sidecar image hands to the shared entrypoint."""
    dockerfile = (SERVICES / service / "Dockerfile").read_text()
    environment: dict[str, str] = {}
    for instruction in re.findall(r"^ENV\s+((?:.*\\\n)*.*)$", dockerfile, re.MULTILINE):
        for assignment in shlex.split(instruction.replace("\\\n", " ")):
            name, _, value = assignment.partition("=")
            environment[name] = value
    return environment


FAKE_XVFB = r"""
import json
import os
from pathlib import Path
import socket
import sys
import time

display = sys.argv[1]
display_number = display.removeprefix(":")
socket_path = Path("/tmp/.X11-unix") / f"X{display_number}"
lock_path = Path("/tmp") / f".X{display_number}-lock"
record_path = Path(os.environ["FAKE_XVFB_RECORD"])
record_path.write_text(json.dumps({
    "args": sys.argv[1:],
    "lock_present_at_start": lock_path.exists(),
    "socket_present_at_start": socket_path.exists(),
}))

mode = os.environ.get("FAKE_XVFB_MODE", "ready")
if mode == "exit":
    raise SystemExit(7)

parent = os.getppid()
server = None
if mode == "ready":
    server = socket.socket(socket.AF_UNIX)
    server.bind(str(socket_path))
    server.listen(1)

# Do not keep subprocess.run's capture pipes open after the entrypoint execs.
os.close(1)
os.close(2)
while os.getppid() == parent:
    time.sleep(0.01)

if server is not None:
    server.close()
try:
    socket_path.unlink()
except FileNotFoundError:
    pass
"""


FAKE_COMMAND = r"""
import json
import os
from pathlib import Path
import sys

Path(os.environ["FAKE_COMMAND_RECORD"]).write_text(json.dumps({
    "args": sys.argv[1:],
    "display": os.environ.get("DISPLAY"),
}))
"""


class CamoufoxEntrypointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory(prefix="picsou-camoufox-entrypoint-")
        self.addCleanup(self.temp_dir.cleanup)
        self.bin_dir = Path(self.temp_dir.name)
        self.xvfb_record = self.bin_dir / "xvfb.json"
        self.command_record = self.bin_dir / "command.json"
        self._write_executable("Xvfb", FAKE_XVFB)
        self._write_executable("uvicorn", FAKE_COMMAND)

    def _write_executable(self, name: str, body: str) -> Path:
        path = self.bin_dir / name
        path.write_text(f"#!{sys.executable}\n{textwrap.dedent(body).lstrip()}")
        path.chmod(0o755)
        return path

    def _environment(
        self,
        *,
        display: str,
        screen: str,
        port: str,
        xvfb_mode: str = "ready",
    ) -> dict[str, str]:
        return {
            **os.environ,
            "PATH": f"{self.bin_dir}:{os.environ['PATH']}",
            "XVFB_DISPLAY": display,
            "XVFB_SCREEN": screen,
            "SIDECAR_PORT": port,
            "FAKE_XVFB_MODE": xvfb_mode,
            "FAKE_XVFB_RECORD": str(self.xvfb_record),
            "FAKE_COMMAND_RECORD": str(self.command_record),
        }

    def _run(
        self,
        *,
        display: str,
        screen: str,
        port: str,
        xvfb_mode: str = "ready",
        command: list[str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["sh", str(ENTRYPOINT), *(command or [])],
            env=self._environment(
                display=display,
                screen=screen,
                port=port,
                xvfb_mode=xvfb_mode,
            ),
            text=True,
            capture_output=True,
            timeout=8,
            check=False,
        )

    @staticmethod
    def _paths(display: str) -> tuple[Path, Path]:
        number = display.removeprefix(":")
        return Path(f"/tmp/.X{number}-lock"), X11_SOCKET_DIR / f"X{number}"

    def _assert_display_available(self, display: str) -> None:
        occupied = [path for path in self._paths(display) if path.exists()]
        if occupied:
            self.skipTest(f"display {display} is already in use: {occupied}")

    def _wait_for_fake_xvfb_cleanup(self, display: str) -> None:
        _, socket_path = self._paths(display)
        deadline = time.monotonic() + 2
        while socket_path.exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        socket_path.unlink(missing_ok=True)

    def test_default_server_preserves_each_sidecars_runtime_parameters(self) -> None:
        for service, expected in RUNTIME_PARAMETERS.items():
            with self.subTest(service=service):
                declared = dockerfile_env(service)
                self.assertEqual({name: declared.get(name) for name in expected}, expected)
                display = declared["XVFB_DISPLAY"]
                screen = declared["XVFB_SCREEN"]
                port = declared["SIDECAR_PORT"]
                self._assert_display_available(display)
                result = self._run(display=display, screen=screen, port=port)
                self._wait_for_fake_xvfb_cleanup(display)

                self.assertEqual(result.returncode, 0, result.stderr)
                xvfb = json.loads(self.xvfb_record.read_text())
                self.assertEqual(
                    xvfb["args"],
                    [display, "-screen", "0", screen, "-nolisten", "tcp"],
                )
                command = json.loads(self.command_record.read_text())
                self.assertEqual(command["display"], display)
                self.assertEqual(
                    command["args"],
                    [
                        "main:app",
                        "--host",
                        "0.0.0.0",
                        "--port",
                        port,
                        "--timeout-keep-alive",
                        "65",
                    ],
                )
                self.xvfb_record.unlink()
                self.command_record.unlink()

    def test_restart_removes_stale_display_lock_and_socket(self) -> None:
        display = f":{20000 + os.getpid()}"
        lock_path, socket_path = self._paths(display)
        X11_SOCKET_DIR.mkdir(mode=0o1777, parents=True, exist_ok=True)
        lock_path.write_text("stale")
        stale = socket.socket(socket.AF_UNIX)
        stale.bind(str(socket_path))
        stale.close()
        self.addCleanup(lock_path.unlink, missing_ok=True)
        self.addCleanup(socket_path.unlink, missing_ok=True)

        result = self._run(display=display, screen="800x600x24", port="8998")
        self._wait_for_fake_xvfb_cleanup(display)

        self.assertEqual(result.returncode, 0, result.stderr)
        xvfb = json.loads(self.xvfb_record.read_text())
        self.assertFalse(xvfb["lock_present_at_start"])
        self.assertFalse(xvfb["socket_present_at_start"])

    def test_fails_immediately_when_xvfb_exits_before_becoming_ready(self) -> None:
        display = f":{21000 + os.getpid()}"
        result = self._run(
            display=display,
            screen="800x600x24",
            port="8997",
            xvfb_mode="exit",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("xvfb", result.stderr.lower())
        self.assertIn("exited", result.stderr.lower())
        self.assertFalse(self.command_record.exists())

    def test_fails_after_bounded_wait_when_x_socket_never_appears(self) -> None:
        display = f":{22000 + os.getpid()}"
        result = self._run(
            display=display,
            screen="800x600x24",
            port="8996",
            xvfb_mode="hang",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("xvfb", result.stderr.lower())
        self.assertRegex(result.stderr.lower(), r"(not|failed to).*ready")
        self.assertFalse(self.command_record.exists())

    def test_command_override_runs_an_offline_browser_smoke_command_under_xvfb(self) -> None:
        display = f":{23000 + os.getpid()}"
        smoke = self._write_executable("smoke-browser", FAKE_COMMAND)
        result = self._run(
            display=display,
            screen="1024x768x24",
            port="8995",
            command=[str(smoke), "about:blank"],
        )
        self._wait_for_fake_xvfb_cleanup(display)

        self.assertEqual(result.returncode, 0, result.stderr)
        command = json.loads(self.command_record.read_text())
        self.assertEqual(command, {"args": ["about:blank"], "display": display})


if __name__ == "__main__":
    unittest.main()
