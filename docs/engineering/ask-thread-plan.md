# Ask PhotoCast — the thread (follow-up questions)

Owner ask, 2026-10-10, after the first real session on v2.25.0:

> anything for sunrise with tide alignment this weekend? → *answer*
> Thanks. Anything closer to home? → *answer*
> Hmmm… neither of those take my fancy. I've been to both recently. What's the best sunset option? → *answer*
> Can you rank those options you returned? And I'll decide sunrise or sunset?

Today each typed question stands alone: the model sees the question, the window and scope in
context, and the five tools — never the previous answer — so "anything closer?" has nothing to be
closer *than*, and "rank those options" has no options. This plan makes the **session a thread**:
every question carries the exchanges before it, until the reader clears the answer or the forecast
moves. It is bounded by size, not by a hop count, because the fourth question above reaches back
to all three answers.

Two owner decisions taken with the ask (2026-10-10): **yes to a thread**, and **"close to home" means
the reader's saved local radius** (`app_user.local_radius_miles`, the Close-to-home radius, 10–50
miles, default `photocast.close-to-home.radius-miles` = 22), never the flat 60 drive minutes #1078
introduced as a stopgap.

Builds on `ask-photocast-plan.md` (the feature) and the 2026-10-08 refactor series (`AskScope`,
`AskConversation`, `AskEventType`, `AskReadyServing`, the client reducer in `utils/askConversation.js`).
Read those "As built" notes first; this plan states only what changes.

---

## §1 Invariants that do not move

1. **The validator decides everything.** A pick is served only if it is pick-eligible against the
   LIVE snapshot at the moment of the answer (`AskSnapshot.isPickEligible`). The thread adds a second
   way a pick can be *offered* to the model (below) but no way for a stale one to be served.
2. **Nothing persisted, nothing in module scope.** The thread lives in `AskContext` (React state) and
   is sent with each request; a logout, a reload or "Clear answer" ends it. `ask_log` keeps one row per
   answered request as now; it does not store the thread.
3. **The guards and their order** (`AskService`: rate limit → sanitise → pre-filter → snapshot → Ready
   match → cache → spend cap → reservation → engine) are unchanged. A follow-up is a typed question
   costing one allowance unit and one engine call; `engine_calls` is never refunded.
4. **The budget**: 4 model turns, `max_tokens` 600, 20 s per call, 30 s per conversation, `$0.50`/day.
   The thread must fit inside it — see §2.4.
5. **Every card fact is re-joined from the live snapshot**, never trusted from the client. A client-sent
   thread carries ids and the reader's own words; the server rebuilds the rest.
6. **Ready answers** are user-less and shared; a Ready answer can START a thread (the reader follows
   up on it) but is never itself computed with a thread.
7. `/` refusals, the four surfaces by width, `registerMapContext` as the one map channel, the
   `--psh` three-valued peek — untouched.

---

## §2 Design

### §2.1 The contract — `POST /api/ask`

`AskRequest` gains one optional field:

```json
{
  "question": "Anything closer to home?",
  "windowId": "2026-10-11_sunrise",
  "regionIds": [],
  "view": "map",
  "thread": [
    {
      "question": "anything for sunrise with tide alignment this weekend?",
      "summary": "Bamburgh and Dunstanburgh both have high water on the light on Saturday …",
      "picks": [
        {"locationId": 41, "windowId": "2026-10-11_sunrise"},
        {"locationId": 57, "windowId": "2026-10-11_sunrise"}
      ],
      "events": [{"type": "KING_TIDE", "date": "2026-10-11"}],
      "generatedAt": "2026-10-10T14:17:40"
    }
  ]
}
```

- `thread` is the prior exchanges, oldest first, **at most `photocast.ask.thread.max-exchanges`
  (default 8)**; a longer list is **400 `INVALID`** (the client never sends one — it trims). Absent or
  empty = a fresh question, byte-identical to today.
