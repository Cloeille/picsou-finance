# Feature: Kubernetes deployment (Helm chart)

> Last updated: 2026-10-31

## Context

Picsou ships as a Docker Compose stack. Users who already run a Kubernetes cluster at home want
to deploy it next to their other services, with their own ingress controller, certificate source
and database operator. `charts/picsou` is the Kubernetes counterpart of `docker/docker-compose.yml`:
same published images, same configuration surface, Kubernetes-native exposure and storage.

## How it works

One release creates:

- **app** — a `Recreate` Deployment of `ghcr.io/cloeille/picsou-finance` (nginx + Spring Boot
  under supervisord), a `/data` PVC and a `ClusterIP` Service on 8080.
- **sidecars** — one Deployment and Service per entry of `sidecars` (the `*-auth` services of
  the Compose file), each toggled by `enabled`. The app receives the matching
  `*_AUTH_URL` for every enabled sidecar. `revolut-auth` gets a PVC for its browser profiles.
- **postgresql** — optional single-replica StatefulSet of `postgres:16-alpine`, or
  `externalDatabase` for an operator-managed cluster.
- **Secret** — `sidecar-api-key` and `postgres-password`, generated once and preserved through
  `lookup`, or an `existingSecret`. `jwt-secret` and `crypto-encryption-key` are optional keys;
  when absent the image entrypoint generates them under `/data/.secrets` as on Compose.
- **migration Job** — `pre-upgrade` hook running `ReleaseMigrationCli migrate` then `verify`
  with the new image.
- **NetworkPolicies** — ingress to each sidecar and to PostgreSQL only from the app (and the
  migration Job for PostgreSQL), optional restriction of the app's own ingress.
- **Ingress or HTTPRoute** — optional, TLS terminated by the controller.

### Publishing

The `chart` job of `.github/workflows/docker.yml` runs after every image of the commit is pushed
and publishes to `oci://ghcr.io/<owner>/charts/picsou`:

- a version tag publishes the chart under that version, `appVersion` = the release;
- a push to `main` publishes `<latest release + 1 patch>-main.<UTC timestamp>.g<short sha>`, a
  pre-release that sorts above the latest release and that Helm only installs with `--devel`, with
  `appVersion` = `sha-<short sha>`, the image tag `docker/metadata-action` gives every image of the
  commit.

Before packaging, `sync-sidecars.sh --apply` adds to the packaged copy the standard sidecars that
`values.yaml` does not list yet, and `global.imageRepository` is set to `ghcr.io/<repository>`, where
this run pushed the images, so a chart published from a fork runs that fork's images (nothing is
committed back). The `helm-chart` job of `ci.yml`
lints and renders the chart and runs `sync-sidecars.sh --check` on pull requests that touch the
chart, the Compose file or the workflows; differences are warnings, a broken chart fails.

### Key files

- `charts/picsou/values.yaml` — every option, commented
- `charts/picsou/templates/app-deployment.yaml` — app pod, environment and sidecar URLs
- `charts/picsou/templates/sidecars.yaml` — sidecar Deployments and Services
- `charts/picsou/templates/secret.yaml` — generated Secret
- `charts/picsou/templates/migration-job.yaml` — pre-upgrade migration and verification
- `charts/picsou/templates/networkpolicy.yaml` — isolation of sidecars and database
- `charts/picsou/sync-sidecars.sh` — compares the Compose sidecars with `values.yaml`, adds the
  standard ones on `--apply`
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
| Sidecar Services named `<release>-<sidecar>`, URLs without a domain | `SidecarBaseUrl` accepts plain HTTP only on a single-label host; the fullname is capped at 40 characters so the longest name stays a valid DNS label | Cluster FQDNs (`*.svc.cluster.local`), which would require HTTPS between pods |
| JWT and encryption keys left to the entrypoint by default | Matches Compose and keeps the encryption key off any value the chart could regenerate | Generating them in the chart Secret, which a `helm template` renderer would rotate on every sync and make stored secrets unreadable |
| `lookup` + `helm.sh/resource-policy: keep` for the generated Secret | Values survive upgrades and uninstall | Regenerating on each upgrade |
| Chart version and `appVersion` stamped at publish time | One chart per release and per commit of main, each pinned to its own images, without a manual bump | A version kept in `Chart.yaml` and bumped by hand |
| Main channel pinned to `sha-<short>` images | A chart version always deploys the same code | `nightly`, which moves under an installed chart |
| Standard sidecars added at publish time, never committed | New sidecars reach the main channel without a chart PR, and CI never writes to the repository | Failing pull requests that add a sidecar; a bot committing to main |
| Pre-upgrade migration Job with the release CLI | Applies the [migration ADR](../decisions/2026-10-08-rehearse-release-migrations.md): migrate and verify before replacing the API | Relying on startup Flyway only, which fails after the old pod is gone |
| Bundled `postgres:16-alpine` StatefulSet | Same image as Compose, no dependency on a third-party chart | Bitnami PostgreSQL subchart |
| Liveness on TCP, readiness on `/actuator/health` | Health includes the database; a database outage must not restart the app in a loop. supervisord restarts a dead JVM | Liveness on `/actuator/health`; `/actuator/health/liveness` is not permitted by `SecurityConfig`/`SetupFilter` |
| `shareProcessNamespace` for `amex-auth` | Equivalent of Compose `init: true`: the pause container reaps orphaned browser processes | Patching the image with an init |

