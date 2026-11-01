# Ask: light character for questions that name no place — build plan

Status: **plan, reviewed** (2026-11-01, two lenses: code facts, answer quality; §7 lists what each changed).
One backend phase (**F1**), one PR, built by a Sonnet subagent from this file. It follows
`ask-place-explain-plan.md`, where E1 gave `look_up_place` the served Fiery Sky and Golden Hour for a named
place. No frontend change, no migration, no new endpoint.

## §1 The gap

The landing page (`landing/index.html`, "Ask PhotoCast") promises: *"Is it a fiery-sunrise morning or a
softer golden-hour one?"*. That question rarely names a place: "Is tomorrow a fiery morning?", "Will tonight's
sunset be colourful?". It runs the general investigation, `list_windows` then `rank_spots`
(`AskPromptBuilder.RULES`). Neither tool returns Fiery Sky or Golden Hour, so Ask answers from stars alone.

`AskSnapshot.Slot` already carries `fierySky` and `goldenHour` (E1). `rank_spots`' `SpotInfo` drops them. Its
`headline` (≤120 characters) is the one served piece of wording about a spot's light, and it is already
returned.

## §2 Decisions

| # | Decision |
|---|---|
| F-1 | `SpotInfo` gains `fierySky` and `goldenHour` (served 0–100, null-omitted). **Typed conversations only** (`user.hasUser()`; false only for `AskUserContext.userLess()`, whose only caller is the Ready precompute). Ready `rank_spots` output, Ready prompt and Ready answers do not change. |
| F-2 | **No new derivation.** No threshold, no label, no window-level count. The evaluation prompt's bands (`PromptBuilder` :264-266) are instructions to the scorer and are never quoted. |
| F-3 | **The character is said in the served headline's words; the figures are support.** "Bamburgh 4★: a fiery canvas over the sea (Fiery Sky 78, Golden Hour 60)". The model never answers yes or no to "fiery?" from a figure alone, and never puts a word like strong, high, low, fiery or soft on a number. It never ranks one score against the other (different calibrations, E1 X4). |
| F-4 | **"Fiery or softer?" questions** (the landing line) are answered per place from the headline's words, with both figures. Where the headlines do not say, the answer says the forecast does not rank the two and gives both figures. |
| F-5 | **Only the places returned.** `rank_spots` returns the top places by stars, not by Fiery Sky. So the figures describe those places only: never "the fieriest" or "the most colourful", and never the whole window from a few places. |
| F-6 | **Trigger by intent.** The figures are mentioned only when the reader asks what the colour or light will be like (fiery, colourful, vivid, dramatic, red or pink sky, afterglow, soft, glow). A where-question ("best place for golden hour tomorrow?") is answered as today, with no figures. |
| F-7 | **Length.** The answer has the spread first (RULES, unchanged), then at most two places with figures in the summary. A pick's "why" may quote "Fiery Sky N" as its sky fact. |
| F-8 | **A stay-home window** with no game-on place stops after `list_windows` (RULES), so the answer has no figures. That is correct: it says stay home. |
| F-9 | **Caching**: the freshness re-check compares only the picks' rating and verdict. A re-evaluation that moves Fiery Sky but not the star, or a figure quoted for a place that is not a pick, can stay in a cached answer until the TTL (30 min) or a new build. This is the same residual as headlines and quoted stars today. No change. |
| F-10 | **Follow-up pick lines** (`AskThreadContext.pickLineBody`) are unchanged and carry no figures. A follow-up such as "which of those is fierier?" gets them by calling `rank_spots` again. The bullet says so. |
| F-11 | **The Ready `AM_OR_PM` answer stays on stars only.** "Fiery" and "colourful" are not in the Ready vocabulary (`ReadyIntentRules`), so these questions always take the typed path. This is a known difference between the two. |
| F-12 | **The landing copy is unchanged.** E1 answers the cloud-layers line for a named place, and F-4 answers the fiery-or-softer line. |

## §3 What must not change

- `rank_spots` ordering, filters, limits, `pick` values, notes and evidence.
- **Ready**: tool output, prompt, freshness.
- `look_up_place`, the night tool, `list_windows`, and `AskThreadContext` pick lines (their golden test at
  `AskPromptBuilderTest` :296 passes unchanged).
- `src/test/java/.../regression/` assertions.
- Ask stays Pro/Admin only (`AskAdmissionTest.liteIsRefusedBeforeTheLimiterCounts`).

## §4 F1 — build

### 4.1 `SpotInfo`

Append `Integer fierySky, Integer goldenHour` to `AskTools.SpotInfo`, which is already
`@JsonInclude(NON_NULL)`. `spotInfo()` (:2057) is the only builder: fill the two fields from the slot **only
when `user.hasUser()`**, otherwise `null`. Update the two call sites, `AskTools` :2060 and `AskToolsTest`
:1376. No delegating constructor is needed. `AskThreadContext` and `StubAskEngine` use `spotInfo()` but write no
new field into prose; leave them.

### 4.2 Budget (measure and report; there is no worst-case test today)

- **Assertions.** Add a test of a typed 8-row `rank_spots` call (`tools(snap, USER, TestScopes.of())`):
  - headlines at 120 characters and both scores at 100;
  - assert its content is under `RESULT_CHAR_CAP / 2`;
  - assert it exceeds the same call with null scores by at most 8 × 32.
- **Measure and print.** Measure a typed conversation of `list_windows` → `rank_spots` (8) → `look_up_place`
  with detail and print its total.
- **Bounds.** Do not raise any bound. If a bound fails, stop and report.

### 4.3 Prompt and schema (typed only)

Append this as a new bullet at the **end of `PLACE_FIT_AFTER`**. It is typed-only, sits beside E1's light
rule, and `THREAD_RULES` is added after it, so follow-ups get it too. Keep source lines ≤120 characters:

