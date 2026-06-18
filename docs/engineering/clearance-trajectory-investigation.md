# Clearance trajectory (#3) — Step 0: cost of extending the trend to mid/high

**Grounded in** [`clearance-investigation.md`](docs/engineering/clearance-investigation.md)
(read it first). That investigation resolved clearance as NOT a hot topic: the
clearance *outcome* already scores 4–5 via the point-in-time rules, but the
clearing *trajectory* + canvas-persistence is unrewarded — the `T-3h…event`
series is printed under `CLOUD APPROACH RISK` with a `[BUILDING]`-penalty-only
instruction set.

We chose approach **#3** (low + mid/high trajectory) over **#2** (low-only)
because only a mid/high trajectory can tell **canvas persisting** (dramatic
clearance) from **canvas dissolving** (sky going to bald blue → CLEAR-SKY-CAP).
This Step 0 asks one question: **is widening `SolarCloudTrend` to carry mid/high
snow-shaped-routine, or entangled?**

---

## Cost verdict (the headline)

**Snow-shaped-routine — and cheaper than snow.** Widening the trend to mid/high is:

- **No new fetch.** The cloud-only batch that feeds the trend already requests
  `cloud_cover_low,cloud_cover_mid,cloud_cover_high` and the response arrays are
  already in scope at extraction time. This is the snow precedent, *better*: snow
  needed new API params; here the mid/high data is **already fetched and already
  read elsewhere in the same method's neighbours**.
- **No migration.** The prompt reads the **live in-memory** trend, never the
  persisted columns. The V51 `solar_trend_*` columns are a separate
  write-only-for-the-DTO path. The #3 prompt change touches no persisted column.
- **Three small in-memory touch-points:** widen the slot record, widen the
  extraction loop to also read mid/high (data in hand), widen the prompt print +
  add the clearing instruction. All in `model/` + `OpenMeteoService` +
  base `PromptBuilder`. No entanglement.

**Go for #3.** The data half is routine; the cost is concentrated entirely in
the (sensitive) prompt-calibration pass, which is exactly where we wanted it.

---

## Section 1 — `SolarCloudTrend` as it stands

**The model** ([`SolarCloudTrend.java`](backend/src/main/java/com/gregochr/goldenhour/model/SolarCloudTrend.java)):

```java
record SolarCloudTrend(List<SolarCloudSlot> slots)
  record SolarCloudSlot(int hoursBeforeEvent, int lowCloudPercent)   // low ONLY
  boolean isBuilding()   // peak(low) − earliest(low) ≥ 20 pp
```

Mid/high are **absent entirely** — the slot carries `lowCloudPercent` and
nothing else. `isBuilding()` computes `peak − earliest` over the low series with
a 20 pp inline threshold.

**The extraction** ([`OpenMeteoService.extractSolarTrend`, ~1090](backend/src/main/java/com/gregochr/goldenhour/service/OpenMeteoService.java:1090)):

```java
List<Integer> lowCloud = forecast.getHourly().getCloudCoverLow();   // low only captured
for (int h = TREND_HOURS_BACK; h >= 0; h--) { ... slots.add(new SolarCloudSlot(h, lowCloud.get(idx))); }
```

It pulls **only** `getCloudCoverLow()`. But the same `forecast` object exposes
`getCloudCoverMid()` and `getCloudCoverHigh()` — **and the directional-sampling
code a few lines up in the very same class already reads all three** off the
identical `fetchCloudOnlyBatch` responses
([OpenMeteoService.java:522-524, 832-833](backend/src/main/java/com/gregochr/goldenhour/service/OpenMeteoService.java:522)).
The mid/high hourly arrays are unquestionably in scope at extraction time.

Confirmed at the source: the batch fetch param set is
`CLOUD_ONLY_PARAMS = "cloud_cover_low,cloud_cover_mid,cloud_cover_high"`
([OpenMeteoClient.java:56](backend/src/main/java/com/gregochr/goldenhour/service/OpenMeteoClient.java:56)),
used by `fetchCloudOnlyBatch`
([OpenMeteoClient.java:251](backend/src/main/java/com/gregochr/goldenhour/service/OpenMeteoClient.java:251)),
which is the source for `extractSolarTrend(responses.get(0), …)`.

**Widening is therefore "also read the two arrays already in the response and
add two fields to the slot record."** Exactly the snow shape (data already
present, just not captured).

