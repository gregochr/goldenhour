# Scores, the written why, best bets and drive times become Pro and Admin only — plan

**Owner decision (2026-10-25).** photocast.online's pricing list, FAQ and Terms §4 list four things as
Pro: **Fiery Sky / Golden Hour scores**, the **per-location written explanation**, the **best-bet
picks** and **personalised drive times**. The v2 app shows all four to LITE. The owner chose to keep the
copy and gate the app to match (`landing-editorial-audit-2026-10.md` §2.1). Nothing in this plan changes
the landing page.

**Reviewed 2026-10-25** by two read-only lenses (server enforcement and caching; client and house
pattern). Their findings are folded in: the self-contained upsell door (§4), the two context memos
(S1), the shaper's early return (S2), `bestBetsWithdrawn` and the digest wither (P2), the strip's real
key row (P1), the drive filter at every origin (D1), and the job's test fixture (D2).

The tide series (`tide-pro-gate-plan.md`) is the template: the client is made null-tolerant and locked
first, then the server strips the fields at the HTTP edge, so the server is the real gate.

---

## 1. Where we are (main at `e9dc9cf7`)

| Feature | Served to LITE by | Drawn for LITE by |
|---|---|---|
| (a) `fierySkyPotential` / `goldenHourPotential` | `GET /api/briefing/evaluate/scores` (`LocationEvaluationView`, no role gate, no `Authentication` param); `GET /api/briefing` slots (`BriefingSlot`); `GET /api/forecast` and `?date=` (`ForecastListDto`: LITE gets `basic_*`, falling back to enhanced; sparse cached-only rows ignore the role); `GET /api/forecast/{id}` (`ForecastEvaluationDto`, same fallback) | `WindowSpotPeek.jsx:194–212` (desktop fine pointer only), `LocationFourDaySheet.jsx:624–645` (all widths), `HeatmapGrid.jsx:230–237` (the Regional planner drill-down, which falls back to the briefing slot at `:79–89`) |
| (b) per-location Claude `summary` | the same four endpoints: `LocationEvaluationView.summary`, `BriefingSlot.claudeSummary` and `claudeHeadline`, `ForecastListDto.summary` (sparse rows), `ForecastEvaluationDto.summary` / `headline` | peek clause `WindowSpotPeek.jsx:220–232`; sheet "why" `LocationFourDaySheet.jsx:695–707` (built at `locationSheet.js:864–912`); Map callout reason `MapCallout.jsx:730–737, 1075–1100`; Regional planner row `HeatmapGrid.jsx:168, 224` |
| (c) best-bet picks | `GET /api/briefing`: `window.pick` (`BriefingWindow.Pick`) and the top-level `bestBets[]`, `bestBetModel`, `bestBetStatus`, `bestBetsWithdrawn`; `GET /api/briefing/digest`: `Window.pick`, `headline`, `regionName`, `locationName`, `locationId` | `windowFirstCards.js:988–1011` → the Plan strip legend and `.best`/`.also` cells (`WindowFirstHeatStrip.jsx:1332–1333, 1664–1682`), the popup's header chip (`WindowSheetDialog.jsx:493–503`), `WindowPickDialog`, the Map window pill's medallion and menu rows (`WindowControl.jsx:408, 751, 976`), the Map panels' `.wf-land-pick` (`MapWindowPanel.jsx:109/134`, `MapRegionPanel.jsx:92/130`) |
| (d) drive times from home | `GET /api/user/settings/drive-times`, `GET /api/user/settings/reach`, `POST /api/user/settings/drive-times/refresh` (class-level `isAuthenticated()` only); `DriveTimeRefreshJob.run` routes **every** enabled account with a home through OpenRouteService | spot card reach and leave lines, the peek, the heat-strip title, the popup's "within reach" wording, ring labels, the sheet meta and row leave, the Map callout facts, `MapLabels` tips, `RegionsJump`, `HeatmapGrid`'s `driveMap`, Plan search. Already Pro in the UI: the reach lens (greyed lock, `WindowFirstLensBar.jsx:241–250`, but keyed on `role === 'LITE_USER'`), the Settings radius and refresh button |

