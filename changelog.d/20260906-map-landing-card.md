### Added — the Map tab opens on an answer, not a map

On a cold open the Map tab now shows a **landing card**: the next two solar windows, each with its
served verdict and the region that verdict is true of, and the forecast's Best bet / Also good
riding the row it belongs to. Its header is derived from the windows on screen ("Tonight, or
tomorrow?" across two days, "Tomorrow — sunrise or sunset?" within one), so it can never name a
window the card is not showing. A pick that falls later in the week becomes one quiet line; a pick
earlier than the first row is dropped, because a window that has passed is not an answer. When
neither of the two clears Poor the card stops comparing and names the next window that is genuinely
Worth it — never a Maybe, and never one of the two it has just dismissed; when no such window
exists the sentence simply ends.

It opens **once per forecast run** and is dismissed by its close control, `Escape`, or selecting a
row — and by nothing else. Not a map click, drag, zoom, wheel or outside tap: panning to the region
it has just named is reading the card, not finishing with it. Dismissing is recoverable — the same
header text is the first row of the window pill's menu, the way `RegionsJump`'s reset row already
works.
