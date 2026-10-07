# Product

## Register

product

## Platform

web

> Note: a native iOS companion app is under evaluation (Swift native vs React Native undecided as of 2026-07). The root PRODUCT.md targets the web frontend, which is the primary and certain surface. When the iOS stack is chosen, a dedicated PRODUCT.md will be created for the app with its own `## Platform` (`ios` for Swift, `adaptive` for React Native sharing this product).

## Users

An individual or the administrator of a small family, managing personal wealth across bank accounts, brokerage, crypto, and on-chain assets. They chose to self-host precisely because the data involved — balances, transactions, bank session tokens — is sensitive, and they want it to stay on their own machine behind their own firewall.

Their context when opening the app: they want a quick, trustworthy read on where they stand financially, without reassembling information from six banking apps and a spreadsheet. The job to be done is **reducing mental load** — turning "how am I doing?" into a glance.

Primary audience: the self-hoster / family admin. Secondary: managed family profiles (spouse, children) who may view shared resources once an activation link upgrades them to a full login.

## Product Purpose

Picsou exists so one person can see their complete financial picture — bank, brokerage, crypto, on-chain, net worth, goals — in one self-hosted dashboard, with the data never leaving their own machine. Success looks like: the user opens the app, knows exactly where they stand financially at that moment, and closes it reassured rather than anxious.

## Positioning

Your financial data stays on your machine. Privacy is not a feature — it is the proposition.

## Brand Personality

Serious and credible without being cold. The interface handles real money and never jokes about it, but it stays warm and human enough that a non-expert can use it. Calm and composed: the dashboard breathes, the data doesn't shout. Three words: **credible, warm, composed**.

## Anti-references

- **Generic fintech flash** — violet gradients, neon accents, flat-design illustrations, the "startup trying to sell you a subscription" aesthetic. Picsou is not selling anything.
- **Cold technical dashboards** (Datadog, Grafana, generic analytics tools) — too jargon-heavy, forgetting the human behind the numbers.
- **Hollow fintech marketing** — big animated numbers, testimonials, reassurance without substance, no real data underneath.
- **Traditional banking apps** — cluttered, prioritizing the bank's own product catalog over the user's patrimony.

## Design Principles

1. **Privacy is the product.** Self-hosting is the proposition, not a line in a feature list. Every surface reinforces that the user's data lives on their own machine — never as a banner, always as the default assumption.
2. **Situation, not report.** The user comes to know where they stand, not to analyze. The dashboard answers in one glance; depth is available on demand, never forced.
3. **Credible without being cold.** Financial seriousness with a human voice. No jargon, no neon, no marketing filler — but no austere terminal either. The middle is the hardest and the point.
4. **Breathing density.** Enough information to be complete, never enough to overwhelm. The surface earns its whitespace; the data earns its presence.
5. **Financial legibility is non-negotiable.** Amounts, balances, and alerts — the content the user actually came to read — meet AAA contrast. Nothing critical is ever ambiguous or barely legible.

## Accessibility & Inclusion

Target **WCAG 2.1 AA** across the interface, with **AAA contrast on critical financial content** (amounts, balances, alerts, and any figure the user came to read). Reduced-motion is respected globally (already enforced app-wide via the base stylesheet). Full keyboard navigation is expected. Specific considerations for color blindness apply to chart palettes, which must remain distinguishable beyond hue alone.
