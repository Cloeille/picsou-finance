# Feature: Logos on Account Cards

> Last updated: 2026-08-13

## Context

Account cards on the Accounts page (`/accounts`) previously showed only a flat color swatch as the account's visual identity. Enable Banking's institution search already returns a real bank logo URL (`InstitutionData.logoUrl`) that was captured but never surfaced anywhere in the UI. This feature threads that logo through to the account and displays it as a circular avatar, falling back to the existing color when no logo is available.

Connectors integrated by hand against a single provider have no catalog to ask for a logo, so a second, much smaller source was added: a map of brand assets that ship with the frontend, keyed by `Account.provider`. Meria, Amundi, BoursoBank and Trade Republic are the entries so far.

A manual account had neither source for a long while: nothing syncs it, so no connector could be asked, and its `provider` is a free-text field a user types — "Crédit Agricole" matched no bundled brand asset and the card fell back to its color circle. It now borrows the *first* source's catalog: the bank named in the account form can be picked from Enable Banking's institution list, and the server resolves that pick's logo onto the account exactly as it does for a real connection.

On-chain wallets fit neither source. `WalletSyncService` writes the native ticker into `provider` (`BTC`, `SOL`, `ETH`...), so there is nothing stable to key a map on — and the asset a user wants isn't derivable anyway, since the chain can't tell whether the address lives on a Ledger. That case is served by a third source: a key stored on the account itself (`Account.logoKey`), seeded with the generic blockchain mark and swappable from the account form.

## How it works

### Scope

There are three logo sources, in priority order:

