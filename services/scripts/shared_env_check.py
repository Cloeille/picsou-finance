#!/usr/bin/env python3
"""Compare one shared Python env against per-sidecar venvs.

Stdlib only. Intended invocation, from the repo root, on Python 3.12:

    python3 -I services/scripts/shared_env_check.py

Host Python is not representative. Reproduce inside python:3.12-slim-bookworm
(see the command printed at the end of the report). The script installs the
apt list from services/corum-auth/Dockerfile when run as root, builds venvs
under SHARED_ENV_WORKDIR (default: a new temp directory), installs Chromium
once, and writes the markdown table to stdout and to SHARED_ENV_OUTPUT.

Does not modify sidecar code, requirements, or Dockerfiles.
"""

from __future__ import annotations

import json
import os
import pwd
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SERVICES = REPO / "services"
SHARED_REQ = SERVICES / "requirements-shared.txt"
CI_WORKFLOW = REPO / ".github" / "workflows" / "ci.yml"
CORUM_DOCKERFILE = SERVICES / "corum-auth" / "Dockerfile"

# Camoufox sidecars stay on their own Playwright/Firefox pin. Not measured.
EXCLUDED = ("amex-auth", "revolut-auth")

# Non-Camoufox sidecars. Order is the report order.
IN_SCOPE = (
    "amundi-auth",
    "bourse-direct-auth",
    "bourso-auth",
    "caisse-epargne-auth",
    "corum-auth",
    "degiro-auth",
    "fortuneo-auth",
    "sofidy-auth",
    "tr-auth",
)

# tr-auth is published (docker.yml) but has no unit-test job in ci.yml.
# Same key the other jobs pass into the image.
TR_AUTH_ENV = {"APP_SIDECAR_API_KEY": "test-key"}

RUNNER = textwrap.dedent(
    """\
    import json
    import os
    import sys
    import unittest

    start = sys.argv[1]
    dest = sys.argv[2]
    os.chdir(start)
    program = unittest.main(
        module=None,
        argv=["unittest", "discover", "-v", "-s", ".", "-t", "."],
        exit=False,
        verbosity=2,
    )
    result = program.result

    def rows(pairs):
        out = []
        for item in pairs:
            test = item[0]
            detail = item[1] if len(item) > 1 else ""
            out.append({"id": test.id(), "detail": detail})
        return out

    payload = {
        "testsRun": result.testsRun,
        "failures": rows(result.failures),
        "errors": rows(result.errors),
        "skipped": rows(result.skipped),
        "expectedFailures": rows(result.expectedFailures),
        "unexpectedSuccesses": [test.id() for test in result.unexpectedSuccesses],
    }
    with open(dest, "w", encoding="utf-8") as handle:
        json.dump(payload, handle)
    """
)

PROBE = textwrap.dedent(
    """\
    import asyncio
    from playwright.async_api import async_playwright

    async def main():
        async with async_playwright() as playwright:
            browser = await playwright.chromium.launch(headless=True)
            page = await browser.new_page()
            await page.set_content("<html><body>ok</body></html>")
            text = await page.inner_text("body")
            await browser.close()
        if text.strip() != "ok":
            raise SystemExit("probe content mismatch: " + text)
        print("PROBE_OK")

    asyncio.run(main())
    """
)


def log(message: str) -> None:
    print(message, file=sys.stderr, flush=True)
    progress = os.environ.get("SHARED_ENV_PROGRESS")
    if progress:
        with open(progress, "a", encoding="utf-8") as handle:
            handle.write(message + "\n")


def fail(message: str) -> None:
    log("ERROR: " + message)
    raise SystemExit(message)


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    log("+ " + " ".join(cmd))
    return subprocess.run(cmd, check=False, **kwargs)


