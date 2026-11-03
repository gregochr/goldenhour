### Added — the user guide on the landing site

Nine guide pages (hub, getting started, reading the forecast, At a Glance, Coming up, Map, aurora and
night sky, Ask PhotoCast, Settings) are served at `photocast.online/guide/`, with a Guide link in the
masthead and footer of the five marketing pages and a pointer from the FAQ's Ask answer. The guide
documents can be framed by `app.photocast.online` only (`frame-ancestors`, no `X-Frame-Options`), and
`/guide` redirects to `/guide/`. The landing image copies `guide/` as a directory and fingerprints its
assets; `release-landing.sh` preflights and smoke-tests the guide and checks its framing headers on the
host and on the public URL. CI no longer treats `landing/guide/`, `landing/nginx.conf`,
`landing/Dockerfile` or `landing/security-headers.conf` as documentation-only, so the frontend suite and
the landing `nginx -t` step run when they change. Deploy the landing site before any app release that
opens the guide panel.
