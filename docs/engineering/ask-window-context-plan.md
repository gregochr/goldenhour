# Ask PhotoCast — the window's rating distribution (`list_windows` spread)

**Status (2026-10-20): the owner accepted every recommendation of §6 (recorded there). W1 (the backend spread,
the prompt, the carried figures and their freshness), W3 (the regression class and its fixtures) and W2 (the
client line, Q4: `AskWindowSpread` over `utils/askSpread.js`, one line per carried rated window under the
summary, on every surface and for every role — the rules are in §2.4's client paragraph) are built; W4 (§2.5,
Q1 chosen option A: nothing to build) and R1 are not. No migration. The W3 regression's assertions are pending
the owner's approval and it has never been run. ⚠️ W2 has been seen only as a static harness of the built
stylesheet at 320/360px (the exact markup, not the app): the live line has never been seen signed-in — the
owner's browser check is owed (the stub engine shows it locally).**

Written 2026-10-17 from the owner's reading of the Plan tab's spread histograms for the weekend of
Sat 17 / Sun 18 October 2026, the day the neighbour guard went on by default (#1220), and reviewed
adversarially the same day (§8). Read beside `docs/engineering/ask-photocast-plan.md` (the engine,
the tools, the validator, the Ready catalogue) and the neighbour guard's plan,
`docs/engineering/neighbour-guard-plan.md` (merged to `main` in #1208 on 2026-10-18, after this plan's
first cut; the guard itself shipped in v2.29.0, and the four `changelog.d/20261017-neighbour-guard-*.md`
entries record what shipped).

## §0 The owner's ask, and the principle it is built under

**The complaint.** On a mediocre weekend Ask still finds "something worthwhile": it calls `rank_spots`,
takes the top of a list that is nearly all 3★ with two 4★ at the top, and presents the 4★ tail as a
recommendation. The owner reads the same weekend off the heat map and the Plan card's histogram as a
stay-home weekend with a couple of coastal exceptions, and wants Ask to say that plainly.

**The owner's rating scale (their words, 2026-10-17):**

| star | reading |
|---|---|
| 1★, 2★ | stay home |
| 3★ | if it's close to home and I'm bored, think about it |
| 4★ | game on |
| 5★ | win, go |

**How the owner reads the heat map:** is the map blue (poor) or red? All blue: stop. All red: get
excited. Pockets of gold or red: drill in. Red at the coast: is there a high tide, or a spring or king
tide? Look at the other promising pockets. Decide, and rule out a rogue rating by eye — which the
neighbour guard now does automatically.

**The example** (from the Plan card's histogram, within the owner's 2h30 reach tier):

| window | 1★ | 2★ | 3★ | 4★ | 5★ | unrated |
|---|---|---|---|---|---|---|
| Saturday sunset | 0 | 56 | 122 | 2 | 0 | — |
| Sunday sunrise | 1 | 98 | 100 | 2 | 0 | 12 |
| Sunday sunset | 0 | 43 | 133 | 4 | 0 | — |

Every window wears "Maybe". Today Ask's `list_windows` reports each as `verdict: MAYBE, bestRating: 4`
— the two facts the pill shows — and nothing about the 178, 199 and 176 places at 3★ or below.
`rank_spots` then returns the 4★ places and 3★ fill, best first; the rules say "give 2 or 3 picks",
so a third pick is a 3★; and the summary reads like a weekend worth going out for.

**The principle, agreed with the owner: criteria, not a script.** Ask gets better information (the
distribution) and the owner's criteria for a good answer (the scale, and that a stay-home window is
called one); the workflow above goes into the prompt only as a hint of what a good investigation
usually looks like. Ask stays free to answer "what's the tide at Bamburgh on Sunday?" or "anywhere
dark tonight?" without walking that ladder. The non-negotiables stay in code and are the ones already
there — the validator (a pick must be a pair a tool returned, still pick-eligible live), the neighbour
guard (an outlier is never a pick), the turn cap and the forced final `submit_answer`. This plan adds
three things in code, none of them a rule about how the model reasons: the figures are served, never
the model's (§2.1); a stored answer whose figures have moved is withheld whole (§2.4); and a Ready
"best" answer may honestly say "nothing" when the served data holds no game-on place (§2.3, a set
test with no threshold). The prose stays the model's, as it is today (§2.4). Option B of §2.5 would
add a fourth, and §2.5 says so, because it would turn a criterion into enforcement.

**Decided with the owner, not re-opened here:** the spread is an **extension of `list_windows`**, not
a new tool. It is the first look in almost every judgement and `list_windows` is where Claude already
looks first (its description says so). It carries a per-window headline — rated and unrated counts,
the five band counts, the count and share at 4★+ ("game on"), any 5★ ("go"), the 1–2★ "stay home"
mass — and a compact pocket list: only the regions holding at least one game-on place, each with its
game-on count and its mean.

**One reading of the brief, stated so it can be refused (owner decision Q7, §6).** The brief says a
stay-home window "must not lead with a 4★ pick". This plan reads "lead" as the *framing*: the summary
must call the weekend what it is, and the 4★ exceptions may still be carried as picks — named as
exceptions, with their region — because a reader who asks "best spot this weekend?" and is told
"stay home" can still want the one place worth it, and cards are how they act on it (Plan this, Show
on map). The other reading — a stay-home answer carries no picks at all, the exceptions named in
prose only — is set out in §2.3 and §2.6 as the alternative, and the regression's assertions are
written so the owner can flip between them by deleting one line.

## §1 What the code says today (facts this plan rests on; each one checked 2026-10-17)

1. **`AskSnapshot` already holds everything the spread needs.** `AskSnapshot.Window` carries
   `regions`, each `Region` its served `displayVerdict`, `meanRating`, `verdictEligible` and `slots`,
   each `Slot` its served `rating` (`BriefingSlot.claudeRating`), `verdict` (`DisplayVerdict`),
   `canopy`, `tideState`, `tideAligned` and `neighbourOutlier` (`AskSnapshotBuilder.toSlot`). Nothing
   new is read from any store; the spread is a fold over slots Ask already reads, built from
   `BriefingService.getCachedBriefingForApi()` — the Plan tab's assembly, so the sample gate, the
   honesty filter, retraction and the guard's mark are inherited.
2. ⚠️ **Inheriting the honesty filter means inheriting its deletions.** `BriefingHonestyFilter.fullRewrite`
   replaces a zero-coverage region's slot list with `List.of()`, keeping its verdict word and weather
   fields: `STAND_DOWN` for a weather stand-down (and the unknown case), `AWAITING` for not attempted /
   failed (and away, but `AskSnapshotBuilder` drops a travel-day window whole, so an away region never
   reaches Ask). Its `sampleSufficient`/`forcedSample` are null, so `verdictEligible` reads false. A
   region the briefing stood down whole therefore reaches Ask as a region with a verdict and **no
   slots**, and nothing slot-level can count it. §2.1 counts such regions as regions.
