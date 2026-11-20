"""The desktop release (#591): version stamp, updater config and latest.json.

    stamp TAG                      sets the shell's version to the tag's, prints it
    config PUBKEY REPO             the `tauri build --config` overlay of a release build
    manifest TAG REPO PLATFORM=INSTALLER...   latest.json (each INSTALLER.sig beside it)
"""

from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]
SHELL = ROOT / "desktop/src-tauri"
# The updater's `<os>-<arch>` keys: the release must serve every installer it builds.
PLATFORMS = ("linux-x86_64", "windows-x86_64")
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$")


def version(tag):
    """`v1.3.0` or `1.3.0` -> `1.3.0`; anything else is not a release the updater can compare."""
    value = tag[1:] if tag.startswith("v") else tag
    if not SEMVER.fullmatch(value):
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


def manifest(tag, repo, installers, now=None):
    """installers: {updater platform: installer path}, each with its `.sig` beside it."""
    platforms = {}
    for platform, installer in sorted(installers.items()):
        installer = Path(installer)
        signature = installer.with_name(installer.name + ".sig").read_text(encoding="utf-8").strip()
        if not signature:
            raise SystemExit(f"{installer.name}.sig is empty")
        platforms[platform] = {
            "signature": signature,
            "url": f"https://github.com/{repo}/releases/download/{quote(tag)}/{quote(installer.name)}",
        }
    if set(platforms) != set(PLATFORMS):
        raise SystemExit(f"latest.json needs {sorted(PLATFORMS)}, got {sorted(platforms)}")
    date = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "version": version(tag),
        "notes": f"https://github.com/{repo}/releases/tag/{quote(tag)}",
        "pub_date": date,
        "platforms": platforms,
    }


def main(argv):
    match argv:
        case ["stamp", tag]:
            print(stamp(tag))
        case ["config", pubkey, repo]:
            print(json.dumps(config(pubkey, repo)))
        case ["manifest", tag, repo, *installers]:
            pairs = dict(item.split("=", 1) for item in installers if "=" in item)
            print(json.dumps(manifest(tag, repo, pairs), indent=2))
        case _:
            raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
