# Changelog

All notable changes to PhotoCast are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Removed — Legacy pence-based cost tracking (micro-dollars is now the single source of truth)
- Ripped out the deprecated flat-rate **pence** cost system that ran in parallel with token-based **micro-dollar** pricing. Since the V38 token-cost migration every record has carried `cost_micro_dollars`/`total_cost_micro_dollars` (+ the stored exchange rate for GBP), and the frontend already rendered GBP from micro-dollars, falling back to pence only for pre-token rows — so the pence path was dead for all current data and only added dual-system confusion.
- **Backend:** deleted the two `@Deprecated CostCalculator.calculateCost(...)` overloads and the five `CostProperties` pence fields; removed every pence write and the `completeRun` pence aggregation from `JobRunService`, `ModelTestService`, and `PromptTestService` (their `completeRun` helpers lost the `totalCostPence` param); dropped the deprecated `cost.*-pence` keys from the `example`/`local`/`prod` YAML profiles (token rates fall back to `CostProperties` defaults).
- **Schema:** migration **V125** drops the six now-unused columns — `api_call_log.cost_pence`, `job_run.total_cost_pence`, `model_test_run.total_cost_pence`, `model_test_result.cost_pence`, `prompt_test_run.total_cost_pence`, `prompt_test_result.cost_pence` — and the matching `@Column` fields were removed from the entities (they were serialized raw on the ADMIN metrics/test endpoints, so this also drops them from those responses; no DTO change needed).
- **Frontend:** `formatCostGbp(microDollars, exchangeRate)` lost its legacy-pence third argument and fallback branch; `MetricsSummary` lost its `legacyOnlyPence`/`hasMixedPricing` logic and mixed-pricing copy (cost is now always "Token-based pricing"); `JobRunDetail`, `JobRunsGrid`, `ModelTestView`, `PromptTestView`, and `BriefingModelTestView` dropped their pence reads and the stale third arg.
- **Behaviour change (accepted, admin-only):** pre-V38 historical metrics/model-test/prompt-test rows — which never had micro-dollar costs — now render "—" instead of a legacy pence estimate. No LITE/PRO forecast data is affected (`ForecastEvaluationDto` never carried cost fields).

### Added — Meteor pill gains an overhead clear-sky count ("clear at X of Y dark-sky locations")
- Completes the "clear at X of Y" idiom across all three night topics. The meteor pill now carries a fact showing how many dark-sky locations are forecast clear on the shower peak night — e.g. `Perseids peak — dark moon, good viewing · ZHR ~100 at peak · radiant best 01:00–04:00 NE · clear at 12 of 30 dark-sky locations`.
- **Honest whole-sky signal, not the horizon proxy.** Aurora and NLC count "clear" from a northern-horizon transect because those phenomena sit low on the poleward horizon. A meteor shower is whole-sky, so a new `OverheadCloudSampler` measures total-column cloud (low+mid+high, capped) **directly above each dark-sky site** — a location under a clear northern horizon but cloud overhead is honestly counted as *not* clear.
- **No API call from the strategy.** Mirroring the NLC pattern, a new `MeteorClarityService` runs the scan during the daily-briefing run (`BriefingService`, failure-isolated) into an in-memory cache the read-only strategy consumes. It fetches **only on shower-peak nights** (gated by the shared `MeteorHotTopicStrategy.peakDatesWithin`), so the vast majority of briefings do no meteor cloud fetch at all. Each peak night is sampled at its deep-night hour; the count is omitted entirely (never fabricated) if the scan didn't run or returned an inconsistent count.
- Frontend needs no change — the fact renders through the generic `TopicFacts` line. Tests: `OverheadCloudSamplerTest` (at-location coords vs the northward transect, `SUM_CAPPED`, lenient overcast defaults, grid dedup), `MeteorClarityServiceTest` (peak-gating with no fetch off-peak, the 75% clear boundary, empty dark-sky), and `MeteorHotTopicStrategyTest` (the fact present/absent/guarded, `peakDatesWithin`).

### Changed — Aurora pill adopts the same "clear at X of Y dark-sky locations" idiom
- Following the NLC change, the aurora tonight pill's detail read "Kp 6 forecast tonight — 8 dark-sky locations with clear skies". The 8 was the clear count, but with no denominator it lost the "how widespread" colour that the same triage already knows.
- It now reads **"Kp 6 forecast tonight — clear at 8 of 30 dark-sky locations"**, matching the NLC pill and the Plan briefing strip. The dark-sky total is summed from the aurora region summaries (each `AuroraRegionSummary` already carries `totalDarkSkyLocations`) onto `AuroraTonightSummary.totalDarkSkyCount`, or read from `AuroraStateCache.getDarkSkyLocationCount()` on the state-cache fallback path — both denominators match their clear count exactly, so the fraction is honest. Falls back to the old "N with clear skies" wording only if the total is unknown or inconsistent.
- The other night topics don't carry a clear count to reframe: **meteor** fires on the shower calendar + moon (no per-location clarity scan), and **supermoon** is sky-wide. Left unchanged.
- Tests: `AuroraHotTopicStrategyTest` covers both the briefing-summary and state-cache paths at the new wording; `BriefingAuroraSummaryBuilderTest` locks that the total sums the region dark-sky counts.

### Changed — NLC pill now reads "clear at X of Y dark-sky locations", not a bare count
- The Noctilucent cloud topic's detail showed e.g. "Clear northern horizon tonight — 241 dark-sky locations". The 241 was already the *clear* count (correctly computed), but with no denominator it read like the total dark-sky inventory, losing the "how many are clear" colour.
- It now reads **"clear at 241 of 312 dark-sky locations"**, matching the Plan briefing strip's *"Clear at X of Y"* idiom — so the number reads as how widespread the clear northern horizon is, and the total is visible for context (a quiet high-pressure night lights up nearly all of them). The total (`darkSky.size()`, already computed by the clarity scan) is now carried onto `NlcNightClarity.ClearNight` as `totalDarkSkyCount`; the in-memory clarity cache is not persisted, so no migration.
- Tests: `NlcClarityServiceTest` locks the denominator (clear 1, total 2 → "of 2"); the NLC detail assertions and `ClearNight` fixtures across the three NLC test classes updated for the new field/wording.
- Adds the generic facts line to the **BLUEBELL** pill, completing the "science showing" roll-out across all hot-topic groups. Two honest, data-backed chips plus a technique note:
  - `conditions 5/5 · excellent` — the best Claude **conditions** rating (1–5) with its quality label. Framed as *conditions*, never flower state.
  - `2 sites scoring 4+/5` — how widespread the good morning is, counting distinct sites at the high-priority tier. Shown only when **≥2** such sites exist (otherwise the rating chip stands alone).
  - Note: "still, misty mornings diffuse the low sun — before wind and harsh light".
