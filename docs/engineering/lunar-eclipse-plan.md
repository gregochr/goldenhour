# Lunar eclipse — extending the ECLIPSE almanac topic: implementation plan

**Source**: the design bundle vendored at `docs/design/lunar-eclipse/` — `README.md` (the spec),
`Lunar Eclipse.html` (the design of record: four screens, the solar-vs-lunar comparison, the
catalogue, four open questions), `Coming Up.html` (the README §6 plain-language copy change),
`Plan Tide Summary.html` (the lunar chip injected into the tide-plan prototype), eight screenshots,
and the solar handoff it extends under `reference/`. `VENDORING.md` records what was copied and what
was not. Read the spec before any phase; **this document is the *port* plan**: what the codebase
already has, what is genuinely new, where the spec and the code disagree on purpose, and how the
work cuts into single-session phases for **Sonnet** sessions. Paste-ready kickoff prompts are
`docs/engineering/lunar-eclipse-prompts.md`.

**The one-sentence version.** The solar eclipse (#485, #489) gave the app an `ECLIPSE` hot topic,
an almanac entry and a catalogue of seeded ephemeris; a lunar eclipse fits the same slots with
three differences the design names — the shadow's depth is the same everywhere, so the only site
question is where the moon sits; it happens before dawn, so the decision is the evening before; and
the moon is racing the brightening sky to the horizon, which is the one new component, **the dawn
race**. Everything else is existing furniture with new data — and two pieces of furniture the spec
assumes (a promoted strip and a west-horizon site score) **do not exist in this codebase**, which is
the first thing §1 records.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§7) → commit. Backend gate: `./mvnw clean verify -Dtest='!**/integration/**'
-DfailIfNoSpecifiedTests=false`, gated on the exit code. Frontend gate before any push: `npm run
lint && npm test && npm audit --audit-level=high && npm run build`. Never push; never tag. Every
phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry (never `CHANGELOG.md`'s `[Unreleased]`
directly). Paste the relevant section of THIS plan and the spec into every review agent's prompt.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *eclipse*, *coming up*,
*topic*, *chip*, *popup*, *badge*. Checked 2026-09-23 at plan time: none open touching these files.

---

## §0 Status

