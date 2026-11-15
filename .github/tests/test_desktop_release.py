"""The desktop release helpers: version stamp, updater config, latest.json."""

from datetime import datetime, timezone
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / ".github/scripts"))

import desktop_release  # noqa: E402


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

    def test_latest_json_points_at_the_installer_of_this_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            installer = Path(tmp) / "Picsou_1.3.0_x64-setup.exe"
            installer.write_bytes(b"MZ")
            Path(f"{installer}.sig").write_text("U0lHTkFUVVJF\n", encoding="utf-8")
            now = datetime(2026, 11, 15, 20, 0, tzinfo=timezone.utc)
            self.assertEqual(desktop_release.manifest("v1.3.0", "o/r", installer, now), {
                "version": "1.3.0",
                "notes": "https://github.com/o/r/releases/tag/v1.3.0",
                "pub_date": "2026-11-15T20:00:00Z",
                "platforms": {"windows-x86_64": {
                    "signature": "U0lHTkFUVVJF",
                    "url": "https://github.com/o/r/releases/download/v1.3.0/Picsou_1.3.0_x64-setup.exe",
                }},
            })
            Path(f"{installer}.sig").write_text("\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                desktop_release.manifest("v1.3.0", "o/r", installer, now)


if __name__ == "__main__":
    unittest.main()
