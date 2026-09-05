# Map landing — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order. Each phase lands as its own
reviewed commit and PR before the next session starts — the plan's **§0 Status block and Phase log
are the source of truth between sessions**, updated in the same commit as each phase, so a new
session never needs this chat's history. If a session dies mid-phase, start a new one with the same
prompt: step one of every prompt is reading the current state from the repo.

The plan: `docs/engineering/map-landing-plan.md`. The spec: `docs/design/map-landing/README.md`, with
`Map Landing.html`'s right-hand notes column (the rejections in it are load-bearing) and
`map-tab-v4.js` as the prototype. All vendored verbatim — **do not edit them**; the plan's §1
corrections, §4 disagreements and §5 decisions **win** wherever the spec and the code disagree.
`docs/design/map-landing/VENDORING.md` says what is in the directory and what deliberately is not.

**The three things every session must internalise before touching code**, because they are what the
spec gets wrong about this codebase:

1. **The verdict, the region name and both picks are already served and already derived.**
   `BriefingWindow`/`BriefingRegion` carry them, and `utils/windowFirstCards.js#buildWindowCards`
   already folds them per window (`verdict`, `verdictLabel`, `hotRegionName`, `pick`, origin-scoped).
   Most of part 1 is **forwarding**, not deriving. Do not write a client verdict from ratings, and do
   not write the spec's client pick rank.
