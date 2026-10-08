"""Opt-in smoke test of the real release image, PostgreSQL, and production Compose path."""
import json
import os
import socket
import subprocess
import uuid
from pathlib import Path

import pytest


REPO = Path(__file__).resolve().parents[2]
IMAGE = os.environ.get("PICSOU_RELEASE_TEST_IMAGE")
pytestmark = pytest.mark.skipif(not IMAGE, reason="Set PICSOU_RELEASE_TEST_IMAGE to a built release image")


def test_packaged_release_migrates_repeats_and_preserves_api_on_bad_schema(tmp_path):
    project = "picsou-release-test-" + uuid.uuid4().hex[:12]
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    origin = f"http://127.0.0.1:{port}"
    env_file = tmp_path / "smoke.env"
    # Synthetic credentials for this isolated disposable project only.
    env_file.write_text("APP_SIDECAR_API_KEY=release-test-only\nPOSTGRES_DB=picsou\n"
                        "POSTGRES_USER=picsou\nPOSTGRES_PASSWORD=release-test-only\n"
                        "APP_USERNAME=release-test-admin\n"
                        "APP_PASSWORD_HASH='$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZabc'\n"
                        "APP_STARTUP_SYNC_ENABLED=false\n")
    env_file.chmod(0o600)
    override = tmp_path / "smoke.yml"
    override.write_text(f"""services:
  app:
    env_file: !override
      - {json.dumps(str(env_file))}
    ports: !override
      - "127.0.0.1:{port}:8080"
    volumes: !override
      - picsou_data:/data
    environment:
      APP_STARTUP_SYNC_ENABLED: "false"
      SPRING_FLYWAY_ENABLED: "true"
""")
    flags = ["-p", project, "--env-file", str(env_file), "-f", str(override)]
    compose = ["docker", "compose", "--project-directory", str(REPO / "docker"),
               "-f", str(REPO / "docker/docker-compose.yml"), *flags]
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("POSTGRES_", "SPRING_DATASOURCE_", "DEPLOY_", "PICSOU_DEPLOY_"))}
    environment["APP_SIDECAR_API_KEY"] = "release-test-only"

    def command(args, *, text=None, check=True, timeout=180):
        result = subprocess.run(args, input=text, text=True, capture_output=True,
                                env=environment, timeout=timeout)
        if check:
            assert result.returncode == 0, (args, result.stdout, result.stderr)
        return result

    def sql(statement):
        return command([*compose, "exec", "-T", "db", "psql", "--username=picsou",
                        "--dbname=picsou", "-v", "ON_ERROR_STOP=1", "-At"], text=statement).stdout.strip()

    def app_id():
        return command([*compose, "ps", "-q", "app"]).stdout.strip()

    def probe():
        return command(["curl", "--silent", "--show-error", "--max-time", "5", "--output",
                        "/dev/null", "--write-out", "%{http_code}", origin + "/api/auth/me"]).stdout

    backup_dir = tmp_path / "backups"
    try:
        image_id = command(["docker", "image", "inspect", "--format", "{{.Id}}", IMAGE]).stdout.strip()
        deploy = ["bash", str(REPO / "docker/deploy.sh"), image_id, origin, str(backup_dir), *flags]
        first = command(deploy)
        assert "API returned 401" in first.stdout
        assert probe() == "401"
        container = app_id()
        assert container
        actual_image = command(["docker", "inspect", "--format", "{{.Image}}", container]).stdout.strip()
        assert actual_image == image_id
        history = sql("SELECT count(*) FROM flyway_schema_history WHERE success;")
        assert int(history) > 100
        assert sql("SELECT 'CREDIT_CARD' = ANY(enum_range(NULL::account_type)::text[]);") == "t"
        sql("CREATE TABLE deploy_test_marker (id integer PRIMARY KEY, value text);"
            " INSERT INTO deploy_test_marker VALUES (1, 'preserved');")
        command(deploy)
        assert sql("SELECT count(*) FROM flyway_schema_history WHERE success;") == history
        assert sql("SELECT value FROM deploy_test_marker WHERE id = 1;") == "preserved"
        assert probe() == "401"

        # Valid recorded history is insufficient: physical schema validation must fail on the clone.
        before = app_id()
        sql("ALTER TABLE account DROP COLUMN payment_due_amount;")
        incompatible = command(deploy, check=False)
        assert incompatible.returncode != 0
        assert app_id() == before
        assert probe() == "401"
        assert sql("SELECT count(*) FROM flyway_schema_history WHERE success;") == history
        sql("ALTER TABLE account ADD COLUMN payment_due_amount numeric(20,8);")

        # Unknown future history must also fail without replacing the running app.
        sql("INSERT INTO flyway_schema_history "
            "(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success) "
            "SELECT max(installed_rank) + 1, '999', 'incompatible', 'SQL', 'V999__incompatible.sql', "
            "1, current_user, 0, true FROM flyway_schema_history;")
        incompatible = command(deploy, check=False)
        assert incompatible.returncode != 0
        assert app_id() == before
        assert probe() == "401"
        assert sql("SELECT count(*) FROM pg_database WHERE datname LIKE 'picsou_deploy_%';") == "0"
        assert len(list(backup_dir.glob("picsou-*.dump"))) == 4
        assert not list(backup_dir.glob(".deploy-*"))
        print(json.dumps({"fresh_api": 401, "repeat_api": 401, "history_rows": int(history),
                          "data_preserved": True, "schema_failure_keeps_app": True,
                          "future_history_keeps_app": True, "retained_backups": 4}))
    finally:
        diagnostics = command([*compose, "logs", "--no-color", "app"], check=False)
        (tmp_path / "app.log").write_text(diagnostics.stdout + diagnostics.stderr)
        print("Synthetic application diagnostics:", tmp_path / "app.log")
        # Only this uniquely named synthetic project is destroyed, including its test volumes.
        cleanup = command([*compose, "down", "--volumes", "--remove-orphans"], check=False)
        assert cleanup.returncode == 0, cleanup.stderr
