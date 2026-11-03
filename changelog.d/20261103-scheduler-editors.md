### Added — Scheduler: schedule editors with a live preview and a save confirmation

Editing a job's schedule in the Scheduler panel now has three purpose-built editors. A **sun-relative**
job gets a slider (30 minutes to 8 hours, in 5-minute steps, read aloud as "4h 15m before sunrise") and
a sunrise/sunset select, with a live line from the server: what that rule means today and how far it
moves across the year in UK time. The afternoon refresh also links to Runs → Timing, to choose the
offset from real run times. A **cron** job that fires at daily or weekly clock times takes comma-separated
24-hour UK times (and day toggles for a weekly job); the server builds the cron, the editor warns about
other jobs firing within 30 minutes ("Neighbours: …" or "Nothing within 30 minutes."), refuses times that
do not share one minute, and saves them as UK time so they follow the clocks. A **fixed-delay** job picks
from 1, 5, 10, 30 minutes or 1 hour; a delay outside those is kept as its own option until another is
chosen. If the preview itself cannot be reached the editor says so and still lets you save. After a save
a toast reads "Saved. Next fire Tomorrow 12:27." from the job as the server then reports it. Hourly crons
and anything else not made of clock times keep the raw cron field.
