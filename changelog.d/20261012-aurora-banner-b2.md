### Added — the aurora banner shows tonight's aurora field and the best location once per-location scores exist (aurora banner, B2)

While an aurora alert is up, the banner now asks for tonight's per-location ratings (the live scoring
when it has any, else the stored run for the night in progress; a simulated alert asks for nothing).
Once your planning area has a rated site, the 96×118 slot at the card's left swaps the Kp meter for
the aurora field itself, clipped to the coastline and framed to the area, and the body line reads
`Best: Kielder 4★ · 147 sites clear` (plus the live Bz); on a phone the strip's second line reads
`Kp 8 · best Hackfall Wood 5★`. The best site is the most stars among the rated sites inside your
planning area, ties going to the shorter drive from home and then the name, so the banner never names
a site beyond the area it draws; an alert whose only rated sites are outside it stays on the Kp meter.
A failed score request leaves the banner as it was, and the field loads as its own chunk with a
fallback to the Kp meter if that chunk cannot be fetched, so a decorative picture can never take the
page down. To reach the planning area the banner now sits inside the briefing provider: the error
boundary and the provider wrap the banner block as well as the page, without adding any element, so
the Map tab's layout is unchanged; a crash in the Plan now also takes the notices above it away
behind the fallback, which has its own Sign out. Frontend only.
