#!/bin/sh
# Compares the *-auth sidecars of docker/docker-compose.yml with the `sidecars`
# map of charts/picsou/values.yaml.
#
# A sidecar is "standard" when its Compose service has nothing but an image, a
# build, a restart policy and APP_SIDECAR_API_KEY: the chart's generic sidecar
# template covers it. Anything else (volumes, networks, init, extra variables, …)
# needs a chart change by hand, so it is reported and never guessed.
#
#   --check   report differences; exit 2 when there are any (default)
#   --apply   add the missing standard sidecars to values.yaml, report the rest;
#             exit 0 unless the files cannot be read
#   --github  also emit GitHub Actions ::warning annotations
#   --json    print the findings as a JSON array instead of text
#
# Requires yq v4 (mikefarah). Exit 1 on usage or read errors.
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
compose="$root/docker/docker-compose.yml"
values="$root/charts/picsou/values.yaml"
mode=check
github=false
json=false

while [ $# -gt 0 ]; do
  case "$1" in
    --check) mode=check ;;
    --apply) mode=apply ;;
    --github) github=true ;;
    --json) json=true ;;
    --compose) compose=$2; shift ;;
    --values) values=$2; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

command -v yq >/dev/null 2>&1 || { echo "yq v4 is required" >&2; exit 1; }
for f in "$compose" "$values"; do
  [ -r "$f" ] || { echo "cannot read $f" >&2; exit 1; }
done

# Inserts an entry at the end of the `sidecars:` map, as text, so that the rest of
# values.yaml (comments, blank lines) stays byte for byte as it was.
add_to_values() {
  tmp=$(mktemp)
  awk -v name="$1" -v env="$2" -v port="$3" '
    /^sidecars:[[:space:]]*$/ { inside = 1; print; next }
    inside && /^[^[:space:]#]/ || inside && /^#/ {
      printf "  %s:\n    enabled: true\n    urlEnv: %s\n    port: %s\n", name, env, port
      printf "%s", blanks; blanks = ""; inside = 0; print; next
    }
    inside && /^[[:space:]]*$/ { blanks = blanks $0 "\n"; next }
    inside { printf "%s", blanks; blanks = ""; print; next }
    { print }
    END {
      if (inside) printf "  %s:\n    enabled: true\n    urlEnv: %s\n    port: %s\n%s", name, env, port, blanks
    }' "$values" > "$tmp"
  [ "$(yq -r ".sidecars[\"$1\"].port" "$tmp")" = "$3" ] || { rm -f "$tmp"; echo "could not add $1 to $values" >&2; exit 1; }
  cat "$tmp" > "$values" && rm -f "$tmp"
}

findings=""   # one "sidecar|kind|detail" line per finding
add_finding() { findings="${findings}$1|$2|$3
"; }

# "<VAR> <host> <port>" for every app variable that points at a sidecar URL,
# whether written as http://host:port or ${VAR:-http://host:port}.
urls=$(yq -r '.services.app.environment | to_entries | .[]
  | select(.key | test("_AUTH_URL$")) | .key + " " + (.value | tostring)' "$compose" \
  | sed -n 's|^\([A-Z0-9_]*\) .*http://\([a-z0-9-]*\):\([0-9]*\).*|\1 \2 \3|p')

compose_sidecars=$(yq -r '.services | keys | .[] | select(test("-auth$"))' "$compose")
chart_sidecars=$(yq -r '.sidecars | keys | .[]' "$values")

for name in $compose_sidecars; do
  wiring=$(printf '%s\n' "$urls" | awk -v h="$name" '$2 == h { print $1, $3; exit }')
  url_env=${wiring% *}
  port=${wiring#* }

  # Keys the generic template does not reproduce.
  extra_keys=$(yq -r ".services[\"$name\"] | keys | .[]
    | select(. != \"image\" and . != \"build\" and . != \"restart\" and . != \"environment\")" "$compose" | tr '\n' ' ')
  extra_env=$(yq -r ".services[\"$name\"].environment // {} | keys | .[]
    | select(. != \"APP_SIDECAR_API_KEY\")" "$compose" | tr '\n' ' ')

  if printf '%s\n' "$chart_sidecars" | grep -qx "$name"; then
    chart_port=$(yq -r ".sidecars[\"$name\"].port" "$values")
    chart_env=$(yq -r ".sidecars[\"$name\"].urlEnv" "$values")
    if [ -n "$wiring" ] && { [ "$chart_port" != "$port" ] || [ "$chart_env" != "$url_env" ]; }; then
      add_finding "$name" mismatch "Compose: $url_env on port $port, chart: $chart_env on port $chart_port"
    fi
    continue
  fi

  if [ -z "$wiring" ]; then
    add_finding "$name" unwired "no *_AUTH_URL of the app service points at it"
  elif [ -n "$extra_keys$extra_env" ]; then
    detail="needs a chart change by hand:"
    [ -n "$extra_keys" ] && detail="$detail keys ${extra_keys% }"
    [ -n "$extra_env" ] && detail="$detail; variables ${extra_env% }"
    add_finding "$name" special "$detail"
  elif [ "$mode" = apply ]; then
    add_to_values "$name" "$url_env" "$port"
    add_finding "$name" added "added as a standard sidecar ($url_env, port $port)"
  else
    add_finding "$name" standard "missing; standard, can be added with --apply ($url_env, port $port)"
  fi
done

for name in $chart_sidecars; do
  printf '%s\n' "$compose_sidecars" | grep -qx "$name" \
    || add_finding "$name" stale "in the chart but no longer in docker/docker-compose.yml"
done

findings=$(printf '%s' "$findings" | sed '/^$/d')

if [ "$json" = true ]; then
  printf '%s\n' "$findings" | awk -F'|' '
    function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return s }
    BEGIN { printf "[" }
    NF == 3 { printf "%s{\"sidecar\":\"%s\",\"kind\":\"%s\",\"detail\":\"%s\"}", (n++ ? "," : ""), esc($1), esc($2), esc($3) }
    END { print "]" }'
elif [ -z "$findings" ]; then
  echo "chart sidecars match docker/docker-compose.yml"
else
  printf '%s\n' "$findings" | awk -F'|' '{ printf "%-22s %-9s %s\n", $1, $2, $3 }'
fi

if [ "$github" = true ] && [ -n "$findings" ]; then
  printf '%s\n' "$findings" | awk -F'|' '$2 != "added" { printf "::warning title=Helm chart: %s::%s\n", $1, $3 }'
fi

# Only --check signals findings through the exit code; added sidecars are not a problem.
if [ "$mode" = check ] && [ -n "$findings" ]; then
  exit 2
fi
exit 0
