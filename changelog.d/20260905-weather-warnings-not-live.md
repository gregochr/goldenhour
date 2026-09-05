### Fixed — the site advertised weather warnings, which are not built

The FAQ said "Weather warnings update in near real-time" while `index.html` said they were
"on the way". The index was right: `WeatherAPI.com` appears nowhere in the repository
outside these pages — no client, no configuration key, no dependency, no UI. The
integration has never existed, so this was not a feature awaiting exposure.

The same untrue claim sat on four pages, and all four are corrected:

- `faq.html` drops the near-real-time sentence and the WeatherAPI.com row from the data
  sources table. That table's own count moves from "nine different sources" to eight,
  which now matches the number of rows beneath it — every one of them a real integration.
- `privacy.html` drops WeatherAPI.com from the third parties table. A privacy policy
  naming a processor that receives nothing is wrong in the direction that matters, and
  removing it is the accurate and narrower claim.
- `acknowledgements.html` drops it from the data providers table, where it was credited
  as a commercial API "used under licence" that is not licensed or used.

The three safety disclaimers on `terms.html` and `faq.html` are untouched. They tell
readers to check *official* weather warnings from authoritative sources, which is correct
advice regardless of what PhotoCast ships, and is if anything more important while the
feature does not exist. `index.html`'s future-tense line is now the site's only mention.
