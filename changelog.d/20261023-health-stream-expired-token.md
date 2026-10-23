### Fixed — waking the app after a day no longer shows a false "service unavailable" banner

The live status stream can't tell an expired sign-in from an outage, so after 24 h in the background it showed DOWN until the next routine request renewed the token, up to ten minutes later. When the stream fails it now makes one ordinary signed-in request, which renews the sign-in (and reconnects the stream) or shows the session-expired prompt; a real outage still shows DOWN, with at most one extra request per client.
