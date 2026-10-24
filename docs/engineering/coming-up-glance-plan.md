# Coming up tidy and "At a Glance": build plan

Owner ask, 2026-10-24. The handoff is in `docs/design/coming-up-glance/`:

- `README.md`, the spec;
- `Coming Up - No Recurring Panel.html`, the design reference (static HTML, no scripts);
- `screens/`, five before/after screenshots.

It asks for three things:

1. **Remove the Recurring conditions panel** from the Coming up tab.
2. **Rebuild "Next four days"** as four day tiles, each listing that day's hot topics as chips. This
   replaces the lanes grid from hot-topics-by-day C1 (#1322). Weather-driven topics (mist,
   inversion, dust …) surface here, and only here.
3. **Rename the Plan tab** to "At a Glance" (phone: "Glance"). Also add an "In next 4 days" tag to
   each dated Coming up entry that overlaps the strip.

Each phase below is **one PR**. A **Sonnet subagent** builds it in its own worktree from this
plan, the planning session reviews the diff, and the PR then goes through CI and Codex (CLAUDE.md,
*How every code change is made*). Every phase except B1 and B2 touches the UI, so each of those
follows the UI review cadence.

**Review record.** Two adversarial lenses reviewed the plan on 2026-10-24: code truth, and
design/sequencing/accessibility. Between them they made 26 findings. Every finding is folded in
below, and the planner re-checked the load-bearing ones against the code: the surge `peak` format,
the briefing cache-equality check, the supermoon `rises` fact, the almanac-only tide percentile
and the `RecurrentTopics` reader.

---

## §1 Corrections to the handoff (read before building anything)

The handoff was drawn against the screenshots, not the code. Where the two disagree, the code and
the invariants win. Items marked **(default)** override an explicit owner ask, so each one is also
an owner question in §5. The build goes ahead on the default.

1. **(default, Q6) Valley mist prints its band, not a score.** The README asks for "dawn · 8/10".
   `MistHotTopicStrategy` fires on the stored **band** (LIKELY), never on a score ("The band,
   never the score" in its Javadoc): FOGBOUND is decided by visibility, so two slots can share a
   score and differ in band. The old panel's "/10" for mist was Claude's `forecast_score` echo,
   which holds only for rows written before V185. The mist chip prints what the topic serves:
   `likely at N sites`. **Inversion** does serve a score: `9/10 · strong`, from the same STRONG ≥
   9 cut that fires it.
2. **Colours come from `utils/topicPalette.js`, not the mock.** hot-topics-by-day Q9 made the
   palette the one colour source, and the README says "match the existing hot-topic colour if one
   exists". Every topic in the mock has one. ⚠️ The palette gives INVERSION the tide teal, so an
   inversion chip and a tide chip share a colour (owner question **Q1**).
3. **Keep `scrollToEntry`'s `scrollIntoView`.** The README's "no `scrollIntoView`" rule comes from
   the design tool's iframe. The production helper (`utils/scrollToEntry.js`) already handles
   focus, reduced motion and the highlight, and its tests cover all three. Reuse it unchanged.
4. **(default, Q7) Filter chip counts stay as served.** The README asks the chips to also count
   strip occurrences. But the chips **filter the dated list**. A chip reading "Air & dust 1" that
   then filters to "Nothing matches" would contradict itself. `chipCounts` stays
   `counts.byFamily`. There is no Mist chip (the README marks it optional): mist never has an
   almanac entry, so it would always read 0.
5. **The new footer copy is true today.** `AlmanacKind.FORECAST` exists, but nothing in `src/main`
   produces it, so `counts.forecast` is always 0. The footer becomes one fixed sentence.
6. **(default, Q2) Storm surge is weather-driven (WX).** A surge is a wind-and-pressure forecast
   with no almanac entry. The README's own definition ("only shown once it's in the forecast")
   makes it WX, though the mock did not badge it.
7. **"Plan this" and "Plan from X" are verbs, not the tab.** R1 renames only places that *name the
   tab*. Verbs, "Plan A / Plan B" (Operations) and every internal identifier stay as they are:
   `plan`, `goToPlan`, `PLAN_OWNED_DAYS`, CSS classes, test ids and file names.
8. **The phone short names are a new `chipName` key, not a change to `shortName`.** The Map's
   topic strip reads `topicStyle().shortName` (`MapTopicStrip.jsx` through `mapTopics.js`).
9. **Chip order is served order.** Within a tile, non-WX chips come first, then WX chips, each
   group in `briefing.hotTopics` order (`HotTopic.compareTo`, priority). The README's "weather by
   score" would re-rank on the client.
10. **(default, Q5) The footnote is reworded to match what a tap does.** "Tap a tide or moon chip
    to jump to its entry below" is false for a run that ends inside the four days. The server
    keeps a run in `/api/almanac` only when it runs past the last Plan day, so such a run has no
    entry to jump to. "Open that dawn" is false for a dusk or night topic. Default copy: *"WX =
    weather-driven, only shown once it's in the forecast. Tap a chip to see its entry below where
    it has one; tap a WX chip to open that morning or evening on the Map."*
11. **(default, Q8) No "top N%" on the tide chip.** No `TideRunDay` field carries a percentile.
    `highWaterRank` is "0.4 m off the record", and the percentile lives only on the almanac
    side (`ComingUpDayRange.rangePercentile`). The peak tide chip prints its range and PEAK.

---

## §2 Data: what each chip prints

### 2.1 The served `glance` line (Backend-heavy)

Each chip's second line is assembled from served fields that differ per topic type (`tideRun`,
`surgeRun`, `facts`). Composing it on the client would be a new per-type derivation, with peak
decisions inside it. **B1 serves it instead:**

