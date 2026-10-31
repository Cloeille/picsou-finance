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


@pytest.mark.parametrize("legacy_tls", [False, True], ids=["http", "legacy-tls-upgrade"])
def test_packaged_release_migrates_repeats_and_preserves_api_on_bad_schema(tmp_path, legacy_tls):
    project = "picsou-release-test-" + uuid.uuid4().hex[:12]
    with socket.socket() as port_socket:
        port_socket.bind(("127.0.0.1", 0))
        port = port_socket.getsockname()[1]
    origin = f"https://localhost:{port}" if legacy_tls else f"http://127.0.0.1:{port}"
    env_file = tmp_path / "smoke.env"
    # Synthetic credentials for this isolated disposable project only.
    env_file.write_text("APP_SIDECAR_API_KEY=release-test-only\nPOSTGRES_DB=picsou\n"
                        "POSTGRES_USER=picsou\nPOSTGRES_PASSWORD=release-test-only\n"
                        "APP_USERNAME=release-test-admin\n"
                        "APP_PASSWORD_HASH='$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZabc'\n"
                        "APP_STARTUP_SYNC_ENABLED=false\n")
    env_file.chmod(0o600)
    override = tmp_path / "smoke.yml"
    app_ports = [] if legacy_tls else [f"127.0.0.1:{port}:8080"]
    override.write_text(f"""services:
  app:
    env_file: !override
      - {json.dumps(str(env_file))}
    ports: !override {json.dumps(app_ports)}
    volumes: !override
      - picsou_data:/data
    environment:
      APP_STARTUP_SYNC_ENABLED: "false"
      SPRING_FLYWAY_ENABLED: "true"
""")
    flags = ["-p", project, "--env-file", str(env_file), "-f", str(override)]
    first_flags = flags
    if legacy_tls:
        caddyfile = tmp_path / "Caddyfile"
        current_caddyfile = (REPO / "docker/Caddyfile").read_text()
        caddyfile.write_text(current_caddyfile.replace("reverse_proxy app-tls:8080", "reverse_proxy app:8080"))
        with override.open("a") as out:
            out.write(f"""  proxy:
    environment:
      PICSOU_DOMAIN: "localhost"
    ports: !override
      - "127.0.0.1:{port}:443"
    volumes: !override
      - {json.dumps(str(caddyfile) + ':/etc/caddy/Caddyfile:ro')}
      - caddy_data:/data
      - caddy_config:/config
""")
        flags.extend(["--profile", "tls"])
        legacy = tmp_path / "legacy.yml"
        legacy.write_text("""services:
  app:
    networks: !override
      default:
      fortuneo-auth-net:
  proxy:
    networks: !override
      default:
""")
        first_flags = [*flags, "-f", str(legacy)]
    stray_flags = ["-p", project + "-stray", *flags[2:]]
    compose = ["docker", "compose", "--project-directory", str(REPO / "docker"),
               "-f", str(REPO / "docker/docker-compose.yml"), *flags]
    stray_compose = ["docker", "compose", "--project-directory", str(REPO / "docker"),
                     "-f", str(REPO / "docker/docker-compose.yml"), *stray_flags]
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("POSTGRES_", "SPRING_DATASOURCE_", "DEPLOY_", "PICSOU_DEPLOY_", "PICSOU_PROXY_"))}
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
        # Without --fresh, an unknown project must be refused before any container is created.
        stray = command(["bash", str(REPO / "docker/deploy.sh"), image_id, origin, str(backup_dir),
                         *stray_flags], check=False)
        assert stray.returncode != 0
        assert "COMPOSE_PROJECT_NAME" in stray.stderr
        assert command([*stray_compose, "ps", "-aq"]).stdout.strip() == ""
        assert not list(backup_dir.glob("picsou-*.dump"))
        if legacy_tls:
            legacy_compose = [*compose, "-f", str(legacy)]
            command([*legacy_compose, "up", "-d", "--no-deps", "--wait", "proxy"])
            ca_file = tmp_path / "caddy-root.crt"
            command([*legacy_compose, "cp", "proxy:/data/caddy/pki/authorities/local/root.crt", str(ca_file)])
            environment["CURL_CA_BUNDLE"] = str(ca_file)
        first = command(["bash", str(REPO / "docker/deploy.sh"), "--fresh", image_id, origin,
                         str(backup_dir), *first_flags])
        assert "API returned 401" in first.stdout
        assert probe() == "401"
        container = app_id()
        assert container
        actual_image = command(["docker", "inspect", "--format", "{{.Image}}", container]).stdout.strip()
        assert actual_image == image_id
        assert not list(backup_dir.glob("*.previous-image"))
        history = sql("SELECT count(*) FROM flyway_schema_history WHERE success;")
        assert int(history) > 100
        assert sql("SELECT 'CREDIT_CARD' = ANY(enum_range(NULL::account_type)::text[]);") == "t"
        sql("CREATE TABLE deploy_test_marker (id integer PRIMARY KEY, value text);"
            " INSERT INTO deploy_test_marker VALUES (1, 'preserved');")
        if legacy_tls:
            previous_proxy = command([*compose, "ps", "-q", "proxy"]).stdout.strip()
            network = f"{project}_tls-proxy"
            previous_details = json.loads(command(["docker", "inspect", previous_proxy]).stdout)[0]
            previous_networks = previous_details["NetworkSettings"]["Networks"]
            assert network not in previous_networks
            # Updating the bind mount leaves Caddy's in-memory config and networks unchanged.
            caddyfile.write_text(current_caddyfile)
        repeat = command(deploy)
        assert f"Previous app image: {image_id}" in repeat.stdout
        assert sql("SELECT count(*) FROM flyway_schema_history WHERE success;") == history
        assert sql("SELECT value FROM deploy_test_marker WHERE id = 1;") == "preserved"
        assert probe() == "401"
        if legacy_tls:
            upgraded_proxy = command([*compose, "ps", "-q", "proxy"]).stdout.strip()
            assert upgraded_proxy != previous_proxy
            proxy_details = json.loads(command(["docker", "inspect", upgraded_proxy]).stdout)[0]
            proxy_networks = proxy_details["NetworkSettings"]["Networks"]
            assert proxy_networks[network]["IPAddress"] == "172.30.253.2"
            active_config = command([*compose, "exec", "-T", "proxy", "wget", "-q", "-O", "-",
                                     "http://127.0.0.1:2019/config/"]).stdout
            assert '"dial":"app-tls:8080"' in active_config
            assert ca_file.read_bytes() == command(
                [*compose, "exec", "-T", "proxy", "cat", "/data/caddy/pki/authorities/local/root.crt"],
            ).stdout.encode()

        # Valid recorded history is insufficient: physical schema validation must fail on the clone.
        before = app_id()
        sql("ALTER TABLE account DROP COLUMN payment_due_amount;")
        incompatible = command(deploy, check=False)
        assert incompatible.returncode != 0
        assert app_id() == before
        if legacy_tls:
            assert command([*compose, "ps", "-q", "proxy"]).stdout.strip() == upgraded_proxy
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
        if legacy_tls:
            assert command([*compose, "ps", "-q", "proxy"]).stdout.strip() == upgraded_proxy
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
        if legacy_tls:
            diagnostics = command([*compose, "logs", "--no-color", "proxy"], check=False)
            (tmp_path / "proxy.log").write_text(diagnostics.stdout + diagnostics.stderr)
            print("Synthetic proxy diagnostics:", tmp_path / "proxy.log")
        # Only this uniquely named synthetic project is destroyed, including its test volumes.
        command([*stray_compose, "down", "--volumes", "--remove-orphans"], check=False)
        cleanup = command([*compose, "down", "--volumes", "--remove-orphans"], check=False)
        assert cleanup.returncode == 0, cleanup.stderr
