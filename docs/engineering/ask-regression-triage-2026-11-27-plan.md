# Ask prompt regression, first keyed run (2026-11-27): triage and fixes

Run 45986396686 on `main` at `c46e533`, job "Prompt regression — ask": **28 tests, 12 failures**. This is the
first run with a real key; every earlier run stopped at the missing-key guard. Source: the job log (865 lines,
read in full). The Surefire artifact could not be downloaded from this session because the proxy refuses the
blob host. The log carries every fault map and every printed answer.

For each failure this doc decides whether **Ask is wrong** (fixed in Part B through the CLAUDE.md process) or
the **test checks more strictly than its intent** (Part A: evidence and a proposed rewording, for the owner
only). Many cases are both. Per CLAUDE.md, no regression assertion or regression-test fixture is edited here.

## Verdicts at a glance

| Test | Pass | Verdict | Ask fix (Part B) | Owner proposal (Part A) |
|---|---|---|---|---|
| `AskWindowContext…stayHome_readyBestWeekend` | 0/10 | both | B1 | A1 |
| `…stayHome_typedBestWeekend` | 0/10 | both | B1 | A1 |
| `…stayHome_typedSundaySunrise` | 0/10 | mostly Ask | B1 | A1 |
| `…mixed_readyBestWeekendNamesBothDays` | 0/10 | mostly Ask | B2 | A2 |
| `AskFavourites…aLowerRatedFavouriteIsNamedAtItsOwnRating` | 1/5 | Ask | B3 | — |
| `…aPlainBestQuestionNamesALowerRankedFavourite` | 1/5 | Ask | B3 | — |
| `…aTieIsALeadAndACompanion` | 0/5 | both | B3 | A3 |
| `AskPlaceLookup…thinSamplePlaceIsExplained` | 8/10 | Ask (small) | B4 | A4 (fixture) |
| `…neighbourOutlierIsExplainedAsOne` | 7/10 | Ask | B4 | — |
| `AskNamedArea…hadriansWallIsAnswered` | 5/10 | test | — | A5 |
| `AskNight…auroraTonightForAProAsker` | 1 run | test + fixture | — | A6 |
| `AskNight…clearSkiesOnTheDurhamCoastTonight` | 1 run | Ask (tool gap) + fixture | B5 | A7 |

## Part A — evidence and proposals for the owner (assertions untouched)

### A1. Stay-home wording (`STAY_HOME`, plan Q3)
- **The regex rejects the owner's own word.** `stay(ing)? (at )?home` needs a space, so "This weekend is a
  **stay-home** window with rare exceptions", "a stay-home weekend" and "a stay-home window overall" all fail.
  That is 3 of 30 answers.
- **The regex rejects the prompt's own sanctioned phrase.** `RULES` says: "Say instead that places are worth
  going to, or that **most places are not worth going out for**". Six answers open "Most places are not worth
  going out for this weekend, but …" and fail. Plan §2.6 kept `not worth` out of the regex on purpose
  ("not worth missing"). The longer phrase `most places are not worth` carries no such risk.
- **Proposed regex:**
  `stay(ing)?[- ](at )?home|not (a |the )?(weekend|window|morning|evening|day|night|one) (worth|to go)|most (places|spots) (are|is) not worth`
- **Check on the game-on side:** `doesNotSayStayHome` uses the same pattern. A game-on answer saying "most
  places are not worth going out for" would be wrong anyway, so widening it there is correct.
