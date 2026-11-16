"""Assembles desktop/runtime from the Docker release, for `cargo tauri build` to bundle.

  runtime/jre/        Temurin JRE, the Java major of backend/pom.xml (Adoptium API)
  runtime/postgres/   PostgreSQL binaries, the Compose major (zonky embedded-postgres)
  runtime/app.jar     backend/target/*.jar   (build it first: make desktop does)
  runtime/spa/        frontend/dist
  runtime/python/     CPython of the sidecar base image's minor (python-build-standalone)
  runtime/sidecars/   the browserless sidecars, the shared lib/ and their packages in site/

Versions come from `.github/scripts/sidecars.py --desktop`, so the desktop app follows the
Docker release. Every download is checked against its published SHA-256. Targets: Linux and
Windows on x86_64 (`--target windows`, default: this machine). macOS comes with #591.
"""

import argparse
import hashlib
import io
import os
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ElementTree
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "desktop/runtime"
ADOPTIUM = "https://api.adoptium.net/v3/assets/latest/{java}/hotspot?os={os}&architecture=x64&image_type=jre"
ZONKY = "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-{os}-amd64"
# Pinned, like the JRE and PostgreSQL are by their manifests: a rebuild of the same commit fetches
# the same CPython, and no GitHub API call (60 per hour unauthenticated) is needed. Bump by hand.
PYTHON_BUILD = "20261110"
PYTHON_RELEASE = f"https://github.com/astral-sh/python-build-standalone/releases/download/{PYTHON_BUILD}"
# Per target: the Adoptium/zonky OS name, the archive inside zonky's jar, the CPython triple.
TARGETS = {
    "linux": {"os": "linux", "archive": "postgres-linux-x86_64.txz", "python": "x86_64-unknown-linux-gnu"},
    "windows": {"os": "windows", "archive": "postgres-windows-x86_64.txz", "python": "x86_64-pc-windows-msvc"},
}
# The shared packages of services/base, as the image's site-packages has them.
SHARED_LIBS = ("picsou_sidecar", "euro_information", "bpce")


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


def latest_postgres(zonky, major):
    """The newest zonky release of the Compose major, e.g. 16.9.0."""
    metadata = ElementTree.fromstring(text(f"{zonky}/maven-metadata.xml"))
    versions = [v.text for v in metadata.iter("version")
                if v.text and re.fullmatch(r"\d+(\.\d+)*", v.text) and v.text.split(".")[0] == major]
    if not versions:
        sys.exit(f"no zonky PostgreSQL {major} release")
    return max(versions, key=lambda v: tuple(int(part) for part in v.split(".")))


def extract(archive, target, strip_top=False):
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    if archive[:2] == b"PK":  # Adoptium ships Windows JREs as zip
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
    for path in doomed:
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()


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
    """CPython, the browserless sidecars and their pinned packages, with sidecars.json."""
    url, sha256 = python_asset(manifest["python"], target["python"])
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
    shipped = [s for s in manifest["sidecars"] if s["desktop"] and s["base"] in ("python", "chromium")]
    for sidecar in shipped:
        folder = ROOT / "services" / sidecar["service"]
        (out / sidecar["service"]).mkdir(parents=True)
        for name in image_files(folder / "Dockerfile"):
            shutil.copy2(folder / name, out / sidecar["service"] / name)
    (RUNTIME / "sidecars.json").write_text(json.dumps(shipped, indent=2), encoding="utf-8")
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
    parser.add_argument("--target", choices=TARGETS, default="windows" if sys.platform == "win32" else "linux")
    target = TARGETS[parser.parse_args().target]
    manifest = pins()
    if not (ROOT / "frontend/dist/index.html").exists() or not list((ROOT / "backend/target").glob("*.jar")):
        sys.exit("build the backend jar and frontend/dist first (make desktop)")

    jre = json.loads(text(ADOPTIUM.format(java=manifest["java"], os=target["os"])))[0]["binary"]["package"]
    extract(download(jre["link"], jre["checksum"]), RUNTIME / "jre", strip_top=True)

    zonky = ZONKY.format(os=target["os"])
    version = latest_postgres(zonky, manifest["postgres"])
    jar_url = f"{zonky}/{version}/embedded-postgres-binaries-{target['os']}-amd64-{version}.jar"
    jar = zipfile.ZipFile(io.BytesIO(download(jar_url, text(f"{jar_url}.sha256"))))
    extract(jar.read(target["archive"]), RUNTIME / "postgres")
    if target["os"] == "windows":
        # zonky's binaries import the MSVC runtime, which a fresh Windows may lack; the JRE ships it.
        for dll in ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll"):
            found = next((RUNTIME / "jre" / "bin").glob(dll, case_sensitive=False), None)
            if found is None:
                sys.exit(f"{dll} is missing from the JRE: PostgreSQL would not start without the VC++ runtime")
            shutil.copy2(found, RUNTIME / "postgres" / "bin" / dll)

    shutil.copy2(next((ROOT / "backend/target").glob("picsou-backend-*.jar")), RUNTIME / "app.jar")
    shutil.rmtree(RUNTIME / "spa", ignore_errors=True)
    shutil.copytree(ROOT / "frontend/dist", RUNTIME / "spa")
    native = (target["os"] == "windows") == (sys.platform == "win32")
    if not native:
        print(f"warning: no sidecars, a {target['os']} runtime built elsewhere cannot install their wheels", file=sys.stderr)
    served = sidecars(manifest, target) if native else []
    print(f"runtime ready: JRE {jre['name']}, PostgreSQL {version}, sidecars: {', '.join(served) or 'none'}")


if __name__ == "__main__":
    main()
