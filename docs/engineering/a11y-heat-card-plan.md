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
