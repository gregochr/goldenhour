# One screen to plan a shoot (no tabs) — plan

**Status:** plan, **draft 2 (2026-11-23), owner-approved.**

**Review.** Draft 1 was reviewed adversarially under six lenses, by three reviewers with two lenses each. It had 6 blockers and 25 should-fix findings, and each one is folded in below. §8 maps them to the sections that answer them.

**Owner answers.** Recorded in §7.

**Source.** The owner's design handoff, `design_handoff_one_screen` (README, `CLAUDE_CODE_PROMPT.md`, and the prototype `One Screen.html`). The prototype is a design reference, not code to port.

**Phases.** O1, O2, O2b, O3, O4a and O4b, one PR each. Each phase is branched from the updated `main` after the previous one has merged. Release only after O4b.

**Not touched:**
- ratings, verdicts and the Best bet
- any Claude prompt
- the `/api/almanac` payload
- Operations' inner tabs (`SchedulerTabs`, `RunsViewTabs`)

The workflow the screen reads as: **when** (Further ahead) → **which morning** (This week) → **where** (the map view) → **go** (callout, Leave by).

## 0. What the code says today (confirmed 2026-11-23 on `main` @ 8d87941d9)

Paths are under `frontend/src` unless they say otherwise.

| Question | Answer |
|---|---|
| Tabs | `WindowFirstShell.jsx:225-230` `TABS` = plan, coming-up, map (`slot: 'mapPane'`), operations (`slot: 'operationsPane'`, `gated`). A slotted tab exists only when App passes that pane. `openedTabs` (:719) mounts a pane on first select and never unmounts it. Hidden panels use the **Tailwind `hidden` class** (:3087), not the attribute. `selectTab` (:868) closes every shell dialog, and it is the shell's one "close everything" list (~30 callers). |
| Opening tab | `utils/initialTab.js`: **phone opens on Plan, tablet and desktop open on Map.** `utils/preloadInitialTab.js` (`main.jsx:17`) preloads that tab's chunk. The rewind seed (`App.jsx:697-708`) also reads `initialTab`. |
| Map mount | App lazy-loads `WindowFirstMapPane` (:54) and passes `mapPane` only when `hasForecastData || auroraTabOn` (:1101-1133). `isMapTabActive` (`onTabChange`) switches App to a `h-[100dvh]` layout (:924, :958, :1026, :1155). |
| 0×0 race | `test/e2e/smoke/map-hidden-mount.spec.js`. The guards are the `ResizeObserver` in `WindowFirstMapPane.jsx:862-883` (`panelShown` from `getBoundingClientRect`, `resizeNonce`), and in `MapView.jsx` `OPENED_UNSIZED`, `HeatBoundsController`, `FlyToController` and `FitBoundsController`, which hold moves until `active`. `MapSizeSync` (:878-886) runs `invalidateSize` on mount **whatever the visibility**. `paneIsOffScreen` matches the `[hidden]` attribute only (:144). Mount fetches: `fetchTravelDayRanges` (:1524), aurora (:2429/:2449), astro (:2509). |
| Map doors | App `openMapTabFromPlan` (:777-858) → `mapTabHandoff` (`source: 'plan'|'map'`, `inPlace`) + `tabRequest {id:'map'}`. Shell: `openMapTab` (:1102), `showOnMap` (:1137), `openWindowOnMap` (:2043, Glance cards and lanes), `pickFindWindow` (:2186), the Find picks on the map (`onFindOnMap`, App `mapFindPick`/`findOpen` :1094-1114), `showAreaOnMap` (:2223), the location sheet footer (:3409), `askShowOnMap` (:1611), App's aurora `handleAuroraViewOnMap` (:660), `onReturnToPlan`/`returnToPlan` (:870), and the rewind seed. Helpers: `mapDoors.js` `openMapDoor`; `mapDoor.js` `doorFromTrigger`/`normalizeMapTrigger`. |
| Map → sheet | `locationSheetHandoff` (shell :1278-1313), with `inPlan: true` switching to Plan first. Used by the phone peek's `openInPlan` (`MapView.jsx:6674`), `askOpenInPlan` (:1792) and `AskPlanThis`. |
| Back engine | `dialogHistory.js`: a stack of layers, each with a marker in `history.state`; **it never touches the URL.** It pushes only under user activation (:241, `hasActivation`). Closing defers `go(-1)` by one task (:239-243). A same-commit swap reuses the leaving layer's entry (`reconcile`, :229-234). `bootDialogHistory` strips stale markers. `MAX_LAYER_DEPTH = 3` (:54) counts every stack entry (:368), as a DEV warning only. `useBackLayer` supports `activationGated`. |
| Two-deep rule | Enforced by hand in the shell. `stackedOverLayer` (:1416). **Every Find route refuses while a sheet stands** (:2110, :2126; A22, comment :3249). `tryOpenMapList` :1570. `mapArrival` (:591) plus its Back layer (:1371) returns to the tab it left. |
| Foreign modals | `mapForeignModal.js` `foreignModalOver(paneRoot)` = any `[role=dialog][aria-modal=true]` **not contained by** the pane. Its readers are seven Escape rules, `useOutsideDismiss` and `paneVisible`. `shellForeignDialog.js:27` `foreignDialogOpen(shellRoot)` = any `role=dialog` outside the shell root, and Ask's sheet/dock, `/` and ⌘K all refuse on it. `Modal.jsx:39` **is not a focus trap**: covered layers go `inert`. No scroll-lock code exists. Phone map sheets (FiltersPopover, MapWarningChips, RegionsJump) are `BottomSheet`s **portalled to `<body>`** (`BottomSheet.jsx:161`, `modal={false}`). |
| History, other | The only hash routing is Operations' `#manage/...` (`ManageView.jsx:80-208`, `useHashSelection.js:44` sets `location.hash` synchronously). `plan-tab.md:175`: never register a `#manage` panel. Invite and link tokens are read from the hash (`takeLinkToken`/`takeInviteToken`). |
| Inside the map | `components/map/MapBreadcrumb.jsx` (returns to the tab of origin). `components/map/WindowControl.jsx` is the existing ‹ › chronological control. `MapCallout.jsx:1385` "Open in ${GLANCE_SHORT_LABEL}". `.wf-map-tab` = MapView root = `MAP_PANE_SELECTOR`. |
| Ask placement | **Desktop (≥1024 px):** the Ask dock and the docked guide are a sibling column of the shell column (`dockedRow`, :1503/:2410; :3101-3130). The map shrinks beside them via its ResizeObserver, and "Show on map" from the dock relies on the dock "staying open beside the map" (:1593-1597). **Tablet:** `AskField` lives in `wf-tabrow` (`askEntry` :1457-1462), sized by a container query on the tab row (`AskField.jsx:18`), with its upsell popover anchored to `wf-tabrow` (:2783). `askGhost` (:1529) only holds the tab row's width. **Phone:** the AskBar (below `Modal` in z-order, :3134), and on the map the peek. |
| Ask view | `ASK_VIEW`/`ASK_DOCK_CONTEXT` (shell :238-250), `AskConversation` `READY_ALL_LABEL` (:31-35), `'coming-up'` PropTypes in AskDock/AskSheet/AskInputRow/AskConversation. Ask's focus fallbacks name tabs: `selectedTabNode` (:1634, :3124), `askRestoreFallback`, `askOpenInPlan` (:1830), `askShowOnMap` (:1612), Find's `focusPlanTab` (:2165), `askSetPostcode` (:1840), `askHighlight` (:1858). **Backend:** `AskRequest.VIEWS = {map, plan, coming-up}` (:41, checked `AskService.java:382`). `ReadyQuestion` `tabs`: `RARE_EVENTS` = coming-up, map; **`SNOW_TOPS` = coming-up only** (:144, :157). `/api/ask/ready` is keyed by scope only, with `tabs` serialised from the enum (`AskReadyServing.java:135`) and filtered client-side (`askModel.js:387`). `ask_log.view VARCHAR(20)`, no CHECK. The seven Ask prompt-regression tests send `"plan"`. `ReadyQuestionTest`/`AskServiceTest` reference `coming-up`. |
| Find | `findOffered` = plan, coming-up or map (:2108). ⌘K: `findKey.js`. Pick handlers :2167-2225. `windows` = `mapFindWindows` vs `heatStripCards` (:3230). Wording: `planSearch.js:399-413`. `PlanSearch.jsx` `tab: oneOf([...])`. The Find button and origin chip are in `MastheadTickLine` (:379-415 is the map-caption variant). |
| Operations | A tab panel only. App passes `operationsPane` only for `isAdmin` (:1138-1142), and that null is the whole client gate. `/api/admin/**` is ADMIN-only (`SecurityConfig.java:98`). With Operations up, the Ask dock, Find and the guide button are off. `openOperations` (:888) sets `#manage/runs/start` and requests the tab. **No load-time route.** |
| Masthead | Phone: `MastheadMenu.jsx` is a `⋯` disclosure popover (`role="group"`, "Account and settings", `aria-controls` `wf-mast-menu-pop`), holding Settings, Change home postcode, Guide and Sign out, plus an admin status line. Shell :508 force-closes it off phone. Desktop/tablet: inline ⚙, `?` and Sign out (:2495-2566). |
| Coming up | `WindowFirstComingUp.jsx`, always mounted, `role=tabpanel aria-labelledby={tab id}` (:236-238). Filter is a plain `useState('all')` and is not remembered. Chips (`comingUpFeed.js:168`): All, Coastal, Night sky, Sun & moon, **Air & dust**. `ALMANAC_DAYS = 90`. `GET /api/almanac?days=90`, eager. Entries inside the forecast days are flagged `inNext4Days`, not excluded. Served types are lower-kebab (`spring-tide`, `king-tide` in `TideAlmanacSource.java:66,69`; `meteor`, `lunar-eclipse`, `nlc-season`, `supermoon`, `eclipse`), and entries carry `regions`. |
| NEW badge | `deriveBadge` (`comingUpArrivals.js:88`), on the tab only, with `badgeAriaLabel` (:2688-2701; the badge span is `aria-hidden`). "Seen" is server-side: `app_user.coming_up_last_seen_at` (V152) → `comingUpLastSeenDate` → `PUT /api/user/settings/coming-up-seen`. It is marked by "Mark seen", plus a one-off bootstrap write while the value is null (shell :833-842). |
| Lanes | From `briefing.hotTopics` (`fourDayTiles.js` `buildGlanceLanes`). An almanac entry is a lane **only if the hot-topic aggregator emits it**. The feed attaches a `jump` (`jumpFor`), and lanes call `onOpenComingUp(entryId)`. Lane copy is in `WindowGlanceLanes.jsx:18-24`. |
| Your places | `ComingUpWatchlist.jsx`, only inside Coming up. Lite gets a "☆ Your places · Pro" upsell. `FAVOURITES_UPSELL_BODY` says "a watchlist in Coming up". |
| Favourites | `user_favourite` (V226): `ck_user_favourite_one_target CHECK ((location_id IS NULL) <> (area_id IS NULL))`, partial unique indexes, cascades. `UserFavouritesController` `/api/user/settings/favourites`, `hasAnyRole('PRO_USER','ADMIN')`. Cap `MAX_FAVOURITES = 25` under an `app_user FOR UPDATE` lock (409, "You can keep up to 25 places."). **`UserFavouritesService` `forAsk` (:120), `replace` (:167) and `build` (:353) treat any non-place row as an area**, and `FavouriteRow` is `(locationId, areaId, createdAt)`. `favouritesApi.setFavourite` maps every kind other than area to `places` (:34), and `FavouriteToggle` `kind: oneOf(['place','area'])`. Latest migration: **V228**. |
| Locations for Lite | `/api/locations` is role-free `findAll()` (`LocationController.java:72-75`), so Lite sees every place. Only ratings are gated (`LitePickShaper`). |
| "At a Glance" | 14 production files carry the label or import it: `tabLabels.js`, `GlanceLabel.jsx`, the shell, `WindowGlanceLanes`, `comingUpFeed`, `guide.js`, `RewindView`, `PlanErrorBoundary`, `HotTopicSimulation`, `AskPlanThis`, `AskConversation`, `RegionForm`, `MapCallout` and `MapBreadcrumb`. Another 14 mention it in comments. `GLANCE_MINUTES`, `buildGlanceLanes` and `WindowGlanceLanes` are internal names and stay. |
| Tests | 58 unit test files match the tab grep. E2E (`src/test/e2e/`): `smoke/tabs.spec.js`, plus `fixture.js:263-272` `openTab`/`openMapTab` used by back, forecast, drilldown-plan, drilldown-map, map-glance, map, map-hidden-mount, map-phone-overflow, window-stepping, find, roles (:58-61 asserts the Operations tab count is 0), roles-write, phone, nginx and `a11y/a11y.spec.js` (Map scans :96/:170, Operations :100-108). **The a11y scan signs in as admin only.** Coverage floors (`vite.config.js:221-235`): global 99/98/98/95, per file 95/90. |
| Guides | `landing/guide/` (12 html plus `shots/`). `frontend/src/utils/guide.js` `GUIDE_PAGES`, `TAB_GUIDE`. `frontend/src/test/guideSections.test.js`. The landing deploys separately (`release-landing.sh`), and the in-app guide iframe loads from the landing origin. |

