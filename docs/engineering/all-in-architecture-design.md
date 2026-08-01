# The all-in architecture — design

Designed against `all-in-architecture-brief.md` §0–§2. Every code reference below was checked
against the tree, not against documentation — see §2.6 for why that distinction is load-bearing.

> **Provenance, and a staleness warning that proves the point.** §0–§2 and the summary-depth
> §3–§7 below were written against the working tree at `f56ed337` / `a484d1c4`, on branch
> `fix/release-ahead-guard`. **That branch is not an ancestor of `main`** (`git merge-base
> --is-ancestor a484d1c4 main` → false). The tree has since moved to `d421ef5f`, where
> `ForecastTaskCollector.java` is **899 lines, not 883** — a 16-line TIDE-LESS COASTAL
> diagnostic was added at `:537-552`. The build plan (`all-in-build-plan.md`) is re-verified and
> supersedes this document wherever they disagree.
>
> ⚠️ **Do not apply a blanket +16.** An earlier draft of this note said "every citation of that
> file past line 534 is off by +16". Both halves are wrong, and acting on it would *introduce*
> an error. The diff hunk is `536a537,552`, so old lines 1–**536** are unchanged — the boundary
> is 536, not 534. And the **only** `ForecastTaskCollector` citation past 534 in this document is
> `:733`, which is **already correct at HEAD** (the `briefingService.getCachedBriefing()` read
> inside `collectRegionFilteredBatches`) because it was written against `d421ef5f` after the
> warning. Adding 16 would break a correct citation. All four citations of that file verify at
> HEAD as written; the file is still 899 lines and unchanged since `d421ef5f`.
>
> Two further doc claims fell to the same rule while this was written. CLAUDE.md cites
> `src/test/java/**/regression/` as the protected test path — **that directory does not exist**;
> prompt-regression is selected by JUnit tag (`pom.xml:23`, `:29-32`). And CLAUDE.md's H2 claim
> was called false here in an earlier draft; **that correction was itself wrong** — see below.
>
> ⚠️ **Retraction (2026-08-01): "there is no H2 at runtime" is false, and this document asserted
> it.** `backend/src/main/resources/application-local.yml` is **tracked**, is a runtime profile,
> and is H2: `jdbc:h2:file:./data/goldenhour` (`:6`), `org.h2.Driver` (`:7`), `H2Dialect` (`:11`),
> `ddl-auto: update` (`:13`), `flyway.enabled: false` (`:20`), console on (`:21-23`).
> `pom.xml:247-249` says so in its own words — *"H2 — used in tests (@DataJpaTest) and local dev
> profile (H2 file DB). test scope makes it available for test execution and spring-boot:run."*
> And `backend/data/goldenhour.mv.db` is on disk. The `<scope>test</scope>` reading was right
> about the dependency and wrong about the conclusion: test scope still reaches `spring-boot:run`
> via Maven.
>
> What is true is narrower and belongs to the user, not the tree: **they do not use it** — local
> development is Postgres, and the `local` profile is a leftover to delete (build plan §2.8).
> The Postgres-only DDL decision **survives** on its own merits, because `local` has
> `flyway.enabled: false` and therefore never runs a migration at all. But the reasoning must be
> stated that way round. Note also that `application-local.yml` **changed since `d421ef5f`** —
> +15 lines of `photocast.tide-run` config appended at `:192-209`, the same block that landed in
> `-prod` and `-example`, so it looks like a blanket edit across profiles rather than active use.
>
> That is seven false doc claims now, two of them this document's own. Read the tree.

---

## §0 — Design posture

The brief's §0 is not preamble. It is a constraint on *how* this change is made, and it has
three consequences that shape everything below.

**Subtraction, not correction.** The redesign removes mechanisms that earned their place and
no longer do. Nothing here is a bug fix except point 7, which genuinely is one. Commit
messages should read `chore:` and `refactor:` far more often than `fix:`, and where a
mechanism is removed the message should name the measurement that retired it — "stability
gate guards 1.7% of $40.69/30d" — not the mechanism's supposed flaw.

**Preserve the knowledge, delete the mechanism.** Several subsystems on the deletion list
encode real meteorology in their comments:

| Subsystem | Knowledge worth keeping | Mechanism to delete |
|---|---|---|
| `FreshnessProperties` | 36h ≈ blocking-high persistence; 12h ≈ half a synoptic cycle (2× NWS 6h cadence); 4h ≈ outer edge of the nowcasting regime | per-stability cache TTL gate |
| `ForecastStability` | four-signal classification (pressure tendency, precip probability/variance, active-weather codes, gust variance) | snapshot persistence, FSM recovery, every gating read |
| `InversionScoreCalculator` | moisture counted once; STRONG requires a measured reversal | *nothing — not on the list, keep entire* |

