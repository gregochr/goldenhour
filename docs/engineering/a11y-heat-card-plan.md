# The Plan heat card: label in name (WCAG 2.5.3)

Status: planned 2026-11-08. This is the last serious finding of the advisory accessibility scan
(`engineering-gaps-plan.md`, Phase 3c). Once it is merged, the scan should report no serious or critical
violations, and the owner can make it a required check.

## The finding

axe `label-content-name-mismatch` flags 36 nodes. Every one is a Plan heat card (`.wf-hc`, `.best`,
`.also`), in the plan, window-sheet and location-sheet scans, at both widths.

- **The card is a `<button>`** (`WindowFirstHeatStrip.jsx`, about line 1972). Its accessible name comes from
  `aria-labelledby` pointing at one `sr-only` sentence ("Tonight, sunset at 21:11, worth it, best
  Ingleborough …"). Every visible string is `aria-hidden`.
- **axe 4.14 counts `aria-hidden` children as visible text** and joins sibling text with no separator. It
  then requires that visible text to appear, in order, inside the name. The card's visible text includes
  the sun word, the time, the verdict, the region labels drawn over the field, the "Spread" chart with its
  numbers ("2 4 6 4 2") and the pick legend. No sentence a person would want read aloud contains all of
  that in that order. The research for #1792 confirmed it:
  - separators alone still fail;
  - prefixing the name with the visible string passes, but makes the screen-reader experience worse.
- **What the rule checks:** it only applies to a control that **has visible text content**. A control
  whose only content is its own `sr-only` sentence has none.

## The fix: a stretched button

The card stops being a button and becomes a visual container. The control becomes a transparent button
that covers the card and contains only the name sentence.

```jsx
<div data-testid="wf-heat-card" data-control="true" className="wf-hc …" style={place}
     data-open=… data-unscored=… data-ask-highlight=…>
  {/* the visible spans exactly as today, still aria-hidden */}
  <button type="button" className="wf-hc-hit" data-testid="wf-heat-card-button"
          aria-haspopup="dialog" aria-expanded={open} aria-labelledby={nameId}
          onClick={() => onOpenWindow?.(card.key)}>
    <span id={nameId} className="sr-only">{accessibleName}</span>
  </button>
</div>
```

**Rules:**
1. **The name sentence, `aria-labelledby`, `aria-haspopup` and `aria-expanded` are unchanged.** They move
   to the inner button, so a screen-reader user hears exactly what they hear today. `getByRole('button',
   { name })` keeps working.
2. **The outer element keeps every class, every `data-*` attribute, `style={place}` and
   `data-testid="wf-heat-card"`.** So the grid placement, the verdict tint, the pick border, the open state
   and the Ask highlight are untouched, and so are the tests that read classes and data attributes.
   - The tag stays a **`div`**, the same as today's away cell.
   - `data-control="true"` marks a control card. An away card has no `data-control` and no inner button,
     exactly as today.
3. **The hit button:** `position: absolute; inset: 0;`, transparent, no border or padding, with the card's
   radius, and above the visible spans in stacking order. The card needs `position: relative`; the builder
   checks first whether it already has a positioning context. The card contains **no other interactive
   element**, so nothing is covered that needs its own pointer events.
   - The builder proves this with a grep and a browser check, including the Ask rank circle and the pick
     legend.
4. **CSS: same cascade, same specificity.** Every `button.wf-hc…` selector (about 15 in `index.css`) becomes
   `div.wf-hc:where([data-control])…`. `:where()` adds no specificity, so `div.wf-hc` matches
   `button.wf-hc` at (0,1,1). The cascade arithmetic those rules' comments rely on therefore holds.
   - **`:hover`** still works on the `div`, because the pointer is over its child button.
   - **`:focus-visible`** moves to the button: `.wf-hc-hit:focus-visible` draws the same ring (2px gold,
     2px offset, 9px radius). The button exactly covers the card, so the ring traces the card as it does
     today.
   - **Focus-state rules on the card** (the Ask highlight's `:focus-visible` arm at about line 15803):
     rewrite them as `div.wf-hc:where([data-control]:has(> .wf-hc-hit:focus-visible))`. `:where` holds
     the specificity at the old value minus the pseudo-class.
   - The builder writes a **before/after specificity table** for every rewritten selector into this
     plan's build notes, and justifies each line where the specificity changes.
5. **No visual change.**
   - Take before and after screenshots of the Plan matrix, desktop and phone.
   - Cover these states: resting, hover (desktop), keyboard focus, open, best, also, Ask highlight with
     focus, unscored, and an away cell.
   - Compare pixels; the result must be 0 differing pixels, or every difference must be explained and
     accepted by the planner.
6. **No behaviour change.**
   - The tab order is unchanged: still one stop per card.
   - Enter and Space still open the window sheet.
   - When the sheet closes, focus returns to the inner button, the element that opened it.
   - The Back engine, `selectTab` and the popup's opener logic are untouched; the builder verifies them.
   - Any code that focuses or measures the card by `wf-heat-card` must be checked. Grep for `focus()`,
     `getBoundingClientRect` and `closest('.wf-hc')`.

## Tests

- **Unit (Vitest):** 28 files reference `wf-heat-card`.
  - Tests that click it with `userEvent` or `fireEvent` must click the **button**: an event dispatched on
    the outer `div` never reaches the inner button's `onClick`. Prefer `getByRole('button', { name })`
    where the test already knows the name, otherwise `getByTestId('wf-heat-card-button')`.
  - Attribute and class assertions stay on `wf-heat-card`. `aria-expanded` and `aria-haspopup` move to the
    button.
  - Change only what the move forces, and weaken nothing.
- **New:** axe's own rule (`src/test/labelInNameCheck.js`) on a rated card, a best pick, an also-good pick,
  an unscored card and an Ask-highlighted card. Each expects `violations: 0`. The result is "inapplicable",
  so the assertion is on violations, not passes.
  - Prove it by mutation: put the visible spans back inside a single button, and the test must fail.
- **E2E smoke:** 18 references.
  - Playwright clicks at coordinates, so clicks on `wf-heat-card` land on the overlay button and keep
    working.
  - Focus assertions (`back.spec.js`: "focus returns to the card") must target the button.
  - The full suite, then `--repeat-each=3`.
- **a11y scan:** `label-content-name-mismatch` must disappear completely. The summary must then show
  **no serious or critical violations**, and that result goes in the PR.

## Review

This is a UI change across the main Plan surface, so it gets the full cadence (CLAUDE.md, *UI Work — Review
Cadence*):
- this plan reviewed adversarially before the build;
- the diff reviewed by several lenses (CSS cascade and visuals, a11y semantics and focus, tests and their
  strength, interaction and keyboard), with refuters;
- browser verification with the screenshots above.

## Out of scope

- The 600-odd axe "needs review" contrast items and the other "needs review" rules. They are not failures,
  and they need imagery the tests stub out.
- Making the advisory check required, which is the owner's call once the scan is clean.

## Amendments from the plan review (2026-11-08)

Three lenses (CSS cascade, a11y semantics, tests and interaction) reviewed the plan. These amendments
override the sections above where they differ.

### A1. The click stays on the card; the hit button is keyboard-only (replaces rule 3's pointer model)
- **The card `div` keeps `onClick`.** `.wf-hc-hit` gets `pointer-events: none`, so pointer clicks and hovers
  land on the card and its spans exactly as today. This keeps the five `title` tooltips
  (`WindowFirstHeatStrip.jsx` ~1710, 1770, 1855, 1888, 1917: the blanked note, the Spread histogram, Best,
  the tide chip and the clocked topic). It keeps the pick legend's overhang (`.wf-hc-lg`, `top: -8px`)
  clickable, and keeps `cursor: pointer` coming from the card.
- **The keyboard and assistive tech reach the card through the hit button.** Enter or Space on the focused
  button fires a `click` on the button, which bubbles to the card's `onClick`. That happens once:
  `pointer-events: none` means a pointer never targets the button. TalkBack and VoiceOver activate the
  focused button the same way.
- **A pointer click on the card focuses the hit button first** (`hitRef.current?.focus({ preventScroll: true })`),
  then opens the window. Without that, `document.activeElement` would be `<body>` when the window sheet
  opens, and focus restore and Back would return focus to the body. Today a click focuses the card
  button in Chromium.
- jsx-a11y will flag a `div` with `onClick`. Use a scoped `eslint-disable-next-line` that names the rules,
  with the reason: "the keyboard and AT path is the inner `.wf-hc-hit` button; the div's click only
  serves the pointer".
- `cursor: pointer` moves from `button.wf-hc` to the rewritten card selector. Set `.wf-hc-hit` to
  `cursor: inherit` for safety.

### A2. The sentence lives only in the button (fixes a double read and a duplicate id)
On a control card, the `sr-only` name span moves from the card body into `.wf-hc-hit` and appears nowhere
else. An away card keeps its span in the body, as today. Test: exactly one `#nameId` in the document, and
the card `div` has no text outside the button and the `aria-hidden` spans.

### A3. Two app focus moves (blocker)
`WindowFirstShell.jsx` ~2277 (`applyConflictAction`) and ~3494 ("Plan from region") call
`document.querySelector('button[data-testid="wf-heat-card"]')?.focus?.()`. That would silently match nothing
after the change. Point both at `[data-testid="wf-heat-card-button"]`, which excludes away cards as
before, and add a test for each route. Also grep for `focus?.(`, `querySelector('button[` and
`` `button[data-testid` ``.

### A4. Geometry and focus ring
- `.wf-hc-hit`: `position: absolute; inset: -1px; border-radius: 9px;` so it covers the border box. The
  card has a 1px border, and `inset: 0` would put the ring 1px inside today's ring. Also `appearance:
  none; background: transparent; border: 0; padding: 0; margin: 0;`.
- The ring: `.wf-hc-hit:focus-visible { outline: 2px solid var(--color-plex-gold); outline-offset: 2px; }`.
- **Scroll reservation (blocker):** `scroll-margin-top` sits on `.wf-hc` (`index.css` ~1701 and ~10171-10186)
  so that a focused card scrolls clear of the sticky bars (SC 2.4.11). Focus now lands on `.wf-hc-hit`,
  so add `.wf-hc-hit` to both reservation rules, and keep `WindowFirstShellSticky.test.jsx` ~444-453's
  unique-declaration check true.
- **Forced colours:** the Ask-highlight forced-colours focus arm (`index.css` ~15803) would otherwise draw
  two rings. In that arm set `.wf-hc-hit:focus-visible { outline: none }` (not `transparent`; see the
  note at ~1006) and let the card's outline be the ring. Do the same anywhere else the card draws its
  own outline.

### A5. Selectors: 17, not about 15
Rewrite the `button.wf-hc` selectors at `index.css` ~1992, 1995, 2006, 2012, 2049, 2053, 2057, 2071, 3078,
3083, 3092-3094, 15789, 15790, 15796 and 15803, and reword the specificity comments at ~915, 1002, 3070
and 15787. The CSS review checked every specificity in the old-to-new table and all of them hold: the
`:where` forms tie or win on order exactly as before. Record the table in the PR.

The UA properties a `button` has and a `div` lacks are `text-align`, `cursor`, `text-transform`,
`word-spacing`, `text-indent`, `text-shadow`, `box-sizing`, `font-size` and `font-family`. Each is either
already set on `.wf-hc`, comes from Preflight, or is not set by any ancestor. That is why the pixel check
should come back at zero.

### A6. Tests: the real counts and patterns
- The counts are **22 Vitest files and 6 Playwright specs**: about 27 click sites, 18 aria reads, 6 focus
  sites, 2 `disabled` sites, 2 `pointerDown` sites and 2 mixed helpers.
- **What moves to the button:** `aria-labelledby`, `aria-expanded` and `aria-haspopup` reads, `focus()`
  calls, `disabled` and `toHaveFocus` assertions. Click sites still work on the card under A1. Leave them
  there unless a test is about keyboard activation, which must use the button.
  - `AskShellDock.test.jsx` ~632 and `planOriginShell.test.jsx` ~269 would pass vacuously if the card
    `div` were focused, so they must focus the button.
  - `AppSettingsRoutes.test.jsx` ~457-513 (focus and `disabled`) target the button.
- **Mixed helpers** (`WindowFirstHeatStripAskHighlight.test.jsx` ~100, `AskShellPlan.test.jsx` ~55): find the
  button by `aria-labelledby`, then use `.closest('[data-testid="wf-heat-card"]')` for the `data-*` reads.
  Index buttons with `within(card)`, never `getAllByTestId('wf-heat-card-button')[n]`: an away cell has no
  button, so the indexes differ.
- `WindowFirstHeatStrip.test.jsx` ~437-438 currently asserts the card is a `BUTTON` with no button inside.
  Invert it deliberately: the card is a `DIV` with `data-control="true"` and exactly one button inside,
  which is `wf-heat-card-button`.
- `askPlanCss.test.js` ~102, 114, 126 and 142 match the selector text. Rewrite the strings, and assert that
  both `indexOf` results are at least 0. Line 126 currently passes vacuously at -1.
- **New guard for WCAG 2.5.3 itself:** for each variant, the button's accessible name contains the
  visible time, the verdict word, the sun word and the pick word, case-insensitively. The fix makes axe's
  rule inapplicable in a browser; this test keeps the conformance it stands for.
- **The jsdom axe check** runs with `css: false`, so the `sr-only` text counts as visible and the rule
  *passes* there. The assertion stays `violations: 0`. Run `npm ci` first, because the lockfile pins
  axe-core 4.14.
- **E2E:**
  - `back.spec.js` ~376's focus assertion targets `card.getByTestId('wf-heat-card-button')`.
  - Add a Playwright click on the legend's top edge: it must open the window.
  - Add a keyboard path: Tab to a card, press Enter, the sheet opens, close it, and focus returns to the
    button.

### A7. The visual check covers forced colours
Add `forcedColors: 'active'` shots (rest, keyboard focus, and Ask highlight with focus), and confirm the
border and the focus ring are both drawn and there is no double ring.

## Build notes (2026-11-08)

Built to A1 to A7. What was built, what was measured, and where the build deviated and why.

### Deviations from the plan, each measured

1. **The hit button is the card's FIRST child, not its last (rule 3's "above the visible spans").** Placed after the
   canvas well, the absolutely positioned button paints above the composited canvas, the engine promotes it to its own
   layer, and the heat field's pixels shift by one level on about 40,000 of them (a 1-in-255 difference, but not zero).
   First in the DOM it paints below the positioned canvas well and the pick legend (tree order in the `z-index: auto`
   layer) and above only the unpositioned text, nothing is promoted, and the card renders as before. The focus ring it
   draws is outside the card and hidden behind the legend where they cross, which the old card ring also was (checked
   in the zoomed screenshot). `z-index: -1` also fixed the pixels but hid the ring entirely (the card is not a stacking
   context: `container-type: inline-size` does not make it one), so it was dropped.
2. **`inset: 0`, radius 8px and `outline-offset: 3px`, not A4's `inset: -1px`, 9px and 2px.** The ring is in the same
   place (the 1px border plus the card's own 2px; an outline's corner is its box's radius plus the offset, 11px either
   way: 8 + 3 and 9 + 2 gave 0 differing pixels, 6, 7, 9 and 10 gave 184 to 266). `inset: -1px` changed the pixels of the
   neighbouring card that peeks in at the edge of the phone's scrolling strip, about 8,000 of them by one level, and
   `inset: 0` changes none.
3. **A forced-colours border arm (new).** A focused `<button>` card had its 1px border drawn in `Highlight` by the
   engine's own forced-colours focus style; the `div` card kept `CanvasText`. One rule inside the existing
   `@media (forced-colors: active)` block restores it (`div.wf-hc.wf-hc.wf-hc.wf-hc.wf-hc:where([data-control]):where(:has(> .wf-hc-hit:focus-visible))
   { border-color: Highlight }`; the repeated class is to out-rank the pick and open border rules).
4. **The card's `onClick` finds the button with `event.currentTarget.querySelector('.wf-hc-hit')`, not a ref.** The card
   is rendered in a `.map`, where a hook-based ref per card is not available, and a callback ref per card would be a
   `Map` for one `focus()`.
5. **`locationSheetShell.test.jsx`'s "puts focus on the plan" test now opens the sheet from search, not from a card.** The
   click on a card now focuses its button before the popup opens, so a popup opened from the first card hands focus back
   to that very button on close, and the test passed with the route's selector broken (found by mutation: reverting the
   selector survived). From search there is no card in the chain, and the same mutation fails.
6. **`--repeat-each` is per project** (`dev-and-testing.md`: it repeats only the projects it is asked for), so the
   repeat proof is three invocations: `--project=smoke`, `--project=roles-write` and `--project=logout`.

### Specificity table

Every old `button.wf-hc…` selector and its replacement. `:where()` contributes nothing, and `div` takes the place of
`button` (both one type selector), so each pair is identical. (a,b,c) = ids, classes/attributes/pseudo-classes, types.

| Old | New | Old | New |
|---|---|---|---|
| `button.wf-hc` (cursor) | `div.wf-hc:where([data-control])` | (0,1,1) | (0,1,1) |
| `button.wf-hc:hover` (+ the reduced-motion `transform: none`) | `div.wf-hc:where([data-control]):hover` | (0,2,1) | (0,2,1) |
| `button.wf-hc.vg/.vm/.vp:hover` | `div.wf-hc:where([data-control]).vg/.vm/.vp:hover` | (0,3,1) | (0,3,1) |
| `button.wf-hc.on:hover` (background, and the border rule) | `div.wf-hc:where([data-control]).on:hover` | (0,3,1) | (0,3,1) |
| `button.wf-hc.best:hover`, `.also:hover` | `div.wf-hc:where([data-control]).best:hover`, `.also:hover` | (0,3,1) | (0,3,1) |
| `button.wf-hc.best.on:hover`, `.also.on:hover` | `div.wf-hc:where([data-control]).best.on:hover`, `.also.on:hover` | (0,4,1) | (0,4,1) |
| `button.wf-hc.wf-hc[data-ask-highlight='true']` | `div.wf-hc.wf-hc:where([data-control])[data-ask-highlight='true']` | (0,3,1) | (0,3,1) |
| the same, `:hover` | the same, `:hover` | (0,4,1) | (0,4,1) |
| forced colours, rest, the same selector | the same | (0,3,1) | (0,3,1) |
| forced colours, `…[data-ask-highlight='true']:focus-visible` (`outline-offset: 2px`) | `div.wf-hc.wf-hc:where([data-control]):where(:has(> .wf-hc-hit:focus-visible))[data-ask-highlight='true']` | (0,4,1) | (0,3,1) |
| `button.wf-hc:focus-visible` (the ring) | `.wf-hc-hit:focus-visible`, on the button | (0,2,1) | (0,2,0) |

The two changed rows, justified:
- **The forced-colours ask-focus arm is one lower** because `:focus-visible` moved into a `:where(:has(…))`, which counts for
  nothing. Its only competitor is the arm's own rest rule at (0,3,1) (`outline-offset: -3px`), which it ties and beats by
  coming later, as before (the test `askPlanCss.test.js` pins the order). The old card-level focus-ring rule it also
  beat has left the card.
- **The ring** moved to a different element, so it competes with no card rule: the only rules on `.wf-hc-hit` are its own
  (0,1,0) box and the ring (0,2,0), plus, in forced colours with the Ask highlight, `div.wf-hc[data-ask-highlight='true']:where([data-control]) > .wf-hc-hit:focus-visible`
  at (0,4,1) turning the button's ring off so the card's is the only one. New rows with no old counterpart: `.wf-hc-hit` (0,1,0),
  the forced-colours border arm (0,5,1: five `.wf-hc` classes plus `div`, `:where` ignored).

### Pixel diff (Plan matrix, Chromium, deviceScaleFactor 1)

The "before" shots were taken from the unmodified build before any source was changed, then re-taken from a pristine
copy of the base commit's build on the same backend as the "after" shots (so the data cannot differ), twice (the second
is the run-to-run noise mask). Each shot is an element clip with 14px of padding, so the focus ring and the legend
overhang are inside it. States: resting, hover (desktop), keyboard focus, for a plain card, a best pick and an also-good
pick; a synthetic open state (`on` + `data-open`) and a synthetic Ask highlight (the attribute, resting and focused, on a
plain and on a best card: Ask is off in the fixture, so the rank circle is covered by the unit tests); the real open
window sheet; and the whole strip; all at desktop 1280x800 and the phone (Pixel 7), each in normal and in forced colours
(`forcedColors: 'active'`, resting and focused). The fixture has no unscored card and no away cell (every window is
rated), so those two are unit-tested only.

| Pair | Result |
|---|---|
| 16 desktop shots (normal): rest, hover, focus, open (real and synthetic), Ask (synthetic), strip | 0 differing pixels, except `desktop-plain-focus`: 2 px at (10..12, 22), the ring's top-left corner, 3 to 5 levels of 255 (anti-aliasing of an 8px+3px corner against a 9px+2px one), and `desktop-synthetic-ask-best-focus`: 1 px, one level, at the clip's corner on the neighbouring card |
| 13 phone shots (normal): the same, no hover | 0 |
| 12 desktop and 12 phone forced-colours shots: rest, focus, synthetic open and Ask with focus | 0 (the border and exactly one ring, the same pixels) |
| strips (4: desktop, phone, each normal and forced) | run-to-run noise: the "Last forecast run Nm ago" text and lazily painted off-screen canvases, 49,197 px on the phone strip between two runs of the SAME old build; outside that mask: 1 px (the "ago" text), 2,063 px in the phone strip's off-screen canvases, 0 in the two forced-colours strips |

Not producible: an unscored card and an away cell (not in the seeded fixture), and the Ask rank circle (the flag is
off); the Ask highlight's attribute-driven look was checked by setting the attribute.

### Mutation results

Each mutation was applied to a copy-backed file, run against the named tests, and restored from the backup.

| Mutation | Killed by |
|---|---|
| put the visible spans back inside one button (the card's `{body}` moved into the button) | the six axe tests (`WindowFirstHeatStripLabelInName`: rated, sunrise, best, also-good, unscored, Ask-highlighted) and the single-owner test: 7 |
| drop the card's focus-the-button call | `WindowFirstHeatStripLabelInName` (2: a pointer click and a click on the card) and `AppSettingsRoutes` (the popup's return focus, 3) |
| revert `applyConflictAction`'s selector | `WindowFirstShell.test.jsx` |
| revert the "Plan from region" selector | `locationSheetShell.test.jsx`, after change 5 above (it survived before) |
| remove `pointer-events: none` | `WindowFirstHeatStripLabelInName` (the stylesheet text) and, built, the e2e `pointer:` test |
| remove `.wf-hc-hit` from the card-selector `scroll-margin-top` list | `WindowFirstShellSticky` |
| remove `.wf-hc-hit` from the rows-mode `scroll-margin-top` rule | `WindowFirstShellSticky` (a new test) |

### a11y scan

```
16 scans, 1 distinct rule/impact pairs, 1 serious or critical.
| `label-content-name-mismatch` | serious | 2: a11y-desktop/coming-up, a11y-phone/coming-up | 16 |
```

`label-content-name-mismatch` is gone from the plan, window-sheet and location-sheet scans, at both widths (the same
scan on the unmodified base build reports it in those and in Coming up: 8 scans failed, 6 heat-card nodes in each plan
scan). **What remains is not the heat card**: the Coming up day chips (`button.wf-cu-day[data-date]`, 8 nodes per width),
whose `aria-label` ("Show … for Sun 8 Nov, peak …") does not hold the visible words ("Today", "8", the month and the mark
labels). It is present on the base build, was named as still to do in `20261108-a11y-label-in-name-glue.md`, and was not
touched here. The scan will not be clean until that is fixed.

### Gates

Frontend lint: 0 warnings. Vitest with coverage: 862 files, 19,159 tests, thresholds met. `npm audit --audit-level=high`:
4 low only. `npm run build`: ok. diff-cover against `origin/main`: 100% (6 changed source lines). Smoke: 47 passed, 8
skipped (the nginx spec, which needs Docker); `--project=smoke --repeat-each=3`: 135 passed; `roles-write` and `logout`
`--repeat-each=3`: green. New e2e: the keyboard path and the legend-edge click with the pointer-events probe.
