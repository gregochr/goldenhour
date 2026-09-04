# Doors from Plan to Map — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order. Each phase lands as its own
reviewed commit and PR before the next session starts — the plan's **§0 Status block and Phase log
are the source of truth between sessions**, updated in the same commit as each phase, so a new
session never needs this chat's history. If a session dies mid-phase, start a new one with the same
prompt: step one of every prompt is reading the current state from the repo.

The plan: `docs/engineering/plan-to-map-doors-plan.md`. The spec: `docs/design/map-tab-v2/
INCREMENT_plan_to_map_doors.md` and `CLAUDE_CODE_PROMPT_doors.md` (vendored verbatim — do not edit
them; the plan's §1 corrections, §4 ledger and §5 decisions **win** wherever the increment and the code
disagree, and the increment's two "ask before deciding" questions plus the plan's Q3 are resolved by
the owner in §6, not by a session).

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the pre-commit
adversarial review, which each prompt instructs explicitly — plain parallel subagents (the Agent tool)
are how every previous series ran it: ~6 prosecutor lenses over the diff (runtime behaviour, CSS and
tokens, test quality, accessibility, project conventions, what it makes harder later), then one
refuter per charge defaulting to REFUTED without citable evidence, then a synthesis. Review agents are
**read-only**; anything that must mutate gets its own worktree; commit before a review that runs
mutations. Paste the phase's plan section, the plan's §1/§4/§5, and the increment into every
reviewer's prompt.

**Between phases (owner):** review the PR in the browser, merge. Sessions never push and never tag.
Before starting the next session, `git rev-list --count HEAD..origin/main` says whether anything else
merged; `changelog.d/` files never conflict.

---

## D1 · One drive accessor, home geography only at home

> You are implementing **Phase D1** of `docs/engineering/plan-to-map-doors-plan.md`. First: read that
> plan **in full** (§1 corrections, §2 strategy, §3 D1, §4, §5 and §9 are binding), then the increment
> `docs/design/map-tab-v2/INCREMENT_plan_to_map_doors.md`, then `gh pr list --state open` and grep the
> titles for *door*, *origin*, *drive* — stop and report if anything overlaps. Then re-verify every file
> and symbol D1 names against the tree before editing — the plan was written 2026-09-04 and code moves.
> Never push, never create or delete tags. Create `feature/doors-d1-drive-accessor` off up-to-date
> `main`, in a worktree.
>
> Scope is §3 D1's five tasks, nothing more: make `MapView.driveMinutesFor` the tab's only drive
> reader (close the four leaks §1 #1 tabulates — the jump list's second precedence expression,
> `mapReachMeasured`'s home-only test, the minutes/miles source split, and *record* `beyondRegionNames`
> as the one deliberate home-only read); verify in the backend that `/drive-times` and `/reach` read the
> same rows before unifying the tab's home source onto `reachById`, and keep the **overlay** on
> `userDriveTimes` gated on `overlayMode` (it is mounted outside the provider and is frozen); add an
> `origin` prop to `MapView` and gate the HOME label, the reach rings, the ring labels and the legend's
> rings toggle on `origin == null`; leave the ⌂ control present while away and doc its naming (O-D5).
> The increment's rule you are honouring: origin changes every number, so a marker or a ring drawn
> round home beside drive times from Keswick is two journeys on one screen. Do not invent a coordinate
> for an away base. Do not add anything to the handover — that is D2.
>
> Tests per D1 task 3, including the negative overlay test and the away/home pairs as
> present-and-absent assertions. Gate on exit codes, never output: `npm run lint && npm test && npm
> audit --audit-level=high && npm run build`. Then run the adversarial review per CLAUDE.md § *UI Work
> — Review Cadence* (agents read-only; paste D1, §1, §4, §5 and the increment into every reviewer's
> prompt). Fix survivors, re-run the gate, then browser-verify per §9 with an **away origin set** (a
> region with a base and a filled matrix — trigger the drive-time refresh job from the Scheduler
> sub-tab) and state which claims were seen versus tested. Commit with a conventional message, a
> `changelog.d/YYYYMMDD-doors-d1-drive-accessor.md` entry, and two plan-doc updates **in the same
> commit**: flip §0 to in-progress and add D1's row to the Phase log; append any §4 entry the work
> forced. Do not push; report the branch, the commit, and what you saw versus tested.

---

## D2 · The handover payload, the breadcrumb, `clear`, the return trip

