/**
 * Utility functions for building map marker SVGs and computing score colours.
 * Extracted from MapView for testability.
 */
import L from 'leaflet';

/** Half-circumference of the arc circle (radius 19). */
const HALF_CIRC = Math.PI * 19;

/** Full circumference of the arc circle (radius 19). */
const FULL_CIRC = 2 * Math.PI * 19;

/** Left half-arc SVG path (counterclockwise from bottom to top = left side). */
const LEFT_ARC = 'M 22 41 A 19 19 0 0 0 22 3';

/** Right half-arc SVG path (clockwise from bottom to top = right side). */
const RIGHT_ARC = 'M 22 41 A 19 19 0 0 1 22 3';

/** Maps a 1-5 star rating to a marker colour. */
export const RATING_COLOURS = {
  1: '#6B6B6B',
  2: '#6B5000',
  3: '#A06E00',
  4: '#CC8A00',
  5: '#E5A00D',
};

/**
 * Maps an average 0-100 score to a marker fill colour.
 *
 * @param {number|null} avg - Average score, or null for no data.
 * @returns {string} Hex colour string.
 */
export function scoreColour(avg) {
  if (avg == null) return '#3A3D45';
  if (avg > 80) return '#E5A00D';
  if (avg > 60) return '#CC8A00';
  if (avg > 40) return '#A06E00';
  if (avg > 20) return '#6B5000';
  return '#6B6B6B';
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
    return { label: '\uD83D\uDC3E', colour: '#16a34a' };
  }
  if (fierySky != null && goldenHour != null) {
    if (rating != null) {
      return { label: `${rating}\u2605`, colour: RATING_COLOURS[rating] ?? '#6B6B6B' };
    }
    const avg = Math.round((fierySky + goldenHour) / 2);
    return { label: avg, colour: scoreColour(avg) };
  }
  if (rating != null) {
    return { label: `${rating}\u2605`, colour: RATING_COLOURS[rating] ?? '#6B6B6B' };
  }
  return { label: '?', colour: scoreColour(null) };
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
  const hasBothScores = fierySky != null && goldenHour != null;
  const hasRatingOnly = rating != null && !hasBothScores;
  const showArcs = !isPureWildlife && (hasBothScores || hasRatingOnly);

  if (!showArcs) {
    return `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  <circle cx="22" cy="22" r="17" fill="${colour}" stroke="rgba(255,255,255,0.2)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="${isPureWildlife ? 20 : 15}" font-weight="800" fill="#0f172a">${label}</text>
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
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="#0f172a">${label}</text>
</svg>`;
  }

  // Haiku: single full ring proportional to rating/5
  const fill = FULL_CIRC * (rating / 5);
  return `<svg width="44" height="44" viewBox="0 0 44 44" xmlns="http://www.w3.org/2000/svg" style="filter:drop-shadow(0 2px 6px rgba(0,0,0,0.7))">
  <circle cx="22" cy="22" r="19" fill="none" stroke="rgba(255,255,255,0.1)" stroke-width="3"/>
  <circle cx="22" cy="22" r="19" fill="none" stroke="#E5A00D" stroke-width="3" stroke-linecap="round" stroke-dasharray="${fill.toFixed(2)} ${(FULL_CIRC - fill).toFixed(2)}" transform="rotate(90 22 22)"/>
  <circle cx="22" cy="22" r="17" fill="${colour}" stroke="rgba(255,255,255,0.2)" stroke-width="1.5"/>
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="#0f172a">${label}</text>
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

  const ratings = markers
    .map((m) => m.options.icon?.options?.rating)
    .filter((r) => r != null);
  const avgScore = ratings.length > 0
    ? (ratings.reduce((sum, r) => sum + r, 0) / ratings.length) * 20
    : null;
  const bg = scoreColour(avgScore);

  const fieryScores = markers
    .map((m) => m.options.icon?.options?.fierySky)
    .filter((s) => s != null);
  const goldenScores = markers
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
  <text x="22" y="22" text-anchor="middle" dominant-baseline="central" font-size="15" font-weight="800" fill="#0f172a">${count}</text>
</svg>`;

  return L.divIcon({
    html,
    className: '',
    iconSize: L.point(size, size),
  });
}