The rationale column moves into this document or into the comment on whatever survives,
*before* the deletion commit. A deletion that takes the reasoning with it is how the same
mechanism gets reinvented in eighteen months.

**The evidence arrived; the design did not change.** This matters for review posture. When
reviewing the deletions, the question is not "was this a good idea?" — it was — but "does the
measurement that now exists retire it?" Those are different questions and only the second one
is open.

---

## §1 — The evidence base

All from production Postgres, all from queries run and pasted by the user.

| Measure | Source | Value |
|---|---|---|
| Cycle health, 30d | `pipeline_run` | 60 cycles, 1 failure — 30/30 nightly, 29/30 intraday, one stuck at `FORECAST_BATCH_WAIT` |
| Spend, 30d | `api_call_log` | $40.69, avg $0.00206/call |
| Cost gates guard | — | ~£193/year total — `SKIPPED_CACHED` 7.0/8.7%, SETTLED 13.0%, stability 1.7% |
| Stability gate alone | — | £16/year |
| Going all-in | — | +28% nightly, ~3× intraday |
| ERA5 verified rows | `cloud_verification` | 25,730 (100% backfilled) |

**The 60-cycles/1-failure line is why there is no pipeline FSM in this design.** Every defect a
state machine would model sits at zero. The one failure was a wait-phase hang, which the
existing `DEFAULT_SAFETY_TIMEOUT` backstop already bounds.

### The window is a light month — the sizing must be normalised

**That 30-day window contained ~15 travel days.** Travel days gate out whole candidate-days
(they are 52.8% of intraday skips, and a gate this design **keeps**), so $40.69 is roughly
a half-strength month. The user's at-home run rate is **~£51/month**.

Observed ≈ £31–32; at-home ≈ £51, a factor of **~1.6**. Since travel days remove candidate-days
roughly uniformly, baseline and delta scale together — the ratios hold, the absolutes move:

| | Light month (observed) | At-home rate |
|---|---|---|
| Baseline | ~£31 | ~£51 |
| All-in delta | +£20–30 | **+£32–48** |

> ⚠️ **Superseded — this figure assumed triage was retained.** Both this row and §2.3 computed
> the delta with the weather-triage and hard-constraint gates still in place. Under "no gates
> except travel days" they are gone too, so the evaluate count rises further — roughly by
> `(triaged + hard_constraint) / evaluated` on top of the figure above, which on
> `V101__forecast_run_disposition.sql:6-8`'s own sample is order **+35% more evaluations than
> this estimate assumes**. (That comment's numbers do not themselves add up — 163 + 48 + 41 + 2
> + 1 = 255, not the 242 it states — so treat it as an order-of-magnitude hint, not a
> measurement.) **Run the real query before anything ships**; it is the first item of the build
> plan, and it must be normalised by `job_run.started_at::date`, never by `evaluation_date`,
> which is the forecast *target* date and over overlapping 4–5-day windows counts ~34 distinct
> dates while saying nothing about how many cycles ran.

*(The £51 is the user's figure; the ×1.6 scaling of the delta is derived from it and endorsed
by "the all-in delta scales with it". Correct the derived row if the true at-home delta was
measured directly rather than scaled.)*

Two design consequences, both real:

- **Phase 2's cost gate must normalise by travel days** (§5), or comparing a travel-heavy week
  against a £20–30 threshold computed on a different travel mix reads as a pass that means
  nothing. Compare like-for-like periods or divide by non-travel days.
- **A travel-heavy month genuinely costing less is correct behaviour, not noise.** The travel
  gate survives this redesign precisely because it gates *unactionable* spend. Only the
  monitoring needs the normalisation; the system does not.

---

## §2 — Retractions, as binding constraints

Each retraction is restated as a rule the design must obey. §2.7 is the one that changes the
architecture rather than merely fencing it off.

**2.1 — No threshold moves on ERA5 evidence.** The +24.67pp offset is flat across lead time
(T+0 +25.19, T+1 +25.52, T+2 +21.01) and is therefore a dataset artefact, not forecast error.
*Constraint:* this design changes no cloud threshold, in either direction. The ERA5 harness
survives as a **relative** instrument — "did this change move the gap?" — never as an absolute
calibration source. Where a report renders an ERA5 delta, it must carry the offset caveat at
the point of display, not in a doc a reader may not have open.

