# Feature: Add Account Modal

> Last updated: 2026-08-13

## Context

Creating a new account or connecting a sync provider required two separate entry points: a simple `AccountForm` dialog for manual accounts, and the `/sync` page for all provider connections. This unified both flows into a single modal accessible from the Accounts page "Add account" button.

## How it works

The `AddAccountModal` is a state-machine dialog with two levels:

1. **Selector screen** — 6 buttons in a grid (Banks, Exchanges, Wallets, Trade Republic, Finary, Manual). Each sync button enters its wizard; the Manual button opens the existing `AccountForm` in a separate dialog.
2. **Wizard screens** — Each sync type has its own compact wizard with a back button. Each wizard manages its own loading and error state inline.

### Key files

- `frontend/src/components/shared/AddAccountModal.tsx` — main component (contains all sub-wizards)
- `frontend/src/pages/accounts/AccountsPage.tsx` — wires `AddAccountModal` for create, keeps `AccountForm` for edit
- `frontend/src/features/sync/hooks.ts` — all sync mutation hooks reused by the wizards
- `frontend/src/components/ui/input-otp.tsx` — shadcn InputOTP component (installed for TR PIN and verification code)

### Flow

```
AccountsPage → "Add account" button
  └─ AddAccountModal (step = "selector")
       ├─ Banks → BankWizard
       │    └─ search institutions → select → initiate OAuth → redirect
       ├─ Exchanges → ExchangeWizard
       │    └─ pick type → API key + secret → add → success
       ├─ Wallets → WalletWizard
       │    └─ pick chain → address + label → add → success
       ├─ Trade Republic → TradeRepublicWizard
       │    └─ phone + PIN (InputOTP 4-digit) → verification code (InputOTP 4-digit) → success
       ├─ Finary → FinaryWizard (3-step)
       │    └─ login/upload → account mapping → results
       └─ Manual → AccountForm (separate dialog)
```

### Error handling

Each wizard owns its error state as a local `useState<string | null>`. On mutation failure, the backend `detail` field is extracted (falling back to `err.message`, then a translated i18n key). Errors are shown in a dismissible red banner inside the wizard, and cleared on the next attempt.

The Trade Republic wizard follows the same state rules as the dedicated Sync
page: initiation errors keep the phone/PIN form visible and clear any stale
process id; verification-code errors keep the code form visible, clear the typed
code, and retain the current process id for retry.

Bank institution search is also handled inline. The `/sync/institutions` GET is
marked with `skipGlobalErrorRedirect` so connector failures such as Enable
Banking misconfiguration stay in the modal instead of sending the whole app to
`/error/500?code=502`.

**Previously**, a global `isPending` overlay in the parent replaced all wizard content with a spinner during mutations. This caused a React unmounting bug: when the mutation completed with an error, `setError(...)` was called on the unmounted (old) wizard instance → no-op → error silently swallowed. That mechanism was removed. Each wizard's button is now disabled via `mutation.isPending` instead.

### Currency field (validation & display resilience)

The manual `AccountForm` currency field is a **curated `<select>`** (not free text), sourced from
`SUPPORTED_CURRENCIES` in `frontend/src/lib/constants.ts`. Option labels are rendered live with
`Intl.DisplayNames` (locale-aware, e.g. "EUR — Euro"), so the list stays codes-only. When editing an
account whose code isn't in the curated list (a legacy or previously-invalid value), that code is
prepended as an extra option so opening the form never silently rewrites the currency.

Validation is layered:

- **Frontend input** — the dropdown makes an invalid code unselectable; zod requires a non-empty string.
- **Backend** — `AccountRequest.currency` carries `@ValidCurrency` (`com.picsou.validation`), which checks
  the code against `java.util.Currency.getInstance(...)`. An unknown code now returns **400** instead of
  persisting. Applies to both create and update (shared DTO).
- **Display** — `formatCurrency` (`frontend/src/lib/utils.ts`) wraps `Intl.NumberFormat` in try/catch and
  degrades to `"<amount> <code>"` on an unknown code, so a bad value can never blank the app again.

This closed issue #9: a free-text code like `AMAT` used to throw a `RangeError` from
`Intl.NumberFormat`, bubble to the root `ErrorBoundary`, and make the account unreachable/undeletable.

### Bank field (manual form)

