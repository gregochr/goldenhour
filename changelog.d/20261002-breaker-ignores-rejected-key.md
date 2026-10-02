### Fixed — a rejected Claude API key no longer opens the circuit breaker

With a rejected key, the first wave of Claude calls answered 401 and the run stopped correctly, but
those 401s also counted as failures toward the `anthropic` circuit breaker (10-call window, opens at
50% once 5 calls are in it), which then opened for a minute. A run started inside that minute never
reached Claude: every place read "Not attempted: Claude calls are paused after repeated failures.",
the run was not stopped, and Retry was offered although the key was still wrong. A rejected key is
a configuration fault, not an outage. HTTP 401 and 403 from Anthropic are now ignored by the breaker
(neither a failure nor a success, and a half-open probe that is rejected returns its permit), through
`ClaudeBreakerIgnorePredicate` wired by `ResilienceConfig.anthropicCircuitBreakerCustomizer()`, so no
per-host `application.yml` needs editing. The definition of "rejected key" is shared with the run's
stop-on-rejected-key rule, so the two cannot drift. Every other failure (400, 404, 429, 5xx, 529,
connection failures) still counts.

Two consequences. The status page no longer shows a rejected key: its overall DEGRADED/DOWN comes
from the circuit-breaker health, which stays CLOSED, and its Claude entry is the `claudeApi` probe,
which counts any HTTP answer (401 and 403 included) as reachable. A rejected key is visible where it
is reported: the stopped run's reason ("Claude rejected the API key."), the server log, and the failed
call's row in the Job Runs detail (`ANTHROPIC · anthropic_401`). And the briefing's gloss and best-bet
calls, which have no stop-on-rejected-key rule, used to be cut off by the open breaker after about ten
calls: with a rejected key one briefing refresh now attempts every call (up to about 60 gloss calls, five
days by two events by the regions, plus one best-bet call), on each of the two scheduled pipeline cycles
a day (nightly and 14:00 UTC intraday, plus any admin briefing run); each is rejected unbilled, and each
gloss call leaves one `api_call_log` row (a failed best-bet call logs none).
