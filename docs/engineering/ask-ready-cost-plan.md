# Ask ready answers: cut the cost of the precompute

**Status: PLANNED and reviewed 2026-11-03; R0 measured the same day; nothing built.** Owner's request
after the cost chart of 3 November 2026 (`claude-cost-ledger.md`, "Why spend doubled"). Written against
`main` at `d319c606`.

## 1. Why

Ask ready answers (`RunType.ASK_READY`, `service/ask/AskReadyPrecompute.java`) went from about $0.40 a
day to about $1.00 in October: £14 of the £48 rise in the 30 days to 2 November. Measured on production
(`cost-queries/overnight-and-ask-daily.sql`):

- **Runs:** 3–4 a day. One runs after each forecast cycle (overnight 02–03 UTC, afternoon 12–13, evening
  20–21; `PipelineOrchestrator.java:801-813, 867-871`), at about $0.25–0.35 each. The astro job adds a
  night-only run at 15:30 UTC (`AstroConditionsRefreshJob.java:245`, about $0.03).
- **Calls:** 42–74 a run on Haiku 4.5 (`photocast.ask.model`, `AskProperties.java:76`). A run asks every
  question for every scope (`ALL` plus each enabled region; `AskReadyPrecompute.java:284-299, 419-425`).
  Each task is a conversation of up to four model calls (`ClaudeAskEngine.java:234`), and tasks run one
  after another.
- **Prompt:** about 2,500 input tokens per call before 12 October and about 4,200 after 21 October. The
  first step came with the night tool and question (N1/N2). The second came with W1's rating
  distributions and covered windows, and `RESULT_CHAR_CAP` going from 6,000 to 9,000.

## 2. What R0 measured (2026-11-03, `cost-queries/ask-ready-cache.sql`)

**Per day since caching began:** caching saves 3–10% (actual $0.56–1.02 a day against $0.58–1.12 with no
caching). 11–20% of calls read the cache and 26–51% write to it. When a call reads, it reads about
4,450 tokens; when it writes, it writes about 2,800.

**One afternoon run, call by call (53 calls, about 22 tasks), shows the shape of every task:**

| Turn | Input | Cache write | Cache read | Output | Cost |
|---|---|---|---|---|---|
| 1 | ~3,600 | 0 | 0 | **21** | $0.0037 |
| 2 | ~20 | ~4,450 | 0 | ~100–300 | $0.0061–0.0070 |
| 3 (about one task in three) | ~20 | ~300–450 | ~4,450 | ~150–270 | $0.0016–0.0023 |

What this shows:
- **The static block is never cached.** Turn 1's whole prompt is about 3,600 tokens, below Haiku 4.5's
  4,096-token minimum (`PromptBuilder.java:43-47`), so the static breakpoint does nothing. No call ever
  reads a prefix shared across tasks.
- **Turn 1 always writes 21 output tokens.** It is the same opening tool call every time, paid at the
  full ~3,600-token input. That is about 40% of a two-turn task's cost, spent on a tool call whose answer
  the server already knows.
- **The rolling breakpoint mostly loses.** Turn 2 writes the whole conversation (~4,450 tokens at 1.25×).
  Only the third of tasks that have a turn 3 read it back. On two-turn tasks it costs about $0.0011 more
  than no caching; on three-turn tasks it saves about $0.0028. Overall it comes out roughly even, which
  matches the small daily saving.

## 3. The levers, ranked by what they could save

1. **Run Ask ready answers on Haiku 5.5 (lever M).** Haiku 5.5 is $0.10 / $0.50 per million input /
   output tokens, against Haiku 4.5's $1.00 / $5.00 (`application-example.yml:42-62`): ten times cheaper.
   That would cut Ready's cost by about 90%, roughly $25 a month to $3. The ledger's rejection of
   Haiku 5.5 was for *sky ratings*: it misread directional cloud on the Sky Eval. Ask is a different
   job: tool use, picking from served ratings, and short prose. It needs its own quality check before it
   is switched, not an assumption either way. The switch is one config value. The work is the check.
2. **Answer the opening tool call in the prompt (lever T).** Every Ready task's first turn requests the
   same tool, and the server could put that tool's result in the opening message. That removes a model
   call per task: about 22 calls a run, ~$0.08 of a ~$0.30 run, ~25–30%. R-prep first confirms which tool
   it is and that it is the same for every question.
3. **Make the cache work (lever C).**
   - Drop the rolling breakpoint for Ready, or keep it only once a conversation has reached turn 2: it
     is roughly break-even today, and after lever T most tasks are one or two turns.
   - Cache the shared static block across tasks, if it can reach 4,096 tokens with content that belongs
     there (no padding for its own sake). The run is sequential and takes about a minute, inside the
     5-minute lifetime, so one write would be read about 20 times. The saving is perhaps 10–20% of the
     input cost, depending on how much of turn 1's 3,600 tokens is static. Measure it with the
     token-counting endpoint, which is free but uses the real key, so the owner runs it or approves it.
   - Lever C matters less after M: on Haiku 5.5, cache arithmetic is worth a tenth as much.
4. **Reuse a still-fresh answer (lever R).** Before asking a task, run its stored answer through
   `AskReadyFreshness` against the run's new snapshot, and skip the task if the answer would be served.
   Review showed this saves less than it looks (§4). It is last.

**Not proposed:** fewer scopes or questions (a product change), or skipping the evening precompute
outright.

## 4. Lever R: what review found

The precompute's `snapshotBuilder.build()` and the serve path's `current()` produce the same `AskSnapshot`
from the same briefing, and `check()` needs only the question, the stored row, the live snapshot and
the scope. So the same rule readers get can be applied before asking. But:

