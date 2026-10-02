### Fixed — a rejected Claude API key no longer opens the circuit breaker, and failed calls say why

With a rejected key, the first wave of Claude calls answered 401 and the run stopped correctly, but
those 401s also counted as failures toward the `anthropic` circuit breaker (10-call window, opens at
50% once 5 calls are in it), which then opened for a minute. A run started inside that minute never
reached Claude: every place read "Not attempted: Claude calls are paused after repeated failures.",
the run was not stopped, and Retry was offered although the key was still wrong. A rejected key is
a configuration fault, not an outage. HTTP 401 and 403 from Anthropic are now ignored by the breaker
(neither a failure nor a success), through `ClaudeBreakerIgnorePredicate` wired by
`ResilienceConfig.anthropicCircuitBreakerCustomizer()`, so no per-host `application.yml` needs
editing. The definition of "rejected key" is shared with the run's stop-on-rejected-key rule, so the
two cannot drift. Every other failure (400, 404, 429, 5xx, 529, connection failures) still counts.

A weather prefetch refused by the Open-Meteo circuit breaker used to read "Forecast run failed - The
run stopped unexpectedly. See the server log.". A refusal is now recognised by the breaker's name
(`open-meteo` or `open-meteo-briefing`) and reads "Weather data (Open-Meteo) calls are paused after
repeated failures; nothing was updated. Try again in a minute." A refusal by any other breaker keeps
the generic reason.

A failed synchronous Claude call (the forecast run and the aurora evaluation) was written to
`api_call_log` with a hard-coded status 500 and no `error_type`. It now records the HTTP status
Anthropic actually answered with (401, 403, 429, 529, 5xx, 400) and the `error_type` the run panel
already uses (`anthropic_401`, `content_filter`, `refusal`, `reply_unreadable`, `circuit_open`, ...).
A failure with no HTTP status of its own (a connection failure, an unreadable reply, a refusal, an open
breaker) records a null status, the same as the batch path and the weather clients; every reader
decides failure from `succeeded`, never from the status. The Job Runs detail's Failed Calls list shows
the status and error type beside the service (the metrics endpoint now serves `statusCode`).
