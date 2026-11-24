# The screen spec, P1–P5 — plan

**Status:** plan, **draft 2 (2026-11-24)**. Draft 1 was reviewed adversarially under six lenses (space budget; backend-heavy, honesty and the two-question rule; accessibility; Lite gating; the Leaflet hidden-mount race under the motion; deletions and the code map), by three reviewers with two lenses each. It had 9 blockers and 27 should-fix findings. Each one is folded in below, and §10 maps them to the sections that answer them.

**Source.** `docs/engineering/screen-spec.md` is the owner's single specification for the screen. It is copied verbatim from `design_spec_photocast_screen/SPEC.md`. The spec wins over this plan. Where this plan has to choose something the spec leaves open, §9 records the choice.

**Owner rules (from the spec and its prompt):**
- No feature flags. Each phase replaces the old behaviour outright.
- Each phase deletes what it replaces in the same PR: code, CSS, tests, E2E, docs and guide text.
- Never lower the coverage thresholds.
- One PR per phase, P1 → P5, each branched from the updated `main`.

**Not touched:**
- ratings, verdicts and the Best bet choice
- the Coming up (`/api/almanac`) payload
- any Claude prompt, except the Ask tool-schema wording that says "starred" for favourites

## 0. What the code says today (`main` @ 684e25bf5)

Paths are under `frontend/src` unless they say otherwise.

