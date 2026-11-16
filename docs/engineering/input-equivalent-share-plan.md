# Input-equivalent sharing: one rating request for places whose inputs match

**Status:** reviewed 2026-11-16 (six adversarial lenses plus a refuter; record in §9). **Verdict: not built.
Phase 0 (a free, read-only replay) runs on or after 2026-11-29; nothing else proceeds unless it passes.**
Revives `triage-cost-cut-plan.md` P2 with a masked equivalence key; reuses P2b's fan-out design with the
changes in §4.

---

## 1. Why, and what the review changed

P2 (one request per set of places whose prompts are identical apart from the name) was rejected on
2026-11-16: 1.25% of Sonnet T+0/1 sky calls ($0.85/30 days), 4.3% with the event time cut to the minute
(`shared-call-replay.sql`), under P2a's 8% bar.

The owner asked whether places whose *decision inputs* match could share. A first measurement grouped on
stored `forecast_evaluation` columns (overhead and sun-side cloud, rain, visibility, plus antisolar, far point
and `[BUILDING]`, by run hour and region) and found 13.2% of calls (~$8.82/30 days) with 79% of groups already
on identical stars.

**That 13% is an upper bound, not evidence for any level we could adopt** (refuter: survives). It ignored
orientation, the upwind values, the overhead detail lines and the site blocks (inversion, valley mist,
stability), all of which every adoptable level keeps. The adoptable saving lies between L1's 4.3% and ~13%,
likely nearer the low end: **realistically ~$3–6/month** on the post-P1 base (~$59–63/30 days).

Sharing also changes evidence the product relies on: it **correlates** neighbouring ratings. It removes
visible disagreement between equivalent places; it does not make any place more accurate.

---

## 2. Phase 0: the replay (read-only, free; the owner runs it on or after 2026-11-29)

`docs/engineering/cost-queries/input-equivalent-share-replay.sql`, tested end to end on PostgreSQL 16 with
synthetic prompts (2026-11-16). It:

- **Uses the post-P1 population:** Sonnet 5.5, T+0/1, scheduled cycles (NIGHTLY, INTRADAY, EVENING), retry
  batches excluded, and only rows P1 still submits (sun-side low cloud ≤ 60% or a thin strip). Run after
  2026-11-29 so the 30-day window is mostly post-P1.
- **Groups per forecast batch** (one `submitForecast` call), the only scope production can share within.
- **Climbs a ladder of masks**, each keeping the date, event, model, system-prompt hash and every bracketed
  rule label:

  | Level | Adds | Status |
  |---|---|---|
  | L0 | name prefix removed | the **control** (identical prompts apart from the name) |
  | L1 | event time to the minute | adoptable |
  | L2 | event time removed | adoptable: no rule reads the clock time |
  | L3 | upwind geometry removed, `current=`/`at-event=` kept | adoptable: rules read only the values (PromptBuilder ~l.291–297) |
  | L4 | approach-trend T-3h..T-1h masked, event slot and `[BUILDING]`/`[CLEARING]` kept | adoptable: thresholds live in `SolarCloudTrend` (~l.25–34), not in the prompt |
  | L5 | mist/visibility trend numbers masked | **reference only, never adoptable**: the mist rules read the visibility direction directly (~l.195, 204–205) and the valley-mist block tells Claude to read it (~l.975–976) |

  "No rule reads it" is not "no influence": Claude may still react to unlabelled numbers. The gate below is
  what proves L2–L4 safe in practice.
- **Measures what sharing does:** each sibling against its group's representative (lowest
  `md5(location_id|date|event)`, a deterministic rotation Phase 1 must also use). Pairs = calls saved.
  Per level, cumulative and **marginal** (pairs new at this level):
  D1 (stars differ by ≥ 1), D2 (≥ 2), X4 (one side ≥ 4★, the "worth going" line), B (mean representative −
  sibling stars); by the representative's star band; and a day-block bootstrap (1,000 resamples, fixed seed)
  of the differences from the L0 control.
