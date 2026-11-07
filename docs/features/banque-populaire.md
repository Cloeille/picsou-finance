# Feature: Banque Populaire sync

> Last updated: 2026-11-06
> Status: sidecar, backend and frontend are in the codebase. Docker Compose (profile `banque-populaire`) and the Helm chart run the `banque-populaire-auth` sidecar. The user connects from the Sync page (Banque Populaire tab) or the add-account modal: they pick their regional bank, type their identifier, click their own digits on the bank's keypad, then approve in Sécur'Pass. Sync is on demand only. A full login and sync through Picsou ran against a real Rives de Paris account on 2026-11-05 (see "Verification boundaries").

## Context

Banque Populaire runs the BPCE platform that Caisse d'Epargne runs, so this connector is the Caisse d'Epargne one with a different `Site` and bank descriptor. Unlike Caisse d'Epargne, it does not import cards. The shared code and the reasons for sharing it are in the ADR [2026-11-05-bpce-shared-sidecar-and-engine](../decisions/2026-11-05-bpce-shared-sidecar-and-engine.md). Everything [caisse-epargne.md](caisse-epargne.md) says about sessions, sync atomicity, card settlement tagging, error codes, timeouts, the no-retry rule and the recovery of a lost completion applies here. This note lists only the differences.

## How it works

### Key files

