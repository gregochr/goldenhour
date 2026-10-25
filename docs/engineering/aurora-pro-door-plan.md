# Aurora for Lite: a Pro door where the aurora rows would be — plan

**Owner decision (2026-10-25).** Aurora is Pro. Today a LITE account gets it *hidden*, which breaks the
house role-gating rule (`roles-and-gating.md`, "Role gating — UI pattern": a Pro feature is visible to
LITE with a "Pro" pill and opens a worded upsell). The owner picked option 2 of two:

1. ~~hide everything aurora from LITE~~ (rejected: silences "what is happening", and LITE never learns
   aurora is a thing they could have);
2. **keep the "something is happening" signal free, and show LITE a Pro upsell door where the aurora
   rows are**, driven by a small role-free "an aurora night exists" answer from the server.

The forecast itself (per-location ratings, the field, the viewline, the stored run's summaries) stays
Pro and stays server-enforced. Nothing in this plan changes what PRO or ADMIN see.

**Reviewed 2026-10-25** by two read-only lenses (server: security, rule parity, caching; client: the
window control's menu, the house door, phone). Folded in: the simulation read order and the
tautological "not ended" test (§2.1), `currentNightEndsAt` and the 5-minute refetch, the true writer of
stored nights (§2.1, Q4), the test base class (B1), the missing inline popover variant, the arrow-key
and Escape-focus conflicts in `WindowControl`, the ~73 mock factories, and footer order (§2.2, F1).

---

## 1. Where we are (main at `e39dbbe9`)

**Server.** `AuroraController` (`/api/aurora/*`, class-level `@PreAuthorize` PRO/ADMIN, line 37) and
`AuroraForecastController` (`/api/aurora/forecast/*`, PRO/ADMIN, line 48; `/preview` and `/run` ADMIN)
refuse LITE with 403. That covers `/status`, `/locations`, `/viewline*`, `/forecast/results` and
`/forecast/results/available-dates`.

**Client.** A LITE client never has an aurora status or an aurora date:
- `api/auroraApi.js:12–22` folds the `/status` 403 to `null`; `AuroraStatusContext.jsx:93` stores it.
- `MapView.jsx:2187–2194` skips the available-dates fetch for LITE (the 403→`[]` fold in
  `auroraApi.js:144–154` is a second net).
- `utils/mapEvents.js:876` drops aurora dates for `isLite`; the module doc (`:89–95`, "Aurora is
  absent, not greyed, for LITE") records why: the client cannot learn a night exists to grey it.
- `MapView.jsx:1837` (`auroraAvailable`), `:3388` (`auroraTabOffered`), `App.jsx:475` (`auroraTabOn`)
  all refuse LITE; the aurora-only Map tab and the banner's map door never exist for LITE.

**What LITE actually sees of aurora today:**
- the **aurora hot topic** on the Plan tab and in Coming up (`AuroraHotTopicStrategy`, emitted at
  MINOR and above, never under a simulation; served to every role, `ReadyQuestion.java:125`);
- **nothing else.** ⚠️ The earlier chat summary said the `AuroraBanner` is free; it is not.
  `AuroraBanner` renders from `useAuroraStatus()`, which is `null` for LITE, so LITE has never seen the
  banner. Likewise the "Aurora viewline available — upgrade to Pro" chip (`MapView.jsx:1767–1769`,
  `:7012–7031`) needs a non-null status and is **unreachable for LITE in production** (only mocked
  tests render it). Its copy also breaks the house rule ("upgrade" text).

**How a Pro aurora row is built** (so the door can stand in the same place):
- `mapEvents.js` `nightRow` (`:504–537`) builds `{ id: 'aur:<date>:AURORA', kind: 'aur', label:
  nightLabel(date) ('Tonight' / '<Day> night'), bestRating: bestOfNight(rows), … }`, pushed after the
  date's solar rows and the astro row.
- Nights come from stored results (`available-dates`, filtered by D-14's `isNightOffered`: a night is
  offered from `currentNightDate` on, so last night stays a row until dawn) plus the live night
  (`auroraTabOffered`: active, real, MODERATE or STRONG, night not ended) when no stored result names it.
- Desktop renders rows in `components/map/WindowControl.jsx` (`wf-win-menu` → `role="listbox"`
  `wf-win-listbox`, rows `wf-win-row` grouped under `wf-win-day`). Phone renders them in
  `components/map/MapPeekSheet.jsx` `MapPeekWindowsSection` (`:370–525`, flat `role="listbox"`
  `wf-map-peek-wlist`). Both get `rows={mapEvents}` and `onSelect={selectEvRow}`.

**The house door.** `components/shared/ProUpsell.jsx` exports `ProUpsellDoor` (`label`, `heading`,
`body`, `testIdPrefix`, `className?`): a trigger button with the `wf-lens-pro` pill inside it and a
non-modal `role="dialog"` popover with a "Not now" button; it owns Escape (stops propagation), outside
pointerdown, focusin-outside and unmount focus handoff. Callers: `LocationFourDaySheet.jsx:421–430`
(scores) and `WindowFirstHeatStrip.jsx:1900–1907` (best bets, `--foot` variant).

---

## 2. Design

### 2.1 Server: `GET /api/aurora/nights` (B1)

A new, **role-free** read: any authenticated role (`/api/**` is already `authenticated()` in
`SecurityConfig`). It must live in its **own controller** (`AuroraNightsController`, mapped to the full
path `/api/aurora/nights`), because `AuroraController`'s class-level PRO/ADMIN gate would otherwise
reach it.

Response (a new record, `AuroraNightsResponse`):

```json
{ "nights": ["2026-10-25", "2026-10-26"] }
```

`nights` is sorted ascending, distinct, and holds every night **a Pro reader's window control would
offer an aurora row for right now**, minus the one client-only exception (`nightOnScreen`, which LITE
never has). The server computes it from the rules that already own each half — it does not re-derive
either:
- **stored nights still offered:** `AuroraForecastRunService.getAvailableDates()` (non-simulated by
  construction) filtered to `date >= currentNightDate()` — D-14's rule, server side;
- **the live night:** `currentNightDate()` when a real alert is live at MODERATE or STRONG — the server
  twin of the client's `auroraNightLive` / `isRealAlert` (`utils/auroraTab.js`,
  `utils/auroraBannerScores.js` `ALERT_WORTHY`). `AuroraStateCache` has no snapshot method, so the read
  order is the one `AuroraController.getStatus` (`:105–118`) and `AuroraHotTopicStrategy.detectTonight`
  (`:142–145`) use: read `getSimulatedData()` **first**; non-null → no live night; only then read
  `getCurrentLevel()` (null = QUIET) and `isActive()`. "The night has not ended" holds by construction
  on the server (`currentNight()` reads one clock instant and always returns a night whose `endsAt` is
  ahead); the client's `isStillCurrent` exists only because a client keeps a stale status — that
  staleness is F1's problem, handled there with `currentNightEndsAt` (below).
- Merge both halves as `LocalDate`s in a `TreeSet` (sorted, distinct), then format; do not rely on
  string order from `getAvailableDates()`.

Response also carries `currentNightEndsAt` (ISO instant, from the same `currentNight()` call) so a
client can drop the current night once it is over without refetching:

```json
{ "nights": ["2026-10-25", "2026-10-26"], "currentNightEndsAt": "2026-10-26T05:41:00Z" }
```

The controller carries **no** class-level role `@PreAuthorize` (or `isAuthenticated()` only).
`SecurityConfig` has no aurora matcher (its one role matcher is `/api/ask`), so a separate controller
does escape `AuroraController`'s gate; B1 pins both sides.

**What it reveals, and why that is acceptable.** Only *which nights* a Pro reader would see an aurora
row for. It carries no rating, no location, no Kp, no summary. ⚠️ Stored nights exist because **an admin
ran a scoring run** (`POST /api/aurora/forecast/run`, the only writer; the poller writes no results),
and an admin may run one on a quiet night, so a LITE reader can see "Aurora Tuesday night" on a night
the hot topic (tonight at MINOR+, tomorrow only at Kp ≥ 4) says nothing about. That is exact parity with
Pro (who gets a row for every offered stored date whatever its ratings) and leaks only a date; accepted
(§5 Q4). It must **never** include a simulated night: stored reads exclude `simulated` rows, and the
live half refuses any simulation; B1's tests pin it. Admin Rewind moves the `Clock` but not the live
`AuroraStateCache`, the same admin-only mix `/status` already has.

**Caching.** The body is identical for every reader and carries no personal data, so it is added to
`HttpCachingConfig.REVALIDATABLE_READ_PATHS` beside `/api/aurora/status`, with the same kind of comment.
It is not a personal path, so `personalDataPathsAreNeverFiltered` is untouched.

**Clock.** "Today" and the current night come only from `AuroraForecastRunService.currentNight()` /
`currentNightDate()` (which already read the injected `Clock`); no `LocalDate.now()`.

### 2.2 Client: the door (F1)

- **Fetch.** `api/auroraApi.js` gains `getAuroraNights()` (`GET /api/aurora/nights`; resolves
  `{ nights: [], currentNightEndsAt: null }` on any error). `MapView` fetches it **only for LITE**
  (PRO/ADMIN keep their existing paths unchanged and never call it), on mount, on a role change, and
  every 5 minutes while mounted (the aurora status poll's cadence; the ETag makes an unchanged answer a
  304). No new context; MapView already reads `role`.
- **Staleness.** Before taking the soonest night, drop a served night equal to the response's current
  night once `currentNightEndsAt` has passed by `appNow()` (the client's `isStillCurrent` test from
  `utils/mapDates.js`), so a door fetched before dawn does not keep last night's label until the next
  poll. A filter over a served list; nothing re-derived.
- ⚠️ **Mock factories.** ~73 test files mock `api/auroraApi.js` with an explicit factory (only ~6 use
  `importOriginal`). Every factory that renders `MapView` or `App` must gain
  `getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null })`, or LITE renders
  through them fail with Vitest's missing-export error. The builder runs the **full** suite.
- **Nothing enters `mapEvents`.** The door is not an EV row. `WindowControl`'s steppers, focus rescue,
  `otherWindow`, `landingRows`, `findEvIndex` and `onlyOneWindow` all index `events`; a synthetic entry
  would be stepped onto, selected and counted. `buildMapEvents`'s `isLite` omission stays exactly as it
  is (its doc gains one sentence pointing at the door).
- **One door, the soonest night.** `MapView` derives `auroraDoorNight = role === 'LITE_USER' ? nights[0]
  ?? null : null` — a select over a served list (the licensed filter/select class), no rating derived —
  and its label from the existing `nightLabel(date, todayStr, tomorrowStr)` through a pure
  `auroraUpsellLabel(nightLabel)`: `Tonight` → **"Aurora tonight"**, `Tomorrow night` → **"Aurora
  tomorrow night"**, `<Weekday> night` → **"Aurora Tuesday night"** (weekday keeps its capital; the
  accessible name is the label, so casing matters even though CSS uppercases the trigger). Pre-dawn the
  soonest night is yesterday's, labelled as the Pro row for it is (`Saturday night`). One door, not one
  per night: the upsell is "aurora is Pro", not a list of forecasts. Keeping the label on the client is
  deliberate: a served "tonight" word inside an ETag-shared body would change at midnight.
- **Where it sits.** As a footer **after** the listbox, never inside it (a listbox's children must be
  options):
  - desktop: inside `wf-win-menu`, after `wf-win-listbox` **and after** the drilldown row `wf-win-more`,
    in a new `wf-win-aurora-door` wrapper;
  - phone: inside `MapPeekWindowsSection`, after `wf-map-peek-wlist` and its drilldown row, **before**
    the `topics` strip (so the door sits next to the aurora hot topic), in `wf-map-peek-aurora-door`.
  Both get a new optional prop (`auroraDoor`: `{ label } | null`), so a caller that passes nothing
  renders exactly what it does today.
- **Placement: a new inline modifier.** ⚠️ No inline variant exists today: `.wf-pro-upsell` is always
  `position: absolute` (`index.css:4261–4268`), and `--foot` changes only width and z-index. Inside
  `.wf-win-menu` (`max-height` + `overflow-y: auto`) and the peek scroller an absolute popover off the
  last item is clipped. F1 adds `.wf-pro-upsell-door--inline .wf-pro-upsell { position: static; width:
  100%; margin-top: 6px; box-shadow: none; z-index: auto; }` beside `--foot`, applied on both hosts. The
  door's open-time `scrollIntoView({ block: 'nearest' })` then only scrolls the menu itself, which is the
  wanted effect; check in the browser that it moves no outer scroller (`WindowControl.jsx:80` warns).
- **Reachability.** `WindowControl` renders nothing with zero events (`:395`), so a LITE reader with no
  window rows gets no door; with one row the steppers are disabled but the pill still opens the menu. On
  phone the desktop menu never opens (`pillOverride`), so the two doors never mount together.
- **Interaction with the menu.** Read against the code, the menu closes only on a row select, a stepper,
  Escape, an outside `mousedown` (`useOutsideDismiss`, which ignores presses inside the control) and the
  Load/drilldown rows; there is no focusout close. Two real conflicts, both fixed in F1:
  1. **Arrow keys.** `WindowControl`'s root `onKeyDown` (`:366–390`) turns ArrowLeft/ArrowRight from
     any descendant into `step(±1)`, which closes the menu and selects another window, unmounting the
     door with focus inside. The handler must ignore arrow keys whose target is inside
     `.wf-win-aurora-door` (as it already exempts `onBetter`, `:377`).
  2. **Escape with the popover closed.** Escape on the door's trigger closes the menu (correct), but the
     door's unmount handoff then focuses the nearest focusable ancestor, which is `.wf-map-tab`
     (`tabIndex={-1}`), not the pill. When Escape closes the menu with focus inside the menu, focus the
     pill (`pillRef`) first, as `loadOlder` and `pressBetter` do.
  Escape with the popover **open** closes only the popover (the door stops it before `WindowControl` or
  `handleMapPaneKeyDown` sees it); a second Escape closes the menu. `utils/mapForeignModal.js` counts only
  `aria-modal="true"` dialogs outside the pane, so the door's non-modal popover changes no Map rule.
  Phone has no key handler in the peek sheet; nothing to exempt there.
- **Copy (placeholder until the owner signs it off, like the scores and best-bets copy).** New
  constants in `ProUpsell.jsx`:
  - `AURORA_UPSELL_HEADING = 'Aurora forecasts'`
  - `AURORA_UPSELL_BODY = 'See where the aurora should show, rated at each dark-sky location, with
    how far south it should reach.'`
  - the label is built per night (above), so there is no `AURORA_UPSELL_LABEL` constant; export a small
    pure `auroraUpsellLabel(nightLabel)` helper beside the constants instead.
  No link, no button to buy, no "upgrade" word.
- **Test ids.** `testIdPrefix="wf-aurora-upsell"` on both hosts (one door is mounted at a time: desktop
  and phone never render together).
- **Phone-only stays phone-only.** The desktop door is in `WindowControl`'s menu, the phone door in the
  peek sheet; neither changes the other width's DOM. PRO/ADMIN DOM is unchanged at every width (pinned).

### 2.3 What does not change

- PRO/ADMIN: every aurora surface, request and test.
- The aurora hot topic (free, every role) and the two-question rule.
- `AuroraBanner` stays Pro-only (§5 Q1).
- Ask (`canSeeAurora: isPro`; LITE has the Ask upsell already).
- The unreachable viewline upsell chip is left alone in this series (§5 Q2).

---

## 3. Phases

One PR per phase, each from the updated `main` after the previous merged. Sonnet builds; the planner
reviews the diff read-only; CI green and Codex 👍 on the head commit before squash-merge.

### B1 — `GET /api/aurora/nights` (backend)

- `AuroraNightsController` + `AuroraNightsResponse` + a service method (in `AuroraForecastRunService`,
  `offeredNights()`, or a small `AuroraNightsService` if that class is the wrong home — the builder
  decides and says why), Javadoc on every public type and method.
- `HttpCachingConfig.REVALIDATABLE_READ_PATHS` gains `/api/aurora/nights`.
- **Tests** (`docs/engineering/test-improvement-standards.md`):
  - controller: `AuroraNightsControllerTest extends AbstractControllerTest` (`@SpringBootTest` +
    MockMvc; the base already mocks `forecastRunService` and `stateCache`), copying
    `AuroraControllerTest.java:64–91`: `@WithMockUser` LITE, PRO and ADMIN all get 200; no user gets
    401; plus a guard that `AuroraController`'s `/status` still 403s LITE. If the builder adds a new
    service bean, it is a **real** bean over the base's mocks — do not add a `@MockitoBean` to the shared
    base (that changes every controller test's context);
  - service, with a fixed `Clock`: stored dates before the current night are dropped, the current night
    and later are kept; pre-dawn (00:00–dawn) keeps *yesterday's* night; the BST 00:00–01:00 window; the
    live night is added only when active + MODERATE/STRONG + no simulation; MINOR adds nothing; a null
    level adds nothing; an **active MODERATE/STRONG simulation** adds nothing; a live night already
    stored appears once; output sorted; `currentNightEndsAt` is the `currentNight()` end;
  - `HttpCachingConfigTest.whitelistedReadPathsAreFiltered`'s `@ValueSource` gains the path;
    `HttpCachingIntegrationTest` gains a LITE case (200 with `ETag` and `Cache-Control: private,
    no-cache`, then 304 on revalidation).
- Docs in the same PR: `reference/api-endpoints.md` (reword the Aurora heading at `:190` to "PRO_USER/ADMIN
  except `GET /api/aurora/nights`, any authenticated role" and add the endpoint), `reference/aurora.md`
  (one line), and a pointer in `utils/auroraTab.js`'s doc is F1's (frontend file).
- `changelog.d/20261025-aurora-nights-endpoint.md`.
- Gates: the backend build ladder through `clean verify` (jacoco per-class 80%, spotbugs, checkstyle).

### F1 — the Lite aurora door (frontend)

- `auroraApi.getAuroraNights`, the LITE-only fetch in `MapView`, `auroraDoorNight`, the `auroraDoor`
  prop on `WindowControl` and `MapPeekWindowsSection`, the copy constants and `auroraUpsellLabel`.
- **Tests** (`docs/engineering/frontend-test-standards.md`):
  - `auroraApi`: success, error → `[]`;
  - `ProUpsell`/label helper: `Tonight` → "Aurora tonight", `Tuesday night` → "Aurora Tuesday night";
  - `WindowControl`: with `auroraDoor` the menu shows the door after the listbox and `wf-win-more`
    (not inside the listbox; its children are all `option`s); without it the DOM is unchanged; opening
    the popover and pressing Escape closes the popover and leaves the menu open; ArrowLeft/ArrowRight on
    the trigger and on "Not now" leave `onSelect` uncalled and the menu open; Escape on the closed
    trigger closes the menu and focuses the pill; the door is not counted in the row count; the door
    wrapper carries the `--inline` modifier;
  - `MapPeekSheet`: the door after the list and before `topics`; without the prop the DOM is unchanged
    (`MapPeekSheet.test.jsx:236`'s button count stays 2);
  - label helper: Tonight, Tomorrow night, a weekday night, the pre-dawn (yesterday) case;
  - `MapView` (LITE): nights `[today]` → "Aurora tonight"; `[]` → no door; fetch error → no door; a
    refetch that adds a night shows the door; `currentNightEndsAt` passing drops the current night;
    PRO/ADMIN never call `/api/aurora/nights` and never render the door;
  - every existing `auroraApi` mock factory updated; the **full** suite green;
  - `mapEvents.test.js:141` / `:684` stay green unchanged (LITE still gets no aurora *row*); the module
    doc's sentence is updated.
- Docs in the same PR: `reference/map-tab.md` (the window control's LITE door), `reference/roles-and-gating.md`
  (the Freemium split paragraph lists **aurora forecasts** under PRO and ADMIN, and the door as the
  LITE surface).
- `changelog.d/20261025-aurora-lite-door.md`.
- Gates: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`.
- UI review: an adversarial read of the diff (a11y of a door beside a listbox, the menu's close rules,
  phone/desktop isolation, PRO unchanged) before merge.
- Browser: a LITE sign-in is needed for a live check; this session cannot sign in, so the check is owed
  to the owner (§5).

---

## 4. Risks

- **The window control's keys reach the door.** Arrow keys step the window and Escape strands focus
  (§2.2, both fixed and pinned in F1). Any future key the root handler grows must exempt the door too.
- **Clipping.** The house popover is absolute; F1's `--inline` modifier is what keeps it inside the
  scroll boxes. Only a real browser proves it (owed, §5).
- **Drift between the server's offered nights and the Pro rows.** The server reuses the two rules that
  own them (`getAvailableDates` + D-14's `>= currentNight`, and the live-alert test). If either rule
  changes, B1's tests are where the drift shows; the `auroraTab.js` doc gains a pointer to the server twin.
- **A Lite reader with no aurora night sees no door.** Deliberate: no night, nothing to upsell (the
  dead-control ban). The hot topic still says when something is happening.

## 5. Owner questions (not blocking)

- **Q1.** A Lite `AuroraBanner` (level and "look north" only, no field, no best site, no viewline)?
  It needs a role-free status, a bigger change than this series; parked.
- **Q2.** The viewline upsell chip is dead for LITE and says "upgrade". Delete it, or reword it into the
  house pattern if Q1 ever gives LITE a status?
- **Q3.** Sign off the door's copy (§2.2).
- **Q4.** Accept that a stored admin run on a quiet night gives LITE a door for that night (§2.1)? The
  alternative is serving only nights the hot topic also names, which would make the door disagree with
  the Pro rows.
- **Owed:** a LITE browser check of the door on desktop and phone once F1 is released.
