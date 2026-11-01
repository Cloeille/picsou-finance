"""Regression checks for published sidecar and image-test workflow coverage."""

from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
DOCKER_WORKFLOW = ROOT / ".github/workflows/docker.yml"
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
CODEQL_WORKFLOW = ROOT / ".github/workflows/codeql.yml"
APP_DOCKERFILE = ROOT / "docker/Dockerfile"
DOCKER_COMPOSE = ROOT / "docker/docker-compose.yml"
APPLICATION_YML = ROOT / "backend/src/main/resources/application.yml"
APPLICATION_DEV_YML = ROOT / "backend/src/main/resources/application-dev.yml"
BASE_DOCKERFILE = "services/base/Dockerfile"


def _load(path):
    with path.open(encoding="utf-8") as workflow:
        return yaml.safe_load(workflow)


def _run_commands(job):
    return "\n".join(step.get("run", "") for step in job.get("steps", []))


SIDECARS_SCRIPT = ROOT / ".github/scripts/sidecars.py"


def _sidecars(*args, env=None):
    result = subprocess.run(
        [sys.executable, str(SIDECARS_SCRIPT), *args],
        capture_output=True, text=True, check=True, env={**os.environ, **(env or {})},
    )
    return result.stdout


def _inventory():
    return json.loads(_sidecars())


def _legs():
    with tempfile.NamedTemporaryFile(mode="r") as output:
        _sidecars("--select", env={"ALL": "true", "FILES": "[]", "GITHUB_OUTPUT": output.name})
        return json.loads(dict(line.rstrip("\n").split("=", 1) for line in output)["matrix"])


def _sidecar_steps(service, arch="x86_64"):
    """The sidecar matrix job's steps as they run for one service on one architecture."""
    entry = next(item for item in _legs() if item["service"] == service and item["arch"] == arch)
    job = _load(CI_WORKFLOW)["jobs"]["sidecar"]
    steps = []
    for step in job["steps"]:
        condition = step.get("if")
        if condition and condition != f"matrix.base == '{entry['base']}'":
            continue
        text = step.get("run", "")
        for key, value in entry.items():
            text = re.sub(r"\$\{\{ matrix\." + key + r" \}\}", value, text)
        steps.append(text.replace('"$IMAGE"', job["env"]["IMAGE"].replace("${{ matrix.service }}", service)))
    return "\n".join(steps)


