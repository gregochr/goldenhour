### Docs — the matrix-axis plan says it shipped

`docs/engineering/matrix-axis-plan.md` still opened with **"Status: planned, not started"** twelve
days after both of its phases merged. It now records that the series completed on 2026-08-30, the
same day the plan itself landed (#707): Phase 1, the sunrise/sunset card chips, as #708
(`37cc964f`), and Phase 2, the row rails and sticky headings, as #710 (`e540680d`).

The same stale status was also held in the project's working notes, which is how it surfaced: a
"what's next" survey nearly listed Phase 2 as outstanding work. A plan header is read as a
statement about the code, so a wrong one is worse than a missing one — it invites a session to
rebuild something that already exists. Checked against `gh` rather than against the prose.

No other plan in `docs/engineering/` opens with a "planned, not started" header.
