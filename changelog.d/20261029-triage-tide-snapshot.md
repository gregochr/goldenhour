### Changed — batch triage reads tide extremes once per collection

The scheduled and region-filtered batch collectors now load one `TideExtremeSnapshot` for all coastal candidates over their date window (a public `TideService.snapshotFor` factory) and pass it through `ForecastService.fetchWeatherAndTriage` to the tide derivation, instead of querying stored extremes separately for every candidate. The derived tide facts are identical; if the snapshot cannot be loaded, or a caller passes none (force-submit, retry), each candidate reads its own extremes as before.
