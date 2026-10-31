"""Render and package regressions for chart health checks and database upgrades."""

import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest


CHART = Path(__file__).resolve().parents[1]


class ReviewRegressionsTest(unittest.TestCase):
    def render(self, *args, chart=CHART):
        return subprocess.run(
            ["helm", "template", "picsou", str(chart), *args],
            capture_output=True, text=True,
        )

    def resources(self, *args, chart=CHART):
        result = self.render(*args, chart=chart)
        self.assertEqual(result.returncode, 0, result.stderr)
        parsed = subprocess.run(
            ["yq", "-o=json", "-I=0", ".", "-"], input=result.stdout,
            check=True, capture_output=True, text=True,
        )
        return [json.loads(line) for line in parsed.stdout.splitlines() if line != "null"]

    def test_packaged_chart_keeps_executable_health_hook_and_excludes_local_tests(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["helm", "package", str(CHART), "--destination", tmp],
                           check=True, capture_output=True, text=True)
            package = next(Path(tmp).glob("*.tgz"))
            with tarfile.open(package) as archive:
                names = archive.getnames()
            self.assertIn("picsou/templates/tests/test-health.yaml", names)
            self.assertFalse(any(name.startswith(("picsou/tests/", "picsou/ci/")) for name in names))
            pod = next(r for r in self.resources(chart=package) if r["kind"] == "Pod")
            self.assertEqual(pod["metadata"]["annotations"]["helm.sh/hook"], "test")
            self.assertIn("http://picsou:8080/actuator/health", pod["spec"]["containers"][0]["command"])

    def test_external_database_without_password_source_fails_even_without_migration_job(self):
        result = self.render("--set", "postgresql.enabled=false",
                             "--set", "externalDatabase.host=pg-rw",
                             "--set", "migrationJob.enabled=false")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("external database requires", result.stderr)

    def test_external_database_password_sources_reach_app_and_migration(self):
        for setting, secret, key in (
            ("externalDatabase.existingSecret=db-password", "db-password", "password"),
            ("secrets.existingSecret=shared-password", "shared-password", "postgres-password"),
            ("secrets.postgresPassword=synthetic-password", "picsou", "postgres-password"),
        ):
            with self.subTest(setting=setting):
                resources = self.resources("--set", "postgresql.enabled=false",
                                           "--set", "externalDatabase.host=pg-rw",
                                           "--set", "externalDatabase.passwordKey=password",
                                           "--set", setting)
                workloads = [r for r in resources if r["kind"] in ("Deployment", "Job")
                             and r["metadata"]["name"] in ("picsou", "picsou-migrate")]
                self.assertEqual(len(workloads), 2)
                for workload in workloads:
                    env = workload["spec"]["template"]["spec"]["containers"][0]["env"]
                    variable = "SPRING_DATASOURCE_PASSWORD" if workload["kind"] == "Job" else "POSTGRES_PASSWORD"
                    password = next(e for e in env if e["name"] == variable)
                    self.assertEqual(password["valueFrom"]["secretKeyRef"], {"name": secret, "key": key})

    def database_pod(self, *args):
        return next(r["spec"]["template"] for r in self.resources(*args) if r["kind"] == "StatefulSet")

    def test_existing_postgresql_claim_can_use_compose_root_layout(self):
        for path in ("/var/lib/postgresql/data/pgdata", "/var/lib/postgresql/data"):
            with self.subTest(path=path):
                args = ["--set", "postgresql.persistence.existingClaim=imported-db"]
                if path == "/var/lib/postgresql/data":
                    args += ["--set", f"postgresql.pgdata={path}"]
                pod = self.database_pod(*args)["spec"]
                env = pod["containers"][0]["env"]
                self.assertEqual(next(e["value"] for e in env if e["name"] == "PGDATA"), path)
                self.assertEqual(pod["volumes"][0]["persistentVolumeClaim"]["claimName"], "imported-db")

    def test_app_secret_rotation_does_not_change_postgresql_pod(self):
        original = self.database_pod()
        for key in ("sidecarApiKey", "jwtSecret", "cryptoEncryptionKey", "postgresPassword"):
            with self.subTest(key=key):
                self.assertEqual(self.database_pod("--set", f"secrets.{key}=rotated-synthetic-value"), original)


if __name__ == "__main__":
    unittest.main()
