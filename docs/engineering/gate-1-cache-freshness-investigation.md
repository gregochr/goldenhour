# Gate 1 cache freshness investigation — per-region vs per-location

Status: investigation only. Written 2026-05-24 against `main` at `3e62a28` (post Gate 2 verdict-as-attribute merge). No production code modified.

This is the fourth and final gate-redesign investigation, sibling to [gating-architecture-investigation.md](gating-architecture-investigation.md), [gate-2-redesign-investigation.md](gate-2-redesign-investigation.md), and the Gate 4 work shipped in PR #100. Section 4 of the parent investigation mapped Gate 1 and recommended per-location granularity. This document is the deeper trace before any redesign — because cache changes touch correctness (stale ratings shown to users, or wasted Claude spend), and the schema does not support per-location freshness out of the box.

---

## TL;DR

- **Per-location freshness cannot be implemented by "walking `results_json` and checking each location's timestamp."** There are no per-location timestamps. The only timestamp on the cache is region-level (`CachedEvaluation.evaluatedAt`, persisted as `cached_evaluation.evaluated_at`). Any per-location design has to add a timestamp dimension to the data model — either a column on a denormalised per-location row, or a field inside each `BriefingEvaluationResult` in `results_json`. This is the trap the prompt warned about.
- **The "results_json holds only rating ≥ 3" framing in the parent doc is empirical, not enforced.** No code-level filter rejects sub-3 ratings before persistence. The pattern arose because the *old* Gate 2 filtered STANDDOWN at briefing-build time and Gate 3 (triage) further screened obvious washouts — so what reached Claude was a pre-filtered slate that typically rated 3+. **After the Gate 2 verdict-as-attribute shipped on 2026-05-23 (commits `3b70066` and `a7b1d0b`), Gate 2 only filters TIDE_MISMATCH.** Low-rated slots will increasingly land in `results_json` going forward — the "≥ 3" assumption is already partly stale and will fully decay over the next few cycles. Any Section 5 reasoning that depends on it (notably the sparse-coverage filter framing in the parent doc) needs revisiting in a separate pass.
- **Replace-on-write is correct for the current architecture and would be a data-loss bug under per-location freshness.** Today, a whole region's slate of GO/MARGINAL+passed-Gate-3 results lands in one `writeFromBatch` call per cycle. Under per-location freshness, multiple writes per cycle for the same `cacheKey` (one batch handling 3 stale locations, the cached 7 fresh ones expected to remain) — the current replace would lose those 7. The redesign would have to merge.
- **TRANSITIONAL freshness threshold (12 h) is dangerously close to the cycle interval (12 h, 03:00/15:00 UTC).** A `cached_evaluation` row written at the end of cycle N is just barely too fresh for cycle N+1's freshness check, depending on millisecond timing. Borderline flap behaviour. UNSETTLED (4 h) clears in time; SETTLED (36 h) blocks 2-3 cycles intentionally. This is a calibration issue independent of granularity.
- **No production data access from this dev machine.** Per CLAUDE.md and the same constraint that limited the Gate 2 investigation: production runs in Docker on a separate host. The dev logs available locally (`backend/logs/`) are dominated by single-location test runs of "Durham UK" — they show the code paths but not real cycle-over-cycle staleness behaviour. Where this doc cites concrete numbers, they come from structural code analysis. Where production data is needed, the queries to run are listed.

What surprised me most: the cache architecture is intentionally region-level all the way down — there is no per-location timestamp anywhere in the system. `BriefingEvaluationResult` (the per-location record inside `results_json`) has no `evaluatedAt` field. The only per-location timestamps live in `forecast_evaluation.forecast_run_at`, but that table is written by the SSE path and by triage rows from the batch path — *not* by scored batch results. So even a "look up the per-location timestamp from forecast_evaluation" fallback doesn't work for the dominant case (scored batch evaluations). The redesign has to add a dimension to the data model, not just change a query.

The honest read: Gate 1 *probably* doesn't need fixing right now. The staleness it permits is bounded (≤ threshold hours), the dominant batch run (T+1) gets a 12 h threshold that aligns with the 12 h cycle, and the just-shipped Gate 2 change will materially shift the cache's contents over the next week — better to observe one of those weeks before redesigning the cache around the old assumptions.

---

## Investigation constraints

Three constraints on the data this document can cite, mirroring the same constraints from the Gate 2 investigation:

1. **No production access.** Per CLAUDE.md: "Production runs in Docker on a separate host machine — NOT on the local dev Mac." Local logs are dominated by single-location dev tests of "Durham UK" running against a development H2 database. Where this doc relies on real cycle-over-cycle behaviour, it lists the production queries to run.
2. **Gate 2 just shifted the cache's content profile.** Commits `3b70066` and `a7b1d0b` shipped on 2026-05-23 (yesterday) — Gate 2 now only filters TIDE_MISMATCH STANDDOWNs. The "results_json holds rating ≥ 3" pattern documented by the parent investigation was *empirical*, not enforced, and was a side-effect of the old Gate 2 + Gate 3 combined screening. The next ~7 days of production data will reveal what the post-Gate-2 cache looks like; any Gate 1 redesign should wait for that observation window.
3. **The `evaluation_delta_log` table exists but I cannot read production rows.** V97 (just landed) added a table that captures *exactly* the data needed to assess whether the current freshness thresholds are causing material staleness — the `rating_delta` column tells you, for each cache refresh, how much the rating moved across the freshness window. Section 3 explains how to use it but cannot run the query.

---

## Section 1 — Cache key + freshness logic map

### 1.1 Where the freshness check fires

Single site — the batch candidate loop:

[`ForecastTaskCollector.collectForecastCandidates:520-539`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java):

