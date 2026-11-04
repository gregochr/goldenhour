### Changed — the landing site sends a strict Content-Security-Policy and drops X-XSS-Protection

`photocast.online` now sends a Content-Security-Policy sized to what its pages load (same-origin scripts
and fetches, same-origin styles plus Google Fonts, Google's font files, same-origin and `data:` images, no
plugins, no foreign form posts), a Permissions-Policy that switches off camera, microphone, geolocation,
payment and USB, and `Cross-Origin-Opener-Policy: same-origin`. The obsolete `X-XSS-Protection` header is
gone. The policy has no `'unsafe-inline'` for styles: every `style=""` attribute on the landing and guide
pages became a class in `photocast.css` or `guide.css`, and the home page's `<style>` block moved to a new
`home.css` (its rules style bare elements, so they stay off the other pages). A Vitest test fails if an
inline style comes back. The guide keeps its framing rule (only `app.photocast.online` may frame it, no
`X-Frame-Options`), now through the same shared header file as every other page. HSTS is not set. The
landing site deploys through `release-landing.sh`, so the headers go live when that is run.
