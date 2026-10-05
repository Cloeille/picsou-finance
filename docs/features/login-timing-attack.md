# Feature: Login timing equalization (username-enumeration defense)

> Last updated: 2026-06-27

## Context

`POST /api/auth/login` returned a generic `401` for both a wrong password and an
unknown username — but the two paths did **unequal work**. An existing user paid a
full bcrypt comparison (~300–450 ms); an unknown username failed in ~15–25 ms (no
stored hash to check). That stable ~20× latency gap let an unauthenticated caller
enumerate valid usernames by timing alone, despite identical status code and body
(CWE-208, observable timing discrepancy). Reported as **GHSA-ww5m-pxgq-8qq6**.

## How it works

`AuthController` precomputes one throwaway bcrypt hash at construction
(`dummyPasswordHash = passwordEncoder.encode("login-timing-equalizer")`), using the
same `PasswordEncoder` bean — and therefore the same cost factor — as real password
hashes.

In `login()`, when the username is **not found**, the controller still runs a real
`passwordEncoder.matches(req.password(), dummyPasswordHash)` before throwing
`BadCredentialsException`. Both failure paths — unknown user and wrong password —
now perform exactly one bcrypt comparison of identical cost, so response latency no
longer distinguishes "no such user" from "wrong password".

### Key files

- `backend/src/main/java/com/picsou/controller/AuthController.java` — the `dummyPasswordHash` field (built in the
  constructor) and the unknown-user branch in `login()` that runs the decoy
  `passwordEncoder.matches(...)` before throwing.

### Flow

```
POST /api/auth/login
  → rate-limit check (5 / 15 min per IP, bucket consumed BEFORE the lookup)
  → findByUsernameWithMember(username)
      ├─ absent  → passwordEncoder.matches(password, dummyPasswordHash)   ← decoy bcrypt
      │             → throw BadCredentialsException → 401
      └─ present → [recovery / activation gates] → passwordEncoder.matches(password, user.hash)
                    → false → throw BadCredentialsException → 401
  (both 401 paths now cost exactly one bcrypt round → no timing oracle)
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Precompute one dummy hash in the constructor | A valid same-cost `$2a$…$` hash is needed only once; encoding per request would add bcrypt **encode** cost (not the **matches** cost we want to mirror) and waste CPU | Encode a constant on every unknown-user request |
| Run `matches()` and discard the result | We want only the constant-time work; the decoy can never match | A fixed `Thread.sleep` delay — fragile, leaks through variance, and not tied to the real bcrypt cost |
| Equalize timing, don't change the response | The response was already identical (same generic 401); only latency leaked | Returning a different/earlier error, which would create new oracles |

## Gotchas / Pitfalls

- **The `boolean ignored = passwordEncoder.matches(...)` assignment is intentional.**
  The result is never read; it exists solely to force the bcrypt work. Do not "clean
  it up" by deleting the call — that re-opens the oracle.
- **The cost factor must track the real encoder.** The decoy is encoded with the same
  `PasswordEncoder` bean, so a change to the bcrypt strength is mirrored automatically.
  Never hardcode a `$2a$12$…` literal as the dummy hash.
- **Rate limiting is the first line of defense, not this fix.** Login is capped at
  5 attempts / 15 min per IP (`loginBuckets`) and the bucket is consumed *before* the
  user lookup, so each timing probe still costs a token. The decoy closes the residual
  oracle that remained within that budget.
- **The other early-return branches are deliberately oracle-free.** The admin-recovery
  branch returns the same `403` regardless of password (see
  [admin-recovery.md](./admin-recovery.md)); the not-activated branch runs *after* the
  password check, so it contributes no extra timing signal.

## Tests

- `AuthControllerTest.login_unknownUserAndWrongPassword_payIdenticalBcryptCost_soLatencyRevealsNothing`
  — drives the controller with a real spied `BCryptPasswordEncoder(12)`. Asserts that
  both the unknown-user and wrong-password paths throw the same
  `BadCredentialsException`, call `matches()` **exactly twice** in total (one round
  each, no short-circuit) against `$2a$12$` hashes with the submitted password, and
  issue no session. It proves the oracle stays closed **without** measuring (inherently
  flaky) wall-clock time.

## Security advisory / CVE

- **Advisory:** GHSA-ww5m-pxgq-8qq6 (GitHub draft at time of writing).
- **Class:** CWE-208 (Observable Timing Discrepancy) → user enumeration.
- **Severity:** CVSS 3.1 = 5.3 (`AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N`). Real-world
  impact is **Low** on a self-hosted deployment (tiny user set, often LAN-only, login
  rate-limited) — but the leak is observable and the fix is trivial.
- **Affected:** ≥ 1.1.0.
- **Fixed by:** commit `877ac0f` (+ test `ae1607b`), merged to `main`. Pending
  propagation to the `1.1.0` release line and a tagged patched release; update the
  advisory's "patched versions" to that release before publishing the CVE.
- **Reporter & fix author:** Romain ([@Aguix](https://github.com/Aguix)) — contributed
  the fix and test via the advisory's private fork.

## Links

- Lesson: [Test a constant-time fix by counting crypto ops, not wall-clock time](../lessons/timing-attack-test-by-op-count.md).
- Related: [2FA (TOTP) and Remember Me](./mfa-and-remember-me.md) — documents the login
  endpoint, its cookies, and the login threat model.
- Related: [Admin recovery (lost-admin console reset)](./admin-recovery.md) — the
  oracle-free admin-recovery branch in the same `login()` method.
- Related: [CORS & cookie security](./security-cors-cookies.md).
