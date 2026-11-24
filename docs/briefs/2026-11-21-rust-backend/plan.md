# Rust backend program plan

Decision: [ADR](../../decisions/2026-11-21-rust-backend.md). Standing orders: [standing-orders.md](standing-orders.md).

## Done predicate

All 336 Java endpoints (`backend-rs/scripts/java_endpoints.tsv`) are served by `backend-rs`, the route-coverage test passes with `PICSOU_REQUIRE_FULL_COVERAGE=1`, every contract scenario passes against the Rust binary, `cargo clippy -D warnings` and `cargo test` are green, the frontend completes the critical flows against it, and the Docker image and desktop app run it without a JRE.

## Module ownership

Each track owns its directories exclusively. The foundation creates every module below as an empty router so later tracks only add files inside their own directories.

| Track | Owns (under `backend-rs/src/`) | Java controllers |
|---|---|---|
| F foundation | `config`, `db`, `error`, `auth`, `crypto`, `http`, `clock`, `testing`, `routes`, `actuator`, `setup_auth/` | Setup, Auth, Mfa, Session |
| H contract | `backend-rs/contract/` | none (harness) |
| T1 identity | `identity/`, `admin/` | SignInMethods, AccessKey, ConnectedApps, OAuthConsent, DynamicClientRegistration, ProtectedResourceMetadata, MeProfile, AdminMfa, AdminIdentity, Admin, Telemetry, SSO chain, OAuth2 authorization server |
| L ledger | `ledger/` | none: account, holding, transaction, snapshot, FX, ownership and ingestion model shared by every later track |
| T2 accounts | `accounts/`, `family/` | Account, Dashboard, History, Analysis, InstitutionLogo, InstrumentLogo, Security, Family, FamilyView |
| T3 budget | `budget/` | Budget, BudgetSettings, Cashflow, Allocation, Spending, Category, CategorizationRule, TransactionCategorization, Recurring, Merchant |
| T4 wealth | `wealth/`, `market/` | Goal, Savings, PeerLoan, RealEstate, Geocoding, ScpiFund, Dividend, Price, plus price, FX, metals, OpenFIGI, JustETF, Nasdaq, INSEE and logo providers |
| T5 sync | `sync/` | Sync (Enable Banking), CryptoExchange, Wallet, Ibkr, Simplefin, Wise, Louve, FinaryApiSync |
| T6 connectors | `connectors/` | the 17 sidecar controllers and the generic sidecar client |
| T7 transfer | `transfer/` | TransactionImport, ActualBudgetImport, HomeBankImport, FinaryImport, AccountExport, MeExport |
| T8 automation | `jobs/`, `mcp/`, `ai/` | schedulers, catch-up, cleanups, startup sync, MCP server and tools, AI categorization |
| D delivery | `desktop/`, `docker/`, `Makefile`, CI | none |

## Waves

1. F and H in parallel.
2. T1 and L in parallel, on the merged wave 1.
3. T2, T3, T4, T5, T6, T7 in parallel, on the merged wave 2.
4. T8 and D, then end-to-end verification and review.
