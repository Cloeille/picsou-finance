"""The desktop release helpers and README stable download links."""

from datetime import datetime, timezone
from fnmatch import fnmatch
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))

import desktop_release  # noqa: E402

WORKFLOW_PATH = ROOT / ".github/workflows/desktop-release.yml"
README_PATH = ROOT / "README.md"
LATEST_DOWNLOAD_RE = re.compile(r"releases/latest/download/([^\s)\"'<>]+)")
SAMPLE_RELEASE_ASSETS = (
    "Picsou_1.3.0_aarch64.dmg",
    "Picsou_1.3.0_amd64.AppImage",
    "Picsou_1.3.0_amd64.AppImage.sig",
    "Picsou_1.3.0_x64-setup.exe",
    "Picsou_1.3.0_x64-setup.exe.sig",
    "Picsou.app.tar.gz",
    "Picsou.app.tar.gz.sig",
    "latest.json",
)


def _publish_run_script():
    workflow = yaml.safe_load(WORKFLOW_PATH.read_text(encoding="utf-8"))
    for step in workflow["jobs"]["publish"]["steps"]:
        run = step.get("run", "")
        if "gh release upload" in run:
            return run
    raise AssertionError("publish job has no 'gh release upload' step")


def _extract_copy_block(run_script):
    match = re.search(r"(?ms)^shopt -s nullglob\n.*?^shopt -u nullglob$", run_script)
    if not match:
        raise AssertionError("publish step has no nullglob copy block")
    return match.group(0)


def _extract_upload_globs(run_script):
    for line in run_script.splitlines():
        tokens = shlex.split(line, comments=True)
        if tokens[:3] == ["gh", "release", "upload"]:
            globs = []
            skip_next = False
            for token in tokens[4:]:
                if skip_next:
                    skip_next = False
                    continue
                if token in ("-R", "--repo"):
                    skip_next = True
                    continue
                if token.startswith("-"):
                    continue
                globs.append(token)
            return globs
    raise AssertionError("publish step has no 'gh release upload' command")


def _run_copy_block(copy_block, asset_names):
    with tempfile.TemporaryDirectory() as tmp:
        dist = Path(tmp)
        for name in asset_names:
            (dist / name).write_bytes(b"")
        result = subprocess.run(
            ["bash", "-e", "-c", copy_block],
            cwd=dist,
            capture_output=True,
            text=True,
        )
        created = {path.name for path in dist.iterdir()} - set(asset_names)
        all_files = {path.name for path in dist.iterdir()}
        return result, created, all_files


def _readme_download_names(readme_text):
    return set(LATEST_DOWNLOAD_RE.findall(readme_text))


