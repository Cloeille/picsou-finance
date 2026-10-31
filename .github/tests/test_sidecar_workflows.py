"""Regression checks for published sidecar and image-test workflow coverage."""

from collections import Counter
from pathlib import Path
import re
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
DOCKER_WORKFLOW = ROOT / ".github/workflows/docker.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
ROOT_COMPOSE = ROOT / "docker-compose.yml"
DOCKER_COMPOSE = ROOT / "docker/docker-compose.yml"
# Built and tested in CI, never published: the Docker install does not run it (desktop only, #235).
UNPUBLISHED_DOCKERFILES = {"services/browser-service/Dockerfile"}
BASE_DOCKERFILE = "services/base/Dockerfile"
# Camoufox sidecars keep their own Firefox and Playwright pin.
OWN_BASE = {"amex-auth", "revolut-auth"}


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
        } - UNPUBLISHED_DOCKERFILES - {BASE_DOCKERFILE}
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

    def test_desktop_only_browser_service_stays_out_of_docker_installs(self):
        matrix = _load(DOCKER_WORKFLOW)["jobs"]["build"]["strategy"]["matrix"]["include"]
        self.assertNotIn("browser-service", {entry["name"] for entry in matrix})
        for path in (ROOT_COMPOSE, DOCKER_COMPOSE):
            with self.subTest(compose_file=path.relative_to(ROOT)):
                compose = _load(path)
                self.assertNotIn("browser-service", compose["services"])
                self.assertNotIn("BROWSER_SERVICE_URL", compose["services"]["corum-auth"].get("environment", {}))

    def test_publish_workflow_keeps_both_platforms_and_branch_tagging(self):
        job_text = DOCKER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("platforms: linux/amd64,linux/arm64", job_text)
        self.assertIn(
            "type=ref,event=branch,enable=${{ github.ref != 'refs/heads/main' }}",
            job_text,
        )

    def test_new_sidecar_jobs_build_and_run_every_test_read_only_in_the_image(self):
        workflow = _load(CI_WORKFLOW)
        for service in ("amex-auth", "degiro-auth", "tr-auth"):
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


    def test_revolut_sidecar_job_runs_its_pytest_suite_in_the_image(self):
        job = _load(CI_WORKFLOW)["jobs"]["revolut-sidecar"]
        commands = _run_commands(job)

        self.assertIn("docker build --tag picsou-revolut-auth:test services/revolut-auth", commands)
        self.assertIn("python -m pytest", commands)
        self.assertIn("services/revolut-auth:/src:ro", commands)
        self.assertIn("-r requirements-test.txt", commands)
        self.assertIn("--env APP_SIDECAR_API_KEY=test-key", commands)
        self.assertTrue((ROOT / "services/revolut-auth/requirements-test.txt").is_file())

    def test_revolut_sidecar_publishes_the_captcha_view_port_and_nothing_else(self):
        for path in (ROOT_COMPOSE, DOCKER_COMPOSE):
            with self.subTest(compose_file=path.relative_to(ROOT)):
                service = _load(path)["services"]["revolut-auth"]
                self.assertEqual(service["ports"], ["6080:6080"])
        dockerfile = (ROOT / "services/revolut-auth/Dockerfile").read_text(encoding="utf-8")
        self.assertIn("EXPOSE 8002 6080", dockerfile)
        for module in ("main.py", "captcha.py", "logsafe.py", "member_state.py", "viewer.py"):
            self.assertIn(module, dockerfile)  # a module missing from the image only fails at runtime


def _sidecar_base(service):
    """The base target a sidecar Dockerfile builds on, or None for its own base."""
    text = (ROOT / "services" / service / "Dockerfile").read_text(encoding="utf-8")
    match = re.search(r"(?m)^ARG SIDECAR_BASE=picsou-sidecar-(python|chromium)$", text)
    return match.group(1) if match else None


def _shared_base_services():
    return sorted(
        path.parent.name
        for path in (ROOT / "services").glob("*-auth/Dockerfile")
        if path.parent.name not in OWN_BASE
    )


