### Fixed — an uncovered dialog no longer strands a reader at the top of the page

Open a Plan dialog, press `/` for search without touching anything inside it, then press Escape.
The dialog underneath is uncovered and still claims `aria-modal="true"` — and focus was left on
`<body>`, so the next Tab walked the page behind the backdrop instead of the dialog on top of it.
That is verbatim the defect `useDialogFocus` was written to prevent, reproduced by the effect
written to fix it.

The cause is a gap between two deliberate rules that were each right on their own. `Modal` records
the last **control** focused inside itself so that an uncover returns a reader to the chip they were
on, and it excludes the dialog's own root from that recording — otherwise the restore would land on
a container rather than on the thing they were using. But a dialog *opens* onto exactly that root,
so a reader who touches nothing inside leaves the recording empty, and the restore's `!node` early
return then did nothing at all. Nothing was ever decided about that case; it was the shape of an
early return.

A null or detached recording now falls back to the dialog root — which is only a restatement of
where this component already puts focus when a dialog opens. That answer needs no focusable child
(the settings modal's refresh spinner has none), survives content that has not loaded yet, and
announces the dialog's accessible name to a screen reader on landing.

⚠️ **The ordering it depends on is a browser fact the suite cannot check.** `inert` makes `focus()`
a silent no-op, so the fallback works only because React clears that attribute in the mutation phase
and the restore runs after it — and jsdom implements no `inert` at all, so the tests would stay
green either way. Measured directly in Chrome 148: `focus()` on an inert root leaves focus on
`<body>`, and the same call after clearing `inert` lands on the root, both in the same task and
across a microtask. The existing restore branch has always relied on the same ordering; it just
never had a measurement.

⚠️ **It does not bite every time on every engine.** Paired old-vs-new runs through Playwright: on
WebKit the reader is stranded on every attempt; on Chromium only when the covering layer mounts a
frame or more after the press, because a cover that mounts in the same frame captures the dialog
root as its own return address and hands it back on unmount. That delay is the normal case on first
use — search, the window popup and the four-day sheet are all lazily loaded behind a `Suspense`
boundary — and the rare one afterwards. A related measurement corrects a claim that has been in this
component's docs for a while: setting `inert` does **not** blur synchronously, in either engine; the
fix-up lands two to four animation frames later.

**It is a keyboard route.** An earlier draft of this entry claimed the ordinary tap-a-chip route
reached it too on macOS and iOS Safari, which do not focus a `<button>` on click — and then said, two
sentences later, that the recording listens on `pointerdown` for exactly that reason. Both cannot be
true, and the second is the true one: `pointerdown` fires on Safari, every control that stacks a
layer over a Plan dialog is a real button inside it, so a pointer route populates the recording and
never reaches the fallback. Corrected rather than quietly dropped, because reading that existing
comment backwards is an easy mistake to make twice.

The restore's "is this a mount or an uncover?" test used to be inferred from the recording being
empty, which is sound only while an empty recording means "do nothing". Making it actionable would
have turned that inference into "focus the root on mount" — in a passive effect, ahead of the
deliberate frame `useDialogFocus` defers to and the consumer-autofocus yield that exists because of
it. An explicit `wasStacked` ref now carries that question, pinned by its own test.

Scope: four Plan-shell dialogs pass `stacked` at all, and only two of them can reach the new branch
in production — `WindowSpotSheet` and `WindowPickDialog` are stacked only while search is open, and
the shell refuses to open search over either. The path also needs a genuine uncover with focus
already orphaned. Every other dialog in the app is untouched.

The orphan test now counts `document.documentElement` alongside `<body>`. Both mean "nowhere", and
this app has measured the second: the shell's tab-select records focus landing on the document root
after the overlay's map hatch. Whether an `inert`-driven blur can land there too is unverified —
jsdom always yields `<body>` — so the guard covers both rather than betting on one.