**Status: NOT STARTED.** Plan written 2026-09-23 against `main` at `16f7c29f` (#908). Today's date
matters more than usual for this plan: the worked example (Friday 28 August 2026) is **already
past**, so no phase can be verified against a live event — §7 says how it is verified instead.

| phase | scope (one session each) | state | branch |
|---|---|---|---|
| L0 | Backend: `LunarEclipseCatalog` + `LunarEclipseCalculator` (seeded contacts, per-location moon geometry, eligibility) | not started | `feature/lunar-l0-catalogue` |
| L1 | Backend: `LunarEclipseHotTopicStrategy`, `LunarEclipseAlmanacSource`, Coming-up scoring, rarity rank, simulation template | not started | `feature/lunar-l1-topic` |
| L2 | Backend: per-location `BriefingSlot.eclipse` sight (the dawn-race data) + simulation parity | not started | `feature/lunar-l2-sight` |
| L3 | Frontend: type registries, channel CSS, the clocked chip on the Plan card | not started | `feature/lunar-l3-chip` |
| L4 | Frontend: popup topic row (aside, info tip), `DawnRace` component + pure geometry | not started | `feature/lunar-l4-race` |
| L5 | Frontend: Coming up lunar row (glyph, `next` line, `aside` slot) | not started | `feature/lunar-l5-coming-up` |
| L6 | **Independent**: Coming up plain-language copy sweep (README §6), backend + frontend | built, in review | `feature/lunar-l6-plain-copy` |
| L7 | Location sheet + map callout per-location line; docs sweep (CLAUDE.md, this plan's §4 close-out) | not started | `feature/lunar-l7-sweep` |

Dependency order: L0 → L1 → L2 → L3 → L4 → L5 → L7. **L6 depends on nothing and may run first or
in parallel with L0–L2**; it shares `ComingUpAssembler.java` with L1 (different methods), so
whichever lands second rebases. L5 depends on L1 (the served fields) and on L6 if L6 has merged
(the since-line copy), else it lands against the old copy and L6 adjusts.

---

## §1 What the codebase already has — reconciliation against the spec's assumptions

The spec's §7 and `Lunar Eclipse.html` §06 assume the solar feature shipped as its own README
described. It did not, entirely. Every row here was verified against the tree on 2026-09-23; line
numbers may drift, so re-verify a citation before relying on it.

| # | the spec assumes | what is true here | consequence |
|---|---|---|---|
| 1 | An `ECLIPSE` type with a `body: 'sun'\|'moon'` field switching glyph, safety line and the race | There is **no `HotTopicType` enum and no style catalogue** (`HOT_TOPIC_STYLES` does not exist). A topic's type is a free string keyed independently in seven registries: `TopicRarity.RANK_BY_TYPE`, `HotTopicEventEnricher.EVENT_BY_TYPE`, `PlanWindowProjector.isDayScoped`, `HotTopicSimulationService.ALL_SIMULATIONS`, `ComingUpAssembler.stage()`'s switch, and on the client `badgeChannel` (`utils/windowFirstCards.js:715`), `WHOLE_SKY_TOPIC_TYPES` (`utils/windowFirstTopics.js:81`), `CHANNEL_ICON` (`components/map/WindowControl.jsx:599`), `SWATCH_COLOR` (`utils/comingUpHandoff.js:64`), `FAMILY_GLYPHS`/`TYPE_GLYPHS` (`utils/comingUpGlyphs.js`), and a hand-copied 16-type list in `test/windowFirstTopics.test.js:129`. | **A distinct type, `LUNAR_ECLIPSE`** (almanac type `lunar-eclipse`, Coming-up family `eclipse`), sharing the solar *channel* (colour, glyph family, filter chip). §4 #1 argues it. `badgeChannel` already has a test anticipating a lunar type must not be caught by the substring arms. |
| 2 | "Same `.promo` component as the solar variant" — a promoted strip at the top of Plan, promoted at T−1 18:00 | **Gone.** `WindowFirstPromotedStrip` and `windowFirstPromoted.js` were deleted at plan-matrix M5 (#599); `SOLO_PROMOTION_RANK` survives only in `TopicRarityTest`. Nothing on the Plan tab is page-level except the hazard line (`WindowFirstShell.jsx:1895`). There is no promotion machinery, no T−1 rule. | **§2 of the spec is not built.** The evening-before need is met by the Friday sunrise card, which is on the matrix from T−4 with the eclipse chip first and its clock time on it (L3), and by the popup carrying the race (L4). Reinstating a strip is owner decision §6 Q1. |
| 3 | "Reuse the solar horizon-obstruction test → `clearToDeg`"; "clear to the west 28 of 57"; locations "ranked on the western horizon"; map "pins with a thin ray at 244°" | **No such test has ever existed.** The solar changelog (#485) dropped "clear to the west at 31 of 57" for want of terrain data: no DEM, no elevation service, no horizon profile per location. The only horizon logic anywhere is `EclipseCalculator`'s −0.833° geometric clamp. | Every `clearToDeg` surface is **dropped, not faked** (§4 #3): no third figure, no ranking switch (the spot strip keeps `compareSpots`' rating-then-drive order), no map change. What survives per location is honest and cheap: the moon's altitude and bearing at maximum, and whether it sets in shadow — served on the slot (L2) and printed as the per-spot line (L4/L7). A horizon score is its own increment (§6 Q5). |
| 4 | Per-event contacts "stored once, seeded from ephemeris" | `EclipseCatalog` is a static in-code table of NASA **Besselian elements**, solar-only (`record Eclipse(BesselianElements, Integer nextComparable)`). `EclipseCalculator.circumstances(elements, lat, lon)` reduces them per location — none of it applies to a lunar eclipse, whose contacts are one instant for every observer. | A **separate `LunarEclipseCatalog`** (L0) seeding `umbralMagnitude`, `kind`, the UTC contacts `P1 U1 U2? max U3? U4 P4`, and `nextComparable`. Same pattern (static table, `between`/`on`/`all`), different record. |
| 5 | Per location: `moonAltAtMax`, `moonAzAtMax`, `moonset`, "derived at forecast time from coordinates" | solar-utils 2.1.0 ships `LunarCalculator.calculate(ZonedDateTime, lat, lon) → LunarPosition(altitude, azimuth, illumination, phase, distanceKm)` and `MoonriseMoonsetCalculator.calculate(LocalDate, lat, lon, ZoneId) → (Optional moonrise, Optional moonset)`; both are beans (`AppConfig`). Its ~10′ lunar error is fine for altitude/azimuth and useless for contact times — which is exactly why contacts are seeded (row 4). Existing callers: `AstroConditionsService`, `SupermoonHotTopicStrategy`, `MoonTransitionCalculator`. | `LunarEclipseCalculator.sight(eclipse, lat, lon)` (L0) — altitude/azimuth at max, altitude at U1/U4, moonset/moonrise on the date, the eligibility test, and the visible umbral span. |
| 6 | The eclipse chip "uses the emphasised chip from the tide work" and carries a clock time after a divider | The emphasised variant exists only for the **client-derived** tide chip (`WindowFirstHeatStrip.jsx:1182–1215`, `.wf-hc-tide[data-best]` at `index.css:2140–2163`). Served topic chips render `badge.label` only. The served `Badge` already carries `eventTime`, `note` and `rarityNote` (`BriefingWindow.java:200`) — **and nothing on the client reads any of the three**; their readers went with the strip (row 2). The Badge javadoc says "render one or the other, never both side by side" of the badge's and the window's clock. | L3 adds a `CLOCKED_TOPIC_TYPES` rule: a badge whose time is a *different event's* instant (both eclipse types) prints it after a divider in the emphasised shape. The javadoc is amended, not violated — its reason (two clocks for the *same* solar event differing by minutes) does not apply. The solar chip gains its time too (§6 Q8). |
| 7 | Coming up featured row with a `NEW` flag, kind tag, pick tag, right-aligned figure, why, facts, a `why2` dashed slot, a `next` line and an action | All exist except `why2` and `next`: `WindowComingUpEntry.jsx` renders `isNew`, `kindTag`, `superlative` (the pick tag), `metric`, `prose`, `facts[]`, `threshold`, `action`; `buildEntryView` at `utils/comingUpFeed.js:220`. The since-line (`WindowComingUpSinceLine.jsx`) and the `◆` tab badge (`WindowFirstShell.jsx:1676`, `utils/comingUpArrivals.js`) exist and key on `bits` against served `bands`. | L1 serves the `next` line as a facts row (the accent row the solar `rarity` row already uses) and a new nullable `ComingUpEntry.aside` for the exposure note; L5 renders the aside as the dashed-top slot and gives the type its 🌘 via `TYPE_GLYPHS`. |
| 8 | "No user-facing string shows a surprisal score" | The client already never prints the raw number except through `bitsWord()` (`utils/comingUpConditions.js:77`); **the one real leak is server-composed**: `ComingUpAssembler.mergeEntries`' `joinNote` prints `"… not the sum: 10.8 bits — …"` (L582), and `markScoreNotes` (L664) writes "over the top contour". `WindowComingUpConditions.jsx` prints `bitsWord` at :122 and :208 and "scores are provisional" at :69; `quantLabel` comes from `ComingUpConditionsBuilder.rarityWord`. | L6, independent of the lunar work. |
| 9 | Per-location figures that "redraw the dawn race when you pick a location" | The precedent for per-location, per-window served facts is `BriefingSlot.TideInfo` (`@JsonUnwrapped`, built at `BriefingSlotBuilder.java:227`), read on the client through `buildTideAlignmentIndex(days)` + `lookupForWindow(idx, id, name, date, targetType)` (`utils/locationSheet.js:353, :463`) and mounted as `TideFitBlock` in the popup, the sheet and the callout. | L2 puts the sight on the slot (`BriefingSlot.eclipse`, nullable, NON_NULL), rides the `daily_briefing_cache` JSON with no migration, legacy payloads deserialise to null. §4 #6 says why not on the topic. |
| 10 | The race's twilight gradient "reuses the masthead light rule" | `components/shared/MastheadLight.jsx` exports `buildRuleGradient(stops)` and `RULE_COLOURS` keyed NIGHT_START… NIGHT_END; stop **positions are served** (`GET /api/user/settings/light`, today at home only). The client never does solar maths. | L2 serves each slot's stops for the eclipse date at that location as `{key, time}` using the masthead's own keys; L4's `utils/dawnRace.js` maps them to track positions and feeds `buildRuleGradient`. |
| 11 | Eligibility: moon ≥ 3° at max, or ≥ 3° for ≥ 30 min in the umbra; penumbral never raises | Nothing lunar exists. `SolarEventFreshness.isAhead(...)` is how the solar topic withdraws after last contact. | L0 implements the rule verbatim; **penumbral eclipses are not seeded at all** (§4 #4). L1 withdraws on `U4`. |
| 12 | The tab badge: "UK-visible lunar eclipse mean gap ~900 days → Interrupt band → solid ◆" | `ComingUpScoringProperties.Rarity.eclipseMeanGapDays = 1500.0`; `SurpriseScore.rarity = log2(gap)`, `+1.0` magnitude; bands `list 5.0 / announce 7.5 / interrupt 10.0`. log2(900)+1 ≈ **10.8 ≥ 10.0** → ◆, as designed. `ComingUpAnnualBadgeCensusTest` pins interrupts = `["eclipse"]` over 1 Sep 2026–31 Aug 2027. | L1 adds `lunarEclipseMeanGapDays = 900.0` + the yml example row. The census year holds only penumbral lunar eclipses (20 Feb 2027, 18 Aug 2027), which are never seeded, so the census should not move — **re-run it, do not assume**. |
| 13 | Dates | `daysAhead`/civil dates are Europe/London (`util/ForecastHorizon`); the aurora night-selection lesson says a `LocalDate` cannot name a night. | A lunar eclipse's `date` is the **London civil date of greatest eclipse** (a 23:30 UTC maximum on the 28th is the 29th in BST); `eventType` from the London hour of maximum exactly as `EclipseHotTopicStrategy.eventType` does (< 12:00 → SUNRISE, else SUNSET). §2.3. |
| 14 | `HotTopicStrategyRegistrationTest` covers every detector | It does not list `EclipseHotTopicStrategy` (pre-existing gap). | L1 adds both eclipse strategies to the `@ValueSource`. |

---

## §2 Decisions taken in this plan (routine calls, made here so no session re-litigates them)

### 2.1 One channel, two types

`LUNAR_ECLIPSE` is its own hot-topic type and `lunar-eclipse` its own almanac type, with Coming-up
`family = "eclipse"`. It takes rarity rank **2** in `TopicRarity.RANK_BY_TYPE` (ECLIPSE stays 1;
SUPERMOON and everything below shift by one — update the hand-copied client list in the same
commit). It shares the eclipse channel on the client: `badgeChannel` returns `'eclipse'` for it
(exact match, placed with the existing exact-match arm, **before** the substring arms), so all five
channel-keyed CSS rules apply unchanged; `CHANNEL_ICON`, `SWATCH_COLOR`, `WHOLE_SKY_TOPIC_TYPES`
gain an entry each; `TYPE_GLYPHS['lunar-eclipse'] = '🌘'` beside the existing `supermoon: '🌙'`
override; `FILTER_CHIPS`' "Sun & moon" already covers the family.

### 2.2 The catalogue (L0)

`LunarEclipseCatalog`, static, same shape as `EclipseCatalog`:

```java
public record LunarEclipse(LocalDate date /* London civil date of max */, Kind kind,
        double umbralMagnitude, LocalDateTime p1, LocalDateTime u1, LocalDateTime u2 /* nullable */,
        LocalDateTime max, LocalDateTime u3 /* nullable */, LocalDateTime u4, LocalDateTime p4,
        LocalDate nextComparable /* nullable */, String nextComparableKind /* nullable */)
enum Kind { PARTIAL, TOTAL }   // penumbral is deliberately absent
```

All instants **UTC** `LocalDateTime`, copied from NASA's eclipse pages
(`eclipse.gsfc.nasa.gov/LEdecade/LEdecade2021.html` and `…2031.html`, or Espenak's
*Five Millennium Canon of Lunar Eclipses*) — take NASA's figures over the design's where they
differ by a minute (the design's "U4 05:52" for 2026-08-28 should be checked against the table).
**Seed every umbral eclipse from March 2025 through 2030 whose umbral phase is above the horizon
somewhere in the British Isles** (the eligibility rule then decides per location). Candidates to
**verify, not trust** — this list is from memory and each row needs a published visibility
statement before it is seeded:

| date (UTC) | kind | notes to verify |
|---|---|---|
| 2025-03-14 | total | sets in eclipse from the UK, morning |
| 2025-09-07 | total | rises eclipsed from the UK, evening — **the design's "first since March 2025" sentence ignores this one; the sentence is derived, so it says whatever the catalogue says** |
| 2026-03-03 | total | check UK visibility at moonset — may be penumbral-only / below horizon; do not seed unless umbral phase is above the UK horizon |
| 2026-08-28 | partial 0.930 | the worked example; U1 02:34, max 04:13, U4 05:52 (design) |
| 2028-01-12 | partial | early-morning maximum, expected UK-visible |
| 2028-07-06 | partial | expected below the UK horizon — verify before seeding |
| 2028-12-31 | total | rises eclipsed, evening; the design's "next from the UK" |
| 2029-06-26 | total | pre-dawn maximum, verify moonset vs umbra |
| 2029-12-20 | total | late-evening maximum, expected fully UK-visible |

Past entries (2025) exist only so the "first visible from here since …" sentence can be derived.
`nextComparable` for each entry is the next **catalogued** UK-visible entry (date + kind), null
for the last; the "next from the UK" line is composed from it and is silent when null.

**Verification tests that catch a mistyped seed** (the solar catalogue's own lesson — every number
checked against something independent): for every entry, `LunarCalculator.calculate(max, 54.5,
−2.5)` reports illumination ≥ 99% (full moon, so a wrong date fails), `u1 < max < u4`, `u2`/`u3`
present iff `kind == TOTAL` iff `umbralMagnitude ≥ 1.0`; for 2026-08-28 at Dunstanburgh (55.49,
−1.59) the moon at max is 7° ± 2° up at 244° ± 4°, and `MoonriseMoonsetCalculator`'s moonset
lies within 10 min of the altitude sign change the calculator finds; for 2028-12-31 from London the
moon rises between U1 and U4 (`risesInShadow`).

### 2.3 Per-location sight and eligibility (L0)

```java
public record LunarEclipseSight(int moonAltAtMax, int moonAzAtMax, String moonAzCardinal,
        LocalDateTime moonset /* London local, nullable */, LocalDateTime moonrise /* nullable */,
        boolean setsInShadow, boolean risesInShadow,
        LocalDateTime visibleUmbraStart, LocalDateTime visibleUmbraEnd /* London local */,
        boolean visible)
```

`visible` = altitude at max ≥ 3°, **or** altitude ≥ 3° for a contiguous 30 minutes inside
`[u1, u4]` (sample every minute with `LunarCalculator`; ~180 calls per location per eclipse, once
per build — measure, but this is well under a second for the roster). `moonAzCardinal` via the
existing `PromptUtils.toCardinal`. Moonset/moonrise from `MoonriseMoonsetCalculator` on the London
date of the umbral span (a span crossing local midnight needs both dates checked — pin it).
`setsInShadow` = moonset ∈ `[u1, u4]`. Nothing here reads a horizon: "clear to N°" is not a field.

### 2.4 The topic (L1)

Mirror `EclipseHotTopicStrategy` structurally; same `PRIORITY = 4`; constructor
`(LocationRepository, SolarService, SolarEventFreshness, LunarEclipseCalculator)`.

- `date` = the catalogue's London date; `eventType` = `"SUNRISE"` if the London hour of max < 12,
  else `"SUNSET"` (pre-set, so `HotTopicEventEnricher` passes it through); `eventTime` = the
  London `HH:mm` of max.
- Representative = the visible location with the **longest** `visibleUmbraEnd − visibleUmbraStart`
  (ties by name) — the deepest *view*, since the magnitude is the same everywhere.
- `label` = `"Lunar eclipse"` on every Plan surface (§4 #2). Coming-up `title` carries the kind:
  "Total lunar eclipse" / "Deep partial lunar eclipse" (≥ 0.80) / "Partial lunar eclipse".
- `detail` = `"93% in shadow at 05:13, moon 7° above WSW, sets 06:16 still in shadow"` (or
  `"… above WSW, 2h 42 in shadow before it sets"` / no clause when it does not set in shadow).
- `facts`: `directional("max", "93% in shadow · moon 7° up", "WSW", true)`; `metric("in shadow",
  "03:34 → sets 06:16")` or `"03:34 → 06:52"`; `metric("seen from", "41 of 57 sites")` — the
  visible count over the enabled roster, replacing the spec's clear-to-west figure (§4 #3).
- `note` = `"No filter needed — bracket, the shadow is ~10 stops under the lit edge"` (the
  exposure note; **never** `safetyNote`, §4 #5).
- `rarityNote` = `"next from the UK: a total eclipse, Sun 31 Dec 2028"` from `nextComparable`, or
  null. `description` = the copper science paragraph (spec §4's tip, verbatim, with the magnitude
  figure substituted) — it becomes the popup's `(i)`.
- `regions` = distinct regions with a visible location; `withLocations(visibleNames)`.
- Withdrawn when `!freshness.isAhead(u4)`.
- `LunarEclipseAlmanacSource` (`TYPE = "lunar-eclipse"`): one `AlmanacEvent(date, date, ALMANAC,
  "lunar-eclipse", title, detail, metaOf("magnitude", "93% in shadow", "maximum", "05:13 · moon
  WSW 244°, 7° up", "shadow", "in shadow 03:34 → sets 06:16", "seen", "seen from 41 of 57 sites",
  "next", "Next from the UK: a total eclipse, Sun 31 Dec 2028", "since", "September 2025",
  "location", representative), List.of())`. Dates-only when the roster is empty; **absent** when
  nobody sees it. Never a network call.
- `ComingUpAssembler.enrichLunarEclipse`: `family = "eclipse"`, `bits = rarity(900) + 1.0`,
  `metric = "93%"`, `superlative = "sets in shadow"` when the representative's `setsInShadow`,
  `prose` = the spec §1 why-paragraph with figures substituted, `facts` = max · shadow row, the
  `next` row (accent), "figures for <location>", `aside` (new nullable `ComingUpEntry` component,
  §4 #7) = the exposure note, `scoreNote` = `"The first visible from here since <Month YYYY>."`
  (from `since`; `markScoreNotes` already skips an entry that has one — verify, it currently skips
  on `joinNote` only), `action = ("See the plan for d MMM →", "plan", date)`. Strip the consumed
  meta keys as `enrichEclipse` does.
- `HotTopicSimulationService`: a `LUNAR_ECLIPSE` template + `Enrichment` with the 2026-08-28
  Dunstanburgh figures (`"SUNRISE", "05:13"`, the three chips, the note, the next line, **no**
  safety note). `HotTopicSimulationServiceTest` pins that only ECLIPSE carries a warning — keep
  that true.

### 2.5 The slot sight and its light stops (L2)

`BriefingSlot.eclipse` — a nullable `@JsonInclude(NON_NULL)` record (not `@JsonUnwrapped`, to keep
the slot's flat namespace from growing eleven more keys):

```java
public record EclipseSight(String type /* "LUNAR_ECLIPSE" */, int moonAltAtMax, int moonAzAtMax,
        String moonAzCardinal, LocalDateTime maximum, LocalDateTime umbraStart, LocalDateTime umbraEnd,
        LocalDateTime moonset, LocalDateTime moonrise, boolean setsInShadow, boolean risesInShadow,
        String race /* "DAWN" | "DUSK" | null */, List<LightStop> stops)
public record LightStop(String key /* a MastheadLight RULE_COLOURS key */, LocalDateTime time)
```

All times **London local** `LocalDateTime` (the slot's `solarEventTime` precedent), formatted on
the client with the formatter the card already uses for `solarEventTime` — geometry needs instants,
labels need `HH:mm`, and serving both strings and instants would be two sources for one clock.
Built at `BriefingSlotBuilder.java:227`'s seam beside `TideInfo`, only when
`LunarEclipseCatalog.on(slotDate)` matches **and** the slot's `targetType` equals the eclipse's
`eventType` (an eclipse lands on one window of its day, exactly as the topic does). `race` =
`DAWN` when the eclipse is a SUNRISE one and `umbraEnd > nauticalDawn − 60 min` at the location
(the sky is brightening while the moon is still in shadow); `DUSK` for the mirror (SUNSET and
`umbraStart < nauticalDusk + 60 min`); else null — a high-moon eclipse at 22:42 (20 Dec 2029) has no
race and the popup shows facts only. `stops` for DAWN: `NAUTICAL_DAWN, CIVIL_DAWN, SUNRISE,
GOLDEN_MORNING_END`; for DUSK: `GOLDEN_EVENING_START, SUNSET, CIVIL_DUSK, NAUTICAL_DUSK` — from
`SolarService`/`SolarCalculator` for the location on the date. Rides `daily_briefing_cache` JSON;
no migration; a row written before the field exists deserialises to null. `List<LightStop>`, never
an array (the equality-comparand rule in CLAUDE.md).

**Simulation parity.** When `HotTopicSimulationService` is active with the lunar template, the
builder attaches the template's fixed sight (2026-08-28 Dunstanburgh figures re-dated onto the
slot's date, same clock times) to every slot of the simulated window — this is the **only** way
the race can be seen in a browser before the next live eclipse (§7). Record it in the template's
javadoc as a verification affordance, not a product path, and gate it on `isSimulated()` exactly
as the aurora simulation is gated.

### 2.6 The clocked chip (L3)

`utils/windowFirstTopics.js` exports `CLOCKED_TOPIC_TYPES = ['ECLIPSE', 'LUNAR_ECLIPSE']` and
`chipClock(badge)` → `badge.eventTime` when the type is listed and the time is non-null, else null.
`WindowFirstHeatStrip` renders a listed chip as `.wf-hc-tw.wf-hc-clocked` — label, then a divider
(`border-left: 1px solid rgba(196,120,127,.45); padding-left: 6px`) and the time — with the
emphasised shape (`padding: 1px 8px; border-radius: 999px; background: rgba(196,120,127,.14);
box-shadow: inset 0 0 0 1px rgba(196,120,127,.42); color: #E3AEB3`, mono 10.5px/600) via
`data-channel`, mirroring `.wf-hc-tide[data-best]`. `title` = `${label} — ${detail}` as today. The
`sr-only` sentence (`WindowFirstHeatStrip.jsx:997–1015`) appends "Lunar eclipse at 05:13". The
card's own time stays the window's. Amend the `Badge` javadoc: the "never both" rule is scoped to a
badge whose clock is the *same* solar event as the window's; a listed type's clock is a different
event and the chip states it.

### 2.7 The popup (L4)

- Header pill: no change beyond the channel (`.wf-wsh-tpb[data-channel="eclipse"]` already exists).
- `WindowTopicRows`: (a) a new **aside** renderer — `badge.note` as a full-width italic serif line
  (`flex-basis: 100%`, the `.wf-trow-warn` shape, prefixed `◑` for the eclipse channel only) for
  **any** badge carrying a note. This reveals the solar note ("a clear low western horizon …")
  that has been write-only since M5 — intended, recorded in §4 #8. Not dismissible, no storage
  (§4 #9). (b) `InfoTip` already mounts when `topic.description` exists; the lunar description is
  the copper paragraph. Heading = the label, as every other tip (§4 #10).
- `DawnRace` (`components/DawnRace.jsx`, geometry in `utils/dawnRace.js`): mounted by
  `WindowSheetDialog` between the topic rows (`:608`) and the tide row (`:610`) when a `LUNAR_ECLIPSE`
  row is present **and** the chosen spot's slot carries `eclipse.race != null`. The spot is the
  window's `bestReach` spot when one exists, else the first ranked spot; the caption names it
  (`THE DAWN RACE · in shadow against the brightening sky · Dunstanburgh`). If `WindowSpotStrip`
  already exposes a selection callback the race follows it; if not, **do not add one in this
  phase** — caption only, recorded in §4 #11. Legend row: `in the umbra` / `below the horizon`.
- `utils/dawnRace.js` (pure, tested to the boundary): `raceModel(sight)` → `{trackStart,
  trackEnd, position(t), umbra:{from,to}, hatch:{from,to}|null, markers:[{key,t,label}],
  ticks:[{key,t,label}], gradient}`. Track start = `min(umbraStart, first stop)` floored to the
  hour; end = `max(min(umbraEnd, moonset ?? umbraEnd), sunrise) + 15 min`. Positions are
  `(t − start) / (end − start)`. The umbra band runs `umbraStart → min(umbraEnd, moonset)`; the
  hatch runs `moonset → umbraEnd` when `setsInShadow`. Gradient = `buildRuleGradient` over the
  served stops mapped to percent positions, with the track's start as `NIGHT_START` and end as
  `GOLDEN_MORNING_END` when that stop is beyond the end. DUSK mirrors it with the evening keys; the
  component is direction-agnostic because the stops come served and sorted. A total eclipse's
  `u2→u3` gets **no second band** (§6 Q3) — the totality interval is a fact row instead.
- Label row: `03:00`, `03:34 enters shadow`, `05:13 max · 7°`, `06:11 sunrise`, `06:16 moonset`
  (mono 10px `--ink-3`, values 10.5px/600, max in `#D79AA0`). Phone (< 560px): hide the track-start
  and sunrise labels and the phase-tick labels. The track is `aria-hidden`; the accessible answer is
  one sentence built from the same model ("In shadow from 03:34, maximum 05:13 with the moon 7° up,
  sunrise 06:11, sets 06:16 still in shadow.") — the tide-run rule, do not hide it.
- Footer: unchanged (§4 #12).

### 2.8 Coming up (L5)

`WindowComingUpEntry` renders `entry.aside` as the dashed-top `why2` slot (Newsreader 13.5px,
`--ink-2`, `border-top: 1px dashed var(--border)`), after the facts and before the threshold — a
generic slot, not a lunar branch. The `next` row arrives as an accent facts row from L1 (nothing to
build). `TYPE_GLYPHS['lunar-eclipse'] = '🌘'`. `superlative` prints `SETS IN SHADOW` as any pick
tag does. The since-line's lunar sentence is the served `scoreNote`; the headline shape is L6's.

### 2.9 Plain-language copy (L6, independent)

Backend: `mergeEntries` → `joinNote = "One perigee causes both. Counted as one event, not two — the
<title> carries it."`; `markScoreNotes` → rarity-carried `"It comes round about once <gapWord>,
which is rare enough to flag on its own."` (`gapWord` from the entry's mean gap: *a fortnight / a
month / a year / every two to three years* — table it, test the boundaries), magnitude-carried
`"An unusually big one — <metric> against the usual <median>."` when both figures are on the entry,
else `"An unusually big one carries it, not rarity."`; `ComingUpConditionsBuilder.rarityWord` →
`gapWord` frequency phrase (`about one a week`, `most mornings` …) so `quantLabel` reads as the
design's condition lines; occurrence `reason` loses "max w/". Frontend: the since-line headline is
`◆ Rare — the <title> entered the window, <date>. <scoreNote>` for interrupt and `<N> announced —
the <title> entered the window, <date>. <scoreNote>` otherwise (the design's two banners);
`bitsWord` becomes a three-word scale for the occurrence column — `exceptional` (≥ 7), `above
usual` (≥ 4.5), `typical` — and the peak line reads `<valueLabel> — <word>`; "scores are
provisional" → "figures are provisional"; the dust row fact `heaviest of N since <month>`; the
coincidence card `Counted as one event, not two.` The screen-reader badge label stays. Scoring
logic **unchanged** — every test on `bits` values stays green; only strings move.

---

## §3 Phases

Each phase: read this plan's §0/§1/§2 and the spec; branch off up-to-date `main`; scope exactly as
listed; tests per the side's standards doc; gate; review; changelog entry; update this table's
row and the Status line **in the same commit**; never push.

### L0 · Catalogue + calculator (backend)
Files: `service/LunarEclipseCatalog.java` (new), `util/LunarEclipseCalculator.java` (new),
`model/LunarEclipseSight.java` (new), tests `LunarEclipseCatalogTest`, `LunarEclipseCalculatorTest`.
Exit: §2.2's verification tests green for every seeded entry; the eligibility rule pinned at 3° and
30 min boundaries (a moon at 2.9° for 40 min is invisible; 3.1° for 29 min is invisible; 3.1° for
30 min is visible); a span crossing local midnight resolves moonset on the right date; JaCoCo's 80%
per-class holds without deleting guards. No wiring into any live path.

### L1 · Topic, almanac source, scoring (backend)
Files: `service/LunarEclipseHotTopicStrategy.java`, `service/LunarEclipseAlmanacSource.java` (new);
`TopicRarity` (rank 2, shift), `ComingUpAssembler` (constant, case, `enrichLunarEclipse`),
`ComingUpScoringProperties` + `application-example.yml` (`lunar-eclipse-mean-gap-days: 900.0`),
`ComingUpEntry` (+ `aside`), `HotTopicSimulationService` (template), `HotTopicStrategyRegistrationTest`
(+ both eclipse strategies). Tests mirror `EclipseHotTopicStrategyTest`/`EclipseAlmanacSourceTest`
with a 2026-08-28 fixture and a stubbed clock: headline, facts, note, next line, representative,
silence (no roster → dates-only; nobody sees it → absent; a 2026-09-01..04 window → nothing),
withdrawal after U4, `eventType` SUNRISE for a 04:13 UTC max and SUNSET for a 16:52 UTC one.
`ComingUpAssemblerTest`: family `eclipse`, bits ≥ 10, not interim, `aside` present, `scoreNote` is
the since-sentence. **Run `ComingUpAnnualBadgeCensusTest` and report its result verbatim** — the
expectation is unchanged; if it moves, that is a re-census, stop and say so. Frontend: none.

### L2 · The slot sight (backend)
Files: `BriefingSlot` (+ `eclipse`), `BriefingSlotBuilder` (the seam at :227), a small
`EclipseSightAssembler` if the builder's constructor is already crowded, `HotTopicSimulationService`
(simulation parity, §2.5). Tests: the sight is attached only on the eclipse's own window of its
day; null elsewhere; `race` DAWN/DUSK/null across the three seeded shapes; stops carry exactly the
four keys; a legacy cache row (JSON without the field) deserialises; the simulated sight appears
only under `isSimulated()`. `JsonDateFormatContractTest`-style check that `LocalDateTime` serialises
in the wire format the slot's `solarEventTime` already uses (the two-Jackson-graphs warning).

### L3 · Registries + the clocked chip (frontend)
Files: `utils/windowFirstCards.js` (`badgeChannel`), `utils/windowFirstTopics.js`
(`WHOLE_SKY_TOPIC_TYPES`, `CLOCKED_TOPIC_TYPES`, `chipClock`), `components/map/WindowControl.jsx`
(`CHANNEL_ICON`), `utils/comingUpHandoff.js` (`SWATCH_COLOR`), `utils/comingUpGlyphs.js`
(`TYPE_GLYPHS`), `components/WindowFirstHeatStrip.jsx` (chip render + sr-only), `index.css`
(`.wf-hc-clocked`), `test/windowFirstTopics.test.js` (the 17-type list), `test/windowFirstCards.test.js`,
`test/WindowFirstHeatStrip.test.jsx`. Backend: the `Badge` javadoc amendment. Exit: a
`LUNAR_ECLIPSE` badge renders in the eclipse channel with `05:13` after a divider and leads the
topics line (rank 2 beats everything but ECLIPSE); a `SUPERMOON` badge with an `eventTime` renders
**no** clock; the sr-only sentence names the time; `getComputedStyle` in the browser confirms the
inset ring and the `#E3AEB3` ink (the pruned-token failure mode).

### L4 · Popup: aside, tip, the dawn race (frontend)
Files: `components/WindowTopicRows.jsx` (aside), `components/DawnRace.jsx` + `utils/dawnRace.js`
(new), `components/WindowSheetDialog.jsx` (mount), `utils/locationSheet.js` (`buildEclipseIndex` +
reuse `lookupForWindow`), `index.css`, tests `dawnRace.test.js` (geometry to the boundary: start
floor, end clip, hatch only when it sets in shadow, DUSK mirror, positions monotone in `[0,1]`),
`DawnRace.test.jsx`, `WindowTopicRows.test.jsx`, `WindowSheetDialog.test.jsx`. Exit: the race
mounts only with a lunar row **and** a served `race`; the accessible sentence is present and the
track is `aria-hidden`; `(i)` click does not close the dialog (`stopPropagation`); phone width
hides the two labels; the solar note now renders as an aside (state it in the changelog).

### L5 · Coming up row (frontend)
Files: `components/WindowComingUpEntry.jsx` (aside slot), `utils/comingUpFeed.js`
(`buildEntryView` passes `aside`), `index.css`, tests. Exit: a served lunar entry renders glyph 🌘,
`NEW`, `ALMANAC`, `SETS IN SHADOW`, `93%`, the why, the three facts, the accent `next` row, the
dashed aside and the action; an entry without `aside` renders no slot; the accessible name has
text-node separators between the new sections (the P3a lesson).

### L6 · Plain-language copy (backend + frontend, independent)
Files: `ComingUpAssembler` (`mergeEntries`, `markScoreNotes`, `gapWord`), `ComingUpConditionsBuilder`
(`rarityWord`, occurrence `reason`), `WindowComingUpSinceLine.jsx`, `WindowComingUpConditions.jsx`,
`utils/comingUpConditions.js` (`bitsWord` scale), tests on both sides. Exit: `grep -rn "bits"` over
`frontend/src/components` and the two backend classes finds no user-facing string; every `bits`
**value** assertion in `ComingUpAssemblerTest`/`ComingUpAnnualBadgeCensusTest` unchanged; the
since-line's two banner shapes pinned by accessible name.

### L7 · Sheet + callout line, docs sweep
Files: `components/LocationFourDaySheet.jsx` and `components/map/MapCallout.jsx` (a sibling block
after `TideFitBlock`: `◑ moon 7° up WSW at max · sets 06:16 in shadow`, from the slot via the L4
index), tests; `CLAUDE.md` (the Almanac section's source count, a Lunar eclipse bullet under What's
Built, the Backend-heavy bullet's licensed-class note that `dawnRace.js` is filter/map/select over
served instants — the sparkline precedent — not a derivation); this plan's §4 closed out against
what shipped (the tide-plan C4 precedent). Exit: `docs/engineering/lunar-eclipse-plan.md` Status
reads COMPLETE with every row `built, in review` or `merged`.

---

## §4 Disagreements with the spec, on purpose

1. **Two types, one channel** (spec: one `ECLIPSE` type with `body`). Every registry in this
   codebase is keyed on the type string, so a `body` field would be threaded through `HotTopic`,
   `Badge`, `AlmanacEvent` and `ComingUpEntry` *and still* branched on in every switch — more
   plumbing for the same touch count. The spec's reasons for one type were a shared scorer (does
   not exist), a shared strip (does not exist) and a shared accent (a channel, which the client
   already maps several types onto — `tide` covers three). `badgeChannel`'s own test anticipated
   this.
2. **The Plan-tab label is "Lunar eclipse"**, not "Partial lunar eclipse" (chip and popup row).
   The badge has no short label and the chip is `nowrap` with an ellipsis; the kind rides `detail`
   and the Coming-up title. Cheaper than a `shortLabel` on three records.
3. **No `clearToDeg`, anywhere** — no "28 of 57 clear to the west", no west-horizon ranking, no
   per-spot "clear to 1° · over the castle" line, no foreground note, no map pins-with-rays. Dropped
   as the solar handoff dropped it, for the same reason: no horizon data. The third fact is `seen
   from 41 of 57 sites` (true, derived), the per-spot line states the moon's altitude and bearing
   at maximum and whether it sets in shadow, and the spot strip keeps rating-then-drive. §6 Q5.
4. **Penumbral eclipses are not seeded**, rather than seeded and suppressed. The spec's rule is
   "never raise a topic"; a row that can never fire is a row a future session un-suppresses.
5. **The exposure note rides `note`, never `safetyNote`.** `safetyNote` is the un-gated,
   non-dismissible field whose rendering exists for the sun; the design is explicit that the lunar
   note "is not a safety item". `HotTopicSimulationServiceTest` keeps pinning that only ECLIPSE warns.
6. **Per-location figures on the slot, not on the topic.** The popup, the sheet and the callout
   already look per-location, per-window facts up through `lookupForWindow`; a list on the topic
   would be a second lookup path for the same shape. The topic keeps only roster-level facts.
7. **`ComingUpEntry.aside` is a new nullable component** rather than reusing `threshold`, which has
   its own semantics and class. Generic, so a later type may use it.
8. **The aside renderer reveals the solar note.** `badge.note` has been served and unread since M5;
   rendering it for every badge is the honest fix and the changelog says so.
9. **The exposure note is not dismissible.** The spec allows it; the app has no per-topic dismissal
   pattern and no `localStorage` suppression anywhere on topics. Adding one for a note is furniture.
10. **The `(i)` heading is the label**, not "WHY IT TURNS COPPER, NOT BLACK". `InfoTip` heads every
    tip with the badge label; the copper sentence opens the body instead.
11. **The race draws for the best-reachable spot, captioned**, unless the spot strip already has a
    selection callback. "Tap a location to redraw" is a new interaction on a strip that has none.
12. **The popup footer is unchanged.** The spec's `28 of 57 sites clear to the west` is #3's; `◑ no
    filter · bracket` is already the aside; `Show on map` exists.
13. **No promoted strip and no T−1 promotion rule** (§1 row 2, §6 Q1).
14. **The weather verdict is the window's**, not a WSW-sector low-cloud verdict (§6 Q6).
15. **Times ride as London `LocalDateTime` and are formatted on the client** with the existing
    formatter — the Plan payload's convention — rather than the Coming-up feed's "no number
    re-formatted in the browser" rule, which governs a different payload.
16. **(L6) §2.9's dust "heaviest of N" line is not built.** The design's plain-language copy
    section names a dust-row fact, `heaviest of 12 since June`, as an example of "no more bits" —
    but nothing in this codebase promotes dust to a chronology `ComingUpEntry` at all (`grep -n
    "DUST\|dust" ComingUpAssembler.java` has no hits): dust exists only as a standing
    `ComingUpCondition`, and its "heaviest of N" claim would require a new comparison (is the
    forward peak genuinely bigger than every recent burst?) that L6's copy-sweep scope — replace
    strings, do not change what is derived — does not license. `ComingUpConditionPeak.valueLabel`
    keeps its plain "AOD 0.55" measurement instead. A future phase may add the fact properly, as a
    served comparison rather than an assumed superlative.

---

## §5 Served contracts (what each client reads)

| surface | reads | from |
|---|---|---|
| Plan card chip | `badge.type`, `badge.label`, `badge.eventTime`, `badge.detail`, `badge.rarityRank` | `GET /api/briefing` → `windows[].badges[]` |
| Popup topic row | `+ badge.facts`, `badge.note`, topic `description` | same |
| Dawn race | `slot.eclipse` for the chosen spot's slot on that window | `days[].eventSummaries[].regions[].slots[]` |
| Sheet / callout line | `slot.eclipse` via `lookupForWindow` | same |
| Coming up row | `entry.title/metric/superlative/prose/facts/aside/scoreNote/action/bits/enteredWindow` | `GET /api/almanac` |
| Tab badge / since-line | `bits` vs `bands`, `enteredWindow` vs `comingUpLastSeenDate` | same + `GET /api/user/settings` |

Nothing per-user rides either payload; both stay ETag-revalidated.

---

## §6 Owner decisions

Taken by this plan as defaults; each reversible, each with its cost stated.

- **Q1 — Promoted strip.** *Default: not rebuilt.* The strip was retired at M5 by owner call; the
  evening-before need is met by the card (chip first, clocked) and the popup. Reinstating it is a
  Plan-tab furniture decision (page-level element above the matrix, the hazard line's slot) worth
  one session plus a promotion rule for night-time almanac events — and it would carry the solar
  strip back too. The spec's own Q2 offers popup-only as the alternative; this plan takes it.
- **Q2 — Dawn race placement.** *Follows Q1: popup only.*
- **Q3 — Totals.** *Default: one umbra band; totality (`u2 → u3`) as a fact row.* The second band
  is undesigned. 31 Dec 2028 enters the 90-day window in early October 2028; design it then.
- **Q4 — Best bet lift.** *Default: no.* The pick ranks on light, server-side, and this plan
  re-scores nothing. A clear-west eclipse lifting a poor-light window is a Best-pick maths change,
  which the Backend-heavy bullet bans on the client and which the advisor would need to learn.
- **Q5 — West-horizon scoring.** *Default: not built.* If wanted, it is its own increment (the
  solar handoff's option 2, a DEM raycast per location on a bearing) serving both eclipse types,
  low-sun alignments and the supermoon rise; it would restore every surface #3 dropped.
- **Q6 — Sector cloud verdict.** *Default: not built.* The forecast pipeline samples the solar
  azimuth cone; a WSW-sector low-cloud verdict is a pipeline change the solar eclipse did not make
  either.
- **Q7 — Lite gating.** *Default: the topic's science facts follow the solar precedent (gated);
  the per-slot sight is ungated like `TideInfo`.* The race is not premium detail; the figures on it
  are on the chip's tooltip anyway.
- **Q8 — The solar chip gains its clock.** *Default: yes.* `CLOCKED_TOPIC_TYPES` lists both; the
  reasoning (a different event's instant) is identical and a lunar-only rule would be a special
  case pretending to be a principle.
- **Q9 — The "since" sentence.** *Default: derived from the catalogue*, so it will say "since
  September 2025" if 7 Sep 2025 is seeded — the design's "March 2025" copy is not authored in.

---

## §7 Browser verification

There is no live lunar eclipse until the first seeded future entry (expected January 2028), so the
recipe is: the local backend (`./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`,
port 8083), the seeded local roster with cached ratings (the memory file
`project_local_browser_verification.md` has the recipe), then `POST` the admin hot-topic simulation
with the `LUNAR_ECLIPSE` template (L1) — which, after L2, also attaches the fixed sight to the
simulated window's slots. Verify, and say which of these were **seen** versus **tested**: the chip
leads the Friday-sunrise card with `05:13` after the divider (L3); the popup row, `(i)`, the aside
and the race at desktop and 390px (L4); the Coming up row needs the almanac path, which the
simulation does not reach — L5 is verified with a Vitest fixture and a backend
`LunarEclipseAlmanacSourceTest` run at a stubbed 2026-06-01, and **seen** only by temporarily
stubbing the clock locally (never committed). State that plainly in the phase's changelog entry.

---

## §8 Docs to update at L7

- `CLAUDE.md`: the Almanac section's "six implementations" becomes seven and names
  `LunarEclipseAlmanacSource`; a **Lunar eclipse** bullet under What's Built (catalogue, sight,
  race, the two-types-one-channel rule, the dropped `clearToDeg`, the Q1 default); the
  Backend-heavy bullet gains one line naming `dawnRace.js` as filter/map/select over served
  instants (the `comingUpSparkline.js` precedent), **not** a new licensed class.
- This plan: §0 Status → COMPLETE, §4 closed out against what shipped, §6 decisions marked
  *taken* or *still open*.
