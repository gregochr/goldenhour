### Fixed — landing assets are fingerprinted so a deploy is not masked by a 30-day cache

The landing stylesheet, script and images now get content-fingerprinted URLs (`?v=<hash>`) when
the image is built, so a deploy can no longer be masked by the 30-day Cloudflare and browser cache
(live, the Pro pills, new fonts and colours were missing because the old `photocast.css` was
still being served). The Ask feature's "Available with PhotoCast Pro" is now a Pro pill.