```java
/** The Coming up strip's chip for one topic (coming-up-glance plan §2). */
public record GlanceLine(
        String value,        // desktop line 2, e.g. "HW 16:02 · +0.72 m"; null = name and badge only
        String shortValue,   // phone line 2, e.g. "+0.72 m pk"; null = name only
        String badge,        // "PEAK" | "FULL" | null
        boolean weather) {}  // true = WX (only knowable from the forecast)
```

**Adding the component to `HotTopic`.** This touches only a few places:

- the `@JsonCreator` canonical constructor;
- the 9-argument convenience constructor's delegation (`HotTopic.java:187-197`, which passes
  null);
- the nine withers (`withEvent` … `withSafety`), each of which must carry `glance` through;
- a new `withGlance`;
- the two 20-argument test calls in `PlanWindowProjectorTest`.

Every other `new HotTopic(` site uses the 9-argument constructor and does not change. Add
`@JsonInclude(NON_NULL)`. Cached JSON with no `glance` must still deserialise.

### 2.2 Where it is computed

There are two writers. Neither re-derives a fact the other owns.

1. **The strategies that own a number stamp their own glance**:
   - `InversionHotTopicStrategy`, in the method that writes the `inversion` fact (it holds the
     rounded score there);
   - `MistHotTopicStrategy`, in `enrich` (it holds the event and the site count).

   Nothing parses a fact string back into a number.
2. **One `@Component HotTopicGlanceDecorator`**, `List<HotTopic> decorate(List<HotTopic>)`, fills
   every other topic. It **never overwrites** a non-null `glance`. It takes the whole list because
   the surge's PEAK is a judgement across topics.

**The seam is the end of `HotTopicAggregator.getHotTopics`, in both branches** (live and
simulated), after the enricher and the travel filter. This is the only correct place:
`BriefingService` writes the aggregator's output into the cached briefing at build time (about line
629). On every serve it recomputes the topics and keeps the cache only when
`Objects.equals(cached.hotTopics(), live)` (about line 293). Decorating anywhere else makes that
check always false, or leaves the persisted copy without `glance`. `glance` is a pure function of
the list, so caching it is harmless. The first serve after deploy rebuilds once, because pre-B1
cached copies lack the field. Ask projects topics into its own types, so no Ask payload changes.

**Rules per type.** The builder confirms each field against the record before relying on it.

