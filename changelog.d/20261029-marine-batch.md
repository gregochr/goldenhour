### Changed — the briefing's sea-state refresh fetches coastal locations in batches

The marine refresh now asks Open-Meteo for 20 coastal locations per request instead of one request per
location, reads the existing `marine_wave` rows in a single query and saves the upserts together. The high-water
refresh for structure locations reads from the briefing build's tide snapshot instead of querying per location.
A failed request still persists nothing for its locations and still clears their stale high-water rows. The rows
written are unchanged.