2. **The pill is a fixed 262px and that was won deliberately** (#773 — the steppers must not move).
   The spec's `max-width: calc(100% - 344px)` and `min-width: 0` are prototype-shaped and do not
   port; `min-width: 0` beside `overflow: hidden` is a **measured no-op** on this codebase.
3. **"Panels do not close when you click the map" is five call sites, not one.** Four components hold
   their own `document` `mousedown` listener and fire *before* the map's `click` handler.

**Owner decisions.** §6 Q1 (night rows' own word) blocks L2's copy; §6 Q2 (when the card opens)
blocks L4's open rule. Q3–Q7 do not block anything. If a blocking decision is still open when the
session reaches it, build the plan's stated recommendation behind one named constant and say so in
the phase log — do not stall, and do not silently pick the other option.

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the pre-commit
adversarial review, which each prompt instructs explicitly — plain parallel subagents (the Agent tool)
are how every previous series ran it: ~6 prosecutor lenses over the diff (runtime behaviour, CSS and
tokens, test quality, accessibility, project conventions, what it makes harder for later phases),
then one refuter per charge defaulting to REFUTED without citable evidence, then a synthesis. Review
agents are **read-only** — one has already destroyed uncommitted work on this project with a
`git checkout --` cleanup; anything that must mutate gets its own worktree, and you commit or stash
before starting a review that runs mutations. Paste the phase's plan section, the plan's §1/§4/§5, and
the spec into every reviewer's prompt: review agents cannot see untracked context, and a compliance
lens with no spec returns zero findings and looks clean.

**Between phases (owner):** review the PR in the browser, merge. Sessions never push and never tag.
Before starting the next session, `git rev-list --count HEAD..origin/main` says whether anything else
merged; `changelog.d/` files never conflict.

---

## L1 · The verdict, the region and the pick, as data

> You are implementing **Phase L1** of `docs/engineering/map-landing-plan.md`. First: read that plan
> **in full** (§1 corrections, §2 strategy, §3 L1, §4, §5, §7 and §9 are binding), then the spec
> `docs/design/map-landing/README.md` §1 and §3, then `gh pr list --state open` and grep the titles
> for *verdict*, *pill*, *landing*, *window control*, *region panel* — stop and report if anything
> overlaps. Then re-verify every file and symbol L1 names against the tree before editing — the plan
> was written 2026-09-05 and code moves. Never push, never create or delete tags. Create
> `feature/map-landing-l1-verdict-data` off up-to-date `main`, in a worktree.
>
> Scope is §3 L1's five tasks and **no visual change whatsoever**: a new pure
> `frontend/src/utils/mapVerdict.js` (the `date|targetType|regionName` index, mirroring
> `utils/regionsJump.js#buildRegionBestIndex` exactly — same key shape, same `region.regionName` join
> key, **never** `region.name`, which the served record does not carry; plus the scope-limited
> `windowVerdict` fold returning tier / regionName / sharingCount / allInScope / scopedRegionCount);
> forwarding `verdict`, `verdictLabel`, `hotRegionName` and `pick` through
> `WindowFirstMapPane.jsx`'s `heat.windows` mapper, which currently drops all four; publishing the two
> candidate region-name sets from the pane (the `heatArea` toggle lives in `MapView`, so the pane
> cannot choose — read the pane's own ownership doc block before moving anything); enriching
> `utils/mapEvents.js#solarRow`; and memoising the tally in `MapView` on `(events, heatArea, index)`.
>
> **Build no threshold function.** The spec's `>= 3.7 / >= 2.8` fallback does not land where it claims
> — `BriefingRatingStats` bands a region mean at `>= 3.5 / >= 2.5` — and `BriefingRegion
> .displayVerdict` is never null, so the fallback is unreachable. §4 #1 and §5 D-1. Where no in-scope
> region carries a served verdict, return `null` and render nothing. Night rows get no verdict and no
> pick and must not synthesise either; leave `bestOfNight`'s existing licence untouched.
>
> Tests per §3 L1, pure, including all three region-label cases (the all-in-scope one is the case that
> regressed during the design), the `scopedRegionCount > 1` guard, an unscored region counting in the
> denominator but never winning the name, and check 8 (Map/Plan verdict agreement, driven from **one**
> fixture so neither surface can pre-satisfy the other). Gate on exit codes, never output:
> `npm run lint && npm test && npm audit --audit-level=high && npm run build`. Then run the
> adversarial review per CLAUDE.md § *UI Work — Review Cadence* (agents read-only; paste L1, §1, §4,
> §5 and the spec into every reviewer's prompt). Fix survivors, re-run the gate. Browser verification
> for this phase is one claim only — that nothing moved: capture the rendered window pill's text
> before and after on the same seeded fixture (§9 recipe; ⚠️ **never** trigger
> `POST /api/forecast/run`, it bills the real Anthropic key). Commit with a conventional message, a
> `changelog.d/YYYYMMDD-map-landing-l1-verdict-data.md` entry, and two plan-doc updates **in the same
> commit**: flip §0 to in-progress and add L1's row to the Phase log; append any §4 entry the work
> forced. Do not push; report the branch, the commit, and what you saw versus tested.

---

## L2 · The pill — verdict cell, medallion, stepper ticks

> You are implementing **Phase L2** of `docs/engineering/map-landing-plan.md`. First: read the plan
> **in full** and its Phase log (L1 has merged; read L1's row and any §4 entries it added), then the
> spec's §1 and §2 **and the notes column of `docs/design/map-landing/Map Landing.html`**, then
> `gh pr list --state open` for overlap, then re-verify every file and symbol L2 names against the
> tree. Never push, never tag. Create `feature/map-landing-l2-pill` off up-to-date `main`, in a
> worktree.
>
> Scope is §3 L2's seven tasks. The verdict cell (word + region **stacked**, never inline — inline
> cost ~120px and pushed the stepper under the nav cluster), the inset-left tier tint, night rows with
> **no** tint and no borrowed word, the outline medallion with **glyph and words as separate
> elements** (`font-size: 0` + `::first-letter` does not work — the glyphs are symbols, not letters,
> and the chip renders as an empty bordered box), the stepper verdict ticks, the re-derived widths,
> and the phone rules. Every colour is a **token in `index.css`**, never a literal in JSX. Never draw
> ramp or verdict colour as text on a coloured fill. Do **not** add a chip that points at other
> windows — it was built and cut.
>
> **The width is the trap.** The pill is `width: 262px; flex-shrink: 0`, derived from the menu's 334px
> so the two share both edges (#773 — read that CSS comment in full before changing a number). A
> verdict cell and a medallion do not fit. Per §5 D-3: **measure** the widest reachable content in
> Chromium against this stylesheet's own loaded fonts, set the menu to pill + 2×32 + 2×4, keep
> `flex-shrink: 0`, and record both figures and the method in the CSS comment the way #773 did. Do
> **not** add `max-width: calc(100% - 344px)` (a fixed group is already bounded) and do **not** add
> `min-width: 0` (a measured no-op beside `overflow: hidden` — and do not write a test that pins the
> no-op).
>
> §6 **Q1** (whether a night row states its own `Clear`/`Cloudy`/`Kp N` word) may still be open: if so,
> render the night cell empty — the plan's recommendation — and say so in the phase log. Do not derive
> a night word from `bestRating`.
>
> Tests per §3 L2, plus **check 1** at the `MapView` level (the pill's word and region are string-equal
> across min-rating / reach / subject / dark-sky changes, and change when `heatArea` flips) and
> **check 2** (all three region-label cases, including the `everywhere` string in its §4 #7 three-way
> form — the app's segment reads `My area` / `Everywhere` / `Around <base>`, so one string is wrong in
> two of the three states). Gate on exit codes. Adversarial review per the cadence (read-only agents;
> paste L2, §1, §4, §5 and the spec). Then **check 4, measured in the browser, not screenshotted**:
> at 1280×800, 834×1112 and 390×844 report the next stepper's `right` vs the Regions chip's `left`,
> `.wf-win-label`'s `scrollWidth` vs `clientWidth`, and the medallion glyph's computed `font-size` at
> 390 — as numbers. ⚠️ The Browser pane can wedge; the fallback that has shipped every phase of this
> series is headless Chromium through `playwright-core` directly, and it is the only instrument that
> can answer check 4 at all. Commit with a changelog entry and the plan-doc updates in the same
> commit. Do not push; report numbers, and what you saw versus tested.

---

## L3 · Panel persistence — a map click no longer closes a panel

> You are implementing **Phase L3** of `docs/engineering/map-landing-plan.md`. First: read the plan
> **in full** and its Phase log, then the spec's §5 closing paragraphs and the "Nothing closes because
> you looked at the map" note in `Map Landing.html`, then `gh pr list --state open` for overlap, then
> re-verify the five call sites §1 #5 tabulates. Never push, never tag. Create
> `feature/map-landing-l3-panel-persistence` off up-to-date `main`, in a worktree. This phase has no
> data dependency on L1 or L2 and may land before either.
>
> Scope is §3 L3's three tasks. ⚠️ **Removing the `setOpenMapMenu(null)` branch from
> `MapBackgroundClickController`'s wiring does nothing on its own** — `WindowControl`,
> `FiltersPopover`, `RegionsJump` and `MapLegendPanel` each hold a `document`-level `mousedown`
> listener that fires *and commits* before the map's `click` reaches that handler. That ordering is
> already documented at length in `MapBackgroundClickController`'s own class doc, where it caused a
> live regression that no unit test caught. Both halves move together. Prefer one shared
> `hooks/useOutsideDismiss.js` with an ignore predicate so the four cannot drift; four inline
> `contains` guards are acceptable only if the shared hook needs four different roots — say which you
> did and why. Clicks **outside** the map frame still dismiss. Bare-ground **deselect** stays: the spec
> keeps it explicitly ("that is a selection, not a panel").
>
> Then audit the affordances the rule now leans on: each panel must close on **its own chip**, **its ✕
> where it has one**, and **Escape**. `RegionsJump` already has `wf-jump-reset`; `FiltersPopover` and
> `RegionsJump` get `BottomSheet`'s close on phone; check `MapLegendPanel` and the window menu and add
> what is missing. A panel closable only by re-pressing a chip the reader has forgotten is worse than
> what it replaces.
>
> Tests per §3 L3 — and ⚠️ write the **real `mousedown` → `click` pair**, not a captured `click`
> handler invoked by hand: that is exactly the shape of test that missed the original regression.
> That is check 9. Gate on exit codes. Adversarial review per the cadence (read-only agents; paste L3,
> §1 #5, §4, §5 and the spec). Browser: with each of the four open in turn, pan / zoom / wheel / click
> bare ground and report four pass-fail cells, then confirm chip / ✕ / Escape each close it. Commit
> with a changelog entry and the plan-doc updates in the same commit. Do not push; report what you saw
> versus tested.

---

## L4 · The landing card

> You are implementing **Phase L4** of `docs/engineering/map-landing-plan.md`. First: read the plan
> **in full** and its Phase log (L1 and L3 have merged), then the spec's §4 **and every note in
> `Map Landing.html`'s right-hand column about the card** — the rejections there are the
> specification: dismiss-on-drag, a header naming windows not on screen, an irrecoverable dismissal,
> and opening on the best bet rather than on now were all built and cut for stated reasons. Then
> `gh pr list --state open` for overlap, then re-verify every symbol L4 names. Never push, never tag.
> Create `feature/map-landing-l4-landing-card` off up-to-date `main`, in a worktree.
>
> Scope is §3 L4's nine tasks: a new `components/map/MapLandingCard.jsx` at 376px, `top: 60px;
> left: 12px`, **z-index 1300 in this app's ladder** (above the callout's 1200, below the tooltip's
> 1400 and the menus' 1500 — a menu must win over a card behind it); two rows, the next two **solar**
> windows from now (the served payload has already withdrawn elapsed events via
> `PlanWindowProjector.hasPassed` — do **not** write a second pastness rule); a header **derived from
> the rows it is showing**, exported and unit-tested on both branches plus the one- and zero-row
> cases; picks riding their rows, a later pick as one quiet uncoloured line, an earlier pick
> suppressed entirely; the all-Poor branch naming a window **strictly later than both rows** that is
> **Worth it**, and ending the sentence early when none exists; dismissal on ✕ / Escape / row-select
> **only**; the reopen row at the top of the pill's menu with the identical header string.
>
> ⚠️ **It is not a `Modal`.** It does not claim `aria-modal`, does not enter the shell's stack, and
> does not consume either of the two permitted dialog layers (§5 D-5, and CLAUDE.md's Plan-screen
> dialog invariant). ⚠️ It must not dismiss on map click, drag, zoom, wheel or outside tap — panning
> to the region it just named is *reading* the card.
>
> §6 **Q2** (how often it opens) may still be open: build the recommendation — once per forecast run,
> keyed on `briefing.generatedAt` through `MapView`'s existing `readMapFilter`/`writeMapFilter`
> helpers, which already carry the try/catch and the read-during-render caveat — behind **one named
> constant**, and say so in the phase log. Pick the phone treatment (`BottomSheet modal={false}`, the
> idiom `FiltersPopover` and `RegionsJump` already use, or the same card inset), state which, and keep
> the dismissal rules identical either way.
>
> Tests per §3 L4, covering **checks 5 and 6**. Check 6 needs a seeded all-Poor fixture (§9 recipe;
> ⚠️ **never** `POST /api/forecast/run` — it bills the real key): assert both row verdicts read Poor,
> and read the "next up" window index and tier out of the DOM **as numbers** to prove it is strictly
> later and Worth it. Gate on exit codes. Adversarial review per the cadence (read-only agents; paste
> L4, §1, §4, §5 and the spec). Browser: check 5's full matrix — pan, zoom, wheel, ground click each
> leave it; ✕, Escape and a row select each remove it; the reopen row returns it with the same header
> string. Commit with a changelog entry and the plan-doc updates in the same commit. Do not push;
> report numbers, and what you saw versus tested.

---

## L5 · The window panel — window → regions

> You are implementing **Phase L5** of `docs/engineering/map-landing-plan.md`. First: read the plan
> **in full** and its Phase log (L1, L3, L4 merged), then the spec's §5 **Window panel** table — its
> four note variants are copy strings to be matched — then `gh pr list --state open` for overlap, then
> re-verify every symbol L5 names. Never push, never tag. Create
> `feature/map-landing-l5-window-panel` off up-to-date `main`, in a worktree.
>
> Scope is §3 L5's five tasks: the panel header (label, time, confidence percentage, medallion if a
> pick, verdict chip in tier colour); the note branched four ways, with the several-regions branch
> printing L1's `sharingCount` and the all-in-scope branch **dropping the drive-time sentence** and
> taking §4 #7's three-way scope wording rather than the spec's single string; region rows **ranked by
> mean with the ceiling shown beside it**; and the panel obeying L3's persistence rule.
>
> ⚠️ **Every number on these rows is served.** `BriefingRegion.meanRating` and
> `BriefingRegion.bestRating` come off the payload — do **not** take a client max over drawn spots.
> `utils/windowFirstRegions.js`'s own doc block records why at length: the canopy fallback lives on
> the backend and cannot be reconstructed here, and it disagrees in exactly the seasons it exists for.
> ⚠️ `N of M at 4 stars+` is a **reach-scoped count of places you could drive to**, so it falls under
> CLAUDE.md's named licensed class and **may not say "within reach" unless a drive time exists to have
> gated on** — reuse the existing `card.reachMeasured` producer; do not mint a second. An unknown
> drive passes every tier.
>
> Tests per §3 L5: each of the four note variants renders for its state **and only for its state**; a
> fixture where mean-order and ceiling-order differ proves the ranking is by mean; a night window's
> rows show an em dash; a region with no mean sorts last rather than as a zero; the reach clause is
> absent when `reachMeasured` is false. That is check 7's panel half. Gate on exit codes. Adversarial
> review per the cadence (read-only agents; paste L5, §1, §4, §5 and the spec). Browser: open the
> panel from all the entrances that exist at this point and confirm it survives a pan. Commit with a
> changelog entry and the plan-doc updates in the same commit. Do not push; report what you saw versus
> tested.

---

## L6 · The region panel, into the existing sheet

> You are implementing **Phase L6** of `docs/engineering/map-landing-plan.md`. First: read the plan
> **in full** and its Phase log (L5 merged), then the spec's §5 **Region panel** paragraphs, then
> `map-tab-v2-plan.md` **O-18** and **O-20** (the sheet already opens over the map, and the pane under
> a peek is not inert — both constrain this phase), then `gh pr list --state open` for overlap, then
> re-verify every symbol L6 names. Never push, never tag. Create
> `feature/map-landing-l6-region-panel` off up-to-date `main`, in a worktree.
>
> Scope is §3 L6's four tasks: the region panel (back arrow to the window panel; name, window,
> medallion, verdict chip; the stats line with the same `reachMeasured` caveat as L5; the top four
> locations; the region's served narrative); the two actions; and the handoff into the sheet.
>
> ⚠️ **The tide glyph is served** — `BriefingSlot.TideInfo.tideOnTheLight`, shipped by #749, which
> closed O-17 precisely so the glyph would stop being one representative coastline's geometry asserted
> on every coastal chip. Do not re-derive it. ⚠️ **`Four days at <full location name>`** — splitting on
> whitespace produced "Four days at Infinity" and "Four days at Scott's", which are not places.
> ⚠️ **Extend `LocationFourDaySheet`; do not build a second sheet.** Route through the same
> `onOpenLocationSheet` handoff with `inPlan: false`, so the sheet's own footer map door is stamped
> `inPlace` from the tab in force and does not import the Plan's lens onto the map the reader is
> already looking at. ⚠️ **Stack depth**: the shell refuses a third dialog layer, and these panels are
> not `Modal`s and must not become them — close the panel before opening the sheet, the same
> close-then-move ordering `utils/mapDoors.js#openMapDoor` already encodes. `Zoom to region` reuses
> `MapView`'s existing `jumpToRegion`, which already flips `heatArea` for an out-of-area region.
>
> Tests per §3 L6: the action label prints the full name for a two-word and an apostrophe'd name;
> `Zoom to region` flips scope for an out-of-area region and not for an in-area one; the sheet opens
> with `inPlan: false` and the panel is closed first; back returns to the window panel on the same
> window. Gate on exit codes. Adversarial review per the cadence (read-only agents; paste L6, §1, §4,
> §5, the spec, **and O-20**). Browser: walk window panel → region panel → sheet and back, and confirm
> one `Escape` does not close two layers. Commit with a changelog entry and the plan-doc updates in
> the same commit. Do not push; report what you saw versus tested.

---

## L7 · Sweep, docs and the OPEN answers

> You are implementing **Phase L7** of `docs/engineering/map-landing-plan.md` — the sweep. First: read
> the plan **in full**, its whole Phase log, and every §4 entry the six build phases appended. Never
> push, never tag. Create `feature/map-landing-l7-sweep` off up-to-date `main`, in a worktree.
>
> Scope is §3 L7's five tasks and **no behaviour change**: answer the spec's three `OPEN`s against
> what actually shipped, recording the owner's decisions in §6; update `CLAUDE.md`'s **Map tab (v2)**
> bullet and its **Backend-heavy** bullet — the latter gains `mapVerdict`'s tally as a **named** member
> of the licensed per-user-join class with its recorded exit (§5 D-4, §6 Q4), written in the same voice
> as the five classes already there, and explicitly **not** as a precedent for client aggregation;
> update `map-tab-v2-plan.md` §6 with what this increment closed or opened (O-4 is now load-bearing
> for the pill's region line; O-6 and O-16 are untouched, and O-16 is *why* night rows carry no
> verdict); grep for orphans — any constant, CSS class or `data-testid` a phase introduced and left
> with no reader; and add the tally to `docs/engineering/plan-panel-data-contracts.md` if and only if
> it survived as a client computation.
>
> ⚠️ Every claim you write into `CLAUDE.md` must be re-verified against the tree, not copied from this
> plan — the plan was written before the code and the phases were allowed to disagree with it. Where a
> §4 entry says the code diverges from the spec on purpose, the doc must say so too, so a later reader
> reads a decision rather than an accident.
>
> Gate on exit codes (`npm run lint && npm test && npm audit --audit-level=high && npm run build`) even
> though this phase is documentation — a stale `data-testid` removal is a code change. Adversarial
> review is optional here; a single read-only conventions lens over the diff is enough. Commit with a
> `changelog.d/` entry and flip §0 to complete. Do not push; report what changed.