- **Checks itself:** mask health (each mask fires; no row loses a label) and concentration (the share of the
  saving carried by the top 20 place pairs).

### Decision (fixed now, before the query is run)

1. **Candidate:** the lowest level ≤ L4 whose post-P1 saving is **≥ 8%** of requests **and ≥ $5/30 days**.
2. **Safety:** the candidate passes only if, cumulatively at its level **and** on the marginal pairs of every
   step up to it (a step with fewer than 200 marginal pairs is judged cumulatively only):
   - upper 95% bound of (D1 − D1 control) ≤ **+5 pp**;
   - upper 95% bound of (X4 − X4 control) ≤ **+4 pp**;
   - D2 ≤ **1%** of pairs, and upper bound of (D2 − D2 control) ≤ **+1 pp**;
   - the 95% interval of B within **±0.10★**.
3. **Monotone stop:** if the first level reaching the saving bar fails safety, stop. No higher level may be
   adopted. A point estimate that passes with a bound that does not is a fail.
4. **Mask health:** if any mask never fires or any row loses a label, fix the query and re-run before deciding.
5. **Record** the ladder, the decision and the concentration result in `claude-cost-ledger.md` either way.

If Phase 0 fails, the plan closes here.

---

## 3. If Phase 0 passes: choose the design

- **Static proxy first.** If the top 20 place pairs carry most of the saving (section 3 of the query), prefer
  a static "rate X as a proxy of Y" setting for those pairs, subject to the same safety evidence per pair,
  over the general fan-out. It avoids per-batch keys, regex parity, retry expansion and failure-evidence
  changes. It still needs §4's read-path rules.
- **General fan-out otherwise**, per §4. It gets its own adversarial plan review before any build.

---

## 4. Requirements any build must meet (from the review)

**Read path: one judgement is one vote (blocker).** `NeighbourOutlierGuard` clears a high slot with a single
supporter within 5 km (`NeighbourOutlierGuard.java` ~l.199–201), so a sibling copying a rogue 4–5★ shields it,
and it can become the named best bet or an Ask pick. Carry a share-group id (the representative's row id) on
the served slot (in memory, not personal data):
- the guard treats a share group as one point: a slot never supports another in its own group, and a group
  counts once toward `min-neighbours`;
- `VerdictSampleGate`'s rated count and the `ConfidenceDeriver` spread count one per request;
- tests: three siblings at 5★ with two independent 3★ neighbours within 5 km are flagged.

**Eligibility (blocker).** Sky lane; T+0/1 only (`ForecastHorizon.daysAhead ≤ 1`); trigger SCHEDULED or RETRY;
model equal to the active `BATCH_NEAR_TERM` model; never woodland, bluebell, forced (`-f`) or a null
`evalRowId`; never a message carrying a `VALLEY MIST CONDITIONS`, `Coastal mist:` or `CLOUD INVERSION FORECAST`
block above L2. Every other path is byte-identical to `off`. Tests for a T+2, region-filtered, Haiku and
valley-mist task.

**Fan-out seam.** `buildResult` is private (`ForecastResultHandler.java` ~l.554) and only reachable through
`parseBatchResponse`, which writes `api_call_log`. Add a dedicated entry point that parses once, writes exactly
one log row, and runs `buildResult` per place with its own location, region, row and tide context, sharing the
**parsed pre-combine answer** (`SunsetEvaluation`), never the representative's combined rating. A sibling whose
`buildResult` throws is logged and skipped without touching `errored`.

**Shared fields.** All of `SunsetEvaluation`: rating, fiery, golden, summary, headline, inversion score and
potential, mist potential. The name swap applies to `summary` **and** `headline`. After the swap, if either
still contains a distinctive word or a feature word (Bay, Castle, Head, Point, Tarn, Fell, Pier, Lighthouse …)
from the representative's name absent from the sibling's, the sibling gets null `summary` and `headline`
(stars and potentials kept; the UI falls back to the region gloss).

**Failure evidence.** Siblings inherit success only. A failed group gives siblings no strike of their own; the
retry expansion (P2b) gives them fresh requests and their own evidence. The like tally counts a group once.
Retry's `failureCap` decision stays on failed requests; expansion happens after it.

**Stamping and readers.** `shared_eval_row_id` is stamped beside `stampPromptHashes`, before submission.
Cost queries that treat a scored row as a request add `AND f.shared_eval_row_id IS NULL` (`triage-headroom.sql`,
`rerating-waste.sql`, `poor-stays-poor.sql`). `ratings-stores.md`: a sibling's prompt row is the message it
*would* have sent; the judged message is on its representative's row.

**Switch** `off` / `verify` / `on` (P2b). `verify` is kept: it is the only test of the Java key, the fan-out and
the name handling in production. Where it stores the shared answer must be specified (a column, or log-only).

---

## 5. Verify and on (only after §3 and §4)

- **Duration:** until **all** of: ≥ 14 days, ≥ 1,000 sibling pairs, ≥ 4 unsettled days (defined before the
  start).
- **Concurrent control:** re-submit the representative's exact request for ~40 groups/day (~$1.50 total, an
  owner spend decision), giving identical-prompt pairs in the same weeks.
