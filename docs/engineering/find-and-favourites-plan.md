# Find everywhere, areas in Find, and your places (favourites) — plan

Status: **plan, adversarially reviewed once (four lenses, 2026-11-18); awaiting owner answers in §6.** Source: the
owner's design handoff `design_handoff_find_and_favourites` (README + prototype `Find and Favourites.html`; a design
reference, not code to port). Phases F1, F2, F3, F4, F4b, F5, **one PR each**, each branched from the updated `main`.

Not touched: ratings, verdicts, Best-bet logic, `LitePickShaper`'s rules.

## 0. What the code says today (confirmed 2026-11-18)

| Question | Answer |
|---|---|
| Latest migration | `V225__weather_warning_real_feed_shape.sql`. Favourites take **V226**; re-read the tree at build time. |
| Search today | `components/PlanSearch.jsx` (a `Modal`, `closeOnEscape={!stacked}`), `utils/planSearch.js`: **plain substring filter per group in catalogue order, cap 8 (`MAX_RESULTS_PER_GROUP`), no scoring, no aliases** (aliases are deliberately absent from the client: a roster fact). Find's state is shell state: `searchSeed` in `WindowFirstShell.jsx`, with its Back layer already (`useBackLayer`). The resting list is windows only, by decision. |
| The Find door | `MastheadTickLine.jsx:274` `searchable = typeof onOpenSearch === 'function' && !isMapTab`; the shell withholds the handler off Plan (`WindowFirstShell.jsx` ~2605). Both gates and the comment at `MastheadTickLine` ~94-102 must change. |
| `/` key | Ask's key, registered only when `askDockEntry` exists (≥1024 px), refusing on `askKeyRefused` (= `askBlocked || askLocked`, includes Lite, backend down, `askDialogOpen`). **⌘K must not copy `askKeyRefused`** (see F1). |
| Pick handlers | `onPickLocation` = `openWindow(null); openOverPopup({spot})`, `onPickWindow`, `onPickRegion` (calls `setOrigin`!) in the shell; none calls `selectTab`. `selectTab` clears `openWindowKey` and `sheetSpot` and closes Find. |
| Map plumbing | No region filter and no "All". Filters: min-stars (persisted `mapFilterMinStars`), drive tier, tide, location type, scope (`scopeRegions`, `onResetScope`), and the named-spot-set lens `focusLensNames`. Opening a callout after a pick has a precedent: the `askWindow` handoff (nonce + `callout` flag, `MapView.jsx` ~4120-4146). `FlyToController` flies only when the pane is shown. |
| Areas for readers | `AreaController` is `/api/areas`, **every method** `hasRole('ADMIN')`. Named-area members: `area_location` (`@ElementCollection` on `AreaEntity`); sub-region members: `location_sub_region` via `SubRegionMembershipService.membersByArea()`; `RepositoryAskAreas.load()` already maps both into `AskSnapshot.Area(name, aliases, locationIds)`. |
| Ask ranking | `AskTools.RANK` static comparator (rating ↓, `pickOrder`, tide-aligned, name, date, type, id); users: `AskTools` ~1134 and `StubAskEngine` ~198. `rank_night_spots` uses `nightOrder`, not `RANK`. `personal` is a sticky OR (nearHome, drive times). Answer cache `CaffeineAskAnswerCache`: key `(scope, date, generatedAt, question, windowId, userId)`; `lookup` tries the shared key first. Prompt: `systemPromptBlocks` puts `RULES` + `WITH_USER`/`USER_LESS` before `staticLength` (the cache breakpoint). `nearHome` is gated into the `withUser` tool-schema branch. **No `nearHome` thread state exists.** |
| Deploys | App: `deploy.yml` on `push: tags: v*` only (merging deploys nothing). Landing and guide: a separate `release-landing.sh` from `main`. The in-app guide iframe loads from the landing origin, so new guide links 404 until the landing is deployed. |
| Toast | **No toast component exists.** Only a scored-legend chip. |

## 1. Decisions

**D1. Reader areas list: `GET /api/areas/directory`, in a new controller** (`AreaDirectoryController`; the admin
`AreaController` is left exactly as is). Any signed-in role, Lite included (Find is not Pro). Body: `[{id, name, kind,
aliases[], regionId, regionName, locationIds[]}]`, **enabled locations only** (matches `/api/locations`), role-free,
ETag-shared: add it to `REVALIDATABLE_READ_PATHS` with a positive `shouldNotFilter(...).isFalse()` case like
`regionWhitelistIsTheMatrixAlone`. Build it from flat projection queries (or `@EntityGraph`/`@BatchSize`) reusing the
`RepositoryAskAreas` member/alias mapping, with a statement-count assertion (no N+1 on collections). Check the Spring
Security chain for any `/api/areas/**` rule. Lite sees all sub-region membership: that is roster data Lite already
sees through `/api/locations` (`subRegionId`), so allowed. **Owner to confirm (Q4).**

