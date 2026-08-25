/**
 * Utility functions for building map marker SVGs and computing score colours.
 * Extracted from MapView for testability.
 */
import L from 'leaflet';
import { rampHex } from '../utils/scoreRamp.js';
import { readableInkOn } from '../utils/windowFirstSpots.js';

/** Half-circumference of the arc circle (radius 19). */
const HALF_CIRC = Math.PI * 19;

/** Full circumference of the arc circle (radius 19). */
const FULL_CIRC = 2 * Math.PI * 19;

/** Left half-arc SVG path (counterclockwise from bottom to top = left side). */
const LEFT_ARC = 'M 22 41 A 19 19 0 0 0 22 3';

/** Right half-arc SVG path (clockwise from bottom to top = right side). */
const RIGHT_ARC = 'M 22 41 A 19 19 0 0 1 22 3';

/** Stand-down fill for triaged rows (dark red). */
export const STAND_DOWN_COLOUR = '#501313';

/**
 * The no-data fill. It is not a score, so the ramp has nothing to say about it and {@code rampRgb}
 * would answer with its bottom stop — which reads as "1 star", the one claim an unscored marker
 * must not make.
 */
const NO_DATA_COLOUR = '#3A3D45';

/**
 * The 1-5 rating a 0-100 average stands for, which is the only sound way to hand an average to a
 * ramp defined on stars.
 *
 * <p>Exactly the inverse of the one derivation that produces such an average from ratings —
 * {@code createClusterIcon}'s {@code mean(ratings) * 20} — so a cluster of 4-star spots resolves to
 * the 4-star colour rather than to a bucket boundary. The other producer,
 * {@code markerLabelAndColour}'s {@code (fierySky + goldenHour) / 2}, is a genuine 0-100 potential
 * and maps onto the same line; {@link rampHex} clamps both ends, so a 0 lands on the bottom stop
 * rather than off the ramp.
 */
function starsFromAverage(avg) {
  return avg / 20;
}

/**
 * Maps an average 0-100 score to a marker fill colour on the score ramp — the map's only SCORE
 * colour language (every rated marker, cluster bubble and star-filter swatch paints on it, in
 * every view). Stand-down, no-data and wildlife markers are not scores and keep their own fills
 * ({@link STAND_DOWN_COLOUR}, {@link NO_DATA_COLOUR}, {@code markerLabelAndColour}'s paw green).
 *
 * @param {number|null} avg - Average score, or null for no data.
 * @returns {string} Hex colour string.
 */
export function scoreColour(avg) {
  return avg == null ? NO_DATA_COLOUR : rampHex(starsFromAverage(avg));
}

/**
 * One rating's fill on the score ramp. Defined on the continuum and clamps, so a rating outside
 * 1-5 resolves to the ramp's end rather than falling through to a fallback colour.
 */
function ratingColour(rating) {
  return rampHex(rating);
}

/**
 * Determines the label text and fill colour for a marker based on available data.
 *
 * Priority: wildlife > both scores (rating label + rating colour) > rating only > no data.
 *
 * @param {number|null} rating - Star rating 1-5.
 * @param {number|null} fierySky - Fiery Sky Potential 0-100.
 * @param {number|null} goldenHour - Golden Hour Potential 0-100.
 * @param {boolean} isPureWildlife - True for wildlife-only locations.
 * @returns {{ label: string|number, colour: string }}
 */
export function markerLabelAndColour(rating, fierySky, goldenHour, isPureWildlife) {
  if (isPureWildlife) {
    // The paw is a subject, not a score, and its green says so. Deliberately NOT rebased on the
    // ramp: a wildlife marker carries no sky rating at all, and giving it a ramp colour would put
    // it on the same scale as the field it is standing on while meaning something else entirely.
    return { label: '\uD83D\uDC3E', colour: '#16a34a' };
  }
  if (fierySky != null && goldenHour != null) {
    if (rating != null) {
      return { label: `${rating}\u2605`, colour: ratingColour(rating) };
    }
    const avg = Math.round((fierySky + goldenHour) / 2);
    return { label: avg, colour: scoreColour(avg) };
  }
  if (rating != null) {
    return { label: `${rating}\u2605`, colour: ratingColour(rating) };
  }
  return { label: '—', colour: scoreColour(null) };
}

