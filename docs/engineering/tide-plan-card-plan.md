# Plan tab — tide alignment on the window card: implementation plan

**Source**: the design bundle vendored at `docs/design/tide-plan/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's working order), `Plan Tide Summary.html` + `plan-tide-v6.js`
(the prototype; the notes column is part of the spec, and its two `cut` notes are the reasoning),
three screenshots. `docs/design/tide-plan/VENDORING.md` records what was copied and what was not.
Read the spec before any phase; **this document is the *port* plan**: what the codebase already has,
what is genuinely new, where the spec and the code disagree on purpose, and how the work cuts into
single-session phases for **Sonnet** sessions.

**The one-sentence version.** The Map tab (the `tide-window` series, `docs/engineering/tide-window-plan.md`)
answers *what is the water doing and does it suit this spot* — a curve. The Plan grid compares six
windows side by side, so what it needs from tide is **a number you can scan down a row**: is the
coast live on this one, and if several are, which do I take. Two marks in slots the card already
has — a wave before the named best-reachable spot when its water suits it, and a `N on tide` topic
chip that appears only where half the reachable coast is served, emphasised with `best of N` on the
one live window to take — plus one fact on the popup. **Nothing is re-scored, nothing is re-coloured,
and where the coast is not live the card says nothing at all.**

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§9) → commit. Frontend gate before any push: `npm run lint && npm test && npm audit
--audit-level=high && npm run build`, gated on exit codes. Never push; never tag. Every phase adds a
`changelog.d/YYYYMMDD-<slug>.md` entry. Paste the relevant section of THIS plan and the spec into
every review agent's prompt.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *tide*, *chip*, *topic*,
*card*, *best reach*. Checked 2026-09-17 at plan time: the `tide-window` series' T4–T6 were in flight
(Map tab only — different files) and T7/T8 pending. This series depends on nothing they draw; it
depends only on the **served** fields T1 (#877) and T2 (#876) landed (§1 #1).

---

## §0 Status

**Status: IN PROGRESS — C0 shipped, C1–C4 not started.** Five phases (C0–C4; C0 was added on the plan
PR after a Codex review found the offset tiebreak inverted for MID wants). Plan written 2026-09-17
against `origin/main` at `0f9273ca`
(#878); re-checked 2026-09-18 against `de396bd2` after the tide-window series' T4–T7 merged (#879–#882) —
nothing this plan builds on moved: the served `BriefingSlot.TideInfo`/`BriefingWindowTide` fields are
unchanged, and T4–T7 touched the Map tab only. Owner decisions this plan needs are listed in §6; **none blocks any phase** — §5 takes the
spec's two OPENs that would, and the owner challenges §5 on the plan PR, not in code.

Phase log (C0 creates the first row; every phase appends its own in the same commit as its code —
the commit column names the PR once it lands, since a phase cannot name its own hash):

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| C0 | `feature/tide-card-c0-quality` | (PR pending) | 2026-09-18 | `BriefingSlot.TideInfo`/`TideDerivation` gain `Double tideAlignmentQuality` (`@JsonInclude(NON_NULL)`, null unless `tideAligned`); computed in `TideFactDeriver.derive()` on the same time axis alignment is decided on — nearest-extreme offset for HIGH/LOW, `TideService.nearestMidpointOffsetMinutes` (new, a second extremes fetch) for MID, best-of when more than one want aligns. `TideService.isMidPointAligned` now delegates to the shared `computeMidpointOffsetMinutes` geometry rather than a separate loop. No migration; both `TideInfo` legacy constructors and `TideDerivation` gain one more (16-field) to keep every predating call site compiling. A WARN logs the one avenue `tideAligned` and the new field can disagree (MID's second fetch racing a tide refresh) rather than degrading silently. No visible change — C1 is the first consumer.

---

## §1 Corrections to the spec — where the codebase has already moved

Numbered so a phase log can cite them. Every file:line was read against `0f9273ca`; **re-verify
before editing**, code moves.

### 1. The "model" this consumes is time-based and already served — there is no `fit` to import

The spec's prerequisite is the Map-tab model: `want`, cosine level, bands 0.75/0.25, tiers
0.80/0.62. `tide-window-plan.md` §1 #2 and §4 #2 record why none of that was built: this app's tide
axis is **time-proximity** (`TideService.classifyTideState`, the dynamic half-golden-blue window)
tested against the location's `Set<TideType>` (`tideAligned`), and the gate, `TideVisitor`, the
Plan badge and `BriefingWindowTide.state` all stand on it. So on every coastal `BriefingSlot`, flat
in JSON (`@JsonUnwrapped`), the facts this increment needs already exist:

| spec | served field (`BriefingSlot.TideInfo`, `model/BriefingSlot.java:376–394`) |
|---|---|
| `coast` | `tideState != null` (HIGH/MID/LOW — null for inland or no stored extremes) |
| `tier === 'match'` | `tideAligned === true` |
| `want` | the location's `tideType` set (roster, `LocationDto.tideType`) — and served as words in `tideFitPhrase` |
| level / state words | `tideState`, `tideDirection`, `tideLevel`, `tideHeight`, `tideFitPhrase` (T1, #877) |
| the tiebreak's `1 − |level − target|` | **`tideAlignmentQuality`** — a served 0..1 "how well-centred in the wanted water" figure this series adds in **C0**, on the same time axis alignment is decided on (§4 #2). ⚠️ Not `nearestSolarOffsetMinutes`: that is distance to the nearest *extreme*, which is the wrong direction for a MID want — a better-centred mid-tide match is *farther* from either extreme (a Codex finding on the plan PR) |

Per window, `BriefingWindow.tide` (`BriefingWindowTide`) carries the representative's `state`,
`direction`, `heightAtWindow`, `nearestTime`/`nearestOffset` and `locationName` (T2, #876). **This
series adds one served per-slot number (C0's `tideAlignmentQuality`) and nothing else — no migration.**

### 2. The card is one component, and its facts are derived in one memo

`components/WindowFirstHeatStrip.jsx` (1559 lines): the card is the `renderCard` closure (`:902`);
its value grid (`.wf-hc-pls`, `:1028`) holds time, verdict, the spread histogram (`:1044–1073`), the
best-reachable line (`:1074–1099`) and the topics line (`:1100–1114`); the BEST BET / ALSO GOOD legend
rides the border (`.wf-hc-lg`, `:1122–1131`). Everything the face reads is computed once per card in
the `derived` memo (`:537–557`): `spread`, `bars`, `title`, `withinReach`, `best: bestReachLine(card)`,
`topics: windowTopics(...)`. **A new per-card tide fact belongs in that memo**, beside `best`.

⚠️ **The value grid is `aria-hidden`.** `.wf-hc-pls` (`:1028`) carries `aria-hidden="true"`, so nothing
inside it — the spread, the named spot, the topics line — reaches assistive technology, and an `sr-only`
span placed there is ignored too. The card button is named by the separate `accessibleName` string
assembled at `:930–943` (label, time, verdict, "not scored", the pool sentence, `facts.best.spoken`,
the topic labels, the pick). **Both new marks must be fed into that construction** — the glyph as a
clause on `facts.best.spoken`, the chip as its own clause — and tested through the button's accessible
name, not through hidden text (a Codex finding on the plan PR).

### 3. The pool is already the right population — and it carries no tide facts

The best-reachable line is the head of `card.pool` — the reach-gated, pre-rating-floor list built
in `utils/windowFirstCards.js:444–450` (origin scope → reach → rating floor; `pool = reached`,
`:547`), pre-ordered by `compareSpots` (rating desc, drive asc, name). Its entries come from
`utils/windowFirstSpots.js#buildWindowSpots` (`:205–246`) and carry exactly `key, locationId,
locationName, regionName, solarEventTime, rating, driveMinutes, distanceMiles, far` — **no
`tideAligned`, no `tideState`**, though the slot they are built from has both flat on it. The spec's
`reachPool(window).filter(s => s.coast)` is therefore one field-copy away, and the spread histogram,
the best-reachable line and the new chip then share **one pool**, which is the rule plan-matrix A10/A11
insist on ("the map, the list and the line share one pool"). The alternative — building
`buildTideAlignmentIndex` in the shell and looking the head up — creates a second population and is
rejected (§5 #2).

⚠️ **`card.tide` is taken.** The tide-window series' T6 (#881) put the raw served `BriefingWindowTide`
on the card descriptor as `tide: win?.tide ?? null` (`windowFirstCards.js:588`) and forwards it through
`windowFirstStrip.js:173` and the map pane's `heat.windows` fold to the Map tab's strip. The reach-scoped
summary this series derives is a **different field, `card.tideFit`**; naming it `tide` would replace the
curve, positions and extremes and blank the strip for every window (a Codex finding on the plan PR).

### 4. The topics line is a list of served badges, coloured by channel — and it has no `--tc`

`WindowFirstHeatStrip.jsx:1100–1114` maps `facts.topics` to `.wf-hc-tw` spans with
`data-channel={badgeChannel(badge.type)}`; colour is by attribute selector
(`index.css:2132–2136`, `[data-channel="tide"] { color: var(--color-badge-tide) }`), **not** the
prototype's `--tc` custom property (no such property exists — `grep -- "--tc"` is empty). The line
is `flex-wrap` through `.wf-hc-pls > .wf-hc-tps` (`index.css:2095–2105`) — ⚠️ selected **through the
parent**, because `.wf-hc-pls > span` at `:1936` outranks a bare class (`:2059–2065` records the trap).
Topics are ordered rarest-first with **no tiebreak** (`windowFirstTopics.js:267–268`, deliberate). The
tide chip is a **client-derived** mark, not a served badge, so it is rendered as its own first element
of the line rather than injected into `facts.topics` (§5 #3).

### 5. Tokens: two tide hues, not three

`--color-tide` (`#6FA8B0`, `index.css:45`) is the accent/border hue; `--color-badge-tide` (`#9CCBD1`,
`:182`) is the small-text hue, measured 9.68:1 on the callout and 10.41:1 on a plain card. **There is
no `#B4DDE2`-like third tint anywhere** (`grep -rin b4dde2` over `frontend/`, `landing/`, `docs/` is
empty), and the arm's established pattern for emphasis is weight and full ink, not a brighter hue.
`rgba(111,168,176,…)` literals of the tide hue are precedent (`.wf-frow`, `index.css:6441–6452`). ⚠️ A
new token must go in **`@theme static`**, not the plain `@theme` block — Tailwind prunes unreferenced
tokens from the latter, and `WindowFirstHeatStrip.test.jsx:2512–2520` pins exactly that. This plan
adds no token (§4 #4).

### 6. The wave glyph exists, and the closest precedent reads the OTHER axis

`components/map/TideWave.jsx` (props `className`, `testId`; the `tide-window` series' T4 may add a
`shortfall` prop — this series passes none). `MapRegionPanel.jsx:198–205` already draws a wave *after*
a location name inside a row with an `sr-only` sentence — the shape to copy — but it reads
`onTheLight` (does an extreme land on the light), not `aligned` (is it the water this spot wants).
The card's glyph is the **preference** question, so it reads `tideAligned`, and its words say so
(`locationSheet.js:315–326`, CLAUDE.md's two-tide-axes rule). ⚠️ The spec's tooltip suffix "high water
on the light" is the *timing* phrase and is not used (§4 #3).

### 7. The popup already has a tide row; the spec's "one tide line" is an addition to it

`WindowSheetDialog.jsx:219,581–585` renders `WindowAttributeRow` for the tide channel, with
`WindowTideSparkline` and four served facts from `utils/windowFirstRows.js#tideFacts` (`:101–141`):
state+direction, `HW 19:28 · 1h43 before sunset`, seas (optional), range · anomaly · `at <station>`.
The spec's popup line (`state · N of M coastal spots on tide`) is therefore **one more fact on that
row**, not a second line — and because the count is reach-scoped client data, it must not enter
`windowFirstRows.js`, which is a pure map of served facts (§5 #6). `heightAtWindow` (served, ignored by
the row today) is the natural companion fact if the phase wants the height the spec's tooltip names.

### 8. No served per-window aligned count exists, at any level

`BriefingRegion.tideHighlights` counts by **size/lunar label** (King/Spring × Extra High), not by
`tideAligned` — `BriefingVerdictEvaluator.buildTideHighlights` (`:447–469`) passes `tideAligned` into
a `TideContext` and never reads it. `tideAlignedCount` exists only as an LLM prompt input
(`BriefingRollupBuilder.java:373,427`). And the count this spec wants is over the **reach pool**, which
is per-user, so it has no servable answer on the ETag-shared `GET /api/briefing` at all — the same
argument that licensed A10/A11. The count is a **new member of CLAUDE.md's reach-scoped class**, and is
recorded as one (§5 #1); its exit is plan-matrix O-4.

### 9. The run vocabulary the spec wants to share is gone from the Plan tab

`OPEN 2` asks to share `SPRING RUN n/N` with Hot Topics. That chip died with `HotTopicStrip` in the
Coming up redesign's P6 (`coming-up-plan.md` D7); nothing on the Plan tab renders `dayNumber`/`dayCount`
as a run ordinal (`windowFirstMatrix.js:66`'s `dayNumber` is day-of-month). The Coming up tab's tide
runs are a different code path. `best of N` counts live windows **in the forecast**, as the spec says,
and there is no run to reconcile it with here (§6 Q2).

### 10. `OPEN 3` is already answered: the want is a set

`LocationEntity.tideType` is `Set<TideType>` (`tide-window-plan.md` §1 #1); a spot that works at both
extremes is `{HIGH, LOW}` today. `tideAligned` tests the set. Nothing to build.

### 11. The `reachMeasured` discipline applies to every word this increment prints

`card.reachMeasured` (`windowFirstCards.js:531`) is the single producer of "a drive time exists to
have gated on"; `bestReachLine` (`:193`) and `derived.withinReach` (`:546`) both read it, and CLAUDE.md
forbids any surface saying "within reach"/"in reach" without it. The chip's tooltip and the popup fact
say "coastal locations in reach" only when it is true, and "coastal locations" otherwise (§5 #4).

---

## §2 Strategy

- **One pool, three readers.** `buildWindowSpots` copies `tideState`, `tideAligned` and
  `nearestSolarOffsetMinutes` onto each spot; `buildWindowCards` derives the per-window tide summary
  from `pool` beside `bestReach`; the strip derives the run (live windows, the best) from the cards.
  The histogram, the named spot and the chip cannot disagree because they never see different lists.
- **No fit formula, no thresholds on levels — on the client.** `match ⇔ tideAligned`; the ranking's
  quality term is a served number (`tideAlignmentQuality`, C0) that the server computes on the same time
  axis alignment is decided on, and the client only averages (§4 #2). Nothing on the client reads a
  level, an offset or a threshold to decide anything.
- **Same element, louder.** The emphasis is the same chip with heavier weight and a hairline pill —
  the spec's own rule from the cut legend. No border badge, no new row, no third tide hue.
- **Silence below the gate.** No greyed chip, no zero, no glyph on a miss.
- **Sonnet sessions, one phase each, review before commit.** §8 is the session map,
  `tide-plan-card-prompts.md` the kickoff prompts.

---

## §3 Phases

### C0 — Backend: a served alignment quality — S

**No visible change; no migration.** The run ranking (C1) needs to break a tie on `matched` by *how
well* the matched spots are served, and the only served candidate — `nearestSolarOffsetMinutes` — points
the wrong way for a MID want (§1 #1). So the server states the quality itself, on the axis it already
decides alignment on.

1. **`BriefingSlot.TideInfo` gains `Double tideAlignmentQuality`** (`@JsonInclude(NON_NULL)`, 0..1,
   **null unless `tideAligned`**): how well-centred the water is in the wanted state at the light, on
   the **time** axis — for a HIGH or LOW want, `1 − |minutes from the light to that extreme| /
   tightWindowMinutes`; for a MID want, `1 − |minutes from the light to the midpoint between the
   bracketing extremes| / tightWindowMinutes` (the same midpoint `TideService.isMidPointAligned` tests);
   when more than one want is aligned, the best of them; clamped to [0, 1]. Computed where the extremes
   and the tight window are already in hand (`TideFactDeriver.derive`, `:88–97`, which already holds
   `tightWindowMinutes` and both classifications), built into `TideInfo` by `BriefingSlotBuilder`
   beside T1's five fields. Keep `TideInfo.NONE` and both legacy constructors.
2. **Tests.** `TideFactDeriverTest`/`BriefingSlotBuilderTest`: a HIGH-aligned slot with the light *at*
   high water scores 1.0; one at the tight window's edge scores ≈ 0; a MID-aligned slot at the exact
   midpoint scores 1.0 and beats one nearer an extreme (the case the offset ordering gets backwards);
   a `{HIGH, LOW}` want takes the better of the two; a non-aligned slot is null; `DailyBriefingResponseJsonTest`
   round-trips it and a pre-field payload reads null. Backend gate with the integration exclusion.

### C1 — The data: tide on the pool, the per-window summary, the run — M

**No visible change.** Depends on C0 (the served quality).

1. **`utils/windowFirstSpots.js#buildWindowSpots`** (`:205–246`): each spot gains `tideState`
   (`slot.tideState ?? null`), `tideAligned` (`Boolean(slot.tideAligned)` when `tideState != null`,
   else `null`) and `tideQuality` (`slot.tideAlignmentQuality ?? null`, C0's served figure). Coastal ⇔
   `tideState != null` — the server's own "coastal with a derivable answer" predicate, the same rule
   `buildTideAlignmentIndex` skips on (§5 #5).
2. **`utils/windowFirstCards.js#buildWindowCards`**: the descriptor gains **`tideFit`** — never
   `tide`, which T6 already uses for the served `BriefingWindowTide` (§1 #3) — `{coastal, matched, live,
   meanQuality}` over `pool`: `coastal` = spots with a `tideState`, `matched` = those `tideAligned`,
   `live = coastal >= 4 && matched >= Math.max(3, Math.ceil(coastal * 0.5))` (the spec's gate,
   verbatim), `meanQuality` = mean of `tideQuality` over the matched spots that carry one (null when
   none). `windowFirstStrip.js` forwards `tideFit` beside `tide`. Constants named (`TIDE_LIVE_MIN_COASTAL = 4`,
   `TIDE_LIVE_FLOOR = 3`, `TIDE_LIVE_SHARE = 0.5`) with the spec's reason in the doc block: a flat floor
   of three put a chip on all six cards.
3. **`utils/windowFirstTideRun.js`** (new, pure): `tideRun(cards)` → `{liveKeys: Set, bestKey,
   liveCount}` over the served, non-away cards: live = `card.tideFit.live`; best = the live card ranked by
   `matched DESC, meanQuality DESC (nulls last), strip order ASC`; `bestKey` null unless
   `liveCount > 1` (the emphasis exists only where there is something to be best of). Ties on all three
   are genuine and the earlier window wins, as the spec says.
4. **Tests.** `windowFirstSpots.test.js` — the three fields copied, null for inland; `windowFirstCards.test.js`
   — the gate at its edges (`coastal 3 → never live`; `coastal 4, matched 2 → not live`; `coastal 4,
   matched 3 → live`; `coastal 8, matched 3 → not live`, `matched 4 → live`), `meanQuality` over
   matched only; `windowFirstTideRun.test.js` — count beats quality, quality breaks a count tie (**the
   spec's own case**: three windows matching the same nine, the one with the highest mean quality
   wins — not the first), strip order breaks a full tie, `bestKey` null with one live window, away and
   unserved cards never live.

### C2 — The two marks on the card — M/L

**Spec §1–§3.** In `WindowFirstHeatStrip.jsx`:

1. **The glyph.** In `bestReachLine(card)` (`:164–204`) and the JSX at `:1074–1099`: when
   `card.bestReach?.tideAligned === true`, render `<TideWave className="wf-hc-best-tw" />` before the
   name inside `.wf-hc-best`, a tooltip suffix ` · <state word> — the water it wants` (state from
   `STATE_WORD[bestReach.tideState]`), and — because `.wf-hc-pls` is `aria-hidden` (§1 #2) — the clause
   `the tide is right here` appended to `facts.best.spoken`, which is what `accessibleName` reads. No
   `sr-only` span: text inside the grid is never announced. **A
   miss is silent**: no glyph, no text (the pick is chosen on score). Name ink stays `--color-plex-text`.
2. **The chip.** First element inside `.wf-hc-tps`, rendered only when `card.tideFit.live`:
   `<span data-testid="wf-heat-tide-chip" data-channel="tide" className="wf-hc-tw wf-hc-tide"
   data-best={isBest || undefined}>` containing `<TideWave/>` + `{matched} on tide` and, when best,
   `<em className="wf-hc-tide-best">best of {liveCount}</em>`. Tooltip: `9 of 14 coastal locations in
   reach get the water they want — mid tide, falling · 3 windows in this run are live · the most of any of
   them`, where the state phrase is the served window tide's `STATE_WORD`/`DIRECTION_WORD` (reuse
   `windowFirstRows.js`'s tables — export them), "in reach" only when `card.reachMeasured` (§5 #4), the
   run clause only when `liveCount > 1`, the last clause only on the best. **The chip's words reach
   the reader through `accessibleName`** (`:930–943`): add one clause — `9 on tide` / `9 on tide, best of
   3` — beside the topic labels the construction already concatenates; there is no `sr-only` text in the
   grid (§1 #2).
3. **CSS**, in the card block beside `.wf-hc-tw` (`index.css:2121–2136`): `.wf-hc-tide { color:
   var(--color-badge-tide); display: inline-flex; align-items: center; gap: 6px }`;
   `.wf-hc-tide[data-best] { font-weight: 700; padding: 1px 8px; margin: -1px -2px; border-radius:
   999px; background: rgba(111,168,176,.15); box-shadow: inset 0 0 0 1px rgba(111,168,176,.42) }`;
   `.wf-hc-tide-best { font-style: normal; font-weight: 600; padding-left: 6px; margin-left: 1px;
   border-left: 1px solid rgba(111,168,176,.45) }`; `.wf-hc-best-tw { width: 13px; height: 7px;
   margin-right: 5px; vertical-align: 1px; color: var(--color-badge-tide) }`. Emphasis ink is the same
   `--color-badge-tide` at 700 (§4 #4); measure it on the pill and record the figure. The `.wf-hc-tw`
   ellipsis rule must not clip the pill — check `overflow` on the emphasised chip at 390px.
4. **Tests** (`WindowFirstHeatStrip.test.jsx`, the topics and best-reachable suites, plus the
   stylesheet-as-text suite for the new rules): glyph present on an aligned coastal head, absent on a
   miss AND on an inland head, name ink unchanged; chip present only on live cards, exactly one
   `[data-best]` when `liveCount > 1` and none when 1; `best of N` text exact; tooltip with and without
   "in reach" (`reachMeasured` true/false); **the card button's accessible name** (`getByRole('button',
   {name: …})`) carries `the tide is right here` on an aligned head and `9 on tide, best of 3` on the
   best live card, and neither on a miss or a silent card; **§7 checks 1, 2, 4, 5 as tests** (chip count = gate count on
   a six-card fixture with the spec's 3-of-6 shape; one emphasis; glyph silent on a miss; verdict word,
   histogram bars and star chip byte-identical across an all-aligned and an all-miss fixture).
5. **Browser (§9)**: the sunset row with three live windows; the emphasised chip's contrast on its pill
   and the plain chip's on the card (§7 check 7) as numbers; no chip overflow at 1280/834/390 (§7 #6).

### C3 — The popup fact, and the phone — S

1. **`WindowSheetDialog.jsx`**: the tide row gains one trailing fact, `9 of 14 coastal locations in
   reach on tide` (or `… coastal locations on tide` when `!card.reachMeasured`), rendered from
   `card.tideFit` and passed to `WindowAttributeRow` as an `extraFacts` prop (an array in `tideFacts`' own
   `{segments, optional}` shape) — **never** added inside `windowFirstRows.js#tideFacts`, which maps
   served facts only (§5 #6). Omitted when `card.tideFit.coastal === 0`. Optionally, and only if the row
   has room at 390px, `heightAtWindow` as a fifth served fact — that one *does* belong in `tideFacts`
   (it is served) and is recorded if added.
2. **Phone (`OPEN 4`)**: at 390×844 with a four-topic night fixture plus the emphasised chip, the
   topics line wraps to two lines and the pill is not clipped by `.wf-hc-tw`'s `overflow: hidden`;
   record the measured line count and card height in the phase log.
3. **Tests**: `WindowSheetDialog.test.jsx` "the rows below" suite — the fact present with the count,
   absent at zero coastal, "in reach" only with `reachMeasured`; `WindowAttributeRow.test.jsx` — the
   `extraFacts` prop appends after the served facts and renders nothing for an empty array.

### C4 — Sweep and docs — S

1. Reconcile §4 against what shipped; flip §0 to complete; fill the phase log.
2. **CLAUDE.md**: the Backend-heavy bullet's reach-scoped class gains its new members — the per-window
   `tideFit` summary (coastal/matched/live/meanQuality over the reach pool) and the strip's
   `tideRun` — "and only those"; the Plan tab bullet gains one sentence naming the two marks and the
   silence rule; the "Two tide axes" bullet notes the card glyph reads the preference axis.
3. Answer the spec's four OPENs in §6 with what shipped; record the §7 measurements.

---

## §4 Disagreements with the spec, on purpose

1. **No level, band or fit tier is computed or imported.** `match ⇔ served tideAligned`; `coast ⇔
   served tideState != null` (§1 #1). The prototype's model block is scaffolding its own README says
   not to port, and in this codebase the model it points at was never built.
2. **The ranking's quality term is a served `tideAlignmentQuality`, not a client `meanFit`.** `matched
   DESC, mean tideAlignmentQuality over matched DESC, window ASC`. The server computes the figure on
   the same time axis it decides alignment on (C0) — at the extreme for a HIGH/LOW want, at the
   midpoint for MID — so it answers the spec's own example ("17 minutes after the light rather than
   nearly an hour off it") *and* orders a MID match the right way round, which the plan's first cut
   (mean nearest-extreme offset, ascending) did not: a better-centred mid tide is farther from either
   extreme (a Codex finding on the plan PR). The client only averages a served number.
3. **The glyph's words are the preference axis.** `— the tide is right here` / `<state> — the water it
   wants`, never the spec's `high water on the light`, which is the timing phrase this app keeps for
   `tideOnTheLight` (§1 #6).
4. **No third tide hue.** The spec's `#B4DDE2` for the emphasised chip is not added; emphasis is
   `--color-badge-tide` at 700 on the hairline pill (the arm's pattern: weight, not hue). The pill's
   fill and hairline are rgba tints of `--color-tide`'s value, which the file already uses (§1 #5).
5. **The chip is rendered as its own element, not injected into the served topics list.** It is
   client-derived and reach-scoped; `facts.topics` is the served badge list ordered by served rarity.
   It sits first in the line, as the spec places it, by DOM order (§1 #4).
6. **The popup fact is appended to the existing tide row, not a new line.** The row, its sparkline
   and its four served facts pre-date this spec (§1 #7).
7. **`reachMeasured` gates the word "in reach"** on the chip's tooltip and the popup fact (§1 #11).
8. **Coastal is "has a served tide state".** A coastal location with no stored extremes has no fit
   answer either way and is counted in neither numerator nor denominator; the tooltip's `of N` is the
   pool the server could judge (§5 #5).

---

## §5 Decisions taken in this plan (challenge in review, not in code)

1. **The counts, the gate and the run are client-derived, and are new members of CLAUDE.md's
   reach-scoped class.** The pool is per-user (reach), so no servable answer exists on the shared
   payload — the identical argument that licensed A10/A11. Members added: `card.tideFit` and `tideRun`,
   nothing else. Exit: plan-matrix O-4.
2. **One pool.** Tide facts ride the spot descriptor so `pool` itself is counted; no second index on
   the Plan tab (§1 #3).
3. **The chip is a sibling of the served badges, first in the line.**
4. **`OPEN 1` — fully reach-bound, as built.** The marks move with the drive filter, and that is the
   statement the lens readout already makes. A scope-wide ranking with a reach-bound count would put
   two pools in one chip. `reachMeasured` decides the wording.
5. **Coastal ⇔ served `tideState != null`.**
6. **Nothing reach-scoped enters `windowFirstRows.js`.** That module maps served window facts; the
   popup's count reaches the row as a prop.
7. **No score, verdict, histogram, border or thumbnail is touched**, and the cut `Tide` border legend
   is not built (spec §5).

---

## §6 Owner decisions / OPEN items

- **Q1 — `OPEN 1`, reach-bound marks.** Decided §5 #4 (as built). Revisit if a reader reports the
  emphasis "moving" when they change the drive filter.
- **Q2 — `OPEN 2`, a shared run vocabulary.** There is nothing to share with on the Plan tab (§1 #9);
  `best of N` counts live windows in the forecast. If the Coming up tab's tide runs and this chip
  should one day agree that a run "continues past the forecast", that is a served `TideRunDay`
  join — its own increment.
- **Q3 — `OPEN 3`, a want that is a range.** The set already expresses "both extremes"; "high water
  but not a spring" would need a new axis on the location. Not this series.
- **Q4 — `OPEN 4`, phone.** Measured in C3 and recorded there.
- **Q5 — Should the gate's constants (4 / 3 / 0.5) be tuned against a real roster?** The spec calls
  them editorial. Production has ~61 coastal locations; at a 45-minute reach a coastal pool of 4–8 is
  typical, so the floor of three is doing most of the gating. Watch the first fortnight of cards.

---

## §7 Verify by measurement — the spec's seven checks, and how each is measured

| # | check | phase | how |
|---|---|---|---|
| 1 | Gate | C2 | Six-card fixture built from `buildWindowCards`: `[data-testid="wf-heat-tide-chip"]` count equals cards with `tide.live`; the spec's default shape (3 of 6) reproduced. |
| 2 | Exactly one emphasis | C2 | `[data-best]` count is 1 when `liveCount > 1`, 0 when 1, 0 when 0. |
| 3 | Ranking is not first-past-the-post | C0/C1 | Three live cards with equal `matched`, mean qualities 0.3 / 0.9 / 0.6 → `bestKey` is the second, not the first; pinned so a `||`→`&&` or a sort-order flip fails it. And on the server: a MID-aligned slot at the exact midpoint scores higher than one nearer an extreme. |
| 4 | Glyph is match-only and silent on a miss | C2 | Head coastal + aligned → glyph + sr-only text; head coastal + not aligned → neither; head inland → neither. |
| 5 | No score moved | C2 | Verdict word, histogram bar heights, star chip text and colour byte-identical across an all-aligned and an all-miss fixture of the same ratings. |
| 6 | No overflow | C2/C3 | `scrollWidth <= clientWidth` on every chip and on `.wf-hc-tps` at 1280/834/390 in the browser. |
| 7 | Contrast | C2 | Emphasised chip ink over its composited pill ≥ 4.5:1; plain chip on the card ≥ 4.5:1; numbers in the phase log. |

Checks this plan adds:

| # | check | phase | how |
|---|---|---|---|
| 8 | "In reach" only when measured | C2/C3 | Tooltip and popup fact contain `in reach` iff `card.reachMeasured`. |
| 9 | One pool | C1 | `card.tideFit.coastal + inland == card.pool.length` on every card of the fixture; and `card.tide` (T6's served window tide) is untouched by this series. |
| 10 | Axis discipline | C2 | No rendered string on the card contains `on the light`. |
| 11 | Announced, not only drawn | C2 | The card button's accessible name carries the glyph clause and the chip clause; no `sr-only` element exists inside `.wf-hc-pls`. |

---

## §8 Phase → session map

| session | phase | size | depends on |
|---|---|---|---|
| 1 | C0 — served alignment quality (backend) | S | `tide-window` T1/T2 merged (they are) |
| 2 | C1 — data on the pool, per-window summary, the run | M | C0 |
| 3 | C2 — the two marks on the card | M/L | C1 |
| 4 | C3 — the popup fact and the phone | S | C1 (independent of C2; may run in parallel) |
| 5 | C4 — sweep and docs | S | all |

---

## §9 Local verification recipe

As `tide-window-plan.md` §9, with one addition: seed **at least four coastal locations in one region
within 45 minutes' drive of the seeded home postcode**, with tide extremes offset so that on one
sunset three of them are aligned (the chip's gate) and on another only one is (silence), and with a
third window where the same three are aligned but their nearest extremes sit further from the light
(the ranking's tiebreak — §7 #3 seen, not only tested). Give the region's non-coastal spots ratings so
the honesty filter keeps it, and rate one aligned coastal spot highest so it becomes the named
best-reachable spot and wears the glyph.

State plainly which claims were **seen** and which were **tested**.
