# Heat field — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session** (Opus), in order. Each phase lands as its
own reviewed commit before the next session starts — the plan's **Status line and §6 phase table
are the source of truth between sessions**, updated in the same commit as each phase, so a new
session never needs this chat's history or the previous session's context. If a session dies
mid-phase, start a new one with the same prompt: step one of every prompt is reading the current
state from the repo.

The plan: `docs/engineering/heat-field-plan.md`. The design bundle: `docs/design/heat-map/`
(vendored verbatim — do not edit it; the plan's §2 wins where the bundle's README is stale).

**Multi-agent note.** These sessions do not need Ultracode or any special mode. The one
multi-agent step is the pre-commit adversarial review, which each prompt instructs explicitly —
plain parallel subagents (the Agent tool) are how every window-first phase ran it. Review agents
are **read-only**; anything that must mutate gets its own worktree; commit or stash before a
review that runs mutations.

**Between phases (owner):** review the phase in the browser, merge, push. Sessions never push.
Before starting the next session, expect a `CHANGELOG.md` conflict if anything else merged —
`git rev-list --count HEAD..origin/main` tells you, not which files changed.

---

## P0 · Kernel + foundations

> You are implementing **Phase P0** of `docs/engineering/heat-field-plan.md`. Start by running
> the plan's **§10 first-hour checklist in full** (reading list, citation re-verification, the
> never-push/never-tag rule). Then create `feature/heat-p0-kernel` off up-to-date `main`.
>
> Scope is §4.1 plus the P0 row of §6, nothing more — no UI this phase: add `d3-geo` +
> `topojson-client`; write `frontend/scripts/generate-uk-land.mjs` and commit its output
> `frontend/src/assets/uk-land-50m.json` (provenance + licence in the script header; no CDN
> reference may survive anywhere); port `docs/design/heat-map/heat-field.js` →
> `frontend/src/utils/heatField.js` with **only** the deviations §4.1 permits — the algorithm is
> load-bearing, resist all other "improvements"; create `utils/scoreRamp.js`; write the kernel
> tests per §7.1 (note: the canvas stub needs the d3 path methods listed there; the
> bucket≡brute-force comparison is ±1 per channel; the aspect clamps are NOT kernel tests).
>
> Gate on exit codes, never output: `npm run lint && npm test && npm audit --audit-level=high
> && npm run build`. Then run the adversarial review per CLAUDE.md § *UI Work — Review Cadence*
> (~6 prosecutor lenses over the diff, one refuter per charge defaulting to REFUTED, synthesis;
> agents read-only), fix survivors, re-run the gate. Commit with a conventional message, a
> `CHANGELOG.md` entry, and the plan's Status line + P0 row updated **in the same commit**. Do
> not push.

---

## P1 · Data plumbing

