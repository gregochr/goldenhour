### Docs — the outcome-rating series is written down, and a dead export goes

`CLAUDE.md` now describes what the series built, replacing the sentences it made false. The overview no
longer says outcome recording is API-only; the Roles table and its note say the Map's per-reader rating
(`PUT /api/outcome/ratings`) is Pro and Admin while `POST /api/outcome` stays open to every role. The
endpoints section gains `GET /api/forecast?date=` (T-4 to T-1, stored rows only), the rule that a passed
slot is served as of its event through `AsOfEventResolver`, the three `/api/outcome/ratings` routes with
their gates, the 24-day `MAX_RATING_AGE_DAYS` and the "not seen is `rating: null`" rule. The calibration
gate paragraph describes its second source (readers' ratings joined on `forecast_evaluation_id`, the
captured star as the prediction, the outcome's identity in the dedup key so counts are pairs, and
`outcomesInRange` counting both tables), and the "starves the calibration gate" bullet is scoped to the
old source. `outcome_rating` (V177) and `forecast_evaluation.scored_at` (V178) are in the migrations table
and in "Where a rating lives". Two new Map-tab bullets cover past windows (the `selectedIsPast`
provenance flag, the PAST pill, the EARLIER group and Load row, the older-day fetch, past windows read
from the forecast rows alone) and the rating block (its mount conditions, the Admin list, the counts and
the badge, and the pin badge that does not reserve space in the placer). The Backend-heavy bullet's
per-user-join class grows from three members to five, the Map-tab client-max class from two to three, and
a sentence names the series' filter/map helpers; the Role-gating section assesses the block (every role
sees it, LITE's stars dim, the controls are `aria-disabled` rather than `disabled`); the Rewind paragraph
lists what is hidden; the `IntegrationTestBase` count is corrected to fifteen.

`v1-retirement-plan.md` §8 item 7 and `all-in/serve-surfaces.md` record that the outcome UI has a v2 home;
`map-tab-v2-plan.md` §4 gains the past-windows entry; the outcome-rating plan is marked complete with
every owner question closed, ten more deviations numbered (§4 #23–#32) and the measured figures in §7;
the bundle's `VENDORING.md` gets its final count.

Removed: `fetchAllOutcomes` in `forecastApi.js`, which nothing has called since the Map stopped fetching
`GET /api/outcome/all`, and the nine test mocks that stubbed it. `OutcomeModal`, `recordOutcome` and
`fetchOutcomes` stay. No behaviour changes.
