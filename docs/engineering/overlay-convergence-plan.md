# Plan-tab map overlay — convergence onto the Map tab (O-6): implementation plan

**Source**: no design bundle. This is the plan for `docs/engineering/map-tab-v2-plan.md`'s open
decision **O-6** ("overlay convergence onto the callout, and `MarkerPopupContent`'s long-term
home"), written 2026-10-19 from a line-by-line inventory of `origin/main` at `ad828e54`. It is a
**retirement** plan: the overlay is not rebuilt out of v2 pieces, it is deleted, and every door that
opened it opens the real Map tab instead — the route `plan-to-map-doors-plan.md` D3 already took for
the location sheet's *Show on map* (#764). Read §1 before any phase; §4 is what must be built on the
tab BEFORE a door may move, because each item is a capability the overlay has and the tab does not.

**The one-sentence version.** The "Aurora tonight" / "Sunrise tomorrow" modal is the pre-v2 map kept
alive behind `MapView`'s `overlayMode` gate — medallions, a Leaflet popup, its own EVENT/QUALITY/
LOGISTICS drawer, its own `/drive-times` fetch — and the only thing it still does that the Map tab
cannot is *show a few facts the callout never learned and open when the tab is withheld*; this series
teaches the tab those two things, routes its seven producers through the structured `source:'plan'`
handoff, and deletes ~700 lines of `MapView.jsx` (9% of the file), `MapOverlay.jsx`,
`utils/mapOverlay.js`, `ForecastTypeSelector.jsx` and about fifty overlay-only test cases.

**The one-paragraph correction to the obvious plan.** "Mount the v2 pane in the modal" is the wrong
convergence. `WindowFirstMapPane` is a never-unmounted pane wired to shell state (Ask's map
context, the window handoff nonce, the tide strip, the peek sheet), and a second mount of it inside a
`zIndex: 200` modal would double every one of those channels and reopen the v1-retirement §8 item 9
route (a map overlay standing over a Plan dialog). The overlay's own footer already says what the
product wants — *"Open the full Map tab →"* — and the tab already has the return path the overlay's
"you stay on the Plan tab" line promises: `MapBreadcrumb`'s **← Plan** (`onReturnToPlan`, doors D2).
So the shape is: extend the one handoff, close the capability gaps, move the doors, delete.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~5 prosecutor lenses + one refuter per surviving charge, all read-only) → fix survivors →
browser verification (§9) → commit. Frontend gate before any push request: `npm run lint && npm test
&& npm audit --audit-level=high && npm run build`, gated on **exit codes**. Never push; never tag.
Every phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry. Paste the phase's section of THIS plan
into every review agent's prompt.

**Check open PRs first.** `gh pr list --state open` and grep for *overlay*, *popup*, *medallion*,
*handoff*, *breadcrumb*, *callout*, *location sheet*. Checked 2026-10-19 at plan time: **no overlap**.

---

## §0 Status

**Status (2026-10-20): C1 is in PR #1246; C2 is built on `feature/overlay-c2-detail`, PR pending; C3–C6 not started.** §5 D1–D6 were TAKEN by the owner 2026-10-20 (every recommendation confirmed; D2 follows from D1). Phase log (each phase appends its own row in
the same commit as its code; the `PR` column names the PR, not a SHA — the repo squash-merges):

| phase | what | PR | notes |
|---|---|---|---|
| C0 | this plan | | |
| C1 | the handoff contract: `source:'plan'` carries everything the overlay's seven triggers carry | | no door moves yet |
| C2 | detail parity: the popup's facts the tab lacks, on the location sheet (and callout where cheap) | pending | built 2026-10-20 on `feature/overlay-c2-detail`; (a), (b), (g), (h) on the sheet, (c)'s level word and (f) on the callout, (e) and the light line already there; **(c)'s `detail` text and (d) have no home** — the sheet has no night rows (see §3 C2 note) |
| C3 | a Map tab with no solar forecast: offered on an aurora alert; a live-night aurora row | | D4 |
| C4 | move the seven doors; delete `App`'s overlay state | | one PR per door group is fine |
| C5 | delete the overlay: `MapView` branches, `MapOverlay`, `mapOverlay.js`, `ForecastTypeSelector`, CSS, tests | | the big one |
| C6 | docs sweep: CLAUDE.md, four plans, memory | | |

