# Input-equivalent sharing: one rating request for places whose inputs match

**Status:** draft, 2026-11-16. Nothing built. Revives `triage-cost-cut-plan.md` P2 with a different
equivalence key; P2b's fan-out design is reused as written. Phase 0 is a free read-only query; Phases 1–3
change the batch path behind a switch that defaults off.

---

## 1. Why, and what changed since P2 was rejected

P2 ("one request per set of equivalent places in a cell") was rejected on 2026-11-16: comparing the **exact
prompt minus the name**, one request per group would have saved 1.25% of Sonnet T+0/1 sky calls (4.3% with the
event time cut to the minute), under P2a's 8% bar (`shared-call-replay.sql`).

The owner asked the follow-up the replay did not: what if places in neighbouring cells, or with slightly
different horizon samples, still get **the same decision inputs**? Measured the same day (production, 30 days,
24,768 Sonnet T+0/1 rated requests, grouped by run hour, date, event, region and coastal flag):

| Key | Calls saved | Share | ~$/30 days | Groups with identical stars | Groups split ≥ 2★ | p90 event-time gap |
|---|---|---|---|---|---|---|
| A: overhead low/mid/high, cone low/mid/high, rain, visibility | 3,671 | 14.8% | 9.91 | 79.1% | 4 | 0.1 min |
| B: A + antisolar mid/high, far low, `[BUILDING]` | 3,268 | **13.2%** | **8.82** | 79.3% | 3 | 0.1 min |

So decision-level equivalence clears the 8% bar, and the places already agree: the 1★ splits (~21% of
groups) sit at the model's measured wobble between identical prompts (16–19%, ledger "Cost per call and
shared cells"), and 2★ splits are 3 groups in 30 days. Sharing would mostly remove noise, and make neighbours
agree (the complaint that moved Haiku off Far-Term).

**What key B ignores** is what made exact prompts differ: the event time (seconds), the upwind sample's
geometry, the approach-trend history slots, the mist/visibility trend numbers, and (between different cells)
the overhead detail lines (humidity, dew point, aerosol, wind, boundary layer). Some of those feed prompt
rules (the mist rules read the temp-dew gaps; the aerosol rule reads AOD and PM2.5). So the key must be chosen
on evidence, not by hand. Phase 0 does that.

Base after P1 (the 60% triage line): ~$63/30 days of Sonnet T+0/1, so 13% is roughly **$8/month**.

---

## 2. Phase 0: choose the key from history (read-only, free, owner runs)

`docs/engineering/cost-queries/input-equivalent-share-replay.sql` replays the last 30 days of stored prompts
(`forecast_evaluation_prompt`) through a **ladder of masks**, each level adding to the one before. Every level
keeps the date, event, model, system-prompt hash (inland/coastal), the pipeline run and **every bracketed rule
label** (`[BUILDING]`, `[THIN STRIP]`, `[CLEARING]`, `[AT/NEAR DEW POINT]` …); masks replace numbers or place
geometry only:

| Level | Adds | Why it is a candidate |
|---|---|---|
| L0 | name prefix removed | the rejected P2 key (1.25%) |
| L1 | event time to the minute | P2a's minute key (4.3%) |
| L2 | event time removed | places in one run differ by seconds |
| L3 | upwind geometry "(43km along 244° WSW)" removed, values kept | per-place geometry, not weather |
| L4 | approach-trend history T-3h..T-1h masked, event slot and labels kept | the labels carry the rule |
| L5 | mist/visibility trend numbers masked, labels kept | the labels carry most of the rule |
| L6 | overhead detail lines masked | reference only; not adoptable without a new review |

The query reports, per level: saveable requests, share, dollars, and inside the groups how many already got
identical stars, a 1★ split and a 2★+ split.