class DesktopReleaseTest(unittest.TestCase):
    def test_only_a_semver_tag_is_a_release(self):
        self.assertEqual(desktop_release.version("1.3.0"), "1.3.0")
        self.assertEqual(desktop_release.version("v1.3.0"), "1.3.0")
        self.assertEqual(desktop_release.version("1.3.0-rc.1"), "1.3.0-rc.1")
        for tag in ("1.3", "latest", "v01.3.0", "1.3.0 ", "1.3.0\n"):
            with self.subTest(tag=tag), self.assertRaises(SystemExit):
                desktop_release.version(tag)

    def test_the_stamp_moves_the_shell_version_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as tmp:
            shell = Path(tmp)
            (shell / "Cargo.toml").write_text(
                '[package]\nname = "picsou-desktop"\nversion = "1.1.0"\n\n[dependencies]\nsemver = "1"\n', encoding="utf-8"
            )
            (shell / "Cargo.lock").write_text(
                '[[package]]\nname = "semver"\nversion = "1.0.26"\n\n[[package]]\nname = "picsou-desktop"\nversion = "1.1.0"\n',
                encoding="utf-8",
            )
            self.assertEqual(desktop_release.stamp("v1.3.0", shell), "1.3.0")
            self.assertIn('version = "1.3.0"', (shell / "Cargo.toml").read_text(encoding="utf-8"))
            self.assertIn('semver = "1"', (shell / "Cargo.toml").read_text(encoding="utf-8"))
            lock = (shell / "Cargo.lock").read_text(encoding="utf-8")
            self.assertIn('name = "picsou-desktop"\nversion = "1.3.0"', lock)
            self.assertIn('name = "semver"\nversion = "1.0.26"', lock)

    def test_the_real_shell_has_one_version_to_stamp(self):
        # tauri.conf.json carries none, so the bundle, the updater and the backups read Cargo.toml.
        self.assertNotIn('"version"', (ROOT / "desktop/src-tauri/tauri.conf.json").read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as tmp:
            shell = Path(tmp)
            for name in ("Cargo.toml", "Cargo.lock"):
                (shell / name).write_text((ROOT / "desktop/src-tauri" / name).read_text(encoding="utf-8"), encoding="utf-8")
            self.assertEqual(desktop_release.stamp("9.9.9", shell), "9.9.9")

    def test_a_release_build_checks_the_latest_release(self):
        overlay = desktop_release.config(" PUBKEY\n", "o/r")
        self.assertIs(overlay["bundle"]["createUpdaterArtifacts"], True)
        self.assertEqual(overlay["plugins"]["updater"], {
            "pubkey": "PUBKEY",
            "endpoints": ["https://github.com/o/r/releases/latest/download/latest.json"],
            "windows": {"installMode": "passive"},
        })
        with self.assertRaises(SystemExit):
            desktop_release.config("  ", "o/r")

    def test_latest_json_points_at_each_installer_of_this_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp) / "Picsou_1.3.0_x64-setup.exe"
            appimage = Path(tmp) / "Picsou_1.3.0_amd64.AppImage"
            app = Path(tmp) / "Picsou.app.tar.gz"
            for installer, sig in ((exe, "V0lO"), (appimage, "TElO"), (app, "TUFD")):
                installer.write_bytes(b"x")
                Path(f"{installer}.sig").write_text(sig + "\n", encoding="utf-8")
            now = datetime(2026, 11, 15, 20, 0, tzinfo=timezone.utc)
            installers = {"windows-x86_64": exe, "linux-x86_64": appimage, "darwin-aarch64": app}
            self.assertEqual(desktop_release.manifest("v1.3.0", "o/r", installers, now), {
                "version": "1.3.0",
                "notes": "https://github.com/o/r/releases/tag/v1.3.0",
                "pub_date": "2026-11-15T20:00:00Z",
                "platforms": {
                    "darwin-aarch64": {
                        "signature": "TUFD",
                        "url": "https://github.com/o/r/releases/download/v1.3.0/Picsou.app.tar.gz",
                    },
                    "linux-x86_64": {
                        "signature": "TElO",
                        "url": "https://github.com/o/r/releases/download/v1.3.0/Picsou_1.3.0_amd64.AppImage",
                    },
                    "windows-x86_64": {
                        "signature": "V0lO",
                        "url": "https://github.com/o/r/releases/download/v1.3.0/Picsou_1.3.0_x64-setup.exe",
                    },
                },
            })
            with self.assertRaises(SystemExit):  # a release that lost one installer
                desktop_release.manifest("v1.3.0", "o/r", {"windows-x86_64": exe}, now)
            Path(f"{exe}.sig").write_text("\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                desktop_release.manifest("v1.3.0", "o/r", installers, now)


class DesktopDownloadLinksTest(unittest.TestCase):
    def test_readme_download_links_match_workflow_outputs(self):
        run_script = _publish_run_script()
        copy_block = _extract_copy_block(run_script)
        upload_globs = _extract_upload_globs(run_script)

        # Copy block must run after latest.json is generated and before gh release upload.
        self.assertLess(run_script.index("latest.json"), run_script.index(copy_block))
        self.assertLess(run_script.index(copy_block), run_script.index("gh release upload"))

        result, created, all_files = _run_copy_block(copy_block, SAMPLE_RELEASE_ASSETS)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(created)
        self.assertTrue(set(SAMPLE_RELEASE_ASSETS).issubset(all_files))
        self.assertFalse(any(name.endswith(".sig") for name in created))

        uploaded_copies = {
            name for name in created if any(fnmatch(name, pattern) for pattern in upload_globs)
        }
        self.assertEqual(uploaded_copies, created)

        readme_links = _readme_download_names(README_PATH.read_text(encoding="utf-8"))
        self.assertTrue(readme_links, "README.md must link to at least one stable installer")
        self.assertEqual(readme_links, uploaded_copies)

    def test_mismatched_readme_link_is_detected(self):
        copy_block = _extract_copy_block(_publish_run_script())
        result, created, _ = _run_copy_block(copy_block, SAMPLE_RELEASE_ASSETS)
        self.assertEqual(result.returncode, 0, result.stderr)

        stale = _readme_download_names(
            "https://github.com/Cloeille/picsou-finance/releases/latest/download/Picsou-macOS-universal.dmg"
        )
        self.assertFalse(stale.issubset(created))

    def test_copy_block_fails_on_missing_or_duplicate_installer(self):
        copy_block = _extract_copy_block(_publish_run_script())

        missing = tuple(name for name in SAMPLE_RELEASE_ASSETS if not name.endswith(".dmg"))
        result, _, _ = _run_copy_block(copy_block, missing)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::", result.stdout)

        duplicate = SAMPLE_RELEASE_ASSETS + ("Picsou_1.3.1_aarch64.dmg",)
        result, _, _ = _run_copy_block(copy_block, duplicate)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error::", result.stdout)


if __name__ == "__main__":
    unittest.main()