**D2. Favourites are served already merged.** `GET /api/user/settings/favourites` returns `{places: [{locationId,
createdAt}], areas: [{areaId, createdAt}], locationIds: [...]}`; `locationIds` is stars plus members of starred areas,
de-duplicated, enabled only, expanded by the backend. The client never walks area membership. This payload is personal
and stays **off** every shared path.

**D3. The area "best" is served by the backend, not derived on the client.** *(Reverses the first draft; the review
showed a client max-of-served breaks the licensed list, bypasses `neighbourOutlier`, is partial on far days where
only a few members are rated, and for Lite drops the `gated` marker so a 3-member max reads as the area's best.)*
`GET /api/areas/directory` stays role-free; a separate per-reader `GET /api/areas/outlook?ids=…` (or the area sheet's
own call) returns per area, per window the strip shows: `{ratedMembers, totalMembers, gatedMembers, topRating,
topLocationId}`, built from the same served-score path as `/api/briefing/evaluate/scores` (Lite-shaped by
`LitePickShaper`: gated members counted, never rated). It is the reader's own shaped view, so it is **not ETag-shared**
(add to `personalDataPathsAreNeverFiltered`). The UI prints the figure with its window and `· N rated`, never "best of
the area" as a verdict, and says how many members are gated for Lite. Windows beyond the settled horizon show no
figure. An area never carries a verdict (sub-regions plan §1); the sheet lists its places' own ratings.

**D4. Favourites only break ties, and only where a tie exists.** Never reorder a list that has no rating key.
- **Ask `RANK`:** `favourite` after `tideAligned`, before `name`. A starred non-Best never beats a Best (`pickOrder`
  is above it); a starred 3★ never beats an unstarred 4★.
- **Find:** the favourite bonus (+6) applies only to rows that already matched, and only to order **within** a group
  before the cap of 8 is applied; windows (date order) never take a favourite key.
- **Sections, not ranked lists:** the Coming up watchlist, `★ My places` and starred-area entries in region pickers,
  and Find's resting `★ Your places` group are **separate sections**, labelled as the reader's own, not rank
  positions. Say so in the docs. Map markers only get a ★ mark; no z-order change.
- One shared client helper `utils/favourites.js`; a test per list that a starred 3★ never ranks above an unstarred
  4★.

**D4b. Ask answers a favourite and the favourite-blind answer as a labelled pair (owner, 2026-11-18).** The owner's
point: a favourite that wins a tie hides the equally good place the reader would otherwise have been told about
("Where is best tomorrow?" → Mary's Lighthouse, a favourite, but Bamburgh also scores 4★). So for a "best" question
Ask returns up to two slots, never a silent substitution:
- `lead` = the winner of the comparator **with** the favourite tie-break (D4 unchanged: a favourite never beats a
  higher rating, pick or tide fit). `companion` = the **favourite-blind** winner (today's order, alphabetical last
  tie-break) when it differs from the lead; else, when the best-ranked favourite in scope is not the lead (it scores
  lower), that favourite. At most one companion; none when lead is both the blind winner and the best favourite.
- Wording is fixed by the prompt, never "wildcard": the lead reads as the answer, a tied companion as "also scores
  the same" and a lower-rated favourite as "your <name> is <n>★". A favourite is never labelled best when it is not.
- Examples: Mary's (favourite, 4★) and Bamburgh (4★) tie → lead Mary's, companion Bamburgh. Bamburgh 5★, favourite
  Mary's 4★ → lead Bamburgh, companion Mary's. Favourite is the clear best → one answer, no companion.

**D5. Cap 25 per user (owner, 2026-11-18)**, enforced in the service under a per-user lock (lock the `app_user` row `FOR UPDATE`, or a
count-then-insert guard with a unique-index fallback): 409 `You can keep up to 25 places.` Concurrency test.

**D6. Where a Find pick lands. DECIDED by the owner 2026-11-18: Plan tab (the recommended option below).** The handoff has a place pick on Coming up open
the sheet *on Coming up*. The code says that is the exact state `selectTab` exists to prevent (`plan-tab.md` ~108-118:
"no other shell layer can stand over the map" and the sheet being "about the Plan tab"). **Recommended:** a place or
window pick from Coming up does `selectTab('plan')` and then opens the sheet in the same batch (as `askOpenInPlan`
does), so no invariant is rewritten. **Alternative:** follow the handoff literally and rewrite those invariants with a
browser-verified test. Map picks always stay on Map (fly + callout).

## 2. Phases

### F1 — Find everywhere (frontend only)
- **Door.** Lift both gates (`!isMapTab` in `MastheadTickLine`, the Plan-only handler in the shell). Labelled `⌕ Find`
  (phone) / `⌕ Find a place, region or day  ⌘K` (desktop) on every tab; origin chip still opens Find and keeps its
  away/home role and go-home button. Update the `aria-label`s ("Search days, regions and places" and the Modal label)
  to include areas once F2 lands.
- **⌘K / Ctrl K.** Not a copy of `/`. Guard = the masthead button's guard (`!stackedOverPopup && !guideModal`) +
  `foreignDialogOpen(shellRef.current)` (`utils/shellForeignDialog.js`: any `[role="dialog"]` outside the shell root,
  not only `aria-modal`) + not inside input/textarea/select/contenteditable. **No `askKeyRefused`**: that would kill
  ⌘K for Lite, when Ask is down, under 1024 px and over the window popup. Listener mounted on every width and role.
  A second ⌘K while Find is open closes it, handled before the refusals. `preventDefault` only when it acts.
  Find is refused (button and key) while the next-days sheet, pick dialog or guide modal is up (`MAX_LAYER_DEPTH = 2`).
- **Stacking and Back.** Reuse the existing `searchSeed` state and `useBackLayer`; **no `FindContext`** is needed.
  Find is a `Modal` in the shell root, so `foreignModalOver` already stands the seven Map Escape rules and
  `useOutsideDismiss` down: add a test that one Escape closes only Find over a selected callout. The callout, phone
  peek sheet, open Ask section and the Filters/Regions popovers are not dialogs, so Find **closes the map menus
  (`openMapMenu`) when it opens**. A pick closes Find and acts in one batch (never a pop plus a push). `selectTab`
  closes Find (keep). Rewind remounts the app, so no Find state survives (a line in the docs).
- **Picks (inline table; the handoff's is not in the repo).**

| Pick | At a Glance | Coming up | Map |
|---|---|---|---|
| Place | next-days sheet | per D6 | fly + callout; **relax only the filters hiding it** and say so (min-stars, drive tier, tide, type, scope; a thin-sample place needs min-stars relaxed; never silently overwrite the saved min-stars); keep the selected window |
| Window | collapse to and flash it | `selectTab('plan')` then flash | select that window on the map (a window outside the control's range is not offered) |
| Region | plan from it (`setOrigin`, as today) | as Glance | **a pan/frame only; no `setOrigin`** (origin moves reach rings and drive times) |
| Area (F2) | area sheet | per D6 | fly + `focusLensNames` lens |

- **Map channel.** A nonce handoff modelled on `askWindow` (`callout` flag, deferred until the pane is shown;
  `selectTab('map')` first if invoked from another tab is *not* used, picks are per-tab). Define the door payload for a
  location. No collision with `peek:ask` / `openMapMenu` on the phone.
- **Anchor.** `PlanSearch` anchors to `.wf-tick`; confirm it exists on Coming up and Map or add the fallback test. The
  `scoreIndex` build is gated on `searchSeed`: confirm its inputs exist when the shell sits on Map or Coming up.
- Docs: `docs/engineering/reference/plan-tab.md` (retire "Search is the Plan tab's alone" with the reason; Back
  section) and `map-tab.md`.
- Tests: ⌘K matrix (Lite, narrow viewport, Ask down, window popup open, Find open, input focus, foreign dialog); each
  tab × pick kind; Back; Escape; filter relaxation; focus return (to the Find button on close, to the opened sheet's
  own origin on a pick).

### F2 — Areas in Find (backend + frontend)
- **Backend:** D1 controller and cache entry; D3 outlook endpoint; controller tests for every role; statement-count
  test.
- **Frontend:** `api/areasApi.js`; **F2 introduces ranking to `planSearch.js`** (it has none today): exact pass first;
  a fuzzy pass only on rows the exact pass rejected; fuzzy rows appended after exact rows; the cap of 8 applied
  **after** the merge so neither drops the other; `matchRange` null for fuzzy rows. One slip (swap, missing, extra,
  wrong letter) in any query word of **5+ letters**; shorter words exact (`st`, `bay`). Aliases from the directory.
  Group order typing: Windows · Regions · Areas · Places. Areas row: `▦`, name, `Sub-region · 16 spots · N rated`,
  D3 figure with its window, chip `Its spots` / `Show`.
- **Area sheet** (a Modal like the place sheet, depth 2 with Find closed on pick): name, kind, spot count and drive
  range, spots with best window each (sorted by rating, then drive), `Show on map →`. Both a sub-region and a named
  area shown when both match. Never a verdict; for Lite, gated members are counted and unrated-versus-gated is
  shown honestly. Focus returns to the origin.
- **Map area pick:** a `focusLensNames` set from the member names (not a new filter value), a visible
  `Showing Hadrian's Wall · clear` chip, defined effect on heat, hatch, chips pass and counts. **No cluster or area
  bubble** (no-clustering rule).
- Tests: `hadrain` → Hadrian's Wall and Hadrian's Wall & Tynedale above places; `roman wall` by alias; `caw` still
  lists Cawfields; typo never displaces an exact match inside the cap; combobox/listbox a11y (no interactive
  children inside option rows).
- **Built (2026-11-18).** `AreaDirectoryController` (`/api/areas/directory`, ETag-shared; `/api/areas/outlook`, personal),
  `AreaDirectoryService.directoryFor(reader)` (the one seam for a later Lite tightening; the reader is ignored in v1),
  `AreaOutlookService`; the client side is `useAreaFind`, `areaFind.js`, `AreaSheet.jsx` and the Map's `findPick`
  `kind: 'area'`. Two readings of the plan worth recording: "no figure beyond the settled horizon" is built as "a window
  is reported only when at least one member is rated or gated" over today to `FORECAST_HORIZON_DAYS`, so a day that has
  not been scored carries no figure without the backend having to name a separate settled date; and an area with no
  ENABLED member is left out of the directory (it has nothing to open). The area sheet's `Show on map →` is the existing
  map door with `focusNames`, not a new channel; only the Map-tab pick uses the Find channel.

### F3 — Favourites backend
- `V226__user_favourite.sql`: `(id, user_id, location_id NULL, area_id NULL, created_at)`, CHECK exactly one target,
  **partial unique indexes** per target, `ON DELETE CASCADE` on all three FKs, index on `user_id`. Testcontainers
  migration test (CI only). A row in `database-migrations.md` with its "proven by" test.
- **Interaction with `app_user_sub_region` (V192):** independent. A Lite sub-region pick and a favourite on the same
  area coexist, and neither changes the other or the pick limit. State it in the docs.
- Endpoints under `/api/user/settings/favourites`: `GET` (D2), `PUT` whole list, `POST` one, `DELETE` one;
  `@PreAuthorize` Pro or Admin, Lite 403; idempotent POST/DELETE; unknown ids 404; a disabled location may be
  starred (hidden from `locationIds` until enabled). `UserFavouriteEntity` independent of `AppUserEntity`;
  dedicated repository (column-scoped writes).
- Add the path to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`; test that no briefing, almanac,
  ask-ready or areas-directory body contains a favourite.
- Docs: `api-endpoints.md`, `database-migrations.md`, `roles-and-gating.md`.

### F4 — Favourites UI
- `FavouritesContext` (fetch only for Pro/Admin; Lite never calls the endpoint) + `utils/favourites.js`.
- **☆/★ toggle:** constant label ("Your place") with `aria-pressed` (not a swapped label *and* a pressed state);
  place sheet, area sheet, map callout header. Phone: glyph alone, 30 px target in a 44 px row. **Never inside a
  Find option row**; toggling happens in sheets and the callout only.
- **Undo:** a **new `useUndoToast` primitive** (no toast exists): `role="status"` text with the Undo button outside
  the live region, pauses on hover and focus, no auto-dismiss under 10 s (WCAG 2.2.1), placed against `Modal` and the
  phone peek sheet (hidden by a dialog is acceptable only because pressing the toggle again undoes). Optimistic with
  revert and a message on failure. **Owner may prefer dropping the toast (Q3).**
- ★ marks: map markers, popup spot cards, Glance best-spot line (starred places only).
- Find resting group `★ Your places` (areas first) and +6 per D4; region pickers `★ My places` + starred areas;
  Coming up watchlist (served figures per D3, below 3★ the quiet line, empty state text) as a separate labelled
  section.
- Lite: ☆ + `PRO` pill → worded upsell; `★ My places` + pill; one-line `Your places · PRO` teaser; no Find group. Phone
  work behind `useIsMobile`; desktop/tablet provably unchanged.
- Tests incl. the honesty test per list and an axe pass on the toggle and toast.

### F4b — Ask
- **Comparator:** `RANK` becomes `rank(AskUserContext)` (both users: `AskTools` ~1134, `StubAskEngine` ~198);
  `favourite` after `tideAligned`. `AskUserContext` gains `favouriteLocationIds` (and area names for
  `favouriteArea`); touches `userLess()`, `AskDryRunService`, `AskService`. **`rank_night_spots` is unchanged**
  (favourites do not apply to night ranking).
- **Pair shape (D4b):** `rank_spots` computes both orders server-side (with and without the favourite key) and the
  result carries `lead` plus an optional `companion` (with a `companionKind`: `TIED`, `YOUR_FAVOURITE`, or, for a `favourites: true` question, `HIGHER_ELSEWHERE`), so the
  model never derives the second slot itself. The pair is emitted when the asker has an in-scope favourite that is not
  the lead (see the next bullet); otherwise the shared, single-answer result is kept. Prompt lines (WITH_USER
  only) fix the wording above. New prompt-regression cases: tie pair, lower-rated favourite companion, clear-best
  single answer, no favourites; existing assertions untouched.
- **Marks and the pair (revised after review, owner 2026-11-18):** whenever a typed asker has an in-scope,
  pick-eligible favourite that is not the lead, `rank_spots` emits the pair (and `SpotInfo.favourite` /
  `favouriteArea`) without `favourites: true`; an unchanged head with no in-scope favourite keeps the byte-identical
  shared result. `favourites: true` is for "my places" questions (see Built).
- **`personal`** is set when the favourite key changed the post-`limit` list or a companion is emitted; otherwise
  the answer stays shared.
- **Cache correctness:** a personal answer carries a favourites fingerprint (the sorted ids and area names, joined) in its owner
  key, so a star change cannot serve a stale answer (test: star, ask, unstar, ask). The shared-first `lookup` is
  skipped when the user's favourites intersect the scope, so a Pro asker is not handed another asker's order.
- **Prompt and schema:** the two prompt lines go in `WITH_USER` only (keeping `RULES` and `USER_LESS` byte-for-byte,
  one cache rewrite for typed asks, none for Ready); `favourites` goes in the `withUser` branch of
  `AskToolSchemas.rankSpots` (absent from Ready by construction). Read `claude-cost-ledger.md` first.
- **Scope:** `favourites: true` keeps starred places plus members of starred areas; nothing starred returns an empty
  result with the note, not an error. **No thread state** for it exists; either follow-ups re-ask the model with the
  conversation (acceptable) or specify new state: default is no new state; follow-ups never touch the cache.
- Ready answers unchanged; the client may ★-mark a Ready pick (mark only).
- Prompt regression: add new cases, never edit existing assertions (they wait on the owner's approval).
- Tests: tie-break (equal rating/pick/tide, second alphabetically starred → first), starred 3★ vs unstarred 4★,
  Best vs starred non-Best, scope, empty note, cache shareable and personal paths, fingerprint invalidation.

- **Built (2026-11-18, revised after adversarial review).** `AskTools.rank(AskUserContext)` replaces `RANK`; `AskUserContext` gained `favourites`
  (`FavouriteLocations`, read by `UserFavouritesService.forAsk`); `RankSpotsResult` gained `lead`, `companion` and
  `companionKind`, `SpotInfo` gained `favourite` and `favouriteArea`; the cache key gained the fingerprint and the
  shared-hit bypass. Readings worth recording: the **pair is emitted**
  for a plain typed question whenever the asker has an in-scope favourite that is not the lead (`TIED` when the lead
  won only on a star, `YOUR_FAVOURITE` when the best favourite ranks lower), and the answer is then personal for that
  asker only; with `favourites: true` the `spots` list holds the favourites only, the lead is the best matching
  favourite (none: no lead, the empty note) and the blind winner is the companion (`TIED`, or `HIGHER_ELSEWHERE`).
  A shared answer stored for an asker whose favourites touch the scope is also stored under their own key.
  `favouriteArea` names the alphabetically first starred area a member belongs to. **Known limitation:** a question
  matched to a Ready answer is served favourite-blind (Ready is user-less and shared; `ReadyIntentRules` untouched).
  `AskDryRunService` carries the admin's own favourites. No frontend change: Ask's
  client renders served picks, so there was no `SpotInfo` to mark; the Ready-pick ★ is left to a later phase.
  `AskWarningPromptTest`'s two typed fingerprints and the two `tool-schemas-with-user*.txt` goldens were re-pinned (the
  intended `WITH_USER` and schema change); the user-less fingerprints are unchanged, proving `RULES` and `USER_LESS`
  are byte for byte as before. New prompt-regression cases are in `AskFavouritesRegressionTest` (never run; pending
  the owner's approval).

### F5 — Guides and landing (required before any release containing F4)
- New `landing/guide/finding-places.html`, `your-places.html`; sections in `at-a-glance.html`, `map.html`,
  `coming-up.html`, `ask-photocast.html`.
- Registration: `frontend/src/utils/guide.js` `GUIDE_PAGES` (and the "ten static pages" count; `GUIDE_SECTIONS` for
  any in-app links), `guideSections.test.js` (it pins the file list to the `GUIDE_PAGES` keys),
  `landing/guide/guide-search.js` (`pro` flag set for Your places), `landing/guide/guide.js`, the hub `index.html`;
  run `guideCss.test.js` and `guideShotPlaceholders.test.js`.
- Landing: pricing "Everything in Lite, plus" (`index.html`), `faq.html` (new entries **and** the existing "difference
  between Lite and Pro" answer), `terms.html` §4 (the tier prose list) and any other tier list.
- `roles-and-gating.md` *Freemium split*.
- **Release gate:** the owner tags the app **only after F5 is merged and `release-landing.sh` has deployed the
  landing and guide**. Stated in F4's changelog entry and F5's acceptance. No runtime flag (Q2).

## 3. Process (CLAUDE.md)
Per phase: Sonnet subagent in its own worktree (never `fork`), given its plan section, the build ladder, the
frontend's four commands (E2E smoke for F1/F4), a `changelog.d/` entry and the docs updates; planner reviews the
diff read-only; feature branch, conventional commit, ready PR to `main`; wait for CI and Codex 👍 on the head
commit; fix through Sonnet; squash merge; next phase from updated `main`. Accessibility and prompt-regression
workflows never block. Back-to-back phases without waiting, per the owner's standing preference. Each UI phase gets
its own adversarial diff review before commit.

## 4. Review lenses used (and to repeat per diff)
Dialog stacking/Back; ⌘K refusals; personal data off shared paths; Lite gating; backend-heavy; honesty rule; a11y.

## 5. Review outcome
Four reviewers (2026-11-18). Material changes from the first draft: D3 reversed (backend outlook); ⌘K guard
rewritten (not `askKeyRefused`); Map "All" filter replaced by relax-only-what-hides; area filter via
`focusLensNames`; Coming up pick conflicts with the `selectTab` invariant (D6); F2 introduces search ranking;
F4b flags emitted only when a favourite is in play (revised: no longer only on `favourites:true`), fingerprinted personal cache key, shared-hit bypass, prompt in
`WITH_USER` only, no thread-state claim; D1 own controller, enabled-only, no N+1; Undo toast is a new primitive;
the release gate includes `release-landing.sh`; F5 file list completed.

## 6. Owner decisions (2026-11-18, all answered)
1. Coming up picks: go to the Plan tab, then open the sheet (D6).
2. Release gate: owner-held tag after F5 is merged and `release-landing.sh` has deployed the landing and guide; no runtime flag.
3. Undo toast: build the new `useUndoToast` primitive.
4. Lite readers get the full areas directory in this series (revised 2026-11-18: **a possible information leak the owner wants tightened AFTER delivery; it must not block the series**). Seam for later: F2 builds the directory through one service method that takes the reader (v1 ignores it and returns everything). Tightening to Lite's own sub-regions then also moves `/api/areas/directory` from `REVALIDATABLE_READ_PATHS` to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` (it stops being ETag-shareable). Before deciding, check how Lite sub-region gating treats `/api/locations` today.
   **Closed 2026-11-19 (owner: leave it).** The check found `GET /api/locations` is role-free and ETag-shared and already gives every Lite reader each location's `subRegionId`/`subRegionName`, so sub-region membership is not new information in the directory; ratings stay gated by `LitePickShaper`. The only extra the directory shows is named-area membership and aliases. Decision: the directory stays full and ETag-shared. Reopen only with a plan that also restricts `/api/locations` (it feeds the Plan tab, Map and caching). The `reader` seam on `AreaDirectoryService.directoryFor` stays.
5. Favourites cap: **25** per user (places plus areas).
