# Lesson: Demo data must include all required interface properties

> Date: 2026-07-06
> Context: Frontend / 1.1.0 branch / Docker build failure during TypeScript compilation

## What happened

The Docker build for the main app image failed with TypeScript errors during the frontend compile step. The error: three Revolut pocket accounts in the demo data file (`frontend/src/demo/data/accounts.ts`) were missing the `logoUrl: string | null` property, which is **required** by the `Account` interface defined in `types/api.ts`.

The first seven demo accounts had correctly included `logoUrl: null`, but the three Revolut accounts (ids 8, 9, 10) were missing it entirely. TypeScript in strict mode caught this — the interface enforces completeness, and partial objects fail compilation.

## What we did

Added `logoUrl: null` to each of the three incomplete Revolut account objects. The frontend then compiled successfully, and all Docker builds (main app, revolut-auth, tr-auth) passed.

No guardrail was added because this is demo data — the fix is the fix. However, the takeaway prevents the same mistake in future demo data additions.

## How to apply

**When adding or updating demo data objects:** verify that every required property from the corresponding TypeScript interface is present in the object literal, even if the value is `null`. 

TypeScript strict mode + Docker's multi-stage build catches these at image build time, not at runtime. Demo data lives in the repo and must match the interface shape exactly — there is no "partial object" pathway. If a property is non-optional in the interface, it must be in the object.

A quick check: copy the interface definition and compare field-by-field with the object literal. If any non-optional field is absent, the build will fail.

## References

- Commit: `eb66ced` (fix(frontend): add missing logoUrl property to Revolut demo accounts)
- Interface: `frontend/src/types/api.ts` — `Account` interface, line 27–50
- Demo data: `frontend/src/demo/data/accounts.ts`
