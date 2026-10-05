# PR #33 — sync hardening: merge, review threads, verification

Pushed SHA: **3deacafe304b9b4f54cfae81b3fb1c8a03c2a489** → `DailyXplorer/Picsou:fix/sync-flow-hardening` (fast-forward, no `--force`).

---

## 0. Divergence from the mandate's measurement

The mandate said **4 conflicts**. My measurement found **5**:

    git merge-tree --write-tree --name-only origin/main pr33

added `backend/src/test/java/com/picsou/service/PriceServiceTest.java`, which the brief did not list. It conflicts because main's #79 (`fix(prices)`) added two cache tests to the same file where the PR added a `lenient` import. Per the mandate ("si ta mesure contredit ce texte, TA MESURE GAGNE"), I resolved all 5.

**No Flyway renumbering was done.** Verified: `V50`, `V51`, `V52` are three *distinct* files — no collision — and `application.yml` carries `flyway.out-of-order: true` with a comment anticipating exactly this. Untouched, as instructed.

---

## 1. Conflict arbitration, file by file

### a) `backend/src/main/java/com/picsou/adapter/EnableBankingBankConnector.java`
- **Both sides kept.** main (#80) replaced inline `institutionId` splitting with `parseInstitutionId()` → `InstitutionRef` (3-segment id carrying `psu_type`). The PR changed the method signature to accept a caller-supplied OAuth `state` nonce.
- **Kept from main:** `InstitutionRef ref = parseInstitutionId(institutionId)` and `ref.bankName()`/`ref.country()`.
- **Kept from the PR:** the `String state` parameter and `"state", state` in the request body.
- **Discarded:** main's `"state", applicationId() + "_" + System.currentTimeMillis()` — this is precisely the never-persisted legacy state the PR replaces with a persisted single-use nonce. Keeping it would defeat the PR's core fix.
- Cross-checks: `BankConnectorPort` (auto-merged) already declares the 2-arg signature; the line immediately below the conflict already used `ref.psuType()`, so main's parsing side was mandatory. `applicationId()` remains used at line 396, so no dead method.

### b) `backend/src/test/java/com/picsou/service/PriceServiceTest.java`
- **Both sides kept, purely additive.** PR adds `import static org.mockito.Mockito.lenient`; main (#79) adds `getPriceEur_cachesAMiss_...` and `getPriceEur_stillCachesAndReturnsHits`. Nothing discarded.

### c) `backend/src/test/java/com/picsou/service/SyncServiceTest.java`
- **Both intents merged into one stub.** PR: `initiateConnection(eq(...), any(String.class))` (2-arg, for the state nonce). main: `initiateConnection("BNP Paribas::FR::personal")` (1-arg, new 3-segment id).
- **Result:** `initiateConnection(eq("BNP Paribas::FR::personal"), any(String.class))` — main's *literal* with the PR's *arity*. Taking the PR's old `"BNP_PARIBAS::FR"` literal would have broken the test against main's institution-id format.

### d) `docs/features/bank-sync.md`
- **Both sides kept** in two spots (additive rows describing independent features).
  - Technical-choices table: PR's "Independent requisition lifecycle checkpoint" row + main's two PSU-type rows.
  - Tests list: the two `SyncServiceTest` bullets described *the same file* differently, so I merged them into one sentence covering both ("checkpoint ordering … **and** logo matching for both the current and the legacy institution id format"), then kept the PR's `RequisitionLifecycleWriterTest` bullet and main's `AddAccountModal.test.tsx` bullet. Nothing dropped.

### e) `frontend/src/components/shared/AccountCard.tsx`
- **Both sides kept.** PR adds the >48 h stale-sync badge (Tooltip imports, `formatTimeAgo`, `SYNC_STALE_THRESHOLD_MS`). main adds provider-logo fallback (`providerLogoUrl`, `provider` prop on `AccountAvatar`).
- **Kept main's `AccountAvatar` signature** — decisive evidence: the already-merged call site below passes `provider={account.provider}`, so the PR's 2-prop signature would not compile. Both doc comments preserved.

**On the #50 overlap:** my resolutions here are conservative — I discarded exactly one thing (main's throwaway `state` expression), and only because the PR's whole purpose is to replace it. The other four are additive unions. Nothing in these resolutions forecloses what #50 might do on the same files; no divergence signal to escalate.

---

## 2. The four unresolved review threads

I did **not** act on the two non-outdated ones. Report only, as instructed — these are Chloé's calls.

### NON-OUTDATED #1 — `PriceService.java:123`, coderabbitai, 2026-07-08
**Ask:** inject `PriceProviderPort` instead of the concrete `CoinGeckoPriceProvider`/`YahooFinancePriceProvider`.

**Still live, verified.** `backend/src/main/java/com/picsou/port/PriceProviderPort.java` **does** exist, declaring `getPricesEur(Set)` **and** `supports(String)`. Both providers already `implements PriceProviderPort`. But `PriceService`'s constructor still takes the two concrete classes.

**Nuance that matters for the arbitration:** the author's stated reason for deferring was that the port would need `supports()` promoted into it. That premise no longer holds — `supports()` **is already on the port** (line 20). So the "real architecture change" objection is weaker than when written; the routing (`coinGecko.supports(upper)` picks crypto vs stock) is still a two-provider ordered choice, not something a bare `PriceProviderPort` injection expresses, so it would need `List<PriceProviderPort>` or a composite. Cheaper than argued, not free. CodeRabbit agreed to defer; the thread was left open, not closed.

### NON-OUTDATED #2 — `SyncService.java:112`, coderabbitai, 2026-07-12 — SECURITY
**Ask:** stop logging the raw Enable Banking session id (a ~90-day PSD2 credential) in plaintext; the author agreed it should be fixed *consistently*, not one line at a time.

**Already fixed — no longer live.** I grepped every log statement in both files on the merged branch:
- `SyncService.java`: **zero** occurrences of `sessionId` or `getRequisitionId()` in any log call. The line in question now reads `log.info("Enable Banking requisition {} ({}) returned no accounts during completion — marking retryable", requisition.getId(), requisition.getInstitutionName())`. All five sites ecurieai listed (109, 167, 246-247, 345-346, 352-353) now log `req.getId()` + institution name.
- `EnableBankingBankConnector.java:157`: now `log.info("Enable Banking session created")` — the id is gone.
- Fixed in **`cd16d03`** (2026-08-03), whose message states it explicitly. Both reviews predate it, which is why the threads were never marked resolved.
- `docs/features/bank-sync.md` now documents the rule: *"Session identifiers stay out of logs … never print the raw session id."*

### OUTDATED #1 — `SyncService.java:109`, ecurieai, 2026-07-15
Same security point ("already flagged, still open"). **Now moot** — same evidence as above. It was accurate when written (the checkpoint refactor `01d6df0` did add that line); `cd16d03` then removed it along with the other four. **No live code behind it.**

### OUTDATED #2 — `BankSyncTab.tsx:136`, ecurieai, 2026-07-15
Ask: `retryMutation`/`completeSync` are local duplicates of the shared `useRetryBankSync`/`useCompleteBankSync`, invalidating `['sync','connections']` instead of `syncKeys.banks()`.

**Now moot — the code no longer exists in that form.** `BankSyncTab.tsx` imports and uses the shared hooks (`useCompleteBankSync` L72, `useRetryBankSync` L101, `useDeleteBankConnection` L151); there are no local `useMutation` definitions left. A repo-wide grep for `'sync', 'connections'` returns **nothing** — the stale key is gone, so the `SyncAllModal` staleness gap ecurieai described is closed. Also fixed in `cd16d03`. **No live code behind it.**

---

## 3. `/code-review low --fix`

Two parallel sub-agents (Standards + Spec) over `git diff origin/main...HEAD`. I verified every finding against the merged tree before acting; several did not survive.

**Rejected as factually wrong (verified against the tree):**
- *"V51 collides, should be V71"* — false. `V50`/`V51`/`V52` are three distinct files, and `out-of-order: true` is configured. This is the exact trap the mandate warned about; no renumbering done.
- *"backend/CLAUDE.md weakens a convention in the same diff (CODING_RULES §0)"* — the diff edits **no** file under `docs/conventions/` and not `CODING_RULES.md`. It ships an ADR alongside. Rule as cited doesn't apply.
- *"`ex.getMessage()` concatenation is new"* — the refactor *centralised* a pattern already present across `PowensBankConnector`, `TradeRepublicAdapter` and this file on main. Pre-existing, not introduced.
- *"Duplicated Code across BankSyncTab/SyncAllModal"* — two uses of the stock TanStack `isPending && variables === id` idiom. Not worth a shared hook.

**Applied (commit `3deacaf`):** renamed the opaque `options` helper in `SyncAllModal.tsx` to `rowCallbacks` with a doc comment (all 4 call sites updated). Suites re-run green after.

**Reported, deliberately not fixed — needs Chloé's call:**
- `frontend/src/lib/errors.ts` — `isTrSessionDeadError`/`matchTrDetail` branch on backend `detail` **prose** (`detail.includes('expired')`). This *is* a correctly-cited violation: `docs/conventions/error-handling.md:89` — *"Codes are API contracts; messages remain English diagnostics and must not be parsed"* — and `api-rest.md:84`. The sanctioned fix is a stable `code` (the backend already has `SyncException(msg, cause, code)` and throws a bare `"SESSION_EXPIRED"` sentinel). But changing it means reshaping the TR error taxonomy end-to-end — the very surface this PR exists to fix — so it is a product decision, not a mechanical cleanup.
- The Spec sub-agent flagged that `TradeRepublicAdapter.refreshSession` maps only **401/403**, while the PR description says **"a sidecar 4xx"**. Confirmed in code (L164-171), and it is *deliberate*: an in-code comment and thread `3543285011` show 429 was excluded on purpose so a TR rate-limit can't destroy a live session. **The code is right; the PR description text is stale.** Worth a one-line description edit, not a code change.
- Also flagged: `refreshPrices` can put `null` cache values into the returned map on the cache-hit branch (`result.put(upper, cached.price())` where a 5-min miss-TTL entry holds `price == null`), which `GET /api/prices` would serialize. Note this hunk is shared with main's #79 negative-cache work; a null filter on that branch would be the fix. Not applied — outside the PR's stated scope and touching #79's code.

---

## 4. Verification, raw, on the merged branch

Toolchain: `JAVA_HOME=/tmp/tools/jdk-21.0.12+8`, `PATH+=/tmp/tools/apache-maven-3.9.16/bin`.

### Backend — `cd backend && mvn test`
```
[WARNING] Tests run: 858, Failures: 0, Errors: 0, Skipped: 11
[INFO] BUILD SUCCESS
[INFO] Total time:  25.419 s
```

**Skip declared explicitly:** the **11 skipped** tests are the Testcontainers-backed migration tests — `TradeRepublicValuationMigrationTest` (1) and `WalletEvmMigrationTest` (10). They self-skip because Docker is absent (podman only on this host):
```
ERROR o.t.d.DockerClientProviderStrategy : Could not find a valid Docker environment.
```
This is the documented behaviour, **not** coverage. Those two migration paths were **not exercised here** and I make no claim about them.

### Frontend
```
$ bun run typecheck        # tsc --noEmit   → exit 0, no output
$ bunx vitest run
 Test Files  30 passed (30)
      Tests  156 passed (156)
$ bun run lint             # eslint .       → exit 0, zero warnings
```
All three re-run **after** the `rowCallbacks` commit; still green.

---

## 5. Push

```
To github.com:DailyXplorer/Picsou.git
   cd16d03..3deacaf  HEAD -> fix/sync-flow-hardening
```

**Remote identity verified before pushing, and the mandate's warning was real.** The pre-existing remote named `fork` points at **`LucasVidelaine/picsou-finance`** — the wrong fork. `theochr72` is a third party. **No** existing remote pointed at DailyXplorer. I added `dailyxplorer` → `git@github.com:DailyXplorer/Picsou.git` and confirmed its `fix/sync-flow-hardening` tip was `cd16d03` — exactly the PR head I merged — before pushing.

Fast-forward confirmed (`git merge-base --is-ancestor cd16d03 HEAD`). No `--force`. `origin/main` untouched at `605e0fd`. PR not merged.

Stopping here on post-push measurement, as instructed — no `gh pr checks` / `gh pr view` (`gh` is unauthenticated in this HOME by design).

---

## 6. For Chloé

- **The CHANGES_REQUESTED is hers to lift** — I did not touch it.
- Two of the four threads (both security-related, and the BankSyncTab duplication) are **already fixed in `cd16d03`** and can be resolved on GitHub as-is.
- The `PriceProviderPort` thread is **still live**, and cheaper than the author's deferral argued, since `supports()` is already on the port.
- One open standards question needing a decision: the `detail`-string parsing in `frontend/src/lib/errors.ts` vs the "codes are API contracts" convention.
- One stale sentence in the PR description: "sidecar **4xx**" vs the deliberate 401/403 in code.
