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
  second-factor client and HTML-to-text normalization. What differs per bank
  is a frozen `Site`; each sidecar keeps its own routes and parsers.
- Both packages ship in the `python` base image through a `.pth` file under
  `/opt/sidecar-base/lib`, so the `chromium` and `camoufox` bases and every
  sidecar built on them (AMEX and Revolut included) get them. The sidecar test
  jobs set `PYTHONPATH=/app`, which a `PYTHONPATH` install would hide. The base
  image copies the Euro-Information runtime modules explicitly. Its build context
  excludes local bytecode and both packages' test helpers; CI mounts the shared
  auth contract and the relevant Euro-Information fixtures read-only for tests.
- Every sidecar's `test_sidecar_auth.py` binds the contract to its app; the
  `sidecar-base` CI job tests the package on its own.
- `take()` disposes expired states and returns them as missing; `put()` inserts
  immediately when there is room. Only a full store sweeps expired states before
  rechecking capacity under the lock. This keeps retries from waiting on unrelated
  browser disposal and avoids a second sweep after initiation, unless concurrent
  initiations fill the store in the meantime. Routes use `put_or_raise()` to report a
  full store with 503 while retaining responsibility for refused resources on
  initial parking. Retrying routes instead transfer ownership to `repark()`,
  which disposes a state if parking fails or is cancelled. The sweeper logs only
  the exception type on failure, since upstream exception text can contain URLs
  or credentials, then continues with the next sweep.
- Pending expiry and shutdown remove and dispose of one state at a time. If
  disposal raises or is cancelled, unvisited states remain in the store for a
  later sweep or shutdown cleanup. Disposers remain responsible for releasing
  all resources of the state they have already received.

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