**2.2 / 2.3 — The hopeless-slot gate stays at 80.** The "inverted veto" measured rows where
the veto never ran: triage fires at `solarLow > 80`, the veto-fired buckets had mean forecast
81.6 and 84.5, and triaged rows persist a null rating with no prompt built. Loosening the gate
also buys nothing visible — newly admitted slots all have solar low > 60%, which the prompt
already forces to rating 1–2. *Constraint:* "everything through Claude" means dropping
**re-evaluation** gates. The triage gate is not a re-evaluation gate and does not move. Same
output, one Claude call each, is not a win.

**2.4 — The unified table must be append-only.** Batch results go to `cached_evaluation`; the
only `forecast_evaluation` inserts on the batch path are triage rows
(`ForecastService.java:372`, `:402`). `CloudVerificationRepository.findUnverified` selects from
`ForecastEvaluationEntity`. So the harness's population *is* the triaged set, by construction,
and the decile-70→80 "reversal" is a composition change between two selection regimes.
*Constraint:* the unified table is a **projection over append-only history**, and the history
records skipped and triaged slots as first-class events. An UPDATE-in-place unified table
would preserve the blindness in a new schema.

**2.5 — D7 is closed.** −14.0pp both capped and uncapped. *Constraint:* no work on
`MAX_UPWIND_DISTANCE_M`. The capped/uncapped split stays in the report as a settled negative
result, so it is not re-opened.

**2.6 — Verify at source, never from docs.** Three doc claims misdirected work and were all
false on inspection. *Constraint, and it applied to this document:* every file path, line
number, constant and behaviour cited here was read from the tree. Two examples of the payoff
are in §4.2 — the `SYSTEM_PROMPT` already handles an absent reliability block, and
`ForceEvalHeadlineSelector` is dead the moment the stability gate goes. Neither is written down
anywhere.

**2.7 — Record what selected the rows.** Every retraction is a statistic computed over a
population selected by the thing being measured. *Constraint, and this one is structural:*
**every persisted decision records why it was made, in the same table as the outcomes.** The
codebase already has the asset — `CandidateDisposition` records a category for every slot the
collector considers, including skips, and `ForecastDispositionService` persists them. Today
that stream lives in `forecast_run_disposition`, separate from the results, which is precisely
what lets a query be written over survivors without noticing. The design merges them.

---

## §3 — The shape of the change

Three concerns are tangled, and all seven points of the brief are downstream of the tangle:

1. **Selection reads from publication.** `ForecastTaskCollector` builds its candidate set from
   `briefingService.getCachedBriefing()` (`ForecastTaskCollector.java:272`), and the cycle's
   tail rewrites that same briefing via `refreshBriefing()`
   (`PipelineOrchestrator.java:427`). The roster a cycle evaluates is the previous cycle's
   output. That is point 7, and it is the T+3 bug's mechanism.

2. **Persistence is split by engine, not by kind of fact.** The synchronous path writes rich
   rows to `forecast_evaluation`; the batch path writes region-grained JSON to
   `cached_evaluation` and only triage rows to `forecast_evaluation`. `EvaluationViewService`
   exists to merge them back at read time. That is §2.4 and point 4.

3. **Five gates key on one classification that earns 1.7%.** Freshness TTL, nightly stability,
   intraday settled-skip, optimisation strategies, force-eval-through-the-gate — all read
   `ForecastStability`. That is points 1, 2, 3 and most of 6.

The all-in architecture makes the cycle linear, with one persistence spine:

```
  SLATE   →   SELECT   →   EVALUATE   →   RECORD   →   PROJECT   →   PUBLISH
 weather      bounds        Claude       append-only    view       gloss+bestbet
  (free)      (cheap)       (£)          (durable)    (derived)      (£)
```

Each stage reads only from upstream. **No stage of the cycle reads the publication; serve-path
panels may.** That one rule dissolves the circularity, and it is the invariant to defend in review.

> **Corrected 2026-07-31.** This was first written as the snappier "publication is terminal —
> nothing reads it", which is false and would break any guard written against it:
> `CloseToHomeService.java:151` calls `getCachedBriefingForApi()` on the **serve** path, which
> creates no cycle. The precise form is the writable one. Two genuine breaks remain and are
> resolved in the build plan: `ForecastTaskCollector.java:733` (the region-filtered admin path,
> unaddressed) and `POST /api/briefing/run` → `republish()`, which under maximum deletion should
> go entirely — the twice-daily cycle already does the job, and an admin pressing it during an
> Open-Meteo degradation persists a sub-50%-coverage briefing over the last known good with no
> recovery path.

---

## §4 — The seven points

### 4.1 Everything through Claude — drop re-evaluation gates, keep the hopeless-slot gate

