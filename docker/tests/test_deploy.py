import json
import os
import subprocess
from pathlib import Path

import pytest


REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "docker" / "deploy.sh"


@pytest.fixture
def fake_tools(tmp_path):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    log = tmp_path / "commands.log"
    docker = bindir / "docker"
    docker.write_text(r'''#!/usr/bin/env bash
{ printf 'docker'; printf ' <%s>' "$@"; printf '\n'; } >> "$MOCK_LOG"
args="$*"
if [[ "$args" == *" ps -aq db"* ]]; then [[ -n "${MOCK_NO_DB:-}" ]] || echo db-container; exit 0; fi
if [[ "$args" == *" ps -aq app"* ]]; then [[ -n "${MOCK_NO_APP:-}" ]] || echo old-app-container; exit 0; fi
if [[ "$args" == *" ps -aq proxy"* ]]; then printf '%s\n' "${MOCK_PROXY_CONTAINER:-}"; exit 0; fi
if [[ "$args" == *" ps -q db"* ]]; then echo db-container; exit 0; fi
if [[ "$args" == *" ps -q app"* ]]; then [[ -n "${MOCK_CUTOVER_NO_APP:-}" ]] || echo new-app-container; exit 0; fi
if [[ "$1" == inspect ]]; then
  [[ "$args" == *"com.docker.compose.project"* ]] && { echo picsou_prod; exit 0; }
  [[ "$args" == *"State.Running"* ]] && { printf '%s %s\n' "${MOCK_APP_RUNNING:-true}" "${MOCK_APP_IMAGE:-sha256:$(printf '%064d' 1)}"; exit 0; }
  [[ "${!#}" == old-app-container ]] && { printf 'sha256:%064d\n' 7; exit 0; }
  exit 1
fi
if [[ "$args" == *"pg_database_size"* ]]; then echo "${MOCK_DB_SIZE_KIB:-1024}"; exit 0; fi
if [[ "$args" == *"df -Pk"* ]]; then echo "${MOCK_DB_FREE_KIB:-10485760}"; exit 0; fi
if [[ "$args" == "image inspect sha256:"* && "$args" != *"--format"* && -n "${MOCK_ID_UNRUNNABLE:-}" ]]; then exit 1; fi
if [[ "$args" == *"pg_isready"* ]]; then
  file="$MOCK_LOG.ready"; n=0; [[ -f "$file" ]] && read -r n < "$file"
  n=$((n + 1)); printf '%s\n' "$n" > "$file"
  (( n >= ${MOCK_READY_AFTER:-1} )) || exit 1
fi
if [[ "$args" == *'printf "%s" "$POSTGRES_DB"'* ]]; then
  [[ "${MOCK_FAIL:-}" == database ]] && exit 19
  printf '%s' "${MOCK_DB_NAME:-picsou}"
fi
[[ "$args" == *'printf "%s" "$POSTGRES_USER"'* ]] && printf '%s' "${MOCK_DB_USER:-picsou}"
[[ "$args" == *'printf "%s" "$POSTGRES_PASSWORD"'* ]] && printf '%s' "${MOCK_DB_PASSWORD:-mock-password}"
if [[ "$args" == *"image inspect"* ]]; then printf 'sha256:%064d\n' 1; fi
if [[ "$args" == *"pg_dump"* && "${MOCK_FAIL:-}" == pg_dump ]]; then exit 19; fi
if [[ "$args" == *"pg_restore"* ]]; then
  [[ "${MOCK_FAIL:-}" == pg_restore ]] && exit 19
  if [[ "${MOCK_INTERRUPT:-}" == restore ]]; then kill -TERM "$PPID"; exit 0; fi
fi
if [[ "$args" == *"dropdb"* && "${MOCK_FAIL:-}" == cleanup ]]; then exit 18; fi
if [[ "$args" == *"ReleaseMigrationCli"* ]]; then
  override=; prev=
  for arg in "$@"; do [[ $prev == -f ]] && override=$arg; prev=$arg; done
  op=${!#}; db=picsou
  [[ -f "$override" && "$override" == *"picsou_deploy_"* ]] && db=clone
  [[ -f "$override" ]] && grep -q 'picsou_deploy_' "$override" && db=clone
  printf 'migration-target <%s> <%s>\n' "$db" "$op" >> "$MOCK_LOG"
  [[ -f "$override" ]] && cp "$override" "$MOCK_LOG.override"
  [[ "${MOCK_FAIL:-}" == candidate && $db == clone && $op == migrate ]] && exit 19
  [[ "${MOCK_FAIL:-}" == live && $db == picsou && $op == migrate ]] && exit 19
  [[ "${MOCK_FAIL:-}" == verify && $op == verify ]] && exit 19
fi
if [[ "$args" == *" up "* && "$args" == *" app"* ]]; then
  [[ "$args" == *"--pull never"* && "$args" == *"--no-build"* ]] || exit 21
  override=; prev=
  for arg in "$@"; do [[ $prev == -f ]] && override=$arg; prev=$arg; done
  [[ -f "$override" ]] && cp "$override" "$MOCK_LOG.cutover-override"
fi
if [[ "$args" == *" up "* && "$args" == *" proxy"* && "${MOCK_FAIL:-}" == proxy ]]; then exit 19; fi
exit 0
''')
    docker.chmod(0o755)
    curl = bindir / "curl"
    curl.write_text(r'''#!/usr/bin/env bash
{ printf 'curl'; printf ' <%s>' "$@"; printf '\\n'; } >> "$MOCK_LOG"
printf '%s\n' "${MOCK_HTTP_STATUS:-401}"
''')
    curl.chmod(0o755)
    sleep = bindir / "sleep"
    sleep.write_text("#!/usr/bin/env bash\nexit 0\n")
    sleep.chmod(0o755)
    return bindir, log


