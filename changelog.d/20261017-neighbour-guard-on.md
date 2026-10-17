### Changed — the neighbour guard is on by default

A sky rating that stands two or more stars above every place within 5 km, when at least two of those places are at a similar height, is no longer named as a destination. Ask no longer offers it as a pick, the BEST BET / ALSO GOOD window pick no longer names it, and the Plan card's best-reachable line and popup header skip it (naming the region only when nothing else is rated 3 or better). Its star is unchanged everywhere it appears: the map, the location sheet and the window's best figure still show it.

Production uses the code default, so no host config change is needed. To roll back, set `photocast.neighbour-guard.enabled: false` and restart; nothing is then flagged and no location coordinates are read.
