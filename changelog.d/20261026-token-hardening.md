### Changed — the emailed verification link is single-use, and signing out ends every session's access token

Opening the verification link now swaps it for a fresh setup token (valid for at most 30 minutes, held
only in the page's memory) that the password step alone accepts, so a link left in a mailbox, a log or a
browser history is dead the moment it has been used. A second visit to the same link says it has already
been used and to sign in or ask for a new one, and a duplicated verify request (a double click) no longer
turns the password step into an error.

Signing out now revokes the presented refresh token and raises the account's credential version, so every
access token issued before it stops working at its next request; another device quietly refreshes once and
carries on. An open live-status stream is cut only when it next reconnects. The browser clears its stored
session before it calls the server, so a request refused in that moment cannot trigger a refresh with the
token just revoked (which would be read as theft and sign every device out).

Ship together: a cached old page fails the password step once.
