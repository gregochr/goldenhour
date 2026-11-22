# Glance-to-Map follow-ups: warning E2E, phone overflow check, guide screenshots

Three items held after the Glance-to-Map series (#1889-#1905) and the Best bet advisor retirement. All test/docs
work; no ratings, verdicts, Best bet choice or Claude prompt changes; no Claude spend (seeded stub fixtures only).

## W: warning Details E2E (builder already running, branch `test/map-warning-details-e2e`)

The seeded local E2E fixture (`E2eLocalFixtureSeeder`, src/local, with a src/local-test twin test) has no Met Office
warning, so `map-warning-toggle` never renders and the chip's Details expand/collapse is untested end to end.
Plan: seed ONE warning through the real persistence path (entities/repository the ingest uses, see
`docs/engineering/reference/weather-warnings.md`, `WarningTimeline`), covering a seeded region for a seeded window,
deterministic against `appNow()`/`ForecastHorizon` (UK civil date); add a Playwright case (map-glance.spec.js or a
sibling) that opens the Map, sees the chip, toggles `map-warning-toggle` (aria-expanded + text); run the full smoke
and check no other spec breaks because a warning now exists.

## M: phone-width overflow check (builder already running, branch `fix/map-phone-overflow-check`)

Headless Playwright at 375x812 and 390x844 (mobile emulation) against the smoke recipe's seeded backend. Measure
`scrollWidth > clientWidth` and any Map-UI element past the viewport edge for: Map default, window selected, Best bet
reason expanded, List sheet, callout, Regions and Filters sheets, top menus (and the warning chip if W has landed).
No overflow: add a small `map-phone-overflow.spec.js` asserting it. Overflow: smallest Tailwind-only phone-only fix
(`useIsMobile`, max-width 639px), proven unchanged at 640 and 1024, plus the same spec. Emulation, not a real phone.

## G: guide screenshots (NOT started; waits for W to merge so a warning exists)

`landing/guide/map.html` describes the Map's new List, Best bet reason and warning Details; it carries a `#shot-open`
placeholder and presumably static screenshots. Plan: find how the guide's existing screenshots are produced and
stored (script, naming, size, theme), then regenerate/add shots for List, Best bet reason and warning Details from
the seeded fixture via headless Playwright, update `map.html` to use them (alt text, no layout shift), replace the
placeholder. The guide is public marketing material: ship as a ready PR for owner review; do NOT auto-merge it.

## Review questions

1. W: is seeding through the real persistence path correct for this codebase (what does `/api/warnings` read; is there a
   cache or `ServedBriefingEpoch` to respect; does a seeded warning change any role-gated or ETag-shared payload, or
   any existing test, E2E spec, or the local-test seeder assertions)? Any time-of-day hazard? Does the seeded warning
   risk reaching a Claude call or a network fetch (e.g. the warnings poller overwriting or deleting it)?
2. M: is the measurement method sound (what could produce a false "no overflow": Leaflet/body `overflow-x: hidden`,
   `100vw` quirks, scrollbars, sheets that are position:fixed so scrollWidth stays small)? Should it also check the
   elements' own bounding boxes and text clipping, not only `scrollWidth`? Are 375/390 enough, or add 360 and 320?
   Is a permanent overflow spec in smoke worth its flake/time cost, or should it be a one-off report?
3. G: what is the right way to produce guide screenshots here (is there existing tooling, naming, theming, retina
   size)? Dependencies on W's fixture, on a built frontend, on the dev server? Anything privacy-sensitive in the
   seeded data (real place names, user data) that must not ship publicly? Should G be a PR the owner reviews first?
4. Ordering and overlap: W edits the seeder and map-glance.spec.js, M adds a new spec and maybe `MapView`/Tailwind
   classes, G edits `landing/guide/*`. Any file overlap or merge-order hazard? Is the plan complete and safe to build
   with Sonnet, and what must each builder be told that the current briefs omit?
