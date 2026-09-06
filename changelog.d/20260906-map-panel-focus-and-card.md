### Fixed — the map's panels hand the keyboard back, and no longer bury the card

Three routes out of the drilldown left the keyboard on the page body: closing either panel with its
✕, and opening a location's four-day sheet from a region row. The last was the worst — it is the
route whose whole point is that you can back out of the peek to what you were reading, and closing
the sheet returned you to the top of the document instead. Focus now goes back to the window pill,
which is the control the drilldown hangs from and the one thing on that route that survives the press.

Opening the drilldown also dismisses the landing card rather than covering it. They arrived together
on the first visit of every forecast run, and the panel hid the card entirely on a phone — including
its close button, which was then unreachable by pointer since the card deliberately survives a tap on
the map. The two ask the same question one after the other: the card asks which window, the drilldown
asks where on it.

The window panel now takes focus when it opens, so a screen reader announces it. It previously handed
focus to the pill — which fixed Escape but left the reader outside a dialog that had just appeared,
with nothing said.

### Fixed — the Plan tab's window card and its region rail agreed again

On a forecast cached before the display-verdict field existed, the card at the head of the window
popup read "Not scored" while the region rail directly beneath it read "Worth it". Both now read the
same value through the same resolver.
