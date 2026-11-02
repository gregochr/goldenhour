### Fixed — Open-Meteo: back off after timeouts and pause after a refusal

When Open-Meteo stopped answering and then refused every request as "too many concurrent requests", the
app kept sending one request a minute for nine minutes, all of them refused. Now a request that times
out is retried after 30 and then 60 seconds instead of 2 and 4, and a refusal for too many concurrent
requests, or an hourly or daily limit, makes the app stop calling Open-Meteo until that limit should have
cleared (10 minutes, the next hour, or midnight UTC). Several timeouts in a row pause it for 10 minutes
too. The batch loops stop early and leave the remaining locations empty, as they already do for a
failed chunk, and the health circuit breakers ignore these local pauses.
