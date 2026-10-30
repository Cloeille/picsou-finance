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
- **sidecars** — one Deployment and Service per entry of `sidecars` (the eleven `*-auth`
  services of the Compose file), each toggled by `enabled`. The app receives the matching
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

### Key files

- `charts/picsou/values.yaml` — every option, commented
- `charts/picsou/templates/app-deployment.yaml` — app pod, environment and sidecar URLs
- `charts/picsou/templates/sidecars.yaml` — sidecar Deployments and Services
- `charts/picsou/templates/secret.yaml` — generated Secret
- `charts/picsou/templates/migration-job.yaml` — pre-upgrade migration and verification
- `charts/picsou/templates/networkpolicy.yaml` — isolation of sidecars and database
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
- **Renaming a sidecar** in `docker/docker-compose.yml` needs the same change in
  `charts/picsou/values.yaml` (`sidecars` key, `urlEnv`, `port`).

## Tests

- `helm lint --strict charts/picsou`
- `helm template` of the default values and of a values set with external database, existing
  secrets, Ingress, HTTPRoute and disabled sidecars, validated with `kubeconform -strict`
- `helm test` — `test-health` pod calls `/actuator/health` through the app Service

## Links

- Related ADR: [Rehearse release migrations](../decisions/2026-10-08-rehearse-release-migrations.md),
  [Caddy as an opt-in TLS terminator](../decisions/2026-07-19-caddy-opt-in-tls-profile.md)
- Compose counterpart: [docker-deployment.md](./docker-deployment.md)
