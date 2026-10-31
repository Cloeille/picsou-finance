"""Tests for the image-build Camoufox installer (pin_camoufox.py).

The GeoIP database must come from the release's direct asset URL: the GitHub
releases API that camoufox's own download_mmdb() queries is rate-limited per IP
and fails the image build on shared CI runners.

Run: .venv/bin/python tests/test_pin_camoufox.py   (no pytest needed)
"""

import os
import sys
import unittest
from unittest.mock import mock_open, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pin_camoufox  # noqa: E402

MMDB_URL = "https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/GeoLite2-City.mmdb"


class PinCamoufoxTest(unittest.TestCase):
    def test_install_downloads_the_mmdb_without_release_api_discovery(self):
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
            MMDB_URL,
            desc="Downloading GeoIP database",
            buffer=open_file(),
        )
        addons_download.assert_called_once_with(list(pin_camoufox.DefaultAddons))
        get_asset.assert_not_called()

    def test_fetch_latest_pins_the_browser_release(self):
        fetcher = pin_camoufox.Pinned()
        fetcher.fetch_latest()

        self.assertEqual(
            fetcher._url,
            "https://github.com/daijro/camoufox/releases/download/v152.0.4-beta.30/camoufox-152.0.4-beta.30-lin.x86_64.zip",
        )
        self.assertEqual(fetcher._version_obj.release, "beta.30")
        self.assertEqual(fetcher._version_obj.version, "152.0.4")


if __name__ == "__main__":
    unittest.main()
