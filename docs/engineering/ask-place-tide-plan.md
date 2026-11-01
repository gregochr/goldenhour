# Ask: tide questions about a named place — build plan

Status: **plan, reviewed** (2026-11-01, two lenses: code facts, reader; §7 lists what each changed). One backend
phase (**T1**), one PR, built by a Sonnet subagent from this file. It extends `look_up_place`
(`ask-place-lookup-plan.md`, `ask-place-explain-plan.md` E1/E2). No frontend change, no migration, no new
endpoint. **T2** (§2 T-9) is the recommended next phase.

## §1 The question

"Is the tide right at St Mary's Lighthouse?", and the same question with aligned, correct, good, or "does the
tide work".

Today the model calls `look_up_place`. Each row carries three served tide facts: `tideState`, `tideAligned`
(coastal only) and `tideFitPhrase`. The fit sentence (`TideWording.tideFitPhrase`) has two forms:

- **Match**: `high water, rising · HW 06:42 · 40m after sunrise · 2.6 m`. That is the state, the direction, the
  nearest high or low water (`HW`/`LW`, of either kind) and its offset from the light, then the height.
  `40m` means minutes, and `1h43` is the hour form. Within 5 minutes the offset reads `at sunrise`.
- **Miss**: `wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m`. That is the wanted water, the
  state, the direction at the light's UK clock time, and the height against the day's high water. **It has no
  nearest high or low.**

The gaps:

1. **On a miss, Ask cannot give the nearest high or low water.** `LocationTideFact.nearestSolarOffsetPhrase`
   (`HW 06:42 · 40m after sunrise`, Pro) is dropped by `AskSnapshotBuilder.toSlot`. ⚠️ It is
   **type-blind**: it is the nearest extreme of either kind (`BriefingSlotBuilder` :440-452), so on a
   wants-low miss it can be a high water. It answers "when's the nearest high or low", **never "when will the
   tide suit"**.
2. **The prompt never explains the tide fields.** RULES only says *Say the tide "suits" a spot only when
   tideAligned is true*. Nothing explains the fit-sentence forms or the `HW`/`40m` shorthand, which reads as
   metres beside "2.6 m".
3. **Spring and king tides are never checked** in a single-place question.
4. **Safety.** St Mary's has a tidal causeway. No served fact covers tidal access, and RULES already says
   "Never give advice about access … or safety". A quoted low-water time must never read as a crossing time.

**Checked and not a gap**:
- `TideResult.tideAligned` is a primitive `boolean`, and the snapshot is projected from un-stripped slots
  (`WindowTideFactProjector` :79; Lite shaping happens only at the controller edge). So no coastal Pro fact
  reaches Ask with `tideAligned` null.
- A location wanting every state (`TideType.coversEveryState`) is always aligned (`TideService` :508).

## §2 Decisions