## 1. Decisions

**D1. One map, a region over an inert screen.**
- **One mount.** The map pane moves out of the tab slot into `MapScreen` (`components/MapScreen.jsx`). It's owned by the shell and rendered **inside `shellRef`**, never portalled, so `foreignDialogOpen` (Ask, `/`, ⌘K) keeps working. There is never a second Leaflet instance.
- **Not an `aria-modal` dialog.** MapScreen is `role="region"`, labelled by its header title (`aria-labelledby`). While it is open, the screen and the masthead behind it are `inert` (the project's existing isolation idiom), which gives assistive tech the same isolation an `aria-modal` would. It is deliberately **not** `role="dialog" aria-modal`, because three things depend on that:
  - `foreignModalOver` treats any `aria-modal` outside `.wf-map-tab` as foreign. An `aria-modal` MapScreen wraps the pane, so it would count as foreign, and that would freeze `paneVisible` and stand down all the map's Escape and outside-press rules.
  - `foreignDialogOpen` would kill Ask and Find.
  - The desktop Ask dock, the docked guide and the phone `BottomSheet`s portalled to `<body>` would all fall outside an `aria-modal` and be hidden from screen readers.
- **The predicates stay untouched.** With no `aria-modal` on MapScreen, `mapForeignModal.js` and `shellForeignDialog.js` need no change. A test pins that no `[aria-modal]` sits on or above `.wf-map-tab` when nothing is stacked. (This departs from the handoff prompt's "an `aria-modal` view" lens; §7 Q6.)
- **What is inert.** The inert set is the screen column and the masthead, never the dock column, the AskBar or `<body>` portals.
- **In O1:**
  - The "Map" tab button becomes a door, rendered **outside** `role="tablist"` and dropped from `handleTabKey`'s roving index. Leaving it inside would break axe's `aria-required-children` rule.
  - `slot: 'mapPane'`, the map's tabpanel and `wf-body--map` go.

**D2. Opening the map view is one function: `openMap({window, focus, from})`.**
- **The arguments:**
  - `window` = `{date, eventType}`, or `null` for the next window still to come.
  - `focus` = `{kind:'location', id|name}`, `{kind:'area', …}`, `{kind:'region', …}`, `{kind:'names', focusNames, title, caption}`, `{kind:'aurora'}`, or `null`.
  - `from` = the element focus returns to.
- **Doors it replaces:**
  - `openMapTabFromPlan`, `tabRequest {id:'map'}` and `openMapTab`
  - `showOnMap`, `openWindowOnMap` and `pickFindWindow`
  - `askShowOnMap` and `handleAuroraViewOnMap`'s tab request
  - `onReturnToPlan` (deleted) and the rewind seed (seeds `openMap` or nothing)
- **What it reuses.** Underneath, it keeps today's handoff (`mapTabHandoff` → `planHandoff` → MapView's `planDoor`), so MapView's landing, lens, scope and camera are unchanged. `doorFromTrigger`, `normalizeMapTrigger` and `openMapDoor` stay as translators.
- **Order of work.** `openMap` first calls `closeShellLayers()` (D17), so Find or a sheet closes and its Back entry unwinds before the map's entry is taken (see D4's swap rule).
- **Already open.** If the map view is already open (the old `inPlace`), `openMap` re-targets it and pushes nothing.