- **Deliberately carries no bloom percentage or "peak" claim.** The mockup wanted `bloom 82% · peak this week`, but phenology — whether the flowers are actually out, and how open — is **modelled nowhere** in the system (`BluebellPromptBuilder` scores conditions *assuming* bloom and is explicitly instructed never to assert it; `SeasonalWindow` is just a calendar gate with no peak date). Any bloom figure would be fabricated, so it's dropped — the same honest-facts discipline applied to the dropped inversion-top altitude.
- The facts line renders **alongside** BLUEBELL's existing expandable per-region card (`TopicFacts` is persistent, not gated behind expansion) — BLUEBELL is the first topic to carry both. No frontend change: the topic already has a style and the generic `TopicFacts` line.
- Tests add fact-line coverage (rating chip, the ≥2-site breadth-chip gate, and an explicit assertion that no `bloom`/`peak`/`%` figure is ever emitted) and bring `BluebellHotTopicStrategyTest` in line with the test standards (the `lenient()` shared-default removed; a `stubAhead(day…)` helper pins the freshness stub's date + SUNRISE event with `eq()`).

### Added — Snow & frost "science showing" enrichment: snow-on-the-fells, fresh snow, hoar frost & inversion facts
- Extends the "science showing" fact line to the **snow & frost** hot topics, all read off the survivor surface with no new API calls. Facts stay **anomaly-first + honest** — a figure appears only where real data backs it.
  - **Snow on the fells** (`SNOW_TOPS`): `snow line ~620 m · 300 m below the tops`. The snow line is the freezing-level altitude; the margin below the summit — the anomaly that confirms the tops are white, not merely at the freezing altitude — comes from the most confidently-capped location that day. Note: "shoot from low ground looking up, before cloud builds".
  - **Fresh snow** (`SNOW_FRESH`): `depth 8 cm · snow line ~620 m` — depth (deepest lying row) and the snow line tell you where it will actually stick, exactly as the mockup framed it. Note: "clean at first light, before wind and footprints".
  - **Snow mist & hoar frost** (`SNOW_MIST`): honestly split by temperature. When the misty snowy air is **provably sub-zero** the pill becomes *"Snow mist & hoar frost"* with `8 cm · -2 °C · hoar frost likely · humidity 96%` (freezing fog deposits rime); when the air isn't provably below freezing it stays *"Fresh snow with mist"* and claims only `humidity 96%`, never fabricating frost. The pill's `(i)` "science" tooltip explains **what hoar frost actually is** — feathery ice deposited when mist freezes onto every twig and blade — but only in the sub-zero case, so the explanation is never shown for plain mist.
  - **Cloud inversion** (`INVERSION`): `inversion 9/10 · strong` — the Claude-scored likelihood band. The mockup's *"inversion top ~450 m"* is **deliberately dropped**: the pipeline scores inversion likelihood but never computes a layer altitude, so a metres figure would be fabricated. Note: "climb above it — the valleys fill with cloud, burning off after sunrise".
- New data source for the honest hoar-frost distinction: the 2 m air temperature (already fetched from Open-Meteo and persisted on `forecast_evaluation`) is now **carried onto the survivor surface** the snow detectors read — `temperature_celsius` on `survivor_atmosphere` (**V124**), written from `AtmosphericData.comfort()`, threaded through `SurvivorSignals.Readings` and the reader. Additive and nullable, mirroring how the humidity column was added; summer rows simply sit well above zero. Without it the mist variant could only guess at frost from humidity.
- Frontend needs no change — the four topics already have styles in `HotTopicStrip`, and the facts render through the generic `TopicFacts` line shipped with the coastal group.
- Tests add band/branch boundary coverage (snow-line margin representative, depth rounding, the sub-zero hoar-frost gate incl. the 0 °C boundary, the dropped inversion altitude, temperature round-trip through writer + reader) and bring `SnowFreshHotTopicStrategyTest` + `InversionHotTopicStrategyTest` in line with the test standards (both `lenient()` shared-default stubs removed, knowable `when()` args pinned with `eq()`).

### Added — Sky & light "science showing" enrichment: dust, aurora, meteor & supermoon facts + NLC migration
- Extends the coastal "science showing" fact line to the four **sky & light** hot topics, and migrates the noctilucent (NLC) pill off its bespoke two-line component onto the same generic `TopicFacts` model. Every figure stays **anomaly-first + honest** — shown only where real data backs it, never fabricated.
  - **Saharan dust** (`DustFactsBuilder`, new): `AOD 0.42 · thick plume · afterglow 21:44–22:12`. The optical depth carries an honest qualitative band (dense ≥0.60 / thick ≥0.40 / light veil), and the afterglow window is sunset→civil-dusk (converted to Europe/London) — the interval when dust-scattered colour peaks *after* the sun has set. Note: "look W, high — colour peaks after the sun's gone". The plume transport bearing is deliberately dropped (no wind vector on the survivor surface — it would be fabricated).
  - **Aurora**: `Kp 6 · glow reaches ~54°N and north · moon 18%`. The glow latitude is the real NOAA Kp→latitude cap (`NoaaSwpcClient.getGlowLatitudeCap`, newly exposed), so it only appears when a trigger Kp is known; extreme storms (cap 0) read "glow across the whole UK". The moon-illumination chip is optional and omitted entirely when unknown (no fabricated figure). The magnetic-midnight peak window is not computed, so it's left off rather than invented. Note: "look due N, low".
  - **Meteor shower**: `ZHR ~100 at peak · radiant best 01:00–04:00 NE · moon 20% · dark enough`. The `Shower` catalogue gained per-shower ZHR, radiant compass point and best-viewing hours (Quadrantids/Lyrids/Perseids/Orionids/Geminids). **Honesty fix** (from adversarial review): the pill headline no longer hardcodes "dark moon" — since the fire gate admits up to 50% illumination, a half-lit peak now reads "some moonlight, still worth a look", matching its own moon chip instead of contradicting it.
  - **Supermoon**: `perigee +8% larger · 100% lit · rises 21:38, just after sunset ESE`. The "% larger" is the honest **mean-distance-based** figure (how much bigger than an *average* full moon), computed from the lunar ephemeris at moonrise via `LunarCalculator` + `MoonriseMoonsetCalculator` (new bean); the rise time, compass bearing and relation to sunset come from the same sample. Note: "catch it low behind a landmark".
  - **NLC**: now renders through the generic `TopicFacts` (two twilight-window chips — NW after dusk, NE before dawn — plus the "look low on the horizon" note) rather than the bespoke `NlcWindows` component, which is deleted along with its `NLC_WINDOW_DIR` map. The `eveningWindow`/`morningWindow` fields are retained for cache JSON back-compat.
- Frontend: the `NlcWindows` component and its NLC-only render branch are removed from `HotTopicStrip`; all five sky topics flow through the existing generic `TopicFacts` (mono chips, `dir` glyph, Lite-tier blur, mobile drop-fact) already shipped with the coastal group. No new migration — the facts are derived at detection time from data the strategies already hold.
- Tests: `DustFactsBuilderTest` (band boundaries at 0.60/0.59/0.40/0.39, afterglow conversion, honest drops); Aurora `cap==0` "whole UK" + moon-null-drop branches; Meteor 25% band boundary + headline/chip consistency; Supermoon's four `sunsetRelation` branches (before sunset / ≤90 min / after dark / no-sunset) + no-moonrise degradations; `NoaaSwpcClient.getGlowLatitudeCap`; and the NLC generic-facts rendering. `DustHotTopicStrategyTest` was also brought in line with the test standards — the two `lenient()` shared-default stubs removed and every knowable `when()` arg pinned with `eq()`.

### Added — Coastal "science showing" enrichment: king/spring/storm-surge pills + sea-state on the map
- The Plan screen's **Hot Topics** strip now gives the three coastal topics an enriched second line of "science showing" facts, generalising the pattern the Noctilucent row already had. **King tide**: `high water 5.8 m · +0.7 m over spring · HW 06:42`. **Spring tide**: `range 4.9 m · +1.1 m over average · LW 12:10 · HW 18:20`. **Storm surge**: `waves 4.2 m · very rough · surge 0.6 m above normal · wind WSW 38 mph`. Each carries a "where to look" note and an accent-bordered `(i)` mechanism tooltip (mono heading + serif body). Facts are **anomaly-first** — every figure is paired with its "vs normal" — and shown only where real data backs them (nothing fabricated).
- New **Open-Meteo Marine** data source for sea-state (significant wave height + swell) — the one genuinely-new signal, since the tide heights/range were already computed. `OpenMeteoMarineApi` is a separate `@HttpExchange` host on the same free tier + `$0` cost tracking as the other Open-Meteo APIs; `MarineClient` does a resilient fetch on the isolated `open-meteo-briefing` breaker and samples Hs at the event hour; `SeaState` maps Hs to the WMO/Douglas band (`4.2 m → "very rough"`). Fetched once per briefing cycle by `MarineWaveRefreshService` (failure-isolated so it can never abort the briefing) into a shared **`marine_wave`** table (V123), read directly by all three coastal pills and the map popups. The band is derived at render, never stored, so no two surfaces can disagree on the same Hs.
- The storm-surge model's **numeric surge + wind** — which it already computed but only kept as a risk-level string — are now persisted on the survivor surface (`survivor_atmosphere`, V123) so the surge pill can show them.
- **Coastal map popups** (`MarkerPopupContent`) gained a **sea-state pill** (`🌊 Seas 4.2 m · very rough`) and a **surge pill** (`⚡ Surge 0.6 m above normal`, gated on MODERATE/HIGH risk), plumbed via two new `ForecastEvaluationDto` fields (`significantWaveHeightMetres`, `seaState`) that `ForecastDtoMapper` resolves from `marine_wave` per coastal row (mirroring its per-row bluebell lookup).
- Model plumbing: a generic `HotTopicFact(key, value, dir, emphasis, optional)` + `facts`/`note` on `HotTopic` (JSON back-compatible via `@JsonCreator`, threaded through every copy method) and a generic frontend `TopicFacts` component (the NLC pill keeps its bespoke line). **Lite users** see the facts obfuscated (a blurred paywall tease); the least-critical chip is dropped on narrow viewports.
- Tests: `SeaStateTest` (band boundaries), `MarineClientTest` (event-hour sampling + land-cell degradation), `CoastalTideFactsBuilderTest` + `StormSurgeFactsBuilderTest` (fact math, anomaly framing, representative-slot selection), `HotTopicFact`/DTO JSON round-trips + cache back-compat, and frontend coverage for `TopicFacts`, the `InfoTip` accent card, and the `MarkerPopupContent` sea-state/surge pills. Migration **V123** adds `marine_wave` plus the numeric surge columns.

### Fixed — Sky-Rating eval batches can no longer get stuck in "Running…" forever
- On the Manage → Operations → Sky Eval tab, scheduled eval runs could sit in **"Running…"** indefinitely (observed >1 day). The weekly multi-model eval submits one Anthropic message batch, but it then awaited **and** finalised that batch on a fire-and-forget in-memory virtual thread, holding the batch id only on that thread's stack — nothing was persisted. A backend restart (deploy/crash) between submit and finish killed the thread, orphaning the RUNNING run rows with no way to ever reconcile them (the 1-hour await meant a *healthy* thread would have marked them FAILED after 60 min, so a multi-day "Running…" was proof the thread was gone). A `RuntimeException` inside the processing thread had the same effect — it was logged but the runs were never finalised.
- The batch is now **restart-safe**, mirroring the forecast pipeline's DB-backed `batch_result_polling` loop: submission persists the batch id onto each run (`sky_rating_eval_run.batch_id`, V121) and returns immediately, and a new scheduled reconciler job — `sky_rating_eval_batch_poll` (`SkyRatingEvalBatchService.reconcilePendingBatches`, FIXED_DELAY 60 s, seeded ACTIVE in V122) — reloads RUNNING runs each tick and finalises the ones whose batch has ENDED, fails those still unfinished past the timeout, and reclaims RUNNING orphans that never recorded a batch id. The reconciler is exception-safe (a `scheduleWithFixedDelay` task that throws is not rescheduled) and idempotent (clears a run's prior result rows before re-persisting, so a crash mid-reconcile can't duplicate child rows). `SkyRatingEvalBatchClient.awaitEnded` (blocking) is replaced by a single non-blocking `isEnded`.
- The dead-batch backstop moved from a **1-hour await timeout** to `photocast.eval.batch.poll-timeout-seconds` defaulting to **24 h** (the Batch API's guaranteed-completion window) — cheap now that polling no longer pins a thread, so slow-but-valid batches are reconciled instead of prematurely failed.
- V121 also one-time-reclaims any existing RUNNING orphans to FAILED on upgrade, so the perpetual "Running…" rows settle immediately. Tests: rewritten `SkyRatingEvalBatchServiceTest` (batch-id persistence on submit; reconciler branches — ended→COMPLETED, in-flight→leave, past-deadline→FAIL, orphan-without-batch→FAIL, status-check-error safety, no-op-when-empty), `SkyRatingEvalBatchClientTest` (`isEnded`), and `SkyRatingEvalServiceTest` (`findRunning`/`attachBatchId`/`deleteResultsForRun`).

### Fixed — "Forecast generated" timestamp now shows the real run time, not the moment you opened the popup
- On the Map/Plan popups, cached-only forecasts (batch-scored slots present in `cached_evaluation` but not yet persisted as a full `forecast_evaluation` row) reported **"Forecast generated: &lt;now&gt;"** — the time the request was served — instead of when the overnight/afternoon batch actually scored them.
- Root cause: the batch run time was available all along (`cached_evaluation.evaluated_at`, and the in-memory cache's `evaluatedAt`), but it was dropped between the cache and the DTO. `EvaluationViewService.getCachedScores()` returned only the results (no timestamp), so `mergeToView` handed a `null` `evaluatedAt` to the `CACHED_EVALUATION` view, and `ForecastDtoMapper.toSparseDto` then fabricated `LocalDateTime.now()` to fill the gap.
- The cache's evaluation instant is now plumbed through: new `BriefingEvaluationService.getCachedEvaluatedAt(...)`; `EvaluationViewService` carries it onto the `CACHED_EVALUATION` view from both the in-memory cache and the DB fallback (`evaluated_at`). `toSparseDto` no longer stamps `now()` — it leaves the run time **null** when genuinely unknown, and the popup footer (already guarded on `forecastRunAt`) simply hides rather than showing a false time.
- The intentional `evaluated_at` (first-evaluated, stable) / `updated_at` (latest write) contract on `cached_evaluation` is untouched. Backend tests: `EvaluationViewServiceTest` (cache hit carries `evaluatedAt`; in-memory and DB-fallback paths in `forDateRange`), `BriefingEvaluationServiceTest` (`getCachedEvaluatedAt` present/absent), and the flipped `ForecastDtoMapperTest` case (null `evaluatedAt` → null run time, no fabricated `now()`). Frontend: two `MarkerPopupContent` cases assert the footer is hidden for a null `forecastRunAt` (admin and non-admin).

### Fixed — full-briefing grid cell tooltip no longer clips on the rightmost column
- Hovering a region cell in the Plan tab's full-briefing grid revealed a tooltip (verdict + Claude gloss + weather), but on the **rightmost day column** its right edge was cut off — the last words of the gloss faded/truncated ("…an excellent canvas for sunset colour across t[he region]").
- Root cause matched the summary-strip fix (#206): the tooltip was an absolutely-positioned, centre-anchored descendant of the daily-briefing card, which has `overflow: hidden` — so the card clipped any tip that overran its right edge, regardless of anchoring.
- The tooltip is now portalled to `document.body` and positioned with `getBoundingClientRect()` (`position: fixed`), escaping every clipping ancestor. It anchors above the cell and flips its horizontal anchor (and caret) left→right only when it would overrun the viewport's right edge. `pointer-events: none` keeps it purely informational; hover **and** keyboard focus both reveal it.
- Frontend-only; the tooltip is now hover/focus-triggered React state rather than a CSS `:hover` descendant. Tests updated to hover the cell before asserting the portalled `role="tooltip"` carries the verdict/gloss/weather.

### Added — Dependabot auto-merge workflow
- New `.github/workflows/dependabot-auto-merge.yml` auto-approves Dependabot PRs and enables GitHub's native auto-merge for **minor and patch** bumps, so they merge automatically once the CI Backend and Frontend checks pass. Major-version bumps are left for manual review.
- Uses the official `dependabot/fetch-metadata@v2` action to classify the update type; runs only for the `dependabot[bot]` actor with a scoped `contents: write` / `pull-requests: write` token.
- Requires "Allow auto-merge" enabled in repo settings and branch protection on `main` with the CI checks marked as required.

### Fixed — summary-strip region hover now shows the verbose gloss, not the terse "X of Y locations"
- Hovering a rated region chip in the Plan tab's summary strip showed only the one-line roll-up (e.g. "Clear at 38 of 53 locations"), while the full-briefing grid's region cell already surfaced the richer Claude-generated gloss paragraph ("78% high cloud creates an excellent canvas for sunset colour across the region…") from the *same* underlying region object.
- The summary strip now carries `glossDetail`/`glossHeadline` through to its tooltip and prefers them over `summary` (matching the heatmap grid's `glossDetail || glossHeadline || summary` precedence), falling back to the terse summary when no gloss exists.
- Frontend-only, no backend change — the gloss fields were already present on the region object. Tests: two new `BriefingSummaryStrip` cases cover the gloss-preferred and summary-fallback tooltip paths.

### Fixed — health status recovers on its own when a backgrounded tab wakes up
- Leaving the app open (e.g. in a background Chrome tab, or across a Mac sleep/wake) reliably left the header showing a red **DOWN** badge and the "Service is temporarily unavailable. Data shown may be stale." banner until the page was manually reloaded — even though the backend was never actually down.
- Root cause was in the health-status SSE (`/api/status/stream`), not the backend. When the tab idled, the browser dropped the connection; `useHealthStatus` flipped to `DOWN` on the first error, and the only reconnect path was a `setTimeout`, which browsers throttle (or freeze) in background tabs — so the retry never fired, and nothing re-checked when the tab became visible again.
- `createEventSource` now takes an opt-in `reconnectOnVisible` flag: on `visibilitychange`/`focus`, if the connection is dead it cancels the throttled retry and reconnects immediately. `useHealthStatus` opts in **and** debounces the DOWN state behind a 6s grace window, so a transient drop that reconnects quickly no longer flashes the banner. Other SSE consumers (run progress, briefing) are unaffected.
- Frontend-only. Tests: 6 new `createEventSource` cases cover reconnect-on-visible, reconnect-on-focus, the no-op-while-OPEN guard, cancelling the pending retry, listener cleanup, and the option being off by default.

### Fixed — best-bet empty state tells the truth when you're away for the whole window
- When the operator is marked away for every upcoming day, no forecasts are generated — but the PhotoCast Planner's best-bet empty state still read "No standout recommendations right now — conditions are similar across all regions." That's misleading: there are no conditions to compare, the run simply didn't happen.
- The empty state now detects the all-travel-days case (every upcoming day falls in a travel range) and instead says **"You're away for the whole forecast window, so no forecasts were generated."** The "conditions are similar" copy is reserved for a genuine no-standout run. A `data-variant` (`away` / `similar`) distinguishes the two.
- Frontend-only. Tests: the empty state switches to the away copy when the whole window is a travel period, and keeps the "conditions similar" copy otherwise.

### Added — noctilucent cloud (NLC) sighting banner, twilight windows, and honest gating
Treats NLC honestly: there is **no mesospheric (~80 km) forecast model**, so PhotoCast only tells a photographer *when the twilight geometry is right and skies are clear* (exact geometry) and *reacts when someone actually reports a sighting* (a community signal) — never a probability.

- **NLC sighting banner (frontend).** A calm, dismissible violet banner beneath the aurora banner, mounted in `App.jsx`. Reads `GET /api/nlc/sighting` via `useNlcSighting` (10-min poll + focus) and leads with *who saw it, where, and how long ago* — no Kp/Bz/G-scale/probability. Renders `null` for free-tier (403), when no active sighting, or when skies aren't clear; per-session dismiss keyed by `reportedAt` re-shows on a newer report; click routes to the map. (`NlcSightingBanner.jsx`, `useNlcSighting.js`, `nlcApi.js` — 29 tests.)
- **Sighting endpoint + NLCNET scraper (backend).** `GET /api/nlc/sighting` (ADMIN/PRO; LITE → 403). `NlcSightingClient` scrapes NLCNET with Jsoup, cached and **fail-open** — any fetch/parse error leaves the banner dark. It reads the **live real-time season table** (rows keyed by `<td title="…">`, so column reordering can't break it) and keeps the older `div.caption` gallery format as a fallback. Because the live sightings sit on a per-month page, `nlc.sightings-url` supports `{year}`/`{month}` placeholders (default `…/{year}-{month}`) resolved at scrape time, so the scraper **auto-tracks the May–August season** without a config change. `NlcSightingService` gates on **season + freshness (≤6 h) + confirmed clear skies tonight** (reusing the `NlcClarityService` scan), so the banner only claims "clear skies" when it can. Scheduled `nlc_sighting_scrape` job (V120) via `DynamicSchedulerService`, self-gating on `nlc.sighting-enabled`.
- **Twilight visibility windows (backend + pill).** New `NlcTwilightWindowCalculator` computes the two nightly windows where the sun sits **6–16° below the horizon** — NW after dusk, NE before dawn — via a NOAA solar-altitude routine (cross-checked against `solar-utils` civil twilight to <0.6°), since `solar-utils` only exposes −6°. Windows attach to the NLC hot topic; `HotTopicStrip` renders them as a two-line pill (`↙ NW after dusk · 22:46–23:52` / `↗ NE before dawn · 02:10–03:18 · look low on the horizon`), degrading to a single line when a window is absent. The pill's `(i)` info-tip now carries the "no mesospheric forecast" caveat.
- **Emit gate tightening.** The NLC hot topic now surfaces only when in season **and** the northern horizon is clear **and** real twilight geometry exists that night (a genuine white night suppresses it).
- Backend: 4306 unit tests pass (0 failures; Docker-only integration tests excluded here), JaCoCo 80% gate + Checkstyle + SpotBugs clean. Frontend: 1636 tests pass; ESLint clean.

### Fixed — summary-strip region tooltip no longer clips its right edge
- On a Plan summary-strip pill, hovering a region name showed that region's gloss in a tooltip, but on the widest line the **last glyph faded/cut off** (e.g. "…all 10 locations" lost the "0"). The tooltip was an absolutely-positioned descendant of the plan card (`overflow: hidden`) — which also gains a `transform` on pill hover — so the card clipped its edge.
- The tooltip is now **portalled to `document.body`** and positioned against the chip with `getBoundingClientRect()` (`position: fixed`), so no clipping/transform ancestor can eat its edge. It anchors above the chip, flipping left→right when it would overrun the viewport's right edge (replacing the old pill-index heuristic with a real viewport check), and gains extra right padding so no glyph touches the border. `pointer-events: none` keeps it purely informational; hover **and** keyboard focus reveal it.
- Frontend-only. Tests updated to hover the chip and assert the portalled `role="tooltip"` carries the verdict/weather/gloss; click-to-navigate and strip/grid parity unchanged. 1603 frontend tests pass; ESLint + build clean.

### Fixed — empty-state popup never shows a solar time that contradicts the active tab
- On the map, an unforecast location's popup could show a **sunrise time under the Sunset tab** (and vice-versa): the empty-state "solar times" row always listed *both* events, so when only one event had data (e.g. a sunrise row exists but sunset was never run), it surfaced that mismatched time with a mismatched icon — reading as a wrong forecast even though the medallion correctly showed `–` (unknown).
- The row now shows **only the selected event** on the Sunrise/Sunset tabs, and **hides entirely** when the selected event has no time available — so a Sunset tab can never display a sunrise time. Aurora/Astro modes still show both events as the dark-window bracket (their event badge is hidden anyway).
- Frontend-only. Tests: solar row shows only the selected event (sunrise and sunset tabs), and is hidden when the selected event has no time (the reported bug). 132 popup tests + 68 MapView tests pass.

### Changed — summary-strip region names are hoverable, each showing its own Claude gloss
- Each rated region in a pill's detail line is now an **individually hoverable chip** (dotted underline). Hovering reveals *that region's own* gloss in a tooltip — verdict-coloured `VERDICT · wx` header (the same `region.summary` + weather the grid cell shows) over the Claude prose in Newsreader italic — so two regions on one pill give two different tooltips. Clicking a name still opens the map for that region (hover reads, click navigates). Every rated region shows as a chip (no more `A, B +N` truncation).
- The tooltip anchors above the chip and flips to right-aligned for right-half pills so it never spills past the plan card's clipped edge.
- Frontend-only. Tests: a chip per rated region, its tooltip carries the verdict/weather/gloss, a chip click routes to the region map overlay, and the strip/grid parity still holds. 1601 frontend tests pass; ESLint + build clean.

### Fixed — summary strip names the event + regions, and can't drift from the grid
- **Actionable pills.** Each strip pill now says *which* event and *which* regions, not a bare count: the peak line appends the rated event (`◎ Worth it · sunset`, `· sunrise`, or `· sunrise/sunset` when both are rated), and the detail line names the rated regions (short names — "N. Yorks Coast, Tyne & Wear" — overflowing to `A, B +N` past two) instead of "2 regions rated".
- **Off-by-one fix — strip and grid share one source.** The strip previously rolled up the raw `region.verdict` while the grid displays the serve-time-re-derived `displayVerdict`, so a day could read "All poor" on the strip while the grid showed two GO regions (they appeared to shift to the next day). The strip now derives each pill from **exactly the grid's day column** — the same `upcomingEvents` (date, targetType) columns and the grid's own `resolveRegionDisplay` — so disagreement is structurally impossible.
- Frontend-only. Tests: pill names the event + regions, `sunrise/sunset` when both, `+N` overflow, and the `displayVerdict`-vs-`verdict` drift case. 1598 frontend tests pass; ESLint + build clean.

### Changed — a hot topic's regions open the map to just its qualifying spots
- **Click a region under a hot topic → the map overlay opens** focused on that region for the topic's day, showing **only the locations that made the topic fire** (the elevated spots for a cloud inversion, the coastal spots for a king/spring tide, the dark-sky spots for aurora/NLC/meteor, the woodland for bluebell). Applies across all hot topics — the revealed region list (inversion, dust, snow, storm surge, equinox, …) and the region/location names inside the tide and bluebell expanded cards are now clickable.
- **Backend attaches the qualifying locations.** New nullable `locationNames` on `HotTopic` carries the exact spots (the browser can't know which locations are "elevated" — that lives in the strategies). Survivor-signal detectors (inversion, dust, storm surge, snow-on-the-fells) set the precise per-date spots via `PerDateHotTopicBuilder`; the central `HotTopicEventEnricher` backfills the rest by intersecting each topic's regions with the right location *kind* (coastal / dark-sky / bluebell / any), so no per-strategy plumbing was needed. Additive and nullable — cached briefing JSON and existing callers are unchanged.
- **Frontend** threads the click through the existing `onShowOnMap` overlay seam: a single qualifying spot flies to and opens its popup; several fit to bounds with a "◍ N spots — tap a pin" caption. Falls back to the region's rated pins when a topic carries no `locationNames`.
- **The overlay shows only the qualifying spots.** While a hot-topic drilldown is open, the overlay's `MapView` renders **only the topic's qualifying locations** as markers — overriding the map's own type/rating filters so nothing worth showing is hidden. Uniform across every topic: tides → only the coastal spots, aurora/NLC/meteor → only the dark-sky spots, inversion → only the elevated spots. Deliberately kept precise via the strategy-computed `locationNames` rather than growing `LocationType` — most topic qualification is dynamic (an inversion that scored ≥9 *this morning*) or already lives in numeric metadata (`bortleClass`, `tideType`), so a static tag would over-include or duplicate.
- **Bluebell** additionally threads its `filterAction` through, so the map's Bluebell filter chip reflects the drilldown (it's a real `LocationType`); the `locationNames` restriction is what actually isolates the pins, so this is now belt-and-braces.
- Tests: `HotTopic` JSON round-trip for `locationNames`, enricher backfill (coastal/dark-sky, and that strategy-provided spots aren't overwritten), `buildMapOverlay` region-with-locationNames + filterAction cases, and clickable-region behaviour in `HotTopicStrip`. 4275 backend + 1594 frontend tests pass; Checkstyle / SpotBugs / ESLint clean.

### Fixed — this-morning's cloud inversion no longer lingers into the evening
- **Why.** A "Cloud inversion — Today sunrise · 04:41" card was still on the board at 21:27, long after that dawn had burned off. The nightly dual-write records an inversion score for whichever event a location was evaluated for — and the augmentor gates only on elevation/overlooks-water, not event type — so an inversion-eligible location also gets a **SUNSET** inversion row. `InversionHotTopicStrategy` judged each row's freshness against its own event, so the today-SUNSET row stayed "fresh" all evening (sunset still ahead), keeping the topic dated today; the frontend then painted it as a sunrise card. A sea of clouds is a dawn phenomenon, so a sunset inversion score is meaningless anyway.
- **Fix.** The inversion detector now considers **SUNRISE rows only**, dropping the sunset noise so the freshness filter retires each morning the instant its sunrise passes. Tests cover a sunset row being ignored and a mixed sunrise+sunset day resolving to the sunrise card.

### Changed — Plan summary strip: one pill per day (both events), away days, sticky grid
- **One pill per day, not per solar event.** The strip now mirrors the grid's day columns — each pill carries *both* solar events (`↑ 04:42 · ↓ 21:49`) and a day-best verdict roll-up (the day's best across sunrise + sunset), capped at 4 days. The earlier per-event version produced two "Tomorrow" pills and read as inconsistent with the grid. A rated pill's "Show on map" click targets the event that drove the day's peak.
- **Away days.** Travel days (no forecast generated) render as `✈ Away` / `Travel day` / `No forecast` — dimmed, tide-tinted, not interactive — never "All poor" (poor implies we forecast and it's bad; away means we didn't). Away days keep their slot in the horizon.
- **Sticky grid across the Map round-trip (handoff B5).** The full grid still defaults collapsed, but once opened it persists for the session (`sessionStorage`), so opening the full Map tab and returning lands the user on the same open grid rather than re-collapsing. A fresh session still starts collapsed. (The handoff's deep-link / scroll-to-cell auto-expand has no entry path in the app today — no grid-cell-targeted navigation exists — so only the realizable "back from Map" case is wired.)
- Tests updated for the per-day/away pill shape plus the grid-persistence behaviour. 1588 frontend tests pass; ESLint clean.

### Changed — Plan tab: map opens over the plan, and the highlights lead
- **Map overlay instead of a tab switch.** Tapping any recommendation (Best Bet, Hot Topic, region row, grid cell, summary pill) now opens the map as a modal *over* the Plan tab — focused on what you tapped — instead of switching to the Map tab and losing your place. Closeable with ✕ / Esc / backdrop; `viewMode` is untouched. A quiet "Open the full Map tab →" performs the old tab switch, landing exactly where the overlay was focused (the same handoff + date feed both).
  - New `MapOverlay` frames the existing `MapView` with a header (region/topic/event + time), the **preserved Claude narrative** as a footer band (verdict-coloured head + the region's gloss from the briefing — no new data), and a foot row. `MapView` gains a small `focus` prop that fits the map to an arbitrary set of pins (a multi-region event or a hot topic); single-region triggers fly to and auto-open the top-rated location's popup via the existing handoff seam.
  - `App.handleShowOnMap` now routes into a `mapOverlay` state (via a new `buildMapOverlay` helper) rather than `setViewMode('map')`; `openFullMapTab()` is the explicit escape hatch. Multi-region triggers fit to all rated pins with a "◍ N regions — tap a pin" caption; single-region and location triggers open the top location's popup.
- **Compact summary strip; the full grid collapses by default.** Between the Hot Topics and the grid, a new `BriefingSummaryStrip` shows one pill per upcoming solar event (calendar chip + verdict roll-up: "◎ Worth it / Maybe / All poor" + "N regions rated"), capped at 4 events so it never implies a forecast further out than the model is confident about. Rated pills are actionable (open the map overlay via the same `onShowOnMap` seam); "All poor" pills are inert. The desktop `HeatmapGrid` moves behind an "Open full table ▾" expander (`gridExpanded` default false) under a "FULL BRIEFING" divider.
- Deterministic roll-up — the strip reuses the same `getVerdictCounts` the grid uses, so it can never disagree with the grid. No prompt/model/endpoint changes.
- Tests: `BriefingSummaryStrip` (roll-up, actionability, travel-day inert), `MapOverlay` (open/close/Esc/backdrop/escape-hatch/caption), `buildMapOverlay` (region/event/location/topic cases), and the `DailyBriefing` grid tests updated to open the collapsed grid first. 1587 frontend tests pass; ESLint clean; production build clean.

### Fixed — Plan cell verdict no longer lags the rating badge ("Worth it" above 1.8★)
- **Why.** A Plan heatmap cell could show a "Worth it sunset" label sitting above a low rating badge (e.g. 1.8★) with a narrative describing poor conditions. The two facts came from different snapshots: the region `displayVerdict` was frozen when the briefing was built (~every 8h), while the per-location scores the badge reads are served live through `EvaluationViewService`. A rating batch that re-scored the region downward between build and serve left the stale positive label stranded above the fresh low score.
- **Serve-time re-enrichment.** `BriefingService.getCachedBriefingForApi()` now re-derives each region's rating rollup (slot scores, `displayVerdict`, `scoredLocationCount`) from the current evaluation state at serve time — reusing the same `enrichWithCachedScores` path the build uses, sourced from the same `EvaluationViewService` the badge reads. Label and rating are now coherent by construction, with no scoring logic pushed into the render layer (the frontend still renders `displayVerdict` verbatim). Scoped to the API read path only, mirroring `BriefingHonestyFilter`, so internal batch callers of `getCachedBriefing()` keep their untransformed triage slots.
- **Stale gloss dropped.** A gloss is Claude prose written against the verdict that held at build time. When re-enrichment moves the verdict, that prose can contradict the fresh rating, so it is now cleared rather than shown (regenerating would cost a Claude call). At build time the gloss is still null, so this is a no-op on the write path.
- **Bulk score load at serve time.** The serve-time re-enrichment now pulls current scores through a single `EvaluationViewService.getScoresForEnrichmentBulk` load over the plan window instead of a lookup per region/date/target. It keeps the in-memory cached path (which carries the Claude `headline`, so card headers are preserved) and batches only the `forecast_evaluation` fallback into one range query per location — collapsing what was O(locations × dates × targets) point lookups into O(locations) range scans. `enrichWithCachedScores` was generalised behind a `RegionScoreResolver` so the build path keeps its per-region lookup while the serve path uses the pre-loaded bulk index.
- Tests: a stale WORTH_IT region downgraded to STAND_DOWN when fresh scores drop; stale gloss cleared on verdict change; gloss + verdict preserved when scores are unchanged; the internal `getCachedBriefing()` path verified to keep the frozen build-time verdict; `getScoresForEnrichmentBulk` covered for headline preservation, forecast fallback, cache-wins precedence, triage fallback, and one-range-query-per-location. New `DailyBriefingResponse.withDays` copy helper.

### Changed — hot topics: one card per date for a multi-day run
- **Why.** The chronological strip plus the day/event/time lead only pays off if a photographer can scan all of Saturday's opportunities, then all of Sunday's. A multi-day phenomenon previously collapsed to a single card dated to the earliest day with a spanning `detail` ("…today and tomorrow"), so the later day — and its own distinct sunrise time — was invisible. Solar times shift daily, so a shared/approximate time is not honest; one card per date is the correct model. This replaces the earlier display-layer phrase-strip shortcut with the full backend fix.
- **Per-date emission.** The seven multi-day-collapsing detectors — `Inversion`, `Dust`, `StormSurge`, `SnowTops` (survivor-signal), `Equinox` (solar-ephemeris), and `KingTide` / `SpringTide` (briefing tide alignment) — now emit one `HotTopic` per qualifying date, each carrying that day's own regions/alignment and a single-day `detail` (no spanning phrasing). The already-per-date detectors (`SnowFresh`, `Bluebell`, `Aurora`, `NLC`, `Meteor`, `Supermoon`) are unchanged. The run is naturally capped to the plan window (`today..+3`) the caller already passes.
- **Shared helper.** New `PerDateHotTopicBuilder` groups survivor-signal rows by date into per-date cards for the four simple detectors, so the expansion mechanics live in one place rather than being copied. `HotTopicEventEnricher` (unchanged) then stamps each card's own `eventType` + `eventTime` from its date, yielding the correct distinct time per card (04:43 Sat, 04:44 Sun) automatically.
- **Tide detail.** King/spring tide cards drop the "King tide {day range} · " prefix and the multi-day `formatDateRange`/day-labelled `buildAlignmentInfo`; each card's detail and expanded `tideMetrics` now reflect only that day's alignment counts. The day is carried by the card's timing lead.
- **Frontend.** No change needed — `HotTopicStrip` already sorts chronologically and keys pills by `type + date`, so two dated cards of the same type render as an adjacent, correctly-timed run.
- Tests: per-date assertions across the seven detectors (a 2-day run → two dated cards, no spanning phrase); a `HotTopicEventEnricher` two-day-run test asserting distinct per-day times; a frontend test asserting two same-type dated pills render in order each with its own time.

### Changed — Plan-screen UX polish: chronological hot topics, calendar-chip day headers, calmer aurora banner
- **Hot Topics — chronological order.** `HotTopicStrip` now sorts topics by day before rendering (all of Saturday's, then Sunday's, then Monday's), falling back to server priority then type for a stable within-day order — a reader scans one day at a time instead of following payload order.
- **Plan grid header — calendar chip.** The day-column header is now a horizontal calendar chip (weekday-over-number tile beside a stacked relative-day label + sunrise/sunset times) instead of three quiet stacked lines. Today's chip carries the gold accent; solar times use clean `↑` / `↓` glyphs rather than 🌅 / 🌇. Travel-day "away" badge preserved; `heatmap-day-header` / `heatmap-day-solar-times` testids preserved.
- **Aurora banner — Direction A redesign.** Replaced the flat neon-fill banner with a calm dark banner where severity is carried by an accent rail + roundel + labelled pill. All accent colour derives from a single `--aurora-accent` CSS variable (set from the API `hexColour`, blended via `color-mix`), so amber→red escalation themes automatically. The pill shows the level word plus the NOAA G-scale when the payload provides `gScale` (graceful "Strong" fallback otherwise). Every behaviour and testid preserved (detection stamp, Kp-forecast "tonight", Bz line, viewline, overcast, dismiss/escalation, pulse, simulated dashed border, click-to-navigate).
- Tests: aurora background-fill assertion re-expressed against the `--aurora-accent` variable; heatmap solar-glyph tests updated from emoji to `↑` / `↓`.

### Added — Hot Topics lead with day + event + time; aurora banner carries the NOAA G-scale
- **Why.** Two approved Plan-screen items were blocked on backend data. The Hot Topic timing lived only inside the English `detail` prose ("…tomorrow night"), so the strip couldn't show *which shoot* to hold or a real clock time; and the aurora banner's escalation index (`Strong · G4`) was invisible because the live status never returned a G-scale.
- **`gScale` on aurora status.** `GET /api/aurora/status` now returns `gScale` (`"G1"`–`"G5"`, or null below the G1 storm threshold). The simulate flow round-trips the value it's already handed; the live path derives it from the alert's trigger Kp. The Kp→G-scale mapping is centralised on `AlertLevel.gScaleFromKp` (the aurora forecast service now delegates to it). The banner already reads `status.gScale` and renders `Strong · G4`, falling back to the level word alone.
- **`eventType` + `eventTime` on hot topics.** Each topic now carries `eventType` (`"SUNRISE"` / `"SUNSET"` / `"NIGHT"`) and `eventTime` (Europe/London `"HH:mm"` on the topic's date, matching the plan header grid). A central `HotTopicEventEnricher` — invoked by `HotTopicAggregator` for both the real and simulated paths — maps each topic type to its photographic event (tides follow their sunrise/sunset alignment; storm surge/clearances get none) and computes the time from `SolarService` at a representative region location, falling back to a UK-centre point so sky-wide phenomena still get a window time (`NIGHT` uses civil dusk). Fields are additive and nullable — cached briefing JSON deserialises unchanged, and callers/tests predating the fields use a delegating 9-arg `HotTopic` constructor.
- **Hot Topic pill lead (Option B).** `HotTopicStrip` now opens the detail line with `{glyph} {day} {event} · {time}` in the topic's accent (↑ sunrise / ↓ sunset / ☾ night), then the plain condition — the redundant relative-day phrase ("today and tomorrow", "tomorrow night") is stripped for display only, so the existing aurora tonight/tomorrow join keeps working off the raw `detail`. Falls back to the bare detail when a topic has no event.
- Tests: `AlertLevel.gScaleFromKp` mapping, live-path gScale on the status controller, banner G-scale rendering; `HotTopicEventEnricher` (event policy, tide alignment, London-time formatting, fallbacks); HotTopic event-field JSON round-trip; and the frontend timing-lead + relative-phrase-strip cases. 4263 backend tests pass (only the pre-existing Docker-less Testcontainers integration tests error); 1563 frontend tests pass.

### Changed — NLC clarity and the aurora briefing count both sample the northern horizon
- **Why.** Aurora and noctilucent clouds both appear low on the *poleward* horizon, so what blocks the view is cloud toward the north — not cloud overhead. The aurora *triage* already sampled a northward transect, but two consumers used overhead point cloud instead: the NLC clarity scan (point cloud at the observer) and the aurora "N dark-sky locations with clear skies" count in the briefing (which even *overrode* the transect score with fresh point cloud). Both could call a night clear when the northern horizon was socked in, or vice-versa.
- **Shared sampler.** New `NorthwardTransectSampler` extracts the transect mechanics that `WeatherTriageService` pioneered — three points 50/100/150 km due north, deduplicated onto the ~0.1° grid across all locations, one `fetchCloudOnlyBatch`, transect-averaged combined cloud at the requested hours — parameterised by a `LayerCombiner` (sum-capped for aurora, worst-layer for NLC). `WeatherTriageService` now delegates to it (behaviour-preserving; its 15 tests unchanged).
- **NLC → transect.** `NlcClarityService` now samples the northern-horizon transect at each dark-sky location for the deep-night hour, instead of reading overhead point cloud from the briefing's colour-location weather. It also now covers **all** Bortle-classified locations (not just colour ones), fixing the earlier coverage gap — at the cost of one extra deduped cloud-only fetch per briefing run.
- **Aurora count → transect.** `BriefingAuroraSummaryBuilder` now takes the clear/not-clear decision (and the shown cloud%) from the score's northern-transect cloud — the same figure the triage used to decide viability — rather than overriding it with overhead point cloud. Point weather still supplies the temperature / wind / weather-code display.
- Tests: new `NorthwardTransectSampler` unit tests; NLC tests rebuilt against the sampler; aurora-summary tests re-expressed so the transect score drives the count. 4238 backend tests pass (only the pre-existing Docker-less Testcontainers integration tests error).

### Fixed — clock-hermetic tests: kill the nightly 23:00–24:00 UTC flake
- **Why.** A class of briefing/batch tests built date-relative data from the ambient wall clock while the production code computed "today" from `Europe/London`. In the 23:00–24:00 UTC window (London already the next day) the two dates diverged, so tests flaked — repeatedly reddening CI and failing locally for anyone not on UTC near midnight.
- **Backend (observed flakers).** Injected the existing `Clock` bean into the four services whose tests actually flaked — `BriefingHeadlineGenerator`, `ForecastTaskCollector`, `BriefingService`, `BriefingBestBetAdvisor` — routing every `LocalDate.now(Europe/London)` / `LocalDateTime.now(UTC)` through it so "today" and "now" derive from one instant and can't disagree. Their tests now run on a pinned `Clock.fixed(...)`, making them fully deterministic (and dropping the earlier zone-alignment band-aids in the best-bet tests).
- **Frontend (flaky files).** Pinned the clock with `vi.useFakeTimers({ toFake: ['Date'] })` + `vi.setSystemTime(...)` in `MapViewStarFilter` and `AuroraBanner` (and fixed `MapViewStarFilter`'s module-level `TODAY`). `DailyBriefing` was already zone-aligned. Only `Date` is faked, so testing-library's real timer waits still work.
- **Scope.** Deliberately narrow — the four observed backend flakers, not a codebase-wide `Clock` sweep. Verified green under both `TZ=UTC` and `TZ=Europe/London`; 4229 backend tests pass (only the pre-existing Docker-less Testcontainers integration tests error); 1555 frontend tests pass.

### Changed — every hot topic now reports all non-expired solar events, not just the earliest
- **Why.** The cloud-inversion fix (below) exposed a pattern shared by most hot-topic detectors: they collapsed to the *earliest* occurrence and/or never checked whether a sunrise/sunset had already passed. So a spring tide aligned with Saturday's sunset was invisible if the earliest spring-tide day was Thursday, and a "sunrise today" pill could point at a dawn already gone. A photographer plans around *every* valid future solar event, so the topic should surface them all.
- **Shared freshness filter.** New `SolarEventFreshness` (injected `Clock` + `SolarService`) centralises the "is this sunrise/sunset still ahead of now?" test that `InversionHotTopicStrategy` pioneered; new `DayLabels` util centralises relative-day wording and the multi-day "today, tomorrow and Saturday" enumeration (replacing 12 copies of `formatDayLabel`).
- **Solar-event-tied topics** (Inversion, Dust, Snow (fresh), Bluebell, Spring tide, King tide, Equinox) now **drop events whose sunrise/sunset has passed** and **survey the whole window**, dating the pill to the earliest non-expired occurrence and enumerating the rest. Spring tide now mirrors King tide (window survey + best-alignment highlight); both compute expiry from a representative coastal location and mask alignment counts to non-expired events. Equinox reports every near-equinox day whose due-east sunrise / due-west sunset still lies ahead.
- **All-day topics** (Snow on the fells, Storm surge) have no solar cutoff but were also collapsing to the earliest day — they now enumerate every qualifying day in the window.
- **Out of scope (unchanged):** night/calendar topics (NLC, Aurora, Meteor, Supermoon) — a later pass if wanted. Bluebell keeps its one-pill-per-day shape (each day carries its own expanded card) but now also drops expired-event rows.
- Tests: new `SolarEventFreshness`/`DayLabels` unit tests; per-strategy expiry + enumeration tests; Spring/King tide detail reformatted and re-asserted. Full hot-topic suite + Spring context load green.

### Changed — hot topics: cloud inversion is now advance notice, NLC gates on a clear night
- **Why.** Two hot topics were noise. (1) **Cloud inversion** is a *dawn* phenomenon, but the topic fired dated "today" — so an afternoon or evening briefing pointed at a sunrise that had already passed, useless for planning a shoot you can no longer reach. Worse, the detector picked the *earliest* strong-inversion day and emitted only that, so genuinely actionable future mornings were hidden behind the un-actionable "today". (2) **NLC** was a pure calendar gate — it fired *every single night* from 25 May to 10 Aug regardless of cloud, so it became ignored wallpaper, when NLC actually needs a clear northern sky.
- **Inversion — time-aware roll-forward.** `InversionHotTopicStrategy` now drops a today-dated strong-inversion row once the current time is past that location's sunrise (injected `SolarService` + `Clock`), so the topic rolls forward to the next actionable morning — or disappears — instead of pointing at a missed dawn. A pre-dawn briefing (before today's sunrise) still surfaces today's inversion.
- **NLC — clear-night gating.** New `NlcClarityService` runs during the briefing off the hourly weather already fetched for colour locations (no extra API call), sampling deep-night cloud (00:00 UTC, matching aurora) at every dark-sky location for each in-season night in the window. `NlcHotTopicStrategy` now reads that cache and fires only for the earliest night with ≥1 clear dark-sky location — naming it ("Clear northern horizon tonight / Friday night — N dark-sky locations") — and **suppresses the topic entirely** when nothing is clear. The densest cloud layer (low/mid/high) governs clarity, since any layer hides the high-altitude NLC.
- Tests: inversion suppresses/rolls-forward past sunrise and still fires pre-dawn; NLC service records clear/overcast/out-of-season/missing-hour nights; NLC strategy fires on the earliest clear night, pluralises correctly, and suppresses when the cache is empty or out of window.

### Fixed — three flaky `BriefingBestBetAdvisor` tests
- **Why.** `pastTodayEventSkipped` and `pastEventsSkippedBeforeCounting` dated their "today" in UTC while the advisor derives today from `Europe/London`, so in the 23:00–24:00 UTC window (under BST) the two dates diverged and the past-event skip silently never fired — a daily one-hour flake. `auroraPickPreservesRelationship` gave its sunset region zero colour coverage, so the honesty gate (`dropUnevaluatedPicks`) dropped it and the aurora pick — left as the sole head — correctly lost its `DIFFERENT_SLOT` relationship, failing the assertion at all times.
- **Fix (test-only).** The two past-event tests now date the day in `Europe/London` to match the advisor. The aurora test stubs `getCachedScores` so the sunset region carries colour coverage and survives as the rank-1 anchor. No production change.

### Fixed — Best Bet never recommends a day it hasn't colour-evaluated
- **Why.** The advisor could crown a day on weather **GO count + tide-alignment alone**, with zero Claude colour ratings behind it — e.g. a far-out T+3 Saturday that was never colour-evaluated presented as "settled skies, best GO count, conditions locked in", while the grid (reading the same absence of colour data) showed it "Poor". A clear sky is not a good sky; this app's whole premise is the colour evaluation, so a pick with no rating is a guess dressed as a recommendation. Confirmed against prod: the crowned pick's `claude_average_rating` was NULL and `cached_evaluation` had zero rows for that date.
- **Fix.** `BriefingBestBetAdvisor` now drops any pick with **zero colour coverage** (`claudeRatedCount == 0`) before ranking (`dropUnevaluatedPicks`); stay-home and aurora picks are exempt (they make no colour claim). If that leaves nothing, the advisor returns `SUCCESS_NO_PICKS` — the honest "nothing evaluated" state — rather than `FAILED` (which would resurrect a stale pick via the fallback) and rather than crowning the guess. Survivors are renumbered so the best-evidenced pick holds rank 1.
- Note: this composes with the travel-day filtering below — an all-travel window already yields no candidates; this additionally guards the non-travel far-out case (a genuine LLM misfire on GO count).

### Fixed — travel days now respect the calendar in Best Bet, hot topics, and the heatmap
- **Why.** The travel-day gate only ever filtered the forecast *batch* (`ForecastTaskCollector`). The Best Bet advisor and the hot-topic strip run off the briefing independently and had no travel-day awareness — so an overnight run that (correctly) submitted zero batches because the whole window was a travel day still crowned a travel-day Best Bet off verdict-only data (a NULL-rated "pick"), and the heatmap labelled away days "Poor" as if they'd been evaluated.
- **Best Bet / Also Good.** `BriefingBestBetAdvisor.buildRollupJson` now skips travel-day events entirely (and gates the aurora candidate on `!isTravelDay(today)`), so they never enter the prompt. An all-travel window yields an empty candidate set, which Claude already resolves to the honest "flat week — stay home" pick. (Deliberately *not* a hard short-circuit — the advisor still runs so the stay-home message is produced.)
- **Hot topics.** `HotTopicAggregator` drops any topic dated on a travel day — no "Spring tide today" / "Aurora tomorrow night" for days you're away.
- **Heatmap cells.** A travel-day cell now reads **"✈️ Away"** instead of a verdict like "Poor" — "Poor" falsely asserted an evaluation that never ran. (The day-header "Away — no forecast" badge and the map-popup notice were already in place.)
- Tests: rollup excludes travel-day events; aggregator suppresses travel-day topics; heatmap renders "Away" not a verdict.

### Added — Plan → Map handoff: "View on map" from Best Bet cards
- **Why.** The Plan view answers "where should I go?"; the Map answers "show me exactly where." The jump should carry context so the user never re-orients on arrival. (Part B of the map filter / handoff addendum.)
- **Bet card → region (B1).** Each navigable Best Bet / Also Good card gets a `🗺 View on map →` affordance (distinct from "Read more"). Clicking it sets the Map's date and event to the bet's, switches to the Map tab, and **fit-bounds to the bet's region** so all that region's pins fill the view (the macro view).
- **Plumbing.** `DailyBriefing` derives date + event from the resolved event key and hands off `{ region, date, eventType }`; `App.handleShowOnMap` routes region handoffs into a new `handoffRegion` prop; `MapView` adds a `FitBoundsController` that fits the map to the region's locations on each handoff (nonce-keyed so repeat taps re-fit).
- **Consistent cue (B2/B3).** The bet card reuses the same `🗺` map cue as the drill-down location rows, which keep jumping to a single pin (the micro view) — same gesture, two scopes.

### Changed — Map filter bar tidy (threshold control, grouped, collapsed default)
- **Why.** The Map is the "tell me more" follow-up to Plan, so the filter bar should be quiet by default. The old bar was one undifferentiated strip of ~16 controls with pipe separators, and the six rating "dots" looked like independent toggles when they actually behave as a minimum threshold.
- **Minimum-quality threshold.** The 1–5★ controls now render as a single segmented "this and above" control (`1★+ · 2★+ · 3★+ · 4★+ · 5★`) with a saved-state hint. It's a true threshold — selecting a level sets it (no more toggle-off), keeps each rating colour pip, persists to localStorage, and **defaults to 3★+** when unset.
- **Grouped & labelled.** Controls are grouped under **Minimum quality / Subject / Logistics** labels instead of pipe separators.
- **Admin-gated debug toggles.** The `— stand-down` and `? unknown` filters are a debug affordance (surface a washout instead of an empty map you can't distinguish from a load failure), so both are now **admin-only**, shown under a small "admin" tag inside the Quality group. Photographers (LITE/PRO) see only the threshold.
- **Collapsed by default.** The filter bar defaults to collapsed — a single `▾ Filters · 3★+ …` pill summarising the active threshold and any other active filters; the open/closed choice persists. The event toggles (Sunrise/Sunset/Astro/Aurora) stay always-visible (they're the mode switch, not a filter).
- **Filtering.** Wildlife (no sky rating by design) is exempt from the quality threshold so it never disappears; other unrated/not-yet-evaluated colour locations stay behind the admin "unknown" toggle, so the default 3★+ map reads quality-first.

### Fixed — Map marker collision in dense corridors (Hadrian's Wall)
- **Why.** Dense corridors (Hadrian's Wall packs ~7 spots into a few km) collided two ways at typical zoom: permanent name-labels overlapped into unreadable text-soup, and the rating discs themselves piled up.
- **Labels on demand (the biggest win).** Dropped the permanent name label under every marker. The default marker is now the rating disc only; the name appears as a small dark chip below the disc on hover/focus (desktop) and is otherwise out of the way. The marker icon footprint shrank from 100×62 to the 44×44 disc, so markers occupy far less space.
- **Hovered disc lifts to front.** On hover/focus the disc's `z-index` is raised so an overlapped marker pops fully above its neighbours — readable and clickable rather than half-buried. (CSS in `MapView`'s injected popup styles.)
- **Cluster tuning.** Raised `maxClusterRadius` 60→80 and `disableClusteringAtZoom` 10→13 so a co-located corridor collapses into one bubble until zoomed in to street level. The cluster bubble already shows count-only with colour = group-average score (unchanged); per-location numbers appear only once unclustered.
- No behavioural change to the cluster icon builder or the rating ramp; full frontend suite green (1553).

### Changed — Map location popup reskin + tidy (Kodachrome)
- **Why.** The global Kodachrome reskin never reached `MarkerPopupContent`, so the map popup still rendered the old light theme (white card, slate text, gold `#E5A00D` pills, blurple links) — a bright white card on the dark map.
- **Reskin.** Replaced the popup's hard-coded hex with `--color-plex-*` tokens so it tracks the theme: the Leaflet popup wrapper/tip/close-button now use `--color-plex-surface` + `--border-light` + a soft shadow (`MapView.jsx`); title/summary/footers use bone ink; the summary sentence uses Newsreader serif. Pills moved off gold — event pill cool blue, type tag surface-light, sunrise/sunset + golden amber, blue hour blue, drive chip neutral, tide chip tide-teal. "More details" link is now bone/mono underlined (was blue). Stars: `--color-verdict-marginal` filled, muted empty.
- **Score bars — corrected direction.** Both bars now run muted-grey (low) → hot (high), so a higher score fills further AND glows hotter (Fiery Sky was previously backwards). The gradient is sized to the full track and the remainder masked, so a low score shows only the muted end. The value number is tinted value-driven along the same ramp (a Fiery Sky of 20 reads muted, not red).
- **Tidy.** The existing "More details" collapse is kept — stars + drive + tide + summary lead; the type/sunrise-sunset/golden-blue tag rows + Scores + footer stay one tap away.
- **Cleanup.** Dropped the now-obsolete `darkMode` light-theme branches (the app is uniformly dark). Popup tests stay green (130).

### Changed — Plan tab reskin (Kodachrome / Option B bone accent) + density tidy-up
- **Why.** The Plan tab leant on repeated low-value text (POOR cells shouting, per-location reasoning paragraphs, inline region lists) and a brand accent that collided with the "stand down" red. The fix: lead with the verdict/star, push prose one interaction away, and free colour to mean only verdict semantics.
- **Reskin.** `index.css` adopts the warm Kodachrome palette — surfaces `#181210`/`#221A15`/`#2A2019`, bone ink `#F2E7D3`, verdict tokens `go #8AAE72` / `marginal #E0A542` / `standdown #C8452F`, new `--color-tide #6FA8B0`. The interactive/brand accent (`--color-plex-gold`) moves to **bone `#F2E7D3` (Option B)** so chrome never competes with the verdict colours. Added Newsreader (serif gloss/reasoning) and IBM Plex Mono (meta/counts) via `index.html` + `--font-serif`/`--font-mono` theme tokens. Leaflet control colours repointed to the new palette.
- **Hot Topics (`HotTopicStrip.jsx`).** Collapsed to a single line — `[icon + label] [detail, ellipsis] [N regions] [▸]`; the full region list never renders inline by default and is revealed on tap. Labels are bone (accent lives only on the 3px left border). Atmospheric topics share the tide teal, nightglow topics a violet, so the strip reads as a small coherent palette.
- **Heatmap cells (`HeatmapGrid.jsx`).** Poor cells collapse to a single quiet "Poor" (~52px); good cells drop the in-cell gloss sentence (now a hover tooltip with verdict + Newsreader-italic gloss + weather) and align in a tidy ~52px band; the per-cell verdict distribution bar is removed; the mean-rating star pill uses rank-bucketed colours.
- **Region drill-down.** Collapsed rows lead with a rank-coloured star pill, name with a rotating ▶ arrow, tide chips and drive; the reasoning sentence is tap-to-expand (Newsreader italic). The "Include poor locations" toggle moved into the drill-down header; the poor section keeps tide chips (objective facts) but drops stars/prose.
- **Bets + mobile (`DailyBriefing.jsx`).** Best Bet / Also Good cards clamp their detail to two lines with a "Read more ▾" toggle and a verdict-coloured left border. The quality slider is removed from the Plan tab (poor cells are now muted passively). Mobile event rows show lowercase `go`/`maybe`/`poor` counts, fade mostly-poor rows to 0.4, and drop the repeated per-row "Away — no forecast" pill.
- **Tests.** Updated the `HotTopicStrip` / `HeatmapGrid` / `DailyBriefing` suites to the new DOM/colours and removed assertions for retired controls (slider, distribution bar, in-cell sentence). Full frontend suite green (1553 tests).

### Removed — retired the obsolete Angel-of-the-North eval fixture
- **Why.** `angel-of-the-north-2mar-spectacular` was observer-point only (no directional cloud / cloud-approach), which made it an *obsolete* test on two counts: (1) the pipeline now augments every forecast with directional data, so an observer-only input is a shape production no longer produces — testing the scorer on data it never receives doesn't measure real behaviour; and (2) its `{4,5}` band came from the *observed evening*, but the fixture's *input* (100% high cloud, no clear-horizon data) only justifies a cautious 3 — a right-answer-to-the-wrong-question test. The persistent flat-3 wasn't a prompt bug or a fixable fixture; it was a fixture pairing a real outcome with an input the app abandoned.
- **What.** Removed the JSON + registry entry + its faithfulness assertion; left a marker in `SkyRatingEvalFixtures` to slot in a captured-real, FULLY-AUGMENTED spectacular day from current prod when one is exported (that restores a genuine "can it return a 5?" probe). `st-marys-10mar-moderate` `{4}` still covers the strong half of the range, so the both-halves invariant holds. Stale "angel-…" labels in the controller/report unit tests repointed to a real fixture / a neutral example.
- **Principle.** Eval fixtures should represent inputs the app produces *now*; captured-real fixtures from current prod get that for free, hand-ported ones can go stale as the pipeline evolves.

### Added — batched execution for the weekly multi-model sky-rating eval
- **Why.** The weekly eval scores every fixture with Haiku, Sonnet, and Opus — ~144 real-time Claude calls each Sunday. Those per-model runs were always "the unit a future batched execution layer would submit" (the deferred-batching note in the multi-model eval). This builds that layer: the same fixtures × runs × models go out as one **Anthropic Batch API** submission, for the 50% token discount and out-of-band execution.
- **Orchestration.** `SkyRatingEvalBatchService` builds one request per (run × fixture × run-index) via the pipeline's own `BatchRequestFactory`, submits them as a single batch, then — on a background virtual thread — awaits completion and parses each result back to its origin via a self-describing `e_<runId>_<fixtureIdx>_<runIndex>` custom id. Result text is parsed with the same `ClaudeEvaluationStrategy.parseEvaluation` the synchronous scorer and the forecast batch handler use.
- **Lean, self-contained path.** A thin `SkyRatingEvalBatchClient` wraps the three SDK calls (submit / await-ended / collect-results); the eval does **not** thread its batches through the forecast pipeline's `ForecastBatchEntity` / polling / disposition machinery (that's coupled to forecast persistence). The few-hundred-request weekly batch completes in minutes, so it polls inline.
- **Shared persistence.** Run lifecycle, band-classification, result rows, and finalisation stay in `SkyRatingEvalService` — the batch path calls its `persistResult`/`finalise` with `isBatch=true` (halving the recorded cost), so both paths write the `sky_rating_eval_*` tables identically. No schema change.
- **Config.** `photocast.eval.batch.enabled` (default true) routes the weekly scheduled job to the batched path; `false` reverts it to synchronous. The admin single-model trigger stays synchronous. Poll timeout/interval are configurable (`poll-timeout-seconds`/`poll-interval-seconds`). Trigger a batched run on demand from Manage → Operations → Scheduler → `sky_rating_eval`.

### Changed — Travel Days panel: usable calendar, explicit inclusivity, future-only descending list
- **Calendar control.** The native date fields had a near-invisible picker icon on the dark theme and only the icon opened the calendar. The whole field now opens it (`showPicker()` on click), and the indicator is inverted/brightened so it reads on dark (`.date-field` rule in `index.css`).
- **Inclusivity made explicit.** The From/To labels now say "(inclusive)" and the intro states "Both the start and end date are included" — matching the actual behaviour (the gate uses `BETWEEN`, inclusive of both bounds).
- **Future-only, descending.** `TravelDayService.list()` now returns only ranges whose end date is on or after today (Europe/London) — already-ended holidays drop off automatically — sorted by start date descending so the furthest-future range sits at the top. A range ending *today* is kept (you're still away today). Backend-side so the admin panel and the briefing/map overlay stay consistent; new `findByEndDateGreaterThanEqualOrderByStartDateDesc` derived query covered by a `@DataJpaTest`.

### Added — click a Plan-tab location to open it on the map
- **Why.** From the briefing drill-down you could see a location's verdict and headline, but reaching its full forecast meant switching to Map, finding the date, the event type, and the marker by hand. The location name is the obvious thing to click — now it is.
- **What.** Each location name in the briefing drill-down slot list is now a button. Clicking it switches to the Map tab with the date and solar event (sunrise/sunset) pre-selected, flies to the location, and opens its popup. Mobile selection drives the existing bottom sheet; desktop opens the Leaflet popup once the fly animation settles (so the marker has declustered).
- **How.** New shared `SlotLocationName` component renders the clickable name (falls back to a plain label when no handler/date is supplied). `LocationSlotList` in both `HeatmapGrid` and `DailyBriefing` thread `onShowOnMap(date, targetType, locationName)` through. `App.handleShowOnMap` gained an optional `locationName` plus a monotonic nonce so repeat taps re-trigger selection; `MapView` flies to the location and a new `HandoffPopupController` opens its popup via a marker-ref map.

### Added — travel-day gate: skip forecasts for dates you're away
- **Why.** The sole operator commutes to London on a roster that changes week to week; on those days no photography happens at the configured locations, so any overnight Claude forecast whose **target** date falls on a travel day is pure spend with no payoff.
- **Gate (per target-date, not whole-run).** `ForecastTaskCollector.collectForecastCandidates` now skips every candidate whose target date is a declared travel day — mirroring the existing PAST_DATE skip — with a new `SKIPPED_TRAVEL_DAY` disposition and a `[BATCH DIAG] SKIP date … reason=TRAVEL_DAY` log line. The nightly batch still runs and still covers non-travel days; only the unusable target dates drop out. An empty `travel_day` table makes the gate a no-op.
- **Storage.** `travel_day` table (V119) — inclusive `[start_date, end_date]` ranges with an optional note. Global, not user-scoped: the scheduler thread has no authenticated user, and there is only one operator. Ad-hoc ranges (travel isn't fixed), added/removed weekly — no seed rows.
- **Admin UI.** Manage → Operations → **Travel Days** — add/remove date ranges (`TravelDaysView`, `TravelDayController` at `/api/admin/travel-days`, ADMIN-only).
- **"Not executed — travel day" surfaced in the UI.** Rather than a silently blank slot, both views now explain the absence. Travel days are a cross-cutting *overlay* derived client-side from a new any-authenticated read endpoint (`GET /api/travel-days`, `TravelDayQueryController`) via `isTravelDate(dateStr, ranges)` — no flag baked into the immutable `BriefingDay`/forecast records. The Plan/Briefing view shows an **"✈️ Away — no forecast"** badge on travel-day columns (desktop heatmap header) and mobile event rows; the Map popup shows a **"Forecast not run — you're away (travel day)"** notice.

### Added — multi-model weekly eval (Haiku vs Sonnet vs Opus accuracy/drift)
- **Why.** The weekly eval now does two jobs: regression-test the prompt AND measure cross-model accuracy — is Haiku as accurate as Sonnet as Opus on the ground-truth fixtures? That directly informs production model selection (the app already picks a model per run-type): a confident "Haiku lands in band 95% as often as Opus here" lets you downgrade a run-type and save real money in the pipeline, where the call volume dwarfs the eval's.
- **Backend.** `SkyRatingEvalService.runScheduled()` now scores every fixture with each model in `SCHEDULED_MODELS` (Haiku, Sonnet, Opus), one persisted run per model; a model's failure is logged and doesn't abort the others. The schema already carried `model` on the run and trend point, so no migration. The per-model runs are deliberately the unit a future batched execution layer would submit.
- **Frontend (Manage → Operations → Sky Eval).** New "Model accuracy over time" chart — one pass-rate line per model — the headline cross-model comparison. The per-fixture drift small-multiples are now scoped to the selected model (pick via the existing model radio) so they aren't 3× the lines.
- **Batching deferred, deliberately.** Batching is the *execution layer* under multi-model (async, −50% tokens) — orthogonal to *what* runs. At the current fixture count its savings are still ~£tens/yr against real async complexity; the multi-model layer is the lasting part and a sync→batch swap later needs no rework. Revisit batching once the fixture count makes the discount (Opus-dominated) worth it.

### Added — monitored (trend-only) eval fixtures + pass^k session-correlation finding
- **Finding.** Running the same fixture many times *within one session* does not give independent pass^k samples — the scorer's answer clusters within a session and flips between sessions. `copt-hill-5mar-washout` (67% low cloud, right on the 60% block line) was seen at 6/8, 0/8, 8/8, 0/8 on identical input/code. So a single-session pass^k result for a boundary-sitting fixture is **one effective sample, not a verdict**, and hard-gating on it would cry wolf — exactly the alert-fatigue that hides a real regression.
- **Fix.** `SkyRatingEvalFixture` gains a `gated` flag (default true). A `gated=false` fixture is still scored, reported, and trended by the weekly recorder, but does **not** fail the manual `SkyRatingEvalTest` run. The washout is the sole monitored fixture; its band is **unchanged at `{1,2}`** (a 3 for a washout would be a real over-rating, never tolerated — the band is not relaxed, only the single-session gate). A guard test pins that *only* the washout is monitored, so the flag can't spread to inconvenient fixtures.
- **Guidance.** The weekly cadence is the `sky_rating_eval` *recorder* (records + graphs), not a binary alarm — read the trend, where a coin-flip (no trend) and a real regression (sustained shift on a stable fixture) look different. A decisively-blocked day (90%+ low cloud) would be a stable *gated* washout monitor; capture one when convenient.

### Fixed — thick solar-side mid/high cloud read as a blocker, not a lit canvas (sky-rating eval finding)
- **Symptom.** The eval's St Mary's 10 Mar fixture (clear solar low cloud under 100% mid/high — observed 4★) scored 4 in 6/8 runs but craters to 2 in the other 2, with summaries calling the thick mid cloud a "blanket" with "no clear canvas above". A consistency failure, not a missing rule: the prompt **already** prescribes RATE 4 for "solar low <20% + thick mid >80%" (PromptBuilder system prompt), and even injects an inline `[THICK MID CLOUD — rate 4, not 5]` annotation.
- **Why it flipped.** Two prompt tensions: the word "blanket" was overloaded (used for both the rate-4 thick-mid case and the rating-1–2 EXTENSIVE BLANKET), and the canvas framing is solar-clear + antisolar-canvas — but this day has the canvas on the **solar** side with a bare antisolar side, so the model occasionally read "100% solar mid + empty antisolar → blocked → 2".
- **Fix (two nudges, measured between).** (1) Clarified the rule: thick solar-side mid/high above clear low cloud is a **lit canvas, not a blocker** — only solar LOW cloud blocks light, and this holds even when the antisolar side is bare; dropped the overloaded "blanket" wording. That killed the catastrophic misread (floor moved 2→3). (2) The residual was the model rounding "muted/diffuse/uniform → maybe(3)": added that **diffuse/soft/muted warm tones across a clear-penetration canvas are STILL rate 4 (worth the trip)** — uniformity caps at 4 and reduces fiery_sky but does not drop to 3 — and strengthened the inline annotation to `[THICK MID CLOUD — rate 4 (worth the trip), not 3, not 5]`. **No expected scores changed** — both steer the model toward the prompt's own stated answer (4), which matches the observation.
- **Band corrected to `{4}`.** St Mary's 10 Mar was briefly widened to `{4,5}` for pass^k headroom, but the prompt caps this scenario at 4 ("never 5") and the observation was 4 — so the band is restored to the exact observed `{4}`, true to ground truth.
- **Overfit caught and corrected (the eval doing its job).** Nudge (2) leaked: it pushed `copt-hill-5mar-washout` (67% blocked solar low + 100% high cloud) from its ~6/8 baseline to 0/8 (all 3s) — a hard-skip horizon creeping up because "muted canvas is still worth it" wasn't anchored to clear low cloud. Fixed by scoping the rate-4-muted floor to **clear light penetration only**: it applies ONLY when solar low <20%; a BLOCKED solar horizon (low >60%) stays rating 1-2 regardless of any mid/high canvas — the blocking ceiling always wins. This didn't just contain the bleed, it **sharpened the discrimination**: the washout went to **8/8 at `{1,2}`** (better than its pre-existing baseline) while St Mary's held **8/8 at `{4}`**.
- **Result.** St Mary's 6/8 (misses to 2) → 5/8 (misses to 3) → **8/8 `{4}`**; washout (a clear-low-cloud-vs-blocked discrimination the prompt now makes cleanly) **8/8 `{1,2}`**. Both confirmed by real `SkyRatingEvalTest` runs; unit tests green (prompt content + fixtures). A full-suite run is the standing collateral-damage check.
- **Tooling.** `SkyRatingEvalTest` gained a `-Deval.fixture=<substring>` filter to run a single fixture (8 calls instead of 64) when iterating on one calibration finding.

### Fixed — `StackOverflowError` parsing long Claude responses in the regex fallback
- **Symptom.** A full `SkyRatingEvalTest` run crashed 6/8 fixtures with `StackOverflowError` deep in `java.util.regex.Pattern`. Not environmental — and not eval-only: production parses Claude responses the same way, so a long-enough malformed response would crash a real evaluation.
- **Root cause.** `SUMMARY_PATTERN` / `BASIC_SUMMARY_PATTERN` used `(?:[^"\\]|\\.)*` — an alternation-inside-a-star that Java compiles to a matcher recursing **once per character**. When strict JSON parsing fails and the regex fallback runs on a long response, the capture group consumes a huge run and overflows the stack.
- **Fix.** The equivalent "unrolled loop" form `[^"\\]*(?:\\.[^"\\]*)*` — same language, but the character-class star matches iteratively, so recursion depth is bounded by the number of escape sequences (≈0), not the input length. Regression test pushes a 20k-char summary through the fallback and asserts no overflow.

### Fixed — degenerate `test`/`placeholder` summaries (surfaced by the sky-rating eval)
- **Symptom.** The first real `SkyRatingEvalTest` run showed the scorer intermittently returning stub summaries (`test`, `placeholder`, `…`) across fixtures — and at least one stub carried an off-band rating, polluting the eval signal.
- **Root cause.** In `PromptBuilder.buildOutputConfig()` the `summary` (and `basic_summary`) fields were bare `{"type":"string"}` with **no field-level description**, while the 0–100 score fields all carry one. All the "one sentence, in Claude's voice" guidance lived only in the (cached) system prompt, far from the constrained field, so under structured-output decoding the model occasionally satisfied the required field with filler.
- **Fix.** Added a `description` to `summary`/`basic_summary` forbidding placeholders — the same description-based mechanism the score fields use (validation keywords like `minLength`/`minimum` are deliberately avoided; Anthropic structured output rejects them, per the existing schema-guard tests). Inherited by `CoastalPromptBuilder`. Scoring fields (`rating`/`fiery_sky`/`golden_hour`) are untouched, so this changes no expected scores. New guard test pins the description so it can't silently regress; the gated `BatchSchemaIntegrationTest` (real API) confirms Anthropic still accepts the schema.

### Added — Sky-rating eval persistence + calibration-drift graphs
- **Why.** The gated `SkyRatingEvalTest` is a pass/fail gate whose output is throwaway log lines. This adds the longitudinal half: an app-side service runs the same frozen fixtures through the **real production scorer** (`EvaluationService.evaluateWithDetails`) on a cadence and persists every run, so calibration drift can be graphed over time and a step-change attributed to the prompt edit that caused it.
- **Schema (V117/V118).** `sky_rating_eval_run` (parent) + `sky_rating_eval_result` (child), mirroring the `prompt_test_run/result` grain. Results store the 1–5 rating **plus** the 0–100 `fiery_sky`/`golden_hour` sub-scores (the finer drift signal — calibration can move 10 points before a star flips), each fixture's expected band, and the `MissDirection` bucket; runs store aggregate pass-rate, direction-bucketed miss counts, token cost (micro-dollars via `CostCalculator`), and the git commit.
- **`SkyRatingEvalService`.** `startRun` (quick RUNNING row for async 202) + `executeRun` (the multi-minute scoring loop — deliberately not one long transaction; each result auto-commits) + `runScheduled` (defaults Sonnet × 8). The fixture primitives (`RatingBand`, `MissDirection`, `SkyRatingEvalFixture(s)` + the 6 JSON fixtures) were promoted from test to **main** sources so both the gated test and the service share one fixture set.
- **Weekly scheduler job (PAUSED).** Registered with `DynamicSchedulerService` as `sky_rating_eval`; V118 seeds it **PAUSED** (Mon 03:00 UTC) so merging never auto-spends until an admin resumes it from the Scheduler UI. Weekly suits slow-moving calibration drift (distinct from the daily real-api smoke).
- **Admin UI (Manage → Operations → Sky Eval).** `SkyRatingEvalController` (ADMIN: async `POST /run`, `GET /runs`, `/runs/{id}`, `/runs/{id}/results`, `/trend`) + `SkyRatingEvalView` (Recharts): per-fixture rating-over-time with the band shaded, a sub-score toggle (0–100 fiery/golden), an overall pass-rate trend, and a runs table with DOWN/UP miss breakdown, cost, commit, and live status polling.

### Changed — sky-rating eval fixtures are now real-day ports (not synthetic)
- **Why.** The strong/flat/clear-cap fixtures were hand-authored placeholders, but every `PromptRegressionTest` case is a real day (real Open-Meteo / production input + the band Chris actually observed). Real provenance beats synthetic, so **all 7 regression cases are now ported verbatim** as the eval fixtures: Copt Hill 5 Mar (washout {1,2}), Angel of the North 2 Mar (spectacular {4,5}), St Mary's 10 Mar (moderate — band widened from observed exact-4 to **{4,5}** for pass^k headroom, Chris's call), Copt Hill 11 + 15 Mar (false-positive / overcast {1,2}), Copt Hill 16 Mar (horizon-strip {3,4}), St Mary's 7 Apr (clear-sky cap {1,3}). The three synthetic strong/flat/clear fixtures are retired; one hand-authored middling **{2,3}** is kept for a low-middle band no real day supplies.
- **Augmentation is the day's reality, not forced.** Some real days carry full augmentation (directional + cloud-approach trend); others only what was persisted that run (Angel is observer-point only). `SkyRatingEvalFixturesTest` now verifies each fixture deserialises *faithfully* (no silent field-name defaults) rather than requiring full augmentation. Two fixtures carry tide and exercise `CoastalPromptBuilder`. A fully-augmented strong day from current prod remains the ideal future upgrade over the observer-only Angel anchor.
- 8 fixtures × 8 runs ≈ 64 real Sonnet calls per eval run (was ~48).

### Changed — dependency bumps
- `com.anthropic:anthropic-java` 2.40.1 → **2.42.0**; `org.pitest:pitest-maven` 1.25.4 → **1.25.5**. Clean compile + full suite green (bar Docker-only Testcontainers integration tests, which need a Docker daemon).

### Added — Sky-rating eval harness (pass^k bands + direction-bucketing)
- **Why.** Grounded in `docs/engineering/sky-rating-eval-investigation.md` (Step 0). The base `PromptBuilder` sky-rating scorer is non-deterministic, so the honest test is **pass^k**: run a frozen input N times and assert the star `rating` lands in an expected BAND every time (never `== point`, which fails on legitimate variance). This is the standing acceptance gate prompt-calibration work has lacked — prompt changes get measured, not vibe-checked.
- **Supersedes the mechanics of `PromptRegressionTest`, inherits its philosophy.** Step 0 found that test is a *partial eval*, not a regression guard: it asserts absolute observation-derived bands but single-shot, on partial input. The new `SkyRatingEvalTest` (gated `@Tag("prompt-regression")`, real Claude) adds what it lacked — the `pass^k` loop (`RUNS_PER_FIXTURE = 8`), direction-bucketed miss reporting, and full-augmentation fixtures. **`PromptRegressionTest` is untouched** (its assertions are user-owned); retiring it is a separate, Chris-approved step once coverage is confirmed.
- **Model = Sonnet** (the near-term scorer PhotoCast actually uses), not the `PromptRegressionTest` Haiku default; override with `-Deval.model=HAIKU`. A full run is ~48 real Sonnet calls (6 fixtures × 8). Run with `./mvnw test -Pprompt-regression -Dtest=SkyRatingEvalTest`.
- **Direction-bucketing is the diagnostic payoff.** Out-of-band misses are split DOWN (below band → scorer too cautious) vs UP (above band → too generous), so a failure says *which way* the prompt is miscalibrated. `RatingBand` + `MissDirection` + `PassRateReport` are pure logic, unit-tested with stubbed scores (`RatingBandTest`, `PassRateReportTest`) — the bucketing is verified in `mvn verify` without Claude.
- **Fixtures at full augmentation, both halves.** Six frozen `AtmosphericData` JSON resources under `eval/fixtures/`, each carrying the always-block PLUS `directionalCloud` and `cloudApproach.solarTrend` (the colour-load-bearing blocks): a strong (4–5), a flat washout (1–2), two ported verbatim from `PromptRegressionTest` (Copt Hill 11 Mar false-positive {1,2}, 16 Mar horizon-strip {3,4}), a middling (2–3), and a clear-sky-no-canvas CLEAR-SKY-CAP edge ({1,3}). Loaded via a Jackson 2 + `JavaTimeModule` mapper matching the production replay engine, so captured-real exports from `prompt_test_result.atmospheric_data_json` drop straight in. `SkyRatingEvalFixturesTest` (runs in `verify`, no Claude) proves every fixture deserialises fully-augmented and key scalars/trend semantics round-trip.
- **Known limits (v1, by design).** Score-band only — rationale (`summary`) assertions are deferred to v2 (prose-keyword brittleness). Strong/flat anchors are hand-authored placeholders pending captured-real prod exports (Chris runs prompt-test on prod; the harness + loader are ready). Aerosol/inversion may be null on captured fixtures (the fresh-fetch blind spot) — acceptable for a colour band eval.

### Fixed — INVERSION hot topic reads the survivor surface (`forecast_score`), not the triaged rejects (Stage A)
- **Why.** Grounded in `docs/engineering/inversion-persistence-gap-investigation.md` + `survivor-surface-investigation.md`. The INVERSION detector read `forecast_evaluation.inversion_potential`, but nightly that table holds only the **triaged-out rejects** (scoreless rows built with an empty eval); the survivors Claude actually scores route to `cached_evaluation`, and the structured inversion value never reached the column the detector queried. Result: the detector was **fully inert in production** (data-confirmed — the 01:00 batch wrote 0 inversion / 0 rated, the 06:00 sync wrote 54 / 186). Unlike its siblings, `inversion_potential` is sourced from the Claude *verdict* (`evaluation.inversionScore()`), so the reject rows' empty eval left it null even though `dust`/`surge` (pre-eval atmospheric data) populated fine.
- **The blessed surface, not a JSON hack.** The fix rides the existing `forecast_score` re-architecture (V107/V108) — the survivor-populated, SQL-queryable, location-grained table V107 already named inversion as a one-seed-row fold-in for, and that `BluebellHotTopicStrategy` already reads. No `cached_evaluation.results_json` field sprawl.
- **INVERSION forecast type (V114).** Seeded `forecast_type` row `(6, 'INVERSION', 'Cloud Inversion Forecast', 10)` + the `ForecastType.INVERSION` enum constant (the pair `ForecastTypeSeedDriftTest` enforces). `scale_max = 10`: inversion is a standalone 0–10 likelihood, not a 1–5 combiner peer or a 0–100 display product — it never folds into the rating.
- **Dual-write from the eval.** `ForecastScoreWriter.write` now upserts an `INVERSION` component from `eval.inversionScore()` (score) + `eval.inversionPotential()` (classification → `summary`), guarded on a non-null inversion score so ineligible locations write no spurious NONE rows. Fires from BOTH the batch survivor path (so the detector reads survivors) and the sync/admin path (consistency). Bluebell-only `writeComponents` untouched.
- **Detector repoint (bluebell pattern).** `InversionHotTopicStrategy` now reads `forecast_score` via the new `findComponentsByType(INVERSION.id, from, to)` and fires at the STRONG band — stored score ≥ 9, mirroring `PromptBuilder.InversionPotential.fromScore` (9–10 = STRONG); MODERATE (7–8) and below never fire. The legacy `forecast_evaluation.inversion_potential` write (`buildEntity`) and `findInversionDaysByPotential` are left in place (sync path still writes them; removal is separate cleanup).
- **Tests.** `ForecastScoreWriterTest` (inversion-bearing eval writes the INVERSION row with score + classification summary; inversion-free eval writes none), `InversionHotTopicStrategyTest` rewritten against `forecast_score` with the STRONG boundary (8 no, 9 yes) and MODERATE-mixed-with-STRONG, `ForecastScoreRepositoryTest` (`findComponentsByType` type + date-range filter), `ForecastTypeSeedDriftTest` green with INVERSION added. Checkstyle clean. The `forecast_score` dual-write **Docker/Testcontainers** integration test (`ForecastScoreDualWriteIntegrationTest`) is environmental here — verified its `evalJson` fixtures carry no `inversion_score`, so its exact row-count assertions are unaffected when it runs in CI.
- **Acceptance (the "shipped ≠ working" guard).** Today's Lakes data peaks at MODERATE, no STRONG — so even a perfect repoint won't light the pill until a genuine STRONG occurs; that is correct, not a failure. Acceptance is the forced-STRONG unit fixture firing + the dual-write landing INVERSION rows on a real cycle, NOT a live pill. **Outstanding (run by Chris against prod/staging):** confirm the `photocast.forecast-score.dual-write` flag is ON in the running container (committed config defaults true; not overridden in `application-prod.yml`); confirm INVERSION `forecast_score` rows populate on a real survivor cycle; sanity-check STRONG fire-frequency against the survivor population.
- **Stage B (deferred, tracked).** DUST/SURGE and (also found) SNOW_FRESH/SNOW_TOPS share the reject-sampling root but are pre-eval *atmospheric* data NOT present at the survivor seam (`buildResult` gets only the Claude eval) and not score-shaped — a separate, larger carrier design, not bundled here.

### Added — Reward cloud clearing-into-the-event in the per-location rating (clearance, NOT a hot topic)
- **Why.** Grounded in `docs/engineering/clearance-investigation.md` + `clearance-trajectory-investigation.md`. Clearance is quality-of-sky, so it belongs in the per-location Claude rating, not a hot-topic banner (a banner would double-count the rating the clearance causes). The clearance *outcome* already scored 4–5 via the point-in-time directional rules; what was missing was recognition of the clearing *trajectory* + canvas-persistence — the `T-3h…event` series was in the prompt but framed only as `CLOUD APPROACH RISK` with a `[BUILDING]`-penalty-only instruction set.
- **#3 over #2 (the full signal).** Extended `SolarCloudSlot` with nullable `midCloudPercent`/`highCloudPercent` (the canvas) alongside low cloud (the blocker), and added `SolarCloudTrend.isClearing()`: true when low drops into the event (≥`CLEARING_LOW_DROP_PP` = 20pp) **while the mid/high canvas survives** (event canvas ≥`CANVAS_PRESENT_FLOOR_PP` = 25%, canvas drop <`CANVAS_COLLAPSE_PP` = 20pp). This distinguishes a dramatic clearance (blocker lifts, canvas stays) from a wholesale clear toward bald blue (the CLEAR-SKY-CAP case) — which a low-only trend (#2) could not.
- **Migration-free, no new fetch.** `extractSolarTrend` now captures mid/high from the same cloud-only batch response (all three layers were already fetched and read for directional sampling). The prompt reads the live in-memory trend, so no persisted column changes; `solar_trend_*` stay low/building-centric.
- **The prompt reframe (base `PromptBuilder` only; Coastal inherits, Bluebell untouched).** The trend print widens to low/mid/high per slot when the canvas is captured and emits a `[CLEARING]` label; the `SYSTEM_PROMPT` adds the clearing case as a **confidence/urgency signal, NOT a rating lever** (no double-count on top of the point-in-time rules) bounded so a wholesale clear carries no label and stays capped. `[BUILDING]` keeps precedence and is untouched.
- **Harness fidelity.** `PromptTestService.fetchAtmosphericData` now mirrors the real pipeline's `augmentWithDirectionalCloud`/`augmentWithCloudApproach` (it previously augmented tide only), so the prompt-test before/after genuinely exercises the trend block rather than comparing two trend-less prompts.
- **Backward-compatible.** Mid/high are nullable with a 2-arg convenience constructor — legacy/synthetic/regression slots construct and print unchanged (low-only), and `isClearing()` returns false when the canvas was not captured. Golden masters change only by the added system-prompt paragraph (regenerated; user messages byte-identical).
- **Validation.** Deterministic layer green: `SolarCloudTrendTest` boundary set (incl. the wholesale-clear negative control), `extractSolarTrend` canvas capture, `PromptBuilderTest` (`[CLEARING]` fires only on a genuine clearance, negative control, building precedence), `PromptTestServiceTest` harness mirror, `PromptGoldenMasterTest` regenerated. **Outstanding (needs `ANTHROPIC_API_KEY`, run by Chris):** the live prompt-test before/after on the boundary set and `PromptRegressionTest` green — the real-Claude calibration that confirms no rating inflation.

### Added — Snow trio hot-topic detectors (snow trio Phase 2)
- **Why.** With the snow data persisted (Phase 1), the snow trio detectors can read it on the proven `@Component HotTopicStrategy` pattern (auto-collected by `HotTopicAggregator`, 4-day window, inherited gating) — no new plumbing, no external API calls. Thresholds are Chris's locked photographic calls.
- **SNOW_FRESH (with SNOW_MIST variant).** Fires when `snow_depth_m ≥ 0.02` (2 cm lying) on any row in the window — depth-only, the photographic call being "is snow underfoot," not "did it fall overnight." When the existing briefing mist signal co-occurs (humidity above `BriefingVerdictEvaluator.HUMIDITY_MARGINAL` = 90% on a snowy row — the briefing's own constant, reused, not a new definition), the topic upgrades to the `SNOW_MIST` variant (priority 1 vs 2, mist-aware label/description) rather than a separate detector. `findSnowFreshDays` returns humidity alongside the rows so this needs no second query.
- **SNOW_TOPS.** Fires when `freezing_level_m ≤ elevation_m − 100` — the 100 m margin gives confidence the fell tops are genuinely white. No minimum-elevation floor: the freezing-level-versus-elevation comparison self-selects high ground (a low fell only fires when the freezing level drops far enough). Reads `forecast_evaluation` joined to `location.elevation_m` (V65/V86 populated), priority 3.
- **No frontend change.** `HotTopicStrip.jsx` already maps `SNOW_FRESH`/`SNOW_MIST`/`SNOW_TOPS` (icon + colour, with tests) from when the sim catalogue templates were added — the real detectors' topics render identically.
- **Tests.** Boundary unit tests per detector (depth at/just-below 0.02; humidity at/just-above 90; freezing level at/just-above the 100 m margin; low-fell-deep-freeze; null-safety) and JPQL slice tests against H2 in `ForecastEvaluationHotTopicQueriesTest` (depth threshold + humidity passthrough; freezing-level margin self-selecting elevation, excluding null-elevation rows). Both detectors added to `HotTopicStrategyRegistrationTest`. Full backend `verify` green (4084 run, 0 failures; the 5 Docker-only Testcontainers integration tests are environmental), Checkstyle + SpotBugs clean.
- **Seasonality.** It is June — these won't fire live until a real winter event (same posture as NLC/meteor, built ahead of season). Build-time proof is the boundary tests + the admin sim render; live firing confirmation is months out (green at build time ≠ seen firing live).

### Added — Snow data ingestion (snow trio Phase 1, data plumbing)
- **Why.** The snow trio hot topics (`SNOW_FRESH`, `SNOW_TOPS`, `SNOW_MIST`) need three Open-Meteo forecast variables that nothing fetched before: `snowfall`, `snow_depth`, `freezing_level_height`. This phase plumbs them through the live weather fetch and persists them; the detectors (Phase 2) read them. Grounded in `docs/engineering/snow-trio-investigation.md`.
- **Single-point request change.** Appended `snowfall,snow_depth,freezing_level_height` to `OpenMeteoClient.FORECAST_PARAMS` — the one constant read by the single fetch, the standard batch, and the resilient chunked briefing path alike, so the new fields ride the existing per-chunk retry/backoff isolation structurally (no separate request, no way to bypass the resilience). `CLOUD_ONLY_PARAMS` and the air-quality path untouched.
- **Parse + sample.** Three nullable `List<Double>` fields added to `OpenMeteoForecastResponse.Hourly`; sampled at the hour nearest the solar event in `OpenMeteoService.extractAtmosphericData` (the existing single-hour `findBestIndex` path, null-safe via `getDoubleValue`). Carried on `WeatherData` (`snowfallCm`, `snowDepthMetres`, `freezingLevelMetres`) — kept out of the Claude prompt (`PromptBuilder` simply doesn't read them).
- **Persistence.** Three nullable columns on `forecast_evaluation` (`snowfall_cm`, `snow_depth_m`, `freezing_level_m`, `DOUBLE PRECISION`, migration **V113**), written in `ForecastService` alongside the inversion/surge values.
- **Empirical acceptance (verifiable now, in June).** Live `/v1/forecast` confirmed to expose all three (units: snowfall cm, snow_depth m, freezing_level m); `freezing_level_height` reads a plausible non-zero value year-round (~3300 m over the Lake District in June, 24/24 non-null) — proving fetch+parse+persist works independent of snow season — while `snowfall`/`snow_depth` correctly read 0 out of season. Worst-case 20-coord chunk URL measured at 958 chars (vs ~8190 limit; snow added 42), so `BATCH_COORD_LIMIT = 20` is unchanged and there is no HTTP 414 risk. No regression to existing weather columns or chunk resilience. New extraction unit tests (snow sampled at the selected slot; null when Open-Meteo omits the arrays).

### Security — Frontend dependency audit fix (undici)
- `npm audit fix` for a newly-disclosed high advisory in `undici` 7.0.0–7.27.2 (transitive via `jsdom`, a devDependency / test env only — not shipped to production): GHSA-vmh5-mc38-953g (TLS certificate validation bypass) and GHSA-pr7r-676h-xcf6 (cross-user info disclosure via shared cache). `undici` 7.25.0 → 7.28.0. Lockfile-only; no `package.json` or source changes. `npm audit --audit-level=high` now reports 0 vulnerabilities (the frontend CI audit gate was failing on this).

### Changed — Briefing labelling honesty: "3 of 54 evaluated", not implied 54
- **Why.** Per `docs/engineering/drilldown-reconciliation-investigation.md`, the Planner drill-down lists a region's full weather-triaged roster but only a sparse Claude-scored subset carries stars (e.g. 3 of 54 at T+2 — intended cost-bounded scoring, not a bug). The UI conflated the two: unscored-but-clear slots wore the identical green "Worth it" pill as Claude-rated slots, and a region header fused a 54-wide weather count ("Clear at 54 of 54") with a 3-wide Claude average ("ratings average 4.0"), reading as "54 locations averaged 4.0★". Every number was individually honest; the framing overclaimed. This pass makes the framing honest — it does **not** change which locations get scored (selection/`force-eval-cap` untouched).
- **Ratio-aware honesty filter (keystone).** `BriefingHonestyFilter` already suppressed regions with **zero** Claude coverage; it now also recognises a lighter middle tier — a region whose scored fraction (`scoredLocationCount / roster size`) is positive but below `photocast.briefing.min-coverage-ratio` (default **0.5**, tunable without redeploy) is flagged `lightlyEvaluated` on the API read path. Nothing is suppressed in this tier (real slots, gloss and scores are preserved); the zero-coverage full rewrite is unchanged. New `lightlyEvaluated` field on `BriefingRegion`; the filter gained a 2-arg `apply(response, minCoverageRatio)` with the prior 1-arg form retained (ratio 0.0 → tier disabled).
- **Header scope-marking (frontend).** When a region is lightly evaluated, the drill-down header now renders a neutral "· N of M evaluated" note beside the weather summary in both `HeatmapGrid.jsx` (desktop) and `DailyBriefing.jsx` (mobile), so the weather count no longer reads as the evaluated count. Well-covered regions are unchanged.
- **Distinct unscored pill (frontend).** Unscored-but-clear slots now render an outline/ghost "Clear · not scored" (or "Maybe · not scored") pill instead of the solid green "Worth it" used for Claude-rated slots — the eye can tell "Claude rated this" from "the sky is clear here". Claude-scored slots keep their star badge / solid pill.
- **Gloss hedging (Claude prompt).** `BriefingGlossService` now feeds the region gloss prompt a `claudeCoverageRatio` + `lowCoverage` signal (alongside the existing `claudeAverageRating`/`totalLocations`). The system prompt steers the gloss to attribute a high average to "the evaluated spots" rather than the whole region when coverage is low, framed as routine far-horizon scoping — never as a warning (at 0.5 this is the common far-out state).
- **Tests.** Ratio-tier honesty-filter tests (3/54 flagged, 40/54 unchanged, boundary at the threshold, zero-coverage still fully rewritten, single-arg overload disables the tier); gloss input-contract tests (`lowCoverage`/`claudeCoverageRatio` emitted on low coverage, absent/false on high); frontend tests for the coverage note and ghost pill in both the desktop and mobile drill-downs, plus a fully-covered regression. All backend (4063, excluding 5 Docker-only Testcontainers integration tests) and frontend (1571) tests green.

### Changed — ESLint 10 upgrade (frontend)
- **Why.** Frontend CI (`npm ci`) was failing with an `ERESOLVE` conflict: `eslint` had been bumped to 10.x but `eslint-plugin-jsx-a11y@6.10.2` (and `eslint-plugin-react@7.37.5`) still cap their `eslint` peer at `^9`. Neither plugin has published an eslint-10-aware release yet, but both run fine against eslint 10's flat config at runtime.
- **Dependencies.** `eslint` `^9.19.0` → `^10.5.0`, `@eslint/js` `^9.19.0` → `^10.0.1`, `eslint-plugin-react-hooks` `^7.0.1` → `^7.1.1` (the first release with a native eslint-10 peer). Added a `package.json` `overrides` block pinning the `jsx-a11y` and `react` plugins' `eslint` peer to the root `$eslint` so resolution succeeds without `--legacy-peer-deps`. Removed `react: { version: 'detect' }` from `eslint.config.js` (the detect path calls `context.getFilename()`, removed in eslint 10) in favour of an explicit `'19.2'`.
- **New lint rules adopted.** `eslint-plugin-react-hooks@7.1.1` ships the React Compiler rule set (`set-state-in-effect`, `purity`, `immutability`, …) in its recommended preset. Fixed all 27 newly-surfaced errors across 18 files — no `eslint-disable` suppressions: `set-state-in-effect` resolved by invoking the offending effect call inside an inline async function (the synchronous body still runs in the same tick, so behaviour is unchanged); `ModelSelectionView` `fetchModels` moved inside its effect (resolves a use-before-declared error); `UserSettingsModal` now reads the current time from an effect-driven `now` state instead of calling `Date.now()` during render (purity), which also makes the "X min ago" label self-refresh. All 1565 frontend tests green; `npm ci`, `npm run lint`, `npm audit`, and `npm run build` all pass.

### Added — Hot topic detectors: forecast-data readers (Group B)
- Three new real `HotTopicStrategy` detectors that read already-persisted `forecast_evaluation` columns directly (the king/spring-tide template — no new data plumbing, no external API calls): **Cloud inversion** (fires on `inversion_potential = STRONG` only, not moderate — column is populated only for elevated/overlooks-water locations), **Storm surge** (fires on `surge_risk_level = HIGH` only, not moderate — column populated only for coastal locations; confirms the stale "surge not cached" note is resolved), **Saharan dust** (fires on the existing dust badge proxy exactly: AOD > 0.3 or surface dust > 50 µg/m³, with PM2.5 < 35 or absent to rule out smoke/haze).
- Backed by three projection queries on `ForecastEvaluationRepository` returning `[date, region]` for the window; each detector dates its pill to the earliest qualifying day and lists the distinct affected regions. A static `isDustEnhanced` predicate mirrors the badge and is boundary-tested to lock consistency with the frontend `MarkerPopupContent` proxy.
- Priorities sit in the act-on-it band above the calendar heads-ups: storm surge 1, inversion 2, dust 3. Auto-collected into `HotTopicAggregator`; inherit the briefing path, 4-day window and PRO/ADMIN gating. Frontend `HotTopicStrip` already maps the type keys.

### Added — Hot topic detectors: deterministic calendar/ephemeris (Group A)
- Four new real `HotTopicStrategy` detectors that fire on live briefing cycles (previously sim-only templates): **Supermoon** (full moon within ±3 days of perigee), **Equinox alignment** (within ±3 days of an equinox with sunrise/sunset azimuth within ±3° of due east/west), **Noctilucent cloud season** (fixed late-May–early-Aug window), **Meteor shower** (fixed peak calendar — Quadrantids/Lyrids/Perseids/Orionids/Geminids — gated on <50% lunar illumination so a washed-out peak is skipped).
- All deterministic: read `LunarPhaseService` / `SolarService` / location regions only — no DB rows, no external API calls. `LunarPhaseService` gained two primitives: `daysFromNearestPerigee` (wider window than the ±0.5d `isMoonAtPerigee`, which now delegates to it) and `getIlluminationFraction` (0–1, for the meteor dark-moon gate).
- **NLC note.** The season is a private `SeasonalWindow` constant inside the detector, not a new Spring bean: the bluebell window is the sole `SeasonalWindow` bean and is injected by type at several sites, so a second bean would make those injections ambiguous at startup. Same calendar-gate behaviour, no bean conflict.
- Priorities slot below the act-on-it topics (calendar heads-ups): supermoon 5, equinox 6, meteor 7, NLC 8. Auto-collected into `HotTopicAggregator` by component scan; inherit the briefing path, 4-day window and PRO/ADMIN gating with no extra wiring. Frontend `HotTopicStrip` already maps all four type keys.

### Security — Frontend dependency audit fix
- `npm audit fix` for two newly-disclosed transitive advisories flagged by the frontend CI audit gate: `form-data` 4.0.5 → 4.0.6 (high — CRLF injection, GHSA-hmw2-7cc7-3qxx, transitive via axios) and `js-yaml` 4.1.1 → 4.2.0 (moderate — quadratic-complexity DoS, GHSA-h67p-54hq-rp68). Lockfile-only; no `package.json` or source changes. `npm audit` now reports 0 vulnerabilities.

### Added — Admin entry point for the advisor replay harness
- **Why.** The `replayWithPrompt` primitive (advisor-contract pass, commit 1) existed but was only reachable from tests. This exposes it as an admin-gated endpoint so the pick-selection before/after can actually be run against captured or synthetic rollups with a live key — the standing validation tool for all future advisor prompt work (the gap that let Change 4 ship unvalidated).
- **Endpoint.** `POST /api/admin/advisor-replay` (`@PreAuthorize("hasRole('ADMIN')")`). One rollup, up to two prompts, per request: takes a rollup either by reference to a captured advisor call (`apiCallLogId` → `api_call_log.request_body`) or supplied directly (`rollupJson`, for synthetic cases like an all-STANDDOWN flat week), plus an optional `candidatePrompt` to diff against the current live `SYSTEM_PROMPT`. Returns **both parsed pick-sets side by side** (`AdvisorReplayResponse`) for a by-eye diff — no automated comparison, no persistence, no UI, no batch runner.
- **Thin by design.** Reuses `replayWithPrompt` exactly as the tests do. Two production edits to the advisor primitive, both minimal exposure: `replayWithPrompt` made `public`, and a new `currentSystemPrompt()` getter so the controller can run the "before" side. No new model-call logic.
- **Notes.** Captured-rollup replay only works for cycles logged after commit 1's capture shipped (older `api_call_log` rows have a null `request_body` → clear 400, not an NPE); the supplied-rollup path works immediately. Each call hits the live model (two calls for a two-prompt before/after) — admin-triggered and occasional, but real spend. Tests: admin two-prompt returns both sets, single-prompt returns current only, captured-by-id resolves from `request_body`, pre-capture null body → 400, unknown id → 404, neither source → 400, non-admin → 403.

### Changed — Tide scarcity preference (advisor-contract pass, commit 4/4)
- **Why.** Perishable opportunities (a king or spring tide aligned with a good slot) pass in a day or two, but the advisor weighed them no differently from an equal everyday slot. Step 0 confirmed the signal is already in the rollup (the lunar tide counts) — no new plumbing needed.
- **Rollup annotation (model-only).** `appendRegionNode` now adds a `scarcity` field to a region — `KING_TIDE` (rarest) or `SPRING_TIDE` (perishable), derived from the in-window lunar tide counts; absent when neither. Model-only by design: it is **not** added to `CandidateCoverage` / the deterministic `applyCoverageAwareRanking` gate, so scarcity is a soft within-band preference that can never override the quality floors.
- **Prompt rule.** A new SCARCITY block instructs: when two candidates are comparable in quality — within ~half a star AND both clearing the ≥3.5 bar — prefer the scarcer one; scarcity is a tiebreak among GOOD options, NEVER a substitute for quality (a sub-3.5 scarce candidate does not beat a solid ordinary one, and it never overrides the headline coverage rules). The "Also Good" pick is named as the natural home for a strong-but-not-best scarce window. The 3.5 tier floors, best-bet coverage gate, and the Change-4 anti-verbosity block are unchanged.
- **Deferred (not this pass):** the "next spring/king tide in N days" forward scan (needs past-the-horizon logic) and bluebell scarcity (needs a new injected dependency; out of season until April). Tests: king/spring/regular annotation, king-precedes-spring within a region, prompt carries the within-band rule. Selection-level before/after is validated via the replay harness + live observation (this commit's automated tests protect the input annotation and the prompt clause). 139 advisor tests green.

### Changed — Aurora region from cached scores (advisor-contract pass, commit 3/4)
- **Why.** Aurora events reached the best-bet advisor with no region at all, so the model improvised one ("Northumberland is typically the premier dark-sky region") — and validation *skipped* aurora region checks, letting the invented region through unchecked. Step 0 found the cleanest fix already in hand: every cached `AuroraForecastScore` carries its full `LocationEntity`, so the scored dark-sky locations already know their region.
- **Region assignment (option c).** `appendAuroraEvent` now derives the aurora event's region from `AuroraStateCache.getCachedScores()`, grouping the scored locations by region and ranking by clear-location count, then mean star rating, then darkness (`bortleClass`). The best region is emitted as the aurora event's `region` field and added to `validRegions` for the night. **No config-default fallback:** when no cached score carries a region the field is omitted and the model is told to use region-agnostic phrasing ("dark-sky sites across the region are well placed") — inventing a default would re-introduce improvisation in disguise.
- **Validation tightened.** `isPickValid` no longer skips the region check for `_aurora` events: a non-null aurora region must now be in `validRegions` (so the data-derived region passes and a hallucinated one is rejected), while a null region still passes (the region-agnostic case). The headline coverage-gate exemption for aurora is unchanged.
- **Prompt.** The aurora guidance now tells the model to use the event's `region` field when present and region-agnostic phrasing when absent, never to invent a region; the hardcoded "Northumberland" exemplar is softened to "the darkest-sky sites". The Change-4 anti-verbosity block is untouched.
- **Separability.** Writes nothing to `forecast_score`; touches only the live rollup JSON + validation — independent of the future aurora-as-forecast-type refactor. Tests: region derived by clear-count, tie-broken by stars then darkness; absent on empty cache; scores without a region ignored; aurora pick with a valid/invalid/null region (tightened validation). 134 advisor tests green.

### Changed — Stay-home floor disambiguation (advisor-contract pass, commit 2/4)
- **Why.** Step 0 found the advisor can "say nothing" two ways: an empty `picks: []` array (classified `SUCCESS_NO_PICKS`) or a single null-event/null-region stay-home pick (`SUCCESS_WITH_PICKS`). The contract wants the stay-home pick on a barren all-STANDDOWN week — a deliberate "stay home today" recommendation — but the prompt didn't forbid the empty array, leaving a semantic fork. The truncation hotfix removed the *budget* cause of the floor going missing; this closes the remaining *instruction* ambiguity.
- **One-line clarification** appended to the existing floor instruction in `BriefingBestBetAdvisor`'s system prompt: the stay-home null/null pick is MANDATORY on a flat week and an empty `picks` array must not be used to signal a barren forecast. The floor instruction itself and the adjacent Change-4 anti-verbosity block are untouched (append-only, to avoid re-introducing the rambling Change 4 curbed). No code path changed — the empty-array → `SUCCESS_NO_PICKS` handling remains.
- **Guard tests** (the plumbing the truncation fix restored, previously untested): a flat all-STANDDOWN rollup with a null/null pick survives as `SUCCESS_WITH_PICKS` (via the replay harness); `validateAndFilterPicks` keeps a lone stay-home pick; the system prompt carries the new mandatory-stay-home / no-empty-array clause.

### Added — Advisor replay harness (advisor-contract pass, commit 1/4)
- **Why.** The best-bet advisor's three banked judgement changes (stay-home floor, aurora region, tide scarcity) are changes to *what the advisor recommends*, so they must be validated at pick-selection level — before/after — not by test-pass alone. Change 4 shipped without this and had to be confirmed from prod after the fact; this builds the validation capability first so the remaining commits can prove their effect synthetically.
- **Capture.** `BriefingBestBetAdvisor.advise` now passes the rollup JSON as the `requestBody` to `JobRunService.logApiCall` (previously `null`), so every live cycle's exact advisor input lands in `api_call_log.request_body` and is replayable.
- **Prompt-swap replay.** New package-private `replayWithPrompt(rollupJson, systemPrompt, model)` runs the advisor against a captured or synthetic rollup with an explicitly supplied system prompt, bypassing `buildRollupJson` (which needs live `BriefingDay` objects a historical replay lacks). It reconstructs the validation sets (`validEvents`/`validRegions`/`validDayNames`/coverage) from the rollup JSON — a faithful inverse of what `buildRollupJson` emits — so replayed picks pass through the same `validateAndFilterPicks` + `applyCoverageAwareRanking` gates production uses; display enrichment is skipped (it changes neither selection nor ranking). Returns the classified `BestBetResult`.
- **No behaviour change** beyond the capture: `advise`/`callModel` share a new `extractFirstText` helper (pure refactor). Tests: replay returns validated picks, swaps the system prompt and sends the rollup verbatim, drops picks naming a region absent from the reconstructed rollup, passes an honest empty array through as `SUCCESS_NO_PICKS`; capture writes the rollup to the `api_call_log` request body on the real `advise` path.

### Changed — Rebrand model self-references to "PhotoCast" in briefing narratives
- **Why.** The best-bet ("PhotoCast Planner") cards and per-region glosses surfaced phrases like "All ten locations Claude-rated excellent" — the model echoing "Claude" from the `claude*`-prefixed evaluation-score fields it reads in its input. User-facing copy should name the product, not the underlying model.
- **Prompt-first fix.** Rebranded the prose in `BriefingBestBetAdvisor` and `BriefingGlossService` system prompts (section headers and descriptions now say "PhotoCast", e.g. "how many locations were fully evaluated") and added an explicit BRANDING rule instructing the model never to write "Claude"/"Anthropic" in the headline or detail. The JSON data keys (`claudeRatedCount` etc.) are unchanged — they are internal and depended on by the DB schema (V104), entity, frontend, and tests; only the human-readable vocabulary moved.
- **Regex backstop.** New `PromptUtils.sanitizeBrand` replaces whole-word `Claude`/`Anthropic` (case-insensitive, hyphen/possessive-preserving) with `PhotoCast`. Applied to headline/detail at parse time in both services as a defensive net, since LLM output is never guaranteed. Tested in `PromptUtilsTest`; existing prompt-content assertions updated to the new headers.

### Added — Bluebell extraction (Pass 3, commit 2/5: BluebellVisitor + exposure rating role)
- **Why.** With bluebell extracted into its own prompt (commit 1), the combiner needs a third visitor to fold the bluebell score into the rating — and to do so differently by exposure (OQ3), because woodland and open-fell bluebells photograph under opposite ideal weather.
- **`BluebellVisitor`** (`type() == BLUEBELL`): applies to bluebell sites (a `BLUEBELL` location type with an exposure set) and produces a 1-5 score + the prompt's prose from a `BluebellEvaluation` on `VisitorContext`. The season gate is the same data-gap abstain `TideVisitor` uses — out of season the orchestration runs no bluebell prompt, the slice is null, and the visitor abstains (never a penalty). The summary re-exposes the bluebell prose so the persisted BLUEBELL component row carries its own narrative (consistent with Pass 2).
- **Exposure rating role in `RatingCombiner`.** All applied components are still RECORDED (the dual-write is unchanged); only *which* components feed the headline rating changed: **WOODLAND** in season → the bluebell score IS the rating, the sky is not a peer (perfect woodland bluebell light is calm bright overcast — ~2-3 as a sky — so averaging would cap woodland sites at ~4 on their best mornings); **OPEN_FELL** → bluebell is a peer averaged with the sky (golden light flatters fell and flowers alike). Everything else — inland sky-only, coastal sky+tide — is the unchanged pre-Pass-3 path, so **no existing rating moves** (cache-payload golden master byte-identical).
- **`VisitorContext`** gains a nullable `bluebell` slice (a 2-arg convenience constructor keeps every pre-Pass-3 caller unchanged), and the sky `evaluation` is now nullable: an in-season WOODLAND bluebell site is scored by the bluebell prompt ALONE (no sky call), so `SkyVisitor` abstains on a null evaluation rather than NPE-ing.
- **Note.** This commit is the visitor/combiner layer, unit-tested in isolation; the BLUEBELL component row flows through Pass 2's type-agnostic dual-write automatically once the orchestration populates the bluebell slice (commit 3). Tests: `BluebellVisitorTest`, rating-role cases in `RatingCombinerTest` (woodland == bluebell; woodland bluebell-only; open-fell == round(avg), half-up boundary; out-of-season == sky), `SkyVisitor` null-evaluation abstain.

### Changed — Bluebell extraction (Pass 3, commit 1/5: dedicated prompt + contract change)
- **Why.** The standard colour prompt moonlighted as the bluebell evaluator — it carried a `BLUEBELL CONDITIONS` block and a rating-**boost** rule (bluebell 8-10 → rating +1) that coupled two unrelated judgements into one score. Pass 3 of the forecast-score re-architecture extracts bluebell into its own prompt and (next commit) a `BluebellVisitor`, so the standard rating becomes purely sky. This commit is the prompt-contract change, golden-mastered.
- **New `BluebellPromptBuilder`** — a standalone Claude prompt (not a decorator over the sky builder): exposure-differentiated rubric (WOODLAND: light-primary, wind heavily discounted under canopy, mist+low-sun = the 5-star case; OPEN_FELL: wind at face value, golden-hour light, sky-behind matters), returning a 1-5 bluebell rating + one-sentence summary + optional headline. The deterministic `BluebellConditionService` is demoted to a subject-quality INPUT block feeding the prompt.
- **Phenology honesty.** Nothing — not Claude, not Open-Meteo — knows whether the bluebells are actually in bloom; the season window is a proxy. The prompt is explicit that it scores *conditions given assumed bloom* ("if they're out, how good is this morning"), must not imply bloom confirmation, and its summary hedges ("ideal light if they're in flower") rather than asserting. A future bloom signal can be fed in without restructuring the contract. **Known limitation:** an in-season confident score over a not-yet-bloomed or gone-over wood is expected behaviour, not a regression.
- **Standard prompt stripped** of the bluebell system block, the bluebell rating-boost rule, the `bluebell_score`/`bluebell_summary` output-schema fields, and the parser's bluebell extraction (both JSON and regex paths) — the standard `SunsetEvaluation` now carries null bluebell fields until the legacy columns drop (commit 5). The **adjacent inversion boost rule is deliberately preserved** (the easy over-deletion). New `parseBluebellEvaluation` parses the bluebell response (strict JSON + bounded/salvage regex fallback) into a small `BluebellEvaluation` record.
- **Golden-master review.** The `woodland-bluebell` prompt fixture diff shows the bluebell block + boost leaving the standard prompt (inversion intact); new `bluebell-woodland`/`bluebell-openfell` fixtures show the dedicated prompt arriving — together the reviewable contract change. New `BluebellPromptGoldenMasterTest`, `BluebellPromptBuilderTest`, parser tests, and `PromptBuilderTest` extraction guards; the old standard-prompt bluebell tests removed. 4988 backend tests green (1 pre-existing unrelated `LocationFailureTrackingUIDemo` environmental failure).

### Added — Best-bet status contract (commit 3/3: freshness-bounded fail-safe fallback)
- **Why.** With the status contract in place, a `FAILED` advisor outcome can now be protected: rather than showing a blank "No standout recommendations" on what may have been a strong day, the API serves the last successful run's picks, labelled stale. This is cause-agnostic — it protects the user view for any future advisor failure, not just the known truncation bug.
- **The fallback.** New `BestBetFallbackService` reads `pipeline_run_pick` for the most recent successful run's picks (pick rows exist only for `SUCCESS_WITH_PICKS`, so presence implies success — no status join). It is **freshness-bounded**: a pick whose event has already passed is excluded (day-granular — the row persists `event_date` but not the event's time of day, a deliberate limitation), and a pick older than `photocast.best-bet.fallback-max-age-hours` (default 30h ≈ one nightly+intraday) is excluded. If nothing qualifies, the API falls through to the honest empty state — better empty than a stale or expired pick.
- **Applied at serve time.** `getCachedBriefingForApi` decorates the response only when `bestBetStatus == FAILED`: freshness is re-checked on every request (never baked into the persisted cache), and the status stays `FAILED` so the frontend renders the picks with the stale chip. `SUCCESS_NO_PICKS` never consults the fallback — honest-empty stays empty, so the fix can't manufacture picks on a flat day.
- **Proof.** `PipelineRunPickRepositoryTest` (`@DataJpaTest`) proves the query's event-passed and age-ceiling exclusions and the newest-first ordering; `BestBetFallbackServiceTest` proves latest-run grouping, entity→`BestBet` mapping, and that the correct freshness bounds reach the query; `BriefingServiceTest` proves serve-time substitution on `FAILED`+fresh, empty-preserved on `FAILED`+none, and that `SUCCESS_NO_PICKS` never consults the fallback. All touched backend suites green.

### Added — Best-bet status contract (commit 2/3: frontend switches on the status)
- **Why.** With the backend now asserting `bestBetStatus`, the Planner stops inferring failure-vs-honest from an empty array. The empty-state copy ("No standout recommendations…") is reserved for genuine no-picks; a failure shows the fallback picks (commit 3) flagged stale instead of a misleading blank.
- **The switch.** `DailyBriefing` keys off `briefing.bestBetStatus`: picks present → `BestBetBanner` (a `FAILED` status means commit 3 served a fallback, so the banner shows a stale chip — "From an earlier forecast — today's update didn't complete…"); no picks → the honest empty state (`SUCCESS_NO_PICKS`, or `FAILED` with no fresh-enough fallback). A missing/legacy status falls back to the previous infer-from-length behaviour, so old cached payloads render unchanged.
- **Proof.** New `DailyBriefing` tests: `FAILED` + fallback picks → banner with `best-bet-stale` chip; `SUCCESS_WITH_PICKS` → banner, no chip; `FAILED` + no picks → honest empty, no chip; `SUCCESS_NO_PICKS` → honest empty. 116 frontend tests green.

### Added — Best-bet outcome status contract (commit 1/3: backend truth)
- **Why.** An empty Best Bet had two opposite meanings the API could not express: the advisor ran and honestly found nothing (a flat week — show the empty state) versus the advisor failed and lost a good pick (fall back to the last good pick). They look identical as an empty array, which is exactly what let the truncation bug masquerade as honest-decline for weeks. The fix: the advisor layer — the only layer that knows which happened — returns an explicit status, and downstream layers switch on it.
- **The contract.** New `BestBetStatus { SUCCESS_WITH_PICKS, SUCCESS_NO_PICKS, FAILED }` and `BestBetResult(status, picks)`. `BriefingBestBetAdvisor.advise()` now returns `BestBetResult`: parsed/salvaged picks → `SUCCESS_WITH_PICKS`; a clean `{"picks":[]}` → `SUCCESS_NO_PICKS`; an exception, unparseable-with-nothing-salvageable, or every pick rejected by validation → `FAILED`. The status REPORTS which existing path was taken — selection, ranking, salvage, and coverage gates are untouched. Salvaging ≥1 valid pick from a truncated response is `SUCCESS_WITH_PICKS`, not `FAILED`.
- **Serving + persistence.** `DailyBriefingResponse` carries `bestBetStatus` (with a backward-compatible constructor so legacy/persisted payloads default to null). `pipeline_run` gains a `best_bet_status` column (V110); the orchestrator records the outcome per cycle and persists pick *rows* only for `SUCCESS_WITH_PICKS` — so absence of rows now unambiguously means "no successful picks" (the fallback in commit 3 relies on this), and the cross-run comparison can finally tell "both declined" from "no baseline".
- **Proof.** Status-mapping tests on `classifyAndParse` (picks/empty/unparseable/blank/missing-key/salvaged-1-of-2) and on `advise()` (all-invalid → FAILED, exception → FAILED); orchestrator tests for status recording + pick-row gating across SUCCESS_WITH_PICKS / SUCCESS_NO_PICKS / FAILED / stale; `recordBestBetStatus` service test. 224 backend tests across the touched suites green; frontend switch + serve-time fallback land in commits 2 and 3.

### Fixed — Best-bet advisor truncation: valid picks now reach the table (service restore)
- **Why.** A COMPLETED nightly cycle (pipeline_run #32, a strong week) persisted **zero** picks. Forensics: the advisor's `api_call_log` response was a sound pick set (rank 1 The North York Moors sunset 4.0, rank 2 The North Yorkshire Coast same-slot spring tide) but the stored body was 3656 chars and ended mid-field at `"differsBy` — the model hit its 1024-token ceiling while reasoning verbosely in the output channel, so the JSON never closed. The atomic parse discarded everything, the Planner showed "No standout recommendations" on a genuinely good day, and because empty picks are indistinguishable from an honest decline, the drop hid for weeks. This restores the **mechanism** (valid picks survive) and adds **visibility** (a recurrence is a glance, not a forensic dig). The advisor's judgement — what it picks and how it frames it — is deliberately unchanged.
- **(1) max-tokens raised and made configurable.** `BriefingBestBetAdvisor`'s standard-call ceiling goes from a hardcoded 1024 to `photocast.best-bet.max-tokens` (default 4096) — ~4× headroom over the 3656-char response that didn't even finish, tunable without a redeploy. Extended-thinking calls keep their 16000 budget.
- **(2) Partial-pick salvage.** The parse was all-or-nothing: a complete rank-1 was thrown away because rank-2 was truncated. `parseBestBets` now falls back to walking the `picks` array element by element (`PromptUtils.balancedObjectAt`, which returns null on a never-closing object rather than the original string), keeping every structurally-complete leading pick and stopping at the first truncated one. Salvaged picks are NOT exempt from validation — they still pass through `validateAndFilterPicks` (valid event/region, within window), so a recovered-but-invalid pick is still rejected.
- **(3) Truncation observability.** The SDK exposes `Message.stopReason()`; the advisor now reads it and emits one classifiable disposition log: `[BEST-BET TRUNCATION]` at WARN when the response stopped on `max_tokens` (with the `jobRunId` that correlates to the `api_call_log` row, the response length, and the configured ceiling — and whether any picks were salvaged), versus an INFO "honest decline" when the model legitimately returned no picks. A truncation can no longer masquerade as an honest empty result.
- **Proof.** New `BriefingBestBetAdvisorTest` cases: the real #32 shape (truncated rank-2) salvages exactly rank-1; a valid two-pick response still parses both (no regression); honest `{"picks":[]}` yields zero with an INFO honest-decline and no truncation WARN; a token-limited `advise()` logs the `[BEST-BET TRUNCATION]` WARN with `jobRunId` and salvages; a salvaged pick with an invalid region is still validation-rejected; and a distinct injected `max-tokens` reaches the request (proving it is config-driven, not hardcoded). `PromptUtilsTest` covers `balancedObjectAt` (truncated → null, nested/string-brace/escape handling). All 152 advisor+util tests green.
- **Scope.** Selection/ranking logic, coverage gates, and the briefing pipeline ordering are untouched. The advisor-contract judgement items (stay-home floor, scarcity-aware ranking, aurora-region contract) are explicitly deferred to a later pass where they can be evaluated at verdict-band level.
- **Empirical acceptance (pending real cycle).** Next non-flat nightly should persist rows to `pipeline_run_pick` and show a Best Bet in the Planner; a re-run against #32's input should close its JSON with `stopReason=end_turn`; a flat day still correctly shows empty and logs honest-decline.

### Changed — Best-bet advisor: reason concisely, then emit only the JSON (output-verbosity bound)
- **Why.** #32's truncation was driven by the model spending its output budget on prose — pages of "key observations", repeated same-slot analysis, and back-and-forth "actually, wait…" deliberation — before it finished the JSON. The advisor's deliberation is load-bearing for *which* picks it makes, so this bounds the **verbosity**, not the reasoning: the closing instruction now reads "reason concisely, then output ONLY the JSON object," with explicit examples of the verbose patterns to avoid and a note that they have truncated the response. The four-line "begin with { / end with }" hard constraint is replaced by this concise-reasoning framing.
- **Judgement-risk gate (NOT YET RUN).** This is the one change in the hotfix that can shift *what the advisor picks*. The runbook mandates a before/after pick-comparison on recent cycles' inputs, which cannot be run from the dev machine (no prod rollup inputs; requires a live Claude key). It is committed separately so it can be reverted independently. **Do not deploy this commit until the before/after has been run and confirms pick SELECTION is unchanged (only brevity differs).** If selection shifts materially, revert this commit — Changes 1–3 alone fully restore service.
- **Proof (so far).** All 111 advisor tests green; system-prompt substring assertions (FORECAST RELIABILITY, ALSO GOOD SELECTION RULE, tiers, differsBy) intact. The empirical pick-equivalence check remains outstanding.

### Added — Forecast-score re-architecture Pass 2, commit 2: dual-write component scores to `forecast_score`
- **What.** Each scored forecast evaluation now ALSO persists its component scores to the `forecast_score` table (V108), alongside — never instead of — the live `results_json` serving path. Per scored (location, date, SUNRISE/SUNSET): a `SKY` row (pre-combine sky score + the response prose), `FIERY_SKY` and `GOLDEN_HOUR` rows (the 0–100 potentials), and — for coastal locations whose tide visitor applied and did not abstain — a `TIDAL` row carrying its 1–5 score and a deterministic state clause (king/spring/aligned/widened/misaligned). The tide gets a narrative channel again, component by component, for Pass 4's read-side composition. **Nothing reads `forecast_score` yet.**
- **Where.** `ForecastResultHandler.buildResult` — the single seam both transports (scheduled batch + admin sync) flow through, so both dual-write. New `ForecastScoreWriter` does the UPSERT against the component unique key `(forecast_type_id, location_id, evaluation_date, event_type)` — latest evaluation wins, so intraday re-runs and sync re-evaluations overwrite the same key. `pipeline_run_id` is threaded from the orchestrated path (via `ResultContext`) as provenance; the sync path writes NULL.
- **Serving path untouched.** No change to `results_json`, cache keys, persistence, prompts, schema, or the parser — guarded by the `CachePayloadGoldenMasterTest` byte-for-byte golden master. The sky-not-forecast branch (Claude omitted the rating) writes NO `forecast_score` rows: the combiner never runs, so there is no genuine component to record.
- **Failure-isolated.** The dual-write runs in its own `REQUIRES_NEW` transaction and is wrapped so any failure logs loudly at ERROR with the component key and the evaluation proceeds — `forecast_score` is the record being proven, the serving payload is the live product. Feature flag `photocast.forecast-score.dual-write` (default true) is the whole-pass rollback, no redeploy.
- **Proof.** `ForecastScoreWriterTest` (row set, scores/summaries, upsert-in-place, HOURLY exclusion, flag-off no-op, provenance threading); a failure-isolation test on the handler (evaluation still succeeds, ERROR logged); and `ForecastScoreDualWriteIntegrationTest` — a testcontainers Postgres real-path test (parse → combine → buildResult → rows land) covering the unique-key upsert, coastal TIDAL clause, and NULL vs populated provenance. The rerunnable reconciliation queries live in `docs/engineering/forecast-score-reconciliation.md` — the empirical acceptance and the proof Pass 4's read migration will reuse.

### Changed — Forecast-score re-architecture Pass 2, commit 1: expose per-visitor component scores from the combiner
- **Why.** Pass 0 flagged this as the riskiest line of Pass 2: `RatingCombiner.combine()` computed each visitor's score internally and returned only the averaged rating — the per-visitor SKY and TIDAL scores were discarded. The Pass 2 dual-write (commit 2) needs them, so this commit surfaces them while provably not moving any persisted rating.
- **The change.** `combine()` now returns a small `CombinedRating(rating, components)` record: the headline rating is computed exactly as before (half-up mean of applied visitors that returned a value), plus an additive `List<ComponentScore>` of `(type, score, summary)`. `Visitor` gains `type()` (SkyVisitor → `SKY`, TideVisitor → `TIDAL`) and a default `summary()`; `SkyVisitor` re-exposes Claude's prose, and `TideVisitor` authors a deterministic one-line clause per alignment state (king/spring/aligned/widened/misaligned) from a single `classify()` band shared by score and clause so the two can never disagree. The sole caller (`ForecastResultHandler.buildResult`) reads `.rating()` and gets the identical value.
- **Proof.** The `CachePayloadGoldenMasterTest` cache-payload golden master passes byte-for-byte unchanged (combined ratings identical before/after). Combiner unit tests extended to assert the exposed components — single SKY component inland, SKY+TIDAL coastal, an abstaining tide absent from the components — plus `type()`/`summary()` clause coverage on both visitors. No dual-write in this commit: the seam exists, nothing uses it yet.

### Fixed — React/react-dom version mismatch + `eslint-plugin-react-hooks` 7 upgrade (Dependabot batch)
- **react-dom realigned to 19.2.7.** Dependabot's react bump (PR #94) rebased ahead of its react-dom bump (PR #93), so `main` briefly carried `react@19.2.7` with `react-dom@19.2.6`. React requires the two packages to be the *exact* same version and otherwise throws `Incompatible React versions` at runtime, which failed the frontend Vitest job. Bumped `react-dom` to `19.2.7` to match.
- **`eslint-plugin-react-hooks` 5.1.0 → 7.0.1 (PR #22).** v7's `recommended` config enables the React Compiler lint rules (`purity`, `set-state-in-effect`, `refs`, `immutability`), which surfaced 11 errors in existing code. Resolved with real refactors where cleanly behaviour-preserving — moved `TurnstileWidget`'s latest-ref writes into an effect; derived `useAuroraViewline`'s disabled state instead of clearing it via `setState` in an effect; dropped redundant mount-time `setState` resets in `AuroraForecastModal`/`TideManagementView` (state already initialises to the pending values) — and targeted `eslint-disable` lines, each with a justification, for the five intentional patterns the rules flag overzealously (`Date.now()` snapshots for "logged in ago"/"days remaining" display, the post-auth verify-token cleanup coupled to a `history.replaceState`, the auto-dismissing run banner, and the `window.location.hash` tab-sync navigation).
- **No behaviour change.** 1563 frontend tests and the production build pass; lint is clean (0 errors).

### Fixed — Pipeline safety timeout lengthened to 4h and made configurable (afternoon Anthropic batch latency)
- **Why.** Intraday cycles (14:00 UTC) failed ~100% of the time on `Safety timeout: Batch set did not reach terminal status within PT1H30M`, while nightly cycles (01:00 UTC) always succeeded. Instrumented prod data shows why: Anthropic batch processing is load-dependent — nightly batches reach terminal in 2–5 min, but afternoon batches (peak US-morning/EU-afternoon demand) took 98–173 min, with **zero** request failures in `api_call_log` and the poller detecting terminal within seconds of `ended_at`. Every request succeeded; the batch simply completed after the 90-min backstop had already failed the run. The orchestrator and poller were working correctly; the timeout was mis-calibrated for the afternoon reality.
- **The change.** `PipelineOrchestrator`'s safety timeout default goes from 90 min to **4 hours** (clears the worst observed 173 min with margin) and is now injected via `photocast.pipeline.safety-timeout` (ISO-8601 duration, default `PT4H`) so it can be tuned without a deploy as the seasonal latency pattern becomes clearer. The timeout's character is unchanged: a FAILURE BACKSTOP for genuinely-stuck batch sets, not a coordination mechanism — 4h still bounds a dead cycle. The failure message already interpolates the configured value, so no message change was needed.
- **Shared benefit.** The RETRY_FAILED phase (PR #129) waits under this same timeout, so afternoon retry batches are unblocked too.
- **Deferred (recorded, not built).** A 4h timeout on a 14:00 UTC fire means completion as late as ~18:00 UTC — fine in summer, but in winter (sunset ~15:30 UTC) a 3h intraday run completes after the event it exists to inform. The proper fix is event-relative intraday scheduling (fire at soonest-event minus ~4h); this latency data is its concrete justification. See `docs/product/backlog.md`.
- **Empirical acceptance.** The next 14:00 UTC intraday cycle should COMPLETE (assuming afternoon latency ≤4h), visible in the Pipeline Runs view with real phase timings.

### Docs — `pipeline_run_pick` empty since run #21: confirmed honest decline-to-crown, not a persistence gap
- **The question.** `pipeline_run_pick` stopped gaining rows after run #20 — including for #24, a COMPLETED nightly whose briefing the Planner displays. Was that best-bet C honestly declining to crown a flat week (nothing broken), or a regression where computed picks are silently not persisted?
- **Verdict: honest decline; no code fix.** The Planner's "No standout recommendations" headline is frontend empty-state copy rendered only when `bestBets` is empty, and `BriefingHonestyFilter` passes `bestBets` through unchanged — so the #24 briefing demonstrably contains zero picks. The persistence hook writes exactly that same list (empty → documented `[PICK] No picks to persist` no-op), so display and table agree end-to-end. Best-bet C's coverage gate is reorder-only (it can never drop a pick) and returns the same `List<BestBet>` shape the hook always consumed — no mismatch is possible. Full chain, the prod log/SQL checks that distinguish honest-zero from a silent advisor failure, and the cross-run comparison view's already-graceful no-pick handling (`PickCell` renders "none"; both-empty hides the card) are in `docs/engineering/pipeline-run-pick-empty-investigation.md`.
- **Flagged for product decision (not built):** an explicit "declined to crown" record per run, and whether Planner copy should distinguish honest-flat from failure — see `docs/product/backlog.md`.

### Changed — Single tide-fact derivation seam (`TideFactDeriver`)
- **Why.** Two paths derived "the same" tide facts independently and could drift: the scoring path (`ForecastDataAugmentor.buildTideSnapshot`) and the hot-topic/briefing path (`BriefingSlotBuilder.calculateTideData`). Both bottomed out in the same library calls (`TideService.deriveTideData` + `LunarPhaseService.classifyTide` + the statistical height comparison) but assembled them separately. This is the technical debt: two assemblies of the same facts will eventually disagree. See `docs/engineering/hot-topics-tide-observer-refactor-design.md` (verdict ii: shared derivation, separate scopes).
- **The change.** New `TideFactDeriver.derive(...)` performs that derivation **once** and returns one `TideDerivation` carrying the complete union of facts. Both consumers now adapt their own existing presentation type from it (`TideSnapshot` for scoring, `TideResult`/`TideInfo` for briefing) and derive nothing themselves. Grep proof: `deriveDualWindowTideData` + `classifyTide` are each called from exactly one place — the new seam — and neither consumer calls `deriveTideData` at all.
- **Single fetch, both alignment windows.** The scoring path's 3★ "widened window" alignment flag is derived from the *same* tide curve as the tight flag — the extremes query depends only on location + event time, never on the window. So `TideService.deriveDualWindowTideData(...)` fetches the extremes **once** and classifies them at both windows; `TideDerivation` carries both `tideAligned` and `widenedAligned`. This removes the second `deriveTideData` fetch the combine seam used to make (scoring goes 2 fetches → 1; the briefing path, which ignores `widenedAligned`, stays at 1) and makes the seam the literal single tide call site.
- **Lossless statistical signals.** `TideDerivation` carries the two independent height booleans (`heightAboveP95`, `heightAboveSpringThreshold`) rather than only the collapsed `TideStatisticalSize` enum, because the briefing path needs both flags independently and the enum's P95-first collapse is only lossless when `p95 >= springThreshold` (not guaranteed). Scoring collapses to its enum via `TideDerivation.statisticalSize()`; briefing applies its own HIGH-tide ±90-minute gate around the raw booleans.
- **Pure refactor.** No behaviour change — produced hot-topic pills and scoring tide facts are identical before/after, proven by the existing consumer tests passing with unchanged assertions (a real `TideFactDeriver` is injected from the same mocks, so every stub and assertion drives behaviour through the new seam). `RatingCombiner`, the `HotTopic` model, the frontend, the simulation harness, and the two tide hot-topic strategies are untouched. New `TideFactDeriverTest` covers the king/spring height thresholds at value−1 / value / value+1.

### Added — Batch retry-on-failure: recover transient parse/API failures (RETRY_FAILED phase)
- **Why.** A real prod case: a 325-request batch had 1 parse failure — Haiku returned a garbled response (off-distribution text mixed with valid schema fragments) that failed JSON parse; 324/325 succeeded, the 1 failure was dropped, and that location went unevaluated for the cycle. This is a *transient* failure — asked again the model almost certainly returns valid JSON. A capped, single retry recovers these cheaply. Foundational batch-correctness work (robustness against transient failures), not a feature add.
- **The critical distinction (commit 1).** Retry ONLY genuinely-failed requests (attempted and came back unusable — parse failures / API errors), NEVER deliberate skips. The guarantee is **structural**: deliberate skips (`SKIPPED_*`) live in `forecast_run_disposition` and are never sent to the model, so they have **no `api_call_log` row**; the retry set is derived purely from `api_call_log` failures (`is_batch = true AND succeeded = false`) within the cycle's precursor batches, so a skip cannot enter it. `BatchRetryService.selectFailures(pipelineRunId)` parses each failed `custom_id` back to (location, date, event), de-duplicates, and drops any unparseable / non-forecast id (cannot be reconstructed → not retried blindly).
- **Cap is budget AND tripwire (commit 1).** `photocast.batch.retry-failure-cap` (default **5**). ≤ cap genuine failures → retry once; > cap → NOT retried, recorded loudly as a **systematic** failure to investigate (a prompt regression / model issue / bad input would fail again on retry and cost far more). Single pass, no retry loops — bounded per-cycle cost ≤ `cap × 1 retry`, a few pence at Haiku rates against ~£2.50/night.
- **Schema + phase enum (commit 1).** V106 adds `forecast_batch.is_retry` (a boolean, not a precursor FK: a cycle has up to four forecast batches and one retry may aggregate failures across several — tie-back is the shared `pipeline_run_id` + the flag). New `PipelinePhase.RETRY_FAILED` (conditional, between WAIT and BRIEFING) and `BatchTriggerSource.RETRY`.
- **Tests (commit 1)**: selection returns exactly the attempted-failures (skips, having no log row, cannot appear); cap boundary at N and N+1 (RETRY vs SYSTEMATIC); clean cycle and no-precursor-batches → no-op NONE; malformed / non-forecast custom ids dropped; duplicate failure rows de-duplicated.
- **Retry submission + RETRY_FAILED phase (commit 2).** `BatchRetryService.submitRetry` reconstructs each failed request into the SAME (location, date, event) request the precursor sent — re-load location, re-derive model tier by horizon, re-assemble atmospheric data via the same `ForecastService` path — and submits them as ONE retry batch tagged `is_retry`. A request that cannot be reconstructed (location gone, or weather has since turned unsuitable) is left failed and falls back to the next cycle. The orchestrator runs the conditional `RETRY_FAILED` phase inside the shared `waitAndBriefPhase` (both NIGHTLY and INTRADAY — single code path) between WAIT and BRIEFING: NONE → no phase recorded; SYSTEMATIC → phase records "not retried"; RETRY → submit, wait (reusing the existing `waitForBatchSetComplete`, which already polls by `pipeline_run_id`, so the retry batch is waited on and ingested through the normal `BatchPollingService → BatchResultProcessor` path — and shares the safety timeout), then record `"N failed, M retried, K recovered, J still-failed"`. Idempotent + single-pass: submission is skipped if a retry batch already exists, so a restart mid-phase resumes safely and a retry's own failures are never retried.
- **Recovery ingestion MERGES, never replaces (the data-loss guard).** `cached_evaluation` is keyed by region and holds the whole region's locations in one `results_json`; a retry batch carries only the recovered handful. Routing those through the normal replace-write (`writeFromBatch`) would wipe the region's originally-successful locations. New `BriefingEvaluationService.mergeFromBatch` overlays the recovered locations onto the existing entry (in-memory, falling back to the persisted `results_json` if the process restarted), and `BatchResultProcessor` selects merge-vs-replace on the batch's `is_retry` flag. Parsing / `api_call_log` / token accounting / status are identical for both — only the terminal cache write differs.
- **Tests (commit 2)**: `submitRetry` reconstructs + submits one `is_retry` batch with only the failed location; idempotent (no second submit when a retry exists); ignores non-RETRY selections; leaves unreconstructable requests failed; recovery summary formatting. `mergeFromBatch` preserves prior in-memory locations and overlays the recovered one, persists the combined set, and falls back to the DB prior when in-memory is absent (post-restart) so a retry can never shrink a region. `BatchResultProcessor` routes a retry batch through `mergeCacheKey` and never `flushCacheKey`. Orchestrator: clean cycle records no RETRY_FAILED phase; within-cap submits + records recovery; over-cap records systematic without submitting; INTRADAY exercises the same shared retry path.
- **Pipeline Run view surfacing (commit 3).** `PipelineRunBatch` gains `retry`, so the Pipeline Run detail view (Manage → Operations → Pipeline Runs) flags the retry batch with a "Retry" badge in "Batches in this cycle" — tied to its precursor(s) via the shared cycle id — while the precursor rows are unflagged. The `RETRY_FAILED` phase appears in the timeline (label "Retry failed requests") with its `"N failed, M retried, K recovered, J still-failed"` detail. Frontend test asserts the badge appears only on the retry batch and the phase row + recovery detail render.

### Added — `cached_evaluation` defensive instrumentation (tripwire for the 2026-06-06 empty-Planner failure mode)
- **Why.** On 2026-06-06 the batch demonstrably *wrote* 21 cache keys (logs prove it) yet the rows later vanished, leaving the Planner empty. The investigation established it was not a Postgres restore and not a manual delete, and the root cause is likely unknowable from existing evidence (`log_statement = none`, so no DELETE was ever recorded). The incident has not recurred. This is proportionate hardening — make any recurrence visible within one cycle and remove a latent footgun — **not** a root-cause fix (none is confirmed). See `docs/engineering/cached-evaluation-clear-investigation.md`.
- **Backwards-jump heartbeat.** `BriefingEvaluationService.recordCacheHealthHeartbeat()` runs on each briefing refresh (wired into the existing `onBriefingRefreshed` event hook — the natural cadence, no new scheduler). It logs `cached_evaluation health: rows=…, maxEvaluatedAt=…, distinctKeys=…` at INFO and compares against an in-memory last-seen baseline; if `maxEvaluatedAt` moves **backwards** or the row count **drops** by more than a small tolerance (with no intervening admin clear, which resets the baseline), it logs a **WARN** — turning the silent, weeks-cold 6-June mystery into a same-cycle alert. Side-effect-free: reads counts only, never mutates, never throws into the refresh path (failures are swallowed and logged).
- **Delete visibility.** `clearCache()` now logs a **WARN** before deleting — `cached_evaluation DELETE: deleteAll requested by {principal}, removing {n} rows` — so a genuine admin wipe is unmistakable in the app logs and a *silent* disappearance (no such line) positively points at a data-layer cause instead. (Operator follow-up, not code: enabling Postgres `log_statement='mod'` or a low `log_min_duration_statement` on `goldenhour-db` would add a second, infra-level record of any DELETE.)
- **Footgun removed.** `CachedEvaluationRepository.deleteByEvaluationDateBefore(...)` was unwired dead code (confirmed: zero callers in main and tests) — a date-scoped delete is exactly the shape that could produce a "drop recent, keep old" wipe if ever wired carelessly. Removed. Two read-only aggregate queries (`findMaxEvaluatedAt`, `countDistinctCacheKeys`) added for the heartbeat.
- **Admin wipe guarded.** `DELETE /api/briefing/evaluate/cache` now requires an explicit `?confirm=true` (400 otherwise) so the full-table wipe cannot be triggered accidentally; auth is unchanged (ADMIN-only) and the WARN audit always fires.
- **Tests**: heartbeat backwards-timestamp and row-count-drop both WARN; normal forward movement and a within-tolerance drop do not; first call establishes a baseline silently; a repository failure is swallowed (never throws) and logs `heartbeat failed`; an admin clear resets the baseline so the next heartbeat does not false-warn; `clearCache` logs the audit WARN with the row count before `deleteAll`; the admin endpoint rejects without `confirm` and proceeds (with the WARN) with it.

### Changed — Sky/tide decomposition: Claude scores the sky alone, a `TideVisitor` re-adds the tide (v2.13.2 step 3)
- **The monolithic coastal rating is now two separately-scored concerns averaged together.** Before: `CoastalPromptBuilder` folded the tide *into* Claude's single `rating` (a "+1 boost for an aligned tide"). After: the coastal prompt asks Claude to score the **sky alone**, and a deterministic, rule-based **`TideVisitor`** contributes a separate 1–5 tide score that `RatingCombiner` averages in (plain mean, round half-up — no gate, no cap, no weakest-link). This is the goal of the v2.13.x arc (option B from the investigation: decompose the prompt, re-derive tide post-batch). **This is the one intentional behavioural change in the arc — coastal ratings shift.**
- **`TideVisitor` — rule R1 (a misaligned tide PENALISES, it does not abstain), two concentric windows, derivability-gated.** `appliesTo` = a *tidal location* (non-empty `tideType`, a property of the location not the forecast). `evaluate`: tide **un-derivable** (data gap — no stored extremes) → `OptionalInt.empty()` (abstain → sky alone; a data gap must never penalise); **king/spring aligned within the tight golden/blue-hour window** → 5; **aligned within the tight window** (regular tide) → 4 (this tier is the existing `TideService.calculateTideAligned`, reused unchanged); **aligned only within the window widened ±60 min beyond each edge** → 3 ("imperfect tide, but a great sky could work an average foreground"); **outside even the widened window** → 1 (no foreground — drags the rating, the St Mary's Lighthouse case)
- **The widened window is the existing rule + 60 min, not a reimplementation.** `ForecastDataAugmentor.deriveTideContext` runs `calculateTideAligned` twice — once over tide derived with the existing golden/blue half-width window (tight, the snapshot's `tideAligned`), once with that window extended by `WIDENED_ALIGNMENT_EXTENSION_MINUTES = 60` (widened). The 3★ band is exactly "widened-aligned AND NOT tight-aligned". The extra widened derivation runs **only at the combine seam**, never in the forecast prompt path
- **Tide re-derived at the shared combine seam (option B), single path for batch + sync.** `ForecastResultHandler` injects `ForecastDataAugmentor` and re-derives the `TideContext` via `deriveTideContext(location, date, targetType)` (event time re-computed from `SolarService` — a pure, deterministic instant; no new persistence or per-request side-channel needed). Both `parseBatchResponse` and `handleSyncResult` flow through one shared `buildResult(...)` helper, so the two transports behave identically. A new `VisitorContext(SunsetEvaluation, TideContext)` threads the sky + tide inputs through `Visitor`/`SkyVisitor`/`TideVisitor`/`RatingCombiner`. The widened flag is carried on `TideContext` (not added as a field to the heavily-constructed `TideSnapshot` record — that would have churned 38 test sites including the untouchable prompt-regression suite)
- **Sky-not-forecast is now an honest, visible 1★ rather than a silent null.** When Claude returns a parseable response but omits the rating (`eval.rating() == null`), `buildResult` substitutes — *before* combining — `rating = 1`, a code-injected summary "Claude did not forecast the fiery sky and golden hour for this location", a null headline, and **no** triage fields ("evaluated but unscoreable", distinct from "triaged out"). Applies to all locations, coastal and inland. Branching here, ahead of the combine, also structurally prevents a coastal sky-empty location from ever being scored on tide alone (which would average to a misleading 4/5) — the tide is never reached
- **Sky-only coastal prompt.** `COASTAL_SYSTEM_PROMPT_SUFFIX` is now surge-only guidance (the tide-boost / king-tide rating instructions removed); the `Tide:` data line is gone from the coastal user message. The **storm-surge block is kept** — it is a foreground/safety concern, not a tide-rating lever
- **Golden masters regenerated intentionally** (the safety nets the earlier steps put in place did their job): prompt goldens `coastal-tidal` and `coastal-surge` (eyeballed: only the tide instruction + `Tide:` line removed, surge block intact); cache goldens `coastal-misaligned` (3★ → **2★** under R1: sky 3 + misaligned tide 1 → avg 2) and a new `sky-not-forecast` fixture (inland-shaped: pins 1★ + the exact not-forecast summary + null headline + no triage). `coastal-aligned` stayed byte-identical (sky 3 + spring tide 5 → avg 4, the value it already held) and **`inland` stayed byte-identical** (no `TideVisitor` applies — the proof nothing leaked). Mutation check still guards
- **Tests**: `TideVisitorTest` (11 — every tier + both abstain cases + both edges of the +60 boundary); `ForecastDataAugmentorTest` deriveTideContext cases (widened = tight + 60 reusing `calculateTideAligned`; outside-widened; inland short-circuit; data-gap abstain; event-time derivation via `SolarService`); `ForecastResultHandlerTest` (coastal aligned/misaligned/un-derivable averaging + sky-not-forecast for **both** inland and coastal, proving the pre-combine branch fires before tide). `RatingCombinerTest`/`SkyVisitorTest`/`CoastalPromptBuilderTest` updated for the new `VisitorContext` and surge-only coastal prompt
- **Untouched, as designed**: triage (the pre-submission gate), `BriefingService`/best-bet/also-good (consume the score unchanged), aurora (separate prompt path), and the `tide_extreme`/`TideSnapshot`/`calculateTideAligned` tight-window rule. Empirical acceptance (comparing nightly `cached_evaluation.results_json`) is a post-deploy human check — deploy this alone for a clean overnight bake

### Added — Cache-payload golden master: pin `results_json` before the v2.13.2 decomposition (v2.13.2 step 2, test-only)
- **`CachePayloadGoldenMasterTest`** + three fixtures (`src/test/resources/cache-golden/{coastal-aligned,coastal-misaligned,inland}.json`) pin the serialised per-region cache payload (`cached_evaluation.results_json` = `List<BriefingEvaluationResult>`) *before* any decomposition. This is the prerequisite the v2.13.2 investigation (§4) named: with no golden master on the cache payload, step 3's intentional change (sky-only coastal prompt drops coastal ratings ~1★, then `TideVisitor` re-adds via averaging) could not be distinguished from unintended drift (a field emptied, order changed, a rating moved unexpectedly). Same role the v2.13.1 prompt golden masters played
- **Pins structure + numerics + rating, tolerates prose.** Asserts the JSON field names + order and the deterministic fields exactly (`locationName`, `rating`, `fierySkyPotential`, `goldenHourPotential`, and the absent triage fields — null and so `NON_NULL`-omitted on the scored path); asserts `summary`/`headline` are present-strings only (headline may be legitimately absent) without pinning their text, because Claude prose is non-deterministic across runs and pinning it would generate false positives that train the team to ignore red. Fixtures store the payload with `summary`/`headline` values replaced by `<<SUMMARY>>`/`<<HEADLINE>>` placeholders; the test normalises the live serialisation the same way before comparing
- **Confirmed field order** (Jackson, record declaration order): `locationName, rating, fierySkyPotential, goldenHourPotential, summary, [triageReason], [triageMessage], [headline]`. **Confirmed serialisation seam**: `BriefingEvaluationService.persistToDb` serialises via the Jackson-2 `AppConfig.objectMapper()` bean (`new ObjectMapper().registerModule(JavaTimeModule)`) — *not* the Jackson-3 `tools.jackson` mapper the handler uses as a parser handle. The test serialises through a Jackson-2 mapper configured identically
- **Real construction seam, no Claude call.** Drives the real `ForecastResultHandler.parseBatchResponse` → real `RatingCombiner` over `SkyVisitor` → the exact `new BriefingEvaluationResult(...)` production builds, from a fixed `SunsetEvaluation` injected via a stubbed parser (the parser is upstream of, and distinct from, the serialisation seam). No `@SpringBootTest`, no `lenient()`, no `any()` in verify
- **Mutation-verified** (ran locally, reverted): flipping a pinned `rating` → FAIL; reordering/renaming a field → FAIL; changing only `summary` text → PASS (prose tolerance proven). Test-only; no production code, prompt, visitor, combiner, or scoring touched — safe to merge ahead of step 3

### Added — Visitor foundation: relocate the whole-rating Claude result into a `SkyVisitor` (v2.13.1)
- **`service/evaluation/visitor/Visitor`** — foundation interface for the v2.13 photogenic-evaluator architecture: `appliesTo(LocationEntity)` plus `evaluate(LocationEntity, SunsetEvaluation) → OptionalInt`. Two deliberate deviations from the original prompt sketch, code-grounded: (1) `OptionalInt` not `int` because the Claude `rating` field is nullable in the schema and parser, and `OptionalInt.empty()` preserves today's "no number this time" behaviour rather than fabricating a value; (2) the second arg is the produced `SunsetEvaluation` not raw forecast data because the rating only exists at result time on the asynchronous batch path — recomputing from inputs would require re-calling Claude, which a batch result handler cannot do. Both class Javadocs spell this out so the contract is honest about what v2.13.1 is and what changes in v2.13.2
- **`SkyVisitor`** — the only visitor in v2.13.1. `appliesTo` → true (sky is universal); `evaluate` returns the parsed rating as-is, empty when null. This is the existing whole-rating Claude result relocated, not a new evaluator
- **`RatingCombiner`** — plain arithmetic average of applicable visitors that produced a value, rounded to the nearest integer; `null` when no visitor produced a score. No weakest-link, no veto: a bad condition is just a low score that averages in. With only `SkyVisitor` applying today the "average" is over a single value and equals it exactly — combined rating is byte-equivalent to the pre-visitor rating for inland *and* coastal locations. Package-private `appliedVisitors()` accessor lets the boundary test assert "exactly one visitor applies to an inland location today" so future regressions are caught
- **`SunsetEvaluation.withRating(Integer)`** — record copy-with helper that lets the combiner-derived rating flow into the persisted payload, including the path where `ForecastService` later derives the `forecast_evaluation` row from the returned `Scored(eval)`
- **`ForecastResultHandler`** — wired the combiner into both convergence points. `parseBatchResponse` (batch → `cached_evaluation`) and `handleSyncResult` (sync → `cached_evaluation` and, via `Scored(eval.withRating(combinedRating))`, → `forecast_evaluation`) now go through `RatingCombiner.combine(...)`. This was chosen as the seam after planning showed it is the single result-time point both transports converge with a rating in hand; `BatchRequestFactory.selectBuilder` is the prompt-build seam, not the result seam
- **Triage stays a pre-visitor gate** — not on the `Visitor` interface, not moved into `SkyVisitor`. The "triage in SkyVisitor, fail → null" option from the prompt was rejected once the wiring was planned: a Claude-owning, synchronous SkyVisitor would force a sync-only visitor, fork the async batch path, and violate the pass's "no fork" rule. Triage relocation defers to v2.13.2 alongside sky-prompt decomposition
- **Equivalence proof, not just green tests**: the Part A golden-master prompt fixtures (added in commit `4ec6fe8`) stay green — the assembled prompt is unchanged. `RatingCombinerTest` (17 cases): with the single `SkyVisitor`, combined rating equals `eval.rating()` across 1–5 for inland *and* coastal, `null` when the rating is absent; plus mechanics — averaging, half-up rounding, non-applicable exclusion, empty case — via stub visitors. `SkyVisitorTest` (7 cases): universal applicability, faithful pass-through including the null case. Boundary: exactly one visitor applies to an inland location today (no phantom tide). Touched suites green: `ForecastResultHandlerTest`, `BatchResultProcessorTest`, `EvaluationServiceImplTest`, `ForecastServiceTest`
- **Discrepancy recorded (not fixed in this pass)**: CLAUDE.md and `ForecastService:242` Javadoc say a triaged-out location gets `rating=1`; the **code sets `rating=null`** (`ForecastService:367` builds `new SunsetEvaluation(null, null, null, null)`) and `EvaluationViewService:233-241` distinguishes scored (`rating != null`) from triaged (`triageReason != null` with null rating). The docs are wrong, the code is right; flagged for follow-up
- **Briefing layer untouched**: confirmed. `BriefingService`, `BriefingBestBetAdvisor`, best-bet / also-good logic unchanged; `ForecastResultHandler` still calls `briefingEvaluationService.writeFromBatch(...)` with the same `BriefingEvaluationResult` shape
- **Investigation doc**: `docs/engineering/v2-13-1-foundation-notes.md` records the ground facts, the discrepancies found, and the three design reconciliations where the prompt and the code's architecture collided. Companion to the Pass 0 investigation in `chore/v2-13-visitor-investigation`
- **What's deferred to v2.13.2 (explicitly NOT in this pass)**: a rule-based `TideVisitor` + non-trivial averaging; sky-prompt decomposition (regenerates golden masters intentionally); relocating triage into `SkyVisitor` as a `SkyTriage` collaborator; switching `Visitor.evaluate`'s second arg from `SunsetEvaluation` to forecast inputs once `SkyVisitor` owns its own Claude call

### Added — Cross-run comparison: did Plan A or Plan B change since this morning? (intraday refresh, commit 3 of 3)
- **The Pipeline Run detail view now shows an intraday-vs-nightly best-bet comparison** — the value signal the whole intraday refresh exists to produce. For an INTRADAY run it finds the same-day NIGHTLY baseline (latest nightly run on the same Europe/London day, at or before the intraday trigger) and surfaces, per pick rank (Plan A / Plan B), whether the pick changed and on which dimensions: region, event date, event type, or snapshotted Claude rating
- **A headline verdict — "plan changed" (amber) or "no change" (green)** — answers the "do I need to replan tonight's sunset / set an alarm for tomorrow?" question at a glance, with the now-vs-this-morning slots shown side by side. This is how the keep/tune/kill-intraday decision gets made without SSH
- **`PipelineRunComparisonService`** computes it from the existing `pipeline_run_pick` rows (no new persistence): rating drift below half a star is treated as forecast noise (not flagged), event-type matching is case-insensitive, and a pick present in one run but not the other is flagged by presence. Returns nothing for nightly runs (they are the baseline) or intraday runs with no same-day baseline
- Surfaced on `GET /api/admin/pipeline-runs/{id}` as a nullable `comparison` field on `PipelineRunDetail`; the `STABILITY_RECLASSIFY` phase's cost-gate detail now also renders in the phase timeline

### Added — Intraday forecast refresh runs as an INTRADAY cycle through the shared orchestrator (commit 2 of 3)
- **A mid-afternoon intraday refresh now runs daily at 14:00 UTC** (`intraday_forecast_refresh`, V105), re-evaluating the decision-window events — tonight's sunset, tomorrow's sunrise + sunset (the next ~36h of actionable events) — to catch within-day forecast movement before the morning's nightly plan goes stale. It is a *plan-change detector*, not a generic cache refresh
- **It reuses the exact same `PipelineOrchestrator.runCycle` path as nightly** — no parallel orchestrator, collector, briefing, or persistence. `runIntradayCycle()` is a thin peer of `runNightlyCycle()` differing only in: an `IntradayCandidateCollectionStrategy` (the decision window, clock-pinned to Europe/London "today"), an `IntradayEligibilityPolicy` (the skip-settled cost-gate), and an ephemeral re-classification. Wait, briefing, best-bet/also-good persistence and disposition recording are byte-for-byte the nightly code
- **Cost is bounded by stability.** SETTLED decision-window locations are skipped with no Claude call (recorded as `SKIPPED_NO_REFRESH_NEEDED` — a new skip category surfaced through `EligibilityDecision`, distinct from nightly's `SKIPPED_STABILITY`); only TRANSITIONAL/UNSETTLED locations are re-evaluated, on the near-term model. A typical cycle costs a fraction of nightly's
- **The re-classification is ephemeral.** It computes fresh stability for its own gating but does NOT call `stabilitySnapshotProvider.update()` — the morning's snapshot stays authoritative for every other consumer (other run types, the admin stability view). This is the one real new seam, threaded as a single `ephemeral` boolean
- **Intraday records four phases** — `STABILITY_RECLASSIFY → FORECAST_BATCH_SUBMIT → FORECAST_BATCH_WAIT → BRIEFING`. The collect→submit logic stays one shared implementation; a between-collect-and-submit hook (fired inside the batch service's concurrency guard) lets the orchestrator close `STABILITY_RECLASSIFY` (recording the cost-gate summary "N considered, M settled-skipped, K unsettled-evaluated") and open `FORECAST_BATCH_SUBMIT` between the two steps — so both have real, separate durations. Nightly keeps its single SUBMIT phase via a no-op hook
- Best-bet/also-good picks persist for the intraday run via the unchanged PR #113 hook, so the cross-run "did Plan A change vs this morning?" comparison (commit 3) is a query over existing data

### Added — Ephemeral stability re-classification + STABILITY_RECLASSIFY phase (intraday refresh, commit 1 of 3)
- **`ForecastTaskCollector.collectScheduledBatches` gained an `ephemeral` overload.** When `ephemeral=true`, the stability classification is still computed and returned in-memory (so it can drive the cycle's eligibility cost-gate) but the snapshot is **not** written through to `stabilitySnapshotProvider.update()`. This is the one real seam the intraday refresh needs: intraday re-classifies the decision-window cells with fresh afternoon weather purely for its own gating, then discards — the morning's nightly snapshot stays authoritative for everything else (other run types, the admin stability view). Nightly continues to pass `ephemeral=false` (classify + publish); behaviour byte-for-byte unchanged
- **`PipelinePhase.STABILITY_RECLASSIFY` added.** Intraday will record this as a distinct phase before `FORECAST_BATCH_SUBMIT` so the cost-gate decision ("N reclassified, M settled-skipped, K unsettled-evaluated") is visible in the Pipeline Runs UX rather than buried in logs. Nightly's phase set is unchanged
- Pure foundation commit — nothing yet invokes the ephemeral path; wiring follows in commit 2

### Added — Targeted force-evaluation backs far-out headline picks with real evidence (Option C commit 2 of 2)
- **`ForecastTaskCollector` now force-evaluates a small, capped set of far-out best-bet headline contenders** that the Gate 4 stability gate would otherwise drop. Without this, the coverage-aware selection (commit 1) would make best bet structurally near-term-biased — far-out cells are rarely Claude-evaluated, so a genuinely clear "the weekend looks special" day could never clear the coverage floor. Force-evaluation guarantees the would-be-crowned region actually reaches the floor, so a clear far-out day stays promotable *with real Claude evidence behind it*
- **It is a rule WITHIN the single eligibility loop, not a parallel path.** At the existing `!decision.eligible()` skip branch, a far-out (T+2/T+3) GO candidate that is a ranked headline contender is force-included on the far-term model instead of skipped. The forced eval flows through the same near/far × inland/coastal bucketing → same `EvaluationService.submit` → same Anthropic Batch API as every other evaluation
- **`selectForceEvalKeys`** ranks far-out region/event cells with evaluation-eligible GO slots by GO count, then tide-aligned count, then horizon (sooner first), and accumulates their slots up to the cap — so the highest-merit contenders get the budget. New `FORCE_EVALUATED` disposition category records each one for observability (`[BATCH DIAG] FORCE-EVAL` log line + `forced=` in the triage summary)
- **Capped by `photocast.batch.force-eval-cap` (default 6).** Per-cycle cost increase ≈ 6 far-term batch (Haiku) evals ≈ £0.005, ~0.2% of a ~£2.50 night — noise. Cap 0 disables the feature. The cap bounds the cost regardless of how many GO candidates the briefing contains
- Gate 4's general stability economics are unchanged for non-headline candidates — force-evaluation is the capped exception for headline contenders only

### Fixed — Best bet can no longer be crowned on cheap thresholds (coverage-aware selection, Option C commit 1 of 2)
- **`BriefingBestBetAdvisor` now enforces a headline Claude-coverage floor.** The Planner could crown a headline BEST BET on a large triage GO count while only a couple of that region's locations were actually Claude-evaluated — confidently promoting a barely-assessed, further-out day over a nearer, better-evidenced one (observed: Northumberland T+2, 2 Claude-rated, crowned over North Yorkshire Coast T+1, 5 Claude-rated). `GO` is a cheap threshold verdict, not an evaluation; only `claudeRatedCount` measures what was assessed
- **`applyCoverageAwareRanking`** runs after pick validation: a region cannot hold rank 1 when its `claudeRatedCount` is below `MIN_HEADLINE_CLAUDE_COVERAGE` (3, calibrated to sit between the 2-rated and 5-rated regions of the observed case) **and** a pick that clears the floor exists to crown instead. The gate is deliberately comparative — it only demotes a thin headline by promoting a genuinely better-evidenced pick; when no covered alternative exists the order is left as Claude ranked it. Extends `BriefingHonestyFilter`'s "rewrite at zero coverage" principle to the insufficient-coverage case at the crowning decision
- **Coverage is read once per region** from the same `getCachedScores` lookup the rollup JSON already uses (surfaced via `RollupResult.coverageByKey`), so cache call counts are unchanged. On promotion the new headline's `relationship`/`differsBy` are cleared and trailing picks' relationships recomputed so they stay coherent. Stay-home and aurora picks are exempt (aurora has its own clear-sky gate). The system prompt also now tells Claude not to crown thin coverage — the Java gate is the deterministic backstop
- A far-out day remains crownable **with evidence** — the targeted force-evaluation path (commit 2) is what raises headline contenders above the floor in the first place; this commit alone removes the wrong crowning

### Fixed — Stale briefings no longer record carried-forward picks (best-bet persistence follow-up)
- **`PipelineOrchestrator.persistPicksForCycle` now skips persistence when the briefing is stale.** On a below-threshold run, `BriefingService.refreshBriefing()` serves the last-known-good briefing whose `bestBets` are the *previous* cycle's picks carried forward. Persisting those against the current `runId` would have recorded the prior run's picks as this run's, silently corrupting the cross-run "did Plan A change?" comparison the table exists to power. The orchestrator now checks `briefing.stale()` and skips — only genuinely fresh picks are recorded
- **Minor cleanups** from a quality pass on the best-bet-persistence + orchestrator-extraction commits: `NightlyCandidateCollectionStrategy` / `NightlyEligibilityPolicy` constructors made private (all callers use the shared `INSTANCE`)

### Changed — Pipeline orchestrator extracted for cycle reuse (intraday refresh prep, commit 2 of 4)
- **`NightlyPipelineOrchestrator` renamed to `PipelineOrchestrator`** — the class is now the sequencer for any cycle type, not nightly's alone. Its mechanics (start run → submit → wait → brief → persist picks → complete) are shared single code path; cycles differ only in their strategy + policy
- **`runCycle(CycleType, CandidateCollectionStrategy, EligibilityPolicy)`** is the new generic entry point. `runNightlyCycle()` is now a thin caller of `runCycle(NIGHTLY, NIGHTLY_STRATEGY, NIGHTLY_POLICY)`. The intraday cycle (commit 3) will be another caller of the same `runCycle` with intraday's strategy + policy — no parallel orchestrator
- **`CandidateCollectionStrategy`** interface — per-cycle filter over `(date, targetType)` deciding which event slots enter the candidate set. `NightlyCandidateCollectionStrategy` accepts every future event (the existing behaviour). The intraday cycle's strategy will restrict to the decision window (T sunset, T+1 sunrise, T+1 sunset)
- **`EligibilityPolicy`** interface — per-candidate include/skip decision function. `NightlyEligibilityPolicy` encodes the Gate 4 horizon-depth table verbatim. The intraday cycle's policy will skip SETTLED with `SKIPPED_NO_REFRESH_NEEDED` and include only TRANSITIONAL/UNSETTLED
- **`ForecastTaskCollector.collectScheduledBatches(strategy, policy)`** is the new fully-parameterised entry point. The zero-arg overload is preserved as a convenience that uses nightly defaults — keeps the admin `submitForecastBatch()` path and any test harnesses working unchanged. The internal `collectForecastCandidates` gained a third parameter (the strategy) so the iteration's filter is the cycle's, not a hard-coded "accept everything"
- **`ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun(runId, strategy, policy)`** — fully-parameterised cycle-aware variant. The single-arg overload remains as a convenience wrapping nightly defaults
- **Pure refactor — nightly behaviour identical.** All 17 existing `ScheduledBatchEvaluationServiceTest` cases, 39 `ForecastTaskCollectorTest` cases, 8 `CollectForecastTasksCachedGateTest` cases, and 15 existing `PipelineOrchestratorTest` cases pass with no behaviour assertion changes — only the stub / verify call shapes were updated to match the new method signatures. Verified Checkstyle clean
- The "single code path, parameterised" hard constraint is now structurally enforced: intraday wiring (commit 3) only needs to add two concrete impls + a scheduled job + a STABILITY_RECLASSIFY phase, without touching the orchestrator's coordination mechanics

### Added — Per-cycle best-bet / also-good persistence (intraday refresh prep, commit 1 of 4)
- **New `pipeline_run_pick` table (V104)** — one row per Plan A / Plan B pick the briefing phase emits. Captures the qualitative pick from the advisor (headline, detail, event id, region, confidence, relationship, differs_by) plus a numeric snapshot of the region's `claudeAverageRating` at persist time. The numeric rating is the cross-run comparison primitive: a future intraday run's Plan A rating can be compared directly to the same morning's nightly Plan A rating to detect "did anything actionable move?" — the value-proving signal intraday will exist for. Headline prose alone is too noisy to compare run-to-run; the rating snapshot makes the comparison crisp
- **`PipelineRunPickEntity` + `PipelineRunPickRepository`** — JPA entity mirrors the schema; finder returns picks ordered by `pick_rank` so the Pipeline Runs view renders Plan A then Plan B
- **`PipelineRunPickService.persist(pipelineRunId, picks)`** — maps `BestBet` → entity, parses the composite event id, snapshots `claudeAverageRating` by mirroring `BriefingBestBetAdvisor.appendClaudeScores`'s computation exactly (same `BriefingRatingStats.compute` call the advisor itself uses). Robust by construction: null pipelineRunId logs and returns; null/empty picks is a no-op; per-pick DB failures are caught and counted, so one bad row never aborts the others; aurora picks skip the lookup (no region-level rating exists); stay-home picks persist with null event/region/rating
- **`NightlyPipelineOrchestrator` BRIEFING phase now persists the picks** — `persistPicksForCycle(runId)` is called after `briefingService.refreshBriefing()` returns and before `completePhase(BRIEFING)`. Belt-and-braces try/catch around the call so a contract violation in the service (which itself catches internally) still cannot fail a BRIEFING phase whose briefing actually succeeded. Pick persistence is observability, not correctness
- **Tests**: 22 new tests across `PipelineRunPickServiceTest` (mapping, rating-snapshot boundaries, robustness, helper-function boundaries) and `NightlyPipelineOrchestratorTest.PickPersistence` (4 cases — happy path with 2 picks; empty pick list still persisted; null cached briefing skips persist; service contract violation does NOT fail the BRIEFING phase or run). All 15 existing orchestrator tests pass unchanged
- **Why ship this independently of intraday**: nightly runs now start accumulating a per-cycle record of their picks immediately. When intraday lands (commits 3 + 4), there's already a baseline of morning picks to compare against — no warm-up gap

### Added — Pipeline Run observability UX (commit 3 of 3)
- **New Pipeline Runs sub-tab** under Manage → Operations (added as the first sub-tab, before Job Runs). Lists the most recent 50 cycles with status pill, current phase, waiting_on / failure reason, and total duration. The list polls every 60s so a stuck cycle becomes visible without manual refresh
- **Pipeline run detail panel** — click any row to drill in. Renders:
  - Summary card with status, started / completed timestamps, total duration, current phase, and a prominent waiting_on (RUNNING) / failure reason (FAILED) line so the "is it stuck?" / "did the safety timeout fire?" questions answer themselves at a glance
  - Phase timeline (SUBMIT → WAIT → BRIEFING) with per-phase status, started timestamp, duration, and the final detail (WAIT's last waiting_on, or a FAILED phase's reason)
  - Batch list — every `forecast_batch` row tagged with this cycle id, each with a "Show dispositions" toggle that embeds the existing `DispositionBreakdown` view inline so the cycle → phase → batch → disposition hierarchy renders in one place. No SSH, no separate page navigation
  - Auto-refreshes every 15s while the run is RUNNING so waiting_on ticks live
- **Backend API**: `GET /api/admin/pipeline-runs` (recent list) and `GET /api/admin/pipeline-runs/{id}` (detail with phases + batches). ADMIN-only. `PipelineRunSummary` / `PipelinePhaseSummary` / `PipelineRunBatch` / `PipelineRunDetail` records carry pre-computed duration so the frontend doesn't recalculate; `PipelineRunBatch.jobRunId` is the deep-link key the frontend uses to embed `DispositionBreakdown`
- **`PipelineRunController`** (4 MockMvc tests: list shape, detail shape, 404 for unknown id, PRO_USER 403 on ADMIN-only endpoint)
- **`PipelineRunsView.jsx`** (7 vitest cases: list rendering with status pills, empty state, row-click selects, detail panel with phase timeline + batch list, disposition expand-on-toggle, live `waitingOn` surface, FAILED `failureReason` surface)
- **`AbstractControllerTest`** gains `@MockitoBean PipelineRunService` so all controller tests share one context
- The no-SSH bar is now satisfied: where is the pipeline, is it stuck, what's it waiting on, how long did each phase take, did anything fail — every question is answerable in the browser, and the cycle's batch dispositions are one click away

### Changed — Nightly pipeline orchestrator wired; daily_briefing cron retired (commit 2 of 3)
- **`NightlyPipelineOrchestrator` now owns the `near_term_batch_evaluation` cron**. Constructor takes `DynamicSchedulerService`; `@PostConstruct registerJobTarget()` binds the schedule to `runNightlyCycle()`. The orchestrator is the single source of truth for when a nightly cycle runs
- **`ScheduledBatchEvaluationService.registerJobTargets()` no longer registers `near_term_batch_evaluation`** — only `aurora_batch_evaluation` (aurora stays parallel to the orchestrated cycle). The legacy `submitForecastBatch()` entry point is retained for admin invocations and tests but is no longer wired to the cron
- **V103 migration deletes the `daily_briefing` row from `scheduler_job_config`** — the briefing now runs as the orchestrator's BRIEFING phase on actual batch completion (~01:15 in production), not at 04:00 on a ~3h time buffer. The `ScheduledForecastService.registerJobTarget("daily_briefing", ...)` call is retained as a no-op so a one-line revert path stays open
- **Effect in production**: the briefing runs ~2.75 hours earlier on a typical night, and is gated on actual completion of the batch set rather than a fixed clock time. The safety timeout backstop (90 min) fires only if batches genuinely stall — distinguishable from the retired coordination-timeout by the `Safety timeout:` prefix on the run's `failure_reason`
- **Tests**: `NightlyPipelineOrchestratorTest` gains 2 cases under a `Cron wiring` nested class (verifies near-term registration and null-scheduler no-op); `ScheduledBatchEvaluationServiceTest.registerJobTargets_registersExpectedKeys` updated to assert near-term is now NOT self-registered. 57 tests pass across the 4 affected classes (up from 55 in commit 1)

### Added — Nightly pipeline orchestrator spine (commit 1 of 3)
- **V102 migration** — new `pipeline_run` table (cycle identity + lifecycle: cycle_type / status / current_phase / waiting_on / trigger_time / completed_at / failure_reason) and `pipeline_run_phase` table (per-phase status + start/complete timestamps). Adds `forecast_batch.pipeline_run_id` so a cycle's batches are queryable as a set
- **`CycleType` / `PipelinePhase` / `PipelinePhaseStatus` / `PipelineRunStatus`** enums. `CycleType` reserves `INTRADAY` for the intraday refresh follow-on (no intraday phases wired today — those live in code when intraday is built)
- **`PipelineRunEntity` / `PipelineRunPhaseEntity`** + their repositories
- **`PipelineRunService`** — transactional CRUD + phase-lifecycle helpers (startRun / startPhase / completePhase / failPhase / updateWaitingOn / completeRun / failRun). Keeps the orchestrator's main method free of persistence noise so the sequence reads top-to-bottom
- **`NightlyPipelineOrchestrator`** — the sequencer. The one method `runNightlyCycle()` reads as `SUBMIT → WAIT → BRIEFING → COMPLETED`. WAIT is a synchronous DB poll over `forecast_batch.status` for batches tagged with this cycle's id — chosen over callbacks/futures so state is durable across restart and `waiting_on` is a queryable fact. WAIT+BRIEFING runs on a virtual thread so it doesn't tie up a scheduler thread. Safety timeout (default 90 min, expected ~10 min) is a failure backstop, clearly distinguished from the old time-buffer coordination. `ApplicationReadyEvent` listener resumes RUNNING cycles left by a process restart (mid-WAIT or mid-BRIEFING re-enter idempotently; mid-SUBMIT is marked FAILED — partial submission state can't be safely recovered)
- **Cycle-aware batch submission** — `BatchSubmissionService.submit(...)` gains an optional `pipelineRunId` parameter (5-arg overload; existing 4-arg signature retained for aurora batch / admin paths). `EvaluationService.submit(...)` gains a parallel 3-arg overload. `ScheduledBatchEvaluationService.submitForecastBatchForPipelineRun(Long)` is the new cycle-tagging entry point the orchestrator calls. Aurora batch path unchanged (passes null at the bottom)
- **`Clock` bean** added to `AppConfig` so `PipelineRunService` and the orchestrator both run deterministically in tests
- **Tests**: `NightlyPipelineOrchestratorTest` (13 cases — happy path with phase-state recording, zero-batch short-circuit, completion-detection boundary at N-1 vs N, safety timeout, submit/briefing phase failures, restart resume mid-WAIT / mid-BRIEFING / mid-SUBMIT, no-running-runs no-op, structural forecast-only / INTRADAY-enum reserved) and `PipelineRunServiceTest` (6 cases for the CRUD helpers). 19 new tests, 0 lenient stubs, no `@SpringBootTest`
- **Spine is not yet wired** — the scheduler still invokes `submitForecastBatch()` directly, and `daily_briefing` still fires at 04:00. Commit 2 swaps the cron target to `runNightlyCycle()` and retires the briefing cron
- **Aurora is untouched** — no aurora services are injected into the orchestrator; the briefing's read of `AuroraStateCache` remains a non-blocking volatile lookup unrelated to the cycle
- Verification: 19/19 new tests green; full verify green except 3 pre-existing environmental failures (Docker-dependent testcontainers tests `IntegrationTestBaseSmokeTest` + `ForecastBatchPipelineIntegrationTest`, stale-schema `LocationFailureTrackingUIDemo`) confirmed by `git stash` + rerun on main HEAD

### Removed — Stale SSE-path whitelist in JwtAuthenticationFilter (Pass 3.3.3, commit 4 tidy)
- **`JwtAuthenticationFilter.isSsePath`** — dropped the `/api/briefing/evaluate` entry from the SSE token-via-query-param whitelist. The endpoint was deleted in commit 2; the whitelist line was stale. The three surviving SSE endpoints (`/api/forecast/run/{id}/progress`, `/api/forecast/run/notifications`, `/api/status/stream`) remain whitelisted unchanged
- Found during acceptance grep for `/api/briefing/evaluate` references — the only stray hit beyond the controller's `@RequestMapping` base path (which legitimately serves `/scores` and DELETE `/cache`)
- Tests: `JwtAuthenticationFilterTest` passes; covered by integration coverage of the three surviving SSE endpoints

### Removed — SSE service methods + 7 constructor dependencies pruned (Pass 3.3.3, commit 3)
- **`BriefingEvaluationService`** — file shrinks 660 → 348 lines. Deleted the 7 SSE-only methods (`evaluateRegion`, `evaluateSingleLocation`, `getEvaluableLocationNames`, `emitCachedResults`, `completeEmitter`, `sendSafe`, `cancelOutstandingForecastBatches`) plus the orphaned `getCachedEvaluatedAt` (only the deleted `/cache/timestamp` endpoint called it). After this commit no caller exists in the codebase — including admin features — for the Anthropic batch-cancel API; the cancel method body is gone, not just unreachable
- **Constructor dependencies pruned (the riskiest change in this commit)** — 7 of the 12 constructor params dropped: `LocationService`, `BriefingService`, `ForecastService`, `ModelSelectionService`, `JobRunService`, `ForecastBatchRepository`, `AnthropicClient`. Each was confirmed to have zero remaining usage in the class before removal. Surviving deps: `CachedEvaluationRepository`, `EvaluationDeltaLogRepository`, `ObjectMapper`, `FreshnessResolver`, `StabilitySnapshotProvider`. The deletion is in a single deliberate commit so the test-class mock-list fan-out is visible in one diff
- **`BriefingEvaluationServiceTest`** — rewritten from 1643 lines (≈60 SSE-based tests) to 478 lines (23 batch-path tests). Every surviving test exercises a surviving service method: `writeFromBatch` (replace semantics, DB persistence, upsert preserves `evaluatedAt`, multi-result serialisation, DB-failure resilience), `hasEvaluation` / `getCachedScores`, `onBriefingRefreshed` (cache retention), `clearCache` (idempotency, count, DB clear), and `rehydrateCacheOnStartup` (today-or-future filter, rating clamp, corrupt-row resilience, multi-entry round-trip). No SSE-only behaviour assertions remain
- **`BriefingEvaluationServiceCacheFreshnessTest`** + **`EvaluationDeltaLogTest`** — updated to the pruned constructor signature. Test bodies unchanged
- **`AbstractControllerTest`** — unaffected because it uses `@MockitoBean`, which never calls the real constructor
- **Acceptance criterion 1 (final state)**: `grep -rn "batches().cancel" backend/src/main` returns zero hits. Nothing in the codebase can cancel an in-flight FORECAST batch via the Anthropic API, by deliberate intent or otherwise. The "100% reliable batch" objective the retirement was framed around is satisfied
- Verification: 3626 backend tests pass; the 2 Docker-dependent integration tests (`IntegrationTestBaseSmokeTest`, `ForecastBatchPipelineIntegrationTest`) fail on environmental Docker unavailability — confirmed pre-existing across commit 1, 2 and 3 HEADs by stash-and-rerun
- Frontend untouched in this commit

### Removed — Backend SSE endpoints + cancel invocation (Pass 3.3.3, commit 2)
- **`BriefingEvaluationController`** — deleted the SSE evaluation endpoint (`GET /api/briefing/evaluate`, the `SseEmitter` producer), `GET /api/briefing/evaluate/cache` (per-region cache read), and `GET /api/briefing/evaluate/cache/timestamp` (formatted-time read). All three had no surviving callers after commit 1. Pruned the controller's now-unused `forecastExecutor` constructor injection and the `SseEmitter` / `CompletableFuture` / `BriefingEvaluationResult` imports. `GET /scores` and `DELETE /cache` survive — they're the merged-view endpoint (consumed by Plan + Map tabs) and the admin clear-cache escape hatch
- **`BriefingEvaluationService.evaluateRegion`** — removed the `cancelOutstandingForecastBatches()` invocation at line 152. The method body still exists (commit 3 deletes it) but is no longer reachable from any path. **Acceptance criterion 1 is now satisfied**: nothing in the codebase can cancel an in-flight FORECAST batch via the Anthropic API without deliberate intent, because the one and only caller is gone
- **`BriefingEvaluationControllerTest`** — replaced 13 SSE/cache-endpoint tests with the 3 surviving DELETE `/cache` tests (admin allowed + count, PRO_USER 403, unauthenticated 401). Coverage for `/scores` lives elsewhere
- **`BriefingEvaluationServiceTest`** — deleted the 7 `cancelOutstandingForecastBatches_*` test cases that asserted cancel was called as a side-effect of `evaluateRegion` (lines 914–1015) plus `cancel_twoForecastBatches_bothCancelled` and `evaluateRegion_fullCacheHit_stillCancelsBatch` (lines 1084–1135). Once the side-effect was removed they had no subject; the cancel method itself dies in commit 3. Pruned now-unused imports: `MessageService`, `BatchService`, `ForecastBatchEntity`, `BatchType`, `doThrow`
- Verification: 3658 backend tests pass; the 2 failures are testcontainer integration tests (`IntegrationTestBaseSmokeTest`, `ForecastBatchPipelineIntegrationTest`) that need Docker, which isn't running on this dev machine — confirmed pre-existing by stashing and re-running on commit 1's HEAD
- Frontend untouched in this commit

### Removed — Frontend SSE evaluation consumer (Pass 3.3.3, commit 1)
- **`frontend/src/api/briefingEvaluationApi.js`** — removed `subscribeToBriefingEvaluation` (EventSource consumer for `/api/briefing/evaluate`) and the dead `getCachedEvaluationScores` REST helper. The file now exports only `getAllEvaluationScores`, which the Plan and Map tabs use to hydrate batch-written scores on mount
- **`DailyBriefing.jsx`** — removed `handleRunEvaluation` / `handleStopEvaluation` callbacks, the `evaluationProgress` / `evaluationTimestamps` state, the `evalCleanupRef` cleanup, the `getAvailableModels` cost-estimate fetch, and the `activeModelName` state. Renamed the duplicated `canRunEvaluation` / `canSeeBestBets` locals to a single `isPro` flag (they were identical role checks). The `evaluationScores` Map survives, populated only by `getAllEvaluationScores()` on mount
- **`HeatmapGrid.jsx`** — deleted the per-region "Run full forecast" button, its LITE-greyed variant with the Pro pill, the confirmation dialog with cost estimate, the running / error / complete progress UI, and the timestamp display on the mean-score badge. Dropped now-unused imports: `useConfirmDialog`, `ProPill`, `COST_PENCE_PER_CALL`. The drill-down panel still shows location slots with their batch-written ratings; on-demand re-evaluation is no longer available
- **`DailyBriefing.test.jsx`** — deleted the `Evaluation confirmation dialog` describe block (6 tests covering the SSE button, dialog, and subscription); dropped the SSE-related `vi.fn()` mocks
- Motivation: removing the frontend SSE consumer first lets commit 2 delete the backend endpoints + `cancelOutstandingForecastBatches()` invocation without breaking the running app. The cancel call is the only path by which a user interaction can kill an in-flight `FORECAST` batch via the Anthropic API — retiring SSE before intraday refresh ships removes the cost coupling
- Investigation: `docs/engineering/sse-retirement-investigation.md` (untracked at time of this commit; mapped the SSE-ONLY vs SHARED boundary across `BriefingEvaluationService`'s 663 lines, the 7 SSE-only methods, the 7 constructor dependencies that become orphans, the dead REST companions, and the deletion sequence this prompt implements)
- Tests: 1535 frontend tests pass; backend untouched in this commit

### Fixed — Map star-threshold filter hid briefing-rated locations
- **`MapView.getRatingForLocation`** — now consults `briefingScores` before falling back to `forecastsByDate.[type].rating`, matching the precedence the marker render path has used all along (line ~901, `briefingScore?.rating ?? forecast?.rating`). Before this fix, a location whose rating came only via `cached_evaluation` (i.e. the Plan tab's briefing enrichment) would render a 3★ medallion on the marker but get filtered out by the star-threshold filter — `getRatingForLocation` returned `null`, the filter routed it to the `showUnrated` branch (default off), and the marker disappeared. Visible bug: selecting 3★ on a Sunset map with a 3★ briefing-only location (e.g. College Valley) emptied the map
- Test: 1 new `MapViewStarFilter.test.jsx` regression case (`briefingScore.rating survives the star-threshold filter`)

### Fixed — Map tab missing future-date forecasts that Plan tab shows
- **`ForecastController.getForecasts`** — merge cached-only rows so the Map date strip surfaces future dates that the batch pipeline has scored but not yet persisted as full `forecast_evaluation` rows. Before this fix, the Map tab read only `forecast_evaluation` directly, so the date strip cut off at today while the Plan tab (which reads `cached_evaluation` via `BriefingService.enrichWithCachedScores`) correctly showed T+1 through T+3. The two views now share the same source-of-truth boundary that `EvaluationViewService`'s Javadoc has always promised
- **`ForecastDtoMapper.toSparseDto`** — new method that synthesises a sparse `ForecastEvaluationDto` from a `LocationEvaluationView` whose source is `CACHED_EVALUATION`. Atmospheric, tide, surge and inversion fields are left null (cached_evaluation doesn't carry them); solar event time + azimuth, golden/blue hour windows, lunar tide type and lunar phase are computed deterministically from the location's lat/lon so the marker and popup render sensible scaffolding
- **Merge rule** — when the same `(location, date, type)` tuple has both a `forecast_evaluation` row and a `cached_evaluation` entry, the rich row wins; the cached entry is dropped as a duplicate
- Tests: 2 new `ForecastControllerTest` cases (`getForecasts_surfacesCachedOnlyRows`, `getForecasts_prefersForecastRowOverCachedDuplicate`); `EvaluationViewService` added to `AbstractControllerTest` mock superset

### Added — SCHEDULED_BATCH summary line on metrics page (v2.11.18)
- **`BatchSummary` record** (`backend/.../model`) — new read-only enrichment carrying horizon range, event types, evaluation model, location count, region count, and extended-thinking flag for batch job runs
- **`HorizonRangeFormatter`** (`backend/.../util`) — pure utility that turns a set of day-ahead offsets into a compact label (`T`, `T+1`, `T to T+2`, `T, T+2`)
- **`BatchSummaryDeriver`** (`backend/.../service`) — derives `BatchSummary` for `SCHEDULED_BATCH` / `BATCH_NEAR_TERM` / `BATCH_FAR_TERM` rows at read time by inspecting linked `api_call_log` rows. Region count resolved by parsing the `customId` (`fc-{locationId}-…`) and joining `locations.region_id`. No schema change; works for historical batches that have logged result rows
- **`JobMetricsController.getJobRuns`** — populates a new `@Transient batchSummary` field on each row before returning. Non-batch run types are unaffected
- **`JobRunsGrid.jsx`** — renders one extra summary line for batch rows (`T+1 · SUNRISE · 29 locations · HAIKU · 7 regions`), or "No detail available" when the run has no usable call rows
- Tests: 8 `HorizonRangeFormatterTest` cases, 10 `BatchSummaryDeriverTest` cases, 2 new `JobMetricsControllerTest` cases, 6 new `JobRunsGrid.test.jsx` cases

### Fixed — V84 migration tolerant of missing locations (v2.12.2)
- **`V84__add_bluebell_support.sql`** — replaced two `INSERT INTO location_location_type (location_id, location_type) VALUES (40/87, 'BLUEBELL')` statements with `INSERT…SELECT…WHERE EXISTS` against `locations.id`. The original hardcoded IDs 40 (Allen Banks) and 87 (Roseberry Topping) work in production where those rows exist from accumulated seed data, but fail the V8 foreign key against any fresh database — including the Postgres testcontainer introduced in v2.12.2's integration test pyramid. The bug was latent because existing `@SpringBootTest` tests use H2 with `ddl-auto=create-drop` and skip Flyway entirely. Production behaviour is unchanged: where IDs 40 and 87 exist, the new statement produces identical rows. The `NOT EXISTS` predicate also guards against accidental replay
- **`V84__add_bluebell_support.sql`** — additionally added a section-0 backfill seeding regions `id=6 Teesdale` and `id=7 The North York Moors` with `ON CONFLICT (id) DO NOTHING`. V31 only seeds regions 1–5 via Flyway; regions 6 and 7 were added later in production via the API and never made it into a migration. V84's downstream location inserts reference `region_id=6` and `region_id=7`, which failed the `locations.region_id` FK on fresh databases. Production behaviour is unchanged (rows already exist, INSERT is a no-op). A `setval` on the regions identity sequence prevents future name-only INSERTs from colliding with the explicitly-seeded IDs
- Note: production Flyway has `validate-on-migrate: false`, so the changed migration checksum won't block redeploy. No equivalent latent FK bugs found in other migrations (V60 has many hardcoded `UPDATE…WHERE id = N` statements but UPDATE on a non-existent row is a clean no-op, not a constraint violation)

### Added — Test dependencies for integration testing pyramid (v2.12.2 prep)
- **Testcontainers** (`spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql`) — versions managed by Spring Boot 4.0.5 testcontainers-bom (2.0.4); enables Postgres-backed integration tests that run real Flyway migrations, matching production exactly instead of relying on H2 with `ddl-auto=create-drop`
- **WireMock** (`org.wiremock:wiremock-standalone:3.10.0`) — stubs Anthropic API endpoints at the HTTP boundary so the full forecast batch pipeline (BatchRequestFactory → BatchSubmissionService → BatchPollingService → BatchResultProcessor → cache write) can be exercised without burning API cost; standalone variant chosen to avoid classpath conflicts with Spring's Jetty/Jackson
- **Awaitility** — version managed by Spring Boot (4.3.0); replaces `Thread.sleep` for condition-based polling in async integration tests

### Added — Integration test base class with testcontainers Postgres (v2.12.2)
- **`IntegrationTestBase`** — `@SpringBootTest` base for tests that need a production-shaped schema. Spins up a `postgres:17-alpine` container (same image as `docker-compose.yml` production), enables Flyway, switches Hibernate to `ddl-auto=validate`, registers a WireMock JUnit 5 extension on a dynamic port, and exposes its URL as `photocast.test.anthropic-base-url` via `@DynamicPropertySource`
- **`WireMockAnthropicClientTestConfiguration`** — `@TestConfiguration` that supplies a `@Primary` `AnthropicClient` bean built with `AnthropicBackend.builder().baseUrl(wiremockUrl)`, routing all SDK calls (single message and batch) at WireMock without changing production code
- **`DynamicSchedulerBootstrap`** — extracted the `@EventListener(ApplicationReadyEvent.class)` listener out of `DynamicSchedulerService` into its own `@Component` annotated `@Profile("!integration-test")`. The split keeps `DynamicSchedulerService.registerJobTarget()` available to all profiles (so batch services can still register their job targets) while the cron firing itself is suppressed under the `integration-test` profile
- **`IntegrationTestBaseSmokeTest`** — four-assertion smoke test proving the base works: Postgres reachable on v17, V68 seed rows present (Flyway ran), `DynamicSchedulerBootstrap` absent from context (profile gate works), Anthropic client routed to WireMock (SDK base URL override works)
- **`AnthropicWireMockFixtures`** — stub builders for `POST /v1/messages/batches`, `GET /v1/messages/batches/{id}`, and `GET /v1/messages/batches/{id}/results`, plus `BatchResultFixture` records with success and error factory methods that render JSONL stream lines matching the SDK's deserialiser shapes
- **`ForecastBatchPipelineIntegrationTest`** — four-test class (happy path + errored-only + submission failure + multi-region split + mixed batch) exercising the full `BatchSubmissionService → BatchPollingService → BatchResultProcessor → cache write` chain via WireMock-stubbed Anthropic endpoints. Asserts `cached_evaluation` JSON contents, `api_call_log` per-row fields (custom_id, target_date, target_type, error_type, token counts), and `forecast_batch` lifecycle transitions
- **`ForecastBatchPipelineRealApiE2ETest`** — auto-skipped without `ANTHROPIC_API_KEY`; with the key, submits one Haiku request through the production primitives against the real Anthropic API, polls with Awaitility (4-min cap, 5-min `@Timeout`), and asserts the same end-state invariants as the WireMock test. Companion to `BatchSchemaIntegrationTest` from `e91376f` — the schema test is the narrow fast-fail guard, this is the broad full-pipeline contract test
- **`ForecastBatchRepository.findByAnthropicBatchId`** — small additive Spring Data finder that the integration tests use to look up the batch row for assertions; not yet used by production code

### Changed — CI workflow gains a real-API E2E gate (v2.12.2)
- **`.github/workflows/ci.yml`** — new `e2e-real-api` job runs on `main` pushes only (not PRs), depends on the standard `backend` job, sets `ANTHROPIC_API_KEY` from repo secrets, and runs `mvn verify -Dtest='BatchSchemaIntegrationTest,*RealApiE2ETest' -Dsurefire.skipAfterFailureCount=1`. Schema test runs first by alphabetical order; if it fails, the broader pipeline test is skipped to avoid a second wasted Anthropic call. Cost per main push: ~2 Haiku batch inferences (sub-pence each). Prerequisite: `ANTHROPIC_API_KEY` must be set as a repository secret

### Added — Batch observability: per-request api_call_log persistence
- **`api_call_log` persistence for batch results** — every request in a completed Anthropic batch (success or failure) now writes a row to `api_call_log` with `is_batch=true`, `custom_id`, `batch_id`, `error_type`, token counts, and decoded `target_date`/`target_type`. Turns ephemeral log output into durable, queryable forensic data that survives log rotation.
- **`describeFailedResult()` NPE guard** — Anthropic SDK errored results with a null error chain no longer abort the entire processing loop; the error is caught, logged as "unknown", and processing continues to the next request
- **V99 migration** — adds `custom_id` (VARCHAR 64), `error_type` (VARCHAR 100), `batch_id` (VARCHAR 100) columns to `api_call_log`; widens `error_message` from VARCHAR(500) to TEXT; adds partial indexes on `(is_batch, called_at)` and `(custom_id)`
- **`JobRunService.logBatchResult()`** — new method for persisting batch result rows with token-based cost calculation; all DB writes are try/catch-guarded — persistence failures never break batch processing
- **Tests** — 6 new tests: succeeded/errored result persistence, persistence failure resilience, no-jobRunId skip, parse failure persistence, describeFailedResult NPE guard

### Changed — Stability-driven cache freshness for overnight batch

- **`FreshnessProperties`** — new `@ConfigurationProperties("photocast.freshness")` class with per-stability thresholds: SETTLED=36h (blocking persistence), TRANSITIONAL=12h (half synoptic cycle), UNSETTLED=4h (nowcasting window), safety floor=2h
- **`FreshnessResolver`** — shared primitive resolving `ForecastStability` → `Duration` maxAge, used by both the overnight batch CACHED gate and (future) intraday refresh; applies safety floor; logs effective thresholds at startup
- **`ScheduledBatchEvaluationService`** — CACHED gate now uses stability-driven freshness instead of flat 18h; looks up most volatile stability per region from the latest snapshot; DIAG output enriched with stability level, threshold, and per-stability breakdown summary
- **`BriefingEvaluationService`** — `writeFromBatch()` logs rating deltas to `evaluation_delta_log` for empirical threshold refinement; delta logging is try/catch-guarded — failures never break the cache write path
- **V97 migration** — `evaluation_delta_log` table with stability/age/rating-delta columns and an index on `(stability_level, age_hours)` for post-deploy analysis
- **Config** — removed `photocast.batch.cached-gate-freshness-hours`; replaced with `photocast.freshness.{settled,transitional,unsettled,safety-floor}-hours`
- **Tests** — 19 new tests: `FreshnessResolverTest` (7: per-stability returns, safety floor enforcement), `CollectForecastTasksCachedGateTest` rewritten (8: SETTLED/TRANSITIONAL/UNSETTLED skip/refresh, no-snapshot fallback), `EvaluationDeltaLogTest` (3: delta insert, no-prior-entry skip, failure resilience); existing `ScheduledBatchEvaluationServiceTest` updated

### Fixed — Unified score reads for Plan and Map tabs
- **Bug A**: Map tab didn't render batch-evaluated scores — it only read `forecast_evaluation`, while batch results live in `cached_evaluation`
- **Bug B**: Sunset toggle disabled for dates where only `cached_evaluation` had data — availability check only looked at `forecastsByDate`
- **`EvaluationViewService`** — new merge layer combining both data sources with clear precedence: cached evaluation > scored forecast row > triaged forecast row > none
- **`LocationEvaluationView`** — new record carrying merged evaluation state (rating, summary, fiery/golden scores, triage fields, source indicator)
- **`BriefingEvaluationController`** — new `GET /api/briefing/evaluate/scores` endpoint returning all merged scores for the Map tab
- **`BriefingService`** — `enrichWithCachedScores()` now delegates to `EvaluationViewService.getScoresForEnrichment()`, supplementing cache hits with `forecast_evaluation` fallback
- **Frontend** — `DailyBriefing` hydrates `briefingScores` from the new endpoint on mount; `MapView` sunset toggle checks both `forecastsByDate` and `briefingScores`
- **Tests** — `EvaluationViewServiceTest` (10 tests: merge precedence, mixed states, enrichment delegate); `MapViewSunsetToggle.test.jsx` (2 tests: toggle enabled from briefingScores, disabled when empty)

### Fixed — Map popover shows triage reason for briefing-cache stand-downs
- **`MarkerPopupContent`** — new `briefingScore` prop; when `forecast` is null but `briefingScore.triageReason` is set, renders the stand-down badge with `triageMessage` instead of the "no forecast yet" divider + Run Forecast button; marker was already rendering the dashed medallion correctly, only the popover body was wrong
- **`StandDownBadge`** — extracted shared helper; reused by both the scored-forecast triage branch and the new briefing-cache triage branch (removes duplicated dark-red styling)
- **`MapView`** — passes computed `briefingScore` to `MarkerPopupContent` at both call sites (desktop popup and mobile bottom sheet)
- **Tests** — 11 new tests across 2 files. `MarkerPopupContent.test.jsx` (8): triage message rendered when `briefingScore.triageReason` set, no Run Forecast button in that state, empty-state branch unchanged when `briefingScore` null, location header + region sub-row preserved, parameterised label-map check across all 5 `TriageReason` values, no message row when `triageMessage` is null, scored `briefingScore` (no `triageReason`) still falls through to empty state, forecast branch takes precedence over `briefingScore.triageReason`. New `MapViewBriefingScoreWiring.test.jsx` (3): verifies MapView looks up and passes the exact score object reference, passes null when no entry matches, and hands the same object identity to both the cache lookup and the popup prop

### Changed — Also Good surfaces a genuinely distinct opportunity
- **`BestBet`** — new `relationship` (SAME_SLOT / DIFFERENT_SLOT) and `differsBy` (DATE / EVENT / REGION) fields distinguish tier-1 same-slot backups from tier-2 different-slot alternatives
- **`BriefingBestBetAdvisor` system prompt** — tiered "Also Good Selection Rule": Tier 1 picks same-slot region within 0.5 rating and ≥3.5 absolute; Tier 2 picks the best opportunity on a different date/event; no rank 2 emitted when neither tier clears threshold
- **`parseBestBets()`** — parses `relationship` and `differsBy` from Claude response; unrecognised values silently dropped
- **Frontend** — `BestBetBanner` PropTypes extended with `relationship` and `differsBy`; card labels already derive from each pick's own `dayName`/`eventType`/`eventTime` so tier-2 picks naturally show different dates/events

### Added — Drill-down Claude scores in briefing
- **`BriefingSlot`** — 4 new nullable fields: `claudeRating`, `fierySkyPotential`, `goldenHourPotential`, `claudeSummary`; convenience constructor for backward compatibility; `withClaudeScores()` copy method
- **`BriefingService`** — new `@Lazy BriefingEvaluationService` dependency; `enrichWithCachedScores()` walks the day/event/region hierarchy after `buildDays()` and populates each slot's Claude fields from the evaluation cache
- **Frontend drill-down** — `LocationSlotList` in `HeatmapGrid.jsx` merges backend-cached and SSE scores; collapsed rows show first-sentence preview (100 chars); per-row expand/collapse with full summary + Fiery Sky / Golden Hour secondary scores; `HeatmapCell` falls back to backend-cached scores for mean badge

### Fixed — Sonnet token exhaustion and schema compliance
- **`EvaluationModel.getMaxTokens()`** — Sonnet/Sonnet ET now get 1024 output tokens; all other models stay at 512; used in both `ScheduledBatchEvaluationService.buildForecastRequest()` and `ClaudeEvaluationStrategy.invokeClaude()`
- **`PromptBuilder.SYSTEM_PROMPT`** — added `CRITICAL OUTPUT FORMAT RULES` block: first char must be `{`, no reasoning/markdown in output, all 4 required fields mandatory; prevents chain-of-thought leakage into JSON values

### Fixed — Open-Meteo zero-task parse failure
- **`OpenMeteoService.prefetchWeatherBatch()`** — early return with empty map when `coords` list is empty; prevents pointless API call that fails to parse the response

### Added — Briefing gloss and best-bet read real Claude scores
- **`BriefingBestBetAdvisor`** — now consumes cached Claude evaluation scores from `BriefingEvaluationService`; when per-location drill-down scores exist, adds `claudeRatedCount`, `claudeHighRatedCount`, `claudeMediumRatedCount`, and `claudeAverageRating` fields to the rollup JSON sent to Claude; system prompt updated with `CLAUDE EVALUATION SCORES` guidance so the model prefers nuanced scores over triage verdicts; per-date cache coverage logging
- **`BriefingGlossService`** — same cache lookup; appends Claude score distribution to each gloss user message; system prompt updated with `CLAUDE SCORES` calibration guidance; work-item-level cache coverage logging
- **Circular dependency** — `@Lazy` on the `BriefingEvaluationService` constructor parameter in both services breaks the `BriefingService` → `BriefingBestBetAdvisor` → `BriefingEvaluationService` → `BriefingService` cycle
- **Tests** — 6 new tests in `BriefingBestBetAdvisorTest` (cached scores added, omitted when empty, triaged entries filtered, all-triaged omitted, exact cache key verification, system prompt check); 5 new tests in `BriefingGlossServiceTest` (same patterns); regression test updated

### Fixed — Aurora hot topic pill uses consistent clear count
- **`AuroraHotTopicStrategy`** — tonight detail line now reads the cloud-triaged clear count from `BriefingAuroraSummaryBuilder.buildAuroraTonightCached()` (fresh Open-Meteo weather) instead of `AuroraStateCache.getClearLocationCount()` (stale polling-cycle count); falls back to state cache when briefing summary is unavailable
- **Tests** — 2 new tests: briefing summary count preferred when available, state cache fallback when null (32 total)

### Fixed — Persistent logs across deploys
- **Rolling application log** — added `FILE` appender to `logback-spring.xml` writing `goldenhour.log` with size+time rolling (50MB max file, 30 days, 1GB total cap); attached to root logger alongside console
- **Volume mount** — `docker-compose.yml` mounts `/Users/gregochr/goldenhour-data/logs` to `/app/logs` so both `goldenhour.log` and `surge-calibration.log` survive container recreation
- **Deploy workflow** — `mkdir -p` for log directory added to GitHub Actions deploy step before `docker compose up`
- **Docker log rotation** — `json-file` driver with 10MB×3 cap on backend and database services to prevent unbounded Docker stdout logs

### Added — Add Location enrichment
- **`LocationEnrichmentService`** — new service that auto-detects bortle class, sky brightness (SQM), elevation, and Open-Meteo grid cell coordinates via three parallel API calls (lightpollutionmap.info, Open-Meteo elevation, Open-Meteo forecast); each source fails independently, returning null for failed fields
- **`GET /api/locations/enrich`** — new ADMIN-only endpoint on `LocationController` that returns a `LocationEnrichmentResult` record for a given lat/lon
- **`AddLocationRequest` extended** — 7 new fields: `bortleClass`, `skyBrightnessSqm`, `elevationMetres`, `gridLat`, `gridLng`, `overlooksWater`, `coastalTidal`; `LocationService.add()` persists all enrichment fields on the entity
- **Frontend enrichment panel** — after geocoding, the Add Location form shows an "Auto-detected" panel (elevation, bortle, SQM, grid cell) and a "Manual" panel (overlooks water / coastal tidal checkboxes); enrichment data and manual toggles flow through the confirm modal into the save request
- **Tests** — `LocationEnrichmentServiceTest` (19 tests with exact matchers, verify calls, edge cases), enrichment tests in `LocationControllerTest`, `LocationServiceTest` (swap-killing boolean tests), and `LocationManagementView.test.jsx` (7 enrichment tests)

### Added — Light pollution job API call logging
- **`LIGHT_POLLUTION` service name** — added to `ServiceName` enum; `CostCalculator` handles it with zero cost (free API) in both modern micro-dollar and legacy pence switches
- **API call logging for Bortle enrichment** — `BortleEnrichmentService.enrichAll()` now records each `lightpollutionmap.info` call via `jobRunService.logApiCall()` with per-location URL (lon,lat), duration, HTTP status, and success/error flag; the LIGHT_POLLUTION job run panel now shows individual API calls instead of "No API calls recorded"
- **Mutation-killing test coverage** — `BortleEnrichmentServiceTest` verifies exact `logApiCall` arguments (service name, method, URL coordinate order, status code, succeeded flag, error message); `CostCalculatorTest` covers zero-cost entries for `LIGHT_POLLUTION`

### Added — Aurora heatmap grid integration + cloud inversion scoring
- **Aurora grid columns** — aurora promoted from separate banner row to grid columns in Plan tab heatmap with proper day-spanning; aurora data renders alongside sunrise/sunset in the same visual grid
- **Cloud inversion scoring** — `InversionScoreCalculator` produces 0–10 likelihood score from temperature-dew gap, wind speed, humidity, and low cloud; location `elevation_m` and `overlooks_water` metadata (V65); `inversion_score` + `inversion_potential` columns on forecast_evaluation (V66); integrated into PromptBuilder for valley/lake locations
- **Astro conditions API** — `AstroConditionsService` template-scores nightly observing quality for dark-sky locations (cloud cover, visibility, moonlight modifiers); `AstroConditionsController` with `GET /api/astro/conditions` and `GET /api/astro/conditions/available-dates`; `astro_conditions` table (V64)
- **Aurora viewline endpoint** — `GET /api/aurora/viewline` returns OVATION nowcast southernmost visibility boundary; `AuroraViewlineOverlay` with colour-coded zones (green ≤55°N, amber 55–58°N, grey >58°N)
- **Storm surge calculation** — `StormSurgeService` (inverse barometer effect + wind setup) for coastal tidal locations; coastal parameters on locations (V60); surge forecast columns on forecast_evaluation (V61); integrated into forecast pipeline and prompt
- **Lunar tide classification** — spring/king tides derived from lunar cycle (`TideClassificationService`) integrated into PromptBuilder and BriefingBestBetAdvisor; replaces statistical-only thresholds
- **User settings** — `UserSettingsService` + `UserSettingsController` for home location (postcode via `PostcodesIoClient` geocoding, lat/lon) and per-user drive times; `user_drive_time` table (V67); `UserSettingsModal` frontend component; `DriveTimeResolver` abstraction replaces per-location `drive_duration_minutes`
- **Briefing model comparison** — `BriefingModelTestService` calls Haiku/Sonnet/Opus with same rollup; `briefing_model_test_run` + `briefing_model_test_result` tables (V63); `BriefingModelTestView` with agreement highlighting
- **Light pollution API update** — sb_2025 dataset with SQM conversion; `sky_brightness_sqm` column (V62)
- **Quality slider** — heatmap cell visibility tier filtering in Plan tab (`QualitySlider` component)

### Changed
- Drive times moved from per-location (`drive_duration_minutes` on `LocationEntity`) to per-user (`user_drive_time` table); `DriveDurationService` refactored to use `DriveTimeResolver`; `BriefingBestBetAdvisor` simplified to use per-user drive times
- Briefing schedule changed from every 2 hours to 04:00/14:00/22:00; model switched to Opus
- Briefing triage tightened with mid-cloud blanket and building trend checks
- Aurora viewline threshold raised to 10%
- Aurora response matching switched from array index to location name
- Quality slider direction reversed (left=worst, right=best)
- Dark sky chip admin tooltip gated to ADMIN role only
- Night qualifier required when best bet mentions aurora

### Fixed
- NOAA SWPC Kp endpoint format change (array-of-arrays to object)
- Light pollution API content type handling (text/plain, bare number responses)
- PostgreSQL-compatible syntax in V62–V63 migrations
- SSE `IllegalStateException` in `sendSafe` during briefing evaluation
- Health indicator shows red DOWN when backend is unreachable
- Auto-reconnect SSE stream after backend restart
- Docker logs directory created for surge calibration appender

### Database
- V59: `daily_briefing_cache` table
- V60: Storm surge coastal parameters on locations
- V61: Storm surge forecast columns on forecast_evaluation
- V62: `sky_brightness_sqm` column on locations
- V63: `briefing_model_test_run` + `briefing_model_test_result` tables
- V64: `astro_conditions` table
- V65: `elevation_m` and `overlooks_water` columns on locations
- V66: `inversion_score` and `inversion_potential` columns on forecast_evaluation
- V67: User home location + `user_drive_time` table

---

### Added — Briefing evaluation via SSE (Claude scoring from Plan tab)

Wire the "Run full forecast" button in the Plan tab's heatmap drill-down to trigger Claude evaluations for GO/MARGINAL locations, streaming results back via SSE, and propagating scores to the grid cells and map pins.

**Backend:**
- `BriefingEvaluationService` — orchestrates per-region Claude evaluations with `ConcurrentHashMap` cache; filters to GO/MARGINAL slots only; cache cleared via `BriefingRefreshedEvent`
- `BriefingEvaluationController` — SSE endpoint (`GET /api/briefing/evaluate`) streams `location-scored`/`progress`/`evaluation-complete` events; cache endpoint (`GET /api/briefing/evaluate/cache`) returns cached scores; gated to ADMIN/PRO_USER
- `BriefingEvaluationResult` record, `BriefingRefreshedEvent` application event
- `BriefingService` publishes `BriefingRefreshedEvent` after each refresh
- `JwtAuthenticationFilter` — added `/api/briefing/evaluate` to SSE query-param auth paths

**Frontend:**
- `briefingEvaluationApi.js` — EventSource subscription + REST cache fetch
- `DailyBriefing.jsx` — evaluation state, SSE subscription lifecycle, score lift to parent
- `HeatmapGrid.jsx` — stateful "Run full forecast" button (ready/running/complete/error), score badges on location rows with re-sort by rating, mean score pill on heatmap cells
- `App.jsx` — lifts `briefingScores` state to pass from DailyBriefing → MapView
- `MapView.jsx` — overrides forecast scores with briefing evaluation scores when available

### Changed — SSE health status stream

Replaced polling-based `/actuator/health` approach with a Server-Sent Events endpoint (`GET /api/status/stream`) that pushes enriched status every 30 seconds. The frontend `useHealthStatus` hook now uses native `EventSource` with automatic reconnection.

**Backend:**
- `StatusController` — new SSE endpoint using `SseEmitter` with 30-second push interval; assembles `StatusResponse` from `HealthEndpoint` + `GitProperties`
- `StatusResponse` — rich status record: overall status, degraded components, DB health, per-service circuit breaker statuses (with detail like CB state), build/git info, session info
- Soft components (mail) trigger DEGRADED not DOWN; ignored components (rateLimiters) are excluded
- `JwtAuthenticationFilter` — added `/api/status/stream` to SSE query-param token auth paths

**Frontend:**
- `useHealthStatus` — rewritten from `setInterval`/axios polling to `EventSource` SSE consumer
- `HealthIndicator` — enriched tooltip now shows build info (commit, branch, dirty) and service statuses with circuit breaker detail
- `healthApi.js` — orphaned (no longer imported); can be removed

### Changed — Structured best bet banners

Best bet pick banners now display day, event type, time, and drive distance as a structured header line derived server-side from triage data, instead of burying them in Claude's narrative text. Claude's headline and detail now focus on the "why" (conditions, special features) rather than repeating when/where.

**Backend:**
- `BestBet` record — added `dayName`, `eventType`, `eventTime` fields derived from triage data
- `BriefingBestBetAdvisor.enrichWithEventData()` — new enrichment step that parses event IDs, resolves day names ("Today"/"Tomorrow"/weekday), and looks up UK-local event times from the slot hierarchy
- System prompt updated to instruct Claude not to repeat structured fields in headline/detail

**Frontend:**
- `BestBetBanner` — renders structured header line (`Wednesday sunset · 18:48 · 37 min drive`) between rank label and Claude's headline
- Drive time omitted when unavailable; entire structured line omitted for stay-home/aurora picks

### Refactored — Briefing subsystem code quality

Seven targeted refactorings across the briefing pipeline:

1. **BriefingHeadlineGenerator** — extracted shared `appendVerdictCounts()` helper from near-identical `buildVerdictBreakdown()` and `buildNonGoSuffix()`
2. **BriefingVerdictEvaluator** — introduced `WeatherMetrics` and `TideContext` records, reducing `buildFlags()` from 9 positional parameters to 2 named record arguments
3. **BriefingAuroraSummaryBuilder** — extracted `CLEAR_SKY_THRESHOLD` constant (75%), replaced `.mapToInt(s -> 1).sum()` with `.count()`
4. **BriefingSlotBuilder** — extracted 30-line tide calculation into `calculateTideData()` with `TideResult` record, shrinking `buildSlot()` from 95 to 60 lines
5. **BriefingSlot** — split 18-field flat record into `WeatherConditions` + `TideInfo` sub-records with `@JsonUnwrapped` to preserve the flat JSON contract
6. **BestBet** — converted `confidence` from raw String to `Confidence` enum (`HIGH`/`MEDIUM`/`LOW`) with `@JsonValue` for backward-compatible lowercase serialization
7. **BriefingHierarchyBuilder + BriefingAuroraSummaryBuilder** — extracted shared `RegionGroupingUtils.groupByRegion()` utility replacing duplicated `LinkedHashMap + computeIfAbsent` loops

### Added — Daily Briefing ("Go or Movie Night?")

Zero-Claude-cost pre-flight check that runs every 2 hours, fetching live Open-Meteo weather and existing DB tide data for all enabled colour locations, then rolling results up by region per solar event (today + tomorrow, sunrise + sunset).

**Backend:**
- `Verdict` enum (GO / MARGINAL / STANDDOWN) — aligned with WeatherTriageEvaluator thresholds
- `BriefingSlot`, `BriefingRegion`, `BriefingEventSummary`, `BriefingDay`, `DailyBriefingResponse` records — hierarchical briefing model
- `BriefingService` — orchestrates weather fetch (parallel via virtual threads), tide DB lookup, verdict logic, region rollup, headline generation; stores result in `AtomicReference` cache
- `BriefingController` — `GET /api/briefing` (Bearer, all roles) serves cached result; 204 when cache empty
- `RunType.BRIEFING` — new enum value for job run tracking
- `ScheduledForecastService.refreshDailyBriefing()` — `@Scheduled` every 2 hours

**Frontend:**
- `briefingApi.js` — Axios wrapper for `GET /api/briefing`
- `DailyBriefing.jsx` — collapsible card above the map: headline + freshness in collapsed state; per-day sunrise/sunset sections with region rows (verdict pill + summary + tide highlights) and expandable location slot detail
- `App.jsx` — renders `<DailyBriefing />` above DateStrip in map view
- `JobRunsMetricsView.jsx` — BRIEFING run type in filter dropdown; "Show briefing runs" checkbox (hidden by default)

**Tests:**
- `BriefingServiceTest` (18 tests) — verdict logic, region rollup, tide classification, headline generation, colour location filter, cache behaviour
- `BriefingControllerTest` (5 tests) — HTTP status, auth, JSON structure, 204 when cache empty
- `DailyBriefing.test.jsx` (12 tests) — rendering, expand/collapse, region cards, verdict badges, flags, tide highlights, unregioned slots

### Added — Aurora simulation mode (admin-only)

Allows the admin to inject fake NOAA space weather data to test the full aurora UI flow (banner, forecast runs, night selector) without a real geomagnetic storm. No Claude API calls are made on activation — the admin controls spend via the existing Forecast Run flow.

**Backend:**
- `AuroraStateCache` — `SimulatedNoaaData` inner record; `simulated` + `simulatedData` volatile fields; `activateSimulation(AlertLevel, SimulatedNoaaData)` method; `isSimulated()` / `getSimulatedData()` getters; `reset()` clears simulation flag
- `AuroraSimulationRequest` — new record (kp, ovationProbability, bzNanoTesla, gScale)
- `AuroraSimulationResponse` — new record (level, message, eligibleLocations)
- `AuroraStatusResponse` — new `simulated` boolean field
- `AuroraForecastPreview` — new `simulated` boolean field propagated to the night selector
- `AuroraController.getStatus()` — when simulated, returns fake Kp/OVATION/Bz values directly from the state cache; skips live NOAA fetch; sets `simulated: true` in the response
- `AuroraAdminController` — two new ADMIN-only endpoints: `POST /api/aurora/admin/simulate` (activates simulation) and `POST /api/aurora/admin/simulate/clear` (calls `reset()`); injected `LocationRepository` to count eligible locations
- `AuroraForecastRunService` — injected `AuroraStateCache`; `getPreview()` substitutes simulated Kp for all 3 nights when simulation is active; `runForecast()` builds synthetic `SpaceWeatherData` from simulated values instead of calling NOAA; helper methods `buildSimulatedKpForecast()` (72 h of windows at fixed Kp) and `buildSimulatedSpaceWeather()` (KpReading, SolarWindReading, OvationReading, optional G-scale alert)

**Frontend:**
- `auroraApi.js` — `simulateAurora(request)` and `clearSimulation()` functions
- `AuroraSimulateModal.jsx` — new admin-only modal with Kp/OVATION/Bz/G-Scale form fields, three preset buttons (Moderate G2, Strong G3, Extreme G5), disclaimer, and optional "Clear Simulation" button when a simulation is active
- `AuroraBanner.jsx` — when `status.simulated === true`: hatched background + dashed border; 🧪 icon instead of 🌌; "SIMULATED —" prefix; kpText appended with "(SIMULATED)"; click navigates to Manage tab (not map); Bz pulse suppressed
- `AuroraForecastModal.jsx` — per-night 🧪 SIM badge when `preview.simulated === true`; amber warning banner below nights explaining real vs fake data
- `JobRunsMetricsView.jsx` — new "🧪 Simulate" button beside Aurora Forecast button; shows amber "🧪 Simulated" label when a simulation is active; opens `AuroraSimulateModal`; imports `useAuroraStatus` hook to detect live simulation state

**Tests (backend):**
- `AuroraStateCacheTest` — covered by existing reset/lifecycle tests (no new tests needed; `activateSimulation` path exercised by admin controller tests)
- `AuroraAdminControllerTest` — 4 new tests: simulate 403 for PRO, simulate 200 for ADMIN (activates + returns STRONG + eligibleLocations), simulate/clear 403 for PRO, simulate/clear 200 for ADMIN
- `AuroraControllerTest` — 2 new tests: simulated status returns fake Kp/OVATION/Bz + `simulated: true`; normal status returns `simulated: false`; added `stateCache.isSimulated()` stub to `setUp()`
- `AuroraForecastRunServiceTest` — 3 new tests: `getPreview()` uses simulated Kp (all nights Kp 7, `simulated: true`); `getPreview()` returns `simulated: false` normally; `runForecast()` bypasses `noaaClient.fetchAll()` and calls Claude with STRONG alert when simulated
- `AuroraForecastControllerTest` — updated `new AuroraForecastPreview(...)` calls to include the new `simulated` boolean parameter

**Test count:** 1048 backend (↑ from 1043) · 443 frontend (unchanged) · JaCoCo ≥80% maintained

### Fixed — Aurora banner shows trigger Kp, not current Kp

**Backend:**
- `AuroraStateCache` — new `updateTrigger(TriggerType, double kp)` method + `getLastTriggerType()` / `getLastTriggerKp()` getters; `reset()` clears trigger metadata
- `AuroraOrchestrator.scoreAndCache()` — new `triggerKp` param; calls `stateCache.updateTrigger()` on every NOTIFY. `runForecastLookahead()` passes `maxKpTonight`; `run()` passes `latestKp(spaceWeather)` (the most recent real-time Kp reading)
- `AuroraStatusResponse` — two new fields: `Double forecastKp` (the Kp that triggered the alert), `String triggerType` (`"forecast"` or `"realtime"`, null when IDLE)
- `AuroraController` — populates `forecastKp` from `stateCache.getLastTriggerKp()`, `triggerType` derived from `stateCache.getLastTriggerType()`

**Frontend:**
- `AuroraBanner` — `kpText` prefers `forecastKp` over `kp`; uses `Math.round()` (not `toFixed(1)`); appends `"forecast tonight"` suffix when `triggerType === "forecast"`. Examples: `"Kp 6 forecast tonight"` (lookahead) or `"Kp 6"` (realtime)

**Tests:**
- `AuroraStateCacheTest` — 2 new tests: `updateTrigger` stores type + kp; `reset()` clears them
- `AuroraBanner.test.jsx` — 3 new tests: forecast trigger shows suffix, realtime shows plain Kp, falls back to `kp` when `forecastKp` absent

### Added — Aurora: daytime forecast lookahead + map aurora mode

**Backend — dual-path aurora polling:**
- `AuroraPollingJob` — split into two independent paths: (1) forecast lookahead (no daylight gate, runs every poll cycle); (2) real-time check (daylight-gated, unchanged). `executePoll()` always runs forecast lookahead first, then real-time only if `!isDaylight()`. New `calculateTonightWindow()` handles daytime (today dusk → tomorrow dawn) and post-midnight (yesterday dusk → today dawn) cases. `NAUTICAL_BUFFER_MINUTES` made package-visible for tests.
- `TonightWindow` — new record (`dusk`, `dawn` as `ZonedDateTime`); `overlaps(from, to)` half-open interval check
- `TriggerType` — new enum (`FORECAST_LOOKAHEAD`, `REALTIME`) driving Claude prompt tone
- `AuroraOrchestrator.runForecastLookahead(TonightWindow)` — new method; fetches Kp forecast cheaply via `fetchKpForecast()`, checks if any window overlaps tonight and meets threshold, calls `scoreAndCache()` with `FORECAST_LOOKAHEAD` trigger type only on NOTIFY. `run()` now passes `TriggerType.REALTIME, null` to `scoreAndCache()`.
- `ClaudeAuroraInterpreter` — `interpret()` and `buildUserMessage()` updated to 7 args (added `TriggerType`, `TonightWindow`). System prompt gains tone guidance: `forecast_lookahead` → planning language ("forecast tonight", "plan your evening"); `realtime` → urgent language ("happening now", "get out there"). User message header includes trigger type, current UTC time, and tonight's dark window when provided.

**Frontend — aurora mode in map:**
- Forecast type selector — button row (☀️ Sunrise | 🌇 Sunset | 🌌 Aurora) above star filter chips; Aurora option visible only for ADMIN/PRO when `auroraStatus.active === true`; switching resets active star filters
- Aurora marker mode — markers show aurora stars (no fiery/golden arcs) when Aurora selected; null star = unrated marker
- Best Location card — banner between filter row and map showing highest-starred aurora location + "Centre map" flyTo button (`FlyToController` inner component)
- `MarkerPopupContent` — `isAuroraMode` prop; ineligible locations (no aurora score) show "🌌 Not suitable for aurora photography" pill

**Tests:**
- `AuroraPollingJobTest` — 3 new dual-path `executePoll` tests; 3 new `calculateTonightWindow` tests (daytime, post-midnight, dusk-always-before-dawn)
- `AuroraOrchestratorTest` — 6 new `runForecastLookahead` tests (threshold check, NOTIFY, SUPPRESS, outside window, fetch failure, Kp 7+ STRONG); real-time NOTIFY updated to verify `TriggerType.REALTIME`
- `ClaudeAuroraInterpreterTest` — 3 new trigger-type tests (`buildUserMessage` includes realtime context, forecast context, omits window section when null); all `interpret()`/`buildUserMessage()` calls updated to 7-arg signatures
- Total: 1009 backend tests passing

---

## [v2.5.0] - 2026-03-21

### Changed — Aurora: NOAA SWPC replaces AuroraWatch UK

Complete rewrite of the aurora pipeline replacing the deprecated AuroraWatch UK XML API with
NOAA SWPC public JSON endpoints. Alert levels renamed QUIET/MINOR/MODERATE/STRONG (from GREEN/YELLOW/AMBER/RED).

**Backend — new classes:**
- `NoaaSwpcClient` — fetches and caches 5 NOAA SWPC endpoints: Kp index (15min TTL), 3-day Kp forecast (15min), OVATION aurora probability grid at 55°N (5min), solar wind Bz/speed (1min), G-scale alerts (5min); fail-open (returns cached/empty on error); per-endpoint `CachedResult<T>` with timestamp
- `MetOfficeSpaceWeatherScraper` — Jsoup HTML scraper of the Met Office specialist space weather page; `@Scheduled` refresh (60min); truncates at 2000 chars to fit Claude context
- `WeatherTriageService` — northward 3-point transect triage (50/100/150 km) via Open-Meteo hourly `cloud_cover`; 0.1° grid deduplication; pass = any hour < 75% overcast in 6h window; `TriageResult` record with viable/rejected/cloudByLocation
- `ClaudeAuroraInterpreter` — single `claude-haiku-4-5` call for all viable locations; prompt includes Kp trend, 24h forecast, OVATION %, solar wind Bz, active alerts, Met Office narrative; returns JSON array with stars/summary/detail; strips code fences; fallback 1★ on parse error
- `AuroraOrchestrator` — drives full pipeline: NOAA fetch → `deriveAlertLevel()` (Kp + OVATION dual-signal, forecast lookahead) → `AuroraStateCache.evaluate()` → triage → Claude → cache; overcast-rejected locations auto-assigned 1★
- `SpaceWeatherData`, `SpaceWeatherAlert` — new model records

**Backend — modified:**
- `AlertLevel` — `QUIET(0)/MINOR(1)/MODERATE(2)/STRONG(3)` replacing `GREEN/YELLOW/AMBER/RED`; `fromKp(double)` factory; `hexColour()` and `description()` updated
- `AuroraProperties` — new `NoaaConfig` (5 URLs + plasma URL), `MetOfficeConfig`, `TriggerConfig` (kp/ovation thresholds + forecast lookahead), `BortleThreshold` (moderate/strong Bortle limits)
- `AuroraStatusResponse` — removed `station`; added `kp`, `ovationProbability`, `dataSource`
- `AuroraController` — enriches status with live NOAA Kp and OVATION (best-effort); no admin endpoints (moved to `AuroraAdminController`)
- `AuroraAdminController` — added `POST /api/aurora/admin/run` (triggers immediate orchestration cycle)
- `AuroraPollingJob` — simplified to `orchestrator.run()` call guarded by daylight check

**Backend — deleted:**
- `AuroraWatchClient`, `AuroraScorer`, `AuroraTransectFetcher` — replaced by above
- `AuroraStatus` model (orphaned after `AuroraWatchClient` removal)

**Dependencies:** `org.jsoup:jsoup:1.18.3` added

**Frontend:**
- `AuroraBanner.jsx` — `AMBER/RED → MODERATE/STRONG`; subtitle now includes live `Kp X.X` reading alongside location count
- `MapView.jsx` — `AMBER/RED → MODERATE/STRONG` in `ALERT_WORTHY` set, `auroraThreshold`, InfoTip copy
- `MarkerPopupContent.jsx` — aurora score pill colour logic `RED → STRONG`
- `auroraApi.js` — updated comment; added `triggerAuroraRun()` and `resetAuroraState()` for the two new admin endpoints

### Tests
- `NoaaSwpcClientTest` (21) — parse methods + fetch methods with mocked RestClient
- `WeatherTriageServiceTest` (8) — null/exception fallback, viable/rejected discrimination via reflection
- `AuroraOrchestratorTest` (18) — `deriveAlertLevel()` parameterized (8 cases), pipeline control flow
- `ClaudeAuroraInterpreterTest` (16) — `buildUserMessage()`, `parseResponse()`, `interpret()` with mocked Anthropic SDK
- `AlertLevelTest`, `AuroraStateCacheTest`, `AuroraPollingJobTest`, `AuroraControllerTest` — updated for new enum names
- JaCoCo: `NoaaSwpcClient$CachedResult`, `MetOfficeSpaceWeatherScraper`, `WeatherTriageService$CloudResponse/HourlyCloudData` added to exclusions

## [v2.4.0] - 2026-03-21

### Added — Aurora Photography Feature
- **Aurora alert status banner** — polling `AuroraWatchClient` (Scottish/English sites) via `AuroraPollingJob` (15 min, night-only); `AuroraStateCache` FSM (IDLE → MONITORING → AMBER → RED); `AuroraStatusBanner` React component shown above forecast timeline
- **Aurora location scoring** — `AuroraScorer` computes 1–5 star ratings per location using cloud cover (35% weight), moon penalty (`LunarPosition.auroraPenalty()`), and Bortle light-pollution class; `GET /api/aurora/locations` endpoint (Bearer) with `maxBortle`/`minStars` filters; `AuroraController`
- **Map popup aurora score section** — when alert level is AMBER or RED, `MapView` fetches scored locations and passes `auroraScore` to `MarkerPopupContent`; shows 🌌 Aurora header, star display (`★★★☆☆`), and cloud/moon/light-pollution detail breakdown
- **Bortle enrichment** — `LightPollutionClient` queries lightpollutionmap.info QueryRaster API (wa_2015 layer); `BortleEnrichmentService` batch-enriches all unenriched locations with SSE per-location progress (PENDING → EVALUATING → COMPLETE/FAILED); `LIGHT_POLLUTION` RunType; `POST /api/aurora/admin/enrich-bortle` returns 202 with `jobRunId` and appears in Job Runs page; `POST /api/aurora/admin/reset` resets the aurora state machine to IDLE
- **Directional cloud sampling for aurora** — `AuroraTransectFetcher` samples cloud cover at 3 points along the northward transect (0°, 345°, 15° azimuth) at 113 km offset via Open-Meteo hourly cloud_cover_low; deduplicates nearby grid cells; falls back to 50% on error
- **V55 migration** — adds `bortle_class` column to `locations` table (nullable integer)
- **Frontend aurora API module** — `auroraApi.js` with `getAuroraStatus()`, `getAuroraLocations()`, and `enrichBortle()`
- **Bortle column in Location Management** — read-only in both view and edit modes; InfoTip explaining 1–9 scale
- **Aurora filter InfoTip** — click-to-reveal tooltip on the 🌌 Aurora friendly map filter button explaining scoring criteria (alert level, cloud, moon, Bortle factors)
- **Drive Times + Light Pollution buttons moved** — from Location Management panel to Operations → Job Runs → Data Refresh section; Light Pollution enrichment triggers the live SSE progress panel

### Fixed
- **InfoTip line breaks** — changed `whitespace-normal` to `whitespace-pre-line` so `\n` in tooltip text renders as actual line breaks across all InfoTip instances

### Tests
- `AuroraAdminControllerTest` (10), `BortleEnrichmentServiceTest` (5), `LightPollutionClientHttpTest` (6), `AuroraTransectFetcherTest` (8)
- `AuroraPollingJobTest` updated for solar-utils v2 API (`civilDawn`/`civilDusk`)
- `ForecastControllerTest` extended with retry-failed, SSE endpoint, and location-filter coverage
- `LocationManagementView.test.jsx` — Bortle column: header renders, null shows `—`, value renders, read-only in edit mode (5 tests)
- JaCoCo ≥80% threshold maintained; `LightPollutionClient` JaCoCo-excluded (untestable UriBuilder lambda)

### Added (Mar 20, 2026) — Tide Alignment Pre-Claude Triage
- **TIDE_ALIGNMENT run optimisation** — new `OptimisationStrategyType` enabled by default for all colour run types (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM) via V54 migration
  - For SEASCAPE locations with tide preferences, applies a pre-Claude check: if no preferred tide type (HIGH/LOW/MID) falls within the golden/blue hour window around the solar event, skips Claude and persists a 1★ canned result
  - Window: `[civilDawn, sunrise+60min]` for sunrise; `[sunset-60min, civilDusk]` for sunset
  - Nearest tide extreme limited to ±12h of the solar event
  - Fail-open: sends to Claude when no tide data or no preferences are set
  - `TideAlignmentEvaluator` service with any-match across multiple preferred tide types
  - `TIDE_MISALIGNED` triage rule; map popup footer detects and displays "tide not aligned" reason
  - `TideData` + `TideSnapshot` records extended with `nearestHighTideTime` / `nearestLowTideTime`
  - `TideService.findNearestExtreme()` helper finds closest HIGH/LOW extreme within ±12h
- **SEASCAPE tide preference enforcement** — add/edit location forms require at least one tide preference when location type is SEASCAPE
- **Amber warning chip** — map popup shows an amber ⚠️ chip for SEASCAPE locations with no tide preferences set

### Added (Mar 12, 2026)
- **Spring/king tide badge pills** — map popup shows spring tide (🌊) or king tide (👑) badges when a HIGH tide exceeds the spring (125% avg) or king (P95) threshold; prominent styling when within ±90 min of the forecast solar event, muted with "outside golden/blue hours" text otherwise; king trumps spring (never both for the same tide); coexists with the existing rising tide warning badge
- **Tide stats: spring & king tide thresholds** — `TideStats` record extended with `springTideThreshold` (125% of avg high), `kingTideThreshold` (P95), and `kingTideCount`; displayed in Location Tides modal
- **H2 aggregate query robustness** — `TideService.getTideStats()` unwraps nested `Object[1]{Object[4]}` arrays returned by H2 and safely converts `Double`→`BigDecimal` for AVG() results
- **Map popup scroll fix** — expanded "More details" popup now scrolls when content exceeds map height; `PopupResizer` component directly manipulates Leaflet popup DOM to enforce max-height
- **Tide indicator spacing** — improved vertical breathing room between tide schedule, typical range, and golden/blue hour pills

### Added (Mar 12, 2026) — Cloud Approach Risk
- **Cloud approach risk detection** — two new signals augment directional cloud data to detect cloud approaching the solar horizon that a single event-time snapshot would miss
  - `SolarCloudTrend`: hourly low cloud at the 113 km solar horizon from T-3h to event time; `isBuilding()` detects a peak-vs-earliest increase of 20+ pp, appending a `[BUILDING]` label to the prompt that instructs Claude to penalise fiery_sky by 10–25 points
  - `UpwindCloudSample`: current low cloud at an upwind point along the wind vector vs the model's event-time prediction; high current cloud with low event-time prediction flags over-optimistic clearing
  - `CloudApproachData` record composes both signals into `AtmosphericData`; `ForecastDataAugmentor` assembles the data from Open-Meteo; `PromptBuilder` formats it as a `CLOUD APPROACH RISK:` block
  - V51 migration adds persistence columns to `forecast_evaluation`
  - Motivated by the Copt Hill 2026-03-11 sunset failure case (4-star prediction, ~2-star reality)

### Added (Mar 11, 2026)
- **LocationType.WATERFALL** — new location type with 💦 emoji across map filters, badges, location editor, and metrics
  - V50 migration reclassifies 31 waterfall locations from LANDSCAPE to WATERFALL
  - Waterfall locations show both colour forecasts AND hourly comfort rows (temp/wind/rain)
  - Waterfall scores excluded from cluster marker averages (waterfall photography is about the water, not sky colour)
- **NEXT_EVENT_ONLY optimisation strategy** — evaluates only the single nearest upcoming solar event per location, skipping all other sunrise/sunset slots; ideal for last-minute checks before heading out
  - V49 migration seeds the new strategy; conflicts with EVALUATE_ALL only

### Added (Mar 10, 2026)
- **Tide history backfill** — 12-month historical fetch via WorldTides API (7-day chunks, duplicate-aware skipping) with admin UI button and async execution
- **3-point cone cloud sampling** — replaces single-point directional sampling with azimuth ±15° cone (3 points averaged) to smooth Open-Meteo grid-cell boundary effects that caused inconsistent ratings for nearby locations (~11km resolution)
- **Tide stats endpoint** — `GET /api/tides/stats` with avg/max high and avg/min low from accumulated `tide_extreme` data; "Typical range" row shown in TideIndicator
- **Rising tide warning badge** — amber badge when high tide falls within ±90 min of solar event (blue+golden hour window)
- **Tide history preservation** — windowed delete replaces only the 14-day fetch window instead of deleting all rows per location
- **SEASCAPE filtering** — both tide refresh and backfill consistently filtered by `LocationType.SEASCAPE`
- **Editable lat/lon** — location inline edit now supports lat/lon editing with validation
- **Region pagination** — client-side pagination with location count column in regions table
- **St Mary's Lighthouse regression test** — new prompt regression test case from 10 Mar 2026 sunrise prod data

### Fixed (Mar 10, 2026)
- **Rising tide window** — widened from ±60 to ±90 min of solar event
- **Manage view gate** — `ManageView` guarded with `isAdmin` in all render paths so PRO/LITE users only see the map
- **Jackson CVE suppression** — suppress CVE-2026-29062 (Jackson 3.0.4 nesting depth bypass); fix requires Jackson 3.1.0 incompatible with Spring Boot 4.0.3

### Refactored (Mar 8, 2026)
- **Evaluation strategy hierarchy collapse** — replaced `AbstractEvaluationStrategy` + 3 trivial subclasses (`HaikuEvaluationStrategy`, `SonnetEvaluationStrategy`, `OpusEvaluationStrategy`) with a single `ClaudeEvaluationStrategy` parameterised by `EvaluationModel`
  - Model ID is the single source of truth via `EvaluationModel.getModelId()` — no more per-class `getModelName()` overrides
  - `EvaluationConfig` produces a `Map<EvaluationModel, EvaluationStrategy>` bean; `EvaluationService` and `ForecastCommandFactory` use map lookup instead of switch/injection of 4 named beans
- **PromptBuilder extraction** — moved prompt construction (`SYSTEM_PROMPT`, `PROMPT_SUFFIX`, `buildUserMessage()`, `buildOutputConfig()`, `toCardinal()`, `isDustElevated()`) from `AbstractEvaluationStrategy` into a dedicated `PromptBuilder` class; injected as a Spring bean
- **MetricsLoggingDecorator** — extracted timing, logging, and metrics recording from `EvaluationService` into a GoF Decorator (`MetricsLoggingDecorator`) that wraps any `EvaluationStrategy`; applied transparently when a `JobRunEntity` is present
- **Double buildUserMessage bug fix** — `evaluateWithDetails()` was calling `buildUserMessage()` twice per evaluation (once explicitly, once inside `invokeClaude()`); `invokeClaude()` now accepts a pre-built `String` parameter
- **ForecastDataAugmentor extraction** — moved `augmentWithDirectionalCloud()` and `augmentWithTideData()` from `ForecastService` into a dedicated `ForecastDataAugmentor` service; `ForecastService`, `ModelTestService`, and `PromptTestService` all delegate to it
- **Forecast DTO layer** — `ForecastEvaluationDto` record decouples the REST API contract from the JPA entity; `ForecastDtoMapper` maps entities to DTOs with role-based score selection (LITE users get basic observer-point scores, PRO/ADMIN get enhanced directional scores); `basic_*` columns never appear in the API response; `ForecastController` GET endpoints return DTOs instead of entities
- 690 backend tests — all passing, JaCoCo >= 80%

### Added (Mar 7, 2026)
- **Directional cloud sampling** — fetches cloud cover at 50 km offset points toward the solar horizon and antisolar horizon using Haversine forward formula (`GeoUtils.offsetPoint()`)
  - `DirectionalCloudData` record with 6 fields: solar/antisolar low/mid/high cloud percentages
  - `OpenMeteoClient.fetchCloudOnly()` — lightweight cloud-only Open-Meteo request with `@Retryable`
  - `OpenMeteoService.fetchDirectionalCloudData()` — computes offset coordinates, fetches cloud at both points, extracts nearest time slot
  - Graceful degradation: returns null on failure, evaluation falls back to single-point inference
  - 6 new columns on `forecast_evaluation`: `solar_low_cloud`, `solar_mid_cloud`, `solar_high_cloud`, `antisolar_low_cloud`, `antisolar_mid_cloud`, `antisolar_high_cloud`
- **Dual-tier scoring (freemium)** — single Claude API call returns both enhanced scores (using directional data) and basic scores (observer-point inference only)
  - 3 new columns on `forecast_evaluation`: `basic_fiery_sky_potential`, `basic_golden_hour_potential`, `basic_summary`
  - `SunsetEvaluation` extended with basic fields; 4-arg convenience constructor for backward compatibility
  - Evaluation prompt updated with directional cloud rules and dual-tier output schema
  - LITE users will see `basic_*` scores; PRO/ADMIN get enhanced directional scores (frontend gating TBD)
  - V48 migration adds all 9 columns
- **Prompt regression test suite** — live Claude API tests with real-world atmospheric data that assert scores stay within observed bounds
  - `PromptRegressionTest` with `@Tag("prompt-regression")`, excluded from `mvn verify`
  - Run on demand: `ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression`
  - Copt Hill (negative case): blocked solar horizon, asserts rating <= 2, fiery <= 25, golden <= 35
  - Angel of the North (positive case): spectacular sunset, asserts rating >= 4, fiery >= 60, golden >= 60
  - `generate-regression-fixture.sh` — fetches Open-Meteo historical data and outputs Java fixture code
- **AtmosphericData decomposition** — split the 27-field `AtmosphericData` record into 5 composable sub-records: `CloudData`, `WeatherData`, `AerosolData`, `ComfortData`, `TideSnapshot`
  - `AtmosphericData` reduced from 27 positional fields to 9 named sub-records
  - `withDirectionalCloud()` and `withTide()` copy methods replace the painful 27-field copy-with pattern
  - `ForecastService.augmentWithDirectionalCloud()` reduced from 15 lines to 1 line
  - `ForecastService.augmentWithTideData()` reduced from 25 lines to 10 lines
  - `TestAtmosphericData` builder centralises test data construction across 12 test files
- 664 backend tests — all passing, JaCoCo >= 80%

### Fixed (Mar 7, 2026)
- **Solar-aware slot selection** — `findBestIndex()` replaces `findNearestIndex()` for choosing the Open-Meteo hourly slot; sunset picks the last slot at or before the event, sunrise picks the first slot at or after. Prevents using post-sunset or pre-sunrise data (0 W/m² radiation, meaningless conditions)
- **Directional cloud scoring thresholds** — adjusted from >50% to >60% solar low cloud for the hard "blocked" ceiling; 40-60% band penalises but considers that mid/high cloud may still catch colour through gaps in breaking low cloud

### Added (Mar 4, 2026)
- **Popup preview on prompt test results** — eye icon button on each succeeded result row opens a modal rendering the real `MarkerPopupContent` with mapped atmospheric data, scores, and location metadata; useful for visually checking badges like Sahara Dust
- **URL hash navigation** — active view and Manage tab persisted in URL hash (e.g. `#manage/prompttest`); page refresh returns to the same screen
- **Prompt test harness** — end-to-end prompt evaluation test that runs all colour locations through the Claude pipeline with a chosen model, stores results, and supports run comparison
  - `prompt_test_run` + `prompt_test_result` tables (V44, V45)
  - `PromptTestService` orchestrates: pick colour locations, fetch weather, evaluate with selected model, persist results with rating/fiery sky/golden hour scores
  - `PromptTestController` with ADMIN-only endpoints: `POST /api/prompt-test/run`, `POST /api/prompt-test/replay`, `GET /api/prompt-test/runs`, `GET /api/prompt-test/runs/{id}`, `GET /api/prompt-test/results`, `GET /api/prompt-test/git-info`
  - **Async execution** — POST /run and /replay return 202 Accepted immediately; work runs in background via `CompletableFuture.runAsync()` on virtual thread executor; frontend polls `GET /runs/{id}` every 3s for live progress updates
  - **Run comparison** — select two runs via checkboxes to see side-by-side results with score deltas
  - **Replay** — re-run a previous test with the same locations and dates but current prompt version, for A/B comparison of prompt changes
  - **Build info section** — shows current git commit, branch, and relative commit date above controls; hidden when git info unavailable
  - **Model versions** — `EvaluationModel` enum gains `version` field (HAIKU 4.5, SONNET 4.5, OPUS 4.6); `/api/models` returns `[{name, version}, ...]`; versions shown next to model radio buttons
  - **Date and Target columns** — results table shows target date and target type (SUNRISE/SUNSET) for each result
  - **Docker git fix** — build context changed from `./backend` to repo root so `git-commit-id-maven-plugin` can access `.git/`; git badge no longer shows "?" in Docker builds
  - 646 backend tests, 321 frontend tests — all passing

### Fixed (Mar 4, 2026)
- **Dust badge PM2.5 threshold** — raised from < 15 to < 35 µg/m³; Saharan dust events commonly push PM2.5 into the 20–30 range which was incorrectly suppressing the badge
- **Wildlife marker emoji** — fixed leftover eagle emoji (🦅) in `markerUtils.js` map marker medallions; now shows paw prints (🐾) matching the rest of the UI
- **Locations table layout** — removed Created column, rebalanced widths to prevent Type and Tide chips overflowing into adjacent columns
- **MetricsSummary cost aggregation** — combined token-based micro-dollar costs with legacy flat-rate pence costs instead of ignoring pence when any micro-dollars exist; was showing £0.14 instead of £24.99 for 19 runs
- **ModelSelectionView pricing** — converted from USD to GBP primary display with greyed-out USD in parentheses, matching the JobRunsGrid pattern

### Changed (Mar 4, 2026)
- **TideType enum refactored** — simplified from 5 sentinel values (HIGH_TIDE, LOW_TIDE, MID_TIDE, ANY_TIDE, NOT_COASTAL) to 3 real values (HIGH, MID, LOW)
  - Empty set replaces NOT_COASTAL; all three selected replaces ANY_TIDE
  - `AddLocationRequest` and `UpdateLocationRequest` now use `Set<TideType> tideTypes` (was single `TideType tideType`)
  - V43 Flyway migration renames and expands existing data in place — no data loss
  - `TideService.calculateTideAligned()` simplified to 3-case switch
  - `LocationService.isCoastal()` simplified to `!tideType.isEmpty()`
- **Emoji chip UI for location metadata** — Type and Tide columns in Locations table now use compact toggle chips
  - Location type: 🏔️ (Landscape), 🌊 (Seascape), 🐾 (Wildlife) — single-select, clickable in edit mode, read-only display with faded unselected icons
  - Tide type: H/M/L gold toggle chips — multi-select for SEASCAPE, disabled for non-coastal, prevents deselecting last chip
  - Column header filters replaced with matching clickable chips (no more text inputs for Type and Tide)
  - Tide column header filter supports multi-select with AND logic
- **Wildlife emoji** — changed from 🦅 to 🐾 (paw prints) across MapView filter bar and Locations table for better dark theme contrast (brightness filter applied)
- **MetricsSummary time filtering** — added Today / Last 7 Days toggle to filter summary statistics by date range; shows "mixed pricing" label when both cost types are present

### Added (Mar 4, 2026)
- **Client-side pagination for Locations and Users tables** — shared `usePagination` hook and `Pagination` component
  - Default page size of 10 with 10/25/50 size chips
  - First/Prev/Next/Last navigation buttons with "Showing X-Y of Z" summary
  - `table-fixed` with explicit column widths prevents column shifting between pages
  - Spacer rows on partial last page keep pagination controls anchored (no layout jump)
  - Resets to page 1 when filters change; pagination hidden when all items fit on one page
  - Truncated cell content with hover tooltips for long names/emails
  - 32 new tests: `usePagination.test.js` (13), `Pagination.test.jsx` (13), `UserManagementView.test.jsx` (4), `LocationManagementView.test.jsx` (+2)

### Added (Mar 3, 2026)
- **Marker clustering** — `react-leaflet-cluster` groups nearby markers at low zoom levels
  - Clusters display marker count with grey→gold background based on average child rating
  - PRO/ADMIN cluster icons include fiery sky (orange) and golden hour (gold) half-arc progress from averaged scores
  - LITE users see plain coloured cluster circles (no arcs)
  - `disableClusteringAtZoom={10}` ensures individual markers at close zoom
  - `maxClusterRadius={60}` for moderate clustering density
  - Long location name labels truncated with ellipsis (90px max-width); full name shown on hover via `title` attribute
  - `createClusterIcon` in `markerUtils.js` with 15 unit tests
- **Radial progress arcs on map markers** — SVG-based arc gauges replace plain coloured circles
  - PRO/ADMIN: two half-arcs (left = Fiery Sky orange, right = Golden Hour gold) filling bottom-up proportionally to 0–100 score
  - LITE: single proportional ring based on 1–5 star rating
  - Wildlife: plain green circle with eagle emoji (no arcs)
  - No-data: plain grey circle with ? (no arcs)
  - `markerUtils.js` extracted for testability: `buildMarkerSvg`, `scoreColour`, `markerLabelAndColour`, `RATING_COLOURS`
  - Marker label shows star rating (e.g. "4★") with rating-graded colour for all users
- **MarkerPopupContent tests** — 28 tests covering role-based score bar visibility, coastal tide display, wildlife and no-data rendering
- **useForecasts regression tests** — 6 tests including wildlife location inclusion, disabled location exclusion

### Fixed (Mar 3, 2026)
- **Wildlife locations missing from map** — `useForecasts` now builds location list from the full locations API response, not just forecast rows; wildlife locations without evaluations are no longer dropped
- **goldenHourType no longer filters evaluations** — `shouldEvaluateSunrise()` and `shouldEvaluateSunset()` now always return true; both sunrise and sunset forecasts are generated for every non-wildlife location regardless of `goldenHourType` (which is photographer preference metadata, not an evaluation filter)
- **Wildlife marker colour** — toned down from `#4ade80` to `#16a34a` (green-600) for consistency with popup header
- **Vite proxy default** — changed from port 8083 to 8082 to match actual backend port

### Changed (Mar 3, 2026)
- **Merge REQUIRE_PRIOR into SKIP_LOW_RATED** — SKIP_LOW_RATED now also skips when no prior evaluation exists, reducing strategies from 6 to 5
  - V40 migration: deletes REQUIRE_PRIOR rows from `optimisation_strategy` table
  - `OptimisationSkipEvaluator`: SKIP_LOW_RATED checks `latest.isEmpty()` first, then rating threshold
  - Mutual exclusion rules updated (REQUIRE_PRIOR entries removed)
- **Improved strategy labels and descriptions** — all five strategies have clearer names and actionable descriptions in the Admin UI
  - Skip Existing → "Skip Already-Evaluated", Force Imminent → "Always Evaluate Today", Force Stale → "Re-evaluate Stale Data", Evaluate All → "Evaluate Everything (JFDI)"
- **Per-call cost estimates in Run Config** — model cards show typical cost per call; cost estimate table shows run total based on actual configured location count
- **Configurable Vite proxy target** — `VITE_API_TARGET` env var in `frontend/.env` switches between local dev (8083) and Docker prod (8082); `/actuator` also proxied

### Fixed (Mar 3, 2026)
- **Disabled button UX** — `btn-primary` and `btn-secondary` now show visible disabled state (40% opacity, not-allowed cursor)
- **Add Location hint** — "Review & Confirm" button shows helper text when in place search mode without a geocode result
- **Vitest 4 compatibility** — `useIsMobile.test.js` replaced `vi.spyOn(window, 'matchMedia')` with `vi.stubGlobal('matchMedia', ...)` because `window.matchMedia` is `undefined` in Vitest 4's jsdom environment

### Added (Mar 3, 2026)
- **Configurable cost optimisation strategies** — five toggleable strategies per run type replace hard-coded Opus gate and long-term skip logic
  - Strategies: SKIP_LOW_RATED (threshold param), SKIP_EXISTING, FORCE_IMMINENT, FORCE_STALE, EVALUATE_ALL (JFDI mode)
  - V39 migration: `optimisation_strategy` table (15 rows seeded), `active_strategies` column on `job_run` for audit trail
  - `OptimisationSkipEvaluator` evaluates strategies with shared DB lookup; `OptimisationStrategyService` handles CRUD + mutual exclusion validation
  - `ForecastCommandExecutor` refactored to delegate skip logic to evaluator instead of hard-coded methods
  - Admin UI: "Cost Optimisation" section in Run Config tab with toggle pills, parameter buttons, and conflict indicators
  - `PUT /api/models/optimisation` endpoint for strategy toggles; `GET /api/models` now includes strategy data
  - Job Runs grid shows active strategies as badges; EVALUATE_ALL displays distinct "JFDI" badge
  - **LocationManagementView tests** — 8 new tests covering add form, disabled states, and hint messages
  - 607 backend tests, 151 frontend tests; all passing

### Added (Mar 2, 2026)
- **Token-based cost tracking** — replaces flat per-call pence estimates with actual token-based micro-dollar pricing from Anthropic SDK responses
  - `TokenUsage` record captures input, output, cache creation, and cache read tokens from every `Message.usage()` response
  - `CostCalculator` computes costs in micro-dollars (1 USD = 1,000,000 µ$) using real per-model USD/MTok rates: Haiku ($1/$5), Sonnet ($3/$15), Opus ($5/$25), with cache write/read rates and 50% batch discount
  - `ExchangeRateService` fetches daily USD-to-GBP rate from Frankfurter API (ECB data, no API key); caches in `exchange_rate` table; falls back to most recent cached rate on failure
  - Exchange rate snapshot stored per `job_run` and `model_test_run` so historical costs convert at the rate from the day they were incurred
  - V38 migration: token columns + `cost_micro_dollars` on `api_call_log` and `model_test_result`; `total_cost_micro_dollars` + `exchange_rate_gbp_per_usd` on `job_run` and `model_test_run`; new `exchange_rate` table
  - `AbstractEvaluationStrategy.extractTokenUsage()` reads all four token categories from SDK response
  - `JobRunService.logAnthropicApiCall()` records tokens + micro-dollar cost per call; `completeRun()` aggregates both legacy pence and micro-dollar totals
  - `ModelTestService` populates token fields and micro-dollar costs on test results and runs
  - Frontend `formatCost.js` utility: `formatCostGbp()` (with legacy pence fallback), `formatCostUsd()`, `formatTokens()`
  - `MetricsSummary` shows both GBP and USD totals; `JobRunDetail` shows per-call token breakdown (input/output/cache write/cache read); `JobRunsGrid` uses token-based costs; `ModelTestView` adds Tokens and Cost columns
  - `ModelSelectionView` shows real per-model pricing rates instead of hardcoded estimates
  - Legacy `cost_pence` / `total_cost_pence` columns retained for backward compatibility
  - 569 backend tests (up from 565), all passing; Checkstyle/SpotBugs/JaCoCo clean

### Changed (Mar 2, 2026)
- **Spring Framework 7 feature adoption** — virtual threads, RestClient, declarative resilience, and HTTP interface clients
  - **Virtual threads** — `spring.threads.virtual.enabled: true` in both profiles; `forecastExecutor` uses `Executors.newVirtualThreadPerTaskExecutor()` (replaces sized `ThreadPoolTaskExecutor`)
  - **RestClient replaces WebClient** — all HTTP clients migrated from reactive `WebClient.block()` to synchronous `RestClient`; Reactor/WebFlux removed from classpath entirely (`spring-boot-starter-webclient` + `reactor-test` dependencies dropped)
  - **@HttpExchange interfaces** — `OpenMeteoForecastApi` and `OpenMeteoAirQualityApi` declarative interfaces proxied via `HttpServiceProxyFactory` + `RestClientAdapter`; `OpenMeteoClient` wraps both with `@Retryable`
  - **Declarative retry** — `@EnableResilientMethods` + `@Retryable` (Spring Framework 7 `org.springframework.resilience`) replaces hand-rolled retry loops; `AnthropicApiClient` retries 529/content-filter, `OpenMeteoClient` retries 5xx/429; `MethodRetryPredicate` implementations: `ClaudeRetryPredicate`, `TransientHttpErrorPredicate`
  - **@ConcurrencyLimit(8)** on `ForecastService.runForecasts()` — caps parallel evaluations, replacing thread pool sizing
  - **TurnstileService** migrated from `RestTemplate` to `RestClient`
  - **GlobalExceptionHandler** — `WebClientResponseException` → `RestClientResponseException`
  - 541 backend tests (up from 535), all passing; Checkstyle/SpotBugs/JaCoCo clean
  - 10 new files created, 25 modified; net -810 / +923 lines

### Added (Mar 2, 2026)
- **PIT mutation testing** — `pitest-maven-plugin` 1.17.4 with JUnit 5 support; targets service, controller, and config packages; run locally with `./mvnw pitest:mutationCoverage`; HTML + XML reports in `target/pit-reports/`
  - Weekly CI workflow (`.github/workflows/pitest.yml`) runs every Monday 06:00 UTC with manual dispatch; uploads report as artifact

### Fixed (Mar 2, 2026)
- **H2 driver missing from fat JAR** — removed `<optional>true</optional>` from the H2 dependency in `pom.xml`; Spring Boot 4 excludes optional dependencies from the packaged JAR, causing `Cannot load driver class: org.h2.Driver` at startup in Docker
- **Jackson serialization config incompatible with Boot 4** — removed `spring.jackson.serialization.write-dates-as-timestamps: false` from `application.yml`; Spring Boot 4 uses `tools.jackson.databind` (Jackson 3) which doesn't recognise the old enum constant format; the default is already `false`

### Added (Mar 2, 2026)
- **Mobile bottom sheet for map markers** — on viewports ≤639px (below Tailwind `sm:` breakpoint), tapping a map marker opens a slide-up bottom sheet instead of a cramped Leaflet popup; scrollable content, tap-to-dismiss overlay, close button, body scroll lock; desktop keeps existing Leaflet popup unchanged
  - `useIsMobile` hook (MediaQueryList-based, listens for resize/orientation changes)
  - `BottomSheet` component (overlay + sheet + drag handle pill + close button, 200ms slide-up animation)
  - `MarkerPopupContent` extracted from MapView — shared by both Leaflet popup (desktop) and bottom sheet (mobile)
  - 11 new tests (7 BottomSheet, 4 useIsMobile) — 127 frontend tests total

### Changed (Mar 2, 2026)
- **React 19 upgrade** — `react` and `react-dom` 18.3.1 → 19.2.4, `react-leaflet` 4.2.1 → 5.0.0
  - Migrated all 4 `defaultProps` usages to JavaScript default parameters (deprecated in React 19): LocationAlerts, JobRunsGrid, LoginPage, RegisterPage
  - Supersedes two separate Dependabot PRs that couldn't be merged individually due to peer dependency conflicts
- **Spring Boot 4.0 migration** — upgraded from Spring Boot 3.x to 4.0.3 (Spring Security 7, Jackson 3)
  - `spring-boot-starter-web` → `spring-boot-starter-webmvc`, `spring-boot-starter-webflux` → `spring-boot-starter-webclient`
  - `flyway-core` → `spring-boot-starter-flyway`
  - Jackson 2 → Jackson 3: `com.fasterxml.jackson.databind` → `tools.jackson.databind`
  - Test annotations: `@MockBean` → `@MockitoBean`, new Boot 4.0 modularised test starters
  - `spring-boot-starter-security-test` for `@WithMockUser` MockMvc integration
  - Springdoc OpenAPI 2.3.0 → 3.0.1
  - All 535 backend tests pass, Checkstyle/SpotBugs/JaCoCo clean

### Added (Mar 2, 2026)
- **90 regression tests** — pre-v1.0 test hardening across backend and frontend
  - Backend (37 new, 497 → 534): RequestLoggingInterceptor (17), LocationController edge cases (+7), RegionController 404s (+4), UserController DELETE (+5), ModelTestController validation (+4)
  - Frontend (53 new, 57 → 110): conversions-extra (22), ScoreBar (10), SessionExpiryBanner (10), OutcomeModal (11)

### Added (Mar 1, 2026)
- **Responsive map popup** — popup width now uses `calc(100vw - 40px)` capped at `max-width: 600px`, so popups fit within phone viewports instead of overflowing at the hardcoded 600px
- **Model Test button descriptions** — descriptive text below each button explaining what it does: which locations are tested, whether weather/tide data is fetched fresh, and that all three Anthropic models are always run
- **Event-specific azimuth line** — map now only renders the sunrise or sunset azimuth line matching the selected event type, instead of always showing both
- **Star rating filter on map** — 5 toggle chips (1★–5★) on the map filter bar let users show any permutation of star ratings; AND-ed with existing location type filters; gold highlight when active; single Clear button resets both filter groups
- **Re-run model test** — re-run a previous model test using the same locations but fresh weather data and fresh Anthropic API calls, to measure variance between runs
  - `POST /api/model-test/rerun?testRunId=X` endpoint (ADMIN only)
  - "Re-run" button in results header with confirmation dialog listing locations
  - 5 new backend tests (3 service, 2 controller)
- **Last active tracking** — renamed `lastLoginAt` to `lastActiveAt`; now updated on every authenticated API request (throttled to once per hour) instead of only on login
  - V37 migration renames column
  - `JwtAuthenticationFilter` updates `lastActiveAt` when stale (>60 min)
  - Frontend column label: "Last Active"
- **Single-location model test** — admin can test one specific location with all three Claude models (Haiku/Sonnet/Opus) using identical atmospheric data, for debugging or spot-checking
  - `POST /api/model-test/run-location?locationId=X` endpoint (ADMIN only)
  - `ModelTestService.runTestForLocation()` validates location (enabled, has colour types, has region), fetches weather once, evaluates with 3 models
  - Frontend: "Test One Location" button opens a location picker modal with text filter, eligible location list, and cost note (3 API calls)
  - 9 new backend tests (6 service, 3 controller)
- **Run row toggle collapse** — clicking an already-expanded run in the Model Test table now collapses it instead of re-fetching results
- 497 backend tests, 57 frontend tests — all passing
- **Marketing email opt-in preference** — users can opt in/out of marketing emails during registration
  - V35 migration: `marketing_email_opt_in BOOLEAN NOT NULL DEFAULT TRUE` on `app_user`
  - Checkbox on RegisterPage (default checked): "Send me occasional emails about new features and photography tips"
  - Privacy policy modal updated with "Marketing Emails" section explaining opt-in, right to unsubscribe, and transactional email distinction
  - `POST /api/auth/register` accepts optional `marketingEmailOptIn` parameter (defaults true)
  - `PUT /api/auth/marketing-emails` new authenticated endpoint to toggle preference
  - Login and set-password responses include `marketingEmailOptIn` field
  - `AuthContext` stores and exposes preference for future settings page
  - 7 new backend tests (AuthControllerTest, UserServiceTest, RegistrationServiceTest), 2 new email service tests
- **Account deletion email notification** — when an admin deletes a user account, a polite notification email is sent to the user (if they have an email address) informing them their account has been removed

### Fixed (Mar 1, 2026)
- **Verification link bug after logout** — after completing registration (verify email + set password), logging out would show "Verification failed — This verification link has already been used" because the `?token=` URL parameter was never cleared; now cleared via useEffect in AuthGate when user becomes authenticated (RegisterPage unmounts before its own cleanup can fire)

### Changed (Mar 1, 2026)
- **Tailwind CSS v3 → v4 migration** — replaced `tailwindcss` v3 + `autoprefixer` with `@tailwindcss/postcss` v4; moved theme config from `tailwind.config.js` into CSS `@theme` block in `index.css`; deleted `tailwind.config.js`; inlined `.btn` base styles into `.btn-primary`/`.btn-secondary` (v4 disallows `@apply` of custom component classes)

### Added (Mar 1, 2026)
- **Model comparison test harness** — A/B/C test that runs Haiku, Sonnet, and Opus against identical atmospheric data for one location per region, for side-by-side evaluation comparison
  - V34 migration: `model_test_run` and `model_test_result` tables with FKs to regions/locations
  - `ModelTestService` orchestrates: find enabled regions, pick representative colour location per region, fetch weather once, run all three models, persist results with prompt/response capture
  - `ModelTestController` with ADMIN-only endpoints: `POST /api/model-test/run`, `GET /api/model-test/runs`, `GET /api/model-test/results`
  - `EvaluationDetail` record captures exact prompt sent and raw Claude response for reproducibility
  - `evaluateWithDetails()` method added to `AbstractEvaluationStrategy` and `EvaluationService`
  - Frontend: `ModelTestView.jsx` with run button, confirmation dialog, runs table, and comparison grid grouped by region with Haiku/Sonnet/Opus rows and delta indicators from Haiku baseline
  - "Model Test" tab added to ManageView
  - 12 service tests, 6 controller tests, 5 frontend tests
- **Styled confirmation dialogs for Job Runs** — replaced all 4 `window.confirm()` browser dialogs in JobRunsMetricsView with styled modal dialogs matching the app's existing pattern (dark overlay, rounded card, Cancel/Confirm buttons)

### Fixed (Mar 1, 2026)
- **Flaky LocationFailureTrackingTest** — removed `@ActiveProfiles("local")` that forced file-based H2 (causing stale state between test runs); now uses in-memory test DB with explicit state reset in `@BeforeEach`

### Added (Mar 1, 2026)
- **Self-registration with email verification** — users can sign up with email + username, verify via email link, then set their own password
  - V33 migration: `email_verification_token` table + unique email index on `app_user`
  - `RegistrationService` orchestrates register, resend (rate-limited: max 3 in 5 min), verify, and activate
  - Four new public auth endpoints: `/register`, `/resend-verification`, `/verify-email`, `/set-password` (auto-login on completion)
  - `verification-email.html` Thymeleaf template (dark theme, CTA button, 24-hour expiry)
  - Abandoned pending registrations (unverified) are automatically replaced on re-registration
  - Duplicate email/username returns 409 Conflict; resend returns generic 200 to prevent email enumeration
  - Frontend: `RegisterPage.jsx` multi-step state machine (register -> check email -> verify -> set password -> success)
  - `AuthContext.completeRegistration()` for token storage after registration
  - Login page gains "Don't have an account? Create one" link; privacy policy modal
  - `app.frontend-base-url` config for verification link URLs
  - 432 backend tests passing (31 new: RegistrationServiceTest, AuthController registration tests, UserService, UserEmailService)
- **Backend down indicator for all users** — non-admin users now see a red banner and greyed-out UI (opacity + pointer-events disabled) when the backend health poll returns DOWN; clears automatically on recovery
- **Region entity** — new `Region` table with nullable FK on locations for geographic grouping
  - `V31` migration creates `regions` table and seeds 5 rows: Tyne and Wear, The North Yorkshire Coast, The Lake District, The Yorkshire Dales, Northumberland
  - `V32` migration adds `region_id` FK column to `locations`
  - `RegionEntity`, `RegionRepository`, `RegionService`, `RegionController` — full CRUD with enable/disable
  - `GET /api/regions` (authenticated), `POST /api/regions` (ADMIN), `PUT /api/regions/{id}` (ADMIN), `PUT /api/regions/{id}/enabled` (ADMIN)
  - `LocationEntity` gains `@ManyToOne` nullable `region` field
  - `AddLocationRequest` and `UpdateLocationRequest` gain `regionId` parameter
  - Frontend: `regionApi.js`, `RegionManagementView.jsx` (sortable table with add/edit/enable-disable)
  - "Regions" tab added to ManageView between Locations and Job Runs
  - Region dropdown in location add and edit forms; Region column in locations table
  - 27 new backend tests (RegionServiceTest: 18, RegionControllerTest: 9) — 393 total passing

### Fixed (Mar 1, 2026)
- **Anthropic content filter retry** — 400 errors with "content filtering" now retry with exponential backoff (1s -> 2s -> 4s, 3 retries) alongside existing 529 retry; on final failure, full prompt inputs logged at WARN for reproduction
- **Anthropic retry exception type** — fixed dead code: replaced `HttpServerErrorException` (Spring) catch with `AnthropicServiceException` (SDK) which is what the Anthropic OkHttp client actually throws

### Changed (Mar 1, 2026)
- **Forecast horizon reduced to T+5** — `FORECAST_HORIZON_DAYS` changed from 7 to 5; Long-Term job now runs T+3 through T+5; WEATHER and TIDE date ranges also reduced accordingly
- **PhotoCast rebrand** — app name changed from "Photo Cast" to "PhotoCast" across all UI files (index.html, LoginPage, ChangePasswordPage, tests)
- **Leaflet dark theme** — custom-styled zoom controls and attribution matching the Plex dark palette (dark backgrounds, gold hover accents, rounded corners, subtle shadow)

### Added (Feb 28, 2026)
- **Manual tide refresh button** — admin Job Runs dashboard now has a "Refresh Tide Data" button alongside the existing forecast run buttons; triggers `POST /api/forecast/run/tide` to refresh WorldTides extremes for all coastal locations on demand
- **Today/Tomorrow labels on date strip** — first two date chips now show "Today · Sat 28 Feb" and "Tomorrow · Sun 1 Mar" with a trailing fade gradient to hint at scrollable overflow
- **Progressive disclosure popup** — map marker popup shows star rating + Claude summary at first glance; location metadata, score bars, and comfort data behind a "More details" toggle
- **Improved information hierarchy** — Claude summary promoted above score bars in the popup layout
- **Score bar tooltips** — Fiery Sky and Golden Hour labels show descriptive tooltips on hover (e.g. "Dramatic colour from clouds catching light")
- **SVG weather icons** — replaced emoji icons with inline SVG thermometer, wind, rain cloud, and droplet icons in both wildlife hourly table and colour comfort rows

### Changed (Feb 28, 2026)
- **Async forecast run endpoints** — all five run endpoints (`/run`, `/run/very-short-term`, `/run/short-term`, `/run/long-term`, `/run/tide`) now return **202 Accepted** immediately and execute asynchronously via `CompletableFuture.runAsync()`; eliminates `ClientAbortException: Broken pipe` errors caused by long-running runs exceeding HTTP/Cloudflare Tunnel timeouts
- **Frontend run buttons** — success messages now show "Forecast run started" instead of waiting for completion; job runs grid refreshes after 3-second delay to pick up the new run
- **Job Runs button layout** — run buttons grouped into a bordered card with labelled sections ("Forecast Runs" and "Data Refresh"); all buttons now gold `btn-primary` instead of a mix of gold and hard-to-see grey

### Fixed (Feb 28, 2026)
- **Today label duplication** — date strip was showing "Today · Today" instead of "Today · Sat 28 Feb"; `formatDateLabel` now accepts a `skipRelative` flag
- **Map popup footer always visible for ADMIN** — forecast generation timestamp (and tide data fetch timestamp for seascape locations) now shows outside the collapsible "More details" section for admin users
- **Map popup score labels** — removed `cursor: help` style that was showing a confusing `?` cursor on hover over Fiery Sky / Golden Hour labels

### Refactored (Feb 28, 2026)
- **Command + Strategy pattern refactoring** — GoF patterns applied to forecast run pipeline
  - `ForecastCommand` record encapsulates run parameters (run type, dates, locations, strategy, manual flag)
  - `ForecastCommandFactory` builds commands from `RunType`, resolving active model and evaluation strategy
  - `ForecastCommandExecutor` executes commands with parallel execution, skip logic (event passed, long-term exists, Opus min rating), and metrics tracking
  - `NoOpEvaluationStrategy` for wildlife/comfort-only locations — returns null evaluation without calling Claude
  - `RunType` enum (VERY_SHORT_TERM, SHORT_TERM, LONG_TERM, WEATHER, TIDE) replaces both `JobName` and `ModelConfigType`
  - `ScheduledForecastService` simplified to thin scheduling wrapper: `commandFactory.create()` → `commandExecutor.execute()`
  - All forecast controller endpoints simplified to two-line create + execute
  - `V29` Flyway migration: renames `job_name` → `run_type` + adds `evaluation_model` on `job_run`; renames `config_type` → `run_type` on `model_selection`
  - `JobName` and `ModelConfigType` enums deleted
  - `JobRunEntity` now tracks both `runType` and `evaluationModel` separately
  - Frontend updated: `metricsApi.js`, `modelsApi.js`, `JobRunsGrid.jsx`, `JobRunsMetricsView.jsx`, `ModelSelectionView.jsx` all use `runType`
  - 357 backend tests (up from 332), 50 frontend tests — all passing with JaCoCo ≥ 80%

### Added (Feb 27, 2026)
- **Opus optimisation gate** — Opus very-short-term runs now skip slots where the most recent prior rating is below 3 stars (or no prior evaluation exists), saving cost and time on low-value forecasts
- **Per-run-type model configuration** — each run type (Very Short-Term, Short-Term, Long-Term) has an independently configurable Anthropic model (Haiku, Sonnet, Opus)
  - `ModelConfigType` enum: `VERY_SHORT_TERM`, `SHORT_TERM`, `LONG_TERM`
  - `V28` migration adds `config_type` column to `model_selection` and seeds one row per type
  - `ModelSelectionService` rewritten with per-config-type get/set and `getAllConfigs()`
  - `GET /api/models` now returns `{ available: [...], configs: { VERY_SHORT_TERM: "HAIKU", ... } }`
  - `PUT /api/models/active` now accepts `{ configType: "...", model: "..." }`
  - Frontend "Model Config" tab split into three sub-tabs with independent model pickers
- **Very-short-term forecast run** — new `POST /api/forecast/run/very-short-term` endpoint (T, T+1) using the VERY_SHORT_TERM model config; new run button in Job Runs dashboard
- **Flat evaluation strategy hierarchy** — Haiku, Sonnet, and Opus all extend `AbstractEvaluationStrategy` directly (Opus previously extended Sonnet); shared `SYSTEM_PROMPT` and `PROMPT_SUFFIX` in the abstract class; strategy differentiation is purely which Anthropic model is used
- **Cloudflare Tunnel** — app publicly live at `https://app.photocast.online` and `https://api.photocast.online`; installed as macOS launchd service, starts at boot
- **Email field on users** — `V27` migration adds `email` column to `app_user`; required when creating a user (basic format validation front and back); displayed in the Users table in ManageView
- **Admin password reset** — `PUT /api/users/{id}/reset-password` generates a secure 12-char temporary password server-side, sets `passwordChangeRequired = true`, returns the raw password once; ManageView shows a modal with copy-to-clipboard
- **Photo Cast rebrand** — app name updated from Golden Hour to Photo Cast; subtitle updated to "AI Driven Sunrise and Sunset Forecasting"; browser tab title updated
- **Username validation** — changed from "must be an email address" to "at least 5 characters" (email is now a separate required field)
- 332 backend tests, all passing

### Added (Feb 25, 2026)
- **Job Run Metrics** — persistent tracking of scheduled forecast runs and API call timings
  - `V20` migration adds `job_run` and `api_call_log` tables with cost tracking
  - `V21` migration adds `consecutive_failures`, `last_failure_at`, `disabled_reason` to locations table
  - `JobRunEntity`, `ApiCallLogEntity` JPA entities with full metrics capture
  - `JobRunService` for recording job starts/completions and API call details
  - `CostCalculator` service for calculating API call costs by service and model
  - Cost configuration in `application*.yml` with per-service pricing (Anthropic, WorldTides, Open-Meteo)
  - `JobMetricsController` endpoints: `GET /api/metrics/job-runs`, `GET /api/metrics/api-calls`
- **Job Metrics Dashboard** — Admin-only view in ManageView showing last 7 days of metrics
  - `JobRunsMetricsView`, `JobRunsGrid`, `JobRunDetail`, `MetricsSummary` React components
  - Sortable/pageable grid with per-service API call breakdown
  - 7-day aggregated statistics: total runs, success rate, slowest service, evaluation count
  - Cost aggregation per run and per job type
- **Retry Robustness** — resilient API failure handling
  - Anthropic 529 (overloaded) retry logic with exponential backoff (1s → 2s → 4s, max 30s, 3 retries)
  - Dead-letter mechanism: locations auto-disabled after 3 consecutive forecast failures
  - `AbstractEvaluationStrategy.invokeClaudeWithRetry()` with detailed logging
  - Request/response interceptor logs all `/api/**` calls at INFO level with timing
- **Docker & CloudFlare Deployment** — production-ready containerization
  - `Dockerfile` — multi-stage build, alpine base, health checks, non-root user
  - `docker-compose.yml` — service definition with volumes, environment variables, restart policy
  - `application-prod.yml` — production Spring Boot config with H2 persistence
  - `goldenhour-backup.sh` — automated daily database backups (keeps last 7)
  - Support for CloudFlare Tunnel exposure without opening router ports
  - Documented cron schedule for backups and scheduled forecast jobs
- 271 backend tests (up from 214), all passing with ≥80% JaCoCo coverage

### Earlier changes
- Wildlife location UI — pure-WILDLIFE locations get a green 🦅 map marker and an hourly comfort timeline in the popup (time · temp · wind · rain); no colour score bars
- Hourly comfort forecasts — one DB row per full UTC hour between sunrise and sunset via a single Open-Meteo call (`getHourlyAtmosphericData`); no Claude evaluation, zero AI cost
- `WILDLIFE` added to `EvaluationModel` enum; `HOURLY` added to `TargetType` enum
- `V19` Flyway migration — adds `temperature_celsius`, `apparent_temperature_celsius`, `precipitation_probability_percent` to `forecast_evaluation`; columns populated on every row (colour and wildlife)
- Comfort data on all colour popups — temp / feels-like / wind / rain shown below colour scores for LANDSCAPE/SEASCAPE locations
- `runWildlifeForecasts()` scheduled at 06:00 and 18:00 UTC for pure-WILDLIFE locations
- `hasColourTypes()` / `isPureWildlife()` location helpers in `ScheduledForecastService` and `ForecastController`
- `WildlifeComfortCard.jsx` — reusable comfort card component
- `conversions.js groupForecastsByDate` collects `HOURLY` rows into a sorted `hourly[]` array, deduplicating by slot + most-recent run
- 257 backend tests (up from 244), 78 frontend tests — all passing

### Session expiry warnings — amber banner at ≤7 days, red at ≤1 day; "Refresh session" button extends by 30 days
- Refresh token rotation — `/api/auth/refresh` revokes the old token and issues a new 30-day one on every call
- `refreshExpiresAt` included in login and refresh responses; stored in localStorage
- `SessionExpiryBanner` component — renders between header and main content when session is close to expiry
- Session countdown (`Session: 30d`) displayed in header below Sign out button for ADMIN users
- "↻ Reload data" button removed — F5 has the same effect and the data auto-reloads after any re-run

### Fixed
- `password_change_required` column add fails on H2 (`ddl-auto: update`) — added `DEFAULT FALSE` to `columnDefinition`
- Session days display shows 30d on fresh login (was 29d due to `Math.floor` rounding)


- JWT authentication — stateless Spring Security with ADMIN and USER roles
- `app_user` table (V10 migration) with default `admin` / `golden2026` account
- `refresh_token` table (V11 migration) for refresh token persistence
- `AppUserEntity`, `RefreshTokenEntity`, `UserRole` — user and token JPA entities
- `JwtService` — access token generation/validation, refresh token hashing (SHA-256)
- `UserService` — implements `UserDetailsService`, user CRUD operations
- `SecurityConfig` — stateless filter chain, disables anonymous auth (unauthenticated → 401)
- `JwtAuthenticationFilter` — extracts and validates JWT on every request
- `JwtProperties` — typed config binding for `jwt.*` in `application.yml`
- `AuthController` — `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout`
- `UserController` — `GET/POST /api/users`, `PUT /api/users/{id}/enabled`, `PUT /api/users/{id}/role` (ADMIN-only)
- `AuthContext` + `AuthProvider` React context for token/role state
- `LoginPage` component — dark-theme login form with `data-testid` attributes
- `authApi.js` — `login()`, `refreshAccessToken()`, `logout()` API calls
- Axios request/response interceptors in `forecastApi.js` — auto-attaches JWT, retries once on 401 with refresh token
- `App.jsx` auth gate — renders `LoginPage` when unauthenticated; logout button in header
- `ViewToggle` hides Manage tab from non-ADMIN users
- User Management section in `ManageView` — table of users with enable/disable toggles and add-user form
- `UserRole` enum: renamed `USER` to `LITE_USER`; added `PRO_USER`
- `POST /api/auth/change-password` endpoint — validates complexity (min 8 chars, upper, lower, digit, special character)
- `V12` migration: `password_change_required` column on `app_user`
- First-login password change gate — `ChangePasswordPage` with live complexity checklist; shown after login when `passwordChangeRequired` flag is set
- ManageView split into Users / Locations sub-tabs
- Add-user form: username must be `"admin"` or a valid email address; show/hide password eye toggle
- CORS moved from deleted `CorsConfig.java` to `SecurityConfig` (`CorsConfigurationSource` bean at filter level)
- Show/hide password toggle on `LoginPage`
- `JwtServiceTest`, `AuthControllerTest`, `UserControllerTest`, `UserServiceTest`, `JwtAuthenticationFilterTest` — new test classes (148 total tests, 0 failures)
- `spring-security-test` added as explicit test dependency for `@WithMockUser`
- `jwt.*` config block added to `application-example.yml`, `application-local.yml`, and test `application.yml`
- SpotBugs exclusions for pre-existing `EI_EXPOSE_REP` issues in `OpenMeteoForecastResponse`, `OpenMeteoAirQualityResponse`, and `LocationEntity`

- `LocationType` enum (`LANDSCAPE`, `WILDLIFE`, `SEASCAPE`) stored as `@ElementCollection` join table (`V8` migration)
- `tideType` converted from single value to `Set<TideType>` via `@ElementCollection` join table (`V9` migration) — supports multiple preferred tides per location (e.g. `LOW_TIDE` + `MID_TIDE`)
- `MID_TIDE` added to `TideType` enum
- `goldenHourType`, `tideType`, and `locationType` wired into `application.yml` location config — YAML is now the source of truth for location metadata and synced to the DB on every startup
- `LocationRepository.findByName()` used by seeder to detect and apply metadata changes for existing locations
- `LocationService.isSeascape()` helper
- `LocationTypeBadges` component — pill badges for golden hour preference (amber), location type (grey), and tide preference (cyan) shown on compact cards, by-location header, and map popups
- Map popup redesigned: prominent title, event time pill inline with title, location type / golden hour type / tide rows, golden & blue hour row; azimuth direction pills removed (lines on map convey this better)
- All popup pills standardised to 11 px / 2 px 8 px padding
- Map popup footer shows "Forecast generated: 23 Feb 2026 13:25" (full date including year) separated by a hairline; `formatGeneratedAtFull()` added to conversions.js

## [0.3.0] - 2026-02-22

### Added
- DB-backed locations table (`V5` Flyway migration) — locations now persist across restarts
- `LocationEntity`, `LocationRepository`, `LocationService`, `LocationController`
- `GET /api/locations` and `POST /api/locations` endpoints
- `@PostConstruct` seed: YAML-configured locations are promoted to the database on first boot
- Runtime add-location form in ManageView — name, lat, lon with client-side validation and inline error display
- `fetchLocations` and `addLocation` added to `forecastApi.js`
- ManageView now fetches its own location list from the API (decoupled from props)
- Exponential backoff retry on Open-Meteo 5xx errors (2 retries, 1 s base, 4xx not retried)
- Multi-location UI: location tabs, by-date strip, compact card grid, map view (Leaflet), and view toggle
- Golden hour and blue hour time-range pills on each ForecastCard
- Per-card Re-run button wired to `POST /api/forecast/run` with specific `targetType`
- `ForecastService.runForecasts()` overload accepting optional `targetType` (null = both)
- `formatShiftedEventTimeUk`, `formatGeneratedAt`, `groupForecastsByLocation` utility functions
- 84 backend unit and integration tests (up from 61); covers LocationRepository, LocationService, LocationController

### Fixed
- `ForecastControllerTest` stub updated to match 5-arg `runForecasts` signature

## [0.2.0] - 2026-02-22

### Added
- Evaluation strategy pattern — `EvaluationStrategy` interface with `HaikuEvaluationStrategy` and `SonnetEvaluationStrategy`
- `@Profile("lite")` activates Haiku; default profile activates Sonnet
- Solar and antisolar horizon model added to Claude system prompt for directional cloud reasoning
- Local H2 dev profile (`-Dspring-boot.run.profiles=local`) — no Docker required for development
- H2 console available at `/h2-console` when running with local profile

### Fixed
- Java 23 test compatibility issue resolved

## [0.1.0] - 2026-02-22

### Added
- Spring Boot 3 REST API with Open-Meteo Forecast and Air Quality integration
- Claude evaluation via Anthropic Java SDK — 1–5 colour potential rating with reasoning
- `SolarService` wrapping `solar-utils` for precise sunrise/sunset times
- `ForecastService` orchestrating Open-Meteo → Claude → PostgreSQL pipeline
- `ScheduledForecastService` running evaluations at 06:00 and 18:00 UTC for T through T+7
- `ForecastController` — `GET /api/forecast`, `GET /api/forecast/history`, `POST /api/forecast/run`, `GET /api/forecast/compare`
- `OutcomeController` — `POST /api/outcome`, `GET /api/outcome`
- Flyway migrations V1–V4 for `forecast_evaluation` and `actual_outcome` tables
- Notification system — email (Thymeleaf HTML), Pushover (iOS push), macOS toast (osascript)
- React 18 frontend with Vite, Tailwind CSS, ForecastTimeline, StarRating, CloudCoverBars, WindIndicator, VisibilityIndicator, OutcomeModal
- `solar-utils` published to GitHub Packages; pulled automatically by Maven

[Unreleased]: https://github.com/gregochr/goldenhour/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/gregochr/goldenhour/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/gregochr/goldenhour/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/gregochr/goldenhour/releases/tag/v0.1.0
