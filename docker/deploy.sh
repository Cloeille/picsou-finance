#!/usr/bin/env bash
# Single-quoted command strings are expanded by the shell inside the db container, on purpose.
# shellcheck disable=SC2016
set -Eeuo pipefail
umask 077

usage() {
    printf 'Usage: %s [--fresh] IMAGE API_BASE_URL BACKUP_DIRECTORY [extra Compose flags ...]\n' "$0" >&2
    exit 2
}

fresh=0
if [[ "${1:-}" == --fresh ]]; then
    fresh=1
    shift
fi
[[ $# -ge 3 ]] || usage
image=$1
if [[ "$image" == sha256:* && ! "$image" =~ ^sha256:[0-9a-fA-F]{64}$ ]]; then
    usage
fi
api_base=${2%/}
backup_dir=$3
shift 3
compose_flags=("$@")

[[ -n "$image" && "$image" != -* && "$image" != *[[:space:]]* ]] || usage
[[ "$api_base" =~ ^https?://[^[:space:]]+$ ]] || { echo 'Invalid API base URL.' >&2; exit 2; }
if ! API_BASE_URL_VALUE="$api_base" python3 -c 'import os, sys
from urllib.parse import urlsplit
try:
    parts = urlsplit(os.environ["API_BASE_URL_VALUE"])
    valid = (parts.scheme in ("http", "https") and bool(parts.hostname)
             and parts.username is None and parts.password is None
             and not parts.query and not parts.fragment and "%" not in parts.hostname)
    parts.port  # Reject malformed or out-of-range ports.
except ValueError:
    valid = False
sys.exit(0 if valid else 1)'; then
    echo 'API base URL must have no credentials, query, or fragment.' >&2
    exit 2
fi
[[ -n "$backup_dir" ]] || usage

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
compose_file="$script_dir/docker-compose.yml"
# Never change the mode of an existing directory: it may be shared (/var/backups, $HOME).
if [[ -e "$backup_dir" || -L "$backup_dir" ]]; then
    [[ -d "$backup_dir" ]] || { echo 'Backup path exists and is not a directory.' >&2; exit 2; }
    backup_mode=$(stat -L -c '%a' -- "$backup_dir")
    if (( 8#$backup_mode & 8#077 )); then
        printf 'Backup directory is accessible by group or others (mode %s). Pass a private subdirectory such as /var/backups/picsou; a missing one is created with mode 0700.\n' \
            "$backup_mode" >&2
        exit 2
    fi
else
    mkdir -p -- "$(dirname -- "$backup_dir")"
    mkdir -m 0700 -- "$backup_dir"
fi
backup_dir=$(cd -- "$backup_dir" && pwd)
exec 9>"$backup_dir/.deploy.lock"
flock -n 9 || { echo 'Another deployment is already running for this backup directory.' >&2; exit 1; }

work_dir=$(mktemp -d "$backup_dir/.deploy-XXXXXXXX")
clone_db="picsou_deploy_$(od -An -N8 -tx1 /dev/urandom | tr -d '[:space:]')"
clone_created=0
backup_file=
backup_complete=0
compose_base=(docker compose --project-directory "$script_dir" -f "$compose_file" "${compose_flags[@]}")
docker_compose() {
    env -u POSTGRES_DB -u POSTGRES_USER -u POSTGRES_PASSWORD \
        -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD \
        "${compose_base[@]}" "$@"
}
# Invoked through the EXIT trap: SC2329 (shellcheck >= 0.10) / SC2317 (older runners).
# shellcheck disable=SC2317,SC2329
cleanup() {
    local status=$?
    trap - EXIT INT TERM
    if (( clone_created )); then
        if ! docker_compose exec -T -e "DEPLOY_CLONE_DB=$clone_db" db sh -ceu \
            'export PGPASSWORD="$POSTGRES_PASSWORD"; dropdb --if-exists --force --username="$POSTGRES_USER" "$DEPLOY_CLONE_DB"' \
            >/dev/null 2>&1; then
            echo 'Warning: temporary clone database cleanup failed.' >&2
            (( status != 0 )) || status=1
        fi
    fi
    if [[ -n "$backup_file" ]] && (( ! backup_complete )); then
        rm -f -- "$backup_file"
    fi
    rm -rf -- "$work_dir"
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

single_container() {
    [[ -n "$1" && "$1" != *[[:space:]]* ]]
}

# Bind the run to the stack being upgraded. A forgotten -p or COMPOSE_PROJECT_NAME would
# otherwise create, back up and migrate an empty database in another Compose project.
existing_db=$(docker_compose ps -aq db)
existing_app=$(docker_compose ps -aq app)
running_proxy=$(docker_compose ps -q --status running proxy)
if (( ! fresh )) && ! { single_container "$existing_db" && single_container "$existing_app"; }; then
    echo 'No existing db and app container in the addressed Compose project.' >&2
    echo 'Pass the production project name (-p or COMPOSE_PROJECT_NAME) and the same Compose flags,' >&2
    echo 'or put --fresh first for a first installation.' >&2
    exit 1
fi

# Start the database before reading its authoritative runtime configuration.
docker_compose up -d --no-deps --no-recreate db
db_container=$(docker_compose ps -q db)
single_container "$db_container" || { echo 'Could not identify the running db container.' >&2; exit 1; }
project_name=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$db_container")
printf 'Compose project: %s; database container: %s\n' "$project_name" "$db_container"
ready=0
for _ in {1..30}; do
    if docker_compose exec -T db pg_isready -q; then
        ready=1
        break
    fi
    sleep 2
done
(( ready )) || { echo 'Database did not become ready within 60 seconds.' >&2; exit 1; }

if ! db_name=$(docker_compose exec -T db sh -ceu 'test -n "$POSTGRES_DB"; printf "%s" "$POSTGRES_DB"'); then
    echo 'Could not read database name from the running database service.' >&2
    exit 1
fi
if ! db_user=$(docker_compose exec -T db sh -ceu 'test -n "$POSTGRES_USER"; printf "%s" "$POSTGRES_USER"'); then
    echo 'Could not read database user from the running database service.' >&2
    exit 1
fi
if ! db_password=$(docker_compose exec -T db sh -ceu 'test -n "$POSTGRES_PASSWORD"; printf "%s" "$POSTGRES_PASSWORD"'); then
    echo 'Could not read database password from the running database service.' >&2
    exit 1
fi
[[ -n "$db_name" && -n "$db_user" && -n "$db_password" ]] || {
    echo 'Database configuration from the running database service is incomplete.' >&2
    exit 1
}

# Resolve mutable references once, then pin every later Compose invocation.
if [[ ! "$image" =~ ^sha256:[0-9a-fA-F]{64}$ ]]; then
    docker pull "$image" >/dev/null
fi
candidate_image=$(docker image inspect --format '{{.Id}}' "$image")
[[ "$candidate_image" =~ ^sha256:[0-9a-fA-F]{64}$ ]] || {
    echo 'Could not resolve the candidate to an immutable image ID.' >&2
    exit 1
}
# The containerd image store can report an index digest that Compose cannot run.
if ! docker image inspect "$candidate_image" >/dev/null 2>&1; then
    echo 'The resolved candidate ID is not a runnable local image. Pass the local image ID (sha256:...).' >&2
    exit 1
fi

# Measure the database and the free space for the dump (host) and the restored clone (PGDATA).
db_size_kib=$(docker_compose exec -T db sh -ceu \
    'export PGPASSWORD="$POSTGRES_PASSWORD"; psql -At -v ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT pg_database_size(current_database()) / 1024"')
db_free_kib=$(docker_compose exec -T db sh -ceu \
    'df -Pk "${PGDATA:-/var/lib/postgresql/data}" | tail -n 1 | tr -s " " | cut -d " " -f 4')
host_free_kib=$(df -Pk -- "$backup_dir" | tail -n 1 | tr -s ' ' | cut -d ' ' -f 4)
for value in "$db_size_kib" "$db_free_kib" "$host_free_kib"; do
    [[ "$value" =~ ^[0-9]+$ ]] || { echo 'Could not measure database size or free disk space.' >&2; exit 1; }
done
# Require twice the database size plus 256 MiB on each side: migrations rewrite tables and
# write WAL in the clone, and a dump is not guaranteed to be smaller than the database.
required_kib=$(( db_size_kib * 2 + 262144 ))
if (( host_free_kib < required_kib || db_free_kib < required_kib )); then
    printf 'Not enough free disk space: database %d MiB, %d MiB required on each side, free %d MiB for the backup, %d MiB for the clone.\n' \
        $(( db_size_kib / 1024 )) $(( required_kib / 1024 )) $(( host_free_kib / 1024 )) $(( db_free_kib / 1024 )) >&2
    exit 1
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file="$backup_dir/picsou-${stamp}-$$.dump"
docker_compose exec -T db sh -ceu \
    'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    > "$backup_file"
chmod 0600 "$backup_file"
backup_complete=1
printf 'Backup retained: %s\n' "$backup_file"

# Record the rollback input before anything changes.
if single_container "$existing_app"; then
    previous_image=$(docker inspect --format '{{.Image}}' "$existing_app")
    previous_file="$backup_dir/picsou-${stamp}-$$.previous-image"
    printf '%s\n' "$previous_image" > "$previous_file"
    chmod 0600 "$previous_file"
    printf 'Previous app image: %s\n' "$previous_image"
fi

# Emit literal YAML scalars. JSON strings are valid YAML; doubling dollars
# prevents Compose interpolation while preserving the actual database password.
write_override() {
    local database=$1
    DEPLOY_DB_VALUE="$database" DEPLOY_USER_VALUE="$db_user" \
        DEPLOY_PASSWORD_VALUE="$db_password" DEPLOY_IMAGE_VALUE="$candidate_image" \
        OVERRIDE_PATH="$work_dir/override.yml" python3 -c 'import json, os

def scalar(name):
    return json.dumps(os.environ[name].replace("$", "$$"))

with open(os.environ["OVERRIDE_PATH"], "w", encoding="utf-8") as out:
    out.write("services:\n  app:\n    image: " + scalar("DEPLOY_IMAGE_VALUE") + "\n    environment:\n")
    out.write("      SPRING_DATASOURCE_URL: " + json.dumps("jdbc:postgresql://db:5432/" + os.environ["DEPLOY_DB_VALUE"].replace("$", "$$") + "?sslmode=prefer") + "\n")
    out.write("      SPRING_DATASOURCE_USERNAME: " + scalar("DEPLOY_USER_VALUE") + "\n")
    out.write("      SPRING_DATASOURCE_PASSWORD: " + scalar("DEPLOY_PASSWORD_VALUE") + "\n")
    out.write("      POSTGRES_PASSWORD: " + scalar("DEPLOY_PASSWORD_VALUE") + "\n")'
    chmod 0600 "$work_dir/override.yml"
}
write_override "$db_name"
compose_pinned=(docker compose --project-directory "$script_dir" -f "$compose_file" "${compose_flags[@]}" -f "$work_dir/override.yml")
docker_pinned() {
    env -u POSTGRES_DB -u POSTGRES_USER -u POSTGRES_PASSWORD \
        -u SPRING_DATASOURCE_URL -u SPRING_DATASOURCE_USERNAME -u SPRING_DATASOURCE_PASSWORD \
        "${compose_pinned[@]}" "$@"
}
run_migration_cli() {
    local operation=$1
    docker_pinned run --pull never --rm --no-deps --entrypoint java app \
        -Dloader.main=com.picsou.migration.ReleaseMigrationCli \
        -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$operation"
}

# Rehearse against a disposable clone restored from the complete custom dump.
docker_compose exec -T -e "DEPLOY_CLONE_DB=$clone_db" db sh -ceu \
    'export PGPASSWORD="$POSTGRES_PASSWORD"; createdb --username="$POSTGRES_USER" --template=template0 "$DEPLOY_CLONE_DB"'
clone_created=1
docker_compose exec -T -e "DEPLOY_CLONE_DB=$clone_db" db sh -ceu \
    'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_restore --exit-on-error --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$DEPLOY_CLONE_DB"' \
    < "$backup_file"
write_override "$clone_db"
run_migration_cli migrate
run_migration_cli verify

# Change the live schema only after clone rehearsal succeeds.
write_override "$db_name"
run_migration_cli migrate
run_migration_cli verify

docker_pinned up -d --no-deps --pull never --no-build app

# The probe URL may reach another stack: first prove this project now runs the candidate.
app_container=$(docker_pinned ps -q app)
single_container "$app_container" || { echo 'Cutover did not leave exactly one running app container.' >&2; exit 1; }
app_state=$(docker inspect --format '{{.State.Running}} {{.Image}}' "$app_container")
if [[ "$app_state" != "true $candidate_image" ]]; then
    echo 'The app container is not running the candidate image after cutover.' >&2
    exit 1
fi

# A restart reloads the Caddyfile but leaves old Compose network attachments intact.
if [[ -n "$running_proxy" ]]; then
    if ! docker_compose up -d --no-deps --force-recreate --wait --wait-timeout 60 proxy; then
        echo 'Deployment cut over, but TLS proxy recreation or health check failed (60-second wait limit).' >&2
        exit 1
    fi
    # Health only proves the admin API responds, not that app traffic uses the trusted bridge.
    if ! docker_compose exec -T proxy wget -qO- http://127.0.0.1:2019/config/ > "$work_dir/proxy-config.json"; then
        echo 'Deployment cut over, but TLS proxy active config could not be read from the admin API.' >&2
        exit 1
    fi
    if ! python3 - "$work_dir/proxy-config.json" <<'PYCONFIG'
import json
import sys


def upstream_dials(node):
    if isinstance(node, dict):
        if node.get("handler") == "reverse_proxy":
            for upstream in node.get("upstreams", []):
                yield upstream.get("dial", "")
        for value in node.values():
            yield from upstream_dials(value)
    elif isinstance(node, list):
        for value in node:
            yield from upstream_dials(value)


try:
    with open(sys.argv[1]) as config:
        dials = list(upstream_dials(json.load(config)))
    # Other sites may proxy unrelated hosts; only Picsou service names constrain this gate.
    valid = "app-tls:8080" in dials and all(
        dial == "app-tls:8080" or dial.split(":", 1)[0] not in {"app", "app-tls"}
        for dial in dials
    )
except (OSError, ValueError, TypeError, AttributeError):
    valid = False
sys.exit(0 if valid else 1)
PYCONFIG
    then
        echo "Deployment cut over, but TLS proxy active config is invalid; set the Caddyfile's reverse_proxy target to app-tls:8080 (remove other app targets)." >&2
        exit 1
    fi
fi

probe_url="$api_base/api/auth/me"
for _ in {1..20}; do
    status=$(curl --disable --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 3 "$probe_url" 2>/dev/null || true)
    [[ "$status" == 401 ]] && { echo 'Deployment verified: API returned 401.'; exit 0; }
    sleep 2
done
echo 'Deployment cut over, but the unauthenticated API probe did not return 401.' >&2
exit 1
