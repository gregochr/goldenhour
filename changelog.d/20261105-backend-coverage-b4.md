### Testing — Cover client, controller, config, model and utility branches (backend B4)

The `client`, `controller`, `config`, `entity`, `model`, `model.comingup`, `repository`, `util` and `eval` classes in the B4 list now sit at
95% lines and 90% branches or better, with behaviour tests for the NOAA, NLCNET, Environment Agency and OpenRouteService clients (cache windows,
retained values after a failed fetch, malformed rows), the controllers' missing-input and refused-issue paths, the status stream's lifecycle, the
CORS and caching configuration, the model records' null handling, the entity accessors and the dormant-account state records. No production code
changed.
