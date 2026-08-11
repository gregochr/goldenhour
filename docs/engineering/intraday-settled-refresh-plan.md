# Intraday settled refresh — the decision-time problem

Status: plan, awaiting approval and adversarial review. Written 2026-08-11 against `main` at
`d7e3c77`, i.e. with both #471 (horizon-aware cache freshness) and #472 (per-location delta age)
already merged. No production code modified. This is "Phase 2" as deferred by §4 of
[horizon-aware-freshness-plan.md](horizon-aware-freshness-plan.md), but the framing has changed
enough since that section was written that it needs its own document.

---

## TL;DR

- **The issue**: the 14:00 UTC intraday cycle skips every SETTLED location, on the stated grounds
  that "the synoptic pattern has not moved since the morning, so the nightly evaluation still
  holds." That is the same claim the 36h freshness threshold rested on, and the same evidence
  falsifies it. On a settled afternoon the cycle therefore does nothing at all.
- **What that costs**: the last look at tonight's sunset is the 01:00 cycle, ~19h before the event.
- **The reframe** (the part §4 got wrong): ranking runs by *lead time before the event* is the
  wrong metric. What matters is lead time before the **decision**. For sunset the two roughly
  coincide. For sunrise they do not — the 01:00 run is 4h before a 05:00 sunrise and 0h before any
  decision a sleeping person can make.
- **The change**: widen `EligibilityPolicy.resolve` to take the target type; in the intraday cycle
  include SETTLED for **T+0 sunset** and **T+1 sunrise**, and keep skipping it for **T+1 sunset**.
- **Cost**: ~49 extra near-term evaluations/day, held loosely (see §5).
- **What it does not fix**: 14:00 is a fixed clock time against events that move ~5h across the
  year. This is the cheap step, not the finished answer (§7).

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
falsifies it. From `evaluation_delta_log`, 30 days, SETTLED rows — the 12h bucket is the one that
matters here, because it is closest to the ~13h gap between the 01:00 write and the 14:00 cycle:

| gap | refreshes | mean Δ★ | moved ≥1★ | moved ≥2★ |
|---|---|---|---|---|
| **12h** | 232 | **1.13** | **171 (74%)** | 73 (31%) |
| 24h | 1024 | 0.97 | 648 (63%) | 268 (26%) |
| 48h | 625 | 1.22 | 447 (72%) | 223 (36%) |

Synoptic persistence is not rating persistence. A blocking high is stable; the low cloud loitering
on the western horizon underneath it is not, and that is what the rating turns on.

⚠️ **Caveats on this table, carried forward from the freshness plan §1.4 and still true.** These
are refreshes that *happened*, so the population is the gate's complement — a SETTLED slot
refreshed at 12h got through despite a 36h threshold, most likely because the gate read the
region's most volatile stability from the previous snapshot while the delta row is stamped with the
location's own from the new one. And every row here pre-dates #472, so `age_hours` was measured per
cache key rather than per location. The 12h bucket should be largely free of the units artefact
(which manufactures ~0h rows, not 12–18h ones) but is not free of the selection effect. **Nothing
in this table is a clean experiment.** It is suggestive, consistent across three buckets, and
consistent with the one direct observation below.

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

| event | decision taken | run that serves it | notice at the decision |
|---|---|---|---|
| sunset (~19:50 Aug) | that afternoon | 14:00 | ~6h ✅ |
| sunrise (~05:00 Aug) | previous evening | 14:00 | ~15h ✅ |
| sunrise | — | 01:00 | ~0h ❌ (asleep) |

The 01:00 run still earns its place: it is the freshest thing available to someone genuinely awake
at 04:00, and it already evaluates every stability at T+0/T+1, so that coverage costs nothing extra.
But it cannot be the *primary* signal for a sunrise, and the current design treats it as if it were.

⚠️ **"You are asleep at 01:00" is a product judgement about one operator's behaviour, not a
measurement.** It is almost certainly right for a single-user pilot and should be revisited if the
user base ever includes people in other timezones reading the same UK forecasts.

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

