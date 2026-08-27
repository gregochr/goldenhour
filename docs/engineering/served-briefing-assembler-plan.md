# Served-briefing assembly boundary

Give the serve-time transformation of a briefing snapshot **one owner**. `BriefingService`
keeps cache access and refresh orchestration; a new `ServedBriefingAssembler` owns the
ordered composition that turns a stored snapshot into what a client is served.

Origin: an architecture review (2026-08-26) run against a checkout with `CLAUDE.md` and
both `AGENTS.md` removed, so the reviewer could not be led to known answers. This plan is
its first proposal, revised after an adversarial review that found the first draft's
headline benefit was overstated and two of its wiring routes failed silently.

## Why — and NOT the reason first drafted

⚠️ **This is not a "shrink the constructor" change, and must not be sold as one.** The
first draft claimed S2 would materially shrink `BriefingService`'s 26-parameter
constructor. It will not. Every parameter was classified by usage site:

| parameter | serve | build | can it leave? |
|---|---|---|---|
| `bestBetFallbackService` | ✅ only use, `applyBestBetFallback` | — | **yes** |
| `windowTideRollupBuilder` | ✅ only use, `getCachedBriefingForApi` | — | **yes** |
| `regionSnapshotService` | read, `attachMovement` | **write, `recordRegionSnapshots`** ← called from `refreshBriefing` | **no** |
| `evaluationViewService` | bulk load | default resolver (a `::` reference, easy to miss) | no |
| `clock` | ✅ | ✅ | no |
| all others | — | build path, or `getCachedBriefing` which stays | no |

**26 → 24.** The real benefit is *where changes land*: a serve-path change today must be
made inside a 1314-line orchestrator that changed 72 times in 12 months with a mean blast
radius of 8.7 Java files; afterwards it is made in one class with one job. That is what the
co-change evidence supports, and it is the only claim this plan makes.

The companions that evidence names — `DailyBriefingResponse` (13 co-changes),
`BriefingBestBetAdvisor` (13), `BriefingRegion` (10), `BriefingSlot` (9),
`BriefingHonestyFilter` (8), `BriefingGlossService` (7), `BriefingSlotBuilder` (6),
`PlanWindowProjector` (5) — are the serve representation and its policy, not infrastructure.

Already banked separately, and **not** to be counted toward this work: the dead
`briefingEvaluationService` parameter (injected `@Lazy`, assigned, never read) was deleted
on its own branch. Counting it here would flatter the result.

## Scope

**In:** the ordered composition —
`reEnrichVerdicts → applyBestBetFallback → BriefingHonestyFilter → attachMovement → tide rollup → PlanWindowProjector`.

**Out:** the region evaluation rollup (`enrichWithCachedScores`, `enrichSlot`, `rosterOf`).
Verified shared with the build path — `refreshBriefing` calls the 1-arg overload, the serve
path the 2-arg one. That is the review's Proposal 2. **Conflating the two is the main way
this goes wrong.**

Also out: `getCachedBriefing()` (cache + live aurora/hot-topic overlay). ⚠️ Moving it later
is *optional and mined*, not merely optional — it is **re-entrant**: it calls
`hotTopicAggregator.getHotTopics`, and both tide strategies hold `@Lazy BriefingService`
and call back into `getCachedDays()`. Whoever moves it inherits that cycle.

## The seam

```
ServedBriefingAssembler
  assembleWithoutPlan(snapshot)  -> reEnrich -> fallback -> honesty
  assembleForPlan(snapshot)      -> assembleWithoutPlan -> movement -> tide rollup -> projection
```

`getServedBriefing()` and `getCachedBriefingForApi()` become one-line delegations.

**The enrichment socket.** `reEnrichVerdicts` calls `enrichWithCachedScores`, which the build
path shares, so the assembler takes a narrow functional dependency rather than the logic:

```java
@FunctionalInterface
public interface BriefingScoreEnricher {
    List<BriefingDay> enrich(List<BriefingDay> days, RegionScoreResolver resolver);
}
```

Proposal 2 later swaps the implementation without touching the assembler. Creating that
socket is the main reason to do this first.

⚠️ **Visibility surgery, not "just a method reference".** `RegionScoreResolver` is a
**private nested interface** and `enrichWithCachedScores` is **private**. Both must be
promoted for the signature above to compile. Budget for it.

