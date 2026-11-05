# ADR: Banque Populaire shares the Caisse d'Epargne code through a BPCE package and sync engine

> Date: 2026-11-05
> Status: ✅ Active

## Context

Banque Populaire (BP) and Caisse d'Epargne (CE) both belong to the BPCE group and
run the same web platform. A live run on a Banque Populaire Rives de Paris account
(2026-11-05) showed:

- the same login pages on `www.icgauth.banquepopulaire.fr`: identifier
  `neo-input-*`, ten shuffled `.keyboard-button` keys, Sécur'Pass in the
  `(modal:icg/cloudcard)` modal;
- the same OAuth replay chain: authorize with PKCE, SAML, `consume`, token;
- the same data API: `augmentedSynthesisViews`, `/pfm/user/v1.1/transactions`.

What differs: the hosts (`*-ext-bad-ib.banquepopulaire.fr` instead of
`*-ext-bad-ce.caisse-epargne.fr`), the login entry (`service=cyber`; `service=dei`
lands on an error page), a regional-bank picker before the identifier, the public
OAuth client ids, and the account prefix and labels in Picsou.

The CE connector was about 12,000 lines of sidecar code and tests and 2,500 lines
of Java, much of it BPCE platform logic: loan debts, card and loan row tagging, and
the login state machine.

## Decision

1. **Sidecar.** CE's login, replay, fetcher, parsers, PER client and keypad
   helpers move to `services/base/bpce`. A frozen `Site` (`bpce/site.py`),
   read at call time, holds what differs per bank: hosts, allow-list, login
   entry, cookie domain, region picker, and which loan and PER chains exist. The
   base image ships the package like `euro_information`; its test helper
   (`bpce/testing.py`) is not shipped and is mounted in CI. `caisse-epargne-auth`
   and the new `banque-populaire-auth` each keep only their routes and their
   `Site`.
2. **Backend.** The bank-agnostic part of `CaisseEpargneSyncService` becomes
   `BpceSyncEngine`, configured by a `BpceBank` descriptor: provider label,
   external-id prefix, lender name, display names. `BpcePort`,
   `BpceSidecarAdapter`, `BpceSession` and `BpceErrorCode` carry the shared
   contract. Each bank keeps its own controller, session table, rate-limit
   buckets, executor and error-code CHECK. CE keeps `caisse_epargne_session`,
   the `ce_` prefix and its REST paths. BP gets `banque_populaire_session`
   (`V122`), the `bp_` prefix and `/api/banque-populaire`.
3. **Frontend.** `BpceSessionPanel` holds the keypad pop-up, the Sécur'Pass
   wait and the recovery of a lost completion. `CaisseEpargnePanel` and
   `BanquePopulairePanel` wrap it with their own API, translation prefix and,
   for BP, the region select.
4. **Scope of the first BP version: only what was observed live.** BP imports the
   current account, Livret A, Livret Jeune, LDDS and card. Loans, PER and the
   revolving credit are listed as unsupported, because their OAuth clients and
   hosts on BP were never observed.

## Alternatives considered

### Duplicate the CE stack as `BanquePopulaire*` (the CIC / CMES precedent)
- **Pros**: no change to a working connector.
- **Cons**: about 1,200 lines of sync logic and the whole sidecar duplicated; every
  BPCE fix (like the Livret Jeune one below) would have to be made twice.

### One connector with a `bank` column in `caisse_epargne_session`
- **Pros**: least code.
- **Cons**: changes the CE schema, REST paths and classes that are in production;
  one table and one rate-limit bucket for two banks.

### Guess the BP loan and PER clients from the CE ones
- **Pros**: more products on day one.
- **Cons**: code that was never run against the bank. A wrong guess would show the
  accounts as unavailable at best, and the user cannot tell why.

## Reasoning

The platform is shared, so the code that talks to it is shared too. The parts
that belong to Picsou stay per bank: tables, prefixes, endpoints, buckets and
error CHECKs. This follows the 2026-11-01 shared-package decision. CE's existing
tests guard the refactor.

## Trade-offs accepted

- A change to `services/base/bpce` or `BpceSyncEngine` affects both banks; both
  test suites run on it.
- BP loans, PER and revolving credit are not imported until they are observed live.
- The region is asked at each login and is not stored: only the session cookies are.

## Consequences

- Shared parser fix: a BPCE `3/01` product labelled Livret Jeune is now imported as
  `LIVRET_JEUNE` for both banks. CE imported it as a Livret A before. A LEP-looking
  label is listed as unsupported.
- New: `services/base/bpce`, `services/banque-populaire-auth`, the `BanquePopulaire*`
  backend classes, `V122__banque_populaire_session.sql`, `BpceSessionPanel`,
  `BanquePopulairePanel`, Compose profile `banque-populaire`, GHCR image
  `banque-populaire-auth`.
- Details: [docs/features/banque-populaire.md](../features/banque-populaire.md).
