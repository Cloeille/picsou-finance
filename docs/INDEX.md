# Technical Documentation Index

> Picsou is a self-hosted personal finance dashboard.
> It aggregates bank accounts, brokerage, crypto, and on-chain assets, and tracks net worth over time.
>
> This file is the entry point for technical documentation.
> Read it first to know where to find information.

## Coding rules

- [CODING_RULES.md](./CODING_RULES.md) -- Non-negotiable charter (convention integrity, theme, layers). Read before a large refactor or review.

## Architecture

- [ARCHITECTURE.md](./ARCHITECTURE.md) -- Overview, modules, data flows

## Release deliverables

- [release/1.0.0/](./release/1.0.0/README.md) -- IEEE-style docs for 1.0.0:
  [SRS](./release/1.0.0/SRS.md), [SDD](./release/1.0.0/SDD.md),
  [SDS](./release/1.0.0/SDS.md), [STP](./release/1.0.0/STP.md),
  [User Manual](./release/1.0.0/USER_MANUAL.md)

## Technical decisions (ADR)

| Date | Decision | Status |
|------|----------|--------|
| 2026-01-01 | [Ports and adapters architecture](./decisions/2026-01-01-ports-and-adapters.md) | Active |
| 2026-01-01 | [Single user with JWT in HttpOnly cookies](./decisions/2026-01-01-single-user-jwt-cookies.md) | ⚠️ Superseded |
| 2026-01-01 | [Flyway owns the schema](./decisions/2026-01-01-flyway-schema-ownership.md) | Active |
| 2026-03-01 | [Dual bank provider](./decisions/2026-03-01-dual-bank-providers.md) | ⚠️ Revised — Powens experimental, disabled in 1.0.0 |
| 2026-03-01 | [AES-256-GCM encryption for crypto secrets](./decisions/2026-03-01-aes-gcm-crypto-secrets.md) | Active |
| 2026-04-05 | [Component-local state for UI filters](./decisions/2026-04-05-component-local-state-for-ui-filters.md) | Active |
| 2026-04-08 | [Mandatory encryption key at startup](./decisions/2026-04-08-mandatory-encryption-key.md) | Active |
| 2026-04-08 | [CSS relative color syntax for theme-adaptive brightness](./decisions/2026-04-08-css-relative-color-syntax.md) | Active |
| 2026-04-23 | [Two-layer bootstrap for first-launch Setup Wizard](./decisions/2026-04-23-first-launch-wizard.md) | Active |
| 2026-04-25 | [tr-auth as isolated sidecar with Chromium-only image](./decisions/2026-04-25-tr-auth-sidecar-slim-image.md) | Active |
| 2026-04-25 | [Admin page reuses SetupService writers behind a role-gated controller](./decisions/2026-04-25-admin-page-reuses-setup-writers.md) | Active |
| 2026-04-26 | [Compute loan amortization schedules on the fly](./decisions/2026-04-26-loan-amortization-on-the-fly.md) | Active |
| 2026-04-26 | [TOTP 2FA and persistent (Remember-Me) sessions](./decisions/2026-04-26-totp-2fa-and-persistent-sessions.md) | Active |
| 2026-05-19 | [FX conversion inside the Yahoo price provider](./decisions/2026-05-19-yahoo-fx-conversion.md) | Active |
| 2026-05-31 | [ETF composition from issuer holdings files (no auth)](./decisions/2026-05-31-etf-composition-issuer-holdings.md) | ⚠️ Superseded |
| 2026-06-01 | [ETF composition via Boursorama (single source)](./decisions/2026-06-01-etf-composition-via-boursorama.md) | Active |
| 2026-06-02 | [Budget cycle, categorization engine, and transfer kind](./decisions/2026-06-02-budget-cycle-and-categorization.md) | Active |
| 2026-06-05 | [Access-key auth + embedded MCP server](./decisions/2026-06-05-access-key-auth-and-embedded-mcp.md) | Active |
| 2026-06-09 | [Offline merchant knowledge base and nested-route Budget IA](./decisions/2026-06-09-merchant-kb-and-budget-ia.md) | Active |
| 2026-06-26 | [Optional AI transaction categorization via TransactionCategorizerPort](./decisions/2026-06-26-ai-transaction-categorization.md) | Active |
| 2026-06-26 | [AI provider runtime admin config (DB-only, no restart)](./decisions/2026-06-26-ai-provider-runtime-admin-config.md) | Active |
| 2026-06-28 | [Reconstruct Revolut pockets from PSD2 internal-transfer rows](./decisions/2026-06-28-revolut-pockets-reconstruction.md) | ⚠️ Superseded |
| 2026-06-28 | [Savings livrets: classify accounts and project interest (not written to balance)](./decisions/2026-06-28-savings-livrets-interest-projection.md) | Active |
| 2026-07-03 | [OAuth2 Authorization Server for the native iOS app](./decisions/2026-07-03-oauth2-authorization-server-for-native-app.md) | Active |
| 2026-07-06 | [Drop oauth2:token scope from MCP allowlist](./decisions/2026-07-06-drop-oauth2-token-scope.md) | Active |
| 2026-07-11 | [Realized P&L: average-cost, computed on the fly](./decisions/2026-07-11-realized-pnl-average-cost-on-the-fly.md) | Active |
| 2026-07-12 | [Remote-MCP OAuth authorization for third-party clients (claude.ai)](./decisions/2026-07-12-remote-mcp-oauth-authorization.md) | Active |
| 2026-07-12 | [UI controls follow the shadcn theme radius, not a pill shape](./decisions/2026-07-12-ui-controls-follow-shadcn-theme-radius.md) | Active |
| 2026-07-17 | [EVM multichain wallets — one address, many chains](./decisions/2026-07-17-evm-multichain-wallets.md) | Active |
| 2026-07-19 | [Caddy as an opt-in TLS terminator for the Docker stack](./decisions/2026-07-19-caddy-opt-in-tls-profile.md) | Active |
| 2026-07-21 | [Bourse Direct isolated browser sidecar and atomic complete snapshots](./decisions/2026-07-21-bourse-direct-isolated-atomic-sync.md) | Active |
| 2026-07-26 | [Fortuneo isolated browser sidecar and complete atomic history sync](./decisions/2026-07-26-fortuneo-isolated-atomic-history-sync.md) | Active |
| 2026-07-19 | [Interactive Brokers via the Flex Web Service (read-only, EOD)](./decisions/2026-07-19-ibkr-flex-web-service.md) | Active |
| 2026-08-11 | [BoursoBank through a browserless sidecar, app-push only](./decisions/2026-08-11-boursobank-httpx-sidecar.md) | Active |
| 2026-08-01 | [Value assets from the last known price rather than not at all](./decisions/2026-08-01-last-known-price-fallback.md) | Active |
| 2026-08-05 | [DEGIRO: session-only, no stored TOTP secret](./decisions/2026-08-05-degiro-session-only-no-stored-totp.md) | Active |
| 2026-08-09 | [Amundi Épargne Salariale through an isolated browser sidecar](./decisions/2026-08-09-amundi-epargne-salariale-sidecar.md) | Active |
| 2026-08-01 | [Estimate property value from French open data](./decisions/2026-08-01-open-data-property-valuation.md) | Active |
| 2026-08-01 | [Per-member ownership shares on properties and loans](./decisions/2026-08-01-account-ownership-shares.md) | Active |
| 2026-08-10 | [Verify an ISIN's ticker against Yahoo instead of predicting it](./decisions/2026-08-10-yahoo-verified-isin-tickers.md) | Active |
| 2026-08-11 | [Deleting an account removes the connection behind it](./decisions/2026-08-11-account-deletion-removes-its-connection.md) | Active |
| 2026-08-13 | [A country breakdown mixes domicile and exposure, and says so](./decisions/2026-08-13-equity-domicile-vs-etf-exposure.md) | Active |
| 2026-08-13 | [Persist security profiles and warm them on a schedule](./decisions/2026-08-13-persisted-security-profiles.md) | Active |
| 2026-08-15 | [Key security lookups on the ISIN, and read fund facts from justETF](./decisions/2026-08-15-isin-keyed-lookups-and-justetf.md) | Active |
| 2026-08-18 | [Client-supplied labels for the xlsx account export](./decisions/2026-08-18-client-supplied-labels-for-xlsx-export.md) | Active |
| 2026-09-17 | [Defer the MCP protocol upgrade until the Spring Boot 4 migration](./decisions/2026-09-17-defer-mcp-protocol-upgrade.md) | Active |

## Feature notes

| Feature | Last updated | Note |
|---------|-------------|------|
| MCP server + scoped access-keys | 2026-06-26 | [mcp-server.md](./features/mcp-server.md) |
| Budget + OAuth2 tools in MCP | 2026-07-06 | [mcp-budget-oauth2.md](./features/mcp-budget-oauth2.md) |
| Remote-MCP OAuth (claude.ai connector) | 2026-07-12 | [mcp-oauth-remote.md](./features/mcp-oauth-remote.md) |
| Internationalization (FR/EN/DE/ES) | 2026-09-02 | [i18n.md](./features/i18n.md) |
| Frontend utilities (lib/utils.ts) | 2026-08-07 | [frontend-utils.md](./features/frontend-utils.md) |
| Demo mode | 2026-08-13 | [demo-mode.md](./features/demo-mode.md) |
| Theme (dark / light / system) + theme-adaptive rendering | 2026-06-02 | [theme-persistence.md](./features/theme-persistence.md) |
| Dashboard — Time range isolation | 2026-04-13 | [dashboard-time-range-isolation.md](./features/dashboard-time-range-isolation.md) |
| Bank sync | 2026-08-11 | [bank-sync.md](./features/bank-sync.md) |
| Budget & Cashflow | 2026-06-28 | [budget.md](./features/budget.md) |
| Budget categorization rules (word-picker authoring UX) | 2026-06-28 | [budget-rules.md](./features/budget-rules.md) |
| Optional AI transaction categorization | 2026-06-26 | [ai-categorization.md](./features/ai-categorization.md) |
| Dashboard — Liabilities separated from performance | 2026-07-12 | [dashboard-liabilities-separation.md](./features/dashboard-liabilities-separation.md) |
| Dashboard — Liabilities card (repayment progress) | 2026-06-28 | [dashboard-liabilities-card.md](./features/dashboard-liabilities-card.md) |
| Trade Republic | 2026-07-07 | [trade-republic.md](./features/trade-republic.md) |
| Bourse Direct | 2026-07-21 | [bourse-direct.md](./features/bourse-direct.md) |
| Fortuneo — PEA/CTO positions, cash accounts, transactions | 2026-09-03 | [fortuneo.md](./features/fortuneo.md) |
| Interactive Brokers (IBKR) sync | 2026-07-19 | [ibkr-sync.md](./features/ibkr-sync.md) |
| DEGIRO sync | 2026-08-05 | [degiro-sync.md](./features/degiro-sync.md) |
| Amundi Épargne Salariale sync | 2026-08-09 | [amundi-epargne-salariale.md](./features/amundi-epargne-salariale.md) |
| Trade Republic — Holdings deduplication | 2026-05-18 | [trade-republic-holding-deduplication.md](./features/trade-republic-holding-deduplication.md) |
| ISIN → Ticker conversion | 2026-08-10 | [ISIN_TO_TICKER_CONVERSION.md](./features/ISIN_TO_TICKER_CONVERSION.md) |
| Encryption at rest | 2026-04-08 | [encryption-at-rest.md](./features/encryption-at-rest.md) |
| Crypto tracking | 2026-08-11 | [crypto-tracking.md](./features/crypto-tracking.md) |
| Wealth pyramid (Analysis) | 2026-08-16 | [wealth-pyramid.md](./features/wealth-pyramid.md) |
| Portfolio diversification (sector + geography) | 2026-08-15 | [portfolio-diversification.md](./features/portfolio-diversification.md) |
| Recurring investment plans + wealth projection | 2026-08-19 | [goal-recurring-investment.md](./features/goal-recurring-investment.md) |
| Member profile (age, TMI, income) | 2026-08-19 | [member-profile.md](./features/member-profile.md) |
| Savings goals | 2026-06-02 | [goals.md](./features/goals.md) |
| Goals — Grid view (donuts) | 2026-06-02 | [goal-calendar-donut.md](./features/goal-calendar-donut.md) |
| Price service | 2026-08-07 | [price-service.md](./features/price-service.md) |
| Live prices (holdings) | 2026-05-19 | [live-prices-holdings.md](./features/live-prices-holdings.md) |
| Security Insight (asset type + ETF composition) | 2026-06-02 | [security-insight.md](./features/security-insight.md) |
| Finary import + auto-sync | 2026-04-21 | [finary-import.md](./features/finary-import.md) |
| Manual transactions + holdings derivation | 2026-09-02 | [manual-transactions.md](./features/manual-transactions.md) |
| Transactions — global view (all accounts) | 2026-06-28 | [transactions-global-view.md](./features/transactions-global-view.md) |
| CSV transaction import (investment accounts) | 2026-07-11 | [csv-transaction-import.md](./features/csv-transaction-import.md) |
| Realized P&L on closed positions | 2026-07-11 | [realized-pnl.md](./features/realized-pnl.md) |
| BoursoBank sync | 2026-08-13 | [bourso-bank.md](./features/bourso-bank.md) |
| Accounts overview (PnL chart + summary card + filters + card anatomy + sortable positions) | 2026-08-19 | [accounts-overview.md](./features/accounts-overview.md) |
| Savings livrets (classification + projected interest) | 2026-06-28 | [savings-livrets.md](./features/savings-livrets.md) |
| Logos on account cards (catalog-resolved, bundled, wallet picker, property kind) | 2026-08-13 | [bank-logos.md](./features/bank-logos.md) |
| Add Account modal (unified sync + manual) | 2026-08-13 | [add-account-modal.md](./features/add-account-modal.md) |
| Account visibility (hidden accounts, `/sync` Comptes tab) | 2026-07-15 | [account-visibility.md](./features/account-visibility.md) |
| Docker deployment | 2026-07-19 | [docker-deployment.md](./features/docker-deployment.md) |
| Navigation (sidebar + mobile bottom nav) | 2026-07-12 | [sidebar-navigation.md](./features/sidebar-navigation.md) |
| UI control shape (shadcn theme radius) | 2026-08-10 | [ui-control-shape-system.md](./features/ui-control-shape-system.md) |
| Privacy mode (hiding amounts for demos) | 2026-08-16 | [privacy-mode.md](./features/privacy-mode.md) |
| Multi-account family system | 2026-07-07 | [multi-account-family.md](./features/multi-account-family.md) |
| CORS & cookie security | 2026-06-02 | [security-cors-cookies.md](./features/security-cors-cookies.md) |
| 24H Intraday net worth chart | 2026-04-18 | [intraday-chart.md](./features/intraday-chart.md) |
| First-launch Setup Wizard | 2026-07-19 | [setup-wizard.md](./features/setup-wizard.md) |
| Admin page (instance settings) | 2026-05-29 | [admin-page.md](./features/admin-page.md) |
| Admin recovery (lost-admin console reset) | 2026-05-29 | [admin-recovery.md](./features/admin-recovery.md) |
| Frontend error display (`extractErrorMessage`) | 2026-05-31 | [frontend-error-display.md](./features/frontend-error-display.md) |
| Loan accounts (LOAN type, amortization view) | 2026-04-26 | [loans.md](./features/loans.md) |
| Real estate valuation | 2026-08-11 | [real-estate-valuation.md](./features/real-estate-valuation.md) |
| Ownership shares | 2026-08-10 | [account-ownership-shares.md](./features/account-ownership-shares.md) |
| 2FA (TOTP) and Remember Me | 2026-06-01 | [mfa-and-remember-me.md](./features/mfa-and-remember-me.md) |
| Login timing equalization (username-enumeration defense, GHSA-ww5m-pxgq-8qq6) | 2026-06-27 | [login-timing-attack.md](./features/login-timing-attack.md) |
| GDPR data export (JSON + CSV) | 2026-04-26 | [data-export.md](./features/data-export.md) |
| Account spreadsheet export (xlsx) | 2026-08-18 | [account-xlsx-export.md](./features/account-xlsx-export.md) |
| Revolut pockets (reconstruction from PSD2 internal-transfer rows) (removed, 2026-07-14) | 2026-06-28 | [revolut-pockets.md](./features/revolut-pockets.md) |
| Revolut sidecar (assisted-enrolment login connector) | 2026-07-08 | [revolut-sidecar.md](./features/revolut-sidecar.md) |

## Lessons

| Lesson | Recorded | Note |
|--------|----------|------|
| Thread-bound context lost across an async thread hop (Spring Security × Spring AI MCP) | 2026-06-26 | [thread-local-context-across-async-hop.md](./lessons/thread-local-context-across-async-hop.md) |
| Test a constant-time fix by counting crypto ops, not wall-clock time | 2026-06-27 | [timing-attack-test-by-op-count.md](./lessons/timing-attack-test-by-op-count.md) |
| Demo-mode data resilience — truthy `{}` objects and stale TanStack Query references | 2026-06-28 | [demo-mode-data-resilience.md](./lessons/demo-mode-data-resilience.md) |
| The savings-livrets integration seam — defects survive where two green streams meet | 2026-06-28 | [savings-livrets-integration-seam.md](./lessons/savings-livrets-integration-seam.md) |
| Stop protocol surfaces false brief hypotheses before cargo-cult code | 2026-07-06 | [stop-protocol-discovers-false-hypotheses.md](./lessons/stop-protocol-discovers-false-hypotheses.md) |
| Demo data must include all required interface properties | 2026-07-06 | [demo-data-interface-completeness.md](./lessons/demo-data-interface-completeness.md) |
| A child row whose parent is filtered out of a list needs an explicit rendering fallback | 2026-07-14 | [orphaned-child-needs-a-rendering-fallback.md](./lessons/orphaned-child-needs-a-rendering-fallback.md) |
| A final whole-branch review catches call sites no single task-scoped review can | 2026-07-14 | [final-whole-branch-review-catches-what-task-scoped-review-cannot.md](./lessons/final-whole-branch-review-catches-what-task-scoped-review-cannot.md) |

## Conventions

| Topic | File |
|-------|------|
| REST API | [api-rest.md](./conventions/api-rest.md) |
| Error handling | [error-handling.md](./conventions/error-handling.md) |
| Testing | [testing.md](./conventions/testing.md) |
| Frontend | [frontend.md](./conventions/frontend.md) |
| Database | [database.md](./conventions/database.md) |

## Templates

- [FEATURE.md](./templates/FEATURE.md) -- Feature note template
- [DECISION.md](./templates/DECISION.md) -- Architectural decision record (ADR) template
