### Changed — The API no longer sends Lite the Fiery Sky and Golden Hour scores or the per-location written why

Readers who are not Pro or Admin now get no Fiery Sky or Golden Hour number and no per-location written
explanation from `GET /api/briefing/evaluate/scores`, the briefing's slots, `GET /api/forecast` (list, `?date=`
and the sparse rows) or `GET /api/forecast/{id}`. The star, the verdict, the stand-down reasons and the region
prose are unchanged. The check is fail-closed: a caller with no recognised role is treated as Lite. The
browser's cached forecast copies move to a new key generation so no earlier, unstripped body is ever painted.
