# Horizon-aware cache freshness — implementation plan

Status: plan, awaiting approval. Written 2026-08-11 against `main` at `7581da7`; revised the same
day after adversarial review (§8). No production code modified. Sibling to [gate-1-cache-freshness-investigation.md](gate-1-cache-freshness-investigation.md),
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
- **Cost**: an **upper bound** of +~90 slot-conversions/day on the near-term model. Not a point
  estimate — see §7. The true figure is lower and is not currently measurable; §6 says how to get it.
- **Justification is no longer an estimate.** The 36h SETTLED threshold rests on "blocking highs
  persist, so the evaluation holds." `evaluation_delta_log` falsifies it. The cleanest leg is the
  **48h** bucket: 625 SETTLED refreshes, **72%** moved ≥1★ and **36%** moved ≥2★. That bucket needs
  no explaining-away, because 36h *is* the SETTLED threshold — a 48h gap is the gate's own permitted
  output, so those rows are what the gate currently considers an acceptable amount of staleness.
  (The 24h bucket — 63% / 26% — points the same way but carries the §1.4 caveats.)

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

**Lead with the 48h row, not the 24h one.** 36h is the SETTLED threshold, so a 48h gap is behaviour
the gate already permits — those 625 refreshes are not an escape from the gate but a sample of what
it deliberately allows, and 72% of them moved a full star. That leg survives caveat (a) entirely,
because it needs no mismatch to explain how the refresh happened.

**Three caveats, stated so nobody over-reads the table.**

