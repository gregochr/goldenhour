# Horizon-aware cache freshness — implementation plan

Status: plan, awaiting approval. Written 2026-08-11 against `main` at `7581da7`. No production code
modified. Sibling to [gate-1-cache-freshness-investigation.md](gate-1-cache-freshness-investigation.md),
which proposed this fix ("Option C") on 2026-05-24 and deferred it pending production data. That data
now exists and is reproduced below.

---

## TL;DR

- **The reported symptom**: tonight's Angel of the North sunset (11 Aug, 5★) was evaluated at 01:05 on
  10 Aug when it was T+1, and never re-evaluated — not by the 14:00 intraday cycle, not by the 01:00
  nightly on the day of the event. The user saw a forecast issued 43 hours ahead with no path to
  improvement.
- **The cause**: Gate 1, the CACHED gate in
  [`BriefingCandidateCollector:209-236`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BriefingCandidateCollector.java#L209),
  resolves its freshness threshold from stability **alone**. `daysAhead` is computed 60 lines earlier
  and never consulted. The gate runs *before* both eligibility policies, so
  `NightlyEligibilityPolicy`'s `case 0, 1 -> include(nearTermModel)` is never reached.
- **The fix**: `FreshnessResolver.maxAgeFor` gains a `daysAhead` parameter and returns
  `max(floor, min(stabilityThreshold, horizonCap))`. T+0 capped at the 2h safety floor, T+1 at 8h.
  Strictly a tightening — no slot can become staler than it is today.
- **Cost**: +~90 evaluations/day on the near-term model, ~+10% of the T+0–T+3 evaluated population.
- **Justification is no longer an estimate.** The 36h SETTLED threshold rests on "blocking highs
  persist, so the evaluation holds." `evaluation_delta_log` falsifies it: a SETTLED slot re-evaluated
  24h later moves by ≥1★ **63%** of the time and by ≥2★ **26%** of the time.

---

## Section 1 — Evidence

All figures from production (`goldenhour-db`) on 2026-08-11. Queries in
`scripts/diagnose-stale-forecast.sh` (to be added by this change; see §6).

### 1.1 The reported slot

`forecast_run_disposition` for Angel of the North, 2026-08-11 SUNSET:

| cycle (UTC) | daysAhead | disposition | detail |
|---|---|---|---|
| 2026-08-08 01:06 | 3 | SKIPPED_STABILITY | T+3 TRANSITIONAL |
| 2026-08-09 01:06 | 2 | EVALUATED | |
| 2026-08-10 01:05 | 1 | EVALUATED | ← the 5★ on screen |
| 2026-08-10 14:01 | 1 | SKIPPED_CACHED | Fresh cached evaluation within 36h (SETTLED) |
| 2026-08-11 01:05 | 0 | SKIPPED_CACHED | Fresh cached evaluation within 36h (SETTLED) |

The grid cell (`54.9146,-1.5925`) classified SETTLED at 01:04 on the 11th. Nothing was
misconfigured: both cycles are ACTIVE (`0 0 1 * * *` and `0 0 14 * * *`) and every `pipeline_run` in
the window COMPLETED, bar the 9 Aug intraday killed by a container restart.

### 1.2 Blast radius

Dispositions by horizon, 14 days:

| daysAhead | evaluated | skipped_cached | skipped_settled | total |
|---|---|---|---|---|
| 0 | 3799 | **544** | 236 | 8906 |
| 1 | 4862 | **709** | 905 | 10882 |
| 2 | 2767 | 316 | 0 | 7269 |
| 3 | 1355 | 0 | 0 | 6064 |

So ~12.5% of T+0 slots that reach the cache decision are skipped on it. A minority case — but it
selects for settled, clear evenings, which is where the 5★ ratings live.

Breaking the skips down by threshold: **500 of the 544 T+0 skips are the 36h SETTLED path**, 44 are
the 12h TRANSITIONAL one. This is a SETTLED-threshold problem, not a whole-table re-tier.

### 1.3 The gate disables the intraday cycle exactly when it matters

Intraday dispositions (14:00–15:00 UTC):

| day | EVALUATED | SKIPPED_CACHED |
|---|---|---|
| 2026-08-07 | 175 | 0 |
| 2026-08-08 | 377 | 0 |
| 2026-08-10 | **0** | **419** |

The intraday refresh works normally while the pattern is mixed and shuts down completely once the
grid goes SETTLED. That is inverted from what the product needs.

### 1.4 The threshold's premise is false

`evaluation_delta_log`, 30 days, SETTLED rows:

| gap | refreshes | mean Δ★ | moved ≥1★ | moved ≥2★ |
|---|---|---|---|---|
| 24h | 1024 | 0.97 | 648 (63%) | 268 (26%) |
| 48h | 625 | 1.22 | 447 (72%) | 223 (36%) |

Synoptic persistence is not rating persistence. The rating turns on low cloud at the 113 km solar
horizon, which moves freely under a stable pattern. The 36h number was calibrated against the wrong
quantity.

**Two caveats, stated so nobody over-reads the table.** (a) These are refreshes that *happened*, so
the population is selected — a SETTLED slot re-evaluated at 24h got through by some other route
(stability reclassified between cycles, force-eval, admin run) and may not represent the ones the
gate blocks. (b) The 0–6h buckets are not a noise floor: `logEvaluationDeltas` fires from
`writeFromBatch` and from `mergeFromBatch`, and a RETRY_FAILED merge lands minutes after its
precursor write, producing `age_hours ≈ 0` rows. Only the 24h+ buckets are cycle-to-cycle. §5.3 adds
the column that makes this checkable rather than argued.

---

## Section 2 — The change

### 2.1 Threshold function

`FreshnessResolver` gains a horizon-capped overload. The existing single-argument method stays as a
delegating default (`daysAhead` unknown → no cap), so no caller is silently changed by the signature
growing.

```java
public Duration maxAgeFor(int daysAhead, ForecastStability level) {
    Duration base  = stabilityBase(level);          // 36 / 12 / 4, as today
    Duration cap   = horizonCap(daysAhead);         // 2 / 8 / none
    Duration floor = Duration.ofHours(props.getSafetyFloorHours());
    Duration tighter = base.compareTo(cap) < 0 ? base : cap;
    return tighter.compareTo(floor) < 0 ? floor : tighter;
}
```

Horizon caps, new config under `photocast.freshness.horizon`:

| daysAhead | cap | effect |
|---|---|---|
| 0 | 2h (= safety floor) | always re-evaluated on the day of the event |
| 1 | 8h | survives no cycle boundary; both nightly and intraday refresh it |
| 2+ | none | stability table governs, unchanged |

**Why `min()` rather than a replacement table.** Taking the tighter of the two makes the change
provably monotone: no slot can come out staler than it is today, at any horizon or stability. That
is a property worth being able to state in one sentence during rollout, and it means a
misconfigured horizon cap degrades to current behaviour rather than to something worse. A
replacement table has no such guarantee.

**Why T+0 gets the floor rather than a considered number.** On the day of the event there is no
horizon left to trade against. The only reason not to re-evaluate is cost, and the safety floor
already exists to stop trigger thrash. Any value above the floor is an arbitrary amount of staleness
to defend.

### 2.2 Call site

One functional site — [`BriefingCandidateCollector:214`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BriefingCandidateCollector.java#L214).
`daysAhead` is already in scope at line 150:

```java
Duration freshness = freshnessResolver.maxAgeFor(daysAhead, regionStability);
```

The `detail` string at :222 must carry the horizon too, or the disposition trail stops explaining
itself: `"Fresh cached evaluation within 2h (T+0, SETTLED)"`. That string is the operator's only
window into this gate and it is what made the diagnosis possible; it must not degrade.

Two other `maxAgeFor` calls are logging-only (`logStabilityBreakdown` :303, the no-snapshot warning
:318) and stay on the stability-only overload.

### 2.3 What this does and does not fix

Fixes the reported incident outright: the 11 Aug 01:05 nightly would have found the entry 22.8h old
against a 2h cap, collected it, and `NightlyEligibilityPolicy` would have returned
`include(nearTermModel)` for T+0.

Does **not**, on its own, restore the intraday refresh on settled days. A T+1 SETTLED slot would now
clear the cache gate at 12.8h against an 8h cap — and then be skipped by
[`IntradayEligibilityPolicy`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/IntradayEligibilityPolicy.java),
which returns `SKIPPED_NO_REFRESH_NEEDED` for SETTLED. Cost is one weather prefetch, no Claude call.
That policy is a separate decision — see §4.

---

## Section 3 — Phase 1 (the fix)

| # | Change | File |
|---|---|---|
| 1.1 | `horizon` sub-properties (`t0Hours=2`, `t1Hours=8`), Javadoc'd with the §1.4 rationale | `config/FreshnessProperties.java` |
| 1.2 | `maxAgeFor(int, ForecastStability)`; keep the 1-arg overload delegating with no cap; extend the `@PostConstruct` threshold log to print the horizon caps | `service/FreshnessResolver.java` |
| 1.3 | Pass `daysAhead`; widen the CACHED `detail` string to name the horizon | `service/batch/BriefingCandidateCollector.java` |
| 1.4 | Document the caps alongside the existing freshness block | `resources/application-example.yml` |

No migration. No schema change. No API change. Rollback is a config edit
(`photocast.freshness.horizon.t0-hours: 36`) with no redeploy.

### Tests

| File | Change |
|---|---|
| `FreshnessResolverTest` | New `@Nested HorizonCaps`: T+0 SETTLED → 2h; T+1 SETTLED → 8h; T+1 UNSETTLED → 4h (stability still tighter); T+2 SETTLED → 36h (uncapped); T+0 with a 1h configured floor → floor wins. Plus a **monotonicity** test asserting the 2-arg result is never greater than the 1-arg result, across every (0..7 × 3) pair — that is the §2.1 guarantee, pinned. |
| `CollectForecastTasksCachedGateTest` | The existing cases pin stability-only behaviour at unspecified horizons; they need explicit `daysAhead` per case. Add the regression: a SETTLED region with a 23h-old entry at **T+0** is collected, and the same entry at **T+3** is skipped. That pair is the bug and its boundary in one test. |
| `BriefingEvaluationServiceCacheFreshnessTest` | Unaffected — `hasFreshEvaluation(key, Duration)` keeps its signature; the horizon decision happens before it. Confirm, don't edit. |
| `DispositionWriteIntegrationTest`, `OrchestratedDispositionWriteIntegrationTest` | Assert the widened `detail` string if they match on it. Needs Docker. |

---

## Section 4 — Phase 2 (separate decision, not bundled)

**Should `IntradayEligibilityPolicy` stop skipping SETTLED at T+0?**

The policy's rationale — "the synoptic pattern has not moved since the morning, so the nightly
evaluation still holds" — is the same claim §1.4 falsifies. But this is a *cost* decision that the
user should make explicitly, not a defect: it would add a second Claude call per settled T+0 slot,
mid-afternoon, on the near-term model.

Bounded version if wanted: skip SETTLED at T+1, evaluate SETTLED at T+0 only. That buys a ~6-hour
lead-time refresh on the evening's shoot, which is the single most decision-relevant moment in the
day, and leaves the T+1 cost envelope untouched.

Recommend shipping Phase 1, watching one week of `evaluation_delta_log` filtered to T+0, then
deciding with numbers.

---

## Section 5 — Secondary fixes surfaced by the investigation

These are independent of the main change. Each is small; each cost time during this investigation.

### 5.1 `cached_evaluation.evaluated_at` is a lie after the first write

[`persistToDb`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)
sets `evaluatedAt` only in the `orElseGet` insert branch; every subsequent upsert moves only
`updated_at`. So the column reports when the *key was created*, not when the evaluation ran. It
showed 54.5h for a slot last evaluated 23h earlier during this investigation and sent the first
reading of the evidence down a blind alley.

The in-memory clock is correct and `rehydrateCacheOnStartup` already reads `updated_at` with a
comment explaining why — so this is a reporting bug, not a behavioural one. Fix: set `evaluatedAt`
on the update path too. Check `getCachedEvaluatedAt` first — it feeds a view-time read, and if the
UI has been showing first-write timestamps that is a second user-visible symptom of the same bug.

### 5.2 Ship the diagnostic queries

The investigation was only possible because `forecast_run_disposition` records a reason per
candidate. The queries that read it should live in the repo rather than in a chat log:
`scripts/diagnose-stale-forecast.sh`, read-only, parameterised by location and date.

### 5.3 Give `evaluation_delta_log` a `write_kind`

Per §1.4(b), RETRY_FAILED merges land in the same table as cycle-to-cycle refreshes and are
indistinguishable, which is why the 0–6h buckets can't be read. Add a nullable
`write_kind VARCHAR(20)` (`BATCH_WRITE` / `RETRY_MERGE`) set at the two `logEvaluationDeltas` call
sites — **V140**, the next free number (`ls backend/src/main/resources/db/migration/ | sort -V | tail -1`
before writing it; V139 is latest as of this plan). Nullable, so existing rows keep their meaning and
no backfill is needed.

Pre-flight check that confirms the hypothesis before writing the migration:

```sql
SELECT count(*) FILTER (WHERE age_hours < 1) AS sub_hour,
       count(*) FILTER (WHERE age_hours < 6) AS sub_six, count(*) AS total
FROM evaluation_delta_log WHERE logged_at >= NOW() - INTERVAL '30 days';
```

If `sub_hour` accounts for most of `sub_six`, it is retry merges and the column is worth adding.

---

## Section 6 — Sequencing, verification, rollback

**Order**: 5.1 and 5.2 first (independent, tiny, and 5.2 is what verifies everything after it) →
Phase 1 → observe one week → 5.3 if the pre-flight supports it → Phase 2 decision.

**Build ladder** per CLAUDE.md, gating on exit codes, never on grepped output:

```bash
cd backend && ./mvnw compile -q
cd backend && ./mvnw test -Dtest='FreshnessResolverTest,CollectForecastTasksCachedGateTest' -q
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

JaCoCo's 80%-per-class rule will bite the new `horizonCap` branch — cover every arm with real
assertions rather than trimming the method.

**Verification after deploy**, first nightly cycle:

1. `[FRESHNESS]` startup line prints the horizon caps — proves the config bound.
2. The Angel of the North T+0 row for the next day's sunset reads `EVALUATED`, not `SKIPPED_CACHED`.
3. `skipped_cached` at `days_ahead = 0` in the §1.2 query trends to zero over 14 days.
4. Claude spend on the near-term model rises ~10% and no more. A larger jump means the cap is being
   applied at a horizon it shouldn't be.

**Rollback**: set `photocast.freshness.horizon.t0-hours: 36` and `t1-hours: 36`. No redeploy, no
migration to reverse. This is the reason the caps are config rather than constants.

---

## Section 7 — Risks and non-goals

- **Cost.** +~90 near-term evaluations/day (+~10%). Bounded by construction: the horizon cap only
  ever converts an existing `SKIPPED_CACHED` into an evaluation, and §1.2 counts exactly how many of
  those exist.
- **Region-level granularity is unchanged.** One location's freshness still governs its whole region
  for a (date, event). That is the gate-1 investigation's Option B — a data-model change (there is
  no per-location timestamp anywhere in the system) and firmly out of scope here.
- **Thrash.** The 2h safety floor is the only thing standing between T+0 and a re-evaluation on
  every trigger. It is load-bearing under this change in a way it was not before; the monotonicity
  test pins that the floor always wins.
- **Not a forecast-accuracy fix.** This makes the displayed forecast *current*. Whether a current
  forecast is *right* is the calibration gate's question, and it remains starved —
  `actual_outcome` has no rows.