| Type | `value` | `shortValue` | `badge` | `weather` |
|---|---|---|---|---|
| INVERSION *(strategy)* | `dawn · {N}/10` | `{N}/10` | null | true |
| MIST *(strategy)* | `{dawn\|dusk} · likely, {n} site(s)` | `likely` | null | true |
| STORM_SURGE | `HW {surgeRun.highWaterTime} · {surgeRun.peak}`. `peak` is already formatted ("+0.72 m"), so print it as served. Drop the HW half when `highWaterTime` is null. When `surgeRun` is null: `value` null. | `{peak}`, plus ` pk` on the peak date | `PEAK` on **every** surge topic dated on the date whose `max(surgeMetres)` (ignoring nulls) is the largest among the list's STORM_SURGE topics. Ties go to the earliest date. A date can carry a SUNRISE and a SUNSET topic. | true |
| SPRING_TIDE / KING_TIDE | `{tideRun.range}`, plus ` · run starts` when `dayNumber == 1 && dayCount > 1`. When `tideRun` is null: `value` null. | `{range}`, plus ` pk` when `tideRun.peak` | `PEAK` when `tideRun.peak` | false |
| SUPERMOON | `rise {HH:mm}`, taken from the `rises` fact's value before the comma ("16:34, just after sunset"). That fact is absent when the moon does not rise, and then `value` is null. **Never `eventTime`**, which is civil dusk for a NIGHT topic. | `full` | `FULL`, always: the strategy emits one topic, dated to the full-moon date (`SupermoonHotTopicStrategy`, about lines 99-106) | false |
| DUST | the `AOD` fact value ("0.45 · thick") | the AOD number alone | null | true |
| CLEARANCE, SNOW_*, AURORA, NLC | the first served fact value, else null | same | null | true |
| METEOR, EQUINOX, ECLIPSE, LUNAR_ECLIPSE, BLUEBELL | the first served fact value, else null | same | null | false |

"`weather`" is one `Set<String>` constant in the decorator (and reused by the two strategies), not
a per-call decision. No magic numbers. A formatter returns null rather than a half-filled line.

### 2.3 What stays on the client (licensed, filter/map class)

`utils/fourDayTiles.js` (new, replacing `utils/fourDayLanes.js`) only re-buckets served topics by
date and copies served fields. That is the class the lanes were licensed under
(architecture-decisions "Backend-heavy"):

- **Day buckets.** Use `laneDates(topic)` (`comingUpHandoff.js`, unchanged). A NIGHT topic covers
  its date and the next morning.
- **`isContinuation`.** The same `type` was also on the previous tile.
- **The jump.** Move `ALMANAC_TYPES_BY_TOPIC` and `tideContinues` into the new module. ⚠️ **The
  jump is NOT `jumpFor`.** The lanes called `jumpFor` only on an *open* segment, and its fallback
  `matches[0]` ("next run in the list") would send a tide chip for a run ending on day 3 to next
  month's run. The new rule: a chip jumps to a rendered entry only when **that entry's own span
  covers the chip's date**.
  - For a tide, use the tide's own days: an unmerged entry's `startDate..endDate`, or a merged
    entry's `dayRanges`, as `tideContinues` reads them.
  - For every other type, use `startDate <= chipDate <= endDate`.
  - Otherwise, no jump.
- **`inNext4Days`** on an almanac entry: `startDate <= lastPlanDateStr(todayStr) && endDate >=
  todayStr`, over the entry's own (union) dates. The tag describes the card, and the card shows the
  union.

T1 replaces the `fourDayLanes` line in `docs/engineering/reference/architecture-decisions.md`'s
licensed list with one covering `fourDayTiles.js`, and T2 adds `inNext4Days` to it.

---

## §3 Phases

| Phase | What | Side | Depends on | Can be built alongside |
|---|---|---|---|---|
| **P2** | Remove the Recurring conditions panel (frontend), new footer | frontend | — | B1 |
| **B1** | Served `HotTopic.glance` | backend | — | P2, R1 |
| **R1** | Rename the tab, "At a Glance" / "Glance" | frontend | P2 merged (P2 deletes files R1 would otherwise rename) | B1, B2 |
| **B2** | Delete the conditions plumbing (backend) | backend | P2 merged | R1, T1 |
| **T1** | Next-four-days tiles | frontend | P2, B1, R1 merged | B2 |
| **T2** | "In next 4 days" tag and the phone title block | frontend | T1 merged (both edit `WindowFirstComingUp.jsx` and the `.wf-cu-*` CSS) | T3 |
| **T3** | A WX chip opens the Map with its topic selected | frontend | T1 merged | T2 (T3 stays out of `WindowComingUpEntry.jsx` and `comingUpFeed.js`) |

Every phase branches from the updated `main` once the phases it depends on have merged. The
B2-after-P2 order is safe for a stale bundle: the almanac ETag is a body hash
(`HttpCachingConfig`), and an old bundle with `conditions` undefined renders nothing for the panel.

### P2: Remove the Recurring conditions panel (frontend)

1. Delete `components/WindowComingUpConditions.jsx`, `utils/comingUpConditions.js`, their tests,
   and their CSS: the **`.wf-cond*`** block in `index.css` (the classNames start at
   `WindowComingUpConditions.jsx:63`). Grep each selector before deleting it.
