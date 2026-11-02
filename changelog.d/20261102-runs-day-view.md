### Added — the Runs screen's Day view

Operations → Runs now opens on a Day view in place of the long reverse-chronological list. One UK day at a time:
a date bar (previous and next day, a calendar disclosure with a spend bar and an attention dot per day, Today,
and the Left, Right and T keys), a summary strip, a 24-hour strip of every run in five lanes (with sunrise,
sunset, a Now line and the still-to-come runs as dashed outlines), and the day's runs in time order with filter
chips. Today splits into what has finished and what is still to run, with the estimated times marked `~`.
Opening a run shows its times, cost in pounds and dollars, how it compares with the last 30, and a table of
the external calls it made, with a link to the existing run detail. The running-cycle banner, the progress panel,
Start a run and the cycle and job-run panels are unchanged and stay above the views. Cost trends and Timing are
placeholders until a following change.

The view, day and filter ride the address (`#manage/runs?view=day&date=2026-11-01&filter=cycles`) and survive a
reload; the Scheduler's "All in Runs" link opens the Day view on the matching filter, and the old
`?range=&kind=&attention=` links are mapped to it. An open run panel is no longer closed when the filter changes.
