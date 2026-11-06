# Feature: Kubernetes deployment (Helm chart)

> Last updated: 2026-11-01

## Context

Picsou ships as a Docker Compose stack. Users who already run a Kubernetes cluster at home want
to deploy it next to their other services, with their own ingress controller, certificate source
and database operator. `charts/picsou` is the Kubernetes counterpart of `docker/docker-compose.yml`:
same published images, same configuration surface, Kubernetes-native exposure and storage.

## How it works

One release creates:

- **app** — a `Recreate` Deployment of `ghcr.io/cloeille/picsou-finance` (nginx + Spring Boot
  under supervisord), a `/data` PVC and a `ClusterIP` Service on 8080. Rendering
  rejects more than one replica; zero is supported for maintenance. Custom pod labels
  cannot override the three selector labels on the app or migration pod.
- **sidecars** — one Deployment and Service per entry of `sidecars` (the `*-auth` services of
  the Compose file), each toggled by `enabled`. The app receives the matching
  `*_AUTH_URL` for every sidecar: the release Service when enabled, loopback
  (`http://127.0.0.1:<port>`) when disabled, preventing fallback to unrelated Services.
  Explicit app environment overrides still take precedence. `revolut-auth` gets a PVC for its browser profiles.
  Its pod sets `fsGroup: 1000` so the non-root process can write to a fresh volume through
  a supplemental group, including with an existing claim. This requires storage that supports
  `fsGroup` permissions, or a directory provisioned with write access for that group.
  Operators can override `sidecars.revolut-auth.podSecurityContext.fsGroup`.
- **postgresql** — optional single-replica StatefulSet of `postgres:16-alpine`, or
  `externalDatabase` for an operator-managed cluster.
- **Secret** — `sidecar-api-key` and `postgres-password`, generated once and preserved through
  `lookup`, or an `existingSecret`. `jwt-secret` and `crypto-encryption-key` are optional keys;
  when absent the image entrypoint generates them under `/data/.secrets` as on Compose.
  Disabling app persistence requires both inline keys or an existing Secret; in the latter
  case both key references become mandatory at pod startup so a missing key cannot be regenerated.
- **migration Job** — `pre-upgrade` hook running `ReleaseMigrationCli migrate` then `verify`
  with the new image.
- **NetworkPolicies** — ingress to each sidecar and to PostgreSQL only from the app (and the
  migration Job for PostgreSQL), optional restriction of the app's own ingress.
- **Ingress or HTTPRoute** — optional, TLS terminated by the controller. NOTES accept empty or
  null hostname lists and direct operators to their controller or Gateway listener address.
  Empty or null `ingress.hosts` routes requests to the app through the Ingress default backend;
  nonempty lists retain their configured host and path rules.

### Publishing

The `chart` job of `.github/workflows/docker.yml` runs after every image of the commit is pushed
and publishes to `oci://ghcr.io/<owner>/charts/picsou`:

- a version tag publishes the chart under that version, `appVersion` = the release;
- a push to `main` publishes `<highest tagged version core + 1 patch>-main.<UTC commit timestamp>.g<short sha>`, a
  pre-release that sorts above all valid release and prerelease tags and that Helm only installs with `--devel`, with
  `appVersion` = `sha-<short sha>`, the image tag `docker/metadata-action` gives every image of the
  commit. The latest release comes from the tags of the checkout (`fetch-depth: 0`), not from a
  network call: the checkout token is not persisted, so `git ls-remote` could not authenticate on a
  private repository. The version must be a valid semantic version (no leading zero in a numeric
  pre-release identifier), otherwise nothing is published.

The chart checkout fetches full history and tags. Main version selection uses local
`git tag --list` without a remote lookup. The timestamp comes from the HEAD commit
in UTC, so late-finishing pipelines do not get newer versions from job execution time. Release tags must be complete SemVer,
including no leading zeros in numeric prerelease identifiers; invalid tags skip
chart publishing. Only this SemVer result gates the packaging and pushing steps;
the base job has no constant publishing flag.

