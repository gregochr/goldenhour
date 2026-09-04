### Removed — marker clustering, everywhere on the map

Locations no longer collapse into numbered bubbles as you zoom out. Every spot draws as itself, on
both the Map tab and the map that opens from a plan card.

Clustering was the thing the heat field was built to replace. A bubble reading `12` cannot tell you
whether tonight is worth driving for — it averages the good evenings in with the bad ones, which is
why the warmth map became the default view in the first place. Keeping it alongside meant the Pins
view, which exists as the honest side-by-side comparison, was quietly doing the same averaging: you
could never see the pile the heat map is there to solve.

Pins already handles a crowded map on purpose — the best-scoring dots draw on top, and named spots
draw larger than unnamed ones. Clustering was undoing both.

One smaller thing goes with it: clicking a location's name label no longer jerks the map to a new
zoom level. That camera jump existed only to break a cluster open.
