# Comprehensive Feature Audit — Competitors vs Golden Hour

## Competitor Overview

| App | Strengths | Weaknesses |
|-----|-----------|------------|
| **Alpenglow** | Solid forecasting (NAM/GFS), 4-day, AR sun path, community | Degrades close to event, inaccuracy reports |
| **Sunsethue** | Simple, worldwide coverage | Sparse detail, no methodology transparency |
| **SkyCandy** | Hyperlocal, multi-location, widget, satellite view, AR | Paywalled heavily, US-centric |
| **PhotoWeather** | Two metrics, directional analysis, aerosol, solar elevation window | Most technically complex to use |
| **Burning Sky** | 3D cloud models, red afterglow probability, water reflections, nightscapes | European-centric, complex UX |

---

## Full Feature Checklist

### Scoring & Forecast

| Feature | Alpenglow | SkyCandy | PhotoWeather | Golden Hour |
|---------|-----------|----------|--------------|-------------|
| Quality percentage | ✓ | ✓ | ✓ | ✓ |
| Two separate metrics (Golden Hour vs Fiery Sky) | ✗ | ✗ | ✓ | Planned |
| AI-generated explanation | ✗ | ✗ | ✗ | ✓ (Claude) |
| Aerosol optical depth | ✗ | ✗ | ✓ | ✓ (Open-Meteo CAMS) |
| Aerosol type discrimination (dust vs smoke) | ✗ | ✗ | ✓ (satellite) | Partial (AOD + PM2.5 proxy) |
| Cloud layer breakdown (low/mid/high) | ✗ | ✓ | ✓ | ✓ |
| Directional analysis | ✗ | ✗ | ✓ | Layer inference (v1), multi-point (v1.1) |
| Solar elevation window | ✗ | ✗ | ✓ | ✓ (via solar-utils) |
| 7-day forecast | ✓ | ✓ | ✓ | ✓ |
| Forecast update cadence shown | ✗ | ✗ | ✗ | ✓ (timestamp) |
| Historical accuracy for location | ✗ | ✗ | ✗ | Planned |

### Locations & Planning

| Feature | Alpenglow | SkyCandy | PhotoWeather | Golden Hour |
|---------|-----------|----------|--------------|-------------|
| Multiple locations | ✓ | ✓ Premium | ✓ | ✓ |
| Location types (landscape/wildlife/coastal) | ✗ | ✗ | ✗ | ✓ |
| Sun direction / azimuth | ✓ | ✓ | ✓ | ✓ (map lines) |
| Tide information | ✗ | ✗ | ✗ | Planned |
| Map view | ✗ | ✓ | ✗ | ✓ (Leaflet) |
| Wildlife-specific forecast (comfort vs colour) | ✗ | ✗ | ✗ | Planned |
| AR sun path visualisation | ✓ | ✗ | ✗ | Not planned |
| Satellite imagery | ✗ | ✓ | ✗ | Not planned |

### Notifications & Alerts

| Feature | Alpenglow | SkyCandy | PhotoWeather | Golden Hour |
|---------|-----------|----------|--------------|-------------|
| Configurable quality threshold alerts | ✓ | ✓ | ✓ | Planned |
| Score improvement alerts | ✗ | ✗ | ✗ | Planned (significant change only) |
| Daily digest | ✓ | ✗ | ✗ | ✓ (email + Pushover) |
| Countdown timer to event | ✓ | ✓ | ✗ | ✗ (not planned) |
| App widget (phone home screen) | ✗ | ✓ | ✗ | Planned (Tauri menu bar) |

### Social & Community

| Feature | Alpenglow | SkyCandy | PhotoWeather | Golden Hour |
|---------|-----------|----------|--------------|-------------|
| User-submitted outcome reports | ✓ | ✗ | ✗ | ✓ (outcome recording) |
| Community gallery | ✗ | ✓ | ✗ | ✗ (not planned) |
| Prediction accuracy feedback | ✗ | ✗ | ✗ | Planned (ACCURATE/SLIGHTLY_OFF/VERY_INACCURATE) |
| Crowd-sourced corrections | ✓ | ✗ | ✗ | Not planned |

### Other / Niche

