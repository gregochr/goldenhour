# Aurora viewline investigation — why the line sat over North Yorkshire on 10 Oct 2026

**Status: FINDINGS COMPLETE from the code; the 10 Oct log/DB reconstruction (§5) and the owner
questions (§6) are OPEN. No viewline code changes until §6 is answered** — the design bundle's
own instruction (`docs/design/aurora-heat/README.md`, Task 3), kept.

Written 2026-10-11 against `7b2358df` (v2.25.1). Line numbers are from that commit. The
investigation is Task 3 of `docs/engineering/aurora-heat-plan.md` (phase A5/A6).

## 0. The one-paragraph answer

The line the app draws is not where the aurora was. It is a **hard-coded, geographic Kp→latitude
table** (`NoaaSwpcClient.KP_LATITUDE_CAP`: `4→58, 5→56, 6→54, 7→52, 8→50, 9→48`), read with the
Kp **truncated to an integer**, fed with the Kp **frozen at the moment the alert fired** and
never updated unless the alert *level* escalates — and the same table **caps the live OVATION
line** (`max(lat, cap)`), so the live product can never draw further south than the table allows
either. The table's figures are NOAA's own *geomagnetic* boundary numbers used as if they were
*geographic*, which for the UK puts every row about 2° too far north before truncation costs up to
another 2°, and the boundary NOAA describes is the oval's equatorward edge, from which the aurora
"can often be observed hundreds of kilometers equatorward". On a G3 night with the stored Kp in
[6, 7) every one of those compounds in the same direction, and the line lands at 54°N.

## 1. What was on screen

The owner's screenshot (22:43 BST, 10 Oct 2026): banner `STRONG · G3 · AURORA ACTIVE NOW ·
DETECTED 19:47 · Kp 7 · 222 dark-sky locations clear`; map in aurora mode; a **dashed amber** line
at roughly 54°N. Dashed amber is `AuroraViewlineOverlay`'s forecast styling (`isForecast === true`:
`#ff9900`, `dashArray '10, 6'`); the live line is solid green `#33ff33`.

## 2. The forecast path, end to end

| step | where | what it does |
|---|---|---|
| Frontend picks the path | `hooks/useAuroraViewline.js:40-95` | `triggerType === 'forecast'` → `GET /api/aurora/viewline/forecast`, keyed `forecast:${forecastKp}`; otherwise `GET /api/aurora/viewline` polled every 5 min. The forecast key re-fetches **only when `forecastKp` changes**. |
| Controller | `AuroraController.java:224-233` | Reads `stateCache.getLastTriggerKp()`; null → inactive response; else `noaaClient.buildForecastViewline(kp)`. No `isActive()` check, no request parameter. |
| The table | `NoaaSwpcClient.java:106-116` | `KP_LATITUDE_CAP = {4: 58.0, 5: 56.0, 6: 54.0, 7: 52.0, 8: 50.0, 9: 48.0}`, `DEFAULT_KP_CAP = 60.0` for Kp < 4, `0.0` for Kp ≥ 10. Javadoc: "based on real-world UK aurora observer reports", latitudes where a photographer "has a realistic chance of capturing aurora on camera", "naked-eye visibility will typically be 1-2° further north". |
| The lookup | `getKpLatitudeCap`, `:700-706` | `int kpFloor = (int) kp` — **truncation**, no rounding, no interpolation: 6.67 → row 6 → 54.0. |
| The line | `buildForecastViewline`, `:760-768` | A flat two-point line `(-12°, cap)`–`(4°, cap)`, `isForecast=true`, `forecastTime=now`. |
| Which Kp | `AuroraStateCache.lastTriggerKp` | Written only by `updateTrigger`, called only from `AuroraOrchestrator.scoreAndCache` (`:475`), i.e. **only on a NOTIFY**. Forecast trigger → `maxKpRestOfTonight`; real-time trigger → the Kp for now. Nulled on CLEAR. |
| When it changes | `AuroraStateCache.notifies`, `:257-261` | NOTIFY only from IDLE, during a simulation, or when `incoming.severity() > current.severity()`. A reading at the **same level** is SUPPRESS and touches nothing. Levels are coarse: STRONG ≥ 7, MODERATE ≥ `kp-threshold` (5.0 in prod), MINOR ≥ 4. |

So: an alert raised at 19:47 on a rest-of-tonight maximum of, say, 6.67 keeps `lastTriggerKp =
6.67` and a line at 54.0°N for the rest of the night. If NOAA later revised the forecast to 7+ or
the live Kp reached 7, the level would escalate MODERATE → STRONG and the trigger would be
rewritten (to REALTIME with the Kp for now, if the forecast alone did not reach STRONG — then the
frontend switches to the live path). But **within a level nothing moves**: 5.33 → 6.67 is still
MODERATE, and the line stays where the first reading put it.

