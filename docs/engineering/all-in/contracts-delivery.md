# DRAFT — contracts-delivery

> ⚠️ **Working material, not the build source.** Written against a mix of `a484d1c4`
> (not an ancestor of `main`) and `d421ef5f`; citations of `ForecastTaskCollector.java`
> past line 534 are off by +16. Adversarial verification refuted claims in this draft —
> see `contracts-delivery.corrections.md`. **Resolved positions live in `../all-in-build-plan.md`,
> which supersedes this file wherever they disagree.**

---

## §8 — Stage contracts and phased delivery

Everything below was read from the tree at `fix/release-ahead-guard` (`a484d1c4`). Paths are relative to `/Users/gregochr/IdeaProjects/goldenhour/backend/`. Where a claim could not be checked against a file it is marked **UNVERIFIED** in place.

---

### 8.0 Three corrections that change this section's shape

**(a) Postgres only.** No stage contract below needs an H2-compatible formulation. `evaluation_current` uses `DISTINCT ON`, the slate uses `JSONB`, and the phase table gets a `CHECK` constraint on the phase name. Verified: `backend/pom.xml` scopes h2 to `test`; `application-local.yml:19-20` disables Flyway, so the H2 file never ran a migration and cannot be a schema authority.

**(b) No gates but travel days.** This deletes more than §4.1 of the summary design listed. In this section's scope it additionally deletes the **sentinel-sampling early stop** (`ForecastCommandExecutor.java:316-337` + `SentinelSelector`), which is a cost gate the summary design never named — it persists canned results for a whole region when its sentinels rate ≤ 2 (`ForecastCommandExecutor.java:550-565`), and unlike the other sync-path gates it is **not** bypassed for manual runs.

**(c) Maximum deletion.** §8.4 argues the synchronous engine goes entirely — 1,528 production lines and 3,029 test lines from one decision.

---

### 8.1 The conflict the user overrode, and what it now costs

The summary design's §2.3 argued triage stays at 80 because loosening it "buys nothing visible — newly admitted slots all have solar low > 60%, which the prompt already forces to rating 1–2". That reasoning is sound on its own terms and is now overridden. Two consequences must be stated plainly rather than buried.

**The +£32–48/month figure no longer holds.** It was computed with triage retained (`all-in-architecture-design.md:66-70`). Triage is a *pre-Claude* gate at the front of the per-candidate loop (`ForecastTaskCollector.java:434-444`); every slot it drops is a slot that now becomes a Claude request. The delta is therefore baseline + (freshness) + (settled-skip) + (stability) + (optimisation) **+ (triage)**, and the last term was excluded from the estimate. Do not carry the old number forward into Phase 2's pass/fail threshold.

**Triage's magnitude is measurable today and has not been measured.** The disposition stream already records it. `forecast_run_disposition` (`V101__forecast_run_disposition.sql`) carries one row per candidate with `disposition = 'SKIPPED_TRIAGED'` written at `ForecastTaskCollector.java:438-441`. Retention is 30 days (`disposition_cleanup`, cron `0 30 3 * * *`, seeded in the same migration), so the window available is exactly the evidence window.

```sql
-- How much does triage actually gate? Run BEFORE Phase 2b.
-- Every SKIPPED_TRIAGED row is one Claude request the all-in design will now pay for.
SELECT date_trunc('day', d.created_at)::date        AS cycle_day,
       COUNT(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')      AS triaged,
       COUNT(*) FILTER (WHERE d.disposition IN ('EVALUATED','FORCE_EVALUATED')) AS evaluated,
       ROUND(100.0 * COUNT(*) FILTER (WHERE d.disposition = 'SKIPPED_TRIAGED')
             / NULLIF(COUNT(*) FILTER (WHERE d.disposition IN
                 ('SKIPPED_TRIAGED','EVALUATED','FORCE_EVALUATED')), 0), 1)
                                                     AS triage_share_pct
FROM forecast_run_disposition d
WHERE d.created_at >= now() - interval '30 days'
GROUP BY 1 ORDER BY 1;
```

`triage_share_pct` is the multiplier on the *evaluated* population that Phase 2b adds. Multiply it by the observed per-call cost ($0.00206) and the evaluated count to get the triage term in isolation. **Record this number in the phase-2 commit message.** It is the single input that makes the revised delta honest, and it is one query away.

A second query separates the three triage rules, because they have different volumes and the cloud rule is the one the design cares about (`WeatherTriageEvaluator.java:40-61` — solar low cloud > 80, precip > 2.0 mm, visibility < 5000 m):

```sql
SELECT CASE
         WHEN detail LIKE '%low cloud%'    THEN 'HIGH_CLOUD'
         WHEN detail LIKE '%Precipitation%' THEN 'PRECIPITATION'
         WHEN detail LIKE '%Visibility%'    THEN 'LOW_VISIBILITY'
         ELSE 'OTHER' END AS rule, COUNT(*)
FROM forecast_run_disposition
WHERE disposition = 'SKIPPED_TRIAGED' AND created_at >= now() - interval '30 days'
GROUP BY 1 ORDER BY 2 DESC;
```

(The detail string is the triage reason verbatim from `WeatherTriageEvaluator.java:44-46`, `:52-53`, `:58-60`; matching on it is a reporting convenience, not a contract.)

---

### 8.2 Stage contracts

This is the document a PR is checked against. Each stage declares: **reads / writes / must not read / failure mode / idempotency / restart**.

The unifying rule appears in every one of them: **a stage may classify, and may write the classification down; a stage may not branch on a classification to avoid spending.** The only branch on a classification anywhere in the pipeline is SELECT's travel-day test.

#### Phase enum after the change

`PipelinePhase` (`entity/PipelinePhase.java`, 56 lines) loses `STABILITY_RECLASSIFY` — that phase exists solely to time the intraday cost gate (`PipelinePhase.java:29-34`, `PipelineOrchestrator.java:363-379`) and there is no cost gate left to time. It gains `SLATE`, `SELECT` and renames the rest for the stage vocabulary:

```java
public enum PipelinePhase {
    SLATE,             // weather only, free
    SELECT,            // bounds only, free
    EVALUATE_SUBMIT,   // batches to Anthropic
    EVALUATE_WAIT,     // poll forecast_batch to terminal
    EVALUATE_RETRY,    // conditional; capped single retry
    PUBLISH            // gloss + best-bet + served-cache write
}
```

`RECORD` and `PROJECT` are deliberately **not** phases, and the reason is load-bearing: RECORD is driven by a *different scheduled job* — `batch_result_polling`, registered at `BatchPollingService.java:66-70` on a 60 s tick — and the orchestrator observes it only as `forecast_batch.status` leaving `SUBMITTED` (`PipelineOrchestrator.java:602-609`). PROJECT is a SQL view with no runtime at all. Giving either a phase row would claim the orchestrator drives something it does not, and that misattribution is exactly what produced the one observed failure (a cycle stuck at `FORECAST_BATCH_WAIT`): the orchestrator was blamed for a poller problem.

---

#### SLATE

*The day's weather truth. Free. The only stage that talks to Open-Meteo.*

