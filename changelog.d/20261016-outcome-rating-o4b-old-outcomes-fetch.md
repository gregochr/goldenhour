### Changed — The map's load no longer fetches every recorded outcome

Loading the forecast data made three requests, one of which (`GET /api/outcome/all`, over the last
seven days) fed nothing: the outcome screens it served were retired in February, and nothing reads
`outcomes` on a location. It is gone, so the load is two requests (forecasts and locations), and a
location no longer carries an empty `outcomes` list. The instant-paint cache no longer stores the
field, and a payload cached by an earlier build, which still has one, loads as it did. The API
bindings and `OutcomeModal` stay, unused, as before.
