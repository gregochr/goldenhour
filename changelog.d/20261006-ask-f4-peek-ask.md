### Added — Ask PhotoCast on the phone Map: the Ask row in the peek sheet

On a phone the Map's bottom sheet now has an Ask row above its three buttons — "ASK · Ask about what's on the map… ↑" —
which takes the sheet from 74px to 126px (with Ask off, or unreachable, the sheet is exactly what it was). Pressing it opens
the Ask section at 470px: the question field and a ✕ in the row, the Ready suggestions, and the answer with its pick cards
beneath, which replace the three buttons while they are open. Another section opened from the pill (Windows, Tide, Layers)
now stands at 408px under the Ask row.

Touching, dragging or zooming the map closes the suggestions, and minimises an answer to one 112px line — rank, spot, time,
"3 picks ▴" — which stays on screen while you look at the map, across a switch to another tab and back, and is opened again
by pressing it or by pressing one of the numbered picks on the map. The ✕ in the open row clears the answer and brings the
three buttons back. A question still being fetched is left open by a map touch. Escape, the window pill and a pressed chip
each do what the plan's table says for the state the sheet is in, and a spot's callout may now stand over the 112px line.
The camera fits the picks into what the sheet leaves uncovered and fits again when the answer is minimised; the label
placer treats the sheet as an obstacle while picks are numbered. A refusal ("Slow down a moment.") that arrives while the
row is closed is shown in the row until it has been read.

Under the hood the sheet's resting height is now state-driven: `--psh` is written on the Map pane (74 with no Ask row, 126
with one, 112 under a minimised answer) and the Leaflet corner, the bottom-left chrome and the callout's placement band all
follow it, the callout repainting when it changes. Focus is parked and handed on whenever a control that held it is replaced
by the answer, so Escape keeps working. Desktop and tablet are unchanged.
