### Changed — the landing site sends a Content-Security-Policy and drops X-XSS-Protection

`photocast.online` now sends a Content-Security-Policy sized to what its pages load (same-origin scripts
and fetches, same-origin and inline styles, Google Fonts, same-origin and `data:` images, no plugins, no
foreign form posts), plus a Permissions-Policy that switches off camera, microphone, geolocation, payment
and USB, and `Cross-Origin-Opener-Policy: same-origin`. The obsolete `X-XSS-Protection` header is gone.
The guide keeps its framing rule (only `app.photocast.online` may frame it, no `X-Frame-Options`), now
through the same shared header file as every other page instead of a hand-repeated copy. HSTS is not set.
The landing site deploys through `release-landing.sh`, so the headers go live when that is run.
