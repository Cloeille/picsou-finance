"""Assembles desktop/runtime from the Docker release, for `cargo tauri build` to bundle.

  runtime/jre/        Temurin JRE, the Java major of backend/pom.xml (Adoptium API)
  runtime/postgres/   PostgreSQL binaries, the Compose major (zonky embedded-postgres)
  runtime/app.jar     backend/target/*.jar   (build it first: make desktop does)
  runtime/spa/        frontend/dist

Versions come from `.github/scripts/sidecars.py --desktop`, so the desktop app follows the
Docker release. Every download is checked against its published SHA-256. Linux x86_64 only
for now; macOS and Windows come with #591.
"""

import hashlib
import io
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
ADOPTIUM = "https://api.adoptium.net/v3/assets/latest/{java}/hotspot?os=linux&architecture=x64&image_type=jre"
ZONKY = "https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-linux-amd64"


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


def latest_postgres(major):
    """The newest zonky release of the Compose major, e.g. 16.9.0."""
    metadata = ElementTree.fromstring(text(f"{ZONKY}/maven-metadata.xml"))
    versions = [v.text for v in metadata.iter("version")
                if v.text and re.fullmatch(r"\d+(\.\d+)*", v.text) and v.text.split(".")[0] == major]
    if not versions:
        sys.exit(f"no zonky PostgreSQL {major} release")
    return max(versions, key=lambda v: tuple(int(part) for part in v.split(".")))


def extract(archive, target, strip_top=False):
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        for member in tar.getmembers():
            if strip_top:
                parts = Path(member.name).parts[1:]
                if not parts:
                    continue
                member.name = str(Path(*parts))
            tar.extract(member, target, filter="data")


def main():
    manifest = pins()
    if not (ROOT / "frontend/dist/index.html").exists() or not list((ROOT / "backend/target").glob("*.jar")):
        sys.exit("build the backend jar and frontend/dist first (make desktop)")

    jre = json.loads(text(ADOPTIUM.format(java=manifest["java"])))[0]["binary"]["package"]
    extract(download(jre["link"], jre["checksum"]), RUNTIME / "jre", strip_top=True)

    version = latest_postgres(manifest["postgres"])
    jar_url = f"{ZONKY}/{version}/embedded-postgres-binaries-linux-amd64-{version}.jar"
    jar = zipfile.ZipFile(io.BytesIO(download(jar_url, text(f"{jar_url}.sha256"))))
    extract(jar.read("postgres-linux-x86_64.txz"), RUNTIME / "postgres")

    shutil.copy2(next((ROOT / "backend/target").glob("picsou-backend-*.jar")), RUNTIME / "app.jar")
    shutil.rmtree(RUNTIME / "spa", ignore_errors=True)
    shutil.copytree(ROOT / "frontend/dist", RUNTIME / "spa")
    print(f"runtime ready: JRE {jre['name']}, PostgreSQL {version}")


if __name__ == "__main__":
    main()
