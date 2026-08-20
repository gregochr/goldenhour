# Plan matrix — the day/window matrix replaces the strip-plus-list (design v3)

**Status: IN PROGRESS — M1 landed, M2 next.** Written 2026-08-20 against the v3 design bundle at
`docs/design/plan-matrix/` (README.md there is the pixel-level spec; this document is the port
plan). Base: main at v2.18.14, heat-field series P0–P8 complete and merged, window-first flag
still defaulting to v1. The plan itself was adversarially reviewed before landing (six lenses,
sixteen charges, per-charge refutation): fourteen survived and their corrections are folded in
— notably A21 (no served window-level prose exists; the bundle's `Window.lead` collides with
the codebase's boolean `card.lead`), A8's two join rules (NIGHT topics bucket onto the *next*
morning's card; aurora/NLC serve populated `regions` lists that are NOT eligibility rosters
and must be exempted from the scope filter by type), and M3's sticky-chrome tasks. The six §8 owner decisions were all resolved by the owner on
2026-08-20 (recommendations adopted) — no phase is decision-gated.

### Phase log

Later phases append a row. `notes` is what a reader of a *later* phase needs and would not get
from the diff. The `commit` cell names the merge commit once the phase's PR lands — a phase
cannot name its own hash, since writing it in changes it.

| phase | branch | commit | date | notes |
|---|---|---|---|---|
| M1 — the matrix | `feature/plan-matrix-m1-matrix` | tip of that branch (merge hash lands with the PR) | 2026-08-20 | Six lenses + a per-charge refutation pass; the survivors are folded in and the CHANGELOG entry names them. **Five things a later phase should not rediscover.** (1) The card face is `overflow: visible` for the pick legend, so nothing inside it clips itself any more — the canvas well and the topic labels each carry their own clip, and anything new with a `nowrap` or an inline width needs one too. (2) `.wf-hc-pls > span` is a `(0,1,1)` selector: an override on a value-grid child must be written `.wf-hc-pls > .x`, or it silently loses. (3) `button.wf-hc:hover` is `(0,2,1)` and out-specifies every `.wf-hc.x` state rule — the pick and open-state borders each carry their own `:hover` arm, and a new state rule must too. jsdom cannot see any of this: it computes a selector list's specificity as the max over the list, so a sliced-cascade test here answers the *opposite* and passes. (4) The topic scope filter fails **open**, so `windowFirstTopics.js`'s two sets must together cover `TopicRarity.RANK_BY_TYPE`'s sixteen types — the test holds its own literal copy, and both have to move together. (5) Measured redraw: a full matrix repaint is 60–210 ms (median ~97 ms) over six canvases at DPR 2, against zero long tasks with the matrix hidden. The mitigation is a debounce on `useHeatCanvas`'s observer nonce, which the Map pane shares — M5 task 5. **Deviations from the bundle, both for measured contrast:** the best-reach rating is a filled `spotBadgeStyle` chip rather than bare ramp-coloured text, and the histogram sits in a `--color-heat-sea` well. **One deviation for SC 1.4.1:** today's column says the word "Today" in the weekday slot, because the bundle's reason for omitting it (the cards carry the day in their own labels) stopped being true when the card face changed. Charge refuted and worth not re-raising: the A8 scope filter DOES run at home — the bundle's own `HOME` origin is "every region in your area" at the same 180-minute rule — and `.wf-shell`'s `max-width: 1080px` bounds the widest a card can ever get, so there is no runaway-card case at 2560px. |

---

**Scope: v2 / window-first Plan arm only, behind `usePlanLayout` (default stays `v1`).** v1 is
the pilot's frozen comparison control and nothing here touches it except where a shared
component gains a caller opt-in. After this series settles, the owner flips the flag and the
v1 deletion follows — this series is what the flip now waits for.

**How to use this document.** Each phase runs the full UI review cadence (CLAUDE.md → *UI Work
— Review Cadence*): build → tests → adversarial review of the diff → fix survivors → re-verify
→ commit. Paste the relevant phase section AND §3–§5 of this document into every review
agent's prompt — an untracked plan is invisible to agents in other worktrees, and a compliance
lens with no spec returns zero findings. Review agents are read-only; anything that mutates
gets its own worktree. Re-verify every `file:line` and symbol named here against the tree
before editing — this plan was written from a survey on 2026-08-20 and code moves.

---

## 1. What this is

The v3 design keeps everything the heat-field series built — the kernel, the ramp, the origin
system, search, leave-by, movement, the location sheet — and changes the *shape of the page*:

> The six pictures ARE the plan. The verdict pills move onto the thumbnails, so the six-row
> list they used to label is deleted, along with the accordion, the per-row second map and the
> Order control. Everything that lived inside an open row opens as one popup on the picture
> you clicked. The origin is stated once, in the masthead's tick line, which is also the
> search trigger.

Three bundle facts that size this work correctly:

1. **`docs/design/plan-matrix/heat-field.js` is byte-identical to
   `docs/design/heat-map/heat-field.js`** — the kernel P0 ported (now
   `frontend/src/utils/heatField.js`) is unchanged. There is **no kernel work** in this series.
2. The bundle's `Map Tab with Heat.html` and `map-tab.js` are byte-identical to the heat-map
   bundle's copies. The Map tab (built in P4) is **out of scope** here.
3. `plan-data.js` is fixture data. Its synthetic scatter locations, its `CONF` array, its
   verdict thresholds and its `DELTA` matrix are all **prototype stand-ins for served data
   that already exists** — §4 maps each one to its real source and names the ones we
   deliberately do not port.

So this series is **frontend-only**. No migration, no new endpoint, no new backend field is
required for any phase. (§8 lists five optional backend enhancements the owner may choose
later; none blocks anything.)

### What the shipped v2 Plan pane is today (the "before")

```
WindowFirstShell
  masthead (BrandLockup · admin healthPill · ⚙ · Sign out · MastheadLight)
  rail footer (PlanOriginChip · "Home not set" · Edit reach · forecast age)
  ARIA tab bar (Plan · Coming up · Map · Operations)
  lens bar (reach 45/90/150/Any · rating Any/3★+/4★+ · Order When|Best)
  ── Plan panel ──
  WindowFirstHeatStrip        ← six thumbnails in ONE ROW + legend + change line + beyond line
  WindowFirstPromotedStrip    ← P7b, at most one per page
  paneItems list              ← WindowAwayRow | WindowFirstWindowCard (expandable rows)
      open row → WindowAttributeRow(s) + WindowRowRegionLayer
                 (WindowRowFieldMap + WindowRegionRail + WindowRegionBand)
                 + WindowSpotStrip → WindowSpotSheet
  WindowFirstDoors (HotTopicStrip · WindowFirstRegionalPanel → HeatmapGrid)
  dialogs: PlanSearch · LocationFourDaySheet · WindowSpotSheet · WindowPickDialog
```

### The "after" (v3)

```
WindowFirstShell
  masthead (unchanged row 1 · light rule · TICK LINE: origin + search + times)   ← M3
  ARIA tab bar (unchanged)
  lens bar (reach · rating — Order deleted)                                      ← M2
  ── Plan panel ──
  conflict messages (page-level)                                                 ← M2
  THE MATRIX: day columns × sunrise/sunset rows, cards carry verdict tint,       ← M1
      verdict word, spread histogram, best-reachable line, topics, pick legends
  legend footer · change line · beyond line (carried over)
  WindowFirstDoors (kept below the matrix — D-2, resolved)
  WINDOW POPUP (dialog): header · big field with location chips · region cards   ← M2
      · always-rendered prose slot · topic rows · tide row · ranked spot strip
  location sheet (v3 anatomy, new entry points)                                  ← M4
  dialogs: PlanSearch (restyled, tick-line-anchored) · WindowSpotSheet
```

---

## 2. The delta, exactly

| v3 element | Today | Work |
|---|---|---|
| Day-column matrix, sunrise row over sunset row, explicit grid placement, phone transpose | Strip is one row of six | **M1** — new layout util + component rework |
| Empty cells that say why (`this morning has gone` / `past the end of the forecast`) | Strip renders only rendered events | **M1** — derived from `renderedEvents` |
| Card anatomy: verdict word + tint, spread histogram, best-reachable line, topics named on card, `BEST BET`/`ALSO GOOD` riding the border, `SUNRISE`/`SUNSET` word | Card = dow + time + verdict + movement chip + best-bet flag | **M1** |
| Drill-down as a popup over the plan | Inline accordion row | **M2** — transplant existing row internals into a dialog |
| Region pick swaps prose + repaints field, never moves furniture; prose slot always rendered | WindowRegionBand appears on selection | **M2** |
| Location chips on the big field (greedy placement, from the same pool as the cards) | Field has region labels only | **M2** |
| Topic rows in the popup (science `i`, scope note) | Full topic detail lives only in HotTopicStrip behind the doors | **M2** — client join of `Badge` → `hotTopics` |
| Six-row list, accordion, Order control, away rows, per-card lens-empty states | All live | **M2** — deleted; conflicts move page-level |
| Origin control + times as the masthead tick line; origin chip/rail footer deleted | PlanOriginChip in the rail footer; MastheadLight renders times | **M3** |
| Search anchored under the masthead; result rows gain best-figure + action chips; better folding | PlanSearch is a centred Modal with plainer rows | **M3** |
| Location sheet v3 anatomy; opened from popup chips + spot cards; `Plan from <region>` / `Show on map` footer | P8 sheet, search-only entry, no footer actions | **M4** (D-3/D-4 resolved: yes to both) |
| Promoted strip: none in v3 (topics ride the cards) | P7b ships one per page | **M5** (D-1 resolved: delete) |
| Change line under the matrix; per-card movement chip deleted | Both exist on the strip (P6) | **M1** carries the line over; the chip dies with the old card face |

Not ported at all (prototype stand-ins — see §4): client verdict thresholds, numeric
confidence, the `DELTA`/`RUNAGE` fixtures, synthetic scatter locations, the `needs:
coast/lake` eligibility attribute, the fixture's per-window `lead` prose (`Window.lead` is a
prose sentence in `plan-data.js`; the codebase's `card.lead` is an unrelated **boolean**
lead-card flag — see A21), the viewport toggle and its localStorage key.

