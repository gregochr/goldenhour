# Ask: richer "why" answers and closest-spelling place lookup — build plan

Status: **plan, reviewed** (2026-11-01, three adversarial lenses: code facts, fuzzy matching, answer
quality; §9 lists what each changed). Two backend phases, one PR each, built by a Sonnet subagent from this
file: **E1** (the forecast's reason in `look_up_place`), then **E2** (closest spelling), branched from `main`
after E1 merges. Both touch `AskTools`, `AskPromptBuilder.PLACE_FIT_AFTER` and the lookup tests, so they run in
sequence. No frontend change, no migration, no new endpoint.

## §1 The gap

The landing page sells Ask with *"Why does a location look promising? What are the cloud layers doing? Is it a
fiery-sunrise morning or a softer golden-hour one?"*.

`look_up_place` (`ask-place-lookup-plan.md`) returns a place's served rating, verdict, headline (≤120
characters) and tide fit, but **not the evaluation's reason**. "Why is Bamburgh 4★ tonight?" can only be
answered by paraphrasing a 4–9-word headline. The reason is already on the served slot, and
`AskSnapshotBuilder.toSlot` drops it:

- `claudeSummary`, `skyRating`, `fierySkyPotential` and `goldenHourPotential`, all from the same
  `cached_evaluation` row as the rating, and all cleared on retraction (`BriefingRegionEvaluationRollup`
  :407-423);
- the snapshot is built from the un-stripped briefing (`getCachedBriefingForApi`, before role shaping).

The summary is the sky prompt's prose about cloud structure, e.g. *"Thick low cloud at the western horizon
with only a narrow strip of clear sky above it."*

**Typos.** Matching is whole words as a contiguous run (`PlaceNames`). "Bamborough", "Witby" and "Alnmuth" find
nothing, and the answer becomes *Not in the forecast*. That is false: the place was misspelt, not missing.