2. `WindowFirstComingUp.jsx`:
   - remove the import, the `conditions` const, the render, the `conditions` propType, and the
     Javadoc section "Recurring conditions strip (plan §7 P4)";
   - keep the comment over `coming-up-empty` ("SCOPED TO THE DATED LIST") and its wording, but
     re-point its reasoning at the four-day strip, which can still show content when the list has
     none.
3. **Footer.** In `utils/comingUpFeed.js`, replace `FOOTER_LEAD` and `footerCopy(counts)` with
   one exported constant, `FOOTER`: *"This list starts where the four-day forecast stops and shows
   dates fixed in advance. Weather-driven topics can't be known that far out, so they appear only
   in Next four days."* Render it in every status, since it no longer states a count. Delete
   `footerCopy` and its tests. Keep the `coming-up-footer` test id. The "All" chip keeps reading
   `fixed + forecast`.
4. Chips unchanged (§1.4). The header sub-line is unchanged.
5. The served `conditions` field stays until B2; the client stops reading it. The test fixtures
   carrying `conditions: []` (`useComingUpFeed.test.js`, `WindowFirstShellTabs.test.jsx`,
   `AskShellPlan.test.jsx`) may lose the key, but don't have to.
6. Docs: in `docs/engineering/reference/` (grep "Recurring conditions", "conditions strip"), mark
   the panel as removed by this plan. Leave the backend half described until B2.

**Tests.** No `.wf-cond` element renders. The footer reads the new sentence in `loading`, `error`
and `ready`. The empty-list and filter-empty notes still render.
**Gates.** The frontend's four commands. **Browser check:** the Coming up tab at 1280 and 375
(see T1 for the browser memories to read).

### B1: Served `HotTopic.glance` (backend)

As §2.1 and §2.2.

1. `model/GlanceLine.java`, with Javadoc on every public member.
2. `HotTopic`: the component, the constructors, the withers and `withGlance` (§2.1).
3. `InversionHotTopicStrategy` and `MistHotTopicStrategy` stamp their own glance (§2.2).
4. `service/HotTopicGlanceDecorator.java`, wired at the end of both branches of
   `HotTopicAggregator.getHotTopics`.
5. Tests (read `docs/engineering/test-improvement-standards.md`; real records, minimal `any()`):
   - `HotTopicGlanceDecoratorTest`, one case per table row, including:
     - surge: PEAK on both events of the peak date, ties go to the earliest date, a null
       `surgeRun`, a null `highWaterTime`, `peak` printed once (assert the exact string);
     - tide: "run starts" only when `dayCount > 1`, the PEAK `shortValue` ends " pk", a null
       `tideRun`;
     - supermoon: the `rises` fact is parsed, and a topic with no `rises` fact keeps FULL with a
       null value;
     - dust: the AOD fact;
     - an unknown type gets a null glance;
     - an existing glance is never overwritten.
   - The inversion and mist strategy tests assert the stamped glance. The mist test covers SUNSET
     → "dusk" and the singular "1 site".
   - `HotTopicAggregatorTest`: both branches return decorated topics.
   - Wire: `glance` is absent when null, and present with its four keys when set. Old JSON with no
     `glance` deserialises (the briefing cache round trip).
   - `BriefingService`: two consecutive serves with an unchanged world keep the cached briefing,
     so the equality check still holds.
6. Docs: `docs/engineering/reference/api-endpoints.md` (the briefing shape) and
   `hot-topics-tides-eclipse.md` (one paragraph on `glance` and its seam).

**Gates.** The full build ladder through `clean verify` with the integration exclusion. The 80%
per-class coverage covers the decorator.

### R1: Rename the tab (frontend)

**Goal.** The tab reads **At a Glance** at ≥ 640px and **Glance** below 640px. It keeps the ◉
glyph, and the internal id stays `plan`.

**Mechanism: CSS dual spans, the house pattern** (`AskPlanThis.jsx`, around lines 160-161:
`hidden sm:inline` / `sm:hidden`), not a JS `useIsMobile` switch. One span is
`display: none` at a time, so the accessible name is always the visible word. Put the words in a
new `utils/tabLabels.js` (`GLANCE_LABEL = 'At a Glance'`, `GLANCE_SHORT_LABEL = 'Glance'`) and a
tiny `<GlanceLabel />` component that renders the pair. Every site below uses one of them.

