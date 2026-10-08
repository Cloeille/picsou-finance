import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ENTRYPOINT = Path(__file__).resolve().parents[1] / "entrypoint.sh"
COMPOSE_FILE = ENTRYPOINT.parent / "docker-compose.yml"


class EntrypointDatasourcePasswordTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.data_dir = self.root / "data"
        self.data_dir.mkdir()
        self.secrets_dir = self.data_dir / ".secrets"
        self.nginx_dir = self.root / "nginx" / "snippets"
        self.nginx_dir.mkdir(parents=True)
        self.result_file = self.root / "environment.json"
        self.supervisord_stub = self.root / "supervisord"
        self.supervisord_stub.write_text(
            "#!/usr/bin/env python3\n"
            "import json, os\n"
            "with open(os.environ['RESULT_FILE'], 'w') as result:\n"
            "    json.dump({key: os.environ.get(key) for key in ("
            "'POSTGRES_PASSWORD', 'SPRING_DATASOURCE_PASSWORD')}, result)\n",
            encoding="utf-8",
        )
        self.supervisord_stub.chmod(0o755)

        source = ENTRYPOINT.read_text(encoding="utf-8")
        replacements = (
            ('SECRETS_DIR="/data/.secrets"', f'SECRETS_DIR="{self.secrets_dir}"'),
            ('mkdir -p /etc/nginx/snippets', f'mkdir -p "{self.nginx_dir}"'),
            ('HSTS_SNIPPET="/etc/nginx/snippets/picsou-hsts.conf"', f'HSTS_SNIPPET="{self.nginx_dir}/picsou-hsts.conf"'),
            ('exec /usr/bin/supervisord -c /etc/supervisor/conf.d/picsou.conf', f'exec "{self.supervisord_stub}"'),
        )
        for original, replacement in replacements:
            self.assertEqual(source.count(original), 1, f"expected one sandbox replacement for {original!r}")
            source = source.replace(original, replacement, 1)
        self.entrypoint_script = source

    def run_entrypoint(self, **overrides):
        env = {
            "PATH": os.environ["PATH"],
            "RESULT_FILE": str(self.result_file),
            "HSTS_ENABLED": "false",
        }
        env.update(overrides)
        completed = subprocess.run(
            ["bash", "-s"],
            env=env,
            input=self.entrypoint_script,
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
        )
        return completed

    def captured_environment(self):
        return json.loads(self.result_file.read_text(encoding="utf-8"))

    @staticmethod
    def compose_password_for(service):
        compose = COMPOSE_FILE.read_text(encoding="utf-8")
        service_match = re.search(
            rf"(?ms)^  {re.escape(service)}:\n(.*?)(?=^  [a-z][a-z0-9-]*:\n|\Z)",
            compose,
        )
        if service_match is None:
            raise AssertionError(f"Compose service {service!r} not found")
        password_match = re.search(r"(?m)^      POSTGRES_PASSWORD: (.+)$", service_match.group(1))
        if password_match is None:
            raise AssertionError(f"POSTGRES_PASSWORD not set for Compose service {service!r}")
        return password_match.group(1)

    def test_compose_app_and_database_receive_the_same_password_expression(self):
        self.assertEqual(
            self.compose_password_for("app"),
            self.compose_password_for("db"),
        )

    def test_compose_password_fallback_covers_unset_and_empty_values(self):
        expression = self.compose_password_for("app")
        self.assertEqual(expression, "${POSTGRES_PASSWORD:-picsou}")
        for configured_password in (None, ""):
            resolved = configured_password or expression.partition(":-")[2].removesuffix("}")
            self.assertEqual(resolved, "picsou")

    def test_postgres_password_is_used_by_spring_when_no_override_exists(self):
        password = "p; $pecial'\"\\pass"
        result = self.run_entrypoint(POSTGRES_PASSWORD=password)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured_environment()["SPRING_DATASOURCE_PASSWORD"], password)
        self.assertNotIn(password, result.stdout + result.stderr)

    def test_nonempty_spring_password_override_is_preserved(self):
        result = self.run_entrypoint(
            POSTGRES_PASSWORD="postgres-special; $value",
            SPRING_DATASOURCE_PASSWORD="spring-override",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured_environment()["SPRING_DATASOURCE_PASSWORD"], "spring-override")

    def test_empty_spring_override_falls_back_to_postgres_password(self):
        password = "postgres-special; $value"
        result = self.run_entrypoint(
            POSTGRES_PASSWORD=password,
            SPRING_DATASOURCE_PASSWORD="",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured_environment()["SPRING_DATASOURCE_PASSWORD"], password)

    def test_persisted_postgres_password_is_used_by_spring(self):
        self.secrets_dir.mkdir()
        (self.secrets_dir / "postgres_password").write_text("fixture-postgres-password", encoding="utf-8")
        result = self.run_entrypoint()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured_environment()["SPRING_DATASOURCE_PASSWORD"], "fixture-postgres-password")

    def test_generated_postgres_password_is_stable_and_used_by_spring(self):
        first = self.run_entrypoint()
        self.assertEqual(first.returncode, 0, first.stderr)
        first_environment = self.captured_environment()
        second = self.run_entrypoint()
        self.assertEqual(second.returncode, 0, second.stderr)
        second_environment = self.captured_environment()

        self.assertTrue(first_environment["POSTGRES_PASSWORD"])
        self.assertEqual(first_environment["SPRING_DATASOURCE_PASSWORD"], first_environment["POSTGRES_PASSWORD"])
        self.assertEqual(second_environment["POSTGRES_PASSWORD"], first_environment["POSTGRES_PASSWORD"])
        self.assertEqual(second_environment["SPRING_DATASOURCE_PASSWORD"], first_environment["POSTGRES_PASSWORD"])

    def test_explicit_picsou_password_is_preserved(self):
        result = self.run_entrypoint(POSTGRES_PASSWORD="picsou")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.captured_environment()["SPRING_DATASOURCE_PASSWORD"], "picsou")


if __name__ == "__main__":
    unittest.main()