**Not in scope here.** The landing questions are often asked with no place named ("is it a fiery morning
tomorrow?"). Those go to `rank_spots` and `list_windows`, and neither phase changes them. That gap needs either
a later phase (light character on `rank_spots`' top rows) or a copy edit. Follow-up, owner's call.

## §2 Decisions

| # | Decision |
|---|---|
| X1 | The explanation comes from **served evaluation fields only**: `claudeSummary`, `skyRating`, `fierySkyPotential`, `goldenHourPotential`. |
| X2 | **No raw weather numbers.** Cloud %, visibility and humidity are the triage point forecast, not the directional horizon sample the rating used, and may come from a later refresh. No UI shows them, and `cached_evaluation` holds no directional cloud data. |
| X3 | **The summary describes the sky; the star may also carry the tide.** `claudeRating` is `RatingCombiner`'s sky-and-tide figure (`ForecastResultHandler` :575-606). When `skyRating` is above `rating`, the answer says the light looks good and the tide pulls it down, using `tideFitPhrase`. |
| X4 | **Fiery Sky and Golden Hour are named the way the Pro UI names them** (`ScoreBar`, `WindowSpotPeek`), and the numbers may be quoted out of 100. The model **never ranks one against the other**: they are calibrated differently (clear sky is fiery 20–40 but golden 65–85, `PromptBuilder` :262-265), so "more fiery than golden" would be an unlicensed derivation. It mentions them **only when the question asks about the light's character**. A served fiery/golden label is a possible later backend phase, not this one. |
| X5 | Which rows carry detail: **only the rows of the one place the answer is about**, i.e. the exact match, or the only place matched. Up to `MAX_DETAIL_ROWS = 4` rows, so a weekend (two days, sunrise and sunset) is covered. |
| X6 | Rows that carry **no detail**: `UNRATED`; `NEIGHBOUR_OUTLIER` (the product disowns that star, see `THREAD_RULES`); any row whose summary is `ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY` (it names "Claude"). `CANOPY` rows carry **the summary only**: a wood's summary describes the light under the trees, with the polarity reversed. |
| X7 | Closest spelling **corrects the spelling, then reruns the normal lookup** with the corrected words. So a misspelling of a name shared by several places ("Witby") finds all of them, an area name ("Hadrians Wal") reaches the area branch, and `matchedAlias`, `MAX_PLACE_MATCHES` and the scope note all behave as they do today. |
| X8 | Fuzzy compares the query **from the start of a name** (the name's first word onward, and the same for each alias and area name), and the **first letter must match**. Comparing any run inside a name made "bamborough" match Flamborough Head (2 edits) ahead of Bamburgh (3), and "moors", "Alnwick", "Hexham" and "Newcastle" each match a real location. |
| X9 | Fuzzy runs **only when the words match nothing exactly**: no place, alias or area anywhere in the snapshot (every window, in scope or not). A correct spelling is never "corrected" into a different place. |
| X10 | A guessed answer always **opens "Assuming you mean <name>,"**. |
| X11 | Caching does not change: explanation answers stay uncached. |
| X12 | **Turn budget.** `max-turns` is 4. A clear correction costs no extra turn. A tie (suggestions, then the model re-calls) uses list_windows, look_up_place, look_up_place and submit_answer, which is all 4, so that question cannot also reach `rank_spots`. Accepted, because X7 and the margin rule make ties rare. |

## §3 What must not change

- `rank_spots` (its output byte-identical), `isPickEligible`, `candidates`, `isHeldBack`, `resolvePick`, the
  validator's pick rules and `AskEvidence.pairs`. **A looked-up place, guessed or not, is never a pick.**
- **Ready**: no `look_up_place` (`ClaudeAskEngine` :227, `AskToolSchemas` :82) and no `PLACE_FIT_AFTER`
  (`AskPromptBuilder` :285). The Ready prompt bytes are already pinned at `AskPromptBuilderTest` :120-122, and
  that test must pass unchanged.
- **Scope**: an out-of-scope place is never named, as a guess, a suggestion or a row.
- `PlaceNames.words` / `containsRun` behaviour (also used by `NameNamespaceGuard` and `AreaService`).
- `src/test/java/.../regression/` assertions.
- **Ask stays Pro/Admin only.** The potentials are Pro-only on the client. Add a test asserting that
  `POST /api/ask` from a Lite user is still 403 (reuse an existing one if it exists; name it in the PR).

## §4 E1 — the forecast's reason in `look_up_place`

### 4.1 Snapshot

`AskSnapshot.Slot` gains trailing `String summary`, `Integer skyRating`, `Integer fierySky`,
`Integer goldenHour`. Add a delegating constructor with the current 10 arguments, so the existing 9- and
10-argument call sites (5 in `AskReadModelTest`, 2 in `AskToolsLookUpPlaceTest`) compile unchanged.
`AskSnapshotBuilder.toSlot` fills the new fields from `claudeSummary()`, `skyRating()`,
`fierySkyPotential()` and `goldenHourPotential()`.

### 4.2 Result shape

```java
/** The forecast's own reason for a looked-up rating (ask-place-explain plan E1). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceDetail(String summary, Integer skyRating, Integer fierySky, Integer goldenHour) { }
```

`PlaceInfo` gains a trailing `PlaceDetail detail` (null-omitted). A new 14-argument constructor delegates with
`null`, and the 13-argument one keeps working.

### 4.3 Rows and budget

- **Detail rows.** Detail goes on the rows of the one place the answer is about (X5): the first ordered place
  when it is exact, or when it is the only place matched. That is at most `MAX_DETAIL_ROWS = 4` rows, in
  window order, following X6 for which fields each status gets.
- **Summary.** Before capping, replace `"` with `'` and drop `\`, so that JSON escaping cannot grow it. Then
  cap it with `cap()` at `SUMMARY_CAP = 200`.
- **Row cap.** When any row carries detail, the place-row cap is `MAX_PLACE_ROWS_WITH_DETAIL = 4`, not 6. The
  existing "Showing N of M" note uses whichever cap applied.
- **Why the cap drops (measured).**
  - Today's worst cases are 3,785 (6 windows), 2,503 (all reasons) and **4,199** (alias,
    `aliasPlaceWorstCaseFitsHalfTheCap`).
  - One detail row costs about 300 characters, so six rows of anything with detail would not fit.
- **The bound stays at 4,500** (`WORST_CASE_CHARS`). Add worst-case tests for the 6-window and alias shapes:
  long names, 40-character aliases, 4 detail rows, 200-character summaries made of quotes and backslashes.
  If any of them exceeds 4,500, **stop and report the figures**; do not tune further.

### 4.4 Prompt (typed only, `PLACE_FIT_AFTER`)

Replace *"Answer from what it returns: the rating, verdict, headline and tide fit."* with:

> Answer from what it returns: the rating, verdict, headline and tide fit, and a row's detail when it has one.
> detail.summary describes the sky (the light under the trees for a wood): give the rating and its reason
> from it, in plain words, without its distances or figures. When detail.skyRating is above the rating, the
> sky looks better than the star: say the tide pulled it down, using the tide fit. Mention detail.fierySky and
> detail.goldenHour only when the reader asks what kind of light to expect: call them Fiery Sky and Golden
> Hour, out of 100, and never say one is stronger than the other. Never add a reason the result does not
> give. Order: the rating and its reason, then why PhotoCast is or is not suggesting it, then the rest. The
> summary is cut at 60 words, so keep within that.

The `look_up_place` schema description in `AskToolSchemas` gains *"and, for the place asked about, the
forecast's reason"* after "headline".

### 4.5 Tests (read `docs/engineering/test-improvement-standards.md` first)

- **`toSlot`**: all four fields come through, and a retracted slot carries none.
- **`AskToolsLookUpPlaceTest`**:
  - one place and one window gives the full detail;
  - coastal sky 4, combined 3: `skyRating` 4, rating 3;
  - the summary is capped and has its quotes swapped;
  - five windows: four rows, all with detail, and the note says "Showing 4 of 5";
  - two non-exact places: no detail on either. Exact plus partial: detail on the exact place only;
  - `UNRATED`, `NEIGHBOUR_OUTLIER` and a `SKY_NOT_FORECAST_SUMMARY` row get no detail; `CANOPY` gets the
    summary only;
  - all fields null means no `detail` key in `result.content()`;
  - the worst cases in §4.3.
- **`rank_spots`**: content is byte-identical for a slot with and without the new fields.
- **`AskPromptBuilderTest`**: the typed prompt contains the new sentence, and the Ready assertion is unchanged.
- **Mutation**: remove the X5 "one place" rule and see the two-place test fail (back up with `cp`).

### 4.6 Docs and changelog

- `reference/ask-photocast.md`, the `look_up_place` paragraph: the detail fields, which rows get them, the row
  cap with detail, and X2–X4.
- `changelog.d/20261101-ask-place-reason.md`: `### Added — Ask explains a place's rating from the forecast's
  own reason`.

## §5 E2 — closest spelling

### 5.1 Distance (`PlaceNames`, new functions only)

```java
/** Edits from the query's letters to the closest leading run of the name's words, or MAX_VALUE. */
public static int prefixDistance(List<String> name, List<String> query)
```

- **Inputs.** Join the query's words without spaces. For each leading run of the name (words 1..k, for every
  k), join it the same way.
- **First letter.** If the first letters differ, return `Integer.MAX_VALUE`.
- **Result.** Otherwise return the least optimal-string-alignment distance (Levenshtein plus adjacent
  transposition) over the leading runs.
- **What this handles.** Joining handles split and joined words ("dunstan burgh" ↔ "Dunstanburgh"). The
  leading runs handle "Bamburgh" ↔ "Bamburgh Castle".

```java
/** The most edits allowed for a query of this many letters. */
public static int allowedEdits(int letters)
```

The limits are 0 for ≤3 letters, 1 for 4–5, 2 for 6–8 and 3 for ≥9.

### 5.2 Where it runs (`AskTools.lookUpPlace`)

1. **Exact pass** (today), then **area pass** (today). If either finds anything, stop.
2. **X9 check.** Add a new loop over **every window** of the snapshot. If the wanted words `matchPlace` any
   place or alias anywhere, return today's note: `PLACE_OUT_OF_SCOPE_NOTE` when every such hit is out of
   scope, otherwise `NO_SUCH_PLACE_NOTE`. Do the same check for area names.
3. **Correct.** Score every distinct spelling in the snapshot: each place name, each alias and each area name
   and alias, in scope or not. A spelling's score is its least `prefixDistance` with this query. Keep the
   spellings within `allowedEdits`. A spelling's text is its matched leading run, joined with spaces (e.g.
   "bamburgh", not "bamburgh castle"), and spellings with equal text are one spelling.
4. **Clear winner.** Exactly one spelling text is at the least distance d, and no other is within d+1 when
   d ≥ 2. Re-enter step 1 with the winner's words in place of the reader's (one level only; never
   fuzzy again) and set `LookUpPlaceResult.closestTo` to the reader's spelling. If that re-entered lookup
   reaches only out-of-scope places, return `PLACE_OUT_OF_SCOPE_NOTE` with **no** `closestTo`, so no name
   leaks. If it finds the place but not in the requested windows, return `closestTo` with no places and
   `NO_SUCH_PLACE_NOTE`.
5. **Tie.** Otherwise return no places and `suggestions`: up to `MAX_SPELLING_SUGGESTIONS = 3` in-scope place
   names reached by the tied spellings, ordered by distance and then case-insensitively. Add the note
   *"No place has that spelling; closest names are in suggestions."* If none of them is in scope, return
   `PLACE_OUT_OF_SCOPE_NOTE`.
6. **Nothing within allowance**: return `NO_SUCH_PLACE_NOTE` (today's behaviour).
7. **Extra words.** A query with extra words ("Bamburgh beach tonight", "Sycamore Gap tree") usually matches
   nothing exact or fuzzy and behaves as it does today. That is accepted.

`LookUpPlaceResult` gains `String closestTo` and `List<String> suggestions`, both null-omitted. A new
4-argument constructor delegates with `null`s. Nothing is added to evidence.

### 5.3 Prompt (typed only)

**Reword** the existing no-match sentence (`AskPromptBuilder` :49-52, pinned at `AskPromptBuilderTest`
:113-116, not a regression test). Its condition becomes *"returns no places, no named area, no closestTo and no
suggestions"*. Update that assertion. Then append:

> If the result has closestTo, the reader's spelling matched no place and the result is for the closest
> name: start the answer "Assuming you mean <name>,". If it has suggestions, call look_up_place again with
> the one the reader plainly meant and start the answer the same way. If none fits, answer answerable false
> and name the suggestions as the places they may mean.

### 5.4 Tests

All `AskToolsLookUpPlaceTest` cases run on a **realistic name fixture**: Bamburgh, Bamburgh Beach, Bamburgh
Castle, Flamborough Head, Scarborough (if configured; otherwise add it as a decoy), Whitby Abbey, Whitby Piers,
Whitby West Cliff, Saltwick Bay, Alnmouth, Alnmouth Estuary, Seaham, Grey Moor, Orrest Head, Hartlepool
Headland, Stanley Gill Force, and the area Hadrian's Wall with one member place.

- **`PlaceNamesTest`**:
  - `prefixDistance`: "bamborough"/Bamburgh 3, "bamborough"/Flamborough Head `MAX_VALUE`, "witby"/Whitby
    Abbey 1, "bmaburgh" 1 (transposition), "dunstan burgh"/Dunstanburgh 0, "stanley gil force" 1, empty
    query;
  - `allowedEdits` at the boundaries 3, 4, 5, 6, 8 and 9.
- **`AskToolsLookUpPlaceTest`**:
  - "Bamborough": all three Bamburgh places, `closestTo` set, Flamborough absent;
  - "Witby": all three Whitby rows;
  - "moors", "Newcastle", "Alnwick", "Hexham": `NO_SUCH_PLACE_NOTE`;
  - X9: "Alnmouth" existing only in an unrequested window gives today's note and no `closestTo`, even with
    "Almouth Bay" in the requested window;
  - a margin tie gives `suggestions`;
  - a three-letter query is never fuzzy;
  - an out-of-scope misspelling gives `PLACE_OUT_OF_SCOPE_NOTE`, no `closestTo` and no name anywhere in
    `content()`;
  - "Hadrians Wal" takes the area branch with `closestTo`;
  - "Gill Force" no longer matches Stanley Gill Force. This pins the leading-run choice;
  - an alias misspelling sets `matchedAlias`;
  - evidence is empty after a guessed lookup.
- **Stub-engine test**: a suggestions result followed by a re-call finishes within 4 turns.
- **`AskPromptBuilderTest`**: the reworded sentence and the appended sentence are present, and the Ready
  prompt is unchanged.
- **Mutation**: drop the first-letter rule and see the Flamborough test fail.

### 5.5 Docs and changelog

- `ask-photocast.md`: add the fallback to the matching sentence (X7–X10, X12, the thresholds).
- `changelog.d/20261101-ask-place-closest-spelling.md`: `### Added — Ask finds a place when its name is
  misspelt`.

## §6 L-check (owner-run, costs money, after E2)

Run locally with `photocast.ask.enabled` on and `stub: false`. Ask:

1. "Why is <a 4★ place> 4 stars tonight?"
2. "What are the cloud layers doing at <place> tomorrow morning?"
3. "Is Bamborough good this weekend?" Expect "Assuming you mean Bamburgh,", with the reason on each weekend
   row.
4. "Why is <coastal place, sky above combined> only 3★?" Expect "the tide pulls it down".
5. "Is it a fiery sunset at <place> tonight?" Expect Fiery Sky and Golden Hour by name, with no ranking of
   one against the other.

In every answer, check for figures copied from the summary, invented cloud numbers, and a cut-off end.

## §7 Subagent brief (per phase)

Sonnet, in its own worktree, with `model: "sonnet"`. The brief is §2–§3 plus that phase's section, the files
it names, and the listed tests.

- **Gates**: the backend build ladder (compile, the touched test classes, checkstyle, then full `clean verify`
  with the integration exclusion), gated on the exit code. No frontend gates.
- **Changelog**: the file named in the phase section.
- **Commit**: `feat(ask): …`.
- **Report**: the measured worst-case figures. Stop and report if §4.3's bound fails.

## §8 As built

**E1 (built 2026-11-01).** As specified in §4, plus one narrowing: a row carries detail only when it has a served
summary (the scores explain nothing without the reason they came with, and a fixture slot with only a sky score
would otherwise have dropped the cap to 4 rows for no gain). `SKY_NOT_FORECAST_SUMMARY` was made `public` rather
than duplicated. Measured worst cases (limit 4,500, unchanged): 6 windows with detail 3,666; 40-character alias
with detail 3,942; the existing no-detail cases are untouched (3,785, 2,503, 4,199). The Lite 403 on `POST
/api/ask` is `AskAdmissionTest.liteIsRefusedBeforeTheLimiterCounts`. Mutation check: forcing "about one place" to
true fails `twoNonExactPlacesGetNoDetail`.

## §9 Review record (2026-11-01)

- **Code facts**:
  - confirmed: the un-stripped snapshot, field-by-field `rank_spots`, Ready untouched, the constructors;
  - found: the alias worst case overflows (→ §4.3 row cap and quote-safe summary); the no-match prompt
    sentence contradicts E2 (→ §5.3); the 60-word answer cut (→ §4.4); the 4-turn budget (→ X12); the
    collision with "closest" (→ schema wording); X9 needs a new loop (→ §5.2 step 2).
- **Fuzzy matching**: the Flamborough wrong guess and run-anywhere false positives (→ X8); ties between
  places that share a name (→ X7); a margin (→ step 4); the scope hole (→ step 4); near-miss area names
  (→ X7, test).
- **Answer quality**: the summary is sky-only while the star is combined (→ X3, `skyRating`); the "Claude"
  leak (→ X6); canopy and outlier polarity (→ X6); the fiery/golden derivation (→ X4); answer order and
  length (→ §4.4); unserved place-less questions (→ §1); detail landing on the wrong place (→ X5).
