### Fixed — a refused token refresh no longer hangs the app

When a session's refresh token had been revoked, the next request after the access token expired waited forever (an Ask question spun indefinitely) instead of signing the reader out. A refused refresh now ends the session and sends the reader to sign in.
