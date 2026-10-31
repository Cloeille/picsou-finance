# Lesson — the savings-livrets integration seam (and what the smoke proved)

**Recorded:** 2026-06-28
**Context:** Building the savings-livrets feature (classification + projected interest) across
three parallel work streams — backend core, backend API, frontend.

## What broke at the seam

Each stream was internally consistent and green on its own tests, yet the feature was unusable
end-to-end. A bank-synced livret is created as `AccountType.CHECKING` (Enable Banking never
classifies). The suggestions banner surfaced those CHECKING accounts and linked to the account
detail page — but the detail page only rendered the config section for `type === 'SAVINGS' || 'LEP'`.
A suggested Livret A (still CHECKING) therefore led to a dead end: nothing to configure.

No unit test could catch this — it lived purely in the boundary between "what the API surfaces"
and "what the UI gates on". Only an integration review across streams found it.

**Fixes:**
- Backend: setting a savings config now reclassifies `account.type` to `SAVINGS` (or `LEP`) — the
  config *is* the user's ratified classification, so it takes effect and the book groups as savings
  everywhere.
- Frontend: the detail-page gate also opens when the account already has a config or is a detector
  suggestion, so the first-time configuration is reachable.

## What the live smoke proved (demo mode, Playwright)

Drove the real served frontend in demo mode (`VITE_DEMO_MODE=true`, mocked API):
- Suggestions banner "N livrets à configurer" renders and links to the detail page.
- Config section: product dropdown, regulated-NET lock + "net d'impôt" message, rate auto-fills on
  product change (LEP → 3.50), optional plafond, save persists, delete present.
- Projection card renders YTD + full-year estimate + Dec 31 capitalization + the honest-limit
  disclaimer ("Estimation uniquement. Le crédit annuel de la banque fait foi.").
- **Net worth identical before/after configuring** — the no-double-count guardrail holds at the UI
  level, complementing the structural backend guarantee (`@Transactional(readOnly=true)`, no balance
  write) and the 662 backend unit tests.

**Smoke limit:** demo mode mocks the backend, so the projection numbers come from demo handlers,
not the real quinzaine engine (that engine is covered by hand-computed unit tests). The smoke
validates the integration/flow, not the arithmetic.

## Follow-up found during the smoke (resolved)

The suggestions banner navigates to the account, but the config form defaulted the product to the
first option (Livret A) instead of pre-selecting the *suggested* product (e.g. LEP) and its default
rate. Fixed by threading `suggestedProduct` / `defaultAnnualRate` from the suggestion into
`SavingsConfigSection`'s initial state when the account has no saved config.

## Takeaway

When work is partitioned across parallel streams, the producer is never the validator of the seam.
Budget an explicit integration gate after the streams land: walk one real end-to-end path. The
defects that survive unit tests live exactly where two green streams meet. See also
[[demo-mode-data-resilience]].
