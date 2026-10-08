#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
    printf 'Usage: %s IMAGE API_BASE_URL BACKUP_DIRECTORY [extra Compose flags ...]\n' "$0" >&2
    exit 2
}

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
mkdir -p -- "$backup_dir"
chmod 0700 -- "$backup_dir"
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

# Start the database before reading its authoritative runtime configuration.
docker_compose up -d --no-deps --no-recreate db
ready=0
for attempt in {1..30}; do
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
if [[ "$image" =~ ^sha256:[0-9a-fA-F]{64}$ ]]; then
    candidate_image=$image
else
    docker pull "$image" >/dev/null
fi
candidate_image=$(docker image inspect --format '{{.Id}}' "$image")
[[ "$candidate_image" =~ ^sha256:[0-9a-fA-F]{64}$ ]] || {
    echo 'Could not resolve the candidate to an immutable image ID.' >&2
    exit 1
}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_file="$backup_dir/picsou-${stamp}-$$.dump"
docker_compose exec -T db sh -ceu \
    'export PGPASSWORD="$POSTGRES_PASSWORD"; exec pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    > "$backup_file"
chmod 0600 "$backup_file"
backup_complete=1

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

probe_url="$api_base/api/auth/me"
for attempt in {1..20}; do
    status=$(curl --disable --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 3 "$probe_url" 2>/dev/null || true)
    [[ "$status" == 401 ]] && { echo 'Deployment verified: API returned 401.'; exit 0; }
    sleep 2
done
echo 'Deployment cut over, but the unauthenticated API probe did not return 401.' >&2
exit 1
