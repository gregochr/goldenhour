### Fixed — the drilldown closes when its window is retired under it

The forecast retires a window once its event has passed. With the region drilldown already open on
that window, the panel stayed up and went on showing each region's verdict beside a count of zero
locations — the figures were read from the wider forecast payload while the map had nothing left to
draw. It now closes with the window.

`Zoom to region` also left the keyboard on the page body, like the two exits fixed alongside it.

If the window is retired while you are reading the panel, the keyboard comes back to the window pill
rather than being left on the page body.