The manual `AccountForm`'s provider field is a `BankPicker`: free text that also searches the
institution catalog as you type. Picking a bank sets the field to the institution's name and
sends its catalog id alongside, which is what lets the backend resolve a logo for an account no
connector syncs — see [bank-logos.md](./bank-logos.md#the-bank-a-manual-account-names). A loan's
lender field is the same control on the same form value: a loan's provider *is* its bank.

It never blocks on the search. An unconfigured or failing catalog simply shows no suggestions,
and the typed name is saved as before.

### Account type labels

`ACCOUNT_TYPES` and `accountTypeLabelKey()` (`frontend/src/lib/constants.ts`) are the only
list of account types and the only way to get one's translation key. Five call sites used to
carry their own copy — the two type dropdowns (this modal's manual form and its Finary mapping
step), `AccountTypeBadge`, `HoldingsCard`, `PortfolioView` and `HoldingDetailModal` — and three
of them derived the key from the type name (`type.toLowerCase()`, with special cases bolted on
for `COMPTE_TITRES` and `REAL_ESTATE`).

That derivation only held while every key was the lowercased value. Adding `LIVRET_A` broke it
immediately: the badge beside the account name rendered the literal string
`accountTypes.livret_a`. `constants.test.ts` now pins every type to a key that exists in all
four locales, and the partial maps are gone.

### SyncPage integration

`SyncPage` reads `?tab=` from the URL query params to set the initial tab. This was added for forward-compatibility; the modal does not redirect there — all wizards are inline.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Single file with sub-components | All wizards share the same imports, hooks, and patterns | Separate file per wizard |
| `InputOTP` for TR PIN and verification code | shadcn component, consistent UX for digit-only inputs | Regular password input |
| `AccountForm` reused for manual | Already existed, handles validation and color picking | Inline form in the modal |
| Per-wizard error state (no global overlay) | Global `isPending` unmounts the wizard, losing error state (React no-op on unmounted setter) | Global `onPending` callback |
| `mutation.isPending` on buttons for loading | Keeps the wizard mounted throughout; spinner is inline on the submit button | Parent-level overlay |

## Account types

`ASSURANCE_VIE` and `SCPI` joined the picker on 2026-08-13 for the investment pyramid
(see [wealth-pyramid.md](./wealth-pyramid.md)). Both need an entry in `ACCOUNT_TYPES`,
in `AccountForm`'s zod enum, in `AccountsPage`'s `ASSET_FILTER_MAP`/`TYPE_TO_GROUP`, and
an `accountTypes.*` label in all four locales — `src/lib/constants.test.ts` fails if
`ACCOUNT_TYPES` misses one, and the locale parity test fails if a label does.

## Gotchas / Pitfalls

- **Never replace wizard content with a parent-level overlay during mutations.** When the wizard unmounts and remounts after an error, any `setError(...)` called on the old instance is silently ignored by React 18. Each wizard must stay mounted while its mutation is in flight.
- **`input-otp` package must be in root `node_modules`** — Vite resolves from the project root. If installed only in `frontend/`, it fails at runtime with "error loading dynamically imported module".
- **Trade Republic PIN and verification code are 4 digits** — `maxLength` on `InputOTP` controls this.
- **Trade Republic initiation errors stay on credentials** — never move the
  wizard to the verification-code step unless `/tr/auth/initiate` returned a
  process id. TAN completion errors are the only errors that keep the code step
  visible for retry.
- **Bank OAuth is fire-and-forget** — `window.location.href = data.authLink` redirects the entire page. The modal does not reach a success state; the redirect carries the user away. Error handling (e.g. `REDIRECT_URI_NOT_ALLOWED`) surfaces as a banner before the redirect happens.
- **`ENABLEBANKING_REDIRECT_URI` must match the EB portal** — see [bank-sync.md](./bank-sync.md).
- **Finary wizard is the only multi-step wizard** (3 steps: login/upload → mapping → results). All others are single-step.
- **Edit flow is unchanged** — `AccountsPage` uses `AccountForm` for editing. The modal is create-only.

## Tests

- `frontend/src/lib/utils.test.ts` — `formatCurrency` regression case: an invalid code does not throw
  and the raw code appears in the output (issue #9).
- `frontend/src/components/shared/AddAccountModal.test.tsx` — Trade Republic wizard regression cases for initiation failure staying on credentials and TAN completion failure staying on the code step.
- `backend/src/test/java/com/picsou/validation/CurrencyValidatorTest.java` — accepts valid ISO 4217
  codes, rejects unknown ones, leaves null/blank to `@NotBlank`.

## Links

- i18n keys: `addAccount.*`, sync keys reused from `sync.*` namespace in `en.json` / `fr.json`
- Related: [Finary import](./finary-import.md), [Trade Republic](./trade-republic.md), [Bank sync](./bank-sync.md), [Crypto tracking](./crypto-tracking.md)
