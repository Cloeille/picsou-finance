"""Contract and render tests for Helm chart `nativeSidecars` wiring."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import unittest


CHART = Path(__file__).resolve().parents[1]
VALUES_FILE = CHART / "values.yaml"
HELPERS_FILE = CHART / "templates/_helpers.tpl"
SIDECARS_FILE = CHART / "templates/sidecars.yaml"
NETPOL_FILE = CHART / "templates/networkpolicy.yaml"
README_FILE = CHART / "README.md"

NATIVE_SIDECARS = {
    "bourso-auth": "BOURSO_AUTH_URL",
    "cic-auth": "CIC_AUTH_URL",
    "cmes-auth": "CMES_AUTH_URL",
    "degiro-auth": "DEGIRO_AUTH_URL",
    "natixis-auth": "NATIXIS_AUTH_URL",
    "sofidy-auth": "SOFIDY_AUTH_URL",
}

BROWSER_AND_OTHER_SIDECARS = {
    "bnp-auth": "BNP_AUTH_URL",
    "amex-auth": "AMEX_AUTH_URL",
    "revolut-auth": "REVOLUT_AUTH_URL",
    "tr-auth": "TR_AUTH_URL",
    "fortuneo-auth": "FORTUNEO_AUTH_URL",
}

HAS_HELM_AND_YQ = bool(shutil.which("helm") and shutil.which("yq"))


def _extract_define(text: str, name: str) -> str:
    pattern = rf'\{{\{{-?\s*define\s+"{re.escape(name)}"\s*-?\}}\}}(.*?)\{{\{{-?\s*end\s*\}}\}}'
    match = re.search(pattern, text, re.DOTALL)
    if not match:
        raise AssertionError(f"Template define {name!r} not found")
    return match.group(1)


class NativeSidecarsContractTest(unittest.TestCase):
    """Static contract checks that run without helm/yq binaries."""

    def test_values_yaml_declares_native_sidecars_false_by_default(self):
        values = VALUES_FILE.read_text(encoding="utf-8")
        self.assertRegex(values, r"(?m)^nativeSidecars:\s*false\s*$")
        for sidecar, url_env in {**NATIVE_SIDECARS, **BROWSER_AND_OTHER_SIDECARS}.items():
            with self.subTest(sidecar=sidecar):
                self.assertRegex(values, rf"(?m)^  {re.escape(sidecar)}:\s*$")
                self.assertRegex(values, rf"(?m)^    urlEnv:\s*{re.escape(url_env)}\s*$")

    def test_helpers_wire_exact_six_pure_http_sidecars_and_app_env_override_precedence(self):
        helpers = HELPERS_FILE.read_text(encoding="utf-8")
        is_native_block = _extract_define(helpers, "picsou.sidecar.isNative")

        listed_match = re.search(r"\$native\s*:=\s*list\s+([^\n]+)", is_native_block)
        self.assertIsNotNone(listed_match, "picsou.sidecar.isNative must declare $native list")
        listed = set(re.findall(r'"([^"]+)"', listed_match.group(1)))
        self.assertEqual(listed, set(NATIVE_SIDECARS))
        self.assertTrue(listed.isdisjoint(BROWSER_AND_OTHER_SIDECARS))
        self.assertIn(".ctx.Values.nativeSidecars", is_native_block)
        self.assertIn("has .name $native", is_native_block)

        # defaultEnv emits APP_NATIVE_SIDECARS="true" when enabled and skips default URLs for native sidecars.
        default_env_idx = helpers.index('define "picsou.app.defaultEnv"')
        default_env_section = helpers[default_env_idx:]
        self.assertRegex(
            default_env_section,
            r"\{\{-?\s*if\s+\.Values\.nativeSidecars\s*-?\}\}\s*- name:\s*APP_NATIVE_SIDECARS\s+value:\s*\"true\"",
        )
        self.assertIn(
            'if not (include "picsou.sidecar.isNative" (dict "ctx" $ "name" $name))',
            default_env_section,
        )

        # picsou.app.env always appends userEnv (app.env / app.extraEnv), so explicit <BANK>_AUTH_URL
        # overrides survive even when default URLs are omitted by nativeSidecars=true.
        app_env_idx = helpers.index('define "picsou.app.env"')
        app_env_section = helpers[app_env_idx:default_env_idx]
        self.assertIn('include "picsou.app.userEnv"', app_env_section)
        self.assertIn("with $userEnv", app_env_section)

    def test_sidecars_and_networkpolicy_gate_on_is_native(self):
        expected_guard = (
            'if and $sc.enabled (not (include "picsou.sidecar.isNative" '
            '(dict "ctx" $ "name" $name)))'
        )
        for path in (SIDECARS_FILE, NETPOL_FILE):
            with self.subTest(template=path.name):
                self.assertIn(expected_guard, path.read_text(encoding="utf-8"))

    def test_readme_documents_native_sidecars(self):
        readme = README_FILE.read_text(encoding="utf-8")
        self.assertIn("nativeSidecars", readme)
        self.assertIn("APP_NATIVE_SIDECARS", readme)
        for sidecar in NATIVE_SIDECARS:
            self.assertIn(sidecar, readme)


@unittest.skipUnless(HAS_HELM_AND_YQ, "helm and yq are required for render tests")
class NativeSidecarsRenderTest(unittest.TestCase):
    """End-to-end Helm template rendering tests (executed when helm & yq are available)."""

    def resources(self, *args):
        result = subprocess.run(
            ["helm", "template", "picsou", str(CHART), *args],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        parsed = subprocess.run(
            ["yq", "-o=json", "-I=0", ".", "-"],
            input=result.stdout,
            check=True,
            capture_output=True,
            text=True,
        )
        return [json.loads(line) for line in parsed.stdout.splitlines() if line != "null"]

    def _app_env(self, resources):
        app = next(
            r for r in resources
            if r["kind"] == "Deployment" and r["metadata"]["name"] == "picsou"
        )
        return {e["name"]: e for e in app["spec"]["template"]["spec"]["containers"][0]["env"]}

    def test_default_native_sidecars_false_deploys_all_sidecars(self):
        resources = self.resources()
        env = self._app_env(resources)
        self.assertNotIn("APP_NATIVE_SIDECARS", env)

        by_kind = {(r["kind"], r["metadata"]["name"]) for r in resources}
        for sidecar, url_env in NATIVE_SIDECARS.items():
            full = f"picsou-{sidecar}"
            self.assertIn(("Deployment", full), by_kind)
            self.assertIn(("Service", full), by_kind)
            self.assertIn(("NetworkPolicy", full), by_kind)
            self.assertIn(url_env, env)

    def test_native_sidecars_true_omits_six_http_sidecars_and_keeps_overrides_and_browsers(self):
        resources = self.resources(
            "--set", "nativeSidecars=true",
            "--set", "app.env.BOURSO_AUTH_URL=https://custom-bourso.example",
        )
        env = self._app_env(resources)
        self.assertEqual(env["APP_NATIVE_SIDECARS"]["value"], "true")
        self.assertEqual(env["BOURSO_AUTH_URL"]["value"], "https://custom-bourso.example")

        by_kind = {(r["kind"], r["metadata"]["name"]) for r in resources}
        for sidecar, url_env in NATIVE_SIDECARS.items():
            full = f"picsou-{sidecar}"
            self.assertNotIn(("Deployment", full), by_kind)
            self.assertNotIn(("Service", full), by_kind)
            self.assertNotIn(("NetworkPolicy", full), by_kind)
            if sidecar != "bourso-auth":
                self.assertNotIn(url_env, env)

        for sidecar, url_env in BROWSER_AND_OTHER_SIDECARS.items():
            full = f"picsou-{sidecar}"
            self.assertIn(("Deployment", full), by_kind)
            self.assertIn(("Service", full), by_kind)
            self.assertIn(("NetworkPolicy", full), by_kind)
            self.assertIn(url_env, env)


if __name__ == "__main__":
    unittest.main()
