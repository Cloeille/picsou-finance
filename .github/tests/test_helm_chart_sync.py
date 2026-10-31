"""Regression checks for charts/picsou/sync-sidecars.sh (Compose sidecars vs chart values)."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "charts/picsou/sync-sidecars.sh"
VALUES = ROOT / "charts/picsou/values.yaml"

COMPOSE = textwrap.dedent(
    """\
    services:
      app:
        image: app
        environment:
          STD_AUTH_URL: ${STD_AUTH_URL:-http://std-auth:8001}
          KNOWN_AUTH_URL: http://known-auth:8001
          VOL_AUTH_URL: http://vol-auth:8001
      known-auth:
        image: known
        environment:
          APP_SIDECAR_API_KEY: ${APP_SIDECAR_API_KEY:?Set APP_SIDECAR_API_KEY}
      std-auth:
        image: std
        restart: unless-stopped
        environment:
          APP_SIDECAR_API_KEY: ${APP_SIDECAR_API_KEY:?Set APP_SIDECAR_API_KEY}
      vol-auth:
        image: vol
        volumes: [vol:/data]
        environment:
          APP_SIDECAR_API_KEY: x
          VOL_MODE: "1"
      lost-auth:
        image: lost
    """
)

CHART_VALUES = textwrap.dedent(
    """\
    # Leading comment kept as is.
    sidecars:
      known-auth:
        enabled: true
        urlEnv: KNOWN_AUTH_URL
        port: 8001

      old-auth:
        enabled: true
        urlEnv: OLD_AUTH_URL
        port: 8001

    # Trailing comment kept as is.
    postgresql:
      enabled: true
    """
)


@unittest.skipUnless(shutil.which("yq"), "yq v4 is required")
class SyncSidecarsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.compose = self.tmp / "docker" / "docker-compose.yml"
        self.compose.parent.mkdir()
        self.values = self.tmp / "values.yaml"
        self.compose.write_text(COMPOSE, encoding="utf-8")
        self.values.write_text(CHART_VALUES, encoding="utf-8")

    def run_script(self, *args, compose=None, values=None):
        return subprocess.run(
            ["sh", str(SCRIPT), *args,
             "--compose", str(compose or self.compose), "--values", str(values or self.values)],
            capture_output=True, text=True,
        )

    def findings(self, *args):
        result = self.run_script(*args, "--json")
        return result.returncode, {(f["sidecar"], f["kind"]) for f in json.loads(result.stdout)}

    def test_repository_inventory_check_succeeds(self):
        result = self.run_script("--check", compose=ROOT / "docker/docker-compose.yml", values=VALUES)
        # Exit 2 is drift, reported by the workflow's warning step; errors still fail CI.
        self.assertIn(result.returncode, (0, 2), result.stdout + result.stderr)

    def test_repository_inventory_allows_sidecar_drift(self):
        with patch(__name__ + ".ROOT", self.tmp), patch(__name__ + ".VALUES", self.values):
            self.test_repository_inventory_check_succeeds()

    def test_repository_inventory_rejects_script_errors(self):
        self.compose.unlink()
        with patch(__name__ + ".ROOT", self.tmp), patch(__name__ + ".VALUES", self.values):
            with self.assertRaises(AssertionError):
                self.test_repository_inventory_check_succeeds()

    def test_check_reports_each_kind_of_difference(self):
        code, found = self.findings("--check")
        self.assertEqual(code, 2)
        self.assertEqual(found, {
            ("std-auth", "standard"),
            ("vol-auth", "special"),
            ("lost-auth", "unwired"),
            ("old-auth", "stale"),
        })

    def test_apply_adds_only_standard_sidecars_and_keeps_the_rest_of_the_file(self):
        code, found = self.findings("--apply")
        self.assertEqual(code, 0)
        self.assertIn(("std-auth", "added"), found)

        text = self.values.read_text(encoding="utf-8")
        self.assertIn("  std-auth:\n    enabled: true\n    urlEnv: STD_AUTH_URL\n    port: 8001\n", text)
        self.assertNotIn("vol-auth", text)
        self.assertNotIn("lost-auth", text)
        # Everything that was there is still there, byte for byte.
        self.assertEqual(text.replace("  std-auth:\n    enabled: true\n    urlEnv: STD_AUTH_URL\n    port: 8001\n", ""),
                         CHART_VALUES)

    def test_apply_twice_changes_nothing_the_second_time(self):
        self.run_script("--apply")
        once = self.values.read_text(encoding="utf-8")
        self.run_script("--apply")
        self.assertEqual(self.values.read_text(encoding="utf-8"), once)

    def test_a_port_change_is_reported_as_a_mismatch(self):
        self.compose.write_text(COMPOSE.replace("http://known-auth:8001", "http://known-auth:9000"), encoding="utf-8")
        _, found = self.findings("--check")
        self.assertIn(("known-auth", "mismatch"), found)

    def test_check_github_warns_about_missing_standard_sidecars_without_editing_values(self):
        result = self.run_script("--check", "--github")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("::warning title=Helm chart: std-auth::", result.stdout)
        self.assertEqual(self.values.read_text(encoding="utf-8"), CHART_VALUES)

    def test_github_mode_emits_warnings_but_never_for_added_sidecars(self):
        result = self.run_script("--apply", "--github")
        warnings = [line for line in result.stdout.splitlines() if line.startswith("::warning")]
        self.assertTrue(any("vol-auth" in line for line in warnings))
        self.assertFalse(any("std-auth" in line for line in warnings))


if __name__ == "__main__":
    unittest.main()
