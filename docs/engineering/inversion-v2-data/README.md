# Inversion v2, Phase 1a raw data

Fetched by the owner on 2026-10-30 from Open-Meteo, unchanged. Analysis: `../inversion-v2-plan.md` §8.
All times UTC, wind in m/s. Each file is a JSON array, one element per point; Open-Meteo snaps each
requested point to a model cell, so read the returned `latitude`, `longitude` and `elevation`.

| File | API | Points requested (in order) | Dates | What was seen |
|---|---|---|---|---|
| `2a-helvellyn-2025-01-15.json` | historical-forecast | Helvellyn summit 54.527,−3.016; Glenridding 54.543,−2.951; Thirlmere 54.560,−3.065 | 14–15 Jan 2025 | Inversion reported from Helvellyn; cloud top roughly 700–870 m (UKC thread; date confirmed) |
| `2b-scafell-2025-10-17.json` | historical-forecast | Scafell Pike 54.454,−3.212; Wasdale Head 54.466,−3.262; Keswick 54.600,−3.135 | 16–17 Oct 2025 | **Wrong days**: the reported inversions were Wed 15 Oct. Kept as a 16–17 Oct record only |
| `2b2-scafell-blencathra-2025-10-15.json` | historical-forecast | Scafell Pike 54.454,−3.212; Wasdale Head 54.466,−3.262; Keswick 54.600,−3.135; Blencathra 54.641,−3.050; Scales/Threlkeld 54.626,−3.020 | 14–16 Oct 2025 | Wed 15 Oct: Blencathra inversion from 05:30, Scafell Pike inversion until dark, Red Pike above the cloud (Cumbria Crack, article dated 17 Oct) |
| `2c-skiddaw-2024-11-04.json` | historical-forecast | Skiddaw 54.651,−3.148; Latrigg 54.615,−3.118; Keswick 54.600,−3.135 | 3–4 Nov 2024 | No inversion: murky, cloud around and above the summits (UKC; date confirmed) |
| `3-catbells-elevation.json` | forecast, `past_days=2` | Cat Bells 54.568,−3.170 three times, `elevation=nan,451,80` | 28–30 Oct 2026 | The `elevation=` parameter test (no observation) |

Variables in 2a–2c (2b2 also has temperature and geopotential height at 900 hPa): 2 m temperature and dew point, 10 m wind, total/low/mid/high cloud, visibility,
precipitation, boundary layer height, temperature and geopotential height at 975/950/925/850 hPa, wind at
975/950/925 hPa. File 3: 2 m temperature and dew point, 975 hPa temperature.
