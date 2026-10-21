"""Install the noVNC web client (static files) for the captcha hand-off view (viewer.py).

Debian's `novnc` package drags in nodejs (~55 MB) just to ship a folder of static files, so the
pinned upstream release is fetched instead and checked against its SHA-256: a changed tarball
fails the image build rather than silently shipping different code to the browser.
"""
import hashlib
import io
import os
import shutil
import sys
import tarfile
import urllib.request

VERSION = "1.6.0"
SHA256 = "5066103959ef4e9b10f37e5a148627360dd8414e4cf8a7db92bdbd022e728aaa"
DEST = "/usr/share/novnc"

data = urllib.request.urlopen(
    f"https://github.com/novnc/noVNC/archive/refs/tags/v{VERSION}.tar.gz", timeout=60).read()
digest = hashlib.sha256(data).hexdigest()
if digest != SHA256:
    sys.exit(f"noVNC {VERSION} checksum mismatch: {digest}")

os.makedirs(DEST, exist_ok=True)
with tarfile.open(fileobj=io.BytesIO(data)) as archive:
    archive.extractall("/tmp/novnc-src", filter="data")
root = f"/tmp/novnc-src/noVNC-{VERSION}"
for name in ("app", "core", "vendor"):
    shutil.copytree(f"{root}/{name}", f"{DEST}/{name}", dirs_exist_ok=True)
shutil.copy(f"{root}/vnc.html", f"{DEST}/vnc.html")
shutil.rmtree("/tmp/novnc-src")