def run_deploy(fake_tools, tmp_path, *extra, fail=None, status="401", image="ghcr.io/picsou:stable",
               leading=(), **extra_env):
    bindir, log = fake_tools
    backup = tmp_path / "backups"
    backup.mkdir(mode=0o700, exist_ok=True)
    backup.chmod(0o700)
    env = os.environ.copy()
    env.update(PATH=f"{bindir}:{env['PATH']}", MOCK_LOG=str(log), MOCK_HTTP_STATUS=status)
    env.pop("POSTGRES_PASSWORD", None)
    if fail:
        env["MOCK_FAIL"] = fail
    env.update(extra_env)
    result = subprocess.run(
        ["bash", str(SCRIPT), *leading, image, "https://picsou.example", str(backup), *extra],
        text=True,
        capture_output=True,
        env=env,
        timeout=15,
    )
    lines = log.read_text().splitlines() if log.exists() else []
    return result, lines, backup


def test_candidate_and_live_migrations_precede_pinned_app_cutover(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, "--profile", "tls")
    assert result.returncode == 0, f"{result.stderr}\\n{commands}"
    cli_indices = [i for i, line in enumerate(commands) if "ReleaseMigrationCli" in line]
    targets = [line for line in commands if line.startswith("migration-target")]
    recreate = next(i for i, line in enumerate(commands) if "<up>" in line and "<app>" in line)
    assert len(cli_indices) == 4
    assert [line.rsplit("<", 1)[-1].rstrip(">") for line in targets] == ["migrate", "verify", "migrate", "verify"]
    assert "<clone>" in targets[0]
    assert "<picsou>" in targets[2]
    assert cli_indices[-1] < recreate
    assert any("<image> <inspect>" in line for line in commands)
    assert "--profile" in "\\n".join(commands)
    assert any("/api/auth/me" in line for line in commands)
    database_start = next(line for line in commands if "<up>" in line and "<db>" in line)
    assert "<--no-recreate>" in database_start


@pytest.mark.parametrize("flags", [("--profile", "tls"), ()], ids=["cli-profile", "env-profile"])
def test_existing_tls_proxy_is_recreated_after_app_cutover_before_probe(fake_tools, tmp_path, flags):
    result, commands, _ = run_deploy(
        fake_tools, tmp_path, *flags, MOCK_PROXY_CONTAINER="old-proxy-container",
        COMPOSE_PROFILES="" if flags else "tls",
    )
    assert result.returncode == 0, result.stderr
    app_check = next(i for i, line in enumerate(commands) if "State.Running" in line)
    proxy_recreate = next(i for i, line in enumerate(commands) if "<up>" in line and "<proxy>" in line)
    probe = next(i for i, line in enumerate(commands) if line.startswith("curl"))
    assert app_check < proxy_recreate < probe
    assert "<--force-recreate>" in commands[proxy_recreate]
    assert "<--no-deps>" in commands[proxy_recreate]
    assert "<app>" not in commands[proxy_recreate]


def test_absent_tls_proxy_is_not_started(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path)
    assert result.returncode == 0, result.stderr
    assert not any("<up>" in line and "<proxy>" in line for line in commands)


