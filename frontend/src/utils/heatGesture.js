/**
 * The heat field's gesture arithmetic (performance review F17 / P4b) — pure, so it is testable
 * without Leaflet.
 *
 * <p>While a pinch or fly is in flight the field is not redrawn: its last bitmap is moved to stay
 * registered with the map. That is the same sum Leaflet's {@code GridLayer._setZoomTransform} does
 * for a tile level — the bitmap's top-left corner is a fixed geographic point, and the picture
 * scales about that corner by the ratio of the two zooms.
 *
 * @param {number} drawnZoom the zoom the bitmap was drawn at
 * @param {number} zoom the map's current (possibly fractional) zoom
 * @param {{x: number, y: number}} topLeft the bitmap's top-left corner projected at {@code zoom}
 *        into layer coordinates ({@code map.latLngToLayerPoint} of the corner's LatLng)
 * @returns {{offset: {x: number, y: number}, scale: number}} the arguments for
 *          {@code L.DomUtil.setTransform}, applied with {@code transform-origin: 0 0}
 */
export function gestureTransform(drawnZoom, zoom, topLeft) {
  // Web Mercator doubles per zoom level — the same ratio as `map.getZoomScale(zoom, drawnZoom)`
  // for the map's default CRS.
  return { offset: { x: topLeft.x, y: topLeft.y }, scale: 2 ** (zoom - drawnZoom) };
}
