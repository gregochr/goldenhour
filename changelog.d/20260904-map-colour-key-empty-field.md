### Fixed — the Map tab's colour key no longer explains a gradient that isn't there

Stepping the window control past the days the forecast has scored lands on a real, selectable event
that simply has no ratings behind it yet. The map painted nothing there — correctly — but still
showed the `Poor → Worth it` colour key above the empty ground, and withheld the line that would
have said why. The same happened on any date the event list does not reach.

Both now do the honest thing: the key appears only when there is something for it to be a key to,
and "This event is not scored yet" says so on any event you can actually select. A date with no
event at all stays quiet, because the window control already says "No forecast window" and does not
need answering twice.

This was masked until now by the old location medallions, which used to reappear as you zoomed in
and made an empty map look merely sparse.
