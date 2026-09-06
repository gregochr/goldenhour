### Added — the Map tab's window pill states the verdict

The pill now says what the window it names is worth. The verdict word sits over the region that
verdict is actually true of — "Worth it / the Lakes", or "the Lakes +2" when others share the band,
or "everywhere in your area" when they all do and there is no one region to send anyone to. The
window's own tier tints the pill's left edge. When the window on screen is the forecast's Best bet
or Also good it wears an outline medallion, and both steppers carry a small bar in the colour of the
window they would take you to, so `‹ ›` stop being blind: you can see whether the night either side
is better before spending a tap.

A night row states none of this. Astro and aurora are scored on darkness, clarity and Kp rather than
on the colour forecast, so they have no verdict to borrow and the cell is simply empty — nothing is
invented to fill it.

The control's width had to be rebuilt to hold all this. It has been a fixed 262px since #773, which
stopped the steppers travelling as the reader stepped; the new content reaches 417px, so a fixed
262px would have truncated the day label — the one thing the design says must never truncate. The
width is now a property of the frame rather than of the content: the group is bounded clear of the
Regions/Filters cluster, given a declared width, and the pill fills it. At any given window size
every event still renders the same width, which is the property that mattered all along.

Under pressure the yield order is the design's own — the region line ellipses first, then the
medallion's words, and the day label last. On a phone the region goes and the medallion keeps its
glyph; below 390px the medallion goes too, so the day keeps its space. The medallion's words are
hidden from view but kept for screen readers, and each stepper now says which verdict it is pointing
at rather than leaving that to colour alone.
