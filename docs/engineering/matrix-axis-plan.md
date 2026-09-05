# Plan — naming the matrix axis (design bundle `design_handoff_matrix_axis`)

**Status: planned, not started.** Two phases, one PR each, implemented by Claude Sonnet sessions.
Every decision an implementer would otherwise have to make is made in this document; where a value
must be measured rather than chosen, the step says so and says with what instrument. **This plan
was adversarially reviewed before landing** (five prosecutor lenses — sticky/CSS mechanics,
codebase breakage, test adequacy, design fidelity, handoff completeness — every confirmed charge
folded in; §10 records the review so its refuted charges are not re-raised).

The design bundle (`design_handoff_matrix_axis`, from the owner's `PhotoCast.zip`) is **vendored
at `docs/design/matrix-axis/`** — the house pattern from `docs/design/field-geography/` (#691).
Its README is the spec of record for *intent*; **this document is the spec of record for
*implementation*** — every value a phase needs is transcribed inline (verified
character-by-character against the prototype by the fidelity lens), so a session should never
*need* to open the prototype, but it can. Wherever the two disagree, the disagreement is
deliberate and recorded in §4.

## §1 What the change is

The Plan tab's "The days ahead" strip is a matrix — a column per day, sunrise on the top row,
sunset beneath — and nothing in the layout says so. Three deltas make the axis explicit:

1. **Row rails** (desktop/tablet only): a full-width `SUNRISE` / `SUNSET` band across the top of
   each row — dawn-blue and coral labels, each followed by a 1px rule fading out across the days.
2. **Card chips**: the per-card `SUNRISE`/`SUNSET` caption (`.wf-hc-sun`, currently plain
   secondary-ink mono text) becomes a tinted chip in the same two colours, so a card read alone —
   or on the phone, where the rails are dropped — still states which end of the day it is.
3. **Sticky headings** (desktop/tablet only): the day-tile row pins under the lens bar while the
   strip is in view; each row's rail pins under the day tiles for exactly as long as its own cards
   are on screen, then leaves with them.

Everything else on the tab — data, popup, lens bar, masthead, regional planner door, footer,
movement line — is untouched. No new state, no API change, no backend change.

## §2 Codebase ground truth (verified 2026-08-30, main @ `768f8af3`)

Facts the phases depend on. Re-verify only if the file has changed since; do not re-derive.

- **The strip is ONE grid with explicit placement**, not rows:
  `frontend/src/components/WindowFirstHeatStrip.jsx` renders a single `.wf-hstrip` div
  (`data-testid="wf-heat-grid"`, `--dc` = column count, `attachFrame` ref) whose direct children
  are, **per day in day order**: a `.wf-hday` header, then the sunrise cell, then the sunset cell.
  Cells carry `--c`/`--r` inline custom properties; `index.css` places them via
  `.wf-hstrip > * { grid-column: var(--c); grid-row: var(--r); }` (~:1006–1021). Rows come from
  `MATRIX_ROW = { header: 1, sunrise: 2, sunset: 3 }` in
  `frontend/src/utils/windowFirstMatrix.js:46`.
- **The phone transpose is pure CSS, day-major, and depends on that DOM order**:
  `@media (max-width: 639px)` (~:1937–1959) reflows the same markup into a 2-column grid — day
  header spans both columns, `.wf-hgap` empty cells are `display: none`, a `.solo` card spans the
  row. ⚠️ The media block sets only `grid-template-columns`/`gap` and the direct-child rules; the
  `display: grid` itself lives on the **unconditional base rule** at ~:1006 — this matters for
  D4's specificity guard, which must beat the base rule at *every* width, not only in a transient.
  There is **no** viewport hook in the strip component today.
- **`useIsMobile()` exists** (`frontend/src/hooks/useIsMobile.js`), keyed on
  `(max-width: 639px)` — the *same* breakpoint as the phone media block, so a JS branch and the
  CSS flip together. It is already used by `WindowFirstLensBar`, `WindowSpotStrip`,
  `MapView`, `WindowRowFieldMap`. `src/test/setup.js:46` documents the house convention for
  testing its branches: *mock the hook itself*, as the MapView suites do.
- **Sunrise vs sunset** is `card.sunrise` (boolean, set from `targetType === 'SUNRISE'` in
  `windowFirstStrip.js`). The caption is rendered at `WindowFirstHeatStrip.jsx` ~:781:
  `<span className="wf-hc-top" aria-hidden="true"><span data-testid="wf-heat-sun"
  className="wf-hc-sun">{card.sunrise ? 'SUNRISE' : 'SUNSET'}</span></span>`. The whole caption is
  `aria-hidden`; the card's accessible name is a single `sr-only` span that already names the event.
- **Sticky chrome today**: `.wf-lens` sticky `top: var(--safe-t)` (identical to `top: 0` wherever no safe-area inset is reported — a sticky element sticks to its scrollport, so the root's own inset padding cannot reach it), z-index **20** (⚠️ not the prototype's 30),
  and it is the pane's ONLY sticky element.
  > ⚠️ **Superseded, 2026-09-05.** This section described `.wf-mast` as sticky `top: 0` at z-index
  > **45** with `.wf-lens` resting on `top: var(--wf-mast-h, 128px)`, and D8/D9 below both add a
  > `--wf-mast-h` term for the same reason. The masthead's stick never took effect — its containing
  > block is the shell's `WRAP_MAX_WIDTH` wrapper (masthead + tab bar + tab rule, ~46px taller than
  > the band), and a sticky element cannot leave its containing block. Measured in Chromium at
  > 1280×800: the band pinned for those 46px, then left with the page (`bottom: -397` by 600px of
  > scroll), while the bar and both rails went on sticking against chrome that had gone — the bar
  > hovering a masthead's height down the viewport with cards scrolling through the band above it.
  > `.wf-mast`'s stick and `--wf-mast-h` are both deleted; every `top` and reservation below loses
  > that term. The band keeps `position: relative; z-index: 45` — a stacking context rather than a
  > stick, and load-bearing: the admin health panel renders inside it at `z-index: 9999` and escapes
  > over every dialog without it. See `index.css`'s `.wf-mast` block for the checklist a phase pinning the masthead
  > for real would have to work through. `useLensReserve`
  (ResizeObserver on `.wf-shell`, re-observing `.wf-mast` and `.wf-lens`, **measuring
  synchronously on attach** and again per observation) writes `--wf-mast-h` and
  `--wf-lens-reserve` (= mast + bar + 6px ring allowance) onto `.wf-shell`; its `written` ref is
  seeded with exactly those two keys and its unmount cleanup enumerates them (~:77, :136–137).
  Resting browser measurements recorded in `index.css` ~:548–571: masthead 128px desktop / 134px
  phone; lens bar **53.5px** desktop at full width (⚠️ it wraps to ~91px in the 640–~780px band —
  see D11); reserve 188px / 270px. **Fallback literals are never 0** — a settled rule with its
  reasoning in that comment block.
- **The lens bar is conditionally mounted** (`WindowFirstShell.jsx` ~:1279–1291, on resolved
  lenses); the sticky suite constructs the barless state even though `useReachLens` never returns
  null in production. D11 decides what `--wf-lens-h` means in that state.
- **z-index inventory on the Plan tab**: masthead 45, lens 20, hovered heatmap cell 10 (inside a
  door panel the strip's pinned layers can never vertically overlap), focused spot card 3, every
  `Modal` (popup, sheet, search) Tailwind `z-50`, `.wf-peek` (portalled to body) 60,
  `MapOverlay` 200. Stacking contexts inside the strip: a hovered card's `translateY` transform,
  and **every away card always** (`.wf-hc-away { opacity: 0.78 }`) — all at z-auto, below the new
  layers. The pick legend (`.wf-hc-lg`, absolute `top: -8px`, z-auto inside its card) therefore
  paints *under* a pinned rail/tile row, which is the correct occlusion.
- **Sticky ancestry is clean** (verified): body → `div.min-h-screen.bg-plex-bg` → `main` →
  `.wf-shell` → `.wf-body` → `section.wf-hstrip-block` → `.wf-hstrip` carries no `overflow`,
  `transform`, `filter`, or `container-type`. `.wf-body` gains `opacity-50` while a dialog dims
  the page — a stacking context that collapses the strip's layers to auto, still below lens/mast:
  ordering preserved in both states.
- **Scroll reserve**: `.wf-hc` (among others) has
  `scroll-margin-top: var(--wf-lens-reserve, 188px)` (~:2712) —
  `WindowFirstShellSticky.test.jsx` asserts that selector list as stylesheet text, slicing from
  `lastIndexOf('scroll-margin-top: var(--wf-lens-reserve')`; D12's new rule starts
  `scroll-margin-top: calc(` so it cannot disturb that slice.
- **Tokens** live in `index.css` `@theme` (~:3–47) / `@theme static` (~:68+). The app's names for
  the design's tokens: `--bg` → `--color-plex-bg: #181210` (exact match), `--coral` →
  `--color-plex-coral: #E8593F` (exact match), `--panel` → `--color-plex-panel: #1E1712` (exact
  match), `--ink-3` → `--color-plex-text-muted` (⚠️ repeatedly rejected on contrast; the caption
  currently uses `--color-plex-text-secondary`). **No `--dawn` exists anywhere.** New tokens
  referenced only from hand-written CSS must go in **`@theme static`** or Tailwind v4 prunes them
  (comment at ~:61) — and a whole-file declaration grep cannot tell the two blocks apart (§5.4).
- **The page behind the strip is `--color-plex-bg`** (`@apply bg-plex-bg` on body, `App.jsx:332`;
  neither `.wf-body` nor `.wf-hstrip-block` sets a background), so the design's fade-to-`--bg`
  gradients translate token-for-token.
- **`color-mix()` is established** (12 sites); the house rule is *mix against the token, never a
  hardcoded rgba literal* (~:1714–1720). **IBM Plex Mono 600 is already loaded** (`fonts.js`).
- **Card `on` (open) state** has three markers today: gold background wash (~:1198), gold border
  (moved after the pick rules, ~:1583), and a caption recolor `.wf-hc.on .wf-hc-sun` (~:1233 —
  no test pins it; D15 deletes it).
- **The shell focuses "the matrix's first card" in two places** —
  `applyConflictAction` (~:1003) and `onPlanFrom` (~:1690), both
  `document.querySelector('button[data-testid="wf-heat-card"]')`, documented as "what the reader
  has just been shown". DOM order decides which card that is; D20 owns the consequence.
- **Heat-field internals survive the restructure** (verified, do not re-litigate): canvas sizing
  measures each card's own well (`well.clientWidth`, ~:550); geography labels position against
  `.wf-hc-cv`, which is `position: relative`, so the offsetParent is inside the card; no live CSS
  uses sibling/nth-child combinators on strip children. But `useHeatCanvas`'s paint effect re-runs
  only on its nonces, and `attachFrame` seeds `lastSizeRef` from the node's current width — the
  branch-flip repaint therefore rides the resize/RO events that accompany the viewport crossing
  (§6.6 verifies it; the sanctioned fix if a flip ever leaves a blank matrix is bumping the
  observer nonce on branch change, not a rewrite).
- **Test environment**: Vitest + jsdom with `css: false` — jsdom parses no CSS and evaluates no
  `@media`; `setup.js` stubs `matchMedia` (fixed `matches: false`, so **every existing shell/strip
  test renders the desktop branch**) and a **no-op** `ResizeObserver`.
  `WindowFirstShellSticky.test.jsx` stubs a *running* RO with a **class-keyed
  `getBoundingClientRect` mock** for the measurement half and reads `index.css` **as text** for
  the stylesheet half. Assert classes/attributes/CSS text in jsdom; measure pixels in a real
  browser (Playwright headless Chromium — the Browser pane's document is
  `visibilityState: 'hidden'`, which suspends RO/IO and rAF).

## §3 Decisions (all made; none left to the implementer)

| # | Decision | Value | Why |
|---|---|---|---|
| D1 | Desktop structure | The prototype's three-block structure: `.wf-dhrow` (day tiles, `data-testid="wf-heat-dhrow"`) + two `.wf-hrow` blocks (`.wf-rail` + `.wf-hcards`, each `.wf-hcards` carrying `data-testid="wf-heat-cards"`), because a rail can only travel over its own cards if its containing block wraps rail + cards — a single grid confines a sticky item to its own row track, so the row-scoped pinning is impossible without the restructure. Order within the container: `.wf-dhrow`, then the `am` row, then the `pm` row — the sticky stack presumes tiles first, and §6.4 pins it. | Sticky containment is a CSS fact, not a preference (verified against the proposed markup by the mechanics lens). Testids per the standards' rule that structural containers with no role get them. |
| D2 | Phone structure | **Unchanged, byte for byte**: the existing single-grid day-major markup and the existing `@media (max-width: 639px)` block. Rails and day-tile row are not rendered on the phone; nothing in the strip is sticky there. | The bundle says so; the existing phone CSS already implements the bundle's phone layout exactly (2 columns, header spans, gaps hidden, solo spans, heading `margin-top: 4px`). |
| D3 | How the two structures coexist | A JS branch on the existing `useIsMobile()` hook — one structure rendered at a time, both built from the same `buildWindowMatrix` output, sharing `renderCard`/`renderEmpty`/`renderDayHeader` unchanged. **Not** CSS-only (no CSS can reorder row-major DOM into day-major flow), **not** double-render-and-hide (doubles the heat-field canvases; a hidden canvas burns its `useHeatCanvas` retry budget at `clientWidth` 0). The day-header JSX (the ~25-line block including its "Today" comment) is **extracted** into a `renderDayHeader(day, column)` helper returning the keyed (`key={day.date}`) `.wf-hday` div, called from inside the day loop on phone and from the `.wf-dhrow` map on desktop — it cannot be both "moved" and "left in place", so it is shared. | The bundle's own instruction ("render the two structures from the same window list"); the hook's 639px query equals the media block's, so JS and CSS flip together. This deliberately retires M1 task 5's "same markup, flowed not placed" rule for this component — recorded here so it reads as a decision. |
| D4 | Desktop container classes | `<div className="wf-hstrip wf-hstrip-rows">` keeping `data-testid="wf-heat-grid"`, the `attachFrame` ref and `--dc` on it in **both** branches. New rules are written `.wf-hstrip.wf-hstrip-rows { … }` at (0,2,0) so they beat the **base** `.wf-hstrip { display: grid; … }` (~:1006, (0,1,0)) at every width — note the phone media block itself declares only track/gap overrides, and its direct-child rules (`.wf-hstrip > .wf-hday` etc.) cannot match the rows DOM at all, so both transient directions are closed: desktop DOM at phone width holds its flex layout; phone DOM at desktop width lands in the base grid where the still-emitted `--c`/`--r` place it correctly. | Test continuity (the testid/`--dc` anchors survive); the specificity guard is load-bearing **always**, not only during the flip window. |
| D5 | Cell placement inside the row grids | Cells keep emitting `--c`/`--r` in both branches (render functions stay branch-free); the rows mode consumes only `--c`: `.wf-hstrip.wf-hstrip-rows .wf-dhrow > *, .wf-hstrip.wf-hstrip-rows .wf-hcards > * { grid-column: var(--c); }`. `--r` is inert in rows mode (row is structural) and still consumed by the base/phone rules — which also harmlessly hit the new direct children (`--c` unset → auto) under `display: flex`. | Natural flow would place correctly today (every day emits exactly one cell per row, including away days and wordless holes), but explicit `--c` keeps columns honest if a future change ever skips a cell. |
| D6 | Grid track definitions | `.wf-hstrip.wf-hstrip-rows { display: flex; flex-direction: column; gap: 7px; }`; `.wf-hstrip.wf-hstrip-rows .wf-dhrow, .wf-hstrip.wf-hstrip-rows .wf-hcards { display: grid; grid-template-columns: repeat(var(--dc, 4), minmax(0, 1fr)); gap: 8px; align-items: stretch; }`; `.wf-hrow { display: flex; flex-direction: column; gap: 7px; }`. `--dc` stays on the container and reaches the inner grids by custom-property inheritance. **`minmax(0, 1fr)` is load-bearing** — plain `1fr` lets the chip's min-content width overflow the track on narrow viewports (bundle §1) — and §6.4 pins the string. | Prototype values, verbatim. |
| D7 | Rail markup | `<div className={`wf-rail ${am ? 'am' : 'pm'}`} data-testid="wf-heat-rail" aria-hidden="true"><span>{am ? 'Sunrise' : 'Sunset'}</span><span className="wf-rail-rule" /></div>` — a div, not a button (the bundle: "nothing is clickable in the rails — they are labels, not filters"). `aria-hidden` because every card already self-describes its event in its `sr-only` accessible name; the rail is a visual restatement, and announcing it as well would double-speak the axis (same reasoning as the existing `aria-hidden` on `.wf-hc-top` and `.wf-hday-rule`). | WCAG 1.3.1 is satisfied programmatically per card already. |
| D8 | Rail CSS | Per the bundle, with app tokens: `.wf-rail { position: sticky; top: calc(var(--wf-mast-h, 128px) + var(--wf-lens-h, 54px) + var(--wf-dh-h, 45px) - 2px); z-index: 14; display: flex; align-items: center; gap: 9px; padding: 4px 2px 3px; background: linear-gradient(180deg, var(--color-plex-bg) 0 70%, transparent); font-family: var(--font-mono); font-size: 9.5px; font-weight: 600; letter-spacing: 0.16em; text-transform: uppercase; line-height: 1; }`; `.wf-rail.am { color: var(--color-plex-dawn); }`; `.wf-rail.am .wf-rail-rule { background: linear-gradient(90deg, color-mix(in srgb, var(--color-plex-dawn) 42%, transparent), transparent); }`; `.wf-rail.pm { color: var(--color-plex-coral); }` + same rule pattern with `--color-plex-coral`; `.wf-rail-rule { flex: 1; height: 1px; }`. **Amended at Phase 2 (A-14): the fade cutoff is `0 85%, transparent`, not the bundle's `0 70%`** — measured contrast failure (coral text 4.02:1) at the literal value, since the text's own bottom edge sat inside the fade. | Bundle values; token names per D14. |
| D9 | Day-tile row CSS | `.wf-dhrow { position: sticky; top: calc(var(--wf-mast-h, 128px) + var(--wf-lens-h, 54px) - 1px); z-index: 15; padding-top: 9px; background: linear-gradient(180deg, var(--color-plex-bg) 0 82%, transparent); }` (grid definition per D6). The `- 1px` closes the hairline against the lens bar's bottom border (the bar painting over the tiles' top 1px also keeps its border visible — `heightOf` rounds, and the overlap absorbs the sub-pixel error); the fade must be the **page** background (`--color-plex-bg`), not the panel. | Bundle values. |
| D10 | z-index for the new layers | **15 (tiles) / 14 (rails) — a deliberate deviation from the prototype's 22/20.** The prototype's lens bar is z-30; the codebase's is z-20, so 22/20 would paint the tiles *over* the lens bar as they slide under it. Constraint order preserved: masthead 45 > lens 20 > tiles 15 > rails 14 > every stacking context inside the strip (hovered-card transforms and the always-on `.wf-hc-away` opacity context, all z-auto; the absolute pick legend paints under a pinned layer, which is the correct occlusion). Dialogs (z-50), `.wf-peek` (60) and `MapOverlay` (200) stay above. Do **not** raise `.wf-lens` to 30 — `WindowFirstShellSticky.test.jsx` pins `z-index: 20` as stylesheet text and nothing needs the renumbering. | §4 row A-1; inventory verified by the mechanics lens. |
| D11 | Sticky offset plumbing | Two new measured properties. (a) **`--wf-lens-h`** — the lens bar's own height, written by `useLensReserve` beside its existing writes; **when the bar is absent, write a measured `0px`, do NOT clear to the fallback** — this deliberately differs from `--wf-lens-reserve`'s clear-on-absent discipline, because over-reserving a scroll-margin is harmless while a 54px sticky `top` with no bar leaves the tiles floating 53px below the masthead with cards scrolling through the naked band above them. (A *measured* zero is not the banned zero *fallback text*.) Seed the hook's `written` ref with the third key and add the third `clear()` to the unmount cleanup — today's two-key enumeration would only clear it by accident. Fallback literal in the calcs: **54px** (the browser-measured 53.5 at full width, rounded up). ⚠️ Accepted cost, stated rather than borrowed from the overshoot rule: in the 640–~780px band the bar rests *wrapped* (~91px), so until the RO's first write the tiles' fallback position sits ~37px under the z-20 bar — one frame, the same resting-height basis every existing fallback uses. (b) **`--wf-dh-h`** — the day-tile row's rendered height (including its 9px padding-top, matching the prototype's `offsetHeight` basis), measured by a ResizeObserver inside `WindowFirstHeatStrip` attached via a **state-carrying callback ref** on `.wf-dhrow` (the node exists only in the desktop branch and arrives/leaves on viewport flips — a plain `useRef` + effect misses its arrival; M3 lesson 1, `plan-matrix-plan.md`). The effect **measures once synchronously on attach** (the `useLensReserve` shape — the RO covers subsequent changes), writes onto the section via `node.closest('.wf-hstrip-block')`, and on cleanup disconnects and removes the property. A measured `0px` while the pane is hidden (`hidden` attribute, strip not unmounted) is inert and self-corrects on return — distinguish that from the forbidden zero *fallback*. Fallback literal **45px** — measured in the live app at Phase 2 verification (36px tiles + 9px padding-top, 1280px, 2026-08-30; the plan's provisional 49px estimate re-measured per §6.6). **Neither fallback literal may be 0**, and §6.4 asserts their absence. | Extends the existing measurement architecture instead of duplicating it; the one place its discipline does *not* transfer (clear-on-absent) is called out and decided the other way. |
| D12 | Scroll reserve for strip cards under the pinned tiles | New desktop-scoped rule: `.wf-hstrip.wf-hstrip-rows .wf-hc { scroll-margin-top: calc(var(--wf-lens-reserve, 188px) + var(--wf-dh-h, 45px) + 17px); }` — 17px is the rail's fixed height **16.5px (9.5px line-height-1 text + 4px + 3px vertical padding; it cannot wrap) rounded up to 17, overshoot being the harmless direction** — the CSS comment must carry exactly that derivation, rounding included. Without this rule, a keyboard-focused card scrolls up underneath the newly pinned tiles + rail — the exact defect class `--wf-lens-reserve` exists to prevent, one layer down. The existing base rule and its selector-list text assertions are untouched (additive, more specific, and the new rule's `calc(` prefix cannot match the sticky test's `lastIndexOf` slice). | Found in planning; the prototype has no focus management so it never saw it. Net clearance overshoots by ~2.5px (the reserve already carries the 6px ring) — harmless direction. |
| D13 | Chip CSS | `.wf-hc-sun` keeps its element, testid and words; gains a modifier class from `card.sunrise` (`am`/`pm`) and becomes the chip: base gains `padding: 3px 6px; border-radius: 4px; white-space: nowrap; min-width: 0; overflow: hidden;` and letter-spacing moves 0.09em → **0.11em** (bundle value); size stays 9px mono 600 uppercase line-height 1; the base `color: var(--color-plex-text-secondary)` **stays**, with a one-line comment that it is the unreachable-today no-modifier fallback (legible, not invisible). `.wf-hc-sun.am { color: var(--color-plex-dawn); background: color-mix(in srgb, var(--color-plex-dawn) 15%, var(--color-plex-panel)); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-plex-dawn) 30%, transparent); }`; `.wf-hc-sun.pm { color: var(--color-plex-coral-bright); background: color-mix(in srgb, var(--color-plex-coral) 15%, var(--color-plex-panel)); box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-plex-coral) 30%, transparent); }`. Phone block gains `.wf-hstrip .wf-hc-sun { letter-spacing: 0.04em; padding: 2px 4px; }` **inside the existing 639px media block** — this is what keeps the card's min-content width inside its 2-column track. `color-mix` in **srgb**, matching the bundle's measured colours (the oklch sites in the file mix different tokens for different reasons). **Amended at Phase 1 (A-12): the washes mix against `--color-plex-panel`, not the bundle's `transparent`** — measured 4.01:1 (am) / 4.06:1 (pm) on hovered verdict tints, a state the flat prototype has no equivalent for; panel-mixed is pixel-identical at rest and opaque, so hover-, tint- and `on`-wash-immune. The ring keeps `transparent` (a 1px line has no 4.5:1 duty). | Bundle values; house token rule (mix against tokens, no rgba literals). |
| D14 | New tokens | Two, both in **`@theme static`** (not plain `@theme` — referenced only from hand-written CSS, they would be pruned there, and §5.4's test asserts the block, not the file): `--color-plex-dawn: #8FA8C4;` (rail + chip sunrise) and `--color-plex-coral-bright: #EE8064;` (chip sunset **text only** — the bundle lightens the coral for contrast on the 15% wash; wash and ring still mix from `--color-plex-coral`). House naming (`--color-plex-*`). | Bundle §Design-tokens; `index.css` ~:61 pruning note. |
| D15 | The `on`-state caption recolor is **deleted** | Remove `.wf-hc.on .wf-hc-sun { color: var(--color-close-to-home); }` — at (0,3,0) it would out-specify the new `.wf-hc-sun.am`/`.pm` (0,2,0) and gold-recolor a dawn-blue chip. The chip's tint is the axis signal and must not change with open state; the open card keeps two stronger markers (gold wash ~:1198, gold border ~:1583), and the prototype recolors only the day number, never the caption. No test pins the deleted rule. | Recorded deviation (§4 A-5). |
| D16 | Away cards keep the chip | An away cell renders the same `.wf-hc-top` caption today and keeps the chip, dimmed by the existing `.wf-hc-away { opacity: 0.78; }`. Expected to pass (≈5:1 for the dawn chip). Phase 1 step 6 measures it; **if either chip ink composites below 4.5:1, the decided fix is raising the away-cell opacity until both chips and the two strings its comment block documents all clear 4.5:1** — a child can never be excluded from ancestor opacity, and tokens are not lightened. **Executed at Phase 1 (A-13): 0.78 measured 4.03:1 (am) / 3.95:1 (pm) inside an away cell; opacity raised to 0.87** (chips 4.64/4.57:1, secondary text 5.50:1). | Consistency with the away cell's "state + sun time are almanac" rule; the 0.62→0.78 history in that rule's comment is the precedent for the fix direction. |
| D17 | Phasing | **Phase 1: tokens + chips** (no structural change, ships alone, visibly complete on phone and desktop). **Phase 2: rows restructure + rails + sticky + measurement + scroll reserve.** Phase 2 depends on Phase 1's tokens; Phase 1 has no dependency. One PR per phase, each through the full UI review cadence (build → tests → adversarial review of the diff → fix → browser verification → commit). | Risk isolation: Phase 2 is where all the structural/sticky risk lives. |
| D18 | Copy & rendering of rail labels | Rail source text `Sunrise` / `Sunset` (title case) uppercased by CSS — matching the prototype exactly. The chip keeps the app's existing literal `SUNRISE`/`SUNSET` strings (already pinned by test; changing them buys nothing) — a recorded deviation, §4 A-9. | Fidelity where it's free; zero test churn where it isn't. |
| D19 | No new props, no PropTypes changes | The branch, the RO and the rails are all internal to `WindowFirstHeatStrip`. `useLensReserve`'s signature is unchanged. | Smallest surface. |
| D20 | The shell's "focus the first card" destination changes, and that is accepted | `applyConflictAction` and `onPlanFrom` focus the first `button[data-testid="wf-heat-card"]` in DOM order. Row-major DOM makes that **tomorrow's sunrise** rather than **tonight's sunset** in the ordinary afternoon state (today's morning is a gap div). Accepted: the first button is still the visually top-left card — "what the reader has just been shown" — under the new layout; the two shell comments documenting the old chronology (~:984–988) must be updated to say "first card of the sunrise row". No covering test distinguishes the two (both existing fixtures are single-card). | Preserving the old destination would need the shell to know the strip's internal ordering — coupling in the wrong direction for a focus nicety. |

## §4 Deviations from the bundle, on purpose

| # | Bundle says | We do | Why |
|---|---|---|---|
| A-1 | Tiles z-22, rails z-20 (under a z-30 lens bar) | Tiles 15, rails 14 (under the app's z-20 lens bar) | The app's chrome numbering differs; only the *ordering* is normative. See D10. |
| A-2 | `--dawn`, `--coral`, `--bg` token names; `#EE8064` as a bare literal | `--color-plex-dawn`, `--color-plex-coral`, `--color-plex-bg`, and the literal promoted to `--color-plex-coral-bright` | House naming; house no-bare-literal rule. All hex values are identical — renaming, not recoloring. |
| A-3 | `--mastH`/`--lensH`/`--dhH` written onto a scroll container by a `measureMast()` + debounced resize + `fonts.ready` + transitionend | `--wf-mast-h` (exists), `--wf-lens-h` (new, from the existing `useLensReserve` RO), `--wf-dh-h` (new, strip-local RO via callback ref). An RO fires on font-swap- and layout-induced height changes, so the prototype's trigger set is subsumed. | The app already has a measurement architecture; a second one would drift from the first. |
| A-4 | Prototype re-renders the whole strip on viewport change via its own `isMob()` + view buttons | `useIsMobile()` (matchMedia listener) drives the branch | Same behaviour, the app's existing instrument. |
| A-5 | (no equivalent rule in the prototype) | Delete the app's `.wf-hc.on .wf-hc-sun` recolor | D15. |
| A-6 | Prototype's `.wrap.sideways` card layout appears in its CSS | **Out of scope** — carried over from v4's prototype; the bundle's README scopes this change to the three deltas and the app has no sideways mode | README §Scope: "Nothing else in the tab needs touching." |
| A-7 | (silent in the bundle) | New scroll-margin rule for strip cards under the pinned tiles | D12 — the prototype has no keyboard focus handling to expose it. |
| A-8 | Rails carry visible text with no ARIA treatment | `aria-hidden="true"` on the rail | D7 — every card already self-describes; two announcements of the same axis is noise. |
| A-9 | Chip copy is title-case `Sunrise`/`Sunset` uppercased by CSS | The chip keeps the app's literal `SUNRISE`/`SUNSET` strings (rails follow the prototype) | D18 — visually identical, zero test churn. |
| A-10 | Sticky-calc fallback literals `48px`/`44px`/`40px` | `128px`/`54px`/`45px` | The README's own rule — offsets are "measured, not hard-coded"; these are the app's measured chrome heights. |
| A-11 | (silent in the bundle) | The shell's first-card focus destination moves with the DOM order | D20. |
| A-12 | Chip washes mix against `transparent` | Mix against `--color-plex-panel` (found at Phase 1's browser contrast check — forced, not chosen) | Translucent washes let a hovered card's verdict tint bleed through: measured as low as 4.01:1, below the 4.5:1 floor. Panel-mixed is pixel-identical on a plain card and fully opaque, so every state behind the chip is irrelevant. See D13's amendment and the CSS comment beside the rules. |
| A-13 | (silent in the bundle) | `.wf-hc-away` opacity 0.78 → 0.87 | D16's pre-decided fix direction, executed: a full-cell opacity dims every string equally, so the chip's lower resting contrast set the floor (4.03/3.95:1 at 0.78). All three strings clear 4.5:1 at 0.87. |
| A-14 | Rail fade `0 70%, transparent` (D8) | `0 85%, transparent` | Found at Phase 2's browser contrast check (adversarial review's accessibility lens, then measured) — forced, not chosen. The rail's own text occupies roughly the box's 4–13.5px band inside its 16.5px height; at 70% the fade begins at ~11.55px, so the text's own bottom edge sat inside the fade and composited with whatever card was scrolling up underneath it. Measured with a saturated ramp-hot canvas (`#C82820`) scrolled directly beneath: `--color-plex-coral` text dropped to 4.02:1 at the text's own bottom row, below the 4.5:1 floor. At 85% the fade begins past the text's bottom edge (~14.03px), so the text zone measures fully opaque again (dawn 7.56:1, coral 5.24:1); only the remaining ~2.5px of bottom padding, where no glyph renders, still fades. Close to D9's own 82% cutoff for the same reason. See the CSS comment beside `.wf-rail`. |

## §5 Phase 1 — tokens + chips

**Branch** `feature/matrix-axis-p1-chips`, conventional commit `feat(plan): …`. Files:
`frontend/src/index.css`, `frontend/src/components/WindowFirstHeatStrip.jsx`, tests, `CHANGELOG.md`.

1. Add the two tokens (D14) to `@theme static`, with one-line comments naming their consumers.
2. In `WindowFirstHeatStrip.jsx` ~:781, add the modifier:
   `className={`wf-hc-sun ${card.sunrise ? 'am' : 'pm'}`}`. Nothing else in the component changes.
3. In `index.css`, inside the heat-strip block: restyle `.wf-hc-sun` per D13 (keep the existing
   contrast-documenting comment style — write the new measured ratios into the comment, replacing
   the stale 6.88:1 claim); add the `.am`/`.pm` rules; **delete** `.wf-hc.on .wf-hc-sun` (D15) and
   leave a one-line tombstone comment in the `on`-state block saying the chip owns the caption's
   colour now. Add the phone compaction to the existing 639px media block (D13).
4. Tests (all in `WindowFirstHeatStrip.test.jsx` unless said otherwise; follow
   `frontend-test-standards.md` — mock at the API-module boundary, `fireEvent`, assert
   classes/attributes never pixels):
   - The caption carries `am` on a sunrise card and `pm` on a sunset card — one of each in the
     same render, which kills both the swap and the constant mutants.
   - The words themselves stay pinned (existing test at ~:460, untouched **in this phase**; §6
     re-derives its order).
   - Stylesheet-text assertions (house pattern, `WindowFirstShellSticky.test.jsx` stylesheet-half
     style), **each scoped by slicing the block it belongs to, not by whole-file grep** —
     brace-depth slicing precedent in `mapChipFlipCascade.test.jsx`:
     * the two token declarations appear **inside the sliced `@theme static { … }` block**
       (a whole-file grep passes with the token in plain `@theme`, where Tailwind prunes it — the
       exact failure D14 legislates against; `MastheadTickLine.test.jsx:394` documents that its
       own guard cannot see pruning);
     * the `.wf-hc-sun` base block contains `padding: 3px 6px` and `border-radius: 4px`;
     * `.wf-hc-sun.am` contains `background: color-mix(in srgb, var(--color-plex-dawn) 15%` and
       `box-shadow: inset 0 0 0 1px`; `.wf-hc-sun.pm` likewise with
       `var(--color-plex-coral-bright)` for `color:` and `var(--color-plex-coral) 15%` for the
       wash — the token *names* alone are the member/non-member trap: pin the clauses;
     * the chip compaction (`letter-spacing: 0.04em`, `padding: 2px 4px`) appears **inside the
       sliced 639px media block** (outside it, the rule would break desktop);
     * `.wf-hc.on .wf-hc-sun` appears **nowhere**.
5. Gate: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`
   (all four — the audit step is the one nothing local runs by default and it has cost a CI round
   before).
6. Browser verification (recipe in §8): chips render in both colours on desktop and phone widths;
   contrast ≥ 4.5:1 measured (getComputedStyle + manual ratio, or devtools) for: `am` chip ink on
   its wash over the panel, `pm` chip ink likewise, both **on a hovered `vg` tint** (worst case),
   and both inside an away cell at opacity 0.78 (D16 — its fix direction is decided there).
   Record the measured ratios in the CSS comment. Screenshot the strip at 1280px and 390px.
7. Adversarial review of the diff (§7), fix survivors, re-verify, commit. CHANGELOG under
   `[Unreleased]`.

## §6 Phase 2 — rows, rails, sticky

**Branch** `feature/matrix-axis-p2-rails` off main **after Phase 1 merges**. Precondition check
(executable, not a log-subject guess): `grep -q -- '--color-plex-dawn' frontend/src/index.css` on
main must succeed; stop and say so if it does not. Files:
`frontend/src/components/WindowFirstHeatStrip.jsx`, `frontend/src/hooks/useLensReserve.js`,
`frontend/src/index.css`, tests, `CHANGELOG.md`, plus the two shell comments D20 names.

1. **`useLensReserve`**: add the `--wf-lens-h` write beside the existing `--wf-lens-reserve`
   write — **measured `0px` when the bar is absent** (D11(a), deliberately not the reserve's
   clear-on-absent), the third key in the `written` seed, the third `clear()` in the unmount
   cleanup. Update the hook's doc comment to name the third property, its consumer, and why its
   absent-bar semantics differ.
2. **`WindowFirstHeatStrip.jsx`**:
   - `const isMobile = useIsMobile();`
   - Extract `renderDayHeader(day, column)` (D3) — the diff should show the header JSX moving
     into it unchanged, "Today" comment included.
   - Phone branch (`isMobile`): today's exact markup — the day-major loop into the single
     `.wf-hstrip` grid, headers via `renderDayHeader`. Apart from the branch wrapper and the
     helper call, the diff on this path must be empty.
   - Desktop branch: `.wf-hstrip.wf-hstrip-rows` (D4) containing (a) `.wf-dhrow`
     (`wf-heat-dhrow`) mapping `renderDayHeader` over the days, (b) `.wf-hrow` = rail (D7) +
     `.wf-hcards` (`wf-heat-cards`) of each day's sunrise cell, (c) likewise for sunset.
     `renderCard`/`renderEmpty` are called with the same arguments as today (D5).
   - The `--wf-dh-h` ResizeObserver via state-carrying callback ref on `.wf-dhrow` (D11(b)):
     synchronous measure on attach, write via `node.closest('.wf-hstrip-block')`, disconnect +
     remove on cleanup.
   - Update the two shell comments per D20.
3. **`index.css`**, inside the heat-strip block: D6 (structure), D8 (rails), D9 (tiles), D12
   (scroll reserve). Each sticky rule carries a comment deriving its fallback literals the way the
   `.wf-shell` block does (D12's comment carries the 16.5-rounded-to-17 derivation verbatim), and
   the tiles/rail comments state the z-ordering constraint (D10) so the next renumbering can see
   it.
4. Tests:
   - **Branch mechanics**: mock the hook — `vi.mock('../hooks/useIsMobile.js')` +
     `useIsMobile.mockReturnValue(…)` — per `setup.js:46`'s documented convention and the
     `WindowRowFieldMap.test.jsx` precedent (~:43, :487–502). The flip test needs `rerender`, so
     extend the file's `renderStrip` helper to return it. (A static per-file matchMedia stub also
     works for one-branch tests — `WindowFirstLensBar.test.jsx`'s `asPhone` — but it cannot flip
     mid-test and would flip every other test in this large file; use the hook mock.)
   - **Desktop branch** (the default — setup's matchMedia stub is `matches: false`): the
     container keeps `wf-hstrip`, gains `wf-hstrip-rows`, keeps `--dc` and the testid;
     `wf-heat-dhrow` precedes both rails in DOM order (compareDocumentPosition); exactly one
     `wf-heat-rail` per row, `am` before `pm`, each `aria-hidden="true"`, labels
     `Sunrise`/`Sunset`; the dhrow contains every day header and the first/second
     `wf-heat-cards` contain exactly the sunrise/sunset cells (via `within()` on the testids —
     never `container.querySelector`); empty cells land in the right row's grid; an away day
     contributes a cell to both rows; the two empty-cell sentences still render.
   - **Phone branch** (hook mocked true): **no** rail, **no** `wf-hstrip-rows`, **no** dhrow; the
     container's direct children are in day-major order — pin the exact sequence (header, sunrise
     cell, sunset cell per day), because the phone CSS flows DOM order and this is the assertion
     that protects it.
   - **The order-assertion inventory — budgeted, not discovered.** Row-major DOM reverses
     `getAllByTestId` sequences wherever a fixture mixes sunrise and sunset (the suite's canonical
     fixture is today-SUNSET + tomorrow-SUNRISE, which flips). Every one of these is re-derived
     against row-major order **deliberately**, with its comment updated; day-major protection now
     lives in the phone-branch test above, so nothing is lost:
     * `WindowFirstHeatStrip.test.jsx` ~:460–469 (`['SUNSET','SUNRISE']` → reversed), ~:727–731
       (`--c`/`--r` per index — indices reorder), ~:785–789 (`.solo` indices), ~:1034–1037
       (`[scored, unrated]` destructuring inverts), and the phone-transpose test ~:1449
       (rewritten into the two branch tests above);
     * `WindowFirstShell.test.jsx` — `twoWindows()` fixtures: `openPopup(1)` (~:257) and the
       assertions at ~:377–401 and ~:813 flip index;
     * `WindowFirstShellRegion.test.jsx` — `openWindow(n)` (~:179–183, ~12 call sites) flips
       which window each index opens.
     After the sweep, grep both shell test files for any remaining `wf-heat-card` index
     assumption; `windowFirstMatrix.test.js` needs no change (the data model is untouched).
   - **`--wf-dh-h`**: with the running-RO pattern from `WindowFirstShellSticky.test.jsx`
     (~:85–128), extend the class-keyed `getBoundingClientRect` mock with `'wf-dhrow': 49` and
     assert the section's style is **`'45px'`** — never merely "a value was written", which
     passes at `0px`, the exact defect (a mock class absent from the map measures 0). Then flip
     the hook mock to true + `rerender`: the property is removed (the cleanup path, which a
     fresh-mount phone test can never exercise).
   - **`useLensReserve`** (`WindowFirstShellSticky.test.jsx`, JS half): `--wf-lens-h` written with
     the bar's measured height; written as `0px` (not cleared) when the bar is absent.
   - **Stylesheet half** (same file), each block sliced: `.wf-dhrow` contains `position: sticky`,
     `top: calc(var(--wf-mast-h, 128px) + var(--wf-lens-h, 54px) - 1px)`, `z-index: 15`, and
     `background: linear-gradient(180deg, var(--color-plex-bg)`; `.wf-rail` contains
     **`position: sticky`** (omit it and `top`/`z-index` are inert — every other assertion stays
     green), the D8 top calc, `z-index: 14`, and its gradient; the D6 track string
     `repeat(var(--dc, 4), minmax(0, 1fr))` appears in the rows-mode grid rule; the **full** D12
     string `scroll-margin-top: calc(var(--wf-lens-reserve, 188px) + var(--wf-dh-h, 45px) + 17px)`
     (existence alone passes on `0`); `--wf-lens-h, 0` and `--wf-dh-h, 0` appear **nowhere**; the
     existing assertions (lens z-20, `--wf-mast-h, 128px`, the base scroll-margin selector list)
     are untouched and still pass.
5. Frontend gate as Phase 1 step 5.
6. **Browser verification — Playwright headless Chromium** (`chromium.launch()` from `frontend/`,
   no `executablePath`; the Browser pane cannot verify sticky — RO/IO/rAF suspended). Against the
   local stack (§8), verify and screenshot:
   - Scroll sequence at 1280px: both rails visible → tiles pin under the lens bar (no hairline
     gap, no card bleeding over them — the fade gradient does its job) → sunrise rail pins under
     the tiles → sunrise rail leaves with the last sunrise card → sunset rail pinned → everything
     releases past the strip.
   - Computed `top` of `.wf-dhrow` equals mast + lens − 1 as measured; **replace the provisional `--wf-dh-h` fallback literal with the measured resting tile-row
     height** (and the test's string with it) if it differs — done: measured 45px in the live
     app at 1280px (2026-08-30), literals and test strings updated from the provisional 49px.
   - ~700px width (the wrapped-bar band): after first paint the tiles sit under the bar correctly
     (the RO write has replaced the 54px fallback — D11(a)'s stated one-frame cost).
   - Open the popup mid-scroll with tiles pinned: dialog paints above (z-50 > 15), Escape returns
     focus per the existing behaviour.
   - Keyboard-Tab to a card that is under the pinned tiles: it scrolls into view *below* them
     (D12).
   - Resize across 639px both directions: structure swaps, no crash, **canvases repaint** (the
     one mechanism the flip depends on — §2's `useHeatCanvas` note; if a blank matrix appears,
     the sanctioned fix is bumping the observer nonce on branch flip), nothing sticky remains on
     phone; back to desktop, `--wf-dh-h` is re-written (callback-ref proof).
   - Phone width: layout byte-identical to pre-change (compare against a main-checkout
     screenshot).
7. Adversarial review of the diff (§7), fix survivors, re-verify, commit. CHANGELOG under
   `[Unreleased]`.

## §7 Review cadence (both phases — not optional)

Per `CLAUDE.md` "UI Work — Review Cadence": build → tests → adversarial review **of the working
tree** → fix survivors → re-verify → only then commit. Shape: ~6 prosecutor lenses over the diff
(runtime behaviour, CSS/tokens/cascade, test quality, accessibility, project conventions, what it
makes harder later), one refutation agent per surviving charge (default REFUTED without citable
evidence), then synthesis. **Review agents are read-only** — anything that probes by mutating gets
its own worktree. Paste this plan into every review agent's prompt (agents cannot see an
uncommitted/unmerged doc — `review-agents-cannot-see-untracked-plan`). Report what was not
examined as plainly as what was.

Known traps the reviewers should be armed with (all previously bitten): specificity — a contextual
modifier's descendant selector can out-specify a new class and only `getComputedStyle` catches it
(G4); `font` shorthand after `line-height` zeroes it (M4); jsdom computes a selector list's
specificity wrongly, so sliced-cascade tests can pass backwards (M1); `aria-hidden` spans destroy
accname spacing (M4); scripted edits that no-op silently (`silent-noop-string-replace`).

## §8 Local verification stack (proven 2026-08-20)

- `frontend/.env.local` with `VITE_API_TARGET=http://localhost:8083` (gitignored — absent in a
  fresh worktree; without it every request 502s at the login screen).
- Backend: `cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`
  (port **8083**). Detect startup by grepping the log for `Started GoldenHourApplication` — do not
  poll an endpoint (they 401).
- Frontend: `cd frontend && npm run dev`; sign in `admin` / `golden2026`.
- The local H2 starts empty; the strip's rich states need `scripts/dev-seed-locations.sh` (its
  printed ratings SQL is verified end-to-end). ⚠️ The running backend holds the H2 file lock —
  batch direct inserts as stop → RunScript → start. ⚠️ Do **not** trigger a real forecast run
  locally: it bills the live Anthropic key (~$0.016/location).
- Sticky, RO and resize behaviour: Playwright headless Chromium only (§6 step 6); the Browser
  pane's hidden document suspends the required observers. State plainly which claims were seen in
  the browser and which were only asserted in jsdom.

## §9 Handoff prompts (one Sonnet session per phase)

**Phase 1 session:**
> Implement Phase 1 (§5) of `docs/engineering/matrix-axis-plan.md` — the matrix-axis card chips.
> If that file is absent in your worktree, **stop and ask for the plan text**; do not improvise.
> Work in a fresh worktree off main on `feature/matrix-axis-p1-chips`. Every decision is already
> made in the plan's §3; do not re-litigate them, and record any *forced* deviation in the PR body
> rather than improvising silently. Follow §7's review cadence before committing and §8 for
> browser verification. Once the review survivors are fixed and the frontend gate (lint, test,
> audit, build) is green, this prompt is your explicit authorization to push the branch and open
> the PR (against main, CHANGELOG under [Unreleased]); stop after opening it — the owner merges.

**Phase 2 session:**
> Implement Phase 2 (§6) of `docs/engineering/matrix-axis-plan.md` — the row rails and sticky
> headings. If that file is absent in your worktree, **stop and ask for the plan text**. Run §6's
> executable precondition first (`grep -q -- '--color-plex-dawn' frontend/src/index.css` on main);
> stop and say so if Phase 1 has not merged. Work in a fresh worktree off main on
> `feature/matrix-axis-p2-rails`. §3's decisions are settled — D1–D12 and D20 are the ones you
> will be tempted to redesign; don't. The two browser-measured literals (54px lens, 45px tiles)
> were confirmed/re-measured in the live app on 2026-08-30 — updating a literal *and its test string
> together* is in scope, changing the architecture is not. Budget the §6.4 order-assertion
> inventory as real work, not discovered breakage. Follow §7's cadence and §8's stack; Playwright,
> not the Browser pane, for every sticky claim. Once the review survivors are fixed and the
> frontend gate is green, this prompt is your explicit authorization to push the branch and open
> the PR; stop after opening it — the owner merges.

## §10 Review record (2026-08-30, pre-landing)

Five read-only prosecutor lenses ran against this plan, the prototype and the tree; every
confirmed charge above is folded in (the D11 absent-bar semantics, the D12 rounding derivation,
D20, the §6.4 order-assertion inventory, the sliced-block test scoping, the testids, the prompt
authorizations were all their findings). **Refuted or cleared charges, worth not re-raising:**

- Heat-field geometry survives the nesting: canvas sizing measures the card's own well, labels
  position against the card-internal `position: relative` `.wf-hc-cv`, and no CSS uses
  sibling/nth-child combinators on strip children.
- The sticky ancestry (body → … → `.wf-hstrip`) carries no overflow/transform/filter/container
  that would break `position: sticky`; `.wf-body`'s dialog-dim opacity context preserves the
  ordering.
- Both 639px transient directions are closed by D4's (0,2,0) guard + the still-emitted `--c`/`--r`.
- The prototype transcription (every value in D6/D8/D9/D13, both token hexes, the 70%/82%
  gradients, the phone compaction) was verified character-by-character; the README's
  `grid-column: 1 / -1` claim on the rail is spurious — the prototype's own CSS has no such
  property (`.hrow` is flex) and this plan correctly omits it.
- The per-file matchMedia stub *would* work mechanically (setup's guard only governs its own
  install), but the hook mock is the documented convention and the only shape that can flip
  mid-test — hence §6.4's choice.

**Not examined by the review, stated per its own rule:** pixel truth of the 54px/45px literals
(deferred to §6.6 by design — since closed: live-app verification 2026-08-30 confirmed 54px and
re-measured the tiles at 45px, correcting the provisional 49px); the two shell-tab/sheet test files were grep-checked as likely
order-stable but not read in detail — the §6.4 closing grep covers them; Modal's Tailwind `z-50`
taken on trust from its className.