The `base` job computes the app build version and immutable image tag once. Both the image
matrix and the chart consume those outputs. Every image publishes the pinned tag through
`docker/metadata-action`'s existing SHA and SemVer entries without duplicate raw entries. The build step uses only metadata-action's tag output,
which lowercases repository names so mixed-case GitHub owners and forks publish valid image references.

Before packaging, `sync-sidecars.sh --apply` adds to the packaged copy the standard sidecars that
`values.yaml` does not list yet and disables entries removed from Compose, preserving their other
settings. Port or URL-variable mismatches and missing app wiring disable affected
sidecars in the packaged values and warn, including for custom sidecars. Capability
`drift` (volumes, `init` or extra variables) warns without modifying existing entries
or their enabled state, so an optional Compose change cannot remove a working bank
integration. These warnings need manual review for chart configuration updates.
Script errors still fail. `--check` remains warning-only in CI.
Live inventory tests parse a JSON array and accept exit 0 or 2; supported customization checks use a frozen synthetic
inventory so new Compose sidecars cannot turn inventory drift into a release-blocking test failure.
`global.imageRepository` is set to `ghcr.io/<repository>`, where
this run pushed the images, so a chart published from a fork runs that fork's images (nothing is
committed back). The `helm-chart` job of `ci.yml`
lints and renders the chart and runs `sync-sidecars.sh --check` on pull requests that touch the
chart, the Compose file or the workflows; differences are warnings, a broken chart fails
that independent check. Neither `ci-ok` nor image publishing depends on `helm-chart`.
After synchronizing the packaged values and setting the image repository, the publishing
job repeats the same strict lint, default and optional-resource render matrix, and full
chart test suite before packaging and pushing. Validation failures stop chart publication
without affecting images that have already been pushed.
Changes under `.github/` select every validation job, including `helm-chart` and
`sidecar-base`. Changes under `services/base/` select the shared-package tests and
all sidecar image tests, without selecting Helm validation.
The repository inventory test accepts exit codes 0 (in sync) and 2 (drift), but still fails
on script errors. All chart script tests live in `charts/picsou/tests`, outside the
release-blocking workflow inventory suite. Synthetic fixtures test comparison and warning behavior. Rendered chart
tests in `charts/picsou/tests` verify Revolut's volume permissions, existing-claim overrides,
mandatory keys for ephemeral app storage, hostname-less exposure, packaged health hooks,
external database password sources, configurable PostgreSQL data layout and stable PostgreSQL
pod templates across inline secret changes.

### Key files

- `charts/picsou/values.yaml` — every option, commented
- `charts/picsou/templates/app-deployment.yaml` — app pod, environment and sidecar URLs
- `charts/picsou/templates/sidecars.yaml` — sidecar Deployments and Services
- `charts/picsou/templates/secret.yaml` — generated Secret
- `charts/picsou/templates/migration-job.yaml` — pre-upgrade migration and verification
- `charts/picsou/templates/networkpolicy.yaml` — isolation of sidecars and database
- `charts/picsou/sync-sidecars.sh` — compares the Compose sidecars with `values.yaml` (URL variable and port read from `app.sidecars.*.url` in
  `backend/src/main/resources/application.yml`), adds the
  standard ones and disables stale or broken entries on `--apply` with warnings
- `.github/workflows/docker.yml` (`chart` job) — packages and publishes the chart
- `.github/workflows/ci.yml` (`helm-chart` job) — lint, render, sidecar comparison
- `charts/picsou/README.md` — operator documentation

### Flow

