"""Regression checks for charts/picsou/sync-sidecars.sh (Compose sidecars vs chart values)."""

import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "charts/picsou/sync-sidecars.sh"
VALUES = ROOT / "charts/picsou/values.yaml"

COMPOSE = textwrap.dedent(
    """\
    services:
      app:
        image: app
        environment:
          STD_AUTH_URL: ${STD_AUTH_URL:-http://std-auth:8001}
          KNOWN_AUTH_URL: https://known-auth:8001
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


# Frozen inventory: live Compose additions must not fail customization assertions.
CUSTOM_COMPOSE = textwrap.dedent(
    """\
    services:
      app:
        environment:
          REVOLUT_AUTH_URL: http://revolut-auth:8002
          FORTUNEO_AUTH_URL: http://fortuneo-auth:8001
          AMEX_AUTH_URL: http://amex-auth:8001
      revolut-auth:
        image: synthetic
        volumes: [profiles:/data/revolut-profiles]
        environment:
          APP_SIDECAR_API_KEY: synthetic
      fortuneo-auth:
        image: synthetic
        networks: [private]
        environment:
          APP_SIDECAR_API_KEY: synthetic
      amex-auth:
        image: synthetic
        init: true
        environment:
          APP_SIDECAR_API_KEY: synthetic
    """
)

CUSTOM_VALUES = textwrap.dedent(
    """\
    sidecars:
      revolut-auth:
        urlEnv: REVOLUT_AUTH_URL
        port: 8002
        persistence:
          enabled: true
      fortuneo-auth:
        urlEnv: FORTUNEO_AUTH_URL
        port: 8001
      amex-auth:
        urlEnv: AMEX_AUTH_URL
        port: 8001
        shareProcessNamespace: true
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
        result = self.run_script("--check", "--json",
                                 compose=ROOT / "docker/docker-compose.yml", values=VALUES)
        # Exit 2 is drift, reported by the workflow's warning step; errors still fail CI.
        self.assertIn(result.returncode, (0, 2), result.stdout + result.stderr)
        self.assertIsInstance(json.loads(result.stdout), list)

    def test_repository_inventory_allows_sidecar_drift(self):
        with patch(__name__ + ".ROOT", self.tmp), patch(__name__ + ".VALUES", self.values):
            self.test_repository_inventory_check_succeeds()

    def test_inventory_tests_allow_a_new_standard_compose_sidecar(self):
        compose = (ROOT / "docker/docker-compose.yml").read_text()
        compose = compose.replace(
            "    environment:\n",
            "    environment:\n      SYNTHETIC_AUTH_URL: http://synthetic-auth:8001\n", 1,
        )
        self.compose.write_text(compose.replace(
            "services:\n", "services:\n  synthetic-auth:\n    image: synthetic\n", 1,
        ))
        self.values.write_text(VALUES.read_text())
        result = self.run_script("--check", "--github")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("::warning title=Helm chart: synthetic-auth::", result.stdout)
        with patch(__name__ + ".ROOT", self.tmp), patch(__name__ + ".VALUES", self.values):
            self.test_repository_inventory_check_succeeds()
            self.test_supported_repository_customizations_do_not_warn()

    def test_repository_inventory_rejects_script_errors(self):
        self.compose.unlink()
        with patch(__name__ + ".ROOT", self.tmp), patch(__name__ + ".VALUES", self.values):
            with self.assertRaises(AssertionError):
                self.test_repository_inventory_check_succeeds()

    def test_supported_repository_customizations_do_not_warn(self):
        self.compose.write_text(CUSTOM_COMPOSE)
        self.values.write_text(CUSTOM_VALUES)
        result = self.run_script("--check", "--github")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("::warning", result.stdout)

    def test_https_sidecar_url_is_wired(self):
        result = self.run_script("--check", "--json")
        findings = json.loads(result.stdout)
        self.assertFalse(any(f["sidecar"] == "known-auth" for f in findings), findings)

    def test_supported_settings_must_have_their_chart_equivalents(self):
        self.compose.write_text(CUSTOM_COMPOSE)
        for removed, sidecar in (("    persistence:\n      enabled: true\n", "revolut-auth"),
                                 ("    shareProcessNamespace: true\n", "amex-auth")):
            with self.subTest(sidecar=sidecar):
                self.values.write_text(CUSTOM_VALUES.replace(removed, ""))
                result = self.run_script("--check", "--json")
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn((sidecar, "drift"),
                              {(f["sidecar"], f["kind"]) for f in json.loads(result.stdout)})
                result = self.run_script("--apply", "--json")
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)

    def test_unused_supported_settings_do_not_require_chart_equivalents(self):
        self.compose.write_text(CUSTOM_COMPOSE.replace(
            "    volumes: [profiles:/data/revolut-profiles]\n", "",
        ).replace("    init: true\n", "    init: false\n"))
        self.values.write_text(CUSTOM_VALUES.replace(
            "    persistence:\n      enabled: true\n", "",
        ).replace("    shareProcessNamespace: true\n", ""))
        result = self.run_script("--check", "--json")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout), [])

    def test_compose_variable_must_exist_in_chart_env_or_extra_env(self):
        compose = CUSTOM_COMPOSE.replace(
            "  amex-auth:\n    image: synthetic\n    init: true\n    environment:\n",
            "  amex-auth:\n    image: synthetic\n    init: true\n    environment:\n"
            "      BROWSER_MODE: synthetic\n",
        )
        self.compose.write_text(compose)
        self.values.write_text(CUSTOM_VALUES)
        result = self.run_script("--check", "--json")
        findings = json.loads(result.stdout)
        self.assertTrue(any(f["sidecar"] == "amex-auth" and f["kind"] == "drift"
                            and "BROWSER_MODE" in f["detail"] for f in findings), findings)
        self.assertEqual(self.run_script("--apply", "--json").returncode, 1)

        for setting in ("    env:\n      BROWSER_MODE: synthetic\n",
                        "    extraEnv:\n      - name: BROWSER_MODE\n        value: synthetic\n"):
            with self.subTest(setting=setting.splitlines()[0].strip()):
                self.values.write_text(CUSTOM_VALUES.replace(
                    "    shareProcessNamespace: true\n",
                    "    shareProcessNamespace: true\n" + setting,
                ))
                result = self.run_script("--check", "--json")
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_acknowledgements_apply_only_to_known_keys_on_known_sidecars(self):
        compose = CUSTOM_COMPOSE
        self.values.write_text(CUSTOM_VALUES)
        for name, setting in (("revolut-auth", "    init: true\n"),
                              ("fortuneo-auth", "    volumes: [cache:/cache]\n"),
                              ("amex-auth", "    environment:\n      EXTRA_MODE: synthetic\n")):
            with self.subTest(name=name):
                if "environment" in setting:
                    start = compose.index(f"  {name}:\n")
                    env = compose.index("    environment:\n", start) + len("    environment:\n")
                    changed = compose[:env] + "      EXTRA_MODE: synthetic\n" + compose[env:]
                else:
                    changed = compose.replace(f"  {name}:\n", f"  {name}:\n" + setting)
                self.compose.write_text(changed)
                result = self.run_script("--check", "--github")
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn(f"::warning title=Helm chart: {name}::", result.stdout)

    def test_check_reports_each_kind_of_difference(self):
        code, found = self.findings("--check")
        self.assertEqual(code, 2)
        self.assertEqual(found, {
            ("std-auth", "standard"),
            ("vol-auth", "special"),
            ("lost-auth", "unwired"),
            ("old-auth", "stale"),
        })

    def test_apply_adds_standard_disables_stale_and_keeps_other_values(self):
        code, found = self.findings("--apply")
        self.assertEqual(code, 0)
        self.assertIn(("std-auth", "added"), found)

        text = self.values.read_text(encoding="utf-8")
        self.assertIn("  std-auth:\n    enabled: true\n    urlEnv: STD_AUTH_URL\n    port: 8001\n", text)
        self.assertNotIn("vol-auth", text)
        self.assertNotIn("lost-auth", text)
        # Only the new entry and the stale entry's enabled flag change.
        self.assertEqual(text.replace("  std-auth:\n    enabled: true\n    urlEnv: STD_AUTH_URL\n    port: 8001\n", ""),
                         CHART_VALUES.replace("  old-auth:\n    enabled: true",
                                              "  old-auth:\n    enabled: false"))

    def test_apply_twice_changes_nothing_the_second_time(self):
        self.run_script("--apply")
        once = self.values.read_text(encoding="utf-8")
        self.run_script("--apply")
        self.assertEqual(self.values.read_text(encoding="utf-8"), once)

    def test_a_port_change_is_reported_as_a_mismatch(self):
        self.compose.write_text(COMPOSE.replace("https://known-auth:8001", "https://known-auth:9000"), encoding="utf-8")
        _, found = self.findings("--check")
        self.assertIn(("known-auth", "mismatch"), found)

    def test_apply_disables_stale_sidecar_without_an_enabled_field(self):
        self.values.write_text(CHART_VALUES.replace(
            "  old-auth:\n    enabled: true", "  old-auth:"), encoding="utf-8")
        result = self.run_script("--apply")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("  old-auth:\n    enabled: false", self.values.read_text())
        # The following top-level section must remain enabled.
        self.assertIn("postgresql:\n  enabled: true", self.values.read_text())

    def test_apply_rejects_port_and_url_variable_mismatches(self):
        for old, new in (("known-auth:8001", "known-auth:9000"),
                         ("KNOWN_AUTH_URL:", "RENAMED_AUTH_URL:")):
            with self.subTest(change=new):
                self.compose.write_text(COMPOSE.replace(old, new), encoding="utf-8")
                result = self.run_script("--apply", "--json")
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertTrue(any(f["kind"] == "mismatch" for f in json.loads(result.stdout)))

    def test_apply_rejects_an_unwired_known_sidecar(self):
        self.compose.write_text(COMPOSE.replace(
            "KNOWN_AUTH_URL: https://known-auth:8001",
            "TRADE_REPUBLIC_AUTH_URL: http://renamed-auth:8001",
        ), encoding="utf-8")
        result = self.run_script("--apply", "--json")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        findings = json.loads(result.stdout)
        self.assertIn({
            "sidecar": "known-auth",
            "kind": "unwired",
            "detail": "no *_AUTH_URL of the app service points at it",
        }, findings)

    def test_apply_rejects_mismatches_on_custom_sidecars(self):
        self.compose.write_text(COMPOSE.replace(
            "  known-auth:\n    image: known", "  known-auth:\n    image: known\n    init: true"
        ).replace("known-auth:8001", "known-auth:9000"), encoding="utf-8")
        result = self.run_script("--apply")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("mismatch", result.stdout)

    def test_known_sidecars_report_nonstandard_compose_settings(self):
        for setting in ('    volumes: [cache:/cache]\n',
                        '    networks: [private]\n', '    init: true\n',
                        '    environment:\n      EXTRA_MODE: synthetic\n'):
            with self.subTest(setting=setting):
                self.values.write_text(CHART_VALUES, encoding="utf-8")
                self.compose.write_text(COMPOSE.replace(
                    "  known-auth:\n    image: known\n    environment:\n"
                    "      APP_SIDECAR_API_KEY: ${APP_SIDECAR_API_KEY:?Set APP_SIDECAR_API_KEY}\n",
                    "  known-auth:\n    image: known\n" + setting,
                ), encoding="utf-8")
                result = self.run_script("--check", "--github")
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertIn("::warning title=Helm chart: known-auth::", result.stdout)
                self.assertIn("EXTRA_MODE" if "environment" in setting else setting.split(":")[0].strip(),
                              result.stdout)
                self.assertEqual(self.values.read_text(), CHART_VALUES)
                result = self.run_script("--apply", "--json")
                expected_kind = "drift" if "environment" in setting else "special"
                expected_code = 1 if expected_kind == "drift" else 0
                self.assertEqual(result.returncode, expected_code, result.stderr)
                self.assertTrue(any(f["sidecar"] == "known-auth" and f["kind"] == expected_kind
                                    for f in json.loads(result.stdout)))

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

    def test_json_mode_suppresses_github_annotations(self):
        result = self.run_script("--check", "--json", "--github")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIsInstance(json.loads(result.stdout), list)
        self.assertNotIn("::warning", result.stdout)


if __name__ == "__main__":
    unittest.main()
