# valley-mist test fixtures

## open-meteo-brathay-2026-10-16_20.json

Open-Meteo hourly forecast for the River Brathay, Lake District (54.431217, -3.02565). Open-Meteo
returned an elevation of 68 m. It covers 16-20 Oct 2026 (120 hourly slots) and was supplied by the
owner, unchanged. The morning of 17 Oct 2026 is the one photographed with mist on the river; it is the
observation that prompted the valley-mist feature (`docs/engineering/valley-mist-plan.md`).

Variables: `temperature_2m`, `dew_point_2m`, `relative_humidity_2m`, `wind_speed_10m`,
`cloud_cover_low`/`mid`/`high`, `temperature_975hPa`, `temperature_950hPa`, `temperature_925hPa`,
`wind_speed_925hPa`.

### Units: wind is in mph

Both wind fields carry `"mp/h"` in `hourly_units` (the file was requested without
`wind_speed_unit=ms`). Production requests metres per second, so the tests multiply by 0.44704.

### Timezone: the times are UTC

The file says `"timezone": "Europe/London"` with `utc_offset_seconds: 0` and abbreviation `GMT`, although
17 Oct 2026 is BST. The daily maxima at 13-14h confirm the hourly times are UTC. Production requests
`timezone=UTC`, so the tests treat them as UTC.

### What the file lacks

No `visibility`, `precipitation`, `weather_code`, `shortwave_radiation`, `boundary_layer_height`, total
`cloud_cover`, `wind_direction_10m`, or air-quality data. The production parser requires most of
these, so the tests patch placeholder arrays onto the deserialised response before parsing (each patch
is commented in `BrathayValleyMistFixture`), and then read it through the real
`OpenMeteoResponseParser`.

## open-meteo-stainton-keswick-2026-10-27_29.json

Open-Meteo forecast API response (`past_days=2`, `timezone=GMT`, wind in m/s) supplied by the owner,
unchanged. **It is a JSON array of two responses**, one per location, each with hourly data for
2026-10-27T00:00 to 2026-10-29T23:00 (72 slots):

| Index | Place | Requested | Returned elevation |
|---|---|---|---|
| 0 | Stainton, by Penrith | 54.6478, -2.810852 | 214 m |
| 1 | Keswick | 54.592644, -3.1208649 | 92 m |

Times are GMT = UTC on this date. Sunrise on 29 Oct 2026 was 07:05 UTC at both points, so production
reads the 08:00 slot.

Variables: `temperature_2m`, `dew_point_2m`, `relative_humidity_2m`, `wind_speed_10m` (m/s),
`wind_direction_10m`, total `cloud_cover` and `cloud_cover_low`/`mid`/`high`, `visibility`,
`precipitation`, `weather_code`, `shortwave_radiation`, `boundary_layer_height`,
`temperature_975hPa`/`950hPa`/`925hPa`/`850hPa`, `geopotential_height_975hPa`/`950hPa`/`925hPa`,
`wind_speed_975hPa`/`950hPa`/`925hPa`.

### What the owner saw

- **Stainton area, 09:02-09:08 GMT:** shallow valley mist from a car on the A66 west of Penrith (by
  Ullswater Community College, Redhills, near the Rheged roundabout, just west of Stainton; times and
  places from the iPhone photo metadata). Frost or heavy dew, puddles, broken cloud overhead, mist at
  tree height with sky visible through it.
- **Keswick, 09:22 GMT** (about 20 km further west): no mist ("it's clear").

### What the file lacks

`apparent_temperature`, `precipitation_probability`, `pressure_msl`, `surface_pressure`, `snowfall`,
`snow_depth`, freezing level, wind gusts, and air quality. None of the parser's required fields is
missing, so `PenrithValleyMistFixture` patches nothing; it only supplies the empty-but-non-null
air-quality response the parser dereferences. The tests (`ValleyMistPenrithKeswickFixtureTest`) are
characterisation tests of current behaviour on this observed morning, not tuning targets.