| Gate | Where | Share | Verdict |
|---|---|---|---|
| Weather triage (`solarLow > 80`) | `ForecastService.fetchWeatherAndTriage` | — | **Keep, at 80** (§2.3) |
| Travel day | `BriefingCandidateCollector:177` | 52.8% of intraday skips | **Keep** |
| Hard constraint (tide, verdict) | `BriefingCandidateCollector:239` | — | **Keep** |
| Past date / unknown location | `BriefingCandidateCollector:151`, `:259` | — | **Keep** (structural) |
| Horizon bound T+0..T+3 | `NightlyEligibilityPolicy` | — | **Keep** (as a bound, not a policy) |
| Freshness / `SKIPPED_CACHED` | `BriefingCandidateCollector:217` + `FreshnessResolver` | 7.0 / 8.7% | **Drop** |
| Intraday settled-skip | `IntradayEligibilityPolicy` | 13.0% | **Drop** |
| Nightly stability (T+2/T+3) | `NightlyEligibilityPolicy` | 1.7% | **Drop** |
| Optimisation strategies | `OptimisationSkipEvaluator` | — | **Drop** |

The four eligibility types collapse to one horizon-keyed model selector:

```java
/** Horizon bound + model tier. No stability, no freshness, no cost gate. */
public final class HorizonModelSelector {
    public static boolean withinHorizon(int daysAhead) { return daysAhead >= 0 && daysAhead <= 3; }
    public static EvaluationModel modelFor(int daysAhead) {
        return daysAhead <= 1 ? nearTerm : farTerm;   // NEAR→Sonnet, FAR→Haiku
    }
}
```

`EligibilityPolicy`, `EligibilityDecision`, `NightlyEligibilityPolicy` and
`IntradayEligibilityPolicy` all go. `ForecastCommandExecutor.applyStabilityFilter` calls
`withinHorizon` instead of `permitsHorizon`, so the two engines still share one bound.

**A deletion the brief does not list.** `ForceEvalHeadlineSelector` (167 lines), the
`forceEvalCap` property, the `forceEvalKeys`/`forcedCount` branch at
`ForecastTaskCollector:450–479`, and the `FORCE_EVALUATED` disposition exist *solely* to punch
headline contenders through the stability gate. With the gate gone they guard nothing — every
candidate within the horizon is evaluated. Deleting them removes the collector's only
non-uniform path.

### 4.2 Deletions, and what to re-home first

**Re-home before deleting:**

- **Model selection** → `HorizonModelSelector` above. This is the only thing
  `NightlyEligibilityPolicy` was doing that anyone still needs.
- **Freshness TTLs** → the staleness surface (§4.5). They stop being a *gate* and become a
  *display* threshold. Note honestly: with stability deleted there is nothing to key three
  TTLs on, so they collapse to one. The three-way meteorological rationale is preserved in §0's
  table, not in a constant nobody reads.

**DECIDED — the stability *display* survives; the stability *gates* and *persistence* do not.**
`ForecastStability` has user-visible readers that "delete the stability subsystem" would
otherwise take with it:

- `PromptBuilder:608–625` emits a `FORECAST RELIABILITY:` block into the **user message**.
- `BriefingRollupBuilder.java:403` reads `StabilitySnapshotProvider` and
  `appendStabilityToRegion` writes `stability` / `stabilityReason` into the rollup **JSON**
  (`:427`, `:429`) — which is the Claude **best-bet prompt**, not a rendered surface.
  `BriefingBestBetAdvisor.java:150` only passes the provider through to that builder.

**Keep the classifier as a pure inline function called at slate-build time for display and
prompt enrichment. Delete the snapshot table, the controller, `GridCellStabilityService`, and
every gating read.**

