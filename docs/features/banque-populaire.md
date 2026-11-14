# Feature: Banque Populaire sync

> Last updated: 2026-11-14
> Status: sidecar, backend and frontend are in the codebase. Docker Compose (profile `banque-populaire`) and the Helm chart run the `banque-populaire-auth` sidecar. The user connects from the Sync page (Banque Populaire tab) or the add-account modal: they pick their regional bank, type their identifier, click their own digits on the bank's keypad, then approve in Sécur'Pass. Sync is on demand only. Full logins and syncs through Picsou ran against real Rives de Paris (2026-11-05) and Val de France (2026-11-08) accounts (see "Verification boundaries").

## Context

Banque Populaire runs the BPCE platform that Caisse d'Epargne runs, so this connector is the Caisse d'Epargne one with a different `Site` and bank descriptor. Unlike Caisse d'Epargne, it does not import cards. The shared code and the reasons for sharing it are in the ADR [2026-11-05-bpce-shared-sidecar-and-engine](../decisions/2026-11-05-bpce-shared-sidecar-and-engine.md). Everything [caisse-epargne.md](caisse-epargne.md) says about sessions, sync atomicity, card settlement tagging, error codes, timeouts, the no-retry rule and the recovery of a lost completion applies here. This note lists only the differences.

## How it works

### Key files