---

## 3. Ground rules in force (inherited, not negotiable)

These are the standing decisions from `docs/engineering/heat-field-plan.md` (D-rules, §5
performance, §8 traps), `docs/engineering/plan-verdict-consolidation-plan.md` (§3, §7),
`docs/engineering/plan-panel-data-contracts.md`, and CLAUDE.md. The adversarial review of
every phase checks the diff against this list.

1. **Verdict words, region means, best/also picks and confidence are server-owned** (heat-field
   D3; consolidation §3 "the client aggregates nothing"). The design's `vCls`/`vWord`
   thresholds (≥3.7 / ≥2.8) and its `◐ 65%` numeric confidence are prototype stand-ins and are
   **not ported**. Verdict = served `DisplayVerdict` via the existing `VERDICT_LABEL` map;
   pick = served `BriefingWindow.pick`; confidence = the served three-tier `Confidence`
   rendered through `confidenceUtils` (fill-decay + `ProvisionalMark`), and the kernel's
   `conf` scalar comes from the tier via the existing `fillScale`, exactly as the strip does
   today.
2. **One colour ramp in v2** (D2): `utils/scoreRamp.js` is the single source. v1's
   `markerUtils.RATING_COLOURS` stays frozen.
3. **The lens never filters the field** (heat-field §3/§4.5): reach and rating gate cards and
   lists, never the heat. Only the *framing* (origin scope / planning area) changes what the
   field draws.
4. **Privacy seam** (plan-panel-data-contracts): anything home-derived (reach, drive times,
   leave-by, planning area) stays client-side or on never-ETag'd `/api/user/settings*`
   endpoints, and never rides `GET /api/briefing`. The away-origin drive map builds from the
   shared region matrix **alone** and never borrows home `distanceMiles` (`planOrigin.js`
   module header).
5. **No counts of our own data as facts about the sky** (window-first §6 clause 4; heat-field
   §2.6). The matrix header carries no roster count; the spread histogram's tooltip counts
   *places within reach by rating* (the same statement the lens readout already makes), never
   "N of M scored".
6. **Degrade is silence, never synthesis** (P6): no movement basis → no change line and no
   delta chips, never a fabricated `—`; a null tide rollup → no tide row; a missing gloss →
   the explicit null path `WindowRegionBand` already has.