---

## §1 What the overlay is (facts, `origin/main` `ad828e54`)

1. **One opener, seven doors.** `App.jsx:507–521 handleShowOnMap` (via `normalizeMapTrigger` →
   `buildMapOverlay`, `utils/mapOverlay.js`) and `handleAuroraViewOnMap` (:535). Doors: **(1)** the
   aurora banner's *View on map* (`AuroraBanner.jsx:750`) — mounted above `<main>` on EVERY tab, so it
   opens the overlay over the Map tab too; **(2)** `WindowPickDialog`'s *Show {region} on map →*
   (:178); **(3)** its *Open {location} on map →* (:194); **(4)** `WindowSpotSheet`'s spot rows
   (:331 → shell `handleSpot`); **(5)** `HeatmapGrid`'s drill-down 🗺 (:409–420, inside the Regional
   planner); **(6)** `shared/SlotLocationName` (:31–40); **(7)** `WindowComingUpEntry`'s
   coastal/dark-sky cards and `DayChips` (:416–426, :43–49). Three producers already go to the tab
   through `openMapTab → openMapTabFromPlan`: the location sheet footer, `WindowRowFieldMap`'s *Open
   in map*, Ask's *Show on map ›*.
2. **Doors out.** *Open the full Map tab →* (`MapOverlay.jsx:187`, `App.openFullMapTab` :582) —
   ⚠️ writes `mapTabHandoff` with **no `source`**, so it travels the LEGACY `handoff*` props, not the
   structured door; withheld when `!hasForecastData`. ✕, Esc (window listener, no trap), backdrop,
   and the settings rising edge (`App.jsx:739`) all `setMapOverlay(null)`. ⚠️ `handleShowOnMap` also
   calls `selectDate(trigger.date)` — opening the overlay MOVES the Plan tab's selected date as a
   side effect. Not carried forward (§4 #7).
3. **Overlay-only rendering in `MapView.jsx`** (~460 lines): the context bar (event chip + clock,
   filter chips, "N pins in view", Filters ▾), the `advanced-filters-panel` drawer (EVENT
   `ForecastTypeSelector` "inherited from the plan card", QUALITY star segment, LOGISTICS drive
   select + dark sky + Clear), the Leaflet `<Popup>` with `MarkerPopupContent` (desktop) and a
   `BottomSheet` with it (phone), the dead `'medallions'` toolbar branch, the focus-pin
   `emphasiseLocationName`, the fixed 470/300px frame. Overlay-only logic (~215 lines):
   `HandoffPopupController`, `PopupResizer`, the overlay's own `/drive-times` fetch
   (`driveMinutesFor` reads `userDriveTimes` there and `reachById` on the tab), `retry: !overlayMode`
   on both night fetches, `servedMapEvents = []`, `solarWindowOnScreen` always true, the
   `focus.points` fit and the `focus.names` filter-bypass override.
4. **What the TAB gets that the overlay does not**: `heat` (the whole field), the structured
   `planHandoff`, `onSelectDate`, `onOpenLocationSheet`, `onReturnToPlan`, every served index
   (`scoreIndex`, `slotIndex`, `regionVerdictIndex`, `tideAlignmentIndex`, `eclipseIndex`,
   `reachById`), the Ask channels, `outcomeRatings`. The overlay mount passes `focus`,
   `emphasiseLocationName`, `overlayMode` and the legacy `handoff*` props — nothing else.
5. **Tests.** ~50 overlay cases across 18 `MapView*` files (roughly 15 are "absent on overlay"
   negatives); `MapOverlay.test.jsx` 7; `mapOverlay.test.js` 25; two `App.test.jsx` cases. Tab
   twins already exist for the floor/filters (`MapViewAuroraUnavailableFloor` tab half,
   `FiltersPopover.test`), handoffs (`MapViewPlanHandoff`, `MapViewDarkSkyHandoff`,
   `AppOpenMapTabFromPlan`), the callout and the sheet.
6. **Shared, must survive.** `MarkerPopupContent` is also mounted by the admin `PromptTestView`
   (:893) — its 190 tests stay; `markerUtils` is imported by `PinsLayer` and `FiltersPopover`;
   `makeMarkerIcon` still draws the tab's hidden medallions; `ForecastTypeSelector`'s
   `EVENT_TYPE_LABELS` has other readers (check before deleting the file).

## §2 What the codebase already has (do not rebuild)

- **The structured door**: `App.openMapTabFromPlan` → `mapTabHandoff {source:'plan', …}` →
  `WindowFirstMapPane` → `MapView`'s one nonce-keyed `planHandoff` effect, carrying the window, the
  Plan lens (rating floor, reach tier), the region focus, and — since D3 — the location whose callout
  opens. `MapBreadcrumb` names what is carried, with `clear` and **← Plan** (`onReturnToPlan`).
- **Filters** with every control the drawer has, plus Bluebell: `FiltersPopover`. **Event choice**:
  `WindowControl`, with honest night rows. **Selection**: `MapCallout` → `LocationFourDaySheet`.
  **Counts**: the tab's footer / phone toast. **Comfort rows**: callout + sheet (2026-10-02).
- **A map with no event at all**: `MapView`'s `hideDayCard` / `MapCallout`'s `dayMode` prove the
  tab can stand without a solar row — the precedent C3 extends to a live aurora night.
- `utils/mapOverlay.js`'s `normalizeMapTrigger` already turns all seven trigger shapes into one
  record; C1 reuses it as the producer side of the handoff rather than writing a second normaliser.

## §3 Phases

### C1 — the handoff contract (no door moves)

Extend `source:'plan'` so one structured door can express every overlay trigger:
- `window` (date + eventType, already), `lens` (already), `region` (already), `location` (already,
  D3) — plus: **`night`** (`{kind:'aurora'|'astro', date}` for the banner), **`focusNames`** (the
  coming-up cards' exact spot set; applied as a TEMPORARY lens that the breadcrumb names and `clear`
  removes — never a silent filter bypass), **`focusPoints`** (fit several pins; the multi-region
  🗺 door), **`filterAction` / `darkSky`** (the coming-up subject/dark-sky lens, promoted from the
  legacy props), and **`title`/`caption`** for the breadcrumb (the overlay's header line: "Aurora
  tonight", "Sunrise tomorrow · Northumberland").
- `App.openMapTabFromPlan` accepts the normalised trigger (move `normalizeMapTrigger` out of
  `utils/mapOverlay.js` into `utils/mapDoor.js`; `buildMapOverlay`'s title/caption logic comes with
  it as `doorHeadline`); the legacy `handoff*` props are NOT extended — they die in C5.
- `MapBreadcrumb` shows `title · caption`, the named lenses, **← Plan** (already) and `clear`.
- `MapView`'s `planHandoff` effect applies `night` (select the night row — the `askWindow` effect's
  own `isNight` handling is the model), `focusNames`/`focusPoints`, `filterAction`/`darkSky`.
- Tests: `utils/mapDoor.test.js` (the 25 `mapOverlay.test.js` cases re-targeted),
  `MapViewPlanHandoff.test.jsx` for each new field, breadcrumb text per door kind, `clear` resets
  the temporary lenses and the app-wide origin together as today.

### C2 — detail parity (owner list, D3)

What `MarkerPopupContent` shows that `MapCallout`/`LocationFourDaySheet` do not (grep-confirmed:
none of these names appears in the callout or sheet code): **(a)** dust / inversion / bluebell
badges; **(b)** surge, sea state, rising-tide warning; **(c)** the aurora read — `alertLevel` and
Claude's `detail` text (the callout shows stars only); **(d)** the astro explanations (cloud,
visibility, moon, phase); **(e)** Fiery Sky / Golden Hour `ScoreBar`s; **(f)** the travel-day
notice; **(g)** golden/blue hour, wind and comfort rows for a sky location ("More details"); **(h)**
the generated-at footer; **(i)** the live `TideIndicator`; **(j)** ADMIN *Run forecast*.
Recommended home: the **location sheet's window row expansion** for (a)–(e), (g), (h); the
**callout** for (c)'s `alertLevel` word and (f); (i) is NOT ported — `window.tideFacts` already serves
the tide the sheet shows and a second live tide fetch was the overlay's own duplication; (j) is
**O-7**, its own small admin phase on the callout's actions row, not this series. The owner strikes
or adds before C2 starts (D3). Map-tab-v2 §4.9 #11's rule stands: each is added to the callout/sheet
*deliberately*, never by porting `MarkerPopupContent` across.