⚠️ The screenshot shows `STRONG · G3` *and* a dashed forecast line at ~54°N. `gScale` is derived
from `lastTriggerKp` (`AuroraController.java:175-176`), so G3 means the stored Kp was ≥ 7 at
screenshot time, which the table maps to 52°N, not 54°N. The two readings on one screen are
inconsistent under the rules above unless (a) the status poll had refreshed and the viewline hook
had not (the hook re-fetches only on a `forecastKp` change, and the 5-minute retry stops once a
first answer lands), or (b) the stored Kp was in [6, 7) and the STRONG level came from the
OVATION raise (`raisedByOvation` only raises to MODERATE, so not that) or from the Kp for now on a
night poll. **This is exactly what §5's reconstruction must settle** — it is the difference
between "the table is wrong" and "the table is wrong *and* the frontend was stale".

## 3. The live path, and why it cannot rescue the forecast one

| step | where | what it does |
|---|---|---|
| Product | `NoaaSwpcClient` | `https://services.swpc.noaa.gov/json/ovation_aurora_latest.json` — the OVATION Prime nowcast grid (probability per 1°×1° cell), fetched separately from `fetchOvation()` (the ~900 KB grid is downloaded twice). |
| Southernmost | `parseViewline`, `:598-666` | Longitudes −12..4 only; per integer longitude, the **minimum latitude with probability ≥ 5%** (`VIEWLINE_THRESHOLD = 5`, lowered from 10 after a Kp 6 night visible from Northumberland put the line north of Edinburgh — `docs/pipeline-reference.html:1033-1050`); smoothed ±2 neighbours; `southernmostLatitude` = min of the smoothed line. No continuity check (a stray 5% cell wins). |
| Which Kp | `AuroraController.resolveCurrentKp`, `:244-261` | Last published Kp **reading** (`fetchKp()`'s last row, 15-min cache); else the max of the whole 3-day forecast; else 4.0. |
| **The cap** | `applyKpCap`, `:733-749` | **Every** point becomes `max(lat, KP_LATITUDE_CAP[(int) kp])`, and so does `southernmostLatitude`. Runs on every call, including on a cache hit. The cap can only move the line **north**. |

So the live line is `max(OVATION 5% contour, table[(int) latest published reading])`. The table
governs both lines. On 10 Oct, with the latest *published* 3-hour reading possibly still 6.67
while the estimate for the running block was 7+, the live line would have been capped at 54°N as
well — the same latitude the forecast line drew — so switching the frontend to the live path
would not, on its own, have moved the line.

## 4. What the table is, against what NOAA says

NOAA SWPC, *Tips on viewing the aurora* (`https://www.spaceweather.gov/content/tips-viewing-aurora`):

> "At Kp = 0, the equator ward edge of the auroral oval is approximately 66 degrees" … it
> "moves equatorward about 2 degrees for each level of Kp" … "until reaching Kp of 9 at 48
> degrees magnetic latitude." … "This relationship holds true in **geomagnetic** latitude, not
> geographic." … "these figures do not indicate where the aurora may be but rather the point from
> which it may be observed" … "the aurora can often be observed **hundreds of kilometers**
> (miles) equatorward of the actual aurora."

That gives, in geomagnetic latitude: Kp 4 → 58, 5 → 56, 6 → 54, 7 → 52, 8 → 50, 9 → 48. **These
are the app's table, row for row.** The app applies them as geographic latitude.

A centred-dipole conversion (IGRF-14 2025 pole ≈ 80.9°N 72.6°W; the full IGRF/AACGM figure for
the UK is a little larger, ~+2.5°, which only strengthens the point):

| place | geographic | geomagnetic | Δ |
|---|---|---|---|
| Edinburgh | 55.95 | 58.1 | +2.2 |
| Durham | 54.78 | 56.8 | +2.0 |
| Birmingham | 52.49 | 54.6 | +2.1 |
| London | 51.50 | 53.4 | +1.9 |
| Brighton | 50.82 | 52.7 | +1.9 |

So NOAA's Kp 6 boundary (54° geomagnetic) is ≈ **52°N geographic** over the UK — Birmingham, not
North Yorkshire — and Kp 7 (52° geomagnetic) is ≈ **50°N**, the Channel coast. The app's table is
therefore about **2° too far north at every row**, before:

- **truncation** costs up to a further 2° (a 6.9 reads as 6);
- the boundary is the oval's **edge**, from which NOAA says the aurora is observed hundreds of
  kilometres further equatorward on the horizon — the table's own javadoc frames it as a
  *camera-visible* line, which should sit **south** of the edge, not on it;
- the 1–2° naked-eye-vs-camera distinction the javadoc mentions is in the opposite direction from
  the error the table actually carries.

The javadoc's "based on real-world UK observer reports" is not evidenced anywhere in the repo
(no measurement file, no issue, no changelog entry names the reports). It should be read as a
**claim**, not a calibration.

Also worth knowing: NOAA's own *Aurora Viewline for Tonight and Tomorrow Night* is a
North-America-only experimental product built from the max forecast Kp 6pm–6am Central; there is
no published machine-readable UK equivalent, so a server-side table of some kind stays
necessary for the forecast line. The question is which table, in which latitude system.

## 5. The 10 Oct night, reconstructed (DONE 2026-10-11, from the host log)

Nothing logs the viewline that was served (no INFO line in `fetchViewline`, `buildForecastViewline`,
`applyKpCap` or the controller), so it was inferred from the poller's lines
(`grep -hE "Aurora (NOTIFY|night poll|scoring complete|event ended)|Forecast lookahead"
~/goldenhour-data/logs/goldenhour*.log | grep 2026-10-10` — the rolled file, since the log rolls
daily). Log times are **UTC**; the banner's `DETECTED 19:47` (BST) is the 18:47 NOTIFY.

| UTC | poller line | stored trigger / Kp | table row → line |
|---|---|---|---|
| 00:14–04:44 | night polls, QUIET (rest of tonight 2.67) | — | — |
| **16:14** | `Forecast lookahead: maxKp=5.67 level=MODERATE action=NOTIFY`, 220 candidates (Bortle ≤ 4), `highest=1★` | FORECAST_LOOKAHEAD / **5.67** | 5 → **56°N**, dashed amber |
| 16:47–18:17 | forecast 5.67 → SUPPRESS ×4 | unchanged | 56°N |
| **18:47** | `night poll: level=STRONG from REALTIME (rest of tonight Kp 5.67, Kp now 7.0) action=NOTIFY`, 249 candidates (Bortle ≤ 5), `highest=1★` | REALTIME / **7.0** | live path: `max(OVATION 5% contour, 52°N)` — **never south of 52°N**, solid green |
| 19:22 | STRONG from REALTIME (5.67 / 7.0) → SUPPRESS | unchanged | ≥ 52°N |
| 19:52–20:52 | STRONG from FORECAST_LOOKAHEAD (forecast revised to **7.0** / 7.0) → SUPPRESS ×3 | unchanged (same level: nothing rewritten) | ≥ 52°N |
| **21:21** | **v2.25.1 deploy restarted the backend → cache IDLE.** First poll: `level=MODERATE from FORECAST_LOOKAHEAD (rest of tonight Kp 6.67, Kp now 6.67) action=NOTIFY`, 220 candidates (Bortle ≤ 4), `highest=1★` | FORECAST_LOOKAHEAD / **6.67** | `(int) 6.67 = 6` → **54°N**, dashed amber |
| 22:00–23:30 | MODERATE (6.67 / 6.67) → SUPPRESS ×4 | unchanged | 54°N |

**What this settles.**

- §2's inconsistency is resolved, and no frontend staleness is needed to explain it: the 22:43 BST
  (21:43 UTC) screenshot is **after the restart**. It shows the *second* alert of the night — a
  MODERATE on a 6.67 forecast, truncated to row 6, 54°N, dashed amber. The `STRONG · G3 ·
  DETECTED 19:47` banner was a different, earlier screenshot of the first alert.
- Before the restart (18:47–21:21) the trigger was REALTIME/7.0, so the frontend was on the **live**
  path — but `applyKpCap` clamps that line to `table[7] = 52°N`, so the best the app could have
  drawn through the whole G3 window was a line over Birmingham, while aurora was photographed on
  the south coast.
- The restart's own cost: STRONG → MODERATE (Bortle ≤ 5 → ≤ 4, 249 → 220 candidates), a fresh
  NOTIFY (alerts re-sent), and the forecast-truncation error swapped in for the live-cap error.
- Every `scoring complete` that night reads `highest=1★` — the missing-system-prompt bug #1076
  fixed, visible here as its own line.
- The forecast was revised upward during the alert (5.67 → 7.0 at 19:52) and the stored Kp did
  not follow, exactly as §2 predicted (same level → SUPPRESS → nothing rewritten). On this night it
  was harmless only because the trigger was already REALTIME/7.0.

**What the corrected rule would have drawn** at 21:43 UTC for the stored 6.67: interpolated row
**52.7°N**; NOAA's rule in geomagnetic latitude converted for the UK (−2°) **≈ 50.7°N**; with the
"hundreds of km equatorward" horizon allowance (3–5°) **≈ 46–48°N** — south of the UK entirely,
which is what was observed. For the live window's 7.0: 52 → 50 → ≈ 46°N.

The live line itself (the OVATION contour under the cap) is still not reconstructable from our
logs; it is now moot, since the cap bounded it at 52°N regardless.

## 6. Owner decisions (TAKEN 2026-10-11)

The bundle's five questions and the four the findings added, answered by the owner on 2026-10-11
after reading §5. A6 builds these; it does not reopen them.

| # | question | decision |
|---|---|---|
| 1–2 | screenshot time / app freshly loaded / did the line move | Settled by §5 without needing recall: the 22:43 BST shot is the post-restart MODERATE alert (6.67 → row 6 → 54°N); the line did not move because nothing rewrites the stored Kp within a level. |
| 3 | naked-eye or camera | **Camera.** It is a photography app and phones reach several degrees further south than eyes; the table's own javadoc already claimed camera. |
| 4 | forecast vs live once a storm is in progress | **Always live when a live answer exists; forecast as the fallback** (before a live answer lands, or when live is inactive/unavailable). Never both at once. |
| 5 | colours over the green aurora field | **Forecast stays dashed amber** (`#ff9900` — it reads as a warning, which a forecast is). **Live changes from green to the ink colour `#F2E7D3`**, solid, so it does not vanish into the field. Both lines carry a label with source and age. |
| 6 | the latitude rule | **NOAA's geomagnetic rule plus a horizon allowance**: equatorward edge at `66° − 2°·Kp` geomagnetic (NOAA, *Tips on viewing the aurora*), converted to geographic **per longitude** with a centred-dipole conversion (IGRF-14 2025 pole ≈ 80.9°N, 72.6°W — ≈ −2° over the UK), then **4° further south** for the horizon (NOAA: "hundreds of kilometers equatorward"; 4° ≈ 445 km). Not a re-rowed hand table. |
| 7 | interpolate fractional Kp | **Yes** — inherent in the continuous rule; truncation is gone. |
| 8 | the table capping the live OVATION line | **Stop capping.** The live line is the OVATION 5% contour as parsed, nothing else. |
| 9 | which Kp feeds the forecast line | **Per request, from the live `maxKpRestOfTonight`** (one NOAA forecast read, its existing cache), not the Kp frozen at the first NOTIFY. `lastTriggerKp` stays what it is for the status response and the glow cap's successor; the line stops reading it. |

## 7. The A6 specification

What A6 builds, from §6. Phase A6 in `docs/engineering/aurora-heat-plan.md` §3 points here.

### Backend

1. **One rule, one class.** `service/aurora/AuroraViewlineRule` (new, pure, unit-tested):
   `double geographicLatitudeFor(double kp, double longitudeDeg)` — clamp Kp to 0..9, target
   geomagnetic latitude `66 − 2·kp − HORIZON_ALLOWANCE_DEG` (`HORIZON_ALLOWANCE_DEG = 4.0`, a named
   constant whose javadoc cites NOAA's wording and the owner decision), solved for the geographic
   latitude at that longitude under the centred dipole (pole constants named and dated; the
   conversion is the inverse of the standard `sin(λm) = sin(λ)sin(λp) + cos(λ)cos(λp)cos(φ−φp)`,
   found by a short monotonic bisection on λ at fixed φ — no new dependency). Javadoc states
   plainly: **camera** visibility; geomagnetic in, geographic out; the three known checks below.
   `List<ViewlinePoint> line(double kp)` — points at every integer longitude from `VIEWLINE_LON_WEST`
   (−12) to `VIEWLINE_LON_EAST` (4), so the forecast line is a shallow curve like the live one, not
   a flat two-point segment.
2. **`NoaaSwpcClient.buildForecastViewline(kp)`** uses the rule. `KP_LATITUDE_CAP`,
   `DEFAULT_KP_CAP`, `getKpLatitudeCap` and **`applyKpCap` are deleted**; `fetchViewline` returns
   the parsed OVATION contour unchanged (cache hit or miss; the stale-on-failure path too).
   `viewlineSummary`'s bands are unchanged. `getGlowLatitudeCap` (the Plan card's "visible as far
   south as", `AuroraHotTopicStrategy`) is replaced by the rule evaluated at the UK's central
   longitude (−2°), so the Plan and the Map agree.
3. **`AuroraController` `/viewline/forecast`** computes the Kp per request: the current night's
   window from `AuroraForecastRunService.currentNight()`/`computeWindowForDate`, the forecast from
   `noaaClient.fetchKpForecast()`, and `maxKpRestOfTonight` — the orchestrator's own method, made
   package-visible or moved to a small shared helper so the poller and the endpoint cannot
   disagree. Fallback when no forecast block remains or the fetch fails: `lastTriggerKp`; when that
   is null too, the existing inactive response. `/viewline` is unchanged except for the cap removal.
4. **`AuroraViewlineResponse`** gains `kp` (the Kp the line was computed from; null for live) and
   `asOf` (the forecast's fetch instant for the forecast line, the OVATION `forecastTime` for live)
   — `isForecast` stays the discriminator. Both new fields are nullable and additive (no client
   breaks; the existing Jackson-3 wire-format contract test covers the date).
5. **One INFO line per served viewline**: `Aurora viewline served: source=forecast kp=6.67
   southernmost=47.9 asOf=…` / `source=live southernmost=… points=17`, so the next night is read
   from the log, not reconstructed.
6. **`docs/pipeline-reference.html` "Kp Latitude Hard Caps"** → the rule, the allowance and a
   worked row (Kp 6.67 at −2°).
7. **Tests.** `AuroraViewlineRuleTest`: Kp 0 at −2° ≈ 60°N geographic (66 − 4 = 62 geomagnetic →
   ≈ 60), Kp 6.67 at −2° ≈ 46.7°N, Kp 9 at −2° ≈ 42°N (all ±0.5° — the dipole figures are
   approximate and the tolerance is deliberate); strictly decreasing in Kp; varies with longitude
   (west of the UK sits at a higher geomagnetic latitude, so the line dips further south there —
   assert the sign, not a figure); Kp outside 0..9 clamps. `NoaaSwpcClientTest`: the
   `applyKpCap_*` and `getGlowLatitudeCap_*` tests are **deleted** with the code (they pinned the
   defect); `buildForecastViewline` at 6.67 is a 17-point curve with southernmost ≈ 46.7; a live
   contour at 49°N under Kp 6 stays at 49 (the inverse of the deleted `applyKpCap` pin).
   `AuroraControllerTest`: the forecast endpoint reads the live forecast (a revised 5.67 → 7.0
   moves the line without any NOTIFY — the gap the trace found); falls back to `lastTriggerKp`;
   the two new fields. `AuroraHotTopicStrategyTest`: the glow figure moves with the rule.

### Frontend

8. **`hooks/useAuroraViewline`** implements "always live, forecast as fallback": when eligible it
   fetches **both** endpoints, polls `/viewline` every 5 minutes as today, re-fetches
   `/viewline/forecast` on the same cadence (its Kp now changes per request), and returns
   `{ viewline, source }` where `viewline` is the live answer whenever `viewlineDrawable(live)`,
   else the forecast answer, else null. The `forecast:${kp}` key goes (the Kp is in the answer).
9. **`AuroraViewlineOverlay`**: live → solid `#F2E7D3` at 0.85 (fill 0.08 of the same); forecast →
   dashed `#ff9900` unchanged. The permanent tooltip becomes the label: `Forecast · Kp 6.7 · 19:40`
   / `Live · 22:40` (Europe/London clock, `kp` to one decimal, from the response's new fields).
   `utils/viewlineDrawable.js` unchanged.
10. **`MapLegendPanel`** aurora note: "The dashed line…" → wording that is true for both lines —
    *"The line is where the aurora clears the northern horizon on camera; dashed amber is the
    forecast, pale is live."* — still gated on `viewlineShown`.
11. **Tests**: hook selection (live wins; forecast while live is pending/inactive; null when
    neither drawable; both polled); overlay colours per source and the label text; legend wording.
    `MapViewViewline.test.jsx`'s hook-argument assertions change with the signature.

### Docs

12. CLAUDE.md's *Aurora photography* bullet: the viewline sentence ("OVATION nowcast southernmost
    visibility boundary, `AuroraViewlineOverlay` with colour-coded zones") gains the rule, the
    always-live policy and the new colours; the *Aurora API* section's endpoint list is unchanged.
    `changelog.d/<date>-aurora-viewline-a6.md`: `### Fixed — the aurora viewline drew over North
    Yorkshire through a G3 storm seen from the south coast`.

### Not in A6

- Refreshing `lastTriggerKp` within a level (the poller's own rule) — the line no longer reads it,
  so the poller's behaviour is out of scope here; `aurora-polling-flap` territory.
- A per-location "visible from here" verdict — the field's own ratings carry that.