7. **Movement wording**: 'moved at'-family vocabulary from `utils/movement.js`, one age per
   screen (`generatedAt`), never "since <previousGeneratedAt>" alongside it. The bundle's
   "Since your last look 52m ago" copy is **adapted to the shipped vocabulary**, not the other
   way round.
8. **Performance invariants** (heat-field §5): cull + 3×3 bucketing untouched; draw after
   layout with the `useHeatCanvas` measure/retry contract (MIN 21px, 30 tries); per-card
   measurement where card widths differ; DPR cap 2; grid dials never below 3 unmeasured; no
   heavy synchronous paint in the same tick as an animated fitBounds.
9. **Tokens**: new CSS tokens go in the `@theme static` block in `frontend/src/index.css`
   (plain `@theme` gets pruned — the `--color-plex-panel` empty-string incident); JS hexes
   live in `scoreRamp.js`; token liveness is a browser-verification claim, not a jsdom one.
10. **Shared components get caller opt-ins** (`heat`, `serverCellRating` precedents): any edit
    to `HotTopicStrip`, `HeatmapGrid`, `MapView`, `DateStrip`, `Modal`, `BrandLockup`,
    `HealthIndicator` or anything in `components/shared/` must leave v1 byte-identical
    without the opt-in, with a pinning test per surface.
11. **Spot data joins are locationId-first, name-fallback** (`heatSpots.js`, P8's
    `buildScoreIndex`); the provider's name-keyed `scoreIndex` is **not** read by any new
    surface — raw `scoreRows` are. Region names join byte-identically, never normalised.
12. **A rating that does not mean sky colour never reaches the field or a pool** — the
    existing sky-subject gate (`isSkyPromptCandidate` filtering in `heatSpots.js` /
    `windowFirstCards.js`) fronts every new derivation (spread histogram, best-reach line,
    chips).
13. **`leaveBy`/`SETUP_MINUTES` stay the single client producers**; `spot.driveMinutes` has
    one producer (`effectiveReachById` overwrite) — nothing adds a sibling drive figure.
