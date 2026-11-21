"""Assembles desktop/runtime from the Docker release, for `cargo tauri build` to bundle.

The backend is not here: the shell links it (backend-rs) and runs it in its own process.

  runtime/postgres/   PostgreSQL server binaries, the Compose major (zonky embedded-postgres)
  runtime/pgtools/    pg_dump and pg_restore of the same major and their libraries (theseus-rs)
  runtime/spa/        frontend/dist          (build it first: make desktop does)
  runtime/python/     CPython of the sidecar base image's minor (python-build-standalone)
  runtime/sidecars/   the browserless sidecars, the shared lib/ and their packages in site/

Versions come from `.github/scripts/sidecars.py --desktop`, so the desktop app follows the
Docker release. Every download is checked against its published SHA-256. Targets: Linux and
Windows on x86_64 (`--target windows`) and macOS on Apple Silicon (`--target macos`); default: this machine.
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
ZONKY = "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-{artifact}"
# Pinned, like PostgreSQL is by its manifest: a rebuild of the same commit fetches
# the same CPython, and no GitHub API call (60 per hour unauthenticated) is needed. Bump by hand.
PYTHON_BUILD = "20261110"
PYTHON_RELEASE = f"https://github.com/astral-sh/python-build-standalone/releases/download/{PYTHON_BUILD}"
# zonky ships no client tools: pg_dump (the backup before a migration) and pg_restore come from
# theseus-rs/postgresql-binaries, pinned like CPython. Its major must be the Compose one. Bump by hand.
PG_TOOLS = "16.16.0"
PG_TOOLS_RELEASE = f"https://github.com/theseus-rs/postgresql-binaries/releases/download/{PG_TOOLS}"
# Per target: the OS family, zonky's artifact and the archive inside its jar, the triple of the
# CPython and PostgreSQL builds, the sys.platform that can install its wheels, and the libraries
# pg_dump and pg_restore load (their closure in the theseus build, read with otool, ELF and PE
# headers; a native build checks it by running pg_dump).
TARGETS = {
    "linux": {"os": "linux", "artifact": "linux-amd64", "archive": "postgres-linux-x86_64.txz",
              "triple": "x86_64-unknown-linux-gnu", "platform": "linux",
              "pg_libs": ("libpq.so.5", "libssl.so.3", "libcrypto.so.3", "libgssapi_krb5.so.2", "libkrb5.so.3",
                          "libk5crypto.so.3", "libkrb5support.so.0", "libcom_err.so.2", "libkeyutils.so.1",
                          "libzstd.so.1", "liblz4.so.1", "libz.so.1")},
    "windows": {"os": "windows", "artifact": "windows-amd64", "archive": "postgres-windows-x86_64.txz",
                "triple": "x86_64-pc-windows-msvc", "platform": "win32",
                "pg_libs": ("libpq.dll", "libssl-3-x64.dll", "libcrypto-3-x64.dll", "zstd.dll", "lz4.dll", "z.dll")},
    # Apple Silicon only: Intel Macs are no longer sold, and a second build doubles the release.
    "macos": {"os": "macos", "artifact": "darwin-arm64v8", "archive": "postgres-darwin-arm_64.txz",
              "triple": "aarch64-apple-darwin", "platform": "darwin",
              "pg_libs": ("libpq.5.dylib", "libssl.3.dylib", "libcrypto.3.dylib", "libzstd.1.dylib", "liblz4.1.dylib")},
}
# The MSVC runtime zonky's and theseus' Windows binaries import, which a fresh Windows may lack.
VC_RUNTIME = ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")
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
    shipped = [s for s in manifest["sidecars"] if s["desktop"] and s["base"] in ("python", "chromium")]
    for sidecar in shipped:
        folder = ROOT / "services" / sidecar["service"]
        (out / sidecar["service"]).mkdir(parents=True)
        for name in image_files(folder / "Dockerfile"):
            shutil.copy2(folder / name, out / sidecar["service"] / name)
    (RUNTIME / "sidecars.json").write_text(json.dumps(shipped, indent=2), encoding="utf-8")
    # The ones the desktop cannot run (Camoufox): the UI marks them "not available on desktop".
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


def pg_tools(manifest, target, native):
    """pg_dump and pg_restore with the libraries they load, in pgtools/bin and pgtools/lib."""
    if PG_TOOLS.split(".")[0] != manifest["postgres"]:
        sys.exit(f"PG_TOOLS {PG_TOOLS} is not PostgreSQL {manifest['postgres']}: bump it")
    top = f"postgresql-{PG_TOOLS}-{target['triple']}"
    url = f"{PG_TOOLS_RELEASE}/{top}.tar.gz"
    out = RUNTIME / "pgtools"
    shutil.rmtree(out, ignore_errors=True)
    windows = target["os"] == "windows"
    exe = ".exe" if windows else ""
    # Windows finds DLLs next to the program; the others use the build's ../lib rpath.
    lib_dir = "bin" if windows else "lib"
    wanted = [f"bin/pg_dump{exe}", f"bin/pg_restore{exe}"] + [f"{lib_dir}/{lib}" for lib in target["pg_libs"]]
    with tarfile.open(fileobj=io.BytesIO(download(url, text(f"{url}.sha256")))) as tar:
        for path in wanted:
            # extractfile follows the archive's versioned symlinks: the bundle holds plain files.
            body = tar.extractfile(f"{top}/{path}").read()
            target_path = out / path
            target_path.parent.mkdir(parents=True, exist_ok=True)
            target_path.write_bytes(body)
            target_path.chmod(0o755)
    if native:
        subprocess.run([str(out / "bin" / f"pg_dump{exe}"), "--version"], check=True)


def vc_runtime(folders):
    """Windows: the MSVC runtime from this machine's System32, next to each PostgreSQL program."""
    system32 = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32"
    for dll in VC_RUNTIME:
        found = system32 / dll
        if not found.exists():
            sys.exit(f"{found} is missing: install the VC++ redistributable on the build machine")
        for folder in folders:
            shutil.copy2(found, folder / dll)


