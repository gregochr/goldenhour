### Fixed — a refused request shows the server's sentence, not "Request failed with status code 400"

The backend answers every refusal with a JSON body whose key is `error`, but a dozen admin and map
surfaces read `message` (or only axios's generic `err.message`), so the sentence the server wrote
never reached the reader. The overlay popup's Run Forecast was the one that surfaced it: a 400 from
`POST /api/forecast/run` for a wildlife hide ("'…' is not a sky location: it has no sunrise or
sunset forecast") read as a bare "Request failed with status code 400". One helper,
`apiErrorMessage(err, fallback)` in `utils/apiError.js`, now names the key in a single place: the
server's `error` string, then `message`, then the caller's fallback. It tolerates a missing
response and a non-object body (a proxy's HTML page is never shown). Moved onto it: the popup's Run
Forecast, the forecast-data load, the outcome form, the drive-time refresh's 429 line, the
optimisation-strategy toggle, the model, prompt, briefing-model and sky-rating test views, and the
job-run, pipeline-run and API-call loads. Sites that already read `error` (login, registration,
user, region and location management) and the status-keyed run-button lines are unchanged.
