### Changed — the backend container gets 40 s to stop instead of Docker's default 10 s

The dynamic scheduler waits up to 30 s on shutdown for a job that is running to finish, so a deploy
that landed mid-job had the JVM killed at 10 s, before that wait, Hikari or the entity manager
finished. `stop_grace_period: 40s` on `goldenhour-backend` covers the scheduler's full budget with a
margin. A restart with nothing running still stops in a second or two, since pending cron firings no
longer hold the await (#1026). Boot's graceful HTTP drain (up to 30 s, run first) is not
covered on top of that; a slow request still in flight and a running job together could need 60 s,
a coincidence this was deliberately not sized for.