Nothing in the frontend reads `claudeHeadline`, `bestBets` or the digest (grepped). Ask is already
Pro-only (`SecurityConfig` matcher plus `AskController`'s `@PreAuthorize`), so it keeps the full data.
The operator notifications (email, Pushover, macOS toast) go to one configured recipient, never to a
user; they are untouched. `GET /api/almanac` carries none of the four.

**Already free, and staying free** (the two-question rule and earlier decisions):
- the star **rating**, the **verdict** (`displayVerdict`), the triage stand-down and its reason;
- **region** prose: `BriefingRegion.glossHeadline` / `glossDetail` / `summary`, drawn by
  `WindowProseSlot`, `MapRegionPanel`, `HeatmapGrid`'s region rows, and the callout's and sheet's
  region fallback (`summaryRegion`, `map-callout-reason-region`);
- hot topics, Coming up, the almanac;
- `GET /api/regions/drive-times`, the **region-base** matrix. It is admin-managed geography, the same
  for every reader, and it is not "from your home"; the away-origin lens keeps using it for LITE.

## 2. The target

| | LITE | PRO / ADMIN |
|---|---|---|
| Star rating, verdict, stand-downs | yes | yes |
| Fiery Sky / Golden Hour scores | **no** (absent from every payload) | yes |
| Per-location written why (`summary`, `claudeSummary`, `claudeHeadline`, detail `headline`) | **no**; the callout and sheet fall back to the **region** gloss, which stays free | yes |
| Best bets (`window.pick`, `bestBets[]`, the digest's pick) | **no** | yes |
| Drive times from home (minutes, leave-by, reach, the nightly measurement) | **no**: the three endpoints are 403, the job skips the account | yes |
| Region-base drive times (`/api/regions/drive-times`), shown as lines under an away origin | yes | yes |
| **Filtering** by drive time (the reach lens and the Map's drive filter), at any origin | **no** (greyed lock) | yes |

The reach lens is already locked for LITE at every origin, away included; D1 locks the Map's drive
filter the same way, so the two controls agree. Region-base minutes stay visible as lines.

**Decisions taken in this plan** (reversible; each is a one-line change if the owner disagrees):
1. **LITE gets no Fiery Sky / Golden Hour number at all**, not the `basic_*` evaluation's numbers. The
   site names the scores as Pro without qualification. `basic_fiery_sky` / `basic_golden_hour` /
   `basic_summary` stay in the database; they are simply never served.
2. **`claudeHeadline` and the detail `headline` count as the written why.** Both are per-location
   Claude prose.
3. **Region prose stays free**, including the region gloss that a pick copies. Only the pick object
   (which window, which region, which location) goes.
4. **Bluebell is out of scope.** `bluebellSummary` / `bluebellScore` are already treated as Pro by the
   UI, with the wrong copy ("Upgrade to Pro" at `FiltersPopover.jsx:188–190` and
   `forecastDetailFacts.js:126–160`, both against the house rule). That is a separate follow-up.
5. **Stored LITE drive times are left in place.** No endpoint serves them after D2 and the job stops
   refreshing them; a promoted account's next nightly run, or its own Refresh press, writes fresh ones.
   Until then a promoted account's stale rows feed Ask's `hasDriveTimes` and its drive answers
   (accepted; Ask is Pro, and the rows are that account's own).

## 3. Where the lock comes from (every client phase)

- **Copy the tide lock.** The role is read in `WindowFirstBriefingContext` and only booleans go down.
  Beside `isPro` (`:682`) and `tideLocked` (`:685`) add **`scoresLocked`**, **`picksLocked`** and
  **`driveLocked`**, all `!isPro`. Each context default is **`false`** (`:97–98`), and each prop that
  carries one defaults to `false`, so a bare test render and every PRO suite stay unlocked and unedited.
  Do **not** add a `role ===` check in a component. `MapView.jsx:1216` already reads `useAuth().role`;
  leave that, and pass the new booleans through `WindowFirstMapPane` as `tideLocked` is.
- **Fail closed.** `!isPro` treats a null or unknown role as locked. The reach lens's
  `role === 'LITE_USER'` (`WindowFirstBriefingContext.jsx:557`) becomes `driveLocked`, so a null role
  locks there too.
- **The stale role.** `goldenhour_role` is written at sign-in only; the server reads the row on every
  request. A demoted PRO→LITE session draws PRO surfaces against stripped bodies (each client phase
  makes that render as absent, never as an error or a misleading line). A promoted LITE→PRO session
  stays locked until the next sign-in, as tide and Ask do.
- **Server rule.** Every server phase decides with one helper, fail-closed:
  `pro = Authorities.hasRole(auth, PRO_USER) || Authorities.hasRole(auth, ADMIN)`, as
  `BriefingController.getBriefing` does (`:85–87`). `ForecastController.isLiteUser` (`:855`) fails
  **open** today (`auth == null || hasRole(LITE_USER)`, so a bare `ROLE_USER` is PRO); S2 replaces it with
  the fail-closed rule. The 22 bare `@WithMockUser` tests in `ForecastControllerTest` get an explicit
  role (`PRO_USER` where they assert PRO data).

## 4. The shared upsell (built in S1, reused by P1)

There is no generic upsell today: `TideUpsell.jsx` and `AskUpsell.jsx` are two copies of one shape that
share CSS through selector lists (`index.css` ~4151–4243). S1 adds a third, generic one rather than a
fourth copy, and leaves tide and Ask as they are (migrating them is a follow-up, not this series).

- `components/shared/ProUpsell.jsx`:
  - `ProUpsellContent({heading, body, headingId, headingRef?, bodyId?, testIdPrefix})`: the `wf-lens-pro`
    pill, an `h3`, a `p`. **No preview sketch** (the two existing sketches are feature drawings; a
    generic one would say nothing).
  - default **`ProUpsellDoor({label, heading, body, testIdPrefix, className?})`**: a **self-contained**
    trigger plus popover plus lifecycle. ⚠️ `AskUpsellPopover` (`ask/AskUpsell.jsx:83–140`) is only half
    of its precedent: the rest lives in the host (`WindowFirstShell.jsx:1603–1677`). The door owns all of
    it itself, so a host only renders `<ProUpsellDoor …/>`:
    1. open/closed state, `aria-expanded`, `aria-controls`;
    2. the popover: `role="dialog"`, `aria-labelledby` the heading, `aria-describedby` the body, **no**
       `aria-modal`, rendered inline next to the trigger (not portalled) and **absolutely positioned**
       so it never becomes a flex item of its host row; heading focused on open; a "Not now" button;
    3. Escape on the popover **or on the trigger** closes it, returns focus to the trigger, and calls
       `stopPropagation()` + `preventDefault()` so the Escape never reaches a host `Modal`'s document
       listener (`shared/Modal.jsx:248–253`);
    4. an outside `pointerdown` (capture) closes it — Safari gives a null `relatedTarget`, so blur alone
       is not enough;
    5. a `focusin` anywhere outside the trigger and popover closes it (Shift+Tab out through the
       trigger);
    6. if the trigger unmounts while open, the popover closes and focus is not stranded on `<body>`.
    ⚠️ **No foreign-modal stand-down predicate.** `foreignModalOverPaneOf` (`map/TideUpsell.jsx:105`) and
    `shellForeignDialog.js:27` both count any `aria-modal` / `role="dialog"`, which includes the
    location sheet the S1 door lives in, so either would make one Escape close the whole sheet. The door
    is only ever the topmost layer while it is open (nothing opens over it), so it handles its own keys
    unconditionally. It must not add a dialog to the two-dialog count: it is a non-modal popover inside
    its host, not a `Modal`.
    No link, no "upgrade" text.
  - CSS: add `.wf-pro-upsell` to the existing `.wf-tide-upsell, .wf-ask-upsell` selector lists, so it
    inherits the look; add `max-width: min(360px, calc(100vw - 32px))` so it fits a 320px phone.
- **The trigger** is a real `<button>` with the `wf-lens-pro` pill **inside** it, `aria-haspopup="dialog"`,
  `aria-expanded`, `aria-controls` the popover id. Its accessible name is its text plus "Pro".
- **Copy** (placeholder until the owner signs it off, as Ask's was):
  - scores: heading `Fiery Sky and Golden Hour scores`; body `See how strong the colour and the light
    should be at each location, with a note on why.`
  - best bets: heading `Best bets`; body `See the forecast's two best windows, and where to go for each.`

## 5. S1 — scores and the written why: the client (one PR, Sonnet)

Changes nothing for PRO. For LITE it hides what the server still sends, and it makes every surface
render cleanly when S2 stops sending it.

1. **Context: two memos, one per route in.** `scoresLocked = !isPro` (§3).
   - **Score rows.** `fetchScores` (`WindowFirstBriefingContext.jsx:341–369`) has empty deps, so do
     **not** read `scoresLocked` inside it (stale closure). Keep the raw rows in state and derive, in a
     `useMemo` over `[rawRows, scoresLocked]`, the rows with `fierySkyPotential`, `goldenHourPotential`
     and `summary` removed; rating, `displayVerdict`, triage and light times are kept. **Every**
     published form derives from the stripped rows: `scoreRows`, `scoreIndex`, `evaluationScores`, and
     the `onEvaluationScoresChange` lift to `App.briefingScores` → `MapView` → `MapCallout`'s
     `scoreEntry.summary` (`:730`).
   - **The briefing.** One `servedBriefing` memo over `[briefing, scoresLocked]`: when locked, a copy
     whose slots lack `fierySkyPotential`, `goldenHourPotential`, `claudeSummary`, `claudeHeadline`;
     when unlocked, **the same object** (identity kept, so PRO memo chains do not re-run). It feeds every
     derivation in the context and is the `briefing` the context publishes, so `HeatmapGrid.mergedScore`
     (`:79–89`) sees the stripped slots with no prop. Region fields are untouched.
   - The PR lists every reader of the raw briefing and raw scores (grep `briefing.days`, `scoreRows`,
     `evaluationScores`, `briefingScores`) and shows each reads the served form.
2. **The peek** (`WindowSpotPeek`, desktop only). With all three null `resolveSpotPeek` returns null and
   the peek no-ops (`WindowSpotStrip.jsx:186–188`). That is the LITE behaviour: no peek. No change.
3. **The location sheet** (`LocationFourDaySheet`, all widths):
   - the score bars are absent (null-tolerant already; pin it);
   - the "why" line uses the region gloss when there is one (already the fallback); when there is none,
     a LITE sheet draws **nothing** in place of the `Nothing written about this one yet.` branch
     (`:715–720`) only, which would tell a LITE reader the forecast has no prose. The away line and
     `Ratings are still loading.` are unchanged;
   - **one upsell door per sheet**, below the sheet's header meta: `<ProUpsellDoor label="Scores and the
     why" …/>` with the scores copy. Only when `scoresLocked` **and** the sheet is a sky sheet with
     window rows (`!hourlyBody`, `LocationFourDaySheet.jsx:294`: a hide sheet has no scores to sell).
   - **Test:** with the popover open inside the sheet, one Escape closes the popover and the sheet stays
     open; a second Escape closes the sheet.
4. **The Map callout** keeps its region fallback (`map-callout-reason-region`). No door there: the
   sheet, one tap away, carries it.
5. **The Regional planner** (`HeatmapGrid`): a row with no `summary` is not expandable (`:168`); that is
   the LITE behaviour. No door.
6. **Forecast detail readers.** Grep every reader of `GET /api/forecast/{id}`'s `summary`, `headline`,
   `fierySkyPotential`, `goldenHourPotential` (`useForecastDetail`, `forecastDetailFacts.js`,
   `LocationSheetFacts`) and of `ForecastListDto`'s `summary` and potentials (`useForecasts`), and show
   in the PR that each is absent-safe. `MarkerPopupContent`, `PromptTestView`, `ModelTestView` are v1 or
   admin-only and are excluded.
7. **Tests** (`frontend-test-standards.md`):
   - context: a LITE provider's score rows carry no potentials or summary; a PRO provider's do;
   - sheet: LITE draws no bars, no `Nothing written` line, the region gloss when present, and the door;
     the door opens and closes the popover (Escape, "Not now", blur), returns focus, has no `<a>`, and
     contains no "upgrade" text (case-insensitive); PRO draws the bars and no door;
   - `ProUpsellDoor` unit tests for each of §4's six behaviours (focus on open; Escape on popover and
     on trigger, with propagation stopped; outside `pointerdown`; `focusin` outside; trigger unmount)
     and the trigger's accname (`label` + "Pro");
   - PRO: the published `briefing` is the same object as the fetched one (identity);
   - `HeatmapGrid`: a LITE drill-down shows no "Fiery Sky N" and no reasoning.
8. **Docs:** `reference/map-tab.md` and `reference/plan-tab.md` (the sheet door, LITE's peek),
   `reference/roles-and-gating.md` (a scores bullet in the UI-pattern list, current state);
   `changelog.d/20261025-lite-scores-locked.md`, `### Changed — Lite readers see the star rating, not the Fiery Sky and Golden Hour scores or the written why`.

## 6. S2 — scores and the written why: the server (one PR, Sonnet; after S1 merges)

1. **`GET /api/briefing/evaluate/scores`.** Add an `Authentication` parameter. LITE gets each row through
   a new `LocationEvaluationView.forLite()` that nulls `fierySkyPotential`, `goldenHourPotential` and
   `summary` via the canonical constructor; everything else is kept.
2. **`GET /api/briefing`.** Extend `BriefingRoleShaper.forLite`'s `liteSlot` to null
   `fierySkyPotential`, `goldenHourPotential`, `claudeSummary`, `claudeHeadline`, through a new
   `BriefingSlot.forLite()` built on the canonical constructor. ⚠️ **Not** `withClaudeScores`, which
   recomputes `displayVerdict` from the rating (`BriefingSlot.java:449–455`) and would overwrite a
   triage, neighbour-guard or sample-gate verdict. ⚠️ `liteSlot` **returns early** today when a slot has
   no tide-match flag (`BriefingRoleShaper.java:66–68`): strip the scores on **every** slot first, then
   filter the flags. A test fixture slot with potentials and **no** tide flag pins it. Region prose is
   untouched.
3. **`GET /api/forecast`, `?date=`, `/{id}`.** In `ForecastDtoMapper`'s LITE branches, null the potentials
   (basic and enhanced), `summary` (basic and enhanced) and the detail's `headline`. **Include the sparse
   cached-only rows** (`toSparseListDto`, ~`:210–250`) and the passed sparse slots built by
   `ForecastController.viewOf` (~`:400`), which ignore the role today. `rating` keeps today's selection.
   `bluebellSummary` is untouched (§2 decision 4). With LITE now getting null potentials and summaries,
   the `useBasic` branches (`ForecastDtoMapper.java:168, ~393`) are dead: **collapse them**, rather than
   leave `basic_*` selection behind. Fix the javadocs that become false: `ForecastController.java:418–420`
   ("LITE users get basic …"), `ForecastDtoMapper.java:37–38` and `:206` ("sparse rows are role-agnostic").
   Every LITE branch must be exercised (jacoco 80% per class).
4. **Fail closed** (§3): replace `ForecastController.isLiteUser` (`:855–858`) with the shared rule. `/api/**`
   is `.authenticated()`, so `auth == null` occurs only in unit tests; `/history` and `/compare` are
   ADMIN-only and unaffected. Of the 22 bare `@WithMockUser` tests in `ForecastControllerTest`, only those
   that pin the lite flag (e.g. `toDto(entity, false)`) break; give them `roles = "PRO_USER"`.
   `ForecastWindowAnchorTest.java:129,133` (`getForecasts(null)` expects lite) stays true.
5. **Caching.** `/api/briefing`, `/api/briefing/evaluate/scores`, `/api/forecast` and the detail pattern
   are ETag-listed; the ETag is hashed from the body, so the LITE and PRO bodies get different
   validators. Pin it for `/api/briefing/evaluate/scores` in `HttpCachingIntegrationTest` (template
   `:211–240`), with a fixture that **really carries** a non-null potential and summary: a LITE GET with a
   PRO 200's `If-None-Match` gets a **200 with the LITE body**, and the reverse. A role variant is not
   personal data; `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` is unchanged.
   **Client SWR:** `briefing:${role}` and `forecasts:${role}` are keyed by role, but a LITE key may hold a
   pre-S2 body that still carries the fields. Bump `swrCache.js`'s `PREFIX` from `v2` to `v3` in this PR
   so no pre-strip body is ever painted. (S1 already hides them for LITE, so this is belt and braces.)
6. **Tests:** `BriefingRoleShaperTest` (the slot fields, PRO golden unchanged; the completeness test at
   `:204–257` covers only `BriefingWindowTide` and `LocationTideFact` today, as kept/stripped sets:
   extend it to `BriefingSlot`'s 18 components, minding the `@JsonUnwrapped` `weather` and `tide`);
   `LocationEvaluationView.forLite` completeness in its own test (20 components); javadoc on every new
   public method (checkstyle); `BriefingEvaluationControllerTest`
   LITE, PRO, ADMIN and fail-closed (no auth role) shapes; `ForecastDtoMapperTest` (`:308–360` change:
   LITE now gets null potentials and summary, rich and sparse); `ForecastControllerTest` and
   `ForecastControllerAsOfEventTest` LITE cases; the ETag pin. Minimise `any()` in `when`/`verify`.
7. **Docs:** `reference/api-endpoints.md` (Forecast `:23–47`, the `:41–42` LITE sentence; Briefing
   `:215–250`); `reference/architecture-decisions.md` (the role-variant note beside the Forecast DTO
   precedent); `reference/roles-and-gating.md` (scores bullet: server enforced);
   `changelog.d/20261025-lite-scores-api.md`, `### Changed — The API no longer sends Lite the Fiery Sky and Golden Hour scores or the per-location written why`.

## 7. P1 — best bets: the client (one PR, Sonnet; after S2 merges)

1. **Context.** `picksLocked = !isPro`. When locked, `windowFirstCards.js` sets `card.pick = null` at its
   one derivation (`:988–1011`), via a `picksLocked` argument defaulting to `false`; `buildWindowCards`
   is called only from the context (`:626`), whose memo gains `picksLocked` in its deps. Every
   downstream reader then sees no pick: `windowFirstStrip.js:131/138` (the strip chip, the cell class,
   the spoken label at `WindowFirstHeatStrip.jsx:1332`), `WindowFirstMapPane:723` → `mapEvents:300` →
   `WindowControl:408/919`, `MapWindowPanel:107`, `MapRegionPanel:92`, `WindowSheetDialog:493`,
   `WindowFirstShell:951/3207` (the pick dialog). `regionBestIndex` reads the region's best rating, not
   the pick; `MapLabels`, `mapLabels.js` and `AskContext` "picks" are Ask's. Grep `\.pick\b` across `frontend/src` (not tests) and list every hit in the PR, marking
   which read `window.pick` / `card.pick` and which are unrelated (`WindowSheetDialog.jsx:546` "no clear
   pick" is the neighbour guard; `LocationFourDaySheet.jsx:545` "best here" is local; the Map landing
   card was removed in #1391).
2. **Surfaces go absent:** the per-card "Best bet / Also good" chip (`wf-hc-lg`,
   `WindowFirstHeatStrip.jsx:1664–1672`, `data-testid="wf-heat-legend"`, `aria-hidden`) simply stops
   rendering, as do the `.best`/`.also` cell classes and their spoken "best bet", the popup's header
   chip, the Map pill's medallion and its menu rows, and the panels' `.wf-land-pick`. `WindowPickDialog`
   can no longer be opened. Pin each.
3. **One door**, in the strip's key row **`wf-heat-foot`** (`WindowFirstHeatStrip.jsx:1892–1932`; every
   width; a wrapping flex row, `index.css:2842–2851`; not `aria-hidden`): `<ProUpsellDoor label="Best
   bets" …/>` with the best-bets copy. Its popover is absolutely positioned (§4), so it is not a flex
   item of the row, and it must not overflow a 320px phone (§4's `max-width`).
4. **Tests:** LITE: no pick on any surface above, the door works (as S1's), no `<a>`, no "upgrade";
   PRO: unchanged (existing suites untouched). A LITE render with a payload that **still carries** a
   pick (pre-P2) still shows none.
5. **Docs:** `reference/plan-tab.md`, `reference/map-tab.md`, `reference/roles-and-gating.md`;
   `changelog.d/20261025-lite-best-bets-locked.md`, `### Changed — Best bets are Pro`.

## 8. P2 — best bets: the server (one PR, Sonnet; after P1 merges)

1. **`GET /api/briefing`.** `BriefingRoleShaper.forLite` also nulls each `window.pick` (add
   `BriefingWindow.withPick`, canonical constructor) and serves `bestBets` as an empty list with
   **`bestBetsWithdrawn` explicitly `null`** (`withBestBets(List.of(), null)`; passing the original, as
   `ServedBriefingAssembler.java:448` does, would tell LITE "a pick was withdrawn" beside an empty list;
   `bestBets` is `@JsonInclude(ALWAYS)`, so it stays present and empty), with `bestBetModel` and
   `bestBetStatus` null (add withers, with javadoc). Region gloss is untouched.
2. **`GET /api/briefing/digest`.** `getDigest(int limit)` (`BriefingController.java:99–108`) gains an
   `Authentication` parameter. For LITE, each `Window` loses `pick`, `headline`, `regionName`,
   `locationName`, `locationId` (the location, region and headline the digest names are the pick's;
   decision 3), through a new `BriefingDigestResponse.Window.withoutPick()` wither (the record is
   positional with 11 components, `:96–107`, so no hand-built constructor call in the controller).
   Fix the javadoc at `:69–76` and `:95–99` ("a gate here would deny nothing").
3. **Internal callers keep the full data:** `getCachedBriefingForApi()` for the digest service and
   `AskSnapshotBuilder`; `PipelineOrchestrator` persisting `pipeline_run_pick`; `AskTools`. The shaper
   runs only at the controller edge.
4. **Caching:** extend S2's ETag pin to `/api/briefing` with a fixture carrying a pick (the tide pin
   exists; add the pick to it or add a case). Digest: the same body-hash argument; add a LITE/PRO case.
5. **Tests:** `BriefingRoleShaperTest` (pick, bestBets, model, status, withdrawn; completeness for
   `BriefingWindow`'s 9 components and `DailyBriefingResponse`'s 17, with `pick`, `bestBets`,
   `bestBetModel`, `bestBetStatus`, `bestBetsWithdrawn` in the stripped set); `BriefingControllerTest` (`:182` `bestBets` present
   for every role, now empty for LITE; `:445` the PRO pick kept; `:497` the LITE digest shaped).
6. **Docs:** `reference/api-endpoints.md` (`:217` the role-shaped briefing, `:219–230` the digest, which
   is no longer "pinned across LITE/PRO/ADMIN"); `reference/plan-tab.md`;
   `changelog.d/20261025-lite-best-bets-api.md`, `### Changed — The API no longer sends Lite the best-bet picks`.

## 9. D1 — drive times: the client (one PR, Sonnet; after P2 merges)

1. **Context.** `driveLocked = !isPro` replaces `role === 'LITE_USER'` in `useReachLens` (`:557`). When
   locked, the context **does not call** `getReach()` (`~:477–500`) and `reachById` stays empty. The
   away-origin swap to the region matrix (`effectiveReachById`) is unchanged, so an away origin still
   shows region-base minutes for LITE (§1, free).
2. **A stale demoted client** gets a 403 from `getReach()` after D2. The context is its only caller
   (`:475`) and its `.catch` already empties the map on any error; pin that a 403 renders as no drive
   times with no error state and no retry. `getDriveTimes` has no caller. `forecastApi.refreshDriveTimes`
   (`:265`) is dead code posting to `/api/locations/drive-times`, which no longer exists: delete it and
   its tests.
3. **Every drive and leave line goes absent** with an empty map (the normal no-postcode state). Pin it on
   the spot card, the sheet, the callout facts and the popup's "within reach" wording.
4. **The Map's drive filter** (`FiltersPopover.jsx:208`; state `driveTimeFilter = useState(0)`,
   `MapView.jsx:1294`, in memory only) is not locked today. With `driveLocked`, at **every origin** (§2):
   - `FiltersPopover` gets a `driveLocked` prop (it reads `role` directly at `:187–190`; add no new
     `role ===` check) and draws the segment as the reach lens does: `.wf-lens-locked`, real `disabled`,
     pill outside the grey. It is a segmented control too small to carry an upsell (house rule).
   - The value is forced to "Any" **where the filter is applied** (`MapView.jsx:~3216`,
     `driveLocked || filter === 0`), not at each setter: it is set by a click (`:5905`), the Plan
     handoff's `limitMinutes` (`:1985`), the reset (`:4813`) and the breadcrumb (`:6202`). The
     filter-count badge (`:4803`) and the breadcrumb's reach clause read the effective value too.
5. **Settings** already says `Pro accounts also get drive times from home.` and locks the radius and
   the refresh button. Its `isPro` (`UserSettingsModal.jsx:556`, from `settings.role`) reads the server's
   role, which is fresher than the client's; keep it. **Hide** the `Last calculated:` stamp
   (`:891`) for non-Pro: under decision 5 it goes stale and means nothing to LITE.
6. **Tests:** LITE makes no `/api/user/settings/reach` request; a 403 renders as no drive times; the
   Map drive filter is locked and forced to any; PRO unchanged.
7. **Docs:** `reference/platform.md` (drive times), `reference/map-tab.md` (filter lock),
   `reference/roles-and-gating.md`; `changelog.d/20261025-lite-drive-times-locked.md`,
   `### Changed — Drive times from home are Pro`.

## 10. D2 — drive times: the server (one PR, Sonnet; after D1 merges)

1. Method-level `@PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")` (overrides the class-level
   `isAuthenticated()`; `@EnableMethodSecurity` is on, and `OutcomeRatingController.java:92` is the
   precedent; the JWT filter loads the role from the row per request, so a demotion is a 403 at once)
   on `GET /drive-times` (`UserSettingsController:212`),
   `GET /reach` (`:232`) and `POST /drive-times/refresh` (`:201`). LITE gets 403. Remove the "no role gate"
   javadoc. `GET /api/user/settings` is unchanged (`driveTimesCalculatedAt` is harmless metadata).
2. `DriveTimeRefreshJob.run` (`:199–203`) considers only enabled PRO_USER and ADMIN accounts with a
   home, including the admin "Run now". **Filter the `findAll()` roster in memory** (`role` is on
   `AppUserEntity`, `:86`) rather than adding a repository query, so the existing `findAll()` stubs and
   verifies (`DriveTimeRefreshJobTest.java:102–114`) keep working. A null role is excluded. ⚠️ The
   test fixture `user()` (`:73–80`) never sets a role: set `PRO_USER` there by default first, or ~29
   tests fail or pass vacuously on the "nothing to do" early return (`:205`). This also stops spending
   OpenRouteService quota on LITE homes.
3. Saving a home stays open to every role (the postcode prompt is for everyone, `roles-and-gating.md`).
4. **Tests:** `UserSettingsControllerTest` (`:192–280`: LITE 403 on all three, PRO and ADMIN 200; the
   bare `@WithMockUser` cases get explicit roles; `:400` lookup/reverse stay open to LITE);
   `DriveTimeRefreshJobTest` (a LITE user with a home is not routed; PRO and ADMIN are).
5. **Docs:** `reference/api-endpoints.md` (`:391–393`, `:415–418`: drive times are now server-gated);
   `reference/platform.md`; `reference/roles-and-gating.md` (the account-deletion paragraph's "The server
   does not gate drive times by role either" sentence becomes the current state);
   `changelog.d/20261025-lite-drive-times-api.md`, `### Changed — The API no longer measures or serves drive times from home for Lite accounts`.

## 11. Order, release and the briefs

| PR | Depends on | Why |
|---|---|---|
| plan (this doc + the `roles-and-gating.md` freemium line) | — | |
| **S1** | plan | client lock and null tolerance; builds `ProUpsell` |
| **S2** | S1 | server strip; without S1 a stripped sheet says "Nothing written about this one yet." |
| **P1** | S2 | reuses `ProUpsell`; touches the same context file |
| **P2** | P1 | |
| **D1** | P2 | same context file |
| **D2** | D1 | without D1 a LITE client asks for `/reach` and gets 403 |

One PR per phase, each branched from the updated `main` after the previous one merged. **PWA residual
(accepted, as tide Q8):** an installed PWA runs its precached bundle for one session after a deploy; if
a client phase and its server phase ship in one release, a LITE PWA may draw the old bundle's absent-field
behaviour for that session (no bars, no pick, no drive lines). Nothing errors.

**Briefs.** Each phase is one Agent call with `model: "sonnet"` and `isolation: "worktree"`, from the
updated `main`, carrying this plan's §2, §3, §4 (client phases) and its own section verbatim, the files
named, the tests listed, the gates (the backend ladder ending in `clean verify` excluding integration,
gated on Maven's exit code; the frontend's four commands, all four on every phase because S2 touches
`swrCache.js` and frontend tests regex backend source), and its changelog file. The planner reviews each
diff read-only and sends fixes back. Then CLAUDE.md steps 3–6.

**UI review cadence (S1, P1, D1):** read-only lenses: focus and keyboard; accessibility (accname,
dialog naming, forced colours); phone vs desktop (320px, 640px); **role leakage** (no score, no
per-location prose, no pick, no home drive minute for LITE on any surface; no link; no "upgrade");
visual; **PRO regression**. Then a browser check on 8083 as `admin` and, where a LITE account can be
signed in, as LITE; the report says what was seen and what was only tested.

## 12. Known open paths after the series

- Bluebell (`bluebellSummary` / `bluebellScore`) and its two "Upgrade to Pro" strings (§2 decision 4).
- The region gloss names the best region of a window for every role; it is region prose, kept free.
- `GET /api/regions/drive-times` (region-base, not personal).
- Tide and Ask keep their own upsell copies; migrating them to `ProUpsell` is a follow-up.
