### Changed — the nightly topic log records inversions from the calculator, not Claude's echo

`TopicDailyLogJob` used to log inversions from `forecast_score`, which only holds slots that reached
Claude, so it could never become the unbiased history the Coming up tab's rarity figure needs. Since
V158 `slot_atmosphere` carries the inversion calculator's own 0–10 score for every inversion-eligible
place the pipeline fetched weather for, triaged or not, so the job now reads that: SUNRISE slots whose
reading was written with `inversion_scored` true and a non-null score, present at 9 or above, intensity
the region's highest score. Claude's echo is never consulted. A night with no readings logs nothing
(unmeasured, not "no inversion"), as SNOW already did.

The new series is written under the topic type `INVERSION_CALC`. The rows the job wrote before
(`INVERSION`) came from the survivor-biased population, stay in the table untouched and are no longer
written; the table has no source column and a migration was not needed, because the topic type is part
of the unique key and keeps the two populations apart by name. A later rarity computation must read
`INVERSION_CALC` alone. Nothing reads the table yet, and the inversion rarity on the Coming up tab stays
on its config fallback by decision until enough of the new log has accumulated; no user-visible change.
