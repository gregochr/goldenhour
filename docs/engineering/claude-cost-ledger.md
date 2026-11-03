# Claude spend: decision ledger

**Start here** when the monthly Anthropic bill looks high. This file records what was measured, what was
changed, what was tried and rejected, and why. Each entry links the plan with the details. The queries are
in `docs/engineering/cost-queries/`. Every one is read-only and must run against **production**:

```
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour < docs/engineering/cost-queries/<file>.sql
```

Never judge the bill from local logs or the local database.

## Baseline (2026-11-01, 30 days, production `api_call_log`)

The total was **£89.29 ($119.64)**. The owner called this "a large monthly cost".

| Item | $/30 days | Note |
|---|---|---|
| Batch sky ratings, Sonnet 5.5 | ~81 | 29.5k requests at ~$0.0028 each: ~43% output, ~29% uncached input, ~27% cache reads |
| Aurora | ~7.6 | One call per run, 27k–40k output tokens |
| Batch sky ratings, Haiku 4.5 | ~5 | |
| Briefing gloss | ~4.3 | |
| Best-bet | ~2.8 | |
| Weekly Sky-Rating Eval | ~6.5 | Mostly the retired Opus 4.6 and Sonnet 4.6 |
| Ask and the rest | small | |

The Sonnet 5.5 batch cost splits by cycle as follows:

| Cycle | $/30 days |
|---|---|
| Nightly T+1 | 21 |
| Nightly T+0 | 19 |
| Intraday T+1 | 17 |
| Intraday T+0 | 8 |
| Evening T+1 | 6 |

Query: `rerating-waste.sql`.

To get today's figures, run `measured-costs.sql`, or open Operations → Run Config, which shows measured costs.

## Done (2026-11-01 → 02)

| Change | Effect | Where |
|---|---|---|
| Basic tier removed | Removes a whole rating path | #1596, `basic-tier-removal-plan.md` |
| Aurora detail trim | Fewer aurora output tokens | #1600, `aurora-output-trim-plan.md` |
| Intraday skips the T+1 sunrise when an evening refresh is due (phase 1) | Fewer intraday requests | #1601, `intraday-settled-refresh-plan.md` |
| Run Config shows measured costs | Visibility | #1602 |
| Sky Eval: choose the weekly models, filter the chart | Eval drops from ~$6.50 to ~$1/month once Opus 4.6 and Sonnet 4.6 are unticked | #1607, `sky-eval-model-controls-plan.md` |
| Prompt regression and Sky Eval share one fixture registry | Every new regression case is evaluated weekly on every model | #1605, #1606 |
| **Batch Far-Term → Haiku 4.5** (a `model_selection` row, set by the owner) | ~$5.50/month, against ~$16 on Sonnet 5.5. The owner "banks the $10" | #1609, `reference/pipeline.md` |
| Near-Term stays on Sonnet 5.5 | — | see "Rejected" |

## Rejected or shelved, and why

Read these before re-proposing any of them.

- **Phase 2: the nightly run reuses the evening T+0 sunrise rating when inputs have not changed. SHELVED.**
  Measured on identical inputs (`noise-floor.sql`), the model moves 1★ in about 8.5% of repeats (Sonnet).
  Real re-ratings move about 45–50% of the time, so most moves are real signal. The inputs also almost
  never hold still (`input-delta.sql`): for T0 sunrise pairs no more than 8 h apart, 75% moved some cloud
  field by more than 40 points, and only 23 of 1,838 stayed within 10 points. A gate would skip almost
  nothing, and blind reuse would hide real changes.
- **Phase 2b: the evening refresh skips slots already rated 2★ or lower. MEASURING (2026-11-03).**
  The owner's question: a sunrise that already looks poor will not turn into a "go" by 21:00, so why
  re-rate it? Phase 2's gate asked whether the *inputs* had changed (they nearly always have); this
  gate asks only whether the *previous rating* was poor, which is the thing that drives the bedtime
  decision. It is worth building only if a rating of 2★ or lower almost never comes back at 4★ or higher
  from the evening run, and the most it can save is part of Evening T+1's ~$6 per 30 days.
  Query: `poor-stays-poor.sql` (section 1: previous-stars to new-stars transitions for the evening and
  for all T+1 sunrise re-ratings; section 2: requests and dollars the gate would skip, and how many
  2★-or-lower ratings it would have hidden that came back at 3★ or at 4★ or higher). **Result: not yet
  run.** Record the figures here when it has been, with the date and the number of evening nights.
  Decision rule proposed: build the gate only if `hidden_jumps_to_ge_4` is 0, or under 1% of
  `skippable_requests`, over at least 14 evening nights.
- **Cutting cadence further: rejected.** Intraday T+1 sunset re-ratings stay the same only 51.6% of the
  time, and 5% move 2★ or more. That is real freshness, so cutting it is a product choice the owner
  declined.
- **Haiku 5.5 for Near-Term or Far-Term: rejected 2026-11-02.** It costs ~$0.00013 per request but scored
  90% on the Sky Eval (3 runs, `haiku55-vs-sonnet55.sql`). All 24 misses were Steel Rigg, rated 2 against
  the owner's "solid 4": it vetoes on the upwind bank and scores the overhead cloud instead of the
  directional data. Sonnet 5.5 scored 100% and gave the same rating on 100% of repeats.
- **Haiku 4.5 for Near-Term: not started.** On 3 runs it scored 97.8%, but gave the same rating on only
  89.3% of repeats (historically 79.5%, against Sonnet's 91.5%). It was taken off Far-Term on 2026-10-20
  because neighbouring cells drew different stars (`same-cell-rating-spread-plan.md`). It would save
  ~$53/month (~$80 → ~$27). Revisit only if Far-Term on Haiku 4.5 shows no rogue stars between neighbours.
- **Prompt trims (phase 3), mostly not worth it.** The system prompt (~4.6k tokens) is cached, so cutting
  it saves little and risks the calibration. The trend `event` slots in the user message look like
  duplicates but are not: the labels and the mist and clearing rules read them. Only three safe user-message
  trims remain, worth ~25 tokens a request or **~$1/month**. The owner chose to build them anyway
  (`sky-user-message-trim-plan.md`). Dropping the `headline` output field would save ~6% more, but Ask
  would have to switch to reading `summary`. Not done.

## Watch items

- Re-run `measured-costs.sql` about a week after each release that touches cost.
- Far-Term on Haiku 4.5: watch days 3–4 for rogue stars between neighbouring locations, the reason it was
  moved off in October.
- In Operations → Sky Eval → Weekly run, Opus 4.6 and Sonnet 4.6 should be unticked.
- Optional: an index on `api_call_log.called_at` for the 30-day cost query.

## Remaining levers, ranked

1. **The Near-Term model.** This is the only large lever, ~$53/month. It needs the Sky Eval and the
   prompt regression suite to pass, plus a check for rating spread between neighbouring cells.
2. **Cadence.** This is a freshness choice, not an engineering one.
3. **Everything else** is a few dollars at most.

`sky_rating_eval_result.miss_direction` stores `'IN_BAND'` for passes, so count misses as
`miss_direction <> 'IN_BAND'`.
