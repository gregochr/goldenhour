/**
 * The heat field's pure GEOMETRY — the arithmetic that needs no projection library.
 *
 * <h2>Why this is its own module</h2>
 *
 * <p>Everything here is {@code Math.min}/{@code Math.max}/{@code Math.cos} over lat-lng pairs, and
 * splitting it out is the {@code scoreRamp.rgb} precedent applied a second time. {@code heatField.js}
 * statically imports {@code d3-geo} and {@code topojson-client}, so a consumer that wanted only a
 * padded bounding box was pulling a 24 KB projection chunk into its module graph — which is exactly
 * what happened at P4: {@code WindowFirstMapPane} needed {@code latLngBounds} to frame the Map tab,
 * and fetched {@code geo} the moment the tab opened, in medallion view, before any field existed.
 * {@code scoreRamp.js}'s own docs warn about this pattern in as many words.
 *
 * <p>⚠️ {@code heatField.js} RE-EXPORTS every name here, so its own callers are unchanged and its
 * tests pass unedited. Import from there when you are already using the kernel; import from here
 * when geometry is all you want.
 */

/**
 * Default framing box — northern England and the Scottish border, the app's current coverage.
 * A corner MultiPoint, never a ring: a polygon's winding can be read as the whole globe, which
 * silently fits the projection to the world instead of the area you asked for.
 */
export const BBOX = {
  type: 'MultiPoint',
  coordinates: [
    [-3.85, 53.8],
    [-0.28, 53.8],
    [-0.28, 55.88],
    [-3.85, 55.88],
  ],
};

/** Arithmetic mean of an array, optionally through an accessor. Inlined rather than importing d3-array. */
export function mean(values, accessor) {
  if (!values.length) return undefined;
  let total = 0;
  for (const v of values) total += accessor ? accessor(v) : v;
  return total / values.length;
}

/**
 * Constrains {@code v} to [{@code a}, {@code b}]. Exported because both hosts need it for their
 * own dials (the prototype's `map-tab.js` and `plan-tab.js` both open by aliasing it).
 *
 * @param {number} v value to constrain
 * @param {number} a lower bound
 * @param {number} b upper bound
 * @returns {number} the constrained value
 */
export function clamp(v, a, b) {
  return Math.max(a, Math.min(b, v));
}

/**
 * A padded corner MultiPoint around a set of spots, for {@link proj}/{@link aspect}.
 * Longitude padding is 1.7× the latitude padding because at UK latitudes a degree of longitude is
 * roughly 0.6 of a degree of latitude on screen — equal degrees would pad unevenly.
 *
 * @param {Array<{lat: number, lng: number}>} spots
 * @param {number} [padDeg] latitude padding in degrees (default 0.16)
 * @returns {object} a GeoJSON MultiPoint of the four corners
 */
export function bbox(spots, padDeg) {
  if (!spots || !spots.length) return BBOX;
  const p = padDeg == null ? 0.16 : padDeg;
  const la = spots.map((s) => s.lat);
  const ln = spots.map((s) => s.lng);
  const a = Math.min(...la) - p;
  const b = Math.max(...la) + p;
  const c = Math.min(...ln) - p * 1.7;
  const d = Math.max(...ln) + p * 1.7;
  return {
    type: 'MultiPoint',
    coordinates: [
      [c, a],
      [d, a],
      [d, b],
      [c, b],
    ],
  };
}

/**
 * The same box as `[[south, west], [north, east]]` for Leaflet's `fitBounds`.
 *
 * @param {Array<{lat: number, lng: number}>} spots
 * @param {number} [padDeg] latitude padding in degrees
 * @returns {number[][]} [[south, west], [north, east]]
 */
export function latLngBounds(spots, padDeg) {
  const c = bbox(spots, padDeg).coordinates;
  const la = c.map((x) => x[1]);
  const ln = c.map((x) => x[0]);
  return [
    [Math.min(...la), Math.min(...ln)],
    [Math.max(...la), Math.max(...ln)],
  ];
}

/**
 * Aspect of a frame (height / width) so a surface can size itself to its geography. The longitude
 * span is cos-latitude corrected, which is why a UK frame is taller than its raw degree span.
 *
 * @param {object} [fitTo] a corner MultiPoint (default {@link BBOX})
 * @returns {number} height / width, or 1 for a degenerate frame
 */
export function aspect(fitTo) {
  const cs = (fitTo || BBOX).coordinates;
  const la = cs.map((c) => c[1]);
  const ln = cs.map((c) => c[0]);
  const dLat = Math.max(...la) - Math.min(...la);
  const dLng = (Math.max(...ln) - Math.min(...ln)) * Math.cos((mean(la) * Math.PI) / 180);
  return dLng > 0 ? dLat / dLng : 1;
}
