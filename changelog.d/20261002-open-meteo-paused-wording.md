### Fixed — a weather prefetch refused by the Open-Meteo circuit breaker says so

A weather prefetch refused by the Open-Meteo circuit breaker used to read "Forecast run failed - The
run stopped unexpectedly. See the server log.", because the refusal (Resilience4j's
`CallNotPermittedException`) is not a weather fetch failure as far as `RunCompletion.reasonForForecastRun`
knew. A refusal is now recognised by the breaker's name (`open-meteo`, or the briefing's
`open-meteo-briefing`), anywhere in the cause chain, and reads "Weather data (Open-Meteo) calls are
paused after repeated failures; nothing was updated. Try again in a minute." A refusal by any other
breaker keeps the generic reason, and each place keeps its own "Weather data could not be fetched."
line, which does not contradict the run-level one.
