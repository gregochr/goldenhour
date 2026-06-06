# Hot Topics ↔ Visitors investigation (READ-ONLY)

**Date:** 2026-06-05
**Scope:** Map how Hot Topics work today and answer whether Hot Topics and the
Visitor evaluation model are orthogonal (→ share a derivation utility) or
redundant (→ one subsumes the other). No code or design changes — findings only.
Trust the code over prior session notes; disagreements are flagged inline.

---

## 1. Hot Topics mechanism, end-to-end

### 1.1 Types

**`HotTopic`** — `model/HotTopic.java` (record, implements `Comparable<HotTopic>`).
Fields:
- `String type` — e.g. `"BLUEBELL"`, `"SPRING_TIDE"`, `"AURORA"`
- `String label` — pill text (`"Bluebell conditions"`)
- `String detail` — supporting line
- `LocalDate date` — day the topic applies to
- `int priority` — lower = more important (natural order: priority asc, then date asc)
- `String filterAction` — optional map-filter key applied on tap (`"BLUEBELL"`), nullable
- `List<String> regions` — best regions, may be empty
- `String description` — 1–2 sentence explanation, nullable
- `ExpandedHotTopicDetail expandedDetail` — structured drill-down, nullable

There is **no per-location score field**. A `HotTopic` is a platform/region-level
signal, not a contribution to any location's rating.

**`HotTopicStrategy`** — `service/HotTopicStrategy.java`. Single-method contract:
```java
List<HotTopic> detect(LocalDate fromDate, LocalDate toDate);
```
Contract note in the Javadoc: strategies are **read-only consumers of
already-fetched data** — they must not make external API calls, and run after the
triage cycle completes. There is **no `appliesTo`-style gate** on the interface;
each strategy self-gates inside `detect()` (e.g. seasonal window, alert level).

**`HotTopicAggregator`** — `service/HotTopicAggregator.java` (`@Service`).
Constructor injects `List<HotTopicStrategy>` (all beans, auto-collected) plus
`HotTopicSimulationService`. `getHotTopics(from, to)`:
- if simulation enabled → returns `simulationService.getSimulatedTopics(...)` and
  **skips all real strategies entirely**;
- else → streams every strategy, flat-maps `detect()`, sorts by natural order,
  collects.

**`HotTopicDetector`** — does **not exist**. The "detector" role is the per-strategy
`detect()` method. (Prior notes referencing a `HotTopicDetector` are inaccurate.)

### 1.2 Built vs planned strategies

**BUILT (4 real `@Component` beans):**

| Strategy | File | Fires when | Data source |
|---|---|---|---|
| `BluebellHotTopicStrategy` | `service/BluebellHotTopicStrategy.java` | bluebell season + best persisted `bluebell_score ≥ 6` | DB: `findBluebellLocations()` + `findBluebellEvaluations()` reading the **persisted `bluebell_score`/`bluebell_summary`** columns |
| `AuroraHotTopicStrategy` | `service/AuroraHotTopicStrategy.java` | alert level ≥ MINOR tonight, or cached Kp forecast peak ≥ 4.0 tomorrow | In-memory `AuroraStateCache` + cached NOAA/aurora-summary state |
| `SpringTideHotTopicStrategy` | `service/SpringTideHotTopicStrategy.java` | a spring-but-not-king tide exists in window (suppressed if any king tide present) | Cached briefing `BriefingService.getCachedDays()` → `BriefingSlot.TideInfo` |
| `KingTideHotTopicStrategy` | `service/KingTideHotTopicStrategy.java` | a king tide exists in window | Cached briefing `BriefingService.getCachedDays()` → `BriefingSlot.TideInfo` |

**PLANNED / simulation-only (not detected by any real strategy):**
STORM_SURGE, DUST, INVERSION, SUPERMOON, SNOW_FRESH, SNOW_MIST, SNOW_TOPS, NLC,
METEOR, EQUINOX, CLEARANCE. These exist **only** as hardcoded templates in
`HotTopicSimulationService.ALL_SIMULATIONS` (15 types total; 4 real, 11 sim-only).

### 1.3 Trigger / lifecycle / persistence / API