| # | Decision |
|---|---|
| T-1 | `AskSnapshot.Slot` gains `String nearestTide`, copied verbatim from `LocationTideFact.nearestSolarOffsetPhrase`. |
| T-2 | `PlaceInfo` gains `nearestTide` only on a **miss row**, meaning `tideState != null` and `tideAligned` false. The match form already carries it. It also goes only on **rows of the one place the answer is about** (E1's X5 rule). Cap it at `NEAREST_TIDE_CAP = 60`; real phrases are 30 characters or fewer. |
| T-3 | **No `tideOnTheLight` and no `tideShortfall`.** The question "is high or low water at sunrise?" is answered by quoting the HW/LW phrase, never by calling it aligned. The miss form's "wants …" clause already says which water is wanted. A reader using the Map's own "on the light" wording means `tideAligned`. |
| T-4 | **Prompt** (typed only, one bullet, §4.3): synonyms map to `tideAligned`; the answer leads with the tide verdict; the shorthand is spelled out; the type-blind nearest high or low is never framed as when the tide suits; times and heights are quoted, never computed; heights are never called big or small. |
| T-5 | **Safety**: crossing, a causeway, being cut off, when it is safe to walk out: answer `answerable false`, missing "tidal crossing times". A high or low time is never presented as access or safety. |
| T-6 | **Spring and king tides**: call `look_up_place` first, then in the same turn `get_hot_topics` with `types: ["KING_TIDE","SPRING_TIDE"]` and `limit: 2`. Mention a topic only when its date is that window's day and its regions include the place's region or are empty. "Big tide" is said only from such a topic. A tide answer that carries such an event becomes cacheable (an event is re-checked); record this in the reference doc. |
| T-7 | **No tide fields on a row**: say the forecast has no tide for that place in that window. Never say "not tidal", because a coastal place with no derivation looks the same as an inland one. |
| T-8 | **A day with no row** (not scored yet: T+4 and beyond, or a travel day, which `list_windows` omits entirely): if the day is not among the windows, or `look_up_place` has no row for it, answer `answerable false`, saying PhotoCast has not forecast that day yet. Never give a tide. |
| T-9 | **T2, the next phase** (recommended, not built here): tide-only rows from `BriefingWindow.tideFacts` for unscored windows. Weekend questions asked early in the week will hit T-8 often. |
| T-10 | **Budget**: the row cap is 4 when any row carries detail or `nearestTide`. One lookup stays ≤ 4,500. A tide conversation (list_windows + look_up_place + hot topics at limit 2) is measured against `RESULT_CHAR_CAP` (9,000). No bound is raised. The `look_up_place` tool description is unchanged. |

## §3 What must not change

- `rank_spots`, `list_windows`, the night tool, evidence, picks, and Ready. The Ready prompt and tool bytes are
  guarded by the F1 Ready tests.
- E1 detail, E2 spelling and F1 light rules. The new bullet adds an order rule for tide questions only.
- The tide axes: no `tideOnTheLight`; nothing ORs a height test or an "on the light" test into `tideAligned`.
- `src/test/java/.../regression/` assertions.

## §4 T1 — build

### 4.1 Snapshot

Append `String nearestTide` to `AskSnapshot.Slot`, keeping the existing constructors by delegation, as E1 did.
`AskSnapshotBuilder.toSlot` fills it with `fact == null ? null : fact.nearestSolarOffsetPhrase()`.

### 4.2 `look_up_place`

- **`PlaceInfo`** gains a trailing `String nearestTide`, omitted when null, with delegating constructors.
- **When a row gets it.** Set it in the row loop only when all of these hold:
  - the row belongs to the one place the answer is about (reuse E1's `aboutOnePlace`/`firstPlace`);
  - `tideState != null`;
  - `!tideAligned`;
  - the phrase is not blank.

  Cap the phrase with `cap(…, NEAREST_TIDE_CAP)`.
- **Row cap.** It is 4 when any row carries detail or `nearestTide`.
- **Unchanged.** The named-area branch.

### 4.3 Prompt (typed only)

Append one bullet at the end of `PLACE_FIT_AFTER`, after F1's. Keep source lines ≤120 characters:

> - For a tide question about a named place (is the tide right, aligned, correct, good, does it work), call
> look_up_place, then in the same turn get_hot_topics with types KING_TIDE and SPRING_TIDE and limit 2. Lead
> with whether the tide suits: it suits only when tideAligned is true. Then give the rating in one clause. In
> tideFitPhrase and nearestTide, HW and LW are high and low water, 40m and 1h43 are minutes and hours, and times
> are UK clock times: say "high water 06:42, 40 minutes after sunrise". In "2.6 m of 4.3 m", 4.3 m is that day's
> high water. nearestTide is the nearest high or low of either kind: never say the tide would suit then. Quote
> times and heights as given, never work out another, and call a tide big only from a king or spring topic whose
> date is that day and whose regions include the place's region or are empty. A row with no tide fields: the
> forecast has no tide for that place then. No row for the day asked: PhotoCast has not forecast that day yet;
> answer answerable false and give no tide. Crossing, a causeway, being cut off or when it is safe to walk out:
> answer answerable false, missing tidal crossing times; never present a tide time as access or safety.

### 4.4 Tests (read `docs/engineering/test-improvement-standards.md` first)

- **`AskSnapshotBuilderTest`**: `nearestTide` is copied from the window's fact, by id and by the name
  fallback. A slot with no fact gives null.
- **`AskToolsLookUpPlaceTest`**:
  - A miss row of the asked-about place carries `nearestTide`. Use real-format fixtures such as
    `"HW 06:42 · 40m after sunrise"` and `"LW 18:03 · at sunset"`.
  - A match row does not carry it, and the phrase is not repeated.
  - A row with no tide state has none.
  - A second matched place's rows have none.
  - A 70-character phrase is capped at 60.
  - With only `nearestTide` present (no detail), the row cap is 4 and the note reads "Showing 4 of N".
- **Worst cases**: switch E1's worst-case fixtures to **miss** rows (they use `tideAligned=true` today, so
  `nearestTide` would never appear) with 60-character phrases. Cover the 6-window, alias and correction shapes,
  each ≤ 4,500, and print the figures (the review estimates about 4,254 at most).
- **Combined budget**: in a typed conversation, `list_windows` + a worst-case `look_up_place` +
  `get_hot_topics(types KING_TIDE, SPRING_TIDE; limit 2)` fits `RESULT_CHAR_CAP`. Print the total. If any bound
  fails, stop and report.
- **`AskPromptBuilderTest`**:
  - The typed prompt and the thread prompt contain the bullet, and it comes after E1's order sentence.
  - The user-less prompt contains no `nearestTide`, `KING_TIDE` or `causeway`.
  - The F1 Ready guards pass unchanged.
- **`ClaudeAskEngineTest`**: use the stub harness, and capture or constrain the requests (no broad `any()`
  stubs; `backend/AGENTS.md`, Codex #1591).
  - Case (a): list_windows, then look_up_place + get_hot_topics in one turn, then submit.
  - Case (b): an E2 spelling tie, then the re-call + get_hot_topics in one turn, then submit.
  - Both finish within 4 turns, and each request carries the expected tool results.
- **Mutation**: remove the "miss only" condition and see the match-row test fail. Back up with `cp`, never
  `git checkout --`.

### 4.5 Docs and changelog

- `reference/ask-photocast.md`, the `look_up_place` paragraph:
  - add `nearestTide`, T-2 to T-8 in brief, and the §1 "not a gap" findings;
  - record that tide answers carrying a king or spring event are now cacheable;
  - record T2 as next.
- `changelog.d/20261101-ask-place-tide.md`: `### Added — Ask answers whether the tide is right at a named place`.
- Fill in §6 "As built".

## §5 L-check (owner-run, costs money)

Run with `photocast.ask.enabled` on and `stub: false`:

1. "Is the tide right at St Mary's Lighthouse tonight?" Run it once on a window where the tide suits and once
   where it misses. Expect the tide verdict first.
2. "Is the tide aligned at Saint Marys tomorrow morning?" Expect the E2 "Assuming you mean" opening.
3. "When's high water at St Mary's tomorrow?" on a miss window. Expect the nearest HW or LW quoted in words,
   never "the tide will suit at …".
4. "Will the causeway be open at St Mary's tomorrow?" Expect a refusal with no tide time.
5. A coastal place with no tide that window: expect "no tide for that place then", never "not tidal".
6. During a live spring or king tide in that region: expect it mentioned. In another region: expect it not
   mentioned.
7. A weekend day not yet scored: expect "not forecast that day yet" and no tide.

For every answer, check: no computed time, no "suits" when `tideAligned` is false, and nothing cut off at the
60-word limit.

## §6 As built

T1 built as §4.1 to §4.5 specify, one PR, no deviation from the decisions.

- `AskSnapshot.Slot.nearestTide` (delegating 14-argument constructor kept); `AskSnapshotBuilder.toSlot` fills it from the window fact.
- `AskTools.PlaceInfo.nearestTide` (delegating 15-argument constructor kept), `NEAREST_TIDE_CAP` 60, set only on miss rows of the one place asked about; a set value counts as detail for the row cap (4).
- §4.3 bullet appended to `PLACE_FIT_AFTER` verbatim.
- Measured: `look_up_place` worst cases with 60-character miss phrases: 6 windows 3,978; 40-character alias 4,254; correction path 4,008; `nearestTide` only (no summary) 2,898; all under `WORST_CASE_CHARS` 4,500, which is unchanged. Tide conversation: `list_windows` 1,677 + `look_up_place` 3,978 + `get_hot_topics` (limit 2) 776 = 6,431 of 9,000.
- Engine: tide question in 3 turns, and with an E2 spelling tie in 4, both with per-turn tool results asserted.
- Mutation: removing the `!tideAligned` condition fails `matchRowHasNoNearestTide`.

## §7 Review record (2026-11-01)

- **Code facts**: no blockers.
  - Confirmed: `tideAligned` is never null on a coastal Pro fact; the id and name joins; `coastal()`; E1's
    structure takes T-2 and T-6; the type strings and topic regions/date; parallel tool calls; 4 turns; Ready
    unaffected.
  - Found: the phrase format (→ §1, T-4); the nearest high or low is type-blind (→ T-2, T-4); T-7 was missing
    from the bullet, and travel days are omitted rather than shown unscored (→ T-8); the combined budget
    (→ T-6 limit 2, a test); the turn budget with an E2 tie (→ engine case b); `coversEveryState`; the
    worst-case fixtures must be miss rows; the tool description and the cacheability note.
- **Reader**: causeway safety (→ T-5); type-blind wording (→ T-4); `HW`/`40m` shorthand (→ T-4); "no tide ≠
  not tidal" (→ T-7); answer order for tide questions (→ T-4); T-3's rationale (Map's "on the light" is the
  preference axis); empty topic regions (→ T-6); T-8 unworkable as first written (→ rewritten); T2 made the
  recommended next phase (→ T-9); one tight bullet.

## §8 T2: tides for windows Ask does not rate (plan, reviewed 2026-11-01)

One backend phase, one PR. Two review lenses (code facts, reader), §8.8.

### 8.1 The gap

- The served briefing covers `ForecastHorizon.BRIEFING_WINDOW_DAYS` = 5 UK dates (today to T+4). The Ask
  snapshot keeps only the Plan tab's `renderedEvents` (`PlanRenderLimits.MAX_VISIBLE_EVENTS` = 6 solar events)
  that have not passed and are not travel days. Two to four solar windows lie after the six.
- `PlanWindowProjector.apply` gives every summary a `window()`, rendered or not, and gives it `tideFacts`
  (:192-200).
- ⚠️ **The slots of those later windows are empty.**
  - Gate 4 does not score T+3 (except SETTLED regions) or any of T+4.
  - `BriefingHonestyFilter.fullRewrite` (:381-420) empties a zero-coverage region's slots, which is the
    designed state of those windows and of every travel day.
  - `ServedBriefingAssembler.assembleForPlan` (:152-158) projects `tideFacts` from the RAW snapshot before
    the filter, so the facts survive. **They are the only place-level data a later window has.**
- Ask drops all of this, and T-8 answers "not forecast that day yet". T2 serves the facts. A day past T+4
  (Saturday asked on a Monday) is still T-8.

### 8.2 Decisions

| # | Decision |
|---|---|
| T2-1 | **Later windows.** `AskSnapshot` gains `List<LaterWindow> laterWindows`. A later window is a solar summary in the served briefing with a non-null `window()` that has not passed (`PlanWindowProjector.hasPassed`, same `now` as `toWindow`) and has a non-empty `tideFacts`, and that is **not** an Ask window. Not being an Ask window means it is outside `renderedEvents` **or** it is a travel day: a travel day's tide is from tables and does not depend on the owner being away, so it never will be "forecast later". `LaterWindow(String id, LocalDate date, TargetType targetType, LocalDateTime eventTime, List<LaterTide> tides)`. `id` is from `AskWindowId.format`. |
| T2-2 | **Built from the facts, not slots.** `LaterTide(long locationId, String name, String region, String tideState, boolean coastal, boolean tideAligned, String tideFitPhrase, String nearestTide)`, one per `LocationTideFact`. Facts with a null `locationId` are skipped. `region` is resolved from the **location roster** by id (an enabled location's region name, read once per snapshot build through a query that fetch-joins the region: the Ready precompute builds snapshots outside a transaction, see the class javadoc on ASTRO rows). A fact whose location has no region, or is unknown or disabled, is skipped (the briefing never walks `unregioned()` for Ask). The roster read is fail-soft: a failure gives no later windows, never no snapshot. **`windows()` is unchanged**, so rank_spots, list_windows' rated rows, picks, evidence, spreads, Ready, the cache key and freshness never see a later window. |
| T2-3 | **Only the tide is served from a later window**: never a rating, verdict, headline, summary, scores or status. The Plan tab never shows a later window's rating, and a SETTLED T+3 rating that exists is withheld. `LaterTide` has no field for any of them, so this holds by construction. |
| T2-4 | **`list_windows`, typed only** (`user.hasUser()`), gains `laterWindows: [{id, day, event, time}]`. The component is `@JsonInclude(NON_EMPTY)` (the mapper is a bare Jackson 2 `ObjectMapper` that writes nulls), and it is empty when user-less, so Ready's bytes are unchanged. `AskToolSchemas.listWindows()` takes `withUser` and appends, when true: " Then laterWindows: later sunrises and sunsets that are not rated, for tide questions only: pass their ids to look_up_place, never to rank_spots." The user-less text stays byte-identical. |
| T2-5 | **`look_up_place` and window ids.** Its id resolution (AskTools :1080-1086, today `snapshot.window(id)` only, an unknown id failing the call) also accepts a later window id. A later id selects that later window's tides; a rated id selects as today. With no `windowIds` (the default) nothing later is included and the result is byte-identical to today. `windowIds`' description, typed only, becomes "Only these window ids (from list_windows, including laterWindows). Default: every rated window." (Is the schema typed only? `lookUpPlace()` is added only when `!ready`, so yes, and Ready's bytes are untouched.) |
| T2-6 | **`rank_spots` and a later id.** Today an unknown id fails with "Unknown window id … Call list_windows for the valid ids", which risks a loop because list_windows lists it. For an id that is a later window, the error reads "<id> is not rated: use look_up_place for its tide." |
| T2-7 | **Matching.** The place is matched over the requested rated windows' slots **and** the requested later windows' tides (name and `locationAliases` by id), merged by location id into the same `PlaceMatch`, so a call naming only a later id still finds the place. Region scope and out-of-scope reach apply to later tides as to rated slots. The E2 spelling passes (`reach`, `nearSpellings`, `closestSpelling`) also see later-tide names. A named area never carries later tides. |
| T2-8 | **The result.** `LookUpPlaceResult` gains `laterTides: [{windowId, day, event, tideState, tideAligned, tideFitPhrase, nearestTide}]` (`NON_NULL`, null when none), for the first place only, and only when `aboutOnePlace`. Order is chronological. `tideFitPhrase` is capped as the rated row's is. `nearestTide` is only on a miss (`coastal && !tideAligned`), capped at 60, as T-2. A tide with a null `tideState` is no row. |
| T2-9 | **Budget.** `MAX_LATER_TIDE_ROWS` = 2. When any later tide is present, the rated rows are capped so that rated rows plus later tides ≤ `MAX_PLACE_ROWS_WITH_DETAIL` (4). Later tides are kept first, because the reader asked for them by id. "Showing N of M" counts rated rows. One lookup stays ≤ 4,500 (`WORST_CASE_CHARS`), and no bound is raised. |
| T2-10 | **Never cached.** Set `uncacheable` in look_up_place's accepted runnable whenever `laterTides` is non-empty. When a sunrise passes, the horizon shifts under the same `generatedAt` and a later window becomes rated, so an "is not giving a sky rating" answer would be stale. |
| T2-11 | **Answer shape.** There is no pick (a pick is a rated window). The answer is answerable when a tool was called (`AskAnswerValidator` :290-301). |
| T2-12 | **Hot topics.** T-6's same-day, same-region rule is unchanged. Topics on travel days are dropped by the hot-topic travel filter, so Ask never says "big tide" on a travel day. That is safe; record it in the reference doc. |

### 8.3 What must not change

- Everything in §3.
- `AskSnapshot.windows()`, and `withNames` must carry `laterWindows` through. Keep a delegating constructor
  without `laterWindows` (about 18 test call sites).
- The rank_spots error for a truly unknown id.
- Ready tool output, prompt and answers.
- The `look_up_place` result when no later id is requested.
- The named-area branch.

### 8.4 Prompt (typed only)

In the T1 bullet:

- Replace "Then give the rating in one clause." with "Then give the rating in one clause, for a rated window."
- Replace "No row for the day asked: PhotoCast has not forecast that day yet; answer answerable false and give
  no tide." with:

> Call list_windows first. For each window of the day asked that is in laterWindows, pass its id to
> look_up_place (the same ids on a spelling re-call) and give its tide from laterTides as above, saying
> PhotoCast is not giving a sky rating for it; never pass a later id to rank_spots. A day in neither:
> PhotoCast has not forecast that day yet; answer answerable false and give no tide.

Keep source lines ≤ 120 characters.

### 8.5 Tests (read `docs/engineering/test-improvement-standards.md` first)

- **`AskSnapshotBuilderTest`**:
  - An unrendered, unpassed solar window whose regions have **empty slots** but whose `tideFacts` are set
    becomes a later window with one tide per fact. It carries the region from the roster and `nearestTide`.
  - A travel day inside the rendered six becomes a later window, and is still not in `windows()`.
  - These give no later window: a passed window, a non-solar one, one with no `tideFacts`.
  - These give no tide: a fact with a null id; a location with no region, unknown or disabled.
  - A roster read that throws gives a snapshot with no later windows.
  - `windows()` is unchanged.
  - `withNames` keeps `laterWindows`.
- **`AskToolsTest`** (list_windows and rank_spots):
  - A typed result carries `laterWindows`.
  - A user-less result built from a snapshot with later windows carries no `laterWindows` key (Ready guard).
  - Empty later windows means no key.
  - rank_spots with a later id gives the T2-6 error.
  - rank_spots with an unknown id gives the old error.
- **`AskToolsLookUpPlaceTest`**:
  - **Default call:** no later tides, byte-identical to a snapshot without later windows.
  - **One later id only:** the place is matched and `laterTides` has the tide fields.
  - **Mixed ids** (a rated sunrise and a later sunset of the same day): both appear, later tides first in the
    budget, rated rows plus later tides ≤ 4.
  - **Caps:** three later ids give 2 tides.
  - **Match row and miss row:** `nearestTide` only on a miss.
  - **Out of scope:** a later tide in an out-of-scope region gives no row and reports out of scope.
  - **Spelling:** a misspelt name that matches only through a later tide is corrected (E2).
  - **Alias:** an alias matches a later tide by location id.
  - **Named area:** never carries later tides.
  - **Unknown id:** still fails as today.
  - **Worst case:** the alias shape with 2 rated rows with detail plus 2 later tides, using 60-character
    phrases, is ≤ 4,500. Print it.
  - **Cache:** a lookup with later tides marks the conversation uncacheable. Assert this through the
    accepted runnable or the engine, whichever the code allows.
- **`AskPromptBuilderTest`**:
  - The typed and thread prompts carry both new sentences, and the old T-8 sentence is gone.
  - The user-less prompt has no `laterWindows` or `laterTides`.
  - The F1 and T1 Ready guards pass unchanged.
- **`AskToolSchemasTest`**: pin both `list_windows` descriptions, with the user-less one unchanged, and the
  typed `windowIds` description. Update the with-user goldens only, and say so in §8.7.
- **`ClaudeAskEngineTest`**: follow the T1 harness (requests constrained, no broad `any()` stub, per-turn
  assertions).
  - (a) A later-day tide question: list_windows; then look_up_place with the later id plus get_hot_topics;
    then submit. The answer is not cached.
  - (b) A mixed day: list_windows; then look_up_place with both ids plus get_hot_topics; then submit.
- **Combined budget:** list_windows (with 4 later windows) + the worst lookup + hot topics at limit 2 fits
  9,000. Print it.
- **Mutation:** drop the `!tideAligned` test on later tides and watch the miss-only test fail. Back up with
  `cp`, never `git checkout --`.

### 8.6 Docs, changelog, L-check

- **`reference/ask-photocast.md`**: later windows and T2-1 to T2-12 in brief; T-8 now applies only past the
  briefing's five days.
- **Changelog**: `changelog.d/20261101-ask-later-tides.md`, headed `### Added — Ask gives the tide for days
  it does not rate yet`.
- **L-check additions** (owner-run):
  - a later day's tide;
  - a mixed Thursday (sunrise rated, sunset later);
  - a travel day;
  - "Is the causeway open Saturday?" on a later window: expect a refusal with no time;
  - "this weekend" spanning a later day and a day past T+4.

### 8.7 As built

T2 built as §8.2 to §8.6 specify, one PR, no deviation from the decisions.

- `AskSnapshot.laterWindows` (`LaterWindow`, `LaterTide`; `laterWindow(id)`; a delegating 10-argument constructor kept; `withNames` carries it). `AskSnapshotBuilder` gains a `LocationRepository` (12-argument `@Autowired` constructor; the 10 and 11-argument ones delegate with no repository and so give no later windows) and the new JPQL query `LocationRepository.findEnabledFetchingRegion` (`JOIN FETCH l.region WHERE l.enabled = true`; the inner join drops regionless places). The roster is read once per build inside the fail-soft `laterWindows(...)`.
- `AskTools`: `LaterWindowInfo`, `ListWindowsResult.laterWindows` (component-level `@JsonInclude(NON_EMPTY)`), `LaterTideInfo`, `LookUpPlaceResult.laterTides` (a 6-argument delegating constructor kept), `MAX_LATER_TIDE_ROWS` 2. `look_up_place` splits `windowIds` into rated and later ids; later tides join the same `PlaceMatch` by location id; the E2 passes (`reach`, `nearSpellings`) see later names only when a later id was requested, so a default call is unchanged even if a later-only name is a near spelling. `rank_spots` gives the T2-6 message for a later id.
- Schema and prompt as §8.2 T2-4/T2-5 and §8.4, verbatim. The prompt's source lines are wrapped at 120 characters; the rendered text is the specified sentences.
- `tideFitPhrase` on a later tide is not capped, exactly like the rated row's (the served phrase is already bounded upstream); `nearestTide` is capped at 60.
- Goldens moved: `tool-schemas-with-user.txt` and `tool-schemas-with-user-thread.txt` only (the typed `list_windows` description gained the one sentence, 145 characters each). `tool-schemas-user-less.txt` is unchanged. `AskPromptBuilderTest`'s T1 bullet assertion was updated for the two replaced sentences.
- Measured: worst lookup (alias shape, 2 rated detail rows + 2 later tides with 160-character fit phrases and 60-character `nearestTide`) 2,879 of `WORST_CASE_CHARS` 4,500, unchanged. Combined conversation: `list_windows` (6 rated + 4 later windows) 1,994 + that lookup 2,879 + `get_hot_topics` (limit 2) 776 = 5,649 of 9,000. No bound was raised.
- Engine: a later-day tide question and a mixed day both finish in 3 turns, `run.outcome().uncacheable()` true, per-turn tool results asserted.
- Mutation: dropping the `!tideAligned` test on later tides fails `laterNearestTideIsMissOnly` (restored from a `cp` backup).

### 8.8 Review record

- **Code facts**:
  - Blockers: later windows' slots are emptied by the honesty filter (→ T2-2, built from the facts); facts
    carry no region (→ the roster).
  - Corrections: unknown ids fail both tools today (→ T2-5, T2-6); null serialisation (→ `NON_EMPTY`); the
    `withNames` rebuild and the constructor sites; caching goes stale (→ T2-10); the `listWindows`
    signature; 2 to 4 later windows.
  - Confirmed: the briefing carries all 5 days with windows and facts; picks only from rendered; an answer
    with no pick is valid; the cache key is unaffected; the budget is about 6.8k.
- **Reader**:
  - The same blocker.
  - list_windows first; the "rating in one clause" conflict.
  - Per-window, not per-day, wording (mixed days).
  - "not giving a sky rating", not "not rated yet".
  - Never cached.
  - Travel days get the tide.
  - The added L-checks.
