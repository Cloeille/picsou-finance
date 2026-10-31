#!/usr/bin/env bash
#
# End-to-end smoke test for the iOS app's OAuth2 Authorization Code + PKCE flow.
#
# Drives the exact sequence the native app performs, against a *running* Picsou instance
# whose setup wizard is complete and that has a real user:
#
#   1. log in (password [+ TOTP]) to obtain the access_token cookie  (as ASWebAuthenticationSession would)
#   2. GET  /oauth2/authorize with that cookie      -> 302 picsou://callback?code=...
#   3. POST /oauth2/token (code + PKCE verifier)     -> access_token + refresh_token (JWT)
#   4. GET  /api/dashboard with Authorization: Bearer -> 200 (proves the Bearer path works)
#   5. POST /oauth2/token (grant_type=refresh_token)  -> rotated tokens
#
# Usage:
#   BASE_URL=http://localhost:8080 USERNAME=alice PASSWORD=secret [TOTP=123456] \
#     backend/scripts/verify-oauth-pkce.sh
#
# Requires: bash, curl, openssl, python3 (for JSON field extraction).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:?set USERNAME}"
PASSWORD="${PASSWORD:?set PASSWORD}"
CLIENT_ID="${CLIENT_ID:-picsou-ios}"
REDIRECT_URI="${REDIRECT_URI:-picsou://callback}"
SCOPE="${SCOPE:-read}"

COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
json_field() { python3 -c 'import sys,json; print(json.load(sys.stdin).get(sys.argv[1],""))' "$1"; }

echo "==> 1. PKCE parameters"
VERIFIER="$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')"
CHALLENGE="$(printf '%s' "$VERIFIER" | openssl dgst -binary -sha256 | b64url)"
echo "    code_verifier  = ${VERIFIER:0:12}… (${#VERIFIER} chars)"
echo "    code_challenge = ${CHALLENGE:0:12}… (S256)"

echo "==> 2. Login (establishes the access_token cookie)"
LOGIN="$(curl -sS -c "$COOKIES" -X POST "$BASE_URL/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"rememberMe\":false}")"
if printf '%s' "$LOGIN" | grep -q '"mfaRequired":true'; then
  : "${TOTP:?login needs a second factor — set TOTP=your-6-digit-code}"
  echo "    MFA required — verifying TOTP"
  curl -sS -b "$COOKIES" -c "$COOKIES" -X POST "$BASE_URL/api/auth/mfa/verify" \
    -H 'Content-Type: application/json' \
    -d "{\"code\":\"$TOTP\",\"trustDevice\":false,\"isRecoveryCode\":false}" >/dev/null
fi

echo "==> 3. Authorize -> auth code"
LOCATION="$(curl -sS -b "$COOKIES" -o /dev/null -D - \
  "$BASE_URL/oauth2/authorize?response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URI&scope=$SCOPE&code_challenge=$CHALLENGE&code_challenge_method=S256" \
  | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')"
CODE="$(printf '%s' "$LOCATION" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')"
[ -n "$CODE" ] || { echo "!! no authorization code (Location: ${LOCATION:-none}); is the cookie valid?"; exit 1; }
echo "    code = ${CODE:0:12}…"

echo "==> 4. Token exchange (code + PKCE verifier)"
TOKENS="$(curl -sS -X POST "$BASE_URL/oauth2/token" \
  -d grant_type=authorization_code -d code="$CODE" \
  -d redirect_uri="$REDIRECT_URI" -d client_id="$CLIENT_ID" -d code_verifier="$VERIFIER")"
ACCESS="$(printf '%s' "$TOKENS" | json_field access_token)"
REFRESH="$(printf '%s' "$TOKENS" | json_field refresh_token)"
[ -n "$ACCESS" ] || { echo "!! no access_token in response: $TOKENS"; exit 1; }
echo "    access_token  = ${ACCESS:0:16}…"
echo "    refresh_token = ${REFRESH:0:16}…"

echo "==> 5. Call /api/dashboard with the Bearer token"
STATUS="$(curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $ACCESS" "$BASE_URL/api/dashboard")"
echo "    GET /api/dashboard -> HTTP $STATUS"
[ "$STATUS" = "200" ] || { echo "!! expected 200"; exit 1; }

echo "==> 6. Refresh-token grant (rotation)"
REFRESHED="$(curl -sS -X POST "$BASE_URL/oauth2/token" \
  -d grant_type=refresh_token -d refresh_token="$REFRESH" -d client_id="$CLIENT_ID")"
ACCESS2="$(printf '%s' "$REFRESHED" | json_field access_token)"
[ -n "$ACCESS2" ] || { echo "!! refresh did not return a new access_token: $REFRESHED"; exit 1; }
echo "    new access_token = ${ACCESS2:0:16}…"

echo
echo "✅ OAuth2 + PKCE flow verified end-to-end."
