# ADR: Sidecars share one Python package for their plumbing

> Date: 2026-11-01
> Status: ✅ Active

## Context

Each of the 13 bank sidecars carried its own copy of the shared-key middleware,
the FastAPI lifespan, `/health`, the 400 validation envelope, the request log,
log sanitising and a pending-login store, plus a near-copy of the same
`test_sidecar_auth.py`. The copies had drifted: AMEX answered a bad key with
`invalid sidecar key` and loaded it lazily, Sofidy logged requests in its own
format, only some sweepers survived a failing sweep, and only three sidecars
escaped control characters in their whole log record. CIC and Crédit Mutuel
ran the same Euro-Information login flow from two copies, with `forms.py`
byte-identical (issue #338, part of #326).

## Decision

- **`services/base/picsou_sidecar`** holds the plumbing: `create_app()` (key
  middleware, lifespan, `/health`, validation envelope, request log),
  `PendingStore`, `configure_logging()`/`safe()`, and `contract.SidecarAuthContract`,
  the shared-key contract as one test class. Each sidecar keeps its routes,
  parsers and what its pending state pins (`dispose`).
- **`services/base/euro_information`** holds the CIC / Crédit Mutuel login and
  second-factor client. What differs per bank is a frozen `Site`; each sidecar
  keeps its own routes and parsers.
- Both packages ship in the `python` base image through a `.pth` file under
  `/opt/sidecar-base/lib`, so the `chromium` base and every sidecar built on it
  get them. The sidecar test jobs set `PYTHONPATH=/app`, which a `PYTHONPATH`
  install would hide. AMEX and Revolut keep their own Python and Camoufox pins
  and copy only that directory from the `python` base (`SIDECAR_LIB`).
- Every sidecar's `test_sidecar_auth.py` binds the contract to its app; the
  `sidecar-package` CI job tests the package on its own.

## Consequences

- A key, health or logging change is made once and reaches every sidecar.
  `secrets.compare_digest` appears in one non-test file.
- Every sidecar answers a bad key with `401 {"detail": "UNAUTHORIZED"}` and
  `WWW-Authenticate: Picsou-Sidecar-Key`; the backend matches only the header.
- A `services/base` change rebuilds and retests every sidecar, as a pin bump
  already did.
- Python under `services/` dropped from 38,455 to 35,189 lines.

## Links

- [Sidecars build on two shared base images](./2026-10-21-shared-sidecar-base-image.md)
- [Docker deployment](../features/docker-deployment.md)
