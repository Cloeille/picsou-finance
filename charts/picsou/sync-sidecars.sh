#!/bin/sh
# Compares the *-auth sidecars of docker/docker-compose.yml with the `sidecars`
# map of charts/picsou/values.yaml. The URL variable and port of each sidecar come
# from the `app.*.url` entries of backend/src/main/resources/application.yml
# (`${VAR:http://host:port}`), the single place where sidecar URLs are set.
#
# A sidecar is "standard" when its Compose service has nothing but an image, a
# build, a restart policy, Compose profiles and APP_SIDECAR_API_KEY (the chart
# enables sidecars with `sidecars.<name>.enabled` instead): the chart's generic sidecar
# template covers it. A missing sidecar with anything else (volumes, networks,
# init, extra variables, …) needs a chart change by hand, so its settings are
# reported and never guessed.
#
# A sidecar the chart already lists is compared with its own chart entry, not
# with the generic template: Compose volumes need persistence.enabled, `init:
# true` needs shareProcessNamespace, and every other variable needs an env or
# extraEnv entry, otherwise it is reported as drift. Compose networks are not
# compared: the chart's NetworkPolicies isolate every sidecar the same way.
#
#   --check   report differences; exit 2 when there are any (default)
#   --apply   add missing standard sidecars; disable stale or broken entries;
#             warn on differences, exit 1 on usage/read errors
#   --github  also emit GitHub Actions ::warning annotations (text output only,
#             so that --json stays a parseable array)
#   --json    print the findings as a JSON array instead of text
#
# Files default to the repository's; --compose, --values and --app-config <path>
# override docker-compose.yml, values.yaml and application.yml.
#
# Requires yq v4 (mikefarah). Exit 1 on usage/read/write errors.
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
compose="$root/docker/docker-compose.yml"
app_config="$root/backend/src/main/resources/application.yml"
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
    --app-config) app_config=$2; shift ;;
    -h|--help) sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

command -v yq >/dev/null 2>&1 || { echo "yq v4 is required" >&2; exit 1; }
for f in "$compose" "$values" "$app_config"; do
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