**D3. The map view is the base layer.**
- **Depth.** The map view's own Back entry counts in `dialogHistory`'s stack. `MAX_LAYER_DEPTH` stays **3**, and its doc and the CLAUDE.md invariant are reworded to "the map view plus two".
- **What stacks over it:**
  - A sheet, Find or the List is layer 1.
  - The guide's modal, or a second sheet (area sheet → place sheet), over that is layer 2.
  - Find still refuses while a sheet stands (A22). That is unchanged.
- **`mapArrival`.** It and its Back layer are deleted. The map view's own entry replaces them.
- **Back and Esc order.** Back closes the top layer first and the map view last. Esc does the same, and MapView's inline layers (callout, popovers, List) close before the view itself. Those inline layers keep their existing Escape rules.
- **Stacking over the map.** A sheet over the map makes MapScreen `inert` under it, by the shell's existing `stacked`/inert protocol. `warmStackedChunks` covers the routes that start from the view.
- **Phone portals.** Phone `BottomSheet`s keep portalling to `<body>`, which is fine with no `aria-modal` on MapScreen. MapScreen's inert set must never include `<body>` portals.

**D4. The map view's address: `#map/<yyyy-mm-dd>/<sunrise|sunset|night|aurora>[/<locationId>]`.**
- **Format.** `utils/mapRoute.js` parses and formats it as a pure function.
- **Who writes the URL.** The URL lives **on the layer**:
  - `registerBackLayer({url})` stores `layer.url`, and `update({url})` changes it.
  - The engine writes it when it pushes, when a swap takes over an entry, and whenever traversal lands at that layer's depth. Every write copies `history.state`.
  - Callers never call `replaceState` on the URL themselves. That's what keeps the URL correct when a sheet or Find is over the map while the window steps, and through the same-commit swap of the location sheet's "Show on map".
