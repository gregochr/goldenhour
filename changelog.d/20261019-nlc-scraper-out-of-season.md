### Fix — NLC sighting scraper no longer hammers a 404 page out of season

`NlcSightingClient` fetched `…/nlcnet/2026-october` every 10 minutes all year, logging the whole ~30 KB 404 HTML page each time (NLCNET only publishes May–August). It now skips the fetch outside the NLC season (`NlcClarityService.isNlcSeason`, the hot topic's own definition; one INFO line a day), stops retrying a URL that answered 404 until the next UK day, and truncates any other logged error message to 200 characters. A pinned, placeholder-free archive URL is unaffected by the season gate.