**The wrapper** ([`CloudApproachData.java`](backend/src/main/java/com/gregochr/goldenhour/model/CloudApproachData.java)):
`record CloudApproachData(SolarCloudTrend solarTrend, UpwindCloudSample upwindSample)`.
Mid/high lives *inside* `SolarCloudSlot`, so `CloudApproachData` and
`AtmosphericData.cloudApproach()` need **no change** — they carry the widened
trend transparently.

---

## Section 2 — the persistence question: no migration needed

**The prompt reads the live, recomputed trend — not persistence.**
`PromptBuilder.buildUserMessage` reads `data.cloudApproach().solarTrend()`
([PromptBuilder.java:407-410](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:407)),
which is the in-memory object assembled by
`augmentor.augmentWithCloudApproach(...)` earlier in the same pipeline call.
Nothing on the prompt path reads a persisted column.

**The persisted `solar_trend_*` columns are a separate, write-only-for-the-DTO
path.** They are written once in `ForecastService`
([:649-656](backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java:649)) from the
*same* in-memory `ca` object (`slots().getLast()/getFirst()/isBuilding()`), and
read only by `ForecastDtoMapper`
([:196-198](backend/src/main/java/com/gregochr/goldenhour/model/ForecastDtoMapper.java:196)) into
`ForecastEvaluationDto` for the frontend. No reader on the prompt path; no reader
in triage.

**Consequence:**
1. The #3 **prompt** change needs **no migration**. Widen the in-memory model +
   extraction + prompt print; the persisted columns stay low/building-centric
   and keep working unchanged.
2. Adding two fields to `SolarCloudSlot` does **not** break the persistence
   write site — it reads `lowCloudPercent` off the first/last slot, which still
   exists; the new fields simply go unread there.
3. *If* mid/high trend were ever wanted persisted (observability / a future
   DTO surface), that would be a clean additive nullable migration — next free
   is **V114** (highest is `V113__add_snow_fields.sql`). **Out of scope per the
   brief: don't add it for its own sake — nothing reads it.**

So the cheapest real outcome the brief hypothesised is **confirmed**:
**in-memory only, no migration.**

---

## Section 3 — what the prompt change touches (scope, not wording)

All verified against HEAD:

1. **The trend print** — [PromptBuilder.java:407-426](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:407).
   Today prints a low-cloud `T-3h… event` series and appends `[BUILDING]` (or the
   THIN-STRIP-confirmed variant). Widening = also print mid/high per slot. Print
   change only.
2. **The instruction** — `SYSTEM_PROMPT`, the `CLOUD APPROACH RISK` block at
   [:193-219](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:193).
   Today: `[BUILDING]` penalty only. #3 adds the clearing-with-canvas-persistence
   positive (and the bound against clearing-to-bald-blue). **Pure prompt text —
   the calibration pass, out of scope here.**
3. **Optional `isClearing()`** on `SolarCloudTrend`, mirroring `isBuilding()`:
   `earliest(low) − peak-or-event(low) ≥ N pp` **AND** mid/high **not** dropping
   ≥ M pp (canvas holds). Needs the widened slot from §1. Mirrors the existing
   `peak − earliest ≥ 20` shape. Lets the print emit a clean `[CLEARING]` label
   rather than making the model infer it from four numbers.
4. **Architecture location — base `PromptBuilder` only.** `CoastalPromptBuilder
   extends PromptBuilder` and only *appends* a surge suffix, so it inherits the
   clearing reasoning automatically. `BluebellPromptBuilder` is standalone (shares
   none of the sky rubric) and is correctly untouched. Confirmed in the prior
   investigation; unchanged at HEAD.

---

## Section 4 — calibration-validation: harness exists, with one real gap to close

**`PromptTestService` exists and is the right replay harness**
([`PromptTestService.java`](backend/src/main/java/com/gregochr/goldenhour/service/PromptTestService.java)):
its docstring is literally "Re-running with stored data allows measuring the
impact of prompt changes." Replay deserializes the full
`AtmosphericData` from `atmosphericDataJson`
([:324-327](backend/src/main/java/com/gregochr/goldenhour/service/PromptTestService.java:324)) and
re-evaluates — and `cloudApproach` is a serializable field on `AtmosphericData`
([AtmosphericData.java:45](backend/src/main/java/com/gregochr/goldenhour/model/AtmosphericData.java:45)),
so a stored trend round-trips into the replayed prompt.

