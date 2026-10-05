# Picsou — User Manual

> Version: **1.0.0**
> Audience: end-users (households self-hosting Picsou)
> Date: 2026-04-26

Welcome to Picsou — your self-hosted personal-finance dashboard. This
manual covers what Picsou does, how to set it up, and how to use every
feature day to day.

---

## 1. What is Picsou?

Picsou is a private dashboard for your money. It aggregates:

- **Bank accounts** — synced from Enable Banking (PSD2/OAuth, 2000+ EU
  banks)
- **Brokerage** — Trade Republic positions
- **Crypto** — Binance balances and on-chain BTC / ETH / SOL wallets
- **Loans / debts** — with on-the-fly amortisation
- **Savings goals** — with manual or automatic contributions

It is single-instance and single-tenant: one Picsou install serves one
household. Your data never leaves your server.

---

## 2. Getting started

### 2.1 First launch

When you open Picsou for the first time, the **Setup wizard** appears.
It walks you through:

1. **Admin account.** Create the first user (this is the owner of the
   instance; that user can later invite others).
2. **Security.** Confirm the URL Picsou is served from (CORS), whether
   cookies should be marked `Secure` (yes for HTTPS, no for plain
   HTTP dev), and where to store the encryption key.
3. **Integrations** *(optional)*. Set up Enable Banking, Trade
   Republic, Binance, Finary import — you can skip and add them later.

When the wizard finishes, Picsou redirects you to the login screen.

### 2.2 Logging in

Enter your username and password. If you have enrolled 2FA, you are
asked for a 6-digit code from your authenticator app (or one of your
recovery codes). Tick **Remember Me** to stay logged in on this device
for 90 days.

### 2.3 Two-factor authentication

We strongly recommend enabling 2FA. Go to **Settings → Security**:

1. Click **Enable two-factor authentication**.
2. Scan the QR code with your authenticator app
   (1Password, Google Authenticator, Authy, …).
3. Enter the 6-digit code shown by the app.
4. **Save your recovery codes.** Store them in a password manager — they
   are the only way back if you lose your phone.

To disable 2FA later, you must re-enter your password and a current
TOTP code.

### 2.4 Managing your sessions

In **Settings → Security → Active sessions**, you can see every
"Remember Me" session that is still valid (device label, last seen IP,
last seen at). Revoke any session you do not recognise.

### 2.5 Changing your password

In **Settings → Profile → Change password**, enter your current and
new password. Picsou automatically re-issues your session cookies, so
you stay logged in on this device — every other device is signed out.

---

## 3. Navigating Picsou

Picsou's main views are reachable from the sidebar:

| Page | Purpose |
|------|---------|
| **Dashboard** | Net worth over time, P&L, contributions, recent transactions |
| **Accounts** | Every account, with detail page and holdings |
| **Transactions** | Searchable list of every transaction |
| **Holdings** | Stocks, ETFs, crypto positions with live prices |
| **Goals** | Savings goals with calendar donut and breakdown |
| **Debts / Loans** | Loans with amortisation chart and progress card |
| **Sync** | Bank, broker, exchange, wallet sync wizards |
| **Family** | Members, sharing, family-wide dashboard |
| **Settings** | Profile, security, theme, integrations |
| **Admin** *(admin only)* | Members management, integrations, security |

The sidebar collapses on mobile; tap the menu icon to expand it.
Picsou supports light and dark themes — toggle from
**Settings → Appearance**, the choice is remembered.

---

## 4. Dashboard

The dashboard shows your **net worth** over a chosen time range
(1 day, 1 week, 1 month, 3 months, 1 year, all-time, or a custom
window). Below the chart you find:

- **P&L** — gain/loss across the window
- **Contribution breakdown** — how much of the change came from new
  deposits vs. price movement
- **Holdings card** — top positions with live prices
- **Recent transactions**
- **Goals progress**

The time range is independent per chart — changing the dashboard
range does not reset the holdings or goal charts.

---

## 5. Accounts

### 5.1 Adding an account

Click **+ Add account** on the Accounts page. Choose:

