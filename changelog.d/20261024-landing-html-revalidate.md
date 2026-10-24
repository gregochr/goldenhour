### Fixed — landing pages revalidate on every visit; security headers on every response

The landing pages are now served `Cache-Control: no-cache`, so browsers revalidate on every visit and
a deploy is seen at once instead of an old page lingering under heuristic caching. The security headers
(frame, content-type, XSS and referrer policy) are now sent on every response, including css, js and
images, which previously dropped them. `.webp` images now get the 30-day asset cache.
