# Changelog

All notable changes to Picsou are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **A price the provider could not deliver a minute ago no longer blanks a position
  in the exchange sync.** `refreshPrices` returned a remembered miss as a `null`
  price, `refreshCryptoQuotes` wrapped it in a quote, and the last-known-price
  fallback that exists for exactly this case was skipped: during a CoinGecko rate
  limit, an exchange account holding BTC and ETH was stored, and snapshotted for
  the day, at its BTC value alone. A remembered miss now stays out of the result,
  so the fallback runs, and misses expire after 60 seconds as documented rather
  than after the 15-minute hit TTL.
- **The startup price backfill no longer records a listed company's history under
  a coin's symbol.** Holdings of CRYPTO accounts are backfilled through CoinGecko
  only, as the hourly refresh already did; a coin CoinGecko cannot map is left
  without history instead of receiving twelve months of the stock trading under
  the same ticker (STX: Stacks in the wallet, Seagate on Nasdaq).
- **A cash account in USD, GBP or any other currency is worth its FX-converted
  balance again.** `toEur` used the currency code as a Yahoo chart symbol when the
  account had no ticker, and Yahoo answers `chart/USD` with the ProShares Ultra
  Semiconductors ETF (~89 USD a share): a 1 000 USD checking account showed as
  ~76 000 EUR on the dashboard and in every daily snapshot. Cash now converts with
  the `{CURRENCY}EUR=X` rate, and its cost basis is that same converted figure, so
  a foreign-currency account no longer reports the FX rate as a gain or a loss.
  The balances typed on the account form or as a manual snapshot, and the ones
  Enable Banking reports, are converted the same way before they enter the
  history, which always read them as EUR. A failed FX lookup is remembered for a
  minute instead of being retried by every valuation.
- **A CSV transaction import can no longer run twice on one preview.** The
  `fileToken` was removed from the cache only after the rows had been saved, so a
  second execute that arrived during the import (a double click, a request the
  client retried after a timeout) passed every check and saved the whole file a
  second time: duplicated transactions, and a cost basis and realized P&L off by
  exactly one import, with nothing to flag it. The token is now consumed atomically
  before anything is written, and handed back if the import fails, since its
  transaction rolled back.
