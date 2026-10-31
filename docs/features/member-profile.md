# Feature: Member profile (personal and fiscal context)

> Last updated: 2026-08-19

## Context

Picsou knew what a member owned and nothing about the person holding it. The same 30 000 € PEA
means something different at 25 in the 11 % bracket than at 58 in the 41 % one, and the
[account spreadsheet export](./account-xlsx-export.md) exists precisely to be read by someone — or
something — that has to tell the two apart.

`member_profile` records that context: date of birth, marginal tax rate, household, income,
savings capacity, retirement horizon, risk profile. It is edited from a card in Settings.

The first consumer shipped alongside it is the **savings rate** on the Goals page, which needs a
denominator (see [goal-recurring-investment.md](./goal-recurring-investment.md)). Wiring the
profile into the xlsx export is deliberately not part of this change — see **Not done yet**.

## How it works

### A table of its own

Not columns on `member_allocation_profile`, which is the other per-member preferences row.
`PUT /api/analysis/allocation-targets` replaces that profile wholesale, so fiscal columns living
there would be wiped every time the allocation modal saved — unless a merge were bolted on to
prevent it. Two tables, two endpoints, nothing to remember.

### Absence is meaningful

Every column is nullable and stays that way, and a member who has stated nothing has **no row**.
`MemberProfileService.profileFor` returns an unsaved empty instance for them; reading never
writes. That is the same contract `MemberAllocationProfile` and `AccountOwnership` carry, and it
is what makes "never stated" distinguishable from "stated as blank" — a figure guessed on someone's
behalf and then stored is indistinguishable from one they vouched for.

Null on the way in is a value too: it is how a member withdraws a figure they no longer stand
behind. `PUT /api/me/profile` is a full replacement, so an omitted field clears what was there.

### The date, never the age

`birth_date` is stored; `age` is derived on read. An age written down as an integer is wrong the
morning after a birthday and nothing in the app would ever correct it. `monthlyNetIncome` is
derived the same way, so every screen that wants a monthly figure gets the same one — the goals
page's savings rate is the first, and it must not disagree with whatever comes second.

### Three income fields, and why

```
annual_gross_income                     fiscal context only — nothing is computed from it
monthly_net_before_tax  ┐
                        ├──→  monthlyNetIncome  ──→  the savings rate's denominator
withholding_tax_rate    ┘
```

**The savings rate has to be measured against net income.** Money withheld at source was never
available to save, so dividing contributions by a gross salary understates the rate by roughly a
quarter.

This is also what makes the comparison to INSEE's figure legitimate, and the point is easy to get
backwards: the **"brut" in *revenu disponible brut* does not mean "before tax"**. It means gross
of consumption of fixed capital — an accounting convention. RDB is computed *after* compulsory
levies (income tax, CSG/CRDS, social contributions) and after social benefits. Its base is
therefore a net-of-tax concept, much closer to net salary than to gross. An earlier version of
this feature divided the gross by twelve on exactly that misreading.

**Gross cannot get to net on its own**, which is why there are two fields rather than a derivation.
Social contributions come off first, at ~22–25 % varying by status (cadre / non-cadre, private /
public), and withholding applies to what remains. Deriving net from gross would have meant storing
a contribution rate the member does not know offhand — a guessed number promoted to a stored one,
which is precisely what `member_allocation_profile.monthly_essential_expenses` refuses to become.

The two fields chosen instead are both **printed on every French payslip**: the "net à payer avant
impôt sur le revenu" line, and the withholding rate applied beneath it. The arithmetic is then
exact and assumes nothing:

```
monthlyNetIncome = monthlyNetBeforeTax × (1 − withholdingTaxRate / 100)
```

**Null unless both are stated.** A blank withholding rate means "not said", not "zero" — someone
genuinely below the taxable threshold types a `0`. Without that rule the Goals page would show a
rate built on a figure nobody supplied, which reads as a measurement while being an artefact.

The gross figure stays because it is real fiscal context — it is what a tax bracket is read
against, and what the export will want — but nothing computes from it.

The two rates are still not the same quantity: disposable income is not salary (it includes
benefits and property income, household-wide), and standing orders are not all of saving. The
tooltip therefore quotes the definition rather than presenting the comparison as arithmetic.

### The fields

| Field | Column | Notes |
|---|---|---|
| Date of birth | `birth_date` | `age` derived on read |
| Marginal tax rate | `marginal_tax_rate` | Percent (0/11/30/41/45 in France), **not** a ratio |
| Household | `household_status` | `SINGLE` / `COUPLE` |
| Tax household shares | `tax_household_parts` | `NUMERIC(4,2)` — half-shares are real |
| Dependents | `dependents` | `SMALLINT` |
| Annual **gross** income | `annual_gross_income` | Fiscal context; feeds nothing |
| Monthly net before tax | `monthly_net_before_tax` | The payslip's "net à payer avant impôt sur le revenu" |
| Withholding rate | `withholding_tax_rate` | Taux de prélèvement à la source, percent |
| Monthly savings capacity | `monthly_savings_capacity` | What the member says they *can* save, next to what they do |
| Target retirement age | `target_retirement_age` | `SMALLINT`, 40–90 |
| Risk profile | `risk_profile` | `PRUDENT` / `BALANCED` / `DYNAMIC` / `AGGRESSIVE` — stated, never inferred |