- **Turn on** only if §2's safety rules hold against that control (same bootstrap), the Java grouping equals
  the SQL grouping on 100% of the period's prompts, the trailing 30-day replay still clears §2.1, and named
  window picks changed by the guard are zero or explained.
- **After on:** a permanent ~5% deterministic holdout of sibling slots stays individually submitted; re-run
  the gate monthly and at each season's first month; a breach sets `off`. Re-run `measured-costs.sql` after
  14 days and record the saving.

---

## 6. Build and review (per CLAUDE.md)

Phase 0 is docs only (this plan and the query). Any build gets its own plan review sized for a cross-cutting
batch-path change, then Sonnet builds it in a worktree with P2b's test list plus §4's tests. The migration is
proven in CI.

## 7. Limits

- ERA5 plays no part; "equivalent" means equal at the chosen mask level in the prompt Claude would see.
- The replay is the October–November regime; the holdout covers seasons.
- Calibration counts siblings as separate samples of one judgement unless de-duplicated by
  `shared_eval_row_id`.
- The representative's name stays in its own request; Claude may infer a site's elevation or water from it.

## 8. Out of scope

Far-Term, woodland and bluebell lanes, cross-run reuse (the rejected stability gate), and any prompt or model
change.

---

## 9. Review record (2026-11-16)

Six read-only lenses (SQL and masks, rating safety, statistics, batch pipeline, user-visible effects, is the
saving real), then a refuter tested eleven claims against the code.

- **Survived:** L2–L4 rule-safe (as "no rule reads it"); the 13% is not evidence for an adoptable level;
  sharing defeats `NeighbourOutlierGuard` and makes `VerdictSampleGate` and the confidence spread count one
  call as several votes; eligibility did not exclude Far-Term, admin runs or retries.
- **Valid but narrowed:** L5 unsafe (event visibility and the valley-mist score stay visible, and the gap
  labels partly carry direction, but the visibility trend rules have no label); the group-spread metric is
  biased (the L0 control already existed; the threshold was not anchored to it); the 8.5% verify bar was the
  wrong null, but `verify` does test the code path; the name leak was a known P2b risk, the headline gap new;
  duplicate `api_call_log` rows only on a naive reuse of `parseBatchResponse`; inherited failure strikes are
  a narrow risk, not a general one; the Ask-precompute comparison is weakly evidenced (October figures).
- **Changes:** the query was rewritten (post-P1 population, per-batch grouping, pair metrics against the L0
  control, marginal pairs, bootstrap, star bands, mask health, concentration) and tested on PostgreSQL 16;
  L5 is reference only; the decision rule is pre-registered with a $5 floor and a monotone stop; §4 lists the
  read-path, eligibility, fan-out, shared-field, failure and stamping requirements; §3 prefers a static proxy
  when the saving is concentrated.
