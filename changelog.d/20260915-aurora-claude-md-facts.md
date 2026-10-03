### Docs — CLAUDE.md's aurora facts corrected against the code

Three claims in CLAUDE.md's aurora coverage no longer matched `backend/.../service/aurora` and
`controller`. No code changed; the doc did.

**Polling is not night-only.** `AuroraPollingJob.executePoll` runs two paths every cycle: the
forecast lookahead (checks tonight's NOAA Kp forecast) runs day and night, unconditionally; only the
real-time path (confirms, escalates, or clears against live Kp/OVATION) waits for `!isDaylight()`.
The "night-only" label described the second path and silently dropped the first, which is the one
that fires a daytime NOTIFY so a reader can plan ahead of dusk. The bullet now names both paths.

**The Aurora API line was missing three endpoints and mislabelling three more.** The review named all
three gaps directly — `POST /api/aurora/admin/simulate`, `POST /api/aurora/admin/simulate/clear`,
`GET /api/aurora/viewline/forecast` — plus one mislabel, `status` marked `(Bearer)`; checking the rest
of the line per the review's own instruction turned up the same mislabel on `locations` and `viewline`.
`AuroraController` (all three, plus `viewline/forecast`) is class-level
`@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")`: there is no LITE-tier aurora read at all, unlike
the forecast endpoints' basic/enhanced split. The section header changes from `(Bearer / ADMIN for
writes)` to `(PRO/ADMIN; ADMIN for writes)` to match.

⚠️ **One review claim described code that has not merged.** The review's parenthetical for
`simulate/clear` — that it "ends only a running simulation, answering 200 either way" — is the
behaviour of an unmerged, unpushed commit (`fix/aurora-simulation-lifecycle`, stacked on this
session's own HEAD). On the code this branch actually builds on, `clearSimulation()` still calls
`AuroraStateCache.reset()` unconditionally, same as `/admin/reset` — it wipes a real alert along with
a simulated one and always answers 200. Documenting the pending behaviour as current would have traded
one contradiction for another, so the endpoint is documented as it behaves today, with that
today-only equivalence to `/admin/reset` spelled out inline. Whoever merges the lifecycle fix should
drop that aside along with the rest of this line's now-stale detail.

**A fourth aurora controller, added on request.** `AuroraForecastController`
(`/api/aurora/forecast/preview|run|results|results/available-dates`) had no entry anywhere in
CLAUDE.md's API section; it's now on the same line as the other two controllers, also
`@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")`. Adding it forced a second correction: the section
header said "ADMIN for writes", true of every write under `/api/aurora/admin/*` but not of
`POST /api/aurora/forecast/run` — a write (it persists results and spends a Claude call per viable
night) that a PRO account reaches without ADMIN. The header now reads "PRO/ADMIN; admin paths
ADMIN-only", scoped to the path rather than to read/write, and the blockquote says why the old
shorthand didn't hold.

⚠️ **The polling-scope fix above was superseded by a real code change before this PR landed.**
While this branch was open, #849 (`fix(aurora): every poll evaluates the alert state machine at most
once`) refactored `AuroraPollingJob` itself — the old two-path `isDaylight()` structure this entry's
first fix describes is gone; every poll now evaluates the state machine exactly once, choosing a
daylight or a dark path from one clock read. #849 rewrote the exact same "Aurora photography" bullet
with a fuller, code-verified account of the new behaviour, so merging main took that side of the
conflict wholesale rather than reconciling wording about superseded code — this fix's own contribution
to that one bullet did not survive to the merged CLAUDE.md. The API-section conflict merged both
sides: this branch's per-endpoint annotations (`viewline/forecast`, `forecast/preview`,
`admin/simulate`, and the still-true `simulate/clear`-equals-`reset()` note above) plus #849's
`/admin/run` correction (it now runs through the same guarded `runCycleIfIdle()` cycle as the
schedule, 409 while one is running, with a richer response body) — verified independently against
`origin/main`'s actual source rather than trusted from either diff.

⚠️ **A second merge conflict, same shape, landed within the hour.** #847
(`fix(aurora): isolate an admin's simulation from real users`) tightened
`POST /api/aurora/forecast/run` and `GET /api/aurora/forecast/preview` from `PRO_USER/ADMIN` to
`ADMIN` — a real, independently-verified role-gate change (`AuroraForecastController`'s two methods
now carry their own `@PreAuthorize("hasRole('ADMIN')")`, confirmed by reading the class on
`origin/main` directly) — and rewrote the same Aurora API line and blockquote again. This made this
PR's own "`forecast/run` is the one Aurora write a PRO account can reach without ADMIN" sentence false
the moment #847 merged; a peer session (`vigilant-leavitt-fed9d0-0d`, working on #849) had already
flagged this exact eventuality as a pure FYI two hours earlier, logged in the
`aurora-claude-md-facts-pr` project memory rather than acted on early, since #847 hadn't merged yet
and pre-editing for unmerged code is the mistake the note above already describes once. Resolution
merged #847's corrected role gates and its new blockquote (simulation-isolation fix, V154 migration,
`AuroraForecastModal`'s Admin-only mount point — spot-checked against `frontend/src/components/` before
accepting) with this branch's still-accurate per-endpoint detail that #847's rewrite had dropped:
`viewline/forecast`'s Kp-to-latitude explanation, `forecast/preview`'s "no Claude call" note, and the
`simulate/clear`-equals-`reset()` finding (re-verified against `origin/main`'s `AuroraStateCache.java`
and `AuroraAdminController.java` — both unchanged by #847, so the alias claim still holds).
