### Fixed — the Map tab's old medallions no longer come back on the Heat view

Past zoom 12 the Heat view faded the pre-v2 cluster bubbles and coloured location discs back in
underneath the labels, so a street-level Heat map carried two vocabularies for the same places at
once: a chip naming a location and giving its star, and a medallion under it saying the same thing
in the language the tab replaced. Dense corridors got a `3` and a `2` cluster bubble on top.

Three other routes to the same doubling turned up while fixing it, all of them from a rule that
handed the medallions back whenever the field had no points to paint: a forecast day beyond the
briefing's scored window (one click away in the window control), a well-rated window narrowed to
nothing by the dark-sky filter, and a scored window whose points failed to join. None of these
means "nothing is scored" — and even when nothing is, the map was never blank, because the labels
draw for unrated locations too.

So the medallions are now simply hidden the whole time the Heat and Pins views are on screen,
whatever the zoom and whatever the window. Selecting a location no longer flashes them in either;
the callout draws its own anchor ring. They still appear where they are the only thing there is:
an aurora window, and a catalogue with nothing scored at all.
