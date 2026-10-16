# Design: Budget tab redesign for a standalone iOS app

> Date: 2026-07-22
> Status: ✅ Implemented and shipped (branch `iOS-app`)

## Context

Chloé redirected the iOS effort: it is not a companion app to the web frontend — some users will
only ever use iOS, never open the web app — so every feature area has to stand on its own. The
Budget tab was the weakest: a read-only cashflow card + envelope list, zero interaction beyond
pull-to-refresh, and an empty state that literally said "create it on the web." Worse, **no
transaction anywhere in the app was categorizable** — not even in the existing Accounts transaction
detail sheet, which showed the category as static text.

The backend already exposed everything a native categorization/budget experience needs
(`BudgetController`, `BudgetSettingsController`, `SpendingController`, `RecurringController`,
`TransactionCategorizationController`, `CategoryController`) — this was a 100% client-side gap.

## Process

Three agent personas worked in sequence, each grounded in the real codebase (not a blank-slate
brainstorm): a **product lead** scoped a prioritized, native-first must-have list; a **product
designer** turned it into an information architecture and screen-by-screen spec, reusing existing
patterns (`GoalFormView`'s Form+toolbar, `GoalsView`'s NavigationStack shape); a **UX/UI designer**
added interaction/motion/accessibility detail and caught two structural mistakes before
implementation: (1) `swipeActions` only works inside a `List` in this app (confirmed: it's used in
exactly three places, `SecurityView`/`SyncView`/`AccessKeysView`, all `List`-based) — the rest of the
app's `ScrollView+VStack` cards can't have them, so Recurring's "Séries" tab had to be a `List`; (2)
the category picker must be **pushed**, never a nested sheet, since it's reached from screens that
are themselves sheets or pushed screens.

After implementation, an **independent reviewer** (not one of the three design personas, to avoid
rubber-stamping) read the actual code against the spec and against its own judgment, and found real
bugs the design/implementation passes missed (below). A second independent pass verified the fixes
and caught one more (`acceptSafeSuggestions()`'s reconciliation gap).

## What shipped

**Hub** (`BudgetView`, wraps itself in its own `NavigationStack` — `MainTabView` has no
tab-level `NavigationStack`, confirmed by reading it): cycle cashflow card + over-budget status
strip, "N à catégoriser" banner (or a calm "Tout est catégorisé" row at N=0), envelope list (now
tappable, `DashedAddCard` to create), and nav rows to Spending and Recurring.

**Categorization inbox** (`CategorizationInboxView`) — the core screen. One tap accepts an AI
suggestion (`AISuggestionChip`, confidence shown only ≥60%); no suggestion means a pushed
`CategoryPickerView` (2 taps). "Catégoriser tout avec l'IA" runs the async job
(`POST /transactions/categorize-ai` + polling `/status`) with a live progress bar; "Accepter les N
sûres" bulk-applies only ≥70%-confidence suggestions and reconciles with a real reload afterward (not
a local-only removal) so a failed categorize reappears instead of silently vanishing. Accept shows an
undo snackbar (auto-dismisses after 3s, owned by the view model so it survives view redraws).

