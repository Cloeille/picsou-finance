# ADR: Estimate property value from French open data

> Date: 2026-08-01
> Status: ✅ Active

## Context

Picsou aims to be an open-source alternative to Finary. Finary estimates the current value of
a property through **PriceHubble** and derives a gain/loss curve from it. Picsou had nothing
equivalent: a `REAL_ESTATE` account's value was typed in once and never moved again.

A hard constraint shapes everything here: **Picsou is self-hosted, and every user runs their
own instance.** Any solution requiring a per-instance API subscription is not a solution.

We surveyed the commercial market first. There is **no valuation API with a free personal
tier** comparable to Enable Banking's:

| Provider | Free tier | Entry price |
|---|---|---|
| PriceHubble (used by Finary) | none, no self-serve signup | enterprise contract |
| Homiwoo | none | quote only |
| Yanport | none | **€200/month** (400 estimates) |
| Immo API | 2-day trial | €29/month |
| MeilleursAgents, SeLoger | no public API at all | — |

The Enable Banking model works because PSD2 *forces* licensed access to bank data. There is no
regulatory equivalent for property valuation, so every provider is seat- or volume-priced and
none would survive N self-hosted instances each holding their own key.

So the choice was not "which API do we rent" but "do we build an estimator, or ship nothing".

## Decision

**Build the estimator on French open data, behind a port.**

Three sources, all **Licence Ouverte 2.0** and all **unauthenticated**:

| Source | Endpoint | Role |
|---|---|---|
| Cerema DV3F market indicators | `apidf-preprod.cerema.fr/indicateurs/dv3f/prix/annuel/` | median €/m² per commune and property type, with q25/q75 and a transaction count |
| IGN Géoplateforme geocoding | `data.geopf.fr/geocodage/search` | address → INSEE commune code, coordinates |
| INSEE BDM housing index | `api.insee.fr/series/BDM/V1` | carries a DVF vintage forward to today |

`PropertyValuationPort`, `GeocodingPort` and `HousingPriceIndexPort` follow
[the ports-and-adapters ADR](2026-01-01-ports-and-adapters.md), so a future local-DVF
comparables engine or another country's land registry drops in without touching the service.

**No credentials of any kind.** Nothing to encrypt, nothing to add to the setup wizard,
nothing for the user to sign up for. That is the whole point of this source selection.

### The estimate is persisted, not computed on read

This deliberately diverges from
[the loan amortisation ADR](2026-04-26-loan-amortization-on-the-fly.md), which recomputes a
loan's schedule on every request. That works because the amortisation formula is local and
costs microseconds. A valuation depends on **two external HTTP services**, so recomputing per
request would put a third party in the path of every dashboard load.

Instead a monthly job writes `account.currentBalance`, and **`AccountService.liveBalanceEur`
is not modified at all** — a valued property behaves exactly like any other manual account.
The existing daily snapshot job then captures the gain curve for free.

Monthly, not daily: DVF is published twice a year and the INSEE index is quarterly. A nightly
run would produce identical figures while hammering a service that is still on a preprod host.

### Adjustments are declared heuristics

DVF records **no** construction year, floor, lift, balcony, garden, garage or condition. Half
of the Finary-parity field set therefore *cannot* be calibrated against the data. Rather than
pretend otherwise, `PropertyAdjustments` holds a small, bounded, documented coefficient table,
and **every applied coefficient is returned to the caller and shown in the UI** behind a
"how this figure is built" panel carrying an explicit disclaimer.

Two properties of that table are load-bearing: the combined multiplier is clamped to
[0.75, 1.25], and area-equivalents are capped, so no pile-up of individually plausible bonuses
can produce a silly total. Garage and parking are priced as a multiple of the *local* €/m²
rather than a flat euro amount — a parking space in central Paris and in a village differ
several-fold.

## Alternatives considered

### Rent a commercial API (PriceHubble, Yanport, Homiwoo)

