"""The desktop release (#591): version stamp, updater config and latest.json.

    stamp TAG                      sets the shell's version to the tag's, prints it
    config PUBKEY REPO             the `tauri build --config` overlay of a release build
    manifest TAG REPO INSTALLER    latest.json for the signed installer (INSTALLER.sig beside it)
"""

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SHELL = ROOT / "desktop/src-tauri"
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$")


def version(tag):
    """`v1.3.0` or `1.3.0` -> `1.3.0`; anything else is not a release the updater can compare."""
    value = tag[1:] if tag.startswith("v") else tag
    if not SEMVER.match(value):
        raise SystemExit(f"{tag!r} is not a semver release tag")
    return value


def stamp(tag, shell=SHELL):
    """Writes the version into Cargo.toml and Cargo.lock: the app, its backups (#588) and the
    updater all read it from there."""
    value = version(tag)
    toml = shell / "Cargo.toml"
    text, count = re.subn(r'(?m)^version = "[^"]+"$', f'version = "{value}"', toml.read_text(encoding="utf-8"), count=1)
    lock = shell / "Cargo.lock"
    locked, lock_count = re.subn(
        r'(name = "picsou-desktop"\nversion = )"[^"]+"', rf'\g<1>"{value}"', lock.read_text(encoding="utf-8")
    )
    if count != 1 or lock_count != 1:
        raise SystemExit("picsou-desktop's version was not found in Cargo.toml and Cargo.lock")
    toml.write_text(text, encoding="utf-8")
    lock.write_text(locked, encoding="utf-8")
    return value


def endpoint(repo):
    return f"https://github.com/{repo}/releases/latest/download/latest.json"


def config(pubkey, repo):
    if not pubkey.strip():
        raise SystemExit("the updater public key is empty")
    return {
        "bundle": {"createUpdaterArtifacts": True},
        "plugins": {
            "updater": {
                "pubkey": pubkey.strip(),
                "endpoints": [endpoint(repo)],
                # A progress window, no question: the user already chose Install in the tray.
                "windows": {"installMode": "passive"},
            }
        },
    }


def manifest(tag, repo, installer, now=None):
    installer = Path(installer)
    signature = installer.with_name(installer.name + ".sig").read_text(encoding="utf-8").strip()
    if not signature:
        raise SystemExit(f"{installer.name}.sig is empty")
    date = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "version": version(tag),
        "notes": f"https://github.com/{repo}/releases/tag/{quote(tag)}",
        "pub_date": date,
        "platforms": {
            "windows-x86_64": {
                "signature": signature,
                "url": f"https://github.com/{repo}/releases/download/{quote(tag)}/{quote(installer.name)}",
            }
        },
    }


def main(argv):
    match argv:
        case ["stamp", tag]:
            print(stamp(tag))
        case ["config", pubkey, repo]:
            print(json.dumps(config(pubkey, repo)))
        case ["manifest", tag, repo, installer]:
            print(json.dumps(manifest(tag, repo, installer), indent=2))
        case _:
            raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
