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

### A2. Mixed weekend names Saturday
- **Real fault, 8/10:** the answer never mentions Saturday (B2).
- **Over-strict, 2/10:** "Sunday sunset is the forecast's best bet this weekend. Tomorrow sunset is not worth
  going out for across every region." The fixture's `now` is Friday 2026-10-16, so "tomorrow" *is* Saturday.
  The answer is right, but the `saturday` and `MIXED` checks don't accept it.
- **Proposed:** accept `saturday|tomorrow` in both checks, i.e.
  `(saturday|tomorrow)[^.]*(stay|poor|not worth|nothing|stood down|stand down)|mixed`.
  This holds only while the fixture's today is the Friday; say so in the Javadoc.

### A3. Tied favourites
- **Real fault, 3/5:** "two places rate 4★" names nobody (B3).
- **Over-strict, 2/5:** "Mary's Lighthouse and Bamburgh are both rated 4★" states the tie, but
  `SAME = same|equal|tie|also` doesn't accept "both".
- **Proposed:** `SAME = same|equal|tie|also|both|as well`, and require it in the sentence that names Bamburgh.

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

### A7. "Durham coast" night fixture
- **Production's shape:** "Durham coast" is not a region. It is an alias of the sub-region "Sunderland & the
  Durham Coast", inside "Northumberland & Tyneside" (`V191__sub_regions.sql:58,78`).
- **The fixture's shape:** a region called "County Durham", which no exact match can reach.
- B5 lets `rank_night_spots` take `areaName` and list known names on a miss.
- **Proposed fixture:** mirror production, with region "Northumberland & Tyneside" and an
  `AskSnapshot.Area` "Sunderland & the Durham Coast" (alias "Durham coast") holding the coastal places.
- **Proposed assertion:** "a pick is one of the area's location ids", replacing `DURHAM.equals(regionName)`.

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

**B1 — a stay-home window is called that, never "marginal".**
- In `RULES`' "How to judge a window" bullet, after "is a stay-home window with a few exceptions, not a good
  window: say so plainly,", insert: `calling it a stay-home window (never marginal: marginal is one place's 3★),`
- Leave the existing sentence "Say instead that … most places are not worth going out for" untouched.

**B2 — a Ready answer names a covered day that has nothing.**
- In `systemPromptBlocks`, after "Every pick must be on one of these windows.", append:
  ` If a day among these windows has no place worth going to, the summary names that day as one to stay home.`
- This is in the dynamic tail, so only Ready conversations with covered windows see it.

**B3 — favourites: the right call, and the companion said.**
- In `WITH_USER`, replace "pass favourites true only for a question about their own places." with:
  `pass favourites true only when the question asks about nothing but their own places; a question that also asks where is best leaves it unset, because the result already names their favourite.`
- Mirror the same rule in `AskToolSchemas.rankSpots`' `favourites` description.
- In `WITH_USER`, turn the companion rule into its own leading sentence:
  `When rank_spots returns a companion, the summary names it by name with its stars:`
  followed by the existing three `companionKind` clauses, unchanged in meaning.
- Not done: an engine step that appends the companion sentence itself. It rewrites model prose after
  validation, so it is a design decision for the owner. It is the fallback if B3 measures short.

**B4 — a looked-up place with no detail is still an answer.**
- In `PLACE_FIT_AFTER`, after "using the reason given for its status.", insert:
  `A row with no detail is still answerable: give the rating and the reason given for its status, and never say the reason is unavailable.`
- Change the `NEIGHBOUR_OUTLIER` status reason in `AskTools.statusReason` to:
  `Its rating is well above the places beside it, so PhotoCast doubts that star and is not suggesting it.`
  This explains the star, not only why the place is not suggested.
- Check that `THREAD_RULES`' held-back wording ("never call it low, poor or unreliable") still reads true.
  "Doubts that star" says neither low nor poor.

**B5 — `rank_night_spots` resolves a named area, and a miss lists the names.** This is a production gap: the
owner's own question "clear skies on the Durham coast tonight?" hits it.
- **Schema:** in `AskToolSchemas.rankNightSpots`, add `areaName` under the same condition as `rank_spots`
  (`withNamedAreas`), with the same description.
- **Matching:** in `AskTools.rankNightSpots`, resolve it exactly as `rank_spots` does: user-only,
  `matchArea(snapshot.areas(), placeWords(...))`, and the same "Unknown named area '…'. Known: …" failure.
  Then filter the night spots to `area.locationIds()`.
- **Miss text:** the night tool's unknown-region failure appends `Known: <region names in scope>.` capped as
  `KNOWN_AREAS_CAP`, and offers "or leave regionNames out". `rank_spots`' region miss gets the same treatment.
- **Prompt:** `RULES`' night bullet gains: `For a named area or a stretch of coast, pass it as areaName.`
- **Defence in depth:** `rankNightSpots` drops canopy (wood) spots, as its schema already promises. The
  `rank_night_spots` output leaked "Hamsterley Bluebell Wood" into prose in two regression answers.
  Production's snapshot drops woods already.

**Tests (unit, no API):** cover every prompt string change, plus B5's three paths: area hit filters, area miss
lists known names, and `areaName` with no user fails. Also cover the region-miss text and the canopy filter.
Do **not** touch anything under the `prompt-regression` tag, `AskFixtures`, or the regression fixtures.

**Docs:**
- `docs/engineering/reference/ask-photocast.md`, in the night-tool and look-up paragraphs: night `areaName`,
  the region-miss list, the no-detail rule, the outlier reason.
- A `changelog.d/20261127-ask-regression-triage.md` entry.

**Verify:** the local gates pass. Proof that B1–B4 move the pass rates needs a paid re-run of
"Prompt regression — ask" (about $1.50–3.60, the owner's approval in the `prompt-regression` environment).
**The PR is held unmerged until the owner has run it**, because a prompt change without a measurement is a
guess. Compare against this run's figures, the paired control.