def check(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    completed = run(cmd, **kwargs)
    if completed.returncode != 0:
        stderr = completed.stderr if isinstance(completed.stderr, str) else ""
        fail(f"command failed ({completed.returncode}): {' '.join(cmd)}\n{stderr}")
    return completed


def expose(path: Path) -> None:
    subprocess.check_call(["chmod", "-R", "a+rX", str(path)])


def dir_bytes(path: Path) -> int:
    completed = subprocess.run(
        ["du", "-sb", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return int(completed.stdout.split()[0])


def mib(nbytes: int) -> str:
    return f"{nbytes / (1024 * 1024):.1f}"


def require_python_312() -> None:
    if sys.version_info[:2] != (3, 12):
        fail(
            f"need Python 3.12, found {sys.version.split()[0]}. "
            "Run inside python:3.12-slim-bookworm; host 3.14 is not representative."
        )


def apt_packages(dockerfile: Path) -> list[str]:
    text = dockerfile.read_text(encoding="utf-8")
    match = re.search(
        r"apt-get install -y --no-install-recommends\s+(.*?)\\\s*\n\s*&&",
        text,
        re.S,
    )
    if match is None:
        fail(f"could not parse apt list from {dockerfile}")
    packages = []
    for line in match.group(1).splitlines():
        line = line.strip().rstrip("\\").strip()
        if not line or line.startswith("#"):
            continue
        packages.extend(part for part in line.split() if not part.startswith("#"))
    if not packages:
        fail(f"empty apt list parsed from {dockerfile}")
    return packages


def install_system_deps(packages: list[str]) -> None:
    if os.geteuid() != 0:
        log("not root; skipping apt install (Chromium libs must already be present)")
        return
    env = os.environ.copy()
    env["DEBIAN_FRONTEND"] = "noninteractive"
    check(["apt-get", "update"], env=env)
    # useradd lives in passwd, which slim does not always include.
    check(
        ["apt-get", "install", "-y", "--no-install-recommends", "passwd", *packages],
        env=env,
    )


def parse_ci_envs() -> dict[str, dict[str, str]]:
    """Env vars each CI job injects, keyed by sidecar directory name.

    PYTHONPATH=/app is the image copy of the sidecar. This experiment runs
    with cwd = the sidecar directory, so that value is rewritten to the
    sidecar path later. tr-auth has no CI job. A job counts only when it
    passes APP_SIDECAR_API_KEY into `docker run` — the backend job also
    mentions services/sofidy-auth/requirements.txt and must not win.
    """
    text = CI_WORKFLOW.read_text(encoding="utf-8")
    blocks = re.split(r"(?m)^  (?=[A-Za-z0-9-]+:\n)", text)
    found: dict[str, dict[str, str]] = {}
    for sidecar in IN_SCOPE:
        needle = f"services/{sidecar}"
        matches = [
            block
            for block in blocks
            if needle in block and "--env APP_SIDECAR_API_KEY=" in block
        ]
        if not matches:
            continue
        if len(matches) > 1:
            fail(f"ci.yml has {len(matches)} test jobs referencing {needle}")
        envs = dict(re.findall(r"--env\s+([A-Za-z_][A-Za-z0-9_]*)=(\S+)", matches[0]))
        if "APP_SIDECAR_API_KEY" not in envs:
            fail(f"ci.yml job for {sidecar} has no APP_SIDECAR_API_KEY")
        found[sidecar] = envs
    missing = [name for name in IN_SCOPE if name not in found and name != "tr-auth"]
    if missing:
        fail("ci.yml has no job for: " + ", ".join(missing))
    if "tr-auth" in found:
        log("note: tr-auth now has a CI job; using its env")
    else:
        found["tr-auth"] = dict(TR_AUTH_ENV)
        log("note: tr-auth has no CI job in ci.yml; using APP_SIDECAR_API_KEY=test-key")
    return found


def chromium_dockerfiles() -> list[str]:
    names = []
    for dockerfile in sorted(SERVICES.glob("*/Dockerfile")):
        text = dockerfile.read_text(encoding="utf-8")
        if re.search(r"playwright install chromium\b", text):
            names.append(dockerfile.parent.name)
    return names


def ensure_user(home: Path) -> tuple[int, int]:
    if os.geteuid() != 0:
        return os.getuid(), os.getgid()
    name = "envcheck"
    try:
        entry = pwd.getpwnam(name)
    except KeyError:
        check(["useradd", "--create-home", "--home-dir", str(home), "--shell", "/bin/sh", name])
        entry = pwd.getpwnam(name)
    home.mkdir(parents=True, exist_ok=True)
    os.chown(home, entry.pw_uid, entry.pw_gid)
    return entry.pw_uid, entry.pw_gid


def demote(uid: int, gid: int):
    def _preexec() -> None:
        if os.geteuid() != 0 or os.getuid() == uid:
            return
        os.setgid(gid)
        os.setuid(uid)

    return _preexec


def make_venv(path: Path, requirements: Path, pip_cache: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    check([sys.executable, "-m", "venv", str(path)])
    pip = path / "bin" / "pip"
    env = os.environ.copy()
    env["PIP_CACHE_DIR"] = str(pip_cache)
    env["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
    completed = run(
        [str(pip), "install", "--disable-pip-version-check", "-r", str(requirements)],
        env=env,
    )
    if completed.returncode != 0:
        log(f"pip failed for {requirements}; retrying once")
        check(
            [str(pip), "install", "--disable-pip-version-check", "-r", str(requirements)],
            env=env,
        )


def pip_freeze(python: Path) -> str:
    completed = subprocess.run(
        [str(python), "-m", "pip", "freeze"],
        check=True,
        capture_output=True,
        text=True,
    )
    return completed.stdout


def test_env(sidecar: Path, ci_env: dict[str, str], browsers: Path, home: Path) -> dict[str, str]:
    env = {
        "PATH": os.environ.get("PATH", "/usr/local/bin:/usr/bin:/bin"),
        "HOME": str(home),
        "LANG": os.environ.get("LANG", "C.UTF-8"),
        "LC_ALL": "C.UTF-8",
        "TMPDIR": os.environ.get("TMPDIR", "/tmp"),
        "PYTHONDONTWRITEBYTECODE": "1",
        "PYTHONUNBUFFERED": "1",
        "PLAYWRIGHT_BROWSERS_PATH": str(browsers),
        "XDG_CACHE_HOME": str(home / ".cache"),
    }
    for key, value in ci_env.items():
        if key == "PYTHONPATH" and value == "/app":
            # CI workdir is /tests; /app is the image copy of this sidecar.
            env["PYTHONPATH"] = str(sidecar)
        else:
            env[key] = value
    if "APP_SIDECAR_API_KEY" not in env:
        fail(f"missing APP_SIDECAR_API_KEY for {sidecar.name}")
    return env


def counts(payload: dict) -> dict[str, int]:
    failures = len(payload["failures"])
    errors = len(payload["errors"])
    skipped = len(payload["skipped"])
    expected = len(payload["expectedFailures"])
    unexpected = len(payload["unexpectedSuccesses"])
    ran = payload["testsRun"]
    passed = ran - failures - errors - skipped - expected - unexpected
    if passed < 0:
        fail(f"negative pass count from {payload}")
    return {
        "ran": ran,
        "pass": passed,
        "fail": failures,
        "error": errors,
        "skip": skipped,
        "expected_fail": expected,
        "unexpected_success": unexpected,
    }


def cell(count: dict[str, int]) -> str:
    text = f"{count['pass']}/{count['fail']}/{count['skip']}"
    extra = []
    if count["error"]:
        extra.append(f"errors={count['error']}")
    if count["expected_fail"]:
        extra.append(f"expected_fail={count['expected_fail']}")
    if count["unexpected_success"]:
        extra.append(f"unexpected_success={count['unexpected_success']}")
    if extra:
        text += " (" + ", ".join(extra) + ")"
    return text


def outcome_map(payload: dict) -> dict[str, str]:
    outcomes = {}
    for row in payload["failures"]:
        outcomes[row["id"]] = "fail"
    for row in payload["errors"]:
        outcomes[row["id"]] = "error"
    for row in payload["skipped"]:
        outcomes[row["id"]] = "skip"
    for row in payload["expectedFailures"]:
        outcomes[row["id"]] = "expected_fail"
    for test_id in payload["unexpectedSuccesses"]:
        outcomes[test_id] = "unexpected_success"
    return outcomes


def run_tests(
    python: Path,
    sidecar: Path,
    env: dict[str, str],
    runner: Path,
    dest: Path,
    log_path: Path,
    uid: int,
    gid: int,
) -> dict:
    dest.parent.mkdir(parents=True, exist_ok=True)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    harness_error = None
    try:
        with log_path.open("w", encoding="utf-8") as handle:
            completed = subprocess.run(
                [str(python), str(runner), str(sidecar), str(dest)],
                cwd=sidecar,
                env=env,
                stdout=handle,
                stderr=subprocess.STDOUT,
                preexec_fn=demote(uid, gid),
                timeout=900,
            )
    except subprocess.TimeoutExpired as exc:
        harness_error = f"timed out after 900s: {exc}"
        completed = None
    if harness_error is None and not dest.exists():
        tail = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
        code = completed.returncode if completed is not None else 1
        harness_error = f"no test result (exit {code}):\n{tail}"
    if harness_error is not None:
        log(f"harness error for {sidecar.name}: {harness_error.splitlines()[0]}")
        return {
            "testsRun": 1,
            "failures": [],
            "errors": [{"id": "harness.Run.crashed", "detail": harness_error}],
            "skipped": [],
            "expectedFailures": [],
            "unexpectedSuccesses": [],
            "exit_code": 1,
        }
    assert completed is not None
    payload = json.loads(dest.read_text(encoding="utf-8"))
    payload["exit_code"] = completed.returncode
    return payload


def record_break(
    sidecar: str,
    test_id: str,
    status: str,
    detail: str,
    differences: list[str],
    log_dir: Path,
) -> None:
    cause = first_error_line(detail)
    kind = classify(detail)
    item = (
        f"{sidecar} `{test_id}`: shared {status}, own pass. "
        f"Error: {cause}. Fix class: {kind}."
    )
    differences.append(item)
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", test_id)[:180]
    (log_dir / f"{sidecar}-diff-{safe}.txt").write_text(detail, encoding="utf-8")


def collect_pin_diffs(
    sidecar: str,
    own_payload: dict,
    shared_payload: dict,
    log_dir: Path,
) -> tuple[list[str], list[str]]:
    """Return (shared broke what own passed, own broke what shared passed)."""
    own_outcomes = outcome_map(own_payload)
    shared_outcomes = outcome_map(shared_payload)
    shared_details = index_details(shared_payload)
    own_details = index_details(own_payload)
    broke: list[str] = []
    reverse: list[str] = []
    for test_id, detail in shared_details.items():
        status = shared_outcomes.get(test_id)
        if status not in ("fail", "error"):
            continue
        if own_outcomes.get(test_id) == "pass":
            record_break(sidecar, test_id, status, detail, broke, log_dir)
            continue
        if "FailedTest" not in test_id and "ModuleImportFailure" not in test_id:
            continue
        module = test_id.rsplit(".", 1)[-1]
        passed = [
            tid
            for tid, own_status in own_outcomes.items()
            if own_status == "pass" and tid.split(".")[0] == module
        ]
        if not passed:
            continue
        cause = first_error_line(detail)
        kind = classify(detail)
        broke.append(
            f"{sidecar} module `{module}` ({len(passed)} tests passed on own pins, "
            f"e.g. `{passed[0]}`): shared {status} at import. "
            f"Error: {cause}. Fix class: {kind}."
        )
        safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", test_id)[:180]
        (log_dir / f"{sidecar}-diff-{safe}.txt").write_text(detail, encoding="utf-8")
    for test_id, detail in own_details.items():
        status = own_outcomes.get(test_id)
        if status not in ("fail", "error"):
            continue
        if shared_outcomes.get(test_id) != "pass":
            continue
        cause = first_error_line(detail)
        reverse.append(
            f"{sidecar} `{test_id}`: own {status}, shared pass. Error: {cause}."
        )
    return broke, reverse


def index_details(payload: dict) -> dict[str, str]:
    details = {}
    for key in ("failures", "errors", "skipped", "expectedFailures"):
        for row in payload[key]:
            details[row["id"]] = row.get("detail", "")
    return details


def chromium_executable(python: Path, browsers: Path, home: Path, uid: int, gid: int) -> str:
    code = (
        "from playwright.sync_api import sync_playwright\n"
        "with sync_playwright() as playwright:\n"
        "    print(playwright.chromium.executable_path)\n"
    )
    env = test_env(SERVICES, {"APP_SIDECAR_API_KEY": "test-key"}, browsers, home)
    completed = subprocess.run(
        [str(python), "-c", code],
        env=env,
        capture_output=True,
        text=True,
        preexec_fn=demote(uid, gid),
    )
    if completed.returncode != 0:
        fail("could not resolve chromium executable:\n" + completed.stderr)
    return completed.stdout.strip()


def wrap_no_sandbox(executable: str) -> str:
    path = Path(executable)
    real = path.with_name(path.name + ".real")
    if not real.exists():
        path.rename(real)
        real.chmod(real.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    wrapper = "#!/bin/sh\nexec " + sh_quote(str(real)) + ' --no-sandbox --disable-dev-shm-usage "$@"\n'
    path.write_text(wrapper, encoding="utf-8")
    path.chmod(0o755)
    return str(path)


def sh_quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def probe_chromium(python: Path, browsers: Path, home: Path, uid: int, gid: int, probe: Path) -> tuple[bool, str]:
    env = test_env(SERVICES, {"APP_SIDECAR_API_KEY": "test-key"}, browsers, home)
    completed = subprocess.run(
        [str(python), str(probe)],
        env=env,
        capture_output=True,
        text=True,
        preexec_fn=demote(uid, gid),
        timeout=180,
    )
    output = (completed.stdout or "") + (completed.stderr or "")
    return completed.returncode == 0 and "PROBE_OK" in output, output[-4000:]


def install_chromium(python: Path, browsers: Path) -> str:
    browsers.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["PLAYWRIGHT_BROWSERS_PATH"] = str(browsers)
    cmd = [str(python), "-m", "playwright", "install", "--with-deps", "chromium"]
    completed = run(cmd, env=env)
    if completed.returncode != 0:
        log("playwright install --with-deps failed; retrying without --with-deps")
        check([str(python), "-m", "playwright", "install", "chromium"], env=env)
        return "playwright install chromium ( --with-deps failed, browsers only )"
    return "playwright install --with-deps chromium"


def self_check(runner: Path, work: Path) -> None:
    sample = work / "self-check"
    sample.mkdir(parents=True)
    (sample / "test_ok.py").write_text(
        "import unittest\n"
        "class T(unittest.TestCase):\n"
        "    def test_pass(self):\n"
        "        self.assertTrue(True)\n"
        "    @unittest.skip('no')\n"
        "    def test_skip(self):\n"
        "        pass\n",
        encoding="utf-8",
    )
    dest = work / "self-check.json"
    check([sys.executable, str(runner), str(sample), str(dest)])
    payload = json.loads(dest.read_text(encoding="utf-8"))
    count = counts(payload)
    if count != {
        "ran": 2,
        "pass": 1,
        "fail": 0,
        "error": 0,
        "skip": 1,
        "expected_fail": 0,
        "unexpected_success": 0,
    }:
        fail(f"runner self-check mismatch: {count}")
    log("runner self-check ok")


def first_error_line(detail: str) -> str:
    lines = [line.rstrip() for line in detail.strip().splitlines() if line.strip()]
    if not lines:
        return "(no traceback)"
    # unittest tracebacks end with the exception line.
    for line in reversed(lines):
        if line.startswith(" ") or line.startswith("\t"):
            continue
        if line.startswith("Traceback") or line.startswith("---") or line.startswith("==="):
            continue
        return line
    return lines[-1]


def classify(detail: str) -> str:
    text = detail.lower()
    if "modulenotfounderror" in text or "importerror" in text:
        return "incompatible pin or missing extra package (import failed; do not guess a code fix)"
    if "typeerror" in text and ("unexpected keyword" in text or "got an unexpected" in text):
        return "incompatible pin (API removed or renamed between the pinned versions)"
    if "attributeerror" in text:
        return "incompatible pin or code change (attribute missing on the upgraded library)"
    if "validationerror" in text or "pydantic" in text:
        return "code change (pydantic model no longer accepts the previous shape) or incompatible pin"
    if "assertionerror" in text:
        return "test change or code change (assertion changed under the new pins; not a missing package)"
    return "see traceback; not classified automatically"


def build_report(
    rows: list[dict],
    shared_bytes: int,
    chromium_bytes: int,
    chromium_names: list[str],
    install_note: str,
    sandbox_note: str,
    differences: list[str],
    reverse: list[str],
) -> str:
    own_sum = sum(row["own_bytes"] for row in rows)
    # Copies today are the auth sidecars that still `playwright install chromium`.
    # browser-service (and anything else) is a separate process, not one of those copies.
    copies = [
        name for name in chromium_names
        if name.endswith("-auth") and name not in EXCLUDED
    ]
    others = [name for name in chromium_names if name not in copies and name not in EXCLUDED]
    excluded_copies = [name for name in chromium_names if name in EXCLUDED]
    lines = [
        "# Single Python env experiment",
        "",
        f"Python {sys.version.split()[0]}. Shared pins: `{SHARED_REQ.relative_to(REPO)}`.",
        f"Chromium install: {install_note}.",
        f"Excluded (Camoufox, not measured): {', '.join(EXCLUDED)}.",
        sandbox_note,
        "",
        "| sidecar | own-venv MB | tests with own pins (pass/fail/skip) | tests with shared pins (pass/fail/skip) |",
        "|---|---:|---|---|",
    ]
    for row in rows:
        lines.append(
            f"| {row['name']} | {mib(row['own_bytes'])} | {row['own_cell']} | {row['shared_cell']} |"
        )
    lines.extend(
        [
            "",
            "## Totals",
            "",
            f"- Sum of own venvs: {mib(own_sum)} MB ({own_sum} bytes).",
            f"- One shared venv: {mib(shared_bytes)} MB ({shared_bytes} bytes). Chromium is not inside this directory.",
            (
                f"- Chromium copies today: {len(copies)} sidecar Dockerfiles install chromium "
                f"({', '.join(copies) or 'none'}) × {mib(chromium_bytes)} MB "
                f"({chromium_bytes} bytes) = {mib(len(copies) * chromium_bytes)} MB "
                f"({len(copies) * chromium_bytes} bytes) vs one copy {mib(chromium_bytes)} MB."
            ),
            "- MB in this table is MiB (1024^2), from `du -sb`.",
        ]
    )
    if others:
        lines.append(
            "- Also installs chromium, not counted as a sidecar copy: "
            + ", ".join(others)
            + "."
        )
    if excluded_copies:
        lines.append(
            "- Excluded Dockerfiles that also install chromium: " + ", ".join(excluded_copies) + "."
        )
    lines.extend(["", "## Differences (shared fail/error, own pass)", ""])
    if differences:
        lines.extend(f"- {item}" for item in differences)
    else:
        lines.append("- None.")
    lines.extend(["", "## Reverse (own fail/error, shared pass)", ""])
    if reverse:
        lines.extend(f"- {item}" for item in reverse)
    else:
        lines.append("- None.")
    lines.extend(
        [
            "",
            "pass/fail/skip counts failures separately from errors. "
            "A cell with `errors=N` failed to load or raised outside an assertion. "
            "fail in the three-number cell is assertion failures only.",
            "",
            "tr-auth has no job in `.github/workflows/ci.yml`; tests ran with "
            "`APP_SIDECAR_API_KEY=test-key` only. Other sidecars use the `--env` "
            "lines of their CI job. `PYTHONPATH=/app` is rewritten to the sidecar "
            "directory because cwd is that directory, not `/tests`.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    require_python_312()
    if not SHARED_REQ.is_file():
        fail(f"missing {SHARED_REQ}")
    output = os.environ.get("SHARED_ENV_OUTPUT")
    json_path = os.environ.get("SHARED_ENV_JSON")
    log_dir = Path(os.environ.get("SHARED_ENV_LOG_DIR", "")) if os.environ.get("SHARED_ENV_LOG_DIR") else None
    progress = os.environ.get("SHARED_ENV_PROGRESS")
    if progress:
        Path(progress).parent.mkdir(parents=True, exist_ok=True)
        Path(progress).write_text("", encoding="utf-8")

    work = Path(os.environ.get("SHARED_ENV_WORKDIR") or tempfile.mkdtemp(prefix="shared-env-"))
    work.mkdir(parents=True, exist_ok=True)
    os.chmod(work, 0o755)
    if log_dir is None:
        log_dir = work / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    log(f"workdir {work}")

    packages = apt_packages(CORUM_DOCKERFILE)
    log("apt packages from corum Dockerfile: " + " ".join(packages))
    install_system_deps(packages)

    ci_envs = parse_ci_envs()
    home = work / "home"
    uid, gid = ensure_user(home)
    os.makedirs(home / ".cache", exist_ok=True)
    if os.geteuid() == 0:
        os.chown(home / ".cache", uid, gid)
        os.chmod(log_dir, 0o777)

    runner = work / "run_discover.py"
    runner.write_text(RUNNER, encoding="utf-8")
    probe = work / "probe_chromium.py"
    probe.write_text(PROBE, encoding="utf-8")
    self_check(runner, work)

    pip_cache = work / "pip-cache"
    pip_cache.mkdir(exist_ok=True)
    browsers = work / "playwright-browsers"

    shared_venv = work / "venv-shared"
    log("building shared venv")
    make_venv(shared_venv, SHARED_REQ, pip_cache)
    shared_python = shared_venv / "bin" / "python"
    install_note = install_chromium(shared_python, browsers)
    expose(shared_venv)
    expose(browsers)
    chromium_bytes = dir_bytes(browsers)
    log(f"chromium tree {chromium_bytes} bytes")

    sandbox_note = "Chromium probe: not run."
    ok, probe_out = probe_chromium(shared_python, browsers, home, uid, gid, probe)
    if ok:
        sandbox_note = "Chromium probe (headless launch, no extra flags) passed as the test user."
        log(sandbox_note)
    else:
        log("chromium probe failed; wrapping the executable with --no-sandbox")
        (log_dir / "chromium-probe.txt").write_text(probe_out, encoding="utf-8")
        executable = chromium_executable(shared_python, browsers, home, uid, gid)
        wrapped = wrap_no_sandbox(executable)
        ok, probe_out = probe_chromium(shared_python, browsers, home, uid, gid, probe)
        (log_dir / "chromium-probe-after-wrap.txt").write_text(probe_out, encoding="utf-8")
        if not ok:
            sandbox_note = (
                "Chromium probe failed even after a --no-sandbox wrapper on "
                f"{wrapped}. Browser tests may error. See chromium-probe logs."
            )
            log(sandbox_note)
        else:
            sandbox_note = (
                "Chromium probe failed as the test user without extra flags "
                f"(typical Docker sandbox). Wrapped {executable} with --no-sandbox "
                "so launch() matches what CI gets from a non-root user namespace. "
                "The wrapper is outside the repo."
            )
            log(sandbox_note)

    shared_bytes = dir_bytes(shared_venv)
    log(f"shared venv {shared_bytes} bytes")
    freeze_shared = pip_freeze(shared_python)
    (log_dir / "freeze-shared.txt").write_text(freeze_shared, encoding="utf-8")
    expose(shared_venv)
    expose(browsers)
    expose(work / "run_discover.py")
    os.chmod(log_dir, 0o777)

    rows = []
    differences: list[str] = []
    reverse: list[str] = []
    json_rows = []
    for name in IN_SCOPE:
        sidecar = SERVICES / name
        requirements = sidecar / "requirements.txt"
        if not requirements.is_file():
            fail(f"missing {requirements}")
        own_venv = work / f"venv-{name}"
        log(f"building own venv {name}")
        make_venv(own_venv, requirements, pip_cache)
        expose(own_venv)
        own_python = own_venv / "bin" / "python"
        own_bytes = dir_bytes(own_venv)
        env = test_env(sidecar, ci_envs[name], browsers, home)
        log(f"testing {name} with own pins")
        own_payload = run_tests(
            own_python,
            sidecar,
            env,
            runner,
            log_dir / f"{name}-own.json",
            log_dir / f"{name}-own.log",
            uid,
            gid,
        )
        log(f"testing {name} with shared pins")
        shared_payload = run_tests(
            shared_python,
            sidecar,
            env,
            runner,
            log_dir / f"{name}-shared.json",
            log_dir / f"{name}-shared.log",
            uid,
            gid,
        )
        own_count = counts(own_payload)
        shared_count = counts(shared_payload)
        own_cell = cell(own_count)
        shared_cell = cell(shared_count)
        log(f"{name}: own {own_cell} ({mib(own_bytes)} MB), shared {shared_cell}")
        broke, reverse_rows = collect_pin_diffs(name, own_payload, shared_payload, log_dir)
        differences.extend(broke)
        reverse.extend(reverse_rows)
        rows.append(
            {
                "name": name,
                "own_bytes": own_bytes,
                "own_cell": own_cell,
                "shared_cell": shared_cell,
            }
        )
        json_rows.append(
            {
                "sidecar": name,
                "own_bytes": own_bytes,
                "own": own_count,
                "shared": shared_count,
                "ci_env": ci_envs[name],
                "own_failures": own_payload["failures"],
                "own_errors": own_payload["errors"],
                "shared_failures": shared_payload["failures"],
                "shared_errors": shared_payload["errors"],
                "broke": broke,
                "reverse": reverse_rows,
            }
        )

    chromium_names = chromium_dockerfiles()
    report = build_report(
        rows,
        shared_bytes,
        chromium_bytes,
        chromium_names,
        install_note,
        sandbox_note,
        differences,
        reverse,
    )
    print(report, flush=True)
    if output:
        Path(output).write_text(report, encoding="utf-8")
        log(f"wrote {output}")
    if json_path:
        Path(json_path).write_text(
            json.dumps(
                {
                    "python": sys.version,
                    "shared_bytes": shared_bytes,
                    "chromium_bytes": chromium_bytes,
                    "chromium_dockerfiles": chromium_names,
                    "install_note": install_note,
                    "sandbox_note": sandbox_note,
                    "differences": differences,
                    "rows": json_rows,
                    "shared_freeze": freeze_shared,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        log(f"wrote {json_path}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.TimeoutExpired as exc:
        fail(f"timed out: {exc.cmd}")
    except subprocess.CalledProcessError as exc:
        fail(f"command failed: {exc.cmd} ({exc.returncode})")
