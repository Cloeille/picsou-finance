# ADR: Helm chart in the repository, published by CI next to the images

> Date: 2026-10-31
> Status: ✅ Active

## Context

Picsou ships as a Docker Compose stack. Operators who already run Kubernetes want to deploy it next
to their other services, with their own ingress controller, certificate source and database
operator, instead of a Compose file on a VM.

A Kubernetes deployment depends on details that change with the application: the set of `*-auth`
sidecars (two were added in the last week of October 2026), their ports and URL variables, the
single-label host rule of `SidecarBaseUrl`, the release migration CLI, the entrypoint's secret
bootstrap. A chart that does not move with them breaks silently.

## Decision

1. **Ship the chart in this repository** under `charts/picsou`, using the published GHCR images.
2. **Publish it from CI** (`chart` job of `.github/workflows/docker.yml`) once every image of the
   commit is pushed, to `oci://ghcr.io/<owner>/charts/picsou`:
   - a version tag publishes that version, `appVersion` = the release;
   - a push to `main` publishes a pre-release `<latest release + 1 patch>-main.<timestamp>.g<sha>`
     pinned to the commit's `sha-<short>` images.
   Version, `appVersion` and `global.imageRepository` are stamped at publish time; nothing is
   committed back.
3. **Terminate TLS on the cluster's Ingress or Gateway**, not with the Caddy profile of the Compose
   stack.
4. **Never block a contributor on the chart.** `charts/picsou/sync-sidecars.sh` compares the Compose
   sidecars with `values.yaml`: publishing adds the standard ones to the packaged chart, the
   `helm-chart` job of `ci.yml` reports differences as warnings and fails only on a broken chart.
   This independent check is not a dependency of `ci-ok` or image publishing.
   Sidecars that need more than the generic template are left out of the published chart until
   the chart maintainer adds them.
5. **Upgrades migrate before the app is replaced**: a `pre-upgrade` Job runs `ReleaseMigrationCli
   migrate` then `verify`, as [the release migrations ADR](./2026-10-08-rehearse-release-migrations.md)
   requires of `docker/deploy.sh`.

## Alternatives considered

### Separate chart repository

- **Pros**: no change to this repository or its CI; releases at the chart maintainer's pace.
- **Cons**: lags behind every new sidecar and Compose change with nothing to notice it; cannot
  publish against the exact images of a commit; one more repository for users to find.

### Chart in the repository, not published (install from a checkout)

- **Pros**: no publishing job.
- **Cons**: users must clone the repository and pick a matching image tag by hand; no versioned
  chart for Argo CD or Flux to track.

### Classic Helm repository (`index.yaml` on GitHub Pages, chart-releaser)

- **Pros**: works with `helm repo add`, the older workflow.
- **Cons**: needs a `gh-pages` branch and Pages enabled; a second distribution channel besides
  GHCR. Helm 3.8+, Argo CD and Flux all read OCI charts, which keeps charts next to the images with
  the same permissions.

### Keep the Caddy terminator in the chart

- **Pros**: same TLS behaviour as the Compose `tls` profile.
- **Cons**: every cluster already has an ingress controller and a certificate source; a second TLS
  hop would duplicate them and fight over `:443`. The [Caddy ADR](./2026-07-19-caddy-opt-in-tls-profile.md)
  made Caddy opt-in precisely to avoid colliding with an existing proxy.

### Failing CI when the chart misses a sidecar

- **Pros**: the chart is complete on every merge.
- **Cons**: every sidecar pull request would need a chart change from its author; the chart would
  become a cost for contributors who do not use Kubernetes.

## Reasoning

The chart is only correct if it moves with the code, and only cheap for contributors if it never
gets in their way. Building it in the repository and publishing it from the same run as the images
gives both: a published chart always points at images that exist, built from the same commit, and
the chart check validates rendering independently of the application release gate.

## Trade-offs accepted

- A sidecar that needs volumes, networks, `init` or extra variables is missing from the published
  chart until the chart maintainer adds it.
- The migration Job migrates and verifies but does not back up or rehearse on a restored copy like
  `docker/deploy.sh`; backups belong to the operator's storage or database operator.
- Charts from `main` are pre-releases: Helm installs them only with `--devel` or a version
  constraint that allows pre-releases.
- Network policies are only effective with a CNI that enforces them.
- `PICSOU_PROXY_IP` trusts one exact address ([trusted proxy ADR](./2026-10-31-trusted-proxy-client-address.md)),
  which ingress controller pods do not have: by default the app sees the controller pod's address,
  so per-address limits apply per controller pod.

## Consequences

- New directory `charts/picsou` and script `charts/picsou/sync-sidecars.sh`.
- `docker.yml` gains a `chart` job after the image builds; `ci.yml` gains the `helm-chart` job,
  registered with the job selector but independent of the `ci-ok` and image-publishing gates.
- A new GHCR package, `charts/picsou`, which must be made public once after its first publication.
- Feature note: [helm-chart.md](../features/helm-chart.md).