> You are implementing **Phase D2** of `docs/engineering/plan-to-map-doors-plan.md`. First: read the
> plan **in full** and its Phase log (D1 has merged; read D1's row and any §4 entries it added), then
> the increment, then `gh pr list --state open` for overlap, then re-verify every file and symbol D2
> names against the tree. Never push, never tag. Create `feature/doors-d2-handover-breadcrumb` off
> up-to-date `main`, in a worktree. Check §6 first: if the owner has recorded an answer to **Q2** (the
> return trip) other than the default, build that; otherwise the default (land on the plan itself,
> reopen nothing, carry no window key).
>
> Scope is §3 D2's seven tasks: `App.openMapTabFromPlan` on the EXISTING `mapTabHandoff` +
> `tabRequest` nonce channel (read `App.jsx:307–318`'s ⚠️ before touching it — the pane is never
> unmounted); the shell's single `openMapTab(door)` that closes the sheet and the popup FIRST and reads
> the lens values at the tap; the pane forwarding a `source:'plan'` handoff as ONE `planHandoff` prop
> and the hatch's handoff exactly as today; ONE nonce-keyed effect in `MapView` applying event, floor,
> tier, region (via `jumpToRegion`/`resetToMyArea`, never `FitBoundsController`) and location;
> `components/map/MapBreadcrumb.jsx` mounted ABOVE the map frame, outside it (§4 #3), with the derived
> carrying clause (§5 rule 3 — an axis shows only while the map still holds the carried value);
> `clear` mirroring the prototype's `crumbclr` exactly (rating, reach, scope, origin — not subjects, not
> dark-sky) through the context's `setOrigin(null)`; `← Plan` → a `tabRequest` for `plan`. The two
> rules that decide the review: **the payload does not carry the origin** (it is shared state — §2, §4
> #1) and **nothing is sent that nothing reads**. Rating and reach carry the Plan's actual lens values
> (Any / day-derived — §1 #6, §5 rule 2), not the increment's example. ⚠️ The map has NO Any rating
> state and must not grow one: a Plan `minRating` of null lands as `minStars = 1` with `showUnrated`
> untouched, which is exactly what the Plan's Any admits (§4 #11, a Codex finding on the plan PR).
>
> Tests per D2 task 6 — the nonce replay guard, the hidden-pane case, `null → 1` (never null) and `null → 0`, the
> filtered-out location still getting its callout, every crumb clause present AND absent, `clear`'s
> calls in order, and the `mapEvents` interleave check (§7 #1). Gate on exit codes: `npm run lint &&
> npm test && npm audit --audit-level=high && npm run build`. Adversarial review per CLAUDE.md (read-only
> agents; paste D2, §1, §2, §4, §5 and the increment). Fix survivors, re-gate, browser-verify per §9
> (frame height with and without the crumb at 1280×800 and 390×844, no horizontal scroll, crumb ink
> ≥ 4.5:1) driving the payload through a temporary test-only caller you do NOT commit. Commit with a
> conventional message, `changelog.d/YYYYMMDD-doors-d2-handover-breadcrumb.md`, D2's Phase-log row and
> any §4 additions in the same commit. Do not push.

---

## D3 · Door 2 — re-point the location sheet's `◍ Show on map →`

> You are implementing **Phase D3** of `docs/engineering/plan-to-map-doors-plan.md`. Read the plan in
> full and its Phase log (D1 and D2 have merged), the increment, `gh pr list --state open`, then
> re-verify every symbol against the tree. Never push, never tag. Create `feature/doors-d3-door2-sheet`
> off up-to-date `main`, in a worktree. Check §6 **Q3** first: this phase re-points the button from the
> frozen overlay to the Map tab; if the owner has recorded "no", stop and report.
>
> Scope is §3 D3's four tasks and nothing else: the shell's `onShowOnMap` wiring for
> `LocationFourDaySheet` becomes D2's `openMapTab({ date, targetType, locationName, region: null })`;
> the button's copy, its ONE-text-node accname (`LocationFourDaySheet.jsx:511–518`'s ⚠️) and its
> withholding rule are untouched, except that with no map door the sheet shows its "map opens once the
> forecast loads" sentence rather than a dead button; every OTHER `onShowOnMap` producer stays on the
> overlay (O-6 is not this phase). Move `WindowFirstShellSheet.test.jsx`'s and
> `locationSheetShell.test.jsx`'s expectations to the new callback with the full door shape and add the
> close-then-move ordering assertion.
>
> Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
> Adversarial review per CLAUDE.md (read-only; paste D3, §1 #3, §4 #7, §6 Q3 and the increment). Fix
> survivors, re-gate, browser-verify §7 checks 1–3 for this door: from a sheet opened over the popup on
> a window AFTER the first night, the map opens on that window with the location's callout up and the
> crumb naming the lens facts; from an away origin the callout's Drive reads from the base. Commit
> with `changelog.d/YYYYMMDD-doors-d3-door2-sheet.md`, the Phase-log row and any §4 additions. Do not
> push.

---

## D4 · Door 1 — `◍ Open in map →` on the popup's field, seeded as an obstacle

> You are implementing **Phase D4** of `docs/engineering/plan-to-map-doors-plan.md`. Read the plan in
> full and its Phase log (D1 and D2 have merged; D3 may have), the increment — **especially "The
> defect worth knowing about"** — `gh pr list --state open`, then re-verify every symbol against the
> tree. Never push, never tag. Create `feature/doors-d4-door1-field` off up-to-date `main`, in a
> worktree.
>
> Scope is §3 D4's five tasks: the button inside `.wf-mapbox` (rendered only when `onOpenInMap` is a
> function; ONE text node; `line-height` set explicitly because the box is `line-height: 0`); **the
> seed, measured from the live element in the placement effect, flagged `target: true`** because it
> is a control and the 24px separation must hold against every chip — this is where the designer broke
> it, and moving the button would only have changed which label it covered; `WindowSheetDialog`
> passes the prop through; the shell carries `field.selectedRegion` (null under an away origin) with the
> window. The field's click gesture and the bottom-left hint are untouched: two meanings on one tap is
> how you lose the one people learned. Do NOT turn `HINT_BOX` into a measurement (its doc says why).
>
> Tests per D4 task 4 — no prop → no button AND no change to placement; a stubbed top-right rect with
> a chip anchored under it flips or drops and never overlaps (template `WindowRowFieldMap.test.jsx:
> 1311–1327`); the 24px `target` case; the exact accname. Then **the increment's check 5, scripted**:
> a Playwright spec `src/test/e2e/door1-obstacles.spec.js` that opens all six matrix cards and samples
> `document.elementFromPoint` every 2px across every chip's width at its vertical centre, at 1280×800
> and 390×844, against a seeded local stack (§9 — ratings on ≥ 6 windows). Put the six-window table in
> the Phase log; the defect appeared in 4 of 6, so one window proves nothing. Gate on exit codes:
> `npm run lint && npm test && npm audit --audit-level=high && npm run build`. Adversarial review per
> CLAUDE.md (read-only; paste D4, §1 #8/#9, §4 and the increment). Fix survivors, re-gate, commit with
> `changelog.d/YYYYMMDD-doors-d4-door1-field.md`, the Phase-log row and any §4 additions. Do not push.

---

## D5 · Door 3 — the thumbnail glyph, built to be judged

> You are implementing **Phase D5** of `docs/engineering/plan-to-map-doors-plan.md`. Read the plan in
> full and its Phase log (D1 and D2 have merged), the increment's Door 3 row and its open question,
> `gh pr list --state open`, then re-verify every symbol against the tree. Never push, never tag.
> Create `feature/doors-d5-door3-glyph` off up-to-date `main`, in a worktree. This phase exists to be
> **judged**: build it exactly as specified so the owner can keep or drop it (§6 Q1); do not round the
> 34px/40px targets up, and do not hide it behind a flag.
>
> Scope is §3 D5's five tasks: a **sibling grid item** in the card's cell (§1 #10 — the card is a
> `<button>`, so a nested control is invalid HTML), `data-testid="wf-heat-tomap"`, glyph hidden and a
> real sr-only name, placed on the thumbnail's top-right by `justify-self`/`align-self` and a margin;
> hover-revealed on desktop through `button.wf-hc:hover + .wf-hc-tomap` (lifted with the card),
> always visible at 40px on the phone; none on away cells, none without the door;
> `WindowFirstHeatStrip` gains `onOpenInMap(cardKey)` and the shell carries the window with
> `region: null`. No `stopPropagation` is needed as a sibling, but the "does not also open the window
> sheet" test stays. Tests per D5 task 4, including the CSS-slicer pin on the reveal rules.
>
> Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
> Adversarial review per CLAUDE.md (read-only; paste D5, §1 #10, §4 #8, §6 Q1 and the increment).
> Fix survivors, re-gate, then produce the judging material: screenshots at 1280 (rest, hover, focus)
> and 390, and one honest paragraph on discoverability and hit size. Commit with
> `changelog.d/YYYYMMDD-doors-d5-door3-glyph.md`, the Phase-log row and any §4 additions. Do not push.
> Report that the PR is a keep-or-drop decision, not a merge-by-default.

---

## D6 · Sweep and docs

> You are implementing **Phase D6** of `docs/engineering/plan-to-map-doors-plan.md`. Read the plan in
> full and its Phase log (every phase's outcome, including whether D5 merged or was closed), then
> re-verify every cross-reference against `origin/main`'s tail. Never push, never tag. Create
> `docs/doors-d6-sweep` off up-to-date `main`.
>
> Scope is §3 D6: reconcile §4 against what shipped (renumber, then grep your own references); flip §0
> to complete; the CLAUDE.md Map-tab and Plan-tab bullets per D6 task 2; `map-tab-v2-plan.md` §6's O-6
> and O-18 lines; the Playwright spec's disposition and a README line on running it. Docs-only, but
> the review cadence still applies — the map series' docs sweep drew 21 findings including two
> blockers for over-claiming against the shipped code. Adversarial review (read-only; paste the whole
> plan), fix survivors, commit with a `docs:` message and `changelog.d/YYYYMMDD-doors-d6-sweep.md`.
> Do not push.