| | |
|---|---|
| **Reads** | `location` (enabled colour locations, `BriefingService.java:421-424`); Open-Meteo forecast/air-quality/marine; `tide_extreme`; solar ephemeris |
| **Writes** | one `briefing_slate` row keyed by `pipeline_run_id`, holding the `List<BriefingDay>` produced by `hierarchyBuilder.buildDays` (`BriefingService.java:483`) as `JSONB` |
| **Must NOT read** | `daily_briefing_cache`; the in-memory `cache` `AtomicReference`; `evaluation_event`; any Claude output. A slate that reads a score is a slate that inherits last cycle's selection |
| **Failure mode** | partial — per-location weather failures already counted as `failed` (`BriefingService.java:452-456`). Below 50 % success the run does **not** publish (existing threshold, `BriefingService.java:509`); under the new split it does not write a slate either, and the cycle fails at SLATE rather than proceeding to spend on a half-roster |
| **Idempotency** | re-running SLATE for the same `pipeline_run_id` **replaces** that run's slate row (`ON CONFLICT (pipeline_run_id) DO UPDATE`). Safe because nothing downstream has consumed it yet within a run, and a resumed run that re-slates gets fresher weather, which is strictly better |
| **Restart** | not resumable — a mid-SLATE restart fails the run (same rule as today's mid-SUBMIT, `PipelineOrchestrator.java:333-336`). Nothing has been spent, so a fresh cycle is free |

The extraction is a clean cut, not a rewrite. `BriefingService.refreshBriefing()` (`:404-571`) already has the boundary: `:404-483` is slate (dates, colour-location filter, sequential weather fetch, horizon cloud, marine waves, slot build, `buildDays`), `:486-571` is publication (score enrichment, gloss, headline, best-bet, aurora, hot topics, cache write, persist). Split at line 484:

```java
/** Free half: weather → slots → day hierarchy. No Claude, no cache write. */
public BriefingSlate buildSlate(Long pipelineRunId);

/** Paid half: enrich, gloss, best-bet, hot topics; writes the served cache. */
public void publish(BriefingSlate slate, Long pipelineRunId);
```

`BRIEFING_WINDOW_DAYS = 5` (`BriefingService.java:118`) is a compensator for the circularity and drops to 4 in the same commit — the fifth date exists only so the window still reaches T+3 after ageing overnight, and with the slate built at the head of the cycle that consumes it there is no ageing. Verify against the rendered grid before removing (the frontend caps at six solar events).

---

#### SELECT

*Which slots this cycle is responsible for. Free. Bounds and one gate.*

| | |
|---|---|
| **Reads** | this run's `briefing_slate`; `location`; `travel_day` |
| **Writes** | `evaluation_event` rows with `outcome = 'SKIPPED'` for every non-selected slot, and an in-memory candidate list for EVALUATE |
| **Must NOT read** | `daily_briefing_cache` / `getCachedBriefing()` (this is point 7 — the defect); `cached_evaluation`; `stability_snapshot`; any freshness TTL |
| **Failure mode** | all-or-nothing. A slate that yields zero candidates is a legitimate outcome (every date a travel day) and must still write its skip rows |
| **Idempotency** | append-only. A resumed run re-running SELECT writes a second set of rows; the projection takes the latest. Do **not** add a dedupe key — "we decided this twice" is a fact worth keeping |
| **Restart** | not resumable (pre-submission). Same rule as SLATE |

**The four surviving tests, and why each is not a gate:**

| Test | Site today | Classification |
|---|---|---|
| target date is not in the past | `BriefingCandidateCollector.java:151-172` | physical — you cannot forecast yesterday |
| the event has not already passed | `ForecastCommandExecutor.java:751-760` (sync only today; must move to SELECT) | physical |
| the location resolves to an enabled row | `BriefingCandidateCollector.java:259-270` | physical |
| horizon within T+0..T+3 | `NightlyEligibilityPolicy.java:44-56` | **bound**, not policy — it is the depth of weather the slate holds |
| **travel day** | `BriefingCandidateCollector.java:177-198` | **the one gate**, and it gates unactionable spend |

Everything else in that method is deleted: the freshness/`SKIPPED_CACHED` block (`:214`, `:217-236`), the stability lookup that feeds it (`:313-333`), the verdict/hard-constraint block (`:239-258`), and the whole `EligibilityPolicy` indirection.

**A defect SELECT must fix while it is being rewritten.** `BriefingCandidateCollector.java:206-208` is:

```java
if (!candidateStrategy.includes(date, targetType)) {
    continue;
}
```

No disposition row is written and `totalSlots` is not incremented. For the intraday cycle, whose whole job is a narrow window, this means **the majority of the roster vanishes from the selection record with no trace**. Under the §2.7 constraint ("every persisted decision records why, in the same table as the outcomes") that is precisely the invisible selection effect the redesign exists to eliminate: a query over intraday dispositions today cannot see that T+2 was never considered. The rewrite records `outcome='SKIPPED', skip_category='OUT_OF_CYCLE_WINDOW'` for these, and a test pins reconciliation:

```java
@Test
@DisplayName("every slate slot produces exactly one evaluation_event for the run")
void selectionReconcilesAgainstTheSlate()
```

**The cycle window is a de-duplication boundary, not a gate — and it must be recorded as data.** Nightly and intraday run against one roster thirteen hours apart. If intraday also took T+2/T+3, it would re-ask a question the same day's nightly already answered. That is division of labour between two cycles, not a decision that a slot does not deserve a call. To keep it honest rather than merely arguable, the window becomes a column: `pipeline_run.select_window` (e.g. `'T0_SUNSET,T1_SUNRISE,T1_SUNSET'` for intraday, `'ALL_T0_T3'` for nightly), so any downstream query can see the denominator it is working over instead of inferring it from a Java class name.

---

#### EVALUATE

*The only stage that spends. Submit, wait, one capped retry.*

| | |
|---|---|
| **Reads** | the candidate list from SELECT; `model_selection` (near/far tier); Open-Meteo cloud-point prefetch |
| **Writes** | `forecast_batch` rows tagged `pipeline_run_id` (`BatchSubmissionService.java:149-153`); `job_run` + `api_call_log` |
| **Must NOT read** | `evaluation_event` (a "have we scored this already?" read is a freshness gate wearing a different hat); `daily_briefing_cache` |
| **Failure mode** | **currently silent, and this must change.** `BatchSubmissionService.java:162-166` catches every exception, logs at ERROR, and returns `null`; `EvaluationServiceImpl.java:159-163` maps that to `EvaluationHandle.empty()`; `ScheduledBatchEvaluationService.java:382-422` ignores it. If *every* bucket fails to submit, `currentCompletionState` sees `total == 0`, `allTerminal()` returns true (`PipelineOrchestrator.java:632-634`), and the cycle publishes on stale scores and is marked **COMPLETED**. Contract: EVALUATE fails the phase when `submittedRequests < candidates` |
| **Idempotency** | submission is **not** idempotent — a re-run submits a second batch and pays twice. The retry path is guarded by an existence check (`PipelineOrchestrator.java:466-472` documents "skipped if a retry batch already exists"); the primary submit is not |
| **Restart** | mid-SUBMIT → fail the run (`PipelineOrchestrator.java:333-336`); mid-WAIT / mid-RETRY → resumable, because progress lives in `forecast_batch.status`, not in memory (`:397-415`) |

The submit path keeps its bucketing (near/far × inland/coastal, plus the homogeneous bluebell and woodland mini-batches, `ScheduledBatchEvaluationService.java:382-422`) — that is prompt-cache homogeneity, not a gate. It keeps `NEAR_TERM_MAX_DAYS = 1` (`ForecastTaskCollector.java:93`) as the model-tier split, re-homed into the horizon selector.

The four-overload `submitForecastBatchForPipelineRun` ladder (`ScheduledBatchEvaluationService.java:193, :210, :244`) collapses to one method once `EligibilityPolicy`, `CandidateCollectionStrategy` and the `ephemeral` flag are gone:

```java
public void submitForPipelineRun(Long pipelineRunId, List<ForecastCandidate> candidates);
```

#### RECORD

*Append-only. Never updated. Driven by the poller, not the orchestrator.*

| | |
|---|---|
| **Reads** | `forecast_batch` rows in `SUBMITTED`; Anthropic results |
| **Writes** | one `evaluation_event` row per response — **raw cloud values, untransformed** |
| **Must NOT read** | `daily_briefing_cache`; the slate |
| **Failure mode** | per-response. A parse failure records an `api_call_log` failure row and becomes retry input (`BatchRetryService` javadoc, `:33-64`) |
| **Idempotency** | append. A reprocessed batch writes duplicate events; `evaluation_current` picks the latest. This is the property that makes "what did we think, and when?" answerable and it must not be optimised away |
| **Restart** | fully resumable — the poller is stateless over `forecast_batch.status` |

Today RECORD is split by engine, not by kind of fact: batch results go to `cached_evaluation` via `forecastResultHandler.mergeCacheKey` (`BatchResultProcessor.java:347`) while the *only* `forecast_evaluation` inserts on the batch path are triage rows (`ForecastService.java:376`, `:406`). Two consequences for sequencing, and the second is a trap:

1. `EvaluationViewService` exists solely to merge the two at read time (`EvaluationViewService.java:29-34`) and dies with the merge.
2. **Deleting triage severs the ERA5 harness's input.** Its population is `forecast_evaluation`; on the batch path the only rows written there are triage rows. Remove triage before the harness is retargeted at `evaluation_event` and the cloud-verification backlog silently stops growing — no error, no warning, exactly the failure class §2.6 is about. This makes Phase 0 a **hard** precondition for the triage removal, not a soft one.

#### PROJECT

*A view. No runtime, no job, nothing to fail.*

```sql
CREATE VIEW evaluation_current AS
SELECT DISTINCT ON (location_id, target_date, target_type, prompt_kind) *
FROM evaluation_event
ORDER BY location_id, target_date, target_type, prompt_kind, produced_at DESC, id DESC;
```

Postgres-native `DISTINCT ON` replaces the `ROW_NUMBER()` sub-select in the summary design — same semantics, one scan, and it reads as what it is. **Must NOT** be materialised in v1: a view cannot drift from its source and needs no rebuild job. Revisit at ~1 M rows or a >50 ms Plan-tab enrichment, at which point the history to rebuild from is already there.

#### PUBLISH

*Terminal. Gloss, best-bet, served cache. Nothing reads its output.*

| | |
|---|---|
| **Reads** | this run's `briefing_slate`; `evaluation_current`; `aurora_forecast_result`; hot-topic sources |
| **Writes** | `daily_briefing_cache` (id = 1); the in-memory `cache`; `pipeline_run_pick`; `pipeline_run.best_bet_status` |
| **Must NOT be read by** | SLATE, SELECT, EVALUATE, RECORD. §8.3 enumerates and resolves every current reader |
| **Failure mode** | fails the phase and the run (`PipelineOrchestrator.java:430-434`), leaving the previous published briefing intact. Correct: a failed publish must never blank the served surface |
| **Idempotency** | last-write-wins on a single row. Re-running PUBLISH re-pays gloss + best-bet (~56 Claude calls per the summary design's §4.7); the phase-resume logic must not re-enter it after completion, which `atOrPastBrief` already handles (`PipelineOrchestrator.java:405, :421`) |
| **Restart** | resumable but not free. Keep the existing "mid-BRIEFING re-runs the briefing only" behaviour and its test (`PipelineOrchestratorTest.java:397`) |

---

### 8.3 "Publication is terminal" — the reader census

Complete enumeration from `grep -rn "getCachedBriefing\|getCachedDays" --include="*.java" src/main` (13 call sites across 8 files). Every one is classified.

| Reader | Site | Verdict |
|---|---|---|
| `BriefingController.getBriefing` | `BriefingController.java:58` → `getCachedBriefingForApi()` | **legitimate serve path.** This is what publication is *for* |
| `CloseToHomeService` | `CloseToHomeService.java:136` → `getCachedBriefingForApi()` | **legitimate serve path.** Read-only, request-scoped, per-user |
| `BriefingService.getCachedBriefingForApi` internals — `reEnrichVerdicts` (`:326-352`), `BriefingHonestyFilter.apply`, `applyBestBetFallback` (`:308-322`) | `BriefingService.java:303-307` | **legitimate serve path.** Serve-time decoration of the published artefact; writes nothing |
| **`ForecastTaskCollector.collectScheduledBatches`** | **`ForecastTaskCollector.java:272`** | **MUST BE REWIRED.** This *is* point 7. Reads the previous cycle's publication to decide what this cycle evaluates. Becomes a read of this run's `briefing_slate` |
| **`ForecastTaskCollector.collectRegionFilteredBatches`** | **`ForecastTaskCollector.java:733`** | **DELETE the caller.** Admin region-filtered batch (`POST` via `BatchAdminController`). It re-implements the whole selection loop with `NightlyEligibilityPolicy` inline (`:803-806`); with policies gone it has nothing left that the normal cycle does not do. Removing it deletes the second reader outright |
| **`PipelineOrchestrator.persistPicksForCycle`** | **`PipelineOrchestrator.java:538`** | **Legitimate, but reclassify it.** It runs *inside* PUBLISH, after `refreshBriefing()`, reading the artefact the same phase just wrote in order to record which picks this cycle made (`:544-558`). That is publication reading *itself*, not a downstream stage reading publication — the invariant holds. Make it structural rather than incidental: `publish()` returns `PublishResult(bestBetStatus, picks)` and the orchestrator persists from the return value. Then the re-read disappears and there is nothing to argue about |
| `BriefingModelTestService` | `BriefingModelTestService.java:100` → `getCachedBriefing()` | **MUST BE REWIRED — to the slate.** It needs the *rollup input* (untransformed slots) to feed the same input to Haiku/Sonnet/Opus. That input is the slate. Reading it from the publication only worked because the publication happens to contain it |
| **`SpringTideHotTopicStrategy`** | **`SpringTideHotTopicStrategy.java:92`** → `getCachedDays()` | **MUST BE REWIRED — to the slate.** See below |
| **`KingTideHotTopicStrategy`** | **`KingTideHotTopicStrategy.java:97`** → `getCachedDays()` | **MUST BE REWIRED — to the slate.** See below |

**Resolving `getCachedDays()`.** Its javadoc says it exists so hot-topic strategies can "scan triage data (e.g. tide classifications) without triggering a recursive hot topic re-detection" (`BriefingService.java:387-391`). The recursion it dodges is real and visible: `getCachedBriefing()` calls `hotTopicAggregator.getHotTopics(...)` at `:259` to overlay live topics, and the strategies would call back into it.

Read what they actually consume and the problem dissolves. `SpringTideHotTopicStrategy.java:110` and `KingTideHotTopicStrategy.java:108` both call `findSpringTide(day)` / `findKingTide(day)`, which read `BriefingSlot.TideInfo` — produced by `slotBuilder.buildSlot` in the **slate** half (`BriefingService.java:471-474`), before any Claude call. They are reading slate data through a publication-shaped hole. Point them at `briefing_slate` and:

- the invariant holds with no exception carved for hot topics;
- the recursion is structurally impossible rather than avoided by convention, because the slate contains no topics to recurse into;
- **`getCachedDays()` is deleted** (`BriefingService.java:392-395`), and with it the last non-serve reader of the in-memory cache.

**Result: the invariant is achievable with no exceptions.** After Phase 1 + Phase 4, `daily_briefing_cache` and the in-memory `cache` have exactly two readers — `BriefingController.java:58` and `CloseToHomeService.java:136` — both request-scoped serve paths that write nothing. Pin it:

```java
@Test
@DisplayName("no stage before PUBLISH reads the published briefing")
void publicationIsTerminal()  // ArchUnit: no type in service.batch, service.pipeline
                              // (except the publish path) may call
                              // BriefingService#getCachedBriefing / #getCachedDays
```

One caveat worth writing down rather than discovering: `loadPersistedBriefing()` (`BriefingService.java:223-236`) repopulates the in-memory cache from `daily_briefing_cache` at startup. That is a serve-path warm-up and stays. It is only safe because nothing upstream reads the cache — which is the invariant, stated from the other end.

---

### 8.4 The intraday cycle under the new design

**What it is today.** `PipelineOrchestrator.runIntradayCycle()` (`:249-253`) calls the same `runCycle` with two substitutions: `IntradayCandidateCollectionStrategy` (window = T sunset, T+1 sunrise, T+1 sunset; `IntradayCandidateCollectionStrategy.java:53-62`) and `IntradayEligibilityPolicy` (skip SETTLED, include TRANSITIONAL/UNSETTLED; `IntradayEligibilityPolicy.java:46-53`). Cron `0 0 14 * * *` (`V105__intraday_forecast_refresh_job.sql:24`). It records `STABILITY_RECLASSIFY` before submit purely to time the cost gate, and it re-classifies stability *ephemerally* — computed for gating, snapshot suppressed (`ForecastTaskCollector.java:268-271`, `:339-341`).

**What survives.** The eligibility policy is deleted. The `ephemeral` flag is deleted with it — the flag only exists to suppress a write-through of a classification whose only consumer was the gate. The `STABILITY_RECLASSIFY` phase and `ReclassSummary` (53 lines) go. The between-collect-and-submit hook (`ScheduledBatchEvaluationService.java:244-264`, `:344-350`) exists solely to give that phase a truthful duration and goes too.

**Does it rebuild the slate? It must, and this is the decisive fact.** `V103__retire_daily_briefing_cron.sql:13` deleted the standalone `daily_briefing` cron. Since V103, `refreshBriefing()` is invoked by exactly two schedulable things: the nightly cycle and the intraday cycle (`PipelineOrchestrator.java:207-213`), plus the admin `POST /api/briefing/run`. **Delete intraday and the Plan tab's weather runs on a single 01:00 fetch for the whole day** — by 20:00 the slate is nineteen hours old, and tonight's sunset column is showing this morning's cloud. Intraday is not an optional refresh; it is the day's second and only other weather truth.

So the shape is settled: **intraday runs the same six-stage pipeline with a narrower SELECT window.** SLATE is mandatory (it is the refresh), PUBLISH is mandatory (the gloss and best-bet must reflect the refreshed slate), and only EVALUATE is discretionary — which means any decision to skip it would be a gate, and gates are banned.

**Is it worth running at all? The cost argument.**

The nightly all-in candidate set is roughly `locations × slots(T+0..T+3)` = up to 8 event-slots per location, minus T+0 events already passed at 01:00 (none — sunrise is ahead). The intraday set is 3 event-slots per location. So intraday's *maximum* all-in evaluation volume is **3/8 ≈ 37.5 %** of nightly's.

But intraday's marginal increase from the gate removal is far smaller than that, because most of its window is already being evaluated. The summary design's evidence line puts the settled-skip at 13.0 % (`all-in-architecture-design.md:48`) — meaning ~87 % of intraday candidates are already TRANSITIONAL/UNSETTLED and already paid for. Removing the settled-skip therefore adds roughly `13/87 ≈ 15 %` to intraday's *current* evaluation volume, not 100 %. That 13.0 % figure's exact denominator is ambiguous in the source and must be re-derived before it gates anything:

```sql
-- Intraday's own disposition mix. Re-derive the settled-skip share
-- before quoting 13.0% as intraday's marginal cost.
SELECT d.disposition, COUNT(*),
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS pct
FROM forecast_run_disposition d
JOIN job_run jr           ON jr.id = d.job_run_id
JOIN forecast_batch fb    ON fb.job_run_id = jr.id
JOIN pipeline_run pr      ON pr.id = fb.pipeline_run_id
WHERE pr.cycle_type = 'INTRADAY'
  AND d.created_at >= now() - interval '30 days'
GROUP BY 1 ORDER BY 2 DESC;
```

(Note the join limitation, which is real: a zero-batch cycle's dispositions hang off a `SCHEDULED_BATCH` **disposition-anchor** run created at `JobRunService.java:521-546` with no `forecast_batch` row, so it will not appear. For an all-cycles view, fall back to the day-grain query in §8.6.)

**Verdict: keep intraday, unchanged in cadence.** Its three-slot window is the next ~36 h of actionable events — the only window in which a re-forecast can still change a decision — and the gate removal moves its cost by a modest fraction of an already-small base. The alternative that a strict no-windows reading would demand — one cycle, full roster, twice a day — doubles nightly's evaluation spend to re-answer T+2 and T+3 thirteen hours apart, which is the *most* expensive way to buy the least information, since far-horizon slots are exactly the ones that move least within a day.

`IntradayCandidateCollectionStrategy` (63 lines) and the `CandidateCollectionStrategy` interface (38) + `NightlyCandidateCollectionStrategy` (37) are deleted as an *abstraction*: with only two windows and both now recorded as `pipeline_run.select_window` data, a strategy interface with two stateless singletons is indirection over a two-branch switch. The windows survive as data; the pattern does not.

---

### 8.5 The synchronous engine — delete it

`ForecastCommandExecutor` (826 lines) shares Gate 4 with the batch path via `NightlyEligibilityPolicy.INSTANCE.permitsHorizon` (`ForecastCommandExecutor.java:624`). With the policy gone, the question is what is left.

**Finding, from reading every call site: in production the sync engine's gates are structurally unreachable.**

There are exactly five call sites of `commandExecutor.execute` in `src/main`, all in `ForecastController`, and **all five pass `manual = true`**:

- `:329` `commandFactory.create(RunType.SHORT_TERM, true, locations, dates, excludedSlots)`
- `:353` `create(RunType.VERY_SHORT_TERM, true, ...)`
- `:377` `create(RunType.SHORT_TERM, true, ...)`
- `:396` `create(RunType.LONG_TERM, true)`
- `:517` `create(RunType.SHORT_TERM, true, locations, dates)` (retry-failed)

`ForecastCommandFactory.create(RunType, boolean manual, ...)` — second parameter is `manual` (`ForecastCommandFactory.java:47`). And in the executor:

- `:260-262` — `boolean optimisationSkip = !triggeredManually && optimisationSkipEvaluator.shouldSkip(...)` → **never true in production**
- `:352-358` — `if (!triggeredManually) { applyStabilityFilter(...) }` → **never runs in production**

`ScheduledForecastService`'s javadoc confirms the sync wrappers were removed after the v2.12 batch consolidation and that "admin manual runs go through `ForecastController` → `ForecastCommandExecutor` directly" (`ScheduledForecastService.java:18-25`). So `OptimisationSkipEvaluator` (206 lines) and the entire stability filter (`:615-640`) are dead on every real path. CLAUDE.md's claim that "both engines consult" the eligibility policy is true in code and vacuous in behaviour — a fifth doc claim that reads plausibly and does not survive the file.

**One sync gate is genuinely live, and the summary design missed it.** The sentinel-sampling early stop (`:316-337`) is *not* guarded by `triggeredManually`. `SENTINEL_SAMPLING` is seeded `TRUE` with threshold 2 for all three colour run types (`V52__*.sql:5-10`). So on every admin run: sentinels are evaluated first, and if all rate ≤ 2 the rest of the region gets `persistCannedResult` (`:550-565`) — an unevaluated canned row. Under "everything through Claude" that is exactly the class of thing being removed, and it is currently firing.

**Does it write to `evaluation_event`?** It writes to `forecast_evaluation` — `ForecastService.evaluateAndPersist` → `repository.save` at `:456` and `:514`, triage rows at `:376` and `:406`. Under the new design it would need a fourth writer into `evaluation_event`, with its own atmospheric-capture path, its own disposition semantics (it has none today — no `forecast_run_disposition` rows at all from this engine), and its own confidence write.

**Argument for keeping it:** it is the only path that evaluates on demand with live SSE progress, and it is wired to real admin UI — `frontend/src/api/forecastApi.js:119, :137, :155, :166` and `runProgressApi.js:17, :53`.

**Argument for deleting it, which wins:**

1. It is a *second implementation of the same pipeline* — its own triage phase, its own weather/cloud prefetch, its own stability enrichment, its own persistence target. Every rule in this document would have to be written twice and would drift; the `permitsHorizon` sharing exists precisely because it already drifted once and had to be re-unified.
2. Its distinguishing features are gates (sentinel, optimisation, stability) — all deleted. What remains is "submit these slots to Claude now", which the batch path does with better observability.
3. It is the last consumer of `SentinelSelector` (58 lines — grep finds no other reference in `src/main`), and the largest consumer of `OptimisationStrategyService`.
4. It writes results into `forecast_evaluation`, the table Phase 4 retires. Keeping it means keeping the merge layer forever.

**The replacement, so the capability is not lost:** `POST /api/forecast/run` becomes `POST /api/pipeline/run` — an admin trigger that creates a `PipelineRunEntity` with an operator-supplied `select_window` override, runs the same six stages, and is observed through the existing pipeline-run view rather than the bespoke SSE tracker. It is the same button with a different engine behind it. Latency changes from seconds to Anthropic batch latency (2–5 min nightly, 98–173 min at afternoon peak per `PipelineOrchestrator.java:96-105`) — which is a genuine loss and the only real cost of this deletion. Mitigate with `EvaluationService.evaluateNow` (`EvaluationServiceImpl.java:127-136`), which already exists as the synchronous single-task path and produces byte-identical observability to the batch handlers (`BatchResultProcessor.java:52-56`). A one-slot admin re-run stays instant; a whole-roster re-run becomes a batch, which it should have been all along.

`RunProgressTracker` (288 lines) **survives** — `BortleEnrichmentService` also uses it. Do not delete it on the strength of the executor going.

---

### 8.6 Phased delivery

The sketched order (0 measurable / 1 circularity / 2 gates / 3 subsystems / 4 read-switch / 5 UI) is **right in shape and wrong in granularity**. Two changes:

- **Phase 2 splits.** Gate removal is now much larger than scoped. The pure-skip removals (freshness, stability, optimisation, force-eval, settled-skip) change *volume* and nothing else; the triage removal changes *what reaches Claude*, *what lands in `forecast_evaluation`*, and *the batch's prompt-cache composition*. Different blast radii, different rollback, different proof. Ship them as 2a and 2b.
- **Phase 5 partly merges into Phase 3.** The four Run-Forecast buttons are the UI of the engine Phase 3 deletes. Shipping the deletion without the UI leaves buttons that 404. Delete engine and buttons in one commit; the *rest* of the UI shrink (stability view, optimisation config, freshness config) stays in Phase 5.

Rollback is cheap throughout because of one asset: `DynamicSchedulerService.pause(jobKey)` (`:160`) exposed at `POST /api/admin/scheduler/jobs/{jobKey}/pause`. A cost blow-up is stopped in seconds with no deploy by pausing `near_term_batch_evaluation` and `intraday_forecast_refresh`.

---

#### Phase 0 — Make it measurable

**Ships:** `evaluation_event` + `evaluation_current` (migration V139 — verified next free number, latest in tree is `V138__durham_heritage_coast_locations.sql`); dual-write alongside `cached_evaluation` and `forecast_evaluation`; backfill of `forecast_evaluation` history; `CloudVerificationRepository.findUnverified` retargeted to `evaluation_event`; `CalibrationBucket.missedOpportunities`/`wastedTrips` become `Integer` and null on an empty bucket (`CalibrationBucket.java:58-61`).

**Proof obligations:**
- Query: `SELECT COUNT(*) FROM forecast_evaluation` vs `SELECT COUNT(*) FROM evaluation_event WHERE source='BACKFILL'` — must be equal.
- Query: one full cycle later, `SELECT outcome, COUNT(*) FROM evaluation_event WHERE pipeline_run_id = :latest GROUP BY 1` must reconcile against `forecast_run_disposition` for the same cycle, category for category.
- Test: `EvaluationEventRepositoryTest.everyQueryStatesItsOutcomeFilter` — an ArchUnit/reflection test that fails on any repository method against `evaluation_event` without an `outcome` predicate. This is the §2.4 enforcement and it belongs here, before there is anything to get wrong.
- Test: `CalibrationBucketTest.emptyBucketReportsNullDecisionCounts`.
- Log line: `[EVENT] wrote N events for pipeline_run=X (evaluated=A triaged=B skipped=C)` at the end of every cycle.

**Rollback:** drop the view, drop the table, revert the dual-write. Nothing else read it.

**Precondition for next phase:** the reconciliation query passes on two consecutive cycles.

---

#### Phase 1 — Break the circularity

**Ships:** `refreshBriefing()` split into `buildSlate()` / `publish()`; `briefing_slate` table (V140, 1:1 with `pipeline_run`, `JSONB` payload); `SLATE` and `SELECT` phases; `ForecastTaskCollector.java:272` reads the slate; `SpringTideHotTopicStrategy.java:92` and `KingTideHotTopicStrategy.java:97` read the slate; `getCachedDays()` deleted; `BRIEFING_WINDOW_DAYS` 5 → 4.

**Proof obligations:**
- Test: `publicationIsTerminal()` (§8.3) — the ArchUnit guard. This is the phase's whole point and must land with it.
- Test: `BriefingServiceTest.buildSlateMakesNoClaudeCall` — verify zero interactions with `glossService` and `bestBetAdvisor`.
- Test: `PipelineOrchestratorTest.slatePrecedesSelectAndPublishIsLast` — phase sequence assertion.
- Query: `SELECT phase, sequence_order FROM pipeline_run_phase WHERE pipeline_run_id = :latest ORDER BY sequence_order` returns SLATE, SELECT, EVALUATE_SUBMIT, EVALUATE_WAIT, PUBLISH.
- Query, the T+3 bug's direct evidence: `SELECT target_date, COUNT(*) FROM evaluation_event WHERE pipeline_run_id = :latest AND outcome='EVALUATED' GROUP BY 1` — must show four distinct dates including T+3, not three.
- Log line: `[SLATE] built N days × M locations in Xms, 0 Claude calls`.
- Cost check: gloss calls per cycle must be **unchanged** (this phase moves the slate, it does not double the publish). `SELECT COUNT(*) FROM api_call_log WHERE service='ANTHROPIC' AND request_url LIKE '%gloss%'` — or count by the `job_run` of `RunType.BRIEFING`.

**Rollback:** revert the split; the collector's `getCachedBriefing()` read is a one-line restore. `briefing_slate` rows become orphans and are harmless.

**Precondition for next phase:** one week with the phase sequence stable and no gloss-count regression.

---

#### Phase 2a — Drop the pure-skip gates

**Ships:** deletion of `FreshnessResolver` / `FreshnessProperties`, `NightlyEligibilityPolicy`, `IntradayEligibilityPolicy`, `EligibilityPolicy`, `EligibilityDecision`, `ForceEvalHeadlineSelector` + `photocast.batch.force-eval-cap` + the `FORCE_EVALUATED` disposition, `OptimisationSkipEvaluator`, `OptimisationStrategyService`, `StabilitySnapshotProvider`, the `STABILITY_RECLASSIFY` phase and `ReclassSummary`. `HorizonModelSelector` re-homes the model tier in the same commit.

**Why 2a is safe to ship as one unit:** every removal here deletes a *skip*, all five skip categories already exist in `forecast_run_disposition`, and the volume delta is one query away. Nothing about what reaches Claude changes in kind, only in count.

**Proof obligations:**
- Query, the before/after: run the §8.6 cost query for the 14 days before and 7 days after. The predicted delta is the sum of the pre-change shares of `SKIPPED_CACHED`, `SKIPPED_STABILITY`, `SKIPPED_NO_REFRESH_NEEDED` measured on the *same* window.
- Query: `SELECT COUNT(*) FROM forecast_run_disposition WHERE disposition IN ('SKIPPED_CACHED','SKIPPED_STABILITY','SKIPPED_NO_REFRESH_NEEDED') AND created_at > :deploy` must be **0**.
- Test: `HorizonModelSelectorTest.nearTermIsT0AndT1FarTermIsT2AndT3`.
- Test: `SelectionStageTest.onlyTravelDaysAndStructuralBoundsSkip` — an exhaustive assertion over the surviving skip categories.
- Log line: `[BATCH ELIG]` (`ForecastTaskCollector.java:613`) is deleted with the aggregator; replace with `[SELECT] N candidates, M travel-day skips, K out-of-window, 0 policy skips`.

**Rollback:** `git revert` the commit and redeploy; or, for an immediate stop, pause both cycle jobs via the scheduler API.

**Precondition for 2b:** one week within the cost envelope, and Phase 0's ERA5 retarget confirmed live.

---

#### Phase 2b — Drop the weather triage gate

**Ships:** `WeatherTriageEvaluator` demoted from gate to classifier — its output stops branching and becomes a recorded field on `evaluation_event` plus prompt context. `ForecastTaskCollector.java:434-444` (the `continue`) is deleted; the two `forecast_evaluation` triage inserts (`ForecastService.java:376`, `:406`) are deleted; the tide-alignment triage (`ForecastService.java:386-412`) goes with them; the sentinel early stop (`ForecastCommandExecutor.java:316-337`) goes if Phase 3 has not already taken it.

**This is the phase with the largest and least predictable cost delta, and it is the one the summary design excluded from its estimate.**

**Proof obligations:**
- Query, run **before** the deploy: the triage-share query in §8.1. Predicted new evaluated count = `evaluated × (1 + triage_share / (1 − triage_share))`.
- Query, run **after**: the same query — `SKIPPED_TRIAGED` must be 0 and evaluated must have risen by the predicted amount ±15 %.
- Query, the ERA5 continuity check that the summary design's §2.4 makes essential: `SELECT COUNT(*) FROM evaluation_event WHERE produced_at > :deploy AND solar_low_cloud IS NOT NULL` must keep growing. If it flatlines, the harness's input was severed and Phase 0's retarget did not take.
- Test: `WeatherTriageEvaluatorTest` keeps every threshold assertion (80 / 2.0 mm / 5000 m) — the classifier is unchanged, only its consumer is. **Do not touch `PromptRegressionTest`**; `coptHill_5Mar` pins solar low 67 % → rating ≤ 2, 7 pp above the ">60 % BLOCKED" rule.
- Log line: `[TRIAGE] classified N slots (cloud=A precip=B vis=C) — 0 gated`.
- **Prompt-cache check, and this one is easy to forget:** `SELECT SUM(cache_read_input_tokens), SUM(cache_creation_input_tokens) FROM api_call_log WHERE is_batch AND called_at > :deploy` — if cache reads collapse, the larger, more heterogeneous batch has broken system-prompt caching and the cost delta will be far worse than the request-count delta predicts. **UNVERIFIED**: whether `api_call_log` carries cache-token columns; if not, read the batch result usage in `BatchResultProcessor.persistTokenUsage` (`:532`) instead.
- **`SYSTEM_PROMPT` untouched.** ≥ 15,500 chars (`PromptBuilder.java:65`). Below it Haiku caching dies silently.

**Rollback:** revert. The gate is a single `continue`; restoring it is a small, well-tested diff. Immediate stop = pause the cycle jobs.

**Precondition for next phase:** two weeks within the (revised) cost envelope.

---

#### Phase 3 — Delete the subsystems, including the synchronous engine

**Ships:** `ForecastCommandExecutor`, `ForecastCommand`, `ForecastCommandFactory`, `SentinelSelector`, the four `POST /api/forecast/run*` endpoints + `retry-failed`, and — in the same commit — the frontend buttons that call them (`frontend/src/api/forecastApi.js:119, :137, :155, :166`). `POST /api/pipeline/run` replaces them. `stability_snapshot` (V98), `evaluation_delta_log` (V97) and `optimisation_strategy` are dropped.

**Proof obligations:**
- Test: `PipelineRunControllerTest.adminRunCreatesACycleWithTheGivenSelectWindow`.
- Test: existing `ForecastControllerTest` cases for the deleted endpoints are removed, not weakened.
- Query: `SELECT COUNT(*) FROM forecast_evaluation WHERE forecast_run_at > :deploy` must be **0** — proof the last writer to that table is gone, which is Phase 4's precondition.
- Manual: an admin run from the UI produces a `pipeline_run` row and reaches PUBLISH.
- JaCoCo: deleting `ForecastCommandExecutorTest` (2,073 lines) removes coverage of code that is also gone; watch the per-class gate on whatever absorbs `shouldSkipEvent` (`:751-760`), which must move to SELECT with its own tests rather than vanish.

**Rollback:** the largest revert in the plan and the one to rehearse. `git revert` restores engine and endpoints together — which is exactly why they must ship together.

---

#### Phase 4 — Switch reads to `evaluation_current`, retire `cached_evaluation`

**Ships:** `EvaluationViewService` deleted; `BriefingService.enrichWithCachedScores` (`:664-673`) reads the view; `cached_evaluation` and `forecast_evaluation` dropped after reconciliation.

**Proof obligations:**
- Query: for a fixed date window, the old merge and the new view must return identical (location, date, target, rating) tuples. Run it for a week before dropping anything.
- Query: `SELECT COUNT(*) FROM cached_evaluation WHERE updated_at > :deploy` = 0.
- Test: `EvaluationCurrentViewTest.latestEventWinsPerSlot` — including the retry case (two events, later one projected).

**Rollback:** the view is derived; reverting the read switch restores the merge as long as the tables are still there. **Do not drop the tables in the same release as the read switch.** One release apart, minimum.

---

#### Phase 5 — UI shrink

**Ships:** stability view, optimisation-strategy config, freshness config removed; the disposition view grown into the selection record (skipped rows visible by default). `SchedulerView` untouched — it manages jobs, not gates, and it is the rollback lever for every earlier phase.

**Proof obligation:** frontend suite green (`cd frontend && npm run test -- --reporter=dot`); no route in `App.jsx` points at a deleted endpoint.

---

### 8.7 Cost monitoring, normalised by travel days

The naive comparison is wrong twice over. The summary design catches one error ("the 30-day window had ~15 travel days") and states the fix as "divide by non-travel days" (`all-in-architecture-design.md:77-82`). **That fix is itself wrong**, and the reason is in the gate's implementation: the travel gate keys on the **target** date, not the run date (`BriefingCandidateCollector.java:177`). A cycle that runs on a workable Tuesday is still cheap if Wednesday, Thursday and Friday are all travel days, because three of its four target dates are gated out. Dividing by "non-travel run days" attributes that cheapness to nothing.

The correct normaliser is **workable target-days covered**, computed per run day over that run's horizon.

```sql
-- Claude spend per calendar day, normalised by the workable target-days
-- each day's cycles actually covered.
--
-- Column provenance:
--   api_call_log.service / .called_at / .cost_micro_dollars  V20, V38:8-9
--   travel_day.start_date / .end_date                        V119
--   ServiceName.ANTHROPIC                                    entity/ServiceName.java
WITH run_days AS (
    SELECT generate_series((now() - interval '30 days')::date,
                           now()::date, interval '1 day')::date AS run_day
),
workable AS (
    SELECT r.run_day,
           COUNT(*) FILTER (
               WHERE NOT EXISTS (
                   SELECT 1 FROM travel_day t
                   WHERE h.target_date BETWEEN t.start_date AND t.end_date)
           ) AS workable_target_days
    FROM run_days r
    CROSS JOIN LATERAL (
        SELECT r.run_day + i AS target_date FROM generate_series(0, 3) AS i
    ) AS h
    GROUP BY r.run_day
),
spend AS (
    SELECT a.called_at::date AS run_day,
           SUM(COALESCE(a.cost_micro_dollars, 0))::numeric / 1e6 AS usd,
           COUNT(*)                                    AS calls,
           COUNT(*) FILTER (WHERE a.is_batch)          AS batch_calls
    FROM api_call_log a
    WHERE a.service = 'ANTHROPIC'
      AND a.called_at >= now() - interval '30 days'
    GROUP BY 1
)
SELECT w.run_day,
       w.workable_target_days,
       COALESCE(s.usd, 0)                       AS usd,
       COALESCE(s.calls, 0)                     AS calls,
       COALESCE(s.batch_calls, 0)               AS batch_calls,
       CASE WHEN w.workable_target_days = 0 THEN NULL
            ELSE ROUND(COALESCE(s.usd, 0) / w.workable_target_days, 4)
       END                                      AS usd_per_workable_target_day
FROM workable w
LEFT JOIN spend s USING (run_day)
ORDER BY w.run_day;
```

`usd_per_workable_target_day` is **the** comparison metric. It is NULL — not zero — on a day whose entire horizon is travel, which is the same "absence is not a measurement" rule the design applies to `ConfidenceDeriver` and to `CalibrationBucket`. One rule, now four surfaces.

The 30-day at-home rate, as a single number to compare against:

```sql
WITH per_day AS ( /* the CTE above */ )
SELECT ROUND(AVG(usd_per_workable_target_day), 4) AS usd_per_workable_day,
       ROUND(AVG(usd_per_workable_target_day) * 30, 2) AS projected_usd_30d,
       COUNT(*) FILTER (WHERE usd_per_workable_target_day IS NULL) AS fully_gated_days
FROM per_day;
```

Split by cycle so intraday's contribution is separable — nightly fires ~01:00 UTC, intraday at 14:00 UTC (`V105:24`), so a time-of-day split is exact and needs no join through the incomplete `job_run → forecast_batch → pipeline_run` chain:

```sql
SELECT CASE WHEN EXTRACT(hour FROM a.called_at) < 12 THEN 'NIGHTLY' ELSE 'INTRADAY' END AS cycle,
       ROUND(SUM(a.cost_micro_dollars)::numeric / 1e6, 2) AS usd,
       COUNT(*) AS calls
FROM api_call_log a
WHERE a.service = 'ANTHROPIC' AND a.called_at >= now() - interval '30 days'
GROUP BY 1;
```

**The threshold to gate on, stated as a procedure rather than a number:** compute `usd_per_workable_target_day` over the 14 days before each phase and the 7 days after. The pre-change value is the baseline; the predicted post-change value is baseline × (1 + the disposition share that phase removes, measured on the *same* pre-window). Pass if observed is within 25 % of predicted. **Do not reuse +£32–48/month** — it was computed with triage retained and with the wrong normaliser.

---

### 8.8 What no existing harness can verify

Every phase above is provable in the sense of "did the code do what we said". None of them is provable in the sense of "is the forecast better", and the instruments are more confounded than their names suggest.

| Harness | What it compares | What it cannot see |
|---|---|---|
| Prompt regression (`src/test/java/**/regression/`) | Claude output vs hand-written expectations | Whether the expectations are right. Stays green while every forecast drifts, because both sides are authored |
| Sky-rating eval | model output vs fixtures | Same closure. Fixtures were written by the same process being tested |
| Model comparison (`BriefingModelTestService`) | Haiku vs Sonnet vs Opus on one rollup | Only *disagreement between models*. Three models sharing an input bias agree perfectly and are all wrong |
| ERA5 cloud verification | forecast cloud vs reanalysis | Shares biases with the forecast model exactly where cloud is hardest (marine layers, stratocumulus, orographic). The +24.67 pp offset is flat across lead time — a dataset artefact. **Relative only** |
| Calibration gate | forecast rating vs recorded outcome | **Nothing, today: `actual_outcome` has zero rows.** The only non-self-referential instrument, and it is empty |

**Stated per phase, plainly:**

- **Phase 0** proves the event table reconciles with history. It proves nothing about forecasts.
- **Phase 1** proves T+3 now enters the candidate set and that no upstream stage reads publication. It does **not** prove the T+3 evaluations are any good — only that they happen. The T+3 bug was a selection defect; fixing it makes T+3 *measurable* for the first time, which is a precondition for judging it, not a judgement.
- **Phase 2a** proves the skip categories are gone and quantifies the cost. It proves nothing about whether the newly-evaluated slots produce different or better ratings. The only way to know is a rating-distribution diff, which is descriptive: `SELECT rating, COUNT(*) FROM evaluation_current WHERE target_date BETWEEN ... GROUP BY 1`, before and after. A shift there is information; it is not validation.
- **Phase 2b** proves triage no longer gates. It **cannot** prove the summary design's §2.3 prediction — that newly-admitted slots all land at rating 1–2 because the prompt forces it. That prediction is testable and should be tested as a *description*: `SELECT rating, COUNT(*) FROM evaluation_current WHERE solar_low_cloud > 80 GROUP BY 1`. If a meaningful share come back ≥ 3, either the prompt is not doing what §2.3 assumed or triage was discarding real signal — and **no harness in the project can tell you which**. That question needs recorded outcomes.
- **Phase 3** proves the endpoints are gone. It proves nothing about forecasts.
- **Phase 4** proves the projection equals the merge. Structural only.
- **Phase 5** proves the UI still renders.

**The honest summary: this entire redesign can complete, every phase green, with forecast quality unchanged, improved, or degraded, and the project would not be able to tell.** That is not an argument against it — the redesign's case is simplification and selection-effect elimination, both of which *are* verifiable here. But it means the twenty recorded outcomes Phase 0 asks for are worth more than every other proof obligation in this document combined, and they are the only thing that turns Phase 2b's prediction into a finding.

---

### 8.9 Risk register

Ranked by expected damage × probability.

**R1 — Removing triage collapses the prompt cache and the cost delta is several times predicted.** *(highest)*
The batch grows by the triage share and, more importantly, changes composition: the bucketing (`ScheduledBatchEvaluationService.java:382-422`) exists to keep each batch homogeneous so its system prompt caches. Adding a large tranche of high-cloud slots does not break homogeneity by itself — they take the same sky prompt — but it does shift the near/far and inland/coastal mix, and cache behaviour under a much larger request count is not something this project has measured. Below 15,500 chars caching dies silently (`PromptBuilder.java:65`); above it, a bad mix simply reads fewer cached tokens with no signal at all.
*Mitigation:* the cache-token check is a **named proof obligation of Phase 2b**, not a follow-up. Ship 2b on a Monday and read it Tuesday. Scheduler pause is the immediate stop.

**R2 — The ERA5 harness silently stops accumulating when triage is deleted.** *(high probability, high damage, and it is invisible)*
Its population is `forecast_evaluation` (`CloudVerificationRepository.findUnverified`), and on the batch path the only inserts there are triage rows (`ForecastService.java:376`, `:406`). Delete triage before retargeting and 25,730 verified rows stop growing with no error. This is the exact failure shape of the four false doc claims: nothing breaks, so nothing is noticed.
*Mitigation:* Phase 0's retarget is a **hard** precondition for Phase 2b, enforced by the continuity query in that phase's proof obligations. Run it daily for the first week.

**R3 — Silent submission failure publishes a stale briefing and marks the cycle COMPLETED.** *(exists today; the redesign makes it likelier)*
`BatchSubmissionService.java:162-166` swallows every exception → `EvaluationHandle.empty()` (`EvaluationServiceImpl.java:159-163`) → ignored by `submitBuckets`. With zero batches persisted, `allTerminal()` is true (`PipelineOrchestrator.java:632-634`) and the run completes having spent nothing and evaluated nothing. A larger, ungated batch raises the odds of hitting an API limit mid-submit.
*Mitigation:* the EVALUATE contract clause — fail the phase when `submittedRequests < candidates`. Land it in **Phase 2a**, ahead of the volume increase, not with it.

**R4 — Batch latency stretches past the 4 h safety timeout.** *(medium)*
`DEFAULT_SAFETY_TIMEOUT` is 4 h and was calibrated on observed afternoon batches of 98–173 min (`PipelineOrchestrator.java:96-105`). Intraday fires at 14:00 UTC — peak Anthropic load — and its request count rises. The single observed failure in 60 cycles was already a wait-phase hang.
*Mitigation:* raise `photocast.pipeline.safety-timeout` to PT6H **before** Phase 2b (property, no deploy). Monitor `SELECT AVG(EXTRACT(epoch FROM completed_at - started_at)) FROM pipeline_run_phase WHERE phase='EVALUATE_WAIT'` weekly. Note the real dependency: WAIT completes only when `batch_result_polling` transitions the rows, so a paused poller looks identical to a slow Anthropic — and the phase detail string does not distinguish them. Add the poller's last-tick time to `waiting_on`.

**R5 — The best-bet advisor's input distribution shifts and its picks get worse.** *(medium, and the least likely to be noticed)*
`BriefingBestBetAdvisor.advise(days, ...)` (`BriefingService.java:499`) is handed the enriched day hierarchy. Today that hierarchy is sparse in a *structured* way — triaged and gated slots carry no rating. After 2b every slot carries one, and a large new mass of 1–2 ratings enters the distribution the advisor ranks over. A prompt tuned on "these are the slots worth considering" now sees the whole roster.
*Mitigation:* `pipeline_run_pick` already records each cycle's picks and `PipelineRunComparisonService` already compares across runs — that infrastructure exists for exactly this question. Diff Plan A picks for the two weeks either side of 2b. A change in *which* location wins is expected; a change in the *character* of the winner (consistently lower-rated, consistently nearer) is the alarm.

**R6 — Deleting the synchronous engine removes an operator capability that turns out to be load-bearing.** *(medium probability, low damage, easy to reverse)*
The four Run buttons and the SSE progress view are real UI. The replacement is a batch cycle with batch latency.
*Mitigation:* ship `POST /api/pipeline/run` one release **before** the deletion so the operator uses it in anger first. Keep `EvaluationService.evaluateNow` for single-slot immediacy.

**R7 — Phase 1 doubles gloss + best-bet spend by accident.** *(low probability, immediate cost)*
The naive form of this fix — calling `refreshBriefing()` at the head of the cycle — runs *both* halves twice, at ~56 Claude calls per refresh, and leaves the served cache gloss-less for the 2–4 h of batch latency. The split avoids it by construction; a partial implementation would not.
*Mitigation:* Phase 1's "gloss calls per cycle unchanged" query is a listed proof obligation, and `buildSlateMakesNoClaudeCall` is a mockito-verified test.

**R8 — Losing the intraday cycle's daily weather refresh.** *(low probability, high damage)*
Since V103 removed the standalone `daily_briefing` cron, intraday is the day's only second slate. Any reasoning that treats it as "just a re-evaluation" and deletes it leaves the Plan tab on a 01:00 fetch until the next night.
*Mitigation:* stated as a first-class conclusion in §8.4 rather than left to be rediscovered. If the intraday cost ever needs cutting, the honest lever is to run SLATE + PUBLISH without EVALUATE — losing re-scores but keeping the weather — and that trade must be made explicitly, not by deleting a job.

**R9 — JaCoCo's 80 %-per-class gate blocks the deletion commits.** *(low, but it will cost an afternoon)*
Deleting 4,049 test lines alongside 3,224 production lines shifts coverage unpredictably per class; the classes that *absorb* moved logic (SELECT taking `shouldSkipEvent` from `ForecastCommandExecutor.java:751-760`) are new and small, which is exactly where the per-class rule bites.
*Mitigation:* cover the moved guards with real assertions. Never delete a guard to hit coverage. Reproduce CI locally with `./mvnw clean verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false` and gate on the exit code, never on a grep of the output.

---

### 8.10 Line-count tally for this section

**Deleted whole (production, verified by `wc -l`):**

| File | Lines |
|---|---|
| `service/ForecastCommandExecutor.java` | 826 |
| `service/OptimisationStrategyService.java` | 241 |
| `service/OptimisationSkipEvaluator.java` | 206 |
| `service/batch/ForceEvalHeadlineSelector.java` | 167 |
| `service/ForecastCommandFactory.java` | 152 |
| `service/batch/NightlyEligibilityPolicy.java` | 73 |
| `service/batch/EligibilityDecision.java` | 66 |
| `service/batch/IntradayCandidateCollectionStrategy.java` | 63 |
| `service/SentinelSelector.java` | 58 |
| `service/batch/IntradayEligibilityPolicy.java` | 54 |
| `service/batch/ReclassSummary.java` | 53 |
| `service/ForecastCommand.java` | 45 |
| `service/batch/EligibilityPolicy.java` | 40 |
| `service/batch/CandidateCollectionStrategy.java` | 38 |
| `service/batch/NightlyCandidateCollectionStrategy.java` | 37 |
| **Subtotal** | **2,119** |

**Reduced in place (estimates against verified current sizes):** `ForecastTaskCollector` 899 → ~520 (−380); `ScheduledBatchEvaluationService` 621 → ~330 (−290); `BriefingCandidateCollector` 354 → ~180 (−175); `PipelineOrchestrator` 669 → ~560 (−110); `ForecastController` (−~150). **Subtotal ≈ −1,105.**

**Production total ≈ 3,224 lines removed.**

**Tests removed (verified):** `ForecastCommandExecutorTest` 2,073 · `OptimisationSkipEvaluatorTest` 368 · `ForecastTaskCollectorForceEvalTest` 350 · `CollectForecastTasksCachedGateTest` 350 · `ForecastCommandFactoryTest` 234 · `OptimisationStrategyServiceTest` 216 · `SentinelSelectorTest` 138 · `ForecastTaskCollectorEligibilityPolicyTest` 117 · `IntradayCandidateCollectionStrategyTest` 78 · `IntradayEligibilityPolicyTest` 67 · `NightlyEligibilityPolicyTest` 58. **Total 4,049 test lines.**

**Added:** two migrations (V139 `evaluation_event` + `evaluation_current`, V140 `briefing_slate` + `pipeline_run.select_window`), `HorizonModelSelector` (~30 lines), a `SelectionStage` class (~180 lines absorbing what survives of two collectors), the `publicationIsTerminal` ArchUnit guard, and the new phase constants. Net production change is roughly **−3,000 lines**.
