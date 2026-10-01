### Added — production health check, every morning and after every deploy

On 2026-10-01 the owner logged into dockermacmini by hand, saw Ubuntu's "26 updates can be applied"
banner, applied them, hit "*** System restart required ***", rebooted, and then checked production
came back the only way available: running `docker compose ps`, `systemctl status cloudflared`,
`tailscale status` and a `curl` at `https://app.photocast.online/api/briefing` by eye. The 26 updates
had sat unapplied until someone happened to log in and notice a banner — nothing had been watching
for that.

`scripts/prod-health.sh` is that four-command check, automated. It runs in two modes: `--deploy`
(compose service health, cloudflared, Tailscale, and a real 401 from the app from outside the
tunnel, retried up to 3 times 10s apart to ride out a momentary Cloudflare blip) and `--daily`,
which adds a pending-reboot check and a pending-apt-updates count. The new `Production Health`
scheduled workflow runs `--daily` on the self-hosted runner at 07:00 UTC, plus a second,
deliberately `ubuntu-latest` job that probes `app.photocast.online` from outside — if the host is
down entirely, the self-hosted job never starts and never fails, so nothing would otherwise fire.
The two jobs are notified by **two independent** notifier jobs rather than one shared one, because
`needs: [a, b]` waits for every listed job to finish — if the host were down, the external probe
would fail in seconds while the self-hosted job sat queued for a runner that will never appear, so
one notifier would not fire until GitHub eventually cancelled that queued job hours later. The
external job also carries a watchdog step that polls the run's own job list for up to 10 minutes,
because an Actions runner that fails to restart after a reboot leaves the on-host job permanently
queued rather than failed — invisible to both the host's own checks and the external probe's 401 —
so without it the runner itself could die silently and nothing would ever notice. The `deploy` job
in `deploy.yml` now runs `--deploy` immediately after bringing the new containers up, in place of
the old fixed `sleep 30; docker compose ps`.

Owner decision: a pending reboot or pending apt updates **fail** the morning run — a warning on an
otherwise-green run is invisible, which is exactly how the 26 updates went unnoticed — but they must
**never** fail a deploy. A release must not be blocked because the host wants a reboot, so `--deploy`
deliberately omits both checks.
