"""Tests for architecture-specific Camoufox release pinning."""

import platform
import unittest
from unittest.mock import patch

import pin_camoufox


class PinCamoufoxTest(unittest.TestCase):
    def test_fetch_latest_selects_x86_64_archive_and_pins_version(self):
        with patch.object(platform, "machine", return_value="x86_64"):
            fetcher = pin_camoufox.Pinned()
            fetcher.fetch_latest()

        self.assertEqual(
            fetcher._url,
            "https://github.com/daijro/camoufox/releases/download/v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.x86_64.zip",
        )
        self.assertEqual(fetcher._version_obj.release, "beta.30")
        self.assertEqual(fetcher._version_obj.version, "152.0.4")

    def test_fetch_latest_selects_arm64_archive(self):
        with patch.object(platform, "machine", return_value="aarch64"):
            fetcher = pin_camoufox.Pinned()
            fetcher.fetch_latest()

        self.assertEqual(
            fetcher._url,
            "https://github.com/daijro/camoufox/releases/download/v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.arm64.zip",
        )

    def test_fetch_latest_accepts_amd64_and_arm64_machine_aliases(self):
        expected_archives = {
            "amd64": "x86_64",
            "arm64": "arm64",
        }
        for machine, archive in expected_archives.items():
            with self.subTest(machine=machine):
                with patch.object(platform, "machine", return_value=machine):
                    fetcher = pin_camoufox.Pinned()
                    fetcher.fetch_latest()

                self.assertTrue(fetcher._url.endswith(f"-lin.{archive}.zip"))

    def test_fetch_latest_rejects_unknown_architecture(self):
        with patch.object(platform, "machine", return_value="riscv64"):
            with self.assertRaisesRegex(RuntimeError, "(?i)unsupported.*riscv64"):
                pin_camoufox.Pinned()


if __name__ == "__main__":
    unittest.main()
