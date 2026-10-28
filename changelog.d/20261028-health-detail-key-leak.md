### Fixed — API keys kept out of health details, logs and the API-call log

`/actuator/health` details were shown to every signed-in user, and the WorldTides, Open-Meteo and Claude
probes echoed the exception message; on a network error Spring's message quotes the full request URL, so
the paid WorldTides key could reach any logged-in user. Health details are now ADMIN-only and carry only
the exception class name (plus the HTTP status for an error response), never a message. The WorldTides
probe also builds its URL with a URI builder. `GET /api/models` is now ADMIN-only, as documented and as
its two PUT siblings already were. A new `SecretRedactor` masks `key=` / `apikey=` / `api_key=` query
values in the WorldTides ingestion and light-pollution client log lines and in the admin API-call log.
