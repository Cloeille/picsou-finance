"""Regression checks for published sidecar and image-test workflow coverage."""

from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
DOCKER_WORKFLOW = ROOT / ".github/workflows/docker.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
CODEQL_WORKFLOW = ROOT / ".github/workflows/codeql.yml"
APP_DOCKERFILE = ROOT / "docker/Dockerfile"
ROOT_COMPOSE = ROOT / "docker-compose.yml"
DOCKER_COMPOSE = ROOT / "docker/docker-compose.yml"
BASE_DOCKERFILE = "services/base/Dockerfile"


def _load(path):
    with path.open(encoding="utf-8") as workflow:
        return yaml.safe_load(workflow)


def _run_commands(job):
    return "\n".join(step.get("run", "") for step in job.get("steps", []))


def _builds_then_runs_image(job, service):
    commands = _run_commands(job)
    build = commands.find(f"docker build --tag picsou-{service}:test services/{service}")
    run = commands.find("docker run", build) if build != -1 else -1
    return run != -1 and f"picsou-{service}:test" in commands[run:]


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
        } - {BASE_DOCKERFILE}
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

    def test_every_published_sidecar_runs_its_image_tests_before_publishing(self):
        matrix = _load(DOCKER_WORKFLOW)["jobs"]["build"]["strategy"]["matrix"]["include"]
        published = sorted(
            Path(entry["context"]).name
            for entry in matrix
            if entry.get("context", "").startswith("services/")
        )
        jobs = _load(CI_WORKFLOW)["jobs"]
        gate = set(jobs["docker"]["needs"])
        self.assertIn("revolut-auth", published)
        for service in published:
            with self.subTest(service=service):
                testing = {name for name, job in jobs.items() if _builds_then_runs_image(job, service)}
                self.assertTrue(testing, f"no CI job runs tests in the {service} image")
                self.assertTrue(gate & testing, f"publishing does not wait for the {service} image tests")

    def test_revolut_job_runs_the_whole_test_directory_in_the_image(self):
        commands = _run_commands(_load(CI_WORKFLOW)["jobs"]["revolut-auth-sidecar"])

        self.assertIn("services/revolut-auth/tests:/app/tests:ro", commands)
        self.assertIn("python -m pytest -p no:cacheprovider -p no:logging --asyncio-mode=auto -v tests", commands)

    def test_revolut_job_preserves_the_legacy_profile_owner(self):
        commands = _run_commands(_load(CI_WORKFLOW)["jobs"]["revolut-auth-sidecar"])

        self.assertIn(
            "docker run --rm --network none --entrypoint sh picsou-revolut-auth:test "
            "-c 'test \"$(id -u)\" = 999'",
            commands,
        )

    def test_camoufox_starts_on_a_native_arm64_runner(self):
        job = _load(CI_WORKFLOW)["jobs"]["camoufox-arm64-smoke"]
        commands = _run_commands(job)

        self.assertEqual(job["runs-on"], "ubuntu-24.04-arm")
        self.assertIn('test "$(uname -m)" = aarch64', commands)
        self.assertIn("docker build --target camoufox --tag picsou-sidecar-camoufox services/base", commands)
        for service in ("amex-auth", "revolut-auth"):
            with self.subTest(service=service):
                image = f"picsou-{service}:arm64-smoke"
                self.assertIn(f"docker build --tag {image} services/{service}", commands)
                self.assertIn(image, commands)
        self.assertEqual(commands.count("--network none"), 2)
        self.assertIn('test "$(id -u)" -ne 0', commands)
        self.assertIn("/opt/sidecar-base/entrypoint.sh", commands)
        self.assertIn("python /opt/sidecar-base/smoke_camoufox.py", commands)

    def test_camoufox_starts_on_amd64_in_each_built_sidecar(self):
        for service in ("amex-auth", "revolut-auth"):
            with self.subTest(service=service):
                job = _load(CI_WORKFLOW)["jobs"][f"{service}-sidecar"]
                commands = _run_commands(job)
                image = f"picsou-{service}:test"
                self.assertEqual(job["runs-on"], "ubuntu-latest")
                self.assertIn(f"docker run --rm --network none --entrypoint sh {image}", commands)
                self.assertIn('test "$(uname -m)" = x86_64', commands)
                self.assertIn('test "$PICSOU_SIDECAR_BASE" = sidecar-camoufox', commands)
                self.assertIn("python /opt/sidecar-base/smoke_camoufox.py", commands)

    def test_shared_camoufox_tests_run_in_the_base_image(self):
        commands = _run_commands(_load(CI_WORKFLOW)["jobs"]["amex-auth-sidecar"])

        self.assertIn("--env PYTHONPATH=/opt/sidecar-base", commands)
        self.assertIn("services:/services:ro", commands)
        self.assertIn("-m unittest discover -s /services/base -p 'test_*.py' -v", commands)

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


