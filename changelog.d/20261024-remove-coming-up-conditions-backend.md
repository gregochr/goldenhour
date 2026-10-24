### Removed — the Recurring conditions backend

`GET /api/almanac` no longer serves a `conditions` array. `ComingUpConditionsBuilder`, the three
`ComingUpCondition*` records, the unused `ForecastEvaluationRepository` range query and the
builder-only `coming-up.scoring` settings (dust, inversion and mist sub-configs, the evidentiary
bar, cadence words and the peak-light window) are deleted; the frontend stopped reading the array
in the previous change. `recurrent.trailing-window-days` stays (the slot-atmosphere retention
guard reads it), as do `topic_daily_log` and `TopicDailyLogJob`. The almanac cache warm after each
cycle stays for the chronology.
