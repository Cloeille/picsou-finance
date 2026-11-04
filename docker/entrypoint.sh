#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────
# Picsou container entrypoint.
#
# Secrets (JWT_SECRET, CRYPTO_ENCRYPTION_KEY, POSTGRES_PASSWORD) are no
# longer handled here: the Spring Boot app resolves and persists them under
# PICSOU_SECRETS_DIR (/data/.secrets in the image) before any bean exists,
# generating the first two only. See SecretBootstrap.
#
# This script still:
#   • writes the nginx trusted-proxy snippet from PICSOU_PROXY_IP,
#   • sets SERVER_PORT and the APP_STARTUP_SYNC_ENABLED default,
#   • execs supervisord (nginx + the Spring Boot app).
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

# nginx only accepts a forwarded client address from a known peer. Compose
# assigns the bundled Caddy this exact address on a dedicated network and
# passes the same value here. A standalone image trusts no proxy by default.
mkdir -p /etc/nginx/snippets
TRUSTED_PROXY_SNIPPET="/etc/nginx/snippets/picsou-trusted-proxy.generated.conf"

is_ipv4_literal() {
    local address="$1"
    local -a octets
    local octet

    # Validate the complete value before `read`: read stops at a newline and can
    # otherwise hide a suffix, while an empty trailing field may be discarded.
    [[ "$address" =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]] || return 1
    IFS='.' read -r -a octets <<< "$address"
    for octet in "${octets[@]}"; do
        (( 10#$octet <= 255 )) || return 1
    done
}

trusted_proxy_ip="${PICSOU_PROXY_IP:-}"
if [ -n "$trusted_proxy_ip" ]; then
    if ! is_ipv4_literal "$trusted_proxy_ip"; then
        echo "[entrypoint] FATAL: PICSOU_PROXY_IP must be one plain IPv4 address." >&2
        exit 1
    fi
    printf 'set_real_ip_from %s;\n' "$trusted_proxy_ip" > "$TRUSTED_PROXY_SNIPPET"
else
    : > "$TRUSTED_PROXY_SNIPPET"
fi

# HSTS is emitted by Spring Boot alone (HSTS_ENABLED, opt-in); see SecurityConfig.

# Defaults that never changed — keep backward compatibility with the old
# single-line entrypoint.
export SERVER_PORT=9090
export APP_STARTUP_SYNC_ENABLED=${APP_STARTUP_SYNC_ENABLED:-false}

exec /usr/bin/supervisord -c /etc/supervisor/conf.d/picsou.conf
