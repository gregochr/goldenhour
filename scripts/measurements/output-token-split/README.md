# Output-token split — answer JSON versus thinking

The harness behind the figure in `docs/engineering/basic-tier-removal-plan.md`: **92% of a sky call's
billed output tokens are the JSON answer, and thinking is about 19 tokens a call** (one Batch
Near-Term job run, 2026-11-01). It is the measurement to repeat before deciding whether an output field
(for example `headline`, see `claude-cost-ledger.md`) is worth dropping.

## What it does

1. `export-answers.sql` runs read-only on **production** Postgres. For one batch job run it pairs each
   billed sky call in `api_call_log` (`output_tokens`) with the answer it produced, rebuilt from the
   stored `forecast_evaluation` columns in the output schema's property order, absent fields dropped.
   One JSON line per call.
2. `measure_split.py` counts each rebuilt answer's tokens with the free Messages `count_tokens`
   endpoint (a fixed wrapper overhead is measured once and subtracted) and reports
   billed, answer and thinking-by-difference totals, plus per-call thinking median / p90 / max.

## Running it

```bash
psql "$PROD_DB_URL" -At -v job_run_id=2353 -f export-answers.sql > answers.jsonl
ANTHROPIC_API_KEY=... python3 -I measure_split.py answers.jsonl
```

Change `job_run_id` to measure another batch. The SQL's trailing comment holds a sanity query for how
many calls the join could not pair (bluebell / woodland lanes, failures, ids without an `-r` suffix).

## Limits

- Thinking is a **difference**, not a count, so any tokenisation drift between `count_tokens` and the
  billed answer shows up as "thinking". The model is hard-coded in `measure_split.py`; update it if the
  run type's model changes.
- The answer is rebuilt from stored columns, so a field the model emitted but the pipeline did not store
  is invisible here.
- Production is a separate host: run the SQL there, never against the local DB.
