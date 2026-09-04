### Changed — the Map tab keeps the masthead's column instead of going full-bleed (O-17)

Reverses one half of map-v2 P7: the Map tab's panel region no longer releases its width
constraint. It now shares the exact same `WRAP_MAX_WIDTH` (1080px) column the masthead and tab bar
use, on every tab including Map — one `style` object in `WindowFirstShell.jsx`'s panel-region
wrapper, not two copies of the constant, so the two can never drift apart again.

Bundle rev 2's case for the reversal is structural, not a taste call: full-bleed made the tab strip
look like it was floating above an unrelated surface, since the strip stopped at the content column
while the panel it belonged to carried on to the window edge. Full width also added sea and empty
moor rather than information — at 2400px one screen spans roughly 150 miles, labels crowd the right
third, and the window control and Filters end up a head-turn apart.

WIDTH ONLY. P7's full-frame HEIGHT behaviour is untouched — the `100dvh` flex recast, the
zero-padding `.wf-body.wf-body--map`, and the no-page-scroll contract all stand exactly as before.
Every overlay chip (window control, filters, legend, counts footer, the selection callout) is
absolutely positioned inside the map's own `position: relative` container rather than the viewport,
so all of it narrows with the column automatically — no changes needed in `MapView.jsx`,
`MapCallout.jsx` or any of the overlay components.

**`App.jsx`'s `<main>` gets `sm:px-4` on the Map tab, not zero padding** — an adversarial-review
finding. Below `sm` (640px) the column cap never binds anyway (390px is nowhere near 1080px), and
`<main>` stays padding-free there so P12's full-bleed phone chrome keeps the genuine edge it was
tuned against — so a real, deliberate residue survives only on the phone: the masthead still
shifts by `<main>`'s own 32px of horizontal padding on a Plan⇄Map switch below 640px, exactly as it
did before this change. At `sm` and up, `<main>` now matches every other tab's `px-4` inset instead
of dropping it — tablets and desktop get the SAME horizontal padding as Plan/Coming up, which is
what makes O-17's "the masthead's column never moves" claim true in that range; an earlier cut of
this change left `<main>` padding-free unconditionally, which put the masthead 32px narrower on Map
than on every other tab between 640px and ~1112px — the exact disagreement O-17 exists to close.

Two stale comments citing the old full-bleed rationale (`WindowFirstShell.jsx`'s panel-region
wrapper, and `index.css`'s `.wf-body.wf-body--map` block) are rewritten to record the reversal
rather than left contradicting the code.
