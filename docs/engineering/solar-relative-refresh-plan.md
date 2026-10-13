# Plan: run the two forecast cycles relative to the sun, with one safety net

**Status: DRAFT v2, 2026-10-05, not started.** v1 was reviewed the same day by two read-only
prosecutors (code fit; timing and numbers). v2 carries the owner's decisions (§2) and their
findings (§9). The safety net (Part B) needs a further review before any code.

## 1. Why

The two scheduled cycles fire at fixed UTC times: `near_term_batch_evaluation` at 01:00 and
`intraday_forecast_refresh` at 14:00. The events they serve move by about five hours over the
year (computed at 54.9N 1.9W for 2026, cross-checked against
`forecast_evaluation.solar_event_time`):

| | Event (UK local) | Lead after today's fixed run |
|---|---|---|
| Sunset, midsummer | ~21:49 | ~6.7 h after 15:00 BST |
| Sunset, early October | ~18:30 | ~3.5 h |
| Sunset, midwinter | ~15:40 | ~1 h 40 min |
| Sunrise, midsummer | ~04:28 | ~2.5 h after 02:00 BST |
| Sunrise, midwinter | ~08:32 (latest, 29 Dec) | ~7.5 h |

A long lead is a stale forecast; a short one lets a slow batch miss the event. Production,
last 30 days, 49 cycles with batches: the wait from first submit to the last batch ending is
normally under 20 minutes, but 5 cycles took 32, 45, 60, 60 and 181 minutes. The delay is on
Anthropic's side (`ended_at` is stamped by the 60-second poller). On 30 September results
landed about 40 minutes before sunset.

## 2. Owner decisions (2026-10-05)

1. Both cycles run at an offset from the sun, all year. No cap and no seasonal switch.
2. Morning cycle: **sunrise − 255 minutes**. Sized so the latest sunrise of the year still
   finishes by 05:00 UK time; the owner checks the app first thing and wants a completed run.
   Accepted consequence: the midsummer run fires about 00:13 local.
3. Afternoon cycle: **sunset − 240 minutes**. Accepted consequence: the midsummer afternoon
   refresh fires about 17:50 local instead of 15:00.
4. One safety net for a slow batch, shared by both cycles, with a **20-minute** timeout.
5. An admin screen that documents all of this (§5).

## 3. Part A — sun-relative scheduling

**A1 — Schedule type.** `ScheduleType` gains `SOLAR_OFFSET`. `scheduler_job_config` gains
nullable columns: the event (`SUNRISE`/`SUNSET`) and the offset in minutes before it. The
fire time is computed per day from `SolarCalculator` at a **configured reference point**
(`photocast.scheduler.solar-reference.lat/lon`; no region has a base location set in
production, so it cannot be derived), in the UK civil calendar via `ForecastHorizon`.

**A2 — What the trigger must define** (v1 left these open):
- which UK civil date's event a fire belongs to. The midsummer morning run sits 13 minutes
  after local midnight, so the trigger computes "the sunrise of local date D, minus 255
  minutes" and must never land on D−1; if arithmetic would, it clamps to 00:05 local on D;
- a fire time already past when the app starts: skip to the next day, never fire late;
- DST changes: fire times are computed in local civil time, so no fixed-UTC assumption;
- the offset is the cycle's TRIGGER time. Collection and triage before submit take about
  five and a half minutes (max 6.3) and the cache primer up to three more.

**A3 — Reverting must work.** Today `updateSchedule` cannot change a job's `scheduleType`,
`SchedulerController` has no endpoint for it, `SchedulerView.jsx` treats non-CRON as fixed
delay, and an older image reading a `SOLAR_OFFSET` row would fail to load the job table and
schedule nothing. So: add an endpoint and UI control to switch a job between CRON and
SOLAR_OFFSET (keeping its cron expression stored while it is solar), and ship the enum value
and tolerant loading one release BEFORE any row uses it.

**A4 — Code that assumes 01:00 and 14:00.**
- `IntradayEligibilityPolicy` is a stateless singleton whose "guaranteed later look" rests on
  "the 01:00 run, when nobody is awake". With a 04:17 midwinter run that premise changes.
  This is a policy decision for the owner, not a restatement (§8 Q2).
- `FreshnessProperties.t1Hours = 8` is justified by the 01:00/14:00 gap. New gaps: midsummer
  about 17.3 h (morning to afternoon) and 6.7 h; midwinter about 7.4 h and 16.6 h. Freshness
  windows must be re-derived in the same phase as the first schedule move, not afterwards.
- Fixed-time neighbours: the aurora batch (03:30 UTC) and the 03:45/03:50 cleanups. The
  morning cycle crosses them in the shoulder weeks (when sunrise − 255 passes 03:30 UTC).
  Confirm they can overlap, or move them.

**A5 — Year at a glance** (trigger times, UK local):

| | Morning cycle | Afternoon cycle |
|---|---|---|
| Midsummer | ~00:13 | ~17:49 |
| Early October | ~03:00 | ~14:30 |
| Midwinter | ~04:17 | ~11:40 |

## 4. Part B — the safety net (needs its own review)

