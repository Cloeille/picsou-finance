"""Runs the browserless sidecars outside a container, as the desktop app will (#235).

For each sidecar on the `python` base (sidecars.py): its unittest modules from its folder,
then a start with uvicorn bound to 127.0.0.1 on a free port and one GET /health. The
shared package comes from services/base on PYTHONPATH, where the image has it in its
site-packages. CI runs it under uv (see ci.yml); locally, any Python of the base
image's minor with `pip install -r services/base/requirements.txt` does.

  python .github/scripts/native_sidecars.py            every browserless sidecar
  python .github/scripts/native_sidecars.py cic-auth   only the ones named
"""

import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sidecars import ROOT, inventory  # noqa: E402

HEALTH_TIMEOUT_S = 30
# Loopback only: ignore proxy variables and the Windows proxy settings.
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _env():
    return {
        **os.environ,
        "PYTHONPATH": str(ROOT / "services/base"),
        "PYTHONDONTWRITEBYTECODE": "1",
        "PYTHONUTF8": "1",
        "APP_SIDECAR_API_KEY": "native-test-key",
    }


def _free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def _health(port, server):
    deadline = time.monotonic() + HEALTH_TIMEOUT_S
    while time.monotonic() < deadline:
        if server.poll() is not None:
            return f"exited with {server.returncode} before answering"
        try:
            with NO_PROXY.open(f"http://127.0.0.1:{port}/health", timeout=2) as response:
                body = json.load(response)
                return None if body.get("status") == "ok" else f"answered {body}"
        except (OSError, ValueError):
            time.sleep(0.5)
    return f"no answer within {HEALTH_TIMEOUT_S}s"


def check(service):
    folder = ROOT / "services" / service
    tests = subprocess.run([sys.executable, "-m", "unittest", "discover", "-v"], cwd=folder, env=_env())
    if tests.returncode != 0:
        return f"{service}: tests failed"
    port = _free_port()
    server = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", str(port)],
        cwd=folder, env=_env(),
    )
    try:
        problem = _health(port, server)
    finally:
        server.terminate()
        try:
            server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
    return f"{service}: /health on 127.0.0.1:{port} {problem}" if problem else None


def main(names):
    browserless = [sidecar["service"] for sidecar in inventory() if sidecar["base"] == "python"]
    unknown = set(names) - set(browserless)
    if unknown:
        sys.exit(f"not a browserless sidecar: {', '.join(sorted(unknown))}")
    failures = [problem for problem in map(check, names or browserless) if problem]
    for problem in failures:
        print(f"::error::{problem}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main(sys.argv[1:])
