"""Regression checks for published sidecar and image-test workflow coverage."""

from collections import Counter
from pathlib import Path
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
DOCKER_WORKFLOW = ROOT / ".github/workflows/docker.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
ROOT_COMPOSE = ROOT / "docker-compose.yml"
DOCKER_COMPOSE = ROOT / "docker/docker-compose.yml"


def _load(path):
    with path.open(encoding="utf-8") as workflow:
        return yaml.safe_load(workflow)


def _run_commands(job):
    return "\n".join(step.get("run", "") for step in job["steps"])


class SidecarWorkflowInventoryTest(unittest.TestCase):
    def test_ci_pins_the_workflow_yaml_dependency(self):
        workflow = _load(CI_WORKFLOW)
        commands = _run_commands(workflow["jobs"]["workflow-inventory"])

        self.assertIn("python -m pip install PyYAML==6.0.3", commands)

    def test_root_backend_configures_the_degiro_sidecar_url(self):
        compose = _load(ROOT_COMPOSE)

        self.assertEqual(
            compose["services"]["backend"]["environment"]["DEGIRO_AUTH_URL"],
            "http://degiro-auth:8001",
        )

    def test_amex_sidecar_uses_an_init_process_in_both_compose_files(self):
        for path in (ROOT_COMPOSE, DOCKER_COMPOSE):
            with self.subTest(compose_file=path.relative_to(ROOT)):
                compose = _load(path)
                self.assertIs(compose["services"]["amex-auth"]["init"], True)

    def test_every_service_dockerfile_is_in_the_publish_matrix_exactly_once(self):
        workflow = _load(DOCKER_WORKFLOW)
        matrix = workflow["jobs"]["build"]["strategy"]["matrix"]["include"]
        expected = {
            path.relative_to(ROOT).as_posix()
            for path in (ROOT / "services").glob("*/Dockerfile")
        }
        entries = [
            entry["dockerfile"]
            for entry in matrix
            if entry.get("dockerfile", "").startswith("services/")
        ]

        self.assertEqual(set(entries), expected)
        self.assertEqual(Counter(entries), Counter({path: 1 for path in expected}))
        for service in ("amex-auth", "degiro-auth"):
            with self.subTest(service=service):
                entry = next(item for item in matrix if item.get("name") == service)
                self.assertEqual(entry["context"], f"services/{service}")
                self.assertEqual(entry["image"], f"${{{{ github.repository }}}}/{service}")
                self.assertEqual(entry["versioned"], False)

    def test_publish_workflow_keeps_both_platforms_and_branch_tagging(self):
        job_text = DOCKER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("platforms: linux/amd64,linux/arm64", job_text)
        self.assertIn(
            "type=ref,event=branch,enable=${{ github.ref != 'refs/heads/main' }}",
            job_text,
        )

    def test_new_sidecar_jobs_build_and_run_every_test_read_only_in_the_image(self):
        workflow = _load(CI_WORKFLOW)
        for service in ("amex-auth", "degiro-auth"):
            with self.subTest(service=service):
                job = workflow["jobs"][f"{service}-sidecar"]
                commands = _run_commands(job)
                image = f"picsou-{service}:test"
                self.assertIn(f"docker build --tag {image} services/{service}", commands)
                self.assertIn("--entrypoint python", commands)
                self.assertIn(f"{image} -m unittest discover -v", commands)
                self.assertIn("--env PYTHONPATH=/app", commands)
                self.assertIn("--env APP_SIDECAR_API_KEY=test-key", commands)
                test_files = sorted((ROOT / "services" / service).glob("test*.py"))
                self.assertTrue(test_files)
                for test_file in test_files:
                    mount = f"services/{service}/{test_file.name}:/tests/{test_file.name}:ro"
                    with self.subTest(test_file=test_file.name):
                        self.assertIn(mount, commands)


if __name__ == "__main__":
    unittest.main()
