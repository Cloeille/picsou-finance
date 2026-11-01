# ADR: Sidecars build on three shared base images

> Date: 2026-10-21
> Status: ✅ Active
> Amended: 2026-10-31 — shared Camoufox target for AMEX and Revolut (#330).
> Amended: 2026-11-01 — Playwright 1.64.1 for both browser targets (#386).

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

`services/base/Dockerfile` builds three targets from one common pin set
(`services/base/requirements.txt`, plus browser-specific `requirements-chromium.txt`
and `requirements-camoufox.txt`):

- `python`: Python 3.12 slim bookworm (pinned digest) and fastapi, uvicorn,
  pydantic, httpx. Used by bourso-auth, sofidy-auth, degiro-auth, cmes-auth
  and cic-auth.
- `chromium`: `python` plus Playwright 1.64.1, its Chromium and the Chromium
  system libraries. Used by amundi, bourse-direct, caisse-epargne, corum,
  fortuneo and tr-auth.
- `camoufox`: `python` plus Playwright 1.64.1, Camoufox 0.4.11, pinned Firefox
  152.0.4-beta.30 and Xvfb. Used by amex-auth and revolut-auth. The shared
  installer selects `lin.x86_64` or `lin.arm64` from the target CPU and rejects
  unsupported architectures. The installer does not alter the browser or Playwright pins.

Each sidecar Dockerfile is `FROM ${SIDECAR_BASE}` plus its own Unix user and its
code. No sidecar installs a package.

- **Publishing**: `docker.yml` builds and pushes all three bases first, then passes
  each sidecar its base **by digest**. Every published sidecar therefore shares
  the base layers byte for byte, and a pull fetches them once.
- **Compose `--build`**: the bases are build-only Compose services
  (`scale: 0`, never started, not a profile: `docker compose down` and `config`
  without the profile would not see them and reject the sidecars' contexts); each sidecar points at one with
  `additional_contexts: service:…`, so Compose builds it first.
- **CI**: each sidecar job builds its base, then the sidecar, then runs the same
  tests as before. A change under `services/base/` therefore reruns every
  sidecar's tests.

The Camoufox target inherits `python`, independently of `chromium`, so their
Playwright versions cannot overwrite each other. It downloads Firefox, addons and
the GeoIP database once during the base build. `XDG_CACHE_HOME=/opt/camoufox-cache`
puts the browser and addons outside /app and the service homes; both non-root
users read the same assets without copying/chowning browser layers. The shared
entrypoint takes the port, display and screen from each service's environment,
removes stale Xvfb locks on restart, and refuses to start the API without a ready
display. Ports and login code are unchanged. Revolut pins `revoauth` to UID 999,
the owner of existing `revolut_profiles` volumes and their mode-700 member directories.
Its group remains automatically allocated because the base already uses GID 999.
AMEX has no persistent volume, so its service UID remains automatically allocated.
CI checks the built Revolut image's UID with a test run inside that image.
CI launches both headless and headful Firefox in both derived images, on native
ARM64 and on AMD64, using offline HTML; this does not verify bank login compatibility.

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
  tr and degiro from fastapi 0.111 to 0.141 (with uvicorn 0.52, pydantic 2.13,
  and httpx 0.28 for tr and degiro). These four lose uvicorn's `[standard]`
  extras, which fastapi 0.111 pulled in; the other five already ran without
  them. Their unit tests pass; Trade Republic, Bourse Direct and Caisse
  d'Epargne passed a live login and sync on a disposable instance built from
  this branch (2026-10-21). **Fortuneo and DEGIRO have unit and image tests
  only**: a live login is still owed by someone with an account.
- **One base failure blocks every published image.** `build` needs `base`, so
  the app and the Camoufox images wait on it too. Accepted: the base is the
  first thing to fix either way.
- **Dependabot bumps the base digest** (`docker` entry for `/services/base`),
  so Debian security fixes still reach every sidecar.

## Consequences

- New `services/base/` and three GHCR images, `sidecar-python`,
  `sidecar-chromium` and `sidecar-camoufox`, tagged like the sidecars (branch, `latest`, semver, SHA). They are build inputs, not services.
- `docker compose pull` is unchanged for users. `docker compose up --build`
  builds the three bases first and needs Compose v2.33+.
- **The three base packages must be public on GHCR.** They are labelled with the
  repository as source, but a package first pushed by `GITHUB_TOKEN` can start
  private. Sidecar layers may be mounted from them, so after the first publish
  the owner checks their visibility and an anonymous `docker pull` of a sidecar.
- Chromium moves to `/opt/playwright-browsers` (was `/app/playwright-browsers`),
  so a sidecar's `chown -R /app` no longer copies it into a new layer.
- tr-auth gets a CI job; it had none. `compose-sidecar-build` builds three
  Chromium/Python/Camoufox sidecars through Compose (bases, `additional_contexts`) and tears down without
  a profile, so the wiring is tested as users run it.

## Dependency update coverage

Dependabot checks Maven in /backend, bun in /frontend, all shared pip requirements
in /services/base, GitHub Actions, every Dockerfile directory, and Compose image references weekly. Updates
use the default branch. Camoufox/Playwright bumps still require compatibility
review and passing browser smoke tests.