```
helm upgrade
  └─ pre-upgrade Job (new image): ReleaseMigrationCli migrate → verify
       ├─ failure → upgrade fails, running app untouched
       └─ success → app Deployment recreated → startup Flyway (no-op) → ready
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| TLS on the Ingress/Gateway, no Caddy | Clusters already have a controller and a certificate source (cert-manager, internal CA); the chart only needs `X-Forwarded-Proto` | Shipping the Caddy terminator of the `tls` Compose profile |
| Sidecar Services named `<release>-<sidecar>`, URLs without a domain | `SidecarBaseUrl` accepts plain HTTP only on a single-label host; the fullname starts with a lowercase letter for Java URI parsing and is capped at 40 characters (a 33-character prefix plus a six-character hash for longer names) so the longest name stays a valid DNS label | Cluster FQDNs (`*.svc.cluster.local`), which would require HTTPS between pods |
| JWT and encryption keys left to the entrypoint by default | Matches Compose and keeps the encryption key off any value the chart could regenerate | Generating them in the chart Secret, which a `helm template` renderer would rotate on every sync and make stored secrets unreadable |
| `lookup` + `helm.sh/resource-policy: keep` for the generated Secret | Values survive upgrades and uninstall | Regenerating on each upgrade |
| Chart version and `appVersion` stamped at publish time | One chart per release and per commit of main, each pinned to its own images, without a manual bump | A version kept in `Chart.yaml` and bumped by hand |
| Main channel pinned to `sha-<short>` images | A chart version always deploys the same code | `nightly`, which moves under an installed chart |
| Standard sidecars added at publish time, never committed | New sidecars reach the main channel without a chart PR, and CI never writes to the repository | Failing pull requests that add a sidecar; a bot committing to main |
| Pre-upgrade migration Job with the release CLI | Applies the [migration ADR](../decisions/2026-10-08-rehearse-release-migrations.md): migrate and verify before replacing the API | Relying on startup Flyway only, which fails after the old pod is gone |
| Bundled `postgres:16-alpine` StatefulSet | Same image as Compose, no dependency on a third-party chart | Bitnami PostgreSQL subchart |
| Liveness on TCP, readiness on `/actuator/health`, no app startup probe | Health includes the database; a database outage must not restart the app in a loop. supervisord restarts a dead JVM. Liveness is a TCP check on nginx (port 8080, the JVM listens on 9090), which answers at once, so a TCP startup probe adds nothing, and one on `/actuator/health` would restart the pod when the database is down at startup | Liveness on `/actuator/health`; `/actuator/health/liveness` is not permitted by `SecurityConfig`/`SetupFilter` |
| Startup probe on the bundled PostgreSQL | Crash recovery after an unclean stop can outlast the liveness window; liveness starts once it succeeds | Longer `initialDelaySeconds` on liveness, which would also delay detecting a hung database |
| `shareProcessNamespace` for `amex-auth` | Equivalent of Compose `init: true`: the pause container reaps orphaned browser processes | Patching the image with an init |

## Gotchas / Pitfalls

- **External database credentials must be explicit.** With `postgresql.enabled=false`, provide
  `externalDatabase.existingSecret`, `secrets.existingSecret` or `secrets.postgresPassword`.
  Rendering fails without one instead of generating a password the external server does not know.
  Both the app and the migration Job use the selected source.
- **Existing PostgreSQL claims must match `postgresql.pgdata`.** The default
  `/var/lib/postgresql/data/pgdata` avoids `lost+found` on fresh volumes. A Compose volume or
  restore with `PG_VERSION` at the root needs `postgresql.pgdata=/var/lib/postgresql/data`
  before the first start. Otherwise PostgreSQL initializes a separate empty database in `pgdata/`.
  Back up first and use PostgreSQL 16-compatible data or restore a logical dump.
- **Inline secret changes do not restart PostgreSQL.** Its pod has no secret checksum;
  unrelated application key rotations must not cause database downtime. Updating the password
  environment variable does not rotate credentials in an initialized database. Coordinate server
  and Secret password changes and restart consumers as needed.

- **Bundled password changes require coordination.** When `lookup` finds an existing
  password, a differing inline password fails rendering. Inline values are converted to
  strings before comparison, so numeric passwords (including zero) and booleans supplied
  through `--set` remain stable across upgrades. First `ALTER ROLE`, then
  update `postgres-password` in the Kubernetes Secret, then set the same inline value
  and restart consumers. The chart README documents the maintenance procedure.
  External databases and externally managed Secrets remain operator-managed.
- **Switching from external to bundled PostgreSQL needs one upgrade without the hook.**
  Use `--set postgresql.enabled=true --set migrationJob.enabled=false` for that
  upgrade because the new database resources do not exist when the hook runs.
  The app migrates the new empty database on startup; once ready, re-enable the Job
  for subsequent upgrades. This does not transfer existing data; plan a separate
  backup and restore if the existing database must be preserved.
- **Generated Secrets stay ordinary kept resources.** A Secret hook without a delete
  policy defaults to `before-hook-creation`; that deletion ignores `resource-policy: keep`.
  A failed recreation could lose credentials. Keep the ordinary Secret and migration guard.
- **Generated database credentials must precede the migration hook.** Live upgrades reject a
  missing generated `postgres-password` or a different inline password before starting
  the Job. Before switching away from `secrets.existingSecret`, provision the generated
  Secret with the current database password, sidecar key and any optional app keys.
  For external database password changes, rotate the server credential and update the
  generated Secret before upgrading, during a maintenance window. This avoids using
  stale credentials or waiting for a Secret applied only after the pre-upgrade hook.
  Operator-managed Secrets and explicit environment overrides must also exist beforehand.
  Offline client previews skip the guard when neither the Secret data nor the namespace
  is visible through `lookup`, so `helm template --is-upgrade`, client dry-runs and
  `helm diff` remain usable. Use a server dry-run to validate live credentials.
- **App readiness has no startup deadline.** The default `app.startupProbe` is `{}`,
  so slow migrations and database outages hold readiness without restarting the app.
  TCP liveness remains enabled. PostgreSQL uses `pg_isready` startup checks every
  ten seconds, with 60 failures allowed before liveness takes over during recovery.
- **Fullnames must start with a lowercase letter.** Java cannot parse a single-label
  sidecar URL host starting with a digit; rendering fails with a clear error. Set a
  letter-prefixed `fullnameOverride` for numeric release names.
- **Long resource names use a hash.** Fullnames up to 40 characters stay unchanged;
  longer ones use a 33-character prefix plus a hyphen and six-character hash.
  Previously truncated names change on upgrade; plan existing claim and Secret
  migration before upgrading such releases.

- **GitOps renderers** (Argo CD) have no `lookup`: the generated Secret changes on every render.
  `secrets.existingSecret` is required there. Argo CD also maps `pre-upgrade` to PreSync, which
  runs on the first sync before the bundled PostgreSQL exists: disable `migrationJob` for that
  sync. When moving an existing installation to an external Secret, preserve the current
  sidecar API key and database password. Copy `/data/.secrets/crypto_key` and `jwt_secret`
  into the corresponding Secret keys, or leave those keys absent with persistent `/data`.
  Never generate replacement keys: environment values override the files and lose access to
  existing encrypted credentials.
- **All Secret rotation requires an explicit rollout.** No pod annotation hashes secret
  values: a plaintext-derived checksum would let pod readers guess weak passwords offline.
  Inline and external Secret changes therefore require an operator rollout. For JWT or
  database password changes, restart only the app; unrelated keys must not interrupt
  sidecar browser sessions. For sidecar API key rotation, restart the app and enabled
  sidecars together. See the chart README for the command. Never rotate the encryption
  key without a data migration.
- **Explicit app environment entries replace chart defaults by name.** This includes app keys,
  sidecar URLs and the Enable Banking private key path. `app.extraEnv` wins over `app.env`;
  duplicate names within `extraEnv` fail rendering with a clear error. The environment is
  merged by name before rendering to keep the list valid for Helm 4 server-side apply.
- **Migration database context matches the app.** Shared helpers render datasource defaults
  and `app.env`/`app.extraEnv` overrides; explicit overrides replace defaults by name.
  The Job also inherits `app.envFrom`, extra volumes/mounts (including database CA files),
  pod labels, pod annotations (including mesh injection opt-outs) and node selector, tolerations and affinity. It keeps its `migrate` component
  label and does not attach the app's `/data` PVC. The CLI password falls back from
  `SPRING_DATASOURCE_PASSWORD` to `POSTGRES_PASSWORD`, as the app entrypoint does.
  Explicit env entries take precedence over `envFrom`, so generated datasource values
  must be overridden through `app.env` or `app.extraEnv`. Hook dependencies must exist
  before the upgrade, before Helm applies ordinary resources.
- **Health tests support private registries and scheduling.** `tests.image` configures the
  wget-compatible image (default `busybox:1.37`); pull policy falls back to the global
  policy. The pod inherits global pull secrets and app node selector, tolerations and affinity.
- **Numeric inline secrets are stringified before encoding.** Numeric `--set` values render
  successfully for all four secret keys; use `--set-string` to preserve leading zeros.
  Quote numeric secrets in values files: floating-point inputs fail with an actionable
  error because stringification can produce scientific notation or lose precision.
  Null and empty inline values preserve stored keys through `lookup`. Without a stored key,
  required keys are generated and optional app keys remain absent.
- **The migration Job does not back up or rehearse.** `docker/deploy.sh` restores a backup into a
  clone first; on Kubernetes, backups belong to the storage or database operator. For bundled
  PostgreSQL, use `kubectl exec` into its pod to run `pg_dump` (command in the chart README);
  separate backup pods are blocked by the default NetworkPolicy.
- **Use `helm upgrade --timeout 35m`** for the default 1800-second migration deadline. If that
  deadline increases, increase Helm's timeout beyond it with startup margin. After a timeout,
  inspect the hook and let it finish before retrying: the next upgrade deletes the old Job.
- **Wizard values win over `app.env`.** `ENABLEBANKING_REDIRECT_URI`, `ALLOWED_ORIGINS` and
  `SECURE_COOKIES` are database-first once setup completed.
- **Sidecar images run as named, non-numeric users**, so `runAsNonRoot` cannot be verified by the
  kubelet and is not set; privilege escalation is disabled and all capabilities dropped instead.
- **New sidecar persistence has PVC defaults.** Setting `persistence.enabled` and `mountPath`
  is enough to request storage; omitted `accessModes` and `size` render as
  `[ReadWriteOnce]` and `1Gi`. Explicit per-sidecar values and existing claims remain supported.
- **`PICSOU_PROXY_IP` is left unset.** It names one exact trusted proxy address
  ([trusted proxy ADR](../decisions/2026-10-31-trusted-proxy-client-address.md)); ingress controller
  pods change addresses, so the app sees the controller pod's address and per-address limits apply
  per controller pod. `app.env` can set it when the controller has one stable address.
  `X-Forwarded-Proto` is honoured regardless, so HTTPS same-origin detection works without it.
- **NetworkPolicies need an enforcing CNI.** On Flannel (Talos default) they are created and
  ignored: a pod in the namespace still reaches the sidecars and PostgreSQL.
- **Pod Security `baseline` is required**, `restricted` rejects the app (root under supervisord),
  PostgreSQL and the sidecars (non-numeric users).
- **Annotation values are quoted** in pod templates: `--set app.podAnnotations.x=1` would
  otherwise send a number, which the API server rejects.
- **A missing sidecar that is not standard** (volumes, networks, `init`, extra variables) is left out of
  the published chart until `values.yaml` and the templates support it; `sync-sidecars.sh --check`
  lists it. For every existing entry, volumes require `persistence.enabled`, `init: true`
  requires `shareProcessNamespace`, and extra variables must exist in `env` or
  `extraEnv`; missing equivalents emit `drift` and preserve the entry on `--apply`.
  HTTP and HTTPS app URLs are recognized. Compose networks are acknowledged through
  NetworkPolicies for every sidecar; other unsupported keys still warn. These checks verify
  chart capabilities, not individual mount or network-rule equivalence. JSON mode
  emits only an array, even with `--github`; annotations are restricted to text mode.
- **A checkout of the repository describes main** (`appVersion: nightly`, `version: 0.0.0-main`);
  only published charts carry a release version. Local fork installs must set
  `global.imageRepository` and `global.imageTag` to their own published images; CI
  stamps these automatically.

## Tests

- `helm lint --strict charts/picsou` and two `helm template` renders (`helm-chart` CI job)
- `charts/picsou/tests/test_helm_chart_sync.py` (run by the `helm-chart` job): the repository inventory
  check accepts both in-sync and drift results and fails on script errors; synthetic fixtures
  verify `--check` reports each kind of difference, including nonstandard settings on known
  sidecars; `--apply` adds only standard
  sidecars, disables stale ones and keeps unrelated values byte for byte; a second `--apply`
  is a no-op; port and URL-variable mismatches disable sidecars on `--apply`, including custom ones;
  generic volume, init and environment capabilities are checked for every listed entry;
  Compose networks are acknowledged through NetworkPolicies; HTTP and HTTPS wiring is parsed;
  `--github` warns about unacknowledged differences but not added sidecars, and JSON output
  stays parseable with GitHub mode; the frozen supported inventory emits no warnings
- `sync-sidecars.sh` on modified Compose files: new standard sidecar (added by `--apply`, values
  otherwise unchanged byte for byte), sidecar with a volume and extra variable (reported, not
  added), sidecar without an app URL, removed sidecar, changed port; idempotent second `--apply`
- `.github/tests` workflow tests, extended with the `helm-chart` selection cases
- `helm template` of the default values and of a values set with external database, existing
  secrets, Ingress, HTTPRoute and disabled sidecars, validated with `kubeconform -strict`
- `charts/picsou/tests/test_review_regressions.py` packages the chart and verifies that the
  rendered package retains the `test-health` hook while excluding local Python tests.
  It also checks database TLS mounts, environment overrides and password fallback, migration
  scheduling, labels and quoted mesh annotations, missing or changing generated database
  passwords on upgrade (including explicit password and external Secret overrides),
  numeric and null inline secrets, existing-Secret upgrades with numeric and boolean inline
  values, stored-key fallback, numeric fullname rejection,
  disabled-sidecar loopback URLs, explicit Secret rotation without password fingerprints, all generated
  app environment overrides, duplicate-name rejection, and health-test images and scheduling.
  Review regressions also verify stored-password mismatch rejection, the zero/one replica
  boundary, reserved pod labels, app readiness without a startup deadline, PostgreSQL recovery
  probes, the documented database transition and distinct long release names.
- Manual cluster check: `helm test` runs the `test-health` pod, which calls `/actuator/health`
  through the app Service. This requires a running release and is not run by the render tests.
- Validated on 2026-10-31 on a Talos cluster (Kubernetes 1.37, Trident NFS, Traefik, cert-manager
  with an internal ACME CA): all pods ready in about two minutes, 114 migrations applied on first
  boot, HTTPS through the Ingress, sidecar key enforced (401 without it), two `helm upgrade` runs
  with the migration Job succeeding and the generated secrets unchanged

## Links

- Related ADR: [Helm chart published by CI](../decisions/2026-10-31-helm-chart-published-from-ci.md),
  [Rehearse release migrations](../decisions/2026-10-08-rehearse-release-migrations.md),
  [Caddy as an opt-in TLS terminator](../decisions/2026-07-19-caddy-opt-in-tls-profile.md)
- Compose counterpart: [docker-deployment.md](./docker-deployment.md)
