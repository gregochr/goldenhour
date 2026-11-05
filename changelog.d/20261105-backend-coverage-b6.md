### Testing — Cover service-package branches, part 2 (backend B6)

The second half of the flat `service` package (Inversion to Woodland) now has behaviour tests for its missing-data and failure paths:
the hot-topic strategies, Open-Meteo parsing and failure logging, tide size and run builders, the surge curve and its facts, the
sky-rating eval batch reconciler, the run progress stream, the solar trigger fallbacks and the account link writers. No production code changed.
