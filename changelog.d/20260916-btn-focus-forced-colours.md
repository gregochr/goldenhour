### Fixed — a focused button shows in Windows High Contrast again, and a click no longer leaves a ring on it

Every `.btn`, `.btn-primary` and `.btn-secondary` in the app lost its focus indicator in forced
colours, and `.btn` and `.btn-primary` also drew their ring on a mouse press. They are in the admin
views, the sign-in, registration and change-password pages, the settings dialog, `ConfirmDialog` and
the Plan's error boundary, among others. Both defects are fixed in the three `@apply` rules in
`index.css`.

**No focus indicator in forced-colours mode.** The focus ring is a Tailwind `ring`, which is a
`box-shadow`, and forced-colours mode (Windows High Contrast) removes every box-shadow. Beside it
the classes said `focus:outline-none`, which Tailwind 4 compiles to `outline-style: none`, so a
focused button showed nothing at all. It was not always so: Tailwind 3's `outline-none` was a
transparent outline that the mode repaints, Tailwind 4 renamed that utility `outline-hidden`, and
the v4 migration (`3e45d18b`, 2026-03-01) kept the old name. The classes now use `outline-hidden`,
which is `outline-style: none` everywhere except under `forced-colors: active`. There it is a
transparent 2px outline at a 2px offset, repainted by the mode in a system colour, in the band the
ring draws in (`ring-offset-2`, then `ring-2`).

**A ring on every mouse press.** `.btn-primary` and `.btn` keyed their ring on `focus:`. Chromium and
Firefox focus a button when it is pressed (Safari does not), so every click drew the ring, and it
stayed until focus moved on. `.btn-secondary` moved to `focus-visible:` in #431; the other two now
match. The new outline is on `focus-visible:` as well. Tailwind's upgrade guide suggests
`focus:outline-hidden`, which would have drawn the forced-colours outline on a mouse press.

**Measured before and after**, in headless Chromium 151, Firefox 153 and WebKit 26.5 (Playwright),
against the built stylesheet: six call-site class lists, plus `.btn-primary` at a busy button's 40%
opacity, inside `Modal`'s own element chain, each focused by keyboard and by mouse, with forced
colours off and on (dark and light), compared pixel by pixel in a 5px frame round each button. Of 126
states, the 38 that changed are exactly the intended ones:

- *Forced colours, keyboard:* before, not one pixel round the button changed on focus. Now a 2px
  outline stands 2px off the button's edge: cyan on black in Chromium's dark palette (8.7:1),
  indigo on white in its light one (11.3:1), and black on white in Firefox (21:1), 2px clear of
  the grey button face. At 40% opacity the outline dims with the button, to 2.1–2.9:1. The
  settings dialog's Save and Look up are in that state while busy, since #859 keeps them focused
  with `aria-disabled`.
- *Mouse press, forced colours off:* `.btn-primary` and `.btn` drew the ring in Chromium and Firefox,
  and now draw nothing.

Keyboard focus with forced colours off is pixel-identical before and after in all three engines.
WebKit changed nowhere: it renders no forced colours (the emulated media query matches and the ring
still paints), and it does not focus a pressed button. The built app's real sign-in page shows the
same in Chromium and Firefox, without a backend. Against its base at the time (`86b70436`), the
built CSS differed only in the three rules, and the JS bundles only in their hashes.

**Tests.** jsdom renders no CSS, so `buttonFocusRules.test.js` pins the rules as text, in three
places:

- *The `@apply` lists as written.* No `focus:` utility. `focus-visible:outline-hidden` is the only
  outline utility. The ring is on `focus-visible:` at `ring-2`/`ring-offset-2`, and gold on primary
  and secondary. No other rule in `index.css` gives these classes an outline or a box-shadow.
- *What Tailwind's own compiler makes of the real stylesheet.* The only outline declarations per
  class are `outline-style: none` on `:focus-visible`, plus the transparent outline and its offset
  under `forced-colors: active`. This guards against a Tailwind release that changes a utility under
  an unchanged name, which is how the first defect arrived.
- *Every `className=` in `src/` carrying a `.btn*` class.* None may add an outline or ring utility,
  because utilities sit in a later cascade layer and would beat the class.

A mutation sweep killed all 21 mutants: the old rules, each utility moved back or dropped, a plain
`outline: none` inside a rule, two override rules, three call-site overrides, two blinded call-site
scanners, and a Tailwind whose `outline-hidden` had lost its forced-colours outline.

**#859's entry is superseded on two points.** `20260915-settings-dialog-focus-targets.md` says the
settings dialog's button landings have no forced-colours indicator, and that `.btn-primary`'s ring
is a `focus:` rule that stays drawn after a mouse press. Neither holds after this change. The two
`UserSettingsModal.jsx` comments that said so now describe the classes' own treatment.

Not addressed: 28 class lists on text inputs, selects and textareas pair `focus:outline-none` with a
`focus:ring-1` or a focus border colour — the auth pages, the admin forms, the settings postcode
field, `SortableHeader`, the map overlay's drive-time filter. Measured on the sign-in username field
only: in forced colours Chromium still marks focus by repainting the field's 1px border in its focus
colour, while in Firefox nothing round the field changes and the caret is the only sign. Selects and
textareas were not measured.
