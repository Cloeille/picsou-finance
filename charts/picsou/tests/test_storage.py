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


if __name__ == "__main__":
    unittest.main()
