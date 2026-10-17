/**
 * Whether a viewline answer is one {@code AuroraViewlineOverlay} will actually draw — the overlay's
 * own "I will render" test, kept in its own module so the overlay and the Map legend's note about
 * the dashed line both read it and cannot drift (aurora heat A3).
 *
 * @param {object|null} viewline the viewline response
 * @returns {boolean}
 */
export function viewlineDrawable(viewline) {
  return Boolean(viewline && viewline.active && viewline.points && viewline.points.length > 0);
}