- `services/banque-populaire-auth/main.py`: routes and the Banque Populaire `Site`. See its README for what was observed live.
- `services/base/bpce/`: login, replay, fetcher and parsers shared with Caisse d'Epargne.
- `BanquePopulaireController` (`/api/banque-populaire`), `BanquePopulaireSyncService` (on `BpceSyncEngine` with the Banque Populaire `BpceBank`), `BanquePopulaireAdapter`, `BanquePopulaireSession` (table `banque_populaire_session`, migration `V134`), `BanquePopulaireRegion`; its error codes are `SidecarBank.BANQUE_POPULAIRE` (the same 11 as Caisse d'Epargne).
- `frontend/src/components/sync/BanquePopulairePanel.tsx` (on `BpceSessionPanel`), `pages/sync/BanquePopulaireTab.tsx`, `banquePopulaireApi`, i18n `sync.banquePopulaire.*`.

### Login

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/banque-populaire/auth/initiate` | `{customerId, region}` | `{processId, keypad, expiresInSeconds}` |
| `POST /api/banque-populaire/auth/keypad` | `{processId, positions}` | `{processId}` |
| `POST /api/banque-populaire/auth/complete` | `{processId}` | `{connected: true}` |

- `customerId` is 1 to 20 ASCII letters and digits: a Banque Populaire identifier starts with a letter (observed live), so the UI keeps letters and only drops spaces and symbols. Caisse d'Epargne identifiers stay digits only. Dropping the letter would send another identifier to the bank, which still shows a keypad for it and then refuses the password: an attempt counted against someone else's identifier.
- `region` is one of the 12 regional banks offered on the bank's identifier page: `ALSACE_LORRAINE_CHAMPAGNE`, `AQUITAINE_CENTRE_ATLANTIQUE`, `AUVERGNE_RHONE_ALPES`, `BOURGOGNE_FRANCHE_COMTE`, `BRED`, `GRAND_OUEST`, `MEDITERRANEE`, `NORD`, `OCCITANE`, `RIVES_DE_PARIS`, `SUD`, `VAL_DE_FRANCE`. A missing or unknown value is refused before the sidecar is called. It picks the option on the bank's page, and is kept server side with the pending login: `/auth/complete` stores it on the session it opens and never reads one from the request (see [Storage](#storage) and [Branding](#branding)).
- The sidecar opens `https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber`. The `service=dei` entry used for Caisse d'Epargne lands on `/se-connecter/erreur` for Banque Populaire. It dismisses the consent banner if one shows, picks the region in the establishment combobox and checks that the combobox shows it. The rest is the Caisse d'Epargne flow: identifier, keypad, Sécur'Pass.
- Rate limits per IP use separate buckets from Caisse d'Epargne: `/auth/initiate` 3 per 15 minutes (`banquePopulaireAuthBuckets`), `/auth/keypad` 5 per 15 minutes (`banquePopulaireKeypadBuckets`).
- Timeouts are Caisse d'Epargne's: the backend waits 90 s for `/auth/initiate`, 70 s for `/auth/keypad` and 170 s for `/auth/complete`, and the shared sidecar answers within 80 s, 60 s and 160 s. No proxy timeout sits in front of them. The region option is matched on its exact text once its Unicode form and spacing are made canonical; case and accents still count.

### Storage

Table `banque_populaire_session` (migration `V134`) has the Caisse d'Epargne table's shape and the full 11-code `last_sync_error` CHECK, plus `region` (`VARCHAR(32) NOT NULL`, CHECK listing the 12 `BanquePopulaireRegion` names). It stores the cookies encrypted; no password and no Sécur'Pass approval is stored. The region is stored in clear: it is not a secret, it names the bank. A new login replaces the row, region included. External ids are prefixed `bp_`.

### Branding

Banque Populaire accounts look like the ones Enable Banking creates for the same bank:

- `account.provider` is the regional bank's official name, `BanquePopulaireRegion.institutionName()` of the session's region: "Banque Populaire Rives de Paris", "BRED Banque Populaire", "Banque Populaire du Nord", and so on. It is written on create and rewritten on every sync (the connector owns it).
- `account.logoUrl` comes from the Enable Banking catalog through `BankLogoResolver.logoUrlOrNull("FR", null, <that name>)`, so the name tier of its matching applies. Same rule as `SyncService.upsertAccount`: set on a new account, filled on an existing one only while it is `null`, never overwritten.
- The lookup runs once per sync, on the sync thread, outside any transaction, and only after the snapshot was validated (a refused snapshot costs none). Enable Banking not configured, a catalog outage or no match all leave the logo `null` and the sync goes on; the catalog is asked again on the next sync.
- How: `BpceSyncEngine` takes a `BrandingSource` per bank. It reads the session inside the queueing transaction and returns a `Branding` (the provider label and a lazy logo lookup). Caisse d'Epargne passes `BrandingSource.fixed("Caisse d'Epargne")`: label unchanged, no logo. Messages, logs and fallback names keep the generic `Banque Populaire` (`BpceBank.provider`), as does a loan's `lenderName`.
- The frontend recognises these accounts by that set of labels (`BANQUE_POPULAIRE_PROVIDERS` in `SyncAllModal.tsx`), plus the generic `Banque Populaire` of the earliest imports.

### Supported products

| BPCE family / sub | Picsou type | Observed live |
|---|---|---|
| `1` current account | `CHECKING`, with transactions | yes ("Compte De Cheques") |
| card in `augmentedCards` | not imported (`Site.cards=False`): never an account, its operations never read, not listed in `unsupported` (a card is not a contract) | yes (Visa Premier, reported with `cardCreditIndicator=true`) |
| `3/01` labelled Livret A | `LIVRET_A`, with transactions | yes ("Livret A-Particuliers") |
| `3/01` labelled Livret Jeune | `LIVRET_JEUNE`, with transactions | yes ("Livret Jeune") |
| `3/01` labelled LEP | `LEP`, with transactions | no |
| `3/28` | `LDDS`, with transactions | no (same code as Caisse d'Epargne) |
| `6/12`, `6/13` loans, `4/05` PER, `2/11` revolving credit, anything else | listed in `unsupported`, never fetched, never fatal | revolving credit seen ("Credit Renouvelable Facelia", no balance) |

Loans and the PER read their detail through separate OAuth clients on Caisse d'Epargne. Their Banque Populaire equivalents were never observed, so this version does not guess them.

### Wrong identifier or password

The bank answers a wrong identifier or password with a modal titled "Identifiant ou mot de passe erroné" over the keypad page (observed live 2026-11-05). The sidecar reads it as `INVALID_CREDENTIALS` (`ERROR_SELECTOR` matches that wording), so the UI says the credentials were refused and warns about the lock risk instead of reporting a format change.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Shared `bpce` package + `BpceSyncEngine` | Same platform, one place to fix | Duplicating the Caisse d'Epargne stack |
| Region chosen by the user at each login, stored on the session | The bank's page requires it; the sync needs it to name the accounts and find the logo | Guessing it from the identifier; taking it from the `/complete` request |
| Provider = the regional bank's official name, logo from the Enable Banking catalog | The accounts look exactly like those Enable Banking creates for that bank | A generic `Banque Populaire` label; a bundled logo per region |
| Loans and PER unsupported | Their BP clients and hosts were not observed | Reusing the Caisse d'Epargne client ids |

## Gotchas / Pitfalls

- A BPCE `3/01` code covers both Livret A and Livret Jeune. The product label tells them apart (whole word `JEUNE`), for both banks. A LEP label (`LEP`, `EPARGNE POPULAIRE`) is imported as `LEP` (not observed live).
- The host allow-list is strictly `*.banquepopulaire.fr`. A `caisse-epargne.fr` or `bpce.fr` URL is refused for this bank, and the other way round for Caisse d'Epargne.
- During login, an earlier authorize request (client `adad0322-…`, `response_type=id_token token`) runs on the login host. Only the authorize sent from the client space is captured, as for Caisse d'Epargne.
- The shared sidecar code logs under the `bpce` logger name for both banks.
- The 12 official names were written from the bank's own naming; whether each matches the Enable Banking catalog name exactly was not checked against the live catalog. A name the catalog spells differently simply gets no logo (the name tier ignores case only: accents, hyphens and spacing must match). `BanquePopulaireRegionTest` pins the names.
- The provider label is shared with Enable Banking on purpose. A member who also has an Enable Banking connection to the same regional bank sees both sets of accounts under one label, and the Sync-all modal keeps a Banque Populaire row while any account carries one of these labels, even with no sidecar session.

## Tests

- Sidecar: `services/banque-populaire-auth/test_*.py` (region picker against a synthetic page in real Chromium, login, `/initiate` validation, accounts), `services/base/test_bpce.py` (Site, allow-lists, Livret Jeune / LEP rules, unsupported families).
- Backend: `BanquePopulaire*Test` (region kept from the pending login, provider label and logo rules, catalog miss and Enable Banking not configured), `BanquePopulaireRegionTest` (the 12 names), `V134BanquePopulaireSessionMigrationTest` (Testcontainers, region column and CHECK), plus the Caisse d'Epargne suites that cover `BpceSyncEngine` (label unchanged, no logo).
- Frontend: `BanquePopulaireTab.test.tsx`, `banque-populaire-scope.test.ts`, `demo/banque-populaire.test.ts`, SyncAllModal (each regional label keeps the row) / AddAccountModal / providers tests.

## Verification boundaries

Run live end to end on 2026-11-05 with a Rives de Paris account, through the dev stack (sidecar, backend, frontend): region picker, identifier with its letter, keypad, Sécur'Pass approval, session stored, first sync `SUCCESS`. It imported a current account, a Livret A and a Livret Jeune with their transactions (several hundred rows on the current account, paged within the 365-day window) all with the `bp_` prefix; the revolving credit was listed in `unsupported` (family `2`). That run also imported the Visa Premier as a `CREDIT` card (the bank reports `cardCreditIndicator=true`) with no operations; cards were taken out of the Banque Populaire scope afterwards and are no longer imported. A wrong identifier was also seen live: the bank still shows a keypad, then the refusal modal.

Not observed: LDDS, LEP, loan, PER and revolving-credit detail.