**Inline categorization everywhere**: the transaction detail sheet's category row (`Transaction
sListView.swift`, previously static text, hidden when nil) is now always shown, tappable, pushes the
same `CategoryPickerView`. Same component reused in the inbox, the Spending drill-down, and the
envelope form's category field — one cross-cutting picker, not four separate ones.

**Envelope CRUD** (`EnvelopeFormView`): mirrors `GoalFormView` exactly (sheet, `NavigationStack{Form}`,
Annuler/Créer|Enregistrer toolbar, destructive delete behind a `confirmationDialog`).

**Spending** (`SpendingView`): ranked category breakdown (Cycle/Année toggle) → drill-down with
inline re-categorization. The "uncategorized" bucket (`categoryId == nil`) routes to the
categorization inbox instead of a category-detail fetch — there's no backend endpoint for an
uncategorized-bucket detail, so this was a real dead-end in an early pass, not a design choice.

**Recurring** (`RecurringView`): Séries (`List`, swipe to confirm/ignore/undo, a "what changed"
activity feed for auto-confirmations and price steps) / Agenda (upcoming charges grouped by month).
No "Detect" button — detection runs automatically after sync. A `variable` series (usage-based
billing) shows a "~min–max" range instead of one misleading fixed amount.

**Settings** (`BudgetSettingsView`): cycle start day + AI toggle only, autosave per control.

## Bugs the independent reviews caught (all fixed, verified in a second pass)

- **Silent categorize failure**: an accept failure put the transaction back in the list, but the
  error banner was gated on the list being empty — which it never was right after re-appending. Split
  into `loadErrorMessage` (list-empty case) vs. `actionErrorMessage` (always shown).
- **Spending drill-down dead end**: `try?` swallowed load errors (blank screen, no retry), and the
  uncategorized bucket called the category-detail endpoint with a fabricated `-1` id.
- **Error states had no pull-to-refresh** on the hub and Recurring — fixed by wrapping the error text
  in the same scrollable+refreshable container as the loaded state, matching the inbox's own pattern.
- **Undo snackbar never auto-dismissed** (a dead `.task { sleep }` on the view instead of the view
  model owning the timeout).
- **Category picker was a sheet in the inbox**, inconsistent with the pushed pattern used everywhere
  else — same file, so the inconsistency was easy to miss reviewing screen-by-screen.
- **`accessibilityElement(children: .combine)` on a whole card with two buttons** — merged them into
  one unreadable VoiceOver element; scoped `.combine` to just the non-interactive header instead.
- **`acceptSafeSuggestions()` bulk-accept avoided reconciling with the server** (its own doc comment
  claimed a `load()` that was never actually called) — a failed bulk item vanished from the inbox
  without being persisted. Same failure class as the first bug, found by the *second* review pass
  after the first bug's fix was already verified elsewhere in the same file.
- **`BudgetSettingsView` had no failed-load state** — `settings = try? await ...` swallowed the
  error, and the view's body only handled `isLoading`/`if let settings`, so a failed GET rendered a
  silent blank screen under the nav title, with no retry. Found independently by two teammate
  reviews during the pre-release pass (a product-lead pass and a UX/a11y pass), both citing the same
  contradiction: every other Budget screen already had this exact failure class fixed. Now mirrors
  `RecurringView`'s pattern (error text in a `ScrollView` + `.refreshable`).

## Technical notes

- **`Transaction` model extended**: `categoryId`, `aiSuggestedCategoryId`, `aiConfidence`,
  `merchantBrandId` added (backend `TransactionResponse` already had them). `BudgetEnvelope` gained
  `categoryId`. New models: `Category`, `Spending{ByCategory,Detail}`, `Recurring{Series,Activity,
  Occurrence}`, `AiJobStatus`, `BudgetSettings(+Request)`.
- **New design-system components** (`Core/DesignSystem/Components.swift`): `AISuggestionChip`,
  `CategoryChip`, `StatusChip`, and `ProgressBar` gained an optional `tint`. No new colors/type/radii
  — a repeated `"#6366f1"` fallback across several files was consolidated into `Theme.fallbackColorHex`.
- **`DemoBudgetStore` is instance-scoped, not a global singleton** — owned by `AppState`
  (`lazy var demoBudgetStore`, one per app launch, `@ObservationIgnored` since `@Observable` can't
  make a computed property `lazy`) so every screen's `DemoBudgetDataSource` shares one session's
  worth of demo mutations, while each XCTest constructs its own fresh `DemoBudgetStore()` for
  isolation (a first version used `static let shared` and would have made tests order-dependent).
- **`DemoBudgetDataSource.init` takes no default parameter** — a `DemoBudgetStore()` default argument
  is evaluated at the *call site's* isolation context, not the initializer's, so a `@MainActor`-typed
  default value doesn't compile from a synchronous nonisolated context. Callers pass the store
  explicitly.

## Tests

`ios-app/PicsouTests/BudgetTests.swift` gained real-shape decode tests for every new model (JSON
fixtures cross-checked field-by-field against the actual backend DTOs, including deliberately extra
keys to prove decode robustness) plus demo-data-source round-trip coverage (categories, inbox
categorize, envelope CRUD, recurring confirm/ignore/undo, settings). The gap flagged by the
independent review — no test exercised `LiveBudgetDataSource` itself, so the real HTTP request
shapes weren't tested on the wire — is now closed: `ios-app/PicsouTests/LiveBudgetDataSourceTests.swift`
exercises it against `MockURLProtocol` (method, path, query params, body fields), including the
`CategorizeRequest.createRule` always-false case, `BudgetSettingsRequest`'s full field carry-through,
and the `horizonDays` query param. 73/73 tests passing (8 skipped: the live e2e suite, which needs a
running backend).

## Links

- Feature note: [`ios-app.md`](../features/ios-app.md)
