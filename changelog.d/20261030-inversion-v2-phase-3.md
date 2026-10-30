### Added — cloud inversion v2: wind at summit height

The v2 inversion score now reads the wind across the top of an elevated warm layer (inversion-v2-plan
Phase 3). The valley profile request gains the wind speed at the five pressure levels (17 to 22 hourly
variables). At the event slot the larger of the wind at the layer's base level and at the level above it
caps the score: 12 m/s or more at 8, 18 m/s or more at 6. Surface-based layers and sites with no layer are
unchanged, a missing wind means no cap, and the cap is recorded in the log line's `caps=` only when it
lowers the score. The `[INVERSION v2]` log line gains `wind=<m/s>@<hPa>`, and the prompt's warm-layer line
gains a short clause only when the wind changed the score. No fixture score moves. Open-Meteo load on the
main fetch rises from about +16 % to about +21 %.