- **New events are not caught (review blocker).** `recheck` only asks whether each event the answer
  already carries is still live (`AskReadyFreshness.java:192-200`).
  - `RARE_EVENTS` and `SNOW_TOPS` have no windows or covered set, so a reused answer would miss a newly
    added warning or eclipse.
  - `NIGHT_SKY` would miss a new aurora topic, because only the astro version is checked.
  - Today the next cycle picks these up, so reuse would be strictly worse, and a missed warning is a
    safety regression.
  - Rule: at write time, store the set of (type, date) events the question admits from the snapshot (the
    counting is in `AskAnswerValidator`, around `:338`). Reuse only when the live set is equal; for
    `NIGHT_SKY`, also compare the live aurora topics. Otherwise never reuse those questions.
- **Same UK day only.** `recheck` keeps the model's `summary` and `missing` text as well as `why`
  (`AskReadyFreshness.java:205-206`; its Javadoc at `:41-43` says otherwise). A "tomorrow" in a summary
  goes stale at midnight, and fixed-text questions don't trip the question-text rule. Reuse only when
  the stored row was written on the same UK civil date as `ForecastHorizon.today(clock)`.
- **Current-format rows only.** A row with no covered set or no carried spreads reads as "unknown, not
  stale" (`AskReadyFreshness.java:95-99, 209-217`).
- **Keep the stored "as of".** `generatedAt` and `runLabel` come from the row
  (`AskReadyServing.java:135-136`), and the `/api/ask/ready` ETag is a shallow body hash
  (`HttpCachingConfig.java:114`). Don't bump either.
- **Less survives than it seems.** By 20:00–21:00 UTC, today's sunset has left the window set. So the
  covered set of `BEST_SOON` and `COASTAL_HIGH` changes and they are re-asked anyway
  (`ReadyQuestion.java:58,89`; `AskReadyFreshness.java:97-99`). `AM_OR_PM` moves to another day, and
  `BEST_NEXT`'s text changes.
  - After the evening, only `BEST_WEEKEND` on a weekday and `NIGHT_SKY` survive, plus the events
    questions once the event-set rule is in.
  - After the overnight, almost nothing survives.
- **Accepted residual gaps** on the pick questions:
  - two places swapping ratings outside the four listed pockets leave the spread record equal;
  - a pick's `why` may quote cloud or wind figures that drifted while its rating held.

## 5. Phases

Each phase is one PR from `main`, built by a Sonnet agent in its own worktree and reviewed by the planner.

**R0 — done.** See §2.

**R-prep — facts for M and T (read-only, no PR):**
- which tool turn 1 calls, and whether it is the same for every question and scope;
- the static block's token count (the owner runs or approves the free token-counting call);
- what Ask quality checks exist today: fixtures, `AskLocalFixtureSeeder`, any eval or regression suite.

Record the findings here.

**M — the Haiku 5.5 quality check, then the switch.**
- Build an offline comparison: run a fixed set of Ready tasks (today's snapshot fixtures) through both
  models and compare.
  - **Mechanical, by script:** each answer passes `AskAnswerValidator`; same picks or an equally rated
    pick; no invented place or window.
  - **The owner's read:** a side-by-side page of the two answers per task.
- It costs a few cents of real API spend, so it needs the owner's go-ahead.
- If the owner approves the answers, `photocast.ask.model` becomes `HAIKU_55` for Ready. Typed Ask stays
  as it is unless the owner extends it.
- Check first that `ClaudeAskEngine` and `CostCalculator` handle `HAIKU_55` (it exists in
  `EvaluationModel`, and Haiku 5.5's cache minimum may differ; read it from the model's documentation,
  not memory).

**T — answer the opening tool call in the prompt.**
- For Ready only, put the result of the turn-1 tool (from R-prep) in the opening user message.
- Tell the model it already has that result, and keep the tool available.
- Prove it with the engine's existing tests and a before/after count of turns per task on a local stub
  run.

**C — the cache, after M and T.** Re-run R0's query a week after M and T. Change the breakpoints only if
the numbers still say so.

**R — reuse, last, behind a switch.**
- **R1 (shadow):** count *would reuse* and *would re-ask*, with §4's rules applied. Totals go on
  `job_run` notes (500 characters), with a log line per task.
- **R2:** `photocast.ask.ready.reuse-fresh`, default `false`.
  - `precomputeOnDemand` always re-asks.
  - A run that skips everything still counts toward `ready.max-cycles-per-day`.

## 6. Expected effect

| Lever | Ready cost after it (rough) | Confidence |
|---|---|---|
| Today | ~$0.30 a run, ~$25–30 a month | measured |
| M (Haiku 5.5) | ~$0.03 a run, ~$3 a month | price arithmetic; quality not yet checked |
| T (no opening call) | −25–30% of whatever is left | arithmetic from the run in §2 |
| C, R | a few percent of what is left | small |

## 7. Review record

2026-11-03, one read-only reviewer, on the first draft.
- **Blocker folded in:** reuse would miss newly added events (§4).
- **Should-fixes folded in:**
  - model text other than `why` is kept (the same-UK-day rule);
  - the evening's reuse is smaller than first claimed;
  - caching was not a net loss on the averages (R0 then showed it roughly break-even);
  - the SQL's run pick, model and `succeeded` filters.
- **Verified:** the same snapshot on both paths; the prompt order and breakpoints; the 4,096 minimum; the
  5-minute cache lifetime; the SQL's joins.

R0 (same day) reordered the levers. The model and the opening tool call now come first, and reuse moved
to last.