1. `WindowFirstShell.jsx`:
   - The `TABS` constant (around line 177) gives `plan` a `shortLabel`, and the tab renders both.
   - `badgeAriaLabel` (around line 2313) interpolates `tab.label`. Keep the full label there.
   - `'Plan · all regions'` (around line 191) becomes `'At a Glance · all regions'`.
   - The `'on Plan'` phrase (around line 198) becomes `'on At a Glance'`. Read every sentence that
     uses it, and reword the sentence if needed.
   - `aria-label="Plan sections"` (around line 2295) becomes `"At a Glance sections"`.
2. `components/map/MapBreadcrumb.jsx`: `RETURN_LABEL.plan` is the bare word, and the arrow is a
   separate `aria-hidden` node. Change it to the pair, and update the accessible-name comment
   around line 119.
3. "Open in Plan": in `components/map/MapCallout.jsx` (around line 1297) and
   `components/ask/AskPlanThis.jsx` (visible text and `aria-label`), it becomes "Open in At a
   Glance" (phone: "Open in Glance"). Each `aria-label` must start with the visible words (WCAG
   2.5.3), so drop the `aria-label` where a dual span makes it redundant.
4. `components/ask/AskConversation.jsx` and `test/askConversationHarness.jsx`: `'Plan · all
   regions'` becomes `'At a Glance · all regions'`.
5. `WindowComingUpFourDays.jsx`: "On Plan →" and the spoken "opens Plan …". T1 rewrites this file
   later, but R1 still renames it here so that `main` is never half-renamed.
6. Prose that names the tab:
   - `RewindView.jsx` (three sentences);
   - `HotTopicSimulation.jsx` ("Switch to the Plan tab …");
   - `PlanErrorBoundary.jsx` ("The Plan stopped working" → "At a Glance stopped working");
   - `RegionManagementView.jsx` ("the origin the Plan tab can plan from").
7. **Sweep.** Run `grep -rnw Plan frontend/src` and classify every hit that is not a comment
   (§1.7). The backend has no Ask prompt or Ready text that names the tab. ⚠️ The only backend hits
   are inside Flyway migrations (e.g. `V145__region_base_and_drive_time.sql`). **Never edit a
   migration**: it breaks the checksum.
8. **Phone tab bar** (`max-width: 639px` only). `.wf-tabs` is `overflow-x: auto`, and admins see
   **four** tabs (Operations). Give the tabs `flex: 1 1 auto`, with a min-height of 36px inside a
   44px hit area. Keep the overflow scroll so the admin case never clips. Verify both cases at
   375px in the browser: three tabs (LITE/PRO) and four (admin), with the Coming up badge showing.
   Desktop and tablet must be provably unchanged.
9. Tests:
   - Update every query by tab name. Find them with `grep -rln "name: /Plan\|'Plan'"
     frontend/src`. ⚠️ Read memory `frontend-tests-regex-backend-source`.
   - Assert that both spans render and that the phone rule lives inside the media query (memory
     `jsdom-can-test-css-cascade`).
10. Docs:
    - `docs/engineering/reference/plan-tab.md` gets one line at the top: the user-facing label is
      "At a Glance" / "Glance" since this phase, and the docs still say "Plan tab".
    - CLAUDE.md's overview mention of the Plan matrix gets "(labelled *At a Glance*)".

**Gates.** The frontend's four commands. **Browser check** at 1280, 768 and 375, with the
three- and four-tab cases.

### B2: Delete the conditions plumbing (backend)

Only after P2 has merged.

1. Remove:
   - `ComingUpConditionsBuilder` and its test;
   - `ComingUpCondition`, `ComingUpConditionOccurrence` and `ComingUpConditionPeak`;
   - the `conditions` component of `ComingUpResponse`;
   - the builder's wiring in `AlmanacService`;
   - `ForecastEvaluationRepository.findByTargetDateBetweenAndTargetTypeIn`, whose only caller is
     the builder.
2. **`ComingUpScoringProperties.RecurrentTopics` is NOT builder-only.**
   `SlotAtmosphereCleanupJob` (around line 116) reads `getRecurrent().getTrailingWindowDays()`.
   Keep that property and its yml key. Remove only the sub-configs whose sole reader was the
   builder: `Dust`, `Inversion`, `Mist`, `evidentiaryBarArrivals` and `Cadence`. Grep each one
   first. Update `application-example.yml`, `application-prod.yml` and
   `docs/engineering/reference/configuration.md`. Never touch `application.yml`.
