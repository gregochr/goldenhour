### Changed — Sky Eval rebuilt around which model to run, whether a change made ratings worse, and a quick check

Operations → Tuning → Sky Eval now answers its three questions directly. A header with the weekly schedule
and an "Edit in Scheduler" link replaces the three rows of checkboxes; a Run a check panel starts a quick
manual check (models, 1 / 3 / 8 repeats, all fixtures or only the ones missed last time) on the current build
with a cost and time estimate. The Models tab has a verdict card for the running version, one card per
production lane with how it compares with the other models, an Accuracy over time chart (versions as gold
lines, hollow points for manual runs), "Did a change make ratings worse?" before and after per version with the
fixtures that moved, and an All models table. The Runs tab groups runs into sessions with a per-run table, a
progress bar while running and the failure message. The weekly model checkboxes moved to the Scheduler panel
of the Sky-Rating eval job. The Fixtures tab is a placeholder until the next update. recharts is no longer a
dependency; the charts are hand-rolled SVG.