**(a) The population is the gate's complement, not a sample of what it blocks.** These are refreshes
that *happened*. A SETTLED slot re-evaluated at 24h got through despite a 36h threshold, so some
other condition let it by. The dominant route is a **stability mismatch across the two readings**:
the gate reads the region's *most volatile* stability from the *previous* cycle's snapshot
([`BriefingCandidateCollector:142, :212-214`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/BriefingCandidateCollector.java#L212),
`mostVolatileStability` :335-353), while the delta row is stamped with the *location's own*
stability from the snapshot published later in the same cycle
([`ForecastTaskCollector:339-341`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/ForecastTaskCollector.java#L339)
→ `BriefingEvaluationService:417-418`). A row labelled SETTLED at 24h was very likely gated as
TRANSITIONAL or UNSETTLED. Force-eval is **not** one of the routes — `ForceEvalHeadlineSelector:38`
sets `FORCE_EVAL_MIN_DAYS_AHEAD = 2`, so it cannot touch T+0/T+1 at all.

**(b) The sub-6h rows are a units mismatch, not retry artefacts.** `age_hours` is
`Duration.between(prior.evaluatedAt(), newEvaluatedAt)` — a per-**cache-key** scalar reset by any
write ([`BriefingEvaluationService:420`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java#L420))
— while `oldRating` is per-**location** (:411, :433). A region's slots routinely span several
batches: `BatchResultProcessor:329-338` names Northumberland 33/43, North Yorkshire Coast 16/9 and
Tyne and Wear 2/9 as inland/coastal splits, and bluebell and woodland are further buckets. So the
second batch of an *ordinary* cycle logs a genuine 24h rating movement at `age_hours ≈ 0`. Routine,
not exceptional. (An earlier draft of this plan blamed RETRY_FAILED merges; that was wrong —
`writeFromBatch` has no live caller at all, since `ForecastResultHandler.flushCacheKey` is
`@Deprecated` and unreferenced, and `BatchResultProcessor:328` routes every sky key through
`mergeCacheKey`.)

**(c) Consequence of (b): the 24h and 48h buckets are drawn from first-written batches only.** The
mislabelled rows are the second-written bucket of every mixed region, so they are absent from the
buckets the argument rests on. This is a distinct selection effect from (a) and neither is
quantified.

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
| 0 | 2h | always re-evaluated on the day of the event |
| 1 | 8h | survives no cycle boundary; both nightly and intraday refresh it |
| 2+ | none | stability table governs, unchanged |

**The T+0 cap and the safety floor are independent properties that happen to share a value.** At the
defaults (`t0Hours=2`, `safetyFloorHours=2`) the two coincide, but under §2.1's function it is the
**cap** that governs T+0, not the floor: `max(min(36, 2), 2) = 2` is decided by the `min`. They
separate the moment either is retuned — raise the floor to 4h and T+0 becomes 4h; §6's rollback
raises the cap to 36h and the floor is nowhere near binding. Do not write code or tests that assume
one implies the other (see §3).

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

Three other `maxAgeFor` call sites exist and all stay on the stability-only overload. Two are
logging-only (`logStabilityBreakdown` :303, the no-snapshot warning :318). The third is **not**
logging and was missed in the first draft of this plan:
[`BriefingEvaluationService:419`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java#L419)
persists the result into `evaluation_delta_log.threshold_used_hours` (:438).

Leaving that one uncapped is deliberate, not an oversight. The column is a **stability-band stamp**,
not a record of the threshold the gate actually applied — it already cannot be the latter, because
the gate reads a region aggregate from the previous snapshot and this writes a per-location value
from the new one (§1.4(a)). `EvaluationDeltaLogTest:199` pins it as a band stamp. Holding its
meaning constant across the change is what makes §4's before/after week comparable; adding a
`days_ahead` dimension to that table is not warranted, since §4's T+0 filter is derivable from
`evaluation_date` and `new_evaluated_at`.

### 2.3 What this does and does not fix

Fixes the reported incident outright: the 11 Aug 01:05 nightly would have found the entry 22.8h old
against a 2h cap, collected it, and `NightlyEligibilityPolicy` would have returned
`include(nearTermModel)` for T+0.

Does **not**, on its own, restore the intraday refresh on settled days. A T+1 SETTLED slot would now
clear the cache gate at 12.8h against an 8h cap — and then be skipped by
[`IntradayEligibilityPolicy`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/IntradayEligibilityPolicy.java),
which returns `SKIPPED_NO_REFRESH_NEEDED` for SETTLED. That policy is a separate decision — see §4.

**State the per-cycle cost, not just the per-slot one.** "One weather prefetch" is the marginal cost
of a single slot; the cycle-level change is larger. On a fully-settled afternoon the intraday cycle
goes from doing effectively no work (every region gated out at collection — 419 slots on
2026-08-10) to running a full decision-window prefetch and triage pass over those slots, whose
results the very next gate then discards. Two things bound it: the coordinate set is a subset of
what nightly already prefetches, and the work is not actually wasted — it is the sole source of the
fresh afternoon stability classification that `IntradayEligibilityPolicy` consumes
(`GridCellStabilityService:100-108`). But it is a real change in Open-Meteo load and it interacts
with `minPrefetchSuccessRatio` (`ForecastTaskCollector:313`): a degraded prefetch over a *larger*
candidate set can now abort a cycle that previously had nothing to abort.

---

## Section 3 — Phase 1 (the fix)

| # | Change | File |
|---|---|---|
| 1.1 | `horizon` sub-properties (`t0Hours=2`, `t1Hours=8`), Javadoc'd with the §1.4 rationale | `config/FreshnessProperties.java` |
| 1.2 | `maxAgeFor(int, ForecastStability)`; keep the 1-arg overload delegating with no cap; extend the `@PostConstruct` threshold log to print the horizon caps | `service/FreshnessResolver.java` |
| 1.3 | Pass `daysAhead`; widen the CACHED `detail` string to name the horizon | `service/batch/BriefingCandidateCollector.java` |
| 1.4 | Document the caps alongside the existing freshness block | `resources/application-example.yml` |

No migration. No schema change. No API change. Rollback is a **config change plus a container
restart** — see §6, which corrects an earlier claim that it needed neither.

### Tests

| File | Change |
|---|---|
| **`ForecastTaskCollectorTest`** | **Required — the build fails without it.** `:114` declares `@Mock FreshnessResolver` and `:138` stubs only the 1-arg form (`lenient().when(freshnessResolver.maxAgeFor(any()))`). Once the call site moves to the 2-arg overload the mock returns `null`, `any()` still satisfies the `hasFreshEvaluation` stub, and `freshness.toHours()` at `BriefingCandidateCollector:221` NPEs. Three cases gate on that path (`:377`, `:1213`, `:1342`). Convert the stub to the 2-arg form. |
| `CollectForecastTasksCachedGateTest` | Not "horizon-agnostic" as first drafted — the fixture is hard-wired to T+1, so the requested T+0/T+3 pair is not writable without changing it, and three cases will error under strict stubs. Widen the fixture to parameterise the target date, then add the regression: a SETTLED region with a 23h-old entry at **T+0** is collected, and the same entry at **T+3** is skipped. That pair is the bug and its boundary in one test. |
| `FreshnessResolverTest` | New `@Nested HorizonCaps`: T+0 SETTLED → 2h; T+1 SETTLED → 8h; T+1 UNSETTLED → 4h (stability still tighter); T+2 SETTLED → 36h (uncapped). For the floor, a **binding pair** that proves cap and floor are independent (§2.1): `safetyFloorHours=4, t0Hours=2 → 4h` and `safetyFloorHours=1, t0Hours=2 → 2h`. |
| `ForecastTaskCollectorForceEvalTest` | Never stubs `hasFreshEvaluation`; unaffected. Do not touch. |
| `BriefingEvaluationServiceCacheFreshnessTest` | Unaffected — `hasFreshEvaluation(key, Duration)` keeps its signature; the horizon decision happens before it. Confirm, don't edit. |
| `DispositionWriteIntegrationTest`, `OrchestratedDispositionWriteIntegrationTest` | Assert the widened `detail` string if they match on it. Needs Docker. |

**Do not claim the monotonicity test pins the safety floor.** A test asserting
`maxAgeFor(d, s) ≤ maxAgeFor(s)` still passes with the floor clamp deleted, since
`max(min(base,cap),floor) ≤ max(base,floor)` regardless. It is worth writing — it pins the §2.1
no-slot-gets-staler guarantee across all (0..7 × 3) pairs — but the floor is pinned by the existing
`FreshnessResolverTest` `@Nested SafetyFloor` cases (:55-90), reached through the delegating 1-arg
overload, plus the new binding pair above. §7 previously credited the wrong test.

An earlier draft listed "T+0 with a 1h configured floor → floor wins". That case is arithmetically
impossible: with `t0Hours=2`, `max(min(36, 2), 1) = 2` — the cap wins. Under the proposed caps no
horizon admits a winning 1h floor.

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

### 5.3 Give `evaluation_delta_log` a per-location prior timestamp

**This replaces an earlier proposal that would have measured nothing.** The first draft blamed the
sub-6h rows on RETRY_FAILED merges and proposed a `write_kind VARCHAR(20)`
(`BATCH_WRITE`/`RETRY_MERGE`) column to separate them. That diagnosis was wrong (§1.4(b)):
`writeFromBatch` has no live caller — `ForecastResultHandler.flushCacheKey` is `@Deprecated` and
unreferenced, and `BatchResultProcessor:328` routes every sky key through `mergeCacheKey`. The
column would have stamped `RETRY_MERGE` on essentially every row and separated nothing. A migration
that measures nothing is worse than no migration.

The actual defect is a units mismatch: `age_hours` is per cache key, `oldRating` is per location,
and a region spans several batches. The fix is to make the two agree — record the age against the
**location's own** previous write rather than the cache key's. Two shapes, in preference order:

1. **Derive it at write time.** `logEvaluationDeltas` already holds the prior
   `BriefingEvaluationResult` per location; give `BriefingEvaluationResult` an `evaluatedAt` and
   compute `age_hours` from it. No migration, but it touches the record that rides
   `cached_evaluation.results_json`, so legacy rows deserialize with a null and the column has to
   tolerate that.
2. **Add `old_evaluated_at_location TIMESTAMPTZ` nullable** alongside the existing per-key
   `old_evaluated_at`, so the two are visibly different quantities and old rows keep their meaning.
   Migration number: read it from the tree at the time of writing
   (`ls backend/src/main/resources/db/migration/ | sort -V | tail -1`) — do not trust a number
   recorded here, per CLAUDE.md.

**Sequencing.** Shape 1 is a prerequisite for shape 2 being worth anything, so start there. Do not
gate this on a pre-flight count: an earlier draft proposed one that could only confirm, never
refute, since a high sub-hour count is consistent with both the retry theory and the units-mismatch
theory. The units mismatch is settled by reading the code, not by counting rows.

Until this lands, the sub-6h buckets in §1.4 should simply be treated as unreadable, and §4's
one-week observation should filter to `age_hours >= 12` to stay clear of them.

---

## Section 6 — Sequencing, verification, rollback

**Order**: 5.1 and 5.2 first (independent, tiny, and 5.2 is what verifies everything after it) →
Phase 1 → 5.3 shape 1 → observe one week, filtered to `age_hours >= 12` → Phase 2 decision.

5.3 moved **ahead** of the observation week in this revision. It was previously sequenced after it
and gated on a pre-flight query; that pre-flight could only ever confirm (§5.3), and the week of
data §4 depends on is exactly the data the units mismatch corrupts. Fixing the instrument before
taking the measurement is the whole point.

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
2. **The outcome check.** The Angel of the North T+0 row for the next day's sunset reads
   `EVALUATED`, not `SKIPPED_CACHED`. This is the one that actually proves the fix; 3 and 4 are
   supporting.
3. `skipped_cached` at `days_ahead = 0` trends toward zero over 14 days — **but report
   `skipped_settled` beside it** (the §1.2 query already carries the column). On its own this step
   is satisfiable by pure relabelling: an intraday SETTLED row that clears the cache gate becomes
   `SKIPPED_NO_REFRESH_NEEDED` at `IntradayEligibilityPolicy:48-50` with no Claude call. If the two
   columns simply trade places, Phase 1 has changed nothing that a user can see, and §4 is the
   remaining lever.
4. Claude spend on the **near-term model** rises, **within a floor and a ceiling**. Name the
   denominator: §7's "+~10%" is against the whole T+0–T+3 evaluated population (89.5 / 913), not
   against near-term spend, whose base is ~619/day. A jump above the ceiling means the cap is being
   applied at a horizon it shouldn't be; **no rise at all is equally a failure** and means the
   conversions went to step 3's relabelling rather than to evaluations. One live confounder to
   exclude: the admin `POST /api/admin/batch/submit-scheduled` path
   (`ForecastTaskCollector.collectRegionFilteredBatches:732`) deliberately discards its dispositions
   (:743-746), so its conversions never appear in step 3's query at all and its spend is separable
   only by `BatchTriggerSource.ADMIN`.

**Rollback**: set `t0-hours` and `t1-hours` to 36. **Not the file-edit this plan first claimed** —
`photocast.freshness` appears in no production-loaded file (`application-prod.yml`'s `photocast:`
block carries only `tide-run`), so the live values are the Java defaults compiled into the jar and
nothing in the repo rebinds them at runtime. The actual lever is
`PHOTOCAST_FRESHNESS_HORIZON_T0_HOURS=36` (and `..._T1_HOURS=36`) in `backend/.env`, then
`docker compose up -d goldenhour-backend`. That is a container restart, not an image rebuild — no
migration to reverse and no redeploy in the project's usual sense, but it is not zero-downtime and
the plan should not have implied it was. This is still the reason the caps are config rather than
constants.

---

## Section 7 — Risks and non-goals

- **Cost — an upper bound, not an estimate.** `(544 + 709) / 14 = 89.5` slots/day is what *full*
  conversion would cost, and it is against the whole T+0–T+3 evaluated population (913/day) rather
  than near-term spend (~619/day). The real figure is lower, for three reasons the count cannot see:
  the intraday share converts to **zero** Claude calls (§2.3), and a slot cleared by the cache gate
  still faces the hard-constraint (`BriefingCandidateCollector:239-258`), unknown-location (:259-270),
  eligibility and triage gates. §1.2 counts *slots*, not evaluations. To get a real figure, split
  the §1.2 query by `job_run` — `ForecastRunDispositionEntity:48` carries it — and separate the
  nightly rows from the intraday ones before converting.
- **The bound is not "by construction" in the way an earlier draft claimed.** A 2h T+0 cap makes the
  gate's cost proportional to *trigger frequency*, not to the §1.2 row count: the same slot can now
  convert on every trigger within a day rather than once. The scheduled cycles are fixed at two per
  day, but the two admin trigger paths are not rate-limited, so a burst of manual runs multiplies
  the cost in a way the 89.5 figure does not capture. The 2h safety floor is the only backstop, and
  under this change it is load-bearing in a way it was not before.
- **Region-level granularity is unchanged.** One location's freshness still governs its whole region
  for a (date, event). That is the gate-1 investigation's Option B — a data-model change (there is
  no per-location timestamp anywhere in the system) and firmly out of scope here.
- **Thrash.** The 2h safety floor is the only thing standing between T+0 and a re-evaluation on
  every trigger. It is pinned by `FreshnessResolverTest`'s existing `@Nested SafetyFloor` cases
  (:55-90) plus the new binding pair in §3 — **not** by the monotonicity test, which an earlier
  draft wrongly credited with it (see §3).
- **Not a forecast-accuracy fix.** This makes the displayed forecast *current*. Whether a current
  forecast is *right* is the calibration gate's question, and it remains starved —
  `actual_outcome` has no rows.

---

## Section 8 — Review provenance and open items

This document was revised on 2026-08-11 after an adversarial review (six prosecutor lenses over the
plan and the code it touches, one refutation agent per charge defaulting to REFUTED, then a
synthesis pass — the cadence in CLAUDE.md's "UI Work — Review Cadence" section, applied to a plan
rather than a diff). 18 charges were verified: 14 survived, all narrowed to PARTIAL; 4 were refuted;
6 fell below a per-lens cap of 3 and were never verified.

**Nothing found touched the design.** The `max(floor, min(base, cap))` combinator, the monotonicity
guarantee, and the T+0/T+1 cap values all survived direct attack. Every amendment above is to the
document's evidence, test plan, cost figures or rollout procedure — not to what the code should do.

### 8.1 Noticed during review, deliberately not charged

`BriefingCandidateCollector` derives `daysAhead` from **Europe/London** (:104, :146), while
`ForecastService.fetchWeatherAndTriage` derives it from **UTC** (:290) — and the eligibility call at
`ForecastTaskCollector:445` uses the UTC one. Under this change the horizon **cap** would be applied
with the London value and **eligibility** decided with the UTC value.

Inert today: the two diverge only between 23:00 and 00:00 UTC during BST, and both scheduled cycles
(01:00 and 14:00 UTC) run far from that window. But it means the `days_ahead` column in §1.2 already
mixes two definitions across disposition categories, so any future analysis that joins on it should
know. Worth a follow-up that picks one zone; not worth blocking Phase 1.

### 8.2 What the review could not check

- **No production database.** Every figure in §1 was taken as given; only its interpretation was
  challenged. Still unmeasured: the nightly-vs-intraday split of the 1253 T+0/T+1 `SKIPPED_CACHED`
  rows, the real distribution of `threshold_used_hours`, the Gate-3 triage attrition rate, and the
  true noise floor in `evaluation_delta_log`.
- **Nothing was built or run.** The `ForecastTaskCollectorTest` NPE and the
  `CollectForecastTasksCachedGateTest` strict-stub failures are derived from reading stubs and
  matchers, not observed. Confirm them on rung 2 of the §6 ladder before assuming the §3 table is
  complete.
- **Not examined at all:** the batch submission path below `EvaluationServiceImpl` (bucket sizing,
  per-batch caps, whether a larger near-term bucket changes batch latency against the `PT4H`
  backstop); `BatchRetryService`; `ForecastDispositionService` internals; §5.1 beyond confirming the
  in-memory clock is correct; §5.2; all frontend code.