**Requirement.** If a cycle's batches have not all finished 20 minutes after first submit,
the imminent event's slots (this morning's sunrise, or tonight's sunset) are scored directly,
and the briefing is rebuilt straight afterwards so the Plan tab shows them. One mechanism,
one setting (`photocast.pipeline.direct-fallback-after`, default PT20M), both cycles. The
retry step (`RETRY_FAILED`, which ran in 11 of 30 nightly cycles and once took 98 minutes)
must not delay that rebuild.

**Budget for a 05:00 finish on the worst day:** 04:17 trigger + ~6 submit + 20 timeout +
~5–10 direct scoring + ~2 briefing ≈ 04:50–04:55.

**v1's design does not work as written** (§9). The constraints any design must meet:
- *Inputs.* At wait time the cycle's tasks and their `AtmosphericData` are gone. Use the
  stored prompt: since V164 every batch SKY request's exact user message is kept in
  `forecast_evaluation_prompt` against its PENDING row. The fallback re-sends that message
  and scores the SAME row. No weather re-fetch, no second row, no re-triage.
- *All three stores must agree.* Today a late batch answer is rejected only by
  `cached_evaluation`. `forecast_score` would take it (a direct write carries no pipeline run
  id) and `forecast_evaluation` would too (the PENDING row is scored by primary key). The
  fallback must write with the cycle's pipeline run id, and a batch result for a row already
  answered directly must be ignored in all three.
- *Concurrency and bookkeeping.* Direct calls must run under the existing `claude` bulkhead
  and one job run, not one per call.
- *Failure evidence.* `LocationFailureService` reads success from batch `api_call_log` rows
  and from `forecast_score` rows carrying the cycle's run id. A slot answered directly whose
  batch request later errors must count as answered.
- *Bluebell.* In season, `recombineBluebell` would treat the same cycle's bluebell result as
  stale against a newer direct sky answer. The direct answer must carry the cycle's
  submission instant, or the blend must be told.
- *Durable state.* "Measured from first submit" and "fallback already ran" must survive a
  restart mid-wait.

**Cost.** Imminent-event calls average 143 for the afternoon cycle and 184 for the morning.
Direct costs about twice batch, and the batch still bills: roughly $0.50 extra per firing on
Haiku, about $2.60 a month at the measured rate (5 firings in 60 cycles).

## 5. The admin screen

One read-only panel on the Operations tab's Scheduler screen, so nobody has to remember the
rules. For each forecast cycle it shows:
- the rule in words ("4 h 15 min before sunrise at the reference point");
- today's and tomorrow's event time, trigger time, and expected finish (trigger + typical
  duration), plus the worst-case finish with the safety net;
- the year at a glance (the A5 table, computed, with the earliest and latest trigger of the
  year and the dates they fall on);
- the safety net: its timeout, what it scores, and when it last fired;
- the reference point, and a note of which other jobs are fixed-time.
The editable controls (offset, switch back to CRON) live with the existing job row. UI review
cadence applies.

## 6. Phases

- **P1 — Tolerant schedule type.** Enum value, columns (one migration), loading that cannot
  break on an unknown or solar row, the switch endpoint. No job uses it. Ship and release.
- **P2 — The trigger and the admin panel** (A1, A2, §5), still with no job using it; the
  panel shows what the times WOULD be.
- **P3 — Move the afternoon cycle** to sunset − 240, with the freshness and eligibility
  changes it needs (A4).
- **P4 — Move the morning cycle** to sunrise − 255.
- **P5..P7 — The safety net** (Part B), after its own design review: stored-prompt fallback
  and the three-store rule; failure evidence and bluebell; briefing rebuild and retry-step
  deadline.

Until P5–P7 land, a slow batch still means a late morning: 3 of 30 nightly cycles in the
last month would have finished after 05:00 from a 04:17 start.

## 7. Stop-points

- Migrations are proven only in CI.
- P3 and P4 change when production spends money and what users see first; each deploys on
  the owner's say, and each is reversible through the P1 switch.
- The Scheduler screen changes: UI review cadence.
- Part B gets a second adversarial review before code.

## 8. Open owner questions

- **Q1** — Order: scheduling first (P1–P4), safety net after (recommended), or the reverse?
- **Q2** — Under the new times, should the afternoon run still skip slots that the next
  morning run is "guaranteed" to look at again? The old reasoning was that the 01:00 run
  happens while nobody is awake.
- **Q3** — The reference point's coordinates.
- **Q4** — May the aurora batch and the 03:45/03:50 cleanups overlap the morning cycle?

## 9. Review record, 2026-10-05

Code-fit lens: v1's claim that a late batch cannot overwrite a direct answer held for one
store of three; the wait step has no inputs to score from; the 04:25 start arithmetic failed
once pre-submit time and the briefing were counted; freshness and failure counting assume
the old times and paths; reverting a schedule type had no route. Timing lens: 20 minutes
fires on the same cycles as 30; 180 minutes before sunset leaves no decision time for the
furthest tenth of locations (drive times p50 81, p90 117, max 161 minutes, from two users);
"whole-hour multiples" described only half the slow batches. Not examined: what
`GET /api/forecast` serves when the stores disagree, the frontend beyond three lines of
`SchedulerView`, direct-call latency on Sonnet, and batch data older than 30 days.
