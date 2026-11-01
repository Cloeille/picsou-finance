# PR #414 round 1

- [ ] Reproduce it yourself on the matching surface via the driver skill (Non-negotiables), even when a debug or instrumentation protocol says to ask the user to reproduce. Ask the user only with a stated, specific reason the control surface cannot reach the target, and only after driving it as far as it goes. If it won't reproduce directly, synthesize the trigger, tighten conditions, or instrument until it fires.
- [ ] Binary-search the cause. Form the candidate hypotheses, then rule them out until one survives. Seed them with `how` over the affected subsystem and the **why** skill for regression history. Each pass, take the split that cuts the most remaining problem space, get runtime evidence, eliminate. When program state is unclear, add instrumentation or logging and read it as the code runs. Don't guess. Drive a long or stubborn hunt with Claude Code's `loop` skill. Confirm the surviving *mechanism* with runtime evidence before the step-3 architect/interrogate fan-out.
- [ ] Plan the fix. If it crosses a function boundary, `architect` first. Delegate implementation to a subagent using your configured bug-fix model (default in poteto-mode's Models section) with a specific scope.
- [ ] Verify on the same surface. The original repro now passes. "Inconclusive" or wrong-surface is not a pass. Flag it. Unit tests show branch behavior, not bug absence.
- [ ] Stage the commits so the failing repro lands before the fix in git history. See the **tdd** skill for the failing-test-first cadence when the bug has a cheap local test path. Skip it when the test would be expensive, integration-heavy, or unclear.
- [ ] Run **Opening a PR**.

## Task checkpoints

- [x] Read instructions and relevant feature/testing documentation.
- [x] Use REA against the public Trade Republic web target; it exposed only the unauthenticated landing URL, not instrument payloads.
- [x] Verify the duplicate test-helper signature and the securities balance arithmetic from source.
- [x] Consolidate findings and choose scoped fixes before editing.
- [x] Update the adapter tests, feature note, and Unreleased changelog.
- [x] Run the requested targeted backend test once and inspect the edited sections.
- [ ] Report every finding and the design decisions. Do not commit because this directory has no `.git` and the pipeline commits the copy.

## Design grill

- Duplicate helper signature: rename the overload that takes a complete portfolio payload; overload parameter shapes cannot distinguish these Java methods.
- Balance expectation: expect 71366.25, the sum of the two position values; this fixture's regular securities account does not receive PEA cash.
- Fallback tests: assert the account balance stays at 447.05 for the unpriced bond, proving cost-basis valuation.
- Currency parsing branches: skip additional parser tests because the required adapter test command had already run once; the source retains fail-closed parsing, and this does not change production logic.
- Multi-account ordering: do not alter adapter sequencing without a reproducing test; record the remaining ordering risk.
- Historical snapshots: document that sync repairs current holdings and today's snapshot, while earlier snapshots are retained.
- Commit: skipped because this worktree copy has no `.git`; pipeline owns the commit.
