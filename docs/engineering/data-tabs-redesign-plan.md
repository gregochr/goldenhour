# Data tabs redesign: Locations, Regions, Tides and Waitlist

**Status:** planned 2026-10-31; P1 built (Waitlist and the shared components); P2 next. **Source:** the owner's Claude Design export "PhotoCast - Admin Screens"
(`design_handoff_data_tabs`: `README.md`, `Data Tabs Redesign.html`, 18 frames). It lives with the owner, not in the repo.
Where it and the codebase disagree, **the codebase wins** (the handoff says so itself).

The handoff rebuilds Manage → Places (Locations, Regions, Tides) and Manage → People → Waitlist on the pattern the Users
rebuild set (`sub-regions-plan.md` §S4b, `components/users/*`). Rows carry no buttons; a row (click, Enter or Space) opens
a 440px panel; edits happen in the panel. The tabs must work on desktop, iPad (portrait and landscape) and iPhone. This
supersedes the "phone layout for Manage is out of scope" line in the Users rebuild.

## 1. Owner decisions (2026-10-31)

- **D1 Waitlist actions.** A Waitlist row opens a panel (dates, invite status, **Invite** / **Invite again**, **Remove**
  with its confirm). This departs from the mock ("rows aren't clickable") so that no row carries a button.
- **D2 Server counts are dropped for now.** No "{n} picks" per sub-region, no "{n} Lite readers have picks here", and no
  sub-region move warning. The server has none of these counts (§3). A counts endpoint is a possible follow-up.
- **D3 Regions: a turned-off region never holds a switched-on location** (§5.3). The owner asked for the right fix,
  not the quick one. Today a turned-off region only leaves the pickers, Ask and the Plan origin. Its locations still show
  to readers and still get forecast. That half-off state helps nobody. The server enforces the rule at **every door**
  (turning a region off, turning a location on, a failure reset re-enabling a location, adding a location, moving a
  location between regions), not just the region toggle, so the state cannot come back. No cascade across reader
  surfaces is needed: switched-off locations are already hidden and skipped.

**Handoff access for builders.** The bundle is unpacked at
`/private/tmp/claude-501/-Users-chrisgregory-IdeaProjects-goldenhour--claude-worktrees-admin-screens-ux-update-aafc59/afa8f5a3-b229-46ea-b584-5b224ca1f616/scratchpad/bundle/design_handoff_data_tabs/`
(`README.md`, `Data Tabs Redesign.html`, `screens/01…18-*.png`) for the life of the planning session. The copy sheet is
reproduced in §11 so the plan stands alone. The mock PNGs have three rendering slips to ignore: a duplicate Locations
column on Regions, the avg range repeated under the name on desktop Tides, and the Mist tag box overflowing.

## 2. What stays as it is

- `ManageView`'s two-level tabs and groups (Runs, Tuning, **Places**, **People**, Testing) and its phone `<select>` and
  wide sidebar. The mock's "Data / Operations" row is the old grouping; ignore it.