> - When the reader asks what the light or colour will be like and names no place ("is tomorrow a fiery
> morning?", "fiery or soft tonight?"), give the spread first as for any window, then rank_spots. Describe
> each place's light in its headline's words and add its fierySky and goldenHour as Fiery Sky N and Golden
> Hour N, out of 100, for at most two places. Never answer yes or no from a figure alone, never call a figure
> strong, high, low, fiery or soft, and never say one is stronger than the other: if the headlines do not say
> which, say the forecast does not rank them. The figures are for the places returned only: never call one the
> fieriest, or the whole window fiery or soft. A pick's why may quote Fiery Sky N as its sky fact. Leave the
> figures out of a where-question ("best place for golden hour?"). To compare earlier picks' light in a
> follow-up, call rank_spots again.

`AskToolSchemas.rankSpots(withUser, …)`: when `withUser`, append "Each spot also has its Fiery Sky and Golden
Hour (0-100)." to the description. Use `withUser ? base + extra : base`, and leave the user-less text
byte-identical.

### 4.4 Tests (read `docs/engineering/test-improvement-standards.md` first)

- **`AskFixtures`**: add `withPotentials(slot, fiery, golden)` (or equivalent) so a fixture slot can carry
  both scores.
- **`AskToolsTest`**:
  - a typed result carries both fields from the slot;
  - **Ready absence**: a slot with both scores set, run through the user-less `tools(snapshot)`, puts neither
    key in `content()`;
  - null scores mean no keys;
  - ordering and picks are identical typed and user-less;
  - the §4.2 budget test.
- **`AskPromptBuilderTest`**:
  - the typed prompt contains the new bullet, and so does a follow-up thread's prompt;
  - **new Ready guard**: the user-less `systemPrompt(...)` contains none of `fierySky`, `Fiery Sky` or
    `Golden Hour`;
  - existing assertions pass unchanged.
- **`AskToolSchemasTest`**: pin both versions of the `rank_spots` description, and pin the user-less one as
  unchanged.
- **Mutation**: drop the `hasUser()` gate and see the Ready-absence test fail (back up with `cp`, never
  `git checkout --`).

### 4.5 Docs and changelog

- `reference/ask-photocast.md`: add the two typed-only fields and F-3 to F-11, in brief, to the `rank_spots`
  description.
- `changelog.d/20261101-ask-light-character.md`: `### Added — Ask describes the kind of light for questions
  that name no place`.
- Fill in §6 "As built".

## §5 L-check (owner-run, costs money)

Run with `photocast.ask.enabled` on and `stub: false`:

1. "Is tomorrow a fiery morning?" Expect the spread, then two places described in their headline words with
   the figures.
2. "Fiery sunrise or a softer one tomorrow?"
3. "Will tonight's sunset be colourful?"
4. "Where's best tomorrow?" and "best place for golden hour tomorrow?" Expect no figures.
5. "Where's the most fiery tomorrow?" Expect no "fieriest" claim.
6. On a window with pockets: "Is tonight going to be fiery?" Expect the spread, then the exceptions named as
   exceptions.
7. On a window with no game-on place: expect stay home, with no figures.

For each answer, check:
- no yes or no answer rests on a figure alone;
- no adjective is put on a number;
- no comparison between Fiery Sky and Golden Hour;
- nothing is cut off at the 60-word limit.

## §6 As built

Built as one PR.

- `AskTools.SpotInfo` gained trailing `Integer fierySky, Integer goldenHour`; `spotInfo()` fills them only when
  `user.hasUser()`. The new bullet is the last in `PLACE_FIT_AFTER`; `AskToolSchemas.rankSpots` appends the
  sentence to the description when `withUser`.
- Tests: `AskFixtures.withPotentials`; typed/user-less/null/ordering/budget tests in `AskToolsTest`; the light
  rule (typed, thread) and the Ready guard in `AskPromptBuilderTest`; the description pins in
  `AskToolSchemasTest`. Mutation (dropping the `hasUser()` gate) fails `rankSpots_userLessNeverCarriesTheScores`.
- Two existing assertions had to move, both an inevitable consequence of the change: the two with-user schema goldens
  (`tool-schemas-with-user.txt`, `-thread.txt`; the user-less golden is untouched) and
  `AskToolsLookUpPlaceTest.rankSpotsIgnoresTheReasonFields` (a typed result now also carries the two scores; it asserts
  that is the only difference).
- Budget (measured): typed `rank_spots(8)` at 120-char headlines and both scores 100 is 2923 characters against
  2667 without, a delta of 256 (exactly the 8 x 32 bound), well under `RESULT_CHAR_CAP / 2` (4500). A typed
  `list_windows` + `rank_spots(8)` + `look_up_place` with detail totals 3751 of 9000. No bound raised.

## §7 Review record (2026-11-01)

- **Code facts**: no blockers.
  - Found: the Ready prompt is not pinned byte for byte (→ the new Ready guard test); no fixture for the
    scores (→ `withPotentials`); follow-up pick lines lack the figures (→ F-10); no worst-case budget test
    (→ §4.2); the bullet's placement and the schema split (→ §4.3).
  - Confirmed: `hasUser()` is false only for Ready; `SpotInfo` is `NON_NULL` and built in one place; nothing
    persists it; the typed cache never reaches Ready.
- **Answer quality**:
  - Found: the landing question is a comparison (→ F-3, F-4); a yes or no needs a threshold (→ F-3,
    headline words); the top five are by stars (→ F-5); stay-home windows (→ F-8, L-check 7); length (→ F-7);
    trigger words (→ F-6).
  - Landing copy edit considered and not taken (→ F-12).
