### Fixed — an expired sign-in froze the app behind a false "service unavailable" banner

After 30 days unused (the refresh-token expiry) the app's token refresh was itself refused. The refresh request re-entered the client's own refresh handling and waited on itself, so every request hung, the session-expired prompt never came and the health stream showed DOWN until a manual sign-out and sign-in.

A refresh request (and login and logout) now never re-enters the refresh path, a failed refresh rejects the waiting requests straight away and shows the session-expired prompt, and no request is retried with an empty token.
