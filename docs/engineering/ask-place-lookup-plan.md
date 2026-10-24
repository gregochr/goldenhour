# Ask: explaining a named place's rating — build plan

Status: **plan**, 2026-10-22. One backend phase (L1), then an owner-run prompt check (L2). Built by a
Sonnet subagent from this file alone; §6 is its brief.

## §1 The defect

A reader looked at the Map, saw **Alnmouth 4★** for Saturday sunrise, and asked Ask "Why is Alnmouth a 4 on
Saturday morning?". Ask answered **"Not in the forecast — PhotoCast doesn't have: Alnmouth's rating for this
window"**. A question just before it ("how is Hartlepool Headland this weekend?") was told Northumberland &
Tyneside is "broadly game on, but no spot there rates 3 stars or better", while the Map showed five 4★
places there.

**Cause (read from the code, not confirmed against production).** Ask has exactly one tool that returns a
solar place's rating: `rank_spots`. It draws only from `AskSnapshot.candidates(window, scope)`, which keeps
only **pick-eligible** slots (`AskSnapshot.isPickEligible`):

- the region must be `pickEligible()`, which since thin-sample B1 (#1282, released in v2.30.3) means a
  *sufficient* sample only. A forced sample sets the region's verdict but takes no pick (thin-sample plan
  §5 Q3);
- the slot must not be a neighbour outlier (neighbour-guard G2, #1216);
- rating ≥ 3, not canopy, has an id and a name.

Saturday-sunrise Northumberland & Tyneside was hatched on the Map (thin sample). Every slot in it therefore
fell out of `rank_spots`, while `list_windows` still reported the region's verdict. The model had no way to
see Alnmouth's rating, so it told the truth about what its tools returned. The neighbour guard is not the
cause here: Alnmouth sits among four other 4★ places, and a held-back place makes `rank_spots` add its "N
places … not offered" note, which would have produced different wording.

**The design gap.** Both rules answer *"should PhotoCast send you there?"*. Ask uses them to decide
*"may PhotoCast talk about it at all?"*. A reader asking why a place the Map shows as 4★ is 4★ wants an
explanation, not a recommendation, and the Map already shows the star.

## §2 Owner decisions (2026-10-22)

| # | Decision |
|---|---|
| D1 | A question about one named place's rating is an **explanation**, not a pick. Ask may state that place's served rating and reason even when the place is not pick-eligible. |
| D2 | This **includes a neighbour outlier**. It reverses part of G2 for this one route: G2 said Ask gives no name or star of a held-back place. Now, when the reader names the place, Ask may give its star and must say it is rated well above the places beside it, so it is not a suggestion. `rank_spots`'s held-back note (count only, no name) is unchanged. |
| D3 | Recommendations do not change: `rank_spots`, `isPickEligible`, the validator's pick rules, the BEST anchor, Ready answers, the Plan and Map picks. |
| D4 | The `*` / `★` / `☆` characters are allowed in a typed question (shipped separately, `d24b4002`). |

## §3 What must not change

- `AskSnapshot.isPickEligible`, `candidates`, `isHeldBack`, `resolvePick` and `rank_spots`' results.
- **A looked-up place is never a pick.** The new tool adds **nothing** to `AskEvidence.pairs`, so the
  validator drops any pick the model tries to make from it (`evidence.offers` fails, and `resolvePick`
  refuses a non-eligible slot anyway: two guards). If the model wants to recommend a place, it calls
  `rank_spots` as today.
- Ready answers: the Ready precompute's conversation does not get the tool (§4.3), so Ready prompt bytes
  and Ready behaviour are unchanged.
- The scope rule: a question scoped to regions only sees places in those regions.
- The aurora gate (`canSeeAurora`): this tool is solar only.
- No frontend change. An `answerable: true` answer with no picks and no events already renders as a summary
  (window-context W1). Confirm this in L1 by reading the component (§4.6), and stop and report if it is
  not so.
- Never change assertions in `src/test/java/.../regression/` (CLAUDE.md).

## §4 L1 — the `look_up_place` tool (backend)

### 4.1 `AskTools.lookUpPlace(LookUpPlaceArgs)`

```java
public record LookUpPlaceArgs(String name, List<String> windowIds) { }
```

- `name` is required: blank or missing → `fail("look_up_place", "name is required.")`.
- `windowIds` is optional. Null or empty means every solar window in the snapshot. An unknown id → the
  same "Unknown window id … Call list_windows for the valid ids." error `rank_spots` gives.

**Matching** (pure, static and package-private so it can be unit-tested):

- Normalise both the query and each slot's `name()` to lower case (root locale), with apostrophes (`'`, `’`)
  removed and every other non-letter/digit turned into a space, then split into words. Do **not** reuse
  `AskQuestionSanitiser.normalise`: its filler-word list would drop "the" and "a" from place names. Write
  a small private tokeniser beside the matcher.
- A slot matches when its words contain the query's words as a **contiguous run**. "alnmouth" matches
  "Alnmouth" and "St Cuthbert's Cross, Alnmouth". "cuthberts cross" matches the second. "mouth" matches
  neither (whole words only).
- Exact matches (the word lists are equal) come first, then the rest by name. Distinct locations are
  capped at `MAX_PLACE_MATCHES = 4`, and each location carries every requested window it appears in. When
  more locations match, `note` says "N more places match; use a longer name."

**Scope.** Walk `window.regions()`, skipping regions `!scope.contains(region.name())`. If nothing in scope
matches but something out of scope does, return no places and the note "That place is outside the
regions this question is about." Never return the out-of-scope place's rating.

**Result** (`@JsonInclude(NON_NULL)`, the `RankSpotsResult` pattern):

```java
public record LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note) { }
public record PlaceInfo(long locationId, String name, String region, String windowId, String day,
        String event, Integer rating, String verdict, String headline, String tideState,
        Boolean tideAligned, String tideFitPhrase, String status) { }
