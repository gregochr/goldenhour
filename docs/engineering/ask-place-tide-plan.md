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