/**
 * Builds a muted stand-down SVG marker (em-dash, dark red, 55% opacity, 30px).
 *
 * Used for triaged forecasts where the colour pipeline was skipped and there is
 * no score to display.
 *
 * @returns {string} SVG markup string.
 */
export function buildStandDownSvg() {
  return `<svg width="30" height="30" viewBox="0 0 30 30" xmlns="http://www.w3.org/2000/svg" style="opacity:0.55;filter:drop-shadow(0 1px 3px rgba(0,0,0,0.5))">
  <circle cx="15" cy="15" r="12" fill="${STAND_DOWN_COLOUR}" stroke="rgba(255,255,255,0.18)" stroke-width="1"/>
  <text x="15" y="15" text-anchor="middle" dominant-baseline="central" font-size="14" font-weight="700" fill="#f1d6d6">\u2014</text>
</svg>`;
}

/**
 * Builds an SVG string for a map marker circle with optional radial progress arcs.
 *
 * Three rendering modes:
 * - Sonnet/Opus (fierySky + goldenHour present): two independent half-arcs filling bottom-up.
 * - Haiku (rating only, no scores): single full ring proportional to rating/5.
 * - Wildlife / no-data: plain circle, no arcs.
 *
 * @param {string|number} label - Text displayed in the centre of the marker.
 * @param {string} colour - Fill colour for the inner circle.
 * @param {number|null} fierySky - Fiery Sky Potential 0-100.
 * @param {number|null} goldenHour - Golden Hour Potential 0-100.
 * @param {number|null} rating - Star rating 1-5.
 * @param {boolean} isPureWildlife - True for wildlife-only locations.
 * @returns {string} SVG markup string.
 */
export function buildMarkerSvg(label, colour, fierySky, goldenHour, rating, isPureWildlife) {
  // The label's ink is derived from the fill it sits on, per stop, by the same two-ink rule the
  // v2 spot badges use ({@link readableInkOn}) — no single ink clears WCAG AA across the score
  // ramp, whose luminance peaks at 4-5★ and falls away below 3★. The hard-coded dark ink this
  // replaces measured 2.96:1 at 1★ and 3.70:1 at 2★ (v1-retirement plan §8.13, D3's review
  // finding); the derived pair clears 4.5:1 on every stop, and on the no-data grey, too.
  const ink = readableInkOn(colour);
  const hasBothScores = fierySky != null && goldenHour != null;
  const hasRatingOnly = rating != null && !hasBothScores;
  const showArcs = !isPureWildlife && (hasBothScores || hasRatingOnly);

  if (!showArcs) {
    return `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  <circle cx="22" cy="22" r="17" fill="${colour}" stroke="rgba(255,255,255,0.2)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="${isPureWildlife ? 20 : 15}" font-weight="800" fill="${ink}">${label}</text>
</svg>`;
  }

  if (hasBothScores) {
    const fieryFill = HALF_CIRC * (fierySky / 100);
    const goldenFill = HALF_CIRC * (goldenHour / 100);

    const fieryArc = fierySky > 0
      ? `<path d="${LEFT_ARC}" fill="none" stroke="#f97316" stroke-width="3" stroke-linecap="round" stroke-dasharray="${fieryFill.toFixed(2)} ${HALF_CIRC.toFixed(2)}"/>`
      : '';
    const goldenArc = goldenHour > 0
      ? `<path d="${RIGHT_ARC}" fill="none" stroke="#E5A00D" stroke-width="3" stroke-linecap="round" stroke-dasharray="${goldenFill.toFixed(2)} ${HALF_CIRC.toFixed(2)}"/>`
      : '';

    return `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  <circle cx="22" cy="22" r="19" fill="none" stroke="rgba(255,255,255,0.1)" stroke-width="3"/>
  ${fieryArc}
  ${goldenArc}
  <circle cx="22" cy="22" r="17" fill="${colour}" stroke="rgba(255,255,255,0.2)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="${ink}">${label}</text>
</svg>`;
  }

  // Haiku: single full ring proportional to rating/5
  const fill = FULL_CIRC * (rating / 5);
  return `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  <circle cx="22" cy="22" r="19" fill="none" stroke="rgba(255,255,255,0.1)" stroke-width="3"/>
  <circle cx="22" cy="22" r="19" fill="none" stroke="#E5A00D" stroke-width="3" stroke-linecap="round" stroke-dasharray="${fill.toFixed(2)} ${(FULL_CIRC - fill).toFixed(2)}" transform="rotate(90 22 22)"/>
  <circle cx="22" cy="22" r="17" fill="${colour}" stroke="rgba(255,255,255,0.2)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="${ink}">${label}</text>
</svg>`;
}

