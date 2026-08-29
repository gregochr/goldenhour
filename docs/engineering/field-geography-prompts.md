# Field geography + glyphs — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order. Each phase lands as its
own reviewed commit before the next session starts — the plan's **Status line and phase table are
the source of truth between sessions**, updated in the same commit as each phase, so a new session
never needs this chat's history. If a session dies mid-phase, start a new one with the same
prompt: step one of every prompt is reading the current state from the repo.

**Status vocabulary** (the phase table's `state` column): the implementing session sets its own
row to `built, in review` in its commit — it never pushes, so it can never truthfully write
"merged"; the **owner** flips the row to `merged` when merging. A downstream session finding the
predecessor's row at `built, in review` (or `not started`) stops and says so; finding `merged`,
it branches **off up-to-date `main`** — every phase does, since each depends on its merged
predecessor.

The plan: `docs/engineering/field-geography-and-glyphs-plan.md`. The design bundle:
`docs/design/field-geography/` (vendored verbatim — do not edit it; the plan's §0 wins where the
bundle's README is stale against this repo).

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the
pre-commit adversarial review, which each prompt instructs explicitly — plain parallel subagents
(the Agent tool). Review agents are **read-only**; anything that must mutate gets its own
worktree; commit or stash before a review that runs mutations (CLAUDE.md § *UI Work — Review
Cadence* records why, including the destroyed-work incident).

**Every phase, without being reminded:** read CLAUDE.md and the frontend test standards
(`docs/engineering/frontend-test-standards.md`) before writing code; never push, never tag; gate
on exit codes, never on what output appears to say; run the full frontend gate
`npm run lint && npm test && npm audit --audit-level=high && npm run build` (the audit step is
the one nothing local runs by default and it has cost a CI round before).

**Between phases (owner):** review in the browser, merge, push. Expect a `CHANGELOG.md` conflict
if anything else merged (`git rev-list --count HEAD..origin/main`).

---

## G1 · Label placement utility + kmPerPx

> You are implementing **Phase G1** of
> `docs/engineering/field-geography-and-glyphs-plan.md`. Read the plan in full first — its §0
> reconciliation table is load-bearing and its cited line numbers may have drifted, so re-verify
> every citation you rely on before editing. Confirm the plan's Status line says no phase has
> started, then create `feature/field-geo-g1-placement` off up-to-date `main`.
>
> Scope is the plan's §1 exactly, nothing more — no UI this phase: a new pure
> `frontend/src/utils/labelPlacement.js` (`placeWithNudges`, exported constants for the nudge
> ladder and paddings) and `kmPerPx` added to `frontend/src/utils/heatField.js` beside
> `proj`/`centroid`. The algorithm is the deliverable of the whole handoff — port it faithfully
> from the spec in §1.1 (cross-check against `docs/design/field-geography/plan-tab-v4.js`
> `placeLabels`, lines ~26–41); resist all "improvements". Do not touch `WindowRowFieldMap`'s
> existing `fits` — the plan's §1.1 records why they stay separate. Tests per §1.3, including
> the boundary and asymmetric-padding cases.
>
> Run the frontend gate, then the adversarial review per CLAUDE.md § *UI Work — Review Cadence*
> (prosecutor lenses over the diff, one refuter per charge defaulting to REFUTED, synthesis;
> agents read-only), fix survivors, re-run the gate. Commit conventionally with a `CHANGELOG.md`
> entry and the plan's Status line + G1 row updated **in the same commit**. Do not push.

---

## G2 · Plan thumbnails: home marker + area names

> You are implementing **Phase G2** of
> `docs/engineering/field-geography-and-glyphs-plan.md`. Read the plan in full, re-verify its
> cited line numbers, and confirm the Status table shows G1 merged (if it does not, stop and say
> so). Create `feature/field-geo-g2-thumbs`.
>
> Scope is §2 plus the design bundle's Screen 1 section
> (`docs/design/field-geography/README.md` §Screen 1) for visual reference: plumb `homeCoords`
> from `App.jsx` through `WindowFirstShell` to `WindowFirstHeatStrip`; add the `.wf-tlab`
> overlay inside the existing `.wf-hc-cv` well; `card.hotRegionName` is the **existing**
> `topRegion(es)?.regionName` reused in `utils/windowFirstCards.js` AND carried through
> `buildHeatStripCards`' whitelist fold in `utils/windowFirstStrip.js` with its own fold test —
> §2.3 explains why the component tests cannot catch a dropped fold field (never a fresh argmax,
> never recomputed from heat spots; architecture rule, not a preference); area-name tables with
> the §2.4 fallbacks + dev-mode warning (verify the map's keys against the seeded roster's
> region names first, knowing that check is local-only); anchors in one per-card state object
> set from the paint pass, placed via G1's `placeWithNudges` with home first (§2.5); CSS in
> `index.css` per §2.2 with `--color-home` in the **`@theme static`** block, no same-value
> fallback. New styles go in `index.css` beside their `.wf-*` siblings — not Tailwind arbitrary
> classes. Tests per §2.7 exactly — its stub-leakage, fixture-coordinate and collision-height
> mechanics are review findings, not suggestions. Re-measure and update the strip's documented
> long-task figure (§2.5).
>
> Run the frontend gate, then the adversarial review (read-only agents), fix survivors, re-run.
> Then verify in the browser: backend
> `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port 8083), `npm run
> dev`, sign in `admin`/`golden2026`; a fresh DB has no ratings so seed first (the heat-field
> handoff memory records the working seed recipe) — check the home marker, area names, the hot
> highlight, the tiny-name switch at narrow widths, and that an away origin drops the marker.
> State which claims were seen and which were only tested. Commit with CHANGELOG + Status/G2 row
> in the same commit. Do not push.

---

## G3 · Popup field: reach rings + home marker

> You are implementing **Phase G3** of
> `docs/engineering/field-geography-and-glyphs-plan.md`. Read the plan in full, re-verify its
> cited line numbers, confirm the Status table shows G2 merged. Create
> `feature/field-geo-g3-rings`.
>
> Scope is §3 (design reference: bundle README §Screen 2, `plan-tab-v4.js` `drawBig`): pass
> `homeCoords` into `openField` → `WindowSheetDialog` → `WindowRowFieldMap` as `homePoint`; the
> new `.wf-mgeo` sibling layer (rings SVG, ring labels, home marker) — **not** inside
> `.wf-mlab`, whose universal span rule would centre-shift and plate everything (§3.3's
> layering warning); ring radii from `kmPerPx` with the §3.2 skip rules; ring labels through
> `placeWithNudges` sharing the field's existing box list — normalise the two box shapes first
> (§3.3) — labelled by `formatDriveDuration(45)`/`formatDriveDuration(90)`, imported, never
> literal strings; home marker placed after rings, before region labels; region labels become
> droppable **only when homePoint produced boxes**, on the chips' two-pass treatment (§3.3
> step 4 — a deliberate behaviour change: pin both sides with tests AND rewrite the stale
> never-dropped comment at the pass in the same commit). Keep the field's one-piece-of-state
> invariant and the hint-corner reservation; every new element is `pointer-events:none`. Gating
> per §3.1: home origin + saved postcode only. The PR description must present the §5.2 owner
> flag in full — the LITE incoherence and the km-vs-minutes divergence by name, not just "role
> gating: owner call". Tests per §3.5 exactly — its worked projection scales are the difference
> between the ring tests being writable and not; do not substitute the default ×10 stub.
>
> Run the frontend gate, adversarial review (read-only), fix, re-run. Browser-verify on port
> 8083 with seeded data, desktop AND a 390px phone viewport: rings under the labels, labels
> never on a chip, the centroid click passing through the new layer, away origin drops
> everything, small popup drops the inner ring. Commit with CHANGELOG + Status/G3 row. Do not
> push.

---

## G4 · Coming up topic glyphs

> You are implementing **Phase G4** of
> `docs/engineering/field-geography-and-glyphs-plan.md`. Read the plan in full, re-verify its
> cited line numbers, confirm the Status table shows G3 merged. Create
> `feature/field-geo-g4-glyphs`.
>
> Scope is §4 (design reference: bundle README §Screen 3, `Coming Up.html`): new
> `utils/comingUpGlyphs.js` exactly per §4.1 — the family/type/chip maps are decided, including
> the eclipse `◐`, aurora `🌌` and air-dust `🏜️` calls (§5.7; do not re-litigate); insertion
> points per §4.2: on condition rows (the `.wf-cond-fam` wrapper) and filter chips the glyph
> goes `aria-hidden` **after** the colour swatch — glyph and swatch are redundant on purpose,
> remove neither — while the timeline card has **no swatch element** (its colour is a
> `data-family` border accent): there the glyph is the title's first child. The coincidence
> sub-line is **conditional scope**: §4.5 — if Coming-up P3b (PR #690) has merged, add the
> sub-line glyph per that section; if not, pointer comment only and say so in the PR.
> `ComingUpConditionOccurrence.label` (P3b's unrendered field) is explicitly out of scope
> (§5.10). Mind `WindowComingUpEntry`'s accessible-name rule: every top-level section is
> separated by a bare `{' '}` text node, and your glyph must not glue the title to anything.
> Tests per §4.4 — note its completeness pin derives the family universe from live exports, not
> a literal copy alone.
>
> Run the frontend gate, adversarial review (read-only), fix, re-run. Browser-verify the Coming
> up tab on port 8083: glyphs on rows, conditions and chips; condition columns unshifted;
> filter-chip `all` bare. Commit with CHANGELOG + Status/G4 row, and mark the plan's Status line
> complete. Do not push.
