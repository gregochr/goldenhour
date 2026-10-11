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

## 5. OPEN — reconstructing the 10 Oct night (owner, on the host)

Nothing logs the viewline that was served (no INFO line in `fetchViewline`, `buildForecastViewline`,
`applyKpCap` or the controller), so it has to be inferred. On the host:

```bash
grep -E "Aurora (NOTIFY|night poll|scoring complete|event ended)|Forecast lookahead" ~/goldenhour-data/logs/goldenhour.log | grep "2026-10-10" | tail -40
```

What to read off it:

- Every `Aurora NOTIFY (LEVEL)` line and the `night poll: level=… from TRIGGER (rest of tonight Kp
  X, Kp now Y) action=NOTIFY` line preceding it give `lastTriggerType` and `lastTriggerKp` at each
  NOTIFY. ⚠️ A `night poll` line logs that poll's *computed* trigger even on SUPPRESS; only the
  NOTIFY lines tell you what the state cache stored.
- The forecast line at any time = `table[(int) lastTriggerKp]` from the last NOTIFY before it.
- Whether a second NOTIFY (MODERATE → STRONG) happened after 19:47, and with which trigger — this
  decides §2's inconsistency.
- ⚠️ v2.25.1 (#1076) deployed at 21:21 UTC **restarted the backend**, which resets the in-memory
  `AuroraStateCache` to IDLE; the first poll after it NOTIFIED afresh. Lines after 21:21 describe
  a *new* alert, not the one in the 22:43 screenshot's `DETECTED 19:47`… unless the screenshot's
  status was itself stale. Read the timestamps carefully.

The live line cannot be reconstructed from our logs at all (needs the NOAA OVATION archive for the
hour plus the published Kp readings); `services.swpc.noaa.gov/products/noaa-planetary-k-index.json`
still holds the 3-hour readings for the night, which bounds the cap.

## 6. OPEN — questions for the owner before any fix

From the bundle, plus three the findings add:

1. What time was the screenshot taken, and had the app been open since before 19:47 or freshly
   loaded? (Decides whether §2's stale-hook explanation is in play.)
2. Did the line move at all during the night?
3. **Naked-eye or camera?** The table's javadoc says camera; NOAA's boundary is the edge the
   aurora is *observed from*; phone cameras reach several degrees further south than eyes.
4. When forecast and live disagree once a storm is in progress: always live, or both lines?
5. Is dashed-amber / solid-green still wanted over the new green aurora field (Task 1)? A green
   line on a green field will clash; the plan's A6 proposes a recolour if so.
6. *(new)* Should the table be replaced by a **geomagnetic** boundary converted per longitude
   (NOAA's rule, −2°/Kp from 66°, converted with a dipole or AACGM offset of ~2–2.5° for the UK),
   plus an explicit **horizon-visibility** allowance (NOAA: "hundreds of km"; a defensible figure
   is 3–5° further south)? Or keep a hand table and re-row it (e.g. 5 → 54, 6 → 52, 7 → 50,
   8 → 48, 9 → 46 geographic)?
7. *(new)* Should the Kp lookup **interpolate** (6.67 → between the 6 and 7 rows) instead of
   truncating? Truncation is the single largest avoidable error at any non-integer Kp.
8. *(new)* Should `applyKpCap` stop clamping the **live** OVATION line? OVATION is the better
   product for "now"; the cap was added as a sanity floor and now overrides it. If it stays, it
   should at least use the same corrected, interpolated table.
9. *(new)* Should `lastTriggerKp` be refreshed on every NOTIFY-worthy *reading* within a level
   (so the forecast line follows NOAA's revisions), or should the forecast line be derived from
   `maxKpRestOfTonight` per request rather than from the stored trigger? The second is simpler and
   is what the status response's `forecastKp` already claims to be.

## 7. Likely fixes (after §6 — do not build yet)

- Correct the table's latitude system and re-row or interpolate it; apply the horizon allowance.
- Stop capping the live line with the table, or cap with the corrected one.
- Derive the forecast line per request from the live `maxKpRestOfTonight` (one NOAA snapshot
  read, 15-minute cache) rather than the stored trigger Kp.
- Label the line with its source and age (`Forecast · Kp 6.7 · 19:40` / `Live · 22:40`).
- Log the served viewline at INFO (Kp in, cap, southernmost out) so the next night can be read
  from the log instead of reconstructed.
- Tests: a FORECAST_LOOKAHEAD alert whose Kp rises within a level (today untested — the gap the
  trace found); fractional Kp; the live line *not* clamped north of OVATION.