def test_tls_proxy_recreation_failure_does_not_report_success(fake_tools, tmp_path):
    result, commands, backup = run_deploy(
        fake_tools, tmp_path, fail="proxy", MOCK_PROXY_CONTAINER="old-proxy-container",
    )
    assert result.returncode != 0
    assert not any(line.startswith("curl") for line in commands)
    assert list(backup.glob("picsou-*.dump"))
    assert any("dropdb" in line for line in commands)
    assert not list(backup.glob(".deploy-*"))


@pytest.mark.parametrize("failure", ["pg_dump", "pg_restore", "candidate", "verify", "live"])
def test_preflight_or_migration_failure_never_recreates_app(fake_tools, tmp_path, failure):
    result, commands, backup = run_deploy(
        fake_tools, tmp_path, fail=failure, MOCK_PROXY_CONTAINER="old-proxy-container",
    )
    assert result.returncode != 0
    assert not any("<up>" in line and "<app>" in line for line in commands)
    assert not any("<up>" in line and "<proxy>" in line for line in commands)
    if failure in {"pg_restore", "candidate", "verify", "live"}:
        assert any("dropdb" in line for line in commands)
        assert list(backup.glob("picsou-*.dump"))


def test_probe_requires_exact_401_and_bounds_request(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, status="200")
    assert result.returncode != 0
    probe = next(line for line in commands if "api/auth/me" in line)
    assert "--max-time" in probe


def test_cli_is_pinned_and_does_not_use_bootstrap_entrypoint(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path)
    assert result.returncode == 0, f"{result.stderr}\\n{commands}"
    calls = [line for line in commands if "ReleaseMigrationCli" in line]
    assert len(calls) == 4
    assert all("--entrypoint> <java>" in line for line in calls)
    assert all("mock-password" not in line for line in calls)
    assert all("--no-deps" in line for line in calls)
    assert not any("mock-password" in line for line in commands)
    assert "mock-password" not in result.stdout
    assert "mock-password" not in result.stderr
    recreate = next(line for line in commands if "<up>" in line and "<app>" in line)
    assert "--no-deps" in recreate
    assert "--pull" in recreate and "<never>" in recreate and "--no-build" in recreate


def test_database_readiness_retries_before_dump(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, MOCK_READY_AFTER="3")
    assert result.returncode == 0, result.stderr
    ready = [i for i, line in enumerate(commands) if "<pg_isready>" in line]
    dump = next(i for i, line in enumerate(commands) if "pg_dump" in line)
    assert len(ready) == 3 and ready[-1] < dump


def test_database_config_failure_is_not_masked(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, fail="database")
    assert result.returncode != 0
    assert not any("<pg_dump>" in line for line in commands)


def test_local_sha256_image_is_inspected_without_pull(fake_tools, tmp_path):
    image = "sha256:" + "1" * 64
    result, commands, _ = run_deploy(fake_tools, tmp_path, image=image)
    assert result.returncode == 0, result.stderr
    assert any("<image> <inspect>" in line for line in commands)
    assert not any("<pull>" in line for line in commands)


def test_malformed_sha256_image_is_rejected_before_compose(fake_tools, tmp_path):
    bindir, log = fake_tools
    result = subprocess.run(
        ["bash", str(SCRIPT), "sha256:bad", "https://picsou.example", str(tmp_path / "backups")],
        capture_output=True,
        text=True,
        env={**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}", "MOCK_LOG": str(log)},
    )
    assert result.returncode == 2
    assert not log.exists()


def test_extra_flags_precede_last_candidate_overlay(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, "--profile", "tls", "--ansi", "never", "-f", "/operator.yml")
    assert result.returncode == 0, result.stderr
    call = next(line for line in commands if "ReleaseMigrationCli" in line)
    assert call.index("<--profile>") < call.rindex("<-f>")
    assert ".deploy-" in call.rsplit("<-f>", 1)[-1]
    assert call.index("docker-compose.yml>") < call.index("</operator.yml>") < call.rindex("<-f>")


def test_inherited_postgres_environment_cannot_override_running_db(fake_tools, tmp_path):
    result, commands, _ = run_deploy(
        fake_tools, tmp_path, POSTGRES_DB="shell-db", POSTGRES_USER="shell-user",
        POSTGRES_PASSWORD="shell-password",
    )
    assert result.returncode == 0, result.stderr
    joined = "\n".join(commands)
    assert "shell-password" not in joined
    assert "shell-db" not in joined and "shell-user" not in joined


