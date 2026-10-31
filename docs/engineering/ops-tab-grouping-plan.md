# Operations tab grouping plan

**Status:** planned 2026-10-31. Two phases, one PR each (phase 2 branches from `main` after phase 1 merges).

## Why

`ManageView` (the admin-only Operations tab) has two groups: **Data** (5 tabs) and **Operations** (9).
"Operations" became a catch-all: monitoring, model config, calibration, scheduling, test tools and a
Lite-user policy all sit in one row of nine sub-tabs, which also overflows a phone.

Owner decisions (2026-10-31):
- Regroup by job (Option A), then a desktop sidebar plus phone dropdown (Option C).
- **Aurora Simulate stays in Job Runs.** Simulating is step 1 of a two-step flow whose step 2 is the
  paid 🌌 Aurora Forecast button beside it; its amber "🧪 Simulated" state is a deliberate warning
  next to that button; the aurora banner's simulated door (`App.jsx` `openOperations`) lands on
  `#manage/metrics`. None of that changes.
- Travel Days stays its own tab (folding it into Scheduler was not chosen).

## Phase 1 — regroup (Option A)

Only `ManageView.jsx`'s `GROUPS` table, its default, and a badge change. No sub-view component changes.

| Group `value` | Label | Tabs (`value` unchanged) |
|---|---|---|
| `runs` | Runs | `pipelineruns` Pipeline Runs · `metrics` Job Runs · `scheduler` Scheduler · `traveldays` Travel Days |
| `tuning` | Tuning | `models` Run Config · `skyeval` Sky Eval |
| `places` | Places | `locations` Locations · `regions` Regions · `tides` Tides |
| `people` | People | `users` Users · `waitlist` Waitlist · `subregions` Sub-regions |
| `testing` | Testing | `hottopics` Hot Topics · `rewind` Rewind |

Rules:
1. **Tab `value`s do not change.** `parseHash()` maps a tab to its group by lookup, so every existing
   `#manage/<tab>` hash (bookmarks, `openOperations`'s `manage/metrics`, `UserManagementView`'s
   `#manage/users/<id>`) keeps resolving. Pin this with a test per moved tab.
2. **Default landing** (no hash, `#manage`, or an unknown tab) becomes `runs` / `pipelineruns`
   (was `data` / `users`). Clicking a group still selects its first tab.
3. **Waitlist badge on the People group button.** With Runs as the landing group the Waitlist sub-tab
   badge is no longer on screen at landing, so the People group button carries the same count
   (`data-testid="waitlist-group-badge"`, same pill classes, hidden when null or 0). The existing
   `waitlist-count-badge` on the sub-tab stays, and `onCountChange` updates both.
4. Group testids become `manage-group-runs|tuning|places|people|testing`; `manage-group-data` and
   `manage-group-operations` cease to exist. Sub-tab testids are unchanged.
5. Fix the misplaced JSDoc: the component comment currently sits above `parseHash`, not `ManageView`.

Tests (Vitest, read `docs/engineering/frontend-test-standards.md` first):
- Update `ManageViewWaitlist`, `ManageViewRewind`, `ManageViewSubRegions`, `RunProgressKeptAfterFailures`
  for the new groups and default.
- New `ManageViewGroups.test.jsx`: each group renders exactly its tabs in order; default landing is
  Pipeline Runs; every tab's hash lands on its group and tab (table-driven over `GROUPS`'s expected
  layout written out literally in the test, not imported); unknown tab falls back to Pipeline Runs;
  group click selects first tab and writes `#manage/<first tab>`; People badge shows/hides with count.
- `test/e2e/forecast.spec.js` "Operations view — ADMIN": new group testids and default.

Docs: `docs/engineering/reference/platform.md` paths ("Manage → Data → Users" → "Operations → People →
Users", "Operations → Rewind" → "Operations → Testing → Rewind"); grep `docs/engineering/reference/`
for other "Data →"/"Operations →" sub-tab paths. Changelog: `changelog.d/20261031-ops-tab-groups.md`,
`### Changed — …`.

## Phase 2 — sidebar on desktop, dropdown on phone (Option C)

Three layouts, exactly one rendered at a time (never CSS-hidden duplicates: testids must stay unique):

| Width | Navigation |
|---|---|
| ≥ 1024 px | Left sidebar `<nav aria-label="Operations sections">`: each group as a small uppercase heading, its tabs as buttons below; active tab `aria-current="page"` and the gold accent. Content in a `min-w-0 flex-1` column to its right. Fixed sidebar width (about `w-44`), `shrink-0`. |
| 640–1023 px | Phase 1's two-row tab bar, unchanged. |
| < 640 px | One labelled `<select className="field">` (visually hidden `<label>` "Section") with an `<optgroup>` per group; option text "Waitlist (3)" when the count is > 0. Change writes the same hash. |

- New hook `hooks/useIsWide.js` (`(min-width: 1024px)`), a copy of `useIsMobile.js`'s shape. Phone
  detection uses the existing `useIsMobile`.
- Sidebar and select reuse the same `GROUPS`, `setActiveTab` and badge state; selecting a tab sets
  its group. Sub-tab buttons keep their `manage-tab-<value>` testids in the sidebar; the select is
  `manage-section-select`; the sidebar is `manage-sidebar`.
- Tablet must be provably unchanged from phase 1 (a test renders it with both hooks false and
  asserts the phase 1 markup).
- Browser check at 390, 800, 1024 and 1280 px against the local backend (port 8083): no horizontal
  page scroll; Job Runs and Pipeline Runs tables still fit or scroll inside their own card.

Tests: sidebar renders all groups and tabs, `aria-current` follows selection, hash sync; select lists
every tab under its optgroup, changing it switches content and hash, waitlist count in option text;
each width renders exactly one navigation. Mock `matchMedia` the way existing `useIsMobile` tests do.

Docs: add the layout to `platform.md`'s admin-features paragraph. Changelog:
`changelog.d/YYYYMMDD-ops-tab-sidebar.md`.

## Out of scope

Moving Aurora Simulate; folding Travel Days into Scheduler; ARIA tab roles on the phase 1 bar;
changing any sub-view's content.
