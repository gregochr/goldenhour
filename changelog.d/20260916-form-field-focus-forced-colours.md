### Fixed — text inputs, selects and textareas show focus in Windows High Contrast again

The 28 remaining call sites the button focus fix (`.btn`/`.btn-primary`/`.btn-secondary`, see the
neighbouring entry) left open: every text input, `<select>` and `<textarea>` that pairs
`focus:ring-1`/`focus:ring-2` (a `box-shadow`) or `focus:border-indigo-500` with `focus:outline-none`
— the sign-in and registration pages, change password, the settings postcode field, the admin user,
region and location management views, `SortableHeader`'s per-column filter, the Plan-overlay
drive-time `<select>`, and the aurora simulation form. Forced-colours mode (Windows High Contrast)
removes every `box-shadow` and repaints border colours regardless of what a `focus:border-*` utility
specified, and `focus:outline-none` — Tailwind 4's `outline-style: none`, the name the v3→v4 migration
(`3e45d18b`, 2026-03-01) kept for what v4 renamed `outline-hidden` — left nothing standing in for
either. All 28 now use `focus:outline-hidden`, unconditionally invisible outside forced colours and a
transparent 2px outline at a 2px offset inside it, which the mode repaints in a system colour.

**The variant stays `focus:`, not `focus-visible:`.** These fields already ring on `focus:` — any
focus, mouse included — and that is existing, intended behaviour, not the defect the shared buttons
had (a ring drawn by a mouse press and left standing through a busy state). Measured before choosing:
a `<select>` does not universally get `:focus-visible` on a mouse click. Chromium's does; Firefox's
does not. Scoping the new outline to `focus-visible:` would have shipped a `<select>` whose forced-
colours outline works by keyboard and silently does nothing on a mouse click in Firefox — invisibly,
since the only page this project measures live (sign-in) has no `<select>`. `focus:outline-hidden`
shows it either way, and changes nothing else: `focus:ring-*`/`focus:border-*` already fire on any
focus, so the fix rides the same trigger they already use.

**Measured**, headless Chromium 151 and Firefox 153 (Playwright 1.62.1), `forced-colors: active`,
pixel-diffing a 14px frame round each field, blurred vs focused, both keyboard (a real Tab press) and
mouse (a real click):

- *The built sign-in page's username field*, before this fix: Chromium already repaints the field's
  own 1px border on focus — a native behaviour, independent of any authored CSS — so it degrades
  (806 of 24,948 pixels) rather than vanishing outright; Firefox changes not one pixel (0/24,948) and
  is left with only the caret. After: Chromium rises to 2,422 (the native repaint plus a real
  outline), Firefox from 0 to 1,617. Normal, non-forced rendering is pixel-identical before and after
  in both engines (0/24,948) — `outline-hidden` is `outline-style: none` outside forced colours, the
  same as `outline-none`.
- *A `<select>` and a `<textarea>` carrying the exact class lists above*, against Tailwind's own
  compiler output for the project's real theme (not the sign-in page, which has neither): before,
  Chromium already shows a small native repaint (526–585/15,624–21,328) on both inputs and selects;
  Firefox shows none on either (0). After, both rise by roughly the same amount in both engines
  (+1,085 to +1,777) for keyboard focus, and the `focus:` choice above is what keeps that true for a
  *mouse-clicked* `<select>` in Firefox too — the `focus-visible:` alternative measured 0 there.

WebKit renders no forced colours in either state (the emulated media query matches, but the mode's
colour substitution never fires) — moot in practice, since Safari does not run on Windows.

**Tests.** `formFieldFocusRules.test.js` pins the same three places `buttonFocusRules.test.js` does,
adapted for plain `className` utilities rather than a shared `@apply`'d class: every call site
carrying an outline utility (found both in inline `className=` values and in the six
`const someClass = '…'` definitions three of these views build once and apply at several call sites)
uses `outline-hidden` under `focus:`, never `focus-visible:`, except the three dialog roots
`useDialogFocus` focuses programmatically (`shared/Modal.jsx`, `MapOverlay.jsx`, `BottomSheet.jsx`,
excluded by name); and what Tailwind's own compiler makes of the bare `focus:outline-hidden` and
`focus-visible:outline-hidden` utilities against the real theme (`compile().build([...])`, no full
source scan needed), guarding the same "a Tailwind release renames what a utility means" risk the
button test does. Five hand-run mutants were killed: reverting a plain `className=` site and a
`const xClass=` site back to `outline-none`, reverting a `focus:border-indigo-500` (Aurora) site, and
switching either the input or the `<select>` site to `focus-visible:`.

Not addressed: `PlanErrorBoundary.jsx`'s programmatically-focused heading has a bare, unprefixed
`outline-none` with no ring or border-colour utility beside it — a different declaration (always no
outline, not "no outline standing in for a focus ring") and outside this task's scope. A comment on
that class now says so directly, because a targeted adversarial review (below) found the obvious
"complete the fix" edit — swapping it to a bare `outline-hidden` — would draw a *permanent*
forced-colours box round the heading, not a focus-only one; `formFieldFocusRules.test.js` pins that
it never happens silently.

**A four-agent targeted review** (not the full sweep — the diff is one token swapped identically 28
times, already measured pixel-identical outside forced colours) found two things worth fixing and
confirmed two decisions sound:

- **The scanner had no comment-awareness — fixed with `blankComments`.** Neither
  `classNameAttributeValues` nor `classVariableDefinitions` stripped comments before scanning, so a
  comment merely *narrating* old `className="...focus:outline-none..."` syntax would read as a real
  call site. Worse: a comment nested inside a template literal's `${…}` interpolation — the real
  shape at `ModelTestView.jsx:654`'s row-selection ternary, which already carries a `//` comment
  there — could have its own apostrophe misread as opening a string, desyncing the whole walk and
  failing every test in the file's first `describe` at once. Reproduced against that real,
  currently-shipping file by changing four characters of its prose; confirmed fixed by re-running the
  exact same mutation against the real file post-fix (14/14 green, no crash). `blankComments` blanks
  `//` and `/* */` comments to spaces (offsets preserved) while leaving every string and template's
  own text untouched, mirroring — for JS — what this file's `stripComments` already did for CSS.
- **The "three dialog roots" exemption comment undersold the mechanism — corrected.**
  `useDialogFocus.js`'s own docblock names a *fourth* consumer, `RegionsJump.jsx`; it simply carries
  no outline utility at all today, so it has nothing to exempt, but the test's comment now says so
  explicitly rather than implying three is the complete count of `useDialogFocus` call sites.
- **Confirmed sound, no change**: keeping the outline on `focus:` rather than `focus-visible:` — an
  independent re-verification, live in a real Chromium instance, found the mechanism is type- and
  engine-agnostic by construction (`:focus` fires unconditionally, inheriting exactly the pre-existing
  ring/border trigger) — and the three named dialog-root exemptions, each independently re-traced to
  confirm `useDialogFocus`'s ref and the `outline-none` class sit on the same literal element.
