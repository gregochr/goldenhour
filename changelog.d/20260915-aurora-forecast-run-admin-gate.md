### Fixed — POST /api/aurora/forecast/run and GET /preview are ADMIN-only

Both were gated `hasAnyRole('ADMIN', 'PRO_USER')`, the same class-level annotation that legitimately
covers the two read endpoints on this controller (`/results`, `/results/available-dates` — the map's
normal PRO read path). But `/run` spends real Claude API cost the same way every other forecast-run
endpoint in this app does (all ADMIN-gated), `AuroraForecastRunService`'s own javadoc says it "runs
on demand from the Admin UI", and the only frontend caller of either endpoint is
`AuroraForecastModal`, mounted only inside the Operations tab, itself gated on `isAdmin` in
`App.jsx`. A PRO_USER had no UI path to either endpoint, only a direct API call — the same shape as
two previously-fixed gates on this project (`POST /api/locations`, `GET /api/forecast/history` and
`/compare`): the code was more permissive than the product ever intended, with nothing depending on
the gap. Both endpoints now carry `@PreAuthorize("hasRole('ADMIN')")`, overriding the class-level
gate; `/results` and `/results/available-dates` are unchanged.
