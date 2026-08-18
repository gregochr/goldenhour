# Plan-tab verdict consolidation — one source, one vocabulary

**Status:** in progress. Decision taken: **region-led verdicts** (owner's call, 2026-08-16).
**Phase 0 + Phase 1 shipped 2026-08-16** on branch `fix/plan-verdict-consolidation`, merged to
`main` as #523 (squash — the branch's original hashes are not ancestors of main). Phase 0's prod
diagnostic found BEST on an unrendered window (2026-08-19 sunset, the 7th non-past event),
confirming D3 for the observed instance.
**Phases 2 and 3 shipped 2026-08-17/18** on branch `fix/plan-verdict-phases-2-3`, on the owner's
explicit instruction to proceed — so they land **before** the v2 flag flip, not after it, and §6's
"gate behind a caller opt-in" clause is what carries the Phase 3 work that touches `HeatmapGrid`
(`serverCellRating`, defaulting off, so v1 keeps its own cell star). ⚠️ **That opt-in does not make
v1 untouched, and the canopy fix below is where it stops being true**: `displayVerdict` is a payload
field, not a prop, so v1's grid cell word moved with v2's — and v1's own star derivation was then
moved by hand to match it, which is the one deliberate change to the frozen control.
`usePlanLayout.js` still defaults to `PLAN_V1`. Phase 4 not started.

**Phase 3's one deliberate behavioural cost.** The six-event cap is now the backend's alone, so when
the client's stale-cache guard withdraws an event the payload still lists, the rail shows one day
fewer rather than reaching further down `days` to refill. Refilling means re-implementing the cap
this phase deleted. It is reachable only from an SWR payload that has aged past one of its own
events, under-reports rather than over-reports, and self-heals at the next poll. Pinned by
`WindowFirstBriefingContext.test.jsx` "shows one day fewer, rather than refilling".

**§5's one open question is resolved:** `DisplayVerdict.resolve(Integer, Verdict)` **stays**. The
window verdict was not its last single-rating caller — `BriefingSlot` (3 sites) and
`EvaluationViewService` (2 sites) still pass a rating. Only the projector's call was deleted.
**Goal:** every surface on the Plan tab renders the same backend-computed answer, so the class of
disagreement observed on 2026-08-16 becomes structurally impossible rather than individually patched.
This is a stability investment: the app should be usable for years without these surfaces drifting
apart again.

---

## 1. The observed defects (2026-08-16 screenshot, prod)

**D1 — Three surfaces, three aggregators over one payload.** The window rows said "Worth it ·
best 4★" for Tonight/Tomorrow/Tuesday while the day cards said "All poor" and the regional grid
showed a single Worth-it cell (Tuesday sunset, Northumberland & Tyneside, 3.6★). All three read the
same ratings from the same `GET /api/briefing` payload; they disagree because:

| Surface | Population | Aggregator | "Worth it" rule | Where |
|---|---|---|---|---|
| Window row | every non-canopy slot, **all** regions | **max** | `>= 4` (integer) | `PlanWindowProjector.bestRating`/`verdict`, `DisplayVerdict.resolve` |
| Grid cell | one region's slots | **mean** | `>= 3.5` | `BriefingRatingStats` (:134-145) |
| Day card | the day's region verdicts | any-of | any region `>= 2.5` | client roll-up, `windowFirstRail.js:202-226` |

One 4★ location anywhere in the roster promotes a whole window's badge while its own region's mean
sits below 2.5. The max-based row rule was a deliberate, documented choice
(`PlanWindowProjector.java:52-57`) whose inverse failure mode — a window badge contradicting every
cell beneath it — was never recorded. The row answers *"is the single best spot worth a trip?"*;
the grid answers *"is this region on average worth it?"*; the screen never says they are different
questions.

**D2 — A grid cell's verdict word and its star come from different fetches.** The cell's verdict
derives from the briefing payload's region mean; the star is a client-side mean over
`GET /api/briefing/evaluate/scores`, joined by a region-**name** string prefix
(`HeatmapGrid.jsx:630-643`) that fails silently to a fallback. Two endpoints, two cache lifetimes,
one cell. (Already flagged in `plan-panel-data-contracts.md` §5/§7.)

**D3 — The Best/Also pick pool is not the rendered set.** `PlanWindowProjector.selectPicks` ranks
over every live window in the first **4 distinct dates** (`RENDERED_DAY_COUNT = 4`, up to 8 events);
the client renders the first **6 events** (`WindowFirstBriefingContext.jsx:29`,
`MAX_VISIBLE_EVENTS = 6`). A BEST landing on the 7th/8th event has no card and no badge — silently —
while an ALSO inside the six draws normally, orphaning it. The rail already suppresses orphaned
flags (an earlier half-fix); the window cards do not. The projector's comment claims its horizon
"mirrors the rail's own window", which is false: 4 dates ≠ 6 events. Note
`BriefingRollupBuilder.MAX_VISIBLE_EVENTS = 6` already uses the right rule — the projector is the
odd one out.

**D4 — BST off-by-one in the projector's clock.** `BriefingService.java:336` passes
`LocalDateTime.now(clock.withZone(LONDON))` into `PlanWindowProjector.apply`, but
`BriefingSlot.solarEventTime` is **UTC**. During BST every window is declared past one hour early:
today's sunset leaves the pick pool ~30 minutes *before the sun sets* (afterglow is 30 min), while
the client — which parses the time correctly as UTC — still draws the card. Sibling sites get this
right (`CloseToHomeService.java:582` uses `clock.withZone(UTC)`); this is the "one clock, two
calendars" rule from the `daysAhead` work: **dates** are London, **instant comparisons** are UTC.
This compounds D3 every summer evening by pushing BEST onto a later, possibly unrendered, window.

**D5 (latent) — Two best-bet systems.** `briefing.bestBets` (the Claude advisor, frozen between the
04:00/14:00/22:00 UTC builds, no serve-time re-derivation) is rendered only by the v1 arm.
The v2 Plan tab renders `eventSummaries[].window.pick` (BEST/ALSO), which *is* recomputed per
request and *does* exclude passed events. On v2, `bestBets` is dead weight waiting to confuse
someone.

**Honest caveat on the observed instance:** neither D3 nor D4 perfectly explains the specific
screenshot (D3 requires the unrendered Wednesday-sunset window to be top-ranked, which its all-Poor
grid cells argue against; D4 is an evening mechanism and the screenshot is mid-afternoon). Both are
real code defects regardless. Phase 0 includes the one-step diagnostic that settles which mechanism
fired — do not skip it, in case there is a third.

---

## 2. The decision (taken)

**Region-led vocabulary.** Every verdict word — day cards, window rows, grid cells — derives from
region-average quality (the grid's current rule: mean ≥ 3.5 → WORTH_IT, ≥ 2.5 → MAYBE, else
STAND_DOWN, triage fallback when unrated). The single-best-spot signal survives as an explicitly
**labelled chip** (`best spot 4★`) that never borrows the verdict vocabulary. The 2026-08-16
screenshot's rows would have read `Poor · best spot 4★` — provisional-looking, and truthful.

This retires the `PlanWindowProjector.java:52-57` rationale ("a MAYBE badge above a strip whose
lead card reads Worth it"): with the spot channel explicitly labelled, a `Maybe · best spot 4★`
header above a 4★ lead card is two labelled facts, not a contradiction. **Done in Phase 2** — that
comment now records this decision and its date, and cites this section back. (Grep for the symbol;
the `:52-57` anchors above are from 2026-08-16 and the block has since been rewritten.)

**One consequence the decision did not foresee, found by the Phase 2 adversarial review — now
FIXED (2026-08-18, before the flip, on the owner's instruction).** A region's average was
canopy-**inclusive** while `bestRating` and the card's spot strip both **exclude** canopy slots, so
region-led verdicts let a rated wood lift a badge a band *above every rating the card renders* —
`◎ Worth it` over `best spot 3★`, with the wood shown nowhere. A widening rather than a new class:
the pre-existing unrated-window fallback already read that average.

The rule now has **one owner**: `BriefingSlot.votingSlots` — non-canopy slots, falling back to all
of them for an all-canopy region — replacing four independent copies, one of which was simply
missing. Its callers are `BriefingHierarchyBuilder.buildRegion` (the triage verdict),
`BriefingService.rosterOf` (the confidence roster), `BriefingService.enrichWithCachedScores` (the
region's `displayVerdict` and `meanRating`) and `PlanWindowProjector.rank` (region ranking and the
published `Pick.averageRating`). The projector had to move with the rest or its ranking would order
regions by a mean no surface displays.

**It reaches the frozen v1 arm, and the arm was moved to match.** `displayVerdict` is a payload
field both arms render — there is no prop to gate it — so v1's grid cell word moved with v2's. Its
star is derived client-side, so it was moved too: `HeatmapCell`'s non-opted-in path applies the same
rule by hand across both of its lookups (the name-keyed `/evaluate/scores` join and the slot
fallback), with the all-canopy fallback preserved. **This is the one place the pilot control was
deliberately unfrozen**, on the owner's instruction, because a control whose cell contradicts itself
measures nothing. v2's cell reads both figures from one payload.

**Two averages stay canopy-inclusive, for different reasons.**
`PipelineRunPickService.lookupAverageRating` reads a name-keyed score cache with no canopy flag and
has nothing to filter on. `BriefingRollupBuilder.computeRegionStats` reads the same cache, but its
caller holds the enriched region already — a canopy-name set is one line away — so it is undone
rather than blocked, and it is the one worth doing: it feeds the best-bet advisor's prompt, a
decision input. Neither renders as a verdict and neither is a regression.

**Two things deliberately did NOT move.** `scoredLocationCount` stays canopy-inclusive — the
honesty filter tests it against its own canopy-inclusive `scoreable` count, and both arms render
"N of M evaluated" against the whole slot list, so a voting count there would under-report a scored
wood and could blank a region whose only evaluated location is one. And `ConfidenceDeriver`'s
coverage denominator stays `scoreable` while its numerator is now the voting count, which for a
wood-bearing region is marginally pessimistic — it can only make the channel *more* provisional,
the safe direction.

⚠️ **A region whose only rated slot is a wood is floored at `LOW` explicitly, not left null.** The
first cut of this returned null there and called it honest. It is not: `confidenceUtils
.resolveConfidence` is fail-soft and turns an absent field into a horizon tier capped at *medium*,
so the channel would have read **less** provisional at exactly the point it knows least — nothing
that votes is scored and the band came from the triage fallback. Null still means nothing scored at
all, which is the documented zero-coverage case. Found by the adversarial review, pinned by two
tests on either side of that boundary.

---

## 3. Target architecture

One sentence: **the backend computes, at serve time, everything any Plan surface renders; the
client aggregates nothing.**

- `PlanWindowProjector` (already serve-time) becomes the single owner of: the rendered event list
  (ordered, capped at the shared 6-event constant, pastness decided against a UTC instant), each
  window's verdict (= its top region's `displayVerdict`), the labelled `bestSpotRating`, and the
  BEST/ALSO picks — selected over exactly the rendered list, so an orphaned ALSO is impossible by
  construction.
- `BriefingRegion` gains a serve-time per-(region, window) mean rating, so the grid cell's star and
  its verdict word come from one computation. `/api/briefing/evaluate/scores` remains for
  drill-down detail only.
- The payload gains a per-day peak (the day-card roll-up), computed from the same region verdicts.
- The client (`WindowFirstBriefingContext`, `windowFirstCards`, `windowFirstRail`, `HeatmapGrid`)
  renders fields. Its only remaining logic is a minimal stale-cache pastness guard (an SWR-cached
  payload ages up to 12h; dropping elapsed windows client-side is a data-freshness defence, not an
  aggregation).
- `docs/engineering/plan-panel-data-contracts.md` gets updated: the summary-strip roll-up and grid
  cell derivation debt entries are retired, and §3's "strip/grid disagreement structurally
  impossible" claim is corrected — it held for the *data*, not the *aggregators*; after this work
  it holds for both.

---

## 4. Phases

### Phase 0 — Diagnose + pin (no behaviour change)

1. **Prod diagnostic:** capture the served `/api/briefing` JSON (browser devtools on
   photocast.online) and find which `(date, targetType)` carries `pick: "BEST"`. Unrendered
   window → D3 confirmed for the instance. Passed window → stale client cache (check SWR
   revalidation). A *rendered* window → unknown third mechanism; find it before proceeding.
2. **Pinning tests, written red where the defect lives:**
   - `PlanWindowProjectorTest`: a two-events-per-day fixture (the existing
     `horizonCountsSurvivingDates` uses one event per day, which is why 8-vs-6 was invisible).
     Assert picks land only within the first 6 events.
   - Clock test: at a fixed BST serve instant 19:00 UTC, a window at 19:30 UTC is **not** past
     (fails today). Fixed clocks only — never the wall clock in a date fixture.
   - Frontend invariant: an ALSO pick without a surviving BEST card renders no badge.
   - Surface-agreement (guides Phase 2): window verdict === top region `displayVerdict`.

### Phase 1 — Correctness fixes (small, shippable now)

1. `BriefingService.java:336`: `clock.withZone(LONDON)` → `clock.withZone(ZoneOffset.UTC)` for the
   projector's `now`. Dates elsewhere in the class stay London (lines 267/481/762 are civil-date
   derivations and are correct).
2. Pick pool = rendered window: the projector selects over the first **6 non-past events**, sharing
   one named constant with `BriefingRollupBuilder` (single owner; delete `RENDERED_DAY_COUNT` or
   redefine it in event terms). Fix the "mirrors the rail" comment.
3. Client belt-and-braces in `windowFirstCards.js`: drop an `also` pick when no `best` pick
   survives into the built cards (mirrors the rail's existing suppression). Kept even after the
   backend fix — it is the stale-cache defence.

### Phase 2 — One vocabulary (region-led; the decided semantics)

1. `PlanWindowProjector.verdict` returns the top region's `displayVerdict` (mean-based) instead of
   `DisplayVerdict.resolve(maxRating)`. Triage fallback unchanged for unrated windows.
2. `bestRating` stays on the payload but the row renders it as a labelled chip (`best spot 4★`),
   visually distinct from the verdict badge. Rename the field to `bestSpotRating` if cheap; do not
   let the old name imply verdict semantics.
3. Day cards and grid are already region-led — no semantic change, only the ownership move in
   Phase 3.
4. Update the `PlanWindowProjector.java:52-57` design comment to record this decision and date.

### Phase 3 — One owner per derivation (retire the client-side debt)

1. Backend emits the rendered event list; `WindowFirstBriefingContext.selectUpcomingEvents` becomes
   a pass-through plus the minimal stale-cache pastness guard. `MAX_VISIBLE_EVENTS` leaves the
   client.
2. Backend emits per-cell mean rating; `HeatmapGrid` drops the name-keyed `/evaluate/scores` join
   for the star (D2).
3. Backend emits the per-day peak; retire the `windowFirstRail.js:202-226` roll-up.
4. Update `plan-panel-data-contracts.md` as described in §3 above.

### Phase 4 — Kill the second best-bet system (post-flip)

After the v1→v2 flag flip: remove the v1 `BestBetBanner`/`bestBets` render path and
`applyBestBetFallback`. The Claude advisor's *narrative* (drive times, day-of-week prose) is a paid
build-time call and stays build-time — either render it only when its named window is still live at
serve time, or fold its prose into the serve-time BEST pick's detail. Decide when we get there;
do not let the stale-pick failure mode back in through the prose.

---

## 5. Code this plan deletes

Deletion is part of the deliverable, not cleanup: every aggregator left behind is a candidate for
someone to re-wire. Each removal lands in the phase that makes it dead, in the same commit — never
"later".

**Phase 1**
- `PlanWindowProjector.RENDERED_DAY_COUNT` (`:92`) and the date-counting `.limit()` in
  `selectPicks` — replaced by the shared 6-event constant. The false "mirrors the rail's own
  window" comment goes with it.

**Phase 2**
- The max-based window-verdict branch: `PlanWindowProjector.verdict(bestRating, top)`'s
  `DisplayVerdict.resolve(bestRating, null)` path (`:325-333`). If the window verdict was
  `DisplayVerdict.resolve(rating, …)`'s last caller for a *single* rating (regions use the
  `BriefingRatingStats` mean thresholds), delete that overload too — check callers first.
- The `:52-57` design comment defending the max rule — replaced, not appended to.

**Phase 3**
- `windowFirstRail.js:202-226` — the client day-peak roll-up (day cards render the backend
  `dayPeak` field instead).
- `HeatmapGrid.jsx:630-643` — the star-from-`/evaluate/scores` computation: the name-keyed prefix
  join, the client-side mean, and its silent `region.slots` fallback (the cell renders the backend
  per-cell mean). The `/evaluate/scores` *fetch* survives only where drill-down detail needs it —
  if the grid was its last consumer outside drill-down, remove the fetch from the grid's data path
  entirely.
- `WindowFirstBriefingContext.jsx`: `MAX_VISIBLE_EVENTS` (`:29`) and the capping/ordering logic in
  `selectUpcomingEvents` (`:55-69`) — the backend list is authoritative. Only the minimal
  stale-cache pastness guard remains, and it should shrink to exactly that.

**Phase 4** (post-flip)
- `BriefingService.applyBestBetFallback` (`:437-454`) and the `bestBetStatus == FAILED`
  substitution path.
- The v1 `BestBetBanner` render path in `DailyBriefing.jsx` (`:1332-1343`) — falls out of the
  wider v1-arm removal, but list it here so the `bestBets` chain is traceable end to end.
- The `briefing.bestBets` payload field.
- `BriefingBestBetAdvisor` + `BriefingRollupBuilder`: **conditional** on the Phase 4 narrative
  decision. If the Claude prose folds into the serve-time BEST pick (or is dropped), both classes,
  their tests, and the advisor's build-time invocation go. If the narrative is kept as-is, they
  stay — but then the liveness gate in §4 Phase 4 is mandatory, and this line should be updated to
  say which way it went.

After Phase 3, `docs/engineering/plan-panel-data-contracts.md`'s debt entries for the
summary-strip roll-up and grid cell derivation are deleted (not marked done — deleted), per §3.

## 6. Sequencing and blast radius

- **Phase 1 is safe now.** The backend changes are v2-payload fields the v1 arm ignores, and
  `windowFirstCards.js` / `WindowFirstWindowCard` are v2-only.
- **Phases 2–3 touch shared components** — `HeatmapGrid` is the re-parented v1 heatmap, and the v1
  arm is the frozen control until the flag flip (~2026-08-18). Land them **after the flip**, or
  gate behind a caller opt-in if they must land sooner. Check re-parented components at 390px; the
  phone is a real surface.
- Every UI commit follows the standing cadence: build → tests → adversarial review of the diff →
  fix survivors → re-verify → commit. Review agents read only; anything mutating gets a worktree.
- Test standards: backend `test-improvement-standards.md`, frontend `frontend-test-standards.md`.
  Fixed clocks in every fixture; a `Clock.fixed` must never land on the real today.

## 7. Invariants this buys (the regression suite's contract)

1. Any two Plan surfaces showing a verdict for the same (region, window) show the same verdict —
   payload-equality, not pixel-equality.
2. An "Also good" badge implies a rendered "Best bet" badge.
3. A pick never names a window outside the rendered set, and never names a passed event at serve
   time (UTC instant, afterglow included).
4. A grid cell's star and verdict word derive from one backend computation.
5. The verdict vocabulary is region-led everywhere; the best-spot channel is labelled and never
   uses verdict words.

## 8. Handover notes (for the implementing session)

Written 2026-08-16 when this plan was handed to a fresh session. **No code has been changed** —
this document is the entire deliverable so far, and §1 is the only record of the investigation
(the analysis behind it is not preserved anywhere else).

- **Re-verify the anchors before editing.** Every file:line reference in this doc was verified
  against the tree on 2026-08-16. This codebase moves quickly — grep for the symbol, don't trust
  the line number.
- **Phase 0's prod diagnostic needs the owner.** The dev machine cannot reach production (separate
  Linux host; no log/DB access). The `/api/briefing` JSON must come from the owner's browser
  devtools on photocast.online. If it shows BEST on a *rendered* window, stop and find the third
  mechanism before writing fixes.
- **The working tree at handover carried unrelated uncommitted work**: modified
  `docs/engineering/cloud-approach-veto-fix.md`, untracked `corridor-forecast-cut-plan.md` and
  `veto-demotion-plan.md`. They are not part of this plan — never `git add -A` them into its
  commits.
- **Standing project rules that bind this work** (all in CLAUDE.md / test-standards docs, listed
  here because they are easy to miss): the UI review cadence (build → tests → adversarial review →
  fix → re-verify → commit; review agents read-only); gate builds on Maven's exit code, never a
  grep of `-q` output; fixed clocks in fixtures, never landing on the real today; conventional
  commits + CHANGELOG entry per meaningful commit; never push, never tag.
- **Branch first, commit early.** Another session can switch branches under this checkout
  mid-task — commit this doc and each phase to a `fix/`-prefixed branch before doing anything
  else, and prefer a worktree for long-running work.
