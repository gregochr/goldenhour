# Directional Analysis — What It Is and How to Infer It

## The Problem

A single "40% cloud cover" reading tells you nothing about sunset quality. Two forecasts
can both show 40% cloud and produce completely different results depending on *where*
those clouds are relative to the sun.

## The Two Horizons

**Solar horizon** (where the sun sets/rises):
- Needs to be *clear* — this is the light path
- Low cloud here blocks sunlight reaching the scene entirely
- Even 20% low cloud at the solar horizon can kill the shot

**Antisolar horizon** (the opposite direction):
- Wants *mid/high cloud* — these are the screens that catch and reflect the light back
- Cirrus and altocumulus here = dramatic colour canvas
- Totally clear antisolar horizon = glow but no drama

For sunset: solar horizon is W/SW, antisolar is E/NE.
For sunrise: solar horizon is E/NE, antisolar is W/SW.

## The Problem with Open-Meteo

Open-Meteo weather models resolve at 9–25 km per grid point. You can't query the solar
horizon separately from the antisolar horizon for a single location without making
multiple API calls to nearby coordinates.

True directional sampling (what PhotoWeather does) = 5+ API calls per forecast, knowing
the sun's azimuth for each location/date, and stitching the results.

## What We Can Do Instead — Layer-Based Inference

We don't need true directional sampling to beat most competitors. Open-Meteo gives us
cloud cover *by altitude layer*, which is a strong proxy:

| Layer | Altitude | Cloud type | Directional relevance |
|-------|----------|------------|----------------------|
| Low (0–3 km) | Boundary layer | Stratus, fog | Tend to sit near horizon — bad if thick; block solar horizon |
| Mid (3–8 km) | Free troposphere | Altocumulus | Mixed — often positioned where light can reach them |
| High (8+ km) | Upper troposphere | Cirrus, cirrostratus | Spread widely above — naturally good for catching light |

**Inference rules:**

```
If low_cloud < 30%:       → Solar horizon likely clear (✓)
If mid_cloud 30–60%:      → Canvas exists above horizon (✓)
If high_cloud 20–60%:     → Depth and texture (✓)
If wind_dir FROM_SUN:     → Clouds pile at antisolar horizon (✓)
If visibility > 12km:     → Atmosphere is clean (✓)
If AOD 0.2–0.5:           → Moderate particle scattering (✓ dust not smoke)
```

When all conditions above are met: directionally favourable, even without sampling
multiple grid points.

## When Layer Inference Fails

Layer inference misses ~20% of edge cases:
- All cloud concentrated at the solar horizon (blocks light but layer data looks fine)
- All cloud at antisolar horizon only (drama but light may not reach it)
- These require actual directional sampling or satellite imagery

## Future Enhancement (v1.1+)

Manual 5-point grid sampling — request Open-Meteo for:
1. Observer location
2. Point 50 km toward solar azimuth
3. Point 50 km toward antisolar azimuth
4. Point 50 km N
5. Point 50 km S

Cross-reference cloud covers at each point. More API calls, but meaningfully better
accuracy for edge cases. Estimated improvement: 80% → 92% accuracy on layer-inferred
method.
