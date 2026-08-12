# Intraday settled refresh — the decision-time problem

Status: plan, revised 2026-08-11 after adversarial review (§9). Written against `main` at
`d7e3c77`, i.e. with both #471 (horizon-aware cache freshness) and #472 (per-location delta age)
already merged. No production code modified. This is "Phase 2" as deferred by §4 of
[horizon-aware-freshness-plan.md](horizon-aware-freshness-plan.md), but the framing has changed
enough since that section was written that it needs its own document.

**Decision: ship this, then consider the trigger redesign separately.** Sunset is ~21:00 local
through to early September, so the summer benefit is available now and the winter defect (§7) is
pre-existing rather than introduced. Staged deliberately — see §7 for what the second stage is and
why this plan does not attempt it.

---

## TL;DR

- **The issue**: the 14:00 UTC intraday cycle skips every SETTLED location, on the stated grounds
  that "the synoptic pattern has not moved since the morning, so the nightly evaluation still
  holds." That is the same claim the 36h freshness threshold rested on, and the same evidence
  falsifies it. On a settled afternoon the cycle therefore does nothing at all.
- **What that costs**: the last look at tonight's sunset is the 01:00 cycle, ~19h before the event.
- **The reframe** (the part §4 of the predecessor got wrong): ranking runs by *lead time before the
  event* is the wrong metric. What matters is lead time before the **decision**. For sunset the two
  roughly coincide. For sunrise they do not — the 01:00 run is 4h before a 05:00 sunrise and 0h
  before any decision a sleeping person can make.
- **The change**: widen `EligibilityPolicy.resolve` to take the target type; in the intraday cycle
  include SETTLED for **T+0 sunset** and **T+1 sunrise**, and keep skipping it for **T+1 sunset**.
- **Cost**: ~49 extra near-term evaluations/day, held loosely (see §5).
- ⚠️ **Measure notice in delivery time, not fire time.** 14:00 is a *submission*; afternoon batches
  take 98–173 min (§2.1). Summer notice is ~3h, not 6h. Winter it is **negative** — the rating
  lands after the sunset — which is pre-existing and is the second stage's job (§7).

---

## Section 1 — The issue

### 1.1 The gate

[`IntradayEligibilityPolicy`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/IntradayEligibilityPolicy.java):

```java
case SETTLED -> EligibilityDecision.skip(
        "settled — no intraday refresh needed",
        DispositionCategory.SKIPPED_NO_REFRESH_NEEDED);
case TRANSITIONAL, UNSETTLED -> EligibilityDecision.include(nearTermModel);
```

Its javadoc gives the rationale: *"the synoptic pattern has not moved since the morning, so the
nightly evaluation still holds."*

### 1.2 Why that rationale is false

It is the identical premise the 36h SETTLED freshness threshold rested on, and the same data
falsifies it. From `evaluation_delta_log`, 30 days, SETTLED rows:

| gap | refreshes | mean Δ★ | moved ≥1★ | moved ≥2★ |
|---|---|---|---|---|
| 24h | 1024 | 0.97 | **648 (63%)** | 268 (26%) |
| 48h | 625 | 1.22 | **447 (72%)** | 223 (36%) |

Synoptic persistence is not rating persistence. A blocking high is stable; the low cloud loitering
on the western horizon underneath it is not, and that is what the rating turns on.

**Argue from 24h and 48h, not from 12h.** An earlier draft led with the 12h bucket (232 refreshes,
74% moved ≥1★) on the reasoning that it sits closest to the real ~13h gap between the 01:00 write
and the 14:00 cycle. That is backwards. A 13h-gap refresh is, *by construction*, one the intraday
settled skip did **not** apply to — so the bucket the change targets is precisely the one that row
excludes. The 24h and 48h buckets are gaps across which the intervening intraday cycle did not
fire, i.e. much closer to the population in question. The 12h row is recorded here only so nobody
re-derives it and reaches the same wrong conclusion.

⚠️ **Caveats, carried forward from the freshness plan §1.4 and still true.**

