/**
 * The admin wording for a place OpenRouteService has no route to (reach-scoped-card-plan R1b). Pure helpers over the
 * rows {@code GET /api/admin/drive-times/unroutable} serves; the server owns every count.
 */

/** The flag's heading: what the admin should do about the place. */
export const NEEDS_DRIVE_POINT = 'Needs a drive point';

/**
 * "no route from 3 of 3 homes, 2 of 4 bases": the homes part and the bases part appear only when at least one of
 * that kind has no route; a kind with none unroutable is left out (its denominator would only add noise).
 *
 * @param {{unroutableHomes: number, judgedHomes: number, unroutableBases: number, judgedBases: number}} entry
 * @returns {string}
 */
export function noRouteText(entry) {
  const parts = [];
  if (entry.unroutableHomes > 0) {
    parts.push(`${entry.unroutableHomes} of ${entry.judgedHomes} ${entry.judgedHomes === 1 ? 'home' : 'homes'}`);
  }
  if (entry.unroutableBases > 0) {
    parts.push(`${entry.unroutableBases} of ${entry.judgedBases} ${entry.judgedBases === 1 ? 'base' : 'bases'}`);
  }
  return `no route from ${parts.join(', ')}`;
}

/**
 * "Needs a drive point · no route from 3 of 3 homes", the flag on the Locations list and the location panel.
 *
 * @param {object} entry a served row
 * @returns {string}
 */
export function driveFlagText(entry) {
  return `${NEEDS_DRIVE_POINT} · ${noRouteText(entry)}`;
}

/**
 * Indexes the served rows by location id.
 *
 * @param {Array<object>|null|undefined} rows the served rows
 * @returns {Map<number, object>}
 */
export function unroutableById(rows) {
  return new Map((rows ?? []).map((row) => [row.locationId, row]));
}
