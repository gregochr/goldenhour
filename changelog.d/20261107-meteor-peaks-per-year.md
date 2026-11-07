### Changed — meteor shower peaks follow each year's IMO calendar

A shower's peak moves by a day or two between years, so the peak nights and rates now live in a new
`meteor_shower_peak` table (2026 and 2027 seeded from the IMO calendars) instead of one fixed date in code. The
Geminids' 2026 peak is the night of 13 to 14 Dec, not 14 to 15, and the Orionids' is 20 Oct, not 21 Oct. The hot
topic, the Coming up feed and the dark-sky clarity scan all read the same per-year dates and use that year's rate.
A year with no row falls back to the old date, so nothing breaks if the table is empty.
