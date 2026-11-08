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