- Each exchange carries the reader's `question` (re-sanitised server-side with the SAME
  `AskQuestionSanitiser` rules — 200 chars, the allow-list, refused characters → 400), the answer's
  `summary` (the model's earlier words, now **client-supplied text**: a new
  `AskQuestionSanitiser.sanitiseThreadSummary` applies NFC, the SAME refused-character classes as a
  question (control, format, emoji, combining) and a hard **500 code-point cap** (400 `INVALID` over
  it — the validator's `clean` caps by whitespace-split WORDS, so one 5,000-char "word" would pass it),
  then the validator's `clean` (60 words, brand sanitiser, URL strip). It is treated as the reader's
  text, never the server's, and only ever lands inside the quoted "I answered" line), its `picks` as
  `(locationId, windowId)` pairs only, its `events` as `(type, date)` only, and the briefing
  `generatedAt` it was answered against.
- **Only typed and Ready answers that were `answerable` enter the thread.** A can't-answer, a refusal
  or an error is not an exchange (the client does not append it). An exchange that began as a Ready
  answer carries `ready: true`: its `generatedAt` is the PRECOMPUTE's snapshot (a Ready answer served
  after a manual briefing rebuild — which does not re-precompute — is older than the live run while
  still fresh by the Ready rule, since its picks re-join live), so the §2.2 reset reads **typed
  exchanges only**; a Ready-origin exchange is kept while its picks still re-join, which IS the Ready
  freshness rule.
- The response is unchanged in shape. `kind` keeps its three values; a follow-up that matched a Ready
  answer cannot happen (the matcher is skipped for follow-ups, §2.3).
- **Body cap**: `AskBodyLimitFilter` goes from 8 KiB to **16 KiB** for `POST /api/ask`. The honest
  arithmetic: an exchange is ~285 bytes of JSON structure (three pick objects, an event, `generatedAt`,
  keys) plus a 200-char question and a 500-code-point summary with multibyte characters — up to
  ~1 KiB; eight of them plus the outer body with 20 region ids can reach ~8.2 KiB, so 8 KiB would 400
  a legitimate full thread. The sanitiser's early raw-length refusals still bound the work done.
- **Cost of the cap**: a follow-up at eight exchanges is ~1.6× a fresh question's input (§2.4), so the
  `$0.50`/day typed cap buys roughly a third fewer questions for everyone on a day of long threads.
  Accepted; the cap is not raised.

### §2.2 The thread ends when the forecast moves

Every exchange carries the `generatedAt` it was answered against. The server compares each with the
live snapshot's `generatedAt`:

- **Same run**: the exchange is context.
- **Different run** (a typed exchange's `generatedAt` differs — `generatedAt` is the briefing BUILD
  time, `AskSnapshotBuilder`, which moves on a rebuild, never per serve; ratings re-enrich on every
  serve, so between builds a star can move while the run stands — the fact lines in §2.3 are re-joined
  live and stay current, only the earlier prose can lag): the server **drops the whole thread** and answers the question fresh, and the
  response carries `threadReset: true` with `threadResetReason: "forecast updated"`. The client shows
  one line above the new answer — *"The forecast has updated since your last question — this is a
  fresh answer."* — and empties its thread. (Rationale: "closer than the one you gave me" is
  meaningless against a different run; `generatedAt` is the same invalidation hint the Ready
  freshness and the typed cache already key on.)

The client also ends the thread on **Clear answer** and sign-out (the provider unmounts). Crossing
1024px releases only the surfaces' OPEN state (`askSheetOpen`/`askDockOpen`); the conversation
survives today and so does the thread.

### §2.3 What the server does with a thread