```java
for (BriefingEventSummary eventSummary : day.eventSummaries()) {
    TargetType targetType = eventSummary.targetType();
    for (BriefingRegion region : eventSummary.regions()) {
        String cacheKey = CacheKeyFactory.build(
                region.regionName(), date, targetType);
        ForecastStability regionStability = mostVolatileStability(
                region, stabilityByLocation);
        Duration freshness = freshnessResolver.maxAgeFor(regionStability);
        int regionSlots = region.slots() != null ? region.slots().size() : 0;
        eligibleByStability.get(regionStability)[0] += regionSlots;
        if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
            LOG.warn("[BATCH DIAG] SKIP region {} | reason=CACHED "
                            + "(stability={}, threshold={}h, {} slots skipped)",
                    cacheKey, regionStability,
                    freshness.toHours(), regionSlots);
            cachedByStability.get(regionStability)[0] += regionSlots;
            skippedCache += regionSlots;
            totalSlots += regionSlots;
            continue;  // entire region for this date+event skipped
        }
        for (BriefingSlot slot : region.slots()) { ... }
    }
}
```

That `continue` is Gate 1's blast radius — it skips every slot in the region for that (date, eventType). No other site reads `hasFreshEvaluation`.

### 1.2 Freshness threshold computation

`mostVolatileStability` ([:782-800](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java)) walks the region's slots, looks up each location's grid-cell stability, and returns the *most volatile* one (UNSETTLED > TRANSITIONAL > SETTLED). The threshold is then resolved per stability:

[`FreshnessResolver.maxAgeFor:58-66`](../../backend/src/main/java/com/gregochr/goldenhour/service/FreshnessResolver.java):

```java
public Duration maxAgeFor(ForecastStability level) {
    Duration base = switch (level) {
        case SETTLED -> Duration.ofHours(props.getSettledHours());
        case TRANSITIONAL -> Duration.ofHours(props.getTransitionalHours());
        case UNSETTLED -> Duration.ofHours(props.getUnsettledHours());
    };
    Duration floor = Duration.ofHours(props.getSafetyFloorHours());
    return base.compareTo(floor) < 0 ? floor : base;
}
```

Thresholds from [`FreshnessProperties`](../../backend/src/main/java/com/gregochr/goldenhour/config/FreshnessProperties.java) + [`application-example.yml:129-132`](../../backend/src/main/resources/application-example.yml):

| Stability | Threshold | Rationale per source |
|-----------|-----------|----------------------|
| SETTLED | 36 h | Blocking highs persist 4-5+ days; once-daily refresh rhythm with headroom |
| TRANSITIONAL | 12 h | Half a synoptic update cycle; 2× the NWS 6 h operational forecast cadence |
| UNSETTLED | 4 h | Outer edge of the nowcasting regime where cloud advection becomes unreliable |
| Safety floor | 2 h | Absolute minimum regardless of stability — prevents successive trigger thrash |

Production config (`backend/src/main/resources/application-prod.yml`) has no `photocast.freshness` override, so prod runs the defaults above. The TRANSITIONAL=12 h threshold exactly matches the 12 h batch cycle interval (03:00 + 15:00 UTC, [V73__forecast_batch.sql:20-24](../../backend/src/main/resources/db/migration/V73__forecast_batch.sql)). That alignment is a calibration concern in its own right — see Section 4 option C.

A region with even one UNSETTLED slot gets the UNSETTLED (4 h) threshold for the whole region — `mostVolatileStability` is strict-most-volatile. So a region containing 19 SETTLED locations and 1 UNSETTLED one is governed by the UNSETTLED window. This is conservative and probably what you want, but worth noting because it means SETTLED's 36 h threshold rarely applies in practice in unsettled weather weeks.

### 1.3 Cache key format

[`CacheKeyFactory.build:39-48`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/CacheKeyFactory.java) constructs keys as `regionName|date|targetType`, e.g. `"North East|2026-05-24|SUNRISE"`. The separator `|` is validated to be absent from `regionName` — silent failure mode eliminated by the factory.

Used at exactly five sites in the codebase (per `grep -rn "CacheKeyFactory.build"`):

