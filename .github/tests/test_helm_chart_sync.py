"""Regression checks for charts/picsou/sync-sidecars.sh (Compose sidecars vs chart values)."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


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
        self.compose = self.tmp / "docker-compose.yml"
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

    def single(self, service, chart_entry="", url="http://x-auth:8001"):
        """Findings (kind -> detail) for one Compose sidecar and its chart entry."""
        indent = lambda text: textwrap.indent(textwrap.dedent(text), "    ")
        self.compose.write_text(
            "services:\n  app:\n    image: app\n    environment:\n"
            f"      X_AUTH_URL: {url}\n  x-auth:\n" + indent(service),
            encoding="utf-8",
        )
        self.values.write_text(
            "sidecars:\n  x-auth:\n    enabled: true\n    urlEnv: X_AUTH_URL\n    port: 8001\n"
            + indent(chart_entry),
            encoding="utf-8",
        )
        result = self.run_script("--check", "--json")
        return {f["kind"]: f["detail"] for f in json.loads(result.stdout)}

    def test_the_repository_files_are_compared_without_failing(self):
        # Differences are warnings (ADR 4): only 0 and 2 are allowed, and the findings stay parseable.
        result = self.run_script("--check", "--json", compose=ROOT / "docker/docker-compose.yml", values=VALUES)
        self.assertIn(result.returncode, (0, 2), result.stdout + result.stderr)
        self.assertIsInstance(json.loads(result.stdout), list)

    def test_the_repository_chart_has_no_drift_for_sidecars_it_lists(self):
        result = self.run_script("--check", "--json", compose=ROOT / "docker/docker-compose.yml", values=VALUES)
        drift = [f for f in json.loads(result.stdout) if f["kind"] == "drift"]
        self.assertEqual(drift, [])

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

    def test_json_stays_parseable_with_github_mode(self):
        result = self.run_script("--check", "--json", "--github")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(len(json.loads(result.stdout)), 4)

    def test_a_chart_sidecar_the_app_no_longer_points_at_is_unwired(self):
        self.compose.write_text(COMPOSE.replace("      KNOWN_AUTH_URL: http://known-auth:8001\n", ""), encoding="utf-8")
        _, found = self.findings("--check")
        self.assertIn(("known-auth", "unwired"), found)

    def test_an_https_app_url_is_parsed(self):
        self.assertIn("port 9000", self.single("image: x\n", url="https://x-auth:9000")["mismatch"])
        self.assertEqual(self.single("image: x\n", url="https://x-auth:8001"), {})

    def test_a_compose_volume_needs_persistence_in_the_chart_entry(self):
        service = "image: x\nvolumes: [data:/data]\n"
        self.assertIn("volumes", self.single(service)["drift"])
        self.assertEqual(self.single(service, "persistence:\n  enabled: true\n"), {})

    def test_compose_init_needs_share_process_namespace_in_the_chart_entry(self):
        service = "image: x\ninit: true\n"
        self.assertIn("shareProcessNamespace", self.single(service)["drift"])
        self.assertEqual(self.single(service, "shareProcessNamespace: true\n"), {})

    def test_a_compose_variable_needs_an_env_or_extra_env_entry_in_the_chart_entry(self):
        service = "image: x\nenvironment:\n  APP_SIDECAR_API_KEY: k\n  MODE: \"1\"\n"
        self.assertIn("MODE", self.single(service)["drift"])
        self.assertEqual(self.single(service, "env:\n  MODE: \"1\"\n"), {})
        self.assertEqual(self.single(service, "extraEnv:\n  - name: MODE\n    value: \"1\"\n"), {})

    def test_compose_networks_are_not_drift_for_a_chart_sidecar(self):
        self.assertEqual(self.single("image: x\nnetworks: [isolated]\n"), {})

    def test_github_mode_emits_warnings_but_never_for_added_sidecars(self):
        result = self.run_script("--apply", "--github")
        warnings = [line for line in result.stdout.splitlines() if line.startswith("::warning")]
        self.assertTrue(any("vol-auth" in line for line in warnings))
        self.assertFalse(any("std-auth" in line for line in warnings))


if __name__ == "__main__":
    unittest.main()