14. **Away windows are divs, not buttons** (P2): a control with no visible effect is banned.
15. **Accessibility**: dialogs are real dialogs (the shared `Modal`); canvases stay
    `aria-hidden` with a visually-hidden per-card sentence (the strip's pattern); an
    `aria-label` replaces content, so any label naming a count must count what is actually
    rendered; role queries with accessible-name assertions in tests.
16. **Frontend test standards** (`docs/engineering/frontend-test-standards.md`) bind every
    phase: fixture factories shaped by the real derivers, fixed clocks never landing on the
    real today, `css: false` discipline with sliced-stylesheet cascade tests where the
    cascade itself is load-bearing, named degrade-path tests, no render-only tests.
17. **Git**: never push; CHANGELOG on every meaningful commit; conventional commits; the plan
    doc's Status and phase row update in the same commit as the phase.

---

## 4. Design → build adaptations (where the bundle and the codebase disagree)

Each row is a deliberate decision, made once here so no phase relitigates it.

| # | Bundle says | We build | Why |
|---|---|---|---|
| A1 | `verdict(avg)`: ≥3.7 Worth it, ≥2.8 Maybe | Served `card.verdict`/`verdictLabel` (top-region `DisplayVerdict`) | Rule 1. The served window verdict is already "the top region's mean-rating band" — the same semantics the bundle approximates. |
| A2 | `CONF = [0.95 … 0.57]`, `◐ 65% confidence` in the popup header | Served three-tier confidence; kernel `conf` via `fillScale`; popup header shows the tier treatment (`ProvisionalMark`/fill-decay), no percentage | Rule 1 / heat-field D3, which rejected this exact percentage once already. |
| A3 | `average 4.1★ across 40 locations` in the popup header | **Dropped.** Header keeps: best-in-reach star, confidence tier, movement | A client cross-location mean is the aggregation class consolidation Phase 3 removed; and heat-field §9.3 already flags quality-said-four-times as the disease this redesign cures. |
| A4 | Exactly one `BEST BET` + one `ALSO GOOD`, recomputed per origin scope | Served `BriefingWindow.pick` only. Under an away origin the pick is withheld when it names an out-of-scope region (existing behaviour) — so an away plan may legitimately show **no** pick legend | Rule 1. Recomputing picks per scope is server-owned math. Deviation from the bundle stated in §8 (D-5) with an optional backend follow-on. |
| A5 | `DELTA` per region×window, `RUNAGE`, "Since your last look 52m ago" | Served `meanRatingDelta` + `previousGeneratedAt` (V144 movement channel, live since P6); change line keeps `movement.js` wording and the single `generatedAt` age | Both exist server-side already; Rule 7 governs the copy. |
| A6 | 204 rated locations, 51 named; synthetic scatter for field legibility | The real roster (~100–140 named locations), no synthetic points, coverage clamp leaves empty ground empty — as shipped since P0 | Never synthesise. The old bundle said it itself: "scatter locations exist only to make the field legible at mock scale". |
| A7 | `204 rated locations · 51 named` section-head count | No roster count in the matrix header; the lens readout (`formatLensReadout`) remains the one count statement on the page | Rule 5; duplicate statements are the disease (§9.3). |
| A8 | Topic `needs: coast/lake` attribute; eligibility derived from location properties; origin move edits the topic list | Client join in ONE util, two rules. **(1) The join** replicates `PlanWindowProjector.keysFor` exactly: badge `B` on window `(date, targetType)` matches a `hotTopics` entry with `topic.type === B.type` whose served `topic.eventType`+`topic.date` bucket onto that window — `SUNRISE`→`(date, SUNRISE)`, `SUNSET`→`(date, SUNSET)`, `NIGHT`→`(date, SUNSET)` **and** `(date+1, SUNRISE)`. Key on the served `eventType` field, never a client list of types — a naive `topic.date == card.date` equality silently loses every NIGHT topic (aurora, NLC, meteor, supermoon) on its morning card. **(2) The scope filter** intersects `topic.regions` with the current scope and drops an empty intersection **only for region-scoped topics** (tide, inversion, dust, snow, bluebell classes). Whole-sky types (AURORA, NLC, METEOR, SUPERMOON, EQUINOX, ECLIPSE) are **exempt by type** — a client type map, the `topicCertainty` precedent — because their served `regions` list is populated but means something else (aurora's is Bortle-enrichment coverage, NLC's is where-it's-clear-tonight), so an intersection test would delete aurora from every away plan. The popup scope note (`<n> region(s) in scope`) renders **only** for region-scoped topics, from the same intersection; whole-sky topics get no scope count | No `needs` attribute exists and none is needed — but `regions` is NOT a uniform eligibility roster: its semantics differ per strategy, so eligibility-vs-conditions must be split by type on the client. Zero backend. |
| A9 | Popup topic rows carry the science note | Same join as A8: `topic.description` is served on the full `HotTopic`, just not on the slim `Badge` — the popup reads it from `hotTopics`, rendered with the existing `InfoTip` | Avoids widening `Badge`; the data is already on the wire. |
| A10 | Spread histogram over named in-reach locations, **without** the rating floor | New client derivation over the card's reach-gated, sky-gated slot pool *before* the rating gate (a pool `buildWindowCards` must now expose alongside `spots`) | Reach is per-user (Rule 4) so this cannot be served on the shared payload; it is a count of places-to-go, the statement the lens readout already makes (Rule 5). Tooltip copy: "N locations within reach — … " — never "scored". |
| A11 | Best-reachable line: name, rating colour, region · drive · leave-by | Head of the same pool as A10 under the existing rating-then-drive ordering (`windowSpotBrowse`); its star doubles as the header's "best N★ within reach" | Same ownership argument as A10: the pool is reach-gated, reach is per-user (Rule 4), so "best *within reach*" has no servable answer on the shared payload — the same reasoning D-6 applies. This is deliberately NOT claimed as a licence: P1/P7's recorded refusals of client maxima applied to figures a served field could answer (roster-wide `bestRating`), and both rulings treat per-user reach joins as correctly client-side; Close-to-home's ranking is on record as *debt, not licence* (CLAUDE.md), and P8's licence terms ("aggregates nothing across locations") do not cover this. So: a new member of the per-user-derivation class, recorded here, with the eventual never-cached per-user endpoint (plan-panel-data-contracts §5 migration path) as its exit — see §8 O-4. The design itself demands the map, the list and the line share one pool. |
| A12 | Tide row with sparkline + facts | The existing `windowFirstRows` tide row + `WindowTideSparkline`, transplanted into the popup unchanged | Already matches the design (built from the same design family). |
| A13 | Search: accent/`&`/`saint` folding + aliases (`bait island`) | Fold improvements ported into `planSearch.js` `fold()` (client-only). Aliases: **deferred** — no alias store exists (optional backend enhancement, §8 O-3) | Cheap vs. new schema. |
| A14 | Search dropdown physically under the masthead, replacing the tick line | PlanSearch stays the shared `Modal` (focus trap, esc handling, tested), restyled and visually anchored beneath the masthead; the tick line swaps to the input affordance while open | Function over chrome; the Modal already solves focus/keyboard/scroll problems the prototype ignores. Note the anchored look presumes the masthead is on screen mid-scroll — which only M3's masthead stickiness guarantees. If the anchored look proves impossible inside Modal, M3's review decides — not silently. |
| A15 | Viewport toggle + `photocast.heat.viewport` | Not shipped (bundle says so itself); breakpoints via the existing `wf-` responsive classes + `useIsMobile` | — |
| A16 | Origin/reach persistence (bundle open question 4) | Unchanged: origin in-memory only (P7 rule — a persisted origin would outlive the day-stamped lens that framed it); reach stays day-stamped | Already litigated in P7. |
| A17 | `GLANCE` framing, beyond line, `TODAY ONLY` marker, reach auto-drop to 90 away | All exist (`planningArea.js`, strip beyond line, day-stamped reach, `AWAY_TIER_ID`) — carried over, not rebuilt | — |
| A18 | Masthead status chip `● UP v2.18.12` visible to everyone | Admin-only `healthPill` stays as-is | Decided when the masthead was built; a pilot user sees no status chip. |
| A19 | Empty-scope matrix (bundle open question 5) | An origin scope with zero locations cannot arise from search (baseless/disabled regions are unpickable), but a scope whose window has no scored spots draws the existing `hatch` treatment (never an empty point set — 2026-08-19 lesson); the page-level conflict messages (M2) cover nothing-in-reach | — |
| A20 | Phone 3×2 grid in the old bundle vs. transposed day-grouped matrix in v3 | v3 wins: transposed matrix, day headers spanning the row, solo cards full-width | v3 supersedes. |
| A21 | The prose slot's unpicked state reads the window's own `lead` sentence | **No served window-level prose exists** — the bundle's `Window.lead` is fixture prose, and the codebase's `card.lead` is an unrelated *boolean* (the gold-accent lead-card flag, `windowFirstCards.js`). The unpicked slot renders the **top region's** served prose instead — gloss headline/detail, `summary` fallback, the explicit null path `WindowRegionBand` already has — **labelled with that region's name**, never presented as whole-window prose. The slot keeps its fixed min-height in every state, including null | Degrade is silence, never synthesis (Rule 6). The window's verdict IS its top region's, so the top region's gloss is the honest nearest-served prose. True window-level lead prose is §8 O-5 (optional backend), not a blocker. |

---

## 5. Data mapping — every v3 surface element to its source

**Nothing in this table requires new backend work.** "pool" = the card's origin-scoped,
sky-gated, reach-gated spot list *without* the rating floor (new in M1, see A10); "gated pool"
= pool with the rating floor applied (today's `card.spots`).

| Surface element | Source |
|---|---|
| Day columns / matrix shape | `DailyBriefingResponse.renderedEvents` (≤6, chronological, past-dropped server-side) grouped by UK date; away days from `buildPaneItems` |
| Empty cell "this morning has gone" | First rendered day missing its SUNRISE — that event elapsed (server dropped it) |
| Empty cell "past the end of the forecast" | Last rendered day missing its SUNSET — beyond the 6-event render cap |
| Card sun word / time / date tile | `heatStripCards` descriptors (date, targetType, dow, time) |
| Thumbnail | `heatField.drawGeo` — existing dials (grid 4, radius max(10, w×0.155), blur 2.4), aspect clamp changes 0.85–1.22 → **0.78–1.0**, per-card width measure, `conf` from tier `fillScale`, `fit` from `scopeSpots`; hatch when `card.bestRating` is null |
| Verdict word + tint | `card.verdict` / `card.verdictLabel` (served) |
| Spread histogram | Client count of `claudeRating` per band over the pool |
| Best-reachable line | Pool head under rating-then-drive; drive from `effectiveReachById`; leave-by from `utils/leaveBy.js`; "nothing in reach" when pool empty |
| Topics on card | `card.allBadges` sorted by `rarityRank`, scope-filtered per A8; channel colours from the existing `CHANNEL` map |
| `BEST BET` / `ALSO GOOD` legends | `card.pick.kind` (served BEST/ALSO) |
| Change line | `utils/movement.js` over served `meanRatingDelta` (unchanged) |
| Beyond line | `planningArea.beyondRegions` (unchanged, home-origin only) |
| Legend footer | `RAMP_GRADIENT` from `RAMP_STOPS` (exists) + copy |
| Conflict messages (page-level) | New derivation over all cards' pools + lens state (the per-card `lensEmpty` ladder logic, lifted to page scope) |
| Popup header title/date/time | Card descriptor |
| Popup verdict / pick badges / topic pills | Served card fields, as above |
| Popup "best N★ within reach" | Pool head's rating (same value the best-reach line shows) |
| Popup confidence | Served tier via `confidenceUtils` (A2) |
| Popup movement | `card.movement` + `movement.js` wording |
| Big field | `WindowRowFieldMap` — existing kernel dials (grid 6, radius max(20, w×0.072), blur 3.6), aspect clamp → 0.88–1.34 desktop / 0.5–0.95 phone, region focus via existing centroid pick |
| Location chips on field | Gated pool, focused-region-first then rating-then-drive, cap 8 (phone 6), greedy no-overlap placement, drop-don't-overlap; DOM spans (never canvas text); click → location sheet (M4) |
| Region cards (rail) | `BriefingRegion` per event summary: `verdictLabel` in the band colour, `bestRating`, in-reach count = region slots ∩ pool, else `min` region drive from `effectiveReachById` as "Nh away"; `All N regions` peer cell as the clear (all existing `WindowRegionRail` inputs) |
| Prose slot (unpicked) | Top region's gloss/`summary`/null-path, labelled with the region's name (A21 — `card.lead` is a boolean, NOT prose; there is no served window-level prose) |
| Prose slot (picked) | Region `glossHeadline`/`glossDetail` → `summary` fallback → explicit null path (existing `WindowRegionBand` logic) + `meanRatingDelta` chip + "N of its locations below" from the gated pool |
| Topic rows | Badge ∪ `hotTopics` join per A8's two rules (eventType/date bucketing incl. the NIGHT date+1 case; type-keyed scope exemption): name, `detail`, facts, `description` behind the `i` (InfoTip), scope note for region-scoped topics only, `safetyNote` ungated |
| Tide row | Existing `windowFirstRows` tide row + `WindowTideSparkline` (served `BriefingWindowTide`: curve, windowPosition/Level, nearest extreme, range, anomaly, `at <location>` caveat) |
| Ranked locations strip | `WindowSpotStrip` over the gated pool (existing order); snap-strip sizing 3.5 / 2.6 / 76%; "See all N →" → `WindowSpotSheet` |
| Popup footer | `spotOrderStatement` + active-filter chip (lens state) + see-all |
| Masthead times | `GET /api/user/settings/light` via `MastheadLight` (204 = postcode nudge; failure ≠ 204) |
| Origin label | `origin` descriptor (`{id, name, baseName}`) or `homePlace` from settings |
| Search rows | `planSearch.buildSearchGroups` (windows/regions/locations) + new best-figure column: window rows read the card's pool head; location rows read that location's best window from `sheetScoreIndex`-style id-first rows |
| Location sheet rows | `buildLocationSheet` (existing): per-window rating + `summary` prose from id-first score rows, location's own region confidence, origin-aware drive/leave-by |

---

## 6. Phases

Every phase: **build → `npm run lint && npm test && npm audit --audit-level=high && npm run
build` → adversarial review of the diff (six-lens shape from `ui-work-review-cadence`) → fix →
browser-verify (§9) → commit** (with CHANGELOG + this doc's Status row).

### M1 — The matrix (strip becomes the plan; the list stays, for now)

Replace `WindowFirstHeatStrip`'s single row with the day×event matrix and grow the card
anatomy. **Card click keeps today's behaviour** (open + scroll to the list row below) so the
phase ships without the popup.

Tasks:
1. `utils/windowFirstMatrix.js` (new): group `heatStripCards` + away pane items by UK date →
   `{days: [{date, dow, dn, today, am: card|null, pm: card|null, away}], columns}`. Empty-cell
   classification per §5. Unit-test the grid maths exhaustively: 6 events over 4 days; a
   forecast starting at sunrise (no leading hole); an away day mid-span; a single-window final
   day; zero rendered events.
2. `utils/windowFirstSpread.js` (new): star-band counts over the pool. The tooltip's leading
   `N` is the **pool size** (in-reach places, rated or not — the prototype's `p.length`),
   never the sum of the bars; when unrated in-reach spots exist the tooltip names the
   remainder explicitly (`… · 2 not yet rated`) rather than leaving `N > Σbars`
   undisclosed. `title` copy per Rule 5 — places to go, never "scored".
3. `windowFirstCards.js`: expose the **pool** (reach-gated, pre-rating-floor) beside `spots`,
   and the pool head as `bestReach` (name, locationId, rating, regionName, driveMinutes).
   Reuse the existing ordering comparator — do not write a second one.
4. Rework `WindowFirstHeatStrip.jsx` → the matrix component (keep the file/testids where
   practical; rename only if the review prefers it): CSS grid with explicit placement
   (`--c`/`--r` custom props), day-header tiles + hairline rules, dashed empty cells, away
   cells as divs, card face per the bundle README §"Window card" (sun word, canvas, 4-row
   value grid, topics row with reserved height, verdict tint classes `vg/vm/vp`, pick
   legends riding the border, open state). Aspect clamp 0.78–1.0; **per-card width
   measurement** (a solo phone card is twice a paired one — the single-well measurement the
   strip uses today is no longer valid).
5. Phone transpose: 2-col grid, day headers spanning, `.solo` full-width, empty cells
   `display:none` on phone.
6. Carry over unchanged: legend footer, change line, beyond line, hatch-for-unscored, the
   visually-hidden per-card sentence (update it to include the new card facts — count what is
   rendered).
7. Tokens: new `wf-matrix-*`/tint classes in `index.css`; any new theme token into
   `@theme static`.
8. Delete in this phase (consolidation §5 — deletion lands with the phase that makes it
   dead): the old one-row strip layout, the per-card movement chip (the change line and popup
   header carry movement now — bundle note "the per-card delta earned nothing").

Tests: matrix util (above); card face — verdict word from served label only (mutate: a
client threshold cannot exist); spread histogram band edges (0/1/many per band, canopy
excluded, floor **not** applied — member+non-member is not enough, hold the other axis
constant and sit fixtures on each edge) plus a fixture with unrated in-reach spots pinning
the tooltip's `N` to pool length, not `Σbars`; best-reach = strip pool head identity (one
comparator); topic scope filter (A8): a region-scoped topic (e.g. king tide) drops when the
intersection empties, and a whole-sky topic (aurora, with its **populated** served regions
list) survives any origin scope; pick legends render only served picks; away cell is a div;
empty-cell copy both kinds; accname sentence counts.

Browser-verify: desktop/iPad/phone widths, solo-card width, tint alphas, border legends
(`background: inherit` over the tint), today-column accent, canvas redraw on resize and
`document.fonts.ready`.

### M2 — The window popup (the list dies)

Move the open-row internals into a dialog; delete the list.

Tasks:
1. `components/WindowSheetDialog.jsx` (new, on shared `Modal`): header (date box, title,
   time, verdict badge, pick badge, topic pills, second line per A2/A3/A5, `‹ n/6 ›` nav +
   esc), body two-column ≥ tablet (field left, region cards + prose right), then topic rows,
   tide row, spot strip, footer. Full-screen on phone. State in the shell: `openWindowKey`,
   `focusedRegion` (reset on window change), scroll position preserved under the scrim.
2. Transplant, don't rewrite: `WindowRowFieldMap` (aspect clamp → 0.88–1.34 / phone
   0.5–0.95), `WindowRegionRail` (restyled to the v3 card grid; the "Nh away" figure is an
   **existing** rail input — `buildRegionRows` already derives the per-region min drive from
   `spot.driveMinutes` as `awayLabel` and `metaLine` renders it when reach empties a region,
   the same conditionality the v3 design specifies — the delta is presentation only, and per
   Rule 13 no sibling min-drive derivation is added), `WindowRegionBand` → the
   always-rendered prose slot (fixed min-height in every state; unpicked = top region's
   gloss/`summary`/null-path labelled with the region's name per **A21**; picked = that
   region's existing gloss/summary/null-path + delta chip + below-count),
   `WindowAttributeRow`'s tide row, `WindowSpotStrip` (snap sizing).
3. Location chips on the field (new layer in `WindowRowFieldMap` or a sibling): greedy
   placement per §5, measured DOM spans, flip-left when right side clips, drop when nothing
   fits, `title` = region · drive · leave-by. Click opens the location sheet — wired in M4;
   until then chips render as non-interactive spans (Rule 14's div-not-button remedy).
   D-3/D-4 are resolved (yes to both), so the "land M2 and M4 together" option is open at
   the implementer's discretion if an inert interim state proves unacceptable in review.
4. Topic rows: new `WindowTopicRows.jsx` implementing A8's two rules exactly (eventType/date
   bucketing including NIGHT → date+1 SUNRISE; type-keyed scope exemption); science
   `InfoTip` from `topic.description`; scope note for region-scoped topics only;
   `safetyNote` never blurred.
5. Keyboard: `←`/`→` step windows while open (not while search or a stacked sheet is open).
   Esc closes **topmost first, one layer per press**: search (its own Modal esc handling,
   A14) → any sheet stacked over the popup (`WindowSpotSheet` via "See all", this phase;
   `LocationFourDaySheet` from M4) → the popup itself — the bundle README's "Esc closes
   search → then the location sheet → then the window popup". M4 task 2 cites this ordering
   rather than restating it. Focus trap and focus-return via Modal.
6. Conflict/empty states, two scopes: **page-level** — `utils/planConflicts.js` (new) —
   nothing-in-reach (names scope count + nearest + widen action) and floor-shut (names
   ceiling + where + two actions), rendered above the matrix, actions driving the existing
   lens setters; **per-window** — when the open popup's gated pool is empty, the spot-strip
   slot renders the filter-naming quiet sentence (prototype: "Nothing at `<floor>` within
   `<reach>`[ in `<Region>`] for this window."), with the region clause present exactly when
   a region focus did the emptying — the page-level messages cannot cover this, because a
   single window (or a focused region) can be empty while the plan as a whole is not.
7. Deletions: the paneItems card **list rendering** (`WindowFirstWindowCard` as a list row,
   `WindowAwayRow`, `WindowRowRegionLayer` lazy inline mount — the `buildPaneItems`
   derivation itself survives as matrix + promoted-strip input), Order control end-to-end
   (`usePlanOrder`, `windowFirstOrder.js`, lens segment, order note), per-card `lensEmpty`
   ladder (its plan-wide job moves to the page-level conflicts; its per-window job moves to
   the popup's quiet sentence — both replacements land in this phase),
   `WindowSpotPeek`/`useSpotPeek` if the popup makes hover-peek redundant (review call).
   Their tests go with them; salvage assertions that still describe popup behaviour into the
   new suites.
8. `WindowPickDialog` / `revealWindow` / promoted-strip "Go to" now target the popup (open
   dialog) instead of scroll-to-row. Remove (or redefine) the promoted strip's `adjacent`
   suppression in the same commit: its rationale — a control that scrolls to the element
   directly beneath it has no visible effect — is scroll-specific, and left in place it
   hides the Go-to control for first-window promoted topics for as long as the strip
   survives (D-1).

Tests: dialog semantics (role, accname, focus trap, esc order incl. a stacked
`WindowSpotSheet`, arrow nav incl. wrap and guard-while-search-open); prose slot never moves
(same node; picked, unpicked-with-gloss, and unpicked-null states all asserted at the same
min-height); region pick repaints (focus prop) + filters strip + swaps prose and **nothing
else re-orders**; chips ⊆ gated pool (the map can never name an excluded spot — pin by
identity with the strip fixture); topic-row join: a NIGHT topic (aurora) dated D joins its
badge on D+1's SUNRISE popup (science `i` present), a region-scoped topic drops on an empty
scope intersection, a whole-sky topic with populated regions survives an away origin, and
the degrade path (badge with no matching hotTopic → row renders from badge fields alone, no
science `i`); conflict messages: each rule's edges + actions actually move the lens; the
per-window quiet sentence in both variants (lens-emptied vs region-focus-emptied); deletion
tests: Order gone from the bar, list gone, `revealWindow` opens dialog, promoted-strip Go-to
visible for a first-window topic.

### M3 — The tick line (origin + search into the masthead)

1. Masthead row: split `MastheadLight` (v2-only component) into rule + a tick line that
   composes: origin button (home/away label + pin SVGs + away tint), home button (away
   only), search affordance (`⌕` + `/` kbd chip), times right-aligned (existing LightTime
   spans; phone shows the two golden only). The three MastheadLight states (unlit / postcode
   nudge / lit) survive intact — the nudge moves into the origin button's empty-state label.
2. Stickiness (the design's chrome model, currently unported): the v2 masthead becomes
   `position: sticky` (design z-index 45) — a deliberate change to the shell's documented
   stick invariant, so the `index.css` comment that says "position: sticky here and nowhere
   above it" (near the `.wf-lens` rules) is updated **in the same commit**; the lens re-bases
   from `top: 0` to a runtime-measured masthead height (a `--wf-mast-h` custom property
   written by the shell — the `--wf-lens-reserve` measuring precedent); and the lens gains
   the stuck treatment (`box-shadow 0 12px 26px rgba(0,0,0,.5)` + raised border, `.18s`,
   driven by an IntersectionObserver on a 1px sentinel, per the bundle README) — or its
   omission is recorded as a new §4 row, not left silent. `useLensReserve`'s measurement must
   keep including whatever sits above the cards.
3. Search open: tick line swaps to the input; PlanSearch anchored under the masthead (A14);
   `/` shortcut unchanged.
4. Search anatomy: glyph column, `<mark>` highlighting, sub-line (region · drive ·
   `outside your plan` where applicable), best-figure column (§5), action chips
   (`4 days` / `Plan from here` / `Planning now` / `Open window`); fold improvements (A13).
   Empty query = three windows resting group (exists); "Recent locations" stays unbuilt
   (heat-field §9.11 — no recency store).
5. Deletions: rail footer (`PlanOriginChip`, "Home not set" line, "Edit reach" link,
   forecast-age line). Relocations, not losses: "Edit reach" → the ⚙ settings path it
   already opens; forecast age → beside the change line (one age, Rule 7); "Home not set" →
   masthead empty state. The lens caption `Drive from <base>` and region lead paragraph
   (`region.glossDetail`? — **no**: no served region lead exists, heat-field §9.10 resolved
   *omit*; the section-head suffix `· <REGION>` is enough) follow the origin as today.
6. `WindowFirstShellMasthead`/`planOriginShell` test reworks; keyboard `/` guard tests;
   origin move still: re-frames thumbnails, swaps `effectiveReachById`, drops reach default
   to 90, suppresses beyond line (all pinned already — keep those suites green unedited
   where they describe unchanged behaviour).

### M4 — The location sheet, v3 (D-3/D-4 resolved: both yes — ungated)

1. Restyle `LocationFourDaySheet` to the v3 anatomy: header meta (`region · N min from
   <base>`, `outside your plan` badge), lead block (existing lead line — **no** "1 OF 6
   WINDOWS" ratio; P8 lesson 3 and Rule 5: `2 windows at 4★+` / `none at 4★+`, no
   denominator), event rows with date boxes + rating chips + leave-by line + confidence
   tier mark (not %), expandable per-row prose = the row's own `summary` (id-first score
   row), best row pre-expanded, ≤2★ rows dimmed.
2. New entry points (D-3): popup field chips and spot-strip cards open the sheet **over**
   the popup (Modal stacking; esc order per M2 task 5 — topmost first). Search entry stays.
3. Footer (D-4): `◎ Plan from <region> →` — closes the sheet *and* the popup first, then
   `setOrigin` (preserves the P8 invariant's intent: the origin never moves *under* an open
   sheet); `◍ Show on map →` → existing `onShowOnMap` overlay hatch.
4. Tests: entry from all three routes; stacked esc order; footer close-then-move sequence
   asserted as ordering, not just outcome; the sheet's existing id-first key-policy suites
   stay green unedited.

### M5 — Disposition, sweep, and the settling commit

1. Apply the resolved D-1/D-2: delete the promoted strip (`WindowFirstPromotedStrip`,
   `windowFirstPromoted.js`, their tests, and the shell's `renderedStrip`/`revealWindow`
   strip wiring — ledger entry below); confirm the doors untouched and still rendering below
   the matrix (no build work — a pinning check, not a change).
2. §6-style copy sweep over every new string (counts, denominators, two-ages, "since").
3. Accessibility pass: axe on the four surfaces, screen-reader walk of matrix → popup →
   sheet (the accname/browse-mode check the flip has been waiting for), keyboard-only
   traversal, focus-visible on the new controls.
4. Phone pass on a real device width (390px): matrix transpose, popup full-screen, sheet,
   search.
5. Performance: six taller canvases + dialog field — measure a redraw storm (resize, font
   load, origin move) against the §5 invariants; verify the `geo` chunk boundaries survived
   the component moves (no d3-geo in the entry bundle).
6. Docs: update CLAUDE.md's Plan-tab bullets (strip → matrix; stale "aurora grid columns"
   comment in `DailyBriefing.jsx:926` and dead `computeAuroraCellTier` can be cleaned in the
   v1 deletion, not here); close this doc's Status.
7. Full-page adversarial review (the ~15-agent shape) + browser verification as the final
   gate before the owner considers the flag flip.

---

## 7. Deletion ledger

| Phase | Dies | Replaced by |
|---|---|---|
| M1 | One-row strip layout; per-card movement chip | Matrix; change line + popup header |
| M2 | Card list **rendering only** (`WindowFirstWindowCard` list role, `WindowAwayRow` — the `buildPaneItems` derivation survives as matrix + promoted-strip input), accordion + `WindowRowRegionLayer` inline mount, Order control (`usePlanOrder`, `windowFirstOrder.js`, lens segment, order note), per-card `lensEmpty`, the promoted strip's scroll-specific `adjacent` suppression, (review call) `WindowSpotPeek` | Popup dialog; page-level conflicts + per-window quiet sentence |
| M3 | Rail footer: `PlanOriginChip`, home-not-set line, Edit-reach link, forecast-age line | Tick line; age beside change line |
| M5 | (D-1, resolved) `WindowFirstPromotedStrip` + `windowFirstPromoted.js` + strip wiring in the shell | Topics on cards + popup rows |

`WindowSpotSheet` survives (the popup's "See all"). `WindowFirstDoors`/`HotTopicStrip`/
`WindowFirstRegionalPanel` survive pending D-2. v1 components are untouched throughout.

---

## 8. Owner decisions — ALL SIX RESOLVED 2026-08-20

**All six were resolved by the owner on 2026-08-20, adopting the attached recommendations.
Nothing in M1–M5 is decision-gated any more.** The protocol below stays in force for any
*new* decision a phase surfaces: an unanswered decision blocks the work it gates — stop and
ask; a recommendation is not a default, and no review finding or convenience argument
promotes one.

- **D-1 Promoted strip — RESOLVED: delete in M5.** v3 has no strip — every topic is named on
  its card and all six cards are above the fold, so the strip's job (surfacing a coincidence
  above the fold) no longer exists. The rarity machinery (rarityRank ordering) survives on
  cards and popup rows. `WindowFirstPromotedStrip` + `windowFirstPromoted.js` + their tests
  go in M5; until then the strip runs unchanged (with M2.8's adjacency fix so its Go-to
  control works against the popup).
- **D-2 The doors — RESOLVED: keep below the matrix for this series; revisit after the
  flip.** v3 shows nothing below the legend/change/beyond lines, but `HotTopicStrip`
  (tide-run/surge charts, expanded aurora, certainty chips) and the regional panel have no
  other v2 home, and storm surge/clearance topics never become window badges (no event
  anchor) — deleting the doors would silence them on v2 entirely. No M-phase touches them.
- **D-3 Location-sheet entry points — RESOLVED: yes, adopt v3's entries** (popup field chips
  + spot-strip cards + search). This knowingly reverses the §9.9 search-only call of
  2026-08-20: the context that motivated it (spot cards opened the map) changes when the
  cards live inside a dialog. M4 is ungated.
- **D-4 `Plan from <region>` sheet footer — RESOLVED: yes, build it with close-then-move
  semantics** (M4.3: close the sheet *and* the popup, then `setOrigin`). P8's "never move
  the origin from inside an open sheet" invariant is honoured in intent — the origin never
  moves *under* an open surface.
- **D-5 Away-origin picks — RESOLVED: accept the deviation.** The served pick stays the only
  pick; an away plan may legitimately show no `BEST BET`/`ALSO GOOD` legend (A4). If away
  picks come to matter in use, O-1 (served per-region pick) is the follow-on — not scheduled.
- **D-6 Popup header average — RESOLVED: stays dropped.** A3 stands; the header keeps
  best-in-reach star, confidence tier and movement. Reopening this later means a served
  figure (Rule 1) that cannot be reach-scoped on the shared payload (Rule 4).

Optional backend enhancements (none blocks the series): **O-1** per-region pick for away
origins (D-5); **O-2** full per-day extreme list on `BriefingWindowTide` (the popup currently
shows the nearest extreme + curve, which matches the bundle); **O-3** `location_alias` store
for search aliases (A13); **O-4** a never-cached per-user endpoint for the reach-scoped
figures (spread histogram, best-reachable) — the plan-panel-data-contracts §5 migration path
and the recorded exit for the client-side derivations A10/A11 add to the per-user-debt class;
**O-5** served window-level lead prose (e.g. a `BriefingWindow` gloss) — would upgrade the
prose slot's unpicked state from the top-region gloss (A21) to true whole-window prose.

---

## 9. Verification recipe (worked end-to-end 2026-08-20)

- `frontend/.env.local`: `VITE_API_TARGET=http://localhost:8083` (absent in a fresh worktree;
  without it every request 502s at login).
- Backend: `cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`
  (port **8083**). Detect startup by grepping the log for `Started GoldenHourApplication` —
  never by polling an endpoint (401s defeat `curl -sf`).
- Seed: `scripts/dev-seed-locations.sh` (ratings SQL verified 2026-08-20; local H2 schema is
  Hibernate-generated, so `evaluated_at`/`updated_at` are supplied explicitly). The running
  backend holds the H2 file lock — batch direct inserts as stop → RunScript → start.
- Drive times locally: insert `user_drive_time` and `region_drive_time` rows directly (ORS is
  not configured locally).
- Flag: `localStorage.setItem('photocast.planLayout', JSON.stringify('v2'))` — JSON-encoded,
  the bare string silently falls back to v1.
- Browser-pane clicks land in the **screenshot's** coordinate frame (scaled) — use
  `read_page` refs or drive the DOM via `javascript_tool`. Logs go to the session scratchpad,
  never `/tmp` (shared between worktree sessions).
- Sign in `admin` / `golden2026`. For LITE checks: no LITE account exists locally by default —
  create one via the admin UI first.

---

## 10. Risks worth watching

1. **The matrix is a bigger canvas bill than the strip** (taller thumbnails, per-card
   measurement, plus a dialog field). The §5 invariants were tuned on this kernel at these
   dial values, so risk is moderate — but M1's review must include a measured redraw, not an
   assertion.
2. **Scope-filtering badges client-side (A8)** is the one new place client logic touches
   topic *visibility*. It filters (like the lens), it does not re-derive a served judgement —
   keep the join and the filter in one util. The live hazard is the **type exemption**:
   aurora and NLC serve *populated* `regions` lists whose semantics are not eligibility
   (Bortle coverage / where-it's-clear), so an unexempted intersection test deletes aurora
   from every away plan while every naive test passes. Pin the exemption with a
   populated-regions aurora fixture under an away origin, and pin the NIGHT date+1 join with
   a morning-card fixture — those are the two branches an obvious implementation gets wrong.
3. **Deleting the list deletes surfaces tests point at.** Expect a large test-migration
   diff in M2; the discipline is salvage-by-behaviour (does the assertion describe the
   popup?) not wholesale port.
4. **Modal stacking (sheet over popup)** is new — the shared `Modal` has no current
   double-stack consumer. Prove esc order and focus-return with tests before styling.
5. **Rollback shape**: each phase is additive-then-delete inside the v2 arm; the flag is the
   page-level kill switch (v1 untouched). A phase that half-lands must revert whole — the
   matrix without its card anatomy, or the popup beside a live list, are worse than either
   end state.