- The existing Waitlist count badge (People group, Waitlist sub-tab, sidebar, phone select) and `onCountChange`.
- Every location field an admin can edit today: name, coordinates, the **set** of location types (multi-select, the last
  one sticky), light, tides, region, sub-region, **mist-prone**, **elevation**, **valley floor** (height, suggest, clear,
  cell), **tide point** (lat/lng, clear, #1551) and **Also known as** (aliases). The handoff's Edit frame shows a subset;
  the rest moves into the panel's edit form (§6.4). Nothing an admin can do today may be lost.
- Region **base** editing (name + lat + lon, all three or none, the separate `setRegionBase` call after a rename that
  only fires when the base changed, and the existing copy explaining that moving the base clears drive times).
- Optimistic turn on/off (`useOptimistic`), the API modules, and the 403 enforcement on the server.
- **`AreasSection`** (Ask areas: create, rename, aliases, delete, member picker; the read-only sub-region list and
  deleting an empty sub-region; the "{n} enabled locations have no sub-region" line). It stays below the Locations
  list, unchanged, with its `reloadKey={areasVersion}` / `onAreasChanged` plumbing (pinned by
  `LocationSubRegionField.test.jsx`). Restyling it is out of scope.
- **`onLocationsChanged`** (App's `refresh`): called after an edit save, after an alias-only failure and after a
  re-enable, as today, and now also after Add and after turn on/off (both change what readers see).
- **The edit save sequence** (`LocationManagementView.jsx` ~785–835): `updateLocation` with only the changed height,
  tide-point and sub-region keys → `refreshAreas()` → `setLocationAliases` only if the field moved off its starting
  text → on an alias-only failure "Location saved; its other names were not" plus the server detail, and every baseline
  reset so a second save resends nothing. Plus the alias field's loading / failed states and late fill against a fresh
  baseline, the valley-floor race token and "Save disabled while a cell lookup runs", the tide point shown and sent only
  for Seascape (a half-blanked hidden pair never blocks a save), and the sub-region select rules (the "No sub-region"
  option only when the location has none; hidden when the region has no sub-regions or areas have not loaded; changing
  region drops the pick and changing back restores it; Add offers enabled regions only, Edit enabled plus the current).
  **The builder moves this logic into hooks/utils as it stands rather than rewriting it**, and keeps its tests passing
  (moved, not weakened).
- The re-enable flow's per-location pending state, the server's sentence via `apiErrorMessage` with its fallback, and
  clearing that error on retry (`LocationAlerts.jsx`).
- Every load-error and empty state each tab has today (e.g. *Failed to load tide statistics.*), plus a new Waitlist load
  error (today a failure falls through to the empty state, which would now wrongly read "No one's on the waitlist yet").

## 3. What the server provides (checked 2026-10-31)

| Handoff needs | Server | Decision |
|---|---|---|
| Location types: five, incl. Woodland | `LocationType` has six; a location holds a **set**. `utils/locationTypes.js` `DISPLAY_TYPES` is the five admins see (no BLUEBELL) | Chips and edit toggles come from `DISPLAY_TYPES`; Type cell lists the set's labels joined by ", ". Edit is multi-select (styled as the mock's segments), not single-select. |
| "Mist" tag | `mistProne` | Tag after the name when `mistProne`. |
| Failure reason in "Last failed {when}: {reason}" | `lastFailureAt` only; `disabledReason` only on auto-disable | Copy: *Last failed {when}.* (no reason). When auto-disabled show `disabledReason` if present. |
| Auto-disabled flag | `!enabled && disabledReason != null` | Derive in a util. |
| Reset failures | `PUT /api/locations/reset-failures?name=`; re-enables only an auto-disabled location | Button *Reset failure count*; *Turn back on* when auto-disabled. |
| Sub-region | Admin-chosen; `subRegionId` on add and update; must be inside the location's region | Editable select, options from `GET /api/areas` filtered to `kind === 'SUB_REGION'` and the chosen region (as today's `SubRegionSelect`). |
| Bortle, SQM, grid, overlooksWater, coastalTidal | On add only; not on update | Shown read-only in the panel; set only in Add. |
| Show on map | No route from Manage to a focused Map | **Dropped** in this series. |
| Region location count, sub-region counts | Not in `GET /api/regions`; derive from `GET /api/locations` and `GET /api/areas` (`locationIds`) | Client-side counts: location counts as `RegionManagementView` already derives them; sub-region counts are new (fetch `GET /api/areas`, `kind === 'SUB_REGION'`, by `regionId`). |
| Needs attention | `LocationAlerts` lists `consecutiveFailures > 0` **or** `disabledReason` (a reset counter cannot hide an auto-disabled place) | The alert, the chip and the sub-tab count all use `consecutiveFailures > 0 \|\| disabledReason`. |
| Region "Base" | `baseName`, `baseLat`, `baseLon`; drives drive times | Shown in the panel's Details; edited there. |
| Tide stats, bulk | `GET /api/tides/stats/all` → `{locationName: TideStats}` for enabled locations with data | List from the bulk call joined to `GET /api/locations` (sub-region, tide types, status). |
| King frequency | Not served | Show king `above {kingTideThreshold} m` + `{kingTideCount}` only. |
| Waitlist | `id`, `email`, `submittedAt`, `invitedAt`, `inviteExpiresAt`; invite and delete endpoints | D1. |

