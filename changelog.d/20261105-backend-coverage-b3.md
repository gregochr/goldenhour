### Testing — Cover batch, pipeline and aurora service branches (backend B3)

The `service.batch`, `service.pipeline` and `service.aurora` classes now have behaviour tests for their retry, orphan, partial-failure and fallback paths, reaching 95% lines and 90% branches.
Tests only: no production code changed.
