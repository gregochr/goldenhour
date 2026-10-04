### Added — Claude Sonnet 5.5 is a selectable evaluation model

`claude-sonnet-5-5` can now be chosen as `SONNET_55` on the Models screen (every run type, including
the scheduled-batch tabs), in the Prompt Test and Sky-Rating Eval harnesses, and in the advisor replay.
It is not the default or active model for any run type, and no data changed (no migration: every
column that stores a model name is VARCHAR(10), and `SONNET_55` fits). It is deliberately **not** added
to the fixed lists that run on their own: `SkyRatingEvalService.SCHEDULED_MODELS` (the weekly eval), the
Model Test screen's three-way Haiku/Sonnet/Opus run, and the Briefing Model Test's fixed five variants
are unchanged, so no scheduled or comparison spend moved.

The model rejects `thinking: disabled`, so it is sent no `thinking` parameter (adaptive thinking is its
default) and instead `output_config.effort = low` alongside the existing JSON-schema format. The sky
prompt gets a 4096 ceiling; aurora and the glosses get their usual answer budget plus a 4096 thinking
allowance (thinking tokens count against `max_tokens`); the best-bet advisor gets the thinking-sized
16000 ceiling and ignores the run type's extended-thinking switch for this model. Every other model's
request is unchanged and pinned by tests. Priced at $2.00 in, $10.00 out, $0.20 cache read, $2.50
5-minute and $4.00 1-hour cache write per million tokens.

A refusal (`stop_reason: refusal`) or a `max_tokens` truncation now fails cleanly, for every model, on
the aurora interpreter, the synchronous aurora and sky calls, the batch results (forecast and aurora)
and the sky-rating eval harness, instead of reaching a parser that could salvage a cut-off answer; the
best-bet advisor logs either as a failed `api_call_log` row and returns FAILED. Refused and truncated
batch responses are now costed (their tokens reach the batch totals and the failed row), and the batch
refusal error type is the same `refusal` the synchronous path writes. A refusal counts as a failed
result for `LocationFailureService`, like any other failure.

On 2026-10-04 measurements (10 runs, 3 prompts) it cost about the same as Sonnet 4.6 because its
tokeniser counts roughly 45% more input, and it failed one regression case that Sonnet 4.6 passes.
Model names now come from one front-end table (`utils/modelLabels.js`), so the Models screen labels the
existing Sonnet "Sonnet 4.6" and the Plan popup footer no longer prints "Sonnet_55".

A failed batch now completes its job run with the cost it accrued (a refused or truncated aurora
response, an aurora handler failure and a forecast stream that died after billed responses were all
recorded as free before). The sky-rating eval's pass rate now means passes / evaluations attempted: a
refused, truncated, errored, unreadable or never-answered evaluation counts as a non-pass (before this,
errored fixtures were silently left out of the denominator). The run's error message carries the failed
count and per-type reasons (no new column), the Sky Eval table marks such a run with an asterisk, and a
run in which every evaluation failed is FAILED rather than COMPLETED.