**C2 as built (2026-10-20).** *Sheet, per expanded window row* (`components/map/LocationSheetFacts.jsx`,
`utils/forecastDetailFacts.js`, `hooks/useForecastDetail.js` — a copy of the popup's private hook, so
`MarkerPopupContent` stays untouched until C5): **(a)** dust / inversion / bluebell badges, **(b)**
sea state, surge and the rising-tide warning, **(g)** wind and comfort rows (slim row, no fetch) and
**(h)** the generated-at footer. The detail row is fetched only for an OPENED row. Gating mirrors the
popup section by section: LITE gets "High aerosols", no inversion, a locked bluebell. **(e)** the
Fiery Sky / Golden Hour `ScoreBar`s and **(g)**'s golden/blue-hour line were ALREADY on the sheet (the
location-sheet superset plan, Phases 1–2), so C2 changed nothing there. *Callout*: **(c)**'s
`alertLevel` word as one "Alert level" line under the verdict row (aurora window only, from the same
live-or-stored row the popup read) and **(f)** the travel-day notice (solar window only). Two
thresholds the popup applied client-side — the dust test and the ±90-minute rising-tide window — were
MOVED unchanged, not invented; their exit is a served boolean on the detail DTO, and they die with the
popup's last non-admin mount in C5.

> **Dated note, 2026-10-20 — (c)'s `detail` text and (d) the astro explanations are NOT built.**
> D3 puts them on the sheet "where it shows a night row", and `LocationFourDaySheet` shows only SOLAR
> windows (`buildLocationSheet` rows are the place's own sunrises and sunsets; its Javadoc and tide
> block say so). Inventing night rows was out of C2's scope. They need an owner call: night rows on
> the sheet (a bigger change than a parity phase), or a callout expansion carrying them (which D3
> and map-tab-v2 §4 #11 keep to the level word). Until then the aurora `detail` prose and the astro
> cloud / visibility / moon / phase lines stay overlay-only and are LOST at C5 unless that call is made
> first. Recorded here so C4's door move does not silently drop them.

### C3 — a Map tab without a solar forecast (D4)

Today `App` withholds the whole Map pane when `hasForecastData` is false, and the aurora banner —
which shows on every tab — then needs the overlay to reach a map at all. After C3: the Map tab is
offered when `hasForecastData || auroraStatus.active`; with no solar rows `WindowControl` holds the
night row(s) alone; a **live** alert with no stored results gets a live-night aurora row (today
`auroraAvailable = status.active || hasStoredAuroraResults` admits the mode but D-14's row needs
stored results — close that gap: a live row whose `bestRating` is `bestOfNight` over the live
`auroraScores`); the viewline, the field (A2) and the callout all work on it. `hideDayCard` is the
precedent for a tab with no event. Pin: LITE still sees no aurora row; a banner press on a LITE
account with no forecast goes nowhere honest (the banner's door is PRO/ADMIN already — check).

### C4 — move the doors

Each producer calls `openMapTabFromPlan(trigger)` through the shell's existing `openMapTab` (which
closes any Plan dialog FIRST — the door precaution CLAUDE.md records). Order, cheapest first:
**(6)** `SlotLocationName` and **(3)** *Open {location} on map* (pure location doors — D3's own
shape); **(4)** `WindowSpotSheet` rows; **(2)** *Show {region} on map*; **(5)** the 🗺 (single →
location, multi → `focusPoints`); **(7)** coming-up cards and `DayChips` (`focusNames` +
`filterAction`/`darkSky`); **(1)** the aurora banner (`night`, needs C3). `handleShowOnMap`,
`handleAuroraViewOnMap`, `openFullMapTab` and `App`'s `mapOverlay` state and its settings-edge close
go; `App`'s "a dialog opened OVER a Plan dialog by the regional planner's 🗺 or the aurora banner"
route (v1-retirement §8 item 9) is thereby **closed**, not guarded. The Plan tab's date no longer
moves when a door is pressed (§4 #7). Phone: a door moves the tab; the peek sheet is the surface.

### C5 — delete the overlay

`MapOverlay.jsx` (+7 tests), `utils/mapOverlay.js` (what C1 did not move), `MapView.jsx`'s 72 live
`overlayMode` reads and the ~700 lines behind them (context bar, drawer, both `MarkerPopupContent`
mounts, `HandoffPopupController`, `PopupResizer`, the overlay `/drive-times` fetch — `driveMinutesFor`
collapses to `reachById`; the `retry: !overlayMode` forks; `servedMapEvents`/`solarWindowOnScreen`/
`activeWindowPast`/`landingRunStamp` forks; the `'medallions'` toolbar branch; `zoomSnap`'s branch;
`focus`/`emphasiseLocationName`/`overlayMode`/legacy `handoff*` props and PropTypes), the
unconditional `fetchTravelDayRanges` whose only readers were the popups (unless C2 (f) keeps it —
then it is read by the callout), `ForecastTypeSelector.jsx` (+21 tests; relocate `EVENT_TYPE_LABELS`
first), `HourlyComfortTable`'s hidden-header mode (`headersVisible` false, `.wf-hourly-head-off`, the
`popup-hourly-table` id — O-6's own note), CSS (`map-overlay-rise`, `.map-overlay-panel`,
`.map-ctx-*`, `.animate-popup-refresh`, the dead `.wf-map-toolbar`, the safe-area and z-ladder
entries), and the ~50 overlay test cases (the ~15 "absent on overlay" negatives deleted, the rest
re-targeted to the tab where no twin exists). **Kept**: `MarkerPopupContent` + its 190 tests
(`PromptTestView`), `markerUtils`, `makeMarkerIcon`. `MapView.jsx` lands under 7,000 lines.

### C6 — docs sweep

CLAUDE.md: the *Map tab (v2)* bullet's "deliberately frozen" paragraph becomes a dated retirement
note; the *Locations* bullet's "medallions render unclustered on the frozen Plan-tab overlay"; the
Plan-tab bullet's "the overlay opened OVER a Plan dialog" open item (closed); the Ask-client bullet's
overlay mentions; the Wildlife bullet's "(3) the overlay popup" host; the Waterfall bullet's
`MarkerPopupContent` note. `map-tab-v2-plan.md` §2 (the freeze rule), §4 #9/#11, O-6 (→ decided),
D-14's overlay note; `plan-to-map-doors-plan.md` (D5 "dropped unbuilt" — revisit); `v1-retirement-
plan.md` §8 item 9; `heat-field-plan.md`/`map-landing-plan.md` overlay mentions. Memory note.

## §4 Deliberate decisions baked into the shape

1. **Retire, don't re-skin.** See the one-paragraph correction above.
2. **Nothing the overlay shows is dropped silently.** Every popup fact is in §3 C2's list with a
   home or an explicit strike (D3). "The callout carries a reduced fact set by design" (map-tab-v2
   §4.9 #11) still holds: the SHEET is "one step away", and it is where the detail goes.
3. **`focusNames` is a named, clearable lens, never a hidden filter bypass.** The overlay's
   `focus.names` override silently ignored every filter; on the tab a reader must see "showing 6
   coastal spots" in the breadcrumb and be able to clear it.
4. **The aurora banner's door needs C3 first.** Moving it before the tab can stand without solar
   data would send a reader to nothing on a quiet-forecast storm night.
5. **The legacy `handoff*` props are not extended** — C1 promotes their last two fields
   (`filterAction`, `darkSky`) into the structured door, and C5 deletes them.
6. **The popup's live `TideIndicator` is not ported.** `window.tideFacts` is the one source of
   per-location tide on the API path (window-tide-facts P5); a second fetch was the overlay's own
   debt.
7. **A door no longer moves the Plan tab's selected date.** `handleShowOnMap`'s `selectDate` side
   effect was the overlay's; a door to another tab has no business moving the tab it left.
8. **O-7 (admin Run forecast from the map) is a separate phase after this series**, on the callout's
   actions row for ADMIN only — not smuggled into C2.

## §5 Owner decisions (TAKEN 2026-10-20 — every recommendation confirmed)

- **D1 — shape: retire the overlay and route every door to the Map tab.** (Alternative: a v2 modal;
  rejected in the correction paragraph.)
- **D2 — return path: `MapBreadcrumb`'s existing ← Plan, labelled with the door's title.** The
  overlay's "you stay on the Plan tab" becomes "one press back".
- **D3 — the C2 list, as recommended:** port (a)–(e), (g), (h) to the sheet, (c)'s level word and (f)
  to the callout; strike (i); defer (j) to O-7.
- **D4 — the Map tab is offered on an aurora alert even with no solar forecast.**
- **D5 — the coming-up cards' spot set is a named, clearable lens** (not a filter bypass).
- **D6 — phone: a door moves to the Map tab** (no phone overlay/BottomSheet survives); the peek
  sheet is the selection surface, as everywhere else on the phone Map.

Open, non-blocking: **Q1** should `MarkerPopupContent` be renamed/moved under `admin/` once its
only mount is `PromptTestView`? **Q2** does the Map tab want the overlay's region "narrative band"
(`bs.summary` with a verdict-coloured head) anywhere — the callout's reason prose is per-location,
the region panel (`MapRegionPanel`) already shows the served narrative, so probably no.

## §6 Risks

- **`MapView.jsx` is 7,716 lines and every phase edits it**; C5 touches 72 gates. One phase per PR,
  rebase often, and C5 split by concern (render branches / logic / props) if a reviewer cannot hold
  the diff.
- **The "absent on overlay" negatives are load-bearing today** (e.g. no breadcrumb, no legend, no
  landing card on the overlay); deleting them is right only once the overlay mount is gone — C5,
  never earlier.
- **Door (7)'s `focusNames` meets the field.** A six-spot lens on the heat field paints six blobs;
  the breadcrumb must say why, and `clear` must restore the field whole (C1 test).
- **The aurora banner shows on the Map tab itself.** After C4 its press on the Map tab is a
  handoff to the tab you are on — must select the night row and nothing else (no tab change, no
  lens reset), the `source:'map'` precaution.
- **PromptTestView keeps `MarkerPopupContent`**, so its 1,198 lines do not go; the series removes
  a surface, not that component.

## §7 Follow-ups named here, not built here

- **O-7** admin *Run forecast* on the callout's actions row.
- The stale `handoffEventType`-era comments in `App.jsx`/`WindowFirstMapPane.jsx` that C5 deletes
  with their props.
- `fetchTravelDayRanges` on the tab (a wasted fetch today) — resolved by C2 (f) or deleted by C5.

## §8 Browser verification recipe

Backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port 8083; 8093 if
held), `npm run dev`, the **owner** signs in. For doors: Plan tab → a card → *Show {region} on map* /
*Open {location} on map*; the Regional planner's 🗺; Coming up → a coastal card; the aurora banner
(Operations → Aurora → Simulate STRONG first). Expect: the Map tab opens on the right window with
the breadcrumb naming the door, the callout up for a location door, the field intact, **← Plan**
returning to the Plan tab with its dialogs closed and its date unmoved; no overlay anywhere; 390px
for the phone doors. State seen vs tested.