**(a) The population is the gate's complement.** These are refreshes that *happened*, so some
condition let each one past a 36h threshold. For the nightly path the likely route is a stability
mismatch across the two readings. **For the intraday path it is structural, not probabilistic**:
intraday classifies stability *ephemerally* and publishes nothing —
[`GridCellStabilityService:198`](../../backend/src/main/java/com/gregochr/goldenhour/service/batch/GridCellStabilityService.java#L198)
logs *"snapshot NOT published (morning snapshot preserved)"* — so the delta row is stamped with the
01:00 label while the gate acted on the unpublished 14:00 one. The two can disagree, and when they
do a settled-skipped slot can still appear in this table under a different label.

**(b) Every row pre-dates #472**, so `age_hours` was measured per cache key rather than per
location. That artefact manufactures ~0h rows, not 24h or 48h ones, so the buckets used above are
largely clean of it — but it does mean they are drawn from first-written batches only.

**Nothing in this table is a clean experiment.** It is suggestive, consistent across buckets, and
consistent with the one direct observation below. The clean version needs the gating stability
recorded alongside the stamped one, which is worth doing for §8's verification regardless.

### 1.3 What it does in practice

Intraday dispositions, 14:00–15:00 UTC:

| day | EVALUATED | SKIPPED_CACHED | SKIPPED_NO_REFRESH_NEEDED |
|---|---|---|---|
| 2026-08-07 | 175 | 0 | 232 |
| 2026-08-08 | 377 | 0 | 0 |
| 2026-08-10 | **0** | **419** | 113 |

The cycle works while the pattern is mixed and stops entirely once the grid settles. Note 10 Aug's
419 `SKIPPED_CACHED` were the freshness gate, now fixed by #471 — post-deploy those become the
policy's problem instead, which is precisely why this change is next.

### 1.4 One direct observation

2026-08-11, Angel of the North, sunset. Forecast 5★, issued 01:05 on 10 Aug, never re-evaluated —
42.6h old at the event. Its narrative: *"the mid-cloud canvas ignites brilliantly **as the
low-cloud blocker clears away**, leaving a clear western horizon."*

Observed by the operator on site: **~3★**. A bank of low cloud sat on the horizon and did not
clear, so the sun never lit the high cloud. The rating was conditional on one forecast element, and
that element is exactly the one that drifts under a settled pattern.

⚠️ This is n=1, self-reported, and not recorded in `actual_outcome` (which has zero rows, so the
calibration gate cannot corroborate it). It is an anecdote that agrees with the table. Treated as
motivation, not proof.

---

## Section 2 — The reframe: decision time, not event time

§4 of the freshness plan, and my own first pass at this, ranked the runs by hours before the event.
That metric is wrong, and it produced a wrong recommendation (event-relative firing, §7).

A forecast is only useful if it reaches a human at a moment they can still act. The 01:00 cycle is
4h before an August sunrise — excellent by lead time, worthless by decision time, because the
decision it informs ("set an alarm?") is taken the previous evening by someone who is now asleep.

### 2.1 Measure from delivery, not from the fire

**A cycle's fire time is not when its rating exists.** 14:00 is a batch *submission*.
[`PipelineOrchestrator:98-101`](../../backend/src/main/java/com/gregochr/goldenhour/service/pipeline/PipelineOrchestrator.java#L98)
records the project's own calibration: *"nightly (01:00 UTC) batches reach terminal in 2–5 min, but
afternoon (~14:00 UTC, peak Anthropic load) batches were observed taking **98–173 min** with every
request succeeding."* After the wait comes a conditional retry phase and the briefing rebuild.

So the intraday cycle delivers a usable rating somewhere around **15:38–16:53 UTC**:

| event | decision taken | run | notice from **delivery** |
|---|---|---|---|
| sunset (~19:50 Aug) | that afternoon | 14:00 | **~3–4h** ✅ |
| sunrise (~05:00 Aug) | previous evening | 14:00 | ~13h ✅ |
| sunrise | — | 01:00 | ~0h ❌ (asleep) |
| sunset (~15:35 Dec) | that morning? | 14:00 | **negative** ❌ (§7) |

The argument survives for summer with room to spare. It does **not** survive winter, and an earlier
draft of this document called that output "fresh, but too late to drive anywhere" — wrong twice
over. There is no notice; the rating does not exist when the sun sets. §7 carries that.

The 01:00 run still earns its place: it is the freshest thing available to someone genuinely awake
at 04:00, it delivers in 2–5 min, and it already evaluates every stability at T+0/T+1, so that
coverage costs nothing extra. But it cannot be the *primary* signal for a sunrise, and the current
design treats it as if it were.

⚠️ **"You are asleep at 01:00" is a product judgement about one operator's behaviour, not a
measurement.** It is almost certainly right for a single-user pilot and should be revisited if the
user base ever includes people in other timezones reading the same UK forecasts. Cheap to falsify:
log app opens between 01:00 and 05:00.

⚠️ **The 98–173 min figure is a javadoc note, not a live measurement.** No `forecast_batch` p50/p95
was computed for this plan. §8 records it as the first thing to measure post-deploy.

---

## Section 3 — The change

### 3.1 Interface

`EligibilityPolicy.resolve` gains the candidate's `TargetType`:

```java
EligibilityDecision resolve(int daysAhead, TargetType targetType, ForecastStability stability,
        EvaluationModel nearTermModel, EvaluationModel farTermModel);
```

This is a **missing parameter restored, not complexity added**. The intraday window is *defined* in
terms of target types — `IntradayCandidateCollectionStrategy` returns sunset for today and either
for tomorrow — yet the policy gating that window cannot see them.

### 3.2 Intraday policy

| horizon + event | SETTLED | TRANSITIONAL / UNSETTLED |
|---|---|---|
| T+0 sunset | **include** (new) | include |
| T+1 sunrise | **include** (new) | include |
| T+1 sunset | skip | include |
| any `HOURLY` | skip | skip |

**Why T+1 sunset keeps the skip, on a new justification.** The original rationale is dead. The
replacement is that T+1 sunset is the only slot in the window with a *later* look still coming — it
will be re-evaluated at 01:00 tonight, and again at 14:00 tomorrow when it is T+0 sunset, a slot
this very change un-skips. Both are before the event and before any decision about it, and both are
structurally guaranteed rather than incidental: `NightlyEligibilityPolicy:45-46` includes every
stability at T+0/T+1. Skipping it therefore costs nothing. T+1 sunrise has no such later look,
because its only remaining run is the 01:00 one nobody reads.

**The argument is one-directional and only concerns SETTLED.** It says "adding a call for the
calmest cells buys nothing when two more looks are already guaranteed" — it does *not* license
withdrawing the existing TRANSITIONAL/UNSETTLED include at T+1 sunset, which is a different claim
about the most volatile cells. Nothing here removes an evaluation that happens today.

**On `HOURLY`**: `TargetType` has a third value (`TargetType:15`). It cannot reach this policy —
`BriefingHierarchyBuilder:57` emits only SUNRISE/SUNSET, and `IntradayCandidateCollectionStrategy`
rejects it regardless — but an exhaustive switch needs the arm, and skipping is the safe default.

This distinction is the entire reason for the interface change. If it does not survive review, the
right change is smaller (§6, option A or C). *Review outcome: it survived — see §9.*

### 3.3 Nightly policy

Unchanged. `NightlyEligibilityPolicy` already includes every stability at T+0/T+1; it takes the new
parameter and ignores it.

### 3.4 Files

| File | Change |
|---|---|
| `EligibilityPolicy` | add `TargetType` to `resolve` |
| `IntradayEligibilityPolicy` | the §3.2 table |
| `NightlyEligibilityPolicy` | accept and ignore the parameter; `permitsHorizon` (`:71`) passes one through |
| `ForecastTaskCollector:448` | pass `candidate.targetType()` — the live intraday/nightly call |
| `ForecastTaskCollector:708-715` | `resolveEligibility`, the legacy static delegate |
| `ForecastTaskCollector:803` | the region-filtered **admin** path |
| `ForecastCommandExecutor:620-625` | via `permitsHorizon` — **resolved, see below** |
| `ReclassSummary:51` | renders `"… unsettled-evaluated"`; after this change most of `evaluated` is SETTLED. Shown on the admin Pipeline Runs panel (`PipelineRunsView.jsx:503`). Make the phrase stability-neutral or carry the split |
| `application-example.yml:210-212` | asserts *"BOTH cycles refresh a T+1 slot"* — stays untrue for SETTLED T+1 sunset |
| `scheduler_job_config` row for `intraday_forecast_refresh` | see the migration note below |

**`permitsHorizon` has a target type available — the §6 escape does not fire.**
`ForecastCommandExecutor:620-625` streams `ForecastPreEvalResult`, which declares
`TargetType targetType` (`ForecastPreEvalResult:41`). So the synchronous engine can pass a real
value rather than inventing one, and the widened interface is honest at every call site. Nightly
has no target-type-sensitive branch, so its behaviour is unchanged either way.

**The V105 description needs a migration, not an edit.** V105's `INSERT` has already run in
production, and `application-prod.yml:27` sets `validate-on-migrate: false`, so editing the applied
file changes nothing on the box *and* raises no checksum error — a silent no-op. Write a new
`UPDATE scheduler_job_config SET description = … WHERE job_key = 'intraday_forecast_refresh'`
migration, on the pattern of `V139__tide_refresh_description_horizon.sql`. Read the next free
number from the tree at the time (`ls backend/src/main/resources/db/migration/ | sort -V | tail -1`),
per CLAUDE.md.

---

## Section 4 — Tests

| Test | Change |
|---|---|
| `IntradayEligibilityPolicyTest` | rewrite: SETTLED included at T+0 sunset and T+1 sunrise, still skipped at T+1 sunset with `SKIPPED_NO_REFRESH_NEEDED`; TRANSITIONAL/UNSETTLED unchanged at all three |
| `NightlyEligibilityPolicyTest` | signature only. `permitsHorizon_mirrorsResolve` (`:47-56`) is the only exhaustive `resolve()` cross-product — wrap it in `TargetType.values()` so the no-op is proven, not assumed |
| `ForecastTaskCollectorEligibilityPolicyTest` | **omitted from the first draft.** 15 `@CsvSource` rows + 4 boundary tests, all calling the 4-arg `resolveEligibility`. Signature update throughout |
| `ForecastTaskCollectorTest:1143-1155` | the intraday SETTLED case — currently asserts a skip at what becomes an included slot |
| `ForecastTaskCollectorTest` — **new** | a T+1 **SUNSET** SETTLED case asserting `SKIPPED_NO_REFRESH_NEEDED`, paired with the flipped T+1 sunrise one. Requires parameterising the fixture helper, which hardcodes `new BriefingEventSummary(TargetType.SUNRISE, …)` at `:1441` |
| `OrchestratedDispositionWriteIntegrationTest:252` | asserts intraday persists `SKIPPED_NO_REFRESH_NEEDED` for a T+0 SUNSET candidate — exactly the slot this change stops skipping. Move to T+1 sunset. Needs Docker, so it cannot be verified locally |

**The paired collector cases are the point, not padding.** `ForecastTaskCollectorTest:1143` and its
sibling at `:1178` are the *only* two real-collector intraday cases in the tree. Flipping one and
adding nothing leaves the retained T+1-sunset skip — the entire justification for the interface
change — covered by an isolated policy unit test that cannot see whether `candidate.targetType()`
ever reaches the policy. A hardcoded `SUNRISE` at the call site would pass every test in the first
draft's list. The include/skip **pair** is what pins the wiring.

The nightly no-op is the other thing worth over-testing: largest blast radius, least interesting
behaviour.

---

## Section 5 — Cost

Settled skips by horizon, 14 days: T+0 = 236 (~17/day), T+1 = 905 (~65/day).

T+1 covers sunrise **and** sunset and only sunrise is being freed, so the naive total is
17 + 32 ≈ **49/day** on the near-term model.

⚠️ **Hold that loosely.** Sunrise and sunset rosters are not the same set of locations
(`SolarEventType` makes some sites one or the other), so the T+1 figure will not split evenly and I
cannot tell which way from the data I have. The T+1 sunrise share could plausibly be anywhere from
20 to 45/day. Splitting the §1.3 query by `target_type` would settle it in one query and should be
run before this is costed.

A second, larger effect is not in that number: post-#471 the intraday cycle will already be doing
more work than the 10 Aug sample shows, because the 419 slots it skipped as CACHED that day now
reach the policy. This change decides what happens to the settled ones among them.

---

## Section 6 — Alternatives considered

**A — T+0 sunset only.** Three lines, no interface change: in the intraday cycle `daysAhead == 0`
already implies sunset, because of how `IntradayCandidateCollectionStrategy` is written. Cheapest
(~17/day) and lowest risk. Rejected because it leaves sunrise unserved, which §2 argues is half the
problem — and because it works by exploiting a coincidence of the window's shape rather than saying
what it means, so it needs a comment warning the next person who widens that window.

**C — drop the settled skip entirely.** `resolve` collapses to `return include(nearTermModel)`. One
line, but it orphans three things whose only purpose is this gate: `ReclassSummary`'s
`settledSkipped` counter, the intraday `STABILITY_RECLASSIFY` phase (`PipelinePhase:31` documents it
as the cost gate), and the ephemeral stability re-classification, whose sole consumer is this
policy. Finishing the job — removing the phase and the classification — is a *larger* change than
the one proposed here, though it ends with less code. Rejected for now because the T+1 sunset skip
retains a valid justification (§3.2), so the gate is not vestigial. **If review kills that
justification, C becomes the right answer** and this plan should be replaced rather than trimmed.

---

## Section 7 — What this does not fix

14:00 UTC is a fixed clock time against a sunset that moves about five hours across the year. With
delivery at 15:38–16:53 (§2.1), a late-December sunset at ~15:35 is **already over** by the time
the rating exists. Not "too late to drive" — the forecast does not exist when the sun sets. In
winter this cycle is **actively wasteful**: real spend on output nobody can read.

Three things follow, and the order matters.

**This is pre-existing, not introduced.** `IntradayEligibilityPolicy:51` already submits T+0 sunset
for TRANSITIONAL and UNSETTLED cells every winter afternoon, and those already arrive after dark.
This change adds SETTLED cells to a winter cycle that is already wasted. It makes an existing
defect somewhat more expensive; it does not create one. That is why staging is defensible.

**The project already knew, and already chose the fix.** `CHANGELOG.md:2670` records it verbatim —
*"in winter (sunset ~15:30 UTC) a 3h intraday run completes after the event it exists to inform.
The proper fix is event-relative intraday scheduling (fire at soonest-event minus ~4h); this
latency data is its concrete justification"* — with a pointer to `docs/product/backlog.md`. An
earlier draft of this document re-derived that question and got the arithmetic wrong. Read the
backlog item before starting stage two.

**But that backlog item needs one correction, which is this document's real contribution.**
"Soonest-event minus ~4h" is right for sunset and **wrong for sunrise**: T-4h before a 05:00
sunrise is 01:00, the exact failure §2 exists to describe. Event-relative firing optimises delivery
against the event; what a user needs is delivery before the *decision*, and for sunrise those are
about seven hours apart. A decision-relative schedule fires late afternoon or early evening
year-round and covers tonight's sunset and tomorrow's sunrise together — which is what the 14:00
run does in August by accident and in December not at all.

**Do not attempt to patch this in stage one.** An event-time guard bolted onto
`IntradayCandidateCollectionStrategy` would suppress the winter waste, but it would do so in the
class that decides *membership*, where an exclusion writes no `CandidateDisposition`
(`BriefingCandidateCollector:201-207`) and bypasses the force-eval override — so the cycle would go
quiet in winter with no record of why. The trigger is the right place. Stage two.

---

## Section 8 — Open questions, answered

**Q1 — Is the T+1 sunset distinction worth an interface change, or is option C better?**
Worth it; C is not better. The justification survives: both later looks are structurally
guaranteed, not incidental (§3.2). The rule also cannot be moved into
`IntradayCandidateCollectionStrategy` to dodge the interface change — that class receives only a
`Clock` (`:49-51`) so it cannot see stability, and window exclusion writes no disposition and
bypasses force-eval.

**Q2 — Does `permitsHorizon` have a target type in scope?** Yes, via `ForecastPreEvalResult:41`.
See §3.4. The escape hatch does not fire; widen the interface.

**Q3 — Is the evidence strong enough?** Yes, but from the 24h/48h buckets, not the 12h one (§1.2).
**Do not gate on `actual_outcome`** — it has zero rows and no filling mechanism, so that is a
permanent block dressed as a delay, not a prerequisite.

**Q4 — Load problems?** Not from volume. The marginal ~49 requests land inside batches that already
exist — `ScheduledBatchEvaluationService:382-421` submits at most six buckets, each gated on being
non-empty, so the batch *count* does not move, and the 08 Aug sample already carried 377. Note the
plan's own §1.3 contradicts the intuition that settled days are the heavy ones: the most-settled
sampled day had 113 settled skips, the mixed day 232. The two real risks are latency (§2.1) and the
briefing-skip path below.

⚠️ **Q4a — a failure mode this change makes reachable for the first time.** Today a settled
afternoon submits zero batches, so `allTerminal()` short-circuits on `total == 0`
(`PipelineOrchestrator:632-634`) and BRIEFING always runs. Once batches exist, the WAIT safety
timeout becomes reachable on settled days — and that path (`:437-449`) does `failPhase` → `failRun`
→ return, never reaching `startPhase(BRIEFING)` at `:425`. The cycle would lose the briefing
rebuild, the best-bet Claude call and `persistPicksForCycle`. Per-location ratings still land
(`BatchPollingService` polls SUBMITTED batches independently of the run, and
`BriefingService.reEnrichVerdicts` re-derives verdicts at serve time), so nothing is reverted — but
a settled afternoon can now fail in a way it structurally could not before. Watch it.

**Q5 — Is "asleep at 01:00" safe to design around?** Yes for a single-user pilot; the §2 caveat is
adequate and it is cheap to falsify later.

### Post-deploy, in order

1. **`FORECAST_BATCH_WAIT` duration** against the 98–173 min baseline. This is the number §2.1 rests
   on and it has never actually been measured from `forecast_batch` timings.
2. `SKIPPED_NO_REFRESH_NEEDED` at T+0 should fall to zero; at T+1 it should persist (that is the
   retained sunset skip, working).
3. Split the §5 disposition query by `target_type` to replace the 20–45/day guess with a figure.
4. Any `FAILED` intraday `pipeline_run` — per Q4a, previously near-impossible on a settled day.

---

## Section 8a — RESOLVED: the `daysAhead` timezone basis

**Fixed 2026-08-12, as its own commit, before stage two. Kept here because the reasoning for the
shape chosen is worth more than the diff.**

The §9 review of the shipped diff found that the `daysAhead` reaching the eligibility policy was on
a **UTC** calendar, while the intraday window, the past-date filter and the disposition rows were
all on **Europe/London**:

| value | basis | used for |
|---|---|---|
| `preEval.daysAhead()` (`ForecastService:290`) | UTC | the policy call at `ForecastTaskCollector:448` |
| `candidateDaysAhead` (`BriefingCandidateCollector.daysAheadFor`) | London | the `CandidateDisposition` rows at `:457`, `:600` |
| `IntradayCandidateCollectionStrategy:50` | London | which slots enter the window at all |

The §8.1 note in the predecessor plan spotted this and judged it inert. **This change is what made
it decision-relevant**, because `IntradayEligibilityPolicy` is the cycle's first consumer of
`daysAhead`. §8.1 also described the audit damage correctly — "the `days_ahead` column already mixes
two definitions across disposition categories" — which is what this section then lost in restating
it; see the correction below.

**Blast radius, established under refutation and not merely asserted.** Under BST between 23:00 and
00:00 UTC the two bases differ by a day. Of the three window slots only the nearest sunset flipped,
and it flipped towards *skip* — against intent. No forecast reached a reader stale: the 01:00
nightly picks that slot up 61–120 min later at T+0, where `NightlyEligibilityPolicy` admits every
stability ~18h before the event. What was lost was one afternoon look.

⚠️ **The audit-row half of this section was wrong, and the adversarial review on the fix killed it.**
§8a predicted the row "*would* read `daysAhead=0` beside a skip reason saying two further looks are
guaranteed", and the fix commit initially promoted that prediction to a statement of history. It was
never true. Pre-fix, `ForecastTaskCollector:446` set `int daysAhead = preEval.daysAhead()` (UTC) and
fed **that** to both `eligibilityPolicy.resolve` and the stability-skip disposition at `:475`, so the
row read `1` and agreed with the reason beside it. `candidateDaysAhead` (London) reached only the
SKIPPED_TRIAGED row at `:441` and the SKIPPED_ERROR row at `:600`. The real audit defect is
different, and is the one the fix addresses: **which calendar a run's trail spoke depended on which
branch each candidate took**, so a single run could carry two horizons a day apart for the same
date. Recorded here because a prediction that was never checked became a claim in four documents.

It was also **not reachable on the seeded cron** (`0 0 14 * * *`), nor on the late-afternoon
schedule stage two proposes — only via a manual `POST /api/admin/scheduler/jobs/{jobKey}/trigger`
inside that one-hour band.

### What was done, and why not the one-line fix

§8a originally proposed passing `candidateDaysAhead` at `ForecastTaskCollector:447`. **That was
reconsidered at implementation time and rejected as too narrow**, on two findings the plan did not
have:

1. **There was a second UTC derivation.** `ForecastService:138`, in the synchronous `runForecasts`
   path, computed `daysAhead` on UTC exactly as `:290` did. The one-line fix does not touch it.
2. **The horizon is persisted, and something else is derived from it.** `daysAhead` is written to
   `forecast_evaluation.days_ahead` and is the sole input to the `confidence` column (V127, via
   `ConfidenceDeriver.fromHorizon` at `ForecastService:646`). In the divergent hour both were
   recorded one band too far out. A collector-local fix leaves two columns wrong and leaves the
   synchronous engine's Gate 4 (`ForecastCommandExecutor:625`) reading a horizon it will act on.

Since the domain rule — *"a sunrise in Northumberland on April 19th BST is what matters, not the UTC
date"* — makes **UTC the bug**, the fix was applied at source. The rule now has exactly one home,
`util/ForecastHorizon`, and every derivation delegates to it: `ForecastService` (both sites, with a
`Clock` injected from the existing `AppConfig` bean), `BriefingCandidateCollector`'s inline loop
copy, `IntradayCandidateCollectionStrategy`, and — briefly — `ForecastDtoMapper`.

⚠️ **The `ForecastDtoMapper` leg no longer exists, and the way it was resolved is the point.** It was
found during the fix rather than before it: `toSparseDto` derived a **serve-time** horizon on UTC, so
leaving it would have let the horizon *shown* disagree with the one *stored* on the same row. The
adversarial review then established that `toSparseDto` had **no production caller** — orphaned by
#289, with the live `toDto` path serving `entity.getDaysAhead()` from the persisted column this
commit fixes at source. So the correct move was not to keep a dormant method on the right calendar
but to delete it, which a follow-up did. Recorded because the first instinct was to harden dead code
rather than ask whether it should exist: a method with no callers cannot have a timezone bug.
`BriefingCandidateCollector.daysAheadFor` is gone rather than delegating: it had one caller and one
line, and a second door to the same rule on a class named for briefing candidates is how the rule
gets re-derived next time. It was deliberately **not** routed
through `BriefingCandidateCollector.daysAheadFor` as §8a suggested: the synchronous engine calling a
*briefing candidate collector* for a general horizon rule is a name that lies, and the neutral home
costs one small class.

The collector loop additionally now uses **one** horizon variable for both the policy call and every
disposition row it emits, so those two can no longer disagree by construction rather than by
coincidence.

**What this changes beyond the intraday bug**, stated plainly because it is a behaviour change to a
different engine path:

- Nightly Gate 4, in that same hour, no longer drops a London-T+3 SETTLED candidate as UTC-T+4. The
  direction is *toward* the policy's stated intent, and it admits slots rather than dropping them.
- `forecast_evaluation.days_ahead` and `confidence` become correct in that hour. No migration and no
  backfill: historical rows written in the band keep the value they were written with. There are few
  of them and they are one band pessimistic, which is the safe direction for an analytics column
  that gates nothing.

### What was deliberately left

- **The synchronous engine's date *range* is still UTC-derived** (`ForecastCommandFactory:107`,
  and the same-day past-event check at `ForecastCommandExecutor:236`). That selects *which dates* a
  run covers, not how far ahead they are, so it is an adjacent divergence rather than this one. In
  the band a run's range therefore starts on the UK's *yesterday*. **The default range persists no
  negative horizon**, contrary to an earlier draft of this note: `ForecastCommandExecutor:264`
  applies `shouldSkipEvent` unconditionally, and for that first date — UTC-today, UK-yesterday —
  both solar events are already in the past at 23:00–00:00 UTC, so every slot on it is skipped
  before any row is written. (An explicitly-requested past date still yields a negative, as it did
  before this commit on the UTC basis — unchanged either way.) The residual is milder and pre-existing: in that hour the range spends one of its days on
  a UK day that is entirely skipped, so it reaches one fewer future UK day than intended. What this
  commit *does* change there is that the first actionable day (UTC-today+1 = UK-today) is now
  correctly labelled T+0 rather than T+1, which also moves it from the far-term to the near-term
  model tier. Moving the range itself wants its own commit and its own reasoning about
  `shouldSkipEvent`, which compares UTC instants.
- **Four batch classes still hand-roll the London rule** — `ForceEvalHeadlineSelector:94`,
  `BatchRetryService:296`, `ScheduledBatchEvaluationService:486`, `BriefingRollupBuilder:113`. All
  four are already on `Europe/London`, so this is duplication, not divergence, and nothing behaves
  differently. Worth collapsing onto `ForecastHorizon` opportunistically; not worth a behaviour-
  neutral sweep of its own here.
- **No backfill of historical rows.** Rows written in that hour keep the horizon they were written
  with. They are few, and they are one band pessimistic on a column that gates nothing.

### Tests

`ForecastTaskCollectorHorizonBasisTest` is the case §8a asked for: `IntradayCandidateCollectionStrategy`
paired with `IntradayEligibilityPolicy` against a clock pinned at `2026-08-11T23:30:00Z`, with the
stubbed pre-eval carrying **the horizon UTC would have produced**, so the fixture reproduces the
disagreement instead of describing it. It asserts the premise (the two calendars differ by exactly one
day at that instant), that tonight's SETTLED sunset is evaluated, that its audit row reads T+0, and
that tomorrow's SETTLED sunset still skips — the last one so the fix cannot quietly become a blanket
relaxation. `ForecastServiceTest.DaysAheadBasis` pins the same rule at source, and
`ForecastHorizonTest` pins it as arithmetic, including a GMT case proving it is a zone rule rather
than an offset hack applied to late evenings.

All three were **mutation-verified**: reverting the collector to `preEval.daysAhead()` kills 2 cases;
reverting `ForecastHorizon` to UTC kills 5 across both classes.

## Section 9 — Review provenance

Revised 2026-08-11 after an adversarial review run to CLAUDE.md's cadence: six prosecutor lenses
over the plan and the code it touches, one refutation agent per charge defaulting to REFUTED
without citable evidence, then a synthesis pass. 18 charges verified — 10 survived, 8 refuted, 6
fell below a per-lens cap of 3 and were never verified. Reviewers were told explicitly that this
change affects forecasts people act on, and not to dismiss a correctness charge as unlikely without
evidence.

**No correctness defect was found in the proposed policy**, and the contested leg — the retained
T+1 sunset skip — held up under direct attack. Every amendment above is to the document's evidence,
arithmetic, inventories or framing.

The material findings, in the order they changed the document:

1. **Notice was measured from the fire, not from delivery** (§2.1, §7). The winter conclusion
   inverted: not "95 minutes, too late to drive" but negative — the rating does not exist at
   sunset. The repo had already recorded this in `CHANGELOG.md:2670` and the plan had not carried
   it forward.
2. **The headline evidence bucket was the wrong one** (§1.2). The 12h row is structurally the
   gate's complement.
3. **The test plan lost the distinction the change exists for** (§4). Flipping one collector case
   without adding its opposite leaves the wiring unproven.
4. **Three operator/config strings become false**, one of which needs a migration rather than an
   edit because `validate-on-migrate: false` makes an in-place change silent (§3.4).
5. **A new failure mode**: the WAIT timeout can now skip the briefing on a settled day (§8 Q4a).

### What the review could not check

- **No production database.** Every figure in §1 and §5 was taken as given; only derivation and
  interpretation were attacked. The T+1 sunrise/sunset split remains unresolved.
- **No measurement of real intraday latency.** §2.1 rests on a javadoc note and the CHANGELOG, not
  on `forecast_batch` timings. A p95 would strengthen or weaken §7 either way.
- **Nothing was built or run.** The test-coverage claims are read off sources, not observed.
- **Not read**: `BatchResultProcessor` merge semantics, `EvaluationService` bucket sizing,
  `BatchWeatherPrefetcher` rate-limit headroom under a larger candidate set, and
  `ForecastStabilityClassifier` — whether the 14:00 re-classification materially shifts the SETTLED
  population, which would move every cost figure in this document in both directions.
- **Not assessed**: whether the change is worth doing on product grounds beyond §2's ordering
  argument, and the stage-two trigger redesign itself.

---

## Section 10 — Review of the shipped diff

The plan review in §9 could not catch implementation defects, because there was no implementation.
Commit `230681b` was therefore put through the same cadence a second time, against the diff. Same
shape: six lenses, one refutation agent per charge defaulting to REFUTED, synthesis. 18 charges
verified — 5 survived, 13 refuted, 6 below the per-lens cap. Reviewers were read-only and, unlike
the plan round, explicitly barred from running the build.

**Verdict: fix two Javadoc lines, then ship.** No behavioural change was required. Every substantive
attack on the eligibility rule itself was refuted against source: briefing loss on a settled
afternoon, the "two further looks" claim in winter, unbounded cost, duplicate admin triggers, the
unbounded `daysAhead > TODAY` predicate, V141's scheduling guarantee, and the CHANGELOG's evidence.

What was fixed here:

- **`PipelinePhase:29-31`** described the intraday gate as choosing "(TRANSITIONAL/UNSETTLED) versus
  skipped as settled". False as of the same diff. Worse, this plan cites that line at §6 and the
  commit changed `ReclassSummary` for exactly this reason — the line was read during the change and
  left standing. Now points at the policy instead of restating a rule that has already narrowed once.
- **`EligibilityDecision:19-20`** glossed the intraday skip as *"nothing has changed, no refresh
  needed"*, which `IntradayEligibilityPolicy` explicitly rebuts three files away.
- **`resolveIsEventBlind`** asserted `eligible()` and `model()` while claiming to prove the decision
  invariant. `EligibilityDecision` is a record, so whole-record equality is shorter and also pins
  `skipReason` and `skipDisposition`.

One finding became §8a rather than a fix. Two refutations are worth recording because they rejected
*fixes that were worse than the code*: running BRIEFING inside the timeout handler would persist a
half-rated best bet, reverting V103; and `daysAhead == TOMORROW` would have evaluated the further
slot while skipping the nearer one.

### What this review could not check

- **No build, tests or lint** — the read-only mandate. The commit's "6882 tests pass, Checkstyle 0,
  SpotBugs 0, JaCoCo passes" was verified by the author, not by the review. Integration tests need
  Docker and have been run by nobody.
- **No production database.** Every empirical figure — the 63%/72% star movement, the 419 cache
  skips, the 98–173 min batch latency, all §1.3 counts — taken as given.
- **`ForecastStabilityClassifier` / `GridCellStabilityService`**: whether the ephemeral 14:00
  re-classification materially shifts the SETTLED population. Every cost figure in this document
  moves with that, in both directions, and no lens read it.
- **Downstream of the collector**: `BatchRequestFactory` bucketing and prompt-cache homogeneity,
  `BatchResultProcessor` merge semantics, `BatchWeatherPrefetcher` headroom under a larger candidate
  set, Anthropic rate limits.
