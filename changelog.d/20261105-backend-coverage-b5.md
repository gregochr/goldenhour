### Testing — Cover service-package branches, part 1 (backend B5)

The first half of the flat `service` package (`AstroConditionsService` through `HotTopicGlanceDecorator`, 34 classes) now sits at 95% lines and 90% branches or better. The new tests cover the briefing's optional-sink failures, the served-briefing overlays and single-flight joins, the scheduler's unfireable schedules and fire-log summaries, the cloud-verification archive gaps, the aurora summary caches, the hot-topic strategies' missing-data paths and the git-info fallbacks. No production code changed.
