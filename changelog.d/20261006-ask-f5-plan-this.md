### Added — Ask PhotoCast: "Plan this", "Open in Plan ›" and the Plan-card highlight

Every pick card in an Ask answer now has a **Plan this ›** button, on the docked column, the tablet and phone sheets and the
phone Map's Ask section. It swaps the answer for that pick's own view: **Leave home** (when to set off, with the day if the
drive crosses midnight), **Drive** (from home), **Best light** (golden and blue hour, in the order they happen) and **Tide**
(the water at the light, with the wave glyph that says whether it is the water the spot wants), then the spot's own one-line
reading as a note — only when the forecast has one, never an invented tip about parking or access. These are the four-day
location sheet's own functions over the same forecast, so the two never disagree; the drive is always from home, whatever
the Plan origin is. With no drive time the two cells read a dash, and a reader who has no postcode saved is offered the
masthead's "Set a postcode" button. "‹ Back to the answer" returns to the answer with the pick still chosen and focus back
on the button that opened the plan.

**Open in Plan ›** is the only action there ("Add to Coming up" was removed). It moves to the Plan tab and opens the pick's
four-day sheet at the pick's own window. On the tablet and phone sheets Ask closes first, so the location sheet is the only
dialog; on the docked column Ask stays open beside it (and is inactive while the sheet is up). Closing the sheet puts focus
back where you were: the dock's button, or the Ask bar or field you opened the sheet from — never the top of the page.

On the Plan tab the selected pick's window card is now highlighted: a gold edge with a second inset pixel and the pick's
number on the card's corner, scrolled into view, and "Ask pick N" in its accessible name. It is a different mark from the
open card and the two can sit on one card; it does not open the popup. It is live while the dock is open, and applied when a
sheet closes. A pick whose window has no card highlights nothing.

"Clear answer" now works from the plan view too, and choosing another pick (a chip on the map, say) leaves the plan for the
answer. `lightWindows` and a new `departureWithDay` are exported from `utils/locationSheet.js` for the plan view; the sheet's
own rows read the same functions.
