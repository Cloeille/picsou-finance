"""Render and package regressions for chart health checks and database upgrades."""

import base64
import json
import os
import shutil
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

    def test_bundled_database_rejects_stored_password_mismatch(self):
        stored = {"postgres-password": base64.b64encode(b"synthetic-old-password").decode()}
        with tempfile.TemporaryDirectory() as tmp:
            chart = Path(tmp) / "picsou"
            shutil.copytree(CHART, chart)
            template = chart / "templates/secret.yaml"
            template.write_text(template.read_text().replace(
                '(lookup "v1" "Secret" .Release.Namespace $name).data',
                '(' + json.dumps(json.dumps(stored)) + ' | fromJson)'))
            result = self.render("--set-string", "secrets.postgresPassword=synthetic-new-password",
                                 chart=chart)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ALTER ROLE", result.stderr)
            for password in ("", "synthetic-old-password"):
                with self.subTest(password=password):
                    resources = self.resources("--set-string", f"secrets.postgresPassword={password}",
                                               chart=chart)
                    secret = next(r for r in resources if r["kind"] == "Secret")
                    self.assertEqual(secret["data"]["postgres-password"], stored["postgres-password"])
            resources = self.resources("--set", "postgresql.enabled=false",
                "--set", "externalDatabase.host=pg-rw", "--set-string",
                "secrets.postgresPassword=synthetic-new-password", chart=chart)
            secret = next(r for r in resources if r["kind"] == "Secret")
            self.assertEqual(base64.b64decode(secret["data"]["postgres-password"]),
                             b"synthetic-new-password")

    def test_app_replica_boundary_allows_zero_and_one_only(self):
        for count in (0, 1):
            with self.subTest(count=count):
                resources = self.resources("--set", f"app.replicaCount={count}")
                app = next(r for r in resources if r["kind"] == "Deployment"
                           and r["metadata"]["name"] == "picsou")
                self.assertEqual(app["spec"]["replicas"], count)
        result = self.render("--set", "app.replicaCount=2")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ReadWriteOnce", result.stderr)

    def test_custom_labels_cannot_replace_app_or_migration_selectors(self):
        labels = {f"app.kubernetes.io/{key}": "custom" for key in ("name", "instance", "component")}
        labels["custom-label"] = "preserved"
        resources = self.configured_resources({"app": {"podLabels": labels}})
        for component, kind in (("app", "Deployment"), ("migrate", "Job")):
            workload = next(r for r in resources if r["kind"] == kind
                            and r["metadata"]["name"] == ("picsou" if component == "app" else "picsou-migrate"))
            pod_labels = workload["spec"]["template"]["metadata"]["labels"]
            self.assertEqual(pod_labels, {"app.kubernetes.io/name": "picsou",
                "app.kubernetes.io/instance": "picsou", "app.kubernetes.io/component": component,
                "custom-label": "preserved"})
            if component == "app":
                for key, value in workload["spec"]["selector"]["matchLabels"].items():
                    self.assertEqual(pod_labels[key], value)

    def test_app_has_readiness_without_default_startup_deadline(self):
        resources = self.resources()
        app = next(r for r in resources if r["kind"] == "Deployment" and r["metadata"]["name"] == "picsou")
        container = app["spec"]["template"]["spec"]["containers"][0]
        self.assertNotIn("startupProbe", container)
        self.assertEqual(container["readinessProbe"]["httpGet"]["path"], "/actuator/health")
        self.assertEqual(container["livenessProbe"]["tcpSocket"]["port"], "http")
        resources = self.configured_resources({"app": {"startupProbe": {"tcpSocket": {"port": "http"}}}})
        app = next(r for r in resources if r["kind"] == "Deployment" and r["metadata"]["name"] == "picsou")
        self.assertEqual(app["spec"]["template"]["spec"]["containers"][0]["startupProbe"],
                         {"tcpSocket": {"port": "http"}})

    def test_postgresql_startup_allows_ten_minutes_for_recovery(self):
        container = self.database_pod()["spec"]["containers"][0]
        self.assertIn("startupProbe", container)
        probe = container["startupProbe"]
        self.assertEqual(probe["exec"], container["readinessProbe"]["exec"])
        self.assertEqual(probe["periodSeconds"], 10)
        self.assertEqual(probe["failureThreshold"], 60)

    def test_switch_to_bundled_database_can_defer_migration_hook(self):
        resources = self.resources("--is-upgrade", "--set", "postgresql.enabled=true",
                                   "--set", "migrationJob.enabled=false")
        self.assertFalse(any(r["kind"] == "Job" for r in resources))
        self.assertTrue(any(r["kind"] == "StatefulSet" for r in resources))
        for path in (CHART / "README.md", CHART.parents[1] / "docs/features/helm-chart.md"):
            text = path.read_text()
            self.assertIn("--set postgresql.enabled=true --set migrationJob.enabled=false", text)
            self.assertIn("does not transfer existing data", text)
            self.assertIn("re-enable", text)

    def test_long_release_names_are_distinct_and_short_names_stay_stable(self):
        names = []
        for release in ("a" * 40 + "1", "a" * 40 + "2", "short"):
            result = subprocess.run(["helm", "template", release, str(CHART)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            parsed = subprocess.run(["yq", "-o=json", "-I=0", ".", "-"], input=result.stdout,
                                    check=True, capture_output=True, text=True)
            resources = [json.loads(line) for line in parsed.stdout.splitlines() if line != "null"]
            app = next(r for r in resources if r["kind"] == "Deployment"
                       and r["spec"]["template"]["spec"]["containers"][0]["name"] == "app")
            names.append(app["metadata"]["name"])
            for r in resources:
                self.assertLessEqual(len(r["metadata"]["name"]), 63)
        self.assertNotEqual(names[0], names[1])
        self.assertEqual(len(names[0]), 40)
        self.assertTrue(names[0].startswith("a" * 33 + "-"))
        self.assertEqual(names[2], "short-picsou")

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

    def test_null_and_empty_inline_secrets_use_generated_or_absent_defaults(self):
        keys = {"sidecarApiKey": "sidecar-api-key", "postgresPassword": "postgres-password",
                "jwtSecret": "jwt-secret", "cryptoEncryptionKey": "crypto-encryption-key"}
        for empty in ("null", '\"\"'):
            with self.subTest(value=empty):
                args = [arg for key in keys for arg in
                        ("--set-json", f"secrets.{key}={empty}")]
                secret = next(r for r in self.resources(*args) if r["kind"] == "Secret")
                for key in ("sidecar-api-key", "postgres-password"):
                    value = base64.b64decode(secret["data"][key])
                    self.assertTrue(value)
                    self.assertNotEqual(value, b"<nil>")
                self.assertNotIn("jwt-secret", secret["data"])
                self.assertNotIn("crypto-encryption-key", secret["data"])

    def test_null_and_empty_inline_secrets_preserve_stored_keys(self):
        keys = {"sidecarApiKey": "sidecar-api-key", "postgresPassword": "postgres-password",
                "jwtSecret": "jwt-secret", "cryptoEncryptionKey": "crypto-encryption-key"}
        stored = {key: base64.b64encode(f"stored-{key}".encode()).decode()
                  for key in keys.values()}
        with tempfile.TemporaryDirectory() as tmp:
            chart = Path(tmp) / "picsou"
            shutil.copytree(CHART, chart)
            template = chart / "templates/secret.yaml"
            # helm template has no cluster lookup; inject only its synthetic response.
            template.write_text(template.read_text().replace(
                '(lookup "v1" "Secret" .Release.Namespace $name).data',
                '(' + json.dumps(json.dumps(stored)) + ' | fromJson)'))
            for empty in ("null", '\"\"'):
                with self.subTest(value=empty):
                    args = [arg for key in keys for arg in
                            ("--set-json", f"secrets.{key}={empty}")]
                    secret = next(r for r in self.resources(*args, chart=chart)
                                  if r["kind"] == "Secret")
                    self.assertEqual(secret["data"], stored)

    def test_operator_environment_replaces_all_app_defaults_by_name(self):
        resources = self.resources("--set", "app.enableBanking.existingSecret=eb")
        original = next(r for r in resources if r["kind"] == "Deployment"
                        and r["metadata"]["name"] == "picsou")
        defaults = original["spec"]["template"]["spec"]["containers"][0]["env"]
        self.assertTrue({"JWT_SECRET", "TR_AUTH_URL", "ENABLEBANKING_PRIVATE_KEY_PATH"}
                        <= {entry["name"] for entry in defaults})
        for source in ("env", "extraEnv"):
            with self.subTest(source=source):
                if source == "env":
                    overrides = {entry["name"]: "synthetic-override" for entry in defaults}
                else:
                    overrides = [{"name": entry["name"], "valueFrom": {"secretKeyRef": {
                        "name": "operator-secret", "key": entry["name"]}}} for entry in defaults]
                resources = self.resources("--set", "app.enableBanking.existingSecret=eb",
                                           "--set-json", f"app.{source}=" + json.dumps(overrides))
                app = next(r for r in resources if r["kind"] == "Deployment"
                           and r["metadata"]["name"] == "picsou")
                env = app["spec"]["template"]["spec"]["containers"][0]["env"]
                self.assertEqual(len(env), len(defaults))
                self.assertEqual(len({entry["name"] for entry in env}), len(env))
                for entry in env:
                    if source == "env":
                        self.assertEqual(entry["value"], "synthetic-override")
                        self.assertNotIn("valueFrom", entry)
                    else:
                        self.assertIn(entry, overrides)

    def test_extra_env_wins_over_env_in_app_and_migration(self):
        resources = self.resources("--set-json", 'app.env={"JWT_SECRET":"map-value"}',
                                   "--set-json", 'app.extraEnv=[{"name":"JWT_SECRET",'
                                   '"value":"extra-value"}]')
        for resource in resources:
            if resource["kind"] == "Job":
                pod = resource["spec"]["template"]["spec"]
            elif resource["kind"] == "Deployment" and resource["metadata"]["name"] == "picsou":
                pod = resource["spec"]["template"]["spec"]
            else:
                continue
            entries = [e for e in pod["containers"][0]["env"] if e["name"] == "JWT_SECRET"]
            self.assertEqual(entries, [{"name": "JWT_SECRET", "value": "extra-value"}])

    def test_duplicate_extra_env_names_fail_with_an_actionable_message(self):
        result = self.render("--set-json", 'app.extraEnv=[{"name":"JWT_SECRET","value":"a"},'
                             '{"name":"JWT_SECRET","value":"b"}]')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("app.extraEnv contains duplicate name JWT_SECRET", result.stderr)

    def test_numeric_inline_secrets_render_as_strings(self):
        keys = {"sidecarApiKey": "sidecar-api-key", "postgresPassword": "postgres-password",
                "jwtSecret": "jwt-secret", "cryptoEncryptionKey": "crypto-encryption-key"}
        for value in ("12345678", "0", "false"):
            with self.subTest(value=value):
                resources = self.resources(*[arg for key in keys for arg in
                                             ("--set", f"secrets.{key}={value}")])
                secret = next(r for r in resources if r["kind"] == "Secret")
                for key in keys.values():
                    self.assertEqual(base64.b64decode(secret["data"][key]), value.encode())

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