3. **`list_windows` takes no arguments** and returns, per solar window, `id, day, event, time,
   verdict, bestRating, bestBet?, alsoGood?` (NON_NULL), then the night rows with no figure
   (`AskTools.listWindows`, `WindowInfo`). `bestRating` is the served figure with outliers left out
   (`AskSnapshotBuilder.bestRatingOf` → `PlanWindowProjector.bestRatingWithoutOutliers`) and is
   **window-wide, not scoped** — the one figure in the result that ignores the question's scope.
   `list_windows` records no evidence pair: only `rank_spots` and `rank_night_spots` do
   (`AskTools.finish`'s accepted callbacks), so a place named in `list_windows` alone cannot be a pick.
4. **Every tool result is re-sent on every later turn**, and the engine sends no `cache_control`
   (`ClaudeAskEngine.buildParams`), so a result's size is paid once per remaining turn. A turn can
   carry **several** tool calls (the engine runs every `ToolUseBlock` of a response;
   `ClaudeAskEngineTest.parallelToolCalls` pins three in one turn), and `max-turns` is 4 by default,
   configurable 1–6 — so the cap bounds tool *turns*, not tool results. Only `RESULT_CHAR_CAP` bounds
   output.
5. **`AskTools.RESULT_CHAR_CAP` is 6,000 characters per conversation**, checked BEFORE a result is
   added (`charsUsed + length > cap` refuses it), so a refusal can come well under 6,000 used. §2.2
   measures the extension against it.
6. **The validator does not fact-check prose** (`AskAnswerValidator`'s own "residual, stated"): a
   summary is word-capped, brand-sanitised and URL-stripped, never checked against the tool results.
   Every card fact is re-joined from served data.
7. **Ready `BEST_*` answers are anchored**: pick 1 must be on the window `list_windows` marks
   `bestBet` (`AskAnswerValidator.BestAnchor`; the anchored window must hold a pick-eligible slot in
   scope, and its javadoc allows for "a BEST BET whose own location is under 3★"); while such a window
   exists a BEST answer with no pick is discarded ("pick 1 is not on the BEST BET window"), and so is an
   unanswerable one. `ReadyRelevance` refuses a pick question that comes back with no pick, so nothing
   is stored. `BEST_WEEKEND`, `BEST_SOON` and `BEST_NEXT` are offered on any ≥ 3★ candidate in scope
   (`ReadyQuestion`), so on the owner's weekend they ARE offered.
8. **The Plan card's histogram is reach-gated and per reader** (`windowFirstSpread.js`, over
   `card.pool`; bars only when `rated >= 5` and `rated >= total × 0.5`). Ask is user-less on the
   Ready path and scope-wide on both, so its population differs by construction (§2.3).
9. **Floors.** `AskSnapshot.MIN_PICK_RATING` = 3 at any distance. `NeighbourOutlierGuard.NAMED_FLOOR`
   = 3 and the Plan card's `NEIGHBOUR_NAMED_FLOOR` = 3 apply **only after an outlier has been
   skipped**: with none skipped the BEST BET destination and the card's best-reachable line name the
   pool's head at any rating, 1★ included (`windowFirstCards.bestReachOf`). So "three surfaces name a
   3★ at any distance" is true of Ask's floor and false of the other two, which have no floor at all
   below an outlier skip (§2.5).
10. **Production shape:** 4 enabled regions (plan §1 #3); 253 enabled locations and 61 coastal
    (CLAUDE.md's 2026-10-02 production read and its tide bullet; a roster, not a rated count);
    near-term (T+0/T+1) rated by Sonnet 5.5, FAR (T+2, T+3 SETTLED) by Haiku 4.5. Ask runs on
    `photocast.ask.model` = HAIKU (4.5).
11. **The existing Ask regressions are not wording-free**: `AskNightRegressionTest` already checks a
    summary names the alert level and the Pro-feature clause, and the thread class checks "miles of
    home". A wording assertion here has precedent (§2.6).

## §2 Design

### 2.1 Population: `AskSnapshot.Spread`, server-side, user-less, counts not curves

A pure value computed per **region** in `AskSnapshotBuilder` and summed per window **at tool time
over the regions in scope** (`AskTools.listWindows`). Scope is whole regions matched by name
(`AskScope.contains`), so the sum is exact; unregioned slots never enter the snapshot (the builder
walks `summary.regions()`), so they are never counted, as `rank_spots` never returns them. One pass
over ≤ 253 slots × 6 windows at snapshot build, memoised with the snapshot (30 s).

**Regions first.** Every in-scope region of a window is one of four kinds, read off what the
honesty filter served (fact 2) and the sample gate (`verdictEligible`):

| kind | test | counted as |
|---|---|---|
| **counted** | has slots and `verdictEligible` | its slots enter the bands and the pockets |
| **thin** | has slots, not `verdictEligible` (the Plan tab refuses it a verdict: fewer than 5 rated, or under half examined — the REAL `VerdictSampleGate`, over the pipeline's dispositions) | `regions.thin` — its slots are NOT in the bands |
| **stood down** | no slots, and the filter's reason was `STOOD_DOWN` — a real weather stand-down | `regions.stoodDown` |
| **not scored** | no slots, and the reason was anything else: `AWAITING` (not attempted, failed) **or the `UNKNOWN` the filter also words as `STAND_DOWN`** | `regions.notScored` |

⚠️ **The served verdict word is not enough to tell the two slotless kinds apart**: `fullRewrite`
words `CoverageReason.UNKNOWN` — no disposition record, a tie, an unparseable category, a renamed
location, a failed lookup — as `STAND_DOWN` exactly like a weather stand-down (fact 2), and
`CoverageReasons` answers `UNKNOWN` whenever the record cannot settle the cause. Reading that word as
"poor everywhere" would present missing evidence as bad weather during a partial disposition lookup
(Codex's round six). So the filter's reason rides the served region: `BriefingRegion.coverageReason`
(nullable, NON_NULL-serialised, set only by `BriefingHonestyFilter.fullRewrite` on the API path, so
it is never persisted and a legacy payload reads null), copied onto `AskSnapshot.Region`, and only
`STOOD_DOWN` counts as stood down. A null reason on a slotless `STAND_DOWN` region (a payload from
before the field) is not scored — unknown is never "poor", the safe direction. The Plan tab ignores
the field. ⚠️ **It must survive every `BriefingRegion` wither** — `withMeanRating`, `withBestRating`,
`withMeanRatingDelta`, `withSampleSufficient`, `withForcedSample`, `withLightlyEvaluated`,
`withConfidence`, `withGloss`, `withSlots` (nine today) — because `assembleForPlan` rebuilds each
region AFTER the filter: `withMeanRatingDelta` in the movement step, `withSlots` in `stripSlotTide`
always and in the neighbour guard sometimes. A wither that drops it turns a real `STOOD_DOWN` into
null before `AskSnapshotBuilder` sees it, and the region reads "not scored" — the exact shape
`neighbourOutlier`'s javadoc already warns of for its own field (Codex's round ten). So the test is
the ASSEMBLED payload — a `ServedBriefingAssemblerTest` case that reads `coverageReason` off
`getCachedBriefingForApi()`'s region after the whole chain — not `BriefingHonestyFilter`'s immediate
result alone, with a mutant on each wither.

Why thin regions are out of the bands: `rank_spots` returns only pick-eligible slots, which require
`region.verdictEligible()`, so counting a thin region's 4★ in `gameOn` would tell the model "2 are
game on" and then hand it one. The population of the bands is exactly the population `rank_spots`
draws from, less the floor — "2 at 4★ in the spread" is the same two places `rank_spots` returns at
4★. The thin region is reported as a region, not lost. ⚠️ This reuses the served `verdictEligible`
rather than inventing a thin test of Ask's own: the first cut of this plan had one, over
`rated + stoodDown`, and the review showed it was the P1-A mistake (a served `STAND_DOWN` on an
unrated slot is the briefing's own weather verdict and can mark a slot the batch never looked at)
and that it read an all-blue window as "thin", the opposite of the owner's "all blue: stop". With
regions counted by served kind, an all-blue window is `regions: {stoodDown: 4}` with nothing rated —
and the prompt says what that means (§2.3).

**Within a counted region, a slot is in the population when it is:**

- **not canopy** — a wood's star means the opposite of a sky star and a wood is never a pick. ⚠️
  Deliberately NOT `BriefingSlot.votingSlots`, whose all-canopy fallback lets a wood vote when a
  region holds nothing else: an all-canopy region contributes nothing and is never a pocket, because
  a wood cannot vote on "is this sky worth going out for". A stated divergence from the Plan tab's
  verdict roster.
- **not held back** — a slot the neighbour guard marked (`Slot.neighbourOutlier`) that would
  otherwise be pick-eligible (`AskSnapshot.isHeldBack`, the very test `rank_spots`' note counts) is
  out of the bands, `gameOn` and every pocket, and counted per window as `heldBack` (an integer,
  never a name, id or star), so the model sees one held-back number here and in `rank_spots`' note.
  A marked slot that is NOT otherwise eligible (say a combined 2★ whose `skyRating` the guard
  flagged) is simply in its band. ⚠️ This diverges from the Plan card, whose histogram, pool and
  counts keep the outlier (G3, `changelog.d/20261017-neighbour-guard-client.md`), on purpose: the
  card's bars describe what is rated; the spread decides whether a window is worth going out for,
  and a lone 4★ among 1★ neighbours must not make a pocket.
- **rated** when `rating` is on Claude's 1–5 scale (`RatingValidator.isInRange`) — it falls into
  its band; otherwise **unrated**, and among the unrated those with served verdict `STAND_DOWN` are
  also counted as `stoodDown` — informational only, the owner's "blue" at slot level, keyed to no
  test (the P1-A reason above). A malformed rating is dropped, as everywhere else.

**The rating.** The **combined** served `rating` (`claudeRating`), not `skyRating`. Three reasons:
(a) it is the one scale every figure the model sees is on — `rank_spots`' `rating`, the pick floor,
`bestRating`, the Plan card's histogram — so no answer can carry two stars for one place; (b) the
owner's scale is about whether to GO, and for a coastal place the combined star already folds in
whether the water is the water it wants (`TideVisitor`, the R1 penalty) — a 4★ sky over the wrong
water is not "game on" for that place; (c) `skyRating` is null on rows written before V164 and on
every synchronous-engine row, and `AskSnapshot.Slot` does not carry it. The neighbour guard reads
`skyRating` because it asks a different question — is this star noise against its neighbours' — and a
tide difference between neighbours is not noise. The spread asks the owner's question.

**Counts, never a fitted curve.** Ratings are whole stars and lumpy (Sunday sunrise is 98 / 100 / 2);
a mean and a standard deviation would hide exactly the shape the owner reads. The bands are five
integers.

**Per window, over the scope** (`AskSnapshot.Spread`, serialised NON_NULL):

| field | meaning |
|---|---|
| `over` | the population's name for the prose: `"every region"`, `"Northumberland"`, or the scope's names joined (`"Northumberland and Lake District"`) |
| `rated` | rated population size |
| `bands` | `[n1, n2, n3, n4, n5]` |
| `gameOn` | `n4 + n5` |
| `gameOnPct` | `round(100 × gameOn / rated)`, 0 when nothing is rated |
| `go` | `n5` |
| `below4` | `rated − gameOn` — returned so "178 of 180 are 3★ or below" is a figure a tool returned, not arithmetic the rules forbid |
| `stars1to2` | `n1 + n2` (named for what it is: the prompt's "stay-home window" is a window with almost nothing at 4★+, which is mostly 3★ — a field called `stayHome: 56` under such a window would invite "only 56 are stay-home") |
| `unrated`, `stoodDown`, `heldBack` | omitted when 0 |
| `regions` | `{counted, thin?, stoodDown?, notScored?}` — the four kinds above, zeros omitted |
| `pockets` | the counted regions holding ≥ 1 game-on place: `{region, gameOn, mean}`, `mean` that region's rated mean to one decimal over this population; sorted `gameOn` desc, `mean` desc, name; at most **4**, with `morePockets: n` when cut |

**Pockets are regions, nothing finer.** A pocket on the heat map can be two places in a 60-place
region; `rank_spots` with `regionNames` already names them, best first, with their tide facts. A
pocket needs a finer name only when it is a dozen places in one part of a region — §3.

**Fail-soft.** A window with no counted region has `rated: 0`, `bands: [0,0,0,0,0]`, no pockets and
its `regions` tallies; the field is always present so the model never reads absence as "not scored".

### 2.2 The wire shape, and what it costs — measured on a mock, estimated in tokens

`scripts/measurements/ask-window-context/measure.py` (committed with this plan) builds the exact
NON_NULL compact JSON `AskTools.finish` serialises — the shared `AppConfig.objectMapper()` is a plain
`new ObjectMapper()` with no indentation, checked — for six solar windows plus two astro rows and one
aurora row (a PRO asker), with and without §2.1's spread, every field included. **Characters are
measured on that mock; tokens are character counts ÷ 4 and ÷ 3.3** (the two ends of what compact
JSON costs on Claude's tokeniser; no API key on this machine for `count_tokens`). The exact figures
are `AskTools.ToolCall.chars` in a dry-run trace and `api_call_log.input_tokens` per turn — §7 says
how to read both.

| `list_windows` result | chars | tokens (÷4 … ÷3.3) |
|---|---|---|
| today, no spread | 1,059 | 265 … 321 |
| the owner's weekend (one or two pockets a window) | 2,455 | 614 … 744 |
| a busy 4-region day (every region a pocket, every window) | 3,519 | 880 … 1,066 |
| a busy 8-region day, pockets capped at 4 | 3,513 | 878 … 1,065 |
| `rank_spots`, 5 spots / 8 spots | 1,691 / 2,699 | 423 … 512 / 675 … 818 |

The pocket cap of 4 is what keeps a busy day flat as the roster grows (uncapped, an 8-region day
was 4,365 in the first cut's mock); the derived fields cost under 100 characters a window and save
the model doing arithmetic the rules forbid.

**Against the 6,000-character cap (fact 5).** A busy day's `list_windows` (3,519) plus one
five-spot `rank_spots` (1,691) is 5,210 and fits; plus an eight-spot one (2,699) is 6,218 and is
refused; a second five-spot one (6,901) is refused — so the model is told to answer one tool short
of the owner's "look at the other pockets" exactly on the day with pockets to look at. **`RESULT_CHAR_CAP`
rises to 9,000** with this change (≈ 2,250–2,700 tokens of tool output at most; `AskToolsTest` pins
the literal and moves with it). A test pins the busy-day `list_windows` under half the new cap.

**Per conversation.** The fixed prefix every turn re-sends is the system prompt (`RULES` ≈ 3,100
runtime characters after the text block's indentation is stripped, `WITH_USER` ≈ 600; `USER_LESS`
≈ 90 for a Ready conversation) and the six tool schemas (≈ 5,000 characters of descriptions plus
structure, ≈ 6,500), plus the API's own tool-use overhead: call it **P ≈ 2,900 tokens today, ≈ 3,200
after this change** (§2.3 adds ≈ 1,100 characters of criteria and hint and one sentence to a tool
description). On Haiku 4.5 (`cost.haiku-input-usd-per-mtok` 1.00, output 5.00):

- **The owner's weekend, typed, today:** `list_windows` → `rank_spots` → forced `submit_answer`,
  three turns; input ≈ 3P + 300 (the list, re-sent once) + 300 + 470 (five spots) + ≈ 100 of
  assistant turns ≈ **9,900 tokens**, output ≈ 220: ≈ $0.011.
- **The owner's weekend, with the spread — the SAME path, because the weekend has pockets** and the
  hint says drill into a pocket (and §2.3's Ready answer carries the exceptions, which needs
  `rank_spots`): 3P′ + 680 + 680 + 470 + 100 ≈ **11,500 tokens**: ≈ $0.013. **The owner's example
  costs about 1,650 tokens, ≈ $0.002, MORE per question.** The benefit there is the answer, not the
  money. The first cut of this plan booked a saving here; the review caught it.
- **An all-blue window** (no pocket anywhere in scope, the "all blue: stop" case): today still
  `list_windows` → `rank_spots` → submit ≈ 9,900; with the spread `list_windows` → `submit_answer`,
  2P′ + 680 ≈ **7,100 tokens** — ≈ 2,800 fewer, ≈ $0.003, and one round trip fewer (a Haiku tool
  turn is of the order of a second or two; unmeasured here, the engine's 30 s deadline is the
  budget it matters against). This is the only case that saves, and a no-pick typed answer is
  never cached (`CaffeineAskAnswerCache.store` returns early with no pick and no event), so a
  stay-home answer also gives up the shared cache hits a 3★-pick answer used to earn. Stated.
- **A good day:** the spread adds ≈ 380 tokens to each turn after `list_windows` and the prompt
  ≈ 300 to every turn: ≈ +1,700 tokens on a three-turn answer, ≈ +$0.002; a busy day ≈ +2,300,
  ≈ +$0.0025.
- **Ready precompute:** the catalogue is 8 questions, of which at most 7 are offered per scope
  (`BEST_WEEKEND` and `BEST_SOON` exclude each other) and the four pick-window ones
  (`BEST_WEEKEND|BEST_SOON`, `BEST_NEXT`, `COASTAL_HIGH`, `AM_OR_PM`) are the ones that would call
  `list_windows` — the model's choice, nothing forces it; `NIGHT_SKY` may too. 5 scopes × 4 ≈ 20
  conversations a cycle; the pipeline dispatches two cycles a day (the astro job's run is
  NIGHT-ONLY and asks `NIGHT_SKY` alone), `ready.max-cycles-per-day` allows six. ≈ 40 conversations
  × ≈ +1,650 tokens ≈ **+$0.07 a day typical, +$0.20 at the ceiling**, outside the typed spend cap
  (Ready spend is excluded from it) but real money, stated. A region scope's spread is one pocket at
  most, so most of that is the `ALL` scope.

Prompt caching would not rescue any of this: Haiku 4.5's minimum cacheable prefix is 4,096 tokens
(`all-in-architecture-brief.md`, measured there for the sky prompt) and Ask's whole prefix is
≈ 3,200, so a `cache_control` breakpoint on it would cache nothing. Not proposed.

### 2.3 The prompt: criteria, a hint, the tide, and the Ready wording

Three changes to `AskPromptBuilder` and one to the schemas. ⚠️ `RULES` is shared by Ready and typed
conversations and by fresh questions and follow-ups, so the criteria go there and anything that
mentions home does not (CLAUDE.md: a Ready answer can never mention home, drive or reach). The
wording below is the plan's draft, to be tuned against §2.6's regression, not a byte-exact spec.

**The criteria (into `RULES`, replacing the "give 2 or 3 picks" clause and following the "nothing
worth going for" rule):**

> How to judge a window. Readers rate like this: 1★ and 2★ are stay home; 3★ is marginal; 4★ is
> game on; 5★ is go. list_windows gives each window's spread: how many rated places sit at each
> star, how many are game on (4★ or better), the regions that hold a game-on place (the pockets),
> and how many regions were stood down whole or not scored. Read the spread before the best rating.
> A window whose rated places are nearly all 3★ or below, with a handful at 4★, is a stay-home
> window with a few exceptions, not a good window: say so plainly, quoting the figures as returned
> ("of 180 rated places, 2 are 4★"), and name the exceptions as exceptions, with their region. A
> window that is broadly game on is one to get excited about: say so and give picks. A window
> whose regions were all stood down is poor everywhere: say so. A window whose regions are mostly
> not scored or thin is mostly unlooked-at: say that, never that it is poor. Never call a window
> good because its best rating is 4★ or 5★ when its spread says otherwise.
> Picks are the places worth going to, best first, up to three at different spots: on a stay-home
> window only the game-on exceptions — one is fine, none is fine; never fill a pick list with
> marginal places.

The last sentence replaces "give 2 or 3 picks" and `submit_answer`'s "Two or three different
spots" (→ "Up to three different spots, best first"). The review found that instruction is where
the 3★ fill comes from: with two game-on places the model is told to add a third. This is the
cheapest part of the whole plan and probably the biggest.

**The 3★ clause, split by conversation.** `WITH_USER` gains: "A 3★ place is worth considering only
close to the reader's home; say so when you offer one." `USER_LESS` gains: "Never call a 3★ place
worth the trip; a 3★ pick is only ever the forecast's own BEST BET or ALSO GOOD, named as the
forecast's pick." Neither names a home. (What the validator does about 3★ is §2.5.)

**The hint (into `RULES`, immediately after the criteria):**

> A good investigation usually runs: list_windows; if none of the windows the question is about
> holds a game-on place, say so and stop; if one of them is broadly game on, rank_spots there; if
> they have pockets — even a couple of exceptions on a stay-home window — rank_spots in those
> regions and windows, and for a coastal pocket read tideState and tideAligned and check
> get_hot_topics for a spring or king tide; then pick, naming an exception as one. Questions this
> does not fit — one place, one tide, one event, the night — need none of it.

⚠️ "The windows the question is about", never "every window in scope": `scope` is geographic and
`list_windows` returns every live window, so a Ready `BEST_WEEKEND` whose weekend holds only 3★
would otherwise be steered to a 4★ on Monday, which `ReadyRelevance.admitsPick` rejects (its id is
not in the offer's `windowIds`) and the precompute stores nothing (Codex's round five). So a Ready
conversation's system prompt names its windows — `AskPromptBuilder` gains "The question is about
these windows: <ids>", the way it already names a context window — and the server's `hasGameOn` is
evaluated over exactly those ids. A typed question names its own windows in its words and the model
reads them off `list_windows`. ⚠️ **The ids named are the windows the question COVERS, never the
offer's `windowIds`**: for `BEST_WEEKEND` and `BEST_SOON` the offer's ids come from
`ReadyQuestion.windowsWithCandidates` — only the windows holding a ≥ 3★ candidate — so an all-blue
Saturday beside a game-on Sunday is NOT among them, and naming them would tell the model "this
weekend" means Sunday and turn a mixed weekend into a good one (Codex's round eight). `Offer` gains
`coveredWindowIds` — `BEST_WEEKEND`: every rendered Saturday and Sunday window; `BEST_SOON` and
`COASTAL_HIGH`: every rendered solar window; `BEST_NEXT`: the next window; `AM_OR_PM`: both windows
of its date — read by the prompt line and by `hasGameOn`; `windowIds` keeps its two jobs, the offer
gate (candidate presence) and `ReadyRelevance.admitsPick`'s set.

⚠️ The stop condition is "no game-on place anywhere", never "every window is stay-home": the owner's
weekend is stay-home AND has exceptions, and a hint that stopped on the first would leave a Ready
`BEST_*` answer with no pick while `gameOnCandidates` is non-empty, which the anchor and
`ReadyRelevance` then discard (Codex's round-two review of #1223).

**The thread.** `THREAD_RULES` gains one sentence: "A follow-up about the windows themselves ('so is
it a stay-home weekend?', 'how does Sunday look?') calls list_windows again." Without it the
likeliest answer is prose with no tool call and no pick, which the validator discards as "answerable
with no pick, no event and no tool call" — a FAILED run and a 502 for the reader.

**The tool description** (`AskToolSchemas.listWindows`): "… with the forecast's verdict, best
rating, the spread of its ratings (rated count, the count at each star, how many are game on, the
regions holding a game-on place, and the regions stood down or not scored) and its own BEST BET /
ALSO GOOD pick …". `rank_spots` gains nothing: its tide fields (`tideState`, `tideAligned`,
`tideFitPhrase`) are already the "red at the coast → what is the water doing" step, and the spring
and king topics are already `get_hot_topics` (`KING_TIDE`, `SPRING_TIDE`), which the hint names.

**What the hint is not.** It is not an order of tool calls the engine checks, and nothing in the
validator reads the trace for it. The regression (§2.6) catches a bad investigation as a bad
answer, not a wrong sequence. If the owner later wants the ladder enforced, that is a change to the
principle and a new plan.

**The Ready questions.** A Ready answer is shared by every reader and its conversation is user-less,
so its spread is the whole roster (`ALL`) or one region — never the reader's reach. The Plan card's
histogram beside it IS reach-gated. The two will show different numbers for one window (the owner's
"180 rated within 2h30" against Ask's "of 220 rated across every region"), and that is correct, so
the words must name the population: `USER_LESS` gains "When you quote the spread, say which area it
counts, as the result's `over` says — 'across every region' or 'in Northumberland' — never 'within
reach'"; `WITH_USER` the same sentence plus "the spread counts the whole area, not the places within
the reader's drive; a drive filter is rank_spots' alone." Per question:

- **`BEST_WEEKEND`** on the owner's weekend: offered (fact 7); the answer reads "Not a weekend to go
  out for across every region: of 180 rated places on Saturday evening, 2 are 4★ … The exceptions
  are Bamburgh and Seahouses on the Northumberland coast." and carries those two as picks.
- **`BEST_SOON`** (offered only when no weekend window qualifies): the same shape over the next few
  days — "nothing in the next few days is broadly on; the one pocket is …".
- **`BEST_NEXT`** ("Best spot tonight?", one window, the commonest daily case): "Not worth going out
  for tonight across every region: 1 of 160 rated places is 4★ …" with that one place as the pick, or
  with no pick when there is none (below).
- **`AM_OR_PM`** and **`COASTAL_HIGH`** are comparisons and get the spread as context; their offer
  rules are unchanged, but they are pick questions too (`ReadyQuestion.picks()`), and the hint tells
  the model to stop with no pick when their windows hold nothing game-on — so the no-pick admission
  below applies to **every solar pick question**, not `BEST_*` alone, or these two would be discarded
  every cycle on exactly the days the hint fires (Codex's round five). `NIGHT_SKY` is untouched (a
  night's eligibility is its own, `isNightPickEligible`).

**A "best" question with NO game-on place at all — a set test, not a threshold.** Today such an
answer cannot exist: the hint says "say so and stop", the model submits no pick, the anchor discards
it, `ReadyRelevance` refuses a pick question with no pick, and every scope pays for a conversation
and stores nothing, every cycle. This plan lets the honest answer through on one crisp condition:
`AskSnapshot.gameOnCandidates(windowIds, scope, predicate)` — pick-eligible slots rated ≥ 4 on the
question's windows **that the question itself admits** — **is empty**. The predicate is the
question's own `ReadyRelevance.admitsSlot` (a `COASTAL_HIGH` answer may carry only a high-water
coastal pick, so a 4★ inland place must not count as "game on" for it and discard its honest no-pick
answer, nor withhold a stored one when such a place appears later — Codex's round six); for a
`BEST_*` or `AM_OR_PM` question and for a typed question it is every slot. Then the anchor is not applied (there is nothing to lead with) and
`ReadyRelevance` admits the no-pick answer, which is stored with its carried figures (§2.4) and
served. ⚠️ **The admission is ONE predicate, `AskSnapshot.hasGameOn(windowIds, scope)`, consulted
directly by every site that decides whether a no-pick answer stands — never inferred from the anchor
lookup.** Three sites read it: the validator's anchor rule ("pick 1 is not on the BEST BET window" is
not raised for a no-pick answer when the predicate is false), `ReadyRelevance.violation` ("a pick
question with no pick" is not raised when it is false, for every SOLAR pick question — one method,
called at store AND serve), and
`AskReadyFreshness.check`'s own anchor test (`picks.isEmpty() || pick 1 not on the lead`, skipped when
it is false; without that the stored no-pick answer would be withheld on its first serve — Codex's
round-three review of #1223). `anchoredWindow` itself is **unchanged**: it is already empty when the
forecast-wide BEST pick's region is outside a region-scoped question, and reading that emptiness as
"no game-on place" would store a "nothing worth going for" answer for a region holding a 4★ that
`rank_spots` returns (Codex's round four, the correction to round three's cut). The live behaviour
falls out: if a 4★ appears on those windows before the next precompute, the predicate flips, the
anchor and relevance tests apply again, and the no-pick answer is withheld as stale, as a stale pick
would be. The answer is served as "Nothing worth going out for this weekend across every region: of 180 rated places none
is 4★ …". When the set is NOT empty the anchor stands as today: pick 1 on the BEST BET window —
the forecast's one crown, which the Plan card's medallion shows, and which Ask must not contradict
(`plan-panel-data-contracts.md`'s aggregator divergence) — worded by the criteria. ⚠️ The anchored
window can be one whose own pick is 3★ while another window holds the 4★ exception (fact 7: the
validator tolerates a sub-3★ BEST BET location); the answer then leads with the forecast's pick
"named as the forecast's pick" and carries the exception second. That is the price of one crown;
the alternative — relax the anchor to "the window with the most game-on places" — makes Ask and the
Plan card disagree on the same weekend. **Owner decision Q2** (§6) if they want that trade the
other way, or want `BEST_*` not offered at all on such a weekend (which makes the Ready list go
quiet exactly when the owner wants it to say "stay home").

### 2.4 Validator and freshness: how a quoted figure stays honest

**The figures are offered as context and the prose is the model's — as every Ask summary is today
(fact 6).** No prose fact-checking is added, and here is why: the one cheap rule ("every integer the
summary states must appear in a tool result") rejects honest arithmetic and passes dishonest quoting
("180 places are game on" — 180 is in the result). `below4` exists so the honest sentence is a
returned figure, but a rule that polices prose for figures is the fitted curve one step removed.
What keeps a figure honest is that **the reader can see the served figure beside the prose** and
that **a figure that has moved withdraws the answer**.

**Carried, not quoted (W1 — moved in from W2 after Codex's round seven: a prompt that quotes figures
cannot ship a release before the figures are carried and rechecked, or a refused precompute leaves a
stale count in a stored answer whose picks are unchanged).** `AskAnswer` gains `windows: [{windowId, spread}]` — the WHOLE
`AskSnapshot.Spread` of §2.1 (`over`, `rated`, `bands`, `gameOn`, `gameOnPct`, `go`, `below4`,
`stars1to2`, the tallies, the pockets with their means), not a hand-picked subset, for **every window
whose spread the model could have quoted**: every solar window `list_windows` returned in that
conversation (the tools record it, as they record every offered pair), plus the picks' SOLAR windows
(a night pick's window is an ASTRO or AURORA row, which has no `Spread` — §2.1 defines it for solar
windows only — so a night pick adds nothing to the carried set, and a stored `NIGHT_SKY` answer, or a
typed night answer, is never withheld for want of a spread it could not have; a freshness test pins a
stored `NIGHT_SKY` answer fresh under this rule — Codex's round ten) and,
for a Ready question, **the offer's `coveredWindowIds`** — the temporal set the prompt named, never
the candidate-bearing `windowIds` alone, so a described all-blue Saturday is carried and rechecked
even when no pick is on it — at most the six rendered events, ≈ 150 characters each. Built by the
validator from the live snapshot the way every card fact is (never from `submit_answer`, which has
no such field), for the stub's answers exactly as for Claude's: the stub makes no `list_windows`
call, so for it the carried set is the covered windows plus the picks' (Codex's round nine). A typed no-pick answer carries them on the wire only (it
is never cached). ⚠️ Codex's review of the first cut (#1223) found both halves of this: carrying
only the picks' windows let an unpicked Sunday's figures go stale in the prose while a Saturday pick
kept the answer live, and carrying only `rated` and `gameOn` left a 5★ → 4★ move (same `rated`, same
`gameOn`, a different `go`) and every move inside the 1–3★ bands invisible to the freshness test.
The rule is now: carry everything the prose may quote, compare everything carried.
⚠️ **It must be threaded through every rebuild of `AskAnswer`** — the review counted nine `new
AskAnswer(` sites in main code, among them `ReadyRelevance.relevantPart` (what the precompute
actually stores) and `AskReadyFreshness.recheck`'s own rebuild; a field dropped at either is a stored
row with no figures, read as "unknown, not stale", and no test fails. W1's test is therefore "a
stored Ready answer carries `windows`", with a mutant on each site. Stored in
`ask_ready_answer.answer_json` beside the picks (no migration — a JSON property, the `astroVersion`
precedent) and re-joined live on serve. The client (`askConversation.js`'s `normaliseAnswer`, which
builds the stored answer from a fixed field list — not `askModel.js`, which only joins picks) keeps
the field and, if the owner wants it (Q4), `AskConversation` draws one quiet line per window under
the summary — `Sat sunset · 180 rated across every region · 2 at 4★+` (not "game on": the owner's
word is a criterion in a prompt, not served copy) — so the server's figure stands beside the model's
sentence. Deliberately not the Plan card's five-bar histogram: that one is reach-gated per reader,
and two histograms with two pools on one screen would read as a contradiction; a labelled line does
not. The thread does NOT echo it — `exchangeOf` reduces an exchange to ids and words, never a card
fact, before `threadForWire` sends it. **As built (W2, 2026-10-20; the owner's rules for the line):**
the line reads `rated`, `gameOn`, `go` and `over` off each window's record and sums nothing across
windows; `· N at 5★` only when `go` > 0 and `none at 4★+` when `gameOn` is 0; a region scope reads `in
<names>` (the server's own joined `over`). While any carried window is rated, a window with `rated` 0
draws no line (the line stands beside a count the prose may quote; beside a rated window a second,
countless line is better silent). When NO carried window is rated, every drawable window gets its own
line — `Sat sunset · nothing rated across every region` — carrying that window's served tallies,
`· N regions stood down` and `· N not scored`, read verbatim off `spread.regions`, so the owner's
all-blue stood-down window (§2.1) never reads like one nobody scored (an adversarial-review finding
against the first cut, which collapsed every unrated case to one "Nothing rated" line). An answer with
no `windows` draws nothing. The visible text is `aria-hidden`; a spoken twin voices the long weekday and
the counts in words ("Saturday sunset: 180 rated across every region, 2 at 4 stars or more"), the first
one led by "Rated places, by window." because a live region reads inserted text, not a list's label.
**Removed 2026-10-27:** the owner reversed Q4 and the line is gone from the answer card (it repeated the
summary and pushed the picks down on a phone). `windows` is still served and still drives
`AskReadyFreshness`; the client no longer keeps or draws it.

**Freshness (W1, with the carried figures).** `AskReadyFreshness.recheck` gains `spreadMoved`: a stored answer is withheld whole
when, for ANY carried window, the live `Spread` is not EQUAL to the stored one — record equality
over every field, pockets included, so no figure the prompt lets the model quote can drift unchecked
— and **a carried window that is no longer in the live window set is stale, not skipped**: a
Friday-built `BEST_WEEKEND` answer that quotes Saturday's spread while its one surviving pick is on
Sunday must go when Saturday passes, because the Saturday prose can no longer be verified (Codex's
round-two review of #1223; the first revision skipped passed windows) — the same all-or-nothing rule as a pick whose rating moved, in the one shared code
path the typed cache's re-check also uses (`CaffeineAskAnswerCache.lookup` calls `recheck`), so a
cached typed answer is withheld on the same test. (`Spread` is a record of ints, strings and lists,
so `equals` is the comparison and no tolerance exists to tune.) **And the covered set itself is
compared, not only the spreads in it**: a Ready answer stores the offer's `coveredWindowIds` it was
built over, and at serve the live offer's covered set must equal it — a live covered window absent
from the carried set (a travel day unmarked, a rendered set that has filled in) is stale, because the
answer never considered that window; a carried window absent live is the vanished case above.
`AskReadyFreshness.check` compares offer ids only for `NIGHT_SKY` today, so this is a new comparison
for the solar pick questions (Codex's round eleven). A row with no figures (written before W1) is
"unknown, not stale" — the `forced`/`submittedAt` convention. **How often it fires is not "rarely"**:
the `ALL` scope's spread moves whenever any slot anywhere gains, loses or changes a rating or a mark, and a
precompute that is refused or discarded leaves the earlier row standing, so between a cycle that
moved the figures and the precompute that rewrites them (minutes, normally; longer if the
`max-cycles-per-day` ceiling is reached) the answer is withheld. That is the correct direction — a
"2 are 4★" sentence over a window that now holds 5 is the thing this exists to prevent — and it is
measured in §7 before it is tuned.

**Rollback truth.** `AskReadyStore.decode` uses a plain `new ObjectMapper()` (unknown properties
throw) and reads `astroVersion` by removing it explicitly before `treeToValue`; a stored `windows`
read by a server that does not know it is caught and the row is dropped (`Optional.empty()`) until
the next precompute rewrites it — invisible, not ignored. **W1 ships the tolerance
(`@JsonIgnoreProperties(ignoreUnknown = true)` on `AskAnswer`, or the `astroVersion` treatment for
`windows`) in the same release as the field**, so any LATER field is read fail-soft by a W1 server;
a rollback from W1 to the release before it drops every W1-written row until the next precompute
(no earlier build has the tolerance) — one cycle of an empty Ready list, stated as the cost.

**The thread.** A Ready-origin exchange is exempt from the `generatedAt` reset because its picks
re-join live (`AskThread`); a summary that quotes spread figures is verbatim prose that can go stale
the way a night summary can, and the night reset (`nightVersion`) is the precedent. Accepted and
stated rather than built: the figures in a stale earlier summary reach the model as "I answered …",
which the thread rules already treat as the reader's own words, not facts; the carried `windows`
field is not echoed, so no stale figure is served as a card. If the owner wants it closed, the shape
is a `spreadVersion` on the exchange, the night rule's twin — W2+, not here.

**The validator's new rules are the two above and nothing else.** Pick eligibility, the outlier
refusal, the thread rules and the word caps stand. The validator does NOT refuse a 4★ pick on a
stay-home window — the exceptions ARE the honest picks for a reader who wants to go somewhere (Q7);
the fault was the framing and the fill, which are the prompt's.

### 2.5 3★ at a distance — the owner's product decision (set out, not decided)

The owner's scale says 3★ is worth it close to home. Ask's `MIN_PICK_RATING` names a 3★ at any
distance; the BEST BET destination and the Plan card's best-reachable line name the pool's head at
ANY rating with no outlier skipped and apply the 3 floor only after one (fact 9) — so the Plan
surfaces are not "3★ at a distance", they are "whatever is best, at any distance", and only Ask has
a floor to argue about. "Close" has two existing measures: `nearHome` (the saved local radius,
straight-line miles, default 22; the retired Close-to-home panel's measure) and `maxDriveMinutes`
(measured drive). A LITE account may have a home and no drive times; an account with no postcode has
neither. The owner's own number for "close" is theirs to give (Q1b).

| option | what changes | cost |
|---|---|---|
| **A. Words only** | nothing in the validator; the criteria and the no-fill rule (§2.3) mean a 3★ is offered only when nothing game-on exists, worded "marginal" and, with a user, "only if close to home"; a Ready answer never calls a 3★ worth the trip | the rule is a criterion, not enforced; a 3★ two hours away can still be a pick when nothing better exists; every surface keeps agreeing with every other; S |
| **B. Distance-bounded 3★ in Ask** | a 3★ pick survives only in a `personal` conversation with `nearHome` or `maxDriveMinutes ≤ N` (the owner's N), or as the window's BEST BET / ALSO GOOD destination; a Ready (user-less) answer carries only 4★+ picks and the forecast's pick; `rank_spots`' note says how many 3★ it is not offering and why | ⚠️ **amends the principle**: a judgement criterion becomes code enforcement, and the plan says so; a reader with no home never gets a 3★ pick; **not an M**: `AskSnapshot.candidates(window, scope)` is the one definition of pick-eligible that `rank_spots`, the BEST anchor and the five solar pick questions' `ReadyQuestion.offer` share, so B needs a per-conversation eligibility (user and distance) threaded through all three and through freshness, or `BEST_*` stays offered and anchored on a window B then empties and is discarded every cycle — L; a region-only crown (`PickInfo` with no location) leaves nothing to exempt; Ask and the Plan line disagree on the same window |
| **C. 4★ is the floor in Ask** | `MIN_PICK_RATING` → 4; 3★ is a star, never an Ask pick | S; the honest version of "3★ is marginal" with no distance machinery; the owner's "close to home and bored" case is lost in Ask; `BEST_*` is offered less often (its offer reads `candidates`), which is also when the owner wants it to say "stay home" — §2.3's set test then does the saying; the Plan surfaces are untouched (they have no floor to raise, fact 9) |
| **D. C plus a Plan-card floor** | the card's best-reachable line names a sub-4★ head only under the 45m or 1h30 reach tier (`REACH_TIERS`), "no clear pick" above it | the surfaces agree on "marginal is for close"; a new client rule with a spoken reason; M; a visible product change on most mediocre windows |

**Recommendation: A now, inside W1, then decide between B and C once W3 has measured A.** The
review of the first cut showed the complaint's mechanism is the fill instruction, not the floor: with
"up to three, never fill with marginal places" the owner's weekend answers with its two 4★ exceptions
and no 3★ at all, under any option. A costs nothing, enforces nothing, and keeps every surface in
agreement, which is the property this codebase defends hardest. If A measures badly — the model
keeps offering a 3★ two hours away when nothing better exists — C is the smaller honest step (one
constant, the set test already handles the "nothing" case) and B the larger one that keeps the
owner's close-to-home case; D only once the owner has read C on the Plan card. **Not decided here —
Q1.**

### 2.6 Measuring behaviour: two real-API regression cases

A new class, `AskWindowContextRegressionTest`, in the style of `AskPromptRegressionTest` (the real
engine, real prompt, real schemas, real validator, a fixed snapshot from a JSON fixture, tagged
`prompt-regression`, skipped without a key, a few pence a run) and, like `AskNightRegressionTest`
and `AskThreadRegressionTest`, a sibling that **neither edits nor shares an assertion with the
existing three classes**. Its assertions are written **pending the owner's approval** and say so in
the class javadoc; the owner owns them.

**Fixture: the owner's weekend** (`prompt-regression/ask/ask-stay-home-weekend.json`). Four
regions and the example's bands: Saturday sunset `[0,56,122,2,0]`, Sunday sunrise `[1,98,100,2,0]`
with 12 unrated, Sunday sunset `[0,43,133,4,0]`; the game-on places are named coastal spots (two on
Saturday, two on Sunday morning, four on Sunday evening), one of them the BEST BET at 4★; the 3★
and 2★ mass is synthetic inland slots the fixture builder expands from a band count
(`"synthetic": {"2026-10-17_sunset": [0,56,122,2,0]}` on a region → slots named `<Region> fell 07`),
because a 180-slot hand-written fixture is unreadable and the band is the fact. ⚠️ The bands are
the owner's REACH-GATED Plan-card counts standing in for a whole-roster spread the plan never
computed (the real one is §7 #2); the fixture is honest about being the example, not the roster.
`now` is Friday 16 October 2026 12:00 UTC, so the weekend is T+1/T+2 and `BEST_WEEKEND` is offered.
A third fixture variant, `ask-mixed-weekend.json`, has an all-blue Saturday (every region stood
down, no candidate at all) beside a broadly game-on Sunday, so that the prompt's "the windows the
question is about" is proven to name BOTH days and the summary is proven to call the weekend mixed,
not good — the case Codex's round eight showed the other two fixtures cannot reach, since every
window in them holds a candidate.
Scripts: (a) "Best spot this weekend?" as the Ready conversation (user-less, anchored); (b) the same
typed by a PRO asker with no drive times; (c) "anything for Sunday sunrise?".

**Fixture: a good weekend** (`ask-game-on-weekend.json`): the same roster with Saturday sunset
`[0,5,40,110,25]` and a BEST BET at a 5★ coastal spot. Scripts: (d) "Best spot this weekend?";
(e) "is Saturday evening worth going out for?".

**Assertions, pending the owner's approval:**

- (a)–(c): status OK, `answerable` true; picks **at most 3** (the fixture has 2 + 2 + 4 game-on
  places across the three windows, so a third 4★ pick is real, not fill), **every pick rated ≥ 4**,
  no pick at a synthetic 3★ place — under option A this is what the no-fill rule promises and
  under B–D what the code enforces, so it does not decide Q1; for (a), pick 1 on the BEST BET
  window (the anchor; the fixture's BEST BET is a 4★ exception, so the anchor and the criteria agree
  — a variant with a 3★ BEST BET is the §2.3 ⚠️ case and is written once Q2 is settled); the
  summary within `SUMMARY_WORDS`. **Under the other reading of Q7**, replace "at most 3, every pick
  ≥ 4" with "no picks"; one line.
- (a) only: `list_windows` in the trace — the precedent is `AskPromptRegressionTest`'s "rank_spots
  in the trace", and it is the only observable that the spread was read at all; no ordering, no
  count, no other trace assertion anywhere (a parallel `rank_spots` + `get_hot_topics` turn is a
  good investigation, fact 4).
- (a)–(c), **the wording assertion** (not the first in these regressions, fact 11): the summary
  matches `stay(ing)? (at )?home|not (a |the )?(weekend|window|morning|evening|day|night|one) (worth|to
  go)`, case-insensitive — the owner's words "stay home" and the plan's "not a weekend to go out
  for"; `not worth` alone is deliberately NOT in it ("not worth missing" would match). It exists
  because "called a stay-home window" is the owner's ask and has no structural proxy without a served
  verdict word (which §2.4 refuses). Owner's to keep or drop — Q3.
- (d)–(e): status OK; (d) picks 2–3 with pick 1 on the BEST BET window; (e) answerable with at
  least one pick or none, no shape prescribed for a yes/no question; every pick ≥ 4; the summary does
  NOT match the stay-home pattern.
- **Pass rate:** each script ten runs; 9 of 10 is the bar (`paired-control-worktree-for-prompt-evals`
  applies: tune the prompt against the control, never against its recorded baseline).

**Run** (the owner, on demand): `cd backend && ANTHROPIC_API_KEY=… ./mvnw test -Pprompt-regression
-Dtest=AskWindowContextRegressionTest`. The existing three classes run unchanged afterwards and must
still pass — their fixture's windows are broadly game on, so the criteria should not move them; if
they do, that is a finding, not a reason to touch their assertions.

## §3 Research: sub-regions (South Lakes against North Lakes)

**The question.** A "pocket" is a region in this plan. The owner's eye sees finer: the South Lakes
on while the North Lakes is under cloud, the Durham coast against the Northumberland coast. The
schema has no such concept — `regions` (4 enabled, with a base town) and `locations` (`lat`/`lon`,
`grid_lat`/`grid_lng` from Open-Meteo, `elevation_m`, `region_id`), nothing between.

**Two ways to get one.**

| | hand-drawn `locations.sub_region` | data-driven clusters |
|---|---|---|
| how | a nullable `VARCHAR` the admin Locations screen edits; the owner names 10–20 of them | k-means or agglomerative clustering over lat/lon (and elevation) per region, target 10–25 places a cluster, run offline |
| naming | the owner's words, stable, meaningful to a photographer ("Wasdale", "Durham coast") | a centroid has no name; the nearest town is a guess the model would then say |
| curation | 253 places once, then one field per new place | none, but clusters move as the roster grows, so a name used last week may not exist next week |
| honesty | a sub-region is a claim the owner made | a cluster is a claim a distance metric made, which the owner must still check by eye |
| cost | V-next column + entity + admin field + the spread's pocket key: S/M | the clustering script plus everything hand-drawn needs anyway once named: M |

**If it is built: hybrid.** Cluster once, offline, as a first draft; the owner names, splits and
merges in a spreadsheet; the result lands as the hand-drawn column. The spread's pocket list then
keys on `sub_region` where set, region where not.

**Minimum sample before Ask may quote one.** A sub-region pocket needs the shape of gate every other
served figure has: `rated ≥ 5` and `rated ≥ half the sub-region's non-canopy roster` — the RATED
count on both tests, as `VerdictSampleGate.MIN_RATED` and `windowFirstSpread.js` apply theirs (the
first cut of this plan put "examined" on both and the review showed that is a third variant; a
sub-region has no disposition-sourced examined count of its own without a `VerdictSampleGate` run at
that level, which is the honest way to do it if ever built). Below the gate the sub-region folds into
its region's pocket and is not named. At T+2/T+3 the half test fails for most sub-regions (Gate 4
scores T+2 SETTLED/TRANSITIONAL and T+3 SETTLED only), so sub-region pockets would mostly exist for
today and tomorrow — which is when the owner is deciding.

**A read-only production measurement, before any of that: are sub-region means stable, and do they
differ from the rest of their region by more than a random partition would?** Recipe in the shape of
`scripts/measurements/same-cell-rating-spread/` (its README has the host, `docker exec -i
goldenhour-db psql`, `:'since'`, and the one-run-is-an-hour rule), new folder
`scripts/measurements/sub-region-stability/`:

1. `clusters.sql` — a temp view assigning every non-canopy, regioned location to a candidate
   sub-region: a `VALUES` list piped from this machine (the output of a one-off clustering script
   over `locations` lat/lon, k chosen so clusters average ~15 places), beside (i) the owner's
   hand-drawn assignment for the Lake District alone and (ii) **ten random equal-size partitions of
   each region** — the null baseline, because clusters built on lat/lon always explain some variance
   in a spatially correlated field, and the question is how much MORE than chance.
2. `means.sql` — per (sub-region, window date, target type, run hour, model): rated count, mean
   on the **combined `rating`** (the scale §2.1 chose; `sky_rating` as a second column for the
   inland check, never coalesced into one mean), and the mean of the REST of its region for the same
   window and run (part-against-rest, not part-against-whole, so the gap is not attenuated by the
   part's own weight); over the last 60 days of `forecast_evaluation`.
3. `stability.sql` — per sub-region: how often its mean differs from the rest of its region by
   ≥ 1★ on a window with ≥ 5 rated (the "it is a real pocket" rate), against the same rate for the
   random partitions; the run-to-run change of its mean for the same window (nightly → intraday),
   against its region's; and the between-cluster share of the window's rating variance, against the
   random partitions'.
4. `results.md` — the run, dated, with the question each table answers.

Read-only (`SELECT`, temp views), no Claude cost, an afternoon's work.

**Recommendation on whether to build it: not yet.** Build the measurement; build sub-regions only
if the real-pocket rate clears the random partitions' by a margin the owner finds meaningful and
the between-cluster variance share does too — the owner sets the bar after seeing the null, not the
plan before it. Two reasons to expect "not yet": the FAR bucket's Haiku noise — 6–12% of inland
near-twin pairs 2★ or more apart (`results.md`, T+2/T+3, 60 days and the same week) — is per pair,
and a mean over ~15 places averages some of it away, but what survives is of the order of the ≥ 1★
gap the test looks for, so until the owner switches FAR to Sonnet 5.5 (`same-cell-rating-spread-plan.md`'s
recommendation) a T+2/T+3 sub-region mean carries noise the size of its signal; and a small pocket is
better named by its places (`rank_spots` with `regionNames` does that today) than by a sub-region
label the model then has to explain.

## §4 Delivery

| phase | what | size | tests | what it breaks |
|---|---|---|---|---|
| **W0** | this plan + `scripts/measurements/ask-window-context/measure.py`; CLAUDE.md's *Ask — the engine* bullet gains one sentence pointing here; `ask-photocast-plan.md` §2.2's table gains the spread column | XS | — | nothing |
| **W1** backend spread + prompt | `AskSnapshot.Spread` (pure record + fold, per region in `AskSnapshotBuilder`, summed per scope in `AskTools.listWindows`, with `over`); `WindowInfo.spread`; `BriefingRegion.coverageReason` set by `BriefingHonestyFilter.fullRewrite` and copied onto `AskSnapshot.Region`; `AskSnapshot.gameOnCandidates(windowIds, scope, predicate)`; `AskSnapshot.hasGameOn`, the no-pick admission consulted directly by the validator's anchor rule, `ReadyRelevance.violation` and `AskReadyFreshness.check`'s anchor test, with the question's own `admitsSlot` as the predicate (`anchoredWindow` unchanged); `RESULT_CHAR_CAP` 6,000 → 9,000; `AskToolSchemas` (list_windows description, `submit_answer` "Up to three"); `AskPromptBuilder` (criteria, hint, no-fill rule, the `USER_LESS`/`WITH_USER` sentences, the `THREAD_RULES` sentence, and a Ready conversation's "The question is about these windows: …" line from the offer's new `coveredWindowIds`, the temporal set, never its candidate-bearing `windowIds`); **the carried figures and their freshness** — `AskAnswer.windows` (every window `list_windows` returned, the picks' and the offer's; the whole `Spread` each) built by the validator and threaded through every `new AskAnswer(` (nine sites, `ReadyRelevance.relevantPart` and `recheck`'s rebuild among them), `ask_ready_answer.answer_json` carrying it (no migration), `AskReadyFreshness.spreadMoved`, the `AskReadyResponse`/`AskResponse` wire, and `@JsonIgnoreProperties` on `AskAnswer` (in the same release, because a prompt that quotes figures must not ship before they are carried and rechecked — Codex's round seven); `StubAskEngine` reads `Spread` straight off the snapshot (no tool call: its path already runs two `rank_spots`), fills picks with game-on places only and words the asked windows stay-home / game on / stood down from the figures, so local dev and the dry-run show the shape | M | `AskSpreadTest` (pure: region kinds from the served tree, canopy out, held-back out and counted, thin region's 4★ not in `gameOn`, bands, derived fields, pocket order and cap, scope sums, `over` for one / several / all regions, an all-stood-down window, a slotless `STAND_DOWN` region with reason `UNKNOWN` or null counted as not scored); `BriefingHonestyFilterTest` (the reason rides the rewritten region and nothing else changes) and `ServedBriefingAssemblerTest` (the reason survives the assembled API payload, a mutant per `BriefingRegion` wither); `AskToolsTest` (list_windows per scope, the busy-day mock under half the cap, the cap literal); `AskAnswerValidatorTest` + `ReadyRelevanceTest` + `AskReadyFreshnessTest` (no-pick BEST accepted, stored AND served iff no game-on candidate; withheld as stale once one appears; a region scope whose crown lies elsewhere but holds a 4★ is REFUSED a no-pick answer; a `COASTAL_HIGH` no-pick answer stands beside a 4★ inland place and falls to a 4★ high-water coastal one; anchored otherwise, byte-identical to today); `AskPromptBuilderTest` (`contains`; the thread prompt differs only by the shared change); `AskToolSchemasTest`; `StubAskEngineTest`; the carried figures (windows from the live snapshot, never the model; every rebuild site — a stored Ready answer carries `windows`, mutants per site); freshness (withheld on ANY difference in a carried `Spread` — a 5★ → 4★ move with `gameOn` unchanged, a 2★ → 3★ move, a pocket mean — a carried window that has passed or vanished withholds, a live covered window the answer never saw withholds, an unpicked window carried, a stored `NIGHT_SKY` answer fresh with nothing carried, legacy row served); mutation on the fold and the set test | every test that pins a `list_windows` JSON or a trace `chars` figure (`AskToolsTest`, `ClaudeAskEngineTest`; `AskAdminControllerTest` mocks the engine and does not move); `AskToolsTest`'s 6,000 literal; the stub's summaries and pick counts (fewer picks on a poor fixture); `AskPromptRegressionTest` is re-run, not edited |
| **W2** the client line (Q4) | `askConversation.js` `normaliseAnswer` keeps `windows` (served since W1), `threadForWire` does not; `AskConversation` draws one line per window if Q4 says so, `askCss.test.js` pins it outside every role gate | S | client join and copy; the line absent when `windows` is absent | nothing on the server |
| **W3** regression | `AskWindowContextRegressionTest` + two fixtures + the synthetic-band fixture builder | S/M | the class itself (owner-run) | nothing; excluded from `verify` |
| **W4** §2.5 | whichever of B–D the owner chooses (A is in W1) | L (B) / S (C) / M (D) | per option | per option: B and C change which picks exist, so the Ready catalogue's stored answers turn over at the next precompute; B touches `candidates`, the offer and the anchor |
| **R1** sub-region measurement | `scripts/measurements/sub-region-stability/` + results | M | the SQL checked on sqlite against a hand-worked fixture, as the same-cell recipe was | nothing (read-only) |

**Order:** W1 → W3 (so the prompt is measured before the client line is drawn) → W2 (Q4) → W4 once Q1 is
decided → R1 whenever, independently. Each phase its own branch, the local gate
(`./mvnw clean verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`, the frontend's
lint/test/audit/build for W2), an adversarial review of the diff, a changelog.d entry. No migration
anywhere, so nothing waits on CI alone.

**Costs, summed (§2.2):** ≈ +$0.07 a day on Ready (+$0.20 at the cycle ceiling); ≈ +$0.002 a typed
question on the owner's weekend and on a good day; ≈ −$0.003 and one round trip on an all-blue
window; a few pence per regression run.

**Rollback:** W1 is one commit with no data; reverting it restores today's `list_windows` and prompt
byte for byte (the typed cache is in-memory Caffeine and dies with the restart a deploy is), but a
W1-written Ready row read by the release before W1 is dropped until the next precompute, because no
earlier build tolerates the `windows` property (§2.4, "rollback truth"); W2 touches no stored row.

## §5 Decisions taken in this plan (challenge in review, not in code)

1. **The combined rating, not `skyRating`** (§2.1).
2. **Thin regions are out of the bands and counted as regions** (§2.1): the bands' population is
   `rank_spots`' population, less the floor.
3. **Held-back slots out of the spread and counted** (§2.1), diverging from the Plan card's
   histogram on purpose; `isHeldBack`'s test, so one number here and in `rank_spots`' note.
4. **No served verdict word for the spread** (§2.4): a second word beside the Plan card's "Maybe"
   for one window is the divergence this codebase keeps refusing; counts, criteria, and one owner-owned
   wording assertion.
5. **No prose fact-checking** (§2.4); `below4` makes the honest sentence a returned figure instead.
6. **The no-pick "best" answer is admitted on a set test** (§2.3), never a threshold.
7. **Pockets capped at 4 with `morePockets`**, bands as a five-array, `RESULT_CHAR_CAP` to 9,000
   (§2.2) — measured on a committed mock.
8. **The fill instruction goes** (§2.3): "up to three, never fill with marginal places" replaces
   "give 2 or 3 picks" and the schema's "Two or three".
9. **A new regression class, not new cases in the old one** (§2.6).
10. **Option A is inside W1** (§2.5): it is words and costs nothing; B–D wait for Q1.

## §6 Owner decisions (decided 2026-10-20)

The owner accepted every recommendation below on 2026-10-20. What each decision means for the build:

- **Q1 — 3★ at a distance** (§2.5): **option A, words only.** The criteria and the no-fill rule are the whole
  of it (W1); the validator's floor (`MIN_PICK_RATING` 3) does not change and no distance machinery is
  built. W4 (B, C or D) is therefore not scheduled; it is reopened only if W3's measurement shows the model
  offering a 3★ two hours away when nothing better exists. **Q1b** (the owner's own number for "close") is
  moot while A stands.
- **Q2 — `BEST_*` on a stay-home weekend** (§2.3): **(a) keep it offered and anchored** on the forecast's
  crown; the exceptions are carried as picks; a no-pick answer is admitted on the set test
  (`AskSnapshot.hasGameOn`, no threshold) when nothing game on is covered. Not relaxed to "the window with
  the most game-on places", and not withheld from the catalogue.
- **Q3 — the wording assertion** (§2.6): **keep the stay-home regex**, owner-approvable with the rest of the
  assertions.
- **Q4 — the client line** (§2.4, W2): **it is W2, not part of W1/W3.** The server carries and serves the
  figures from W1; the quiet line that draws them is W2, built 2026-10-20 (§2.4 "As built").
- **Q5 — sub-regions** (§3): **no sub-regions now.** R1 (the measurement) stays independent and unscheduled;
  FAR-to-Sonnet is `same-cell-rating-spread-plan.md`'s question. **Update, 2026-10-20 (from the session
  that wrote this plan's brief):** the owner has since switched the FAR bucket (`BATCH_FAR_TERM`, T+2/T+3) to
  Sonnet 5.5 on the Models screen, so Q5's precondition for R1 is met. R1 itself still waits for enough
  Sonnet-rated FAR runs to accumulate: its SQL reads the last 60 days of `forecast_evaluation`, and the Haiku
  rows from before today must be excluded by `evaluation_model`.
- **Q6 — build at all:** yes; W1 + W3 first.
- **Q7 — "must not lead with a 4★ pick"** (§0): **(a) the framing reading.** The summary must call the
  weekend what it is; the 4★ exceptions may still be carried as picks, named as exceptions. W3's assertions
  are written under this reading and flip to "no picks" by one line (`gameOnPicksOnly`).

## §7 Verify by measurement

1. **Per-conversation input tokens today, before W1** (read-only, production; the same-cell README's
   recipe for the host applies). ⚠️ One `ASK` job run holds EVERY typed turn of a UK day and one
   `ASK_READY` run a whole precompute, and `api_call_log` carries no conversation id, so a `GROUP BY`
   on the run says nothing about a conversation. Read the rows in time order and reconstruct:
   within one conversation `input_tokens` only grows turn on turn, so a row whose `input_tokens` is
   below the previous row's begins a new conversation (the `ask` bulkhead allows four at once, so
   interleaved conversations will mis-split occasionally — read the median, not a single run):

   ```sql
   SELECT c.called_at, c.input_tokens, c.output_tokens, c.cost_micro_dollars
     FROM api_call_log c JOIN job_run j ON j.id = c.job_run_id
    WHERE j.run_type = 'ASK' AND c.called_at >= :'since'
    ORDER BY c.called_at;
   ```

   The first row of a conversation is P plus the question; the last minus the first is what the
   tool results cost by the final turn. The exact per-tool character figure needs no reconstruction:
   `POST /api/admin/ask/dry-run`'s `trace[].chars` is `AskTools.ToolCall.chars`, the real serialised
   length. Run both again after W1 on a quiet day and a busy one; §2.2's figures are a mock and
   estimates until then.
2. **The real whole-roster spread for the owner's weekend**, before W1 exists: `GET /api/briefing`
   from production (an admin token; or the Rewind view to the Friday if the window has passed and
   the briefing still holds the days), the regioned non-canopy rated slots per window counted into
   bands by hand or by a ten-line script — the number §2.3's "of N rated across every region"
   should say, and the fixture's honest alternative to the reach-gated example.
3. **The owner's weekend through the dry-run** (`POST /api/admin/ask/dry-run`, the real engine, no
   guards): "Best spot this weekend?" before and after W1, ten runs each, counting how many
   summaries call the weekend stay-home, how many carry a 3★ pick, and what the `trace` called.
4. **W3's class**, ten runs each way, before any word of §2.3 is tuned.
5. **How often `spreadMoved` withholds** (after W2): the Ready serve's withheld-reason log over a
   week, against the "it moved between a cycle and its precompute" expectation of §2.4.
6. **The cap:** a test, not a measurement — the busy-day mock under half of `RESULT_CHAR_CAP`.
7. **R1** as its own results file.

## §8 What the adversarial review changed (2026-10-17)

Four read-only lenses over the first cut (code facts, engine design, the owner's brief,
measurement), ~60 charges, of which these changed the plan rather than a sentence:

- **The honesty filter empties a zero-coverage region's slots** before Ask sees them, so the first
  cut's thin test read an all-blue window as "thin" — the opposite of "all blue: stop" — and its
  `examined = rated + stoodDown` was the P1-A mistake. §2.1 now counts regions by served kind and
  reuses the served `verdictEligible`; no Ask-local thin constants exist.
- **A thin region's 4★ was in `gameOn` but never in `rank_spots`**; thin regions are out of the
  bands and counted as regions.
- **The saving was priced for a path the design rules out on the owner's weekend** (pockets →
  `rank_spots` still runs); §2.2 now says the example costs ≈ $0.002 MORE and only an all-blue window
  saves. The "today" prefix was the post-change one; the Ready volume was the pre-`NIGHT_SKY`
  figure; the cache_control aside was wrong for Haiku 4.5's 4,096-token floor; the eight-spot cap
  arithmetic was wrong by 19 characters (the final mock settles it the other way, with every field
  in).
- **"Give 2 or 3 picks" is where the 3★ fill comes from**; it is replaced (§2.3 #8).
- **A no-pick "best" answer could never be stored**, so a stay-home weekend paid every cycle for
  nothing; the set-test admission exists for it.
- **"Must not lead with a 4★ pick" was never addressed**; §0 states the reading and Q7 offers the
  other.
- **The §7 query grouped by a run that holds a whole day**; rewritten as a reconstruction, with the
  dry-run trace as the exact instrument.
- **Option B was costed as an M** when it reaches the shared `candidates`/offer/anchor; it is an L,
  it amends the principle, and the recommendation moved to A-then-decide.
- **The rollback claim was wrong** (`AskReadyStore` throws on an unknown property); W1 ships the
  tolerance.
- **The Plan card and the BEST BET have no floor below an outlier skip**; §2.5's "three surfaces"
  claim was corrected and option C's effect with it.
- **Codex's review of #1223 (2026-10-17)** added two P1s against §2.4: the carried figures covered only
  the picks' windows (an unpicked window's figures could go stale while a pick kept the answer live) and
  the freshness test compared only `rated` and `gameOn` (a 5★ → 4★ move was invisible). Both fixed by
  carrying the whole `Spread` of every window the conversation saw and comparing it by equality.
- **Codex's round two (406c67b)** added two more P1s: the hint's stop condition ("every window is
  stay-home") stopped before the pocket branch on exactly the owner's weekend, discarding the Ready
  answer; and the freshness rule skipped a carried window that had passed, serving prose about it
  unverified. Both fixed in §2.3 and §2.4.
- **Codex's round three (60998072)**: the no-pick exception reached the validator and
  `ReadyRelevance` but not `AskReadyFreshness.check`'s own anchor test, so the stored answer would have
  been withheld on its first serve. Fixed by putting the exception in the one shared method,
  `anchoredWindow`.
- **Codex's round four (54eb65e7)**: round three's cut keyed the exception on `anchoredWindow`
  answering empty — but it is already empty for a region scope whose crown lies in another region,
  which would have stored "nothing worth going for" over a 4★. The admission is now its own
  predicate, `hasGameOn`, read directly at every deciding site.
- **Codex's round five (14dd6d5b)**: the hint's stop check read "no window in scope", which steers
  a Ready weekend question to a 4★ on another day that relevance then rejects — it is now "the
  windows the question is about", which a Ready prompt names; and the no-pick admission was limited
  to `BEST_*` while `AM_OR_PM` and `COASTAL_HIGH` are pick questions the same hint stops — it now
  covers every solar pick question.
- **Codex's round six (89b2bd67)**: `hasGameOn` counted every 4★ on the offered windows, so a
  `COASTAL_HIGH` question's honest no-pick answer was discarded beside a 4★ inland place — the
  predicate is now the question's own `admitsSlot`; and a slotless `STAND_DOWN` region was read as a
  weather stand-down although the filter words `UNKNOWN` the same way — the filter's reason now rides
  the served region and only `STOOD_DOWN` counts as stood down.
- **Codex's round seven (3a8837e9)**: the carried figures and `spreadMoved` sat in W2 while W1's
  prompt already told the model to quote figures, so the advertised W1 + W3 minimum could serve a
  stale count from a row a refused precompute left standing. Both moved into W1; W2 is the client
  line alone.
- **Codex's round eight (4607ad81)**: the Ready prompt's window line was to come from the offer's
  `windowIds`, which `windowsWithCandidates` builds from candidate-bearing windows only, so an
  all-blue Saturday vanished from "this weekend" and a mixed weekend read as good. `Offer` gains the
  covered (temporal) set for the prompt and `hasGameOn`; a third regression fixture covers the case.
- **Codex's round nine (03777451)**: the carried set named the offer's candidate-bearing
  `windowIds`, so a stub Ready answer describing an all-blue Saturday did not carry it; the carried
  set is now the covered windows plus the picks', for the stub as for Claude.
- **Codex's round ten (c315d535)**: `coverageReason` had to survive the nine `BriefingRegion`
  withers the assembler runs after the filter, and the test had to be the assembled payload; and the
  carried set admitted a night pick's window, which has no `Spread` — carried pick windows are solar
  only, and a stored `NIGHT_SKY` answer is pinned fresh.
- **Codex's round eleven (6451e09b)**: freshness compared only the carried spreads, so a covered
  window ADDED since the answer was built (a travel day unmarked) was never considered and never
  withheld on; the stored covered set is now compared to the live one.
- The night regression already asserts wording; the thread needs one sentence; `heldBack` now uses
  `isHeldBack`; the research recipe measures the combined rating, part-against-rest, with a random
  partition as the null; the "6–11%" became 6–12% of inland near-twin pairs.
