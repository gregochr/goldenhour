/**
 * Reach ring tiers — ONE definition shared by every host that draws "how far from home" circles,
 * so a second surface can never silently disagree about what the rings mean.
 *
 * <p>Originally authored inline in {@code WindowRowFieldMap.jsx} (field-geography plan §3.2,
 * re-authored §5.2 — a 2026-08-30 owner decision: 25 mi / 50 mi, not the earlier unlabelled 40/80
 * km, and not the design bundle's 36/72 km either). Extracted here at map-tab-v2-plan.md §3 P8,
 * decision D-4, so {@code MapHeatLayer}'s canvas rings on the Map tab draw the exact same two
 * circles as the Plan popup's field map — a second authored pair of numbers here would drift the
 * moment either host's constant changed without the other noticing.
 *
 * <p>25 mi / 50 mi ≈ 40.2336 / 80.4672 km — visually indistinguishable from the original 40/80 km,
 * since those km values were always authored design constants rather than a measurement of
 * anything.
 */

/** 1 mile in km — the SI/international definition, exact, not an approximation. */
export const MI_TO_KM = 1.609344;

/**
 * The two tiers, each carrying its distance in both units and the drive time it approximates.
 *
 * <p>The label a ring carries depends on the SCREEN's own {@code reachMeasured} state (§5.2): by
 * default it states the distance itself — a claim true for every account, measured or not — and
 * only upgrades to the minutes figure once a real drive time has gated that screen's reach lens.
 * See {@code WindowRowFieldMap}'s own ring-label JSX and {@code MapLabels}' ring-label candidate
 * builder for the two call sites that apply that rule.
 */
export const RING_TIERS = [[25, 45], [50, 90]].map(([mi, minutes]) => ({
  mi, km: mi * MI_TO_KM, minutes,
}));

/** A ring drawn smaller than this, in px, is illegible — skip it rather than draw a dot. */
export const RING_MIN_PX = 18;

/**
 * Px-per-km at HOME's own latitude, at the map's current zoom — never the viewport centre's
 * (map-tab-v2-plan.md §3 P8 review). Web Mercator's ground resolution varies with latitude
 * (`cos(lat)`), so a ring's size is only stable across a pan when it is measured at the fixed
 * point it is drawn AROUND. `utils/heatField.js`'s own {@code radiusFor} reads
 * {@code map.getCenter()} instead, which is the right reference for the KERNEL's radius (centred
 * on wherever the reader is currently looking) but the wrong one for a ring centred on home: as
 * the reader pans north or south while home stays put, the "same" 25 mi ring would visibly grow or
 * shrink by double-digit percentages across this roster's latitude span. Adapted from
 * {@code heatField.kmPerPx}'s own "measure a real 1° delta at the reference point" pattern, for
 * Leaflet's {@code latLngToContainerPoint} projection instead of the flat SVG one that function
 * serves.
 *
 * <p>Deliberately takes no {@code lng} parameter: a same-longitude 1° latitude delta is what
 * isolates the latitude-dependent scale term from Web Mercator's OWN longitude-independent
 * horizontal scale, and every caller here only ever needs a scalar (metres/pixels are isotropic
 * at a point — the map does not stretch differently east-west from north-south at one latitude).
 *
 * @param {object} map a Leaflet map
 * @param {{lat: number, lon: number}} homeCoords
 * @returns {number} px per km, at home's latitude, at the map's current zoom
 */
export function pxPerKmAtHome(map, homeCoords) {
  const a = map.latLngToContainerPoint([homeCoords.lat, homeCoords.lon]);
  const b = map.latLngToContainerPoint([homeCoords.lat + 1, homeCoords.lon]);
  return Math.abs(b.y - a.y) / 111.2;
}