- **Closing.** Closing returns to the bare path.
- **An entry-less layer.** A layer with no history entry (the load-time case, or a push refused without activation) closes with `replaceState(history.state, '', bare)`.
- **Load-time:**
  - App reads `#map/...` once after auth and immediately `replaceState`s to the bare path.
  - It opens the view with `activationGated: true`, so the first gesture pushes the `#map` entry. Until then Back is the browser's, which matches today's opening-tab behaviour.
  - A past or unforecast window opens the screen with a one-line, dismissible `ScreenNote` under the lens: "That sunrise has passed" or "Not forecast yet".
  - An unknown or disabled `locationId` opens the window with no focus. **Lite is focused like anyone else.** `/api/locations` is role-free, and the callout shows today's gated state.
  - Area, region and names focus have no URL form, so a refresh keeps only the window.
- **Signing in.** The `#map` hash must survive sign-in. The check is against `takeLinkToken`/`takeInviteToken`, which read tokens from the hash, so the route is read only once neither token is present. A signed-out reader keeps the route through the login screen and lands on the view.
- **No personal data in the URL.** No origin, postcode, drive limit or lens.
- **`#manage` is never parsed here.** Opening Operations while the map is open closes the map and waits for the engine to settle before setting the hash (D9). The engine exposes `whenSettled()`, or the open is held until `data-back-depth` is 0.

**D5. Further ahead lists what This week doesn't already carry.**
- **The rule.** An entry is hidden from Further ahead only when This week renders a lane that carries its jump (the existing `jumpFor`, read from served hot topics and served entries). In-forecast entries with no lane stay, in date order. Everything past `lastPlanDateStr` is listed.
- **Why not a cut by date.** A date cut alone would make lane-less entries, such as an equinox or a dusk moonrise, vanish from both sections.
- **Lane jumps.** A lane jump (`onOpenComingUp`) scrolls to and expands the entry when it is listed. For a graduated entry, which is hidden, the jump becomes `openMap` on its window, or nothing when it has none.
- **Lane copy.** The copy in `WindowGlanceLanes.jsx:18-24` is reworded.

**D6. The NEW badge keeps today's rules** (server-side `comingUpLastSeenDate`, plus Mark seen).
- **Bootstrap.** The one-off bootstrap write (while null) fires the first time Further ahead's heading intersects the viewport, instead of on the first tab open.
- **One result, two places.** One `deriveBadge` result renders on the "Further ahead ↓" jump and on the heading.
- **Accessible names.** The tab's `badgeAriaLabel` moves onto the jump, e.g. "Further ahead, 2 new dates". The heading's accessible name stays clean, and the badge span stays `aria-hidden`. The tab plumbing is deleted.
- **Q3.** The handoff's "reaching the section marks seen" is a rule change (§7 Q3).

**D7. The remembered filter is local.**
- **Storage.** `localStorage['furtherAheadFilter']` holds a chip id. It's validated against `FILTER_CHIPS` on read and read inside `try/catch`.
- **Chips.** They keep `aria-pressed` (documented at `WindowFirstComingUp.jsx:381-384`). Air & dust stays a chip.

**D8. Ask's view is `'map'` when the map view is open, otherwise `'plan'`.**
- **Client:**
  - `ASK_VIEW` and `ASK_DOCK_CONTEXT` are keyed on `mapOpen`. The labels are "This week" and the map view's title.
  - `READY_ALL_LABEL` and the `'coming-up'` PropTypes go.
  - Every tab-named focus fallback in §0's Ask row is replaced: the screen's main heading, or the map view's title, which carries `tabIndex=-1`.
  - `askHighlight` gates on `!mapOpen`.
- **Backend, in O2, not O3.** Moving it to O2 closes the gap where Further ahead is already on the `plan` view.
  - `SNOW_TOPS` becomes `plan` and `RARE_EVENTS` becomes `plan, map`.
  - `AskService`'s validation maps an incoming `"coming-up"` to `"plan"` before logging, so `ask_log` records `plan`. It keeps accepting `coming-up` for one release, for clients loaded before the deploy, and a follow-up drops it.
  - `/api/ask/ready` is keyed by scope only, so the change is live on deploy with no stored-data migration.
- **Tests.** `ReadyQuestionTest` and `AskServiceTest` are updated. The prompt-regression tests send `"plan"`, which survives, so **no regression assertion changes**.

**D9. Operations is an admin-only full-screen view, reached from the menu.**
- **Admin only.** Pro and Lite get no menu row at all: no pill, no greyed row, no upsell, as `roles-and-gating.md` requires for admin-only features. App passes the Operations content only for admin, as today, and the backend's 403 stays the real enforcement.
- **One menu, every width:**
  - `MastheadMenu` becomes the menu at every width. Desktop's inline ⚙, `?` and Sign out fold into it (§7 Q2 asks about the glyph).
  - Rows: Settings, Change home postcode, Guide, **Operations (admin)**, Sign out.
  - It keeps the disclosure `role="group"` pattern (correct for navigation buttons, as `MastheadMenu.jsx:11` explains). Its label becomes "Menu". It's one mount, so `aria-controls` stays single.
  - The force-close at shell :508 is removed.