# Disable removed images without rewriting unrelated values or comments. Keep
# their configuration so operators can inspect it, but never deploy an unbuilt tag.
disable_in_values() {
  tmp=$(mktemp)
  awk -v name="$1" '
    /^sidecars:[[:space:]]*$/ { inside = 1 }
    inside && /^[^[:space:]#]/ && !/^sidecars:/ { inside = 0; target = 0 }
    inside && /^  [^[:space:]#]/ {
      entry = $0; sub(/[[:space:]]+#.*/, "", entry); sub(/[[:space:]]+$/, "", entry)
      target = (entry == "  " name ":")
      if (target) { print; print "    enabled: false"; next }
    }
    target && /^    enabled:/ { next }
    { print }
  ' "$values" > "$tmp"
  [ "$(yq -r ".sidecars[\"$1\"].enabled" "$tmp")" = false ] || {
    rm -f "$tmp"; echo "could not disable $1 in $values" >&2; exit 1;
  }
  cat "$tmp" > "$values" && rm -f "$tmp"
}

findings=""   # one "sidecar|kind|detail" line per finding
add_finding() { findings="${findings}$1|$2|$3
"; }

# "<VAR> <host> <port>" for every `app.<key>.url: ${VAR:http(s)://host:port}`
# of application.yml.
urls=$(yq -r '.app | to_entries | .[]
  | select((.value | tag) == "!!map") | select(.value.url | tag == "!!str")
  | .value.url' "$app_config" \
  | sed -n 's|^\${\([A-Z0-9_]*\):https\{0,1\}://\([a-z0-9.-]*\):\([0-9]*\)}$|\1 \2 \3|p')

compose_sidecars=$(yq -r '.services | keys | .[] | select(test("-auth$"))' "$compose")
chart_sidecars=$(yq -r '.sidecars | keys | .[]' "$values")

for name in $compose_sidecars; do
  wiring=$(printf '%s\n' "$urls" | awk -v h="$name" '$2 == h { print $1, $3; exit }')
  url_env=${wiring% *}
  port=${wiring#* }

  # Keys the generic template does not reproduce.
  extra_keys=$(yq -r ".services[\"$name\"] | keys | .[]
    | select(. != \"image\" and . != \"build\" and . != \"restart\" and . != \"environment\" and . != \"profiles\")" "$compose" | tr '\n' ' ')
  extra_env=$(yq -r ".services[\"$name\"].environment // {} | keys | .[]
    | select(. != \"APP_SIDECAR_API_KEY\")" "$compose" | tr '\n' ' ')

  wiring_error=false
  if printf '%s\n' "$chart_sidecars" | grep -qx "$name"; then
    chart_port=$(yq -r ".sidecars[\"$name\"].port" "$values")
    chart_env=$(yq -r ".sidecars[\"$name\"].urlEnv" "$values")
    if [ -z "$wiring" ]; then
      wiring_error=true
      add_finding "$name" unwired "no app.*.url in application.yml points at it"
    elif [ "$chart_port" != "$port" ] || [ "$chart_env" != "$url_env" ]; then
      wiring_error=true
      add_finding "$name" mismatch "application.yml: $url_env on port $port, chart: $chart_env on port $chart_port"
    fi
    # Compare capabilities for every listed sidecar, not just known service names.
    # NetworkPolicies provide the Compose network isolation for all entries.
    compose_has_volumes=$(yq -r ".services[\"$name\"].volumes // [] | length > 0" "$compose")
    if [ "$compose_has_volumes" = true ] &&
       [ "$(yq -r ".sidecars[\"$name\"].persistence.enabled // false" "$values")" != true ]; then
      add_finding "$name" drift "Compose volumes require sidecars.$name.persistence.enabled=true"
    fi
    compose_init=$(yq -r ".services[\"$name\"].init // false" "$compose")
    if [ "$compose_init" = true ] &&
       [ "$(yq -r ".sidecars[\"$name\"].shareProcessNamespace // false" "$values")" != true ]; then
      add_finding "$name" drift "Compose init requires sidecars.$name.shareProcessNamespace=true"
    fi
    extra_keys=$(printf '%s\n' "$extra_keys" | awk '
      { for (i = 1; i <= NF; i++) if ($i != "volumes" && $i != "init" && $i != "networks") printf "%s ", $i }')

    chart_declared_env=$(
      {
        yq -r ".sidecars[\"$name\"].env // {} | keys | .[]" "$values"
        yq -r ".sidecars[\"$name\"].extraEnv // [] | .[].name" "$values"
      } | sort -u
    )
    missing_env=$(printf '%s\n' "$extra_env" | awk -v known="$chart_declared_env" '
      BEGIN { count = split(known, names, "\n"); for (i = 1; i <= count; i++) present[names[i]] = 1 }
      { for (i = 1; i <= NF; i++) if (!present[$i]) printf "%s ", $i }')
    if [ -n "$missing_env" ]; then
      add_finding "$name" drift "Compose variables missing from chart env/extraEnv: ${missing_env% }"
    fi
    if [ -n "$extra_keys" ]; then
      detail="review custom chart settings: keys ${extra_keys% }"
      add_finding "$name" special "$detail"
    fi
    # Capability drift does not prove an existing sidecar is unusable. Preserve
    # its settings so an optional Compose change cannot remove a working bank sync.
    if [ "$mode" = apply ] && [ "$wiring_error" = true ]; then
      disable_in_values "$name"
      add_finding "$name" disabled "left out of the packaged chart until its wiring is corrected"
    fi
    continue
  fi

  if [ -z "$wiring" ]; then
    add_finding "$name" unwired "no app.*.url in application.yml points at it"
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
  if ! printf '%s\n' "$compose_sidecars" | grep -qx "$name"; then
    [ "$mode" != apply ] || disable_in_values "$name"
    add_finding "$name" stale "no longer in docker/docker-compose.yml; disabled by --apply"
  fi
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

if [ "$github" = true ] && [ "$json" != true ] && [ -n "$findings" ]; then
  printf '%s\n' "$findings" | awk -F'|' '$2 != "added" { printf "::warning title=Helm chart: %s::%s\n", $1, $3 }'
fi

# --check reports all drift without changing the files.
if [ "$mode" = check ] && [ -n "$findings" ]; then
  exit 2
fi
exit 0
