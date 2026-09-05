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
bar rather than a masthead's height below it, `--wf-lens-reserve` is the bar plus a focus ring again
(60px desktop, 136px phone) so a focused card is no longer scrolled 128px further than it needs to
go, the stuck-shadow sentinel insets by zero, and the Coming up tab's scroll-to-entry target drops a
~134px reservation against chrome that was never pinned there either. `--wf-mast-h` is deleted
outright — a published measurement of an element that does not pin is what invited the next rule to
reserve against it.

Pinning the masthead for real remains open, and costs about 182px of permanent chrome on an 800px
viewport, which is why it was not simply fixed in place; `index.css`'s `.wf-mast` block carries the
checklist a phase taking it would have to work through.
