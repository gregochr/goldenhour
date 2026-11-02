### Changed — The afternoon refresh no longer re-rates tomorrow's sunrise when the evening refresh will

When tonight's 21:00 evening sunrise refresh is active and due, the afternoon (intraday) cycle now leaves
a tomorrow's-sunrise slot alone if that location already has a rating for it, at any stability. The
evening run re-rates every one of them, and the last 30 days of same-day re-ratings repeated the earlier
stars about half the time. A slot with no rating is still rated, and if the evening refresh is paused,
missing or not due before midnight UK time nothing changes. Saves roughly $11 a month. If the evening run
fails, the overnight rating (up to about 20 hours old) keeps serving until the next overnight run. The
Runs page's reclassify line gains "N left for tonight's evening refresh", and the intraday job's
description is updated to match (V212).
