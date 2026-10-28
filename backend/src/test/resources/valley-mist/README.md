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