SELECTOR = "changes"
GATE = "ci-ok"
PUBLISH = "docker"


def _gated(jobs):
    return set(jobs) - {SELECTOR, GATE, PUBLISH}


def _flatten(patterns):
    for pattern in patterns:
        if isinstance(pattern, list):
            yield from _flatten(pattern)
        else:
            yield pattern


def _filter_step(jobs):
    return next(step for step in jobs[SELECTOR]["steps"] if step.get("id") == "filter")


def _filters(jobs):
    raw = yaml.safe_load(_filter_step(jobs)["with"]["filters"])
    return {name: list(_flatten(patterns)) for name, patterns in raw.items()}


def _glob_matches(pattern, path):
    regex = "".join(
        ".*" if part == "**" else "[^/]*" if part == "*" else re.escape(part)
        for part in re.split(r"(\*\*|\*)", pattern)
    )
    return re.fullmatch(regex, path) is not None


def _selected(jobs, path):
    return {
        name
        for name, patterns in _filters(jobs).items()
        if name in _gated(jobs) and any(_glob_matches(pattern, path) for pattern in patterns)
    }


def _gate(needs):
    script = _run_commands(_load(CI_WORKFLOW)["jobs"][GATE])
    return subprocess.run(
        ["bash", "-c", script],
        env={**os.environ, "NEEDS": json.dumps(needs)},
        capture_output=True,
        text=True,
    )