- **Bank account** — opens the bank-sync wizard (see §6).
- **Brokerage** — manual or Trade Republic.
- **Crypto exchange** — Binance.
- **On-chain wallet** — paste a BTC xpub/zpub/descriptor, ETH or SOL
  address.
- **Manual account** — name, type (checking, savings, PEA, LEP, real
  estate, …), currency, starting balance.

### 5.2 Editing & deleting

From an account's detail page, click **Edit** or **Delete**.
Deleting removes its transactions and holdings — exporting your data
first is recommended.

### 5.3 Real estate

Real-estate accounts have a detail pane describing the property: type and
category, geocoded address, living area and land area, rooms, construction
year, floor and lift, garage and parking, garden/terrace/balcony, energy
rating, and the full acquisition cost (price plus agency, notary and works
fees).

From that description Picsou estimates the property's current value each
month from French open data (DGFiP transactions via Cerema, addresses via
IGN, re-indexed on the INSEE housing index) and updates the account
balance, so the gain/loss curve builds itself. Turn off "automatic
estimate" to freeze your own figure instead.

Estimation is unavailable in Alsace, Moselle and Mayotte, which keep the
*livre foncier* registry and are absent from the national transaction
dataset; those properties stay on manual valuation.

A property can be split between family members, and a mortgage can be
linked to it, giving gross and net property wealth.

---

## 6. Bank sync (Enable Banking)

1. Go to **Sync → Bank**.
2. Pick your country, then your bank.
3. Picsou opens the bank's authorization page.
4. Authorise; you are redirected back to Picsou.
5. Your accounts appear under **Accounts**, with balances.

Every linked bank is re-synced **daily at 08:00**. To trigger a
manual re-sync, click **Sync all** on the dashboard or the Sync page.
A failed sync shows a red badge with a **Retry** button.

> **Note.** Picsou 1.0.0 only ships with Enable Banking enabled.
> Powens / Budget Insight and BoursoBank adapters exist in the codebase
> but are experimental and disabled in this release.

---

## 7. Brokerage — Trade Republic

1. Go to **Sync → Trade Republic**.
2. Enter your phone number and PIN.
3. Confirm with the SMS code Trade Republic sends you.
4. Picsou pulls your portfolio (positions, cash balance, ISINs).

Holdings appear under **Holdings**; their live prices are refreshed
every 15 minutes from Yahoo Finance (with OpenFIGI converting ISIN
codes to Yahoo tickers).

---

## 8. Crypto

### 8.1 Exchanges

Add a Binance account from **Sync → Crypto → Binance**. Provide a
**read-only** API key and secret. Picsou stores them encrypted with
AES-256-GCM and uses them only to fetch balances.

### 8.2 On-chain wallets

Paste a BTC xpub / zpub / output descriptor, an ETH address, or a SOL
address. Picsou queries public RPCs (Cloudflare for ETH, the
Solana public RPC, electrum-style endpoints for BTC) and shows the
balance. No keys are ever required — addresses only.

---

## 9. Goals

A goal is a target amount tied to one or more accounts.

1. **Create a goal:** name, target amount, target date, contributing
   accounts, sharing level *(optional, for family members)*.
2. Picsou tracks contributions either:
   - **Automatically** — the increase in linked-account balance is
     credited to the goal.
   - **Manually** — record a one-off contribution from the goal page.
3. The **calendar donut** shows month-by-month progress; the
   **breakdown** lists each contribution.

Goals can be **shared** with family members at a `READ` or
`READ_WRITE` level.

---

## 10. Loans / debts

Add a debt with principal, APR, term in months, start date, and any
fees. Picsou computes the amortisation schedule on the fly:

- **Progress card** — paid-off vs. remaining
- **Monthly breakdown** — principal, interest, balance per month
- **Cost summary** — total interest, total fees, true cost
- **Amortisation chart** — over the life of the loan

You can mark a loan as paid off when the balance reaches zero, or
record an early repayment by editing the principal.

---

## 11. Family & sharing

Picsou supports a household — typically 1 to 5 people sharing one
instance.

### 11.1 Roles

