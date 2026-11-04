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
- **Phase 2b: the evening refresh skips slots already rated 2★ or lower. REJECTED (2026-11-03).**
  The owner's question: a sunrise that already looks poor will not turn into a "go" by 21:00, so why
  re-rate it? Phase 2's gate asked whether the *inputs* had changed (they nearly always have); this
  gate asks only whether the *previous rating* was poor, which is the thing that drives the bedtime
  decision. It is worth building only if a rating of 2★ or lower almost never comes back at 4★ or higher
  from the evening run, and the most it can save is part of Evening T+1's ~$6 per 30 days.
  Query: `poor-stays-poor.sql` (section 1: previous-stars to new-stars transitions for the evening and
  for all T+1 sunrise re-ratings; section 2: requests and dollars the gate would skip, and how many
  2★-or-lower ratings it would have hidden that came back at 3★ or at 4★ or higher). Decision rule: build
  the gate only if `hidden_jumps_to_ge_4` is under 1% of `skippable_requests` over at least 14 evening
  nights.
  **Result (run 2026-11-03 on production, 14 evening nights, 2,145 evening requests, $6.18):** the gate
  would skip 880 requests (41%), **$5.32 per 30 nights**. Of those 880, **58 (6.6%) came back at 4★ or
  higher** and 246 (28%) at 3★, so it fails the 1% rule six times over. A 2★-to-4★ move is two stars,
  well above the 1★ noise floor, so these are real changes; 37 of the 58 slots were still 4★ or higher
  at their last rating before sunrise (normally the overnight's). By starting rating, evening
  re-ratings:

  | Previous | Pairs | Now ≤2★ | Now 3★ | Now ≥4★ | Last rating ≥4★ |
  |---|---|---|---|---|---|
  | 1★ | 61 | 52 | 9 | 0 | 0 |
  | 2★ | 819 | 524 | 237 | 58 (7.1%) | 37 |
  | 3★ | 891 | 224 | 429 | 238 (26.7%) | 179 |
  | 4★ | 374 | 18 | 143 | 213 (57.0%) | 194 |

  A 1★-only gate would hide nothing on this sample (0 of 61 reached 4★), but it would save 61 of
  2,145 requests, about $0.18 per 30 nights: not worth a code path. Across all T+1 sunrise re-ratings
  (11,437 pairs, every cycle), 5.8% of 2★ ratings came back at 4★ or higher and 3.4% of 1★ ratings
  did. Note for later: evening re-ratings rise more often than other T+1 re-ratings (3★ to 4★ or
  higher: 26.7% against 17.1%), and the overnight after it pulls some of them back (238 evening 4★+
  from a 3★ start, 179 still 4★+ at the last rating).
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

## Why spend doubled, October to early November (measured 2026-11-03)

The Operations cost chart showed £97.20 for the 30 days to 2 November against £49.27 for the 30 days
before. Query: `overnight-and-ask-daily.sql` (production, 40 days, UTC days).

- **Far-Term on Sonnet 5.5, confirmed, and already fixed.** Far-Term ran on Haiku until about 16
  October, then on Sonnet 5.5 (17 October–1 November; it was taken off Haiku 4.5 on 20 October for
  neighbouring-cell spread). Far-Term volume depends on the weather, because T+2 needs SETTLED or
  TRANSITIONAL cells and T+3 SETTLED: it was 6 requests a night from 19 to 26 October and 348–724 from
  27 October to 1 November, the settled spell. At Sonnet's ~$0.0029 per far request that was
  $1.00–2.05 a night. Since 3 November it is back on Haiku 4.5 at ~$0.00085 per request (446 requests,
  $0.38).
- **Near-Term on Sonnet 5.5 since about 8 October** roughly tripled the near-term share, from
  ~$0.65 a night on Haiku to $1.6–2.0. This is the known ~$53/month lever (see "Remaining levers").
- **Ask ready answers grew from ~$0.40 a day to ~$1.00.**
  - More precomputes: 2 a day until 11 October, 3–4 since. By hour (14 days): the overnight (02–03 UTC,
    14 runs), the afternoon (12–13 UTC, 14), the evening (20–21 UTC, 12, ~$0.31 each) and a cheap one
    at 15 UTC (8 runs, ~$0.03, not yet identified). **The evening refresh's Ask precompute costs about
    $9 a month, more than the evening's sky ratings (~$6).**
  - Bigger prompts: average input went from ~2,500 tokens to ~3,300 (12 October) and ~4,200
    (21 October).
  - Prompt caching started on 30 October, but each call writes more to the cache than it reads: on
    average ~1,380 tokens written against ~860 read. Writes cost 1.25× the input price and reads 0.1×,
    so the cache saves little, and cost per day hardly fell ($0.68–1.02).
  - Next: decide whether the evening needs a full Ask precompute (or only the answers about tomorrow),
    and find out why the cache prefix is written fresh on most calls. Not started.

## Watch items

- Re-run `measured-costs.sql` about a week after each release that touches cost.
- Far-Term on Haiku 4.5: watch days 3–4 for rogue stars between neighbouring locations, the reason it was
  moved off in October.
- In Operations → Sky Eval → Weekly run, Opus 4.6 and Sonnet 4.6 should be unticked.
- Optional: an index on `api_call_log.called_at` for the 30-day cost query.

## Remaining levers, ranked

1. **The Near-Term model.** This is the only large lever, ~$53/month. It needs the Sky Eval and the
   prompt regression suite to pass, plus a check for rating spread between neighbouring cells.
2. **Cadence.** This is a freshness choice, not an engineering one. The owner's next idea (2026-11-04):
   the overnight run does far-term only (about $40/month of overnight T+0 and T+1 on Sonnet 5.5), a
   short-term series re-rates the next couple of windows gated on stability, and perhaps weekend against
   weekday. The catch is today's sunrise, whose last look would become the 21:00 one. Measure first with
   `short-term-looks.sql`: how far each look moves the rating it replaces, by stability, against what
   readers saw, and the cost split once #1662 is live.
3. **Everything else** is a few dollars at most.

`sky_rating_eval_result.miss_direction` stores `'IN_BAND'` for passes, so count misses as
`miss_direction <> 'IN_BAND'`.