The tax rate is offered as the five French brackets rather than a free field, but the column is
numeric and permissive: the list is the shortcut for the members it fits, not the schema.

The risk profile is deliberately *stated*. What someone actually holds is already measurable from
their accounts; the gap between that and what they say they want is the interesting figure, and it
only exists if the two are recorded apart.

### Key files

**Backend**
- `model/MemberProfile.java`, `model/HouseholdStatus.java`, `model/RiskProfile.java`
- `repository/MemberProfileRepository.java`
- `dto/MemberProfileRequest.java` / `MemberProfileResponse.java`
- `service/MemberProfileService.java` — modelled on `AllocationTargetService`
- `controller/MeProfileController.java` — `GET` / `PUT /api/me/profile`
- `db/migration/V90__member_profile.sql`

**Frontend**
- `features/profile/{api,hooks}.ts` — `useMemberProfile`, `useSaveMemberProfile`, key `['me','profile']`
- `pages/settings/sections/ProfileSection.tsx` — the form
- `pages/settings/SettingsPage.tsx` — the `SectionCard` it sits in
- `demo/data/profile.ts`, `demo/index.ts` — the mock and its `GET`/`PUT` handlers

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| A separate table | `AllocationTargetsRequest` is a full replacement; sharing a row means the allocation modal wipes the profile | Columns on `member_allocation_profile` |
| Every column nullable, no row by default | "Never stated" has to stay distinguishable from a value; no backfill needed | `NOT NULL` with shipped defaults |
| Store the date, derive the age | An integer age is wrong the day after a birthday, forever | An `age` column |
| Derive `monthlyNetIncome` server-side | One formula, one rounding, one place — the savings rate is only the first consumer | Computing it in each screen |
| Tax rate as percent | It is how a bracket is named (30 %, not 0.30) | A 0–1 ratio |
| Net from two payslip lines | Exact, and assumes no contribution rate | Deriving net from gross with a stored ~23 % assumption |
| Gross kept but computed from nothing | Genuine fiscal context for the export; it just cannot reach net | Dropping it, or dividing it by 12 for the savings rate |
| `monthlyNetIncome` null unless both inputs are given | A blank rate is "not said"; treating it as 0 fabricates the denominator | Defaulting the withholding rate to 0 |
| `VARCHAR` + named `CHECK` for the two enums | Same reasoning as V83: no native enum has been added since V21, and it sidesteps `ALTER TYPE … ADD VALUE` | `CREATE TYPE` |
| Bracket buttons, numeric column | Fits nearly everyone without making the schema French-only | A `NUMERIC` free field, or an enum of brackets |
| Under `/api/me` | Data about the person, not an app preference — the namespace `MeExportController` already uses | `/api/settings/profile` |

## Not done yet

- **The GDPR export does not include it.** `ProfileExporter`'s CSV header is positional, and
  `member_allocation_profile` is not exported today — this follows that precedent rather than
  breaking it in passing. It is a real gap for a right-of-access request: the fields here are
  personal data.

## Gotchas / Pitfalls

- **The API omits nulls**, so read every field with `== null`, never `=== null`, and write fixtures
  without the key. See [`api-rest.md`](../conventions/api-rest.md).
- **A `PUT` clears what it omits.** The form always sends every field; a partial payload is a
  deletion, not a patch.
- **The savings rate divides by net, never gross.** If a future caller reaches for
  `annualGrossIncome / 12` because it is the field that is filled in, it will report a rate about
  a quarter too low against a benchmark that is measured on net.
- **Income and savings capacity are amounts.** They appear only inside `NumericInput`, which
  privacy mode exempts. Anything rendering them outside a form must go through `CurrencyDisplay`
  or `money-guard.test.ts` fails the build — see [`privacy-mode.md`](./privacy-mode.md).
- **The demo handler derives `age` and `monthlyNetIncome` itself.** The real backend computes them;
  without the same arithmetic in `demo/index.ts` the savings rate never moves in demo mode.
- **Reading must not create a row.** `profileFor` returns an unsaved instance on purpose; a read
  that persisted defaults would erase the "never stated" state for anyone who merely opened the
  Settings page.

## Tests

- `MemberProfileServiceTest` — an unstated profile reads as all-null, reading never saves,
  `replace` creates on first use and updates thereafter, a null field clears, the age turns over on
  the birthday (asserted the day before and the day of), and an unknown member is refused. On the
  income chain: the net is the before-tax figure less the withholding, rounds to cents, a stated
  **0 %** leaves it untouched, it stays **null while either input is missing**, and the gross is
  carried through without ever feeding it
- `MeProfileControllerTest` — both verbs take the member id from `UserContext`
- `SchemaMappingValidationTest` — the entity matches what V90 creates, against real PostgreSQL
- `locales-parity.test.ts` — `settings.profile.*` present in all four locales

## Links

- Related: [Recurring investment plans](./goal-recurring-investment.md) — the savings rate this feeds
- Related: [Account spreadsheet export](./account-xlsx-export.md) — writes this profile at the top
  of its summary sheet, age but never the birth date
- Related: [Wealth pyramid](./wealth-pyramid.md) — `member_allocation_profile`, the table this one
  deliberately does not join
- i18n keys: `settings.profile.*`
