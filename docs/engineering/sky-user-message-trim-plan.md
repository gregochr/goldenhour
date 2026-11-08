# Sky rating: trim duplicate and unused lines from the user message

**Status:** planned 2026-11-02, owner chose "message trims only" · one phase, one PR · backend only

## Why

Batch sky ratings are most of the Claude bill: about $80 a month on Sonnet 5.5 (Near-Term), plus Far-Term
on Haiku 4.5. Each request's user message (`PromptBuilder.buildUserMessage`, PB:490–716) is about 400–450
tokens and is **not cached**, so every token in it is paid at the full input price on every request
(~29.5k requests a month). Several lines are printed twice, or are never referred to by any rule in the
system prompt. Removing them saves roughly 80–100 tokens per request, about 20% of the user message and
about 5% of the request cost (~$4 a month on Sonnet 5.5). The **system prompt is not touched**: its
`prompt_hash`, cache prefixes, `MIN_CACHEABLE_SYSTEM_PROMPT_CHARS` headroom and calibration buckets are
unchanged. The output schema (including `headline`) is unchanged.

Owner decisions already taken: keep `headline`; do not dedupe the system prompt; do not fold the coastal
suffix in this PR.

## The trims (PromptBuilder.buildUserMessage and helpers)

Before changing a line, `grep` the system prompt (`SYSTEM_PROMPT`, PB:82–335, plus
`CoastalPromptBuilder`'s suffix) for the field name and for any phrase that refers to it. **If any rule
names or relies on the line, do not remove it; report it instead.**

1. **`Precip probability: N%`** (the `Humidity: …, Precip probability: …` line): drop the precip-probability
   part, keep `Humidity: N%`. No rule refers to it.
2. **`Shortwave: N W/m²`** (the `Boundary layer: …, Shortwave: …` line): drop the shortwave part, keep
   `Boundary layer: Nm`. No rule refers to it.
3. **Orientation parenthetical:** `Location orientation: sunrise-optimised (this location is best suited
   for sunrise photography)` becomes `Location orientation: sunrise-optimised`. Check PB:208's rule still
   reads naturally against the shorter line; check every orientation value the code can print.
4. **CLOUD APPROACH trend `event=` slot:** the trend's `event=low…/mid…/high…` (or `event=N%` in the
   low-only variant) repeats the Solar horizon line printed just above. Drop the event slot from the trend
   **only if** no rule in the system prompt or any trend label (`[BUILDING]`, `[CLEARING …]`,
   `[BUILDING — but THIN STRIP CONFIRMED at event time …]`) depends on the model seeing it inside the trend.
   The label logic itself must keep using the event slot's data (it is computed in Java, not by the
   model); only its printed text goes. If the rules read the trend as a series ending at the event, keep
   it and report.
5. **MIST/VISIBILITY TREND `event:` slot:** repeats the top-level `Visibility` and `Dew point: X (gap Y)`
   values. Drop the event slot from the printed trend under the same rule as 4 (keep it if the mist rules
   PB:186–199 or the `[AT/NEAR DEW POINT]` labels need the series to include the event). Keep the T−3h…T+2h
   header accurate (e.g. say the event values are the ones above).

**Not trimmed** (decided): `Weather code` (no rule names it, but the model may read fog/rain codes
implicitly; small saving, real risk), PM2.5/Dust/AOD, `Wind`, `Precip`, the suffix `Rate 1-5…`
(`PromptUtils.insertBeforeSuffix` depends on it), the Saharan dust block, the surge block, every
directional/upwind/far-corridor line, and every bracketed label.

## Tests

- **`PromptGoldenMasterTest`:** regenerate the six `prompt-golden/*.txt` files
  (`-Dprompt.golden.regenerate=true`) and check the diff shows **only** the removed text.
- **Prompt fidelity tests** (`LowHauxleyPromptFidelityTest`, `SteelRiggPromptFidelityTest`,
  `SkyRatingEvalPortedFixtureFidelityTest`): the stored `.txt` files under
  `src/test/resources/prompt-regression/` are the record of what production sent on those days and stay
  untouched. Change the assertion to: `buildUserMessage(fixture)` equals the stored prompt **with this
  plan's trims applied by one small, explicit, shared test helper** (e.g. `PromptTrims.apply(String)` in the
  test tree, one documented step per trim). This keeps proving the fixture data matches production,
  and the helper documents exactly what changed. Add a unit test of the helper itself.
