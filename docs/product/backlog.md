# Product / Engineering Backlog

Deferred items with their rationale, so the "why" isn't lost by the time they're built.

## Event-relative intraday scheduling

**What:** Fire the intraday refresh at (soonest upcoming solar event − ~4h) instead of
a fixed 14:00 UTC cron.

**Why (concrete justification, June 2026):** Prod instrumentation showed Anthropic
batch latency is load-dependent — nightly (01:00 UTC) batches reach terminal in
2–5 min, afternoon (14:00 UTC) batches took 98–173 min with zero request failures.
The safety timeout was lengthened to a configurable 4h to absorb this, but that only
makes the fixed 14:00 fire *complete*; it doesn't make it *timely*. A 14:00 fire +
3h batch latency completes ~17:00–18:00 UTC: fine in summer (sunset 21:00+), useless
in winter (sunset ~15:30 UTC) — the run would finish after the event it exists to
inform. Event-relative firing keeps the refresh ahead of the event in every season
regardless of batch latency.

**Prerequisites:** `DynamicSchedulerService` currently drives cron expressions; this
needs a per-day computed fire time (soonest event across enabled locations).

## Explicit "declined to crown" pick record (product decision needed first)

**What:** Optionally persist an explicit no-pick record per pipeline run when the
best-bet advisor returns zero picks, so `pipeline_run_pick` history distinguishes
"no pick — flat week" from "no row — run never reached the briefing".

**Context:** See `docs/engineering/pipeline-run-pick-empty-investigation.md` — the
empty table for runs #21+ was confirmed honest (advisor declined; display and table
agree). This item is about observability of that decline, not a bug. Decide whether
the distinction is worth a row/schema addition before building anything.
