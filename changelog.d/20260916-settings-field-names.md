### Fixed — the settings dialog's postcode field and radius slider are named by the words they show

**The postcode field had no name of its own.** It has no `<label>`, `aria-label` or
`aria-labelledby`, so its accessible name came from its placeholder, "Enter UK postcode". HTML-AAM
uses a placeholder only when nothing else names a field. The text is an instruction rather than a
name, and it is out of sight whenever the field holds a value, which it does whenever a home is
saved. This is the field that the masthead's "set a postcode" nudge and the Map tab's ⌂ both open
settings onto (`focusField="postcode"`). Landing a reader there is those routes' whole purpose.

**The radius slider had a label, but its hint ran into it.** Chromium named it
`Local radiusHow far counts as close to home.` (measured, native). No text separates the label from
its hint span. The gap a sighted reader sees is the span's margin, and a margin is not text.

**The fix.** Nothing on screen moves.

- The postcode field is `aria-labelledby` the section's "Home location" heading, the text directly
  above it. The name and the visible words are then one string by construction (WCAG 2.5.3). The
  placeholder stays as it was.
- The slider's label is split into two spans. The first ("Local radius") names the slider through
  `aria-labelledby`. The second (the hint) describes it through `aria-describedby`. The
  `<label for>` relationship is unchanged.
- The other controls were checked and needed nothing. The two colour radios are named by the labels
  that wrap them, and every button's name is its text or its `aria-label` ("Close" on the ×).

⚠️ **The postcode field's name is a decision, and it can be read the other way.** W3C's
Understanding document for 2.5.3 says a heading is not normally a control's label. It also says a
placeholder can count as the label when no other text sits in the label position. A strict reading
would therefore want "Enter UK postcode" inside the name, on the empty field. The placeholder is
deliberately not folded in, because HTML-AAM exposes it through its own hint property whatever the
name is (`AXPlaceholderValue`, UIA `HelpText`, IA2/ATK `placeholder-text`). A name that also carried
"Enter UK postcode" would say it twice, on the empty field that both routes land on. That rests on
the specification; no screen reader was run. If the owner prefers the strict reading, the change is
one more id in `aria-labelledby`, and the cost is that repetition.

**What was measured.** Readings were taken on the dialog's own rendered DOM, captured from the
component with and without a saved home, under the built stylesheet.

- **Chromium, native accessibility tree (CDP).** Before: `Enter UK postcode`, taken from the
  placeholder, and the run-together slider name above. After: `HOME LOCATION`, taken from
  `aria-labelledby`, and `Local radius` with the description `How far counts as close to home.`.
  Chromium applies the heading's `uppercase` to the name, which is also how it reads the heading
  itself.
- **WebKit and Firefox.** These readings come from Playwright's own accessible-name algorithm, not
  the engines' native trees (this Playwright has no native API for either). They agree:
  `Home location` and `Local radius`.
- **Layout.** Every text node's box and every control's box is identical before and after. That was
  checked in Chromium, WebKit and Firefox, at 1280 and 375 px, with and without a saved home. As a
  positive control, one inserted space moved the hint 3.8–3.9 px in all three, so the comparison can
  see a change that small. The built CSS is byte-identical to `main`'s.
- **Not measured:** any screen reader or speech-input product, and the running app. The dialog sits
  behind sign-in, which this session cannot do.

**Tests.** No focus code changed. Two parts of the landing had no test, and now do:

- The field's `select()` on landing had no test (deleting it failed nothing). A test now pins it.
- Only the nudge's landing on the field was tested. The ⌂'s is now pinned too, through the real
  `App` in `AppSettingsRoutes.test.jsx`.

The existing autofocus test now finds the field by its name. It also no longer depends on a
`getSettings` answer left behind by the test before it. `AppSettingsRoutes.test.jsx`'s
`settingsSettled` now waits on the named field rather than a test id, so the route tests fail if the
name regresses: all 19 in that file did when `aria-labelledby` was removed.

**Mutation testing.** 12 mutants were run on a scratch copy, and 11 were killed.

- With the component reverted to `main`, exactly the five name-bearing tests in
  `UserSettingsModal.test.jsx` fail, and its other 103 pass.
- Removing the slider's `aria-describedby`, or the field's `select()`, fails only the test that pins
  it. So does dropping the focus field from the ⌂'s handler in `App`, or from the nudge's.
- The survivor is an `aria-label` repeating the heading's text, which gives the same name today.
  What `aria-labelledby` adds is that the name cannot drift from the heading, and no rendered test
  can see drift that has not happened yet.