| Question | Answer |
|---|---|
| ★ as "yours" | **`YourPlacesRow.jsx`:** :118 (Lite `☆`), :200 heading `★ Your places`, :217 date chips, :270/:276 "starred area(s)" / "Your starred places…". **`shared/FavouriteToggle.jsx`:** :188 locked `☆`; :221 `★/☆` with `aria-pressed` (CSS `index.css:14643` keys on `[aria-pressed='true']`); place/area label `'Your place'` (:171); `WATCH_LABEL='Watch this date'` (:19); `WATCH_UPSELL_BODY` (:16) says a watch "joins your places"; docblock :138-147. **`FavouriteMark`** (:255) renders `★`; it is used by `map/MapLabels.jsx:1060` (aria-label part `'your place'` at :1018) and by `WindowSpotCard.jsx:97` (sr-only :99). **`WindowFirstHeatStrip.jsx`:** :1978 `wf-heat-best-fav`, :1667 spoken ", your place". **`map/RegionsJump.jsx`:** :157, :178. **`utils/favourites.js`:** :101, :106 (`pickerEntries`), and the toasts at :121-123 ("Added X to your places", "Watching X. It joins your places."). **`utils/planSearch.js`:** :643 (resting group). **`PlanSearch.jsx`:** :353, :357. **Backend:** only `AskToolSchemas.java:126-129`, "favourite or starred places". **Landing:** `your-places.html`, `finding-places.html:69`, `map.html:93`, `planning.html:200,214`, `getting-started.html:42`. |
| Tests on those | `FavouriteSurfaces`, `WatchDate`, `WindowFirstShellFind`, `MapLabels`, `WindowFirstHeatStrip`, `favourites`, `FavouritesContext`, `MapCallout`, `MapViewFind`, `UndoToast` and `guideSections` unit tests. **E2E:** `smoke/roles-write.spec.js:82-121` (finds the toggle by `[aria-pressed="false"]`, and asserts the watch joins Your places as a chip) and `a11y/a11y.spec.js:204-212` (`aria-pressed` and the "Added … to your places" toast). |
| Lens | `WindowFirstLensBar.jsx`, a sticky `div.wf-lens` at `top: var(--safe-t)` (`index.css:3379`). **Phone (:9838-9868):** 130 px tall, or 153.5 px with the override marks; two rows of 40 px chips, the count on its own row, 18 px padding. **Readout:** the count (`formatLensCount`, `utils/reachLens.js:369`) or, on desktop, `formatLensReadout` (:424, tier · default words · rating · count). The plan-conflict verdict replaces the count (:352-363). **Hooks:** `useStuckSentinel` and `useLensReserve` (`--wf-lens-reserve`, first-paint `160px`; `--wf-bar-h` `54px`). |
| Phone heights today | `main` top pad 24 (`App.jsx:978`) · masthead 44 · lens 130 · `.wf-body` top 13 · This week bar 42 · gap 10 · Your places 52 (two-line chips, `min-height 44`) · gap 10 · "The days ahead" ≈ 22. **First day ≈ 347 px down.** |
| Your places | `YourPlacesRow.jsx`: rows `[...dates, ...areas, ...places]` (:99); places in insertion order, never by rating (docblock :24-25). Area figures arrive late (`useAreaOutlook`, :78). **Date-only code:** `buildWatchedChips` (`watchedDates.js:225`, sole caller :97), the date `press` branch (:163-178), the `graduatedIds`/`firstWindow` branch (:69, :171, :299; shell :2574), inert rows, the ★ prefix, `wf-yp-item-date`, the stop-watching ✕ (:251-261), the off-plan date sentence (:277), and dates in `nothingStarred` (:77). |
| Watched dates | `user_favourite.almanac_key` (V229). `FavouritesContext` gives `dates`, `isDateWatched`, `setStar('date', …)`, and the Undo toast with a `returnTo` element captured at press time (:154-161). `watchKeysMatching` (`watchedDates.js:129`) allows ±3 days of tide drift and matches across the spring/king family. `planFor`/`tideWindow`/`topicWindow` read served card fields only. |
| Further ahead | `FurtherAhead.jsx` lists `useListedFeed(events, graduatedIds)` (:144), so an entry already drawn as a This week lane is **not listed**. `graduatedIds` is reported by the heat strip from the lanes it draws (shell :558, :2677). The rest: a remembered filter, `buildChronology` month groups (plain labels, `role=list` each), `WindowComingUpEntry` with `WatchDateControl` in its open panel, Open all (:312) and `onOpenEntry`. The jump button is at shell :2540-2557. |
| This week bar | Shell :2524-2556: `h2#wf-this-week-h` with "· forecast" (`screenSections.js`), plus the jump (`min-height 32`). "The days ahead" is in `WindowFirstHeatStrip.jsx:2220-2231`: `wf-hstrip-h`, `wf-hstrip-k`, `wf-hstrip-rule`, the `wf-heat-head` testid, and `GuideLink section="fixed"`. Its CSS is at `index.css:1364-1390` and :14591. |
| Best bet | There is no week-level field. Each `BriefingWindow.pick` has `kind BEST|ALSO`, `regionName`, `locationName` and `locationId`, and exactly one BEST is served. `regions[].slots[]` carry `locationId` and `claudeRating` (`BriefingSlot.java:113-121`), and `PlanWindowProjector` names the pick's location from those slots. Client: `card.pick` is null when `picksLocked`, or when an away origin scopes the pick out. Lite: stripped server-side (`BriefingRoleShaper.java:99`). |
| Day columns | **Phone:** a 2-column grid (`.wf-hstrip`, `index.css:3231`); per day a header (spanning), `WindowGlanceLanes date=…`, then the am and pm cards. **Desktop:** a header row, then one shared lanes grid (`--ls/--le/--lr`, `rowOffset` = warning rows + footer, `WindowGlanceLanes.jsx:178-217`), then two `.wf-hrow` grids (sunrise, sunset), each with a sticky rail. Every Lite window has `bestRating == null` (`windowFirstCards.js:912`). |
| Light | **Masthead:** `GET /api/user/settings/light`, today at home only. **Phone:** `↑ ↓` in the compact line (`wf-mast-times`, shell :2343-2363) and a 2 px `MastheadLight` rule (`compact`). **Desktop:** the `MastheadTickLine` times. **Per window, per location:** served on the score rows (`/api/briefing/evaluate/scores`), and `lightWindows(score, type)` (`utils/locationSheet.js:200`) gives ordered `{label, range}`; `MapCallout` and `AskPlanThis` already use it. `detailScoreIndex` is null unless a sheet/Find/favourites is open (shell :1797-1803). |
| Map open/close | `openMap` (shell :882-950) runs `closeShellLayers`, `openMapDoor` (App lens/scope state), `setMapRoute`, `setMapNote` and `setMapView`. `from` is captured, and focus returns on close (:983-997). `MapScreen` is mounted once with the `hidden` attribute and a 0.14 s opacity keyframe (`index.css:14689-14698`). The pane is a lazy chunk. ‹ › stepping never calls `openMap`; a door while open is `inPlace`; the URL boot passes `loadTime`. Back pushes need user activation (`dialogHistory.js:183`). Card thumbnail: `span.wf-hc-cv`; away cards have none. |
| Hint storage | Flags are `app_user` columns, e.g. `postcode_prompt_dismissed_at` (V181) + `PUT …/postcode-prompt-dismissed`, served as a derived `postcodePromptDue`. |