> ⚠️ **Corrected 2026-07-31 — this paragraph contained a fatal contradiction, and three design
> agents inherited it.** It previously also said "delete … the provider" in the same breath as
> naming the provider a consumer two lines above. `StabilitySnapshotProvider` is the **only**
> transport carrying stability out of the classifier — nine production call sites across eight
> classes, a count that verifies exactly at HEAD. Deleting it before rerouting silently degrades
> whatever depends on it.
>
> ⚠️ **Second correction (2026-08-01) — it is NOT a display path, and this document said so
> twice.** Verified at HEAD: `appendStabilityToRegion` writes into a Jackson `ObjectNode`
> (`BriefingRollupBuilder.java:427,429`); that node is the rollup JSON from `buildRollupJson`
> (`:111`); its only consumer is `BriefingBestBetAdvisor.advise` (`:202`) as the Claude
> **best-bet user message**. `BriefingRegion` has **no** stability component (`grep -n stability
> model/BriefingRegion.java` → nothing) and no frontend renders one. `BriefingBestBetAdvisor:150`
> is a DI pass-through, not a read. So stability is **prompt enrichment**, full stop.
>
> This sharpens the design rather than weakening it. The classify-for-display rule still keeps
> the classifier — but for the *prompt*, not the Plan tab. "Stability rides the slate" must
> therefore deliver a **prompt input**, not a display attribute on `BriefingRegion`.
>
> **Resolution (unchanged):** the reroute **must land before** anything deletes the provider —
> now because the best-bet prompt would otherwise lose an input with no visible symptom at all,
> which is harder to catch than a blank cell, not easier.
>
> A second hole sits behind it: `grep -rn withStability src` returns exactly **one** production
> producer of the `FORECAST RELIABILITY` block — `ForecastCommandExecutor.java:667`. The batch
> path has never emitted it. So if the synchronous engine is deleted, the block has no producer
> and the reason for keeping the classifier evaporates. **Decide before phase 1:** either the
> batch path starts emitting it (cost: zero API calls — the classification is arithmetic over
> weather already fetched at `ForecastTaskCollector.java:302-303`), or the classifier and the
> block both go and `SYSTEM_PROMPT` is left untouched (`PromptBuilder.java:225` already branches
> on absence).

The rationale, in the user's words: the cost was never in classifying — that is arithmetic over
weather already fetched — it was in the gating and the persistence. Deleting the classifier
would remove a user-facing signal to save nothing, and the Plan tab's confidence channel was a
deliberate product decision, not incidental. This is §0 working exactly as intended: the
mechanism goes, the knowledge stays where it earns its place.

**A prompt finding that makes this cheap.** `SYSTEM_PROMPT` already branches on the block's
absence:

> `PromptBuilder.java:225` — "When no FORECAST RELIABILITY block is present, or stability is
> SETTLED, make recommendations with full confidence."

So suppressing the per-request block needs **no system-prompt change at all** — and the system
prompt must not be trimmed to remove the stability guidance, because it is load-bearing for the
15,500-character Haiku cache floor (`MIN_CACHEABLE_SYSTEM_PROMPT_CHARS`, `PromptBuilder.java:65`).
Below that floor the request silently returns `cache_creation_input_tokens: 0` and pays full
input rate forever. **Leave `SYSTEM_PROMPT` untouched in every phase of this work.**

**Delete outright:** `FreshnessProperties`, `FreshnessResolver`, `IntradayEligibilityPolicy`,
`NightlyEligibilityPolicy`, `EligibilityPolicy`, `EligibilityDecision`,
`ForceEvalHeadlineSelector`, `OptimisationSkipEvaluator`, `OptimisationStrategyService`,
`OptimisationStrategyEntity`, `OptimisationStrategyType`, `StabilitySnapshotProvider`,
`StabilitySnapshotEntity`, `StabilitySnapshotRepository`, `StabilityController`,
`GridCellStabilityService`, `StabilitySummaryResponse`, `EvaluationDeltaLogEntity` +
repository.

### 4.3 Droppable data

| Object | Migration | Note |
|---|---|---|
| `stability_snapshot` (V98) | DROP | Recovery-across-restart for a gate that no longer exists |
| `evaluation_delta_log` (V97) | DROP | Existed to refine freshness thresholds empirically; there are no thresholds left to refine |
| `optimisation_strategy` rows | DELETE rows, DROP table | Also removes the mutual-exclusion validation surface |
| `forecast_evaluation` history | **Preserve** | Backfilled into `evaluation_event` (§4.4). ⚠️ **Do not drop it without remapping `cloud_verification` first** — see below |

> ⚠️ **`forecast_evaluation` carries a cascade that would destroy the ERA5 evidence base.**
> `V129__add_cloud_verification.sql:23,43` declares
> `forecast_evaluation_id BIGINT NOT NULL UNIQUE … REFERENCES forecast_evaluation (id) ON DELETE
> CASCADE`. Dropping or emptying `forecast_evaluation` silently destroys **all 25,730**
> cloud-verification rows — the project's only non-self-referential evidence outside the empty
> `actual_outcome` table. The remap onto `evaluation_event` must land first, and the `NOT NULL
> UNIQUE` must be dropped or made `DEFERRABLE` around the remap `UPDATE`: Postgres enforces
> non-deferrable unique constraints row-by-row, and since the backfill inserts one event per
> non-HOURLY evaluation the two id ranges overlap almost exactly, so a mid-statement duplicate-key
> failure is close to certain.

### 4.4 The unified table — a projection over append-only history

**`evaluation_event`** — one row per decision about a slot, per cycle. Never updated.

