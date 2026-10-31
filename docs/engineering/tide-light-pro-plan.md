# The tide at the light becomes Pro and Admin only — plan

**Owner decision (2026-10-26).** Tide information splits on one line:

- **Free for every role: how big and when.** Tide state and direction, high- and low-water *times*,
  range, range anomaly, rank and percentile, spring/king (lunar) classification, run heights, sea state,
  surge, and whether a high water falls in daylight. These are tide-table facts.
- **Pro and Admin: the tide relative to the light.** Any statement of where high or low water falls
  against sunrise, sunset or the golden/blue hour: "HW 08:05, 26m before" sunrise, "no high water within
  an hour", "nearest HW 05:38, 1h26 before", the run's best day for high water at the light, "Rising
  tide — high at 07:12 (20 min after sunrise)", "tide aligned with sunrise at 47 of 61", big seas'
  "49 min before sunset", and a tide-triage reason that says the tide misses the location's light.
  This is the planning PhotoCast does for the reader.

Heights at the light and the per-location tide match are already Pro (`tide-pro-gate-plan.md`,
#1436). **Admin ⊇ Pro** on every path: a gate is "positively PRO_USER or ADMIN, else Lite" (fail closed),
and every test asserts Admin gets the Pro result.

**Why (owner, devil's advocate):** a photographer who knows St Mary's Lighthouse needs high water reads
"sunrise · HW 08:05, 26m before · Wed 30" on a Coming up card as the tide match, 58 days ahead, free.
The same holds for the Plan row's offset and the four-day sheet's rising-tide badge. Tide and sunrise
times are public, so Pro sells the convenience of being told, as it does with the written why; the
client-side paths that remain (§5) are accepted on that basis.

**The two-question rule holds.** Hot topics and Coming up keep answering "what is happening" for every
role: every topic, card, size fact, clock, map event row and jump stays. Only the clause relating the
water to the light gives way: to a worded Pro door where it was the reader's main point (Coming up), and
to a light-free sentence or nothing where it was a qualifier.

**Reviewed 2026-10-26** by four read-only lenses (server shapers; caching and rollout; UI and house
pattern; missed paths). Their findings are folded in: the badge-detail fallback (F1, B1), big seas
narrowed so no map row or jump is lost (A8, Q3), fail-closed hidden carriers (B1, B2), the coincidence
label carrier (B2), the per-entry withheld marker and door placement (B2, F2), primitive fields and
missing withers (B1, B2), wire and reflection tests for every reshaped record, and the tide-triage
reason (A9).

---

## 1. Inventory (main after #1436)

`B` = `backend/src/main/java/com/gregochr/goldenhour/`, `F` = `frontend/src/`.

### 1a. Drawn for a Lite reader today

| # | Served by | Field | Drawn by | Lite sees |
|---|---|---|---|---|
| A1 | `TideAlmanacSource.toEvent` → `ComingUpAssembler.tideFacts`/`eventWaterFacts`/`eventOffset` | `/api/almanac` `ComingUpEntry.facts[]` (role-free, ETag-revalidated) | `WindowComingUpEntry.jsx:542-565` | "sunrise · HW 07:40, 10m before · Wed 28"; "sunset · no high water within an hour · nearest HW 05:30, 2h20 before · Wed 28" |
| A2 | `ComingUpAssembler:994-1003` (merged card hosted by a supermoon; host family `sun-moon`) | same | same | same |
| A3 | `ComingUpAssembler.coincidenceFacts`/`peakDayAlignedClock` `:1048-1109` | `/api/almanac` `coincidence[].factsLabel` (one string) | `WindowComingUpEntry.jsx:504-530` | "… · top 2% of the year · HW 15:48" (clock appended only when aligned with a light) |
| A4 | `WindowTideRollupBuilder.rollup:301-308` | `/api/briefing` `window.tide.nearestOffset` (`BriefingWindowTide.forLite` keeps it) | `windowFirstRows.js:153-156` → `WindowSheetDialog` → `WindowAttributeRow` | "HW 19:28 · **1h43 before sunset**" |
| A5 | `PlanWindowProjector.badgeFor:757-770`, `nearestWaterPhrase:786-791` | `/api/briefing` `window.badges[].detail`, `.eventTime` (spring/king) | `WindowTopicRows.jsx:121,148`; heat-strip chip title `WindowFirstHeatStrip.jsx:1648` | "King tide — HW 06:18 · 58m after sunrise" |
| A6 | `KingTideHotTopicStrategy.alignmentInfo`/`buildKingTideDetail` `:279-296,493-507`; Spring `:185-191,290-306` | `/api/briefing` `hotTopics[].detail`; the aligned window's `badges[].detail`; **and the fallback** `badge.detail \|\| topic.detail` (`WindowTopicRows.jsx:121`) | same as A5 | "tide aligned with sunrise at 47 of 61 coastal locations", "no tide alignment — …", "tide alignment already passed — …" |
| A7 | (client-derived) `ForecastDtoMapper` serves `nextHighTideTime` + `solarEventTime`, both free | `/api/forecast/{id}` | `forecastDetailFacts.js:62-70,165-175` → `LocationSheetFacts.jsx:52` (four-day sheet; `isPro` already reaches it, `:113`) | "⚠️ Rising tide — high at 07:12 (20 min after sunrise)" |
| A8 | `BigSeasBuilder` → `BigSeasDay.alignedEvent/gapMinutes/gapSide`, `BigSeasStructure.aligned` | `/api/briefing` `bigSeas[]` | `bigSeas.js:109-120`, `bigSeasChips.js:51,66,172-206`, `mapEvents.js:982-997` | "high water 16:58, 49 min before sunset", "On the sunset ›", the aligned ring and the ⚡ on the aligned light row |
| A9 | `TideAlignmentEvaluator:70-73` (only when the TIDE_ALIGNMENT optimisation strategy is on; prod setting unverified) | `triageReason` `TIDE_MISALIGNED` + `triageMessage` "No high tide aligned with golden/blue hour window", on `/api/forecast/{id}` (`ForecastDtoMapper:554-555`) and the scores rows (`/api/briefing/evaluate/scores`, `WindowFirstBriefingContext.jsx:771-772`) | `forecastDetailFacts.js:41,246-250` → `LocationSheetFacts.jsx:54` | "…not evaluated by Claude due to tide not aligned with location preference" |

### 1b. On Lite's wire, drawn for nobody (strip anyway: never sent and obscured)

| # | Field | Note |
|---|---|---|
| B1 | `window.tideFacts[].tideOnTheLight`, `.nearestSolarOffsetPhrase` (`LocationTideFact.forLite` keeps them) | the client builds a populated index for Lite (`WindowFirstMapPane.jsx:450`, `HeatmapGrid.jsx:760`) but nulls it before any light-reading consumer (`MapView.jsx:1201`, `WindowFirstShell.jsx:3147`); HeatmapGrid reads only `.aligned`, already null |
| B2 | `BriefingSlot.TideInfo` `nearestSolarOffsetMinutes`, `nearestExtremeKind`, `tideOnTheLight`, `nearestSolarOffsetPhrase` | slot tide is null on the live path (`ServedBriefingAssembler.stripSlotTide`); shape for the shaper's own guarantee, as #1432 did |
| B3 | `hotTopics[].tideRun` (`verdict`, `aligned`, `bestAligned`, `alignedEvent`, `alignmentPhrase`, `roster.*Aligned`, `sunrise`/`sunset`, `tides[]` clocks) | no client reader (`TideRunRow` deleted) |
| B4 | `hotTopics[].expandedDetail.tideMetrics.sunriseAlignedCount/sunsetAlignedCount` (primitive `int`) | no reader |
| B5 | `hotTopics[].eventType`/`eventTime` resolved to the aligned light for tide topics (`HotTopicEventEnricher.resolveTideEvent`); `window.badges[].eventTime` on the aligned window only | no visible reader for tide types; the badge's non-null `eventTime` says which window is aligned |
| B6 | `BriefingSlot.standdownReason` "Tide mismatch" (`BriefingVerdictEvaluator:95,387-388`, kept by `BriefingSlot.forLite:270-271`) | no reader; the verdict demotion itself is the accepted "the rating folds in the tide" case (§5) |

### 1c. Already safe or out of scope (checked by the missed-paths lens)
- Map tide teaser, phone peek, Spring/King chips and their four-day rows: already Lite doors.
- Map callout `TideFitBlock`: `mapTideIndex` null for Lite.
- Digest, notifications, email, region `tideHighlights`/`summary`, `BriefingHeadlineGenerator`,
  `HotTopicGlanceDecorator`'s tide glance (range only), `CoastalTideFactsBuilder` (clocks, run heights),
  surge offsets (measured from high water), `ComingUpTideSparkline` (no light marker), the stripped
  `sunriseHw*` meta keys, `/api/tides` (Pro since #1436), the PWA (never caches `/api`).
- Ask: Pro-only (`SecurityConfig:90`, `AskController` `@PreAuthorize`); it reads the unshaped feed and briefing.
- Run heights ("high water 5.8 m", `TideRunDay.highWater`): run size, free (Q8).
- **Big seas' daylight high water** (`daylightHighWater`, `daylightStart`/`daylightEnd`): "the HW falls
  in daylight" is a *when* fact, and it drives the Map's daylight High-water row, the chip's "Big seas ·
  HW 12:40", the strip's HW tick, the HW-row callout and the compact line's jump. Kept free (Q3).

---

## 2. Approach and order

**Order: F1 → B1 → B2 → F2.** Each is one PR from updated `main`; a release is always a prefix of main
(`deploy.yml` deploys both images from one tag), and every prefix is complete for Lite, Pro and Admin:
- the briefing goes client-first (F1 makes Lite's client null-tolerant and strips structured fields,
  then B1 stops sending them);
- the almanac goes server-first: its light rows can only be identified by fields the server adds (B2),
  so B2 strips them and marks the entry, then F2 draws the door. Between B2 and F2 a Lite tide card has
  no light rows and no door: a complete card, not a broken one.

An old open tab against a newer server degrades without breaking (the reviewers checked: big-seas
renderers already guard nulls; `windowFirstRows` drops the HW fact until reload; Coming up copes with
empty facts).

**Server shaping only at the HTTP edge**, never in a cached service: `BriefingRoleShaper.forLite`
(extended) for `/api/briefing`; a new pure `ComingUpRoleShaper.forLite` in `AlmanacController` for
`/api/almanac`; the scores and forecast mappers for A9. Nothing runs on the shaped object after the
shaper; the digest and Ask use unshaped objects. Every Pro and Admin body is byte-identical; the
controller tests assert the Pro and Admin path returns the service's object itself (`isSameAs`).

**Hidden carriers fail closed.** Three in-memory `@JsonIgnore` carriers are added (`HotTopic.liteDetail`,
`ComingUpFact.kind`, `ComingUpCoincidenceLine.liteFactsLabel`). The serve path has no JSON round trip,
but two real paths reach the shaper without them: the briefing cache reloaded from JSON after a restart
(`BriefingService.loadPersistedBriefing:240-246`, served as is when the live overlay throws `:302-306`)
and simulated topics (`HotTopicSimulationService:194-196,366-385`). So the rule is: **a missing carrier
means Lite gets nothing for that field, never the Pro value.**

**Caching.** `private, no-cache` plus a body-hash ETag (`HttpCachingConfig:28-31,186`) means a validator
from one role never yields a 304 for the other; there is no shared cache (`nginx.conf` has no
`proxy_cache` for `/api/`; the PWA never caches `/api`); logout clears the SWR cache and remounts the
shell. Role-shaped is not personal: the briefing precedent. No `HttpCachingConfigTest` change; comments
reworded (B2).

---

## 3. Phases

### F1 — client: null-tolerance, the structured strip, the rising-tide badge, the triage reason
1. **Null-tolerance.**
   - `windowFirstRows.tideFacts`: when `nearestOffset` is null but `nearestType`/`nearestTime` are
     present, draw `${nearestType} ${nearestTime}` with no trailing "·" (today the whole fact drops).
     Tests cover HW and LW ("LW 13:02") and assert the text never ends in "·".
   - `WindowTopicRows`: for `SPRING_TIDE`/`KING_TIDE` badges when `tideLocked`, **do not fall back to
     `topic.detail`** (`:121`), so a Lite window never shows the other window's alignment sentence. After
     B1 the fallback stays off for tide types: B1 gives each tide badge its own light-free detail, so
     there is nothing to fall back to. The heat-strip title already draws the label alone.
   - Big seas: with null `alignedEvent`/`gapMinutes`/`gapSide` and `structures[].aligned=false`, the
     line, chips and week rows keep the waves, window, the free HW clock, the daylight High-water row and
     the jumps, and drop only the light clause, "On the sunset ›", the aligned ring and the ⚡ on the
     aligned light row (the ⚡ on the daylight HW row stays). The renderers already guard these nulls
     (`bigSeas.js:111`, `bigSeasChips.js:205`, `mapEvents.js:991`); F1 adds the tests.
   - `WindowComingUpEntry` with no event-water facts already renders a complete card; F1 adds a test.
2. **Structured strip (defence in depth).** Fold it into the existing memo, never a second one:
   `scoresLocked ? stripTideLight(stripBriefingScores(raw)) : raw` (`WindowFirstBriefingContext.jsx`
   `:661-674`; `tideLocked === scoresLocked === !isPro`). It removes, for Lite, only whole structured
   fields: `days[].eventSummaries[].window.tide.nearestOffset`, the `detail` and `eventTime` of
   `SPRING_TIDE`/`KING_TIDE` window badges, and `bigSeas[]`'s `alignedEvent`/`gapMinutes`/`gapSide` and
   `structures[].aligned`. No prose is edited (a hot topic's own `detail` waits for B1). Unlocked it
   returns the fetched object itself (identity matters to the Pro memo chain and `MapView`'s deps). It
   also scrubs a pre-B1 body still held in the `briefing:LITE_USER` SWR entry.
3. **Rising-tide badge (A7).** `detailBadges` already receives `isPro`; for Lite the rising-tide badge is
   not built. A gate, not a new derivation, so no "Backend-heavy" licence entry; recorded as an accepted
   open path in `roles-and-gating.md` (§5).
4. **Triage reason (A9).** Until B1 strips it server-side, `forecastDetailFacts`'s footer for a Lite
   reader reads a `TIDE_MISALIGNED` row as a plain stand-down: the generic not-evaluated line, without
   "due to tide not aligned with location preference".
5. Tests: the frontend files in §6 for the briefing surfaces: Lite shows no offset or light clause on
   each surface; a Lite spring-run evening card never prints "aligned with sunrise"; Pro is unchanged;
   Admin = Pro.

### B1 — server: `/api/briefing`, the scores rows and the forecast detail
`BriefingRoleShaper.forLite` with new withers and `forLite` helpers:
- `BriefingWindowTide.forLite`: null `nearestOffset` (keep `nearestType`, `nearestTime`; Q1).
- `LocationTideFact.forLite`: null `tideOnTheLight`, `nearestSolarOffsetPhrase`.
- `BriefingSlot.TideInfo.forLite`: null `nearestSolarOffsetMinutes`, `nearestExtremeKind`,
  `tideOnTheLight`, `nearestSolarOffsetPhrase`. `BriefingSlot.forLite`: null `standdownReason` when it
  is the tide mismatch (B6) — the verdict itself stays.
- Window badges of type `SPRING_TIDE`/`KING_TIDE` (new `BriefingWindow.withBadges`): `detail` → the
  topic's `liteDetail` (so badge and topic never disagree, as the `Badge` Javadoc `:239-240` promises),
  `eventTime` → null.
- Hot topics of type `SPRING_TIDE`/`KING_TIDE` (new `HotTopic.forLite`/`withDetail`):
  - `detail` → `liteDetail`; **`liteDetail` null → `detail` null** (fail closed).
  - `liteDetail`: a new `@JsonIgnore` component holding the "N coastal location(s)" sentence that
    `buildKingTideDetail`/`buildSpringTideDetail` already produce with null alignment text (verified
    accurate). Set by the strategies through a new `withLiteDetail`; every existing wither forwards it;
    the `@JsonCreator` canonical constructor (`HotTopic.java:131-175`) tolerates its absence. A test
    round-trips a briefing through `AppConfig`'s Jackson 2 mapper (the persisted-cache path,
    `BriefingService:244/796`) and the HTTP mapper: no `liteDetail` key, and old cached JSON still loads.
    (Side effect, harmless: after a restart, `Objects.equals(cached.hotTopics(), liveTopics)` is false
    until the next build, so each serve rebuilds the overlay.)
  - `tideRun` → null; `eventType`/`eventTime` → null.
  - `expandedDetail.tideMetrics.sunriseAlignedCount/sunsetAlignedCount` → null: change the two
    primitives to `Integer` with `@JsonInclude(NON_NULL)` so Pro's JSON is unchanged (writing 0 would
    assert "0 aligned").
- Big seas (new `BigSeasDay.forLite()` and `BigSeasStructure.forLite()` on the canonical constructors):
  null `alignedEvent`, `gapMinutes`, `gapSide`; `structures[].aligned` → false (primitive; drawn as no
  ring, which reads the same as "not aligned" never being said). `daylightHighWater`,
  `daylightStart`/`daylightEnd` and `hwTime` stay (§1c, Q3).
- **A9, the triage reason:** for Lite, a `TIDE_MISALIGNED` reason is served as `GENERIC` with a null
  `triageMessage`, in `ForecastDtoMapper` (`:554-555`) and the scores rows (`LocationEvaluationView.forLite`).
  The stand-down itself stays: the rating is honest for every role (Q10).
- Tests: new "every component is classified" reflection tests for `HotTopic`, `BriefingWindow.Badge`,
  `BigSeasDay`, `BigSeasStructure`, `ExpandedHotTopicDetail.TideMetrics` and `TideRunDay`; `hotTopics`
  and `bigSeas` move from "kept" to "rebuilt" in `BriefingRoleShaperTest`; update `TideWireGoldenTest`
  (Lite strings), `BriefingControllerTest`, `WindowTideFactsWireContractTest`; a Lite shaping test on a
  JSON-reloaded briefing (no `liteDetail`) and on a simulated tide topic; wire tests that `liteDetail`
  never serialises; Pro and Admin `isSameAs`; Admin = Pro throughout.
- Javadocs that now say the opposite: `BriefingRoleShaper`'s class doc ("hot topics are untouched"),
  `TideInfo.forLite` (`BriefingSlot.java:651-660`), `LocationTideFact.forLite` (`:74-78`).

### B2 — server: `/api/almanac`
- `ComingUpFact` gains `@JsonIgnore` `kind` (`EVENT_WATER` set by `eventWaterFacts`; a one-argument
  secondary constructor keeps the many `new ComingUpFact(segs)` call sites).
- `ComingUpCoincidenceLine` keeps serialising `factsLabel` exactly as today and gains `@JsonIgnore`
  `liteFactsLabel`, built in `coincidenceFacts` without the `peakDayAlignedClock` clause.
- `ComingUpEntry` gains `lightRowsWithheld` (`boolean`, `@JsonInclude(NON_DEFAULT)`, so absent from
  Pro's JSON) and withers `withFacts`/`withCoincidence`/`withLightRowsWithheld` on the canonical
  constructor. `ComingUpResponse` gets the wither it needs.
- `ComingUpRoleShaper.forLite(ComingUpResponse)` (pure): drops `EVENT_WATER` facts (A1, A2); swaps each
  `factsLabel` for `liteFactsLabel`, **null → the span-only label built without any clock** (fail
  closed); sets `lightRowsWithheld=true` on an entry only if it actually dropped a fact or a clock.
- `AlmanacController.getAlmanac(int days, Authentication auth)`: the `days` clamp stays;
  `pro ? feed : shaper.forLite(feed)`, fail closed. `AlmanacService`'s cached feed stays role-free (its
  lists are `List.copyOf`, so an in-place edit would throw rather than leak).
- Comments: the `AlmanacController` Javadoc (role-shaped, no personal data), `HttpCachingConfig:95-97`
  ("identical for every user"), `almanacApi.js:29-35`, `useComingUpFeed.js:114` ("no role variance").
- Tests: `AlmanacControllerTest` (Lite body has no event-water facts or aligned clock and carries
  `lightRowsWithheld`; Pro and Admin `isSameAs` the service feed); `ComingUpAssemblerTest` (kind and
  `liteFactsLabel` set); reflection classification for `ComingUpEntry` and `ComingUpCoincidenceLine`;
  wire tests that `kind` and `liteFactsLabel` never serialise and `lightRowsWithheld` is absent when
  false; `HttpCachingIntegrationTest` (runs locally, not Testcontainers) with a fixture that carries an
  event-water fact and an aligned clock, so the two role bodies really differ; Lite, Pro and Admin legs.

### F2 — client: the Coming up door

> **Superseded (draw)** by `coming-up-tide-light-locked-row-plan.md` (2026-10-31): the door is replaced by a
> locked row inside every flagged card. The server side (B2) is unchanged.

- **Which cards:** `tideLocked && entry.lightRowsWithheld`, never the card family (a merged
  supermoon-hosted card has family `sun-moon`) and never fact text.
- **How many:** one door on the **first** such card in render order; later cards show no door (Q9).
- **Where:** outside the card element, as a sibling inside the `role="listitem"` wrapper after the card,
  so it is valid in both branches (an interactive card with no day chips is itself a `<button>`,
  `WindowComingUpEntry.jsx:621-631`, and `button` has `childrenPresentational`). Not inside `.wf-facts`.
- **What:** `ProUpsellDoor` with `className="wf-pro-upsell-door--inline"` (the default popover is
  absolute and would overlay the next card). New exported constants beside the others:
  `TIDE_LIGHT_UPSELL_LABEL` "When high water meets the light", `TIDE_LIGHT_UPSELL_HEADING` "High water at
  the light", `TIDE_LIGHT_UPSELL_BODY` "Pro shows which morning or evening in a tide run puts high water
  at sunrise or sunset, and how the tide sits at the light at every coastal spot." (the second clause
  describes the Map's Pro features, deliberately). House pattern: the pill inside the trigger, inline
  non-modal, Escape handled and stopped, outside press and focus-away close, no link, no "upgrade".
- **Plumbing:** `tideLocked` from `useWindowFirstBriefing()` in `WindowFirstComingUp`, passed to
  `WindowComingUpEntry` as a prop.
- Tests: Lite shows one door on the first withheld card and none on later ones; Pro and Admin show the
  rows and no door; an over-cap (button) tide card puts the door outside the button; keyboard, Escape and
  outside press, as `ProUpsellDoor`'s other mounts are tested.

**Docs in every phase:** `api-endpoints.md` (`/api/briefing` kept and stripped lists, `/api/almanac`,
the scores and forecast triage fields), `roles-and-gating.md` (the tide bullet's free list and "known
open paths": remove `tideRun` and aligned counts, add the accepted client paths), `hot-topics-tides-
eclipse.md`, and a `changelog.d/` entry per PR.

---

## 4. What does not change
- Every Pro and Admin body.
- Tide state, direction, HW/LW times, range, anomaly, rank, run heights, sea state, surge, and big seas'
  daylight high water with its Map row, chip clock, tick, callout and jumps.
- Star ratings and verdicts: a coastal rating already folds in the tide (`TideVisitor` +
  `RatingCombiner`), and a tide stand-down stays a stand-down for every role.
- The digest, notifications, Ask.

## 5. Accepted open paths (to record in `roles-and-gating.md`)
- **Rising-tide badge (A7)** and **the Plan row's "HW 19:28"** under a window header that shows the event
  time: the offset is one subtraction away from free fields the client needs elsewhere.
- **`tideState` + `tideType`** (existing) and the coastal rating itself, which reflects the tide.
- **Stale client role:** a promoted session (role written at sign-in only) may show Coming up's rows and
  the door together until sign-in; a demoted one shows neither. Cosmetic.
- ~~**Claude's region prose** may say "tide alignment" (it is fed `tideAlignedCount`; Q7).~~ **Fixed** by
  the gloss-prompt PR (`audit-followups-plan.md` P): the gloss is no longer fed `tideAlignedCount` and its
  prompt forbids saying the tide suits or aligns. Takes effect on the first briefing build after release.

## 6. Tests that pin today's Lite-visible behaviour (to update, not delete)
Backend: `BriefingRoleShaperTest` (`:91-129`, classification sets `:268-320,424-475`),
`TideWireGoldenTest`, `BriefingControllerTest:388-452`, `WindowTideFactsWireContractTest`,
`AlmanacControllerTest:174-201`, `ComingUpAssemblerTest` (`coincidenceClockReadsTheEventRows`),
`TideAlmanacSourceTest`, `PlanWindowProjectorTest:960-1085`, `KingTideHotTopicStrategyTest`,
`SpringTideHotTopicStrategyTest`, `HotTopicEventEnricherTest`, `BigSeasBuilderTest`,
`ForecastDtoMapperTest`, `HttpCachingIntegrationTest`. Frontend: `LiteShapedTidePlan.test.jsx`,
`windowFirstRows.test.js`, `WindowAttributeRow.test.jsx`, `windowFirstCards.test.js`,
`WindowComingUpEntry.test.jsx`, `WindowTopicRows.test.jsx`, `forecastDetailFacts.test.js`,
`LocationFourDaySheetFacts.test.jsx`, `bigSeas.test.js`, `bigSeasChips.test.js`, `BigSeasLine.test.jsx`,
`MapViewBigSeas*.test.jsx`, `MapLabelsBigSeas.test.jsx`, `locationSheet.test.js`,
`MapTideLocked.test.jsx`, `AskUpsellTideExclusion.test.jsx`.

## 7. Owner questions — defaults applied unless the owner says otherwise
1. **Plan row (A4):** keep "HW 19:28", drop only "1h43 before sunset". *Default: keep the time.*
2. **Hot-topic wording (A6):** Lite gets the light-free "N coastal locations" sentence on the topic and
   both windows' badges; no door per topic row. *Default as said.*
3. **Big seas (A8):** the exact alignment (the gap, which light, the aligned ring and ⚡, "On the sunset
   ›") is Pro; "high water falls in daylight" and everything it drives stay free, so no Map row, chip
   clock or jump is lost. *Default as said.*
4. **Coincidence clock (A3):** strip it (it appears only when aligned). *Default: strip.*
5. **Rising-tide badge (A7):** client-only gate, accepted open path. *Default: accept.*
6. **`tideRun` (B3):** strip whole for Lite (no reader). *Default: strip whole.*
7. **"Tide alignment" in Claude's region prose:** leave it in this series; record it as an open path and
   raise a separate prompt change (prompt edits need the owner's prompt-regression run). *Default: leave.* **Status: fixed by the gloss-prompt PR (`audit-followups-plan.md` P).**
8. **Run heights:** free. *Default: free.*
9. **Coming up door:** one door, on the first withheld tide card. *Default: one per feed.*
10. **Tide triage reason (A9):** Lite sees a plain stand-down without the tide reason; the stand-down
    stays. *Default as said.* (Only bites when the TIDE_ALIGNMENT strategy is on in production.)