- `services/banque-populaire-auth/main.py`: routes and the Banque Populaire `Site`. See its README for what was observed live.
- `services/base/bpce/`: login, replay, fetcher and parsers shared with Caisse d'Epargne.
- `BanquePopulaireController` (`/api/banque-populaire`), `BanquePopulaireSyncService` (on `BpceSyncEngine` with the Banque Populaire `BpceBank`), `BanquePopulaireAdapter`, `SidecarSession` rows with `bank = 'BANQUE_POPULAIRE'` (table `sidecar_session`, since migration `V147`), `BanquePopulaireRegion`; its error codes are `SidecarBank.BANQUE_POPULAIRE` (the same 11 as Caisse d'Epargne).
- `frontend/src/components/sync/BanquePopulairePanel.tsx` (on `BpceSessionPanel`), `pages/sync/BanquePopulaireTab.tsx`, `banquePopulaireApi`, i18n `sync.banquePopulaire.*`.

### Login

| Endpoint | Body | Answer |
|---|---|---|
| `POST /api/banque-populaire/auth/initiate` | `{customerId, region}` | `{processId, keypad, expiresInSeconds}` |
| `POST /api/banque-populaire/auth/keypad` | `{processId, positions}` | `{processId}` |
| `POST /api/banque-populaire/auth/complete` | `{processId}` | `{connected: true}` |

- `customerId` is 1 to 20 ASCII letters and digits: a Banque Populaire identifier starts with a letter (observed live), so the UI keeps letters and only drops spaces and symbols. Caisse d'Epargne identifiers stay digits only. Dropping the letter would send another identifier to the bank, which still shows a keypad for it and then refuses the password: an attempt counted against someone else's identifier. When reconnecting with a remembered identifier from the last successful login (`SessionStatusResponse.remembered=true`), the frontend sends an empty `customerId` (and empty `region`), which the backend resolves from the stored encrypted identifier and the session's region. With an empty `customerId` the request's `region` is ignored: the remembered identifier only ever goes back to the regional bank it logged in at. With nothing remembered, the call answers 422 `INVALID_CREDENTIALS` (after spending an initiate rate-limit token), like Caisse d'Epargne.
- `region` is one of the 12 regional banks offered on the bank's identifier page: `ALSACE_LORRAINE_CHAMPAGNE`, `AQUITAINE_CENTRE_ATLANTIQUE`, `AUVERGNE_RHONE_ALPES`, `BOURGOGNE_FRANCHE_COMTE`, `BRED`, `GRAND_OUEST`, `MEDITERRANEE`, `NORD`, `OCCITANE`, `RIVES_DE_PARIS`, `SUD`, `VAL_DE_FRANCE`. A missing field, or an unknown value with a typed identifier, is refused before the sidecar is called. It picks the option on the bank's page, and is kept server side with the pending login: `/auth/complete` stores it on the session it opens and never reads one from the request (see [Storage](#storage) and [Branding](#branding)).
- The sidecar opens `https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber`. The `service=dei` entry used for Caisse d'Epargne lands on `/se-connecter/erreur` for Banque Populaire. It dismisses the consent banner if one shows, picks the region in the establishment combobox and checks that the combobox shows it. The rest is the Caisse d'Epargne flow: identifier, keypad, Sécur'Pass.
- Rate limits per IP use separate buckets from Caisse d'Epargne: `/auth/initiate` 3 per 15 minutes (`banquePopulaireAuthBuckets`), `/auth/keypad` 5 per 15 minutes (`banquePopulaireKeypadBuckets`).
- Timeouts are Caisse d'Epargne's: the backend waits 90 s for `/auth/initiate`, 70 s for `/auth/keypad` and 170 s for `/auth/complete`, and the shared sidecar answers within 80 s, 60 s and 160 s. No proxy timeout sits in front of them. The region option is matched on its exact text once its Unicode form and spacing are made canonical; case and accents still count.

### Storage

Sessions are `sidecar_session` rows with `bank = 'BANQUE_POPULAIRE'`, as Caisse d'Epargne's are. `V134` first created a `banque_populaire_session` table; `V147` copied its rows as stored and dropped it. The bank's branch of `ck_sidecar_session_last_sync_error` lists the same 11 codes as Caisse d'Epargne, and `region` (`VARCHAR(32)`) is set on every Banque Populaire row and null on every other bank (`ck_sidecar_session_region`, listing the 12 `BanquePopulaireRegion` names). It stores the cookies encrypted; no password and no Sécur'Pass approval is stored. Since `V148`, `encrypted_credentials` stores the identifier of the last successful login (`{"customerId": …}`, encrypted with `CryptoEncryption`) so a reconnect does not ask for it again; a reconnect with a remembered identifier always goes back to the region stored on the row. Disconnecting deletes the row, identifier included, and a bank rejection of the remembered identifier forgets it. The region is stored in clear: it is not a secret, it names the bank. A new login replaces the row, region included. External ids are prefixed `bp_`.

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
| `1` current account | `CHECKING`, with transactions | yes ("Compte De Cheques", "Compte Individuel") |
| card in `augmentedCards` | not imported (`Site.cards=False`): never an account, its operations never read, not listed in `unsupported` (a card is not a contract) | yes (Visa Premier, reported with `cardCreditIndicator=true`) |
| `3/01` labelled Livret A | `LIVRET_A`, with transactions | yes ("Livret A-Particuliers", "Livret A") |
| `3/01` labelled Livret Jeune | `LIVRET_JEUNE`, with transactions | yes ("Livret Jeune") |
| `3/01` labelled LEP | `LEP`, with transactions | no |
| `3/02` | `SAVINGS` (Compte sur Livret, provisional), with transactions | yes ("Compte Sur Livret", with `contractPfmId`) |
| `3/04` | `PEL` (provisional), with transactions | yes ("Plan Epargne Logement", with `contractPfmId`) |
| `3/28` | `LDDS`, with transactions | yes ("Livret De Developpement Durable Et Solidaire") |
| `4/09` | `SAVINGS` (`TERM_DEPOSIT`, provisional), balance from the synthesis (no PFM transactions) | yes ("Tip Top'P 3 Ans", without `contractPfmId`) |
| `4/10` | `SAVINGS` (`COOPERATIVE_SHARES`, provisional), balance from the synthesis (no PFM transactions) | yes ("Part Sociale BP", without `contractPfmId`) |
| `4/05` | `PER` (via Natixis Assurances portal `espace-assurances-de-personnes.banquepopulaire.fr`) | yes (compact contract ID `PERIB<number>`, producer `PERIB`; #524) |
| `6/12` personal loan | `LOAN`, balance from the synthesis, detail from the personal-loan app (provisional) | no |
| `6/13` home loan | `LOAN`, balance from the synthesis, detail from the home-loan app (provisional) | no |
| `2/11` revolving credit, anything else | listed in `unsupported`, never fetched, never fatal | revolving credit `2/11` seen (none with `contractPfmId`) |

Loans read their detail through separate OAuth clients, as on Caisse d'Epargne. The Banque Populaire clients were not observed in a live sync: they come from the bank's public web app configuration (the JavaScript bundles served without a login, read 2026-11-14), the same method that reproduces exactly the Caisse d'Epargne client ids observed live.

- Personal loans (`6/12`, app `espace-gestion/pret-personnel`): client `4107d671-…`, scope `managePersonalLoanPrd readPersonalLoanPrd readUser`, that app's redirect, no `claims`, on the main authorization server and data host (`SITE.personal_loan`). Same detail path as Caisse d'Epargne.
- Home loans (`6/13`, app `gestion-client/credit-immobilier`): client `873a10ce-…` (the same as Caisse d'Epargne), an empty scope, that app's redirect, no `claims`, on the BPCE hosts `www.as-ext-bad-me.bpce.fr` and `www.rs-ext-bad-me.bpce.fr` (`SITE.home_loan`). Same `/loan/v1/loans/{entityCode}-{id}` detail path.
- `LOAN` is a provisional kind here: the client is unverified, so a refusal from either loan app (token or detail, including a 401) never ends the session, and a loan whose token cannot be minted or whose detail cannot be read or parsed is imported with its synthesis balance and no Debt details (`loan: null`), not left in `unavailable`. Caisse d'Epargne keeps the strict behaviour (a personal-loan 401 means an expired session).
- A synthesis loan entry that cannot be read is demoted to `unsupported` instead of failing the sync. A loan entry's `augmentedCards` is no longer shape-checked (Banque Populaire does not import cards).
- Diagnostic logs for a report, field paths and codes only, never a value or an id: `loan detail unreadable: product=… code=… rejected=<field path>`, `loan detail unavailable: product=… code=…`, `loan imported without detail: product=…`.

The PER is read from the Banque Populaire insurer portal (Natixis Assurances, `SITE.per`).

### Wrong identifier or password

The bank answers a wrong identifier or password with a modal titled "Identifiant ou mot de passe erroné" over the keypad page (observed live 2026-11-05). The sidecar reads it as `INVALID_CREDENTIALS` (`ERROR_SELECTOR` matches that wording), so the UI says the credentials were refused and warns about the lock risk instead of reporting a format change.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Shared `bpce` package + `BpceSyncEngine` | Same platform, one place to fix | Duplicating the Caisse d'Epargne stack |
| Region chosen by the user at each login, stored on the session | The bank's page requires it; the sync needs it to name the accounts and find the logo | Guessing it from the identifier; taking it from the `/complete` request |
| Provider = the regional bank's official name, logo from the Enable Banking catalog | The accounts look exactly like those Enable Banking creates for that bank | A generic `Banque Populaire` label; a bundled logo per region |
| Loans from the public app configuration, `LOAN` provisional | The clients are public and readable without a login, but unverified: a refusal costs only the loan detail, never the session | Waiting for a live run before reading loans; treating a loan-app 401 as an expired session |
| PER via Natixis portal | Client ID and routing confirmed live (HTTP 201); format pinned to `PERIB` | Synthesis-only balance |

## Gotchas / Pitfalls

- `V147` drops `banque_populaire_session` after the copy, so there is no rollback past it without a database restore. Upgrade offline, as for `V142`: stop the old app first, then use the [safe release deployment](release-deployment.md). A session the old app writes after the copy is lost with the table.
- A BPCE `3/01` code covers both Livret A and Livret Jeune. The product label tells them apart (whole word `JEUNE`), for both banks. A LEP label (`LEP`, `EPARGNE POPULAIRE`) is imported as `LEP` (not observed live).
- `3/02` (`SAVINGS`, Compte sur Livret), `3/04` (`PEL`), `4/09` (`TERM_DEPOSIT`, term deposit) and `4/10` (`COOPERATIVE_SHARES`, cooperative shares) are enabled on Banque Populaire through `Site.provisional_kinds=frozenset({"SAVINGS", "PEL", "TERM_DEPOSIT", "COOPERATIVE_SHARES", "PER", "LOAN"})` ([ADR](../decisions/2026-11-07-provisional-kinds-per-bank.md)): an unreadable entry is demoted to `unsupported` instead of failing the synthesis, and `3/02`, `4/09` and `4/10` stay in `unsupported_families` on Caisse d'Epargne until observed there.
- The host allow-list is `*.banquepopulaire.fr` plus exactly the two BPCE home-loan hosts (`SITE.extra_hosts`: `www.as-ext-bad-me.bpce.fr`, `www.rs-ext-bad-me.bpce.fr`), no wildcard. A `caisse-epargne.fr` URL or any other `bpce.fr` host is refused for this bank, and the other way round for Caisse d'Epargne.
- During login, an earlier authorize request (client `adad0322-…`, `response_type=id_token token`) runs on the login host. Only the authorize sent from the client space is captured, as for Caisse d'Epargne.
- The shared sidecar code logs under the `bpce` logger name for both banks.
- The 12 official names were written from the bank's own naming; whether each matches the Enable Banking catalog name exactly was not checked against the live catalog. A name the catalog spells differently simply gets no logo (the name tier ignores case only: accents, hyphens and spacing must match). `BanquePopulaireRegionTest` pins the names.
- The provider label is shared with Enable Banking on purpose. A member who also has an Enable Banking connection to the same regional bank sees both sets of accounts under one label, and the Sync-all modal keeps a Banque Populaire row while any account carries one of these labels, even with no sidecar session.

## Tests

- Sidecar: `services/banque-populaire-auth/test_*.py` (region picker against a synthetic page in real Chromium, login, `/initiate` validation, accounts), `services/base/test_bpce.py` (Site, allow-lists, Livret Jeune / LEP rules, unsupported families).
- Backend: `BanquePopulaire*Test` (region kept from the pending login, provider label and logo rules, catalog miss and Enable Banking not configured), `BanquePopulaireRegionTest` (the 12 names), `V134BanquePopulaireSessionMigrationTest` (Testcontainers, the V134 table, kept for history), `V148BanquePopulaireStoredIdentifierMigrationTest`, `V147BanquePopulaireSidecarSessionMigrationTest` (every column, every status and every region copied; region CHECK), `SidecarSyncContractTest` (the shared scenarios), plus the Caisse d'Epargne suites that cover `BpceSyncEngine` (label unchanged, no logo).
- Frontend: `BanquePopulaireTab.test.tsx`, `banque-populaire-scope.test.ts`, `demo/banque-populaire.test.ts`, SyncAllModal (each regional label keeps the row) / AddAccountModal / providers tests.

## Verification boundaries

Run live end to end on 2026-11-05 with a Rives de Paris account, through the dev stack (sidecar, backend, frontend): region picker, identifier with its letter, keypad, Sécur'Pass approval, session stored, first sync `SUCCESS`. It imported a current account, a Livret A and a Livret Jeune with their transactions (several hundred rows on the current account, paged within the 365-day window) all with the `bp_` prefix; the revolving credit was listed in `unsupported` (family `2`). That run also imported the Visa Premier as a `CREDIT` card (the bank reports `cardCreditIndicator=true`) with no operations; cards were taken out of the Banque Populaire scope afterwards and are no longer imported. A wrong identifier was also seen live: the bank still shows a keypad, then the refusal modal.

Run live on 2026-11-08 with a Val de France account: imported a current account, a Livret A (`3/01`), a Livret Jeune (`3/01`), an LDDS (`3/28`), and revealed a Compte sur Livret (`3/02`) and a PEL (`3/04`), both carrying a `contractPfmId`, plus `4/05` (PER), `4/09` (term deposit) and `4/10` (cooperative shares) without a `contractPfmId`.

Not observed live: personal and home loans (`6/12`, `6/13`). Their OAuth clients are read from the bank's public web app configuration (2026-11-14) and not yet verified against a real account, hence the provisional containment above. Also not observed: LEP (`3/27` or `3/01` labelled LEP), revolving-credit detail.
