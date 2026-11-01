# Picsou Helm chart

Deploys Picsou on Kubernetes: the app (frontend and backend in one image), the
bank and broker sidecars, and optionally a single-instance PostgreSQL. It is the
Kubernetes counterpart of `docker/docker-compose.yml` and uses the same images.

> Picsou holds balances, transactions and bank session tokens. Keep it on a
> private cluster, behind an internal hostname or a VPN. See the
> [disclaimer](../../README.md#disclaimer).

## Install

The chart is published on GHCR next to the images, in two channels:

| Channel | Published on | Version | Images |
|---|---|---|---|
| Releases | every version tag | the release, e.g. `1.2.0` | that release |
| main | every push to `main` | a pre-release, e.g. `1.1.1-main.20261031194505.g0f499ca` | that commit (`sha-0f499ca`) |

```bash
# Latest release
helm install picsou oci://ghcr.io/cloeille/charts/picsou -n picsou --create-namespace -f values.yaml
# A given release
helm install picsou oci://ghcr.io/cloeille/charts/picsou --version 1.2.0 -n picsou --create-namespace -f values.yaml
# Latest build of main
helm install picsou oci://ghcr.io/cloeille/charts/picsou --devel -n picsou --create-namespace -f values.yaml
```

Pre-releases are ignored unless asked for (`--devel`, or a version constraint
that allows them), so the main channel never replaces a release by accident.
Main versions bump the patch of the highest valid tagged version core, including
prerelease tags (for example, `1.3.0-rc.1` leads to `1.3.1-main.…`). This keeps
new main builds above published release candidates.
With Argo CD, `targetRevision: "1.*"` follows releases and `">=0.0.0-0"` follows
main. A chart published from main pins the images of its own commit rather than
`nightly`, so a given chart version always deploys the same code.

A typical `values.yaml`:

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: internal-ca
  hosts:
    - host: picsou.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: picsou-tls
      hosts:
        - picsou.example.com
```

From a checkout of the repository, the chart describes `main` and runs the
`nightly` images:

```bash
helm install picsou ./charts/picsou -n picsou --create-namespace \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=picsou.example.com \
  --set ingress.hosts[0].paths[0].path=/ \
  --set ingress.hosts[0].paths[0].pathType=Prefix \
  --set ingress.tls[0].secretName=picsou-tls \
  --set ingress.tls[0].hosts[0]=picsou.example.com
```

Then open `https://picsou.example.com` and run the setup wizard. Without an
Ingress, `kubectl port-forward svc/picsou 8080:8080` and http://localhost:8080
work for a trial, but not for bank sync (see below).

Every image tag defaults to the chart's `appVersion`; `global.imageTag`
overrides them all.

## HTTPS

TLS terminates on your Ingress or Gateway; the chart ships no TLS proxy. Any
certificate source works: cert-manager with Let's Encrypt (HTTP-01 or DNS-01),
an internal CA, a wildcard Secret.

- **Bank sync needs HTTPS.** Enable Banking rejects plain-HTTP callback URLs.
  Enable Banking never calls that URL itself (the redirect happens in the user's
  browser), so a certificate from an internal CA your devices trust is enough,
  and the hostname does not need to be reachable from the internet.
- **The controller must send `X-Forwarded-Proto: https`.** ingress-nginx,
  Traefik, HAProxy and the common Gateway implementations do by default.
  Without it the backend treats same-origin HTTPS requests as cross-origin and
  login and the wizard's Origins step fail with 403.
- **Open the wizard on the final `https://` address.** It stores the Enable
  Banking callback URL, the allowed origins and the secure cookie flag derived
  from the address it is opened on, and these database values win over the
  environment afterwards ([README step 3f](../../README.md#3f-adding-https-to-an-install-that-is-already-set-up)
  explains how to fix them).
- **`HSTS_ENABLED`** (`app.env.HSTS_ENABLED: "true"`) only with a certificate
  every browser that opens Picsou trusts.
- **Client addresses: `PICSOU_PROXY_IP`.** nginx takes the client address from
  `X-Forwarded-For` only when the request comes from that one exact IPv4 address
  ([trusted proxy ADR](../../docs/decisions/2026-10-31-trusted-proxy-client-address.md)).
  Ingress controllers usually run several pods whose addresses change, so the
  chart leaves it unset: the app then sees the controller pod's address, and
  per-address limits such as the login limit apply per controller pod. Set it
  with `app.env.PICSOU_PROXY_IP` only when the controller reaches Picsou from a
  single stable address. HTTPS detection does not depend on it.

With `ingress.enabled=true`, an empty or null `ingress.hosts` list routes requests
to the app through the Ingress default backend. A nonempty list uses its configured
host and path rules.

For the Gateway API, set `httpRoute.enabled=true` and `httpRoute.parentRefs`
instead of the Ingress.

## Secrets

| Key in the Secret | Variable | When absent |
|---|---|---|
| `sidecar-api-key` | `APP_SIDECAR_API_KEY` (app and every sidecar) | generated by the chart |
| `postgres-password` | `POSTGRES_PASSWORD` | generated for bundled PostgreSQL; an explicit source is required for external databases |
| `jwt-secret` | `JWT_SECRET` | generated by the app on first boot, kept on the `/data` volume |
| `crypto-encryption-key` | `CRYPTO_ENCRYPTION_KEY` | generated by the app on first boot, kept on the `/data` volume |

By default the chart creates the Secret, keeps its values across upgrades
(`lookup`) and does not delete it on uninstall. **With Argo CD or any tool that
renders the chart with `helm template`, set `secrets.existingSecret`**: `lookup`
returns nothing there and the generated values would change on every sync.
Null or empty inline secret values preserve the stored key. When no key is stored,
the chart generates required keys and leaves optional app keys absent.
Quote numeric inline secrets in values files (for example,
`postgresPassword: "12345678"`). Floating-point values fail rendering because
Helm can stringify them in scientific notation or round their digits.
Integer `--set` values remain supported; use `--set-string` to preserve leading zeros.

For bundled PostgreSQL, an explicit `secrets.postgresPassword` that differs from
its stored Secret fails rendering. Updating `POSTGRES_PASSWORD` cannot change the
password in an initialized database. To rotate it during a maintenance window:

1. Back up the database and scale the app to zero (`app.replicaCount=0`).
2. Connect to PostgreSQL with the current credentials and run `ALTER ROLE` to set
   the new password. Interactive `psql` with `\password picsou` avoids putting the
   new password in shell history; adjust the role for `postgresql.username`.
3. Update `postgres-password` in the current Kubernetes Secret to that same value
   before upgrading, so the pre-upgrade migration Job can authenticate.
4. Set `secrets.postgresPassword` to the matching value in your protected Helm
   values and upgrade with `app.replicaCount=1` to restart the app. Restart any
   other database clients so they load the new credentials.

The guard uses `lookup`, which is unavailable in offline rendering. For
`secrets.existingSecret`, coordinate the same server and Secret updates yourself.
An external database still accepts an explicit password without this guard.

Secret changes require an explicit rollout, whether supplied inline or through
`secrets.existingSecret`, External Secrets or Sealed Secrets. The chart deliberately
publishes no secret-derived pod checksum: even a hash can reveal a weak password
through offline guessing. Rotating app-only keys must not restart browser sidecars
and interrupt login sessions. For JWT or database password changes, restart only
the app after updating the Secret. After rotating the sidecar API key, restart the
app and all enabled sidecars together so they load the same key. For a release
named `picsou` in namespace `picsou`, choose the matching rollout:

```bash
# JWT or database password rotation: app only
kubectl -n picsou rollout restart deployment -l app.kubernetes.io/instance=picsou,app.kubernetes.io/component=app
# Sidecar API key rotation: app and all sidecars
kubectl -n picsou rollout restart deployment -l app.kubernetes.io/instance=picsou
```

Coordinate the rollout with a maintenance window; authentication can fail while
pods use different keys. Never rotate the encryption key without a data migration.

**New installs only:** generate the keys once and keep a secure copy.

```bash
kubectl -n picsou create secret generic picsou-secrets \
  --from-literal=sidecar-api-key="$(openssl rand -base64 32)" \
  --from-literal=postgres-password="$(openssl rand -base64 24)" \
  --from-literal=jwt-secret="$(openssl rand -base64 48)" \
  --from-literal=crypto-encryption-key="$(openssl rand -base64 32)"
```

**Existing installs moving to `secrets.existingSecret` (including Argo CD):**
do not run the random-key command above. The entrypoint gives environment values
priority over `/data/.secrets/crypto_key` and `/data/.secrets/jwt_secret`. Copy the
exact contents of those files into `crypto-encryption-key` and `jwt-secret`, or
leave both keys out of the new Secret and keep the persistent `/data` volume.
Also copy `sidecar-api-key` and `postgres-password` from the current chart Secret;
a new random database password will not change the password in an existing database.

With `app.persistence.enabled=false`, provide both `secrets.jwtSecret` and
`secrets.cryptoEncryptionKey`, or an existing Secret containing both keys. The
chart rejects missing inline keys, and the pod requires both keys in an existing
Secret. This prevents pod replacement from losing the encryption key and sessions.
Other files under `/data`, including Enable Banking keys, remain ephemeral.

`crypto-encryption-key` decrypts the stored API secrets and bank session tokens.
Losing it makes them unreadable: either provide it and keep a copy, or back up
the `<release>-data` volume where the app generated it.

## Environment overrides

`app.env` and `app.extraEnv` replace generated app variables by name, including
`JWT_SECRET`, `CRYPTO_ENCRYPTION_KEY`, `APP_SIDECAR_API_KEY`, sidecar URLs and
`ENABLEBANKING_PRIVATE_KEY_PATH`. `app.extraEnv` takes precedence over `app.env`.
Duplicate names within `app.extraEnv` fail rendering. Keep app and sidecar API keys
in sync when overriding the app key. Kubernetes gives explicit entries precedence
over `envFrom`.

## Database

The bundled PostgreSQL (`postgresql.enabled`, default `true`) is a single
`postgres:16-alpine` pod, the equivalent of the Compose `db` service. For data
you care about, use an operator-managed cluster and point the chart at it:

```yaml
postgresql:
  enabled: false
externalDatabase:
  host: picsou-db-rw          # e.g. the CloudNativePG read-write Service
  database: picsou
  username: picsou
  existingSecret: picsou-db-app
  passwordKey: password
```

An external database requires one of `externalDatabase.existingSecret`,
`secrets.existingSecret` (key `postgres-password`), or `secrets.postgresPassword`.
The chart fails at render time without one. The supplied password must match the
existing database; the chart cannot configure credentials on an external server.

### Reusing a PostgreSQL volume

Fresh volumes use `postgresql.pgdata: /var/lib/postgresql/data/pgdata` so `initdb`
avoids filesystem entries such as `lost+found`. Before mounting an existing claim,
check where its `PG_VERSION` file is. The default expects `pgdata/PG_VERSION`.
For a Compose volume or restore with `PG_VERSION` at the volume root, set both:

```yaml
postgresql:
  pgdata: /var/lib/postgresql/data
  persistence:
    existingClaim: imported-postgresql
```

Set the path before the first start and keep the existing database password.
Using the default subdirectory with root-layout data initializes a separate empty
database; it does not migrate the original data. Back up first and use a volume
compatible with PostgreSQL 16, or restore a logical dump into a fresh database.

The PostgreSQL pod does not restart when inline application or sidecar secrets
change. Updating `secrets.postgresPassword` does not rotate an initialized
database's password. Coordinate password changes on the server with the Secret
and restart its consumers as needed.

## Upgrades

`helm upgrade` runs a pre-upgrade Job with the new image that migrates and then
verifies the schema (`ReleaseMigrationCli`, the tool `docker/deploy.sh` uses).
If either step fails the upgrade stops and the running app is left untouched.
The app also migrates on startup, as defence in depth.

The Job inherits `app.env`, `app.extraEnv`, `app.envFrom`, `app.extraVolumes`,
`app.extraVolumeMounts`, `app.podLabels`, `app.podAnnotations`, `app.nodeSelector`, `app.tolerations`
and `app.affinity`. Use the extra mounts for database CA certificates referenced
by `externalDatabase.jdbcParams`. It keeps its own `migrate` component label
and does not mount the app's `/data` volume, which may still be in use.
Datasource defaults and explicit overrides are shared with the app. The CLI uses
`SPRING_DATASOURCE_PASSWORD` when nonempty, otherwise `POSTGRES_PASSWORD`, just
like the image entrypoint. Kubernetes gives explicit `env` entries precedence over
`envFrom`; use `app.env` or `app.extraEnv` to override generated datasource values.
Use `app.podAnnotations` for mesh opt-outs such as `sidecar.istio.io/inject: "false"`
so the migration pod can finish without a long-running injected proxy.
The generated Secret remains an ordinary kept resource. Do not turn it into a
pre-upgrade hook: [Helm defaults hooks](https://helm.sh/docs/topics/charts_hooks/#hook-deletion-policies)
to `before-hook-creation`, deleting the
previous Secret even with `resource-policy: keep`. A failed recreation could lose
the stored credentials.

Secrets, ConfigMaps and volumes needed by the hook must already exist before the
upgrade: a pre-upgrade hook runs before Helm updates ordinary release resources.

On a live cluster, the migration hook rejects upgrades when its generated `postgres-password` is missing
or differs from the requested inline password, before creating a Job. When switching
from `secrets.existingSecret` to the generated Secret, first provision the Secret named
after the release fullname with the current `postgres-password` and `sidecar-api-key`.
Preserve any `jwt-secret` and `crypto-encryption-key` too; never generate replacement
keys for existing data. For Helm to adopt the pre-provisioned Secret, set its label
`app.kubernetes.io/managed-by: Helm` and annotations `meta.helm.sh/release-name`
and `meta.helm.sh/release-namespace` to the release name and namespace.
Then clear `secrets.existingSecret` and upgrade. If using an
external database and changing `secrets.postgresPassword`, coordinate the database
password rotation and update `postgres-password` in the generated Secret **before**
upgrading, so the hook sees the current credential. Back up first and arrange a
maintenance window for credential changes. Externally managed Secrets and explicit
`app.env`/`app.extraEnv` password sources must also be ready before the hook runs.
Client-side upgrade previews (`helm upgrade --dry-run=client`, `helm diff` and
`helm template --is-upgrade`) skip this validation when neither the stored Secret
nor the namespace is visible through `lookup`. They do not validate live credentials;
use `--dry-run=server` to check against the cluster. GitOps still requires
`secrets.existingSecret` as described above.

Allow the hook to finish before Helm times out (Helm defaults to only five minutes):

```bash
helm upgrade picsou oci://ghcr.io/cloeille/charts/picsou -n picsou -f values.yaml --timeout 35m
```

The default `migrationJob.activeDeadlineSeconds` is 1800 (30 minutes). If you
increase it, set `--timeout` longer than that deadline, with extra time for pod
startup. If Helm times out, inspect the migration Job and wait for it to finish
before retrying: the next upgrade deletes the previous hook Job.

The Job does not back up or rehearse the migration on a copy the way
`docker/deploy.sh` does: **back up the database before every upgrade.**
For the bundled PostgreSQL, run `pg_dump` inside its pod so the backup needs
no ingress permission for a separate pod. With the default release,
database and username (`picsou`):

```bash
kubectl -n picsou exec picsou-postgresql-0 -- sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > picsou.dump
```

Check that the command succeeds and store the dump securely together with the
app's `/data` backup. Adjust the namespace and pod name for your release. For an
external database, use its operator's backup procedure.

When switching an existing release from an external database
(`postgresql.enabled=false`) to bundled PostgreSQL, the pre-upgrade Job runs before
the new database Service and StatefulSet exist. For that specific upgrade, use:

```bash
helm upgrade picsou oci://ghcr.io/cloeille/charts/picsou -n picsou -f values.yaml \
  --set postgresql.enabled=true --set migrationJob.enabled=false
```

The app migrates the new empty database on first boot. Once it is ready, re-enable
`migrationJob.enabled=true` in your values and on subsequent upgrades. This
procedure does not transfer existing data. Back up and plan a separate restore or
data migration before switching if you need to preserve the external database.

With Argo CD, the hook becomes a PreSync hook, which also runs on the very first
sync, before the bundled PostgreSQL exists. Set `migrationJob.enabled=false`
for the first sync, then enable it.

## Sidecars

Every sidecar is deployed by default. Disable the ones you do not use, the
browser-based ones take the most memory:

```yaml
sidecars:
  amex-auth:
    enabled: false
  caisse-epargne-auth:
    enabled: false
```

Sidecar Service names stay single-label (`<release>-bourso-auth`) and the app
reaches them in its own namespace: the backend accepts plain HTTP to a sidecar
on such a name or loopback; other hosts require HTTPS. Fullnames must start with
a lowercase letter so Java parses these hosts; set `fullnameOverride` when a
release name starts with a digit. Disabled sidecars receive
`http://127.0.0.1:<port>` so backend defaults cannot send credentials to a similarly
named Service in the namespace. Explicit `app.env`/`app.extraEnv` URL overrides
still take precedence. Per-sidecar `resources`,
`securityContext` and `podSecurityContext` are merged over `sidecarDefaults`.
To persist another sidecar's data, set its `persistence.enabled: true` and
`persistence.mountPath`. New PVCs default to `accessModes: [ReadWriteOnce]` and
`size: 1Gi`; override these under that sidecar's `persistence` block as needed.

Revolut stores browser profiles on a PVC at `/data/revolut-profiles`. Its pod
sets `sidecars.revolut-auth.podSecurityContext.fsGroup: 1000` so Kubernetes gives
the non-root process write access to a fresh volume through a supplemental group.
This also applies to `persistence.existingClaim`. The storage driver must support
`fsGroup` volume permissions; otherwise provision a group-writable directory
for that group. Override `fsGroup` if your storage requires another group.

Resource names are capped at 40 characters before the sidecar name: a longer
release name is cut to 33 characters plus a hash of the full name, so two long
names never collide.

### Keeping up with the Compose file

`sync-sidecars.sh` compares the `*-auth` services of `docker/docker-compose.yml`
with the `sidecars` map (requires [yq](https://github.com/mikefarah/yq) v4):

```bash
charts/picsou/sync-sidecars.sh --check   # report, exit 2 on differences
charts/picsou/sync-sidecars.sh --apply   # add standard sidecars, disable stale or broken entries; warn on differences
```

A sidecar is standard when its Compose service has only an image, a build, a
restart policy and `APP_SIDECAR_API_KEY`: the generic template covers it. One
missing from the chart with volumes, networks, `init` or more variables needs a
chart change by hand.
Every sidecar the chart already lists is checked against its own entry, and
reported as `drift` when the entry misses what Compose has: volumes without
`persistence.enabled`, `init: true` without `shareProcessNamespace: true`, or a
variable in neither `env` nor `extraEnv`. Compose networks are not compared:
NetworkPolicies isolate every sidecar the same way. Other unsupported keys still
warn. These checks detect missing chart capabilities, not equivalence of individual
mounts or network rules. Missing custom sidecars still require manual integration.
The URL parser recognizes HTTP and HTTPS app wiring. `--json` always emits a JSON
array, including with `--github`; GitHub annotations are emitted only in text mode.

CI runs it in both directions. On a pull request, the `helm-chart` job reports
differences as warnings and never fails because of them. The repository inventory
test accepts exit codes 0 (in sync) and 2 (drift), while script errors still fail
that check. Supported customization tests use a frozen synthetic inventory so
new Compose sidecars cannot make those assertions block releases. All script tests
live in `charts/picsou/tests` and run only in the independent chart job. Helm validation
is independent of `ci-ok` and image publishing, so a chart failure does not block application releases.
When publishing, stale entries are disabled in the packaged values so removed images
are never deployed. Port or URL-variable mismatches, missing app wiring and semantic `drift` findings
disable the affected sidecars in the packaged values, including custom sidecars,
and emit warnings. This keeps Compose-only changes from blocking chart publication
without deploying known-broken sidecars. Script usage, read and write errors still fail.
The missing standard sidecars are added to the packaged chart,
so a new sidecar of that kind is available without a chart change; the others
are left out of the published chart until it supports them.

## Network policies

Enabled by default, they mirror the Compose isolation: only the app pod may reach
the sidecars, and only the app and the migration Job may reach the bundled
PostgreSQL. Sidecars keep unrestricted egress to their providers. To also
restrict who reaches the app, list the ingress controller in
`networkPolicy.appIngressFrom`. For database backups, use `kubectl exec` into
the PostgreSQL pod as shown under Upgrades; separate backup pods are blocked.

They only take effect with a CNI that enforces NetworkPolicies (Cilium, Calico,
…). Flannel, the Talos default, accepts them and enforces nothing.

## Health test

`helm test` checks `/actuator/health` through the app Service using wget.
Set `tests.image.repository`, `tests.image.tag` and `tests.image.pullPolicy`
to use a registry mirror with a wget-compatible image (default `busybox:1.37`).
The test pod uses `global.imagePullSecrets` and the app's `nodeSelector`,
`tolerations` and `affinity`; its pull policy defaults to `global.imagePullPolicy`.

## Known limitations

- **One app replica.** Flyway and the `ReadWriteOnce` `/data` volume rule out
  more; rendering fails above one replica. Zero scales the app down. The Deployment
  uses the `Recreate` strategy to prevent overlapping versions.
- **Pod Security `baseline`, not `restricted`.** The app image runs nginx and
  the JVM as root under supervisord, PostgreSQL starts as root before dropping
  privileges, and the sidecar images declare non-numeric users, so
  `runAsNonRoot` cannot be verified. The namespace must allow `baseline`.
- **No Ollama.** Run it separately if you want local AI categorization and set
  its URL in Admin → Settings.

The app has no startup probe by default (`app.startupProbe: {}`). HTTP readiness
holds traffic during long migrations or database outages without restarting the
container. TCP liveness still detects an unresponsive nginx listener. PostgreSQL
has a `pg_isready` startup probe with a ten-minute recovery budget before liveness
starts. Custom `app.podLabels` cannot override the reserved `app.kubernetes.io/name`,
`app.kubernetes.io/instance` and `app.kubernetes.io/component` labels on the app or
migration pod.

Resource fullnames of up to 40 characters stay unchanged. Longer names use the
first 33 characters plus a hyphen and six-character hash of the full name to keep
sidecar Service names within 63 characters and distinguish long release names.
Upgrading a release whose old fullname was truncated changes its resource names;
plan the migration of existing claims and Secrets explicitly before upgrading.

A chart installed or packaged from a checkout defaults to the upstream
`ghcr.io/cloeille/picsou-finance` images and `nightly` tag. For local fork installs,
set `global.imageRepository=ghcr.io/<owner>/<repository>` and `global.imageTag`
to a tag published by that fork. CI-published charts already stamp both values.

## Values

See [`values.yaml`](values.yaml), every key is commented. The main ones:

| Key | Default | Description |
|---|---|---|
| `global.imageRepository` | `ghcr.io/cloeille/picsou-finance` | App image; sidecars use `<this>/<sidecar>`. Set by CI to the publishing repository |
| `global.imageTag` | `""` (appVersion) | Tag for every Picsou image |
| `secrets.existingSecret` | `""` | Secret with the keys above; required with GitOps |
| `app.env` | `SPRING_PROFILES_ACTIVE: prod` | Extra environment variables for the app |
| `app.enableBanking.existingSecret` | `""` | Secret holding the Enable Banking private key |
| `app.persistence.size` | `1Gi` | `/data` volume (generated secrets, keys) |
| `ingress.*` / `httpRoute.*` | disabled | Exposure, TLS on the controller |
| `sidecars.<name>.enabled` | `true` | Deploy this sidecar |
| `postgresql.enabled` | `true` | Bundled single-instance PostgreSQL |
| `postgresql.pgdata` | `/var/lib/postgresql/data/pgdata` | Data directory inside the volume; set `/var/lib/postgresql/data` for Compose root-layout imports |
| `externalDatabase.*` | | Connection to your own PostgreSQL |
| `migrationJob.enabled` | `true` | Pre-upgrade migrate and verify Job |
| `networkPolicy.enabled` | `true` | Restrict access to sidecars and database |
