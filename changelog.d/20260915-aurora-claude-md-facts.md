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

Not touched: `AuroraForecastController` (`/api/aurora/forecast/preview|run|results|results/available-dates`,
also `PRO/ADMIN`) is a fourth aurora controller with no entry anywhere in CLAUDE.md's API section. The
review didn't ask for it and adding a new endpoint group is a bigger documentation decision than
correcting three mislabelled ones, so it's left for a separate pass.
