### Docs — five Plan-tab comments still described routes M5 closed

`WindowFirstShell`'s `onPickRegion` handler explained its `openOverPopup(null)` with "Search can now
sit over a location sheet that is itself over the popup". True at M4, false since M5, which refuses
the third layer outright: all three routes into search — the `/` shortcut, the masthead button and
`WindowFirstHeatStrip`'s `onSearchRegion` — guard on `stackedOverPopup`, which counts `sheetSpot`.
The supported stack is two deep (plan-matrix §4 A22): search over the popup, or a sheet over the
popup, never both.

The handler now gives the reason that is actually load-bearing. `openOverPopup(null)` belongs there
because moving the origin under an open sheet would change the drive, the base named beside it, the
outside badge and every departure beneath the reader — P8's invariant, which M4.3's close-then-move
footer protects at the one route where a sheet genuinely is up. It also says plainly that its own
arm cannot fire with a sheet up today: the invariant is stated once per route so a route added
later inherits it, not evidence that the refused stack is reachable.

**Three more said the same thing in different words**, and a keyword sweep for the first one's
phrasing found none of them:

- `onPickWindow`, three lines below, justified its identical belt with "the location sheet is still
  on top" — the same simultaneity, as a premise rather than a claim.
- `WindowSpotSheet`'s `escapeEnabled` said it "declines Escape while search is over it". Search
  cannot be over it; `stackedOverPopup` counts that sheet too. The prop is kept — the component
  derives `stacked` from it and the two must not come apart — but it is now named as a belt that
  cannot go false.
- `LocationFourDaySheet`'s class comment published the bundle README's three-rung order (search →
  this sheet → the popup) as the shipped behaviour. Its own `escapeEnabled={searchSeed == null}`
  gets the `WindowSpotSheet` treatment, with the second reason it can never engage: `PlanSearch`
  calls `onClose` on every pick, so a search result closes search in the same commit that opens the
  sheet.

**A fifth was a stale route rather than a stale stack.** The tick line's `onGoHome` justified its
own `openOverPopup(null)` with "a keyboard reader inside an open location sheet can reach this
button". M5 closed that walk with the fix three lines above it: `searchOpen` is
`searchSeed != null || stackedOverPopup`, and `MastheadTickLine` puts `tabIndex={-1}` on all four
controls in the row when it is set — pinned per control by `MastheadTickLine.test.jsx`'s "takes %s
out of the tab order".

That handler's two calls are no longer on the same footing, so the comment now separates them
instead of covering both with one route. `openWindow(null)` stays live: `useDialogFocus` is not a
trap and nothing makes the masthead inert, so a Tab walk out of an open dialog reaches that row —
M5 measured press 17 — and with only the popup open `searchOpen` is false, so the stops are still
there and the button is reachable from inside the popup. `openOverPopup(null)` is the belt, since
any layer standing over the popup is exactly what removes those stops.

Comment-only throughout; no behaviour changed and no prop moved.

Left alone deliberately: `WindowSheetDialog`'s "search → a stacked sheet → this" and the
`stackedOverPopup` docblock's matching line are **precedence** orderings — which layer answers
Escape when it is on top — not claims that all three can stand at once, and the latter states the
refusal in the sentence before it.