- **Pros**: Best accuracy available. No estimator to build or maintain. Same source Finary uses.
- **Cons**: No free tier exists. Yanport's €200/month floor alone rules it out for a
  self-hosted app. Every user would need their own commercial contract, which contradicts the
  project's premise that features work without a subscription.

### Self-host the full geolocated DVF dataset and compute real comparables

- **Pros**: Much better accuracy — actual sales within a radius, filtered by type and surface
  band, instead of a commune-wide median. Fully offline once imported.
- **Cons**: ~512 MB gzipped for five years of France, ~4-6 GB in Postgres with indexes; too
  heavy for a small self-hosted instance without per-department lazy loading. Needs an import
  pipeline, a dedupe step (`id_mutation` — DVF repeats `valeur_fonciere` on every lot row,
  the classic trap), and radius search. Deferred, not rejected: the port accepts a
  `LocalDvfValuationProvider` with no other change.

### The community API DVF (`api.cquest.org/dvf`)

- **Pros**: Ready-made HTTP API over the same data, no import.
- **Cons**: A personal proof-of-concept with no SLA — **returned HTTP 502 on every attempt
  during evaluation**. Depending on one volunteer's server for a core feature of every
  self-hosted instance is not acceptable.

### Scrape MeilleursAgents / SeLoger

- **Pros**: Street-level price data, better granularity than a commune median.
- **Cons**: Against their terms of service, and structurally unworkable for a distributed app
  where thousands of instances would each scrape independently.

## Reasoning

Free and open was a requirement, not a preference — it is the difference between a feature
every Picsou user has and one only paying users have. Given no commercial option qualified,
the real question was how honest the resulting estimate could be made. Commune-median × living
area is coarse, but it is *auditably* coarse: the confidence band, the sample size, the data
vintage and every applied coefficient are all reported. That is a defensible product, whereas
a single unexplained number derived from the same data would not be.

## Trade-offs accepted

- **Commune-level granularity.** No notion of street or neighbourhood. A well-placed flat and
  a poorly-placed one in the same commune start from the same median. The q25/q75 band is
  reported precisely so the user sees the spread.
- **Alsace-Moselle (57, 67, 68) and Mayotte (976) are not covered at all.** They keep the
  *livre foncier* registry, so DGFiP holds no transactions — the API answers `count: 0`, which
  would otherwise read as "no recent sales". Those departments are refused up front and the UI
  explains why, pointing at the Patrim service on impots.gouv.fr as the manual route.
- **Data lags by roughly a year.** Mitigated by INSEE re-indexing, not eliminated.
- **Best-effort infrastructure.** The Cerema host is still `-preprod`, has no documented rate
  limits, and returned transient 503s during evaluation. Every failure path returns empty and
  keeps the previous valuation rather than blanking a property's value over a blip.
- **The heuristic coefficients are opinions.** They are bounded, documented and disclosed, and
  the user can switch a property to `MANUAL` at any time.

## Consequences

- New ports `PropertyValuationPort`, `GeocodingPort`, `HousingPriceIndexPort`; new adapters
  `CeremaDv3fValuationProvider`, `GeoplateformeGeocoder`, `InseeBdmIndexProvider`.
- New services `PropertyValuationService`, `PropertyAdjustments`, `RealEstateSummaryService`.
- `SchedulerService` gains a monthly `monthlyPropertyValuation()` tick.
- Migration `V66` extends `real_estate_metadata` to Finary parity and adds `property_valuation`.
- `AccountService.liveBalanceEur` is **unchanged** — the design goal, not an oversight.
- **Geocoding targets `data.geopf.fr`, never `api-adresse.data.gouv.fr`**: the latter was
  decommissioned at the end of January 2026 and now survives only as a cross-host 301 that can
  disappear at any time.
- **DGFiP reuse conditions** forbid re-identifying individuals and indexing by search engines.
  Picsou is private and authenticated, so this is satisfied — but demo mode must never ship
  real DVF records, since a demo build is public by definition.
