### Testing — the E2E smoke gate checks role gating as a Lite and a Pro reader (gaps plan, Phase 3a.2)

The required "E2E — Playwright smoke" check now signs in as three seeded accounts. A local-only `E2eRoleUsers` bean
(called by `E2eLocalFixtureSeeder`, so it inherits its four refusals) inserts a `lite` and a `pro` user beside the
admin, with one sub-region per region and two of the three as `lite`'s picks, and the smoke backend runs with
`photocast.sub-regions.enforce=true` as production does.

New real-browser specs: `roles.spec.js` (every non-admin has no Operations tab and a 403 from an admin endpoint; LITE sees
the Pro doors open a worded upsell and never a link, Ask locked with no `/api/ask*` request, "How was it?" locked, chips
outside its picks gated with the callout and sheet saying so, and the served forecast, scores and briefing stripped of
Fiery/Golden scores, the written why and picks; PRO sees none of the locks) and `roles-write.spec.js` (the Pro reader rates
one past window, in its own serial project before sign-out). `fixture.js` gains `loginAs(page, role)`; `global-setup.js`
writes the Coming up "seen" mark for all three accounts.
