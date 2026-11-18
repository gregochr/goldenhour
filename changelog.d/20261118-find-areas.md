### Added — Areas in Find, and a typo-tolerant search

Find now lists **areas** (named areas such as Hadrian's Wall, and sub-regions) between the regions and the places, matched on their name and their other names (`roman wall` finds Hadrian's Wall). An area row says what it is and how many of its spots are rated (`Sub-region · 16 spots · 5 rated`) and shows the top rating among them with the window it belongs to; it is never a verdict on the area, and for a Lite reader it says how many spots are outside their picks. On At a Glance and Coming up an area opens a sheet of its spots, each with its own best window, best first, each opening that place's next-days sheet, with `Show on map →` at the foot. On the Map an area flies there and shows only its spots, with a `Showing <area> · clear` chip to lift it.

Search also forgives one typo in a word of five or more letters (`hadrain` finds Hadrian's Wall); an exact match always comes first and is never pushed out by a near miss, and short words such as `st` and `bay` still have to match exactly.

New for readers of any role: `GET /api/areas/directory` (the areas and their enabled spots, shared and cached) and `GET /api/areas/outlook` (your own per-window counts and top rating, never cached).
