### Testing — the E2E smoke gate now drives the Map and the Plan on seeded data (gaps plan, Phase 3a.1)

The required "E2E — Playwright smoke" check used to boot an empty database, so the Map tab did not exist and
the Plan showed only its empty state. A local-only `E2eLocalFixtureSeeder` (gated by `photocast.e2e.seed-fixture`,
set only by the smoke script; `local & !prod`, H2 only, and only on an empty `locations` table) now writes 3
regions of 6 places, rated for every window from T-3 to T+4 with a BEST pick, with no Open-Meteo call and no Claude
call: it assembles the briefing from the same builder beans instead of running a build. Readiness waits for the seed
through a separate `e2eFixture` health contributor added to the smoke run's `container` group only. The backend runs
behind a dead outbound proxy (the Anthropic SDK's OkHttp client honours it, proved), and a CI step fails the job if
`backend.log` shows a briefing build or lacks the `[E2E FIXTURE] seeded` marker.

New real-browser specs: the Map (Leaflet, tiles, every visible chip against the served star, the callout), stepping
through the windows (forward to the end, back through the past, loading the two older days down to the floor, the
keyboard, a phone leg), forecast drill-down from the Map and from the Plan (the location sheet and its lazily
fetched row detail, the rising-tide fact, a dialog-depth observer that never sees a third dialog, Escape one layer
at a time). `tabs.spec.js` and `phone.spec.js` are rewritten for four tabs, and the Door 1 obstacle sweep moves in
from the manual folder. One frontend line backs them: `data-placed="true"` on the map's label layer once its chips
are measured and placed.

The seeder's own tests run in the E2E job under `-Plocal-dev`, with checkstyle over the two local-only source roots
(`generate-test-sources checkstyle:check`: a bare `checkstyle:check` audits neither).