- **Computed** in two places, both in `BriefingService`:
  1. `refreshBriefing()` (post-batch, orchestrated by the pipeline) →
     `hotTopicAggregator.getHotTopics(today, today+3)`, then
     `BluebellGlossService.enrichGlosses(...)`, baked into `DailyBriefingResponse`.
  2. `getCachedBriefing()` (every read) **re-overlays** live `getHotTopics(...)` on
     top of the cached briefing — so simulation toggles take effect immediately
     without a full refresh.
- **Persisted** as JSON inside the single-row `daily_briefing_cache.payload`
  (the whole `DailyBriefingResponse`); reloaded into memory on startup via
  `@PostConstruct`. Hot topics are also recomputable on demand via the read overlay.
- **API:** `GET /api/briefing` → `DailyBriefingResponse.hotTopics`
  (`controller/BriefingController.java`).
- **Simulation:** `HotTopicSimulationService` (volatile in-memory, resets on
  restart) + `HotTopicSimulationController` (`/api/admin/hot-topics/simulation`,
  ADMIN-only: master toggle + per-type toggle). When enabled it **replaces** real
  detection wholesale.

### 1.4 Strategy → data-source table (the orthogonality-critical bit)

| Phenomenon | Hot-topic reads | Visitor reads | Same derivation? |
|---|---|---|---|
| Bluebell | persisted `bluebell_score` (Claude's number, see §3.1) | *(no visitor yet)* | would share the column if a visitor reads it |
| Tide (spring/king) | `BriefingSlot.TideInfo` from `BriefingSlotBuilder` | `TideSnapshot`/`TideContext` from `ForecastDataAugmentor.deriveTideContext` | **shared atoms, duplicated assembly** (see §3.2) |
| Aurora | `AuroraStateCache` + NOAA cache | *(no colour-rating visitor; aurora has its own `ClaudeAuroraInterpreter` pipeline)* | no overlap |
| Inversion | *(not built — sim only)* | *(not built)* | n/a; would share persisted `inversion_score` if both built |
| NLC | *(not built — sim only)* | *(not built)* | n/a |

---

## 2. The orthogonality verdict

**Verdict: (A) Orthogonal — shared atoms, but the higher-level assembly is
currently duplicated.** Not (B) redundant; not (C) already coupled at the
derivation seam.

Evidence:

1. **A hot topic outputs a region/platform-level signal, not a location score.**
   `HotTopic` has no rating field; it carries `regions`, `priority`, `filterAction`.
   `SpringTideHotTopicStrategy` fires for *all coastal locations in a region*
   regardless of whether any single location scores well — it never touches a
   location's 1–5 rating.
2. **A visitor outputs a per-location 1–5 that averages into the rating.**
   `Visitor.evaluate(...) : OptionalInt`, combined by `RatingCombiner.combine()`
   (plain mean + `Math.round`). `TideVisitor` returns 5/4/3/1 per location.
3. **They are different *outputs*** — one a platform pill, one a location score —
   so they are orthogonal by construction. They are not expressible in terms of
   each other: a hot topic is not "a visitor scored ≥ N across a region" because
   the spring/king hot topics consult statistical tide *heights* (P95 /
   spring-threshold) that the visitor never uses, and the visitor consults
   `tideAligned`/`widenedAligned`/`lunarTideType` per location, which the hot topic
   does not fully use.
4. **But they currently DUPLICATE the assembly of the shared facts.** Tide is the
   proven case: both paths bottom out in the same atoms —
   `TideService.deriveTideData` / `calculateTideAligned` and
   `LunarPhaseService.classifyTide` — yet assemble them independently:
   - hot-topic path: `BriefingSlotBuilder.buildTideResult()` → `BriefingSlot.TideInfo`
   - visitor path: `ForecastDataAugmentor.deriveTideContext()` →
     `buildTideSnapshot()` → `TideSnapshot`/`TideContext`
   Same library functions, two builders. So the clean future is exactly the §A
   shape: **keep the two concepts separate, but have both consume one shared
   derivation utility** instead of two parallel builders. They are *not* already
   coupled (so future work won't accidentally fork a single path — the fork
   already exists), and they are *not* redundant (neither subsumes the other).

Per dual-list phenomenon (shared-derivation vs duplicated):
- **Tide** — shared *atoms* (`TideService`, `LunarPhaseService.classifyTide`),
  **duplicated assembly** (`BriefingSlotBuilder` vs `ForecastDataAugmentor`).
  Convergence candidate of the same class as the prior sky/tide decomposition arc.
- **Bluebell** — would be **shared via the persisted `bluebell_score` column** if a
  visitor reads it (both hot topic and any column-reading visitor see Claude's
  number). See §3.1 for the subtlety that the *deterministic* template score is a
  separate number.
- **Aurora** — **no overlap.** Aurora is not in the colour-rating visitor model at
  all; it has its own `ClaudeAuroraInterpreter` rating pipeline. The aurora hot
  topic reads cached space-weather state. Nothing to share.
- **Inversion** — neither built as hot topic nor visitor. If both are built they
  *could* share the persisted `inversion_score` column (same deterministic-gate +
  Claude-number pattern as bluebell, see §3.1).
- **NLC** — neither built; simulation template only. Nothing to share yet.

---

## 3. Forward-looking answers

### 3.1 Bluebell: how `bluebell_score` is derived, and would a visitor share it?

**Correction to prior notes.** A sub-investigation claimed "single derivation
pipeline: `BluebellConditionService` → augmentor → DB → strategy reads the service
score." That is **wrong**. There are **two bluebell numbers**:

1. **Deterministic template score** — `BluebellConditionService.score(AtmosphericData,
   BluebellExposure)` returns a `BluebellConditionScore` (0–10) from wind / visibility
   / dew-point / temp / precip / cloud, weighted by `WOODLAND` vs `OPEN_FELL`
   (`bluebell_exposure`, enum on the location, added in V84). Attached to
   `AtmosphericData` via `withBluebellConditionScore()` by
   `ForecastDataAugmentor.augmentWithBluebellConditions()`.
2. **Claude's score** — the deterministic score is **only** used to (a) gate, and
   (b) condition the prompt: `PromptBuilder` emits a `BLUEBELL CONDITIONS:` block
   (score, exposure, flags) and asks Claude to return `bluebell_score` +
   `bluebell_summary`. Claude's reply becomes `SunsetEvaluation.bluebellScore()`.

What actually gets **persisted** (`ForecastService` ~line 677):
```java
.bluebellScore(data.bluebellConditionScore() != null ? evaluation.bluebellScore() : null)
.bluebellSummary(data.bluebellConditionScore() != null ? evaluation.bluebellSummary() : null)
```
i.e. the column stores **Claude's number, gated by the deterministic score being
non-null**. The entity Javadoc ("populated by Claude during bluebell season") is
correct; the prior "service score is persisted" claim is not.
(The exact same pattern governs `inversion_score`: deterministic
`InversionScoreCalculator` gates, Claude's value persists.)

`BluebellHotTopicStrategy` reads the **persisted column** (= Claude's number).

So for a future **Bluebell visitor**, there are two distinct options, and the
choice is a real fork:
- **Read the persisted `bluebell_score`** (Claude's number) → it would share the
  *exact same fact* the hot topic reads. Available today via
  `evaluation.bluebellScore()` at the combine seam.
- **Read the deterministic `BluebellConditionScore`** (the template) → this is
  *not* currently reachable from a visitor: `VisitorContext` carries only
  `(evaluation, tide)`; the deterministic score lives on `AtmosphericData`, which
  is not threaded into `VisitorContext`. Choosing this would require plumbing.

Note the architectural divergence: `TideVisitor` is **purely deterministic** (no
Claude), whereas the only bluebell number that currently survives to the DB is
**Claude's**. A bluebell visitor that wants to match the tide visitor's
"deterministic, Claude-independent" character would need the template score
plumbed into `VisitorContext`; a bluebell visitor that just reads the column would
be Claude-dependent but trivially shares with the hot topic.

### 3.2 Tide: does the spring/king hot topic read the same `TideSnapshot` the visitor uses?

**No — it reads a parallel structure built independently, but from the same atoms.**
- Visitor: `TideVisitor` ← `TideContext`/`TideSnapshot` ←
  `ForecastDataAugmentor.deriveTideContext` → `buildTideSnapshot` (calls
  `tideService.deriveTideData`, `calculateTideAligned`, `lunarPhaseService.classifyTide`).
  Visitor uses `tideAligned`, `widenedAligned`, and `lunarTideType` only.
- Hot topic: `Spring/KingTideHotTopicStrategy` ← `BriefingSlot.TideInfo` ←
  `BriefingSlotBuilder.buildTideResult` (also calls `tideService.deriveTideData`,
  `calculateTideAligned`, `lunarPhaseService.classifyTide`) **plus** a
  statistical king/spring test from tide-height stats (`P95HighMetres`,
  `springTideThreshold`). The strategies treat a slot as king/spring if **either**
  the statistical flag **or** `lunarTideType` says so (`isKingTide() ||
  lunarTideType() == KING_TIDE`).

So: **shared atomic utilities, duplicated higher-level assembly, and the hot topic
additionally uses a statistical (height-based) signal the visitor ignores.** This
is a single-code-path convergence candidate of the same class as the prior
sky/tide decomposition — a shared "tide derivation" that both `BriefingSlotBuilder`
and `ForecastDataAugmentor` consume would remove the fork. (Read-only finding; no
change made.)

### 3.3 Does any "capped bonus" mechanism already exist?

**No.** `RatingCombiner.combine()` is a flat arithmetic mean of applicable
visitor scores, `Math.round`-ed, returning `null` if none apply. There is no
weakest-link, no veto, and crucially **no notion of a bonus that nudges within a
band but cannot break out of it.** Nothing elsewhere (hot-topic side included)
models a band-capped bonus.

This confirms the flagged problem: under plain averaging, an open-fell bluebell
**bonus** (e.g. sky 3 + bluebell 5 → mean 4) *does* break the band — exactly what
the original design said a bonus must never do. A future Bluebell visitor will
need a capped-bonus combine rule that does not exist today. **Flagged only — not
solved here.**

---

## 4. Spotted / parked

- **Prior-notes corrections** (trust-the-code): (1) no `HotTopicDetector` class
  exists; (2) the persisted `bluebell_score` is **Claude's** number gated by the
  deterministic template, not the `BluebellConditionService` score itself.
- **Prompt unit mismatch (parked):** `PromptBuilder` asks Claude for
  `bluebell_score (integer, 0-100)` while the deterministic `BluebellConditionScore`
  and the hot-topic thresholds (`≥ 6`, `≥ 8`) are on a 0–10 scale. Worth verifying
  the persisted scale matches what `BluebellHotTopicStrategy` thresholds assume.
- **Tide-derivation fork (parked, convergence candidate):** `BriefingSlotBuilder`
  vs `ForecastDataAugmentor` independently assemble tide facts from the same
  atoms — a future shared utility would serve both the spring/king hot topics and
  `TideVisitor`.
- **`VisitorContext` is narrow:** carries only `(evaluation, tide)`. Any future
  visitor that needs deterministic atmospheric derivations (bluebell template,
  inversion template) would require extending it.
- **Combiner has no capped-bonus rule** — blocker for the Bluebell visitor (§3.3).

---

## Bottom line (the two facts most needed next)

1. **Hot Topics and Visitors are orthogonal (verdict A), not redundant.** They are
   different outputs (region pill vs location score) that today *duplicate* the
   assembly of shared facts. The clean future is **two concepts, one shared
   derivation utility** — most concretely a single tide-derivation path consumed by
   both `BriefingSlotBuilder` and `ForecastDataAugmentor`.
2. **A future Bluebell visitor can share the hot topic's fact only if it reads the
   persisted `bluebell_score` (Claude's number).** That column is what the hot
   topic reads. The *deterministic* `BluebellConditionService` score is a separate
   number that conditions the prompt and gates persistence, and is **not** currently
   reachable from a visitor (`VisitorContext` doesn't carry it). Picking which of
   the two a Bluebell visitor consumes is a deliberate fork, not a default.