## 4. Shared components (phase P1)

New folder `components/data/` (one place, not copies). Users keeps its own components until P2 migrates it.

- **`DataSplitLayout`**: list + panel shell. The mode follows the viewport, as the handoff's panel table says:
  - **≥1000px** split: list `minmax(0,1fr)` + 440px panel (`bg-plex-panel`, left border).
  - **640–999px** overlay: the panel absolute on the right of the list card, scrim `rgba(10,7,6,.66)` over the list,
    shadow `-12px 0 32px rgba(0,0,0,.45)`; tapping the scrim closes. 640px itself is overlay, because phone mode is
    exactly `useIsMobile` (`max-width: 639px`), the project's one phone rule.
  - **≤639px** (`useIsMobile`) full-screen page: a 52px back bar (44px back button "‹ {Tab}", the panel's primary action
    such as Edit on the right), safe-area insets, no list behind it.
  - ⚠️ **The panel is never `role="dialog"` or `aria-modal`**, in any mode. `utils/mapForeignModal.js` treats any such
    element outside the Map pane as a foreign modal without checking visibility, and the Operations pane is only hidden,
    never unmounted, so a panel left open would break Map Escape and outside-press for the session. Use `<aside
    aria-label>` (as Users does) and a plain `<div>` scrim with `aria-hidden`.
  - Desktop (≥1000) must be **provably unchanged** by the phone rules: phone-only classes sit behind `useIsMobile` or
    `max-[639px]:` and nothing else.
- **Expected list widths.** `main` is `px-4`, and from 1280px ManageView adds the `w-44` sidebar + `gap-6`. With the
  panel open the list is about 526px at 1000px (cards), 550px at 1024 and 608px at 1280 (secondary columns dropped).
  That is the handoff's container-query behaviour working as designed ("reflows whether narrowed by the panel or by
  the viewport"); check it against frames 11–13 in the browser and report, don't tune breakpoints away from the handoff.
- **`PanelShell`** (generalised from `users/PanelShell.jsx`: a `testIdPrefix` so Users keeps `user-panel-title` and
  `user-panel-close`), header name Newsreader 600 22px, meta 12.5px ink-2, × 36px. **`PanelSection`** (mono 10px
  uppercase label, optional right-hand action such as Edit or Rename, 14×18 padding, 16 on phone). **`KvList`** (label
  ink-2, value ink, mono where numeric).
