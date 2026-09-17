### Fixed — pressing ⌂ in the masthead no longer drops a keyboard reader on `<body>`

⌂ ("Plan from home again") exists only while the plan's origin is away, so pressing it removes
it. The focused button was destroyed and focus fell to `<body>` on every tab: the next Tab
started at the top of the document, and a screen reader lost its place. Measured before the fix,
with Enter and Space, in Chromium 151, WebKit 26.5 and Firefox 153. It also reproduces in jsdom on
the Plan tab, on the Map tab, with no home saved, and under StrictMode.

⌂ now hands focus to the tick line's origin slot, reusing the handoff #859 built for the slot's own
element swaps. ⌂'s ref cleanup runs while React still has the node attached, and records the
departure only if ⌂ held focus. The same layout effect then focuses whatever the slot holds once
home:
- on the Plan tab, the origin button ("Home · Durham");
- on every other tab (Map, Coming up, Operations), the statement, which can take focus but is
  never a tab stop;
- with no home saved, the "set a postcode" nudge.

It stands down if something else placed focus in the same commit. The departure record and #859's
StrictMode guard now live in one helper, `watchDeparture`, which the slot and ⌂ share.

**One route now lands somewhere different, by owner decision.** The shell's `onGoHome` also closes
the window popup, and ⌂ is reachable from inside the popup by Tab. On that route alone, focus used
to go back to the card that opened the popup, restored by the popup's `useDialogFocus` cleanup (a
passive effect). The handoff is a layout effect, so it now runs first. The restore then finds focus
somewhere real and no modal layer left, and stands down. So every route ends on the origin
control. A test through the real shell pins this order: moving the handoff to a passive effect
fails it.

A mouse press lands the same way where the browser focuses the button on click (Chromium, and
Firefox in Playwright's build), but draws no focus ring. Where it does not (WebKit), nothing
changes.

The browser results come from a harness built on development React with StrictMode active, using
the real tick line and the real `Modal`, in all three engines. They were not seen in the running
app, which needs a sign-in. Mutation-tested: seven mutants, each killed by the test that targets
it. The Map breadcrumb's `clear` has the same defect; it is being fixed separately.
