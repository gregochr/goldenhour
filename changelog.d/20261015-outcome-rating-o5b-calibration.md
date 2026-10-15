### Changed — the calibration gate also scores forecasts against readers' ratings

`GET /api/admin/calibration` now reads two sources of "how did it actually look": the old
`actual_outcome` records, as before, and the new per-user ratings of past sunrises and sunsets. Each
rating is paired with the one forecast row it was recorded against — the forecast available by the
event and not withdrawn at it — never with a forecast that landed after the event, and the predicted
rating is the star the reader saw when they rated (for a place with a woodland or bluebell lane that
is the combined star, not the sky lane's alone). A rating with no recoverable star, or an "I didn't
see it", is not a pair. Several readers rating one slot give several pairs, because each is an
observation; the gate's keep-the-newest-run rule now collapses only repeated runs of one forecast for
one outcome, so two outcomes of a slot (two readers, or a rating beside an old `actual_outcome` row)
no longer overwrite each other, and the report's counts are of pairs, not slots. The report's
outcomes-in-range figure now also counts every rating row in the window, "didn't see it" included.
No API shape changes. Also corrects the `UserRole` javadoc, which implied Lite may record outcomes
generally: only the old `POST /api/outcome` is open to Lite; rating a past sunrise or sunset is Pro
and Admin.
