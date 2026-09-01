### Changed — heat field kernel gains bloom, soft mask and a score callback (inert)

`utils/heatField.js` is ported forward from the Map tab v2 design bundle's reference kernel
(`docs/design/map-tab-v2/heat-field.js`, plan §3 P1): `field()` can build a second, optional
emissive ("bloom") layer whose alpha rises with score above a fixed 3★ gate — the fix for the
temperature ramp's luminance inversion on a dark ground, where 5★ is currently the ramp's darkest
colour; `paint()` gains a soft, blurred land-mask clip route (mask composed on its own surface,
applied in a single `destination-in` draw) alongside a new hard-clip route, both built for the
land clip P4 will add; and `drawTiles()` takes an optional `score` callback so a host can score a
location by something other than a solar-window index (the Map tab's future astro/aurora night
rows, which carry no `r[]` entry). No call site passes any of the new options yet, so no surface
renders any differently — this is the shared kernel contract, ahead of the surfaces that will use
it in P2 (bloom on) and P4 (land clip).
