"""Assembles desktop/runtime from the Docker release, for `cargo tauri build` to bundle.

The backend and SQLite database are not here: the shell links `backend-rs` (with embedded SQLite)
and runs it in-process.

  runtime/spa/        frontend/dist          (build it first: make desktop does)
  runtime/python/     CPython of the sidecar base image's minor (python-build-standalone)
  runtime/sidecars/   the remaining Chromium sidecars, the shared lib/ and their packages in site/

Versions come from `.github/scripts/sidecars.py --desktop`, so the desktop app follows the
Docker release. Every download is checked against its published SHA-256. Targets: Linux and
Windows on x86_64 (`--target windows`) and macOS on Apple Silicon (`--target macos`); default: this machine.
"""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "desktop/runtime"
# Pinned: a rebuild of the same commit fetches the same CPython, and no GitHub API call
# (60 per hour unauthenticated) is needed. Bump by hand.
PYTHON_BUILD = "20261110"
PYTHON_RELEASE = f"https://github.com/astral-sh/python-build-standalone/releases/download/{PYTHON_BUILD}"
# Per target: the OS family, the triple of the CPython build, and the sys.platform that can
# install its wheels.
TARGETS = {
    "linux": {
        "os": "linux",
        "triple": "x86_64-unknown-linux-gnu",
        "platform": "linux",
    },
    "windows": {
        "os": "windows",
        "triple": "x86_64-pc-windows-msvc",
        "platform": "win32",
    },
    # Apple Silicon only: Intel Macs are no longer sold, and a second build doubles the release.
    "macos": {
        "os": "macos",
        "triple": "aarch64-apple-darwin",
        "platform": "darwin",
    },
}
# The 6 pure-HTTP bank sidecars run natively in-process inside backend-rs (`native://<service>`):
# Desktop no longer ships Python copies of them.
NATIVE_SIDECARS = frozenset({
    "bourso-auth",
    "cic-auth",
    "cmes-auth",
    "degiro-auth",
    "natixis-auth",
    "sofidy-auth",
})
# The shared packages of services/base needed by the remaining Chromium sidecars.
# `euro_information` was only used by cic-auth and cmes-auth (both now native Rust).
SHARED_LIBS = ("picsou_sidecar", "bpce")


def pins():
    out = subprocess.run([sys.executable, str(ROOT / ".github/scripts/sidecars.py"), "--desktop"],
                         capture_output=True, text=True, check=True).stdout
    return json.loads(out)


def _open(url, timeout):
    # Some mirrors refuse Python's default User-Agent with a 403.
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "picsou-desktop-build"}), timeout=timeout)


def download(url, sha256):
    with _open(url, 120) as response:
        body = response.read()
    if hashlib.sha256(body).hexdigest() != sha256.strip().split()[0]:
        sys.exit(f"checksum mismatch for {url}")
    return body


def text(url):
    with _open(url, 60) as response:
        return response.read().decode()