```

**Payload shape (review fix, 2026-10-22).** `reasons` maps each status present in `places` to its fixed text once, not on every row; `BELOW_GAME_ON` and `PICK` have none. `places` holds at most `MAX_PLACE_ROWS` (6) rows: the exact match's first, then the others', each place's in window order; a cut adds "Showing N of M place-windows; pass windowIds to narrow." to `note`. The conversation's 9,000-character cap is shared with `list_windows`, so one lookup is held to 4,500 (measured worst case 3,785).

`day` and `event` are formatted as `windowInfo` does (`dayWord`, the event name). Rating, verdict,
headline and tide come straight off `AskSnapshot.Slot`, so they are the served figures.

**`status`**, one per (place, window), tested in this order:

| status | when | `reasons` entry (fixed text, ours) |
|---|---|---|
| `UNRATED` | `rating == null` or not `RatingValidator.isInRange` | `"Not rated for this window."` plus the region's `coverageReason` phrase when it is non-null (reuse whatever wording `list_windows`' spread already uses for stood-down / not scored; do not invent new text) |
| `CANOPY` | `slot.canopy()` | `"A woodland canopy rating: how good the light under the trees is, not the sky."` (check the canopy wording the Plan / Map already use and match it) |
| `BELOW_GAME_ON` | `rating < AskSnapshot.MIN_PICK_RATING` (tested before the outlier and region tests: a 2-star place is below the floor whatever its region, and "too few were rated" would imply it might otherwise be recommended) | null |
| `NEIGHBOUR_OUTLIER` | `slot.neighbourOutlier()` (after the rating floor) | `"Rated well above the places beside it, so PhotoCast is not suggesting it."` |
| `THIN_SAMPLE` | `!region.pickEligible()` | `"Too few places in this region were rated to recommend one."` |
| `PICK` | `AskSnapshot.isPickEligible(region, slot)` | null |

Add a final `else` that throws `IllegalStateException`, so a future pick rule the table misses fails a
test instead of silently returning `PICK`. Write a test that every slot classified `PICK` is
`isPickEligible`, and every `isPickEligible` slot is classified `PICK`.

**No match at all:** `places` empty, note `"No place by that name is in these windows."` The model then
answers `answerable: false` as today, which is now true.

Route the result through `finish("look_up_place", …, () -> { })`. The `accepted` runnable adds nothing to
`pairs` (§3). This keeps `RESULT_CHAR_CAP` and the trace (`ToolCall`) as for every other tool, so
`toolCalls` counts it and the validator's "answerable with no pick, no event and no tool call" discard
does not fire for an explanation answer.

### 4.2 Schema, arguments and dispatch

- `AskToolSchemas.LOOK_UP_PLACE = "look_up_place"` and a `lookUpPlace()` schema: `name` (string,
  required) and `windowIds` (string array). Description: *"Looks up one place by name and returns its
  served rating, verdict, headline, tide fit and whether PhotoCast recommends it (status), for each window.
  Use it when the reader asks about a specific place, e.g. why it is rated as it is. It never makes the place
  a pick."*
- `AskToolArguments.lookUpPlace(JsonNode)`, following the shape of `rankSpots` (`BadArguments` on a wrong
  type).
- `ClaudeAskEngine`'s dispatch switch gets the case, and its unknown-tool message lists the new name.

### 4.3 Which conversations get it

Typed questions only, fresh and follow-up. Not the Ready precompute. Today `tools(withUser, withThread)`
receives `withUser == false` only for Ready; check that this is still true (grep for every
`AskToolSchemas.tools(` caller; today the one caller is `ClaudeAskEngine`, passing `user.hasUser()`). Then add an explicit boolean `withPlaceLookup` rather than overloading
`withUser`, and keep the existing two-argument overloads' behaviour. The tool list order is `list_windows,
rank_spots, look_up_place, rank_night_spots, get_hot_topics, get_coming_up, submit_answer`. If any test
pins the tool list or order, update it. If a Ready path turns out to pass `withUser == true`, stop and
report it.

### 4.4 Prompt (`AskPromptBuilder.RULES`)

Add one bullet after the "good investigation" bullet:

> - When the question is about one named place ("why is Alnmouth 4★", "how is Bamburgh on Saturday"), call
> look_up_place with its name. Answer from what it returns: the rating, verdict, headline and tide fit. If its
> status is not PICK, explain the rating and say in one clause why PhotoCast is not suggesting the place,
> using its statusReason. Never give it as a pick. If the reader also wants somewhere to go, call rank_spots.
> If look_up_place finds nothing, the place is not in the forecast: answer answerable false.

Also change the "good investigation" bullet's "Questions this does not fit (one place, …) need none of it."
to "… (one place: call look_up_place; one tide, one event, the night) …". Do not touch any other sentence.

`THREAD_RULES`: a follow-up such as "why is it 4★" after an answer that named the place is covered by the
same bullet. Add nothing.

### 4.5 Caching and spend

The typed cache stores only an answer carrying a pick or an event, so an explanation answer is never cached
and every such question costs an engine call. That is accepted. Note it in the reference doc, and change
nothing in the cache.

### 4.6 Frontend check (read only)

Find where the Ask answer renders an `answerable: true` answer with an empty `picks` and `events`. Confirm
that it shows the summary and no "Not in the forecast" frame. Report what you found, and cite the file and
line in §7. No frontend edits.

### 4.7 Tests (read `docs/engineering/test-improvement-standards.md` first)

`AskToolsTest`, or a new `AskToolsLookUpPlaceTest` if that file is already large:

- the matcher: exact before partial; "alnmouth" finds both Alnmouth places; whole words only ("mouth"
  finds nothing); apostrophes ("st cuthberts" = "St Cuthbert's"); case; the cap and its note;
- each `status` row, including the order (an outlier in a thin region is `NEIGHBOUR_OUTLIER`; a canopy
  slot with a null rating is `UNRATED`);
- the `PICK` ⇔ `isPickEligible` agreement (§4.1);
- scope: in-scope only; an out-of-scope-only match returns no places, the scope note, and no rating;
- windowIds: the default is all windows; an unknown id is an error;
- **evidence**: after a lookup, `evidence().pairs()` is empty.

`AskAnswerValidatorTest`: an answerable answer whose only tool call was `look_up_place`, with no picks,
survives. A pick naming the looked-up place is dropped, both for a thin-sample place and for a `PICK`
place that `rank_spots` never returned.

`AskToolSchemasTest` (or wherever schemas are pinned): the new schema; Ready's list does not contain it;
typed lists do.

`ClaudeAskEngineTest`: dispatch reaches `lookUpPlace`; bad arguments become a tool error, not an exception.

`AskPromptBuilderTest`: the new bullet is present. Use a containment assertion, not a full-text pin, unless
the existing test pins the full text.

Mutation-check at least the status order and the evidence rule (break each, see a test fail, restore with
`cp` from a backup and never `git checkout --`).

### 4.8 Docs and changelog

- `docs/engineering/reference/ask-photocast.md`: add the tool to wherever the tools are listed; note D1/D2
  and that it adds no evidence pair; note the cache consequence (§4.5).
- `docs/engineering/neighbour-guard-plan.md` G2: add one line saying D2 of this plan narrows the "no
  name or star" rule for a named-place lookup.
- `changelog.d/20261022-ask-place-lookup.md`, `### Added — …`.

## §5 L2 — prompt check (owner-run, costs money)

Add a **new** class `AskPlaceLookupRegressionTest` beside `AskWindowContextRegressionTest` (in
`src/test/java/com/gregochr/goldenhour/service/ask/`), tagged `prompt-regression` like it and registered
wherever it is (the pitest exclusion list). It should have three fixtures built the way that class builds
its own:

1. "Why is Alnmouth 4★ on Saturday morning?" with Alnmouth 4★ in a thin-sample region. Expect
   answerable true, no picks, a summary that quotes 4 and does not say "not in the forecast".
2. The same with Alnmouth a neighbour outlier. Expect answerable true, no picks, and wording about the
   places beside it.
3. "Why is Atlantis rated 5★?". Expect answerable false.

Leave its assertions marked **pending owner approval**, and do not run it. The owner runs
`./mvnw test -Pprompt-regression -Dtest=AskPlaceLookupRegressionTest`.

## §6 Subagent brief (L1 + L2 test class, one PR)

> Build `docs/engineering/ask-place-lookup-plan.md` §4 (L1) and §5's test class (not its run). Read the
> plan in full first, then `docs/engineering/reference/ask-photocast.md` (grep it; don't read it whole) and
> `docs/engineering/test-improvement-standards.md`. Work on a branch `feature/ask-place-lookup` from
> main. Never `git checkout --`, `git restore` or `git stash`; commit as you go; **do not push**; never
> edit existing assertions under `src/test/java/.../regression/`. There is no JDK 25 and no Docker on
> this Mac. Run the backend gate as:
>
> `cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false > "$SCRATCH/v.log" 2>&1; echo "exit: $?"`
>
> Gate on the exit code, never on a grep. Also run `./mvnw checkstyle:check`. Do not start the backend
> and do not trigger any forecast run or real Anthropic call. If the code contradicts the plan (a pinned
> tool list, a Ready path with a user, a frontend that frames a no-pick answer as "not in the forecast"),
> stop and report rather than guess. Report the commits, the gate's exit code and test counts, the
> mutation checks you ran, and the §4.6 finding with file and line.

After it reports, review the diff adversarially before the PR: does any path add a lookup to
`pairs`? Is the status order right? Is Ready's tool list unchanged?

## §7 As built

Built 2026-10-22 on `feature/ask-place-lookup` (Sonnet subagent), L1 plus the §5 test class; not pushed.

**Built.** `AskTools.lookUpPlace` with `LookUpPlaceArgs` / `LookUpPlaceResult` / `PlaceInfo`, the package-private
`PlaceStatus` enum, `classify` (the §4.1 order, ending in an `isPickEligible` agreement check that throws),
`placeWords` / `containsRun` (the matcher); `AskToolSchemas.LOOK_UP_PLACE` and `tools(withUser, withThread,
withPlaceLookup)`; `AskToolArguments.lookUpPlace`; the `ClaudeAskEngine` dispatch case and unknown-tool message;
the prompt wording (§4.4); tests (`AskToolsLookUpPlaceTest`, plus additions to `AskAnswerValidatorTest`,
`AskToolSchemasTest`, `AskToolArgumentsTest`, `AskPromptBuilderTest`, `ClaudeAskEngineTest`,
`ClaudeAskEngineWireTest`, `AskReadyClaudeEngineTest`); `AskPlaceLookupRegressionTest` (registered in the pom's
PIT exclusion list, assertions pending owner approval, never run); the reference doc, the G2 note and the
changelog entry.

**Deviations.**

1. **The prompt change is applied to typed prompts only, not to `RULES`.** The plan says to edit `RULES`
   (§4.4) and also that Ready prompt bytes are unchanged (§3, §4.3). `RULES` is shared by Ready, so editing it
   would change Ready's prompt and tell it to call a tool it is not given. `RULES` is untouched;
   `AskPromptBuilder.systemPrompt` replaces `PLACE_FIT_BEFORE` with `PLACE_FIT_AFTER` (the reworded sentence and
   the new bullet, the plan's text) when `hasUser`. `hasUser` is exactly `!ready` (`ClaudeAskEngine.run`:
   `ready = !user.hasUser()`), the same split that gives the tool list its `withPlaceLookup`.
2. **`withPlaceLookup` is `!ready`**, not a new `hasUser`-style flag threaded from callers: `ClaudeAskEngine` is
   the only caller of `AskToolSchemas.tools(` and a Ready conversation is exactly one with no user (§4.3 holds).
3. **The `UNRATED` reason** adds a clause only from the region's own `coverageReason` ("Its region was stood down
   for the weather." for `STOOD_DOWN`, "Its region was not scored." for any other reason): the spread has counts,
   not phrases, so these are the spread's own two words ("stood down", "not scored"). A region with slots rarely
   carries a reason, so this fires seldom.
4. **The `CANOPY` reason** is the plan's sentence verbatim; no matching canopy phrase exists in the Plan or Map
   client to copy (grep of `frontend/src`).
5. A slot with a null location id or null name is skipped by the matcher (it cannot be identified, and was never
   pick-eligible), so `PlaceInfo.locationId` can be a primitive `long`.
6. Existing tests that pinned the typed request's tool count (6 → 7) were updated:
   `ClaudeAskEngineTest.requestShape` and `ClaudeAskEngineWireTest.toolConversationOverTheWire`
   (`submit_answer` is now index 6). Ready's list is pinned unchanged in `AskReadyClaudeEngineTest`.

**§4.6 frontend finding (read only, no edit).** A no-pick, no-event `answerable: true` answer renders as a summary
with no "Not in the forecast" frame. `frontend/src/utils/askConversation.js:375` (`normaliseAnswer`) makes an answer
a `cant` only when `body.kind === 'cant'` or `body.answerable === false`; an answerable body stays
`phase: 'answer'` (`askConversation.js:247`); `frontend/src/components/ask/AskConversation.jsx:200` renders
`AskAnswer` for it and only `phase === 'cant'` (`:201`) renders `AskCantAnswer` (the "Not in the forecast" label,
`AskCantAnswer.jsx:15`); `frontend/src/components/ask/AskAnswer.jsx:39` draws the summary unconditionally and
guards the events (`:41`) and picks (`:48`) lists on being non-empty. The phone peek (`MapPeekAsk.jsx:317`) shows
"Not in the forecast" only for phase `cant`, otherwise the summary. Nothing contradicts the plan.

**Mutation checks** (each backed up with `cp`, restored with `cp`, the tree confirmed clean after):
status order (THIN_SAMPLE tested before NEIGHBOUR_OUTLIER) fails `outlierBeatsThinSample`; recording the lookup's
places as evidence pairs fails four tests (`lookupIsNeverEvidence`, `rankSpotsUnchangedByLookup`,
`AskAnswerValidatorTest.lookUpPlace_pickOnAPickEligiblePlaceRankSpotsNeverReturnedIsDropped`,
`ClaudeAskEngineTest.pickFromALookupIsDropped`); giving Ready the tool (`withPlaceLookup` always true) fails
`AskReadyClaudeEngineTest`.

**Review fixes (2026-10-22, coordinator review of the first build).**

1. **Character budget.** `RESULT_CHAR_CAP` is per conversation and cumulative, and `list_windows` comes first, so an
   unfiltered "Alnmouth" lookup (2 places x 5-6 windows, ~500 characters a row) could return `LIMIT_REACHED` for the
   very question this feature exists for. Fixed three ways: `MAX_PLACE_ROWS` (6; 8 measured 5,102 characters in the
   worst case, over the 4,500 bar) caps total (place, window) rows, exact match first, with the cut named in `note`;
   `statusReason` moved off every row into a top-level `reasons` map (status name to text, only statuses present;
   the `UNRATED` text uses the first unrated row's region reason); and the prompt now tells the model to pass the
   `windowIds` the question is about. Tests: the worst case (4 places, 160-character tide phrases, 120-character
   headlines, long names and regions) serialises to 3,785 characters (6 windows) and 2,503 (all four reasons present),
   both pinned at <= 4,500; and on one `AskTools` instance the busy-day `list_windows` followed by an unfiltered
   lookup is not an error (`AskToolsTest.lookUpPlace_afterABusyDayListWindowsIsNotCutOff`). The new prompt sentence
   contains "looking at", so `ClaudeAskEngineTest.contextWindowOnlyWhenKnown` now asserts the absence of the context
   sentence "The reader is looking at" instead of the bare phrase.
2. **Status order.** `UNRATED, CANOPY, BELOW_GAME_ON, NEIGHBOUR_OUTLIER, THIN_SAMPLE, PICK`: a 2-star place in a
   thin-sample region used to read `THIN_SAMPLE` ("too few were rated to recommend one"), which implies it might
   otherwise be recommended; its rating is the honest reason. Mutation (BELOW_GAME_ON moved after THIN_SAMPLE) fails
   `lowRatingBeatsThinSampleAndOutlier`. The earlier mutation line above refers to the previous order.
