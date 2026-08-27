# Handover: reconstruct the trend peak from the Open-Meteo forecast archive

Self-contained implementation plan. Backend-only. Adds ONE new archive client call family, ONE
new table, and two admin endpoints. **Does not touch** `cloud_verification` (its self-healing
rule would re-verify all 29k rows if any observation were added there — that is the landmine this
design exists to avoid), `PromptBuilder`, the harness, or any scoring rule.

**Do not start until the prompt-change session (veto demotion + blanket rewording) has
committed** — different files, but one landing at a time is this project's working rule.

## Why (all the context you need)

The veto demotion capped the cloud-approach veto at rating 3. Two ground-truth washout fixtures
(Copt Hill 11 & 15 Mar 2026; the user was physically present on the 15th) now rate 3 by design —
the accepted price, bands at {1,2,3}. The named route back to {1,2} is a **measured**
cap-2-when-the-trend-peaked-high rule. Its sizing question, pre-registered: *among the ~545
promptable veto firings in the verified window (2026-02-01 → 2026-08-06), does a high solar-trend
PEAK select a subset whose ERA5-observed horizon cloud is genuinely high — unlike the +3.4pp
separation of the full fired population (fired 36.3 vs not-fired 32.9 observed)?*

The peak cannot be answered from our own data: `CloudApproachDetails.from()` persists only the
trend's endpoints (`solar_trend_earliest_low_cloud`, `solar_trend_event_low_cloud`, V51) and the
`solar_trend_building` boolean; `isBuilding()` computes the peak internally and discards it. The
15 Mar shape (52 → 100 → 100 → 20) persists as (52, 20). Batch prompts are not stored.

Open-Meteo can answer it: its **historical forecast archive** stores what the forecast models
said at the time, not what the sky did. For each fired slot we hold location, solar azimuth,
event time and `days_ahead` — enough to re-ask "what was the forecast hourly low cloud at the
113 km solar-horizon point, T-3h through event?", whose maximum is the peak.

## The approximation, and how it is measured rather than assumed

The archive may not serve the exact model run the pipeline used. This plan makes fidelity a
**measured, gated quantity** instead of an assumption:

- For every reconstructed row we already hold the true endpoints the pipeline saw (the two V51
  columns). Compute `|recon_earliest − persisted_earliest|` and `|recon_event − persisted_event|`
  per row; report their means and the share of rows with both ≤ 10pp.
- **Fidelity gate, pre-registered:** if mean absolute endpoint error exceeds ~10pp, the
  reconstruction is not trustworthy enough to size a rating rule — report says so and the cap-2
  question falls back to forward data (persist the peak going forward; separate item). Do not
  soften this gate to rescue the analysis.
