# UI Presentation Examples — Detail Tiers

Five levels of information density, from the casual glance to the weather nerd.

---

## Tier 1 — Simple Score (Lite User, default view)

```
SUNSET TODAY — DURHAM
━━━━━━━━━━━━━━━━━━━━
🔥  Fiery Sky: 78%
🌅  Golden Hour: 82%

"Moderate dust haze, clear horizon, mid-level cloud canvas. Worth going out."

17:34 UTC · Updated 15:00
```

- One headline percentage per event type
- One-line Claude summary
- Timestamp so users know forecast age
- No technical metrics visible

---

## Tier 2 — Why It's That Score (Lite User, expanded)

```
SUNSET TODAY — DURHAM
━━━━━━━━━━━━━━━━━━━━
🔥  Fiery Sky: 78%

Three Key Factors
─────────────────
✓ Clear Horizon   — Sun's light reaches the scene unblocked
✓ Cloud Canvas    — Mid-level clouds positioned to catch and reflect
✓ Dust Haze       — Moderate particles enhance warm reds (not smoke)

⚠ Watch: Wind may clear the haze before 17:34. Check back at 15:00.
```

- Explains *why* without showing raw numbers
- Language a photographer understands, not a meteorologist
- Warning when conditions are marginal or changing

---

## Tier 3 — Technical Metrics (Pro User)

```
SUNSET TODAY — DURHAM         🔥 78%  🌅 82%
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Cloud Layers
  Low (0–3km)   ████░░░░░  15%   ← Clear horizon ✓
  Mid (3–8km)   ████████░░ 45%   ← Canvas ✓
  High (8+km)   ██████░░░░ 60%   ← Depth ✓

Atmosphere
  Visibility    24 km              ← Excellent
  Humidity      58%                ← Good (below 65%)
  Wind          8 mph SW           ← From sun direction ✓
  Pressure      1018 hPa rising    ← Stable

Aerosols
  AOD           0.42               ← Sweet spot (0.2–0.6)
  Dust          12 µg/m³           ← Enhancing warm tones ✓
  PM2.5         6 µg/m³            ← Low (not smoke)

Peak Window    17:22 – 17:41 UTC   ← Optimal solar elevation
```

- Raw numbers for users who want them
- Visual bars for cloud layers
- Aerosol breakdown with context labels

---

## Tier 4 — Directional Hint (Pro User, map overlay)

```
        N
        ↑
    ☁️ ☁️   ← High cirrus (good: catching reflected light)

  W ← ● → E
        ↓ ← Sunset direction
    Clear  ← Low cloud < 15% (good: light penetrates)
        S

"Position yourself to shoot SW toward the sunset. The mid-level
cloud bank NE will catch the light. A high viewpoint will help
you see over the boundary layer."
```

- Visual compass with cloud positioning
- Plain English shooting instruction
- Direction relative to their location

---

## Tier 5 — Advanced / Future (Pro+ / Power Users)

- Forecast model comparison (GFS vs ECMWF vs ICON — which is most confident?)
- Historical accuracy for this location/season (e.g. "we've been within 1 star 73% of the time")
- 5-point directional grid sampling (manual multi-point Open-Meteo calls)
- Satellite aerosol type classification (dust vs smoke particle size discrimination)
- Side-by-side 7-day calendar heatmap

---

## Recommended Launch Approach

**Lite users** see Tier 1 by default, can tap to expand Tier 2. Tier 3+ is teased but gated.

**Pro users** see Tier 1 + 2 by default, can expand to Tier 3. Tier 4 visible on map view.

The goal: casual users get a fast, trustworthy answer. Engaged users discover depth naturally.
Don't hide detail behind walls — show it's there through discovery moments.