3. `SlotAtmosphereRetentionReadersTest` (around lines 13 and 129-136) constructs the real builder
   but is not a builder test. **Edit it**, dropping the builder's case. Do not delete it.
4. Reword the Javadoc pointers to the builder:
   - `ComingUpAssembler.java` (about line 1371);
   - `SurpriseScore.java` (about line 141);
   - `InversionHotTopicStrategy.java` (about line 44);
   - `SlotSignals.java` (about line 90);
   - `TopicDailyLogJob` (lines 87 and 97);
   - in tests: `SlotSignalsTest.java:31` and `TideSurfaceAgreementTest.java:107-111,229`.

   **Keep `TopicDailyLogJob` and `topic_daily_log`**: it is an unbiased measurement record (Q4).
   **Keep** `TideRunBuilder`, `TideRunPeakHistory` and `SurpriseScore`, which `ComingUpAssembler`
   shares.
5. `AlmanacService`'s post-cycle cache warm stays, because the chronology needs it. Update its
   Javadoc (around lines 50 and 191).
6. Docs:
   - `api-endpoints.md` (the `/api/almanac` shape);
   - `configuration.md`;
   - `pipeline.md`, if it names the warm's reason;
   - one dated line under P4 in `coming-up-plan.md`: retired by this plan.

**Gates.** The full build ladder. `jacoco:check` is per class, so check that every shrunk class
still clears 80%.

### T1: Next four days as day tiles (frontend)

Recreate `screens/03-desktop-next-four-days.png` and `screens/02-phone-coming-up.png` using the
README's §2 measurements, in the app's tokens.

1. **Model.** Write `utils/fourDayTiles.js` and its tests. Delete `utils/fourDayLanes.js` and its
   tests.

   ```js
   buildFourDayTiles({ todayStr, hotTopics, entries, renderedEntryIds, openableKeys, forecastDates })
   → { days: [{ date, dow, dayNum, relative, relativeShort, today,
                chips: [{ key, type, name, chipName, colour, value, shortValue, badge, weather,
                          isContinuation, events, jump: ?{entryId}, mapDoor: ?{date, eventType},
                          target: ?string }] }],
       topicCount: ?number }   // null while hotTopics has not arrived
   ```

   - `relative`: today / tomorrow / in 2 days / in 3 days. `relativeShort`: today / tmrw / +2 / +3.
   - There is one chip per type per day, and it is the first in served order. Its `glance` is the
     first non-null `glance` among that type's topics on that day. For a surge, either event
     carries PEAK. `events` lists every SUNRISE/SUNSET of that type on that day.
   - `jump` follows §2.3's covering-span rule, and only against `renderedEntryIds`.
   - `mapDoor` is set only for a WX chip, and only when the date is in `forecastDates`.
     - `forecastDates` is three-valued. null means not known yet, which withholds nothing. An
       array, even an empty one, is known (the shell passes `[]` with its no-op `NO_MAP_DOOR`).
     - `eventType` is the first of `events`. A NIGHT or null `eventType` → SUNSET for a NIGHT topic
       and SUNRISE otherwise.
   - `target` is the first openable Plan window key in the date, as the lanes computed it.
   - `topicCount` is the number of distinct `type`s across the four tiles.
2. **Wiring.** `WindowFirstComingUp.jsx` passes `onShowOnMap` and `forecastDates` through to
   `WindowComingUpFourDays`, which gets neither today (around lines 320-328).
