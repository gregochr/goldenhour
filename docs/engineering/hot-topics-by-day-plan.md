# Hot topics by day: build plan

Owner ask, 2026-10-23. The handoff in `docs/design/hot-topics-by-day/` shows **which hot topic
happens on which day, and where**. It contains:

- a README;
- two prototypes, `Coming Up v2.html` and `Map Hot Topics.html`;
- prototype logic in `map-topics.js` and `map-storm.js`;
- seven screenshots.

It has two parts:

1. **Coming up: "Next four days" lanes.** These replace the dashed handoff row ("Now — Mon 26 · Three
   topics on those four days … On Plan →") with a small grid. It has one row per topic and four day
   columns. A topic spanning several days is drawn as a bar, and a single window as a dot. Tapping
   either opens Plan.
2. **Map: hot topics per window.** This adds four things:
   - topic chips under the window control;
   - rings on the places a selected topic applies to;
   - a docked four-day strip (4 days × sunrise ↑ / sunset ↓);
   - a new daylight **High water** event in the window list, for a big-seas day whose high water
     falls between the lights.

Each phase below is **one PR**:

- A **Sonnet subagent** builds it in its own worktree from this plan.
- The planning session reviews it.
- It then goes through CI and Codex (CLAUDE.md, *How every code change is made*).

Every phase except B1 touches the UI, so each of those follows the UI review cadence.

The plan was reviewed adversarially on 2026-10-23 by four lenses: code truth, Map UI and
accessibility, Coming up and the Plan door, and the backend contract. Every finding is folded in
below. The two blockers were re-checked against the code by the planner before they were folded
in.

---

## §1 Corrections to the handoff (read before building anything)

The handoff was written against the prototype. Against the code, these hold.

### Where topics and places come from

1. **"Where" is served, so do not derive it from `needs`.** The prototype derives a topic's places
   from a location property (`needs: 'coast' | 'lake'`). Production already serves them:
   - `HotTopic.locationNames` holds the places that made the topic fire. `HotTopicEventEnricher`
     fills it in when a strategy didn't set it (:161-198):
     - coastal places for tides and supermoon;
     - dark-sky places in the topic's regions for aurora, NLC and meteor;
     - bluebell sites;
     - otherwise every enabled place in `regions`.
   - `utils/mapDoor.js:188-194` already joins `locationNames` to spots. M1 **extends that joiner**
     and does not write a second one.
   - There is no "lake" flag in `LocationDto`. The nearest field is `overlooksWater`, so the
     prototype's rule could not be built faithfully anyway.

2. **Sky-wide is a type set that already exists.** `WHOLE_SKY_TOPIC_TYPES` covers AURORA, NLC,
   METEOR, SUPERMOON, EQUINOX, ECLIPSE and LUNAR_ECLIPSE.
   - These topics get the "sky-wide" treatment and no rings, even though `locationNames` is filled
     in. For aurora that list is the enricher's dark-sky × regions backfill: coverage, not
     eligibility. The `regions` field has the same caveat (`windowFirstTopics.js` class comment).
   - **Dust differs from the handoff.** The handoff calls it sky-wide. In code it is region-scoped,
     with served `regions` and `locationNames` (Q1).

3. **The topic source is `briefing.hotTopics`, through `topicWindowKeys`, never `row.badges`.**
   - Plan renders **six** windows (`PlanRenderLimits.MAX_VISIBLE_EVENTS = 6`,
     `PlanWindowProjector.renderHorizon`).
   - The Map's served solar rows are the same six (`WindowFirstMapPane.jsx:670`). Filler and past
     rows carry `badges: []` (`mapEvents.js:313, :420`).
   - Before today's sunrise has passed, day 4 has no badges at all. After it, day 4's sunset still
     has none.
   - Reading `row.badges` would show "No hot topics" on a window a topic fires on. That breaks the
     two-question rule (CLAUDE.md: hot topics are never silenced). It would also make the Map
     contradict the Coming up lanes.
   - `hotTopics` covers all four days (`HotTopicAggregator`, today…`PlanHorizon.lastPlanDate`).
     `buildTopicIndex`/`topicWindowKeys` already bucket it exactly as `PlanWindowProjector.keysFor`
     does:
     - SUNRISE or SUNSET → that window;
     - NIGHT → that date's sunset **and** the next day's sunrise;
     - KING_TIDE or SPRING_TIDE → both of the date's windows.
   - Both tabs read that index, so the prototype's `WTOPICS[windowId]` is exactly
     `topicIndex.get(windowKey(date, type))`.

4. **The scope filter must work on topics, not badges.** `windowTopics(key, badges, …)` joins badges
   to topics by type and label.
   - C1 factors its region test out into an exported `topicInScope(topic, scopeNames)` in
     `windowFirstTopics.js`. It is the same rule: exempt by type via `isWholeSkyTopic`, which is
     fail-open; an empty `regions` is kept; names are compared byte-identically.
   - `windowTopics` then calls it, with no behaviour change, and the Map calls it directly.
   - Topics carry no `rarityRank`, so Map chip order is the **served order** (priority, then date).

5. **The Map has no region-name scope list.** `MapView` holds a `heatArea` boolean and
   `heat.areaSpots`.
   - M1 derives `mapScopeNames` as the unique `regionName` of `heat.areaSpots` when `heatArea` is
     on, and `[]` otherwise. An empty list means no filter, the `windowTopics` convention.
   - The builder compares this with `planOrigin.scopeRegions` at home and records any difference in
     the PR.

6. **Night rows have no served badges** (`nightRow`, map-tab-v2 §6 O-10). The handoff says a night
   event "inherits that evening's sunset topics, plus Aurora". That would put dust or an inversion
   on an astro night.
   - **A night row shows its own night's NIGHT topics** (Q3): `hotTopics` filtered to
     `eventType === 'NIGHT' && date === row.date`, then passed through `topicInScope`.

### Palette

7. **There is no canonical topic colour, glyph or short-name map.** The existing palettes are:
   - `comingUpHandoff.js` `SWATCH_COLOR`, keyed by type and not exported;
   - `comingUpGlyphs.js`, keyed by almanac family and almanac type, not by HotTopic type;
   - `WindowControl.jsx` `CHANNEL_ICON`, keyed by badge channel;
   - the CSS tokens `--color-topic-*`, keyed by family.

   The two mocks also disagree with each other: aurora is green in one and violet in the other.
   - C1 creates **one** exported `utils/topicPalette.js` that maps type to `{ colour, glyph, name,
     shortName, placeNoun? }`.
   - Colours come from `SWATCH_COLOR`, the app's live colours (Q9). Glyphs are an **explicit map
     for all eighteen types**.
   - Both tabs read it, and `comingUpHandoff.js` re-imports it. The mocks' hexes are **not** used.

### Plan, Coming up and the almanac

8. **Plan has no "topic selected" state, and `goToPlan(date)` discards its date.** This is
   `WindowFirstShell.jsx` ~:813, recorded as open in coming-up-plan §11.9.
   - **The tap-through opens that window's popup** through the existing `openWindow(key)`, with the
     tapped topic's row emphasised (Q6). It adds no Plan-wide topic filter.
   - Many lane cells have **no Plan card**, because Plan renders only six windows. Travel days and
     passed windows are dropped too. For those cells the tap is plain `goToPlan()`, and the button's
     name says so (C1).

9. **The almanac and hot-topic type vocabularies differ.** The almanac uses `spring-tide`,
   `king-tide` and `nlc-season`; hot topics use `SPRING_TIDE`, `KING_TIDE` and `NLC`.
   - The lanes' jump button needs an explicit, exported and tested type map.
   - **A straddling run is listed**: `AlmanacService:308-316` keeps every event that ends after
     `lastPlanDate`. The jump targets **that** entry, and "next run" is only the fallback.
   - **A merged card hides the tide type.** `ComingUpAssembler.mergeEntries` takes the host's
     `type`, so a supermoon-hosted card carries the tide only in `coincidence[].type`. Match
     `entry.type` **or** any `coincidence[].type`, the rule `matchesFilter` already uses.
   - The month list is chip-filtered (`buildChronology`), and its views carry no `endDate`. The
     caller passes the **rendered entry ids**, and the lookup runs over `feed.entries` restricted to
     those ids. When nothing matches, **no jump button is rendered**: no dead buttons.

10. **"Peak" is served for tides only.**
    - `TideRunDay.peak` is a per-day boolean meaning "biggest-range day", independent of alignment.
    - `TideRunDay.alignedEvent` is lowercase `"sunrise"`/`"sunset"`, omitted when null.
    - `SurgeRunDay.peak` is a magnitude string, not a peak day, so surge gets no peak label.
    - Aurora's "peak Sat · after dusk" has no served source.
    - **Peak labels show only for tide days** (Q7). The light suffix is added only when
      `alignedEvent` is non-null.
    - A tide peak beyond the four days ("peak Tue") isn't in `hotTopics`, so an open bar reads
      `runs on` with no peak.
    - ⚠️ **Corrected in C1's review (2026-10-23).** The tide-run fields are window-bounded:
      - `Spring`/`KingTideHotTopicStrategy` filter the run to `lastPlanDate`, and `TideRunBuilder`
        passes `perDay.size()` as `dayCount`.
      - So on day 4, `dayNumber == dayCount` always, and `peak` marks the biggest **visible** day,
        not the run's peak.
      - Neither can say whether a run continues. C1 therefore takes "open" for a tide row from the
        served almanac instead, using the **tide's own span**:
        - For an unmerged `spring-tide`/`king-tide` entry, the span is its `startDate`/`endDate`.
        - For a merged entry (the tide is the host or sits in `coincidence`), the span is its
          `dayRanges` dates. A merged entry's own dates are the union with the other topic, so a
          supermoon running on would otherwise claim the tide continues (Codex P1 on #1322).
        - A merged entry with no `dayRanges` is treated as not continuing.
      - Both tide types are accepted, because the hot topic and the almanac can label one run
        differently.
      - **An open segment prints no peak.**

### Map chrome

11. **The bottom-left slot is taken.** `.wf-map-chrome-bl` already stacks the legend chip, the LITE
    viewline-upsell chip and the **tide strip** (`MapTideStrip`, 474px). It is the labels' obstacle
    seed.
    - The four-day strip is **another flex child of `.wf-map-chrome-bl`**, never separately
      `position: absolute`.
    - Integration points the handoff doesn't know about are listed in M2: the chrome-bl mount gate,
      the counts footer, the callout's band-bar selector and the landing card's height cap.
    - **When the tide strip shows, the four-day strip collapses to its header** (Q8).

12. **The chrome-tl height variable is published only on big-seas days.** `BigSeasLine`'s
    `ResizeObserver` returns early when the line is hidden, and removes `--wf-chrome-tl-h` on
    unmount. The planner checked this against `BigSeasLine.jsx:51-73`.
    - Without a fix, the chips row would grow chrome-tl while `.wf-land`, `.wf-win-panel`, the phone
      legend toast and Ask's `chromeHeightOf` fall back to the 36/40px constants and sit over the
      chips.
    - **M1 moves the observer into one unconditional hook on `.wf-map-chrome-tl` in `MapView`**, and
      removes it from `BigSeasLine`.

13. **On a phone, floating map chrome lives in the peek sheet** (map-mobile-sheet plan); the tide
    strip already moved there. The handoff's phone strip ("full width, bottom 60px above the nav")
    would sit on top of the sheet.
    - **The phone strip is a peek-sheet section, in phase M4** (Q5).
    - The phone chips row does ship in M1, under the pill, with the "where" text hidden.

### Big seas and the High-water event

14. **The High-water event needs served fields and a lead-order change** (B1). `BigSeasDay` already
    serves an unaligned day (`alignedEvent: null`), but:
    - **The lead group is often the wrong one.** On an unaligned day, `LEAD_ORDER` picks most
      structures, then highest wave. High waters are about 12h25m apart and winter days about 8h
      long, so the night group often leads. The daylight high water is then buried in
      `otherWindows`.
    - **"Between the lights" is a solar comparison.** Under the backend-heavy rule it is served, not
      compared on the client. Solar-row `time` strings are the window's representative regional
      time, and they are empty on filler rows, so they cannot place the tick either.
    - B1 therefore adds:
      - a daylight key to the lead order;
      - a `daylightHighWater` flag;
      - `daylightStart`/`daylightEnd`, the anchor structure's own sunrise and sunset.
    - The new lead order **changes F1's served lead** on such days. That is intended, and B1
      updates big-seas-plan §3 B2.

15. **The big-seas line's copy and action change on a High-water day.**
    - `bigSeasFor` sets `jumpRow` only from `alignedEvent`. M3 adds the HW row as a jump target:
      `At high water 12:40 ›`.
    - M3 also suppresses the action on the HW row itself.
    - The first clause reads `11:30–14:00 · high water 12:40, between the lights`. The handoff's
      word "midday" would need a clock threshold (Q4b).

### What stays as it is

16. **Nothing is re-derived.**
    - Big seas stays Map-only (big-seas Q7) and does **not** join the Coming up lanes; the mock has
      none.
    - Rings are the served `locationNames`, joined and scope-filtered.
    - Which window a topic belongs to is decided by `topicWindowKeys`, which mirrors
      `PlanWindowProjector.keysFor` and no new rule.

---

## §2 What must not change

- **Verdicts.** Served verdicts, `meanRating`, picks and the `mapVerdict.js` channel stay as they
  are. An HW row shows the neutral word **Big seas**, never a light score and never another
  window's stars.
- **Server-side topics.** `PlanWindowProjector`, `HotTopicEventEnricher` and `/api/almanac` are
  untouched.
  - The lanes read `briefing.hotTopics`, as the row they replace did. Nothing rides the almanac
    (coming-up-plan D14).
- **Plan's topic surfaces.** The matrix's topic rows and the Hot topics door are unchanged.
  `windowTopics`'s output is unchanged; C1 only factors `topicInScope` out of it.
- **Big-seas chips.** Their tier and suppression rules stay as they are (big-seas F2). Rings are a
  separate overlay under the label pane and never move a chip.
- **The tide strip.** Its behaviour and offsets don't change, apart from M2's collapse coupling.
- **The reader's filters.** The star floor, `showStandDown` and persisted map filters are not reset
  by selecting a topic. M3's HW row must not trigger `selectEvRow`'s `typeChanged` reset.
- **Platforms.** Desktop and tablet behaviour of anything a phase doesn't name stays unchanged.
  Phone-only rules stay phone-only (`useIsMobile`).
- **Role gating.** Topic names, chips, rings and lanes are shown to every role, as Plan cards show
  topic labels today. The facts that are gated for LITE are not rendered on any new surface.

---

## §3 Phases

```
P0 (this plan + design folder)
B1 (lead order + daylightHighWater + daylight bounds) ──────────────────────┐
C1 (palette, topicInScope, Coming up lanes, Plan window door) → M1 (chips, rings, chrome height)
   → M2 (four-day strip, desktop/tablet) → M3 (High-water event; needs B1) → M4 (phone strip, if Q5)
```

- B1 is independent and can go first.
- The frontend phases run one after another, one PR at a time. Each branches from the updated
  `main` after its predecessor merges.

### B1 · Lead order and the daylight high water (backend)

**`Member`.** In `BigSeasBuilder.member()` (:152-166), keep the `sunriseUtc`/`sunsetUtc` it already
computes. Today they are discarded.

**Lead order.** In `LEAD_ORDER` (:197-201), insert "the group is a daylight group" straight after
"any aligned member":

1. aligned;
2. daylight;
3. most structures;
4. highest wave;
5. earliest start.

**`daylightHighWater`.** This is a `boolean` on `BigSeasDay` with `@JsonInclude(NON_DEFAULT)`; the
precedent is `BriefingEvaluationResult.forced`. It is true when **all** of these hold:

- no member of the lead group is aligned;
- the lead group's union `windowStart` is after the **latest** member sunrise;
- its `windowEnd` is before the **earliest** member sunset;
- the anchor `hwTime` is strictly inside.

The union is tested, not only the anchor's own window. A member window that starts before its
neighbour's sunrise would otherwise make the line say "between the lights" over a window that
begins in the dark.

**`daylightStart` / `daylightEnd`.** These are the anchor structure's own `sunriseUtc` and
`sunsetUtc`: `LocalDateTime` UTC with no zone, like the other big-seas times, under
`@JsonInclude(NON_NULL)`. They are set only when the flag is true. M3's tick is computed over these
instants only.

**Bounds.** Use sunrise and sunset, not civil dawn and dusk. Alignment already uses them, and civil
bounds would let a high water be both aligned-adjacent and "daylight".

**Call sites.** Six `new BigSeasDay(` calls need the new arguments:

- `BigSeasBuilder` :190;
- `JsonDateFormatContractTest` ×2;
- `DailyBriefingResponseBigSeasTest`;
- `BriefingServiceTest`;
- `ServedBriefingAssemblerTest`.

**Tests.**

- `BigSeasBuilderTest`:
  - an unaligned midday group is true, with both bounds;
  - an aligned day is false and has no bounds;
  - **a night group with more structures and a daylight group: the daylight group now leads**;
  - **the two-structure straddle**: a member window starting before the neighbour's sunrise is
    false;
  - high water one minute inside each light;
  - the Sat 24 / Sun 25 Oct 2026 pair, kept as a regression guard (all comparisons are UTC).
- `JsonDateFormatContractTest`: the real Jackson 3 wire converter is the only one that proves the
  wire shape.
  - `daylightHighWater` is absent when false and `true` when set.
  - `daylightStart`/`daylightEnd` are UTC-naive and absent when the flag is false.

**Docs.**

- `reference/api-endpoints.md`: the three fields.
- `reference/map-tab.md`: one line.
- big-seas-plan §3 B2: the new lead order.

**Review.** Two lenses: correctness (which member's sun, the union test, the lead change's effect on
F1), and test standards.

### C1 · Palette, scope helper, Coming up lanes, Plan window door (frontend)

**Palette.** Create `utils/topicPalette.js`, exporting `topicStyle(type)` →
`{ colour, glyph, name, shortName, placeNoun? }`.

- Colours come from `SWATCH_COLOR`.
- Glyphs and names are explicit for all eighteen types. Examples: `Aurora` 🌌, `NLC` ✦, `King tide`
  🌊, `Spring tide` 🌊 (short name `Tide`), `Inversion` ☁️, `Dust` 🏜️, `Mist`, `Storm surge`.
- `placeNoun` is `coastal` for the tide and surge types, and absent otherwise.
- An unknown type gets `DEFAULT_SWATCH_COLOR`, `•` and the served `label`, so it is visible and never
  dropped.
- `comingUpHandoff.js` imports from this module.
- `topicPalette.test.js` holds a literal copy of the eighteen types (the `windowFirstTopics.test.js`
  precedent) and asserts every one has an entry.

**Scope helper.** Factor `topicInScope(topic, scopeNames)` out of `windowTopics` (§1.4). The
existing `windowFirstTopics.test.js` must pass unchanged, and a new block tests the helper directly.

**Model.** Create `utils/fourDayLanes.js`, exporting
`buildFourDayLanes({ todayStr, hotTopics, openableKeys, entries, renderedEntryIds })`. It is pure and
licensed as a filter/map member. It returns `{ days: [4 × {date, dow, dayNum, today}], rows }`.

- **Rows.** One row per topic `type`, in served order.
  - It is the same type set `buildHandoff` counts. Both are built from one helper, `laneDates(topic)`:
    - the dates of `topicWindowKeys(topic)`, each with its windows;
    - or, for an unanchored topic, `topicCoveredDates` with no window. That covers CLEARANCE, which
      is simulation-only, and any future type.
  - The equality test includes a null-`eventType` topic.
- **Cells.** Group a row's keys, across all its topics (MIST and STORM_SURGE serve one topic per
  event), by date within today…+3 (`lastPlanDateStr`). A day is "on" with the union of its windows:
  `↑`, `↓`, both, or none for an unanchored day.
- **Segments.** Consecutive "on" days form a segment.
  - A one-day segment is a **dot**, with glyph `↑`, `↓`, `↑↓` or none.
  - A segment of two or more days is a **bar**. Its left text is `{start} → {end}`:
    - `start` is `tonight` when the row has a NIGHT topic dated `todayStr`;
    - `this morning` when the segment starts today only through a NIGHT topic dated yesterday;
    - `today` for any other start today;
    - otherwise the three-letter weekday.
  - A bar's right text is the tide peak only (§1.10): `peak {Dow}`, plus ` · ↑ sunrise` or
    ` · ↓ sunset` when `alignedEvent` is non-null, matched case-insensitively.
- **Open.** A segment is open when the row has a key dated after day 4. That happens for a NIGHT
  topic on day 4, or a tide with `tideRun.dayNumber < dayCount` there.
  - An open bar reads `runs on` and has a dashed right edge.
  - An open dot gets a dashed outer ring. Both get the jump button when there is a target.
- **Jump.** Uses the §1.9 rules: the type map, the coincidence match, and only `renderedEntryIds`.
  - It returns `{ entryId, label }` or null.
  - The label is `In the list · 10–15 Sep` for the straddling run, and `Next run in the list ·
    10–15 Sep` for the fallback.
  - Aurora has no almanac entry, so its jump is always null.
- **Tap target.** The **first key `topicWindowKeys` produced inside the segment that is also in
  `openableKeys`**. The caller passes `openableKeys` as the Plan window cards' keys.
  - Using the first produced key, not "the segment's date + SUNSET", fixes the yesterday-dated NIGHT
    topic. It also settles a day carrying two MIST topics.
  - With no openable key, the target is null and the tap is `goToPlan()`.

**Component.** `components/WindowComingUpFourDays.jsx` replaces `WindowFirstComingUpHandoff` at its
mount (`WindowFirstComingUp.jsx:304-308`). It stays ungated on `status`.

- **Layout.** Follow handoff §1, as tokens, with `--c` taken from the palette. Every grid item is
  explicitly placed.
- **Header.** `NEXT FOUR DAYS` plus `On Plan →`, a button that calls `onGoToPlan()`.
- **Degrades.**
  - With no briefing: header and day headers only. Nothing is synthesised (D14).
  - With an empty list that has arrived: the single line `Nothing on those four days`.
- **Accessibility.**
  - Each segment is a `<button>`. Its accessible name **starts with the visible text** (WCAG 2.5.3,
    since `aria-label` replaces content), then adds the destination. Examples:
    - "Aurora, tonight → Sat, opens Plan on the Friday sunset";
    - "Spring tide, runs on, opens Plan" (no openable window).
  - Arrows and glyphs are `aria-hidden`.
  - Keep the `{' '}` separator rule from the handoff row's Javadoc.
  - The jump button's name is its tooltip text.
- **Jump.** Lift `scrollToEntry` out of `WindowComingUpConditions.jsx` into a small exported util and
  reuse it: smooth scroll, centred, `wf-cu-ent-highlight` for 1600 ms, then focus the entry
  (`tabIndex={-1}` if it has no focus target).
- **Phone** (≤639px, CSS only, like every Coming up component):
  - label column 76px and jump column 16px;
  - bar text hidden, so only the peak label shows, at 9px;
  - `shortName`.

**Plan window door.** Add `goToPlanWindow(key, topicType)` to `WindowFirstShell`. In one click
handler it:

1. calls `selectTab('plan')`;
2. **focuses the Plan tab button** (`tabRefs.current[planIdx]?.focus()`, as `goToPlan` does), so
   that the popup's captured opener is a live node and not the lane button inside the Coming up
   panel that is now hidden;
3. calls the existing `openWindow(key)`, not the raw setter. That also clears `focusedRegion` and
   warms the chunks;
4. sets `emphasisTopic` only if `windowTopics(key, card.allBadges, topicIndex, planScopeNames)`
   contains a row of that type. The popup is scope-filtered and the lanes are not, so a
   region-scoped topic can be absent from it.

`emphasisTopic` resets **whenever the open key changes**: `‹ ›` stepping and close. The dialog is
deliberately unkeyed (:2695-2700).

The location-sheet handoff (:1137-1170) is the batching precedent, but it runs in a nonce-keyed
effect. A click handler needs none of that machinery.

`WindowSheetDialog` takes an optional `emphasisTopic` prop. The matching `WindowTopicRows` row gets
`data-emphasis`: a 1.6 s outline in the topic colour, scrolled into view inside the dialog. Focus
stays on the dialog's normal initial target.

**Tests.** Read `docs/engineering/frontend-test-standards.md` first.

- `fourDayLanes.test.js`:
  - the NIGHT key spanning two days;
  - a yesterday-dated NIGHT topic: the bar reads `this morning`, and its target is today's sunrise;
  - a tide on both windows of a day (`↑↓`);
  - two MIST topics on one day;
  - a single SUNRISE dot;
  - an unanchored topic: a day dot with no glyph, target null;
  - contiguous versus gapped days;
  - an open bar and an open dot;
  - a peak with and without `alignedEvent`;
  - jump to the straddling entry, to a merged supermoon-hosted entry through `coincidence`, the
    fallback, and null when filtered out;
  - the **six-window cap**: `openableKeys` at 06:00 and at 09:00 makes day-4 targets null;
  - the type set equals `buildHandoff`'s;
  - Rewind (`todayStr` from `appNow`).
- `WindowComingUpFourDays.test.jsx`:
  - explicit grid placement;
  - accessible names begin with the visible text;
  - a tap with a target calls `goToPlanWindow(key, type)`, and a tap with a null target calls
    `goToPlan()`;
  - jump scrolls, highlights and focuses;
  - no jump button when there is no target;
  - the degrade states.
- Shell:
  - `goToPlanWindow` opens the popup on the Plan tab with the row emphasised;
  - no emphasis when the row is scoped out;
  - emphasis clears on `‹ ›` and on close;
  - **closing the popup returns focus to the Plan tab**.
- Update or replace `comingUpHandoff*.test.js`, `WindowFirstComingUp.test.jsx` and
  `WindowFirstShellTabs.test.jsx`, which use the `coming-up-handoff` test id.
- Retire `WindowFirstComingUpHandoff.jsx` once nothing mounts it. Keep `lastPlanDateStr`.

**Docs.**

- `architecture-decisions.md` "Backend-heavy": add `fourDayLanes.js` as a named filter/map member,
  and `topicPalette.js` as presentation.
- `coming-up-plan.md` D14: the lanes supersede the row.
- `reference/plan-tab.md`: the window door and emphasis.
- Browser-verify Coming up on desktop and at 390px.

### M1 · Map chips, rings and the chrome-height hook (frontend)

**Chrome height first (§1.12).**

- Add a `useChromeTlHeight(ref)` hook in `MapView`. It uses an unconditional `ResizeObserver` on
  `.wf-map-chrome-tl` that publishes `--wf-chrome-tl-h`, keeping BigSeasLine's hidden-pane rule:
  publish nothing at 0.
- Remove the observer from `BigSeasLine`. Its tests move to the hook.
- Test it on a day **without** big seas: the panel, the landing card, the phone toast and
  `askCamera.chromeHeightOf` all clear the chips row.

**Model.** `utils/mapTopics.js` is pure. It provides two functions:

1. `topicsOnRow({ row, topicIndex, hotTopics, scopeNames, bigSeasDay })` returns
   `[{ type, name, glyph, colour, where, wholeSky, topics }]`, built per row kind:
   - **Solar** (past and filler included): `topicIndex.get(windowKey(row.date, row.eventType))`,
     filtered by `topicInScope`. Topics of one type are grouped into one chip. Order is the served
     order.
   - **Night**: §1.6.
   - **HW** (M3): Big seas only.
   - **Big seas** is prepended on every **solar and night** row of a date where
     `bigSeasFor(...).form === 'full'`. That keeps the chips consistent with the line, which already
     shows on that date's night rows.
2. `placesFor(type, topics, roster, scopeNames)` extends the `mapDoor.js` joiner (§1.1).
   - Whole-sky types return `null`.
   - Otherwise it returns the union of the topics' `locationNames`, joined byte-identically to the
     **scoped roster**, not `labelSpots`. Stood-down and star-floored places are still ringed (the
     big-seas F2 lesson).
   - Big seas joins `structures[].locationId` by id.
   - `where` is:
     - `sky-wide` for a whole-sky type;
     - `{n} structures` for Big seas;
     - `{n} {placeNoun ?? 'places'}` otherwise.
   - With `n = 0` the chip still shows, without `where`.

On an HW day (M3) the Big seas name reads `Big seas · HW 12:40`.

**Scope.** `mapScopeNames` follows §1.5.

**Chips.** `components/map/MapTopicChips.jsx` is a row inside `.wf-map-chrome-tl`, under
`BigSeasLine`.

- **Width:** desktop is bounded at the control's **504px**, like `.wf-big-seas`. On a phone it is
  full width.
- **Hit-testing:** the row has `pointer-events: none` and the chips `auto`, the
  `.wf-map-toolbar-row` precedent. On the phone the gaps would otherwise swallow map drags.
- **Chip:** a toggle `<button aria-pressed>` keyed by type.
  - Its name is state-independent: "King tide, 66 coastal places", or "Aurora, sky-wide".
    `aria-pressed` carries the state.
  - The glyph is `aria-hidden`.
- **Row:** a labelled group, "Hot topics on this window".
- **Focus:** if a focused chip unmounts after a briefing poll or a scope change, focus moves to the
  group.
- **Empty state:** on desktop and tablet, `No hot topics on this window` in muted text; it is not a
  button. On the phone nothing is rendered (Q11).
- **CSS:** follows handoff §2A, with a 30px min-height. In forced colours the selected chip gets a 2px
  outline.
- **Phone:** the chips row is mounted under the pill (§1.13), and `where` is hidden inside
  `@media (max-width: 639px)` only.

**Selection.** `MapView` holds `selectedTopic: string | null`, a type.

- It persists across row changes while the type is on the new row, and clears when it is not. This
  is an effect keyed on the derived list.
- It never touches the star floor, `showStandDown` or persisted filters.
- **Escape order:** the pane ladder (`MapView.jsx:5625-5675`) is panel → menu → callout. Add
  `selectedTopic` **after** the callout.
- **The landing card's document listener** (:1876) also defers while `selectedTopic != null`, so a
  single Escape never closes two levels (the O-20 defect class). Pin this with a test.
- **The toggle's outcome is announced through the existing `statusLine`/`map-status` region.** That
  region is silenced by `foreignModalOverPane`. Do not add a new live region.

**Rings.** `components/map/MapTopicRings.jsx` draws non-interactive react-leaflet `CircleMarker`s in
a **dedicated pane** below `wf-labels`.

- Leaflet reprojects them, so there is no hand-rolled coordinate loop.
- They are 22px in diameter with a 2px stroke in the topic colour, plus the handoff's halo and glow.
- They are mounted in Heat and Pins views alike.
- No marker clustering, at any scale.

**Dim.** While a topic is selected, `.wf-map-tab` gets `data-topic-selected`.

- Ordinary name chips (`.wf-maplab-chip:not(.wf-bsc)`) and Pins' `.wf-pin` labels drop to 0.5
  opacity.
- They are restored on `:hover`, `:focus-visible` and `[data-selected]`, the Ask fade's restore set
  (index.css:7267-7278).
- **When Ask's fade also applies, Ask's 0.25 wins.** The exemption is written into the selector, not
  left to order.
- Big-seas chips, the selected place and Ask picks are never dimmed.
- A jsdom cascade test pins the specificities.

**Sky-wide line.** A whole-sky topic draws no rings. A one-line notice sits in the chips row instead:
`🌌 Aurora possible · sky-wide — every location in range sees it or none does`.

- It is not a centred float (Q4c), because that would be a new obstacle in the middle of the field.
- It is announced through `statusLine` with the toggle.

**Label repaint.** Pass a `chromeKey` (`selectedTopic`, the sky-wide line, and in M2 the strip's
open state) to `MapLabels` and `PinsLayer`. Both repaint only on map move, zoom and resize, or on
prop change (`MapLabels.jsx:465, 491-508`), so a toggle that grows chrome-tl would otherwise leave
labels under it.

**Label obstacle.** Re-run `scripts/measurements/label-obstacle/` with a `topics` surface: the real
chips row inside the real chrome-tl, with three chips, and separately the empty-state row. Record
both in §6. **If the phone loss exceeds the big-seas line's −21%, stop and ask before shipping.**

**Tests.**

- `mapTopics.test.js`:
  - a solar row whose `badges` is `[]` (a filler, day 4) still lists its topics;
  - a night row: NIGHT topics only, scoped;
  - Big seas on solar and night rows of a full day only;
  - grouping by type;
  - `placesFor` cases: scope, a disabled name, whole-sky null, stood-down places kept, Big seas by id;
  - `where` nouns and the zero case.
- `MapTopicChips.test.jsx`:
  - the toggle and `aria-pressed`;
  - the state-independent name;
  - focus moves to the group on unmount;
  - empty states on desktop and phone;
  - `pointer-events`.
- `MapTopicRings.test.jsx`: one ring per place, none for whole-sky, mounted in Pins.
- `MapView`:
  - the chrome-height hook without big seas;
  - persistence and clearing of the selection;
  - the Escape ladder with the callout and with the landing card;
  - the status-line announcement;
  - the dim cascade, including the Ask overlap and its exceptions;
  - labels repaint on toggle.

**Docs.** `reference/map-tab.md`, and `architecture-decisions.md` (`mapTopics.js` as a filter/map
member).

### M2 · The four-day strip, desktop and tablet (frontend)

**Model.** `fourDayGrid({ events, topicIndex, hotTopics, scopeNames, bigSeas, todayStr, activeRow })`
lives in `mapTopics.js`.

- **Columns:** 0–7, computed as `day*2 + (SUNRISE ? 0 : 1)` over today…+3, **by date**. Each column
  carries its EV row when one exists. A column without one is drawn but not interactive.
- **Rows:** the union over the eight windows of §M1's per-window topics, from `topicIndex`, never
  badges.
  - Order is the served order, with Big seas first.
  - Big seas is "on" in both columns of each big-seas day.
- **Bars:** contiguous "on" columns merge into one bar. A NIGHT topic is Fri ↓ + Sat ↑, so it
  merges.
- **Current column:** the active row's column.
  - A night row maps to its date's ↓. The exception is the **night in progress before dawn**, which
    D-14 dates as yesterday, and therefore before `todayStr`. That night maps to **today's ↑
    column**, where its NIGHT topic also sits (Codex P1 on #1319).
  - An HW row (M3) shades its whole day.
  - Past columns of today are drawn but not interactive, matching `row.past`.

**Component.** `components/map/MapTopicStrip.jsx` is a flex child of `.wf-map-chrome-bl`, placed
**before** the tide strip.

- Visual spec: handoff §2C, 440px, with tokens.
- Header: `HOT TOPICS · NEXT FOUR DAYS`, a `<button aria-expanded>`. Its state is stored per viewer
  in `localStorage`, under try/catch. It defaults to open.
- It **renders collapsed while the tide strip shows, and while the landing card is open**, without
  changing the stored preference. The landing card's height cap assumes a 44px bottom chip
  (index.css:5504).
- Every grid item is explicitly placed.

**Integration points (review M2 #8, #9).**

1. **Mount gate:** chrome-bl mounts only under the legend, upsell or tide conditions
   (`MapView.jsx:6700-6702`). Add the strip, so it shows in Pins view with nothing else on.
2. **Counts footer:** `--tsh` is the tide strip's own height. Publish the whole chrome-bl height as
   `--wf-chrome-bl-h` through the same hook pattern, and lift `.wf-map-counts-footer` by it on
   desktop and tablet. At 768px the footer would otherwise overlap the strip.
3. **Callout:** add the strip's test id to `BAND_BAR_SELECTOR` (`MapCallout.jsx:89-94`), plus a
   repaint trigger on its height (the `tideStripHeight` precedent), so the callout is never placed
   over it.
4. **Labels:** pass the strip's open state into M1's `chromeKey`.

**Interactions.**

- **Bar:** selects the topic and `selectEvRow` on the column's row. Use the current column if the bar
  covers it, otherwise the bar's first column that has a row.
- **Row label:** if the topic is on the current row, toggle it. Otherwise jump to its first column
  with a row, and select it.
- **Focus:** use the `focusAfterJump`-before-`onSelectEv` pattern (`MapTideStrip.jsx` ~:438). Focus
  goes to the strip root, which stays mounted.
- **Accessible names:** start with the visible label. Examples: "King tide, Friday sunrise to
  Saturday sunset, current window"; "Inversion: select on this window".

**Forced colours.**

- Bars get a 1px border.
- The current and selected states get an `outline`, since background and box-shadow are removed.
- M3's HW tick is drawn as a border.
- A forced-colors test covers this.

**Label obstacle.** Re-run the harness four ways: the strip open and closed, with and without the
tide strip. Record the results in §6.

**Tests.**

- `mapTopics.test.js`:
  - columns by date with a missing row;
  - day-4 columns filled from `topicIndex` while their rows have no badges;
  - merging, including a night merge;
  - current-column mapping, including a yesterday-dated night row before dawn landing on today's ↑.
- `MapTopicStrip.test.jsx`:
  - placement;
  - collapse, and a storage failure being tolerated;
  - forced collapse under the tide strip and under the landing card, with the stored preference kept;
  - bars and labels, focus, names;
  - forced colours.
- `mapChromeZLadderCascade.test.jsx`: add the strip.
- Footer lift, the callout selector, and the Pins-view mount.
- **Not on a phone:** the strip is not mounted at ≤639px; that is M4.

**Docs.** `reference/map-tab.md`.

### M3 · The daylight High-water event (frontend; needs B1 and M2)

**EV kind.** Add `EVENT_KIND.HW = 'hw'`.

- `buildMapEvents` inserts one row per served `BigSeasDay` that has `daylightHighWater` and passes
  `bigSeasWindowAhead`. The row goes between that date's sunrise and sunset rows.
- Row shape: `{ id: 'hw:{date}:HIGH_WATER', kind: 'hw', eventType: 'HIGH_WATER', date, time:
  formatEventTimeUk(hwTime), label/dayLabel as solar rows, badges: [], inForecastDomain }`.
- **`inForecastDomain` is required** (Codex P1 on #1319). `isForwardableRow` (`mapEvents.js:932-939`)
  rejects any row that lacks it, so `selectEvRow` would skip `onSelectDate` and take the local-night
  fallback, leaving the parent date and the Map pane out of sync. The HW row inherits the value from
  the same date's solar rows. A date that has no solar row is computed exactly as `solarRow` computes
  it, never defaulted.
- `findEvIndex(list, 'HIGH_WATER', date)` must resolve it.

**⚠️ The sweep.** A grep for `eventType ===` misses most of the hazards. The builder works through
**every** item below, plus anything a wider grep finds:

- greps: `kind !== 'solar'`, `!== EVENT_KIND`, `? 'sunrise' : 'sunset'`, `=== 'SUNRISE' ?`,
  `solarWindowOnScreen`, `typeChanged`;
- files: `components/map/`, `MapView.jsx`, `WindowFirstMapPane.jsx`, `MapCallout.jsx`,
  `OutcomeRatingBlock.jsx`, `LocationFourDaySheet.jsx` and `utils/`.

The PR description lists what `HIGH_WATER` does at each one. **The default for an unknown kind is "no
score, no fetch, no reset", never "treat as sunset" and never "treat as night".**

Known items:

- **`solarWindowOnScreen`** (`MapView.jsx:3089`) returns true for any type that is not SUNRISE or
  SUNSET. `getRatingForLocation` (:3173) would then read `dayData.sunset`, and stand-down (:3137)
  uses `'sunset'` too. The result would be **the sunset's stars on the HW row's chips, pins, floor
  and counts**. HW must return no rating and no stand-down.
- **`selectEvRow`'s `typeChanged`** (:5127-5138) resets the star floor and stand-down and **clears
  the persisted `mapFilterMinStars`**. Stepping sunrise → HW → sunset would wipe the reader's saved
  filter.
  - **The rule:** `typeChanged` is false whenever **either** the current or the destination row is
    the HW row (`eventType === 'HIGH_WATER'` on either side). There is no reset in either direction.
  - Comparing against "the last solar type" does **not** work (Codex P1 on #1319). After
    sunrise → HW, the HW → sunset step compares SUNSET with SUNRISE and resets anyway.
  - The accepted consequence: going from sunrise to sunset by way of the HW row keeps the filters,
    while stepping directly from sunrise to sunset resets them as today. A stop on the HW row reads
    as a choice to look at the same day, not to change light. The sweep test pins both paths.
- **`bigSeasChipItems`** (`bigSeasChips.js:44`) returns nothing unless `kind === 'solar'`. Accept
  `'hw'` and ring every structure on it, because high water is the event.
- **Places that read `kind !== 'solar'` as "night"**: `MapView.jsx:3829` (`followWindowRow`), :5185
  (`isNight`), `MapCallout.jsx:702`, `LocationFourDaySheet.jsx:213`.
- **Places that read `=== 'SUNRISE' ? … : sunset`**: `MapCallout.jsx:458`, `OutcomeRatingBlock.jsx:241`,
  `mapDoor.js:79/86`, `MapView.jsx:4368`.
- **`windowUnscored`** (:2979): no "not scored yet" line on HW.
- The score fetch, heat points (an empty set), the tide strip, Rewind and past windows, Ask doors, and
  the phone peek-sheet list.

**What the map shows on an HW row (Q4).**

- No heat field and no score chips.
- Big-seas chips for the day's structures, all ringed.
- Big seas is **auto-selected**, which shows rings and dims ordinary chips. The auto-selection
  **clears when the reader leaves the HW row**, unless they selected it themselves.
- The pill reads `HIGH WATER · Tomorrow 12:40 · BIG SEAS`. Its kind badge is a new `KindChip`
  variant in `--color-tide-light` (16% background, 34% inset border). The verdict cell is the neutral
  word `Big seas`.
- The lead copy comes from `bigSeasCopy`: "High water at 12:40 with 3.4 m waves from NNE. Not a light
  window: this is for spray over the piers, not for colour." The handoff's "in flat midday light" is
  dropped (Codex P1 on #1319). B1's flag covers any high water strictly between the lights, so a
  09:10 high water would be told it was midday. That is the same reason as Q4b.
- The week menu and the phone peek-sheet list show the kind chip, `—` for the score, and `⚡`
  (aria-hidden), with an sr-only ", big seas, high water" clause.
- **`BigSeasLine` / `bigSeas.js`:** on an HW day the full form's action becomes
  `At high water 12:40 ›`, with `jumpRow` set to the HW row. There is no action on the HW row itself.
  The first clause is described in §1.15.
- **Expiry needs its own transition** (Codex P1 on #1319). Once `windowEnd` passes, the existing
  helpers only stop `buildMapEvents` from emitting the HW row. Neither `followWindowRow` nor
  `isForwardableRow` changes the stored `eventType`. `MapView` would keep `HIGH_WATER`, derive
  `activeEvIndex === -1`, and show no active window. M3 adds:
  - **A deadline timer** armed at the selected HW row's `windowEnd`, measured against `appNow()` so
    that Rewind works. It must survive a hidden tab: re-check on `visibilitychange`, and never trust
    one long `setTimeout` (see the wall-clock-deadline lessons). When it fires, it bumps a render
    key so that `buildMapEvents` re-runs.
  - **An effect** that sees `eventType === 'HIGH_WATER'` with no matching row. It calls
    `selectEvRow` on the first row after the HW's date and time (normally that date's sunset). If
    there is none, it calls it on the first row of the list. It goes through the same
    `typeChanged` exemption, so the reader's filters survive.

**Strip tick.**

- Position: `x = (hwTime − daylightStart) / (daylightEnd − daylightStart)` over the served instants
  (B1), within that day's two columns, clamped to 4–96%. This is licensed geometry, like the tide
  sparkline.
- It is drawn only when both bounds are served.
- Label: `HW 12:40`. The current tick gets the handoff's contrast treatment.
- The tap target is a **sibling** button of the bar, never nested inside it, with a hit area of at
  least 24px and a keyboard stop. Its name is "High water 12:40, Friday".

**Tests.**

- `mapEvents.test.js`:
  - the row is inserted only with the flag, in order;
  - it is gone once `windowEnd` passes under a Rewound `appNow`;
  - an aligned day gets no row;
  - the row carries its date's `inForecastDomain`, and `isForwardableRow` accepts it.
- Selecting a **future** HW row from the strip and from the week menu calls `onSelectDate` with
  its date, and the parent date and the Map pane stay in sync.
- **`hwEventSweep.test.jsx`**, one assertion per swept path. It must include:
  - **no stars** through `getRatingForLocation`;
  - the persisted star filter survives sunrise → HW → sunset, while a direct sunrise → sunset step still resets it;
  - big-seas chips are present and ringed;
  - no "not scored" line;
  - the callout and outcome block don't read the HW row as sunset.
- `WindowControl.test.jsx`: the kind chip, the neutral verdict, the sr-only clause.
- `BigSeasLine.test.jsx` / `bigSeas.test.js`: the HW action and copy, and no action on the HW row.
- `MapTopicStrip.test.jsx`: tick position, the clamp, no tick without bounds, the tap, the
  hit-area size.
- Expiry while selected, using fake timers and a Rewound `appNow`:
  - the selection moves to that date's sunset, or to the first row when there is none;
  - `activeEvIndex` is never −1 after the timer fires;
  - a hidden tab that becomes visible past `windowEnd` advances too;
  - the star filter survives.
- The auto-selection clears when leaving the row.

**Docs.** `reference/map-tab.md`, and `big-seas-plan.md` §6 (a pointer).

**Review.** M3 gets a seventh lens on the sweep alone.

### M4 · Phone: the strip in the peek sheet (frontend; only if Q5 = yes)

- A collapsible "Hot topics · next four days" section in the peek sheet, after the Windows section,
  following the tide strip's phone move.
- It reuses `MapTopicStrip`'s grid with the phone metrics: a 70px label column and no footer.
- Tapping a bar selects the row and the topic, then collapses the sheet to peek so the rings can be
  seen.
- Desktop and tablet must be provably unchanged: the mount is gated on `useIsMobile`, and a desktop
  chrome-bl snapshot test proves it.

---

## §4 Brief for every Sonnet build

Each brief carries:

- this plan's phase section and §1–§2, plus the design folder `docs/design/hot-topics-by-day/`;
- "read `docs/engineering/test-improvement-standards.md` (backend) or
  `docs/engineering/frontend-test-standards.md` (frontend) first";
- the gates, run until green, gating on exit codes:
  - backend: `./mvnw checkstyle:check` and the `clean verify` line with
    `-Dtest='!**/integration/**'`;
  - frontend: `npm run lint && npm test && npm audit --audit-level=high && npm run build`;
- a `changelog.d/YYYYMMDD-hot-topics-by-day-<phase>.md` entry;
- the matching `docs/engineering/reference/` update;
- "never `git checkout --` to undo a mutation; commit nothing; don't touch files outside the phase".

Review sizing:

- **B1:** two lenses.
- **C1 and M1–M4:**
  - the UI cadence's six lenses plus refuters;
  - a browser check on port 8083 where possible (Coming up at desktop and at 390px);
  - a statement of what was seen versus only tested.
- **M3:** a seventh lens on the sweep.

---

## §5 Owner answers (2026-10-23)

The owner accepted every recommendation below: Q1, Q4, Q5 and Q6 explicitly, and the rest with "no
changes".

| # | Question | Answer |
|---|---|---|
| Q1 | Should dust be sky-wide (handoff) or region-scoped (code)? | **Region-scoped, as served**, with rings on its `locationNames` |
| Q2 | What should the chip's "where" text say? | `66 coastal`, `4 structures`, otherwise `12 places` |
| Q3 | Should night rows show that night's NIGHT topics, or "inherit the evening's sunset topics + aurora"? | **NIGHT topics only.** Inheriting would put dust and inversion on an astro night |
| Q4 | What should the map show on the HW row? | **No heat and no scores.** Big-seas chips ringed, Big seas auto-selected, ordinary chips dimmed |
| Q4b | Keep "high water 12:40, **midday**"? | Use `between the lights`; "midday" needs a clock threshold |
| Q4c | Should the sky-wide notice be centred over the map or a line under the chips? | **Under the chips.** A centred box is a new label obstacle |
| Q5 | Should the phone get the strip? | **Yes, as a peek-sheet section (M4).** The handoff's docked strip would sit on the sheet |
| Q6 | How should "Plan on that date with the topic selected" work? | **Open that window's popup with the topic row emphasised**, or plain Plan when the window isn't one of Plan's six |
| Q7 | Which peak labels? | **Tide days only.** Aurora's "peak Sat" has no source; serving one is a separate backend change |
| Q8 | What happens when the four-day strip and the tide strip show together? | **The four-day strip collapses to its header while the tide strip (or the landing card) shows** |
| Q9 | Topic colours: the mock hexes or the app's? | **The app's live colours** (`SWATCH_COLOR`), extracted to one palette |
| Q10 | Should Big seas be in the Coming up lanes? | **No.** It stays Map-only, as big-seas Q7 |
| Q11 | Should the Map show "No hot topics on this window"? | **Yes on desktop and tablet; nothing on the phone**, where the row costs labels |
| Q12 | B1 changes which big-seas window leads on an unaligned day (daylight over night). OK? | **Yes.** Otherwise the daylight high water is usually hidden |
| Q13 | M1 tripped the phone stop rule: chips plus the big-seas line cost −23% / −30% of labels on a phone. What should happen? (asked 2026-10-23) | **On a phone, hide the chips row while the big-seas line shows.** Big seas is already the line, and other topics reach the phone through M4's strip. A selection made before the row hides keeps its rings, and Escape still clears it |

---

## §6 Status

| Phase | PR | State |
|---|---|---|
| Plan | — | drafted 2026-10-23; adversarially reviewed (four lenses); every finding folded in; owner answered Q1–Q12 |
| B1 | #1320 | ✅ merged 2026-10-23 (`3bed1f56`); Codex 👍 first round |
| C1 | #1322 | ✅ merged 2026-10-23 (`8661a732`) and released in v2.31.0. One Codex round: the tide continuation now reads the tide's own span. Not seen in the real app behind sign-in |
| M1 | #1328 | ✅ merged 2026-10-23 (`ebf1be9e`); Codex 👍 on the first round. Q13 applied; the label-obstacle numbers are below |
| M2 | #1329 | ✅ merged 2026-10-24 (`5717cbb1`); Codex 👍 on the first round. The label-obstacle numbers are below |
| M3 | — | built 2026-10-24 by Sonnet. Four review lenses, one of them on the sweep alone; one fix round. Fixes: expiry never fights a door, no night reading, the exemption scoped to HW↔solar, the breadcrumb word, the counts footer, and tick placement |
| M4 | — | after M2, if Q5 |

**M1 label-obstacle measurement** (2026-10-23):
- Method: headless Chromium, the dev-seed roster, `scripts/measurements/label-obstacle/` with the
  new `topics`, `topicsEmpty` and `seasTopics` surfaces. The real `MapTopicChips` was mounted inside
  the real `.wf-map-chrome-tl` with three chips and none selected.
- Each figure is labels removed from the placed set, against the window control alone.
- The phone rows apply Q13 (no chips while the big-seas line shows) and pass `isMobile`, so the
  phone empty state draws nothing.

| Frame | topics | big seas | big seas + topics |
|---|---|---|---|
| desktop-1280 @0 / @175 | −3.6% / −1.8% | −7.6% / −6.3% | −11.9% / −11.2% |
| laptop-1024 @0 / @175 | −6.1% / −6.3% | −9.8% / −12.1% | −12.2% / −18.4% |
| tablet-820 @0 / @175 | −1.7% / −4.8% | −8.0% / −11.5% | −11.4% / −15.5% |
| phone-390 @0 / @175 | 0.0% / 0.0% | −16.7% / −21.0% | −16.7% / −21.0% (Q13) |

Before Q13, the phone big-seas + topics figures were −23.2% / −29.8%.

Not measured:
- a selected chip;
- the sky-wide line's extra row;
- `PinsLayer`'s own obstacle list.

**Known gap, recorded rather than fixed:** a topic with no solar anchor sits on no window, so it gets
no Map chip. That covers STORM_SURGE when its `eventType` is null, and CLEARANCE, which only the
simulation produces. Plan cards behave the same way (`keysFor`), but the Coming up lanes still list
such a topic by its date (§1.3).

**M2 label-obstacle measurement** (2026-10-24):
- Same harness as M1. The fixture has five rows: Big seas, Dust, Aurora, King tide and Inversion.
- The footer is lifted only when it overlaps, as the app does it.
- The open/closed state is forced per surface. In the app, a frame shorter than 720px starts collapsed when no preference is stored.
- Each figure is labels removed, against the window control alone. The last two columns are against the tide strip alone.

| Frame @0 / @175 | strip open | strip closed | tide strip alone | tide + collapsed header | tide + open (unreachable) |
|---|---|---|---|---|---|
| desktop-1280 | −14.4 / −19.2 | −2.9 / −3.6 | −11.9 / −14.3 | −2.9 / −2.6 | −13.5 / −25.0 |
| laptop-1024 | −19.1 / −24.2 | −5.3 / −9.2 | −16.3 / −19.3 | −1.5 / −4.2 | −17.0 / −37.1 |
| tablet-820 | −10.7 / −14.7 | −1.7 / −2.4 | −8.0 / −8.7 | −2.6 / −6.5 | −12.4 / −16.1 |
| phone-390 | not mounted (M4) | — | — | — | — |

The default state decides which column applies to each frame:
- desktop-1280@175 (625px) and laptop-1024@175 (545px) start collapsed, so the closed column
  applies.
- Frames 720px or taller start open: −10.7% to −19.1%.
- Q8's forced collapse holds the tide-strip case to between −1.5% and −6.5% on top of the tide strip
  alone. An open strip there would cost up to −37.1%.