## 1. Cross-phase rules (every phase)

- **Glyphs in names.** Every visible `★`, `→`, `▸`, `▾` is in an `aria-hidden` span. Stars are spoken as words ("4 stars"), as cards already do. The visible words stay a substring of the accessible name (WCAG 2.5.3).
- **Focus never drops to `<body>`.** Whenever an element the reader pressed unmounts or moves, focus goes to a named successor, and a unit test pins it. The undo toast's `returnTo` becomes lazy (a function resolved at Undo time) so it never targets a detached node.
- **The two-question rule.** Nothing on this list ever removes a topic or warning lane: the fold counts them, the watch decorates them.
- **Licensed derivations.** Each new client rule gets its line in `architecture-decisions.md` ("Backend-heavy") in the PR that adds it: the Your places order (P2), the watched lane's plan (P2; it rewords the existing `planFor` line from "chip" to "lane"), and `isQuietDay` (P3).
- **Gates.**
  - The frontend four, the changed-lines check and the E2E smoke. The a11y spec is advisory, but it is never left red by our change.
  - The backend ladder in P1 (Ask schema text) and P3 (D8).
  - The import-graph orphan check and the phase's greps, re-run until clean.
  - At 390 × 844 in the browser, the §4 heights **measured and reported in the PR body** (a gate, not a note), with each acceptance line (spec §10) marked seen or tested.
- **Docs.** `plan-tab.md`, `map-tab.md` (and `find-and-favourites` text where it describes the toggle), the guides and landing with their screenshots, and a `changelog.d/` entry.

## 2. P1 — the bookmark mark (D1), the count-only lens (D7), and the space budget

**PR #1935** (`feature/watched-date-tile`, open) edits the watched chips. P1 starts after it merges, and takes over its strings (its "Tap ☆…" empty line). If it is still open when P1 is ready to branch, P1 waits for it.

**The mark.**
- New `components/shared/BookmarkMark.jsx`: an inline SVG ribbon, `aria-hidden`, `focusable="false"`.
  - Filled form: `fill="currentColor"`. Outline form: `stroke="currentColor" fill="none"`.
  - Colour from `color: var(--wf-yours)`, with `--wf-yours: #C9A0E8` (≈ 8.5:1 on `--color-plex-bg`). On the map's label chips, the builder measures its contrast against the chip surface.
  - Forced colours keep filled and outline distinct.
- `FavouriteMark` renders `BookmarkMark`.
- **Every site in the §0 row** changes. Glyphs move out of strings (`pickerEntries`, the resting group title) into a field the renderer draws.
- The outline form is used on the three Lite teasers (the `FavouriteToggle` locked button, the `RegionsJump` locked row, the Your places teaser) and for "not yet" on the toggle.

**Words.**
- Toggles:
  - A place or an area: **Bookmark** / **Bookmarked**. On a phone the place word stays sr-only, as today.
  - A date: **Watch this date** / **Watching**.
- **`aria-pressed` is removed.** The words carry the state; a changing name plus `aria-pressed` announces it twice. The change is still announced, because the undo toast is a status region.
  - CSS moves to `[data-starred='true']`.
  - The E2E tests (`roles-write.spec.js`, `a11y.spec.js`) and the unit tests in §0 switch to the accessible name.
  - The `FavouriteToggle` docblock and the `plan-tab.md` paragraph ("one constant label with `aria-pressed`") are rewritten.
- **Spoken suffixes.** `, your place` → `, bookmarked` in `MapLabels` (label part), `WindowSpotCard`, `PlanSearch` and the heat strip's best (:1667).
- **Toasts.** "Bookmarked X." / "Removed X from your places." `YourPlacesRow`'s "starred" sentences become "bookmarked". The upsell headings stay; the bodies lose "star".
- **Ask.** `AskToolSchemas.java`: "bookmarked places". No prompt-regression assertion changes.
- Ratings keep `★`.

**The lens (D7 and spec §4, phone).**
- **The readout is the count only, at every width.** `formatLensCount` → `N spot(s)`, or `N of M spots` when the rating floor removes some.
  - `within reach` and `across W windows` go.
  - `formatLensReadout` and its tier/default/rating branches are deleted with their tests, and the doc references to the readout are updated (`MapView.jsx:8398`, `planConflicts.js:44`).
  - The default words survive only as the selected drive button's `title`, if a test shows they are still read anywhere; otherwise they are deleted.