- **What the regex rightly catches:** about 15 of 30 answers call the window "marginal" ("This weekend is
  marginal across every region"). On the owner's scale "marginal" is a single 3★ place, not a window. That is
  a real fault, and B1 fixes it.
- **Approved by the owner 2026-11-27; applied in this commit.**

### A2. Mixed weekend names Saturday
- **Real fault, 8/10:** the answer never mentions Saturday (B2).
- **Over-strict, 2/10:** "Sunday sunset is the forecast's best bet this weekend. Tomorrow sunset is not worth
  going out for across every region." The fixture's `now` is Friday 2026-10-16, so "tomorrow" *is* Saturday.
  The answer is right, but the `saturday` and `MIXED` checks don't accept it.
- **Proposed:** accept `saturday|tomorrow` in both checks, i.e.
  `(saturday|tomorrow)[^.]*(stay|poor|not worth|nothing|stood down|stand down)|mixed`.
  This holds only while the fixture's today is the Friday; say so in the Javadoc.
- **Approved by the owner 2026-11-27; applied in this commit.**

### A3. Tied favourites
- **Real fault, 3/5:** "two places rate 4★" names nobody (B3).
- **Over-strict, 2/5:** "Mary's Lighthouse and Bamburgh are both rated 4★" states the tie, but
  `SAME = same|equal|tie|also` doesn't accept "both".
- **Proposed:** `SAME = same|equal|tie|also|both|as well`, and require it in the sentence that names Bamburgh.
- **Approved by the owner 2026-11-27; applied in this commit.**

### A4. Thin-sample fixture
`AskFixtures.slotWith` builds every slot with no headline and no evaluation summary, so Alnmouth's row has no
`detail`. A real thin-sample row would normally carry one. The question "why is it 4★?" with no reason made
the model answer CANT twice (log: "too few places … were rated for the forecast to explain the sky conditions
there").
- B4 fixes Ask's handling of a row with no detail.
- **Optional, owner's call:** give the thin-sample fixture a served summary, so the test measures the
  production shape.
- **Also suggested:** print tool arguments, not only tool names, in the regression `log` lines. The cause
  of the second `look_up_place` call is unknown because the log does not show what it asked for.
- **Approved by the owner 2026-11-27; applied in this commit.** The fixture part is applied. The argument logging is
  not: `AskTools.ToolCall` records only the tool name, an error flag and a size, so printing arguments needs a
  `src/main` change, which this approval excludes.

### A5. Hadrian's Wall "look" question: over-strict
- **Why the five fails are correct answers:** they say "all three rated places 4★ or better". Three is
  exactly the area's `MEMBERS`. The prompt's named-area rule is "answer from those stars, never as picks".
- **The check also passed a bad answer:** the one run that never called `look_up_place` said "five places
  rated 4★ or better". Five is the whole region, not the Wall, yet it passed because it named Cawfields.
- **Proposed:**
  - `look_up_place` must be in the trace;
  - the summary must name a member **or** quote the area's count (`\b(three|3)\b`);
  - fail on `\b(five|5) (rated )?places\b`.
- **Optional prompt nudge (not in B):** "name the best-rated place in the area and its stars".
- **Approved by the owner 2026-11-27; applied in this commit.**

### A6. Aurora alert level: over-strict, and the fixture contradicts itself
- **Where "strong" came from:** the fixture's served aurora summary reads "**Strong** geomagnetic activity
  with a …" (`AskNightRegressionTest.java:471`), while its alert level is `MODERATE`, Kp 6. In production
  `AlertLevel` defines STRONG as Kp 7+.
- **Ask is not inventing it:** `rank_night_spots` passes the summary through as `auroraSummary`, beside
  `alertLevel: MODERATE`, `maxKp: 6.0` and `auroraState: "alert moderate, Kp 6.0"`. The model quoted the
  summary.
- **Why the test fails:** no prompt or schema text asks Ask to name the alert level. The test checks for a
  behaviour Ask was never told to produce.
- **Proposed:** make the fixture summary neutral ("Active geomagnetic conditions with a …"). Then either
  (i) the owner adds a prompt clause "when the result has auroraState, name the level and Kp as it reads"
  and keeps the assertion, or (ii) the assertion becomes "never says strong or severe while the state is
  moderate".
- **Approved by the owner 2026-11-27; applied in this commit.**

### A7. "Durham coast" night fixture
- **Production's shape:** "Durham coast" is not a region. It is an alias of the sub-region "Sunderland & the
  Durham Coast", inside "Northumberland & Tyneside" (`V191__sub_regions.sql:58,78`).
- **The fixture's shape:** a region called "County Durham", which no exact match can reach.
- B5 lets `rank_night_spots` take `areaName` and list known names on a miss.
- **Proposed fixture:** mirror production, with region "Northumberland & Tyneside" and an
  `AskSnapshot.Area` "Sunderland & the Durham Coast" (alias "Durham coast") holding the coastal places.
- **Proposed assertion:** "a pick is one of the area's location ids", replacing `DURHAM.equals(regionName)`.
- **Approved by the owner 2026-11-27; applied in this commit.**

### Noise, not a failure
`[ASK] Could not read the night forecasts … "tonight" is null` appears across many classes.
- **Cause:** `AskFixtures.snapshotAt` passes an unstubbed `AuroraForecastRunService` mock. The builder
  catches the NPE and falls back to `Nights.NONE`.
- **Production:** `currentNight()` is never null, so production is unaffected.
- **Effect on the failures:** none. The night test injects its own nights.
- **Left alone on purpose:** `AskFixtures` has about 50 users, so a stub there would change the unit tests'
  nights.

## Part B — the Ask fixes (Sonnet builds; one PR)

All prompt text lives in `backend/src/main/java/com/gregochr/goldenhour/service/ask/AskPromptBuilder.java`.
`RULES` and `WITH_USER` sit in the cached static part, so one change costs one cache rewrite. The
covered-windows line sits in the per-question tail. Keep each addition to the words given here; every Ask
call pays for them (read `docs/engineering/claude-cost-ledger.md` first, and record the added tokens in the PR).

**B1 — a window is never called "marginal".**
- In `RULES`' "How to judge a window" bullet, after "not a good window: say so plainly,", insert:
  `never calling the window marginal (marginal is one place's 3★),`
- Do not add any new stay-home phrase. Steering toward a test's exact string would be tuning against the test.
  A hyphenated "stay-home" would also still fail today's regex.
- The owner's A1 is what makes the stay-home tests measurable.
- ⚠️ **Interaction for the owner.** In `ask-game-on-weekend.json`, Sunday is itself a stay-home window
  (sunrise 2 of 201 places at 4★ or better, sunset 5 of 181). A correct game-on answer may therefore say
  "Sunday is one to stay home for". Today that slips past `doesNotSayStayHome` only because of the hyphen
  rule. If A1 is adopted, narrow `doesNotSayStayHome` to the summary's lead sentence, or to the BEST BET
  window.

**B2 — a Ready answer names a covered day that has nothing (bounded, computed by the engine).**
- `ClaudeAskEngine` already holds a Ready offer's `windowIds` (the windows with candidates) and its
  `coveredWindowIds`. It computes the **covered dates with no candidate window**, leaving out the BEST BET's
  date, and passes them to `AskPromptBuilder.systemPromptBlocks` as a new trailing parameter.
- Keep the existing overloads byte for byte, with an empty list.
- For each such date, append one line to the dynamic tail, after the covered-windows line:
  `<EEEE d MMMM> has no place worth going to: say so in the summary, naming the day.`
- A typed question, and an offer with no such date, add nothing. The line cannot balloon on a quiet week,
  because it names only days with no candidate at all. It also names the weekday, not "tomorrow".
- If the covered set holds more than two such dates, emit one line listing them, capped at the first three.
- Revised after review (Codex P1): emptiness is read from the spread (gameOn 0, mostly scored), never from the offer's candidate ids, which COASTAL_HIGH and BEST_* filter further.

**B3 — favourites: the right call, and the companion said.**
- In `WITH_USER`, replace "pass favourites true only for a question about their own places." with:
  `pass favourites true only when the question limits itself to their own places ("which of my places…"); a question asking where is best and also how their favourite looks leaves it unset, because the result already names their favourite.`
- Mirror the same rule in `AskToolSchemas.rankSpots`' `favourites` description.
- In `WITH_USER`, turn the companion rule into its own leading sentence:
  `When rank_spots returns a companion, the summary names it by name with its stars:`
  followed by the existing three `companionKind` clauses, unchanged in meaning.
- Keep "favourite" and "companion" out of `RULES` and `USER_LESS`, as `AskPromptBuilderTest:538-539` pins.
- Not done: an engine step that appends the companion sentence itself. It rewrites model prose after
  validation, so it is the owner's design call. It is the fallback if B3 measures short.

**B4 — a looked-up place with no detail is still an answer.**
- In `PLACE_FIT_AFTER`, insert a sentence **after** "using the reason given for its status." so the span
  `AskPromptBuilderTest:108-110` pins survives:
  `A rated row with no detail is still answerable true: give its rating, then the reason given for its status as the explanation.`
- Change the `NEIGHBOUR_OUTLIER` status reason in `AskTools.statusReason` to:
  `Its rating is well above the places beside it, so PhotoCast doubts that star and is not suggesting it.`
  This explains the star, not only why the place is not suggested.
- Update `AskToolsLookUpPlaceTest:312` and the quote of the old outlier reason in
  `docs/engineering/ask-place-lookup-plan.md:109`.

**B5 — `rank_night_spots` resolves a named area, and a miss says what to pass.** This is a production gap:
the owner's own question "clear skies on the Durham coast tonight?" hits it.
- **Schema:** in `AskToolSchemas.rankNightSpots`, add `areaName` under `withNamedAreas` (that is, typed
  only), with `rank_spots`' description.
- **Args:** add the `areaName` component to `RankNightSpotsArgs` with a delegating constructor that takes the
  old 8 arguments, as `RankSpotsArgs` does (`AskTools.java:343-348`), so the 19 call sites stand.
  `AskToolArguments` parses the new field; `StubAskEngine` is unaffected.
- **Resolution:** in `AskTools.rankNightSpots`, resolve it exactly as `rank_spots` does:
  - user-only;
  - `matchArea(snapshot.areas(), placeWords(...))`;
  - the same "Unknown named area '…'. Known: …" failure, plus "or use regionNames";
  - filter the pool to `area.locationIds()`;
  - an area hit that leaves no spot gets an area-named note, not `NOTHING_NIGHT_MATCHES_NOTE`;
  - not `personal`.
- **Region miss, both tools** (`AskTools.java:1114` and `:2104`):
  - In a typed conversation, first try `matchArea` on the name. On a hit, fail with
    `'<name>' is a named area: pass it as areaName.`
  - Otherwise append `Known: <names>.`, where the names come from **one shared helper**:
    `canonicalRegion`'s own universe intersected with scope, capped like `KNOWN_AREAS_CAP`.
  - Mention areaName only when `user.hasUser()`.
- **Prompt:** add `For a named area or a stretch of coast, pass it to rank_night_spots as areaName.` to
  **`WITH_USER`**, beside the `rank_night_spots` nearHome clause, **never `RULES`**. Ready receives `RULES`
  byte for byte, and its schema has no areaName.
- **Canopy:** filter canopy spots out of the night pool before the empty-region note. This is defence only:
  production's builder already drops woods.

**Tests (unit, no API):** cover every prompt string change, with typed and Ready asserted both ways where the
two prompts differ. Also cover:
- B2's date computation: no line for a typed question or a fully candidated weekend; exactly the
  uncandidated date; the BEST BET date left out; the cap.
- B5: area hit filters; area hit with no spot gives the area note; area miss gives the Known list; `areaName`
  with no user fails; `areaName` combined with `regionNames`; a region miss that names an area gives the
  areaName hint; a region miss that names nothing gives the Known list; the canopy filter.
- `AskToolSchemasTest`: the night `areaName` is present for typed and absent for Ready, mirroring `:132-134`.
Do **not** touch anything under the `prompt-regression` tag, `AskFixtures`, or the regression fixtures.

**Docs:**
- `docs/engineering/reference/ask-photocast.md`, in the night-tool and look-up paragraphs: night `areaName`,
  the region-miss list, the no-detail rule, the outlier reason.
- A `changelog.d/20261127-ask-regression-triage.md` entry.

**Verify:** the local gates pass. Proof that B1–B4 move the pass rates needs a paid re-run of
"Prompt regression — ask" (about $1.50–3.60, the owner's approval in the `prompt-regression` environment).
**The PR is held unmerged until the owner has run it**, because a prompt change without a measurement is a
guess. Compare against this run's figures, the paired control.

## Measured: the PR's first paid run (2026-11-27, run 45997936257 on `7f29732`)

**28 tests, 7 failures** (this morning's control: 12). That commit had B1–B5 as first built, with B2 still
reading candidate ids.
- **Now passing:** `aLowerRatedFavourite…`, `aTie…`, `thinSample…`, `neighbourOutlier…`,
  `clearSkiesOnTheDurhamCoastTonight` and `auroraTonight…`. The aurora test fixes nothing in Ask and may be
  sampling variance (A6 still stands).
- **`mixed_…` 1/10. Ask is now right; the test is over-strict.** 9 of 10 summaries open "Saturday has
  **nowhere** worth going to" or "Saturday has **no place** worth going to". `MIXED`
  (`saturday[^.]*(stay|poor|not worth|nothing|…)`) accepts neither. **A2, amended:** add
  `nowhere|no place|no places` to the alternatives, as well as `tomorrow`.
- **Stay-home ×3, 0/10. Still a real fault, and still an over-strict regex.** About 13 of 30 still say
  "marginal" despite B1, about 8 say "stay-home window" (the hyphen, A1), and about 3 "most places are not
  worth" (A1). The next push therefore bans "marginal" outright in `RULES`' never-write list, which already
  holds "stand down" etc. That list's words appeared in neither run's 280 answers.
  - A few answers lead with the pick and never frame the weekend, e.g. "Bamburgh tomorrow at sunset is the
    forecast's pick…". The bestBet rule permits that lead. Whether the frame must come first is the owner's
    call.
- **`aPlainBestQuestionNamesALowerRankedFavourite` 3/5 (bar 4), up from 1/5.** B3 helps but is not enough:
  the misses still say "two places rated 4★ or better" without naming Mary's. The next lever is the engine
  appending the companion sentence itself (B3's "not done"), which is the owner's design call.
- **`hadriansWallIsAnswered` 4/10.** Unchanged in kind: every miss answers from the Wall's three places
  (A5).
- **New: `closerToHomeResolvesAgainstTheEarlierNightAnswer` (single run).** The follow-up used a drive limit
  ("within 40 minutes of home"). The prompt allows that ("'Closer' in a follow-up: pass a maxDriveMinutes
  smaller than the earlier picks' drive, or nearHome true"), and so does the test's own `personal` check
  ("nearHome or a drive limit"). The test then requires "miles of home", which only nearHome produces.
  - **A8:** accept `miles of home|minutes of home` there, or say in the prompt which of the two "closer" uses.
  - **Approved by the owner 2026-11-27; applied in this commit.** (A8, with the rest of A1-A8.)
  - B5's areaName sentence had been inserted between the nearHome and drive clauses, so the next push moves
    it after them, in case it nudged the choice.

**The second push** (`e1c9559`) holds: Codex's P1 on B2 (emptiness read from the spread, gameOn 0 and
mostly scored, never from the offer's candidate ids), the "marginal" ban, and the moved areaName sentence. It
starts one more paid run.