**The gap (flag loudly):** `PromptTestService.fetchAtmosphericData`
([:562-574](backend/src/main/java/com/gregochr/goldenhour/service/PromptTestService.java:562))
augments **only** with tide data — it does **not** call
`augmentWithDirectionalCloud` or `augmentWithCloudApproach`. So a *fresh* prompt-
test fetch produces `AtmosphericData` with `cloudApproach == null` → **no trend
block in the test prompt at all.** As-is, the harness cannot exercise the
clearance trajectory on freshly-fetched data. Two ways to close it (both for the
calibration pass, not now):
- **Synthetic-JSON replay (no production code touched):** seed parent-run
  results whose `atmosphericDataJson` carries hand-built trends, then replay
  old-vs-new `SYSTEM_PROMPT`. Cleanest for a controlled boundary set.
- **One-line harness extension:** add `augmentWithCloudApproach` (and directional)
  to `fetchAtmosphericData` so fresh fetches carry the trend. A trivial code
  change, but a code change — defer to the pass.

**Two cheaper validation layers also exist:**
- **Prompt-text assertions** — [`PromptBuilderTest`](backend/src/test/java/com/gregochr/goldenhour/service/evaluation/PromptBuilderTest.java)
  is deterministic, no API: assert the widened block prints mid/high and the
  `[CLEARING]` label fires only on the right boundary cases. This catches the
  *mechanical* half exactly and for free.
- **`PromptRegressionTest`** exists (real Claude, `-Pprompt-regression`) — but
  per CLAUDE.md its assertions are **user-owned; never edit them.** It's a guard
  the change must not break, not a venue we author.

**The risks validation must catch** (from the prior investigation's guardrail —
reward = confidence/urgency, NOT a free rating lever):
- **(a) over-reward:** ratings inflate on marginal *in-progress* clearances.
- **(b) double-count:** boosting a score the point-in-time rules already gave the
  cleared end-state.

**Boundary set to have ready for before/after:**
| Case | Low trajectory | Mid/high trajectory | Expected behaviour |
|------|----------------|---------------------|--------------------|
| Genuine clearance | drops sharply | **persists** | reward / confirm (4–5 with canvas) |
| Wholesale clear | drops | **also drops** → bald blue | **NOT** rewarded; CLEAR-SKY-CAP ≤3 |
| Building | rises | any | unchanged `[BUILDING]` penalty |
| Already clear throughout | flat low | flat | no trajectory → unchanged |

The wholesale-clear row is the case #2 could not distinguish and the reason we
chose #3 — it must be in the validation set as the explicit negative control.

---

## What surprised me most

That widening is **cheaper than the snow precedent it's measured against.** Snow
needed new fetch params; here the mid/high arrays are not only already fetched —
they're already *read three lines away* in the same class for directional
sampling. `extractSolarTrend` reaches past `getCloudCoverMid()`/`High()` to grab
only `getCloudCoverLow()`. The low-only trend was a deliberate narrowing of data
fully in hand, not a data limitation. There is genuinely nothing to plumb.

The second surprise cuts the other way: the **validation harness has a blind
spot** — `PromptTestService` never augments cloud-approach on fresh fetches, so
the trend the whole change is about is absent from freshly-fetched test prompts.
The *data* half is trivial; the *validation* half needs a small deliberate step
(synthetic JSON or a one-line harness fix) or the before/after will silently
compare two trend-less prompts and show no difference.

---

## Go / no-go for #3, and the decision for Chris

**GO for #3.** The data half is snow-shaped-routine and migration-free:
- widen `SolarCloudSlot` with `midCloudPercent` / `highCloudPercent`;
- widen `extractSolarTrend` to capture them (arrays already in hand);
- widen the `PromptBuilder` print + add the clearing instruction (the calibration
  pass);
- optional `isClearing()` mirroring `isBuilding()`.
No migration, no new fetch, base-`PromptBuilder`-only, coastal inherits, bluebell
untouched.

**One thing for Chris to note before the prompt pass:** budget a small step to
make `PromptTestService` actually carry the trend (synthetic-JSON replay, or a
one-line `augmentWithCloudApproach` in `fetchAtmosphericData`) — otherwise the
before/after validation of this sensitive core-evaluation prompt won't exercise
the very signal it's adding. The `PromptBuilderTest` text-level assertions can
and should validate the mechanical half regardless.

**Out of scope, deliberately not done:** the prompt wording, and persisting the
mid/high trend (nothing reads it — no migration for its own sake).
