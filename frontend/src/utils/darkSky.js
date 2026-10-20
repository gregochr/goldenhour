/**
 * The Bortle-class cutoff for "dark sky". The single source of truth - `MapView.jsx`, the callout,
 * the location sheet and the Plan->Map door's spot resolution (`mapDoor.js`) all import this rather
 * than keeping their own copy, since a second literal `4` with only a comment linking the two can
 * drift silently. Moved here from the retired `mapOverlay.js` (overlay convergence, C5).
 */
export const DARK_SKY_THRESHOLD = 4;
