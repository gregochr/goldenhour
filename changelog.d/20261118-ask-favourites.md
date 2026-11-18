### Added — Ask answers a favourite and the favourite-blind best as a labelled pair

Ask now knows a Pro or Admin reader's favourites. In `rank_spots` a favourite only breaks an exact tie (never beating
a higher rating, the forecast's own pick or a better tide fit), and when it does, the answer names both places: the
favourite as the lead and the equally rated favourite-blind winner as "also scores the same", so a favourite never
hides an equally good place. A lower-rated favourite is named at its own rating ("your Mary's is 4★"), never as the
best. `favourites: true` asks about the reader's own places only. An order the stars do not change is answered and
cached exactly as before; a personal answer is cached per favourites set, so starring or un-starring can never serve a
stale answer. A plain "Where is best?" names a favourite that ranks lower ("your Mary's is 4★"), and `favourites: true`
asks about the reader's own places only. Ready-matched answers stay favourite-blind, and Lite readers are unchanged.