> You are implementing **Phase P1** of `docs/engineering/heat-field-plan.md`. Run the plan's
> §10 checklist first, confirm P0 is on `main` (the plan's Status line says), then create
> `feature/heat-p1-data`.
>
> Scope is §4.2 plus the P1 row of §6. Frontend: the scores fetch is **already eager** (§2.10) —
> retain the raw rows including `locationId`, pass `locations` into the provider via `App.jsx`,
> build the memoised `heatSpots` join (locationId-first, name-fallback; null score for a window
> = that spot contributes nothing to that window) and `utils/planningArea.js` (GLANCE 180;
> no home / no drive times → whole roster, never a synthesised smaller area). Backend:
> `BriefingRegion.bestRating` — nullable, `NON_NULL`, non-canopy max attached in
> `enrichWithCachedScores`, same discipline as `BriefingWindow.bestRating`; no migration.
>
> Backend gate is the Maven ladder from CLAUDE.md § *Speeding Up the Dev Build Cycle*, exit-code
> gated (JaCoCo is 80% per class — cover the new record's null branches with real assertions).
> Frontend gate, adversarial review, fix, re-verify as in P0. Measure the scores payload size
> once and record it in the plan. Commit (CHANGELOG + plan Status/P1 row in the same commit); do
> not push.

---

## P2 · The strip

> You are implementing **Phase P2** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> confirm P0+P1 are on `main`, create `feature/heat-p2-strip`.
>
> **D1 is confirmed by the owner (2026-08-18): the strip replaces the day rail, including its
> above-the-tabs position.** Read §1.1 before touching anything — it is the recorded reversal of
> a pinned decision. When you retire the rail pin in `WindowFirstShellTabs.test.jsx` (~:514),
> the commit message must carry §1.1's rationale (each tab now has its own date context); retire
> `WindowFirstDayRail.test.jsx` and `windowFirstRail.test.js` with the component rather than
> leaving them guarding unmounted code. The rail *footer* (home / edit reach / age) stays.
>
> Scope is §4.3 plus the P2 row of §6: `WindowFirstHeatStrip` under the lens, above
> `WindowFirstPromotedStrip` (two different strips — do not conflate); the Order control on the
> lens bar (`photocast.planOrder`). **Read the P0 row first** — it records the kernel seams P2
> inherits (`drawGeo`'s three null reasons — the rAF retry must use `land()` to tell "not loaded"
> from "zero measure"; the null-2d-context guard belongs to the mounting component, i.e. you).
> **P2 also owns the one-line `vite.config.js` change**: a `manualChunks` rule for `d3-geo`
> placed **before** the existing `id.includes('d3-')` catch (after it, the rule never fires) —
> without it your first `drawGeo` import makes the 375.75 KB / 107.46 KB-gzip recharts chunk,
> today ADMIN-only behind a lazy boundary, a render-blocking first-paint fetch for every user.
> Measure the chunk layout before and after and record it in the plan row. Traps the review will
> check: no database counts anywhere on the strip (§2.6); the `BEST BET` flag is a passive span;
> the strip is never reordered by Order·Best; verdict words come from the payload only (D3 —
> never port the bundle's thresholds); new tokens go in `@theme static`; 3×2 at 639px;
> hidden-pane first paint uses the rAF-retry pattern (§5.6).
>
> Gate → adversarial review → fix → re-verify, then **browser verification** per §7.3 (regions
> before locations; restart the backend after the SQL insert or the briefing will not see it) —
> screenshots at desktop and 390px. Commit (CHANGELOG + plan Status/P2 row); do not push.

---

## P3 · The open window row

> You are implementing **Phase P3** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> confirm P0–P2 are on `main`, create `feature/heat-p3-row`.
>
> Scope is §4.4 plus the P3 row of §6: the full-width field map (DOM centroid labels — mind the
> `.mapbox` `line-height: 0` trap and the label plate's explicit `line-height: 1.35`),
> click-to-region (26% of frame width; same-region click or empty space clears), the region rail
> (`All N regions` peer cell first; nothing-in-reach cells show distance instead), the region
> band (narrative = `region.summary` verbatim, **with the null-summary fallback line** §4.4
> specifies), focus repaint, region filter composed into the card's spot pool, footer naming all
> three filters.
>
> Rail and band star figures come from the served `BriefingRegion.bestRating` and `meanRating`
> **only** — never a client-side max (the canopy rule lives server-side). The existing tide row
> and spot strip must be untouched: their tests passing unedited is the proof. Gate →
> adversarial review → fix → re-verify → browser verification (open row, select a region, clear
> it; desktop + 390px) → commit (CHANGELOG + plan row); do not push.

---

## P4 · Map tab heat

> You are implementing **Phase P4** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> confirm P0+P1 are on `main` (P2/P3 are not prerequisites but note in the plan if they landed),
> create `feature/heat-p4-map`.
>
> Scope is §4.5 + D8 + the P4 row of §6. `MapView` gains an opt-in `heat` prop, default
> undefined — **v1's Map tab and the overlay mount must be byte-identical without it, pinned by
> its own test** (the `serverCellRating` shape). `MapHeatLayer`: custom pane at zIndex 350,
> rAF-throttled `render()` on move/zoom, un-throttled `renderNow()` on moveend/zoomend —
> throttle, never debounce. In heat view the marker panes fade with zoom + the pointer-events
> gate, and v2 marker colours move to `scoreRamp` (D2/D8); medallion view is today's behaviour
> untouched. Toolbar per §4.5: area toggle uses `{animate: false}` and is **absent when no home
> is set**; the dark-sky toggle keeps the real Bortle rule (`<= 4`, D7 — the bundle's scale is
> inverted).
>
> Gate → adversarial review → fix → re-verify → browser verification: seed with the script's
> `--dense` mode and pan ~200 spots without a stall; heat/medallion toggle, area toggle,
> dark-sky repaint screenshots. Commit (CHANGELOG + plan row); do not push.

---

## P5 · Leave-by

> You are implementing **Phase P5** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> create `feature/heat-p5-leaveby`.
>
> Scope is §4.6 plus the P5 row of §6: `utils/leaveBy.js` (event time − drive − `SETUP = 20`,
> London-formatted, **null drive → no line, never a guess**), rendered on spot card, sheet and
> peek; plus the v2 spot-badge swap onto `scoreRamp` (D2 — `windowFirstSpots.js` is v2-only).
> The midnight-wrap test needs a fixture that actually wraps — §4.6 gives one; a 2h30 drive off
> an 05:42 sunrise does not. Zone-separation coverage goes in an abroad-zone test file per the
> test standards. Gate → adversarial review → fix → re-verify → commit (CHANGELOG + plan row);
> do not push.

---

## P6 · Movement (deltas)

> You are implementing **Phase P6** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> create `feature/heat-p6-movement`.
>
> Scope is §4.7 plus the P6 row of §6. Backend: the `briefing_region_snapshot` migration — ⚠️
> read the next migration number from `ls backend/src/main/resources/db/migration/ | sort -V`
> **on main**, never from a doc; writer at the end of `refreshBriefing` with age-pruning;
> serve-time `BriefingRegion.meanRatingDelta` + `previousGeneratedAt`, both nullable `NON_NULL`
> (the `confidence` precedent — legacy caches deserialize null), with §4.7's javadoc caveat
> about post-build drift. Both sides of the delta use the `votingSlots` mean. Frontend: strip
> movement chips and the change line — **null renders nothing; a measured 0.0 renders `—`**;
> the change line's age is `generatedAt`, worded "since the last forecast run".
>
> Maven ladder + frontend gate → adversarial review → fix → re-verify → commit (CHANGELOG +
> plan row); do not push.

---

## P7 · Origin + search

> You are implementing **Phase P7** of `docs/engineering/heat-field-plan.md`. Run §10 first,
> confirm P2+P3 are on `main`, create `feature/heat-p7-origin`. This is the largest phase —
> re-read §4.8 in full before starting, and check §9 in the plan for any answers the owner has
> recorded since (lead narrative is omitted unless §9.10 says otherwise; the search resting
> list is windows-only per §9.11).
>
> Backend: region base columns (nullable — a baseless region cannot be an origin),
> `region_drive_time` via the ORS matrix (hoist the `Semaphore(2)` out of
> `DriveDurationService` — share it, do not duplicate it), refresh job, admin UI edit, the
> shared `GET /api/regions/drive-times` endpoint (ETag-whitelisted — it is user-independent;
> the whitelist is exact-match). Migration number from the tree on main. Frontend: origin chip,
> the search dialog (`/`, three groups, keyboard), `setOrigin` re-pointing pool + frame + lens
> label + drive figures (shared region matrix, never the per-user reach map — keep the privacy
> seam of §3), the two clash states, `⌂` restore (home + 150 tier), and the beyond line's
> search-prefill link deferred from P2.
>
> Gate both sides → adversarial review → fix → re-verify → browser verification (the headline
> state: origin moved to a region re-frames all six thumbnails) → commit (CHANGELOG + plan
> row); do not push.

---

## P8 · Four-day location sheet (optional — confirm with the owner first)

> You are implementing **Phase P8** of `docs/engineering/heat-field-plan.md` — confirm with the
> owner that it is wanted and which entry points (§9.9) before writing code. Run §10 first,
> create `feature/heat-p8-sheet`.
>
> Scope is D10 plus the P8 row of §6: the per-location six-window timeline — ratings and "why"
> prose from the scores rows (the field is **`summary`**; `claudeSummary` is the briefing
> payload's name for the same prose), leave-by per row (P5's util). Preserve the peek and
> map-open behaviours wherever the owner kept them. Gate → adversarial review → fix →
> re-verify → browser verification → commit (CHANGELOG + plan row); do not push.