## Two wiring routes that fail SILENTLY — pick neither

1. **Spring bean satisfied by `BriefingService::enrichWithCachedScores`** — the method
   reference captures a `BriefingService`, so `BriefingService → assembler → BriefingService`
   is a startup cycle. ⚠️ The obvious local gate
   (`./mvnw verify -Dtest='!**/integration/**'`) **excludes exactly the tests that boot a
   Spring context**, so this passes locally and fails at deploy.
2. **`new ServedBriefingAssembler(...)` inside `BriefingService`'s constructor** —
   `minCoverageRatio` is a **field-injected `@Value`**, populated *after* the constructor
   runs. The assembler would capture `0.0`, which that field's own javadoc says **disables
   the coverage tier**. No existing test exercises the positive-but-below-threshold tier, so
   this ships green.

**Do instead:** construct the assembler in `@PostConstruct` (after field injection), or pass
`minCoverageRatio` per call rather than capturing it. Whichever is chosen, add a test that
asserts the below-threshold tier still fires — the absence of one is what makes route 2
invisible.

## S0 — pin the ordering BEFORE moving anything

The serve path encodes **six** rules, not four:

| # | rule | pinned today? |
|---|---|---|
| a | projector outermost | partly |
| b | honesty wraps fallback | ✅ `"A stale FALLBACK pick naming a blanked region is withdrawn too"` |
| c | fallback runs on the **enriched** response | ❌ **gap** |
| d | movement here, not in `getServedBriefing` | ✅ `"the panel path is the same payload without the window projection"` |
| e | rollup fed the **filtered** days | ✅ `"the tide rollup is derived from the FILTERED days"` |
| f | UTC not London | ✅ `"a window 20 minutes from happening is not past, even when served in BST"` |

Most of S0 is therefore already written, and passing those tests **unedited** is the
characterisation guarantee. Two gaps to close first:

- ⚠️ **The existing assertions are negatives.** "A blanked region has no delta" passes just
  as happily if movement is dropped **wholesale** — the exact silent-drop hazard this plan
  flags. Add positive assertions: a normal region *has* a delta; the rollup *reaches* the
  window with content.
- Rule (c) is unpinned: if re-enrichment ran after the filter, the filter would test
  build-time `scoredLocationCount`, and a region rescored to zero since build would not be
  blanked.

## Stages

Each compiles, passes the suite, and is independently revertible.

- **S1** — introduce the assembler, moving `reEnrichVerdicts`, `applyBestBetFallback` and
  `attachMovement` **verbatim, comments included**. `BriefingService` delegates.
- **S2** — delete the dead private methods; move `bestBetFallbackService` and
  `windowTideRollupBuilder` out of `BriefingService`'s constructor (the only two that can go).
- **S3** — the success check is **not** constructor arity. It is: does a serve-only change
  now touch the assembler and not `BriefingService`? Test it by writing one — add a trivial
  serve-only annotation and count the files. If `BriefingService` is still in the diff, the
  extraction moved code without moving responsibility.

## Risks

1. **The ordering comments are load-bearing documentation**, encoding six hard-won rules.
   They move **with** the code. Losing them is the worst realistic outcome.
2. **`CloseToHomeService` is the only other caller** of `getServedBriefing()` and its javadoc
   leans on "they differ by the window projection alone" — the no-movement guarantee is
   load-bearing there.
3. **The 14-arg positional rebuild** in `applyBestBetFallback` is a live hazard. Moving it
   does not fix it. Do **not** opportunistically improve it mid-move.
4. **Six test fixtures construct `BriefingService` directly** with the full argument list, so
   any constructor change edits all six. Expected, but it strains "independently revertible".
5. The new class faces the **JaCoCo 80%-per-class gate**; its `response == null` guards need
   direct assertions rather than deletion.

## Done when

- `BriefingService` no longer contains the serve composition.
- The S0 tests pass **unedited**.
- S3's file-count probe shows a serve-only change missing `BriefingService`.
- `./mvnw clean verify --batch-mode -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`
  exits 0 — **and** a context-booting test has run at least once, since that command excludes
  the wiring failure this plan's route 1 would cause.
