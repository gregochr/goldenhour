### Fixed — pressing ⌂ in the masthead no longer drops a keyboard reader on `<body>`

⌂ ("Plan from home again") exists only while the plan's origin is away, so pressing it removes
it. The focused button was destroyed and focus fell to `<body>` on every tab, where nothing on the
page shows focus and a screen reader can lose its place (not measured). The fall to `<body>` was
measured before the fix, with Enter and Space, in Chromium 151, WebKit 26.5 and Firefox 153. It
also reproduces in jsdom on the Plan tab, on the Map tab, with no home saved, and under StrictMode.

⌂ now hands focus to the tick line's origin slot, reusing the handoff #859 built for the slot's own
element swaps. ⌂'s ref cleanup runs while React still has the node attached, and records the
departure only if ⌂ held focus. The same layout effect then focuses whatever the slot holds once
home:
- on the Plan tab, the origin button ("Home · Durham");
- on every other tab (Map, Coming up, Operations), the statement, which can take focus but is
  never a tab stop;
- with no home saved, the "set a postcode" nudge.

It acts only while nothing has focus, so focus placed earlier in the same commit is left alone.
The departure record and #859's StrictMode guard now live in one helper, `watchDeparture`, which
the slot and ⌂ share.

**One route now lands somewhere different, by owner decision.** The shell's `onGoHome` also closes
the window popup, and ⌂ is reachable from inside the popup by Tab. On that route, focus used to go
back to the card that opened the popup, restored by the popup's `useDialogFocus` cleanup (a passive
effect). The handoff is a layout effect, so it now runs first. The restore then finds focus
somewhere real and no modal layer left, and stands down, so this route ends on the origin control
too. A test through the real shell pins this order: moving the handoff to a passive effect fails it.
The exception is unchanged: if `App`'s map overlay or the settings dialog is still open as well,
the restore treats the origin control as stranded and returns the reader to the card, as before.

A mouse press hands focus on the same way wherever the browser focuses a button on click (Chromium
and Firefox), with no focus ring drawn. A Space or Enter straight after it then opens search, or
settings from the nudge, where it used to scroll the page or do nothing; holding Enter does the
same by key repeat in every engine. That is a class the app already ships (a popup closed by a mouse
click returns focus to its card, and Space reopens it), and it is left alone on purpose: the signals
a gate could read also report a screen-reader or voice-control press as a mouse press (`detail` and
`pointerType` in Chromium's source, `detail` in Firefox's, and likely `:focus-visible`), so a gate
would strand exactly the readers this fix is for. WebKit does not focus a button on click, and
nothing changes there.

The browser results come from a harness built on development React with StrictMode active, using
the real tick line and the real `Modal`, in all three engines. They were not seen in the running
app, which needs a sign-in. Mutation-tested: eight mutants, each killed by the test that targets
it, one of them only after an adversarial review extended the placed-focus tests to catch a record
left standing. The Map breadcrumb's `clear` has the same defect and is tracked separately.
