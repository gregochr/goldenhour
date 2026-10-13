### Added — the Scheduler screen understands sun-relative forecast cycles

The Operations tab's Scheduler screen now shows each schedule type in its own form: a fixed cron
time, a fixed delay, and a sun-relative rule ("4 h 15 min before sunrise") with its next fire time in
UK time. A schedule type the screen does not recognise is labelled as such and cannot be edited, and
editing a sun-relative job no longer sends a zero fixed delay. The two forecast cycle jobs (nightly
and intraday) get a switch between a fixed time and a sun-relative one, behind a confirmation that
states the job's next fire time before and after, pre-fills the plan's rule from the server and
checks the offset (0 to 720 minutes) before anything is sent. A new read-only panel, fed only by the
solar preview endpoint, shows each cycle's rule (proposed or current), today's and tomorrow's event
and trigger times, the expected finish, the year's earliest and latest trigger, the reference point,
and notes on catch-up and the safety budget. No schedule is changed by this release; switching a
job is a deliberate click by an admin.

Review fixes: switching back to a fixed time is described as dropping the sun-relative rule (the
backend clears it), a failed preview refresh keeps the older figures under a notice and blocks the
switch to sun-relative until it recovers, a day that falls back to the stored cron is quoted as such,
a partial save says the fallback cron was saved, a blank fallback cron is refused, slower earlier
responses no longer overwrite newer ones, and the editor moves focus in and back to the Edit button.
