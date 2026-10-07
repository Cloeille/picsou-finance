"""Tests for architecture-specific Camoufox release pinning."""

import platform
import unittest
from unittest.mock import mock_open, patch

import pin_camoufox


class PinCamoufoxTest(unittest.TestCase):
    def test_download_mmdb_uses_direct_release_url_and_camoufox_download_helpers(self):
        with (
            patch.object(pin_camoufox, "geoip_allowed") as geoip_allowed,
            patch.object(pin_camoufox, "webdl") as webdl,
            patch("builtins.open", mock_open()) as open_file,
        ):
            pin_camoufox.download_mmdb()

        geoip_allowed.assert_called_once_with()
        open_file.assert_called_once_with(pin_camoufox.MMDB_FILE, "wb")
        webdl.assert_called_once_with(
            "https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/GeoLite2-City.mmdb",
            desc="Downloading GeoIP database",
            buffer=open_file(),
        )

    def test_install_uses_direct_mmdb_helper_without_release_api_discovery(self):
        with (
            patch.object(pin_camoufox.Pinned, "install") as browser_install,
            patch.object(pin_camoufox, "geoip_allowed") as geoip_allowed,
            patch.object(pin_camoufox, "webdl") as webdl,
            patch("builtins.open", mock_open()) as open_file,
            patch.object(pin_camoufox, "maybe_download_addons") as addons_download,
            patch("camoufox.locale.MaxMindDownloader.get_asset") as get_asset,
        ):
            pin_camoufox.install()

        browser_install.assert_called_once_with()
        geoip_allowed.assert_called_once_with()
        open_file.assert_called_once_with(pin_camoufox.MMDB_FILE, "wb")
        webdl.assert_called_once_with(
            "https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/GeoLite2-City.mmdb",
            desc="Downloading GeoIP database",
            buffer=open_file(),
        )
        addons_download.assert_called_once_with(list(pin_camoufox.DefaultAddons))
        get_asset.assert_not_called()

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
