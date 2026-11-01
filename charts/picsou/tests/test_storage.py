"""Regression checks for non-root sidecar storage in the rendered chart."""

import json
from pathlib import Path
import subprocess
import unittest


CHART = Path(__file__).resolve().parents[1]


class RevolutStorageTest(unittest.TestCase):
    def render_pod(self, *args):
        rendered = subprocess.run(
            ["helm", "template", "picsou", str(CHART),
             "--show-only", "templates/sidecars.yaml", *args],
            check=True, capture_output=True, text=True,
        )
        pod = subprocess.run(
            ["yq", "-o=json", 'select(.kind == "Deployment" and '
             '.metadata.name == "picsou-revolut-auth") | .spec.template.spec', "-"],
            input=rendered.stdout, check=True, capture_output=True, text=True,
        )
        return json.loads(pod.stdout)

    def test_fresh_profiles_volume_has_a_writable_supplemental_group(self):
        pod = self.render_pod()
        self.assertEqual(pod["securityContext"].get("fsGroup"), 1000)
        self.assertEqual(pod["securityContext"]["seccompProfile"]["type"], "RuntimeDefault")
        self.assertEqual(pod["containers"][0]["volumeMounts"], [
            {"name": "data", "mountPath": "/data/revolut-profiles"},
        ])
        self.assertEqual(pod["volumes"][0]["persistentVolumeClaim"]["claimName"],
                         "picsou-revolut-auth-data")

    def test_existing_claim_and_group_can_be_overridden(self):
        pod = self.render_pod(
            "--set", "sidecars.revolut-auth.persistence.existingClaim=profiles",
            "--set", "sidecars.revolut-auth.podSecurityContext.fsGroup=2000",
        )
        self.assertEqual(pod["securityContext"]["fsGroup"], 2000)
        self.assertEqual(pod["securityContext"]["seccompProfile"]["type"], "RuntimeDefault")
        self.assertEqual(pod["volumes"][0]["persistentVolumeClaim"]["claimName"], "profiles")

    def test_new_sidecar_persistence_has_defaults_and_accepts_overrides(self):
        for overrides, modes, size in (([], ["ReadWriteOnce"], "1Gi"),
                (["--set", "sidecars.amex-auth.persistence.accessModes[0]=ReadWriteMany",
                  "--set", "sidecars.amex-auth.persistence.size=2Gi"], ["ReadWriteMany"], "2Gi")):
            with self.subTest(size=size):
                rendered = subprocess.run(
                    ["helm", "template", "picsou", str(CHART),
                     "--set", "sidecars.amex-auth.persistence.enabled=true",
                     "--set", "sidecars.amex-auth.persistence.mountPath=/data", *overrides],
                    check=True, capture_output=True, text=True,
                )
                claim = subprocess.run(
                    ["yq", "-o=json", 'select(.kind == "PersistentVolumeClaim" and '
                     '.metadata.name == "picsou-amex-auth-data")', "-"],
                    input=rendered.stdout, check=True, capture_output=True, text=True,
                )
                spec = json.loads(claim.stdout)["spec"]
                self.assertEqual(spec["accessModes"], modes)
                self.assertEqual(spec["resources"]["requests"]["storage"], size)


if __name__ == "__main__":
    unittest.main()
