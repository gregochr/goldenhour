### Added — Ask PhotoCast reads each window's rating distribution, and says stay home when it is

`list_windows` now serves, for every solar window, the spread of its ratings over the question's scope:
how many rated places sit at each star, how many are 4★ or better ("game on"), the regions holding a
game-on place with their means (at most four), and how many regions were stood down whole, not scored or
too thin for the Plan tab to give a verdict. A wood and a place the neighbour guard withholds are out of
the counts. The system prompt carries the owner's scale (1–2★ stay home, 3★ marginal, 4★ game on, 5★ go),
tells the model to read the spread before the best rating and to call a window of almost all 3★ with a
handful of 4★ a stay-home window with exceptions, and replaces "give 2 or 3 picks" with "up to three, never
fill the list with marginal places". The result cap rises from 6,000 to 9,000 characters.

A shared Ready answer for "Best spot this weekend?" (and the other solar pick questions) may now honestly
say there is nothing worth going out for when no covered window holds a game-on place the question admits,
and is withheld the moment one appears. A Ready answer carries the whole spread of every window its prose
may have quoted and is withheld whole when any of those figures has moved, or a window it covered has
passed or been added. `StubAskEngine` reads the same figures, so local runs show the shape.
`BriefingRegion.coverageReason` (serve path only, never persisted) tells a real weather stand-down from
the "no record" the honesty filter words the same way. No migration; the client line that draws the
figures is a later change.
