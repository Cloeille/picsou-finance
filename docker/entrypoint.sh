#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────
# Picsou container entrypoint — "zero-config" secrets bootstrap.
#
# On first boot, generates the three secrets the app needs before Spring
# can even start (they're read at bean-construction time, so they cannot
# live in the DB):
#   • JWT_SECRET              → /data/.secrets/jwt_secret
#   • CRYPTO_ENCRYPTION_KEY   → /data/.secrets/crypto_key
#   • POSTGRES_PASSWORD       → /data/.secrets/postgres_password
#
# On subsequent boots, the files already exist on the /data volume, so we
# just re-export. A secret is **never** regenerated once created — doing
# so would invalidate every previously-issued JWT (users logged out) and
# every encrypted crypto API secret in the DB (permanent data loss).
#
# If the env var is already set (user-supplied via .env or an external
# secret manager), we respect it and skip generation entirely. That means
# existing installs upgrading to this image see zero behavior change.
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

SECRETS_DIR="/data/.secrets"
mkdir -p "$SECRETS_DIR"

# Tighten permissions, but do not abort if the filesystem cannot represent them.
# /data is frequently a bind mount on a NAS (SMB/CIFS, some overlay and FUSE
# filesystems) where chmod is simply unsupported — failing hard there would break
# exactly the self-hosted deployments this image targets. Warn instead, so the
# operator can see it and decide, rather than the mode silently not applying.
try_chmod() {  # $1 = mode, $2 = path
    chmod "$1" "$2" 2>/dev/null \
        || echo "[entrypoint] WARNING: could not chmod $1 $2 — the filesystem may not support it. Secrets may be readable by other users on the host." >&2
}

try_chmod 0700 "$SECRETS_DIR"

# $1 = file basename, $2 = env var name, $3 = openssl args (e.g. "rand -base64 48")
bootstrap_secret() {
    local file="$SECRETS_DIR/$1"
    local var_name="$2"
    local gen_args="$3"

    # 1. If the env var is already set by the operator, honor it and write
    #    it to the file for consistency across restarts.
    if [ -n "${!var_name:-}" ]; then
        if [ ! -f "$file" ]; then
            printf '%s' "${!var_name}" > "$file"
            try_chmod 0600 "$file"
        fi
        return 0
    fi

    # 2. Env var unset — use the file if present, else generate.
    if [ ! -f "$file" ]; then
        # shellcheck disable=SC2086
        openssl $gen_args > "$file"
        try_chmod 0600 "$file"
        echo "[entrypoint] generated $var_name at $file"
    fi

    # Read into a plain variable first, then export. `export VAR=$(cmd)` returns
    # export's own exit status, so a failing cat would be masked by `set -e` and
    # silently export an empty secret — an empty JWT_SECRET or encryption key
    # fails much later, and far less legibly, inside Spring.
    local value
    value="$(cat "$file")"
    if [ -z "$value" ]; then
        echo "[entrypoint] FATAL: $file is empty — refusing to start with a blank $var_name." >&2
        exit 1
    fi
    export "$var_name=$value"
}

bootstrap_secret "jwt_secret"        "JWT_SECRET"            "rand -base64 48"
bootstrap_secret "crypto_key"        "CRYPTO_ENCRYPTION_KEY" "rand -base64 32"
bootstrap_secret "postgres_password" "POSTGRES_PASSWORD"     "rand -base64 24"

# ── HSTS (opt-in) ────────────────────────────────────────────────────────
# nginx.conf includes this snippet; empty means the header is never sent.
#
# Opt-in rather than always-on because Picsou is commonly served with a
# locally-issued certificate (Caddy's internal CA via the `tls` compose
# profile, or mkcert). Sending HSTS with a certificate the browser does not
# trust is a one-way trap: the policy is remembered, the "proceed anyway"
# bypass disappears, and the user is locked out of their own app with no
# recovery short of clearing HSTS state in browser internals.
#
# Enable it only once the certificate is publicly trusted — i.e. the `tls`
# profile with a real domain (Let's Encrypt), or an upstream proxy holding a
# publicly-trusted cert.
mkdir -p /etc/nginx/snippets
HSTS_SNIPPET="/etc/nginx/snippets/picsou-hsts.conf"

# Normalize: strip *surrounding* whitespace only, then lowercase, so True / TRUE
# / "true " / 1 / yes are all accepted. Deliberately not stripping interior
# whitespace — "t rue" must fall through to the warning rather than silently
# enabling HSTS, which on an internal-CA deployment is the lockout this gate
# exists to prevent. The accepted set must match SecurityConfig.hstsEnabled().
hsts_raw="${HSTS_ENABLED:-false}"
hsts_trimmed="${hsts_raw#"${hsts_raw%%[![:space:]]*}"}"
hsts_trimmed="${hsts_trimmed%"${hsts_trimmed##*[![:space:]]}"}"

hsts_line=""
case "${hsts_trimmed,,}" in
    true|1|yes|on)
        hsts_line='add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;'
        echo "[entrypoint] HSTS enabled"
        ;;
    false|0|no|off|"")
        ;;
    *)
        echo "[entrypoint] WARNING: HSTS_ENABLED='$hsts_raw' not understood — HSTS disabled." >&2
        ;;
esac

# Single write point: empty file means the header is never sent.
if [ -n "$hsts_line" ]; then
    printf '%s\n' "$hsts_line" > "$HSTS_SNIPPET"
else
    : > "$HSTS_SNIPPET"
fi

# Defaults that never changed — keep backward compatibility with the old
# single-line entrypoint.
export SERVER_PORT=9090
export TR_AUTH_URL=${TR_AUTH_URL:-http://127.0.0.1:8001}
export REVOLUT_AUTH_URL=${REVOLUT_AUTH_URL:-http://127.0.0.1:8002}
export APP_STARTUP_SYNC_ENABLED=${APP_STARTUP_SYNC_ENABLED:-false}

exec /usr/bin/supervisord -c /etc/supervisor/conf.d/picsou.conf