3. **Component.** Rewrite `WindowComingUpFourDays.jsx` to the README's container, tile and chip
   specs:
   - **Tile**: `role="group"` with `aria-labelledby` on its day header ("Saturday 24, today"). The
     today tile gets `aria-current="date"`. An empty day renders **no `<ul>`** (avoids "list, 0
     items"), but the tile stays.
   - **Chip**: an `<li>` holding a `<button>` (no chip has an href). **Build the accessible name
     from the content, with no `aria-label`** (WCAG 2.5.3):
     - a desktop-name span (`name`) and a phone-name span (`chipName`), with one `display: none`
       per media query;
     - then the visible value spans (`value` / `shortValue`, likewise paired);
     - then `sr-only` text for the rest: the badge as a word ("peak", "full moon"),
       "weather-driven" when WX, the day ("Saturday"), and the destination ("shown in the list
       below" / "opens the Map that morning" or "… evening" / "opens At a Glance").

     The swatch, badges and WX box are `aria-hidden`. Read the existing separator-rule Javadoc in
     the current file. Other files cite it, so move it with the component and keep the pointer.
   - **Header**: "Next four days", plus the desktop-only suffix "{n} topics · from the forecast"
     ("1 topic"; hidden when `topicCount` is 0 or null). Then the `<GlanceLabel />` link button
     "→", which calls `onGoToPlan()`.
   - **Footnote**: the §1.10 copy.
   - **Empty states**: with no briefing, show the header and four empty tiles. With an arrived
     empty list, show the header, the tiles and the line "Nothing on those four days".
   - **Colour**: the `--c` custom property from `topicStyle(type).colour` (the existing lanes
     already set it this way), then `color-mix(in oklch, var(--c) 13%, transparent)` and so on.
   - **Forced colours**: the swatch gets a border, since background colours vanish. A continuation
     chip stays dashed against a solid default. Focus-visible gets a real outline (memory
     `btn-focus-forced-colours`).
   - **Phone**, inside `@media (max-width: 639px)` only:
     - the README's phone sizes;
     - the relative label stacked under the date;
     - no badge boxes (the `shortValue` " pk" / `full` carries the cue);
     - `chipName` and `shortValue`, which must not ellipsize.
4. **`chipName`** in `topicPalette.js` `TOPIC_META`, returned by `topicStyle()` (defaulting to
   `shortName`). At 375px a tile's name area is about 41px at 10.5px/600, which fits about six
   characters. Defaults (owner question **Q9**):

   | Types | `chipName` |
   |---|---|
   | KING_TIDE / SPRING_TIDE / STORM_SURGE | King / Spring / Surge |
   | INVERSION / MIST / DUST | Invert / Mist / Dust |
   | SNOW_TOPS / SNOW_FRESH / SNOW_MIST | Tops / Snow / Snow |
   | CLEARANCE / BLUEBELL | Clear / Bells |
   | AURORA / NLC / METEOR | Aurora / NLC / Meteor |
   | SUPERMOON / EQUINOX | Moon / Equinx |
   | ECLIPSE / LUNAR_ECLIPSE | Eclips / Lunar |

   A test asserts that every `chipName` has `length <= 6`.
5. **What a tap does**, first match wins:
   1. A chip with `jump` calls `scrollToEntry(jump.entryId)`.
   2. A chip with `mapDoor` calls `onShowOnMap(mapDoor.date, mapDoor.eventType)`. That is the
      existing `kind:'event'` door; T3 adds the topic.
   3. Otherwise, `onGoToPlanWindow(target, type)` when `target` exists, else `onGoToPlan()`.

   The sr-only destination text names whichever route applies.
6. **CSS.** Delete the unused `.wf-cu-fd-*` lane rules and add the tile rules. Reuse existing
   tokens where one matches the README's hexes: grep `index.css` for each hex first.
7. **Tests** (read `docs/engineering/frontend-test-standards.md`):
   - Model:
     - NIGHT topics cover two dates; the continuation flag; one chip per type per day; the surge
       PEAK on either event;
     - `topicCount`, and `glance` null → name only;
     - **"a Sunday tide chip, run ends Monday, a November run rendered → no jump"**;
     - a straddling run → jump on every in-window day it covers;
     - WX `mapDoor` withheld for a date missing from a known `forecastDates`, and present when
       `forecastDates` is null;
     - the event-type defaults.
   - Component:
     - four tiles always; `aria-current` on today; group names;
     - the accessible name starts with the visible name;
     - the three tap routes each call the right prop;
     - the empty states; header suffix pluralisation, hidden at 0;
     - the phone rules sit inside the media query.
   - Delete the lane tests.
8. Docs:
   - `docs/engineering/reference/` (grep "Next four days", "lanes"): describe the tiles, cite this
     plan, and mark hot-topics-by-day C1's lanes as superseded;
   - the architecture-decisions licensed line (§2.3).

**Gates.** The frontend's four commands. **Browser check** at 1280, 768 and 375, against the
local backend on 8083. Read memories `cannot-sign-in-for-browser-checks`,
`browser-pane-headless-fallback` and `parallel-session-holds-port-8083` first, and say plainly
which claims were seen and which were only tested.

### T2: "In next 4 days" tag and the phone title block (frontend)

1. `buildEntryView` (`utils/comingUpFeed.js`) gains `inNext4Days` (§2.3).
2. `WindowComingUpEntry.jsx` renders the tag in the entry's tag row, alongside ALMANAC and BIGGEST
   UNTIL …, in the README's §3 style. The text is "In next 4 days" and the test id is
   `coming-up-tag-next4`.
3. Phone only (`max-width: 639px`): the title sits on its own block line, with the tags wrapping
   underneath. Desktop and tablet stay unchanged.
4. Tests:
   - an entry starting on day 4 → tagged;
   - an entry spanning today → tagged;
   - an entry starting on day 5 → not tagged;
   - a merged entry's union dates;
   - an entry ending today → tagged. This one is a **defensive** case, since the server never
     serves an entry that ends inside the window; say so in the test name.
   - the phone rule sits inside the media query.
5. Docs: the reference line, and add `inNext4Days` to the licensed list.

**Gates.** The frontend's four commands. **Browser check** at 1280 and 375, with a two-line
title on the phone.

### T3: A WX chip opens the Map with its topic selected (frontend)

The selection is reachable without restructuring `MapView`. `MapTopicStrip` is controlled through
`selectedType`, backed by `MapView`'s `selectedTopic` state (around line 1363, set in
`pickTopicFromStrip`, around line 4560). `planHandoff` is already a `MapView` prop.

1. Add an optional `topicType` to the `kind:'event'` trigger (`utils/mapDoor.js`
   `normalizeMapTrigger`, then `doorFromTrigger`), and carry it through
   `App.openMapTabFromPlan` into the handoff.
2. In `MapView`, add a handoff effect. When the landed window carries a topic of `topicType`,
   select it through the same path `pickTopicFromStrip` uses. Otherwise select nothing.
3. T1's WX chip passes `topicType` (positional call → an object trigger, or a fourth argument;
   pick whichever `normalizeMapTrigger` already accepts most cleanly, and say which in the PR).