def main():
    parser = argparse.ArgumentParser()
    here = next(name for name, t in TARGETS.items() if t["platform"] == sys.platform) if sys.platform in ("linux", "win32", "darwin") else "linux"
    parser.add_argument("--target", choices=TARGETS, default=here)
    target = TARGETS[parser.parse_args().target]
    manifest = pins()
    if not (ROOT / "frontend/dist/index.html").exists():
        sys.exit("build frontend/dist first (make desktop)")
    native = target["platform"] == sys.platform
    if target["os"] == "windows" and not native:
        sys.exit("build the Windows runtime on Windows: the VC++ runtime comes from its System32")
    # A runtime assembled by an older version of this script: the shell no longer runs a JRE.
    for gone in ("jre", "app.jar"):
        path = RUNTIME / gone
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()

    zonky = ZONKY.format(artifact=target["artifact"])
    version = latest_postgres(zonky, manifest["postgres"])
    jar_url = f"{zonky}/{version}/embedded-postgres-binaries-{target['artifact']}-{version}.jar"
    jar = zipfile.ZipFile(io.BytesIO(download(jar_url, text(f"{jar_url}.sha256"))))
    extract(jar.read(target["archive"]), RUNTIME / "postgres")
    # The procedural languages link a system Python 3.6, Perl 5.26 and Tcl that no desktop has, and
    # linuxdeploy (AppImage) refuses a library whose dependency is missing. Picsou uses none of them.
    for module in (RUNTIME / "postgres").rglob("*"):
        if module.is_file() and module.name.split(".")[0].startswith(("plpython", "plperl", "pltcl", "hstore_pl", "jsonb_pl", "ltree_pl", "bool_pl")):
            module.unlink()
    pg_tools(manifest, target, native)
    if target["os"] == "windows":
        vc_runtime([RUNTIME / "postgres" / "bin", RUNTIME / "pgtools" / "bin"])

    shutil.rmtree(RUNTIME / "spa", ignore_errors=True)
    shutil.copytree(ROOT / "frontend/dist", RUNTIME / "spa")
    if not native:
        print(f"warning: no sidecars, a {target['os']} runtime built elsewhere cannot install their wheels", file=sys.stderr)
    served = sidecars(manifest, target) if native else []
    print(f"runtime ready: PostgreSQL {version}, pg_dump {PG_TOOLS}, sidecars: {', '.join(served) or 'none'}")


if __name__ == "__main__":
    main()
