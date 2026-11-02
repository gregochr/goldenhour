### Added — Scheduler job descriptions, summary and detail endpoints

The Scheduler's job list now carries, for every job, a plain-English description (an alias, what the
job does, why it runs when it does, a purpose tag, the outside services it calls, whether it spends
Claude money, and how it connects to other jobs: who collects its batch results, who watches it, what
backstops it, what it is staggered against), its newest fire from the run history, and its next three
fires. A job the catalogue does not know (production rows are hand-editable) simply has no
description; it is never an error. A new `GET /api/admin/scheduler/jobs/summary` serves the header
counts (jobs by status and type, how many use Claude, how many last failed, and the next
non-repeating job), and `GET /api/admin/scheduler/jobs/{jobKey}/detail` serves a job's typical
duration and its Claude spend over the last 30 days, priced the same way as the Runs cost view
(forecast cycles include their chained briefing and ask-ready runs). Admin only; backend only for now.