## Gotchas / Pitfalls

- **GitOps renderers** (Argo CD) have no `lookup`: the generated Secret changes on every render.
  `secrets.existingSecret` is required there. Argo CD also maps `pre-upgrade` to PreSync, which
  runs on the first sync before the bundled PostgreSQL exists: disable `migrationJob` for that
  sync.
- **The migration Job does not back up or rehearse.** `docker/deploy.sh` restores a backup into a
  clone first; on Kubernetes, backups belong to the storage or database operator.
- **Wizard values win over `app.env`.** `ENABLEBANKING_REDIRECT_URI`, `ALLOWED_ORIGINS` and
  `SECURE_COOKIES` are database-first once setup completed.
- **Sidecar images run as named, non-numeric users**, so `runAsNonRoot` cannot be verified by the
  kubelet and is not set; privilege escalation is disabled and all capabilities dropped instead.
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
- **A sidecar that is not standard** (volumes, networks, `init`, extra variables) is left out of
  the published chart until `values.yaml` and the templates support it; `sync-sidecars.sh --check`
  lists it.
- **A checkout of the repository describes main** (`appVersion: nightly`, `version: 0.0.0-main`);
  only published charts carry a release version.

## Tests

- `helm lint --strict charts/picsou` and two `helm template` renders (`helm-chart` CI job)
- `.github/tests/test_helm_chart_sync.py` (run by the `helm-chart` job): the repository chart lists
  every Compose sidecar; `--check` reports each kind of difference; `--apply` adds only standard
  sidecars and keeps the rest of `values.yaml` byte for byte; a second `--apply` is a no-op; a port
  change is a mismatch; `--github` warns about everything but added sidecars
- `sync-sidecars.sh` on modified Compose files: new standard sidecar (added by `--apply`, values
  otherwise unchanged byte for byte), sidecar with a volume and extra variable (reported, not
  added), sidecar without an app URL, removed sidecar, changed port; idempotent second `--apply`
- `.github/tests` workflow tests, extended with the `helm-chart` selection cases
- `helm template` of the default values and of a values set with external database, existing
  secrets, Ingress, HTTPRoute and disabled sidecars, validated with `kubeconform -strict`
- `helm test` — `test-health` pod calls `/actuator/health` through the app Service
- Validated on 2026-10-31 on a Talos cluster (Kubernetes 1.37, Trident NFS, Traefik, cert-manager
  with an internal ACME CA): all pods ready in about two minutes, 114 migrations applied on first
  boot, HTTPS through the Ingress, sidecar key enforced (401 without it), two `helm upgrade` runs
  with the migration Job succeeding and the generated secrets unchanged

## Links

- Related ADR: [Helm chart published by CI](../decisions/2026-10-31-helm-chart-published-from-ci.md),
  [Rehearse release migrations](../decisions/2026-10-08-rehearse-release-migrations.md),
  [Caddy as an opt-in TLS terminator](../decisions/2026-07-19-caddy-opt-in-tls-profile.md)
- Compose counterpart: [docker-deployment.md](./docker-deployment.md)
