### Added — `GET /api/forecast?date=`: one stored past day (backend only, nothing calls it yet)

`GET /api/forecast?date=YYYY-MM-DD` returns that one day's stored forecast rows in the list's own
shape, for the days the list does not carry — the Map tab's older-day fetch for rating how a past
sunrise or sunset turned out. The day must be between four days ago and yesterday on the UK calendar
(`ForecastHorizon.MAX_PAST_DAYS`, new); today, tomorrow, five days ago or a malformed date is a 400
with `{"error": "date must be between T-4 and yesterday"}`. It reads stored rows only — it can never
start a run, a batch or a model call — and LITE accounts get the same basic-score selection as the
list.
