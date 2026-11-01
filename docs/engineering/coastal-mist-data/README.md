# Coastal mist: observed mornings vs the forecast model (2023–2025)

Analysis behind `../coastal-mist-plan.md`.

**What the CSV holds:** `boulmer-sunrise-vs-model-2023-2025.csv` has 1,000 mornings, one row each.
- **Columns:** what RAF Boulmer (EGQM, Northumberland coast, about 25 km south of Bamburgh) observed in the
  hour either side of sunrise, beside the forecast model's values for Bamburgh (55.609, −1.709) at the
  sunrise hour.
- **Excluded:** mornings classed "other" (rain, drizzle, no visibility report) are left out.

**Sources:**
- *Observations:* METAR reports from the Iowa Environmental Mesonet ASOS/METAR archive
  (`mesonet.agron.iastate.edu`, station EGQM). The raw file was 5.2 MB and covered EGQM and EGNT; it is not
  committed.
- *Model:* Open-Meteo historical forecast API (best-match model) for visibility, low cloud, 2 m
  temperature and dew point, and 10 m wind. Sea-surface temperature comes from the Open-Meteo marine API
  (cell 55.625, −1.542). Open-Meteo data is CC BY 4.0.

**Observed class**, from the lowest visibility and the weather codes in the window:
- `CLEAR`: ≥ 8 km.
- `MIST-on` / `MIST-off`: BR or HZ, 1–8 km.
- `FOG-on` / `FOG-off`: FG, or under 1 km.

The `on`/`off` suffix is the observed wind: onshore is 20–160°, offshore is anything else.

| Class | n | Model vis median | Model T−Td median | Model onshore |
|---|---|---|---|---|
| CLEAR | 824 | 21.5 km | 2.2 °C | 17 % |
| MIST-off | 94 | 7.6 km | 0.9 °C | 15 % |
| MIST-on | 43 | 7.4 km | 1.1 °C | 81 % |
| FOG-off | 24 | 1.8 km | 0.4 °C | 29 % |
| FOG-on (haar) | 14 | 2.1 km | 0.5 °C | 79 % |

**Findings:**
- **Fog is underestimated:** the model's visibility rarely goes below 1 km for fog that was observed under
  1 km. Observed haar sits at a model median of 2.1 km, and 10 of the 14 haar mornings fall under 3 km.
- **Sea temperature does not separate haar from mist:** dew point minus sea-surface temperature has a median
  of −1.0 °C for both onshore mist and haar.
- **Boulmer is not Bamburgh:** the two are about 25 km apart. Haar is patchy, so treat single mornings as
  indicative, not exact.