- `PromptBuilderTest` and any other line-level pins that mention the removed text: update them and add
  assertions that the removed text is absent and the kept parts (`Humidity`, `Boundary layer`,
  orientation value, the T−3h…T−1h and T+1h…T+2h slots, the Solar horizon line) are still present.
- `SystemPromptCacheabilityTest`, `OutputSchemaOrderTest`, `CachePayloadGoldenMasterTest` must pass
  unchanged (the system prompt and schema do not change). If any of them fails, stop and report.
- **Do not** change any `RegressionBounds`, `PromptRegressionTest` assertion or eval band. Do not run the
  `prompt-regression` profile or any paid call.

## Owner checks before merge (paid, pennies)

The prompt-change brief (`docs/engineering/prompt-change-session-brief.md`) requires a regression run with
the diffs surfaced. After the PR is open, the owner runs, on the PR branch:

```
cd backend && ANTHROPIC_API_KEY=... REGRESSION_MODEL=SONNET_55 ./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest
cd backend && ANTHROPIC_API_KEY=... REGRESSION_MODEL=HAIKU ./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest
```

Both must pass (9 cases each). After deploy, three Sky Eval runs each on Sonnet 5.5 and Haiku 4.5 confirm
no fixture moved band against today's baseline (Sonnet 5.5 100%; Haiku 4.5 97.8%, misses only
`far-corridor-blanket-canvas` ABOVE).

## Docs and changelog

- Update any `docs/engineering/reference/*.md` text that lists the user-message lines
  (`grep -rn 'Precip probability\|Shortwave\|best suited' docs/engineering/reference/`).
- This plan goes to `docs/engineering/sky-user-message-trim-plan.md`.
- `changelog.d/20261102-sky-user-message-trim.md`: `### Changed — Sky ratings send fewer duplicate and
  unused lines to Claude`.

## How we will know it worked

`api_call_log` for `fc-` batch rows after deploy: mean `input_tokens` per Sonnet 5.5 request falls by about
80–100 with `cache_read_input_tokens` unchanged; Sky Eval pass rates unchanged.

## Plan review corrections (2026-11-02) — these override the sections above

- **Trims 4 and 5 are dropped; keep both trend `event` slots exactly as they are.** They are not
  duplicates: the CLOUD APPROACH block can exist without the directional block
  (`CloudPointCacheReader` returns null directional data when any cone/antisolar point is missing, while
  the approach trend averages whichever points exist; `ForecastService:312-316`), so the trend's `event=`
  slot can be the only event-time solar-horizon reading; the `[BUILDING]`/`[CLEARING]` labels are computed
  from it; and the approach, clearing and mist rules (PB:154-155, 195-205, 271, 313-320) and the valley-mist
  block read each trend as a series that reaches the event. The mist event slot also carries the only
  `[AT/NEAR DEW POINT]` label. The MIST trend header stays verbatim (`PromptBuilderValleyMistTest:68-71`).
- **Scope is trims 1–3 only:** precip probability, shortwave, and the orientation parenthetical.
- **Savings, corrected:** the user message is ~350–385 tokens (1,334–1,463 chars), not 400–450. Trims 1–3
  save about 25 tokens per request where orientation is printed (~11 otherwise), roughly 5–6% of the user
  message and about $1 a month. The owner chose to build it anyway.
- Update the `reading()` javadoc (PB ~804) that cites "precipitation probability" as using `N/A`.
- Orientation values are only `sunrise-optimised` / `sunset-optimised`
  (`ForecastDataAugmentor.augmentWithLocationOrientation`), and PB:208-214 names them itself.
- Extra pins to update: `PromptBuilderTest` ~L1100 and ~L1114 (`best suited`). Nothing else in the test
  tree pins "Precip probability" or "Shortwave" besides the six golden files and the two stored prompts.
- The `PromptTrims.apply` test helper is three literal replacements, and **each step asserts it matched
  exactly once**, so a drifted pattern fails loudly instead of silently doing nothing.
- `prompt_hash` covers the system prompt only, so before/after rows share a hash; compare by `app_build`.
- When judging the Sky Eval afterwards, compare band moves per fixture, not the overall Haiku 4.5 rate
  (it is noisy at 97.8%).