class CiJobSelectionTest(unittest.TestCase):
    def test_only_a_pull_requests_stale_run_is_cancelled(self):
        for path in (CI_WORKFLOW, CODEQL_WORKFLOW):
            with self.subTest(workflow=path.name):
                concurrency = _load(path)["concurrency"]
                self.assertEqual(
                    concurrency["group"],
                    "${{ github.workflow }}-${{ github.event_name == 'pull_request'"
                    " && github.event.pull_request.number || github.run_id }}",
                )
                self.assertEqual(concurrency["cancel-in-progress"], "${{ github.event_name == 'pull_request' }}")

    def test_every_job_runs_only_when_the_selector_picks_it(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        outputs = jobs[SELECTOR]["outputs"]
        self.assertEqual(set(outputs), _gated(jobs))
        self.assertEqual(set(_filters(jobs)) - {"workflows"}, _gated(jobs))
        self.assertEqual(
            _filter_step(jobs)["if"],
            "github.event_name == 'pull_request' && github.event.pull_request.changed_files < 3000",
        )
        for name in _gated(jobs):
            with self.subTest(job=name):
                self.assertEqual(jobs[name]["needs"], SELECTOR)
                self.assertEqual(jobs[name]["if"], f"needs.changes.outputs.{name} == 'true'")
                self.assertEqual(
                    outputs[name],
                    f"${{{{ steps.filter.outcome == 'skipped' || steps.filter.outputs.{name} == 'true' }}}}",
                )

    def test_a_change_selects_the_jobs_it_can_affect(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        sidecars = {name for name in _gated(jobs) if name.endswith("-sidecar")}
        services = {"compose-sidecar-build", "workflow-inventory"}
        cases = {
            "docs/features/budget.md": set(),
            "CHANGELOG.md": set(),
            "frontend/src/App.tsx": {"frontend", "e2e", "release-deployment"},
            "backend/src/main/java/com/picsou/App.java": {"backend", "release-deployment"},
            "docker/entrypoint.sh": {"backend", "docker-entrypoint", "release-deployment"},
            "docker/Dockerfile": {"docker-entrypoint", "release-deployment", "workflow-inventory"},
            "docker/docker-compose.yml": {"docker-entrypoint", "release-deployment"} | services,
            "docker-compose.yml": {"docker-entrypoint", "workflow-inventory"},
            "frontend/Dockerfile": {
                "docker-entrypoint", "e2e", "frontend", "release-deployment",
            },
            "frontend/nginx.conf": {
                "docker-entrypoint", "e2e", "frontend", "release-deployment",
            },
            "frontend/picsou-realip.conf": {
                "docker-entrypoint", "e2e", "frontend", "release-deployment",
            },
            "services/sofidy-auth/main.py": {"sofidy-sidecar", "backend"} | services,
            "services/tr-auth/main.py": {"tr-auth-sidecar"} | services,
            "services/amex-auth/main.py": {"amex-auth-sidecar", "camoufox-arm64-smoke"}
            | services,
            "services/revolut-auth/main.py": {"revolut-auth-sidecar", "camoufox-arm64-smoke"}
            | services,
            "services/revolut-auth/Dockerfile": {"revolut-auth-sidecar", "amex-auth-sidecar", "camoufox-arm64-smoke"}
            | services,
            "services/base/requirements.txt": sidecars
            | {"backend", "camoufox-arm64-smoke"}
            | services,
            ".github/workflows/ci.yml": _gated(jobs),
            ".github/tests/test_sidecar_workflows.py": _gated(jobs),
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(_selected(jobs, path), expected)

    def test_each_sidecar_job_runs_for_its_own_service(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        tested = 0
        for name in _gated(jobs):
            for service in re.findall(r"docker build --tag picsou-([a-z-]+):test services/", _run_commands(jobs[name])):
                with self.subTest(job=name, service=service):
                    tested += 1
                    self.assertIn(name, _selected(jobs, f"services/{service}/main.py"))
        self.assertEqual(tested, len(list((ROOT / "services").glob("*-auth/Dockerfile"))))

    def test_the_gate_waits_for_every_job_and_publishing_waits_for_the_gate(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        self.assertEqual(jobs[GATE]["if"], "always()")
        self.assertEqual(set(jobs[GATE]["needs"]), _gated(jobs) | {SELECTOR})
        self.assertIn(GATE, jobs[PUBLISH]["needs"])

    def test_the_gate_is_green_only_when_every_selected_job_succeeded(self):
        jobs = _gated(_load(CI_WORKFLOW)["jobs"])
        frontend_only = {"frontend", "e2e", "release-deployment"}

        def needs(selected, **results):
            needs = {
                name: {"result": "success" if name in selected else "skipped", "outputs": {}}
                for name in jobs
            }
            outputs = {name: str(name in selected).lower() for name in jobs}
            needs[SELECTOR] = {"result": "success", "outputs": outputs}
            for name, result in results.items():
                needs[name]["result"] = result
            return needs

        selector_failed = {name: {"result": "skipped", "outputs": {}} for name in jobs}
        selector_failed[SELECTOR] = {"result": "failure", "outputs": {}}
        cases = {
            "everything selected and green": (needs(jobs), True),
            "frontend change, other jobs skipped": (needs(frontend_only), True),
            "selected job skipped": (needs(frontend_only, e2e="skipped"), False),
            "selected job failed": (needs(frontend_only, e2e="failure"), False),
            "selected job cancelled": (needs(frontend_only, frontend="cancelled"), False),
            "unselected job failed": (needs(frontend_only, backend="failure"), False),
            "selector failed": (selector_failed, False),
        }
        for case, (state, green) in cases.items():
            with self.subTest(case=case):
                result = _gate(state)
                self.assertEqual(result.returncode == 0, green, result.stderr)

    def test_the_demo_playwright_suite_runs_once(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        running = [name for name, job in jobs.items() if "test:e2e" in _run_commands(job)]

        self.assertEqual(running, ["e2e"])

    def test_frontend_jobs_restore_bun_downloads_before_installing(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        for name in ("frontend", "e2e"):
            with self.subTest(job=name):
                steps = jobs[name]["steps"]
                cache = next(
                    index for index, step in enumerate(steps)
                    if step.get("with", {}).get("path") == "~/.bun/install/cache"
                )
                install = next(index for index, step in enumerate(steps) if "bun install" in step.get("run", ""))
                self.assertLess(cache, install)
                self.assertIn("hashFiles('frontend/bun.lock')", steps[cache]["with"]["key"])


def _dockerfile_stages(path):
    stages = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("FROM "):
            stages.append([])
        if stages and line.strip() and not line.lstrip().startswith("#"):
            stages[-1].append(line)
    return stages


class AppImageBuildTest(unittest.TestCase):
    def test_only_the_runtime_stage_is_built_per_target_platform(self):
        *build, runtime = _dockerfile_stages(APP_DOCKERFILE)
        self.assertEqual(len(build), 2)
        for stage in build:
            with self.subTest(stage=stage[0]):
                self.assertTrue(stage[0].startswith("FROM --platform=$BUILDPLATFORM "))
        self.assertNotIn("--platform", runtime[0])

    def test_a_version_bump_keeps_the_dependency_layers(self):
        install = re.compile(r"^RUN .*(bun install|dependency:go-offline|apt-get install)")
        version = re.compile(r"^ARG (APP_VERSION|VITE_)")
        for stage in _dockerfile_stages(APP_DOCKERFILE):
            installs = [index for index, line in enumerate(stage) if install.search(line)]
            for index, line in enumerate(stage):
                if version.search(line):
                    with self.subTest(stage=stage[0], arg=line):
                        self.assertGreater(index, max(installs, default=-1))


def _sidecar_base(service):
    """The shared base target a sidecar Dockerfile builds on."""
    text = (ROOT / "services" / service / "Dockerfile").read_text(encoding="utf-8")
    match = re.search(r"(?m)^ARG SIDECAR_BASE=picsou-sidecar-(python|chromium|camoufox)$", text)
    return match.group(1) if match else None


def _shared_base_services():
    return sorted(
        path.parent.name
        for path in (ROOT / "services").glob("*-auth/Dockerfile")
    )


class SharedSidecarBaseTest(unittest.TestCase):
    def test_every_sidecar_builds_on_a_shared_base(self):
        services = _shared_base_services()
        self.assertEqual(len(services), 13)
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
                if service in {"amex-auth", "revolut-auth"}:
                    expected = "camoufox"
                else:
                    expected = "chromium" if service in uses_playwright else "python"
                self.assertEqual(_sidecar_base(service), expected)

    def test_publish_passes_each_sidecar_its_base_by_digest(self):
        workflow = _load(DOCKER_WORKFLOW)
        self.assertEqual(workflow["jobs"]["build"]["needs"], "base")
        outputs = workflow["jobs"]["base"]["outputs"]
        self.assertEqual(set(outputs), {"python", "chromium", "camoufox"})
        steps = {step.get("id"): step for step in workflow["jobs"]["base"]["steps"]}
        for target in ("python", "chromium", "camoufox"):
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
        self.assertEqual(
            {matrix[service]["base"] for service in ("amex-auth", "revolut-auth")},
            {"camoufox"},
        )
        for service in _shared_base_services():
            with self.subTest(service=service):
                self.assertEqual(matrix[service].get("base"), _sidecar_base(service))

    def test_ci_builds_the_base_before_each_sidecar_image(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        for service in _shared_base_services():
            target = _sidecar_base(service)
            with self.subTest(service=service):
                job = next(
                    job for job in jobs.values()
                    if f"--tag picsou-{service}:test services/{service}" in _run_commands(job)
                )
                commands = _run_commands(job)
                base = f"docker build --target {target} --tag picsou-sidecar-{target} services/base"
                self.assertIn(base, commands)
                self.assertLess(commands.index(base), commands.index(f"--tag picsou-{service}:test"))

    def test_both_compose_files_build_the_base_for_each_sidecar(self):
        for path in (ROOT_COMPOSE, DOCKER_COMPOSE):
            compose = _load(path)["services"]
            for target in ("python", "chromium", "camoufox"):
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
