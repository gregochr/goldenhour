### Changed — the Content-Security-Policy no longer allows inline styles

The enforced `style-src` is now `'self'`: `'unsafe-inline'` is dropped, and the report-only
candidate policy and its header are removed. The candidate shipped report-only in the previous
release and a console check of production on 2026-11-04 showed no violations. React `style={}`
props and Leaflet's `el.style` go through the CSSOM, which CSP does not block. The noctilucent-cloud
sighting banner used to inject a `<style>` element, which the stricter policy would have blocked, so
its rules moved unchanged into a co-located `NlcSightingBanner.css` that the build bundles into a
normal stylesheet. A new test forbids any `<style>` element in app source. The legacy-browser
"Browser Not Supported" notice keeps its `style=""` attributes; only browsers without module support
see it, and its message still shows, unstyled.