- **Trade Republic re-authentication persists again.** Trade Republic now issues a
  session token longer than 1472 bytes, which `CryptoEncryption` inflates past the
  `VARCHAR(2000)` of `trade_republic_session.session_token`: the 2FA succeeded, then
  the INSERT that stored the session was refused, and every following sync asked to
  reconnect, forever. The three encrypted columns whose length a third party controls
  (the Trade Republic session and refresh tokens, the DEGIRO session blob) are now
  `TEXT`, as the Amundi, Bourse Direct and BoursoBank session state already was. (#115)

- **An Amundi account holding two share classes of the same fund now syncs.**
  Amundi does not always put an ISIN in `codeIsin` — on employer funds it holds
  the AMF code — so the holding was keyed on a slug of the fund label, capped at
  30 characters. Two share classes of one fund differ only in their last word, so
  both truncated to the same key, the collision guard correctly refused to merge
  two different funds, and the entire sync failed with `INVALID_DATA` on a
  payload that was perfectly valid. The line's own `codeFonds` is now used
  between the ISIN and the label, and the guard stays behind it.

### Added

- **The French regulated passbooks each get their own account type.** Livret A,
  LDDS, Livret Jeune, PEL and CEL sit alongside the existing LEP instead of all
  collapsing into the generic "Livret d'épargne", so a household holding several
  can tell them apart on the Accounts page — they still total together under the
  Savings filter. The BoursoBank sidecar recognises each of them from the label
  the bank prints, so synced livrets arrive typed rather than lumped; a bank's
  own house passbook (Livret Bourso+) stays the generic type, since it is not a
  regulated product. A new check runs every `AccountType` against the real
  PostgreSQL enum, so a type added without its migration can no longer pass a
  green build and fail on first save.
- **Manual accounts can show their bank's logo.** The bank field of the
  hand-entered account form now searches the institution catalog as you type:
  pick your bank and its real logo lands on the account card, the same one a
  connected account gets. The account still stores only the bank's name — the
  server re-resolves the logo itself from the institution's id, so no
  client-supplied image URL is ever persisted or fetched by a family member's
  browser. Loans get it too, from their lender. The field stays free text
  throughout: a bank the catalog doesn't list, or an Enable Banking install
  that was never configured, simply means no suggestions and the color circle it
  showed before. See [feature notes](docs/features/bank-logos.md).
- **BoursoBank sync — current accounts, livrets and, above all, the PEA.**
  Enable Banking cannot reach a securities account (PSD2 covers payment accounts
  only), so the envelope that often holds the largest balance was invisible.
  Picsou now signs in to BoursoBank directly and imports the current accounts,
  the livrets and the PEA/CTO with their cash, their total and every open
  position. A browserless Python sidecar handles the login: BoursoBank hands out
  its one anti-bot token in the page itself, so no Chromium is needed. The
  virtual keyboard — whose digits are images rather than text, on purpose — is
  decoded by matching each button's SVG. Only app validation is supported as a
  second factor; an SMS prompt is reported as such rather than as a wrong
  password, since every failed attempt counts toward a lockout. Credentials are
  never persisted, only the encrypted session. A portfolio whose total does not
  reconcile with its lines is refused wholesale and the last valid data kept.
  Accounts BoursoBank aggregates from *other* banks are deliberately left out —
  they would duplicate an Enable Banking connection. Reachable from the Sync
  page, the Add-account modal and the setup wizard, in all four locales, and its
  accounts carry the BoursoBank mark rather than a color circle. Validated
  end-to-end against a live account, PEA included.
  See [feature notes](docs/features/bourso-bank.md) and the
  [ADR](docs/decisions/2026-08-11-boursobank-httpx-sidecar.md).
- **Amundi Épargne Salariale sync.** Connect an Amundi account and import every
  funded employee savings plan — PEE/PEG, PERCO, PER Collectif — as its own
  account, leaving emptied and closed dispositifs out,
  with each FCPE line's units, unit value, valuation and unrealized gain. A
  dedicated read-only Playwright sidecar handles the captcha-gated login and the
  mandatory second factor, either an approval in the "Mon Épargne" app or an SMS
  code; credentials and codes are never persisted, only the encrypted session.
  Valuations come from Amundi rather than a price feed, since no FCPE is quotable
  on Yahoo. Imports expose queued/running/success/failure progress, reject plans
  whose total does not reconcile with their lines, and preserve the last valid
  holdings on failure. Reachable from the Sync page and the Add-account modal, in
  all four locales, and its accounts carry the Amundi logo rather than a color
  circle. See [feature notes](docs/features/amundi-epargne-salariale.md)
  and the [ADR](docs/decisions/2026-08-09-amundi-epargne-salariale-sidecar.md).
- **Meria crypto exchange sync.** Connect a Meria account with the single
  read-only API key from `dashboard.meria.com/account/api` — no API secret, and
  the add-exchange form now hides that field for exchanges that don't use one
  (`CryptoExchangePort.requiresApiSecret()`, enforced server-side in all four
  locales). Picsou sums spot wallets, staking and lending contracts per coin,
  counting a contract as its held amount — Meria's `reward` is cumulative
  interest already reflected there — then
  values the total in EUR through the existing price path; coins were added
  to the CoinGecko ticker map so Meria balances aren't silently unvalued. A
  failed sub-call fails the whole sync rather than writing a shrunken balance
  into the net-worth history. The account page groups its positions by product —
  Spot / Staking / Lending — and shows principal, accrued interest and total for
  each yield-bearing line. See [feature notes](docs/features/crypto-tracking.md).
- **Automatic real-estate valuation from open data.** Properties now describe themselves
  (type, category, geocoded address, living and land area, rooms, construction year, floor
  and lift, garage/parking, garden/terrace/balcony, energy rating, and acquisition costs)
  and are re-valued monthly from **free, unauthenticated, Licence Ouverte 2.0** sources:
  DGFiP transaction data via the Cerema DV3F indicators, address geocoding via the IGN
  Géoplateforme, and re-indexing on the INSEE housing price index. No API key and no
  subscription — the estimate writes the account balance, so net worth and the gain curve
  follow automatically, and a MANUAL mode freezes a user's own figure. Every heuristic
  applied to the commune median is disclosed in the UI, along with the confidence band,
  sample size and data vintage. Alsace-Moselle and Mayotte are explicitly reported as
  uncovered rather than given a plausible-looking wrong number. See
  [feature notes](docs/features/real-estate-valuation.md) and the
  [ADR](docs/decisions/2026-08-01-open-data-property-valuation.md).
- **Ownership shares on properties and loans.** A house or a mortgage can be split between
  family members; each member's net worth, history and goals count only their share, and the
  family view stops double-counting a jointly-owned property. A split may total under 100%,
  with the remainder reported as held outside Picsou. Reading a co-owned account is allowed,
  editing it stays with the owner. See
  [feature notes](docs/features/account-ownership-shares.md) and the
  [ADR](docs/decisions/2026-08-01-account-ownership-shares.md).
- **A guided "Immobilier" flow for adding a property.** A dedicated entry in "Ajouter un
  compte" replaces hunting for your house under "Manuel", and with the Immobilier filter
  active the page's primary button targets it directly. Three steps — what it is, where it is,
  what it cost — then the account, its description and its first estimate are created in one
  pass. Bathroom count is now recorded too, and feeds a small declared heuristic.
- **Mortgage-to-property linking.** A loan can be attached to the property it finances,
  giving gross property value, outstanding debt and net equity, both per property and across
  the portfolio.
- **Bourse Direct brokerage sync.** A dedicated read-only Playwright sidecar
  handles login and the six-digit security code, then imports PEA/CTO positions,
  average cost, current price, valuation and account cash. Credentials and OTPs
  are never persisted; only the complete browser session is encrypted at rest.
  Sessions support manual and daily sync, and accounts remain explicitly typed
  as PEA or securities accounts. Imports expose queued/running/success/failure
  progress, reject unreconciled partial portfolios, preserve the last valid
  holdings on failure, and retain native quote currencies alongside broker EUR
  valuations. See [feature notes](docs/features/bourse-direct.md) and the
  [ADR](docs/decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md).
- **Interactive Brokers (IBKR) sync via the Flex Web Service.** Connect once with a
  read-only Flex token + an "Open Positions" query id; Picsou pulls open positions
  end-of-day and maps them to accounts + holdings (one account per IBKR account id),
  valued live in EUR through the existing ticker/price path. Cost basis is converted
  to the account base currency via `fxRateToBase`; per-tax-lot rows are de-duplicated.
  Daily auto-sync runs alongside the other connectors. A connection tab on the Sync
  page (paste token + query id, then sync/disconnect) drives it, in all four locales. See
  [ADR](docs/decisions/2026-07-19-ibkr-flex-web-service.md) and
  [feature note](docs/features/ibkr-sync.md).
- **BNB Chain support and EVM multichain wallets.** On-chain wallets gained an
  `EVM` chain that tracks a single `0x` address across every enabled EVM network
  — Ethereum, BNB Chain, Polygon, Arbitrum, Optimism, Base and Avalanche —
  reporting each native coin (ETH, BNB, POL, AVAX…) plus curated ERC-20/BEP-20
  stablecoins, all over keyless public RPCs (no API key). Balances aggregate by
  symbol across chains. Existing Ethereum wallets are migrated to `EVM`
  automatically, keeping their history — including their display name, which is
  relabelled from "ETHEREUM Wallet" to "EVM Wallet" (custom labels untouched). See
  [ADR](docs/decisions/2026-07-17-evm-multichain-wallets.md).
- **HTTPS for the Docker stack** via an opt-in `tls` compose profile running
  Caddy in front of the app (`docker compose --profile tls up -d`). Caddy picks
  the certificate strategy from `PICSOU_DOMAIN` alone: a real domain gets a
  fully automatic Let's Encrypt certificate, while a LAN IP or `.local` name
  gets one from Caddy's built-in CA (install its root once per device). This
  unblocks Enable Banking, which rejects plain-HTTP callback URLs for
  PRODUCTION applications — previously no Docker deployment could sync banks.
  The profile is off by default so it cannot collide with an existing ingress
  proxy. An optional overlay, `docker/docker-compose.no-http.yml`, removes the
  plain-HTTP `:8080` publish once TLS is confirmed working — pass it as a second
  `-f` alongside the base compose file.
- **HTTPS frontend development mode.** Vite uses trusted local `mkcert`
  certificates from `frontend/.local/certs/` when present and otherwise falls
  back to a generated self-signed certificate. Local Enable Banking callbacks
  and Playwright URL examples now default to `https://localhost:5173`.
- **CSV transaction import for investment accounts (PEA/CTO)** and **realized
  P&L on closed positions**, computed on the fly with the average-cost method
  (#38, #43).
- **German and Spanish translations.** Supported languages are centralized in a
  locale registry (`SUPPORTED_LOCALES`); selectors and `Intl` formatting derive
  from it (#32).
- **Build version surfaced** in Settings → About and `/actuator/info`.
- **More logos on account cards.** Trade Republic accounts now carry the broker's
  mark, and on-chain wallets — whose provider is a bare ticker, so no provider
  logo can match — show a blockchain mark by default. A wallet held on a Ledger
  can be switched to the Ledger logo from the account form, next to the color
  picker; the choice is stored on the account, so it follows every device and
  family member. Existing wallets are backfilled by a migration.
  See [feature notes](docs/features/bank-logos.md).

### Changed

- **HSTS is now opt-in in Docker (`HSTS_ENABLED`, default off).** Nginx
  previously sent `Strict-Transport-Security` unconditionally, including on
  plain-HTTP deployments. Combined with a locally-issued certificate that is a
  lockout trap: the browser remembers the policy, then refuses to offer the
  "proceed anyway" bypass, leaving no in-app recovery. Deployments behind a
  publicly-trusted certificate should set `HSTS_ENABLED=true` to restore the
  previous behavior.
- **The documented Docker Enable Banking callback is now `https://`.**
  `docker/.env.example` previously suggested `http://your-nas-ip:8080/sync/callback`,
  which Enable Banking rejects for PRODUCTION applications — the only mode that
  lists real banks. `docs/features/bank-sync.md` likewise no longer presents a
  plain-HTTP deployment as a supported option for bank sync.
- **UI controls realigned to the shadcn theme radius.** Pill-shape overrides on
  buttons, chips, and tabs were reverted to the theme tokens; Mira
  design-system pass completed with a sidebar style toggle (#46).
- **PnL no longer counts outstanding debt as an investment loss (#18).** Loans
  contribute 0 to `pnl` in every aggregation path (history points, live PnL,
  MCP `get_profit_and_loss`); `rangePnl` compares only holdings priced on both
  sides of the range; allocation percentages divide by their own side of the
  balance sheet (assets by total assets, liabilities by total liabilities).
  Net-worth totals are unchanged — loans remain liabilities. The dashboard
  chart is now titled by wealth mode instead of "Gain / Loss", its tooltip
  shows the backend's debt-neutral gain/loss, and a new Liabilities card lists
  loans separately.

### Fixed

- **Dividend and fee transactions no longer look like purchases.** When a manual
  transaction has a ticker but no supplied or resolved name, the frontend now derives
  its translated fallback label from `txType`. The backend persists only the canonical
  ticker or instrument name, so descriptions no longer embed French UI text (#108).
- **Manual transaction annotations can no longer overwrite synced account state (2026-08-31).**
  Adding, editing, or deleting a manual transaction on a synced account now leaves provider-owned
  holdings, balance, and snapshots untouched. Transaction-derived state is reserved for manual
  accounts. CSV transaction import now accepts only manual investment accounts and rejects synced
  investment accounts before caching a preview or saving rows (#107).
- **Dropdown options were unreadable in dark mode.** Native `<select>` popups
  (account type, currency, bank country, CSV import mapping, property type…)
  don't honour a translucent background — the browser falls back to an opaque
  white popup while the option text stayed the light color meant for a dark
  background, making the list nearly invisible. Every select now uses an
  opaque background/text pair plus a `color-scheme` hint so the native popup
  renders legibly in both themes.
- **A PEA held through ETFs could display 0 €, graph included.** Picsou asked OpenFIGI
  which listings an ISIN maps to and picked one by exchange priority — but OpenFIGI
  returns every listing of an instrument and knows nothing about which one Yahoo quotes,
  and no ordering predicts it: `IE000BI8OT95` (Amundi Core MSCI World) resolved to a US
  OTC ticker Yahoo has delisted, while two other Irish ETFs need exactly that US OTC
  listing to be priced at all. A holding that cannot be priced is excluded from its
  account's total, so an account whose every line resolved that way was worth nothing —
  which is what a PEA of UCITS ETFs, all domiciled in Ireland or Luxembourg, looks like.
  The pick is now verified against Yahoo, and when it has no quote, Yahoo's own search
  for the ISIN decides; a pick is only ever replaced by a symbol that actually quotes, so
  a rate-limited Yahoo can never downgrade a working one. Positions already stored with a
  raw ISIN as their ticker — what an OpenFIGI outage or its 25 requests/min keyless limit
  leaves behind, and which nothing ever revisited — are re-resolved once at startup.
  See [feature notes](docs/features/ISIN_TO_TICKER_CONVERSION.md) and the
  [ADR](docs/decisions/2026-08-10-yahoo-verified-isin-tickers.md).
- **Business-oriented banks never appeared in the bank search.** Picsou asked
  Enable Banking only for retail (`personal`) institutions, so BaaS and
  professional banks — Swan among them — were invisible in the picker even
  though the account existed and the credentials were valid, with nothing to
  distinguish that from a misconfiguration. The catalog is now fetched
  unfiltered, each bank's PSU type is read from the provider and carried through
  the connection flow so the consent page presents the right login, and
  business-only banks are marked with a **Pro** badge in both bank pickers.
  See [feature notes](docs/features/bank-sync.md).
- **Property valuation failed for every commune.** The Cerema response is ~265 KB, just past
  the HTTP client's 256 KB default buffer, so the body was never assembled. Worse, the error
  was swallowed and reported as "no comparable transactions in this municipality" — pointing
  at the address rather than at the transport. The buffer is raised, and a source that cannot
  be reached now says so instead of impersonating an empty market.
- **A property with no valuation showed 0 € and a 100% loss.** It now falls back to its cost
  basis until an estimate succeeds; the fallback only ever lifts a zero.
- **Real-estate gain/loss ignored acquisition costs.** Account cards measured the gain against
  the purchase price alone; French notary fees alone run 7-8% of a purchase, so every property
  overstated its gain by that much. It is now measured against the full cost basis.
- **Saving real-estate metadata on a property that had none violated a NOT NULL constraint**
  (`real_estate_metadata.member_id`). Never surfaced because no client called the endpoint.
- **A price provider outage no longer blanks positions, invents a loss, or writes
  a zero into your history.** After a restart, a rate-limited CoinGecko left the
  largest lines of a crypto account with no price: they dropped out of the account's
  value while their full purchase cost stayed in the cost basis, so an untouched
  account reported **-85%** — and a sync running at that moment stamped a **0 €**
  balance into that day's net-worth snapshot, which nothing later corrected. Three
  changes: prices now fall back to the last one recorded (up to 7 days old, shown
  with a marker and its date instead of a dash); an asset that cannot be valued at
  all is excluded from the cost basis as well as the value, so the percentage stays
  honest; and a sync that can value nothing refuses rather than recording a zero.
  A resync repairs an already-recorded zero for the current day. See the
  [ADR](docs/decisions/2026-08-01-last-known-price-fallback.md).
- **Picsou stops provoking the rate limits it then suffers from.** Reads used to
  issue one price request per holding on every page render and retry immediately on
  failure, so a brief 429 sustained itself for hours. Prices are now resolved one
  batch per page, a failed ticker is left alone for a minute, CoinGecko calls pause
  while a 429 is in force, the hourly refresh finally covers tickers held *inside*
  accounts (previously only account-level ones, i.e. never the crypto ones), and the
  startup backfill skips history it already has instead of re-downloading a year of
  it at every boot.
- **The GitHub link in Settings → About now points to the right repository.** It
  linked to `github.com/zoeille/picsou`, which does not exist; the repository is
  `github.com/zoeille/picsou-finance`.
- **Restoring several tabs at once no longer logs you out everywhere.** When
  multiple tabs were restored together they each presented the same "Remember
  Me" token; the first request rotated it and the rest looked like a replayed
  (stolen) token, so theft detection revoked the whole series and every tab was
  logged out. `validateAndRotate` now serializes the rotate path with a
  row-level lock (`findBySeriesIdForUpdate`) and remembers the immediately-previous
  token hash, accepting it for a short grace window
  (`app.persistent-session.rotation-grace-seconds`, default 30s). The window is
  **anchored** to the rotation that opened it — a previous-token acceptance does
  not advance it — so every tab in the burst is tolerated (not just the first
  two) and replaying the previous token cannot slide the window forward. A token
  presented after the window still trips theft detection (migration `V56`).
- **Bourse Direct positions no longer appear at €0 when an ISIN has no live
  quote.** Dashboard totals now reuse the same atomic account valuation as
  account cards and history. A guarded migration also restores per-position EUR
  values from early connector data only when every stored price reconciles with
  the broker's account total minus cash; ambiguous legacy quotes remain unset.
- **Wallet sync and removal failures now say why.** Both buttons reported nothing
  at all when they failed — the row simply re-enabled, and the delete dialog sat
  there — so a `422` from an RPC outage was indistinguishable from success. The
  reason is now shown against the wallet that failed, and inside the delete
  dialog, matching the add-wallet form.
- **A bad price response can no longer cost a day of history.** The daily
  snapshot job is transactional and looped over every account without a guard, so
  one malformed CoinGecko reply would have aborted the remaining accounts and
  members *and* rolled back the snapshots already taken. Each account, member and
  backfilled ticker is now guarded individually. Relatedly, a genuine bug in the
  price adapter is no longer swallowed as "no prices available" — only real
  upstream outages are.
- **A price-provider outage can no longer zero a wallet's balance.** If no asset
  in a wallet could be priced, the sync recorded a 0 EUR balance and stamped a 0
  snapshot for that day — flattening the net-worth chart for what was a transient
  outage, and doing it quietly because the holdings themselves were preserved.
  That sync now fails instead, leaving the previous balance intact. A partial
  outage still records a partial total.
- **One bad price no longer blanks the intraday chart.** A failure fetching
  intraday prices for a single ticker returned a server error for the whole
  chart; that ticker is now omitted and the rest still renders.
- **Invalid wallet addresses fail fast with a clear error.** Adding a wallet with
  a malformed address — or no address or chain at all — reported a `422` "could
  not sync, please try again later", inviting a retry of input that can never
  succeed, after a pointless call to the chain's RPC. The format is now checked
  up front and comes back as a `400` naming what was expected — and the crypto
  wallet form actually displays it, where it previously swallowed add failures
  and simply appeared to do nothing.
- **CoinGecko outages are diagnosable.** A failed price fetch returned an empty
  map indistinguishable from "nothing to price", logged as one opaque line.
  Failures are now classified — rate-limit, server error with status and body,
  timeout with its duration, or an unexpected error with its stacktrace — and
  always name the tickers involved. Prices still degrade to "unvalued this
  cycle" rather than failing the sync, which is what keeps a price blip from
  touching holdings or their cost basis.
- **Ethereum wallet balances sync again.** The hard-coded `cloudflare-eth.com`
  RPC was deprecated and started returning an HTTP 200 JSON-RPC error for
  `eth_getBalance`, which the adapter read as a 0 balance — wallets appeared to
  sync but showed nothing. Switched to `ethereum-rpc.publicnode.com`.
- **On-chain wallet adapters no longer report a false 0 on RPC failure.** Both
  the Ethereum and Solana adapters read the JSON-RPC `result` with `path(...)`,
  which turned an `error` payload (rate-limit, deprecated method, node outage)
  into a silent 0 balance rather than a sync error. They now validate the
  envelope — a present `error`, a missing `result`, or an empty response throws
  and surfaces as a `422` sync failure instead of corrupting the balance. A
  genuinely empty wallet still reads 0. On Solana this covers both the native
  SOL and the SPL-token call, and sync failures now preserve their root cause in
  the logs.
- **Wallet sync distinguishes bugs from routine RPC failures.** `WalletSyncService`
  now logs unexpected errors (NPE, etc.) at `ERROR` with a full stacktrace instead
  of a one-line `WARN`, so a real bug can't hide as a transient sync; the friendly
  `422` shown to the user is unchanged. A malformed SPL token balance or an
  unexpected token-list shape is logged and skipped (SOL and other tokens still
  sync) rather than silently dropped, and a malformed Ethereum hex balance now
  fails the sync with a clear message instead of an opaque error. Batch resync
  (`resyncAll`) reports per-wallet outcomes, so the scheduler logs which wallets
  failed and the MCP wallet-sync tool answers with the real success count.
  `WalletRpcException` now also has a dedicated `GlobalExceptionHandler` mapping
  (generic `422`) as defense-in-depth, so a bad RPC response can never surface as a
  raw `500` even on an unwrapped call path.

### Security

- **Sync logs no longer dump full third-party payloads.** Provider responses
  were written whole at INFO/WARN/ERROR in production: `EnableBankingBankConnector`
  logged the full balances object (account amounts) — now an INFO **count-only**
  line (visibility kept, amounts dropped); `FinaryApiClient` logged the raw Clerk
  sign-in response (which can carry session tokens) — now a body-free message.
  Every Finary/Clerk error body that flows into an `IOException` → `SyncException`
  (and thus into logs *and* the user-facing 422) is now bounded before it is
  thrown — Clerk auth and the low-level retry path to 200 chars, the Finary
  data-API body to 500 (enough to keep its actionable message, still capped).
  Defense-in-depth against financial PII and third-party secrets landing in logs.

## [1.0.13] — 2026-07-07

### Changed

- **Setup, sync, and family safeguards improved (#29).** Hardening pass across
  the setup wizard, sync flows, and family management.

## [1.0.12] — 2026-07-07

### Fixed

- **Finary sync differentiates error types and retries transient failures
  (#27).**
- **Docker zero-config first boot works without a `.env` file** — the compose
  `env_file` entries are marked optional.

## [1.0.11] — 2026-07-05

### Added

- **Bank logos on account cards.** Enable Banking institution logos are shown
  as circular avatars, falling back to the account color when absent.

### Fixed

- **Remember Me hardening.** Persistent-session revocation is honored on
  `/auth/refresh`; sessions survive tab/browser restarts; security regressions
  in session restore closed; logout failures surface a toast instead of
  failing silently.
- **Trade Republic tickers.** Centralized XF000 crypto detection with legacy
  ticker backfill, generalized ISIN parsing (crypto exchange suffixes),
  resolved ticker `FORBIDDEN` errors and null Bitcoin ISIN names.

### Changed

- **Flyway `out-of-order` enabled** so cross-branch migrations apply cleanly.

## [1.0.10] — 2026-06-29

### Fixed

- **Enable Banking `FAILED` sessions auto-retry** on the next sync instead of
  staying stuck; Trade Republic `compactPortfolioByType` fixed.

## [1.0.9] — 2026-06-27

### Fixed

- **Finary import mapping** type dropdown includes `LOAN` and `REAL_ESTATE`.

## [1.0.8] — 2026-06-27

Security release: remediations from a 2026-06-27 security audit, the login
username-enumeration fix, and import/build fixes.

### Security

- **Login no longer leaks which usernames exist (GHSA-ww5m-pxgq-8qq6, CWE-208).**
  An unknown username now runs a decoy bcrypt comparison so its response latency
  matches a wrong-password attempt, closing the enumeration timing oracle.
- **Goals can no longer read another member's accounts (IDOR, CWE-639).**
  `GoalService.create`/`update` resolve account ids through a member-scoped
  finder instead of the unscoped `findAllById`, so a member cannot attach — and
  read the live balance of — accounts they do not own.
- **Admin password reset now invalidates the member's existing sessions
  (CWE-613/640).** `POST /api/auth/activate/{token}` is the shared sink for
  new-member activation, admin-initiated password reset
  (`FamilyService.resetPasswordToken`) and admin-recovery completion. It set the
  new password hash but — unlike self-service `change-password` — never bumped
  `tokenVersion` or revoked persistent sessions, so after an admin reset a
  (possibly compromised) member's old access/refresh JWTs and Remember-Me cookies
  stayed valid. `activate()` now bumps `tokenVersion` and calls
  `PersistentSessionService.revokeAllForUser`, mirroring `change-password`.
- **Closed a login timing oracle for pending-activation members (CWE-208).** An
  invited-but-not-activated member has a blank `password_hash`, and
  `passwordEncoder.matches(pw, "")` short-circuits without running bcrypt — making
  that path measurably faster than the unknown-user and wrong-password paths and
  letting an attacker tell a "pending-activation profile" apart from "no such
  user". `POST /api/auth/login` now runs the same dummy-hash bcrypt round when the
  stored hash is blank and fails exactly like a wrong password, so all three
  failing paths are timing-indistinguishable. Behavior for activated users is
  unchanged.
- **Static assets keep their security headers.** The nginx cache block no longer
  drops `nosniff`/CSP/`X-Frame-Options`/HSTS for `.js`/`.css` responses.

### Fixed

- **Scoped MCP tools work again — the security context now survives the
  servlet→tool thread hop.** Spring AI runs `@Tool` methods on a Reactor
  scheduler thread, but `AccessKeyAuthFilter` authenticates the `psk_` key on the
  Tomcat servlet thread by setting `SecurityContextHolder` (a `ThreadLocal`). The
  tool thread therefore saw no `Authentication`, so `ScopeEnforcementAspect`
  found no scopes and every scoped `tools/call` failed closed with "missing
  scope" — even for keys that held the scope. A `SecurityContextThreadLocalAccessor`
  is now registered with the Micrometer `ContextRegistry` and Reactor's automatic
  context propagation is enabled (`McpSecurityContextPropagationConfig`), so the
  context is captured at subscription and re-installed around tool execution. The
  accessor self-clears on every reset/close path, so a pooled scheduler thread
  never leaks one request's identity into the next. See
  [docs/lessons/thread-local-context-across-async-hop.md](docs/lessons/thread-local-context-across-async-hop.md).
- **Finary loan accounts are now imported (issue #11).** Loan/mortgage accounts
  are exposed by Finary through a dedicated `/loans` endpoint, not the portfolio
  `credits`/`credit_accounts` categories that the API sync queried — so they
  never appeared in the import preview and were never synced. The sync now also
  calls `FinaryApiClient.fetchLoans()` and adapts each entry to the common
  account shape under a synthetic `loans` category, so loans flow through the
  normal preview/mapping/execute pipeline and map to `AccountType.LOAN`. The
  outstanding amount is stored as a negative balance (a loan is a liability).
- **Frontend `Transaction` types declare the `name` field (#12).** A `name`
  field added to transactions wasn't declared on the TypeScript interfaces,
  breaking `tsc -b` (and the Docker image build) on `main`.

## [1.0.7] — 2026-06-04

Patch release: renumbers the new `transaction.name` migration so it no longer
collides with the 1.1.0 budget branch.

### Changed

- **The `transaction.name` migration moves from `V33` to `V36`.** `V33`–`V35`
  are reserved by the 1.1.0 budgets branch (`budget_foundation`,
  `budgets_envelopes`, `recurring`), so the migration introduced in 1.0.6
  collided — a dev database on the budget timeline rejected it on a Flyway
  checksum mismatch, and a future `main` ↔ `1.1.0` merge would have carried two
  `V33` files. `V33__transaction_security_name.sql` is renamed to
  `V36__transaction_security_name.sql` (the next free slot after the budget
  migrations); the SQL is unchanged. Upgrade directly to 1.0.7 from ≤ 1.0.5 so
  the column is only ever applied as `V36`.

## [1.0.6] — 2026-06-03

Patch release: manual investment entry accepts an ISIN, and positions gain a
human-readable name instead of a bare ticker.

### Added

- **Enter a position by ISIN.** The "Ticker or ISIN" field on manual investment
  entry now accepts a 12-character ISIN. `ManualTransactionService` resolves it
  to a Yahoo ticker and display name via `OpenFigiIsinConverter` at write time,
  so an ISIN entry and the equivalent ticker entry merge into a single position
  and Yahoo pricing keeps working.
- **Positions carry a first-class name.** A nullable `transaction.name` column
  holds the human label, and `HoldingComputeService` names each position from the
  most recent transaction that carries one — guarded so a nameless manual entry
  never erases a bank-synced name. The raw ticker no longer leaks into the Name
  column.

### Database migrations

- **V33** *(renumbered to `V36` in 1.0.7)* — nullable `transaction.name` column.

## [1.0.5] — 2026-06-03

Patch release: closes a session-bleed where, on a shared browser, logging in as a
2FA-protected user could drop you onto a *different* (no-MFA) member's account.

### Fixed

- **Logging in as a 2FA user no longer lands you on someone else's account.** On a
  shared browser still holding a leftover "Remember Me" cookie for a *no-MFA* account,
  a 2FA user's login correctly stopped at the TOTP step and issued no session — so the
  next request fell back on the lingering cookie and authenticated the *other* user.
  `POST /api/auth/login` now severs any pre-existing session cookies the moment a
  password is verified while the second factor is still pending, and drops a leftover
  persistent ("Remember Me") cookie belonging to a different user when completing a
  login without Remember Me. A trusted device's own cookie is preserved.
- **The login path now resets per-user client state.** The cache and impersonation
  reset that already ran on logout (1.0.4) now also runs the instant a login
  establishes a session (non-MFA login and MFA verification), centralised in a single
  `resetClientState` helper. This stops a freshly logged-in user from briefly seeing
  the previous user's cached balance/history on a shared browser.

## [1.0.4] — 2026-06-03

Patch release: fixes two issues hitting non-admin family members — a spurious 403
on first login and seeing another member's financial data on a shared browser.

### Fixed

- **A non-admin member no longer gets a 403 / `/error/403` redirect on login.** The
  sidebar called the admin-only `GET /api/family/members` for every user; the global
  403 interceptor then bounced the whole app to the error page. The call is now gated
  on `isAdmin`, so non-admins never hit the admin-only endpoint.
- **A member no longer sees another member's balance and net-worth history.** On a
  shared browser the persisted impersonation target (`activeMemberId`, in localStorage)
  and the user-agnostic TanStack Query cache survived logout, so the next person's first
  login showed the previous user's data. Login and logout now reset the profile store
  and clear the query cache. As defense-in-depth, the client only sends `?memberId` for
  admins, and `HistoryService` enforces account ownership unconditionally (a `null`
  member id is now rejected instead of bypassing the check).

## [1.0.3] — 2026-06-03

Patch release: clearer Enable Banking setup, an honest admin configuration view,
and integration toggles that reflect what's actually configured.

### Changed

- **Enable Banking now asks for a single "Application ID" instead of two near-identical
  fields.** Per Enable Banking's spec the JWT `kid` *is* the application's ID and the key
  file is named `<applicationId>.pem`, so the "Key ID" was never a distinct value — users
  retyped the same UUID twice, an easy way to mis-key one. Both the setup wizard and
  Admin → Integrations now collect only the Application ID and derive the Key ID from it,
  with a hint explaining they're the same value. Existing installs that set
  `ENABLEBANKING_KEY_ID` (env) or have a `key-id` row keep working — `keyId()` honors an
  explicitly-configured value and only falls back to the Application ID otherwise.

### Fixed

- **Admin → Integrations no longer shows Enable Banking as "not configured" when it works.**
  The settings view read raw DB rows while the connector resolves credentials DB-then-env,
  so an install configured via `.env` saw empty fields and a false warning banner. The
  admin view now reads the same resolved provider the connector uses.
- **Integration toggles reflect actual configuration, not just a stored boolean.** An
  integration configured via `.env`/Docker (Enable Banking, Trade Republic, Finary) now
  reports as enabled even with no DB intent-flag set — the toggle is `stored-flag OR
  detected-config` rather than trusting the flag alone.

## [1.0.2] — 2026-06-02

Patch release: fixes a **403** that blocked login and the setup wizard's **Origins**
step when Picsou is served over HTTPS behind a reverse proxy.

### Fixed

- **403 "Request failed with status code 403" over HTTPS behind a reverse proxy.**
  Picsou serves the SPA and the API from the same origin, so login/setup traffic is
  same-origin and should never hit CORS. But Spring's `CorsUtils.isCorsRequest()`
  compares the `Origin`'s scheme/host/port against the request's, and with no
  forwarded-headers handling the TLS-terminated backend saw `http` while the browser
  sent `https` — the scheme mismatch made same-origin requests look cross-origin and
  the fail-closed allow-list rejected them with `403`. The backend now trusts proxy
  headers (`server.forward-headers-strategy: framework`) and the bundled nginx
  preserves the upstream `X-Forwarded-Proto`/`Host`/`Port` instead of overwriting them
  with its own plain-HTTP `$scheme`. Same-origin HTTPS traffic is now correctly
  recognized and the wizard works without pre-seeding `ALLOWED_ORIGINS`.

  > Behind a reverse proxy, ensure it forwards `X-Forwarded-Proto: https`
  > (Caddy, Traefik, Nginx Proxy Manager and Cloudflare Tunnel do by default).

## [1.0.1] — 2026-06-02

Patch release: a crash-recovery fix for invalid account currencies, a dependency
security bump, and Docker image-tagging refinements.

### Fixed

- **Invalid currency codes no longer crash the whole app.** A manual account saved
  with a non-ISO-4217 code (e.g. `AMAT`) made `Intl.NumberFormat` throw a `RangeError`
  in `formatCurrency`; the throw bubbled to the root error boundary and blanked the
  entire UI, leaving the offending account impossible to reach, edit, or delete.
  `formatCurrency` now degrades to `"<amount> <code>"` instead of throwing (which also
  rescues any already-persisted bad account), the account form uses a curated currency
  dropdown with locale-aware labels, and the backend rejects unknown codes with a `400`
  via a new `@ValidCurrency` constraint (`0703274`). Closes #9.

### Security

- **Dependency advisories patched.** `bcprov-jdk18on` 1.78.1 → 1.84 and `poi-ooxml`
  5.3.0 → 5.4.0 (`5a7e776`).

### Changed

- **Docker image tagging.** `main` builds are now published as `latest` and version
  tags drop the `v` prefix (`5f887ce`); deployment docs run from the published GHCR
  images instead of building locally (`50dc0e1`).

## [1.0.0] — 2026-06-02

First stable release. Builds on the MVP (commit `37920d1`) with multi-member
families, 2FA + persistent sessions, a zero-config setup wizard, bank/broker/crypto
integrations, loan amortization, GDPR data export, savings-goal trajectories,
per-holding security insight, and hundreds of refinements across security,
internationalization, and mobile responsiveness.

### Added

#### Multi-account family
- **Family members & roles.** New `FamilyMember` entity, `UserRole` enum, sharing
  entities, and per-row `member_id` foreign keys across all financial tables
  (`d95b7b7`, `d7abdf2`).
- **Member-scoped services.** All services, controllers, and repositories scope
  queries by `memberId`, with admin override for impersonation (`68a97ba`,
  `2dbbeb6`).
- **`UserContext` helper.** Single source of truth for "who is the current user
  and which member are they viewing?" (`050c49d`, `c548b7f`).
- **Family API & UI.** `FamilyController`, `FamilyViewController`, family stores,
  sidebar, and pages for creating members, switching profiles, and viewing
  shared resources (`a5308e8`, `a3ee1c0`).
- **Username change with token rotation.** Settings → Profile lets a user rename
  themselves; access/refresh JWTs are re-issued so the session doesn't drop
  (`7f8c988`, `3454307`).
- **Admin members management.** Admins can invite, activate, reset password, and
  reset 2FA for other members (`6a3c978`, `36ef439`).
- **Create members from the Admin page.** Admin → Members can now create new
  users directly, deriving the login from the display name (`e305096`).
- **Delete members with an active account.** Admins can remove a member even
  when that member still owns an account, without hitting a
  `TransientObjectException` (`a55dfb2`, `682a6f3`).

#### Two-factor authentication & persistent sessions
- **TOTP 2FA.** `UserMfa`, recovery codes, anti-replay window, full enrolment
  wizard, and verification challenge page (`98243c5`, `ccddd88`, `6dd476a`,
  `a95d752`, `5f60ce5`, `c59a439`).
- **Remember Me / persistent sessions.** Rotating tokens with theft detection,
  silent re-login filter, and admin force-disable (`6c1ae82`, `a481128`,
  `5f724e6`, `7afd7d0`).
- **MFA-aware controllers.** `MfaController` (step-up reauth) and
  `SessionController` (list/revoke active sessions) (`0df1f68`, `7afd7d0`,
  `db40cf6`, `07a4fff`).
- **Stateless JWT invalidation on password change.** New `token_version` claim
  bumped on password change; persistent sessions revoked, refresh+access cookies
  re-issued (`056a900`).
- **Admin password recovery.** `ADMIN_RECOVERY_ENABLED=true` triggers an
  `ApplicationRunner` at boot that mints a 1-hour activation token for the admin
  account, bumps `tokenVersion` to invalidate active sessions, and prints the URL
  to the logs. The login page points the operator to the console reset link while
  recovery is active. Documented in `docs/features/admin-recovery.md` (`9297246`,
  `cd3d88e`).

#### Setup wizard (zero-config first launch)
- **Guided first-boot flow.** `SetupController`, `SetupService`, `SetupFilter`,
  audit log, and `AppSetting` key/value store let admins complete admin account,
  security (CORS, secure cookies, encryption key), and integrations setup with no
  env-var editing (`627533a`).
- **Per-integration mini-wizards.** Enable Banking (5-step keypair flow),
  BoursoBank, Trade Republic, Finary, and crypto-exchange setup pages (`627533a`).
- **CORS reload from environment.** `POST /api/admin/settings/cors/reload-from-env`
  plus a "Reload from environment" button in the admin Security panel — an escape
  hatch when the operator changes the public URL after the wizard ran (`9297246`).

#### Security insight (per holding)
- **Asset type + ETF composition.** `SecurityInsightService` classifies a holding
  (stock / ETF / …) and, for ETFs, surfaces top companies, country, and sector
  breakdowns inside the holding detail modal (`befb13d`).
- **Boursorama composition provider.** Composition is resolved and parsed from
  Boursorama (302-redirect resolution), the single source after the issuer-holdings
  approach was superseded; the port returns an aggregated `EtfComposition` and the
  per-issuer adapters were dropped (`68caf43`, `1f41932`, `dfb1407`, `ca49378`).
- **Stable i18n keys for sectors/countries.** Boursorama FR labels map to stable
  i18n keys with a graceful fallback to the raw label when a key is missing
  (`8bdfc4f`, `bdecde3`).
- **Block / line composition views.** A small per-section toggle switches each
  composition between labeled blocks and a slim colour bar + wrapping legend; the
  line view keeps the breakdown readable on phones (`a66f043`, `0225a25`).

#### Integrations
- **BoursoBank** *(disabled in 1.0.0)*. Python sidecar (Selenium-based),
  `BoursoAdapter`, `BoursoController`, sessions table, V23 migration shipped but
  the sidecar and all UI entry points are gated off until the integration is
  finished (`b953e55`).
- **Trade Republic compact portfolio.** `compactPortfolio` with `secAccNo` for
  portfolio sync; deduplication when multiple ISINs map to the same ticker
  (`91972b6`, `1b91abf`).
- **Finary.** Full Finary API import + auto-sync, holdings preservation across
  syncs, manual-transaction protection, daily scheduler, proactive TOTP detection
  in the Add Account modal (`6c9777e`, `93c7ee0`, `0328533`, `1faa1d8`, `0f4bf07`,
  `fbeb155`).
- **Powens** *(experimental, disabled in 1.0.0)*. `PowensBankConnector` ships in
  the codebase but is untested end-to-end; `@Primary` was removed so Enable
  Banking remains the canonical `BankConnectorPort` even when `POWENS_CLIENT_ID`
  is set (`6c9777e`).
- **Crypto exchanges.** Binance integration via `CryptoExchangePort` (`b70b2aa`,
  `aab51c4`).
- **On-chain wallets.** Bitcoin (xpub, zpub, output descriptors), Ethereum, and
  Solana via dedicated adapters (`b70b2aa`, `2eda584`, `4b51f32`).
- **Solana SPL token support.** `SolanaWalletAdapter` also calls
  `getTokenAccountsByOwner` and surfaces known stablecoins (USDC, EURC, USDT)
  alongside the native SOL balance. `WalletPort.fetchBalance` → `fetchBalances`
  returning `List<WalletBalance>`; Ethereum and Bitcoin adapters adapted to the
  list signature (`9297246`).
- **OpenFIGI ISIN → ticker resolver** for Yahoo Finance prices (`0bf43c6`).

#### Loans
- **On-the-fly amortization.** `LoanAmortizationService` derives the schedule from
  loan inputs without storing a row per month; the UI renders a progress card,
  monthly breakdown, cost summary, and amortization chart (`52aa8e5`).
- **Extra loan fields.** V27 migration adds APR, fees, and supporting columns on
  `Debt` (`52aa8e5`).

#### Manual transactions
- **CRUD endpoints.** `POST` / `DELETE /api/transactions/...`, with the manual
  flag preserved across re-syncs (`969ba10`, `93c7ee0`).
- **Investment transactions.** Holdings auto-fill; BUY/SELL drive position
  derivation via `HoldingComputeService` (`a12321a`, `4f534ac`, `9b144d8`,
  `210d758`).
- **AddTransactionModal** with hooks, error handling, and a TransactionsList
  delete action (`429eca0`, `b5b1d41`).

#### Goals
- **Donut year cards.** Year grid view rendered as donut cards using `oklch` CSS
  vars (`f30d77b`, `33bbcf5`, `a3f9168`).
- **Ideal target trajectory.** The goal detail chart draws an ideal-pace line
  anchored at the baseline, plus an at-current-pace projection, and crops to the
  goal's `createdAt` instead of the full history window; the X axis is now a true
  time scale so uneven date spans render cleanly (`a865c37`, `1fb6ff8`, `25ef6bd`,
  `1f4a9d4`).
- **`isOnTrack` from cumulative objective.** On-track status is derived from the
  cumulative past-month objective vs. effective contributions, giving the benefit
  of the doubt when there is no past data (`3f4ec38`).
- **Flexible contribution editing.** Comma- or point-decimal inputs, side-panel
  calendar editing, and history backfill for manual contributions (`55737ad`).

#### GDPR data export
- **Self-service export** of profile, accounts, holdings, transactions, goals,
  debts, wallets, sharing settings, bank connections, and balance snapshots in
  JSON + CSV, gated by re-authentication and rate-limited (`d0933ac`, `3f58502`,
  `9287d47`, `66a274a`, `22dd58b`).

#### History, debts, real estate
- **History/PnL endpoints**, debts, real estate metadata, error pages, mobile
  nav, sync modal (`dad9bea`).

#### Holdings UX
- **Edit & detail modals**, empty-chart state with intraday support (`de40df0`).

#### Other
- **Mandatory encryption** of secrets at rest (`6c1ae82`, `6c9777e`).
- **Theme persistence** across sessions (`6c9777e`).
- **All-in-one Docker image** with supervisord, dev hot-reload Dockerfiles,
  slimmer `tr-auth` sidecar (`cd651e6`).
- **GHCR publishing.** CI publishes per-branch images on every push and ships the
  `tr-auth` sidecar image alongside the main app (`24dfeb7`, `751160e`).

### Changed

- **Frontend rewrite** with shadcn/ui and a feature-based architecture (`33a384c`).
- **Branding** — the frontend package is now `picsou`, with a black-logo favicon
  (`4c4ca66`).
- **Icon migration** from hugeicons to `lucide-react` (`f3bb858`).
- **Locale-aware formatting** helpers via `getLocale()` (`3b9b0f4`); all remaining
  date displays routed through locale-aware formatters (`37b2cab`); date inputs
  honor the configured date-format setting via a hybrid native/desktop
  `DateInput` (`d66ff93`).
- **Centralized error formatting.** Backend messages are English and surfaced
  through a single friendly frontend formatter (`formatApiError`, which accepts an
  i18next `TFunction`) (`b682a0e`, `c04b4b0`).
- **Add Account modal** unified with embedded sync wizards (`7a96584`).
- **Layout, sidebar, dashboard, account detail** redesign + updated demo data
  (`001551f`).
- **Mobile responsiveness** across the whole app (`5017206`).
- **License** changed from MIT to Apache 2.0 + Commons Clause (`e9185c9`,
  `25825e5`).
- **Enable Banking setup wizard** — the redirect URI is shown before the
  credentials step so users can whitelist it before generating their Application
  ID / Key ID; the test step warns that EB only activates the application after a
  real bank account is connected (otherwise the test reports a misleading "invalid
  key"); SANDBOX vs PRODUCTION warning banner and a mandatory acknowledgement
  checkbox; PSD2 scope note (current accounts only) in step 1 and `BankSyncTab`;
  sign-in link updated to `https://enablebanking.com/sign-in/` (`9297246`).
- **Trade Republic Add Account** — the PIN field is masked (bullets) like a
  password input.
- **Setup wizard intro** — `HelloGreeting` cadence cut from ~19 s to ~7.8 s, with
  an explicit Skip button and a 5 s watchdog so a stalled font/i18n load can never
  block the wizard (`9297246`).

### Fixed

- **CORS with credentials.** Forbid wildcard origins; default to empty (no
  cross-origin); strip `*` in DB-loaded values; defense-in-depth at parse and
  Spring layers (`f8e92f7`).
- **Independent members private from impersonation (BOLA).** Admin impersonation
  can no longer reach independent members' data; debt `linkedAccountId` lookups
  are member-scoped (`e2ad075`, `cb7cf49`).
- **Goal contributions IDOR.** `/api/family/goals/{id}/contributions` checks
  ownership and sharing visibility (`7236faa`); new manual contribution entries
  are bound to the correct member (`45670e0`).
- **MFA error semantics.** An invalid TOTP / recovery code returns `400`, not
  `401`, so the client doesn't trigger a refresh loop (`68649c5`).
- **Deactivated accounts.** Login/refresh rejects deactivated accounts to stop a
  401 loop (`2780573`).
- **Deleted accounts no longer reappear after sync.** Accounts soft-delete via
  `@SQLRestriction("deleted_at IS NULL")`; sync upserts in `SyncService`,
  `TradeRepublicSyncService`, and `CryptoExchangeSyncService` refuse to resurrect
  removed accounts (`9297246`).
- **Add-account no longer 502s.** Enable Banking polling capped at 4.5 s (3 ×
  1.5 s) instead of 24 s; an unlinked session returns `[]` and the requisition is
  marked `FAILED` so the UI retry button takes over; nginx `/api` locations gained
  60 s read-timeout headroom (`9297246`).
- **FX conversion on prices.** Yahoo responses are FX-converted and the unsafe
  native-price fallback was removed (`f0e0ac1`).
- **Holdings.** VWAP deduplication, coherent live-price math, and correct
  capital-invested in the detail modal (`60c9a75`); null-quantity handling, Spring
  `@Transactional`, N+1 fix (`4f534ac`, `9b144d8`).
- **Dashboard net worth** reads invested capital from `balance_snapshot`
  (`d99e67b`).
- **Account balance edits.** Save errors surface in the month-end balance modal
  (`ecd2fcd`); `POST /{id}/history` is accepted for manual snapshots (`7922fb4`).
- **Family** — delete errors surface in the confirm dialog (`cb4e3a3`); admin
  shows "Administrateur" instead of "Compte indépendant" (`2d50db4`); lazy
  serialization, enum mapping, profile switching (`f152cb9`).
- **Finary** — `TOTP_REQUIRED` reported correctly when the session is flagged
  (`5dca6da`); auto-sync transaction handling and BoursoSync filter (`aaf770d`);
  show all bank connections in SyncAllModal (`ab4faa6`).
- **Auth cookies** SameSite=Lax for Safari iOS compatibility (`3e9e140`); CORS
  PATCH method allowed (`e25f57d`); detailed login error reporting + CORS trace
  logs (`2488771`, `4af867a`); local-network CORS via origin patterns (`2832da3`,
  `2859392`).
- **Wallet** — remove ticker from on-chain wallet accounts to prevent double price
  conversion (`916a128`).
- **Pre-existing test regression.** `SetupServiceTest` stubbed `findByUsername`
  while production calls `findByUsernameWithMember` (`9297246`).
- **Frontend** — TypeScript build errors (`7cb7a3d`); AddTransactionModal reset &
  holdings query (`b5b1d41`); export read-only transaction wrapping (`22dd58b`).

### Security

- **CORS hardening** (see *Fixed*): closed the wildcard-with-credentials
  vulnerability.
- **Broken-object-level-authorization (BOLA) hardening** for admin impersonation
  and member-scoped debt lookups (`e2ad075`, `cb7cf49`).
- **JWT invalidation on password change** via the `token_version` claim.
- **`SecureCookieProvider`** centralizes the environment-aware `Secure` flag for
  cookies (`9570661`).
- **Setup wizard** rejects `*` for CORS allowed origins (`627533a`).
- **Rate-limit buckets** for MFA challenge and data export (`a95d752`, `d0933ac`).
- **Persistent token theft detection** (rotating opaque tokens) (`6c1ae82`).

### Documentation

- **README & SECURITY.md** rewritten; license clarified (`e9185c9`, `25825e5`,
  `42ab61b`).
- **CLAUDE.md** files overhauled with conventions and "don'ts"; English-only rule
  enforced (`fb599a5`, `527a0a4`, `5c4d29d`).
- **IEEE-style 1.0.0 release docs** — SRS, SDD, SDS, STP, and User Manual under
  `docs/release/1.0.0/`.
- **ADRs** — first-launch wizard, tr-auth sidecar slim image, loan amortization on
  the fly, Yahoo FX conversion, and ETF composition via Boursorama (superseding the
  issuer-holdings approach) (`cd651e6`, `52aa8e5`, `627533a`, `ca49378`).
- **Feature notes** across bank sync, loans, setup wizard, intraday chart, manual
  transactions, CORS & cookies, accounts overview, ISIN→ticker, data export, MFA,
  multi-account family, admin recovery, and security insight.
- **Deployment** — GHCR image paths corrected (repo is `picsou-finance`)
  (`f5994f0`).

### Database migrations

- **V23** — `bourso_session` (`b953e55`)
- **V24** — manual transaction fields (`a33d53c`)
- **V25** — `setup_state` (`627533a`)
- **V26** — `setup_audit` (`627533a`)
- **V27** — loan extra fields (`52aa8e5`)
- **V28** — MFA + persistent sessions (`98243c5`)
- **V29** — `app_user.token_version` (`056a900`)
- **V30** — `account.deleted_at` soft-delete + index (`9297246`)
- **V31** — price-snapshot cleanup gate
- **V32** — `goal.history_start` (anchors trajectory charts)

[1.0.0]: https://github.com/Zoeille/picsou-finance/releases/tag/v1.0.0