- **What `OperationsScreen` is.** Full-screen, open exactly while `location.hash` starts with `#manage` (App's `hashchange` listener). It is **not** a Back layer, because its hashes already make Back work (`plan-tab.md:175`).
- **What it hides.** While it is open, the screen, the masthead, the Ask dock and AskBar, and Find (⌘K) are hidden with the `hidden` attribute. The screen stays mounted, so its scroll is kept, and nothing behind is reachable by Tab or a screen reader.
- **Focus.**
  - **Opening:** focus moves to Operations' heading (`tabIndex=-1`).
  - **Closing, with "‹ PhotoCast":** `pushState` to the bare path, then focus returns to the menu button.
  - **Closing, with Back:** Back past the first `#manage` entry closes it too.
- **Doors:**
  - **The aurora banner** (`openOperations`) closes the map view if it is open, waits for the engine to settle (D4), then sets the hash.
  - **A load-time `/#manage/...`** opens Operations for an admin. This is new: deep links didn't work before.
  - **A non-admin on `#manage`** gets `replaceState` to the bare path and the screen, and no ManageView is ever imported. Tested for Lite and Pro.
- **Lands in O3**, with the tablist removal, so admins are never without a way in.

**D10. Mount.**
- **Preload, then mount on first open.** The map chunk is preloaded on idle after first paint (`requestIdleCallback`, falling back to a timeout) when the reader has forecast data. That replaces `preloadInitialTab.js`.
- **Visible from its first commit.** The pane mounts **on the first open**, visible from its first commit, so the hidden-mount race can't happen on first open. The first tap is instant because the chunk is already loaded.
- **Kept mounted after closing.** Closing keeps it mounted behind the **`hidden` attribute** (`display: none`), which is today's sticky-tab behaviour. That makes `panelShown` false and satisfies `paneIsOffScreen`. Phone cost is unchanged, because the mount fetches still wait for the first open.
- **No transform on the pane.** Never put a transform on the pane or its ancestors, and never animate with `scale` or `translate`, because `getBoundingClientRect` includes transforms and ResizeObserver doesn't fire on them. The enter animation is opacity only. Closed means `hidden`, never `visibility`/opacity/off-screen.
- **Tests:**
  - Every guard flips on close and on reopen.
  - No fit or fly while hidden. (`MapSizeSync` may still run `invalidateSize`, which is safe on the stored size and is today's behaviour.)
  - One fit after showing.
- **E2E: the first open.** `map-hidden-mount.spec.js` is rewritten to hold the chunk, open the view from a card while it is still loading, release it, and assert zoom < 10 and at least 5 chips.
- **E2E: reopening.** A second case closes the view and reopens it on another window, with the same assertions.
- **E2E: slow 4G.** The slow-4G acceptance runs that spec under CDP network throttling (Chromium only).

**D11. Scroll and focus return.**
- **Scroll.** The screen never unmounts or re-lays-out under the view. It only goes `inert`, and MapScreen is `position: fixed`. A body scroll lock is **new code** (`hooks/useBodyScrollLock.js`), because none exists. Test: scroll to card N, open, close, `scrollY` unchanged.
- **Focus on open.** Initial focus goes to the view's title (`tabIndex=-1`), never the Leaflet container.
- **Focus on close.** Focus returns to `from` if it is still connected. Otherwise it goes to the This week heading (`tabIndex=-1`). A door with no element (load-time, aurora) also lands on that heading.
- **Sticky headings and focus.** `scroll-margin-top`, sized from the sticky stack, applies to every focusable element in both sections, so the sticky headings never cover focus.
- **Layout.** `isMapTabActive`'s layout switch is deleted.

**D12. The map view's frame, header and inside.**
- **Frame:**
  - **Phone:** `100dvh`.
  - **Tablet and desktop:** inset 18px over a scrim. With the desktop Ask dock or docked guide open, the view's box **ends at the dock column's edge**: the dock stays beside the map and interactive, as today, and the pane's ResizeObserver absorbs the width. Clicking the scrim closes the view only when nothing is stacked.
- **z-order:** above the AskBar, below `Modal` (z-50).
- **Header:**
  - ‹ › stepping, by **hosting the existing `WindowControl`**. MapView's own mount moves up through a slot, so there is still one control. Because the header sits outside `.wf-map-tab`, WindowControl's Escape and outside-press checks resolve the pane through MapScreen's root (`foreignModalOverPaneOf` given the MapScreen root, or the header rendered inside `.wf-map-tab`; the build picks one and tests Escape from the header).
  - ←/→ steps only while focus is in the header or view chrome, never while the Leaflet container (which pans), an input or a list has it.
  - The window title, verdict, best ★ and confidence, all served.
  - ⌕ Find and the origin chip, at every width (D13).
  - The Ask entry: **tablet** gets an `AskField` in the header, which opens the sheet; desktop uses the dock; phone uses the peek.
  - ✕ Close.
- **Inside:**
  - `MapBreadcrumb` is deleted.
  - **"Open in Glance"** (`MapCallout`), the phone peek's `openInPlan`, `askOpenInPlan` and `AskPlanThis` keep their behaviour: close the view, open the four-day location sheet on the screen, then return focus to the screen when it closes. That's `locationSheetHandoff`'s `inPlan: true`. `inPlan: false` stays "the sheet over the map, as layer 1".
  - The label becomes "Show in This week".

**D13. Find in two contexts.**
- **The prop.** `PlanSearch`'s `tab` prop becomes `context: 'screen' | 'map'`, derived from `mapOpen`.
- **What follows from it.** The context drives four things:
  - the wording (`planSearch.js`)
  - `windows` (`mapFindWindows` vs `heatStripCards`)
  - the `onFindOnMap` branches
  - App's `mapFindPick` / `findOpen`
- **On the screen:**
  - A location pick opens its sheet.
  - An area pick opens the area sheet.
  - A region pick sets the origin.
  - A window pick closes Find (through `closeShellLayers`), then calls `openMap`.
- **In the map:**
  - A location, area or region pick stays in the view: fly plus callout, the named-spot lens, or a pan. No sheet, and no `setOrigin`.
  - A window pick re-targets the view and pushes nothing.
- **Entry:**
  - ⌘K works on the screen and in the map, never in Operations.
  - The map header carries ⌕ Find on touch.
  - The "coming-up" branches go.

**D14. The masthead without a tab row** (O3).
- **Desktop and tablet:** brand, origin chip, Find (⌘K), the Ask field and the menu.
- **Phone:** the tick line with the ⌕ Find icon, plus the AskBar, unchanged.
- **The Ask field's move** is CSS work:
  - `AskField`'s container query re-targets the masthead row.
  - The field-placement `AskUpsellPopover` re-anchors to the field.
  - `askGhost` is deleted.
- **Tab-row CSS.** `wf-tabrow` and the `wf-tab*` selectors go, each checked against the live tree first.

**D15. Your places at the top of This week** (O2).
- **Pro: `YourPlacesRow`.** `ComingUpWatchlist` becomes `YourPlacesRow`, one horizontal row of compact chips. It scrolls sideways, with scroll-snap on phone.
- **What a chip shows.** Each chip shows the place or area, its best window and stars, from the served `topWindowFor` and `useAreaOutlook`.
- **What a tap does.** A tap opens `openMap` on that window, focused on the place or area.
- **Lite.** Lite gets the one-line upsell. `FAVOURITES_UPSELL_BODY` is rewritten, because it says "a watchlist in Coming up", which is false after this phase.
- **Fetching.** `useAreaOutlook` fetches when the row intersects the viewport.

**D16. Watch a date** (O2b).
- **Schema (V229):**
  - `ALTER TABLE user_favourite ADD COLUMN almanac_key VARCHAR(64)`.
  - `DROP CONSTRAINT ck_user_favourite_one_target`.
  - `ADD CONSTRAINT ck_user_favourite_one_target CHECK (num_nonnulls(location_id, area_id, almanac_key) = 1)`.
  - A partial unique index on `(user_id, almanac_key) WHERE almanac_key IS NOT NULL`.
  - A Testcontainers test for each rule.
- **The key:**
  - **Format.** `<type>:<startDate>`, with the served lower-kebab types (e.g. `king-tide:2026-12-03`).
  - **Validation.** Validated server-side against the `*AlmanacSource` `TYPE` constants and an ISO date, otherwise 400.
  - **Tides.** Tide types match **by family**, so a run reclassified spring ↔ king, or a start that drifts by a few days, still matches: same family, with the date ranges overlapping. Otherwise a watch on an entry that drifts would silently orphan.
  - **More than one match.** If two entries match (different `regions`), the watch shows the earliest.
- **Service, made three-way.**
  - `FavouriteRow` gains `almanacKey`. `forAsk`, `replace` and `build` get an explicit three-way branch, and the place and area reads filter `almanac_key IS NULL`, so Ask's `rank_spots.favourites` and the `locationIds` expansion never see a date row.
  - Tests cover `forAsk`, `build` and `replace` with a date row present.
- **The cap:**
  - Expired date rows (start date before UK-civil today minus `WATCH_GRACE_DAYS = 14`, through `ForecastHorizon` with an injected `Clock`) are **deleted under the same lock on every write**, so invisible rows never block the cap.
  - `replace`'s cap counts dates too.
  - `LIMIT_MESSAGE` is reworded ("You can keep up to 25 places and dates.").
  - GET filters expired rows too.
- **Endpoints:** `POST`/`DELETE /api/user/settings/favourites/dates/{key}`. GET adds `dates: [{key, createdAt}]`. It's the same personal prefix, and `HttpCachingConfigTest` asserts it. Lite gets 403, the cap gets 409, a bad key gets 400.
- **Frontend:**
  - `favouritesApi.setFavourite` gets an explicit `date` kind and path.
  - `FavouriteToggle` gets `kind` `'date'`, with date-worded upsell copy.
  - **The match.** The type-family plus overlap lookup is a client lookup over served fields. It is licensed as a named derivation in `architecture-decisions.md` (§7 Q4). The alternative would put personal data on the ETag-shared `/api/almanac`, which the invariants forbid.
- **The toggle:**
  - A `<button aria-pressed>` "Watch this date" (the label stays the same and `aria-pressed` carries the state).
  - It sits outside the entry's disclosure button.
  - It isn't rendered until favourites are `ready`.
  - Lite gets the `PRO` pill and the upsell.
- **The chip, beyond the forecast:** the entry's calendar tile (as Further ahead's rail draws it) then `★ <glyph> <title>` with `best spots once it's forecast`; no "in N days" countdown. A tap scrolls to the entry and expands it.
- **The chip, inside the forecast:** it shows that morning's window, the best spot and its stars, and "tide on the light", all from served fields. A tap calls `openMap`.
- **Which window, inside the forecast:**
  - **A tide:** the window marked on the light, with the higher served rating breaking a tie.
  - **A topic with its own window:** that window.
  - **No window** (a meteor peak): a static chip reading "clear-sky check on the night", not a button.
  - **A needed field isn't served:** that case falls back to the beyond-forecast chip. The PR lists the fields it reads.

**D17. `selectTab` becomes `closeShellLayers()`.** The body is the same, and it keeps its role as the shell's one list of what a dialog is. Callers keep calling the one list, never inlining it. It's introduced in O1, because `openMap` needs it, and `selectTab` is deleted in O3.

## 2. Phases

### O1 — the map view (the tabs still exist)
- **The map view:**
  - `MapScreen` (D1, D12), `openMap` and `closeShellLayers` (D2, D17).
  - `mapRoute.js` and the `dialogHistory` `url` with `update({url})` (D4).
  - `whenSettled()` (D4), load-time `#map`, and `ScreenNote`.
  - `useBodyScrollLock`, and the mount (D10).
- **Doors.** Every door in §0 routes through `openMap`. The Map tab button becomes a door outside the tablist (D1). `initialTab === 'map'` (tablet and desktop) opens the map view at load through the load-time route path, which keeps desktop's landing until O3.
- **Ask and Find:** Ask's view and focus fallbacks for the map (D8 client part), the tablet `AskField` in the map header, the dock column beside the view, and Find's `context` (D13).
- **Removed:** `slot: 'mapPane'`, the map tabpanel, `wf-body--map`, `mapArrival`, `MapBreadcrumb`, `onReturnToPlan`, the layout switch, `tabRequest {id:'map'}` and `preloadInitialTab.js`.
- **Unit tests:**
  - `openMap`, one per door.
  - `mapRoute`, including bad input.
  - The layer-URL engine: push, swap-take, landing, entry-less close, and a step while a sheet stands.
  - Back order.
  - Scroll and focus return.
  - No `aria-modal` on or above the pane.
  - Escape from the header.
  - The guard flips.
  - Dock open plus map open, both interactive.
  - ⌘K, `/` and the Ask field with the view open.
  - Find picks in both contexts.
- **E2E:**
  - Rewrite `map-hidden-mount`, `map`, `map-glance`, `window-stepping`, `drilldown-map`, `back` and `map-phone-overflow`, plus `a11y/a11y.spec.js`'s Map scans.
  - New `smoke/map-view.spec.js`: card → view → ✕/Back/Esc with the same scroll and focus, a `#map` refresh, the past-window note, and Back after a load-time open.
- **Docs:** `map-tab.md` gets a "Map view" section, and `dialogHistory`'s doc covers the `url` option.

### O2 — Further ahead
- **The section:**
  - `WindowFirstComingUp` is renamed `FurtherAhead` and rendered as `<section aria-labelledby={h2 id}>` at the bottom of the screen, under a sticky "Further ahead · dates known in advance" `<h2>`.
  - This week gets a sticky "This week · forecast" `<h2>` carrying the jump and the badge (D6).
  - The sticky offsets come from a CSS custom property set from the lens' measured height, through a `ResizeObserver`.
- **Moved in:** the chips and remembered filter (D7), graduation (D5), the lane jumps (D5), `YourPlacesRow` (D15), the since-line and warning footer, and the D8 backend `ReadyQuestion` change.
- **Repointed:** the Coming up tab still exists, but nothing links to it. Find's coming-up branch, the lane jumps and the masthead door all point at the section.
- **Unit tests:**
  - The section.
  - Filter persistence, including a bad stored value.
  - Graduation, with and without a lane.
  - The badge on the jump and the heading, plus the bootstrap-on-intersect.
  - `YourPlacesRow` for Pro, Lite and empty.
  - `ReadyQuestionTest` and `AskServiceTest`.
- **E2E:** `smoke/further-ahead.spec.js` covers the jump, and the filter remembered across a reload. The a11y spec's Coming up scan targets the section.

### O2b — watch a date
D16 end to end, with its tests. **E2E:** adds a Lite a11y scan (the upsell, the Pro pill and the watch toggle) and a Pro "watch → chip" smoke test.

### O3 — remove the tabs and the name
- **The tab model:** delete `TABS`, the tablist, `tabDomId`, `openedTabs`, `selectTab` (D17), tab focus management, the Coming up tab and its badge plumbing, `initialTab.js` and the prop (§7 Q1), and the rewind seed's tab.
- **Operations and the menu** (D9).
- **The masthead** (D14).
- **The name:** delete `tabLabels.js`, `GlanceLabel.jsx` and every "At a Glance" string (§0), using section-name wording. Ask's labels go too (D8).
- **CSS:** `wf-tab*`, `wf-tabrow`, `wf-tab-gated` and any Coming up pane CSS the section doesn't reuse, each checked against the live tree first.
- **Unit tests:** rewritten where the behaviour survives, deleted where it doesn't.
- **E2E:**
  - Delete `smoke/tabs.spec.js`.
  - New `smoke/screen.spec.js`: no tablist; the admin menu shows Operations; Pro and Lite get no row (this replaces `roles.spec.js:58-61`'s vacuous count, not a duplicate); Operations opens and closes, Back works inside it; a non-admin `/#manage` is cleared; an admin `/#manage/runs` deep link opens it.
  - Rewrite `fixture.js` helpers and every spec in §0. The a11y Operations scan goes through the menu.
- **Acceptance**, pasted into the PR:
  - `grep -rn "selectTab\|initialTab\|'coming-up'\|role=\"tab\"" frontend/src` → only Operations' inner tabs.
  - `grep -rn "At a Glance\|GLANCE_LABEL\|Open in Glance" frontend/src` → nothing.
  - `npx madge --orphans frontend/src` → no new orphans.
- **Docs:**
  - Rewrite `plan-tab.md` and `map-tab.md` around the one screen and the map view.
  - CLAUDE.md's Dialogs/Back invariant: the map view is the base layer, two over it, `mapArrival` gone, and a `#map` URL carried on the layer.
  - `architecture-decisions.md` gets D16's lookup (if Q4 is approved).
  - The supersede notes in §6.

### O4 — guides and landing (release only after this; owner, 2026-11-23: "a big update of the landing page and help pages")
Two PRs, so the guide rewrite and the pitch are reviewed separately:
- **O4a, the help guides.** The 12 pages in `landing/guide/`, `guide-search.js`, `utils/guide.js` and `guideSections.test.js`.
- **O4b, the landing page.** `index.html`, `faq.html`, pricing, and the root shots `shot-map`, `shot-detail`, `field-*`, `screenshot*.png`.

Scale today, from the 2026-11-23 grep:

| Term | Pages | Hits |
|---|---|---|
| "tab" | 10 | 26 |
| "Coming up" | 13 | 45 |
| "At a Glance" | 13 | 50 |
| "Map tab" | 2 | 2 |

Screenshots: 14 shots in `guide/shots/`, plus the landing root's own. Every page is read end to end, not only grep-fixed, because the workflow framing changes how each page opens.

Each guide page is checked in the browser from a local landing build (desktop and phone). Every new screenshot is taken from a local app build with seeded data, as webp, and opened to confirm there is no tab bar.

The rest of this section is the handoff's §5, in `landing/guide/` and the landing root:
- **New and rewritten pages:**
  - `getting-started.html` becomes the workflow.
  - `at-a-glance.html` → `planning.html` (`#this-week`, `#further-ahead`, `#watch-a-date`), with a meta-refresh stub left at the old address.
  - `coming-up.html` folds into `planning.html`. **Both old pages (`at-a-glance.html` and `coming-up.html`) are kept as redirect stubs, not deleted**: links to them exist in the wild (the landing, the FAQ, bookmarks and app builds already out). Each is a meta refresh (3 s, so the script decides first) plus `moved.js`, which keeps the old `#section` (`#list` becomes `#further-ahead`, `#watchlist` becomes `#your-places`) and `?embed=1`. They are not in `GUIDE_PAGES` or the search index. Every link and anchor is repointed, along with `guide-search.js`, the hub, `utils/guide.js` (`TAB_GUIDE` becomes `SCREEN_GUIDE`) and `guideSections.test.js`.
  - `map.html` is rewritten.
- **Edits to existing pages:** `your-places.html` gets watched dates. Every page is grepped for "tab", "Coming up", "Map tab", "At a Glance" and "Glance".
- **Screenshots:** new webp shots replace every one showing the tab bar or the Coming up tab, each opened and checked.
- **Landing:** the pitch is reframed around planning a shoot, within the honest-claims rules.
- **Deploy order:** the redirect stub and the landing release (the owner runs `release-landing.sh`) go before any app build linking `planning.html`.

## 3. Risks

| Risk | Mitigation |
|---|---|
| The map view isolates itself wrongly and freezes the pane, kills Ask or Find, or hides the dock | D1: a region over an inert screen, not an `aria-modal`; it renders inside `shellRef`; tests pin all three |
| A load-time `#map` breaks Back | D4: replace to bare, then an activation-gated push; entry-less close |
| The URL goes stale under stacked layers or a swap | D4: the URL lives on the layer, written by the engine |
| An Operations hash races the map close | D4/D9: `whenSettled()` |
| The 0×0 race | D10: mount on first open; `hidden` attribute when closed; no transforms; spec rewritten plus throttled run |
| Dates vanish between the sections | D5 lane-carried rule |
| Date rows corrupt favourites reads or the cap | D16: explicit three-way branches, filtered reads, expiry delete under the lock |
| Desktop readers lose the Map landing | §7 Q1 |
| Old clients send `view: 'coming-up'` | D8: mapped for one release |
| Guide links 404 | O4 deploy order |
| Coverage floors | Never lowered; per-file 95/90 on every new file |

## 4. Review lenses (run on draft 1)
1. Dialog layering and Back with the map view as the base layer.
2. Warm mount and the hidden-mount Leaflet race.
3. Ask's view context.
4. Find's behaviour.
5. Lite and admin gating.
6. a11y.

Each phase's built diff gets its own adversarial review before commit (CLAUDE.md *UI Work — Review Cadence*).

## 5. Build process
Per CLAUDE.md, a Sonnet subagent builds each phase in its own worktree from this plan. It runs the build ladder, the frontend's four commands and the E2E smoke until they are green. The planner reviews the diff read-only, and any fixes go back to Sonnet. Then a `changelog.d/` entry, a ready PR, green CI and Codex 👍 on the head commit, merge, and the next phase branches from the updated `main`.

## 6. Superseded on merge of O3
**Done in O3 (2026-11-24).** Each of these has a one-line pointer to this plan (`plan-tab.md` also carries "The one screen", and `map-tab.md` the map-view note):
- `default-tab-by-device-plan.md` (whole)
- `map-tab-v2-plan.md` (tab parts)
- `coming-up-plan.md` and the other `coming-up-*` plans (tab parts)
- the `mapArrival` section of `plan-tab.md`

## 7. Owner answers (2026-11-23)
- **Q1, the landing: every device opens on This week.** The map view opens only from a door. O1 keeps desktop's Map landing (via the load-time route) until O3 deletes `initialTab`.
- **Q2, the menu: one ☰ menu at every width.** Desktop's ⚙, `?` and Sign out fold into it, and Operations is a row only admins see (D9).
- **Q3, the NEW badge: keep today's rule** ("Mark seen", plus the one-off bootstrap, D6). Scrolling past doesn't clear it.
- **Q6, the map view: a labelled region over an inert screen, not `aria-modal`** (D1).
- **Q4 and Q5 take the planner's defaults**, since the owner wasn't asked and can override them:
  - **Q4:** the watch match (type family plus date overlap) is licensed as a named client derivation in `architecture-decisions.md` (O2b).
  - **Q5:** night and aurora windows use `#map/<date>/night` and `#map/<date>/aurora`.

## 8. Draft-1 review findings → where they're answered
- **Blockers** (foreign-modal containment ×2, three-way favourites reads, desktop dock covered, tablet Ask entry, MapScreen portalled): D1, D12, D16.
- **Should-fix:**
  - `MAX_LAYER_DEPTH` and the Find-over-sheet example: D3.
  - Load-time entry and the URL living on the layer: D4.
  - The Operations race: D4/D9.
  - No focus trap or scroll lock exists: D1/D11.
  - Phone portals: D3.
  - Phone mount cost, the `MapSizeSync` assertion, `hidden`/no transforms: D10.
  - Find entry and Find contexts: D12/D13.
  - Open-in-plan from the map: D12.
  - Ask focus fallbacks and the regression-test fact: D8.
  - Expired rows and the cap, the key format and drift, the frontend favourites kinds and upsell copy: D15/D16.
  - The Lite location fact: D4.
  - The door inside the tablist: D1.
  - OperationsScreen a11y and the roles spec: D9/O3.
  - The menu's label and force-close: D9.
  - Badge naming and tabpanel → section: D6/O2.
  - `scroll-margin` everywhere, the a11y spec in every phase, and a Lite scan: D11/O1–O2b.
- **Nits:** door list, token check, ready-tag phasing, Ask labels, `AskField` CSS: D2, D4, D8, D14.
