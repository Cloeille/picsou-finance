"""Rendered-chart regressions for persistent keys and hostname-less exposure."""

import json
from pathlib import Path
import subprocess
import unittest


CHART = Path(__file__).resolve().parents[1]


class UpgradeSafetyTest(unittest.TestCase):
    def render(self, *args):
        return subprocess.run(
            ["helm", "template", "picsou", str(CHART), *args],
            capture_output=True, text=True,
        )

    def app_pod(self, *args):
        result = self.render(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        pod = subprocess.run(
            ["yq", "-o=json", 'select(.kind == "Deployment" and '
             '.metadata.name == "picsou") | .spec.template.spec', "-"],
            input=result.stdout, check=True, capture_output=True, text=True,
        )
        return json.loads(pod.stdout)

    def test_ephemeral_data_requires_both_inline_keys(self):
        for key in (None, "jwtSecret", "cryptoEncryptionKey"):
            with self.subTest(key=key):
                args = ["--set", "app.persistence.enabled=false"]
                if key:
                    args += ["--set", f"secrets.{key}=synthetic-key"]
                result = self.render(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("app.persistence.enabled=false requires both", result.stderr)

    def test_ephemeral_data_accepts_stable_inline_keys(self):
        pod = self.app_pod(
            "--set", "app.persistence.enabled=false",
            "--set", "secrets.jwtSecret=synthetic-jwt",
            "--set", "secrets.cryptoEncryptionKey=synthetic-crypto",
        )
        self.assertEqual(pod["volumes"][0], {"name": "data", "emptyDir": {}})
        self.assert_required_keys(pod, "picsou")

    def assert_required_keys(self, pod, secret):
        env = {entry["name"]: entry for entry in pod["containers"][0]["env"]}
        for var, key in (("JWT_SECRET", "jwt-secret"),
                         ("CRYPTO_ENCRYPTION_KEY", "crypto-encryption-key")):
            self.assertEqual(env[var]["valueFrom"]["secretKeyRef"], {
                "name": secret, "key": key, "optional": False,
            })

    def test_ephemeral_existing_secret_requires_keys_at_pod_startup(self):
        pod = self.app_pod("--set", "app.persistence.enabled=false",
                           "--set", "secrets.existingSecret=stable-keys")
        self.assert_required_keys(pod, "stable-keys")

    def test_persistent_data_still_allows_entrypoint_generated_keys(self):
        pod = self.app_pod()
        env = {entry["name"]: entry for entry in pod["containers"][0]["env"]}
        for var in ("JWT_SECRET", "CRYPTO_ENCRYPTION_KEY"):
            self.assertTrue(env[var]["valueFrom"]["secretKeyRef"]["optional"])
        self.assertIn("persistentVolumeClaim", pod["volumes"][0])

    def test_exposure_with_empty_or_null_hostname_lists_renders(self):
        for exposure, field in (("httpRoute", "hostnames"), ("ingress", "hosts")):
            for empty in ("null", "[]"):
                with self.subTest(exposure=exposure, empty=empty):
                    args = ["--set", f"{exposure}.enabled=true",
                            "--set-json", f"{exposure}.{field}={empty}"]
                    if exposure == "httpRoute":
                        args += ["--set", "httpRoute.parentRefs[0].name=gateway"]
                    result = self.render(*args)
                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_exposure_with_hostnames_still_renders(self):
        for args in (
            ["--set", "httpRoute.enabled=true", "--set", "httpRoute.parentRefs[0].name=gateway",
             "--set", "httpRoute.hostnames[0]=picsou.example.com"],
            ["--set", "ingress.enabled=true"],
        ):
            with self.subTest(args=args):
                result = self.render(*args)
                self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
