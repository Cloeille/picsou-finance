# ADR: BNP Paribas and Hello bank! through a dedicated Camoufox sidecar

> Date: 2026-11-09
> Status: 🟡 Proposed - implemented, not verified against a real account (issue #306)

## Context

Neither bank offers a PSD2 path that covers savings, securities and life insurance. The bank's own web
client does. Its login is a PingFederate OIDC page protected by Akamai Bot Manager, ThreatMetrix and a
nonce-based CSP, with a shuffled 10-button keypad whose clicked positions are written by the page's
JavaScript. A live recon of the public pages (no login) corrected the open-source woob module on the login
hosts, the keypad (text buttons, not images), the form fields, the error channel (`error_code`) and the
session-death signal (`codeRetour: "-10"`, a string).

## Decision

1. **A sidecar, `services/bnp-auth`, on the Camoufox base** (like Amex), because the login only works
   inside a real browser. It types the customer number, reads the ten key labels, and clicks the keys the
   member chose by position. It never synthesises the login POST.
2. **The member clicks the keypad in Picsou**; positions are the only thing that leaves the UI, and the
   backend and sidecar validate them (six integers in 0..9).
3. **Both second factors are implemented** (Cle Digitale approval and SMS code) because which one applies
   is unverified; the sidecar classifies the page and stops with `MFA_TYPE_UNSUPPORTED` when it cannot.
4. **Cookies only, encrypted, in `sidecar_session`** (migration V150, rebuilt from V149). Nothing the
   member typed or clicked is stored.
5. **Strict allow-list of six hosts**, per brand, every request and redirect hop; unknown host = stop and
   log the host name only.
6. **Fail closed**: unknown product kinds are listed, incomplete snapshots never replace data, unexpected
   pages stop the attempt.

## Consequences

- The post-login endpoints and second-factor page are unverified; the first owner-assisted live run may
  require changes to `bank.py` and `parsers.py` only (not to the backend contract).
- Akamai may refuse the sidecar's network (403 was seen from a datacenter IP). That is reported as
  `UPSTREAM_UNAVAILABLE`; no evasion is attempted.
- A Camoufox sidecar is heavier than an httpx one; it shares the base image with Amex and Revolut.
- Status moves to Accepted only after a real-account run by the maintainer or an owner.
