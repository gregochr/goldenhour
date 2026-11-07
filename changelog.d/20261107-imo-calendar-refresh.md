### Added — the meteor peak table keeps itself current from the IMO calendar

A weekly job (Mondays, early morning UK time) now reads the International Meteor Organization's
calendar PDF for this year and next, and keeps the peak night and rate of each meteor shower up to
date, so a revised date reaches Coming up and At a Glance without a release. It always re-reads the
PDF, writes nothing unless the parse is complete and plausible, and emails an admin only when IMO
revises a stored peak, the calendar's layout stops parsing, the download fails three runs in a row, or
next year's calendar is still missing after 1 October. Its attempts are logged in a new
`imo_calendar_fetch` table (V223), and it is a scheduler job (`imo_calendar_refresh`) with Run Now.
