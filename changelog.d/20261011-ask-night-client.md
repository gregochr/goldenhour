### Added — Ask PhotoCast's night pick card (N3 of the Ask night series)

The client half of `docs/engineering/ask-night-plan.md`: an Ask pick on a night window (`ASTRO` or
`AURORA`, window ids `YYYY-MM-DD_astro` / `_aurora`) now draws a card. It reads the place with
"4★ sky" and, for a PRO or ADMIN reader whose answer also named an aurora night, "aurora 3★", then the
kind, "Tonight · 20:40–05:10", "Bortle 3" and the home drive; a night has no verdict word and no tide, so
neither is drawn. The card is joined by (date, kind) to the night payloads, never to the briefing, and a
pick with no row in them is dropped rather than half-drawn (`buildPickCards` used to gate on the AM/PM word
and dropped every non-solar pick). The payloads are fetched once per answer for the DATES its picks name —
`GET /api/astro/conditions?date=` per astro date and `GET /api/aurora/forecast/results?date=` per aurora
date for PRO and ADMIN only — never for "tonight": the running night is yesterday before nautical dawn, and
only the PRO/ADMIN aurora status says so. A LITE client never calls the aurora endpoint. Nothing is kept in
`swrCache`, `localStorage` or module scope.

"Plan this ›" for a night pick shows "Leave home" counted back from the night's start, the drive, the served
dark window and the served Bortle class and moon, with the served astro summary as the note. It offers no
"Open in Plan ›": that door opens the location sheet, whose windows are all solar. "Show on map ›" is the
night's door.
