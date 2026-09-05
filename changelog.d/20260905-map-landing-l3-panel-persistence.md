### Changed — nothing on the map closes because you looked at the map

Opening the week list, the Regions jump, Filters or the Legend and then panning to see what one of
them just named used to lose it: a press anywhere on the map closed whichever panel was open. All
four are *about* the map, so reading "Thursday is Poor" and then going to look is one action, not
two — and losing the list halfway through made every panel feel like something to be got rid of.
They now close on their own chip, on their own close control, or on Escape. A press on bare ground
still deselects a location, because that is a selection rather than a panel.

The rule reads like one change to one handler and is really five: the ground-press handler plus each
panel's own outside-click listener, which fire first. They are now one shared hook, so they cannot
drift apart, and a press outside the map — the masthead, another tab — still dismisses.

Escape had to grow with it. Each panel only ever heard the key when focus was inside it, which was
guaranteed while a map press closed panels and is not any more: a reader can very ordinarily have a
panel open with focus on the map. The map now closes an open panel on Escape itself — but only when
there is no dialog over it, so a press while the four-day sheet or the settings window is up still
operates that window and never the page behind it.

Two limits are worth stating rather than discovering. On a phone, Filters and the Regions list are
bottom sheets whose backdrop covers the map, so a tap there still closes them and the map cannot be
panned while one is open — unchanged by this work, and a separate decision about that sheet. And a
panel opened by a handoff from another tab can start with focus nowhere near the map, where Escape
does not reach it until the reader touches the map once.
