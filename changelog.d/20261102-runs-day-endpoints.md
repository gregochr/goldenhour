### Added — the Runs screen's Day, calendar and external-calls endpoints

Three admin endpoints, `GET /api/admin/runs/day`, `/calendar` and `/calls`, serve what the Runs deep dive's Day
view needs: one UK day's cycles, jobs and sky evals in time order (a cycle folded together with its batch runs,
its briefing and ask-ready runs linked beneath it), each with a served type, status, note, cost in pounds and
dollars (unknown costs are `null`, not zero), whether it was slow against the type's previous 30 runs, and, for
today, what the scheduler will still run with estimated times and each cycle's trigger. The calendar serves a
month of per-day run counts, spend and attention counts; the calls endpoint serves a run's external calls
grouped by service.

Migration V214 adds `job_run.pipeline_run_id`, written when a cycle's briefing build and its ask-ready
precompute start their runs, so a cycle can show its children and when its answers were ready. Older runs are
matched by the briefing phase window and by starting within two minutes of the cycle's end. No screen reads the
endpoints yet; the Day view arrives in a following change.
