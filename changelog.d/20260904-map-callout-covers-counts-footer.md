### Fixed — the callout covered the counts footer on a phone

`calloutBand` only treats a bar spanning at least half the frame width as a floor. Measured at
375×633, the counts footer is 184px — **48.9%** — just under the threshold, so the band ran straight
through it and the callout painted over `16 named · 16 rated of 18` both collapsed and expanded.
The footer now opts out of the width test by name rather than the threshold being lowered, which
would have started counting Leaflet's own zoom+home corner. The card also takes a `max-height` from
the same band that positions it, so no length of narrative can push it over a control.