/**
 * Creates a custom Leaflet DivIcon for a marker cluster group.
 * Background colour follows the grey→gold ramp based on average child rating.
 * PRO/ADMIN users see fiery sky (left) and golden hour (right) half-arc progress.
 * Sized by cluster child count.
 *
 * @param {object} cluster - Leaflet MarkerCluster instance.
 * @param {string} [role] - User role (ADMIN/PRO_USER/LITE_USER).
 * @returns {L.DivIcon}
 */
export function createClusterIcon(cluster, role) {
  const count = cluster.getChildCount();
  let size = 40;
  if (count >= 20) size = 56;
  else if (count >= 10) size = 48;

  const markers = cluster.getAllChildMarkers();

  // Exclude waterfall markers from cluster score averages
  const scorableMarkers = markers.filter((m) => !m.options.icon?.options?.excludeFromCluster);

  const ratings = scorableMarkers
    .map((m) => m.options.icon?.options?.rating)
    .filter((r) => r != null);
  const avgScore = ratings.length > 0
    ? (ratings.reduce((sum, r) => sum + r, 0) / ratings.length) * 20
    : null;
  const bg = scoreColour(avgScore);
  // Same per-fill ink rule as buildMarkerSvg: the count must stay readable on every ramp stop
  // and on the no-data grey, and a hard-coded dark ink fails below 3★.
  const ink = readableInkOn(bg);

  const fieryScores = scorableMarkers
    .map((m) => m.options.icon?.options?.fierySky)
    .filter((s) => s != null);
  const goldenScores = scorableMarkers
    .map((m) => m.options.icon?.options?.goldenHour)
    .filter((s) => s != null);
  const avgFiery = fieryScores.length > 0
    ? fieryScores.reduce((sum, v) => sum + v, 0) / fieryScores.length
    : null;
  const avgGolden = goldenScores.length > 0
    ? goldenScores.reduce((sum, v) => sum + v, 0) / goldenScores.length
    : null;

  const showArcs = role !== 'LITE_USER' && avgFiery != null && avgGolden != null;

  let arcsHtml = '';
  if (showArcs) {
    arcsHtml = `<circle cx="22" cy="22" r="19" fill="none" stroke="rgba(255,255,255,0.1)" stroke-width="3"/>`;
    if (avgFiery > 0) {
      const fill = HALF_CIRC * (avgFiery / 100);
      arcsHtml += `<path d="${LEFT_ARC}" fill="none" stroke="#f97316" stroke-width="3" stroke-linecap="round" stroke-dasharray="${fill.toFixed(2)} ${HALF_CIRC.toFixed(2)}"/>`;
    }
    if (avgGolden > 0) {
      const fill = HALF_CIRC * (avgGolden / 100);
      arcsHtml += `<path d="${RIGHT_ARC}" fill="none" stroke="#E5A00D" stroke-width="3" stroke-linecap="round" stroke-dasharray="${fill.toFixed(2)} ${HALF_CIRC.toFixed(2)}"/>`;
    }
  }

  const html = `<svg width="${size}" height="${size}" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  ${arcsHtml}
  <circle cx="22" cy="22" r="17" fill="${bg}" stroke="rgba(255,255,255,0.15)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="${ink}">${count}</text>
</svg>`;

  return L.divIcon({
    html,
    className: '',
    iconSize: L.point(size, size),
  });
}