1. **A bundled asset the account points at** (`Account.logoKey`). Only **on-chain wallets** carry one — the API lets a client swap that key, never attach one to an account that has none, so a crypto *exchange* account can't be given a wallet mark either. It wins over everything else because it is the only source a user chose by hand.
2. **A provider-supplied URL on the account** (`Account.logoUrl`), from the institution catalog. Two paths write it, both server-side and both through `BankLogoResolver`: a **connected** account inherits it from the requisition it was created under, and a **manual** account gets it from the bank named in its form (see [The bank a manual account names](#the-bank-a-manual-account-names)). Only **Enable Banking** supplies that catalog — it's the sole active `BankConnectorPort` implementation that returns logos (see [bank-sync.md](./bank-sync.md)). Powens (disabled, experimental) hardcodes `logoUrl = null`.
3. **A bundled brand asset**, matched on `Account.provider` when neither of the above applies. Currently `MERIA`, Amundi, BoursoBank and Trade Republic.

Properties take none of the three: they have no `provider` to key on, no connector to ask, and no logo key of their own, so `AccountCard` marks them with the glyph for their `propertyKind` instead — see [accounts-overview.md](./accounts-overview.md#account-card-anatomy). The kind is picked in the property form, so that mark follows a user choice too, but it is chosen as a property attribute rather than from a logo picker.

Everything else — Binance, Bourse Direct, Finary, and any manual account whose bank the catalog does not list — still shows the color fallback. The user-editable visual fields are `color`, the bank a manual account names, and, on a wallet, that logo key: there is still no free-form logo upload, and no account ever takes a logo URL a client supplied.

### Capture at connection time

1. `BankWizard` (`AddAccountModal.tsx`) shows each institution's `logoUrl` from `GET /sync/institutions` in the search list, purely for display — selecting a bank only sends `{ institutionId, institutionName }` to `POST /sync/initiate`. The client-supplied logo URL is never sent to the server or persisted; a client can't inject an arbitrary third-party image URL that every family member's browser would later fetch.
2. `SyncService.initiateConnection()` resolves the logo itself: it re-queries `bankConnector.searchInstitutions(institutionName, country)` (country parsed from `institutionId`, e.g. `"BankName::FR::personal"` → `"FR"`), matches the result by exact institution id, then on name+country alone, and only then by a case-insensitive name match, and stores that logo on the new `Requisition.logoUrl` column. The middle tier covers requisitions written before the id carried a PSU-type segment — see [bank-sync.md](./bank-sync.md).
3. `SyncService.upsertAccount()` copies `requisition.getLogoUrl()` onto `Account.logoUrl` when creating a new account, and onto an existing account only if its `logoUrl` was still `null` (never overwrites a value once set).

### Backfill for pre-existing connections

Requisitions created before this feature shipped (or whose initial lookup missed) have `logoUrl = null`. `SyncService.ensureLogoUrl()` runs at the top of `resyncAll()` (daily scheduler), `retrySync()` (manual retry), and `resyncLatest()` (re-auth of an already-linked session): if the requisition has no logo yet, it re-searches institutions **scoped to the requisition's own country** (not the full multi-country catalog) and applies the same three-tier matching described above. Both this and the initiation-time lookup go through `BankLogoResolver`, which owns the search and the matching tiers — it was extracted from `SyncService` once manual accounts needed the same lookup, so the two paths cannot drift apart.

The backfill is bounded to a single attempt per requisition via `Requisition.logoBackfillAttemptedAt`: the marker is set as soon as the search call completes (hit or miss), so a permanent miss (renamed institution, no provider logo) is never retried on subsequent syncs. A failed *network* call does not set the marker, so a transient provider outage can still be retried next sync. A failed or empty lookup is otherwise swallowed (logged as a warning) — the requisition simply stays logo-less.

### The bank a manual account names

A hand-entered account has no connector behind it, so the only thing that can identify its bank
is the name in its form. `BankPicker` (`frontend/src/components/shared/BankPicker.tsx`) turns that
field into free text that also searches the institution catalog as you type, reusing the same
`useSearchInstitutions` hook and `BankCountrySelect` as the bank wizard.

1. Picking a bank from the list sets the form's `provider` to the institution's **name** and
   remembers its **id** (`"BankName::FR::personal"`). Typing after a pick clears that id — the
   account would otherwise save under one bank's name and another's logo.
2. `AccountRequest.institutionId` carries the id to the server. It is *not* stored: the account
   still only keeps `provider`. The id travels instead of the logo URL for the same reason the
   bank wizard sends one — nothing between a client-supplied URL and the Accounts page
   `<img src>` would validate its scheme or host.
3. `AccountService.refreshBankLogo()` re-resolves the logo through `BankLogoResolver`, using the
   country parsed off the id, and falling back to `BankConnectorPort.DEFAULT_COUNTRY` when the
   request carries none (a hand-typed name, or the MCP tools — an unfiltered search would pull
   the whole multi-country catalog on a path that runs on every account write).

The lookup is deliberately narrow about *when* it runs:

- **Manual accounts only.** Every other account's logo belongs to whatever synced it. Letting a
  free-text field overwrite an Enable Banking logo, or bury BoursoBank's brand mark under
  whatever the catalog matched, is exactly the failure this gate prevents.
- **Only when the bank changed, or the account has no logo yet.** Renaming an account or
  correcting its balance costs no catalog round-trip; an account whose bank was typed before the
  picker existed gets a second chance the next time its form is saved.
- **Clearing the bank clears the logo**, which keeps the card honest — and is what the MCP
  `update_account` tool does as a side effect of blanking `provider` (see Gotchas).

A failed or unconfigured catalog is swallowed (`BankLogoResolver.logoUrlOrNull`): the account
saves normally and keeps its color circle. Enable Banking is not a prerequisite for entering an
account by hand, and never becomes one.

### Bundled provider logos

`PROVIDER_LOGOS` (`frontend/src/lib/provider-logos.ts`) maps a `provider` string to an asset path, and `providerLogoUrl()` resolves it case-insensitively. For an account with no `logoKey` of its own, `AccountAvatar` falls back to `logoUrl ?? providerLogoUrl(provider)`, so a real connector logo always wins over a bundled one and nothing changes for accounts that already had one. The full order, key included, is in [Rendering](#rendering) below.

The key is the exact string the backend writes as `provider`. For crypto exchanges that is `ExchangeType.name()` (`CryptoExchangeSyncService.resolveAccount()`), hence `MERIA` rather than `Meria`; for Amundi it is `AmundiSyncService.PROVIDER`, i.e. `Amundi Épargne Salariale`, accent included; for BoursoBank it is `BoursoSyncService.PROVIDER`; for Trade Republic it is the `"Trade Republic"` literal inlined in `TradeRepublicSyncService.upsertAccount()`. `provider-logos.test.ts` pins all four literals, so an accidental edit to the map — or an accent that drifts between Unicode normalizations on the frontend side — fails loudly instead of silently reverting to the color circle. It cannot see the backend, though: renaming what the connector writes is caught by nothing (see Gotchas).

Assets live under `frontend/public/` (`exchanges/` for crypto, `providers/` otherwise) rather than being imported from `src/assets/` like the app's own logo: a missing file then degrades to the account's color circle — exactly what a logo-less account already shows — instead of failing the build. The trade-off is that a typo'd or missing path is invisible at runtime, so `provider-logos.test.ts` expands `import.meta.glob('../../public/**/*.{svg,png}')` and asserts every mapped path exists on disk.

Nothing is written to the database and no backend change was needed: an existing Meria, Amundi, BoursoBank or Trade Republic account picks the logo up on the next render, and demo mode gets it for free.

### The wallet logo key

`account.logo_key` (V75, `VARCHAR(32)`, nullable) holds the key of a bundled asset, resolved by `logoKeyUrl()` against `LOGO_KEYS` in the same frontend module. Two keys exist: `blockchain` (a generic on-chain wallet mark) and `ledger`.

- **Seeded, not derived.** `WalletSyncService.DEFAULT_LOGO_KEY` (`"blockchain"`) is written when a wallet's account is *created*, and never again — `resolveAccount()` only refreshes balance, `lastSyncedAt` and ticker on an account it finds, so a user's later choice survives every scheduled sync. V75 backfills the wallets that predate the column by joining `wallet_address` on the `wallet_<chain>_<id>` external id, guarded on `logo_key IS NULL` so a replayed migration can't reset a Ledger.
- **Changed from the account form.** `LogoPicker` (`frontend/src/components/shared/LogoPicker.tsx`) sits under `ColorPicker` in `AccountForm` and offers the keys in `WALLET_LOGO_CHOICES`. It renders only when the account being edited already has a key — which is exactly the on-chain wallets, since nothing else is created with one and the picker only ever swaps one key for another. That is also why no "no logo" option is offered: a wallet always shows one of the two, and there is nothing to undo.
- **Opaque to the backend.** `AccountRequest.logoKey` is validated as a lowercase slug (`^[a-z0-9-]{1,32}$`), not against a list of known keys — the assets live in the frontend, so a fixed list here would mean a backend release for every new one. An unknown key resolves to `null` client-side and the account falls through to its provider logo, then its color.
- **Kept when absent.** `AccountService.update()` writes `req.logoKey()` only when it is non-null, the way it treats `color` rather than `ticker`. A client that doesn't know about logos — the `update_account` MCP tool, an older frontend — would otherwise clear a wallet's Ledger mark as a side effect of renaming the account.
- **Only swappable, never attachable.** `AccountService.normalizeLogoKey()` keeps a key only on a `CRYPTO` account that already stores one, which is the same test the picker uses — the stored key answers "is this a wallet?" without an `isWallet` flag having to reach the backend. So `create()` always drops a key a request carries (only `WalletSyncService` seeds one, and it builds the row itself), and `update()` ignores one sent for an account that has none. `CRYPTO` alone would not be enough: it also covers exchange accounts, and a hand-crafted `PUT` could then bury Meria's brand mark under a Ledger.
- **Dropped when the type changes.** The same normalization clears the key when an account is retyped away from `CRYPTO`, and `AccountForm` hides the picker the moment the type changes. Retyping a wallet to `CHECKING` would otherwise leave a blockchain mark on it permanently: the picker has no "none" option and an omitted key is kept. This is also the only way to clear a key from the UI.

### Rendering

`AccountAvatar` resolves `logoKeyUrl(logoKey) ?? logoUrl ?? providerLogoUrl(provider)` — the three sources of the Scope section, in that order. `AccountCard.tsx`'s `AccountAvatar` and `AddAccountModal.tsx`'s `InstitutionLogo` are built on the shared `Avatar`/`AvatarImage`/`AvatarFallback` primitives (`frontend/src/components/ui/avatar.tsx`, Radix-based) rather than a hand-rolled `<img onError>` + `useState`. Radix re-attempts loading whenever `src` changes (e.g. a null logo becoming valid after backfill) and falls back to the color circle / `Landmark` icon automatically on load failure, with no risk of a stale `failed` flag latching across re-renders. The account detail page (`AccountDetailPage.tsx`) and the PnL chart legend (`AccountsStackedChart.tsx`) were intentionally left untouched — they use `account.color` as a small decorative dot/line color, not as the account's primary identity, and are out of scope for this change.

### Key files

- `backend/src/main/java/com/picsou/model/Account.java` — `logoUrl` column
- `backend/src/main/java/com/picsou/model/Requisition.java` — `logoUrl` + `logoBackfillAttemptedAt` columns
- `backend/src/main/java/com/picsou/service/BankLogoResolver.java` — the catalog search and its three matching tiers, shared by the connected and manual paths
- `backend/src/main/java/com/picsou/service/SyncService.java` — `ensureLogoUrl()` backfill, `upsertAccount()` copy logic
- `backend/src/main/java/com/picsou/service/AccountService.java` — `refreshBankLogo()` / `bankLogoUrl()`: when a manual account re-resolves its bank's logo
- `backend/src/main/java/com/picsou/dto/AccountRequest.java` — `institutionId`, consumed once and never stored
- `frontend/src/components/shared/BankPicker.tsx` — the bank field: free text that also searches the catalog
- `backend/src/main/resources/db/migration/V50__account_bank_logo.sql`
- `frontend/src/components/shared/AccountCard.tsx` — `AccountAvatar` and `PropertyAvatar` sub-components
- `frontend/src/components/shared/AddAccountModal.tsx` — `InstitutionLogo` (bank search list preview)
- `frontend/src/components/ui/avatar.tsx` — shared Radix Avatar primitives
- `backend/src/main/resources/db/migration/V75__account_logo_key.sql` — `account.logo_key` + wallet backfill
- `backend/src/main/java/com/picsou/service/WalletSyncService.java` — `DEFAULT_LOGO_KEY`, written at account creation
- `backend/src/main/java/com/picsou/service/AccountService.java` — `normalizeLogoKey()`: a key is swappable only where one is already stored, and `update()` keeps it when the request omits it
- `frontend/src/lib/provider-logos.ts` — `PROVIDER_LOGOS` + `LOGO_KEYS` maps, `providerLogoUrl()` / `logoKeyUrl()`
- `frontend/src/components/shared/LogoPicker.tsx` — the wallet's blockchain/Ledger choice
- `frontend/src/components/shared/AccountForm.tsx` — renders the picker for accounts that carry a key
- `frontend/public/exchanges/meria.svg` — Meria's mark
- `frontend/public/providers/amundi.png` — Amundi's mark
- `frontend/public/providers/boursobank.png` — BoursoBank's mark
- `frontend/public/providers/trade-republic.svg` — Trade Republic's mark
- `frontend/public/wallets/blockchain.svg`, `frontend/public/wallets/ledger.svg` — the two wallet marks

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Provider logos from Enable Banking only, never picked by hand | It's the only connector with real logos; a curated logo library or free-form upload was out of scope for v1. The wallet picker further down is the sole exception, and it chooses between two bundled marks rather than supplying a logo | Static logo library / image upload per account |
| The same catalog serves manual accounts, rather than a bundled bank-logo pack | It already covers essentially every European bank, needs no assets committed and no per-bank curation, and reuses a resolution path whose security properties were already worked out. The cost — nothing resolves when Enable Banking is unconfigured — is a degradation to today's behaviour, not a failure | A curated set of bank SVGs under `public/providers/` with alias matching (bounded to whatever ships, and someone must source and maintain every asset); a backend favicon fetcher keyed on a name→domain map (network-dependent, and the WAF problem that killed it for Meria) |
| The client sends an institution **id**, the server resolves the URL | Identical to the bank wizard's contract, and the reason is the same: an arbitrary client-supplied URL would be fetched by every family member's browser off the Accounts page. The id is an opaque round-trip token, so echoing it back grants nothing | The picker sending `logoUrl` straight through (a stored, member-editable third-party URL) |
| `institutionId` consumed, never stored | The account's bank is already `provider`; a second column would be a copy to keep in step, and the id's shape belongs to the catalog rather than to Picsou. Re-resolution falls back to matching on the name, which is what makes an MCP- or hand-created account work at all | An `account.institution_id` column (a migration, and a stale id the day a catalog name changes) |
| Re-resolve only on a bank change or a missing logo | An account edit is a user-facing write; a 30s-timeout catalog call on every save (rename, balance correction) would be felt. Both conditions are cheap to evaluate and cover the cases that matter | Always re-resolve (a round-trip per save); resolve once at creation only (an account created before the picker could never get a logo) |
| Property kind as the mark, rather than a dedicated icon field | The kind is already a required, user-picked field on every property, so the glyph costs no new column, no migration and no second thing to keep in step; a separate picker would only buy the freedom to label a garage a parking space | A `real_estate_metadata.icon` column with its own picker |
| lucide components for kinds, files under `public/` for brands | A lucide glyph inherits `currentColor`, so one map serves both themes and the account color. A brand logo cannot be redrawn that way — it is someone's asset, and it ships as a file | An SVG file per kind, light and dark variants |
| Bundled asset + frontend map | No institution catalog to query for either provider, and hotlinking is a dead end: `meria.com/favicon.ico` and `/apple-touch-icon.png` both answer **403** to non-browser clients behind their WAF (the only asset that does serve, `og:image`, is a 3299×2475 dark share banner), and a logo pulled off Amundi's own site would rot the moment they reorganise it. A file in `public/` is the only source that can't break underneath us | Hardcoded remote URL on the account; a generic backend favicon fetcher/cache serving `/api/logos/{provider}` (would also cover BoursoBank, IBKR — but the same WAF breaks it on Meria) |
| Mapping in the frontend, not written to `Account.logoUrl` | No migration and no database backfill is needed for a bundled logo — an existing account simply renders it on the next load (unlike an Enable Banking `logoUrl`, which does need the `ensureLogoUrl()` backfill two rows down). It works in demo mode, and the asset path stays a frontend concern instead of the backend storing a frontend URL | `CryptoExchangePort.logoUrl()` / `AmundiPort` returning a logo copied onto the account by the sync service, mirroring Enable Banking |
| A key stored on the account, not a logo derived from `provider` | A wallet's `provider` is its native ticker, so a provider map can't reach it — and no connector can know whether an address is a Ledger. Storing the choice is also what makes it follow the user across devices and family members, like `color` | Extending `PROVIDER_LOGOS` with every ticker (wrong mark for a Ledger, and unbounded); remembering the choice in `localStorage` (per-browser, invisible to the rest of the family) |
| An opaque slug column, validated by regex | Mirrors how `color` is stored: the backend persists a value it doesn't interpret, the frontend owns what it means. Adding a wallet asset stays a frontend-only change | A Postgres enum or a backend whitelist of keys (a migration + release per asset); writing the asset path itself into `logoUrl` (the backend would be storing a frontend URL, and a member-editable one at that — exactly the injection the capture step avoids) |
| A key swappable only where one already exists, in the picker *and* in `AccountService` | An on-chain wallet is the only account with a genuine choice to make; every other account's logo is determined by its connector or its provider name. Gating on the stored key means no new field has to be plumbed through just to answer "is this a wallet?" — and applying the same test server-side keeps that contract true for a client that isn't the picker | A picker on every `CRYPTO` account, or a backend that accepts a key on any of them (would let an exchange account override its own brand mark); an `isWallet` flag added to `AccountResponse` |
| `color` kept as-is on every account | Still used by `AccountsStackedChart` line colors and the detail page dot; removing it would require a chart color strategy | Drop `color` once a logo exists |
| Best-effort backfill via re-search, not a migration | A migration can't make network calls safely; re-searching on the next scheduled/manual sync is free and self-healing | One-off backfill script at deploy time |
| Backfill only overwrites `logoUrl` when it was `null` | Never clobbers a logo the user already got from a real connection | Always refresh from the latest search result |

## Gotchas / Pitfalls

- **Powens never provides a logo.** `PowensBankConnector.searchInstitutions()` hardcodes `logoUrl = null` for every result. If Powens is ever re-enabled, its accounts will always show the color fallback until the adapter is updated.
- **Backfill match is best-effort, bounded to one attempt.** `ensureLogoUrl()` matches by institution id first, then falls back to a case-insensitive name match, scoped to the requisition's own country. A renamed institution on the provider side may never match — `logoBackfillAttemptedAt` prevents retrying forever, and the account just keeps showing its color, which degrades gracefully.
- **A bundled logo is keyed on a free-text column.** `Account.provider` is written by each connector with no shared enum, so renaming what `CryptoExchangeSyncService` stores (today `ExchangeType.name()`), what `AmundiSyncService` or `BoursoSyncService` stores, or the `"Trade Republic"` literal in `TradeRepublicSyncService` silently drops the logo back to the color circle. The lookup is case-insensitive, which absorbs a `Meria`/`MERIA` change but nothing more — and Amundi's key carries an accent, so it is also sensitive to Unicode normalization.
- **A bundled mark needs its own margin.** The avatar is a circle, so the square the image is fitted into is inscribed in it: a mark drawn edge to edge in its `viewBox` gets its corners clipped, and a wide one loses its ends. Brand SVGs ship cropped tight to the artwork, so `trade-republic.svg` and `blockchain.svg` carry a deliberately padded `viewBox`. Padding the avatar instead was rejected — it would shrink every Enable Banking logo, and those already come with their own whitespace.
- **A mapped asset that isn't there fails silently.** `public/` files aren't resolved at build time, so a typo in `PROVIDER_LOGOS` just yields a 404 and the usual color fallback. `provider-logos.test.ts` is the only thing that catches it — keep it in step when adding an entry.
- **Changing the account type clears the logo, for good.** A key survives only on a `CRYPTO` account that already stores one (`AccountService.normalizeLogoKey()`). Retyping a wallet and retyping it back does not restore the mark: the account now has no stored key, so neither the picker nor a hand-written `PUT` can put one back, and the next sync won't re-seed it either since the connector only writes the key at account creation.
- **A wallet's key is written once.** `WalletSyncService` seeds `logoKey` at account *creation* only. A wallet whose account row somehow predates V75 and escaped its backfill (a hand-restored dump, an account re-keyed by hand) never grows one on a later sync — it falls through to the color circle, and the picker won't appear for it either, since the picker is gated on the key already being there.
- **An unknown key is silent.** The backend accepts any lowercase slug, so an account can carry a key this build has no asset for (a downgrade, a hand-edited row). `logoKeyUrl()` returns `null` and the card falls through to the provider logo, then the color — no error, no broken image.
- **A manual account's logo needs Enable Banking configured.** The catalog is the only source
  a hand-entered account has. With no credentials set, `BankPicker` shows no suggestions and
  `logoUrlOrNull` swallows the failure — the account saves fine and keeps its color circle, which
  is exactly what it did before this existed. Nothing warns about it, by design: entering an
  account by hand must not require a bank connector.
- **The catalog lookup runs inside the account write's transaction.** `AccountService.create()`
  and `update()` are `@Transactional`, and the Enable Banking client's timeout is 30s, so a
  provider hanging holds a connection open for that long. It is bounded, and only reached on a
  bank change or a logo-less manual account — but it is the reason the re-resolution conditions
  are as narrow as they are.
- **Retyping the bank name by hand after picking it loses the id, not the logo.** The id is
  cleared on the next keystroke (deliberately — see step 1 above), so the resolution falls back to
  matching the typed name in the default country. An exact name still resolves; an abbreviation
  ("CA" for Crédit Agricole) resolves nothing and the account keeps whatever logo it had, since
  the provider *did* change and the lookup simply missed.
- **`update_account` over MCP doesn't send a logo key.** `AccountService.update()` keeps the stored one when the request omits it, which is what makes that safe. Note the same tool *does* clear `provider` (it hardcodes `null`), so it is not a general-purpose account editor — and on a manual account that now also clears `logoUrl`, since a card must not show a bank mark for a bank it no longer names.
- **Rendering fallback is render-only.** A broken logo URL is not written back to the database — the same broken URL is retried on every mount (Radix re-attempts whenever `src` changes). This is intentional (the URL may become valid again, e.g. a CDN blip) but means a permanently-dead logo shows the color fallback every time rather than healing itself in storage.

## Tests

- `backend/src/test/java/com/picsou/service/SyncServiceTest.java` — logo resolved server-side at `initiateConnection()` by exact institution id; logo copied from `Requisition` to a new `Account`; backfill sets `Requisition.logoUrl` on resync scoped by country; backfill isn't retried once `logoBackfillAttemptedAt` is set; id match wins over a same-named institution from another country; a failed backfill lookup doesn't break the sync.
- `frontend/src/components/shared/AccountCard.test.tsx` — renders the logo image when the (stubbed) image load succeeds, the color fallback when absent, falls back when the image load fails, renders the bundled asset for a provider with no `logoUrl`, and prefers a connector-supplied `logoUrl` over a bundled one. Its `MockImage` must invoke `load`/`error` listeners with an `{ currentTarget }` event — Radix's `handleLoad` reads it, and calling them bare throws inside Radix instead of failing the assertion.
- `frontend/src/components/shared/AccountForm.test.tsx` — the picked bank and its institution id
  reach the submitted request, and a hand-typed one submits with no id. `provider` is written
  through `setValue` rather than being registered by the input, so this is what proves the value
  is not silently dropped on submit.
- `frontend/src/components/shared/BankPicker.test.tsx` — picking a bank reports its name *and*
  the catalog id, editing the name afterwards drops the id, the suggestion list closes on pick,
  and the field stays a plain editable text input when no catalog is available.
- `frontend/src/lib/provider-logos.test.ts` — every path in `PROVIDER_LOGOS` and `LOGO_KEYS` is root-relative and exists under `frontend/public/`, the exact provider literal each connector writes (Meria, Amundi, Trade Republic), every key the picker offers is mapped, plus the case-insensitive provider lookup and the `null`/unknown-key cases.
- `backend/src/test/java/com/picsou/migration/AccountLogoKeyMigrationTest.java` — V75 against real PostgreSQL: existing wallets are backfilled with `blockchain` (including the lowercased chain segment), accounts that merely look like wallets and exchange accounts stay `NULL`, the backfilled value equals `WalletSyncService.DEFAULT_LOGO_KEY`, and a replay of the file leaves a user's `ledger` choice alone.
- `backend/src/test/java/com/picsou/service/WalletSyncServiceTest.java` — a new wallet account is created with the default key; an existing one keeps the key the user picked across a resync.
- `backend/src/test/java/com/picsou/service/AccountServiceTest.java` — the bank logo of a manual
  account: resolved from the institution the picker sent, searched in the default country when no
  institution was picked, never looked up for a synced account, re-resolved when the bank changes,
  skipped (and kept) when nothing relevant changed, retried for an account that never got one,
  cleared when the bank is cleared, and left untouched on a synced account whose provider is
  blanked. Also `update()` stores the key the picker sent, keeps the stored one when the request omits it, drops it when the account is retyped away from `CRYPTO`, and ignores one sent for a crypto account carrying none (an exchange); `create()` ignores a key whatever the type.

## Links

- Related: [bank-sync.md](./bank-sync.md) — Enable Banking connector and requisition lifecycle
- Related: [accounts-overview.md](./accounts-overview.md) — Accounts page, and the card's five-line anatomy
- Related: [real-estate-valuation.md](./real-estate-valuation.md) — `PropertyKind` and the property form
