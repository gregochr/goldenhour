### Docs — resilience annotations and the progress tracker's mapper

`CLAUDE.md` described the Claude and Open-Meteo retry as Spring `@Retryable` with `MethodRetryPredicate`
and `@ConcurrencyLimit(8)`; the code uses Resilience4j `@Retry` and `@CircuitBreaker` (plus
`@RateLimiter` on Open-Meteo) with plain predicates, and `@Bulkhead`. It now also names the second
Open-Meteo instance, `open-meteo-briefing`, and points to the profile YAML for figures that differ by
profile rather than quoting them. It listed `RunProgressTracker` among the services injected with the
Jackson 2 `ObjectMapper` bean; it, `ModelTestService` and `PromptTestService` build their own.
