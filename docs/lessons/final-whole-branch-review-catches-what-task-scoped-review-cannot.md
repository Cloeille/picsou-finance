# Lesson: a call site no single task touches can still be broken by every task individually being correct

> Date: 2026-07-14
> Context: subagent-driven implementation of the account-visibility feature (`hidden` flag), branch `1.1.0`, `docs/briefs/2026-07-14-account-visibility-toggle-plan.md`.

## What happened

The plan scoped "exclude hidden accounts from every user-facing display/total" down to three concrete call sites: `DashboardService` (net worth), `AllocationService` (budget), `SavingsService` (suggestions) — each got its own task, each task's diff was reviewed in isolation, and each review correctly found nothing wrong, because nothing in *that task's diff* was wrong.

The final whole-branch review (not scoped to any one task, reading the branch end-to-end against the design's stated goal rather than against a single task's file list) found that `FamilyViewService.java`'s `SharingLevel.ALL` branch still called the unfiltered account query. A member's hidden account — and its balance — still surfaced in the shared family dashboard other members see. No task's brief mentioned this file, so no task-scoped reviewer had a reason to look at it; the gap only became visible when someone compared the *finished feature* against its *stated goal* rather than against any individual task's stated scope.

## What we did

Fixed the one call site (`findAllByMemberIdOrderByCreatedAtAsc` → `findAllByMemberIdAndHiddenFalseOrderByCreatedAtAsc`), left the `SharingLevel.MANUAL` branch (a different, intentional per-account opt-in mechanism) untouched, and re-verified with a narrow follow-up review before merging.

## How to apply

When a plan enumerates "the places this needs to change" (a finite call-site list, an explicit files-to-touch section), that list is a hypothesis, not a guarantee — it's only as complete as whoever wrote the plan's mental model of every consumer of the thing being changed. Task-scoped code review is necessary but not sufficient: it can only ever confirm a diff matches its own brief, never that the brief's scope was complete. Always run a final review pass that reads the *design's goal* against the *whole branch*, independent of the task list — that is precisely the review with a chance of catching the consumer nobody thought to list. This is a load-bearing reason to keep the "final whole-branch review" step even when every individual task review came back clean.

## References

- Related ADR: `docs/briefs/2026-07-14-account-visibility-toggle-plan.md` (Task 3's "why only these three" rationale, silently incomplete)
- Commit: `ae09139` (the fix)