```
id, pipeline_run_id, location_id, target_date, target_type, prompt_kind

outcome            EVALUATED | TRIAGED | SKIPPED
skip_category      (DispositionCategory, null unless SKIPPED)
skip_detail

rating, fiery_sky, golden_hour, summary,
basic_fiery_sky, basic_golden_hour, basic_summary   -- null unless EVALUATED
model, cost_micro_dollars, confidence

solar_low_cloud, far_solar_low_cloud, upwind_*, inversion_*, surge_*, tide_*
                                                    -- recorded UNTRANSFORMED

forecast_run_at, produced_at
```

**`evaluation_current`** — a plain SQL view, not a materialised one:

```sql
CREATE VIEW evaluation_current AS
SELECT * FROM (
  SELECT e.*, ROW_NUMBER() OVER (
           PARTITION BY location_id, target_date, target_type, prompt_kind
           ORDER BY produced_at DESC, id DESC) AS rn
  FROM evaluation_event e) t
WHERE rn = 1;
```

> **Superseded by the Postgres-only correction.** With H2 gone from the runtime, the idiomatic
> form is `DISTINCT ON (location_id, target_date, target_type, prompt_kind) … ORDER BY …,
> produced_at DESC, id DESC`, not the `ROW_NUMBER()` subquery above — which was written to hedge
> against a database that does not exist. Two further constraints the build plan pins: the
> supporting index must carry **all four** partition columns in the same order (an index omitting
> `prompt_kind` diverges at the fourth column and cannot serve the sort), and with
> `ddl-auto: validate` on every runtime profile, Hibernate's visibility of a Flyway-created view
> must be proven before the migration ships.

A view cannot drift from its source and needs no rebuild job. At ~60 cycles/30d over a few
hundred slots the read cost is irrelevant. *Revisit if* `evaluation_event` passes ~1M rows or
the Plan-tab enrichment query passes ~50ms — at which point it becomes a maintained table with
a rebuild-from-history job, and the history is already there to rebuild from.

**This is the §2.4 fix, and the mechanism matters.** The numerator and denominator now live in
one table: a triaged slot and a scored slot are both events, distinguished by a column rather
than by which table they landed in. Two rules make that durable:

- **Every verification and calibration query states its `outcome` filter explicitly.** No
  default, no implicit survivors-only. Enforce with a test that fails on any repository query
  against `evaluation_event` lacking an outcome predicate.
- **Retries append.** The batch retry path writes a second event; the projection takes the
  latest. Nothing is overwritten, so "what did we think, and when?" is always answerable.

`CloudVerificationRepository.findUnverified` retargets to `evaluation_event` and can then see
batch-scored slots for the first time — which is the point.

**Aurora is out of scope for v1.** Different cadence (5-min polling), different trigger model
(FSM on NOAA state), different persistence (`aurora_forecast_result`). Folding it in would
force the event schema to carry a second set of nullable columns for no gain. Left exactly as
it is.

### 4.5 Staleness — three states, one channel

Carry `forecast_run_at` and `produced_at` on every event. The serve path distinguishes:

| State | Condition | Display |
|---|---|---|
| **NONE** | no event for this slot | confidence `null` — reads provisional |
| **STALE** | latest event older than the threshold | age shown ("from yesterday's run") |
| **FRESH** | within threshold | normal |

NONE and STALE are different facts and must not collapse: "we have never scored this" and "we
scored this two days ago" support different decisions. `ConfidenceDeriver` already returns
**null** on zero coverage deliberately — unlike `Confidence.fromString`'s MEDIUM default — and
NONE maps onto exactly that. **Do not add a second visual channel.** The Plan screen has one
quiet confidence channel layered alongside the star signal; staleness rides it.

### 4.6 Ops UI

**Remove:** stability view, optimisation-strategy configuration, freshness configuration.
**Keep:** `SchedulerView` (unchanged — it manages jobs, not gates), pipeline run view, job
metrics and cost dashboards.
**Grow:** the disposition view. Under §2.7 it stops being a debugging aid and becomes the
selection record — the answer to "what selected these rows?" for every report downstream. It
should show, per cycle, the full candidate set broken down by outcome, with the skipped ones
visible by default rather than filtered out.

### 4.7 Breaking the circularity

**The bug.** `refreshBriefing()` does two jobs in one method:

- `BriefingService.java:409–488` — **slate**: dates, colour locations, sequential weather
  fetch, horizon cloud, marine waves, slot build, day hierarchy (`buildDays` at `:488`). No Claude.