4. Tests:
   - `normalizeMapTrigger` carries `topicType`;
   - the Map selects the chip on arrival;
   - an absent topic is ignored;
   - a second arrival with the same nonce does not re-select (the handoff nonce idiom).

   The Map's Escape and outside-press rules still go through `utils/mapForeignModal.js`.

**Gates.** The frontend's four commands. **Browser check:** tap a WX chip and land on the Map
with the chip selected.

---

## §4 Review shape per phase

- **P2, R1, T2**: two lenses (code truth, UI and accessibility) plus one refuter.
- **B1, B2**: two lenses (backend contract and wire, dead code and coverage).
- **T1**: about six lenses plus refuters (CLAUDE.md, *UI Work — Review Cadence*):
  - code truth;
  - design fidelity against the screenshots;
  - accessibility (names, focus, forced colours);
  - phone versus desktop isolation;
  - Backend-heavy compliance;
  - tests.
- **T3**: three lenses (Map door contract, dialog rules, tests).

Review agents never write to the working tree.

---

## §5 Owner questions

Each question has a default, so the build does not wait.

- **Q1. Inversion shares the tide teal.**
  - Default: keep the palette.
  - Alternative: give INVERSION its own colour (the mock's sage `#9DB59A`), which would also
    change the Map's inversion chip.
- **Q2. Which types are WX.**
  - Default: storm surge, mist, inversion, dust, clearance, snow, aurora and NLC.
  - Note: the mock showed the surge without WX.
- **Q3. The Air & dust chip** always reads 0 today (§1.5).
  - Default: keep it, as the mock does.
- **Q4. The "lately about twice a week" baseline** moving to the Map detail (README: optional).
  - Default: not in this series. B2 keeps `topic_daily_log`, so it can return later.
- **Q5. The footnote wording** (§1.10).
  - Default: the reworded copy.
- **Q6. The mist chip's value** (§1.1).
  - Default: the band and site count, never a /10 score.
- **Q7. Chip counts** (§1.4).
  - Default: dated entries only, and no Mist chip.
- **Q8. "top N%" on the tide chip** (§1.11).
  - Default: dropped. Getting it back means B1 reads the almanac's `rangePercentile`, which couples
    the decorator to `AlmanacService`.
- **Q9. The phone `chipName`s** (T1 §4).
  - Default: the table in T1 §4, at six characters or fewer.

---

## §6 Out of scope

- Any change to the dated entry cards beyond T2's tag (README: "keep the existing entry card
  internals unchanged").
- The masthead and other stand-ins drawn in the mock.
- Renaming internal identifiers.
