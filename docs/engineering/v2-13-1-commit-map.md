# v2.13.1 Visitor-Foundation Commit Map

**Read-only investigation.** No source changed. A revert dry-run was applied and immediately
aborted; the working tree was confirmed clean before and after. This document is the only file written.

Context: production regression — 432 batch evaluation calls succeeded last night, 0 scored rows
written to `forecast_evaluation`. The result-handling / rating-persistence path was relocated by the
v2.13.1 "visitor foundation" work. The deployed build is tagged **v2.11.27**.

---

## 1. Release tag → commit

| Item | Value |
|------|-------|
| Tag | `v2.11.27` (exists — lightweight) |
| Commit | `7fabb4f126f8857d87f0d4b464d6077ad0fd3960` |
| Author date | Tue Jun 2 21:32:27 2026 +0100 |
| Subject | `Merge pull request #118 from gregochr/dependabot/maven/backend/com.anthropic-anthropic-java-2.35.0` |

**Critical confirmation — the deployed build contains the visitor work.** Although the tag commit's
subject is the dependabot merge (#118), the visitor-foundation merge (#119, `1000768`) and Part B
(`b840129`) are both ancestors of `v2.11.27`:

```
git merge-base --is-ancestor 1000768 7fabb4f  → 1000768 (visitor merge) IS ancestor of v2.11.27
git merge-base --is-ancestor b840129 7fabb4f  → b840129 (Part B)        IS ancestor of v2.11.27
```

So the relocation is live in production. The version-label/tag mismatch (work described as "v2.13.1"
but the deployed git tag is "v2.11.27") is just a naming gap; the code is the same.

Nearby tags (newest first): `v2.11.27, v2.11.26, v2.11.25, v2.11.24, v2.11.23, … v2.11.2`. `v2.11.27`
is the newest tag and the one carrying the visitor work.

---

## 2. The visitor-foundation commit range

Both commits exist and are on branch `feat/v2-13-1-visitor-foundation`, in this order:

```
* b840129 feat(evaluation): v2.13.1 Part B — visitor foundation (SkyVisitor + combiner)
* 4ec6fe8 test(evaluation): v2.13.1 Part A — golden-master prompt fixtures
```

### Part A — `4ec6fe845ba206c779f7bbecabd1a9d692380716`
- Date: Sat May 30 15:52:39 2026 +0100
- Subject: `test(evaluation): v2.13.1 Part A — golden-master prompt fixtures`
- **Test-only.** Touches no production code. 6 files, +949:
  - `PromptGoldenMasterTest.java`
  - `prompt-golden/{coastal-surge,coastal-tidal,darksky-inversion,inland-landscape,woodland-bluebell}.txt`

### Part B — `b840129df5c3a55e48f69ee05978efd3f669f6a8`
- Date: Tue Jun 2 19:52:03 2026 +0100
- Subject: `feat(evaluation): v2.13.1 Part B — visitor foundation (SkyVisitor + combiner)`
- 9 files, +566 / −6:
  - **`model/SunsetEvaluation.java`** (+18) — adds `withRating(Integer)`
  - **`service/evaluation/ForecastResultHandler.java`** (+20/−6) — the result seam; routes rating through combiner
  - `service/evaluation/visitor/RatingCombiner.java` (new, +78)
  - `service/evaluation/visitor/SkyVisitor.java` (new, +47)
  - `service/evaluation/visitor/Visitor.java` (new, +72)
  - `service/evaluation/ForecastResultHandlerTest.java` (constructor update)
  - `service/evaluation/visitor/RatingCombinerTest.java` (new)
  - `service/evaluation/visitor/SkyVisitorTest.java` (new)
  - `docs/engineering/v2-13-1-foundation-notes.md` (new)

### Merge (NOT fast-forwarded)

The branch was merged via a real merge commit — a `git revert` of the merge would need `-m 1`, but
reverting the two underlying commits directly is cleaner (see §5).

| Item | Value |
|------|-------|
| Merge commit | `10007685743a39a4ec0351a6ae2d1bf26ca9c653` |
| Subject | `Merge pull request #119 from gregochr/feat/v2-13-1-visitor-foundation` |
| Date | Tue Jun 2 20:05:32 2026 +0100 |
| Parents | `5257330` (mainline) + `e908045` (branch tip — the CHANGELOG commit sitting on top of `b840129`) |

The branch tip merged was `e908045` (`docs: CHANGELOG entry for v2.13.1 visitor foundation`), which is
the child of `b840129`.

---

## 3. Files in the rating-persistence path (Part A vs Part B)

| File | Part A | Part B | Role in persist path |
|------|:------:|:------:|----------------------|
| `service/evaluation/ForecastResultHandler.java` | — | ✅ | **The result seam.** Both batch (`parseBatchResponse`) and sync (`handleSyncResult`) flow through it. |
| `model/SunsetEvaluation.java` | — | ✅ | Adds `withRating(Integer)`; the combiner result is carried into the payload here. |
| `service/evaluation/visitor/RatingCombiner.java` | — | ✅ (new) | Derives the persisted rating; **returns `null` when no applicable visitor produces a score.** |
| `service/evaluation/visitor/SkyVisitor.java` | — | ✅ (new) | `appliesTo → true`; `evaluate` returns `OptionalInt.of(rating)`, or **`OptionalInt.empty()` when `rating == null`.** |
| `service/evaluation/visitor/Visitor.java` | — | ✅ (new) | Interface. |
| `BatchResultProcessor` (`service/batch/`) | — | — | **Not modified by either commit.** It is the batch parse→persist orchestrator and was left as-is (Part B notes it stayed "green"). Worth confirming how the batch transport reaches `forecast_evaluation` — see §4 note. |

Part A touched **no** production / persist-path files (verified: `git show 4ec6fe8 -- '*ForecastResultHandler*' '*SunsetEvaluation*' '*RatingCombiner*'` → empty).

---

## 4. BEFORE vs AFTER — the rating-setting/saving hunk (prime suspect)

All of the production behaviour change is in **`ForecastResultHandler.java`** (`b840129`). The "before"
read the rating straight off the parsed Claude response (`eval.rating()`); the "after" routes it
through `ratingCombiner.combine(...)` and re-wraps the payload with `eval.withRating(...)`.

### Seam 1 — batch path (`parseBatchResponse`)

```java
//  BEFORE (pre-visitor):
SunsetEvaluation eval = parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper);
Integer safeRating = RatingValidator.validateRating(
        eval.rating(), regionName, parsed.date(), parsed.targetType(),
        location.getName(),
        outcome.model() != null ? outcome.model().name() : "UNKNOWN");

//  AFTER (b840129):
SunsetEvaluation eval = parsingStrategy.parseEvaluation(outcome.rawText(), objectMapper);
Integer combinedRating = ratingCombiner.combine(location, eval);          // <-- new indirection
Integer safeRating = RatingValidator.validateRating(
        combinedRating, regionName, parsed.date(), parsed.targetType(),    // <-- combinedRating, not eval.rating()
        location.getName(),
        outcome.model() != null ? outcome.model().name() : "UNKNOWN");
```

### Seam 2 — sync path / the `Scored` payload that `ForecastService` writes to `forecast_evaluation`

```java
//  BEFORE (pre-visitor):
Integer safeRating = RatingValidator.validateRating(
        eval.rating(), regionName, task.date(), task.targetType(),
        task.location().getName(), task.model().name());
...
return new EvaluationResult.Scored(eval);

//  AFTER (b840129):
Integer combinedRating = ratingCombiner.combine(task.location(), eval);    // <-- new indirection
Integer safeRating = RatingValidator.validateRating(
        combinedRating, regionName, task.date(), task.targetType(),        // <-- combinedRating
        task.location().getName(), task.model().name());
...
// Carry the combiner-derived rating into the payload so forecast_evaluation
// (written by ForecastService from this Scored result) also flows through the combiner.
return new EvaluationResult.Scored(eval.withRating(combinedRating));        // <-- payload re-wrapped
```

### Why this is the 432→0 suspect

The new indirection has a **null-collapsing chain** that the old direct `eval.rating()` read did not:

```
SkyVisitor.evaluate():   rating != null ? OptionalInt.of(rating) : OptionalInt.empty()
RatingCombiner.combine(): if no applied visitor produced a score → return null
ForecastResultHandler:    EvaluationResult.Scored(eval.withRating(combinedRating))
```

`SkyVisitor` is the only registered visitor, and `appliesTo → true` always. So `combine()` returns a
non-null rating **only if `eval.rating()` was already non-null** at the seam. If, on the batch
transport, the parsed `SunsetEvaluation` reaches `ForecastResultHandler` with `rating == null` (e.g.
the rating lives on a different field of the batch-parsed payload, or the batch parse populates a
different record than the sync parse), then:

- `SkyVisitor.evaluate` → `OptionalInt.empty()`
- `RatingCombiner.combine` → `null`
- `eval.withRating(null)` → a `Scored` payload whose rating is null → **no scored row persisted.**

Part A's own commit message flags exactly this null hazard (recorded as ground-fact A.0):

> *DISCREPANCY: triaged-out locations get `rating=NULL` … NOT `rating=1` … Flagged for Part B's "match today exactly" rule.*

That confirms `rating == null` is a reachable state the combiner now silently propagates. The
pre-visitor code passed `eval.rating()` directly, so whatever populated it on the batch path before
is now mediated by `combine()` — and any path where the visitor sees a null rating now yields a null
persisted rating. **This is the before/after surface; the fix/revert decision is yours — not changed here.**

> Note for the fix author: `BatchResultProcessor` (`service/batch/BatchResultProcessor.java`) is the
> batch orchestrator and was **not** touched by the visitor work. Confirm how the batch transport's
> result actually reaches `forecast_evaluation` (via `ForecastResultHandler.parseBatchResponse` →
> `BriefingEvaluationResult` vs. a `Scored` payload), since the symptom is batch-specific. The batch
> seam returns `BriefingEvaluationResult` (written via `briefingEvaluationService.writeFromBatch`),
> while only the sync seam returns `Scored(eval.withRating(...))` — worth verifying which one feeds
> the scored `forecast_evaluation` rows on the batch path.

---

## 5. Revert feasibility

**Clean.** A dry-run revert of both commits applied with no conflicts:

```
git revert --no-commit b840129 4ec6fe8     → exit 0, no conflicts
```

Staged changes produced (then aborted, tree restored clean):
- `M  model/SunsetEvaluation.java`  (removes `withRating`)
- `M  service/evaluation/ForecastResultHandler.java`  (restores `eval.rating()` direct read + removes combiner ctor arg)
- `D  visitor/{RatingCombiner,SkyVisitor,Visitor}.java`
- `M/D` the corresponding tests + golden-master fixtures + `v2-13-1-foundation-notes.md`

The dry-run was aborted (`git revert --abort`); `git status --porcelain` is empty again — **tree left exactly as found.**

**Nothing built on top of the persist-path files after Part B:**

```
git log --oneline b840129..HEAD -- \
  '*ForecastResultHandler.java' '*RatingCombiner.java' '*SunsetEvaluation.java' '*visitor/*' '*BatchResultProcessor*'
→ (empty)
```

No later commit touched any of the relocated files. Reverting `b840129` + `4ec6fe8` would restore the
pre-visitor scoring path (direct `eval.rating()` read) **without collateral**.

### Revert mechanics if you go that route
- The two commits revert cleanly **in isolation** — `git revert b840129 4ec6fe8` (in that order, newest first).
- The merge commit (`1000768`) does **not** need to be reverted; reverting the two content commits is cleaner and avoids the `-m 1` mainline-pin that re-reverting a merge later would require.
- `ForecastResultHandlerTest` is reverted too (constructor signature drops the `ratingCombiner` arg), so the test suite stays consistent.

---

## Command appendix (run, output folded into the sections above)

```bash
git tag --list 'v2.11.2*' --sort=-creatordate            # v2.11.27 newest
git rev-list -n 1 v2.11.27                                # 7fabb4f...
git show --stat -s 4ec6fe8                                # Part A (test-only)
git show --stat -s b840129                                # Part B (prod)
git log --oneline --graph 4ec6fe8~1..b840129             # B on top of A
git log --merges --oneline -n 20                          # merge 1000768 = PR #119
git merge-base --is-ancestor b840129 7fabb4f             # visitor work IS in v2.11.27
git show b840129 -- '*ForecastResultHandler.java'         # the suspect hunk
git log --oneline b840129..HEAD -- <persist files>        # empty → clean revert
git revert --no-commit b840129 4ec6fe8 && git revert --abort   # clean, aborted
```
