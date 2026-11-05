# Feature: Banque Populaire sync

> Last updated: 2026-11-05
> Status: sidecar, backend and frontend are in the codebase. Docker Compose (profile `banque-populaire`) and the Helm chart run the `banque-populaire-auth` sidecar. The user connects from the Sync page (Banque Populaire tab) or the add-account modal: they pick their regional bank, type their identifier, click their own digits on the bank's keypad, then approve in Sécur'Pass. Sync is on demand only. The login pages, OAuth chain, synthesis and transaction endpoints were observed live on a Rives de Paris account on 2026-11-05. No full Picsou sync has run against the real bank yet (see "Verification boundaries").

## Context

Banque Populaire runs the BPCE platform that Caisse d'Epargne runs, so this connector is the Caisse d'Epargne one with a different `Site` and bank descriptor. The shared code and the reasons for sharing it are in the ADR [2026-11-05-bpce-shared-sidecar-and-engine](../decisions/2026-11-05-bpce-shared-sidecar-and-engine.md). Everything [caisse-epargne.md](caisse-epargne.md) says about sessions, sync atomicity, card settlement tagging, error codes, timeouts, the no-retry rule and the recovery of a lost completion applies here. This note lists only the differences.

## How it works

### Key files

- `services/banque-populaire-auth/main.py`: routes and the Banque Populaire `Site`. See its README for what was observed live.
- `services/base/bpce/`: login, replay, fetcher and parsers shared with Caisse d'Epargne.
- `BanquePopulaireController` (`/api/banque-populaire`), `BanquePopulaireSyncService` (on `BpceSyncEngine` with the Banque Populaire `BpceBank`), `BanquePopulaireAdapter`, `BanquePopulaireSession` (table `banque_populaire_session`, migration `V122`), `BanquePopulaireErrorCode`, `BanquePopulaireRegion`.
- `frontend/src/components/sync/BanquePopulairePanel.tsx` (on `BpceSessionPanel`), `pages/sync/BanquePopulaireTab.tsx`, `banquePopulaireApi`, i18n `sync.banquePopulaire.*`.

### Login

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/banque-populaire/auth/initiate` | `{customerId, region}` | `{processId, keypad, expiresInSeconds}` |
| `POST /api/banque-populaire/auth/keypad` | `{processId, positions}` | `{processId}` |
| `POST /api/banque-populaire/auth/complete` | `{processId}` | `{connected: true}` |

- `region` is one of the 12 regional banks offered on the bank's identifier page: `ALSACE_LORRAINE_CHAMPAGNE`, `AQUITAINE_CENTRE_ATLANTIQUE`, `AUVERGNE_RHONE_ALPES`, `BOURGOGNE_FRANCHE_COMTE`, `BRED`, `GRAND_OUEST`, `MEDITERRANEE`, `NORD`, `OCCITANE`, `RIVES_DE_PARIS`, `SUD`, `VAL_DE_FRANCE`. A missing or unknown value is refused before the sidecar is called. The region is only used to pick the option on the bank's page and is never stored.
- The sidecar opens `https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber`. The `service=dei` entry used for Caisse d'Epargne lands on `/se-connecter/erreur` for Banque Populaire. It dismisses the consent banner if one shows, picks the region in the establishment combobox and checks that the combobox shows it. The rest is the Caisse d'Epargne flow: identifier, keypad, Sécur'Pass.
- Rate limits per IP use separate buckets from Caisse d'Epargne: `/auth/initiate` 3 per 15 minutes (`banquePopulaireAuthBuckets`), `/auth/keypad` 5 per 15 minutes (`banquePopulaireKeypadBuckets`).
- nginx gives `/api/banque-populaire/auth/complete` 180 s, `/auth/keypad` 90 s and `/auth/initiate` 100 s, like Caisse d'Epargne. The region option is matched on its exact text once its Unicode form and spacing are made canonical; case and accents still count.

### Storage

Table `banque_populaire_session` (migration `V122`) has the Caisse d'Epargne table's shape and the full 11-code `last_sync_error` CHECK. It stores cookies only and encrypts them; no password and no Sécur'Pass approval is stored. External ids are prefixed `bp_`, the provider label is `Banque Populaire`.

### Supported products

| BPCE family / sub | Picsou type | Observed live |
|---|---|---|
| `1` current account | `CHECKING`, with transactions | yes ("Compte De Cheques") |
| card in `augmentedCards` | `CREDIT_CARD` (liability) | yes (immediate-debit Visa Premier) |
| `3/01` labelled Livret A | `LIVRET_A`, with transactions | yes ("Livret A-Particuliers") |
| `3/01` labelled Livret Jeune | `LIVRET_JEUNE`, with transactions | yes ("Livret Jeune") |
| `3/01` labelled LEP | `LEP`, with transactions | no |
| `3/28` | `LDDS`, with transactions | no (same code as Caisse d'Epargne) |
| `6/12`, `6/13` loans, `4/05` PER, `2/11` revolving credit, anything else | listed in `unsupported`, never fetched, never fatal | revolving credit seen ("Credit Renouvelable Facelia", no balance) |

Loans and the PER read their detail through separate OAuth clients on Caisse d'Epargne. Their Banque Populaire equivalents were never observed, so this version does not guess them.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Shared `bpce` package + `BpceSyncEngine` | Same platform, one place to fix | Duplicating the Caisse d'Epargne stack |
| Region chosen by the user at each login | The bank's page requires it and nothing else needs it | Storing it, or guessing it from the identifier |
| Loans and PER unsupported | Their BP clients and hosts were not observed | Reusing the Caisse d'Epargne client ids |

## Gotchas / Pitfalls

- A BPCE `3/01` code covers both Livret A and Livret Jeune. The product label tells them apart (whole word `JEUNE`), for both banks. A LEP label (`LEP`, `EPARGNE POPULAIRE`) is imported as `LEP` (not observed live).
- The host allow-list is strictly `*.banquepopulaire.fr`. A `caisse-epargne.fr` or `bpce.fr` URL is refused for this bank, and the other way round for Caisse d'Epargne.
- During login, an earlier authorize request (client `adad0322-…`, `response_type=id_token token`) runs on the login host. Only the authorize sent from the client space is captured, as for Caisse d'Epargne.
- The shared sidecar code logs under the `bpce` logger name for both banks.

## Tests

- Sidecar: `services/banque-populaire-auth/test_*.py` (region picker against a synthetic page in real Chromium, login, `/initiate` validation, accounts), `services/base/test_bpce.py` (Site, allow-lists, Livret Jeune / LEP rules, unsupported families).
- Backend: `BanquePopulaire*Test`, `V122BanquePopulaireSessionMigrationTest` (Testcontainers), plus the Caisse d'Epargne suites that cover `BpceSyncEngine`.
- Frontend: `BanquePopulaireTab.test.tsx`, `banque-populaire-scope.test.ts`, `demo/banque-populaire.test.ts`, SyncAllModal / AddAccountModal / providers tests.

## Verification boundaries

Observed live (2026-11-05, shape only, no data kept): login pages and selectors, region picker, OAuth chain and main client, synthesis payload, transaction endpoint and envelope. Not observed: a transaction row (the one call seen returned none), card operations, loan, PER and revolving-credit detail. Not run: a full login and sync through the Picsou sidecar against the real bank.
