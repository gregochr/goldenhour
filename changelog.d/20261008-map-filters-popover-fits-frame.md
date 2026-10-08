### Fixed — the Map tab's Filters popover fits the map frame and scrolls

On desktop and tablet the Filters popover had a fixed maximum height of 420px and drops from the
bottom of the top-right cluster, so on a short map frame (a ~1000px window was enough) it ran past
the frame's bottom edge. The frame clipped it, and the Sky row (dark-sky toggle) and the Scope row
below it could not be reached at all. The popover now measures the room between its own top and the
bottom of the map frame (or the viewport, if that comes first), caps its height there, and scrolls
inside it by wheel, scroll bar and Tab; a control Tabbed to at its edge is brought in far enough to
show its focus ring. While it is open the top-right cluster also takes the menus' place in the
stacking order, so the bottom-left chrome (the tide strip, the counts footer) no longer paints over
its last rows on a narrow frame. The phone's Filters sheet in the peek sheet's Layers section is
unchanged; it already scrolled.
