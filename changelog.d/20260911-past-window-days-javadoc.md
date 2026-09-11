### Docs — `PAST_WINDOW_DAYS`'s javadoc states the reasons it actually has

The javadoc on `ForecastController.PAST_WINDOW_DAYS` justified keeping the list endpoint's two-day
past window with two claims that had both stopped being true. "The strip keeps two dimmed chips"
described the DateStrip, which has been retired. And "not zero, for a timezone reason" rested on
`computeAutoSelection` picking the browser's *local* date, so a reader west of the UK could ask for
T-1 — but it has read the UK calendar (`ukDateStr`) for some time, so both sides of that comparison
are `Europe/London`. That left the javadoc's only "not zero" argument false, and a reader following
it could reasonably have set the value to 0.

⚠️ **That would have broken two things the frontend now depends on, neither visible from the
backend.** The aurora night in progress is *yesterday's* date before dawn, and #803's
`resolveMapDate` honours a selection naming that night only if the date is in the set this endpoint
returns — so at zero the Map tab would silently fall through to today and lose its aurora viewline on
the night the alert is about. And on a day nothing has been forecast, the past rows are what keep the
client's date set non-empty, which is what keeps the Map tab reachable at all rather than withheld.
The javadoc now records both, and that each needs only one day: the night in progress began at most
yesterday, and a trace of every frontend reader of a served date found none reaching further back.
The second day is margin inherited from the old reason, so reducing it is a payload decision rather
than a correctness one. It also records that `BriefingEvaluationController` shares the constant, so
changing it moves both endpoints, and that `/history` is the ADMIN-only backtesting endpoint.

No value changed.