class SharedSidecarBaseTest(unittest.TestCase):
    def test_every_non_camoufox_sidecar_builds_on_a_shared_base(self):
        services = _shared_base_services()
        self.assertEqual(len(services), 9)
        for service in services:
            with self.subTest(service=service):
                text = (ROOT / "services" / service / "Dockerfile").read_text(encoding="utf-8")
                self.assertIsNotNone(_sidecar_base(service))
                self.assertRegex(text, r"(?m)^FROM \$\{SIDECAR_BASE\}$")
                self.assertNotIn("pip install", text)
                self.assertNotIn("playwright install", text)
                self.assertFalse((ROOT / "services" / service / "requirements.txt").exists())

    def test_browser_sidecars_use_the_chromium_base_and_the_others_the_python_base(self):
        uses_playwright = {
            service
            for service in _shared_base_services()
            if any(
                re.search(r"(?m)^\s*(from|import) playwright", path.read_text(encoding="utf-8"))
                for path in (ROOT / "services" / service).glob("*.py")
                if not path.name.startswith("test_")
            )
        }
        for service in _shared_base_services():
            with self.subTest(service=service):
                expected = "chromium" if service in uses_playwright else "python"
                self.assertEqual(_sidecar_base(service), expected)

    def test_camoufox_sidecars_keep_their_own_base(self):
        for service in OWN_BASE:
            with self.subTest(service=service):
                self.assertIsNone(_sidecar_base(service))
                self.assertTrue((ROOT / "services" / service / "requirements.txt").exists())

    def test_publish_passes_each_sidecar_its_base_by_digest(self):
        workflow = _load(DOCKER_WORKFLOW)
        self.assertEqual(workflow["jobs"]["build"]["needs"], "base")
        outputs = workflow["jobs"]["base"]["outputs"]
        self.assertEqual(set(outputs), {"python", "chromium"})
        steps = {step.get("id"): step for step in workflow["jobs"]["base"]["steps"]}
        for target in ("python", "chromium"):
            with self.subTest(target=target):
                self.assertEqual(steps[target]["with"]["target"], target)
                self.assertEqual(steps[target]["with"]["context"], "services/base")
                self.assertIn(f"steps.{target}.outputs.digest", steps["refs"]["env"][f"{target.upper()}_REF"])
        build_args = next(
            step["with"]["build-args"]
            for step in workflow["jobs"]["build"]["steps"]
            if step.get("name") == "Build and push"
        )
        self.assertIn("needs.base.outputs[matrix.base]", build_args)
        matrix = {entry["name"]: entry for entry in workflow["jobs"]["build"]["strategy"]["matrix"]["include"]}
        for service in _shared_base_services():
            with self.subTest(service=service):
                self.assertEqual(matrix[service].get("base"), _sidecar_base(service))
        for service in OWN_BASE:
            with self.subTest(service=service):
                self.assertNotIn("base", matrix[service])

    def test_ci_builds_the_base_before_each_sidecar_image(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        for service in _shared_base_services():
            target = _sidecar_base(service)
            with self.subTest(service=service):
                job = next(
                    job for job in jobs.values()
                    # corum-auth builds from services/ to copy picsou_browser.
                    if f"--tag picsou-{service}:test " in _run_commands(job)
                )
                commands = _run_commands(job)
                base = f"docker build --target {target} --tag picsou-sidecar-{target} services/base"
                self.assertIn(base, commands)
                self.assertLess(commands.index(base), commands.index(f"--tag picsou-{service}:test"))

    def test_both_compose_files_build_the_base_for_each_sidecar(self):
        for path in (ROOT_COMPOSE, DOCKER_COMPOSE):
            compose = _load(path)["services"]
            for target in ("python", "chromium"):
                with self.subTest(compose_file=path.relative_to(ROOT), base=target):
                    base = compose[f"sidecar-{target}"]
                    self.assertEqual(base["build"]["target"], target)
                    self.assertTrue(base["build"]["context"].endswith("services/base"))
                    # Not a profile: a profiled service is invisible to `down`/`config`
                    # without it, and Compose then rejects the sidecars' contexts.
                    self.assertNotIn("profiles", base)
                    self.assertEqual(base["scale"], 0)
                    self.assertEqual(base["image"], f"ghcr.io/cloeille/picsou-finance/sidecar-{target}:latest")
            for service in _shared_base_services():
                target = _sidecar_base(service)
                with self.subTest(compose_file=path.relative_to(ROOT), service=service):
                    build = compose[service]["build"]
                    self.assertEqual(build["args"]["SIDECAR_BASE"], f"sidecar-{target}")
                    self.assertEqual(
                        build["additional_contexts"][f"sidecar-{target}"],
                        f"service:sidecar-{target}",
                    )


if __name__ == "__main__":
    unittest.main()