**Why T+1 sunset keeps the skip, on a new justification.** The original rationale is dead. The
replacement is that T+1 sunset is the only slot in the window with a *later* look still coming — it
will be re-evaluated at 01:00 tonight and again at 14:00 tomorrow, both before the event and both
before any decision about it. Skipping it costs nothing. T+1 sunrise has no such later look, because
its only remaining run is the 01:00 one nobody reads.

This distinction is the entire reason for the interface change. If it does not survive review, the
right change is smaller (§6, option A or C).

### 3.3 Nightly policy

Unchanged. `NightlyEligibilityPolicy` already includes every stability at T+0/T+1; it takes the new
parameter and ignores it.

### 3.4 Files

| File | Change |
|---|---|
| `EligibilityPolicy` | add `TargetType` to `resolve` |
| `IntradayEligibilityPolicy` | the §3.2 table |
| `NightlyEligibilityPolicy` | accept and ignore the parameter; `permitsHorizon` passes a value |
| `ForecastTaskCollector:~448` | pass `candidate.targetType()` |
| `ForecastCommandExecutor` | via `permitsHorizon` — check whether it has a target type in scope |
| `V105` scheduler row description | says "gated to TRANSITIONAL/UNSETTLED"; will be wrong |

⚠️ `permitsHorizon` is the synchronous engine's shared read of the nightly table. If it has no
target type to pass, that is a signal the parameter belongs somewhere else — see §6.

---

## Section 4 — Tests

| Test | Change |
|---|---|
| `IntradayEligibilityPolicyTest` | rewrite: SETTLED included at T+0 sunset and T+1 sunrise, still skipped at T+1 sunset with `SKIPPED_NO_REFRESH_NEEDED`; TRANSITIONAL/UNSETTLED unchanged at all three |
| `NightlyEligibilityPolicyTest` | signature only; behaviour must be provably unchanged across all (horizon × stability × targetType) |
| `ForecastTaskCollectorTest:1117` | the intraday SETTLED disposition case — currently asserts a skip at what is now an included slot |
| `OrchestratedDispositionWriteIntegrationTest:252` | asserts intraday persists `SKIPPED_NO_REFRESH_NEEDED` for a T+0 SUNSET candidate. That is exactly the slot this change stops skipping — must move to T+1 sunset. Needs Docker |

The nightly no-op is the one worth over-testing: it is the change's largest blast radius and its
least interesting behaviour.

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

14:00 UTC is a fixed clock time against a sunset that moves about five hours across the year. In
August it is 6h of notice; in late December, sunset is ~15:35 and it would be **95 minutes** —
fresh, but too late to decide to drive anywhere. So this change is right for roughly half the year
and close to useless for the other half.

The general answer is to schedule runs against **decisions**, not against the clock and not against
events. Note this is *not* the same as the event-relative firing §7 of the freshness plan
recommended: T-4h before a sunrise is 01:00, which is the failure this document exists to describe.
A decision-relative schedule would fire in the late afternoon/early evening year-round, covering
tonight's sunset and tomorrow's sunrise together — which is, not coincidentally, what the 14:00 run
does in August by accident.

That is a trigger redesign. This plan is the cheap step that makes the existing trigger honest.

---

## Section 8 — Open questions for review

1. Is the T+1 sunset distinction worth an interface change, or is C (drop the gate, finish the
   demolition) simply better?
2. Does `ForecastCommandExecutor.permitsHorizon` have a target type in scope? If not, is widening
   the shared interface the wrong shape — should intraday's rule live somewhere the sync engine
   does not see?
3. Is the §1.2 evidence strong enough to act on given both caveats, or does this need the
   `actual_outcome` feedback loop first?
4. Does making the intraday cycle do real work on settled days create load problems the 10 Aug
   sample (0 evaluations) hides — Open-Meteo prefetch, the Claude bulkhead, batch latency against
   the `PT4H` backstop?
5. Is "the operator is asleep at 01:00" a safe assumption to design around?
