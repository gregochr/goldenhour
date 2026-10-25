# Aurora for Lite, part 2: a Lite banner, the viewline door, and quiet nights — plan

**Status: PLAN ONLY (2026-10-25).** Reviewed the same day by one read-only lens over both sides;
its findings (provider effect deps, the strip's clipping, the alert live region, the banner root, one gate
helper, the upward popover's CSS, N1's Javadoc and query name, L1's single live-alert definition) are
folded in below. The owner asked for the plan now and the build later; no phase starts
until the owner says so.

Follows `aurora-pro-door-plan.md` (plan #1414, B1 #1415 `GET /api/aurora/nights`, F1 #1418 the Lite window
door), whose §5 questions the owner answered on 2026-10-25:

| Q | Owner answer | Here |
|---|---|---|
| Q1 Lite banner? | **Yes**: alert level and "look north" wording, with a Pro door where Pro's field, best site and viewline are | L1 + L2 |
| Q2 the dead viewline chip | **Reword it into the house pattern** (a `ProUpsellDoor`) | L3 |
| Q3 door copy | **Approved as is**, and reused for Q1 and Q2 | L2, L3 |
| Q4 a quiet-night admin run gives Lite a door | **Change Pro too**: a quiet run gives nobody a night row | N1 |

---

## 1. Where we are (main at `122d9a26`)

**Server.**
- `GET /api/aurora/status` (`AuroraController.java:113–204`) is PRO/ADMIN (class-level `@PreAuthorize`,
  `:37`). It reads the simulation first (`:115`), then level, active, counts, trigger, `activeSince`, then
  `currentNight()`, then best-effort NOAA enrichment. Fields (`AuroraStatusResponse.java:63–82`): level,
  hexColour, description, active, eligibleLocations, darkSkyLocationCount, clearLocationCount, kp,
  forecastKp, triggerType, ovationProbability, bzNanoTesla, dataSource, updatedAt, simulated, detectedAt,
  gScale, currentNightDate, currentNightEndsAt.
- Stored aurora nights: `AuroraForecastResultRepository.findDistinctForecastDatesExcludingSimulated()`
  (`:87–89`) via `AuroraForecastRunService.getAvailableDates()` (`:488–492`). Its only callers are
  `AuroraForecastController.getAvailableDates` (Pro/Admin `available-dates`) and `AuroraNightsService`.
  Rows carry `stars` (1–5, NOT NULL), `triaged`, `source`. A triage-rejected location is always stored as
  1★; a night with Kp < 1 or no eligible locations stores no rows already.

**Client.**
- `AuroraStatusContext.jsx:34–120` is **not role-gated**: a LITE client polls `/api/aurora/status` every
  5 minutes and on focus, gets 403, and stores `null` (`auroraApi.js:12–22`).
- Consumers that are **not** role-checked and would misbehave if LITE suddenly had a status:
  `AuroraBanner` (its `useAuroraScores` calls `/api/aurora/locations` and `/forecast/results`, both 403;
  its map door would open an aurora night LITE has no row for — a dead door); MapView's
  `getAuroraLocations` effect (`:2175–2192`, 403 on every poll); `resolveAuroraNight` (changes LITE's
  small-hours night bookkeeping in App and MapView). Role-checked already: `auroraTabOffered`,
  `viewlineEnabled`, `auroraAvailable`, the available-dates and nights fetches, `auroraDoor`, Ask.
- **The viewline chip** (`MapView.jsx:1775–1776`, rendered `:7052–7072`, testids `viewline-upsell-chip`
  and `viewline-upsell-dismiss`): "Aurora viewline available — upgrade to Pro" plus a ✕, in
  `.wf-map-chrome-bl` beside the legend. Unreachable for LITE (status is null) and its copy breaks the house
  rule.
- **AuroraBanner** (`AuroraBanner.jsx`, 816 lines): ≥640px a card (`AuroraCard`, `:468–584`: thumb slot
  with the field or Kp meter, pill, when-line, title, best/overcast line, viewline sr text, CTA, ✕; the
  whole card is the activation target); <640px a 56px strip (`AuroraStrip`, `:604–683`). Gates: null
  status, not `ALERT_WORTHY` (MODERATE/STRONG), dismissed at this level (session state, re-shows on
  escalation), `hiddenByOvercast` (every dark-sky site overcast and not STRONG, never for a simulation,
  `:158–180`).

---

## 2. Design

### 2.1 N1 — a quiet run gives nobody a night row (backend)

A stored night counts only if **its best stored rating is at least 3★** (the owner's scale: 3★ "only if
close to home", the lowest star worth a trip). One repository query replaces the distinct-dates one:

```sql
SELECT r.forecastDate FROM AuroraForecastResultEntity r
WHERE r.simulated = false
GROUP BY r.forecastDate HAVING MAX(r.stars) >= :minStars
ORDER BY r.forecastDate
```

- `getAvailableDates()` uses it, so **Pro and Admin map rows, `available-dates` and `/api/aurora/nights`
  all change together** (the parity the door depends on). The threshold is a named constant
  (`MIN_OFFERED_NIGHT_STARS = 3`) next to the query's caller, with a Javadoc line naming the owner's scale.
- **The live night is unaffected**: a real MODERATE/STRONG alert still gives its night a row (Pro: the
  live-night row; Lite: `AuroraNightsService`'s live half), whatever a stored run said.
- ⚠️ **Admins lose map browsing of quiet runs.** The owner chose "nobody" (Q4); the run's own response in
  `AuroraForecastModal` still shows a quiet run's results to the admin who ran it, and
  `GET /api/aurora/forecast/results?date=` still answers for any date. Recorded, not mitigated.
- The old query method is deleted (its only caller is `getAvailableDates`, grepped). The new one is
  `findForecastDatesWithMinStarsExcludingSimulated(@Param("minStars") int minStars)`. Update the Javadoc
  that describes the old meaning: `AuroraForecastResultRepository.java:83–84` ("determine whether the
  Aurora toggle should be shown") and `AuroraForecastRunService.java:52` and `:488`. Do not touch
  `AstroConditionsController.getAvailableDates` (a different method).
- **Tests:** repository (`AuroraForecastResultRepositoryTest`): a night whose best is 2★ is excluded, 3★
  included, a mix of triaged 1★ and a Claude 4★ included, simulated rows ignored even at 5★, ordering
  (rewrite `:97–105`); the service-level mocks of `getAvailableDates` return `List<String>` and are
  unaffected; add an `AuroraForecastRunServiceTest` case that `getAvailableDates` passes the constant.
- Docs: `reference/aurora.md` (the rule), `reference/api-endpoints.md` (`available-dates` and `nights`
  now mean "nights worth a row").

### 2.2 L1 — `GET /api/aurora/alert`, the role-free alert (backend)

Same shape of decision as B1's nights endpoint: its own controller (`AuroraAlertController`, full path
`/api/aurora/alert`, **no** role `@PreAuthorize`), any authenticated role, ETag-revalidatable.

```json
{ "level": "MODERATE", "active": true, "triggerType": "forecast", "allOvercast": false,
  "currentNightDate": "2026-10-25", "currentNightEndsAt": "2026-10-26T05:41:00Z" }
```

- **Fields, and only these.** `level` (null → QUIET), `active`, `triggerType` (so the Lite banner can say
  "Forecast · tonight" vs "Active now", as Pro's does), `allOvercast` (below), and the two night fields
  (from one `currentNight()` call). No Kp, OVATION, Bz, G-scale, counts, `detectedAt` or description:
  the owner asked for level-only, and every number the Pro banner shows stays Pro.
- **Simulation:** read `getSimulatedData()` first, as `/status` and `AuroraNightsService` do; a
  simulation answers **QUIET, inactive** — never `simulated: true`. Simulations never reach user surfaces,
  and Lite has no 🧪 preview path.
- **`allOvercast` moves the banner's overcast gate server-side** so the Lite banner obeys it without the
  Pro-derived counts: `darkSkyLocationCount > 0 && clearLocationCount != null && clearLocationCount == 0`,
  exactly `isAllOvercast` in `AuroraBanner.jsx:158`. A boolean, not the counts. The client applies
  `hiddenByOvercast`'s "unless STRONG" itself (it reads `level`).
- **Simulation answers QUIET, `active: false`, `triggerType: null`.** A null `currentNight()` (possible
  from a stub, `AuroraController.java:131`) is handled the way `/status` handles it. "Active and
  alert-worthy" is defined once: extract `AuroraNightsService`'s live-alert read (`:61–66`) into the new
  service (or a shared package-private helper) and have both use it, not a third copy.
- One service method (`AuroraAlertService.liteAlert()` or similar, real bean in tests over
  `AbstractControllerTest`'s mocks, never a new shared `@MockitoBean`), reading the state once in the
  `/status` order. No NOAA calls (nothing it serves needs them), so it is cheap.
- `HttpCachingConfig.REVALIDATABLE_READ_PATHS` + `HttpCachingConfigTest` + a LITE case in
  `HttpCachingIntegrationTest`.
- **Tests:** controller LITE/PRO/ADMIN 200, anonymous 401, `/status` still 403 LITE; service: null level →
  QUIET, active simulation at STRONG → QUIET/inactive, allOvercast true/false/unknown-clear (false), trigger
  type mapping matches `/status`'s, night fields from one `currentNight()`.
- Docs: `reference/api-endpoints.md` (Aurora heading's exception list gains `/alert`), `reference/aurora.md`.

### 2.3 L2 — the Lite banner (frontend)

- **Provider.** `AuroraStatusContext` reads `role` (`useAuth`; verified: `AuthProvider` wraps it,
  `App.jsx:1084` → `:181`, and role is read from storage at mount so it is stable per mount) and for **LITE_USER** stops polling `/api/aurora/status` altogether (no more 403 every 5 minutes) and
  polls `GET /api/aurora/alert` instead, on the same cadence, focus refetch and in-order application. It
  exposes it as a **new** value, `liteAlert`; `status` stays `null` for LITE. Every existing consumer
  therefore sees exactly what it sees today; only code that asks for `liteAlert` changes. PRO/ADMIN:
  unchanged, never call `/alert`. ⚠️ The poll effect (`:100`) has `[]` deps, an unkeyed request counter and
  a catch that writes nothing; its own comment (`:55–62`) says a new dependency needs a per-run `cancelled`
  flag. Read `role` outside the effect, key the effect on `[role]` with a per-run `cancelled` flag, and keep
  one `requestedRef`/`appliedRef` ordering for whichever endpoint is polled. The night-end timer (`:70`) keys
  on `liteAlert?.currentNightEndsAt ?? status?.currentNightEndsAt`. Add `liteAlert: null` to the
  `createContext` default and the `useAuroraStatusContext` JSDoc.
- **One gate helper.** `utils/auroraLiteAlert.js` exports `isLiteAlertShown(liteAlert, now)`: active,
  `ALERT_WORTHY` (`utils/auroraBannerScores.js:20`, not a fourth copy), `isStillCurrent(currentNightEndsAt,
  now)` (`mapDates.js:264`), and not `allOvercast` unless STRONG. L2's banner, L3's chip and the tests all
  read it.
- **`AuroraBanner`.** When `status` is null and `liteAlert` is alert-worthy (`active`, MODERATE/STRONG,
  night still current by `isStillCurrent(currentNightEndsAt)`), not dismissed at this level, and not
  `allOvercast && level !== 'STRONG'`, render a `LiteAuroraBanner` — a separate small component, not new
  branches through `deriveModel`:
  - **card (≥640px):** the same frame, severity colour and pill (`levelWord` from the same `SEVERITY`
    table), the when-line ("Forecast · tonight" / "Active now" from `triggerType`), the title **"Look
    north, low on the horizon, after dark."**, and in the thumb slot's place a `ProUpsellDoor`
    (label **"Aurora forecast"**, the approved `AURORA_UPSELL_HEADING` / `AURORA_UPSELL_BODY`,
    `testIdPrefix="aurora-banner-upsell"`), then the ✕ with the same `dismissedLevel` rule;
  - **strip (<640px):** glyph, headline (levelWord + "Aurora tonight" / "Aurora active now"), detail
    "Look north, low", the door (inline variant) and the ✕;
  - **no card-level activation** (a button inside a button is invalid; the door is the only control
    besides ✕), **no map door** (LITE has no aurora row to open — the window list's door is how the Map
    upsells), no scores, field, viewline or Kp requests, `role="alert"` kept.
  - Popover placement: ⚠️ the strip (`.wf-aurora-strip`, `index.css:13391`) is a single flex row with
    `overflow: hidden`, so neither `--inline` nor the absolute popover works inside it (review finding).
    The Lite strip wraps (`flex-wrap` on a Lite modifier) so the door's popover opens on its own line
    below, or the popover is rendered after the strip, outside the clipped box — the builder picks one,
    pins it with a jsdom cascade assertion, and the browser check confirms. Check the card
    (`.wf-aurora-card`) for clipping too. Escape / outside press inside the banner close only the popover.
  - **Live region.** Both layouts are `role="alert"`; an interactive door inside an alert region is
    announced on every mount. Keep `role="alert"` on a text-only wrapper (pill, when-line, title) and put
    the door and ✕ outside it.
  - **Where the branch goes.** `AuroraBanner` calls `useAuroraScores`, `useAuroraViewline` and
    `useWindowFirstBriefing` before its early returns, so the Lite branch is chosen **above** it: a small
    `AuroraBannerRoot` at `App.jsx:820` renders `LiteAuroraBanner` when `liteAlert` should show and
    `AuroraBanner` otherwise. No conditional hooks. Reuse the exported `SEVERITY` (`:34`); `hiddenByOvercast`
    and `deriveModel` take a Pro-shaped status, so the Lite gate is its own helper (below). The card/strip
    split reuses the existing `useIsMobile` branch.
- **Copy.** The door copy is approved (Q3). The title "Look north, low on the horizon, after dark." and the
  strip detail "Look north, low" are **new placeholder copy**, matching the aurora hot topic's "look due
  N, low" — owner to confirm at the browser check.
- **Tests:** provider (LITE polls `/alert` not `/status`; PRO/ADMIN never `/alert`; order and focus
  refetch); banner: LITE alert-worthy → Lite card / strip with the door, pill and when-line; QUIET/MINOR,
  inactive, ended night, dismissed, overcast-not-STRONG → nothing; overcast + STRONG → shown; no request to
  `/locations`, `/forecast/results`, `/viewline*` for LITE (assert the mocks were never called); PRO/ADMIN
  banner unchanged (existing suites green). `AppAuroraBannerDoor.test.jsx:284–290` ("a LITE reader has no
  aurora status, so no banner") is rewritten to the new truth. Every `auroraApi` mock factory that renders
  `App` gains `getAuroraAlert`.
- Docs: `reference/aurora.md` (banner section), `reference/roles-and-gating.md` (`:43`'s "LITE's one aurora
  surface is a Pro door" becomes: Lite sees the alert level in the banner, plus the window and banner
  doors), `reference/map-tab.md` if it repeats that sentence.

### 2.4 L3 — the viewline chip becomes a Pro door (frontend)

- `showViewlineUpsell` reads `liteAlert` (LITE, alert-worthy, not ended) instead of `auroraStatus`, so it
  can show for the first time.
- The chip becomes a `ProUpsellDoor`: label **"Viewline"**, the approved heading/body,
  `testIdPrefix="viewline-upsell"`. The ✕ stays beside it (the existing local dismissal: a persistent
  chip on the map during an alert must be closable). No "upgrade" text anywhere.
- **Desktop and phone alike.** The chip mounts in `.wf-map-chrome-bl` at both widths; that is the
  existing behaviour and stays so. Drop its inline `style={{fontSize:'11px'}}` (an inline-style
  violation) for a class.
- **Placement.** It sits at the map's bottom-left (`.wf-map-chrome-bl`), so the house popover (absolute,
  opening downwards) would open off the map. Add an upward modifier
  (`.wf-pro-upsell-door--up .wf-pro-upsell { top: auto; bottom: calc(100% + 6px); }`) beside `--foot`
  and `--inline`, plus the `--foot` width rule for `--up` (the door shrinks to its trigger inside the
  flex column, `index.css:3896`); check clipping against `.wf-map-chrome-bl`'s ancestors and the tide strip
  that mounts last in the same column. Desktop and phone both mount `.wf-map-chrome-bl` today only when something is in it
  (`MapView.jsx:7012`); the chip keeps its existing mount rules and stack order with the legend
  (`MapViewHeat.test.jsx:1714–1735`, not `absolute`).
- Escape inside the open popover closes only the popover; the map pane's Escape rules are untouched
  (`mapForeignModal.js` ignores non-modal dialogs).
- **Tests:** `MapViewViewline.test.jsx:262–274` and `MapViewHeat.test.jsx:1714–1735` move to `liteAlert`
  and the new testids; `MapLabels.test.jsx:1162` and `mapPhoneChromeCascade.test.jsx:575–583` updated if
  the testid or width changes; PRO never renders it.

---

## 3. Phases and order

One PR per phase, Sonnet builds, planner reviews read-only, CI green + Codex 👍 on head, squash-merge.

1. **N1** (backend, independent).
2. **L1** (backend, independent of N1; may build in parallel, merges after N1 only to keep the history
   readable).
3. **L2** (frontend, needs L1 merged — it calls `/alert`).
4. **L3** (frontend, needs L2's `liteAlert`).

Gates: the backend build ladder through `clean verify`; the frontend's four commands. UI phases (L2, L3) get
the adversarial review (keyboard/a11y and isolation lenses) before the PR.

## 4. Risks

- **N1 hides runs an admin may want on the map.** Accepted (Q4). If it bites, an ADMIN-only override is a
  later, separate change.
- **The Lite banner competes for the most valuable slot.** It shows only at MODERATE/STRONG, obeys the
  overcast gate, and dismisses per level — the same rules as Pro's.
- **`liteAlert` drift from `/status`.** The service reads the same state in the same order; L1's tests pin
  the trigger mapping and the overcast rule against `/status`'s.
- **Copy.** Only the banner title / strip detail is new; owner confirms.

## 5. Owed

- The owner's go-ahead to build (this plan is paused by request, 2026-10-25).
- After release: a Lite browser check of the window door (#1418), the banner (L2) and the viewline door
  (L3), desktop and phone; the banner title copy.
