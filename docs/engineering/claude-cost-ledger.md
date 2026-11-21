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
| Best-bet | ~2.8 | Retired 2026-11-21 (see "Best bet advisor retired") |
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
  would have to switch to reading `summary`. Not done. Re-measure the answer-versus-thinking split with
  `scripts/measurements/output-token-split/` before revisiting.
- **The overnight run does far-term only: REJECTED (2026-11-04).** Measured with `short-term-looks.sql`
  (production, 45 days). The overnight's T+1 look replaces the far-term rating, and it moves it a lot:
  2★ or more for 18.5% of sunrises (3,415 pairs) and 16.8% of sunsets (2,865), and the moves are mostly
  down. Sunrises that crossed from 4★+ to 2★ or less: 395. The other way: 55. For sunsets it was 235
  against 68. Without that look, tomorrow would show inflated stars all day until the 21:00 refresh. Why
  far-term runs high is answered in the next entry: it is the lead time on sunrises, not the model.
- **Near-Term rates today only, Far-Term (Haiku 4.5) also takes tomorrow: REJECTED (2026-11-04).** It
  would save ~$14/month (the overnight T+1 look is ~$21 per 30 days on Sonnet 5.5). Section 5 of
  `short-term-looks.sql` split the overnight's T+1 look by the model of the 2-days-ahead rating it
  replaced (production, 45 days):

  | Replaced rating | Event | Pairs | Mean change | Moved 2★+ | 4★+ to ≤2★ |
  |---|---|---|---|---|---|
  | Sonnet 5.5 (Oct 24 to Nov 3) | Sunrise | 840 | −0.73 | 24.0% | 165 |
  | Sonnet 5.5 (Oct 25 to Nov 3) | Sunset | 686 | −0.01 | 7.0% | 14 |
  | Haiku 4.5, replaced by Sonnet 5.5 (Oct 9 to Nov 5) | Sunrise | 847 | +0.36 | 15.2% | 23 |
  | Haiku 4.5, replaced by Sonnet 5.5 (Oct 9 to Nov 5) | Sunset | 585 | +0.43 | 16.9% | 15 |
  | Haiku 4.5, replaced by Haiku 4.5 (Sep 23 to Oct 4) | Sunrise | 1,185 | −0.56 | 22.8% | 194 |

  Far-term's high stars are a **lead-time effect on sunrises**: Sonnet 5.5 over-rates a sunrise 2 days out
  as much as Haiku 4.5 did, while its sunsets barely move. Haiku 4.5 does not inflate; against Sonnet a
  day later it rates about 0.4★ low, and it disagrees more (16.9% of sunsets moved 2★+, against 7.0% for
  Sonnet's own). The model rows cover different weeks and the weekly rows swing with the weather, so the
  gaps are indicative. Rejected under the owner's rule for the Near-Term model (see "Remaining levers"):
  Haiku 4.5 is not as good as Sonnet 5.5 (Sky Eval 97.8%, 89% same rating on repeats). If a Haiku passes
  that bar, this is the cheapest way to use it.
- **A stability gate on the overnight's T+0 sunrise: SHELVED (2026-11-04), a false economy.** Against the
  21:00 rating 6.6 h earlier (2,010 pairs):

  | Stability | Pairs | Same stars | Moved 2★+ | Crossed 3★ |
  |---|---|---|---|---|
  | SETTLED | 837 | 62.7% | 1.6% | 7 (0.8%) |
  | TRANSITIONAL | 236 | 60.2% | 2.1% | 4 (1.7%) |
  | UNSETTLED | 937 | 59.4% | 4.3% | 37 (3.9%) |

  Skipping SETTLED and TRANSITIONAL would hide little, but it saves only about **$5/month** (estimated:
  half of the ~$19 overnight T+0 is sunrise, and these two make up about half of it). In summer the
  overnight already marks recent evening slots cached (E-D8), so it saves less still. The build and
  review cost, and another skip rule beside the CACHED gate and retraction, outweigh that. Of the 62
  sunrises that crossed 3★ at the overnight look, 56 were downgrades, the ones a reader checking on
  waking wants. Revisit only if it can ride along with another change to the overnight run. The same
  gate for the T+0 sunset cannot be measured until the evening has rated sunsets for a while (#1662).
  The ground-truth check (outcome ratings) had one sample, so it says nothing yet.

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

## Whole-day render horizon (2026-11-10, `whole-day-horizon-plan.md` P1)

The Plan tab now renders whole days (today to T+3: up to 8 events) instead of the leading 6 events.
No new model calls and no change to which slots are scored; two inputs carry up to two more events:

- **The best-bet advisor's rollup** (one call per briefing build; the advisor was retired 2026-11-21, so this
  input no longer exists). Fixture measurement, not production:
  the same six-region fixture serialises to 12,706 characters at 6 events and 16,863 at 8
  (`BriefingBestBetAdvisorTest.rollupJsonSizeAtSixVersusEightEvents`, deleted with the advisor). The two extra events are mostly
  unscored T+3 nodes, so real growth is smaller than the fixture's +33%; estimated roughly +15-30% advisor
  input, tens of cents a month.
- **Ask's `list_windows`** (enlarges the Ready precomputes). Fixture measurement: the busy four-region
  day is 2,985 characters at 6 windows and 3,973 at 8 (`AskToolsTest.listWindows_sixVersusEightWindowsSize`),
  under the 4,500 half-cap and the 9,000 cap. `look_up_place` worst cases are row-capped, so they do not
  grow with the window count. This adds to the open Ready-precompute item above (the evening refresh's
  Ask precompute); re-measure with `measured-costs.sql` after the release.

## Built: triage headroom (2026-11-15, run on production; P1 built, re-measure pending)

The owner's question (14 Nov: £2.30 for a day where one window of six was worth a look): why pay Claude to
say "poor"? Stood-down and unscored windows already cost nothing. The free weather triage
(`WeatherTriageEvaluator`) stands a slot down before any model call, at solar-horizon low cloud over 80%,
rain over 2 mm or visibility under 5 km, and Gate 4 skips T+2/T+3 in unsettled weather. The money is the
places that pass triage for today and tomorrow, at about $0.0028 each on Sonnet 5.5, rated by all three
cycles. The candidate lever is a stricter triage line, which cuts requests in every cycle without changing
the model or cadence. Query: `triage-headroom.sql` (section 1: stars by sun-side low cloud band;
section 2: requests, dollars and good ratings hidden for low cloud over 50/60/70%, rain over 0.5/1 mm,
visibility 5–10 km; section 3: the saving by cycle). Decision rule: adopt a candidate only if
`final_ge_4_hidden` is under 1% of `skippable_requests` over at least 30 days spanning settled and
unsettled weather.

**Result (2026-11-15, production, 45 days, ~43.7k rated requests):** today and tomorrow (T+0/1, Sonnet 5.5),
4★+ by sun-side low cloud band:

| Band | Sunrise requests | Sunrise 4★+ | Sunset requests | Sunset 4★+ | $ (both) |
|---|---|---|---|---|---|
| 41–50% | 1,806 | 41 (2.3%) | 2,272 | 47 (2.1%) | 11.04 |
| 51–60% | 1,967 | 10 (0.5%) | 2,221 | 19 (0.9%) | 11.43 |
| 61–70% | 2,290 | 2 (0.1%) | 2,036 | 7 (0.3%) | 11.47 |
| 71–80% | 1,901 | 1 (0.1%) | 1,984 | 7 (0.4%) | 10.07 |

The days further out (T+2/3, Haiku 4.5) keep 2–5% 4★+ up to 80% (82 of 2,510 over 60%). They cost $3.21 of
that rule's $23.91, so leave them at 80%. Candidates against the decision rule (`pct_final_ge_4_hidden`):
low cloud over 50% 0.37%, over 60% 0.28%, over 70% 0.29% (all pass); rain over 0.5 mm 0.73% (passes, ~$3/month);
rain over 1 mm 1.12% and visibility 5–10 km 1.78% (fail). The cost is "Maybe" ratings: about 18% of the
skipped near-term requests over 60% were 3★, and they would show as stood down instead.
A **T+0/1-only rule at over 60%** skips ~8.2k requests per 45 days, **~$14/30 days**, hiding 17 4★+ ratings
(0.2%). At over 50% it is **~$22/30 days**, hiding 46 (0.37%). **Owner decision (2026-11-15): over 60%,
T+0/1 only**, with thin strips exempt (`triage-cost-cut-plan.md` P1). `triage-60-thin-strip.sql` (45 days):
the blanket shape is 5,347 requests, $13.55, 7 at 4★+ (0.13%), 77 at 3★; the exempt thin strips are 2,831
requests, $7.89, 9 at 4★+, 1,430 at 3★. **Net saving ~$9/30 days**, hiding 8 good and ~87 Maybe ratings.

**Built (P1):** `WeatherTriageEvaluator.evaluate(data, daysAhead)` stands T+0/1 down over 60% sun-side low cloud
unless the horizon is a thin strip (`DirectionalCloudData.isThinStrip`, the one shared definition), keeps 80% at
T+2 and beyond, and a retried slot that triages is no longer submitted. **Re-measure** with `triage-headroom.sql`
after 14 days: T+0/1 requests in the 61–80% band should be about the thin-strip count only.

## Cost per call and shared cells (2026-11-15, production, 30 days)

Query: `per-call-and-shared-cells.sql`.

- **Thinking is not a hidden cost.** Sonnet 5.5 sky calls average 213–233 output tokens, p50 ~222, p90 ~255,
  max ~830, in every cycle. That is the answer itself, so a cap on output or thinking has nothing to cut.
  The cached system prompt is now ~7.8k tokens a call (cache reads), against the ~4.6k this ledger recorded
  earlier; trimming it stays rejected for calibration risk (see "Prompt trims").
- **Places sharing a weather cell.** 266 enabled places sit in 212 Open-Meteo cells (54 extra). Over 30 days of
  T+0/1 ratings, 3,451 same-cell same-run groups held 8,509 rated places: one call per cell would have saved
  **5,058 of ~28.2k Sonnet calls (18%), up to ~$14/30 days**. 2,354 groups (68%) had identical stored weather,
  2,560 (74%) gave every place the same stars, and only 16 (0.5%) spread 2★ or more. The prompt still differs
  by place (the location name, orientation, coastal and woodland prompt builders), so only same-lane,
  same-orientation, same-weather places could share, and the shared `summary` would name one place.
  A realistic saving is ~$8–14/30 days. Not designed; owner decision pending.
- **Replayed from the stored prompts: rejected (2026-11-16).** `shared-call-replay.sql` groups the last 30
  days of Sonnet T+0/1 sky requests (24,895, $67.94; 98.9% start with the expected `Location: <name>. `) by
  pipeline run and the exact prompt with the name removed. One request per group would have saved **311 calls
  (1.25%), $0.85/30 days**; with the event time cut to the minute, **1,068 (4.3%), $2.82**. Both are under
  P2a's 8% rule (`triage-cost-cut-plan.md`), so P2b is not built and the minute-truncated prompt is not worth a
  Sky Eval. The 18% above was an upper bound on matching *stored weather*; the prompts themselves rarely match.
  Inside the 311 strict groups (identical prompts but the name): 252 gave the same stars, 59 (19%) differed by
  1★, none by 2★ — the same ~16–19% wobble the sun-side gate measured, so sharing would mostly have hidden noise.

## Sun-side change gate and the afternoon's looks (2026-11-16, production, 30 days)

Query: `sun-side-change-gate.sql`. Pairs are a Sonnet T+0/1 rating and the previous Sonnet rating of the
same slot: 16,233 re-ratings, $44.36.

- **The weather a rating turns on rarely holds still.** In 55% of re-ratings (8,985) the sun-side cloud moved by
  more than 25 points, and in 2,974 more the rain or visibility moved. Even with sun-side cloud identical (238
  pairs) the stars moved 1★ 16.4% of the time and crossed the 4★ line 13.5%: the model's wobble on real inputs
  (other fields still move) is about twice the eval noise floor.
- **A gate (skip when sun-side cloud moved ≤ N points and rain and visibility held):** ≤5 skips 713 ($1.84),
  ≤10 skips 1,333 ($3.53), ≤15 skips 2,182 ($5.85). Moves of 2★+ stay at 0.3–0.6% (passes); 4★-line crossings
  run 7–9%, at the wobble level above, so they are mostly noise rather than hidden signal. Worth ~$3.50–6/30 days.
  Not decided.
  **Replayed as specified: REJECTED (2026-11-16).** The figures above compare consecutive ratings; the gate
  compares against the last rating actually made, which ages with each skip, and this window predates the 60%
  rule and the afternoon's T+1 deferral. `sun-side-gate-replay.sql` replays the gate (anchor chains, an 18 h
  anchor-age limit, today's pool from 5 November): at 10 points it skips 230 of 2,712 re-ratings, **$1.48/30
  days**, under the $2 bar set before running (`sun-side-gate-plan.md`, Gate 0). 15 points gives $2.10. The skipped
  looks would have hidden a 1★ move 27–30% of the time and a 4★-line crossing 16–17%, above the wobble on
  identical sun-side inputs, so the kept stars hide real change. Not built.
- **The afternoon is not just today's sunset.** Its Sonnet re-ratings: today's sunset 2,671 ($7.12), tomorrow's
  sunrise 1,979 ($5.84), tomorrow's sunset 1,018 ($2.91). When the afternoon changed **tomorrow's sunrise**, the
  next look kept the change only **47.7%** of the time: a coin flip, so that look mostly adds churn. For tomorrow's
  sunset it was 58.1%. Today's sunset has no later look; it is the freshest rating a reader acts on.
  **Already done, mostly before this window's end.** Per day (same query family, 2026-11-16), the afternoon's
  looks at tomorrow cost $0.34–1.11 a day until 1 November and $0.03–0.21 a day from 2 November, when #1601
  (tomorrow's sunrise left to a due evening refresh) and #1662 (tomorrow's sunset too, 4 November) landed. The
  30-day figures above are mostly the old regime; the coin-flip result confirms those two changes were right.
  What remains is ~$2.85/30 days for both events, mostly places with no rating yet for tomorrow (a first rating,
  which stays). Nothing to build. Note: the afternoon rated nothing on 19 October and 9–11 November.

## Watch items

- Re-run `measured-costs.sql` about a week after each release that touches cost.
- Far-Term on Haiku 4.5: watch days 3–4 for rogue stars between neighbouring locations, the reason it was
  moved off in October.
- In Operations → Sky Eval → Weekly run, Opus 4.6 and Sonnet 4.6 should be unticked.
- Optional: an index on `api_call_log.called_at` for the 30-day cost query.

## Remaining levers, ranked

1. **The Near-Term model.** This is the only large lever, ~$53/month. **On hold by owner decision
   (2026-11-04): it stays on Sonnet 5.5 until a Haiku model is as good as Sonnet 5.5.** "As good" means
   matching it on the weekly Sky Eval (100%, same rating on repeats) and the prompt regression suite, with
   no rating spread between neighbouring cells. Re-check when a new Haiku model ships.
2. **Cadence.** This is a freshness choice, not an engineering one. Measured on 2026-11-04
   (`short-term-looks.sql`): the overnight's looks earn their cost, so "overnight does far-term only" was
   rejected, a stability gate on its T+0 sunrise shelved, and moving tomorrow to Far-Term rejected (see
   "Rejected or shelved"). Weekend against
   weekday was not pursued: the only money is near-term, and cutting it degrades weekday shooters.
3. **Everything else** is a few dollars at most.

`sky_rating_eval_result.miss_direction` stores `'IN_BAND'` for passes, so count misses as
`miss_direction <> 'IN_BAND'`.

## Ask favourites (find-and-favourites F4b, 2026-11-18)

Two sentences were added to `AskPromptBuilder.WITH_USER` (typed Ask only; `RULES` and `USER_LESS` are unchanged, so
Ready is unaffected): one typed-prompt cache rewrite when it ships. A Pro asker whose favourites touch a question's
scope no longer reads the shared cache key (their order may differ), so the first such ask is an engine call; the
answer is stored under their own key (also when it is non-personal), so a repeat of the same question hits the cache.
The cost is at most one extra typed call per favourite-touched question per asker per build; not measured.

## Best bet advisor retired (2026-11-21, `best-bet-advisor-retirement-plan.md`)

The owner decided to stop the Claude Best bet advisor call (`BriefingBestBetAdvisor`, one call per briefing build,
model per the `BRIEFING_BEST_BET` `model_selection` row) and delete its code and admin surfaces. The call only wrote
`DailyBriefingResponse.bestBets`, which no reader screen renders: the Best bet and Also good a reader sees are chosen
by `PlanWindowProjector` from the ratings and worded by the region gloss. **Saving: about $2.8 per 30 days. This is
the 2026-11-01 baseline figure, an estimate, not measured after the change.** After the deploy there are no more
`briefing-best-bet` rows in `api_call_log`; check with `measured-costs.sql`.

- **Rejected, same decision: trim the gloss to four days (drop the fifth date).** The fifth date's gloss is what
  gives T+3 a pick overnight, so trimming it would remove a reader-facing pick to save a few cents.
- **Not part of this saving, and unchanged: force-evaluating headline contenders.** `ForceEvalHeadlineSelector`,
  `ForecastTaskCollector` and `ScheduledBatchEvaluationService` still force-evaluate far-out cells because their
  ratings feed `PlanWindowProjector`. That spend is a separate line and stays.