**Decision (fixed before the query is run):**
1. Adopt the **lowest level ≤ L5** whose saving is **≥ 8%** of requests (P2a's bar, unchanged).
2. That level's groups must have 2★+ splits in **< 1%** of groups and 1★ splits at or below **25%** of groups
   (the measured wobble plus margin). A level that saves more but fails this is not adopted.
3. If no level ≤ L5 passes, stop: record the ladder in `claude-cost-ledger.md` and close the plan.
4. L6 is never adopted from this phase: it would hide inputs the aerosol and mist rules read.

Record the ladder and the chosen level here and in the ledger.

---

## 3. Phase 1: the key and the fan-out, switched off (one PR)

**Key.** A pure function `ShareKey.of(task, builtRequest, level)` in `service/evaluation/`, replacing P2's
"exact message minus name" with the Phase 0 level's masks, implemented as the same regular expressions the
query used (one shared constant list, with a test that each mask leaves every bracketed label intact). The key
also includes, explicitly (P2's list, unchanged): model id, `maxTokens`, thinking and effort settings,
`cachePrefixKey`, target date and event. Eligibility is P2's: sky lane only; never woodland, bluebell, a forced
(`-f`) task or a null `evalRowId`.

**Everything else is P2b as written** (`triage-cost-cut-plan.md` §"P2b: the fan-out"), reused without
redesign:
- **Persistence:** each place keeps its own PENDING row with **its own stored weather**; new nullable
  `forecast_evaluation.shared_eval_row_id` (next free migration, indexed) points a sibling at its
  representative. `custom_id` and `api_call_log` stay one-to-one with requests.
- **Representative:** the lowest location id in the group; its request is unchanged, name included.
- **Prompt store and hash:** each sibling stores its own rendered message and the representative's
  `prompt_hash`.
- **Fan-out in `BatchResultProcessor`**, abandonment, retry expansion, failure evidence counted once per group,
  dispositions, `BatchSummaryDeriver` counts, the cost-per-request note: all as P2b specifies.
- **Name in the summary:** the representative's exact name replaced by the sibling's, literal and
  case-sensitive with explicit boundaries.

**One addition to P2b for this key:** because siblings' masked numbers can differ from the representative's,
the sibling's own row keeps its own weather columns (already true) and `ratings-stores.md` gains one line: a
shared rating is the representative's judgement of inputs equal at the chosen level, not of the sibling's
exact numbers.

**Switch** `photocast.batch.share-equivalent-requests`: `off` (default), `verify`, `on`, as P2b.

**Shadow log.** Replace the `[BATCH SHARE]` counts (#1840, now redundant for the exact key) with the chosen
level's counts, so production confirms the Phase 0 figure after P1's triage line.

---

## 4. Phase 2: the verify week (owner switches `verify`)

`verify` submits every sibling as today and records the shared answer beside each sibling's own; readers see
only the sibling's own. A new read-only query `cost-queries/share-verify.sql` compares them over at least
**7 days** spanning settled and unsettled weather.

**Turn `on` only if:** shared vs own stars differ by **2★ or more in < 1%** of sibling pairs, by **1★ in no
more than the noise floor** (~8.5%, `noise-floor.sql`; the owner may accept up to the identical-prompt wobble of
16–19% if the saving justifies it, decided before the week starts), and the realised saving is **≥ 8%** of
SCHEDULED T+0/1 sky requests. Otherwise switch back to `off` and record why.

## 5. Phase 3: `on`, and the re-measure

Owner sets `on`. After 14 days re-run `measured-costs.sql` and the share log; record the saving in the ledger.
Watch neighbour agreement (the reason Haiku left Far-Term): it should improve, never worsen.

---

## 6. Build and review (per CLAUDE.md)

- Phase 0 is this plan plus the query (docs only).
- Phase 1 gets its own adversarial plan review sized for a cross-cutting batch-path change (P2b's own note),
  then Sonnet builds it in a worktree with P2b's test list (`triage-cost-cut-plan.md` §"P2b tests"), plus:
  each mask preserves every bracketed label; two prompts equal at the chosen level but different below it
  share, one differing in a label does not; the shared key matches the query's on a fixture of real prompts.
  Gates: the backend build ladder; the migration's Testcontainers test is proven in CI.
- `docs/engineering/reference/pipeline.md` and `ratings-stores.md` updated in Phase 1's PR.

---

## 7. Limits

- **ERA5 is not involved**; "equivalent" means equal at the chosen mask level in the prompt Claude would see.
- **The replay is the pre-P1 mix** (before the 60% triage line); the shadow log in Phase 1 re-measures it.
- **Masked numbers can matter at the margin** (a mist gap of 0.6 vs 1.5 °C); the verify week is the guard.
- **Calibration** counts siblings as separate samples of one judgement; noted in `ratings-stores.md`.

## 8. Out of scope

Far-Term (Haiku 4.5, small and weather-dependent), woodland and bluebell lanes, cross-run reuse (the
"stability gate", rejected), and any prompt or model change.
