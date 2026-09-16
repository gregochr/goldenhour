### Fixed — the settings dialog no longer drops a keyboard reader on `<body>`

An accessibility review of #842 found places around the settings dialog where focus fell to
`<body>`. From there the next Tab starts at the top of the document, behind the dialog's
backdrop, and a screen reader loses its place. On the Map tab `<body>` is also outside `MapView`'s
pane-scoped `onKeyDown`. The review listed four; Look up, which fails for the same reason as Save,
made five. Each now has a deliberate focus target:

- **The masthead's "set a postcode" nudge, on the Map tab.** A known home swaps that `<button>`
  for the statement's `<span>` ("Home · Keswick — drive times from here"). That is a different
  element, so the node the dialog recorded as its opener is destroyed. On other tabs React reuses
  the button node and focus rides it. The statement is now a programmatic focus target
  (`tabIndex={-1}`: focusable, never a tab stop). Since #842 the swap happens in two orders, each
  held by its own mechanism:
  - *Before the close — the ordinary save.* The page takes the new home from the save's own
    response, so the nudge is replaced while the dialog is still open. The nudge hands `App` a
    resolver for the slot's current element, and `App` passes it to the dialog as
    `restoreFocusFallback`, a new **caller opt-in** on `Modal`/`useDialogFocus`. It is consulted
    only when the recorded opener cannot take focus back (detached, disabled, or `<body>` to begin
    with), only under the same "the reader has not chosen somewhere else" guard as the ordinary
    restore, and never for an element outside a dialog that still claims `aria-modal` beneath.
    The hook keeps the fallback it held while the dialog was open, so a caller that stays mounted
    and clears it in the closing update still gets it. `App` clears its own on close, so a later
    opening from the cog cannot inherit it. The other three render sites (`BottomSheet`,
    `MapOverlay`, `RegionsJump`) and every other `Modal` pass nothing and behave exactly as before.
  - *After the close — the dialog's own settings read answering late.* The dialog has already
    handed focus back to the nudge when the answer names a home saved elsewhere. The tick line
    hands focus from the departing element to its replacement, but only when the departing one
    held focus. That is read in the ref's cleanup, which React runs while the old node is still
    attached and focused (measured on React 19.3). StrictMode's development-only re-run of a ref
    runs that cleanup and then the setup again on the same, still-focused node, so the setup now
    clears a record naming its own node. Left in place, that record pulled a reader who had
    clicked away back onto the statement at the line's next render, and onto the new tab's slot
    after a tab switch that does not move focus, as a click on a tab does in Safari (both
    reproduced under `StrictMode` in the tests). A looser "focus is nowhere after a swap" rule was
    rejected: a tab switch swaps this slot too, and the shell's `tabRequest` handoff moves focus
    itself. Focus-event tracking was rejected too: Chromium fires `blur` on a node as it is
    removed, WebKit and Firefox fire nothing.

  Every route into settings closes the dialogs it would open over (#858), and a closing dialog
  hands focus back to its own opener, which settings then records as its opener. So on a covered
  route the close restores to that control, and the fallback is asked only if the control has gone
  by then — a window that passed meanwhile, which #858 left on `<body>`. From the nudge, that now
  lands on the origin slot. The cog and the ⌂ pass no fallback, so they still leave it there.
- **Save, and Look up.** A successful save removed its own button, and focus now lands on the
  line naming the new home. The field is the fallback, and the dialog root the last resort. Both
  buttons were also `disabled` while busy. Measured in Chromium, WebKit and Firefox: a focused
  control that becomes `disabled` loses focus, at once in Chromium and within one to four frames
  in the other two. A failed save therefore left the reader on `<body>` too. Both now say they
  are busy with `aria-disabled`, styled with `.btn-primary`'s own `disabled:` treatment, and
  refuse a second press. That also stops repeated Enters in the postcode field from racing two
  lookups for one result. It does not look identical in two cases. Forced-colours mode repaints a
  `disabled` button's text and border in GrayText and leaves an `aria-disabled` one alone
  (measured in Chromium), so there the busy state shows only as the dimming. And a button pressed
  with the mouse now keeps its focus through the request, so in Chromium, which focuses a pressed
  button, `.btn-primary`'s `focus:` ring stays drawn.
- **The drive-time refresh**, which replaces the whole dialog body three times. Focus now moves
  spinner status line → "Back to settings" (described by the count it reports) → the "Last
  calculated" line the refresh changed. On a failure it returns to "Refresh drive times",
  described by the error. Each view's first element is now keyed. Without the keys, React
  reconciled the spinner's focused status `<p>` into the result's `<p>` by position, stripping
  its `tabindex`. jsdom left focus on it; the browsers do not (measured: removing `tabindex` from
  a focused element sends focus to `<body>`).
- **The map-colour radios** sat in a `<fieldset disabled>` while saving. They stay enabled now,
  as the radius slider does, with "Saving…" in the existing live status. The fieldset had also
  kept saves from overlapping, so every choice now joins **one line of saves for the page**
  (`utils/colourSaveQueue.js`), which `App` owns and hands to every opening of the dialog. The
  line sends one save at a time, in the order chosen, and skips a choice a newer one has overtaken
  when its turn comes, so the newest choice is always the last one written. It is the page's
  rather than the dialog's because the dialog's saves outlive it: review found that a line per
  opening let a closed dialog's waiting choice go out after a newer one made in the reopened
  dialog, leaving the server and the map on the older scale. Every save that lands still reports
  its scale to the page (`onColourSaved`), in the order the choices were made. A dialog opened
  while a choice is still in the line opens on that choice and follows it ("Saving…", then the
  error if it fails) until the reader chooses again. One whose read answers after a save has
  landed shows the save, not the read's older scale. On the base, a reopen that overlapped a save
  showed the scale that save had just replaced whenever the server took the read before the save
  committed (driven that way in all three engines).

Each landing moves the reader only when focus has actually been orphaned (`<body>`, the document
or the dialog's own root). A reader who has moved on is left alone: a Tab away during a save, or
out of the dialog during a refresh.

The text landings (the new home, the refresh's status line, "Last calculated" and the refresh
error) draw an **outline**, not the buttons' `ring`, for two measured reasons. Forced-colours mode
(Windows High Contrast) removes every `box-shadow`, so a ring left no focus indicator at all. And
the ring's 2px offset stood 4px proud, exactly the gap between "Last calculated" and the Refresh
button, so it sat on the button's edge. The two button landings, "Back to settings" and Refresh,
keep their `.btn-*` ring, so in forced colours they still land with no indicator. That is true of
every `.btn-*` in the app and is left to its own change. The tick line's shared focus rule gained a
transparent inset outline for the same forced-colours reason, with ⌂'s inset a pixel further to
clear its own 1px border. The outline paints nothing outside forced colours. Inside it the
statement now shows focus (measured), and the nudge, the origin button, ⌕ and ⌂ take the same
outline from the same rule (not separately measured).

**Verified:**
- **Tests.** 86 more tests than the base, across `useDialogFocus`, `Modal`, `MastheadTickLine`,
  `UserSettingsModal`, `App`, `AppSettingsRoutes` and the new `colourSaveQueue`. The `App` tests
  drive #842's real routes: a save from the Map-tab nudge, the dialog's read answering after the
  close, a later opening from the cog, and colour choices across a close and a reopen. Two more
  drive #858's covered routes where the covered dialog's opener can no longer take focus at the
  close: from the nudge the reader lands on the nudge, and from the cog they are left on
  `<body>`. #858's shell test that pinned the nudge's argument as a click event now pins it as
  the tick line's resolver. The forced-colours outline rules are pinned by reading `index.css`,
  since jsdom renders no CSS. That the landing runs in a layout effect, in the commit that removes
  the pressed control, is pinned too: a page's own layout effect in that commit already sees the
  landing.
- **Mutation sweep.** 73 mutants against the final tree, all killed, each by the tests written for
  it; the ones touching `App` were run again after rebasing onto #858. They cover the fallback's
  consultation and its guards, the handoff and its StrictMode record, every landing and whether its
  target can take focus, the busy buttons, the colour line's order, skipping, `pending` and
  landings, the carried choice, `App`'s wiring, and the forced-colours rules. The
  layout-to-passive mutant, which the first sweep could not kill, now fails the same-commit test.
  One candidate was left out as equivalent: reporting the handler's own choice instead of the
  scale the line passes back, which is the same value now that each choice has its own reporter.
- **Real browsers.** A harness mounted the real components on development React under
  `StrictMode`, with a faked settings API and the page's settings wiring as `App` has it
  (including #842's reports). It was driven with real key events in Chromium 151, WebKit 26.5 and
  Firefox 153, against main before this change (#858 changed none of the components it mounts) and
  with it. Before, every landing left the reader on `<body>`, except "Back to settings" in WebKit,
  which left them on the dialog root. With this change every landing reached its target in all
  three engines. That covers both nudge orders, a colour choice waiting in a closed dialog behind a
  newer one made after reopening, and a reopen whose read answered after a save had landed.
- **Forced colours.** Chromium's forced-colours emulation measured no indicator before and an
  outline after, for both the "Last calculated" landing and the statement.
- **Not seen:** the app itself (sign-in required), any screen reader, and a real Windows High
  Contrast theme. Not driven in a browser, and tested in jsdom only: #858's covered routes, a
  fallback declined beneath a dialog still claiming `aria-modal`, a still-mounted caller clearing
  its fallback on close, and the cog after the nudge. Busy buttons now keep focus at
  `.btn-primary`'s 40% opacity: the ring then composites to about 3.3:1, computed, not measured.

Not announced, and left to their own change: a lookup's result or error, a failed postcode save
(which still shows no message), and a change a reader who has moved away from it misses. A polite
status region shared by the dialog's three views is the likely shape.