| Site | Purpose |
|------|---------|
| [`BriefingEvaluationService.evaluateRegion:149`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | SSE drill-down lookup |
| [`BriefingEvaluationService.getCachedScores:264`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | View-time read |
| [`BriefingEvaluationService.getCachedEvaluatedAt:278`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | View-time read |
| [`ForecastTaskCollector.collectForecastCandidates:523-524`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | Gate 1 fresh-check |
| [`ForecastResultHandler.parseBatchResponse:134` + `:214`](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java) | Batch result group-by + sync write |

### 1.4 Read path

In-memory primary, DB durability:

- **In-memory** — [`BriefingEvaluationService.cache`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) at `:83`: `ConcurrentHashMap<String, CachedEvaluation>` where the value is `CachedEvaluation(ConcurrentHashMap<String, BriefingEvaluationResult> results, Instant evaluatedAt)` — defined at `:88-92`.
- **DB** — [`cached_evaluation`](../../backend/src/main/resources/db/migration/V91__create_cached_evaluation_table.sql) table, one row per `cache_key`. `results_json` stores a JSON array of `BriefingEvaluationResult` records.
- **Rehydration** — [`BriefingEvaluationService.rehydrateCacheOnStartup:471-519`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) runs on `ApplicationReadyEvent` and reloads today + future entries into memory. Past dates are not rehydrated (assumed stale).

The freshness check [`hasFreshEvaluation:309-315`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) only reads in-memory — never the DB:

```java
public boolean hasFreshEvaluation(String cacheKey, Duration maxAge) {
    CachedEvaluation cached = cache.get(cacheKey);
    if (cached == null || cached.results().isEmpty()) {
        return false;
    }
    return cached.evaluatedAt().isAfter(Instant.now().minus(maxAge));
}
```

`isAfter` is strict — exactly at `maxAge` returns false (stale). So a 12 h entry hits Gate 1 as stale; an 11h59m59s entry passes as fresh.

### 1.5 Write path

Two writers:

1. **`BriefingEvaluationService.writeFromBatch:327-336`** — invoked once per cache key by `BatchResultProcessor` after streaming all results for a batch. Replaces the in-memory entry and upserts the DB row. Also calls `logEvaluationDeltas` to populate the `evaluation_delta_log` table (Section 3).
2. **`BriefingEvaluationService.evaluateRegion:218-240`** — SSE drill-down. Writes per-location to the in-memory cache (within the loop) and persists at the end. Same `persistToDb` call.

Both flow through [`persistToDb:543-580`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) which serialises the whole results map as `resultsJson` and upserts the row keyed by `cacheKey`. The DB write is best-effort — failures log a WARN and never block the live path.

### 1.6 The 12 h cycle + 12 h TRANSITIONAL threshold alignment

The batch fires at 03:00 and 15:00 UTC. A region with TRANSITIONAL stability has its cache entry expire at 12 h exactly. If cycle N writes at 03:01:15 UTC, cycle N+1 fires at 15:00:00 UTC — that's 11h58m45s later, just inside the 12 h window. Gate 1 fires CACHED, region skipped. The next cycle (cycle N+2 at 03:00:00) is 23h58m45s after N's write — clearly stale.

If cycle N writes at 03:14:00 UTC (e.g. slow batch processing), cycle N+1 at 15:00:00 is 11h46m later — still inside the window. Cycle N+2 at 03:00:00 is 23h46m later — stale.

So in steady state, TRANSITIONAL regions get re-evaluated every *other* cycle, not every cycle. That's a noisy data point — the design probably intended every-cycle for TRANSITIONAL. Or it intended every-other-cycle, in which case the threshold should be slightly larger than 12 h to avoid timing flap. Either way, 12 h-on-12 h is not a stable equilibrium.

---

## Section 2 — Replace-vs-merge write behaviour

### 2.1 Confirmed: writes are full replace

[`BriefingEvaluationService.writeFromBatch:327-336`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java):

```java
public void writeFromBatch(String cacheKey,
        List<BriefingEvaluationResult> results) {
    CachedEvaluation prior = cache.get(cacheKey);
    ConcurrentHashMap<String, BriefingEvaluationResult> resultMap = new ConcurrentHashMap<>();
    results.forEach(r -> resultMap.put(r.locationName(), r));
    Instant now = Instant.now();
    cache.put(cacheKey, new CachedEvaluation(resultMap, now));
    persistToDb(cacheKey, resultMap, "BATCH");
    logEvaluationDeltas(cacheKey, prior, resultMap, now);
}
```

The `cache.put` wholesale replaces the prior entry — no merge. `persistToDb` serialises `resultMap` (the new map only) and upserts the DB row. The DB upsert overwrites `results_json` with whatever is in the new map.

Worth flagging: the SSE path is interestingly different. [`BriefingEvaluationService.evaluateRegion:200-222`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) does merge — it seeds the working map with the prior cache's entries (line 201-203) so partial batch results survive being supplemented by SSE. The two paths' write semantics diverge: SSE merges on top of prior batch, batch replaces wholesale.

### 2.2 The batch path only writes once per (cycle × cacheKey)

`BatchResultProcessor` ([:264-289](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java)) groups successful results by `cacheKey` into a single map and then iterates the map calling `flushCacheKey` (→ `writeFromBatch`) once per key:

```java
for (Map.Entry<String, List<BriefingEvaluationResult>> entry : byKey.entrySet()) {
    forecastResultHandler.flushCacheKey(entry.getKey(), entry.getValue());
}
```

So within a single batch's result-processing loop, exactly one `writeFromBatch` call lands per `cacheKey`. The "5 of 15 locations" scenario from the parent doc would be: batch evaluates 5 (those that survived Gate 2 + Gate 3) → 5 results stream back → grouped under the same cacheKey → one `writeFromBatch` with a list of 5.

### 2.3 What `results_json` actually contains today (post-Gate-2 redesign)

The parent investigation's claim that `results_json holds only rating ≥ 3 locations` was an *empirical* observation, not an enforced rule. **No code-level filter** rejects sub-3 ratings before persistence:

- `parseBatchResponse` ([ForecastResultHandler.java:148-151](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java)) constructs `BriefingEvaluationResult` with whatever rating Claude returned (after `RatingValidator` clamps null and out-of-range).
- `writeFromBatch` writes the full list.
- `persistToDb` serialises the full list.

The "≥ 3" pattern arose because the *old* Gate 2 STANDDOWN filter at briefing-build time + Gate 3 triage screened obvious washouts before they reached Claude. What remained had usable canvas, so Claude typically rated 3+.

After commits `3b70066` and `a7b1d0b` (2026-05-23), Gate 2 only filters TIDE_MISMATCH. Slots that would previously have been STANDDOWN'd for heavy cloud, precipitation, low visibility, mid-cloud overcast, etc. now reach Claude. Claude's prompt rules ([PromptBuilder.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)) explicitly cap rating ≤ 3 for "no canvas" cases and will rate 1-2 for genuinely blanketed slots. **So `results_json` will increasingly contain 1- and 2-rated entries.** The "≥ 3" assumption is already partly stale and will fully decay over the next ~7 days as the cache turns over.

This matters for Gate 1 because: any Gate 1 redesign reasoning that depends on "low array length means few decent locations" (the parent doc's sparse-coverage framing at [gating-architecture-investigation.md:618](gating-architecture-investigation.md)) needs to be revisited. After the Gate 2 change, a long `results_json` array can contain mostly 1-2★ slots, and "low array length" means "few locations the *batch processed at all*" — closer to what the user would actually expect.

### 2.4 The "5 evaluated, 10 not — is data lost?" question

Cycle N: region has 15 GO/MARGINAL slots → 10 Gate-3-triaged, 5 evaluated by Claude → cache holds 5 results.

What happens to the 10 triaged slots?

- **Batch path Gate 3 triage** writes a row to `forecast_evaluation` with `triage_reason` populated and `rating` null, via [`ForecastService.fetchWeatherAndTriage:362-381`](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java) (line 373 `repository.save(entity)`). So the 10 are persisted — just not in `cached_evaluation`.
- They're not lost; they're just in a different table.
- `EvaluationViewService.getScoresForEnrichment` ([:213-247](../../backend/src/main/java/com/gregochr/goldenhour/service/EvaluationViewService.java)) merges `cached_evaluation` first, then falls back to `forecast_evaluation` for locations not in cache — so the Plan tab UI sees both.

So in cycle N's terms, no data is lost. The 5 are in `cached_evaluation`; the 10 triage rows are in `forecast_evaluation`. The UI's merge layer surfaces both correctly.

The actual concern is **cycle N+1**: Gate 1 sees the region as cache-fresh (5 results, age 0 h) and skips the *entire* region — the 10 Gate-3-triaged slots don't get re-evaluated. If the weather has improved for any of them since N, they'd survive triage now and could earn a Claude rating, but the cache freshness gate denies them entry. They stay STANDDOWN/triage-only until the cache expires.

This is the granularity concern. It is real, but its severity depends on:

1. **How often Gate 3 triages slots that *would* have survived re-triage 12 h later** — this is the question Section 3 cannot answer from this machine.
2. **How much the Claude rating would actually move for those slots** — `evaluation_delta_log` can answer this in production.

### 2.5 Replace-on-write becomes a bug under per-location freshness

If Gate 1 moves to per-location ("re-evaluate location X because its individual freshness expired, leave locations Y/Z alone because they're fresh"), then a batch processing only X will produce one result. The current `writeFromBatch(cacheKey, [resultX])` would wipe Y and Z from the cache entry, even though Y and Z are still meant to be considered fresh. **That is the data-loss scenario the prompt warns about.**

The redesign for per-location freshness therefore has *two* coupled changes:

- Per-location freshness check (the granularity change), AND
- Merge-on-write in `writeFromBatch` (the data-preservation prerequisite).

Doing the first without the second is worse than the status quo — it produces incomplete cache entries that look fresh.

---

## Section 3 — Is staleness actually happening?

### 3.1 What I cannot answer from this machine

Per CLAUDE.md (Deployment section): "Production runs in Docker on a separate host machine — NOT on the local dev Mac. Cannot access production logs or DB directly from the dev machine." The dev logs in `backend/logs/` are dominated by single-location "Durham UK" test runs against a development H2 database. They show the code paths firing but not real cycle-over-cycle behaviour across 7 regions × ~140 locations.

The three questions in scope for Section 3, and the production queries that would answer each:

### 3.2 Question: How often is a region cache-skipped?

The `[BATCH DIAG] SKIP region {cacheKey} | reason=CACHED (stability={S}, threshold={N}h, {K} slots skipped)` log line at [`ForecastTaskCollector.java:531-534`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) carries everything you need.

Production query (against `/Users/gregochr/goldenhour-data/logs/goldenhour.log` on the prod host):

```bash
# CACHED-skip distribution by stability, trailing 7 days
grep "[BATCH DIAG] SKIP region.*reason=CACHED" /app/logs/goldenhour.log \
  | awk -F'stability=' '{print $2}' | awk -F',' '{print $1}' \
  | sort | uniq -c | sort -rn
```

What to look for:

- **High UNSETTLED skip count** would be surprising (4 h threshold; cycles are 12 h apart, so UNSETTLED entries should always be stale by next cycle). If non-zero, points to ad-hoc SSE writes refreshing the cache between batch fires.
- **High TRANSITIONAL skip count** would confirm the 12 h-on-12 h timing flap from Section 1.6.
- **High SETTLED skip count** is expected (36 h > 12 h cycle → SETTLED regions skip 2-3 cycles intentionally). This is by design.

The cross-tab `[BATCH DIAG] Candidate breakdown by stability: {STABILITY}: {refreshed} of {eligible} ({pct}% refreshed, threshold {N}h)` line at [`logStabilityBreakdown:739-758`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) gives the % refresh rate per cycle. A consistently low TRANSITIONAL refresh rate (< 30%) would indicate Gate 1 is doing most of the skipping.

### 3.3 Question: How old is the cached data when it's served?

The age at skip-time is implicit in the threshold check but not logged. To get the actual age, two options:

**Option A** — DB query against `cached_evaluation` at any point:

```sql
SELECT
  cache_key, region_name, target_type, source,
  EXTRACT(EPOCH FROM (NOW() - evaluated_at)) / 3600 AS age_hours
FROM cached_evaluation
WHERE evaluation_date >= CURRENT_DATE
ORDER BY age_hours DESC;
```

The `MAX(age_hours)` by `target_type` tells you how stale the served data ever gets at the moment of the snapshot. Run this snapshot at the moment just before a cycle fires (e.g. 02:59 UTC) to see the worst-case age users see.

**Option B** — widen the `[BATCH DIAG] SKIP region` log line to include the age. One-line change at [`ForecastTaskCollector.java:531`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) — read `briefingEvaluationService.getCachedEvaluatedAt(...)` and format the delta. Out of scope for this investigation but worth tracking.

### 3.4 Question: Is the rating actually different when re-evaluated?

This is the question that determines whether staleness *matters*. The `evaluation_delta_log` table (V97) captures exactly this:

```sql
-- Distribution of rating deltas when cache entries are refreshed,
-- by stability level and age-at-refresh
SELECT
  stability_level,
  width_bucket(age_hours, 0, 48, 8) AS age_bucket,  -- 6h buckets to 48h
  COUNT(*) AS refresh_count,
  AVG(rating_delta) AS mean_delta,
  PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY rating_delta) AS p95_delta,
  COUNT(*) FILTER (WHERE rating_delta >= 1) AS one_star_or_more,
  COUNT(*) FILTER (WHERE rating_delta >= 2) AS two_star_or_more
FROM evaluation_delta_log
WHERE logged_at >= NOW() - INTERVAL '7 days'
  AND old_rating IS NOT NULL
  AND new_rating IS NOT NULL
GROUP BY stability_level, age_bucket
ORDER BY stability_level, age_bucket;
```

What to look for:

- **Mean delta < 0.5★ across all buckets** → the cache is essentially right; staleness is theoretical, not material. Recommend Option A (status quo).
- **Mean delta 0.5-1★** → moderate staleness; threshold recalibration (Option C) likely helps without per-location complexity.
- **Mean delta ≥ 1★ at TRANSITIONAL 12 h bucket** → real staleness; either tighten TRANSITIONAL threshold or move to per-location (Options B / C).
- **p95 delta ≥ 2★ at any bucket** → users sometimes see ratings that are wildly wrong; the gate is hurting product trust. Strong case for redesign.

Caveat: `evaluation_delta_log` only captures refreshes that *actually happened*. It does not capture the "would have changed but never re-evaluated" case (a location that stayed cache-fresh through cycles where it should have refreshed). To detect that, you'd need to force re-evaluation of cache-fresh slots periodically and log the deltas — out of scope for current code.

### 3.5 What the local dev logs do show

For completeness — the dev environment's logs show ~41 `reason=CACHED` skip lines on 2026-05-21 across many short test cycles, all for a single location ("Durham UK") in the "North East" region with various overridden stability classifications. Most are at the local config's 6 h UNSETTLED threshold (which suggests the local `application.yml` overrides the example default of 4 h — see Section 1.6 cycle-flap analysis with this in mind for local testing). Not representative for production assessment.

---

## Section 4 — Granularity options

Three options. Refined by the Section 5 findings (where poor-rated locations live and what `results_json` actually contains post-Gate-2).

### Option A — Keep per-region (status quo)

**What changes**: nothing.

**Effort**: 0 days.

**Cost impact**: 0.

**Staleness reduction**: 0. Accepts that a region cache-fresh by even a single recent evaluation blocks the whole region from re-evaluation until the threshold elapses.

**Bounded harm**: a region is at most `threshold` hours stale (4 / 12 / 36 by stability). The dominant batch run today is T+1 (per [V92__batch_near_far_term_model_selection.sql](../../backend/src/main/resources/db/migration/V92__batch_near_far_term_model_selection.sql) the near-term tier covers T+0/T+1 with Sonnet). For T+1, "12 h stale" is the worst case under TRANSITIONAL and aligns with the 12 h cycle — users see at most one stale cycle's worth of data.

**Risk**: low. The harm is bounded and probably small for most cases. The Section 1.6 timing flap (TRANSITIONAL 12 h-on-12 h) is the main calibration issue.

**Honest read**: if Section 3's `evaluation_delta_log` analysis shows mean rating delta < 0.5★ across all stability bands, this is the right answer.

### Option B — Per-location freshness

**What changes**: rip up the per-region freshness check. Walk each region's slots, look up each location's individual evaluation timestamp, partition into fresh + stale, batch only the stale.

**Required data model change**: add per-location evaluation timestamps. Three sub-options:

- **B1 — extend `BriefingEvaluationResult`** with an `evaluatedAt: Instant` field. `results_json` array entries now carry their own timestamps. Backward-compatible JSON read (default null for legacy entries). Section 2.5's merge-on-write becomes a hard requirement — without it, partial re-evaluations lose the unchanged entries.
- **B2 — denormalise to a per-location row**: new table `cached_evaluation_location(cache_key, location_name, result_json, evaluated_at)`. One row per `(region, date, event, location)`. Simpler reasoning, larger schema change, breaks `results_json` semantics that downstream consumers rely on (the JSON shape is read by `EvaluationViewService`, the SSE re-emit path, the briefing enrichment path).
- **B3 — read per-location timestamps from `forecast_evaluation`**: but `forecast_evaluation` doesn't have scored batch entries (Section 2.4) — only triage rows from batch + SSE results — so this doesn't work for the dominant case.

**Effort**: ~3-5 days for B1 (smaller change; touches `BriefingEvaluationResult`, `writeFromBatch`, the candidate loop, the persistence path, and tests). ~5-7 days for B2 (schema change, two writers, two readers, full migration + back-compat).

**Cost impact**: +5-15% Claude evaluations (more re-evaluations as individual locations expire). Concentrated at the Sonnet near-term tier where each call is ~10× the unit cost of Haiku. At today's ~140 evaluations/cycle baseline, +10% = +14 calls/cycle × 2 cycles/day × ~$0.01 Sonnet = +$0.28/day = ~$8/month. Small in absolute terms.

**Staleness reduction**: significant. Eliminates the all-or-nothing "one fresh location locks the region" pattern. Also eliminates the "Gate-3-triaged-in-cycle-N never re-tries until threshold expires" issue.

**Risk**: medium-high. Cache write semantics change (replace → merge). The merge logic has to handle: result for a location that was previously triaged, result for a location not in prior cache, deletion semantics if a location is removed from the region's slot list (does its cache entry persist?). The current single-`writeFromBatch`-per-cacheKey-per-cycle pattern breaks — now multiple writes can land per cycle as different locations are processed. Concurrency on the in-memory `ConcurrentHashMap` becomes a real consideration (it's only safe today because writes are serialised at the per-cacheKey level by `BatchResultProcessor`).

**Section 5 wrinkle**: the per-location freshness check can read `BriefingEvaluationResult.evaluatedAt` (option B1) only for locations *in* `results_json`. For locations Gate-3-triaged in the last cycle (and therefore *not* in `results_json` but persisted in `forecast_evaluation`), the freshness lookup has to fall through to `forecast_evaluation.forecast_run_at`. That's a real cross-table dependency the redesign has to model.

### Option C — Hybrid / shorter region threshold

**What changes**: keep per-region granularity but tune the threshold by days-ahead, not (or in addition to) by stability:

- T+0: 4 h regardless of stability
- T+1: 8 h regardless of stability (under the 12 h cycle, so re-evaluates every cycle)
- T+2: 12 h, eligible if stability ∈ {SETTLED, TRANSITIONAL}
- T+3: 24 h, SETTLED only

**Effort**: ~1-2 days. Change `hasFreshEvaluation` signature to accept days-ahead + stability. Update the candidate loop to pass days-ahead per slot. Update tests.

**Cost impact**: roughly +20-30% Claude evaluations because T+1 now always re-evaluates (the 12 h-on-12 h timing flap is replaced by 8 h-on-12 h, guaranteed cycle expiry). Concentrated at Sonnet near-term. At ~140/cycle × 25% × 2 cycles × $0.01 Sonnet = ~$0.70/day = ~$21/month.

**Staleness reduction**: meaningful for T+0/T+1 (the user-action horizon — "what should I do tomorrow morning"). Less change for T+2/T+3 (where staleness matters less because users don't act on it tonight).

**Risk**: low. No structural changes to the cache; only the threshold function gains a days-ahead parameter. Replace-on-write semantics remain valid because every cycle still writes the region's full evaluated slate.

**Trade-off vs B**: Option C accepts that within a region for a given days-ahead, all locations refresh together. The trapped-by-one-fresh-location problem persists, but the threshold is tight enough that it's a smaller window. Option B fully eliminates it at the cost of model complexity.

### Comparison table

| | A (status quo) | B (per-location) | C (tier by days-ahead) |
|---|---|---|---|
| Effort | 0 days | 3-7 days | 1-2 days |
| Schema change | none | yes (B1 minor / B2 major) | none |
| Cache write semantic | replace (correct) | merge (required) | replace (correct) |
| Cost impact | 0 | +$8/month | +$21/month |
| Staleness reduction | 0 | high | medium |
| Risk | low | medium-high | low |
| Addresses 12h-on-12h flap | no | yes | yes |
| Addresses Gate-3-trapped-slot | no | yes | partly (smaller window) |
| Requires Gate 2 reset to settle | no | yes — depends on post-Gate-2 cache shape | no |

### Sequencing note

Option B's design depends on knowing what the post-Gate-2 cache looks like. The "≥ 3" empirical pattern is decaying *right now* (since 2026-05-23). Implementing B before observing 7-14 days of post-Gate-2 production data risks designing for the wrong cache shape (Section 5 trap).

Option C is safe to implement immediately; it doesn't depend on cache content profile.

Option A is implicit if neither B nor C is done.

---

## Section 5 — Where do poor-rated locations live?

This is the trap. A "walk results_json and check each location's timestamp" implementation of Option B looks tractable until you trace what's *not* in `results_json`.

### 5.1 BATCH path persistence

Tracing the data flow for batch-evaluated slots:

- **GO/MARGINAL slots that survive Gate 2 + Gate 3** are submitted to the Anthropic Batch API. Results stream back via `BatchResultProcessor` → `parseBatchResponse` → `flushCacheKey` → `writeFromBatch` → `cached_evaluation.results_json`. **No write to `forecast_evaluation`.**
- **Slots that Gate 3 triages** never get submitted to Claude. The triage logic in [`ForecastService.fetchWeatherAndTriage:362-381`](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java) DOES write to `forecast_evaluation` with `triage_reason` populated and `rating` null. So triaged batch slots are persisted *somewhere*, just not in `cached_evaluation`.

### 5.2 SSE path persistence

[`BriefingEvaluationService.evaluateSingleLocation:582-607`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java):

- **Triaged SSE slots**: builds a `BriefingEvaluationResult` with `rating=null`, `triageReason` and `triageMessage` populated. Persisted to `cached_evaluation.results_json` via the standard path. Also implicitly persists to `forecast_evaluation` via the triage branch in `fetchWeatherAndTriage` (same code path as batch).
- **Scored SSE slots**: calls `forecastService.evaluateAndPersist` → writes to `forecast_evaluation`, returns the entity → also persisted to `cached_evaluation.results_json`.

So SSE writes to both tables. Batch only writes scored results to `cached_evaluation`; triage rows go to `forecast_evaluation`.

### 5.3 The cross-table picture

| Source | Outcome | `cached_evaluation.results_json` | `forecast_evaluation` |
|--------|---------|----------------------------------|------------------------|
| Batch — survived Gate 2 + Gate 3, scored | rating 1-5 | yes (region-level row) | no |
| Batch — Gate 3 triaged | rating null, triage_reason | no | yes (per-location row) |
| Batch — Gate 2 TIDE_MISMATCH | not submitted at all | no | no (Gate 2 fires before any persistence) |
| SSE — survived triage, scored | rating 1-5 | yes (region-level row) | yes (per-location row) |
| SSE — triaged | rating null, triage_reason | yes (region-level row) | yes (per-location row) |
| Legacy admin path | rating 1-5 | no (WriteTarget.NONE) | yes (per-location row) |

Two important consequences:

**A.** A scored batch result has its timestamp only at the region level (`cached_evaluation.evaluated_at`). There is no per-location timestamp anywhere for the dominant case (batch-scored slots). The Section 4 Option B1 design (extend `BriefingEvaluationResult` with `evaluatedAt`) is therefore *required* for per-location freshness — there is no "read from forecast_evaluation as fallback" that works for the most common case.

**B.** A Gate-3-triaged batch slot has its timestamp only in `forecast_evaluation.forecast_run_at`. A per-location freshness check that only reads `cached_evaluation` would treat that slot as "never evaluated" and re-batch it every cycle — even if it was Gate-3-triaged 5 minutes ago. That's wasteful Open-Meteo prefetch + Gate 3 re-check work. To avoid it, the per-location freshness check has to read both tables.

### 5.4 Implications for Option B design

Option B is materially more complex than the parent doc framed it:

1. The `BriefingEvaluationResult` records inside `results_json` need an `evaluatedAt` field added (or the schema needs denormalising — B2). Both are non-trivial.
2. The merge-on-write semantic is mandatory (Section 2.5), not optional.
3. The per-location freshness check has to query both `cached_evaluation` (for scored entries) and `forecast_evaluation` (for triage entries), unless we accept that triage-recent slots get re-evaluated unnecessarily. Querying both is a non-trivial join across two tables on every Gate 1 firing — the current in-memory ConcurrentHashMap read is O(1).
4. Concurrency: today, write-per-cacheKey-per-cycle is single-threaded by virtue of `BatchResultProcessor` looping serially. Per-location freshness implies multiple writes per cycle for the same cacheKey — the in-memory map's atomicity guarantees need to be re-examined (it's a `ConcurrentHashMap`, so individual `put`s are safe, but the read-merge-write sequence in a redesigned `writeFromBatch` is not atomic without an explicit lock).

Realistic effort for a careful B1 implementation: **5-7 days**, not the 2-3 days the parent doc estimated. The data model change alone (adding `evaluatedAt` to `BriefingEvaluationResult`, migrating existing JSON for back-compat, threading the field through the merge logic, the SSE re-emit path, the briefing enrichment, the EvaluationViewService merge layer) is 2-3 days; the freshness-check redesign + dual-table read is another 2-3 days; tests + verification on local + careful deploy is 1-2 days.

### 5.5 The "≥ 3" framing — already partly stale

The parent investigation's sparse-coverage analysis ([gating-architecture-investigation.md:618](gating-architecture-investigation.md)) wrote off a sparse-coverage honesty filter on the basis that "results_json holds only rating ≥ 3 locations, so a low array length means 'few decent locations,' not 'few evaluated locations.'" That conclusion was sound at the time but is decaying *now* because of the 2026-05-23 Gate 2 redesign:

- **Pre-Gate-2**: STANDDOWN slots filtered at briefing build → never submitted to batch → never in `results_json`. Triage screened remaining washouts. What Claude saw was a pre-filtered slate that empirically rated 3+.
- **Post-Gate-2**: only TIDE_MISMATCH STANDDOWN gates. Heavy-cloud, rainy, low-visibility slots now reach the batch and get scored. Claude's prompt rules cap such cases at ≤ 3 stars and will rate 1-2 for genuine blanketed conditions.

So `results_json` will increasingly contain 1- and 2-rated entries over the next ~7 days as the cache turns over. The sparse-coverage filter discussion in the parent doc should be revisited as a separate item.

For Gate 1 specifically, this matters because: any Option B design that uses "array length" or "presence of decent locations" as a proxy for freshness is implicitly working from the old assumption. Any redesign should use real timestamps, not array length.

---

## Section 6 — Recommendation framing

Three end-state options. Refined by the Section 5 findings.

### Option A — Keep per-region (status quo)

**What changes**: nothing.

**Cost impact**: 0.

**Effort**: 0 days.

**Risk**: low.

**Worth doing?** Maybe yes — this is the option I'd recommend if Section 3's `evaluation_delta_log` analysis shows the mean rating delta across the freshness window is < 0.5★. Bounded staleness, no engineering cost, no risk. The April 22 design principle ("Claude evaluates every photographable opportunity") is now mostly served by the Gate 2 + Gate 4 changes that just shipped — Gate 1 is the smallest remaining lever.

The only thing worth fixing if you keep A: the **12 h TRANSITIONAL on 12 h cycle timing flap** (Section 1.6). Bump TRANSITIONAL to 10 h or 11 h (so it's always stale by next cycle) — one-line config change, no design implications. Or accept it as is, since "every-other-cycle TRANSITIONAL refresh" is also a defensible policy.

### Option B — Per-location freshness

**What changes**: per-location freshness check + merge-on-write + per-location timestamps in `results_json` + dual-table read for triage entries.

**Cost impact**: ~+$8/month.

**Effort**: 5-7 days (revised up from parent doc's 2-3 day estimate — see Section 5.4).

**Risk**: medium-high. Schema change, write-semantics change, concurrency reconsideration, cross-table reads.

**Worth doing?** Only if Section 3 analysis shows mean delta ≥ 1★ at the TRANSITIONAL 12 h bucket *and* the per-cycle skip rate (Section 3.2) is high. Otherwise the engineering effort isn't justified by the staleness reduction.

**Sequencing constraint**: should not be designed until 7-14 days of post-Gate-2 production data is available, because the design depends on what `results_json` actually contains in steady-state (Section 5.5).

### Option C — Tier by days-ahead, keep per-region

**What changes**: `hasFreshEvaluation` takes days-ahead in addition to stability. T+0=4h, T+1=8h, T+2=12h+stability, T+3=24h+SETTLED.

**Cost impact**: ~+$21/month.

**Effort**: 1-2 days.

**Risk**: low. No structural change; threshold function gains a parameter.

**Worth doing?** Yes if Section 3 shows the timing-flap pattern (T+1 TRANSITIONAL regions skipping every other cycle), since C eliminates that directly. Easier middle ground than B.

### Honest read — is Gate 1 a real problem?

The cleanest answer I can give from this side of the firewall: **probably not, right now.**

Gate 1's harm is bounded (≤ threshold hours stale) and the threshold-stability mapping is approximately right for synoptic forecast skill. The dominant batch tier is T+1, where 12 h staleness on a 12 h cycle interval is the worst case. The Section 1.6 timing flap is a real calibration concern but a 1-hour threshold nudge fixes it without redesign.

The strong reason to *not* redesign now: the Gate 2 verdict-as-attribute change shipped 2026-05-23 will substantially shift what `results_json` contains. The cache content profile will look different in 7 days than it does today. Designing per-location freshness around the old assumptions (which is what the parent doc's sparse-coverage analysis did, and what an immediate Option B implementation would also do) risks landing the wrong design.

Unlike Gate 2 (which was demonstrably filtering most of the product's value) and Gate 4 (which had an empty-table bug silently capping T+1 for UNSETTLED cells), Gate 1 may be doing roughly what it's supposed to. The strongest case for action right now is Option C — small, low-risk, addresses the timing flap concretely.

**My recommendation:** wait one week, then run the Section 3 queries. If `evaluation_delta_log` shows mean delta < 0.5★ across stability bands, stop here (Option A + maybe the 1-hour TRANSITIONAL nudge). If it shows clear staleness in the T+1 TRANSITIONAL bucket specifically, do Option C. Hold Option B in reserve for a future pass when the data justifies the complexity.

### "What surprised me most"

The cache architecture is intentionally region-scoped all the way down — there is no per-location timestamp anywhere in the system. `BriefingEvaluationResult` is location-keyed but timestamp-free; the only `evaluatedAt` is on the wrapping `CachedEvaluation` at region level. Per-location freshness is not "change the query" — it's "add a dimension to the data model." That puts Option B in a different effort class than the parent investigation suggested. The trap the prompt warned about is real and easy to miss until you trace where each kind of entry actually lives.

### Open questions before any implementation prompt

1. **Has anyone run the Section 3 queries in production?** Specifically the `evaluation_delta_log` rating-delta distribution by stability + age bucket. This is the single most important data point. Without it, Section 6 is mostly opinion.
2. **Is TRANSITIONAL=12h on a 12h cycle intentional?** It looks like a calibration miss (timing flap) but might be deliberate (every-other-cycle refresh policy). If intentional, the rationale should be documented; if accidental, the 1-hour nudge is risk-free.
3. **Post-Gate-2 cache content profile.** What fraction of `results_json` entries are rating 1-2 after a week? If sub-3 ratings become common, the sparse-coverage filter discussion in the parent doc needs revisiting, and Option B's design assumptions need updating.
4. **Does the SSE drill-down's merge semantic conflict with batch's replace semantic?** Today the SSE path merges results onto prior cache; the batch path replaces. If both fire within a freshness window (admin SSE-evaluates a region, batch lands later), the batch wipes the SSE additions. Worth verifying this is intentional or fixing if not.
5. **Is the `ForecastCommandExecutor.applyStabilityFilter` legacy admin path also reading from this cache?** Section 7 of the gating-architecture investigation flagged the executor path as out-of-scope. A Gate 1 redesign that changes `hasFreshEvaluation`'s signature has to leave the executor path callable or unify it. Should be verified before any code change.

---

## Areas of uncertainty

1. **The actual rating-delta distribution.** Section 3 lays out the queries but I cannot run them. Everything in Section 6 is conditional on what those queries reveal.
2. **Whether the dev `application.yml` override of UNSETTLED to 6 h (which is what the dev logs show) matches production.** Production uses defaults per `application-prod.yml`, so it should be 4 h, but worth confirming with `grep photocast.freshness /app/application.yml` on the prod host.
3. **Concurrency behaviour under per-location freshness.** Today `BatchResultProcessor` serialises writes per-batch; under per-location freshness multiple batches per cycle might write to the same `cacheKey`. The in-memory `ConcurrentHashMap` is safe for atomic `put`, but a read-merge-write sequence isn't atomic without an explicit per-key lock. I haven't traced whether this is a real risk or an acceptable race.
4. **Whether `EvaluationViewService.getScoresForEnrichment`'s cross-table merge logic (cached_evaluation + forecast_evaluation fallback) is the right model for the Gate 1 freshness check too.** The merge pattern works at view time; whether it works at freshness-check time (which fires once per region per cycle, not once per slot) needs a perf check before committing to B.

---

## Appendix: Files referenced

| File | Lines | Purpose |
|------|-------|---------|
| [ForecastTaskCollector.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java) | 520-539, 739-758, 782-800 | Gate 1 firing site; per-stability log; mostVolatileStability |
| [BriefingEvaluationService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java) | 83-92, 309-316, 327-336, 543-580, 200-240, 471-519 | In-memory cache + hasFreshEvaluation + writeFromBatch + persistToDb + SSE merge + rehydration |
| [FreshnessResolver.java](../../backend/src/main/java/com/gregochr/goldenhour/service/FreshnessResolver.java) | 58-66 | Stability → max-age mapping with safety floor |
| [FreshnessProperties.java](../../backend/src/main/java/com/gregochr/goldenhour/config/FreshnessProperties.java) | full file | Threshold defaults (SETTLED=36, TRANSITIONAL=12, UNSETTLED=4, floor=2) |
| [CacheKeyFactory.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/CacheKeyFactory.java) | 39-48 | `regionName\|date\|targetType` format + separator validation |
| [CachedEvaluationEntity.java](../../backend/src/main/java/com/gregochr/goldenhour/entity/CachedEvaluationEntity.java) | full file | Persistence model — note only one `evaluated_at` per row |
| [V91__create_cached_evaluation_table.sql](../../backend/src/main/resources/db/migration/V91__create_cached_evaluation_table.sql) | full file | Schema — no per-location timestamp |
| [V97__create_evaluation_delta_log.sql](../../backend/src/main/resources/db/migration/V97__create_evaluation_delta_log.sql) | full file | The table Section 3's analysis depends on |
| [BriefingEvaluationResult.java](../../backend/src/main/java/com/gregochr/goldenhour/model/BriefingEvaluationResult.java) | full file | Per-location record inside results_json — no evaluatedAt field today |
| [ForecastResultHandler.java](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/ForecastResultHandler.java) | 129-164, 174-176, 213-216 | Batch write path; sync write path; cacheKey grouping |
| [BatchResultProcessor.java](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BatchResultProcessor.java) | 264-289 | Group-by-cacheKey loop; one writeFromBatch per key per cycle |
| [ForecastService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java) | 362-381, 428-481 | Triage row write to forecast_evaluation; scored SSE write |
| [EvaluationViewService.java](../../backend/src/main/java/com/gregochr/goldenhour/service/EvaluationViewService.java) | 213-247 | Cross-table merge (cached_evaluation + forecast_evaluation fallback) |
| [BriefingGatingPolicy.java](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingGatingPolicy.java) | full file | Post-Gate-2 only TIDE_MISMATCH filters |
| [V73__forecast_batch.sql](../../backend/src/main/resources/db/migration/V73__forecast_batch.sql) | 20-24 | Cron `0 0 3,15 * * *` — 12 h cycle interval |
| [gating-architecture-investigation.md](gating-architecture-investigation.md) | §4, §7 Gate 1 | Parent investigation's Gate 1 mapping |
| [gate-2-redesign-investigation.md](gate-2-redesign-investigation.md) | §9.6 | Gate 2 verdict-as-attribute as built — Option B with TIDE_MISMATCH retained |
