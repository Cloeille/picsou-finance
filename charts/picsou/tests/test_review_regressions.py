"""Render and package regressions for chart health checks and database upgrades."""

import base64
import json
import os
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
                    variable = "POSTGRES_PASSWORD"
                    password = next(e for e in env if e["name"] == variable)
                    self.assertEqual(password["valueFrom"]["secretKeyRef"], {"name": secret, "key": key})

    def configured_resources(self, values):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "values.json"
            path.write_text(json.dumps(values))
            return self.resources("-f", str(path))

    def test_migration_inherits_database_environment_mounts_labels_and_scheduling(self):
        app = {
            "env": {"SPRING_DATASOURCE_URL": "jdbc:postgresql://override/db",
                    "SPRING_DATASOURCE_USERNAME": "override-user", "POSTGRES_PASSWORD": "synthetic"},
            "extraEnv": [{"name": "SPRING_DATASOURCE_PASSWORD", "valueFrom": {
                "secretKeyRef": {"name": "override-db", "key": "password"}}}],
            "envFrom": [{"configMapRef": {"name": "database-options"}}],
            "podLabels": {"database-access": "allowed"},
            "extraVolumes": [{"name": "db-ca", "secret": {"secretName": "db-ca"}}],
            "extraVolumeMounts": [{"name": "db-ca", "mountPath": "/certs", "readOnly": True}],
            "nodeSelector": {"kubernetes.io/arch": "amd64"},
            "tolerations": [{"key": "private", "operator": "Exists"}],
            "affinity": {"nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {
                "nodeSelectorTerms": [{"matchExpressions": [{"key": "zone", "operator": "In",
                                                            "values": ["database"]}]}]}}},
        }
        resources = self.configured_resources({"app": app, "postgresql": {"enabled": False},
            "externalDatabase": {"host": "pg-rw", "existingSecret": "db-password",
                                 "jdbcParams": "sslmode=verify-full&sslrootcert=/certs/ca.crt"}})
        deployment = next(r["spec"]["template"] for r in resources
                          if r["kind"] == "Deployment" and r["metadata"]["name"] == "picsou")
        job = next(r["spec"]["template"] for r in resources if r["kind"] == "Job")
        for pod in (deployment, job):
            self.assertEqual(pod["metadata"]["labels"]["database-access"], "allowed")
            spec = pod["spec"]
            for key in ("nodeSelector", "tolerations", "affinity"):
                self.assertEqual(spec[key], app[key])
            container = spec["containers"][0]
            self.assertEqual(container["envFrom"], app["envFrom"])
            self.assertIn(app["extraVolumes"][0], spec["volumes"])
            self.assertIn(app["extraVolumeMounts"][0], container["volumeMounts"])
            env = {e["name"]: e for e in container["env"]}
            for key, value in app["env"].items():
                self.assertEqual(env[key]["value"], value)
            self.assertEqual(env["SPRING_DATASOURCE_PASSWORD"], app["extraEnv"][0])
        self.assertEqual(job["metadata"]["labels"]["app.kubernetes.io/component"], "migrate")
        self.assertFalse(any(v["name"] == "data" for v in job["spec"]["volumes"]))

    def test_database_tls_url_and_extra_env_overrides_reach_both_workloads(self):
        tls_url = "jdbc:postgresql://pg-rw:5432/picsou?sslmode=verify-full&sslrootcert=/certs/ca.crt"
        for override in (False, True):
            with self.subTest(override=override):
                app = {"extraVolumes": [{"name": "db-ca", "secret": {"secretName": "db-ca"}}],
                       "extraVolumeMounts": [{"name": "db-ca", "mountPath": "/certs"}]}
                expected_url = tls_url
                if override:
                    expected_url = "jdbc:postgresql://override/db"
                    app["env"] = None
                    app["extraEnv"] = [{"name": "SPRING_DATASOURCE_URL", "value": expected_url}]
                resources = self.configured_resources({"app": app, "postgresql": {"enabled": False},
                    "externalDatabase": {"host": "pg-rw", "existingSecret": "db-password",
                        "jdbcParams": "sslmode=verify-full&sslrootcert=/certs/ca.crt"}})
                workloads = [r for r in resources if r["kind"] in ("Deployment", "Job")
                             and r["metadata"]["name"] in ("picsou", "picsou-migrate")]
                self.assertEqual(len(workloads), 2)
                for workload in workloads:
                    pod = workload["spec"]["template"]["spec"]
                    container = pod["containers"][0]
                    urls = [e for e in container["env"] if e["name"] == "SPRING_DATASOURCE_URL"]
                    self.assertEqual(urls, [{"name": "SPRING_DATASOURCE_URL", "value": expected_url}])
                    self.assertIn(app["extraVolumes"][0], pod["volumes"])
                    self.assertIn(app["extraVolumeMounts"][0], container["volumeMounts"])

    def test_migration_password_fallback_matches_entrypoint(self):
        job = next(r for r in self.resources() if r["kind"] == "Job")
        script = job["spec"]["template"]["spec"]["containers"][0]["args"][0]
        with tempfile.TemporaryDirectory() as tmp:
            java = Path(tmp) / "java"
            java.write_text('#!/bin/sh\n[ "$SPRING_DATASOURCE_PASSWORD" = "$EXPECTED_PASSWORD" ]\n')
            java.chmod(0o755)
            for explicit in (False, True):
                env = {"PATH": tmp + os.pathsep + os.defpath,
                       "POSTGRES_PASSWORD": "synthetic-postgres",
                       "EXPECTED_PASSWORD": "synthetic-spring" if explicit else "synthetic-postgres"}
                if explicit:
                    env["SPRING_DATASOURCE_PASSWORD"] = "synthetic-spring"
                result = subprocess.run(["sh", "-c", script], env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_numeric_inline_secrets_render_as_strings(self):
        keys = {"sidecarApiKey": "sidecar-api-key", "postgresPassword": "postgres-password",
                "jwtSecret": "jwt-secret", "cryptoEncryptionKey": "crypto-encryption-key"}
        resources = self.resources(*[arg for key in keys for arg in
                                    ("--set", f"secrets.{key}=12345678")])
        secret = next(r for r in resources if r["kind"] == "Secret")
        for key in keys.values():
            self.assertEqual(base64.b64decode(secret["data"][key]), b"12345678")

    def test_health_test_inherits_registry_and_scheduling_settings(self):
        scheduling = {"nodeSelector": {"kubernetes.io/arch": "amd64"},
                      "tolerations": [{"key": "private", "operator": "Exists"}],
                      "affinity": {"nodeAffinity": {"preferredDuringSchedulingIgnoredDuringExecution": [
                          {"weight": 1, "preference": {"matchExpressions": [
                              {"key": "zone", "operator": "In", "values": ["database"]}]}}]}}}
        resources = self.configured_resources({"app": scheduling,
            "global": {"imagePullSecrets": [{"name": "mirror-key"}]},
            "tests": {"image": {"repository": "mirror.example/busybox", "tag": "1.37.0",
                                "pullPolicy": "Always"}}})
        pod = next(r["spec"] for r in resources if r["kind"] == "Pod")
        self.assertEqual(pod["imagePullSecrets"], [{"name": "mirror-key"}])
        for key, value in scheduling.items():
            self.assertEqual(pod[key], value)
        self.assertEqual(pod["containers"][0]["image"], "mirror.example/busybox:1.37.0")
        self.assertEqual(pod["containers"][0]["imagePullPolicy"], "Always")

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
