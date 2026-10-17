# Tide on the window — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order (§8 of the plan says which
may run in parallel). Each phase lands as its own reviewed commit and PR before a dependent session
starts — the plan's **§0 Status block and Phase log are the source of truth between sessions**,
updated in the same commit as each phase, so a new session never needs this chat's history. If a
session dies mid-phase, start a new one with the same prompt: step one of every prompt is reading the
current state from the repo.

The plan: `docs/engineering/tide-window-plan.md`. The spec: `docs/design/tide-window/README.md`, with
`Map Tide Window.html`'s right-hand notes column (its §7 rejection — do not shade the sea — is
load-bearing) and `map-tide-v5.js` as the prototype. All vendored verbatim — **do not edit them**;
the plan's §1 corrections, §4 disagreements and §5 decisions **win** wherever the spec and the code
disagree. `docs/design/tide-window/VENDORING.md` says what is in the directory and what deliberately
is not.

**The three things every session must internalise before touching code**, because they are what
the spec gets wrong about this codebase:

1. **The "want" already exists.** `LocationEntity.tideType` is a `Set<TideType>` (HIGH/MID/LOW),
   edited by `TideToggleChips` on the Locations screen, served as `location.tideType`. Coastal *is*
   the non-empty set. Build no column, no field, no editor (plan §1 #1).
2. **The tide axis here is time-based, and four surfaces stand on it.** `classifyTideState` →
   `tideAligned` → the gate, `TideVisitor`, the Plan badge and `BriefingWindowTide.state`.
   `TideSurfaceAgreementTest` forbids a second definition. Tiers come off the served `tideAligned`;
   the level is a display fact (plan §1 #2, §4 #2). Do not port `tideFitOf`, `bandOf` or `LVL`.
3. **A mismatched coastal slot is UNRATED, not low-rated** — withheld from Claude by
   `BriefingGatingPolicy`, served with `evaluationGate` and no `claudeRating`, then hidden by the
   map's default `showUnrated = false`. That is the "coast disappears" bug, mechanically. The plan
   keeps the gate this series (§5 #1) and draws the gated location dimmed *without* a star.

**Owner decisions: none block.** §5 takes the decisions the spec left open (gate untouched, two tiers,
served representative named on the strip, orthogonal tier/rating, served shortfall arrow, no fact
twice on a card). Sessions build those and do not reopen them; §6 lists what the owner may revisit.

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the pre-commit
adversarial review, which each prompt instructs explicitly — plain parallel subagents (the Agent tool)
are how every previous series ran it: ~6 prosecutor lenses over the diff (runtime behaviour, CSS and
tokens, test quality, accessibility, project conventions, what it makes harder for later phases),
then one refuter per charge defaulting to REFUTED without citable evidence, then a synthesis. Review
agents are **read-only** — one has already destroyed uncommitted work on this project with a
`git checkout --` cleanup; anything that must mutate gets its own worktree; commit before a review
that runs mutations. Paste the phase's plan section, the plan's §1/§4/§5, and the spec into every
reviewer's prompt.

**Between phases (owner):** review the PR in the browser, merge. Sessions never push and never tag.
Before starting the next session, `git rev-list --count HEAD..origin/main` says whether anything else
merged; `changelog.d/` files never conflict.

---

## T1 · Backend — the per-slot tide facts

> You are implementing **Phase T1** of `docs/engineering/tide-window-plan.md`. First: read that plan
> **in full** (§1 corrections, §2 strategy, §3 T1, §4, §5 and §9 are binding), then the spec
> `docs/design/tide-window/README.md` §1, then `gh pr list --state open` and grep the titles for
> *tide*, *strip*, *gate*, *rollup* — stop and report if anything overlaps. Then re-verify every file
> and symbol T1 names against the tree before editing — the plan was written 2026-09-17 and code moves.
> Read `docs/engineering/test-improvement-standards.md` before writing a test. Never push, never
> create or delete tags. Create `feature/tide-t1-slot-facts` off up-to-date `main`, in a worktree.
>
> Scope is §3 T1's six tasks, nothing more: lift `WindowTideRollupBuilder`'s private curve statics
> (`Point`, `Shape`, `seriesAround`, `fillInteriorGaps`, `bracket`, `counterpartHeight`, `shape`,
> `heightAt`, `directionAt`, `clockMinutesFrom`, their constants) into a package-private
> `service/TideCurveCalculator` and make the rollup delegate — **`WindowTideRollupBuilderTest` must
> pass with zero assertion edits** (§7 check 10); add five `NON_NULL` components to
> `BriefingSlot.TideInfo` (`tideLevel`, `tideDirection`, `tideHeight`, `tideShortfall`,
> `tideFitPhrase`), keeping `TideInfo.NONE` and the 9-arg legacy constructor so the fixtures that pass
> `NONE` do not move; build them in `BriefingSlotBuilder.calculateTideData`/`TideFactDeriver` from
> the extremes already fetched (no new query); derive the shortfall from state and the wanted set
> with **null** for a straddling set (§5 #4); add `TideWording.tideFitPhrase` with the two exact forms
> in T1 #4 — the miss form must **not** repeat the nearest-extreme offset clause the gate sentence
> already carries (§5 #6). Task 5 is a confirmation with a stop condition: the plan read
> `TideFactDeriver.java:96–97` as serving the *tight* alignment; re-read it, and if it now serves the
> widened one, stop and report before anything else — the chip's match tier would be looser than the
> gate. No migration: the fields ride the
> `daily_briefing_cache` JSON and a pre-field payload must deserialise to nulls (prove it).
>
> Tests per T1 #6, including the extended `TideSurfaceAgreementTest` (slot level = window level
> ± 0.01 for the representative, one set of extremes driving both — §7 check 9). Gate on the exit
> code, never on output: `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress
> -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"` —
> there is no Docker here and `checkstyle:check` first saves six minutes. Then run the adversarial
> review per CLAUDE.md § *UI Work — Review Cadence* (agents read-only; paste T1, §1 #2/#3/#5, §4 #2,
> §5 and the spec's §1 into every reviewer's prompt; the review lenses for a backend phase are
> correctness, the lift's behavioural identity, test quality, Checkstyle/Javadoc conventions, and what
> it makes harder for T2/T3). Fix survivors, re-run the gate. Nothing here is browser-visible; say so.
> Commit with a conventional message, a `changelog.d/YYYYMMDD-tide-t1-slot-facts.md` entry, and two
> plan-doc updates **in the same commit**: flip §0 to in-progress and add T1's row to the Phase log
> (recording the tight/widened answer); append any §4 entry the work forced. Do not push; report the
> branch, the commit, and the answer to task 5.

---

## T2 · Backend — the strip's window facts

> You are implementing **Phase T2** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T1 may or may not have merged — read `WindowTideRollupBuilder` as it is on
> `main` today), then the spec's §2 ("The chart" and "Header row"), then `gh pr list --state open`
> for overlap, then re-verify every symbol T2 names against the tree. Read
> `docs/engineering/test-improvement-standards.md`. Never push, never tag. Create
> `feature/tide-t2-window-facts` off up-to-date `main`, in a worktree.
>
> Scope is §3 T2's three tasks: `BriefingWindowTide` gains `sunrisePosition`, `sunsetPosition`
> (nullable — a sun that does not rise or set is a real input for a northern anchor), `extremes`
> (`record Extreme(String kind, double position, String time)`, every extreme in the representative's
> **local** day, London clock via `TideWording.clock`) and `heightAtWindow` (formatted via
> `TideWording.metres`) — all `NON_NULL`, all **additive**, so `WindowTideSparkline` and
> `windowFirstRows.tideSparkline` keep working untouched. Compute the solar positions from BOTH
> sunrise and sunset (the method fetches only the event's today, `:296–299`). If T1 has merged, use
> `TideCurveCalculator`; if not, use the private statics where they are and do **not** copy
> `heightAt` — T1's lift will move the call. The axis is the class's own 1440-unit local day even on
> a DST date; instants stay UTC (its `clockMinutesFrom` comment).
>
> Tests per T2 #3, all with exact values, every pre-existing assertion in `WindowTideRollupBuilderTest`
> unchanged, plus the `DailyBriefingResponseJsonTest` round-trip. Gate on the exit code (the full
> backend command from the T1 prompt). Adversarial review per CLAUDE.md (read-only; paste T2, §1 #4/#5,
> §4 #4/#9 and the spec's §2). Fix survivors, re-gate. Nothing browser-visible; say so. Commit with
> `changelog.d/YYYYMMDD-tide-t2-window-facts.md`, T2's Phase-log row and any §4 additions in the same
> commit. Do not push.

---

## T3 · Frontend — plumbing and the pure model

> You are implementing **Phase T3** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T1 and T2 have merged — read their rows for the final field names and
> confirm them against `BriefingSlot.TideInfo` and `BriefingWindowTide` on `main`), then the spec's
> §1 and §5, then `gh pr list --state open` for overlap, then re-verify every symbol T3 names against
> the tree. Read `docs/engineering/frontend-test-standards.md` and CLAUDE.md's **Backend-heavy**
> bullet — §1 #12 says exactly which four client derivations this increment is licensed to make.
> Never push, never tag. Create `feature/tide-t3-plumbing` off up-to-date `main`, in a worktree.
>
> Scope is §3 T3's five tasks and **no visible change**: `buildTideAlignmentIndex` indexes every
> coastal slot with a `tideState` (not only those with `tideOnTheLight != null`) and carries
> `{aligned, onTheLight, phrase, level, direction, height, shortfall, fitPhrase, gated}`, with its
> doc block rewritten to say the index now answers the *preference* question and why the two-axes
> ban does not apply to a reader asking it (§5 #3); `MapView.spotOf` copies `tideTypes`, `coastal`
> (via `utils/mapCallout.isCoastalTidalLocation`), `tideTier`, `tideShortfall`, `tideGated` onto
> `labelSpots` and the Pins pool; `mapEvents.solarRow` forwards `tide: served.tide ?? null` (null on
> the D-13 filler branch, interleave untouched); and the new pure `utils/mapTideFit.js` — `tierOf`,
> `nextAlignedRow`, `stripModel` — exactly as T3 #4 specifies: `pad(0.12)`, counts over the chip pool,
> the want tally per `TideType` across each dimmed spot's set with ties HIGH > LOW > MID, and the
> strip's next fit as the first later solar row in which any currently-dimmed spot wanting the
> dominant want is **served** aligned. **Nothing reads a height, a minute offset or a threshold to
> decide anything** — if you find yourself writing `>= 0.8`, stop.
>
> Tests per T3 #5, mutation-sensitive (a tie-break test must fail when the order flips; the `pad`
> test needs a spot just outside the raw bounds). Gate on exit codes: `npm run lint && npm test && npm
> audit --audit-level=high && npm run build`. Adversarial review per CLAUDE.md (read-only; paste T3,
> §1 #6/#7/#12, §4 #7, §5 #3/#8 and the spec's §1/§5). Fix survivors, re-gate. Browser: confirm
> against the §9 stack that `GET /api/briefing` carries the fields and that the map renders exactly as
> before (no visible change is the claim — state it as tested by the unchanged snapshot tests, seen by
> one screenshot). Commit with `changelog.d/YYYYMMDD-tide-t3-plumbing.md`, T3's Phase-log row and any
> §4 additions. Do not push.

---

## T4 · The chip — dimmed, not dropped

> You are implementing **Phase T4** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T1–T3 have merged), then the spec's §3 and its notes column ("Dimmed, not
> dropped", "What deliberately did not change"), then `gh pr list --state open` (T5 and T6 may be
> open in parallel — they touch other files; if either touches `MapLabels`/`PinsLayer`/`mapLabels.js`,
> stop and report), then re-verify every symbol T4 names against the tree. Read
> `docs/engineering/frontend-test-standards.md`. Never push, never tag. Create
> `feature/tide-t4-chip` off up-to-date `main`, in a worktree.
>
> Scope is §3 T4's seven tasks: `visibleLocations` admits any location with a served tide fact past
> the rating stage (type, drive, dark-sky, scope and `focus` still apply — §5 #9) and the counts
> footer's `+ unknown` excludes them; the label chip's `data-tide` becomes `'match' | 'miss'`, a miss
> at `opacity: .72` restored by hover and selection, the wave in muted ink, `TideWave` growing a
> `shortfall` prop that draws the bundle's 21×8 arrow (`map-tide-v5.js:157–161`) and the plain wave
> for null; the tiebreak promotes matches and **never demotes misses** (pin it with the
> equal-score-budget-of-one test); the tooltip's third line reads the served `fitPhrase`; Pins mode
> dims its dot the same way with the ramp colour untouched; the region panel's row moves onto the
> tier. **Never re-colour the star or the swatch** (the `.wf-maplab-chip-n` rule in `index.css`), never touch the heat,
> never remove a coastal chip for tide. Measure the chip ink at `.72` over the basemap before
> accepting the design's value — the `.wf-loc-row` precedent (`index.css:8361–8400`) won `.8` on
> contrast grounds; record which won in §4. Both `OBSTACLE_SELECTOR` lists are untouched by this
> phase (the strip is T6).
>
> Tests per T4 #7, including the plan's **§7 checks 1, 2, 12 and 13 as tests** (field alpha
> identical across the three wants; equal coastal chip and dot counts on an all-aligned and an
> all-miss fixture; star ink and swatch equal across tiers; gated renders with `showUnrated` false
> while inland unrated does not). Gate on exit codes: `npm run lint && npm test && npm audit
> --audit-level=high && npm run build`. Adversarial review per CLAUDE.md (read-only; paste T4, §1
> #3/#6/#7, §4 #3/#5/#11, §5 #1–#4/#9/#10 and the spec's §3). Fix survivors, re-gate, browser-verify
> per §9 on the seeded mid-water morning: the miss location is on the map without the unknown toggle,
> dimmed, arrow pointing the right way for its want, star (if rated) unchanged; the aligned one wears
> the wave at full strength; Pins mode shows the same pair; state which claims were seen versus
> tested. Commit with `changelog.d/YYYYMMDD-tide-t4-chip.md`, T4's Phase-log row (with the opacity
> measurement) and any §4 additions. Do not push.

---

## T5 · The callout and the location sheet — one block, two tiers, the jump

> You are implementing **Phase T5** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T1–T3 have merged; T4 may have), then the spec's §4 and the notes column's
> "Dimmed, not dropped", then `docs/design/map-tab-v2/INCREMENT_sheet_and_tide.md` §2 ("Extend it. Do
> not build a second one."), then `gh pr list --state open` for overlap on `MapCallout`,
> `LocationFourDaySheet` or `locationSheet.js`, then re-verify every symbol T5 names against the
> tree. Read `docs/engineering/frontend-test-standards.md`. Never push, never tag. Create
> `feature/tide-t5-block` off up-to-date `main`, in a worktree.
>
> Scope is §3 T5's four tasks: a new `components/map/TideFitBlock.jsx` rendering the match heading
> **Tide lands on the light** and the miss heading **Wrong water, not wrong light** (verbatim) over
> the served `fitPhrase`, the miss carrying either the text-button jump `Next <want> on the light ·
> <dayLabel> <sunrise|sunset> <time> ›` (calling `onSelectEv` with the **row object**, as the strip
> cells already do at `MapCallout.jsx:735`) or the italic denial `Nothing in these four days puts
> <want> on the light here.`; the callout's `.wf-callout-tide` row becomes that block, keyed on the
> tide fact rather than `onTheLight`, **with the `.wf-callout-gate` row kept** and added to the
> repaint dependencies; `LocationFourDaySheet` gains a `tideAlignmentIndex` prop from both its hosts
> and renders the block in every solar row, where the next-fit affordance **focuses the target row**
> (`focusWindowKey`) rather than moving the map under a dialog. The rule that decides the review is
> §5 #6: **no fact prints twice on one card** — the gate sentence owns the offset clause, the block
> owns the level, height and "wants"; §7 check 11 asserts the offset clause appears exactly once in a
> gated miss card's text. Tokens only (§1 #9): the miss tint through `--color-plex-text`-derived
> tokens, never the bundle's rgba literals.
>
> Tests per T5 #4. Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high &&
> npm run build`. Adversarial review per CLAUDE.md (read-only; paste T5, §1 #10/#11, §4 #3/#7/#8, §5
> #5/#6 and the spec's §4). Fix survivors, re-gate, browser-verify per §9: select the miss location —
> the block, the gate row and the labelled region gloss all present with no repeated clause; press
> the jump and confirm the window control moves to the named window and the callout re-anchors; open
> `Four days here ›` and confirm one block per solar row and the focus move. Commit with
> `changelog.d/YYYYMMDD-tide-t5-block.md`, T5's Phase-log row and any §4 additions. Do not push.

---

## T6 · The strip

> You are implementing **Phase T6** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T1–T3 have merged; T4/T5 may have), then the spec's §2, §5, §8 and §10
> and the notes column ("Why not shade the sea" is the rejection you are honouring), then
> `map-tide-v5.js:1207–1293` and `Map Tide Window.html:552–584` for the geometry, then `gh pr list
> --state open` for overlap on `MapView`'s chrome or `index.css`'s map section, then re-verify every
> symbol T6 names against the tree. Read `docs/engineering/frontend-test-standards.md`. Never push,
> never tag. Create `feature/tide-t6-strip` off up-to-date `main`, in a worktree.
>
> Scope is §3 T6's eight tasks: `components/map/MapTideStrip.jsx` mounted as the **last child of
> `.wf-map-chrome-bl`** (so the legend chip clears it by flex order and the obstacle seed is inherited
> — §4 #6, §1 #8), rendering `null` unless `stripModel.visible` (§5 #7 — solar row ∧ served window
> tide ∧ a coastal spot in the padded viewport; no toggle, no mode); header, chart, footer and
> collapsed row per T6 #2–#5 with the spec's copy verbatim and the codebase's vocabulary where it has
> one (§4 #8 — `rangeAnomaly`, `STATE_WORD`/`DIRECTION_WORD`) plus a `measured at <locationName>`
> clause (§4 #4); the chart's curve from the served 49-point `curve` with
> `vector-effect="non-scaling-stroke"` (**mandatory**), night rects from the served solar positions,
> the light dot at `(windowPosition, TY(windowLevel))`, the whole svg `aria-hidden`; `--tsh` published
> from the strip's real height by `ResizeObserver` onto the `.wf-map-tab` root with a
> `wf-tide-strip-on` class lifting **only** the bottom-centre count footer (`+ 16px`); collapse held in
> `MapView` state, surviving window changes (§4 #12). Two things that will bite if skipped, in the
> designer's own words: the non-scaling stroke, and publishing the real height rather than a guess —
> open and collapsed are ~3× apart. Tokens only: `--color-tide`, `--color-badge-tide` for the state
> word/dot/hit label (§1 #9 — not `#8FC0C7`), `--color-verdict-marginal`, `--color-plex-text-muted`
> (already `.66` under `.wf-body--map` — do not hard-code `.42`), `--font-mono`.
>
> Tests per T6 #8 and the plan's **§7 checks 3, 4, 5 and 6 as tests** (visibility both ways per
> condition and the footer back at `8px`; `--tsh` clearance at stubbed 166/34; the dot on the curve —
> `|sample(curve, windowPosition) − windowLevel| ≤ 0.02`; every next-fit row served aligned, `beyond`
> otherwise). `mapChromeZLadderCascade.test.jsx` gains the strip. Gate on exit codes: `npm run lint
> && npm test && npm audit --audit-level=high && npm run build`. Adversarial review per CLAUDE.md
> (read-only; paste T6, §1 #4/#8/#9/#12, §4 #4/#6/#8/#9/#10, §5 #5/#7/#8 and the spec's §2/§8/§10).
> Fix survivors, re-gate, browser-verify per §9 in headless Chromium: **§7 check 7's contrast table**
> (every text node, text alpha composited, over the darkest sampled basemap tile) and check 4's
> clearance in both states at 1280×800 go in the Phase log as numbers; pan to the Lake District and
> confirm the strip leaves and the footer returns to `bottom: 8px`; pick an astro row and confirm the
> same. Commit with `changelog.d/YYYYMMDD-tide-t6-strip.md`, T6's Phase-log row and any §4 additions.
> Do not push.

---

## T7 · Phone

> You are implementing **Phase T7** of `docs/engineering/tide-window-plan.md`. Read the plan **in
> full** and its Phase log (T6 has merged — read its measured open/collapsed heights), then the spec's
> §6, then `index.css:3567–3624` (the lifted stack) and `test/mapPhoneChromeCascade.test.jsx` ("THE
> SWEEP") — the arithmetic you are extending, not the literals. `gh pr list --state open` for overlap;
> re-verify every symbol. Never push, never tag. Create `feature/tide-t7-phone` off up-to-date
> `main`, in a worktree.
>
> Scope is §3 T7's three tasks: under `(max-width: 639px)` the strip goes full width at the count
> footer's row of the stack and the footer is `display: none` while `wf-tide-strip-on` (the spec's
> own call: "the tide sentence is the more useful line at that width"); the scored legend and
> `.wf-map-chrome-bl`'s other children lift by `var(--tsh)` rather than an assumed constant; the bar
> (`.wf-map-chrome-tr`) is never covered and the mobile callout still clears the strip. Extend the
> cascade test with the strip's row in both states, each pairwise clearance ≥ 8px asserted on the
> arithmetic, and the footer hidden only while the strip is on.
>
> Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
> Adversarial review per CLAUDE.md (read-only; paste T7, §1 #8, §4 #6 and the spec's §6). Fix
> survivors, re-gate, browser-verify **§7 check 8** at 390×844 in headless Chromium — strip bottom
> versus bar top, legend bottom versus strip top, callout bottom versus strip top, footer hidden — and
> put the four numbers in the Phase log, open and collapsed. Commit with
> `changelog.d/YYYYMMDD-tide-t7-phone.md`, T7's Phase-log row and any §4 additions. Do not push.

---

## T8 · Sweep and docs

> You are implementing **Phase T8** of `docs/engineering/tide-window-plan.md`. Read the plan in full
> and its Phase log (every phase's outcome), then re-verify every cross-reference against
> `origin/main`'s tail. Never push, never tag. Create `docs/tide-t8-sweep` off up-to-date `main`.
>
> Scope is §3 T8: reconcile §4 against what shipped (renumber, then grep your own references); flip
> §0 to complete; the CLAUDE.md edits per T8 #2 — the Map tab (v2) bullet, the Locations bullet's
> `TideType` line, the Backend-heavy bullet's Map-tab class (**the four members §1 #12 names and
> nothing else**), and one sentence on the "Two tide axes" bullet; answer the spec's five OPENs in §6
> with what shipped; record the §7 measurements; a line in `VENDORING.md` naming the final §4 count.
> Docs-only, but the review cadence still applies — the map series' docs sweep drew 21 findings
> including two blockers for over-claiming against the shipped code. Adversarial review (read-only;
> paste the whole plan), fix survivors, commit with a `docs:` message and
> `changelog.d/YYYYMMDD-tide-t8-sweep.md`. Do not push.