def test_special_password_is_encoded_in_both_cutover_fields(fake_tools, tmp_path):
    password = "dollar$ single' slash\\tail"
    result, _, _ = run_deploy(fake_tools, tmp_path, MOCK_DB_PASSWORD=password)
    assert result.returncode == 0, result.stderr
    override = Path(str(fake_tools[1]) + ".cutover-override").read_text()
    scalar = json.dumps(password.replace("$", "$$"))
    assert f"SPRING_DATASOURCE_PASSWORD: {scalar}" in override
    assert f"POSTGRES_PASSWORD: {scalar}" in override


def test_interrupted_restore_cleans_up_clone_and_work_directory(fake_tools, tmp_path):
    result, commands, backup = run_deploy(fake_tools, tmp_path, MOCK_INTERRUPT="restore")
    assert result.returncode == 143
    assert any("dropdb" in line for line in commands)
    assert not list(backup.glob(".deploy-*"))


def test_clone_cleanup_failure_changes_success_to_failure(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, fail="cleanup")
    assert result.returncode != 0
    assert any("dropdb" in line for line in commands)


def test_backup_is_retained_and_temp_artifacts_are_removed(fake_tools, tmp_path):
    result, commands, backup = run_deploy(fake_tools, tmp_path)
    assert result.returncode == 0, f"{result.stderr}\\n{commands}"
    assert list(backup.glob("picsou-*.dump"))
    assert not list(backup.glob(".deploy-*"))
    assert backup.stat().st_mode & 0o777 == 0o700
    assert (backup / ".deploy.lock").stat().st_mode & 0o777 == 0o600
    assert list(backup.glob("picsou-*.dump"))[0].stat().st_mode & 0o777 == 0o600


@pytest.mark.parametrize("url", [
    "file:///etc/passwd", "https://user:secret-marker@picsou.example",
    "https://user@picsou.example", "https://picsou.example?token=secret-marker",
    "https://picsou.example#secret-marker", "https://picsou.example:invalid",
])
def test_invalid_probe_base_url_fails_before_any_compose_command(fake_tools, tmp_path, url):
    bindir, log = fake_tools
    backup = tmp_path / "backups"
    result = subprocess.run(
        ["bash", str(SCRIPT), "image", url, str(backup)],
        capture_output=True,
        text=True,
        env={**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}", "MOCK_LOG": str(log)},
    )
    assert result.returncode != 0
    assert not log.exists()
    assert "secret-marker" not in result.stdout + result.stderr


def test_probe_disables_curlrc_credentials_and_redacts_failure_url(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, status="200")
    assert result.returncode != 0
    probe = next(line for line in commands if line.startswith("curl"))
    assert probe.startswith("curl <--disable>")
    assert "https://picsou.example" not in result.stdout + result.stderr


def _app_started(commands):
    return any("<up>" in line and "<app>" in line for line in commands)


@pytest.mark.parametrize("missing", ["MOCK_NO_DB", "MOCK_NO_APP"])
def test_missing_production_containers_refuse_before_backup(fake_tools, tmp_path, missing):
    result, commands, backup = run_deploy(fake_tools, tmp_path, **{missing: "1"})
    assert result.returncode != 0
    assert "COMPOSE_PROJECT_NAME" in result.stderr
    assert not any("<up>" in line for line in commands)
    assert not any("pg_dump" in line for line in commands)
    assert not list(backup.glob("picsou-*.dump"))


def test_fresh_flag_allows_first_installation(fake_tools, tmp_path):
    result, commands, backup = run_deploy(fake_tools, tmp_path, leading=("--fresh",),
                                          MOCK_NO_DB="1", MOCK_NO_APP="1")
    assert result.returncode == 0, f"{result.stderr}\n{commands}"
    assert _app_started(commands)
    assert not list(backup.glob("*.previous-image"))


def test_project_and_database_container_are_reported(fake_tools, tmp_path):
    result, _, _ = run_deploy(fake_tools, tmp_path)
    assert result.returncode == 0, result.stderr
    assert "Compose project: picsou_prod; database container: db-container" in result.stdout


def test_previous_app_image_is_recorded_before_any_migration(fake_tools, tmp_path):
    result, commands, backup = run_deploy(fake_tools, tmp_path)
    assert result.returncode == 0, result.stderr
    previous = list(backup.glob("picsou-*.previous-image"))
    assert len(previous) == 1
    assert previous[0].read_text().strip() == "sha256:" + "0" * 63 + "7"
    assert previous[0].stat().st_mode & 0o777 == 0o600
    assert previous[0].name.removesuffix(".previous-image") == list(backup.glob("picsou-*.dump"))[0].stem
    assert "Previous app image: sha256:" in result.stdout
    recorded = next(i for i, line in enumerate(commands) if "<old-app-container>" in line)
    first_cli = next(i for i, line in enumerate(commands) if "ReleaseMigrationCli" in line)
    assert recorded < first_cli