def extract(archive, target, strip_top=False):
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    if archive[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
            for info in zipped.infolist():
                parts = Path(info.filename).parts[1 if strip_top else 0:]
                if not parts or info.is_dir() or ".." in parts:
                    continue
                path = target.joinpath(*parts)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(zipped.read(info))
        return
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            if strip_top:
                parts = Path(member.name).parts[1:]
                if not parts:
                    continue
                member.name = str(Path(*parts))
            tar.extract(member, target, filter="data")


def python_asset(minor, triple):
    """The newest stripped CPython build of the base image's minor in PYTHON_BUILD, and its SHA-256."""
    pattern = re.compile(rf"cpython-{re.escape(minor)}\.(\d+)\+{PYTHON_BUILD}-{triple}-install_only_stripped\.tar\.gz")
    found = []
    for line in text(f"{PYTHON_RELEASE}/SHA256SUMS").splitlines():
        sha256, _, name = line.partition("  ")
        if m := pattern.fullmatch(name.strip()):
            found.append((int(m.group(1)), name.strip(), sha256))
    if not found:
        sys.exit(f"no python-build-standalone {minor} build for {triple} in {PYTHON_BUILD}")
    _, name, sha256 = max(found)
    return f"{PYTHON_RELEASE}/{name}", sha256


def trim_python(home):
    """Drops what no sidecar imports (Tk, IDLE, pip once used, headers, the test suite)."""
    lib = next(p for p in (home / "lib", home / "Lib") if p.exists())
    stdlib = lib / "python3.12" if (lib / "python3.12").exists() else lib
    doomed = [home / "include", home / "share", home / "tcl", home / "Scripts"]
    doomed += [stdlib / d for d in ("idlelib", "tkinter", "turtledemo", "ensurepip", "test", "lib2to3", "site-packages")]
    if stdlib != lib:
        # Linux: lib/ holds the Tcl/Tk trees next to python3.12/. On Windows lib/ IS the stdlib,
        # where these prefixes would also match threading.py.
        doomed += [p for p in lib.iterdir() if p.name.startswith(("tcl", "tk", "itcl", "thread", "libtcl", "libtk", "pkgconfig"))]
    doomed += [p for p in home.glob("DLLs/_tkinter*")] + [p for p in home.glob("DLLs/t*86t.dll")]
    # Linux: _tkinter links libtcl/libtk, which are gone; linuxdeploy (AppImage) fails on it.
    doomed += list((stdlib / "lib-dynload").glob("_tkinter*")) if (stdlib / "lib-dynload").exists() else []
    for path in doomed:
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists() or path.is_symlink():
            path.unlink()
    # tauri-bundler dereferences symlinks when copying resources into .app/.deb/AppImage, which
    # turns `python` and `python3` symlinks into extra 17 MB copies of `python3.12`. Flatten
    # `bin/python3` into the single real binary that `sidecars.rs` invokes and drop the rest.
    bin_dir = home / "bin"
    if bin_dir.is_dir():
        py3 = bin_dir / "python3"
        if py3.is_symlink():
            real = py3.resolve()
            py3.unlink()
            shutil.move(str(real), str(py3))
        for entry in list(bin_dir.iterdir()):
            if entry.name != "python3":
                if entry.is_dir() and not entry.is_symlink():
                    shutil.rmtree(entry)
                else:
                    entry.unlink()


def image_files(dockerfile):
    """The files a sidecar's Dockerfile copies into its image: the desktop ships the same ones."""
    files = []
    for line in dockerfile.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if parts[:1] == ["COPY"] and not any(p.startswith("--") for p in parts):
            files += parts[1:-1]
    if "main.py" not in files:
        sys.exit(f"{dockerfile} copies no main.py")
    return files


def sidecars(manifest, target):
    """CPython, the Chromium sidecars and their pinned packages, with sidecars.json."""
    url, sha256 = python_asset(manifest["python"], target["triple"])
    extract(download(url, sha256), RUNTIME / "python", strip_top=True)
    python = RUNTIME / "python" / ("python.exe" if target["os"] == "windows" else "bin/python3")
    out = RUNTIME / "sidecars"
    shutil.rmtree(out, ignore_errors=True)
    # Wheels must match the bundled interpreter, so the runtime is built on its own OS.
    subprocess.run([str(python), "-m", "pip", "install", "--quiet", "--no-compile", "--only-binary=:all:",
                    "--target", str(out / "site"), "-r", str(ROOT / "services/base/requirements.txt"),
                    "-r", str(ROOT / "services/base/requirements-chromium.txt")], check=True)
    compile_later = [out]
    # The image's rule: no test helper (test_*.py, testing.py) in the shipped libraries.
    tests = shutil.ignore_patterns("test_*.py", "testing.py", "__pycache__")
    for lib in SHARED_LIBS:
        shutil.copytree(ROOT / "services/base" / lib, out / "lib" / lib, ignore=tests)
    # Chromium itself is not bundled: the app downloads it on the first Chromium sidecar start.
    # Pure-HTTP sidecars in NATIVE_SIDECARS run natively in Rust and are not shipped as Python.
    shipped = [
        s for s in manifest["sidecars"]
        if s["desktop"] and s["base"] == "chromium" and s["service"] not in NATIVE_SIDECARS
    ]
    for sidecar in shipped:
        folder = ROOT / "services" / sidecar["service"]
        (out / sidecar["service"]).mkdir(parents=True)
        for name in image_files(folder / "Dockerfile"):
            shutil.copy2(folder / name, out / sidecar["service"] / name)
    (RUNTIME / "sidecars.json").write_text(json.dumps(shipped, indent=2), encoding="utf-8")
    # The ones the desktop cannot run via Python (Camoufox): the UI marks them "not available on
    # desktop" unless the native Tauri WebView bridge serves them (`webview_bank::PROVIDED_SERVICES`).
    left_out = [s["service"] for s in manifest["sidecars"] if not s["desktop"]]
    (RUNTIME / "sidecars-unavailable.json").write_text(json.dumps(left_out), encoding="utf-8")
    trim_python(RUNTIME / "python")
    # Bytecode once at build time, hash-checked so bundling's new mtimes do not invalidate it:
    # otherwise every cold start recompiles FastAPI and pydantic.
    subprocess.run([str(python), "-m", "compileall", "-q", "--invalidation-mode", "unchecked-hash",
                    *map(str, compile_later), str(RUNTIME / "python")], check=True)
    # The trimmed runtime must still import what every sidecar needs, as the app will run it.
    path = os.pathsep.join([str(out / "lib"), str(out / "site")])
    subprocess.run([str(python), "-c", "import threading, asyncio, uvicorn, fastapi, httpx, picsou_sidecar, playwright.async_api"],
                   check=True, env={**os.environ, "PYTHONPATH": path, "PYTHONNOUSERSITE": "1"})
    return [s["service"] for s in shipped]


def main():
    parser = argparse.ArgumentParser()
    here = next(name for name, t in TARGETS.items() if t["platform"] == sys.platform) if sys.platform in ("linux", "win32", "darwin") else "linux"
    parser.add_argument("--target", choices=TARGETS, default=here)
    target = TARGETS[parser.parse_args().target]
    manifest = pins()
    if not (ROOT / "frontend/dist/index.html").exists():
        sys.exit("build frontend/dist first (make desktop)")
    native = target["platform"] == sys.platform
    # Clean artifacts from older runtimes: the shell no longer bundles a JRE or PostgreSQL.
    for gone in ("jre", "app.jar", "postgres", "pgtools"):
        path = RUNTIME / gone
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()

    shutil.rmtree(RUNTIME / "spa", ignore_errors=True)
    shutil.copytree(ROOT / "frontend/dist", RUNTIME / "spa")
    if not native:
        print(f"warning: no sidecars, a {target['os']} runtime built elsewhere cannot install their wheels", file=sys.stderr)
    served = sidecars(manifest, target) if native else []
    print(f"runtime ready: embedded SQLite, sidecars: {', '.join(served) or 'none'}")


if __name__ == "__main__":
    main()
