# ADR: Sidecars build on two shared base images

> Date: 2026-10-21
> Status: ✅ Active

## Context

Every sidecar image installed its own Python packages and, for six of them, its
own Chromium. The pins had drifted: four sidecars were still on fastapi 0.111
(and the Starlette range with published advisories that it pulls in), tr-auth on
Playwright 1.44 while the others used 1.52. A security bump had to be made in
nine `requirements.txt` files, and was not.

Each sidecar built its own copy of the same layers. Docker shares a layer between
images only when it is the same layer (same digest), so a Compose install pulled
and stored Chromium six times. Measured on 1.1.0 (issue #235,
`services/scripts/shared_env_check.py` in #242): 6 × 894 MiB of Chromium and
1,178 MiB of per-sidecar Python environments, against 894 MiB and 159 MiB for one
copy of each.

## Decision

`services/base/Dockerfile` builds two targets from one pin set
(`services/base/requirements.txt`, plus `requirements-chromium.txt`):

- `python`: Python 3.12 slim bookworm (pinned digest) and fastapi, uvicorn,
  pydantic, httpx. Used by bourso-auth, sofidy-auth and degiro-auth.
- `chromium`: `python` plus Playwright 1.52, its Chromium and the Chromium
  system libraries. Used by amundi, bourse-direct, caisse-epargne, corum,
  fortuneo and tr-auth.

Each sidecar Dockerfile is `FROM ${SIDECAR_BASE}` plus its own Unix user and its
code. No sidecar installs a package.

- **Publishing**: `docker.yml` builds and pushes both bases first, then passes
  each sidecar its base **by digest**. Every published sidecar therefore shares
  the base layers byte for byte, and a pull fetches them once.
- **Compose `--build`**: the bases are build-only Compose services
  (`profiles: ["sidecar-base"]`, never started); each sidecar points at one with
  `additional_contexts: service:…`, so Compose builds it first.
- **CI**: each sidecar job builds its base, then the sidecar, then runs the same
  tests as before. A change under `services/base/` therefore reruns every
  sidecar's tests.

amex-auth and revolut-auth keep their own Dockerfile: Camoufox needs Firefox and
Playwright 1.55.

## Alternatives considered

### Shared browser service (#242)

- **Pros**: one Chromium for a desktop app that has no Docker.
- **Cons**: Docker already gives one Chromium on disk (layer sharing) and one
  owner for every process (the daemon). Routing pages through one service would
  run every bank under one Unix user with `--no-sandbox`. Kept for the desktop
  app only.

### Same lines copied into every Dockerfile

- **Pros**: no build ordering.
- **Cons**: each image build produces its own layers, so nothing is shared at pull
  time, and the pins drift again.

### A requirements file per sidecar on top of a shared base

- **Pros**: a sidecar can pin a package the others do not need.
- **Cons**: brings back the drift this fixes. No sidecar needs one today; add a
  layer in that sidecar's Dockerfile if one ever does.

## Reasoning

The saving and the security benefit come from the same thing: one set of layers
and one pin set. The isolation stays as it is: one container, one Unix user and
one network policy per bank.

## Trade-offs accepted

- **One bump moves every bank at once.** CI runs every sidecar's tests on a base
  change, but only a live login proves a site still accepts the browser.
- **Browserless sidecars carry httpx** even when they do not use it (it is in
  the shared set). A few hundred KiB.
- **tr-auth moves from Playwright 1.44 to 1.52**, and bourse-direct, fortuneo,
  tr and degiro from fastapi 0.111 to 0.141. Their unit tests pass; Trade
  Republic needs a live login before release.

## Consequences

- New `services/base/` and two GHCR images, `sidecar-python` and
  `sidecar-chromium`, tagged by commit SHA. They are build inputs, not services.
- `docker compose pull` is unchanged for users. `docker compose up --build`
  builds the two bases first.
- Chromium moves to `/opt/playwright-browsers` (was `/app/playwright-browsers`),
  so a sidecar's `chown -R /app` no longer copies it into a new layer.
- tr-auth gets a CI job; it had none.