@pytest.mark.parametrize("knob", [
    {"MOCK_CUTOVER_NO_APP": "1"},
    {"MOCK_APP_IMAGE": "sha256:" + "9" * 64},
    {"MOCK_APP_RUNNING": "false"},
])
def test_cutover_must_run_candidate_before_probe_counts(fake_tools, tmp_path, knob):
    result, commands, _ = run_deploy(fake_tools, tmp_path, MOCK_PROXY_CONTAINER="old-proxy-container", **knob)
    assert result.returncode != 0
    assert "Deployment verified" not in result.stdout
    assert not any(line.startswith("curl") for line in commands)
    assert not any("<up>" in line and "<proxy>" in line for line in commands)


def test_unrunnable_candidate_id_fails_before_backup(fake_tools, tmp_path):
    result, commands, _ = run_deploy(fake_tools, tmp_path, MOCK_ID_UNRUNNABLE="1")
    assert result.returncode != 0
    assert "local image ID" in result.stderr
    assert not any("pg_dump" in line for line in commands)
    assert not _app_started(commands)


@pytest.mark.parametrize("knob", [{"MOCK_DB_SIZE_KIB": "99999999999"}, {"MOCK_DB_FREE_KIB": "1"}])
def test_insufficient_disk_space_fails_before_backup(fake_tools, tmp_path, knob):
    result, commands, _ = run_deploy(fake_tools, tmp_path, **knob)
    assert result.returncode != 0
    assert "Not enough free disk space" in result.stderr
    assert "mock-password" not in result.stderr
    assert not any("pg_dump" in line for line in commands)


@pytest.mark.parametrize("db_free_kib, ok", [
    # 1 GiB database: 2 x 1 GiB + 256 MiB = 2359296 KiB required.
    ("1048576", False),   # exactly the database size: accepted before the margin
    ("2359295", False),   # one KiB short of the margin
    ("2359296", True),    # exactly the margin
])
def test_disk_preflight_requires_a_safety_margin(fake_tools, tmp_path, db_free_kib, ok):
    # Pin the host side too, so the result does not depend on the runner's real free space.
    bindir, _ = fake_tools
    df = bindir / "df"
    df.write_text("#!/usr/bin/env bash\necho 'Filesystem 1024-blocks Used Available Capacity Mounted'\n"
                  "echo 'mock 99999999 0 2359296 0% /mock'\n")
    df.chmod(0o755)
    result, commands, _ = run_deploy(
        fake_tools, tmp_path, MOCK_DB_SIZE_KIB="1048576", MOCK_DB_FREE_KIB=db_free_kib)
    if ok:
        assert result.returncode == 0, result.stderr
        assert any("pg_dump" in line for line in commands)
    else:
        assert result.returncode != 0
        assert "Not enough free disk space" in result.stderr
        assert "2304 MiB required" in result.stderr
        assert not any("pg_dump" in line for line in commands)


def test_existing_shared_backup_directory_is_refused_and_left_unchanged(fake_tools, tmp_path):
    bindir, log = fake_tools
    shared = tmp_path / "shared"
    shared.mkdir()
    shared.chmod(0o755)
    result = subprocess.run(
        ["bash", str(SCRIPT), "image", "https://picsou.example", str(shared)],
        capture_output=True, text=True,
        env={**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}", "MOCK_LOG": str(log)},
    )
    assert result.returncode != 0
    assert "private subdirectory" in result.stderr
    assert shared.stat().st_mode & 0o777 == 0o755
    assert not log.exists()
    assert not (shared / ".deploy.lock").exists()


def test_missing_backup_directory_is_created_private(fake_tools, tmp_path):
    bindir, log = fake_tools
    target = tmp_path / "parent" / "picsou"
    env = {**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}", "MOCK_LOG": str(log)}
    env.pop("POSTGRES_PASSWORD", None)
    result = subprocess.run(["bash", str(SCRIPT), "image", "https://picsou.example", str(target)],
                            capture_output=True, text=True, env=env, timeout=15)
    assert result.returncode == 0, result.stderr
    assert target.stat().st_mode & 0o777 == 0o700
