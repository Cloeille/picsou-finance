#!/bin/sh
# Migrates one empty database with the Java jar and one with the Rust binary, compares
# pg_dump --schema-only and flyway_schema_history (checksums included), then starts the Java
# jar on the Rust-migrated database to show Flyway validates it.
#
#   JAR=path/to/picsou-backend.jar PGBIN=path/to/pg/bin tools/flyway-parity.sh
set -eu
: "${JAR:?the Java backend jar}"
: "${PGBIN:?the PostgreSQL 16 bin directory}"
PG_HOST=${PG_HOST:-127.0.0.1}
PG_PORT=${PG_PORT:-55432}
PG_USER=${PG_USER:-picsou}
JAVA=${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}/bin/java
RUST=${RUST:-$(cd "$(dirname "$0")/.." && pwd)/target/release/picsou}
WORK=$(mktemp -d)
JAVA_DB=parity_java_$$
RUST_DB=parity_rust_$$
JAVA_PID= RUST_PID= CHECK_PID=
psql() { "$PGBIN/psql" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" "$@"; }
wait_ready() { until curl -sf "http://127.0.0.1:$1/actuator/health/readiness" >/dev/null; do sleep 0.2; done; }
cleanup() {
    kill $JAVA_PID $RUST_PID $CHECK_PID 2>/dev/null || true
    sleep 1
    psql -d postgres -qc "DROP DATABASE IF EXISTS $JAVA_DB WITH (FORCE)" -c "DROP DATABASE IF EXISTS $RUST_DB WITH (FORCE)"
}
trap cleanup EXIT
psql -d postgres -qc "CREATE DATABASE $JAVA_DB" -c "CREATE DATABASE $RUST_DB"

run_java() { db=$1; port=$2; shift 2; start_cmd "$db" "$port" "$JAVA" -jar "$JAR"; }
start_cmd() {
    db=$1; port=$2; shift 2
    SPRING_DATASOURCE_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$db" SPRING_DATASOURCE_USERNAME="$PG_USER" \
        SPRING_DATASOURCE_PASSWORD=unused JWT_SECRET=parity-secret-0123456789-abcdefghijklmnopqrstuv \
        CRYPTO_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8= SERVER_PORT="$port" \
        APP_STARTUP_SYNC_ENABLED=false APP_UPDATE_CHECK_ENABLED=false "$@" >"$WORK/$db-$port.log" 2>&1 &
    LAST_PID=$!
}
run_java "$JAVA_DB" 18181; JAVA_PID=$LAST_PID
start_cmd "$RUST_DB" 18182 "$RUST"; RUST_PID=$LAST_PID
wait_ready 18181
wait_ready 18182
kill $JAVA_PID $RUST_PID; JAVA_PID= RUST_PID=

dump() { "$PGBIN/pg_dump" -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" --schema-only --no-owner "$1" | grep -v '^\\.*restrict'; }
history() {
    psql -d "$1" -Atc "SELECT installed_rank, version, description, type, script, checksum, installed_by, success FROM flyway_schema_history ORDER BY installed_rank"
}
dump "$JAVA_DB" >"$WORK/java.sql"
dump "$RUST_DB" >"$WORK/rust.sql"
history "$JAVA_DB" >"$WORK/java.history"
history "$RUST_DB" >"$WORK/rust.history"
diff "$WORK/java.sql" "$WORK/rust.sql" && echo "schema: identical ($(wc -l <"$WORK/java.sql") lines)"
diff "$WORK/java.history" "$WORK/rust.history" && echo "history: identical ($(wc -l <"$WORK/java.history") rows, checksums included)"

run_java "$RUST_DB" 18183; CHECK_PID=$LAST_PID
wait_ready 18183
grep -E 'Successfully validated|Schema "public" is up to date' "$WORK/$RUST_DB-18183.log"