1. **Guards.** Rate limit, sanitiser (question AND every thread question), pre-filter (on the new
   question only — "car park" in a follow-up still refuses), snapshot. **The Ready intent matcher is
   skipped** for a follow-up (a thread question is by definition not a catalogue question — "anything
   closer?" must never silently become `BEST_SOON`). **The typed cache is skipped** for a follow-up
   (its key would have to include the whole thread; a follow-up is unlikely to repeat; simplest is
   honest). Spend cap, reservation, engine — unchanged.
2. **Prompt.** `AskPromptBuilder` renders the thread into the user message as prior turns, compactly:

   ```
   Earlier in this conversation:
   You asked: "anything for sunrise with tide alignment this weekend?"
   I answered: "Bamburgh and Dunstanburgh both have high water on the light on Saturday …"
     Picks: 1. Bamburgh (Northumberland) · Saturday sunrise · 4★ · Worth it · 55 min from home · tide suits
            2. Dunstanburgh (Northumberland) · Saturday sunrise · 4★ · Worth it · 60 min from home · tide suits
     Events: King tide · Saturday
   Now the reader asks: "Anything closer to home?"
   ```

   Every pick line is **re-joined live** (`AskSnapshot` slot: star, verdict, time, tide; the asker's
   drive minutes via `DriveTimeResolver`; distance via home lat/lon — §2.6). A pick whose
   `(locationId, windowId)` no longer exists in the snapshot (its window passed, the place disabled)
   is rendered as `Dunstanburgh · no longer in the forecast` so the model can say so rather than
   invent — the NAME comes from the lazy id→(name, lat, lon) map §2.6 loads (`LocationRepository.
   findAllEnabled`, one query per conversation, mirroring `driveMinutesByLocation`), or `a place no
   longer listed` when even that is gone. A prior pick that is **outside the follow-up's scope** (the
   Map's scope segment can move between questions) is rendered as `Bamburgh · outside the area you
   are now asking about` and is NOT offered to the validator. The system
   prompt gains one rule: *"Earlier picks are real candidates: you may rank, compare, or exclude them.
   Resolve 'closer', 'earlier', 'the other one', 'those' against them. Call `rank_spots` again when
   the reader asks for new places; you do not need to for a question only about the earlier picks."*
3. **Evidence.** `AskEvidence` gains the thread's picks and events as *offered* candidates
   (`ThreadFact`), so the validator's "a pick must have been returned by `rank_spots` IN THIS
   CONVERSATION" rule becomes "returned by `rank_spots` in this conversation **or offered by the
   thread**" — and in both cases **still pick-eligible live**. The same for an event's type. A pick the
   thread offered that is no longer eligible is dropped exactly as a stale `rank_spots` pick would be,
   and the summary rule (*say when something is missing*) applies.
4. **Validator**. Unchanged rules otherwise: ≤ 3 picks at distinct locations, ranks renumbered,
   word caps, brand/URL strip, every fact re-joined. The events-question "none while offered" rule
   (#1043) reads the live snapshot as now, not the thread. **One new outcome**: an answer to a
   follow-up whose every pick was a thread pick that has since dropped (passed, disabled, out of scope)
   and that made no tool call is a **CANT** ("those places are no longer in the forecast"), never the
   FAILED "answerable with no pick, no event and no tool call" — a thread that has gone stale is an
   honest can't-answer, not an engine fault.
5. **Logging.** `ask_log` gains nothing; the `[ASK]` INFO lines gain `thread=N`.

### §2.4 Size

A thread exchange renders to roughly 60 (question) + 60 (summary) + 3 × 25 (picks) ≈ 200 tokens.
Eight exchanges ≈ 1,600 input tokens on top of today's prompt (~2,500 with tools): a follow-up at the
cap costs about 1.6× a fresh question in input, output unchanged. At Haiku prices that is well under
2p; the `$0.50`/day cap stands. `photocast.ask.thread.max-exchanges` (1..20, default 8) bounds it;
the client trims the OLDEST exchange when it would exceed the cap. No token counting on the server —
the exchange count and the two text caps bound the size deterministically.

### §2.5 The client

`AskContext` already holds one conversation through the reducer (`utils/askConversation.js`). It
becomes a **list of exchanges plus the live conversation**:

- `thread: [{question, summary, picks: [{locationId, windowId}], events: [{type, date}],
  generatedAt, ready, answerId}]` — appended by `ANSWERED` when the answer was `answerable`
  (typed OR Ready, `ready` marking which); never appended by `FAILED`/`REFUSED`/a can't-answer;
  emptied by `CLEARED`, by a `threadReset` response, and on sign-out (the provider unmounts).
  ⚠️ Every other action — `ASK_SENT`, `READY_OPENED`, `FAILED`, `REFUSED` (including the `settled`
  conversation it restores from) — **carries `thread` through unchanged**: today those spread
  `INITIAL_CONVERSATION`, which would silently drop the thread on a failed or refused follow-up. A
  reducer test pins each.
- `send(text, asked)` sends `thread` (trimmed to the cap) with the question; `asked` records
  `followUp: thread.length` so the chip row can say **"follow-up · 3 so far"**.
- **Rendering**: the answers stack as a conversation — each prior exchange collapsed to its question
  and summary (its picks, map chips and "Plan this" belong to the LIVE answer only; an earlier
  answer's picks are reachable by asking — "rank those" — not by scrolling back into stale cards).
  The input stays below. The empty state is unchanged. `Clear answer` becomes **"Clear"** when a
  thread exists and ends it. On the phone peek the minimised line shows the live answer as now; the
  expanded sheet scrolls the stack.
- The reset line (§2.2) is one `AskThreadReset` component above the live answer, dismissed with the
  next question.
- The map: picks and the camera follow the LIVE answer only (unchanged).

### §2.6 "Close to home" is the saved radius

`rank_spots` gains **`nearHome: true`** beside `maxDriveMinutes`. The server resolves it:

- the asker's home (`app_user.home_latitude/longitude`) and saved `local_radius_miles`
  (default `photocast.close-to-home.radius-miles`, 22; Pro-gated in the UI, so a LITE account always
  has 22) → keep spots whose straight-line distance from home (`GeoUtils.distanceMiles`, via a lazy
  id→coords map from `LocationRepository.findAllEnabled` on the first `nearHome` — `AskSnapshot.Slot`
  carries no lat/lon) is within the radius. ⚠️ **Stated divergence, accepted**: the Map's reach lens
  and `rank_spots.maxDriveMinutes` measure DRIVE time; `nearHome` measures straight-line miles — the
  measure the "Close to home radius" setting has always named (`CloseToHomeService`, today
  caller-less). Two measures for "near home" across two tabs is the cost; the benefit is that a
  straight-line radius needs no ORS, so a LITE account with a home but no drive times gets it. The
  answer therefore says **"within N miles of home"**, never "within reach" (CLAUDE.md forbids that
  phrase without a drive time). "Closer" in a follow-up is the model narrowing `maxDriveMinutes`
  below the earlier picks' drive — the prompt says so — or `nearHome` when it had not been used.
- No home saved → the tool answers `nearHome is not available: no home postcode is saved`, and the
  model says so (the existing LITE-without-drive-times shape).
- The prompt rule from #1078 ("close to home with no time → 60 minutes") is **replaced**: *"'close to
  home', 'near', 'nearby', 'local' with no number → `nearHome: true`; a number of minutes →
  `maxDriveMinutes`."* `personal` is set by either argument (server-side, as now).
- `AskUserContext` gains the home point and radius (read once in `AskService` from the user row; a
  Ready conversation has neither and refuses `nearHome` as it refuses `maxDriveMinutes`).

### §2.7 What is deliberately not built

- **No server-side conversation store**, no conversation ids, no history across devices.
- **No tool-trace replay**: prior turns are rendered as facts, never as the raw `rank_spots` JSON.
- **No follow-up on a Ready answer while it is being precomputed** — Ready answers are computed
  thread-less; the thread begins when a reader follows one up.
- **No per-thread summarisation** when the cap is hit — the oldest exchange is dropped.
- **No "same question" dedupe** inside a thread (the cache is bypassed; asking twice costs twice).

---

## §3 Phases (each a Sonnet agent in a worktree → PR → Codex clean + CI + CodeQL → merge)

| Phase | Scope | Size |
|---|---|---|
| **T1 backend — contract and guards** | `AskRequest.thread` + `ThreadExchange` records with validation (cap, sanitiser on each question, `clean` on each summary, ids only); `AskService` skips the Ready matcher and the cache for a follow-up; the `generatedAt` comparison and the `threadReset` response fields; `AskErrorCode` unchanged (400 `INVALID` for a bad thread); `ask_log` unchanged; `[ASK]` lines carry `thread=N`. Tests: `AskTypedControllerTest` (400s, the reset), `AskServiceTest` (matcher/cache skipped only for follow-ups, charged as typed). | M |
| **T2 backend — prompt, evidence, validator** | `AskPromptBuilder` renders the thread (live re-join of every pick line; "no longer in the forecast"; "outside the area"); the system-prompt rule; `AskEvidence.ThreadFact`; `AskAnswerValidator` accepts a thread-offered pick/event that is still eligible and the stale-thread CANT; `ClaudeAskEngine`/`StubAskEngine` pass the thread through `AskConversation`. Golden prompt files get a thread case. One real-API check at the end (owner-authorised, ≤ 3 runs) against the fixture with an asker that HAS a home, a radius and stubbed coordinates (today's regression `ASKER` has neither, so "closer to home" could only be a CANT): the owner's four-question script, with the **expected outcome per step written down before the runs** — 1 OK with ≥ 2 coastal sunrise picks; 2 OK with picks inside the radius or an honest CANT naming the radius; 3 OK with sunset picks; 4 OK ranking only places from steps 1–3, no new `rank_spots` call required. | M–L |
| **T3 backend — `nearHome`** | `AskUserContext` home point + radius; `rank_spots.nearHome`; the prompt rule replacing #1078's 60 minutes; `personal` on either argument; `AskToolSchemas` + goldens; `AskToolsTest`. | S–M |
| **T4 frontend — the thread** | Reducer: `thread` list, `ANSWERED` appends when answerable, `CLEARED`/`THREAD_RESET` empty it; `send` carries it (trimmed); `asked.followUp`; the stacked rendering (`AskThreadHistory` collapsed exchanges above the live `AskAnswer`), the chip-row count, "Clear", `AskThreadReset`; the peek sheet's expanded state scrolls the stack. Adversarial review (runtime identity of the fresh-question path, a11y of the stack — one live region, prior exchanges are static text) before merge. | L |
|  | **As built (T4, 2026-10-11):** `utils/askConversation.js` — `thread` and `resetReason` on the conversation, `THREAD_MAX_EXCHANGES = 8`, `exchangeOf` (the wire fields plus the client's own `answerId`), `threadForWire` (newest 8, summary held to 500 code points, `answerId` dropped), `selectHistory` (the thread without the answer on screen, since the live answer is the thread's last exchange), the `THREAD_RESET` action (busy only; empties the thread, strips `asked.followUp`, records the reason; dispatched BEFORE the `ANSWERED` it belongs to). `ASK_SENT`, `READY_OPENED`, `FAILED` and `REFUSED` name `thread` themselves, each pinned; `ASK_SENT`/`READY_OPENED`/`FAILED` clear the reset line (it belongs to the answer it came with), `REFUSED` puts it back with the answer. `AskContext.send` posts `thread` only when non-empty (a first question's body is byte-identical to before) and sets `asked.followUp = thread.length` only when above zero, so a first question's `asked` is unchanged too (not `followUp: 0`); a 200 with `threadReset: true` dispatches `THREAD_RESET` (reason `threadResetReason`) then `ANSWERED`. The context exposes `thread`, `history` and `resetReason`. `AskThreadHistory` is a plain `<ol>` of collapsed exchanges (the reader's question as their bubble, the summary as muted text, visually-hidden "You asked" / "Answer" labels, nothing to press) above the live turn and OUTSIDE the one live region; `AskThreadReset` sits INSIDE it with the fresh answer; the chips gain "follow-up · N so far"; `AskClearAnswer` (and the phone peek's ✕) read "Clear" once `history` is non-empty, "Clear answer" otherwise. A follow-up moves the conversation's own scroller (`utils/askScroll.js`, not `scrollIntoView`, which would move the page under a sticky dock) to the new question; focus stays in the field. The surfaces' layouts needed no change: the history scrolls inside the sheet's scroller, the dock's `.wf-ask-dock-scroll` and the peek's body, with the input row outside all three. The minimised peek line, the map's picks, chips and camera follow the live answer only. Wire: request `thread: [{question, summary, picks: [{locationId, windowId}], events: [{type, date}], generatedAt, ready}]`; response fields read: `threadReset`, `threadResetReason`.
| **T5 docs + regression** | A NEW `AskThreadRegressionTest` (same `prompt-regression` profile, header "assertions pending owner approval, 2026-10-11") holding the four-question script with the per-step expectations above — the existing owner-approved `AskPromptRegressionTest` is never edited; CLAUDE.md "Ask — the engine"/"the typed guards"/"the client" bullets; `ask-photocast-plan.md` cross-link; changelog entries per phase. | S |

T1 → T2 → T3 sequential (same files); T4 can start after T1 (it needs the contract only); T5 last.

---

## §4 Decisions taken in this plan (challenge in review)

1. **Thread cap = 8 exchanges, by count, not tokens.** Deterministic and testable; §2.4's arithmetic
   keeps it inside the budget. A token count would need the tokeniser on the server for no gain.
2. **A rebuild ends the thread** rather than re-joining it across runs. Between builds the pick lines
   are re-joined live and the earlier summary may lag a re-enriched star by a serve — accepted, the
   facts beside it are current. Across a rebuild the reader would be comparing against a forecast
   they never saw; an honest reset beats silent drift.
3. **Prior answers are rendered as facts, re-joined live**, not as the model's earlier prose about
   them. The model's `why` strings are not carried at all (the summary is enough context; the
   per-pick `why` would double the size for little).
4. **The Ready matcher and the typed cache are skipped for follow-ups.** A follow-up that matched a
   Ready answer would answer a different question than the one asked.
5. **An unanswerable or failed step is not an exchange.** The thread is the record of what the
   reader was told, not of what they typed.
6. **`nearHome` resolves to the straight-line local radius**, the same measure the Close-to-home
   radius has always meant, not a drive-time conversion — two measures for one word is the thing
   the tide axes rule in CLAUDE.md warns against.
7. **The client trims the oldest exchange**, the server refuses a list over the cap. Belt and braces;
   the server is the guarantee.

## §5 Open questions — decided without the owner (2026-10-10, autonomous build authorised)

- **Q1** Prior exchanges are NOT tappable: collapsed to question + summary; only the live answer has
  cards, chips and "Plan this". (Alternative, not taken: re-join them live on render — cheap, but shows
  a star beside a summary written against another.)
- **Q2** The cap is 8 exchanges (`photocast.ask.thread.max-exchanges`, 1..20).
- **Q3** The reset line reads: *"The forecast has updated since your last question — this is a fresh
  answer."*
- **Q4** The T5 regression case lives in a new class, assertions marked pending the owner's approval.

## §5a Adversarial review of this plan (2026-10-10)

Five lenses; confirmed and folded in above: the summary channel needed a sanitiser, not just `clean`
(§2.1); the 8 KiB body cap could 400 a legitimate full thread (§2.1); a Ready-origin exchange's
`generatedAt` would trip the reset on the first follow-up (§2.1/§2.2); the reset's rationale over-
claimed across serves (§2.2, §4.2); a scope move between questions could turn a stale thread into a
FAILED (§2.3 items 2 and 4); the 1024px clause contradicted itself (§2.2); the reducer would drop the
thread on `FAILED`/`REFUSED` under the current `INITIAL_CONVERSATION` spread (§2.5); `nearHome`'s
measure diverges from the Map's drive-based reach (§2.6, now stated and accepted); `AskSnapshot.Slot`
has no coordinates (§2.6); the T2 real-API check and the T5 case were under-defined (§3). Refuted:
foreign/disabled/out-of-scope ids reaching a card (the validator re-joins against the live pool and
scope); any guard bypass; `personal` on follow-ups; the BEST-anchor and events rules; module-scope
and logout; one-answer assumptions in the peek and `selectView`; focus after a follow-up; T4's
dependency on T2.

## §6 Measured (fill in at T5)

Per-question cost fresh vs at the cap; thread length distribution from the `[ASK] thread=N` lines
over the first week; how often `threadReset` fires.
