### Fixed — the Plan tab's filter bar anchors to the top of the screen instead of floating below it

The reach/rating bar stayed on screen while the matrix scrolled, but it hung roughly a masthead's
height down the viewport, with window cards scrolling through the naked band above it and a heavy
shadow underneath. It now anchors flush to the top edge.

The cause was a `position: sticky` that never took effect. M3 pinned the masthead and rested the bar
against its bottom edge (`top: var(--wf-mast-h)`), but the masthead's containing block is the shell
wrapper holding only the masthead, the tab bar and the tab rule — about 46px taller than the band
itself — and a sticky element cannot travel outside its containing block. Measured in Chromium at
1280×800: the band pins for those 46px and is then carried off the top with the page, `bottom: -397`
by 600px of scroll, while the bar went on sticking against chrome that had already gone. M3's own
note records re-checking the invariant for `overflow`, `transform`, `filter` and `will-change`
ancestors; the containing block's height is the fourth way to break a stick and was the one in play.

The dead rule is removed rather than repaired, and everything derived from that height loses the
term with it: the bar sticks at `top: 0`, the day-tile row and the row rails stick directly under the
bar rather than a masthead's height below it, the stuck-shadow sentinel insets by zero, and the
Coming up tab's scroll-to-entry target drops a ~134px reservation against chrome that was never
pinned there either (worth about half that in displacement, since that one scroll centres the
scroll-margin box rather than the element). `--wf-mast-h` is deleted outright — a published
measurement of an element that does not pin is what invited the next rule to reserve against it.

`--wf-lens-reserve` — the scroll room every focusable thing on the Plan pane claims against the bar —
is the bar plus a focus ring again rather than a sum, so a focused card is no longer dropped a
masthead's height further down the page than it needs to be. Removing the masthead term also exposed
an under-reservation that had been latent since M1 and that the term had been accidentally covering:
both literals were the bar's SHORTER state, against a rule two comment blocks away that says to take
the tallest. They now obey it — 98px desktop (the bar's wrapped 92px, not its resting 53.5px) and
160px phone (the stacked bar's 153.5px with a non-default tier's two marks, not its base 130px). That
matters on the phone in particular, because `useReachLens` restores the tier from `localStorage` in a
lazy initialiser, so the taller bar is what renders in the first frame — the only frame a fallback is
ever used in.

The masthead keeps `position: relative; z-index: 45`. That pair is now a stacking context rather
than a stick, and it is load-bearing: `HealthIndicator` renders inside the band with a
`position: fixed; z-index: 9999` panel, which only ever composited below a dialog because a
positioned, non-auto-`z-index` masthead was a ceiling over its own subtree. Dropping the whole
declaration block — the first cut of this change — let that panel paint over the search dialog's
scrim while an `aria-modal` dialog was open, hit-testable. Reproduced and fixed against
`elementFromPoint` in Chromium.

Pinning the masthead for real remains open, and costs about 182px of permanent chrome on an 800px
viewport, which is why it was not simply fixed in place; `index.css`'s `.wf-mast` block carries the
seven-item checklist a phase taking it would have to work through.