- `BriefingService.java:490–593` — **publication**: enrich with cached scores, gloss (Claude),
  headline, best-bet (Claude), aurora, hot topics, persist (`:563`), method ends `:593`.

> **Re-measured 2026-08-01.** The file is now 1040 lines (985 when this was written). It is also
> **not a single cut**: three *free* refreshes sit inside the paid half — `nlcClarityService`
> `:521-525`, `meteorClarityService` `:530-535`, and `surgeCurveService` `:544-548` (new, PR #389),
> the last of which consumes `locationWeathers`, a free-half local. See build plan §2.10.

The collector reads the *published* artefact and the cycle tail rewrites it. Split the method:

```java
BriefingSlate buildSlate();          // free; weather only
void publish(BriefingSlate slate);   // gloss + best-bet; writes the served cache
```

**The new cycle:**

| Phase | Action | Cost |
|---|---|---|
| SLATE | `buildSlate()` → persist to `briefing_slate` keyed by `pipeline_run_id` | weather only |
| SELECT | collector reads **the slate**, not `getCachedBriefing()` | free |
| EVALUATE | batch submit → wait → retry | Claude |
| PUBLISH | `publish(slate)` → gloss, best-bet, cache write | Claude, once |

**Why this beats the naive head-position refresh — the brief's ⚠️ is resolved, not dodged.**
Calling `refreshBriefing()` at the head of the cycle runs *both* halves twice, which is where
the doubled gloss + best-bet spend (~56 calls/refresh) comes from, and it overwrites the served
cache with a gloss-less briefing that stays gloss-less until the tail completes — 2–4 hours at
the observed afternoon batch latency (`DEFAULT_SAFETY_TIMEOUT` is 4h precisely because
afternoon batches were seen taking 98–173 min). The split runs the **free** half at the head
and the **paid** half once at the tail, and never touches the served cache at the head. There
is no gloss-less window at all, because the published briefing is not rewritten until the
publication actually exists.

**Storage.** `briefing_slate` as its own table, 1:1 with `pipeline_run` — a large JSON payload
on `pipeline_run` itself would be dragged into every ops-list query.

**A hack this retires.** `BRIEFING_WINDOW_DAYS = 5` is five dates rather than four *purely so
the window still covers T+3 after ageing overnight* — the fifth date is never rendered
(`DailyBriefing.jsx` caps the grid at six solar events) and costs ~14 gloss calls per refresh.
With the slate built at the head of the same cycle that consumes it, the roster is never stale,
so the fifth date has no job. Verify against the rendered grid before removing it, but it
should go — it is a compensator for the circularity, and compensators outlive their causes.

---

## §5 — Sequencing

Each phase is independently shippable and leaves the system green.

**Phase 0 — Make it measurable (do this first, it is the constraint on everything else).**
Three items, all prerequisites rather than follow-ups:

1. Land `evaluation_event` with dual-write alongside the existing tables; backfill
   `forecast_evaluation`; retarget the verification harness.
2. **Fix the calibration gate's zero-data reporting** (§7) — it currently reports a measured
   zero where it has no data, and it is the only non-self-referential instrument in the
   project.
3. **Start recording outcomes.** `POST /api/outcome` is already open to `LITE_USER`; what is
   missing is the prompt to use it.

Without this phase the later phases are unverifiable, which is exactly the trap §2 documents.

**Phase 1 — Break the circularity.** Split `refreshBriefing()`, add `briefing_slate`, point the
collector at the slate. Independent of every gate decision, and it is the one actual defect.
Ship it alone.

**Phase 2 — Drop the gates.** Freshness, intraday settled-skip, nightly stability, optimisation
strategies. Behaviour-visible and cost-visible; watch one week of `job_run` costs before
continuing — **against the at-home rate of +£32–48/month, normalised by travel days** (§1), not
against the +£20–30 figure computed on a half-travel month. A travel-heavy observation week
compared to an unnormalised threshold will read as a comfortable pass while telling you
nothing. Divide by non-travel days, or compare like-for-like periods.

**Phase 3 — Delete the subsystems.** Only after phase 2 has run a week without the gates being
missed. Re-home model selection and the TTLs *in the same commit* that deletes their homes.

**Phase 4 — Switch reads to `evaluation_current`, retire `cached_evaluation`.** Reconcile
row counts, then drop.

**Phase 5 — UI shrink.** Last, because it is the least reversible from the user's side and the
most obvious if the earlier phases need backing out.

---

## §6 — Constraint register

Carried forward verbatim; violations here are how this work goes wrong quietly.

- **Never edit prompt-regression assertions — and they are selected by JUnit *tag*, not by
  path.** `src/test/java/**/regression/` **does not exist** (`find src/test -type d -name
  regression` → empty); CLAUDE.md's citation of that path is wrong, and four design agents
  repeated it, one of them "verifying" it. The real mechanism is `pom.xml:23`
  `<surefire.excludedGroups>prompt-regression</surefire.excludedGroups>` with the
  `prompt-regression` profile at `pom.xml:29-32`, and `@Tag("prompt-regression")` on
  `PromptRegressionTest`, `BestBetAuroraPromptRegressionTest` and `SkyRatingEvalTest` — which
  live in ordinary packages beside the code they test. Grep the tag, never the path.
  Note that `BestBetAuroraPromptRegressionTest.java:111,175,219` are
  `new BriefingBestBetAdvisor(` **constructor** calls, so it is a *constructor* signature change
  that forces edits there — not a change to `advise`, which that file never calls (it calls
  `buildRollupJson` at `:117,:181,:225`). Editing them is legal, since they are construction rather
  than assertions, but it must be called out rather than hidden behind "additive".
- **Never edit prompt-regression assertions.** `PromptRegressionTest` pins `coptHill_5Mar` at
  solar low 67% → rating ≤ 2 — only 7pp above the ">60% BLOCKED" rule, so it is a genuinely
  tight pin. Only the user updates these.
- **Copt Hill's solar low is 7%, not 88%.** Any analysis restating 88% is reading the wrong
  column.
- **`SYSTEM_PROMPT` stays ≥ ~15,500 chars.** Below the floor, Haiku caching dies silently — no
  error, no warning, full input rate forever. This bites in §4.2, where the temptation is to
  trim the now-unused stability guidance. Do not.
- **Never transform a cloud value at the point of record.** `evaluation_event` stores raw
  observations; transformation happens at read.
- **Triage stays at 80** (§2.3).
- **Aurora is out of v1 scope** (§4.4).
- JaCoCo enforces 80% line coverage per class — new records need their null branches covered
  with real assertions, not deleted guards. Docker must be running for any command reaching the
  `test` phase.
- **Read from the tree, through implementation and not just design.** §2.6 went three-for-three
  on doc claims that read plausibly and were false. Everything in this document that turned out
  to matter — `SYSTEM_PROMPT` already branching on the absent block, `ForceEvalHeadlineSelector`
  guarding nothing once the gate goes, the `refreshBriefing()` method boundary, the `int` vs
  `Double` asymmetry in `CalibrationBucket` — came from reading the file, and none of it is
  written down anywhere else. Treat this document the same way once it is a week old.

---

## §7 — Phase 0's first-class item: the instrument reports zero where it has no data

**`actual_outcome` has zero rows**, so `GET /api/admin/calibration` returns `scoredPairs=0`.
Twenty recorded outcomes would do more than twenty thousand more ERA5 comparisons. Every
retraction in §2 is an instrument measuring its own selection effect; ERA5 shares biases with
the forecast model exactly where cloud is hardest (marine layers, stratocumulus, orographic).
It can tell you a change moved something. It cannot tell you whether the sunset was worth
driving to.

**The zero-data reporting is worse than "reads as a clean pass" — it is asymmetrically
dishonest.** Read from source at `CalibrationBucket.java:58–61`, the empty-bucket constructor is:

```java
return new CalibrationBucket(key, 0, null, null, null, null, 0, 0, null);
```

Against the record's field order (`:28–37`), every *rate* is a `Double` and correctly returns
**null** — honestly absent. But `missedOpportunities` and `wastedTrips` are declared `int`, so
they return a literal **0**: a number that reads as "we checked and found none".

That is not a cosmetic asymmetry. Per CLAUDE.md, `missedOpportunities` is the single count that
gates relaxing the cloud-approach veto — so the one number authorised to unblock a threshold
change currently reports zero missed opportunities from zero observations. An absolute rating
ceiling can *only* create missed opportunities, which is precisely why that counter was given
the gate; a false zero there is the most consequential wrong number in the project.

**The fix is the rule this design already applies twice.** Zero coverage must read provisional,
not confident — the same rule as `ConfidenceDeriver` returning null on zero coverage
(deliberately unlike `Confidence.fromString`'s MEDIUM default), and the same rule as NONE vs
STALE in §4.5. Make both counters `Integer` and null on an empty bucket, and give the report an
explicit no-data state so a caller cannot mistake absence for a finding. One rule, three
surfaces, no special cases.

Then record outcomes. The mechanism exists and is already open — `POST /api/outcome` is
deliberately available to `LITE_USER`, because observations are the scarce input. What is
missing is the prompt to use it. Small, product-side, and it gates the honest evaluation of
everything above.
