### Fixed — the docs said a waterfall is not a sky subject; it is

Four comments and two engineering notes described `heatSpots.js`'s sky-subject filter as the thing
that withholds a waterfall's scores. It does not: the sky subjects are landscape, seascape **and**
waterfall, so a waterfall paints in the heat field and counts in the map's `N of M at 4★+` like any
other place. What the filter actually withholds is a wildlife hide, a wood or a bluebell site.

No behaviour changed — the code was always right and only the prose was wrong. It is recorded because
the wrong version had reached `CLAUDE.md`, which the project treats as authoritative, and because the
error had been copied forward three times before a fact-check caught it.