| Feature | Alpenglow | Burning Sky | Golden Hour |
|---------|-----------|-------------|-------------|
| Nightscape / Milky Way | ✗ | ✓ | ✗ |
| Over-cloud shooting (mountain above cloud) | ✗ | ✓ | ✗ |
| Water reflection predictions | ✗ | ✓ | ✗ |
| Moon phase info | ✓ (Magic Hour) | ✗ | ✗ |
| 3D cloud modelling | ✗ | ✓ | ✗ |

---

## What Actually Matters — Prioritised

### Must Have (Core value)
- ✓ Quality score with confidence
- ✓ AI explanation (why — this is the differentiator)
- ✓ 7-day horizon
- ✓ Aerosol data (dust vs smoke proxy)
- ✓ Sun direction
- ✓ Outcome recording / feedback loop

### Should Have (Competitive parity)
- ✓ Multi-location support with map
- ✓ Configurable alerts / notifications
- ✓ Tide info for coastal locations
- Planned: Two separate metrics (Fiery Sky + Golden Hour potential)
- Planned: Improvement notifications

### Nice to Have (Differentiation, post-launch)
- Directional multi-point sampling (v1.1)
- Historical accuracy stats per location
- Wildlife comfort forecast

### Don't Bother
- AR sun path (cool, not essential — azimuth lines do the job)
- Community gallery (scope creep)
- 3D cloud modelling (overkill)
- Satellite imagery (expensive, marginal gain)
- Crowd-sourced corrections (need critical mass)

---

## Your Competitive Advantages

1. **AI explanation (Claude)** — No competitor explains *why* in plain English. This is
   the single biggest differentiator and should be on the free tier.

2. **Aerosol optical depth** — Available via Open-Meteo CAMS for free. PhotoWeather
   does this via satellite (more accurate) but you can approximate dust vs smoke using
   AOD + PM2.5 ratio. Most competitors don't touch aerosols at all.

3. **Location types** — Landscape / Wildlife / Seascape with type-specific UI. No
   competitor segments like this.

4. **Outcome recording + feedback loop** — You know when your forecasts were wrong.
   Over time this becomes a training signal. No competitor does this at user level.

5. **UK / Northern European focus** — Most competitors are US-centric with weather
   models tuned accordingly. Durham in February is not Phoenix in August.

---

## Forecast Refresh Cadence

### Open-Meteo Update Frequencies

| Data source | Refresh interval | Notes |
|-------------|-----------------|-------|
| ECMWF IFS (9km) | Every 6 hours (0z, 6z, 12z, 18z UTC) | Best global model |
| GFS (25km) | Every 6 hours | Good for 4–7 day horizon |
| ICON Europe (7km) | Every 6 hours | Best for UK/Europe short range |
| CAMS Air Quality (aerosols) | Every 24h (Europe), 12h (global) | AOD, dust, PM2.5 |

### Recommended Polling Strategy

- **Days 0–1**: Poll every 3 hours (conditions can change rapidly close to the event)
- **Days 2–4**: Poll every 6 hours (aligned with model refresh cycles)
- **Days 5–7**: Poll every 12 hours (forecast uncertainty makes more frequent polling wasteful)

Current scheduled runs (06:00 + 18:00 UTC) align with model refresh cycles — sensible.
Consider adding a 12:00 UTC run to catch the midday model update for same-day forecasts.

### UX Implications

- Always show "Forecast updated at HH:MM" timestamp — photographers plan around this
- Notify users only when score changes materially (threshold: ±15 percentage points
  or one full star rating) to avoid alert fatigue
- Don't notify for changes beyond 3 days out — too much noise, conditions will change again
- "Conditions improved!" push notification (via Pushover) is a high-value moment — user
  may have written off a shoot but now has reason to reconsider

### API Cost Implications

At current scale (1–5 locations, T through T+7, twice daily):
- Open-Meteo: Free tier, well within limits
- CAMS aerosols: Free via Open-Meteo Air Quality API
- Claude (Sonnet): ~$0.003 per evaluation × 14 evaluations per run = ~$0.04 per run
- Two runs/day = ~$0.08/day = ~$2.50/month

Adding a 12:00 UTC run would bring this to ~$3.75/month — trivial.