- **`ChipRow`** + **`Chip`** (the Users chip, with counts; single-select within a group, groups AND), a **select styled as
  a chip** (*All regions ▾*), 1px group dividers, and a **sort chip** shown only in card mode. Search box 280px. At
  ≤639px chips are 44px tall (min-h-11: the touch-target rule wins over the handoff's 36px) and scroll sideways, and the search moves above them.
- **`DataGrid`**: a CSS grid (not `<table>`) inside a `@container` element (Tailwind 4's built-in container variants;
  JSX has none yet, `index.css` has hand-written ones). Columns come as a template; cells marked secondary drop at
  `@max-[820px]`; at `@max-[540px]` rows become two-line cards (line 1 name + right status/value, line 2 the summary
  cell; padding 12px 4px, min-height 60px) and the header hides. Header labels mono 10px uppercase .12em ink-3; the
  sorted one ink-2 with ▲/▼; sortable headers are `<button>`s with `aria-sort` on their wrapper. Rows: `role="button"`,
  `tabIndex=0`, an `aria-label` reading the row, Enter and Space open, hover tint `rgba(242,231,211,.03)`, selected
  tint `.06` + 2px inset left bar in ink, off rows ink-3. A grid with `role="button"` rows is not an ARIA table; do not
  add table roles. A row may hold **no** interactive element. Rows that don't open anything (none in this series after D1)
  are plain.
- **`StatusDot`**: go / amber / coral / ink-4 dot + text.
- **`useHashSelection(tab)`**: the `#manage/{tab}/{id}` read, write and `hashchange` logic from `UserManagementView`,
  generalised.
- **`usePanelEscape`**: Esc closes the panel, or steps back from a sub-mode (edit, add form) first. It ignores the key when
  another dialog is open (any `[role="dialog"]`/`[aria-modal]`; the panel is neither), when the key was already taken,
  when the Operations pane is hidden (the reader is on Map or Plan: check the panel's own `offsetParent` or `closest('[hidden]')`),
  and when focus is in a text field, **except** a field inside the panel's edit form, where Esc cancels the edit, as
  today's inline editor does (`LocationManagementView.test.jsx` ~575 pins that).
- **Pagination**: `Pagination` / `usePagination`, label `1–13 of 266`; at ≤639px buttons get 9×14 padding. Changing page
  keeps the panel open if the selected item is still in the filtered set, otherwise closes it.
- **Dates**: `19 Oct 2026` in tables and `Today, 07:48` for today, through `formatInstantUk` and `appNow()`; Waitlist's
  Joined adds the time (`2 Oct 2026, 08:14`). Parse server instants with `parseUtcInstant`. One util, tested.
- **Phone extras (≤639px only)**: ManageView's `card` wrapper loses border and background (edge to edge), pane padding
  12/14px, buttons ≥40px tall, touch targets ≥44px.

Use `plex-*` tokens and existing classes (`card`, `btn-primary`, `btn-secondary`). Add a theme token in `index.css` for
a mock colour that has none. Dynamic geometry (the tide bar's positions) may use `style`, as `ScoreBar.jsx` and about 120
other components do; ESLint has no inline-style rule. Everything else is Tailwind.

## 5. Phases

One PR per phase, each branched from `main` after the previous merged.

| Phase | Scope |
|---|---|
| **P1** | §4 shared components and **Waitlist** rebuilt on them (§8), the simplest consumer. Also ManageView's guarded `hashchange` listener (§5.1). |
| **P2** | **Users** migrated onto the shared components. A deliberate tablet change: 1000–1023px goes from list-replaced to split, 640–999px from list-replaced to overlay; Users also gains the phone page. Its tests are rewritten where they assert `<table>` structure (`users-table`, `columnheader`s absent when the panel opens), never weakened in what they prove. Update `sub-regions-plan.md` §S4b's notes. |
| **P3** | **Tides** rebuilt (§7). Read-only, no server change. |
| **P4** | **Server guard (D3)**, backend only (§5.3). |
| **P5** | **Regions** rebuilt (§5.4). |
| **P6** | **Locations**: list, alert (replacing `LocationAlerts`), sub-tab count, view panel, edit and add in the panel (§6). The inline row editor and the confirm modal go. One PR, because splitting list from edit would leave a release where an admin cannot edit a location. The largest phase; the builder moves today's logic into hooks first (behaviour unchanged, tests green), then swaps the view. |

### 5.1 ManageView `hashchange` (P1)

Cross-tab links (*Full tide stats*, *Open in Locations*, *Show them*) set `#manage/{tab}[/{id}]` while Manage is mounted;
ManageView reads the hash only at mount today. Add a listener that:
- **ignores** any hash that is not `manage/<known tab>` (never falls back to Pipeline Runs: `oneTimeLinkToken` /
  `inviteLinkToken` stripping and other tabs write the fragment);
- sets the group and tab **state** directly, never through `setActiveTab`, which rewrites the hash and would strip `/{id}`;
- leaves the per-tab selection to the tab's own `useHashSelection`.
Update the comments at `App.jsx` ~839 (`openOperations`) and `WindowFirstShell.jsx` ~878, whose behaviour this changes
(an already-mounted Manage now follows the hash).

### 5.3 Server guard (P4)

The invariant: **no switched-on location in a switched-off region.** Enforced in the services, 409 through a dedicated
exception mapped in `GlobalExceptionHandler` (the `NameClashException` / `AreaConflictException` pattern), each message a
plain sentence the UI shows as is:
- `RegionService.setEnabled(id, false)` with switched-on locations: *"Turn off its {n} locations first."* (needs a
  `LocationRepository` count query; no cycle, `LocationService` uses `RegionRepository`, not `RegionService`).
- `LocationService.setEnabled(id, true)` in a switched-off region: *"Turn {region} on first."*
- `LocationService.resetFailures` in a switched-off region clears the counters but does **not** re-enable; the response
  says so (or 409, whichever the existing response shape allows more cleanly; document it).
- `add` into a switched-off region, and `update` moving a switched-on location into one: 409.
- Turning a region **on** is always allowed. Existing data in the half-off state is left alone (no migration); the
  rule stops new cases. The check-then-save is not transactional against a concurrent toggle; accepted and noted.
Tests per door (service and controller); every `RegionServiceTest` construction updated. Docs: `reference/api-endpoints.md`
(each endpoint's new 409) and `reference/platform.md`.

### 5.4 Regions (P5)

- **Header** *Regions* + `{n} on · {n} off` + **Add region**.
- **Columns** Region 2fr · Sub-regions .7fr · Locations .7fr · Created .9fr (secondary) · Status .8fr (secondary).
- Off regions sit under a collapsible **Off · {n}** group header, collapsed by default (opened if the hash selects one).
- Card line 2: `{n} sub-regions · {n} locations`.
- **Panel**: meta `On · created 1 Mar 2026`. **Details** (Name with *Rename*; Base with its own *Edit*, or *Not set*) ·
  **Sub-regions** (`{n} · {n} locations` on the right; rows name + mono `{n} locations`; no picks, D2) · **Region actions**.
- **Region actions** (honest copy, replacing the handoff's dialog):
  - On, no switched-on locations: *Turn off — It leaves the region pickers, Ask and the Plan's starting points.* [Turn
    off], no dialog.
  - On, with switched-on locations (count enabled ones only; `locationCountByRegion` counts all): *Turn off — Turn its
    {n} locations off first.* Button disabled, plus *Show them*, which opens Locations filtered to this region
    (`#manage/locations?region={id}` or an equivalent the Locations tab reads in P6; until P6 it just opens Locations).
  - Off: *Turn on — It comes back to the region pickers, Ask and the Plan's starting points.* [Turn on].
  - A failed toggle (409 race or otherwise) reverts and shows the server's sentence under the action (today it is only
    `console.error`ed).
- **Add region / Rename / Edit base**: the panel body becomes a one-purpose form, Cancel / Save. Keep today's partial-base
  message (*A base town needs a name, a latitude and a longitude — or leave all three blank.*), the clear-base save, the
  existing copy that moving the base clears drive times, and the refresh after a failed save.

## 6. Locations (P6)

### 6.1 List
- **Alert** (replaces `LocationAlerts.jsx`; delete it and port every behaviour its tests pin that still applies): one
  line, amber tint (`rgba(224,165,66,.1)`, border `.32`), 6px amber dot: *"**{n} locations need attention.** {name} has
  failed {n} runs in a row. {name} was turned off after {n}."* Name at most one failing and one turned-off place;
  plurals right. **Show them** applies the Needs attention chip. Shown when any location needs attention
  (`consecutiveFailures > 0 || disabledReason`, §3).
- **Search** *Search name or sub-region*. **Chips** All + one per `DISPLAY_TYPES` (a location counts under each type it
  holds) | Needs attention · Disabled (`!enabled`) | All regions ▾ (also set from the Regions *Show them* link).
- **Columns** Location 1.7fr (name 500 + *Mist* tag when `mistProne`; sub-region 12px ink-2) · Type .8fr · Light .95fr
  (secondary) · Tides .6fr · Bortle .45fr (secondary, mono) · Status 1.05fr.
- Light `Rise Set Day` / Tides `H M L`, unselected tokens ink-4; non-seascape Tides "—".
- Status: Active (go) · `{n} failed runs` (amber, while enabled) · Disabled (ink-4 dot, ink-3 text) · Disabled after
  failures (coral; `!enabled && disabledReason`).
- Card line 2: `{Type} · Tides H M L · Bortle {n}` (the Tides part only for seascapes).
- Empty filter: *No locations match.* [Clear filters].
- **`AreasSection`** stays below the list, unchanged (§2).
- **Sub-tab count**: an amber count after **Locations** (sub-tab, sidebar, phone select) when any location needs
  attention. ManageView already fetches the waitlist for its badge; do the same with `fetchLocations`, refreshed by an
  `onAttentionCountChange` from the Locations view (mirroring `onCountChange`).

### 6.2 View panel
Header: name; meta `{Types} · {Sub-region} · {Status}`. Sections in order:
1. **Failure box** (amber):
   - Enabled with `consecutiveFailures > 0`: *"**{n} failed runs in a row** Last failed {when}. A third failure turns
     the location off."* + **Reset failure count**. This button is **new** (today's Re-enable is only for auto-disabled
     places); it calls the same `PUT /api/locations/reset-failures?name=`, which clears the counters.
   - Auto-disabled (`!enabled && disabledReason`): *"**Turned off after {n} failed runs** {disabledReason}"* + **Turn back
     on** (same endpoint, which re-enables). With D3, in a switched-off region it shows the server's sentence instead.
   - Keep the threshold 3 in one named constant mirroring the server's.
   - Per-request pending state, the server's sentence through `apiErrorMessage` with a fallback, the error cleared on retry.
2. **Shooting** (with *Edit*): Type, Light (Sunrise, Sunset, All day), Tides or *Not tidal*, Mist-prone.
3. **Place**: Region, Sub-region, Coordinates (mono, 3 dp), Elevation, Grid cell, Valley floor and Tide point when set.
   (*Show on map* is dropped, §3.)
4. **Sky**: Bortle `{n}` + *Excellent dark sky* (1–2) / *Moderate, aurora-friendly* (3–4) / *Light-polluted* (5–9),
   Sky brightness `{sqm} SQM`, Overlooks water, Coastal tidal. A null value reads "—".
5. **Tides** (seascapes only; replaces the Tides button and modal): the large range bar (§7), min / 0 / max labels, Avg
   range · P95 high (amber) · Spring %. From `fetchTideStats(name)`: loading line; fetch error *Couldn't load tide
   statistics.*; 204 → *No tide data yet.* *Full tide stats* (→ `#manage/tides/{id}`) shows only when stats came back
   **and** the location is enabled (the bulk list holds enabled locations only).
6. **Location actions**: *Turn off — Left out of forecast runs until you turn it back on.* / *Turn on*. Optimistic; a
   failure (incl. the D3 409) reverts and shows the server's sentence.

### 6.3 Rows and toggles
The status badge stops being a toggle; turning on and off happens only in the panel. No row holds a button.

### 6.4 Edit
In the panel; footer pinned: Cancel (link) + **Save changes**. Fields: Name; Latitude/Longitude (2-col, mono); **Type**
(multi-select toggles from `DISPLAY_TYPES`, styled as the mock's segments, last one sticky); **Light** (multi, selected =
tide tint + ✓, at least one); **Tides worth shooting** (High/Mid/Low; enabled only for Seascape; *Seascapes need at least
one.*; choosing Seascape pre-selects all three, dropping it clears them, as now); Region (enabled regions plus the
current one); Sub-region (§2's rules); then a **More** section with everything else today's editor has: Mist-prone,
Elevation (range and inversion-threshold warnings, coordinate-change hint), Valley floor (height, suggest, clear, cell,
the race token, Save disabled while a cell lookup runs), Tide point (Seascape only), Also known as (loading and failed
states). Keep today's validation messages, shown under their field, and the §2 save sequence. No move warning (D2).

### 6.5 Add
In the panel; the confirm modal goes. Place name + **Look up** → result box (mono coords + display name) → auto-detected
kv (Elevation, Bortle, Sky brightness, Grid cell; pulsing *Detecting elevation, Bortle, grid cell…*) → *Enter
coordinates instead* (Name/Lat/Lon). Then Type/Light/Tides as in Edit, checkboxes Overlooks water, Coastal tidal,
Mist-prone, Region (enabled only) + Sub-region (2-col). Footer Cancel + **Add location**, disabled until there is a
result or valid manual coordinates. On success: refresh, `onLocationsChanged`, select the new location.

## 7. Tides (P3)

Read-only. Header *Tides* + *{n} coastal locations · heights in metres* (the Unit column goes). Chips All · Thin data
(`dataPoints < THIN_TIDE_READINGS`, a constant = 2000) | All regions ▾. Key under the chips: light band *Lowest low to
highest high*, dark band *Average low to average high*, amber tick *P95 high*. Columns Location 1.35fr (name +
sub-region) · Range 1.6fr · Avg range .6fr · P95 high .6fr · Spring .55fr (secondary) · Readings .6fr (secondary; amber
when thin). Default sort name ▲; every numeric column sorts. Max high and min low lose their columns and sorts (they are
in the bar and the panel).

Data: `fetchAllTideStats()` (keyed by location **name**, enabled locations with data) joined to `fetchLocations()` by
name for id, sub-region, region, tide types and status. A stats entry with no matching location still lists, with no
sub-region and no panel link to Locations. Null numbers render "—" (today's `fmt`). Keep *Failed to load tide
statistics.* and the empty state.

**`TideRangeBar`** (`components/data/`; P6 reuses it): fixed scale −3.5 to +3.5 m, `left = (v + 3.5) / 7`, clamped to
the track. Track 2px border colour; full band minLow→maxHigh 8px tide at .22; avg band avgLow→avgHigh tide at .62; zero
line 1px ink-3; P95 tick 2×14 amber. `size="lg"`: 30px tall, 12px bands, 22px tick. Any null endpoint drops that band or
tick rather than drawing at 0. `aria-label` reads the numbers. Positions via `style` percentages (§4).

Card: line 1 name + `{avgRange} m`; line 2 the bar. Panel (selection `#manage/tides/{locationId}`): thin-data warning
(*{n} readings, against about {m} for its neighbours. Percentiles here are less reliable.*, m = median `dataPoints`
across the list) · **Range** (large bar + 3×2 mono 15px stats: Highest high, Average high, Average range, Lowest low,
Average low, Readings) · **High-tide percentiles** (P75, P90, P95 in amber) · **Spring and king tides** (spring `above
{threshold} m` + `{freq}% of highs · {count}`; king `above {threshold} m` + `{count}`; help *Spring is more than 125% of
the average high. King is above P95.*) · **Location** (Tides worth shooting, Status; *Open in Locations* →
`#manage/locations/{id}`).

## 8. Waitlist (P1)

Header *Waitlist* + `{n} · oldest first` + **Copy emails** (newline-separated to the clipboard, toast *Copied {n}
emails.*; on a clipboard failure an error line, e.g. *Couldn't copy. Use Download CSV instead.*) + **Download CSV**
(`email,submittedAt`, client-side Blob, RFC 4180 quoting, `submittedAt` as the server's ISO instant). Both act on the
whole list, not the search result. Search *Search email*. Columns # .3fr (secondary; the **queue position**, which does
not renumber when a search filters) · Email 2fr · Joined 1fr (`2 Oct 2026, 08:14` / `Today, 07:48`) · Waiting .8fr
(secondary; `29 days` / `Today`; from `parseUtcInstant` and `appNow()`).

Rows open the panel (D1), `#manage/waitlist/{id}`: title the email; Joined, Waiting, Invite status (*Invited {date} ·
expires {date}*, or *Not invited*); **Invite** / **Invite again** (busy state, *Invitation sent.*, re-read after invite);
**Remove** with today's two-step confirm (*Remove for good*), then close the panel, clear the hash and call
`onCountChange`. Server errors through `role="alert"`. Load error: *Couldn't load the waitlist.* (not the empty state).
Empty state centred, 56px padding: *No one's on the waitlist yet* (Newsreader 600 20px) / *Sign-ups appear here, oldest
first, with the time they joined.* (13px ink-2). Keep `onCountChange` after load and remove.

## 9. Tests (per phase, `frontend-test-standards.md`)

- Shared (P1): DataGrid opens on click, Enter and Space; no interactive element inside a row; selection ↔ hash (incl.
  Back); Esc rules (dialog open, text field, edit-form field, hidden pane); the panel never carries `role="dialog"`;
  ManageView's listener ignores non-manage hashes and keeps `/{id}`; container and phone classes asserted as classes
  (jsdom has no container queries; the browser check proves the layout).
- Per tab: search and chip filtering with counts; the panel opens; the handoff's test list minus the dropped counts.
- Waitlist: copy (and its failure), CSV content, stable #, empty vs load error, invite and remove.
- Tides: thin chip and warning, null fields, name join miss.
- Server (P4): each door's 409 or no-re-enable, turning on allowed, service and controller.
- Regions: disabled turn-off with switched-on locations, a reverted toggle showing the message, rename, base forms.
- Locations: alert membership incl. reset-counter auto-disabled, *Show them*, Reset failure count, Turn back on, edit
  validation (blank name, lat/lon range, seascape needs a tide), every §2 behaviour (save sequence, alias states,
  valley floor, tide point, sub-region rules), add without the modal, `onLocationsChanged` calls.

## 10. Gates and review

Frontend four commands every phase; the backend ladder in P4. Each UI phase: an adversarial review of the diff (about
six lenses: fidelity to the handoff and this plan, regression of today's capabilities, accessibility and keyboard,
responsive incl. "desktop provably unchanged", role gating and data honesty, test quality) plus refuters, then a
browser check on the local backend at 1280, 1024, 1000, 820 and 390 px, saying which claims were seen and which only
tested. Update `reference/platform.md` (admin features) in each PR, and this plan's status line as phases land.

## 11. Copy sheet (from the handoff, adjusted for §1)

British English, sentence case. "Turn off" / "turn on" in actions, "Disabled" in status.

- **Locations**: Locations / Add location · *Search name or sub-region* · All · Landscape · Seascape · Waterfall ·
  Woodland · Wildlife · Needs attention · Disabled · All regions · Location · Type · Light · Tides · Bortle · Status ·
  Active / {n} failed runs / Disabled / Disabled after failures · alert as §6.1 [Show them] · Shooting · Place · Sky ·
  Tides · Location actions · failure box as §6.2 · *Turn off — Left out of forecast runs until you turn it back on.* ·
  *Seascapes need at least one.* · *No locations match.* [Clear filters]. (Move warning dropped, D2.)
- **Regions**: Regions / {n} on · {n} off / Add region · Off · {n} · Details · Sub-regions · Region actions ·
  `{n} locations` per sub-region · actions as §5.4. (Picks and the turn-off dialog dropped, D2/D3.)
- **Tides**: Tides / {n} coastal locations · heights in metres · All · Thin data · the key as §7 · thin-data and
  definitions as §7.
- **Waitlist**: Waitlist / {n} · oldest first / Copy emails / Download CSV · *Copied {n} emails.* · the empty state as §8.

## 12. Carried forward from the P1 review

- **`DataGrid` grows when a tab needs it:** grouped rows with a collapsible header (Regions, P5) and per-row "opens
  nothing" rows (Tides entries with no matching location, P3). A `card: 'status' | 'summary'` column today needs
  `cardOnly`; make a normal column usable in card mode when Tides or Locations first needs it.
- **Pagination** (label `1–13 of 266`, phone 9×14 padding, keep the panel open while the selection is on the filtered
  page) lands with P2, where Users' tests move.
- **Accepted:** ManageView now follows Back and Forward to a `manage/<tab>` hash even while the Operations pane is
  hidden; `DataGrid` sets its column template through a CSS custom property in `style` (per-table geometry).
- **Browser checks still owed:** the sticky panel under the app header, the overlay at 640–999px, the phone page, focus
  return and `inert`. P1 shipped with these proven in jsdom only.
