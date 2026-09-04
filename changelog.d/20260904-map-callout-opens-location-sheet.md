### Added — the map callout's clamped prose now opens the location sheet

The Map tab's selection callout clamps a Claude narrative to three lines. With a real ~90-word
summary that left three dots and no way to reach the rest. The clamped prose is now a button
captioned `Four days here ›`, opening the **existing** location sheet — the one the Plan tab
already has — rather than a second panel that would drift from it.

The sheet gains one row, directly under its header: subject tags, dark sky, coastal/tide and the
week's topics. Those facts previously existed only on the callout, so without them the route would
have lost information; with them the callout is a strict subset of the sheet. The row is derived
from the roster record the sheet already has access to, so every way in gets it — search, a popup
field chip, a spot card, the map callout — not just the map's.

The sheet also opens **on the window whose prose was clicked**, and its `Show on map` action points
back at that window rather than at whichever one scored best — the promise is "the rest of *this*
narrative", so the row carrying it is the one that opens. And its prose now falls back to the
region's gloss exactly as the callout's does, so the deeper surface can never show less than the
card that routed into it.

(The tide-alignment glyph the same increment specifies is not here: it ships in #749, which serves
the answer per location rather than folding one coastline's geometry onto every coastal chip.) The alignment is also stated in words on the callout and on each
sheet row, naming the coastline the figures were measured at: `BriefingWindowTide` describes one
representative location and alignment differs by 20–30 minutes along a coast, so an unattributed
high-water time is a claim this project does not make.
