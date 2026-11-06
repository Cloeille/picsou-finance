<!-- Short on purpose. Details: CONTRIBUTING.md, "Before you open a pull request". -->

## What and why

<!-- What changes, and why. Link the issue: "Closes #123". Features and new bank connectors: the issue where the approach was agreed. -->

## Checklist

- [ ] **One concern**, about 600 changed lines or fewer (lockfiles, changelog, snapshots and fixtures excluded). If bigger, say why it cannot be split: <!-- reason -->
- [ ] **Synced with the branch you target** (a `release/*` branch, merged in, no conflicts) and **CI is green**; this is a draft until it is.
- [ ] **Tests** cover the behaviour change and its failure case; none depends on today's date (inject a `Clock`).
- [ ] **Migration** (if any): `V<n>` is the next free number on an up-to-date target branch, counting `main` and every open `release/*`: <!-- V123, or "none" -->
- [ ] **Docs and CHANGELOG**: feature note / ADR updated, one `[Unreleased]` entry for a user-visible change, added after the last sync, new i18n keys in all four locales.
- [ ] **Not stacked** on another open pull request (or the maintainer agreed: <!-- which one merges first -->).
- [ ] **Bank connector** (if any): synthetic fixtures only, no real account data, and no screenshot, HAR or log from a real account, anywhere in this pull request; a live test with a real account is still to be agreed with the maintainer.

## Self-review

<!-- Which reviewer agent or loop you ran on the full diff, how many rounds, and what it found and you fixed. "None" is a valid answer only for a trivial change. -->

## Design decisions

<!-- The choices a reviewer could question, and why you made them. Say if you used a tool to settle them (for example the grilling skill). -->