- Complication to handle, not hide: the trend was read at the un-coned CENTRE point until F1
  (#294, 2026-07-25) and as the 3-point cone average after. Fetch all three cone bearings
  (`DirectionalSamplingGeometry.computeSolarConePoints`) — 3 points × 4 hours per row is still
  tiny — compute both centre-only and coned variants, and use whichever matches that row's
  persisted endpoints better; report fidelity per era so a pre/post-F1 split in accuracy is
  visible.
- Lead-time matching: prefer requesting the archived forecast at the lead matching the row's
  persisted `days_ahead` (Open-Meteo's previous-runs API exposes `*_previous_dayN` hourly
  variables) and fall back to the plain historical-forecast API (best available lead) when the
  variable/lead is unsupported. Record which source served each row.

## Changes

### 1. Client

New `OpenMeteoForecastArchiveClient` beside `OpenMeteoArchiveClient` (which is hard-wired to
`https://archive-api.open-meteo.com` — do not overload it): base URL
`https://historical-forecast-api.open-meteo.com` (and/or
`https://previous-runs-api.open-meteo.com` for lead-matched fetches — verify variable support
against their docs at implementation time). Same Resilience4j decoration pattern (`@Retry`,
`@CircuitBreaker`, `@RateLimiter` on the shared `open-meteo` instances). Request
`cloud_cover_low` hourly for the event date, per coordinate.

### 2. Persistence

One migration — ⚠️ read the next number from the tree ON MAIN at implementation time
(`ls backend/src/main/resources/db/migration/ | sort -V | tail -1`; V142 as of this writing, but
this repo merges daily and two V-numbers have collided before). Table `trend_peak_recon`:
`forecast_evaluation_id` (PK, FK), `recon_peak_low_cloud`, `recon_earliest_low_cloud`,
`recon_event_low_cloud`, `variant` (CENTRE/CONED — whichever matched), `source`
(LEAD_MATCHED/BEST_AVAILABLE), `endpoint_abs_error` (mean of the two), `fetched_at`.
Insert-only, keyed to the evaluation row; re-running the reconstruction overwrites by PK
(idempotent, resumable via anti-join — same pattern as the verification backfill).

### 3. Population

Rows in the verified window with the veto's two triggers AND promptable gap: mirror — do not
re-derive — the predicates the report already uses (`CloudVerificationPair.vetoFired()`'s
two-trigger logic and the ≤80 triage cut; see `CloudVerificationService.forecastGapUnderTriageCut`
and `TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT`). Cross-check: the selected count for 2026-02-01→2026-08-06
must land at ~545 (the report's `vetoFired&underTriageCut(<=80)` count for that window) — assert
the query against that number in a test with fixtures, and log the production count at run time.
Also reconstruct the not-fired promptable population? NO — the baseline (32.9 observed) is
already served by the report; only fired rows need peaks.

### 4. Endpoints (ADMIN, mirror the verification backfill's shape)

- `POST /api/admin/trend-peak-recon/run` — async, `202` immediately, `409` if running, works in
  committed batches, status object with counts + `lastError` (a batch returning nothing stops
  the run rather than blanking the backlog — same paired rules as the verification backfill).
- `GET /api/admin/trend-peak-recon?from=&to=` — the sizing report. Joins `trend_peak_recon`
  against the ERA5 observations (`cloud_verification`) for the same evaluations and returns:
  - fidelity block: row count, mean endpoint errors, ≤10pp share, per-era (pre/post F1) split,
    per-source split — the gate quantities, first;
  - peak bands `<80` / `>=80` / `>=95`, each with count and mean ERA5 `observedGapLow`, beside
    the not-fired baseline (32.9) and the full fired mean (36.3), both stated as constants of
    the frozen window with a comment saying where they came from;
  - the two March fixture dates called out by name if present in the population (they are the
    ground truth this exists to serve).

### 5. The decision this feeds (NOT this task's to make)

Pre-registered in the round-2/3 stop-point rulings: a non-trivial high-peak band clearly
cloudier observed (dwarfing +3.4pp) AND fidelity gate passed → the cap-2-on-high-peak rule ships
as its own gated prompt change, which also tightens the two March fixture bands back to {1,2}.
Peak not discriminating, or fidelity gate failed → cap-3 stands; persist the peak forward (one
column beside V51's fields, where `isBuilding()` already computes it) and re-ask on live data in
a few months. Either way this task only measures.

## Tests

House rules apply (`docs/engineering/test-improvement-standards.md`): member + non-member per
band, edges pinned (a peak exactly 80 and exactly 95; a gap exactly 80 for the promptable cut; a
single-trigger row excluded from the population), the variant-selection logic tested with a
fixture whose centre and coned endpoint errors differ, the fidelity means hand-counted, and the
population query pinned against fixtures whose count is asserted as a literal. Client tests mock
the HTTP layer; no live calls in the suite. JaCoCo 80%/class; Checkstyle 120-char/javadoc/no
HTML on a `@param`'s first line.

## Verification ladder (exit codes, never grepped output)

```bash
cd backend && ./mvnw compile -q >/tmp/c.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw test -Dtest="TrendPeak*,OpenMeteoForecastArchive*" -q >/tmp/t.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

## Commit

`feat(verification): reconstruct the veto trend peak from the forecast archive` — conventional
commit, CHANGELOG under `[Unreleased]` (state plainly that this measures; no rating changes).
NEVER push; never create tags; never `git checkout --` anything you did not write.

## Out of scope — do not touch

- `cloud_verification` table, its sampling, backfill, or `deleteIncompleteVerifications` — adding
  any observation there triggers a full 29k-row re-verification. The new table exists precisely
  so this cannot happen.
- `PromptBuilder`, the sky-rating harness, fixture bands — the cap-2 rule and the {1,2}
  restoration are a separate, later, gated change.
- Prompt regression tests — user-owned, never edit.
- `CloudApproachDetails` / V51 forward persistence of the peak — a separate one-column follow-up,
  referenced here but not built here.
