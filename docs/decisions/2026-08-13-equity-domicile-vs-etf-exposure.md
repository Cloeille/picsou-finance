# ADR: A country breakdown mixes domicile and exposure, and says so

> Date: 2026-08-13
> Status: ✅ Active

## Context

The Analysis section aggregates the geographic spread of the whole equity sleeve. Two kinds of
holding feed it, and they answer two different questions.

For an **ETF**, the country breakdown Boursorama publishes is the index's **look-through
exposure**: "70% United States" means 70% of the fund's assets are US companies.

For a **directly held share**, all we can obtain is the **country of issuance**, read from the
first two characters of the ISIN. That is where the issuer is registered, which is frequently not
where its business is: Airbus is `NL`, Accenture `IE`, Ferrari `NL`, and every Irish-domiciled
UCITS would be `IE` if it were treated the same way.

Adding "50% US from your world ETF" to "100% NL from your Airbus line" adds two quantities that
are not the same quantity.

## Decision

Aggregate them anyway, into one breakdown, and **label the result**.

`DiversificationResponse.Breakdown` carries a `basis` discriminator:

- `EXPOSURE` — every contribution came from a fund look-through.
- `MIXED` — at least one directly held share contributed its domicile.

The client renders an explicit note under the bar whenever `basis` is `MIXED`. The number is
never silently presented as pure exposure.

## Alternatives considered

### Show two separate bars, one per basis

- **Pros**: never mixes units; each bar is internally consistent.
- **Cons**: neither bar answers the question the user asked ("where is my money?"). A portfolio
  of one world ETF and five French shares would show a 100%-US bar next to a 100%-FR bar, and the
  reader has to weight them mentally — which is the arithmetic the page exists to do.

### Resolve real revenue exposure for individual shares

- **Pros**: the only genuinely correct answer; makes the whole breakdown one quantity.
- **Cons**: no free source publishes revenue-by-geography per company. It is a licensed dataset,
  and inventing it from headquarters or listing venue would be the same approximation with more
  machinery around it.

### Exclude directly held shares from the country breakdown

- **Pros**: keeps the bar pure exposure.
- **Cons**: a stock-picking portfolio would have an empty geographic breakdown — the case where
  concentration risk is highest is the one we would refuse to measure.

## Reasoning

The mixed number is useful and the pure alternatives are not available. A French investor holding
a world ETF and a handful of CAC 40 shares genuinely is more exposed to France than the ETF alone
suggests, and domicile captures that even though it is a proxy.

What makes it acceptable is that it is **stated**. The failure mode we refuse is a number that
silently means two things; a number that means one approximate thing, labelled, is ordinary
financial reporting.

## Trade-offs accepted

- The country breakdown is an approximation whenever `basis` is `MIXED`, and holdings like Airbus
  are filed under a country that has little to do with their business.
- The user carries the interpretation. We give them the flag and the sentence, not a corrected
  figure.

## Consequences

- `EquityProfile.countryIsDomicile` travels from the adapter that resolved the country, so the
  basis is derived from what actually happened rather than assumed.
- `PortfolioDiversificationService` sets `MIXED` as soon as one share or one manual override
  contributes, and the frontend gates its note on `countries.basis`.
- If a revenue-exposure source ever becomes available, the discriminator is the seam to change:
  the providers start answering `countryIsDomicile = false` and the note stops appearing, with no
  change to the aggregation.
