"""Keep chart publication behind the same render and safety checks as PR validation."""

import json
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[3]


def workflow(name):
    result = subprocess.run(
        ["yq", "-o=json", ".", str(ROOT / f".github/workflows/{name}.yml")],
        check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)


class PublishValidationTest(unittest.TestCase):
    def test_publish_checks_match_ci_and_run_after_sync_before_packaging(self):
        ci_steps = workflow("ci")["jobs"]["helm-chart"]["steps"]
        steps = workflow("docker")["jobs"]["chart"]["steps"]
        sync = next(i for i, s in enumerate(steps) if "sync-sidecars.sh --apply" in s.get("run", ""))
        package = next(i for i, s in enumerate(steps) if "helm package" in s.get("run", ""))
        push = next(i for i, s in enumerate(steps) if "helm push" in s.get("run", ""))
        self.assertLess(package, push)
        for name in ("Lint the chart", "Render the default and the optional resources",
                     "Test chart storage and upgrade safety"):
            with self.subTest(check=name):
                expected = next(s for s in ci_steps if s.get("name") == name)
                index, actual = next((i, s) for i, s in enumerate(steps) if s.get("name") == name)
                self.assertEqual(actual["run"], expected["run"])
                self.assertEqual(actual["if"], "steps.version.outputs.publish == 'true'")
                self.assertFalse(actual.get("continue-on-error", False))
                self.assertLess(sync, index)
                self.assertLess(index, package)