- **Admin** — the user who completed the setup wizard, plus anyone the
  admin promotes. Admins can invite, deactivate, rename, reset
  passwords, and reset 2FA for other members.
- **Member** — manages their own data and views shared resources from
  others.

### 11.2 Inviting a member

In **Family → Members**, click **+ Invite**. Provide a username and a
temporary password. The new member logs in and is asked to change
their password on first sign-in.

### 11.3 Sharing resources

Each member's data is private by default. To share an account, a
holding, or a goal:

1. Open the resource.
2. Click **Sharing**.
3. Pick the members and a level — `READ` (view only) or
   `READ_WRITE` (contribute).

The **Family view** on the dashboard aggregates net worth across every
member you can see.

### 11.4 Admin impersonation

An admin can act on behalf of another member. Switch member from the
sidebar dropdown — every page reflects that member's data until you
switch back. Operations are still audited under the admin's identity.

---

## 12. Data export (GDPR)

You own your data. Go to **Settings → Security → Export my data**:

1. Re-enter your password (gate against session theft).
2. Pick which domains to include (everything by default).
3. Download the ZIP.

The archive contains one CSV per domain — accounts, transactions,
holdings, goals, debts, wallets, bank connections, sharing,
balance snapshots, profile. CSVs are UTF-8 with a header row.

---

## 13. Demo mode

If you want to evaluate Picsou without setting up a database, run the
frontend with `VITE_DEMO_MODE=true` (or click **Demo** on the login
screen of a built-in demo build). Demo mode serves frozen sample data
and never calls the backend.

---

## 14. Settings reference

| Section | Setting |
|---------|---------|
| **Profile** | Username, password, language *(EN/FR — best-effort)* |
| **Security** | 2FA enrol/disable, recovery codes, active sessions, data export |
| **Appearance** | Light / dark theme, sidebar density |
| **Integrations** *(admin)* | Enable Banking credentials, Trade Republic URL, exchange keys |
| **Family** | Sharing rules, roles |

---

## 15. Mobile

Picsou's frontend is fully responsive. Every page works on a phone:

- The sidebar collapses to a slide-in drawer.
- Charts adapt to portrait orientation.
- Modals are full-screen on small viewports.

Picsou has been tested on Safari iOS, Chrome Android, and Firefox
Android. If you find a layout issue, open a bug — mobile coverage is
a 1.0.0 commitment.

---

## 16. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `503 setup_required` after fresh install | Wizard not completed | Open `/setup` |
| `401 mfa_required` after correct password | 2FA enabled | Enter the TOTP code |
| Cookies not sent on the API | `SECURE_COOKIES=true` on plain HTTP | Set `SECURE_COOKIES=false` for HTTP dev |
| Bank connection stuck `FAILED` | Bank refused the OAuth code | Click **Retry** on the requisition |
| Live prices stale | 15-minute cache | Wait or restart the backend |
| Login locked out | 5 attempts / 15 min | Wait 15 minutes |
| Lost authenticator phone | — | Use a recovery code, then re-enrol 2FA |
| Locked out entirely | — | Operator: reset DB or use admin reset (CLI) |

For deeper issues, the operator can inspect:

- Backend logs (Spring Boot stdout)
- `setup_audit` table (what the wizard did)
- `failed_login_attempts` (rate-limit state)

---

## 17. Privacy & data

- **No telemetry.** Picsou never phones home.
- **Encryption at rest.** TOTP secrets, exchange keys, bank session
  tokens, and broker refresh tokens are encrypted with AES-256-GCM.
- **Backups are your responsibility.** Snapshot your PostgreSQL volume
  regularly.
- **Right to delete.** Deleting a member soft-deletes their domain
  data; a full hard-delete is performed by dropping the row in the
  database (operator-level).

---

## 18. Where to get help

- This manual: [`docs/release/1.0.0/USER_MANUAL.md`](./USER_MANUAL.md)
- Technical reference for operators:
  - [SRS](./SRS.md) — what Picsou does
  - [SDD](./SDD.md) — how it is architected
  - [SDS](./SDS.md) — REST contract and components
  - [STP](./STP.md) — test plan
- Issue tracker: the project's GitHub repository