- **Phone layout to ≈ 90 px:**
  - chip height 30 px (above the 24 px WCAG 2.5.8 floor; the 40 px was a thumb choice the spec's 80 px band overrides);
  - padding 5 px; a 4 px row gap;
  - the count on a 14 px line;
  - "today only", "Back to X", the Pro pill and the GuideLink folded into the existing rows rather than adding one.
  - The plan-conflict verdict still replaces the count and may wrap (it is a warning, and the one case allowed to exceed the band). That is recorded in §9.
- **Phone screen padding.**
  - `main`'s 24 px top and `.wf-body`'s 13 px are trimmed to about 8 px on the screen.
  - The first-paint literals `--wf-lens-reserve` and `--wf-bar-h` are updated to the new heights.

**Deletions.** Every ★/☆-as-yours literal and its assertions; `formatLensReadout`; `aria-pressed` styling; `wf-yp-star` once unused.

**Grep gate.** `★ Your places|★ My places|☆|starred|your place` over `frontend/src` (non-test) and `landing/` returns only rating uses.

**Guides.**
- `planning.html`, `your-places.html`, `map.html`, `finding-places.html`, `getting-started.html`: the mark and its words, with screenshots.
- The landing pricing list and FAQ: "Bookmark places and watch dates".

## 3. P2 — places and watched dates separate (D2)

**Your places = places and areas.**
- **Order:**
  - the place's served best rating over the forecast days (the figure the chip prints), highest first;
  - ties by drive minutes, then name; areas tie by name;
  - quiet rows (≤ 2★, not rated yet) and gated rows (outside your picks) last, muted (`wf-yp-quiet`).
- **No late reorder.** The row sorts once its figures are in. Until `useAreaOutlook` resolves, areas hold their insertion place and are not re-sorted under the reader's thumb.
- The docblock rule "never placed by its rating" is rewritten.
- **One line per chip, ≈ 34 px:** `[mark] Crimdon Beach · Fri sunrise 4★`.
- **Deleted:**
  - `buildWatchedChips`;
  - the date rows, the date and `graduatedIds`/`firstWindow` branches (with the shell's prop);
  - the inert rows, the stop-watching ✕ and `wf-yp-item-date`;
  - the off-plan date sentence;
  - dates in `nothingStarred`;
  - each of these with its CSS and tests.
- `YOUR_PLACES_EMPTY` drops "or watch a date…" (P3 replaces the empty line with the hint).

**One home per watched date.** A watched date is in exactly one place:
- **Its day's lane** in This week, if its entry is inside the forecast (graduated).
- Otherwise **Watching**, if its entry is listed.
- Otherwise, **no longer listed**: the key matches no entry in the *served* `events.entries`, which is a different set from the *listed* one.

**The lane.**
- `buildWatchedLanes(dates, entries, cards, hotTopics, today)` replaces `buildWatchedChips`, keeping `planFor`/`tideWindow`/`topicWindow`.
- For each watched entry whose plan falls on a forecast day:
  - If a **topic lane** that day already carries that entry's jump id, that lane is **decorated**: it gains the mark and the plan clause (`sunrise 07:54 · best Crimdon Beach 4★ · tide on the light →`). It is never removed (two-question rule).
  - Otherwise a **watched lane** is added after the warnings and before the topics: `[mark] King tide · 4.6 m` / the plan clause.
- Either way the entry id is reported in `graduatedIds`, so Further ahead and Watching do not list it again.
- A watched date whose plan finds no window draws nothing extra. Its entry is not graduated by the watch, so it stays in Watching.
- Press:
  - `openMap({window, focus:{kind:'location', id: bestReach.locationId}, from})`;
  - a `static` plan (meteor) reads `clear-sky check on the night` and opens the entry.
- **Desktop:** `WindowGlanceLanes` gets a `watchedLanes` prop with its own `startCol/endCol/row` packing, offset after the warnings and before the topics. A watched lane spans its own date column and wraps to two lines inside it.

**Watching in Further ahead.**
- A group at the top of Further ahead, before the filter chips:
  - headed by an `h3`, `[mark] Watching`;
  - its own `role=list` of the **same** `WindowComingUpEntry` cards, with the same open panel and `WatchDateControl`;
  - drawn only when it has a member.
- Members are the listed entries with `watchKeysMatching(dates, entry)` non-empty. `buildChronology` leaves them out, so each entry appears once.
- The filter chips do not apply to Watching.
- Open all and `onOpenEntry` include Watching's entries.
- An unlisted watch is a quiet row (`[mark] <title> · no longer listed`) with a real **Stop watching** button.
- **Focus on a move:**
  - Pressing Watch in a month list moves the card into Watching, and focus goes to that card's toggle (found by entry id, scrolled into view).
  - Unwatching from Watching moves it back to its month, and focus follows it there.
  - Removing an unlisted row focuses the next row, or the Watching heading.
  - Undo resolves its target the same way.
- **Words:**
  - Toasts: `Watching <name>. It's at the top of Further ahead.` / `Stopped watching <name>.`
  - `WATCH_UPSELL_BODY` is rewritten (a watch no longer "joins your places").

**The jump button.** `Further ahead ↓` gains `[mark] N` beside the NEW badge, and its accessible name gains ", N watching". This shows only when `favourites.status === 'ready'` and N > 0, so Lite and the error state never show "0". N counts the watched dates that are not yet past.

**E2E.** `roles-write.spec.js`'s "the watch joins Your places as a chip" becomes "the watch moves into Watching" (and back on unwatch).

**Guides.**
- `your-places.html`: places only; watched dates live in Further ahead and move into This week.
- `planning.html`: Watching, the watched lane.

## 4. P3 — the best in the heading (D3), quiet days (D5), the hint (D8)

**D3.**
- **The bar:** `h2#wf-this-week-h` "This week" (still the focus fallback), the `GuideLink section="fixed"` (moved here), and a sibling button.
- **The button** reads `best Fri sunrise, Crimdon Beach 4★ →`. Its name is spoken in words: "Best this week: Friday sunrise, Crimdon Beach, 4 stars. Open on the map."
- **The window** is the card whose served `pick.kind` is `best`, within the four days.
- **The stars** are the `claudeRating` of the served slot in `pick.regionName` whose `locationId` is `pick.locationId` (falling back to `locationName`).
  - This is the slot the projector named the pick from. It can be below the card's `bestRating`, because the pick is the best place in the top-ranked region, not the best spot overall; §9 records this.
  - `detailScoreIndex` is never read.
  - With no slot rating, no stars are shown. With no `locationName`, the button reads `best Fri sunrise, <regionName>`.
- **No BEST pick** (Lite, away-scoped, none served): no button. Lite's Best-bets upsell door stays in the strip foot.
- **Press:** `openMap({window, focus:{kind:'location', id}, from, frame:'home-and-place'})`.
- **Phone:** the bar is ≈ 28-30 px. The jump's `min-height` drops to 28. The button truncates with an ellipsis, as the spec's own prototype does, and its accessible name carries the full text.
- **Deleted:**
  - `THIS_WEEK_SUBHEADING`;
  - the "The days ahead" `h2`, `wf-hstrip-h`, `wf-hstrip-k`, `wf-hstrip-rule`, the `wf-heat-head` testid, and `index.css:14591`;
  - their tests (`WindowFirstHeatStrip`, `WindowFirstHeatStripBestBetsDoor`, `glanceLanesCss`, `WindowGlanceLanes`, `screenSections`).
  - `.wf-hstrip-key` stays.

**D5.**
- **`utils/quietDays.js` `isQuietDay(day, lanes)`** is true when **every** window the day shows (today may have only a sunset left) carries a served `card.bestRating`, the same figure the card prints, that is non-null and ≤ 2; and the day has **no** warning lane and **no** watched or decorated lane.
- A null rating never folds. So a Lite reader, whose windows all serve `bestRating: null`, never sees a fold; §9 records it.
- **One persistent button** per quiet day, the same element in both states:
  - folded: `Wed 25 · nothing above 2★ ▸`, plus `· 1 topic` when topic lanes sit on that day; `aria-expanded="false"`;
  - open: `Wed 25 · nothing above 2★ ▾`; `aria-expanded="true"`.
  - `aria-controls` names the day body, which is always mounted and `hidden` while folded. Focus stays on the button.
- Opening lasts for the session (shell state keyed by date).
- **Phone:** the fold replaces the day header, and the body holds the lanes and cards.
- **Desktop:** the column's header stays. The fold button sits in the sunrise row's cell, and an inert filler takes the sunset row's cell. The topic lanes stay in the shared lanes grid (nothing hidden), so the desktop fold shows no topic count.

**D8.**
- **Backend:**
  - `V<next>__app_user_places_hint_dismissed.sql` adds a nullable `places_hint_dismissed_at TIMESTAMPTZ`.
  - Two writers, both scoped repository updates:
    - `PUT /api/user/settings/places-hint-dismissed`, open to any authenticated role, so Lite gets 200;
    - the first favourite write of any kind, which sets it if null. So "until the first save" is permanent, and removing every bookmark does not bring the hint back.
  - `UserSettingsResponse.placesHintDue` = the column is null.
  - Tests:
    - a Testcontainers migration test;
    - controller tests for Lite, Pro and Admin, and a 401 when not signed in;
    - a service test for the stamp on the first save.
  - Add the path to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`.
  - Update `api-endpoints.md` and `database-migrations.md`.
- **Client:** the hint shows only when `placesHintDue` and either:
  - Pro/Admin with `favourites.status === 'ready'` and nothing saved, or
  - Lite (`locked`).
- It reads `[mark] Bookmark places and watch dates to build your plan.`, with ✕ ("Dismiss").
- ✕ writes the flag, hides the hint at once, and focuses `#wf-this-week-h`.
- **Lite** sees a `PRO` pill that opens an upsell beneath it, with one combined body covering places and dates.
- **The Lite `your-places-teaser` is deleted.** The hint replaces it, and after dismissal Lite meets the upsell on the locked toggles only.

**Guides.** `planning.html`: the best in the heading, quiet days, the hint.

## 5. P4 — the light in the map header (D4), the collapsing lens (D6)

**Map header.**
- `publishHeader` gains `light: [{label, range}]`. It is built with `lightWindows(lookupForWindow(scoreIndex, …), eventType)` from MapView's own `scoreIndex`:
  - for the selected place, if there is one;
  - otherwise for the header card's `bestReach` place;
  - and omitted when neither has a score row.
- It is rendered in `map-screen-facts`: `best 4★ within 45 min · blue 07:11–07:53 · golden 07:54–08:36`, with golden in the golden colour.
- Night windows get no light clause.
- The masthead's endpoint serves today at home only, so it cannot be the source; §9 records the swap.

**Masthead.**
- **Phone:** the `↑ ↓` times (`wf-mast-times`, `masthead-phone-*` testids, the ⌂ `wf-mast-times-home`) and their CSS are deleted, along with any E2E that reads them (`smoke/phone.spec.js`). The 2 px `MastheadLight` rule stays: it is a colour cue, not a time (§9).
- **Desktop and tablet:** today's times stay, followed by a visible `today` label.

**D6 (phone only).**
- **Trigger:** a sentinel at the end of `#wf-this-week`, reusing `useStuckSentinel`. When it has scrolled above the viewport, the lens is **collapsed**.
- **Collapsed:** one button, `Drive 45 min · any rating · 6 spots ▾`, with `aria-expanded="false"` and `aria-controls` naming the lens body. The body stays mounted and `hidden` while collapsed.
- **Opening:** tapping shows the full lens in place, and it stays open until the reader scrolls back to the top, as the spec says (the lens un-sticks). That resets it.
- **Focus:**
  - The lens never collapses while focus is within it (`:focus-within`).
  - A reset never moves focus.
- **No jump.**
  - The last full height is cached before collapsing, and re-measured on resize and rotation while it is full.
  - A spacer right after the lens takes `full − collapsed` while collapsed, so the document height never changes.
  - `--wf-bar-h` and `--wf-lens-h` publish the collapsed height, so the This week and Further ahead bars stick right under it. The spacer is excluded from measurement.
- No animation.
- **Desktop and tablet are unchanged**, tested at ≥ 640 px.
- WCAG 2.4.11: when the lens is re-expanded over content, tabbing through cards scrolls each one clear of it. `scroll-margin-top` reads `--wf-lens-h`.

**Guides.**
- `map.html`: the header's blue and golden hour.
- `planning.html`: the collapsing lens, and the masthead light on desktop only.

## 6. P5 — the motion (D4)

**When it runs.** `utils/mapMotion.js` provides `openWithMotion` and `closeWithMotion`. Motion runs only when **all** of these hold, and otherwise the update runs plainly:
- `document.startViewTransition` exists;
- the source is a connected card thumbnail (`.wf-hc-cv`);
- the view is not already open (no `inPlace`);
- it is not a `loadTime` open;
- it is not stepping.

**Open:**
1. Mark the thumbnail with `wf-vt-card` (`view-transition-name: wf-map-morph`).
2. `startViewTransition(() => flushSync(() => openMapBody()))`. The **whole** `openMap` body runs inside the callback: `closeShellLayers`, the door, the route, the note and `setMapView`. `inPlace` is computed there too, so the old snapshot is the untouched screen and a double press yields one door.
3. In that commit, `MapScreen` takes `wf-vt-frame` (the name moves to the frame) and `wf-map-arriving` **on its root**. The CSS is `.wf-map-screen.wf-map-arriving .wf-map-tab{visibility:hidden}`.
   - It is `visibility`, never `display`, so Leaflet measures a real box and the hidden-mount guards are unchanged.
   - It sits on the root because the lazy pane may not exist yet.
4. The morph runs 230 ms, ease-out.
5. `::view-transition-old(root)`/`new(root)` get `animation: none`, so nothing else on the page moves.
6. Cleanup removes the names and `wf-map-arriving`. It runs in `.finally()` of `finished`, with a 600 ms timeout fallback, so the map can never stay invisible after a skipped or failed transition.
7. The pane then fades in with a 120 ms CSS opacity transition. The live map is never in either snapshot.

**Close:** only ✕ and Escape animate. Back/popstate and every other close are instant.
1. Add `wf-map-arriving` (the pane goes `visibility:hidden`).
2. Wait one `requestAnimationFrame`, so the old snapshot holds the frame without the live tiles.
3. Put the morph name on the frame, then run `startViewTransition(() => flushSync(closeMap))` with the name on the card that opened it, if that card is still connected and visible. Otherwise close plainly.
4. The morph runs 180 ms.
5. Focus returns to the card through the existing return path.

**Reduced motion:** the morph's group/old/new animations become a 110 ms opacity fade.

**Deleted:** `MapScreen`'s `wf-map-screen-in` keyframe and its reduced-motion block.

**Focus while arriving.** Focus goes to the title, which is outside the pane. A `location` door that focuses inside the callout is checked: if focus lands while the pane is hidden, it is re-applied after cleanup.

**Tests:**
- **Unit**, with a stubbed `startViewTransition`, cases:
  - present and absent;
  - reduced motion;
  - stepping, `loadTime` and `inPlace`;
  - a disconnected source;
  - a skipped transition (cleanup still runs);
  - a callback run asynchronously, where `dialogHistory` still pushes under activation.
- **E2E** in Chromium: open from a card, then close with ✕. Assert the map is visible and sized, and focus is on the card. `map-hidden-mount.spec.js` stays green.

**Guides.** `planning.html` gets one sentence on the motion.

## 7. Space budget (phone, 390 × 844), the target per phase

| Band | Today | After |
|---|---|---|
| `main` top + `.wf-body` top | 37 | ≈ 8 (P1) |
| Masthead | 44 | 44 |
| Lens | 130 | ≈ 90 (P1) |
| This week bar | 42 | ≈ 30 (P3) |
| Your places | 52 + 10 gap | ≈ 34 + 6 (P2) |
| The days ahead | ≈ 22 + 10 | 0 (P3) |
| **First day top** | **≈ 347** | **≈ 212** (P3) |

**Known displacing bands**, measured but not budgeted: the safety notice (`wf-plan-safety`) and the aurora and warning banners.

## 8. What goes, per phase

- **P1:**
  - every ★/☆/"starred"/"your place" meaning yours, with their assertions;
  - `aria-pressed` on the toggle and its CSS;
  - `formatLensReadout` and its branches;
  - the phone lens's 40 px chips and extra rows;
  - `main`/`.wf-body` top padding on the screen.
- **P2:**
  - `buildWatchedChips`, and every date branch of `YourPlacesRow` (`graduatedIds`, `firstWindow`, the ✕, the inert rows), with its CSS, tests and the shell prop;
  - the Your places half of `roles-write.spec.js`'s watch test.
- **P3:**
  - `THIS_WEEK_SUBHEADING`;
  - "The days ahead" (`wf-hstrip-h/-k/-rule`, `wf-heat-head`, `index.css:14591`);
  - the Lite `your-places-teaser`;
  - `YOUR_PLACES_EMPTY`.
- **P4:**
  - the phone masthead times (`wf-mast-times*`, `masthead-phone-*`) and the E2E that reads them.
- **P5:**
  - the `wf-map-screen-in` keyframe.
- **After each:** the orphan check and the greps, until clean.

## 9. Choices this plan makes where the spec is open

1. **`aria-pressed` leaves the toggle; the words carry the state.** A focused button's changed name is not reliably re-announced, and the undo toast (a status region) is what announces it.
2. **Watching ignores the filter chip.** An unlisted watch is a quiet row with Stop watching.
3. **A watched date decorates the topic lane for its entry rather than replacing it.** It adds a lane only when no topic lane carries it.
4. **The heading's stars are the pick location's served slot rating.** They can be below the card's best; no stars when there is no slot rating.
5. **Quiet days fold on desktop too, in the column** (spec screenshot 07). The topic lanes stay drawn there.
6. **Lite never folds.** Lite windows serve no rating, and an unscored window never counts as quiet.
7. **The hint flag is a backend column, stamped by the ✕ or by the first save.** The Lite teaser is deleted.
8. **The map header's light comes from the served score rows (`lightWindows`), as ranges.** The masthead's endpoint covers today only. The spec's `blue 07:11` shows one time; the range is the same served fact, written whole.
9. **Motion only from a card thumbnail, and on close only for ✕ and Escape.** Lanes, chips, Find, links and Back are instant.
10. **The lens chips are 30 px on a phone (from 40) to meet the spec's band.** The plan-conflict verdict may wrap past it.
11. **The phone keeps the 2 px `MastheadLight` colour rule.** It shows no times.
12. **D3's button truncates on a phone**, as the spec's prototype does, with the full text in its accessible name.

## 10. Review findings → where answered

| Finding | Answer |
|---|---|
| Space budget unreachable (first day ≈ 347 px; lens can't shrink by readout alone) | §2 lens layout and padding, §7 table, measured gate in §1 |
| Topic lane suppression silences "what is happening" | §3 decorate, never remove; §9.3 |
| Watching vs `graduatedIds` (double show / false "no longer listed") | §3 "one home"; served vs listed |
| Focus lost when a card moves; stale toast `returnTo` | §1 focus rule; §3 focus on a move |
| P5 close snapshots the live map; root cross-fade | §6 close order (hide, rAF, then transition), root `animation:none` |
| `wf-map-arriving` sticking / no node on a lazy first open | §6 class on the `MapScreen` root, `.finally()` + timeout |
| Only the un-hide inside the callback | §6 whole `openMap` body in `flushSync`, `inPlace` computed there |
| `aria-pressed` removal breaks CSS, E2E, unit tests, docs | §2 list |
| Missing ★/"starred"/"your place" sites; `WATCH_UPSELL_BODY` false | §0, §2, §3 |
| Mark in forced colours; non-existent light theme | §2 `currentColor` |
| Fold/lens disclosure: swapped button, missing `aria-controls` target | §4 one persistent button, body `hidden`; §5 lens body `hidden` |
| Lens collapsing under focus | §5 `:focus-within` guard |
| Glyphs read aloud | §1 |
| D8 shows while loading/error; returns after removing all; Lite exit; upsell body | §4 D8 |
| Lite never folds; today with sunrise passed; which rating | §4 D5, §9.6 |
| Heading stars source; `detailScoreIndex`; phone fit | §4 D3, §9.4, §9.12 |
| Your places late reorder; area tie-break; gated rows | §3 |
| Desktop watched lane needs an API; two-line lane | §3 `watchedLanes` prop |
| Desktop fold across two row grids | §4 D5 desktop |
| D6 measurement, `--wf-bar-h`, reset rule | §5 |
| `MastheadLight` on phone; `wf-mast-times-home` | §5, §9.11 |
| PR #1935 open, contradictory handling | §2 opening line |
| P3 deletion list incomplete | §4 D3 deleted |
| Jump count for Lite/error | §3 |
| Watching list markup, Open all, `onOpenEntry` | §3 |
| D8 endpoint roles, tests | §4 D8 |
| Licensed-list wording | §1 |
| Activation under an async callback; callout focus; which closes animate | §6 tests, focus while arriving, close |
| Displacing bands | §7 |