class SidecarWorkflowInventoryTest(unittest.TestCase):
    def run_publish_versions(self, ref_type, ref_name, sha):
        workflow = _load(DOCKER_WORKFLOW)
        step = next(step for step in workflow["jobs"]["base"]["steps"] if step.get("id") == "versions")
        with tempfile.NamedTemporaryFile() as output:
            result = subprocess.run(
                ["bash", "-c", step["run"]],
                env={
                    **os.environ,
                    "GITHUB_REF_TYPE": ref_type,
                    "GITHUB_REF_NAME": ref_name,
                    "GITHUB_SHA": sha,
                    "GITHUB_OUTPUT": output.name,
                },
                capture_output=True,
                text=True,
            )
            output.seek(0)
            values = dict(line.decode().rstrip().split("=", 1) for line in output)
        self.assertEqual(result.returncode, 0, result.stderr)
        return values

    def run_chart_version(self, release_version, *, command_directory=None):
        workflow = _load(DOCKER_WORKFLOW)
        step = next(step for step in workflow["jobs"]["chart"]["steps"] if step.get("id") == "version")
        with tempfile.NamedTemporaryFile() as output:
            result = subprocess.run(
                ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", step["run"]],
                cwd=ROOT,
                env={
                    **os.environ,
                    "PATH": (
                        f"{command_directory}{os.pathsep}{os.environ['PATH']}"
                        if command_directory else os.environ["PATH"]
                    ),
                    "RELEASE_VERSION": release_version,
                    "IMAGE_TAG": "sha-0123456",
                    "GITHUB_OUTPUT": output.name,
                },
                capture_output=True,
                text=True,
            )
            output.seek(0)
            values = dict(line.decode().rstrip().split("=", 1) for line in output)
        self.assertEqual(result.returncode, 0, result.stderr)
        return values

    def test_ci_pins_the_workflow_yaml_dependency(self):
        workflow = _load(CI_WORKFLOW)
        commands = _run_commands(workflow["jobs"]["workflow-inventory"])

        self.assertIn("python -m pip install PyYAML==6.0.3", commands)

    def test_application_yml_is_the_only_place_sidecar_urls_are_set(self):
        for path in (DOCKER_COMPOSE, ROOT / "docker/entrypoint.sh", ROOT / ".env.example",
                     ROOT / "docker/.env.example"):
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertNotRegex(path.read_text(encoding="utf-8"), r"(?m)^[^#]*_AUTH_URL")

    def test_every_compose_sidecar_has_a_url_in_both_spring_profiles(self):
        sidecars = {name for name in _load(DOCKER_COMPOSE)["services"] if name.endswith("-auth")}
        default = _load(APPLICATION_YML)["app"]
        dev = _load(APPLICATION_DEV_YML)["app"]
        for name in sorted(sidecars):
            with self.subTest(sidecar=name):
                self.assertIn(name, default, "missing from application.yml")
                self.assertIn(name, dev, "missing from application-dev.yml")
                variable = name.upper().replace("-", "_") + "_URL"
                self.assertRegex(default[name]["url"], rf"^\$\{{{variable}:http://{name}:\d+\}}$")
                self.assertRegex(dev[name]["url"], rf"^\$\{{{variable}:http://127\.0\.0\.1:\d+\}}$")
        dev_ports = [dev[name]["url"].rsplit(":", 1)[1] for name in sidecars if name in dev]
        self.assertEqual(len(dev_ports), len(set(dev_ports)), "dev ports must not collide")

    def test_amex_sidecar_uses_an_init_process(self):
        self.assertIs(_load(DOCKER_COMPOSE)["services"]["amex-auth"]["init"], True)

    def test_every_service_dockerfile_is_in_the_publish_matrix_exactly_once(self):
        workflow = _load(DOCKER_WORKFLOW)
        self.assertEqual(
            workflow["jobs"]["build"]["strategy"]["matrix"]["include"],
            "${{ fromJSON(needs.base.outputs.matrix) }}",
        )
        matrix = json.loads(_sidecars("--publish", env={"GITHUB_REPOSITORY": "Owner/repo"}))
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
        self.assertEqual(matrix[0], {
            "name": "app", "image": "Owner/repo", "context": ".",
            "dockerfile": "docker/Dockerfile", "versioned": True,
        })
        for service in ("amex-auth", "degiro-auth"):
            with self.subTest(service=service):
                entry = next(item for item in matrix if item.get("name") == service)
                self.assertEqual(entry["context"], f"services/{service}")
                self.assertEqual(entry["image"], f"Owner/repo/{service}")
                self.assertEqual(entry["versioned"], False)

    def test_every_published_sidecar_runs_its_image_tests_before_publishing(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        sidecar = jobs["sidecar"]
        self.assertEqual(
            sidecar["strategy"]["matrix"]["include"],
            "${{ fromJSON(needs.changes.outputs.sidecar-matrix) }}",
        )
        self.assertIs(sidecar["strategy"]["fail-fast"], False)
        self.assertIn("sidecar", jobs["docker"]["needs"])
        published = sorted(
            Path(entry["context"]).name
            for entry in json.loads(_sidecars("--publish", env={"GITHUB_REPOSITORY": "o/r"}))
            if entry["context"].startswith("services/")
        )
        self.assertEqual(published, [entry["service"] for entry in _inventory()])
        self.assertIn("revolut-auth", published)

    def test_every_sidecar_runs_all_its_unittest_modules_from_its_folder(self):
        # Discovery from the read-only checkout runs every test_*.py next to
        # main.py; no workflow lists a sidecar's test files.
        for entry in _inventory():
            service = entry["service"]
            with self.subTest(service=service):
                commands = _sidecar_steps(service)
                image = f"picsou-{service}:test"
                self.assertIn(f"docker build --tag {image} services/{service}", commands)
                self.assertIn('services:/src/services:ro"', commands)
                self.assertIn(f"--workdir /src/services/{service} {image} -m unittest discover -v", commands)
                self.assertIn("--entrypoint python", commands)
                self.assertIn("--env APP_SIDECAR_API_KEY=test-key", commands)
                self.assertTrue(list((ROOT / "services" / service).glob("test_*.py")))
                self.assertFalse((ROOT / "services" / service / "tests").exists())
        self.assertNotIn("pytest", _run_commands(_load(CI_WORKFLOW)["jobs"]["sidecar"]))

    def test_every_sidecar_test_module_is_stdlib_unittest(self):
        for path in sorted((ROOT / "services").glob("*-auth/test_*.py")):
            with self.subTest(module=path.relative_to(ROOT).as_posix()):
                self.assertNotRegex(path.read_text(encoding="utf-8"), r"(?m)^\s*(import|from) pytest\b")

    def test_every_sidecar_image_imports_main_non_root_on_its_base(self):
        for entry in _inventory():
            with self.subTest(service=entry["service"]):
                self.assertIn(
                    f'test "$(id -u)" -ne 0 && test "$PICSOU_SIDECAR_BASE" = sidecar-{entry["base"]} '
                    '&& python -c "import main"',
                    _sidecar_steps(entry["service"]),
                )

    def test_revolut_image_keeps_the_legacy_profile_owner(self):
        # Existing Revolut profile volumes belong to UID 999.
        dockerfile = (ROOT / "services/revolut-auth/Dockerfile").read_text(encoding="utf-8")

        self.assertRegex(dockerfile, r"useradd -r -u 999 -g revoauth .*revoauth")
        self.assertRegex(dockerfile, r"(?m)^USER revoauth$")

    def test_camoufox_sidecars_also_run_on_a_native_arm64_runner(self):
        # A native runner proves the image contains an ARM Firefox binary; QEMU can
        # build the manifest but cannot provide this runtime guarantee.
        job = _load(CI_WORKFLOW)["jobs"]["sidecar"]
        self.assertEqual(job["runs-on"], "${{ matrix.runner }}")
        legs = {(leg["service"], leg["arch"]): leg["runner"] for leg in _legs()}
        self.assertEqual(
            {service for service, arch in legs if arch == "aarch64"},
            {entry["service"] for entry in _inventory() if entry["base"] == "camoufox"},
        )
        self.assertEqual({service for service, arch in legs if arch == "x86_64"},
                         {entry["service"] for entry in _inventory()})
        for (service, arch), runner in legs.items():
            with self.subTest(service=service, arch=arch):
                self.assertEqual(runner, {"x86_64": "ubuntu-latest", "aarch64": "ubuntu-24.04-arm"}[arch])

    def test_camoufox_starts_headful_offline_in_each_built_sidecar_on_both_architectures(self):
        for service in ("amex-auth", "revolut-auth"):
            for arch in ("x86_64", "aarch64"):
                with self.subTest(service=service, arch=arch):
                    commands = _sidecar_steps(service, arch)
                    image = f"picsou-{service}:test"
                    self.assertIn(f"docker run --rm --network none --entrypoint sh {image}", commands)
                    self.assertIn(f'test "$(uname -m)" = {arch}', commands)
                    self.assertIn('test "$PICSOU_SIDECAR_BASE" = sidecar-camoufox', commands)
                    self.assertIn("/opt/sidecar-base/entrypoint.sh", commands)
                    self.assertIn("python /opt/sidecar-base/smoke_camoufox.py", commands)
                    self.assertIn("-m unittest discover -v", commands)
        self.assertNotIn("smoke_camoufox.py", _sidecar_steps("tr-auth"))

    def test_every_shared_base_test_module_runs_in_the_camoufox_base(self):
        # The camoufox target inherits the python one: discovery there runs the
        # package tests and the Camoufox installer and entrypoint tests alike.
        commands = _run_commands(_load(CI_WORKFLOW)["jobs"]["sidecar-base"])

        self.assertIn("docker build --target camoufox --tag picsou-sidecar-camoufox services/base", commands)
        self.assertIn('services:/services:ro"', commands)
        self.assertIn("--workdir /services/base picsou-sidecar-camoufox -m unittest discover -v", commands)
        self.assertTrue(list((ROOT / "services/base").glob("test_*.py")))

    def test_every_sidecar_image_test_mounts_the_test_only_helpers_read_only(self):
        services = [entry["service"] for entry in _inventory()]
        self.assertEqual(len(services), 13)
        for service in services:
            with self.subTest(service=service):
                commands = _sidecar_steps(service)
                for helper in ("picsou_sidecar/contract.py", "euro_information/testing.py"):
                    self.assertIn(f"services/base/{helper}:/opt/sidecar-base/lib/{helper}:ro", commands)
        ignored = (ROOT / "services/base/.dockerignore").read_text(encoding="utf-8").split()
        self.assertIn("picsou_sidecar/contract.py", ignored)
        self.assertIn("euro_information/testing.py", ignored)

    def test_publish_workflow_keeps_both_platforms_and_branch_tagging(self):
        job_text = DOCKER_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("platforms: linux/amd64,linux/arm64", job_text)
        self.assertIn(
            "type=ref,event=branch,enable=${{ github.ref != 'refs/heads/main' }}",
            job_text,
        )
        # One tag set per published image family: the three bases and the build matrix.
        self.assertEqual(job_text.count("type=semver,pattern={{version}}"), 4)
        self.assertEqual(job_text.count("type=sha,format=short"), 4)

    def test_publish_versions_are_computed_once_for_images_and_chart(self):
        sha = "0123456789abcdef"
        cases = {
            ("tag", "v1.2.3"): {
                "app-version": "1.2.3", "image-tag": "1.2.3",
                "chart-release-version": "1.2.3",
            },
            ("branch", "main"): {
                "app-version": "nightly-0123456", "image-tag": "sha-0123456",
                "chart-release-version": "",
            },
        }
        for ref, expected in cases.items():
            with self.subTest(ref=ref):
                self.assertEqual(self.run_publish_versions(*ref, sha), expected)

        workflow = _load(DOCKER_WORKFLOW)
        base = workflow["jobs"]["base"]
        build = workflow["jobs"]["build"]
        chart = workflow["jobs"]["chart"]
        self.assertEqual(set(base["outputs"]), {
            "python", "chromium", "camoufox", "app-version", "image-tag", "chart-release-version", "matrix",
        })
        self.assertEqual(chart["needs"], ["base", "build"])
        version = next(step for step in chart["steps"] if step.get("id") == "version")
        self.assertNotIn("PUBLISH", version["env"])
        self.assertNotIn("$PUBLISH", version["run"])
        downstream = chart["steps"][chart["steps"].index(version) + 1:]
        self.assertEqual(len(downstream), 7)
        for step in downstream:
            self.assertEqual(step["if"], "steps.version.outputs.publish == 'true'")
        metadata = next(step for step in build["steps"] if step.get("id") == "meta")
        self.assertIn("type=sha,format=short", metadata["with"]["tags"].splitlines())
        self.assertIn("type=semver,pattern={{version}}", metadata["with"]["tags"].splitlines())
        self.assertNotIn("type=raw,value=${{", DOCKER_WORKFLOW.read_text())
        # All image references must pass through metadata-action's lowercase normalization.
        self.assertEqual(build["steps"][-1]["with"]["tags"], "${{ steps.meta.outputs.tags }}")
        self.assertIn("needs.base.outputs.app-version", build["steps"][-1]["with"]["build-args"])
        self.assertNotIn("GITHUB_SHA", _run_commands(chart))
        self.assertNotIn("GITHUB_REF", _run_commands(chart))

    def test_chart_version_uses_fetched_local_tags_without_a_remote_lookup(self):
        workflow = _load(DOCKER_WORKFLOW)
        chart = workflow["jobs"]["chart"]
        checkout = next(step for step in chart["steps"] if step.get("name") == "Checkout")
        version_command = next(step["run"] for step in chart["steps"] if step.get("id") == "version")
        self.assertEqual(checkout["with"]["fetch-depth"], 0)
        self.assertIn("git tag --list", version_command)
        self.assertNotIn("git ls-remote", version_command)

        with tempfile.TemporaryDirectory() as directory:
            git = Path(directory) / "git"
            git.write_text(
                "#!/bin/sh\n"
                "case \"$*\" in\n"
                "  'tag --list') printf '%s\\n' v1.2.3 1.9.09 2.0.0 v2.0.1-rc.1 8.0.0-rc.01 not-a-version ;;\n"
                "  'show -s --format=%cd --date=format-local:%Y%m%d%H%M%S HEAD')\n"
                "    [ \"$TZ\" = UTC ] || exit 94\n"
                "    echo 20261031010203 ;;\n"
                "  *) exit 93 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            git.chmod(0o755)
            values = self.run_chart_version("", command_directory=directory)

        self.assertEqual(values["publish"], "true")
        self.assertEqual(values["version"], "2.0.2-main.20261031010203.g0123456")

    def test_chart_version_enforces_semver_prerelease_identifiers(self):
        valid = ("1.2.3", "1.2.3-rc.1", "1.2.3-main.20261101010203.g0123456")
        invalid = ("1.2.3-01", "1.2.3-rc.01")
        for version in valid:
            with self.subTest(version=version):
                values = self.run_chart_version(version)
                self.assertEqual(values["publish"], "true")
                self.assertEqual(values["version"], version)
        for version in invalid:
            with self.subTest(version=version):
                values = self.run_chart_version(version)
                self.assertEqual(values, {"publish": "false"})


SELECTOR = "changes"
GATE = "ci-ok"
PUBLISH = "docker"
NON_BLOCKING = {"helm-chart"}


def _selectable(jobs):
    return set(jobs) - {SELECTOR, GATE, PUBLISH}


def _gated(jobs):
    return _selectable(jobs) - NON_BLOCKING


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


def _select_sidecars(files, every=False, changes=("sidecar",)):
    with tempfile.NamedTemporaryFile(mode="r") as output:
        _sidecars("--select", env={
            "ALL": str(every).lower(), "FILES": json.dumps(files), "GITHUB_OUTPUT": output.name,
            "CHANGES": json.dumps(list(changes)),
        })
        return dict(line.rstrip("\n").split("=", 1) for line in output)


def _selected(jobs, path):
    """The jobs a one-file change runs; the sidecar matrix legs appear as sidecar:<service>."""
    filtered = {
        name
        for name, patterns in _filters(jobs).items()
        if name != "workflows" and any(_glob_matches(pattern, path) for pattern in patterns)
    }
    selection = _select_sidecars([path] if "sidecar" in filtered else [], changes=sorted(filtered))
    filtered = set(json.loads(selection["jobs"]))
    if "sidecar" not in filtered:
        return filtered
    legs = json.loads(selection["matrix"])
    return (filtered - {"sidecar"}) | {
        f"sidecar:{leg['service']}" if leg["arch"] == "x86_64" else f"sidecar:{leg['service']}:arm64"
        for leg in legs
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
        self.assertEqual(outputs, {
            "all": "${{ steps.filter.outcome == 'skipped' }}",
            "jobs": "${{ steps.sidecars.outputs.jobs }}",
            "sidecar-matrix": "${{ steps.sidecars.outputs.matrix }}",
        })
        self.assertEqual(set(_filters(jobs)) - {"workflows"}, _selectable(jobs))
        self.assertEqual(
            _filter_step(jobs)["if"],
            "github.event_name == 'pull_request' && github.event.pull_request.changed_files < 3000",
        )
        self.assertEqual(_filter_step(jobs)["with"]["list-files"], "json")
        sidecars = next(step for step in jobs[SELECTOR]["steps"] if step.get("id") == "sidecars")
        self.assertEqual(sidecars["env"], {
            "ALL": "${{ steps.filter.outcome == 'skipped' }}",
            "CHANGES": "${{ steps.filter.outputs.changes }}",
            "FILES": "${{ steps.filter.outputs.sidecar_files }}",
        })
        self.assertEqual(sidecars["run"].strip(), "python3 .github/scripts/sidecars.py --select")
        for name in _selectable(jobs):
            with self.subTest(job=name):
                self.assertEqual(jobs[name]["needs"], SELECTOR)
                self.assertEqual(
                    jobs[name]["if"],
                    "needs.changes.outputs.all == 'true' || "
                    f"contains(fromJSON(needs.changes.outputs.jobs), '{name}')",
                )

    def test_runs_without_a_file_list_select_every_sidecar(self):
        everything = _select_sidecars([], every=True, changes=())
        self.assertEqual(json.loads(everything["matrix"]), _legs())
        self.assertEqual(len(_legs()), len(_inventory()) + 2)
        self.assertEqual(_select_sidecars([], changes=["frontend"]), {"jobs": '["frontend"]', "matrix": "[]"})
        removed = _select_sidecars(["services/gone-auth/main.py"], changes=["workflow-inventory", "sidecar"])
        self.assertEqual(removed, {"jobs": '["workflow-inventory"]', "matrix": "[]"})

    def test_a_change_selects_the_jobs_it_can_affect(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        sidecars = {f"sidecar:{entry['service']}" for entry in _inventory()} | {
            "sidecar:amex-auth:arm64", "sidecar:revolut-auth:arm64",
        }
        everything = (_selectable(jobs) - {"sidecar"}) | sidecars
        services = {"compose-sidecar-build", "workflow-inventory"}
        cases = {
            "docs/features/budget.md": set(),
            "CHANGELOG.md": set(),
            "frontend/src/App.tsx": {"frontend", "e2e", "release-deployment"},
            "backend/src/main/java/com/picsou/App.java": {"backend", "release-deployment"},
            "docker/entrypoint.sh": {
                "backend", "docker-entrypoint", "release-deployment", "workflow-inventory",
            },
            ".env.example": {"workflow-inventory"},
            "docker/Dockerfile": {"docker-entrypoint", "release-deployment", "workflow-inventory"},
            "docker/docker-compose.yml": {"docker-entrypoint", "release-deployment", "helm-chart"} | services,
            "backend/src/main/resources/application.yml": {
                "backend", "helm-chart", "release-deployment", "workflow-inventory",
            },
            "backend/src/main/resources/application-dev.yml": {
                "backend", "release-deployment", "workflow-inventory",
            },
            "charts/picsou/values.yaml": {"helm-chart"},
            "charts/picsou/templates/sidecars.yaml": {"helm-chart"},
            "services/sofidy-auth/main.py": {"sidecar:sofidy-auth", "backend"} | services,
            "services/tr-auth/main.py": {"sidecar:tr-auth"} | services,
            "services/amex-auth/main.py": {"sidecar:amex-auth", "sidecar:amex-auth:arm64"}
            | services,
            "services/revolut-auth/main.py": {"sidecar:revolut-auth", "sidecar:revolut-auth:arm64"}
            | services,
            "services/revolut-auth/Dockerfile": {"sidecar:revolut-auth", "sidecar:revolut-auth:arm64", "sidecar-base"}
            | services,
            "services/new-bank-auth/main.py": services,
            "services/base/requirements.txt": sidecars
            | {"backend", "sidecar-base"}
            | services,
            "services/base/picsou_sidecar/app.py": sidecars
            | {"backend", "sidecar-base"}
            | services,
            ".github/workflows/ci.yml": everything,
            ".github/tests/test_sidecar_workflows.py": everything,
            ".github/scripts/sidecars.py": everything,
        }
        for path, expected in cases.items():
            with self.subTest(path=path):
                self.assertEqual(_selected(jobs, path), expected)

    def test_each_sidecar_change_runs_only_its_own_sidecar(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        services = [entry["service"] for entry in _inventory()]
        self.assertEqual(services, sorted(path.parent.name for path in (ROOT / "services").glob("*-auth/Dockerfile")))
        for service in services:
            with self.subTest(service=service):
                legs = {job for job in _selected(jobs, f"services/{service}/test_main.py") if job.startswith("sidecar:")}
                arm = {f"sidecar:{service}:arm64"} if _sidecar_base(service) == "camoufox" else set()
                self.assertEqual(legs, {f"sidecar:{service}"} | arm)
        both = json.loads(_select_sidecars(["services/tr-auth/main.py", "services/cic-auth/main.py"])["matrix"])
        self.assertEqual([(leg["service"], leg["arch"]) for leg in both], [("cic-auth", "x86_64"), ("tr-auth", "x86_64")])
        mixed = _select_sidecars(["services/tr-auth/main.py", "services/base/requirements.txt"])
        self.assertEqual(json.loads(mixed["matrix"]), _legs())

    def test_a_new_connector_folder_joins_ci_and_publishing_without_a_workflow_change(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".github/scripts").mkdir(parents=True)
            (root / ".github/scripts/sidecars.py").write_text(SIDECARS_SCRIPT.read_text(encoding="utf-8"))
            for service, base in (("tr-auth", "chromium"), ("zz-bank-auth", "python")):
                (root / "services" / service).mkdir(parents=True)
                (root / "services" / service / "Dockerfile").write_text(
                    f"ARG SIDECAR_BASE=picsou-sidecar-{base}\nFROM ${{SIDECAR_BASE}}\n"
                )
            (root / "services/base").mkdir()
            (root / "services/base/Dockerfile").write_text("FROM scratch\n")
            script = [sys.executable, str(root / ".github/scripts/sidecars.py")]
            listed = json.loads(subprocess.run(script, capture_output=True, text=True, check=True).stdout)
            published = json.loads(subprocess.run(
                script + ["--publish"], capture_output=True, text=True, check=True,
                env={**os.environ, "GITHUB_REPOSITORY": "o/r"},
            ).stdout)
            (root / "services/broken-auth").mkdir()
            (root / "services/broken-auth/Dockerfile").write_text("FROM python:3.12\n")
            broken = subprocess.run(script, capture_output=True, text=True)

        self.assertEqual(listed, [
            {"service": "tr-auth", "base": "chromium"},
            {"service": "zz-bank-auth", "base": "python"},
        ])
        self.assertIn({
            "name": "zz-bank-auth", "image": "o/r/zz-bank-auth", "context": "services/zz-bank-auth",
            "dockerfile": "services/zz-bank-auth/Dockerfile", "base": "python", "versioned": False,
        }, published)
        self.assertNotEqual(broken.returncode, 0)
        self.assertIn("services/broken-auth/Dockerfile does not name a shared base", broken.stderr)

    def test_the_gate_waits_for_every_blocking_job_and_publishing_waits_for_the_gate(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        self.assertEqual(jobs[GATE]["if"], "always()")
        self.assertEqual(set(jobs[GATE]["needs"]), _gated(jobs) | {SELECTOR})
        self.assertIn(GATE, jobs[PUBLISH]["needs"])

    def test_helm_validation_does_not_block_ci_or_image_publishing(self):
        jobs = _load(CI_WORKFLOW)["jobs"]
        self.assertTrue(NON_BLOCKING <= _selectable(jobs))
        for job in (GATE, PUBLISH):
            with self.subTest(job=job):
                self.assertTrue(NON_BLOCKING.isdisjoint(jobs[job]["needs"]))

    def test_the_gate_is_green_only_when_every_selected_job_succeeded(self):
        jobs = _gated(_load(CI_WORKFLOW)["jobs"])
        frontend_only = {"frontend", "e2e", "release-deployment"}

        def needs(selected, **results):
            needs = {
                name: {"result": "success" if name in selected else "skipped", "outputs": {}}
                for name in jobs
            }
            outputs = {"all": "false", "jobs": json.dumps(sorted(selected))}
            needs[SELECTOR] = {"result": "success", "outputs": outputs}
            for name, result in results.items():
                needs[name]["result"] = result
            return needs

        selector_failed = {name: {"result": "skipped", "outputs": {}} for name in jobs}
        selector_failed[SELECTOR] = {"result": "failure", "outputs": {}}
        unfiltered = needs(set())
        unfiltered[SELECTOR]["outputs"] = {"all": "true", "jobs": "[]"}
        cases = {
            "everything selected and green": (needs(jobs), True),
            "push run (no filter), a job skipped": (unfiltered, False),
            "frontend change, other jobs skipped": (needs(frontend_only), True),
            "selected job skipped": (needs(frontend_only, e2e="skipped"), False),
            "selected job failed": (needs(frontend_only, e2e="failure"), False),
            "selected job cancelled": (needs(frontend_only, frontend="cancelled"), False),
            "unselected job failed": (needs(frontend_only, backend="failure"), False),
            "selector failed": (selector_failed, False),
            "sidecar change, matrix green": (needs({"sidecar", "compose-sidecar-build"}), True),
            "sidecar selected but the matrix skipped": (
                needs({"sidecar", "compose-sidecar-build"}, sidecar="skipped"), False,
            ),
            "one matrix leg failed": (needs({"sidecar"}, sidecar="failure"), False),
            "shared base selected but skipped": (needs({"sidecar-base"}, **{"sidecar-base": "skipped"}), False),
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
        self.assertTrue({"python", "chromium", "camoufox"} <= set(outputs))
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
        matrix = {
            entry["name"]: entry
            for entry in json.loads(_sidecars("--publish", env={"GITHUB_REPOSITORY": "o/r"}))
        }
        self.assertEqual(workflow["jobs"]["base"]["outputs"]["matrix"], "${{ steps.matrix.outputs.matrix }}")
        self.assertEqual(
            {matrix[service]["base"] for service in ("amex-auth", "revolut-auth")},
            {"camoufox"},
        )
        for service in _shared_base_services():
            with self.subTest(service=service):
                self.assertEqual(matrix[service].get("base"), _sidecar_base(service))

    def test_ci_builds_the_base_before_each_sidecar_image(self):
        for service in _shared_base_services():
            target = _sidecar_base(service)
            with self.subTest(service=service):
                commands = _sidecar_steps(service)
                base = f"docker build --target {target} --tag picsou-sidecar-{target} services/base"
                self.assertIn(base, commands)
                self.assertLess(commands.index(base), commands.index(f"--tag picsou-{service}:test"))

    def test_compose_builds_the_base_for_each_sidecar(self):
        for path in [DOCKER_COMPOSE]:
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
