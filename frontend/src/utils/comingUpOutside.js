import { isShaped } from './pickScope.js';
import { isCoastalLocation, isDarkSkyLocation } from './locationPredicates.js';

/**
 * Coming up for a Lite reader whose briefing body the server shaped by their sub-region picks
 * (sub-regions-plan §11 S5b-2, items 10-12).
 *
 * <p>A <b>licensed client selection</b> (architecture-decisions.md, "Coming up's coastal and dark-sky membership"):
 * which of the reader's own places are coastal or dark-sky is the served {@code /api/locations} facts
 * ({@link isCoastalLocation}, {@link isDarkSkyLocation}, the Map's own predicates) intersected with the served
 * {@code allowedLocationIds}. It re-derives no verdict, no rating and no pick: the server decided who is allowed.
 */

/** The two doors a Coming up entry's action can be, by its served {@code action.kind}. */
export const COASTAL_KIND = 'coastal-spots';
export const DARK_SKY_KIND = 'dark-sky-spots';

/**
 * Per-subject counts over the roster and over the reader's allowed places.
 *
 * @param {?Array<object>} locations the served enabled roster
 * @param {*} allowedLocationIds the briefing's served {@code allowedLocationIds}
 * @returns {?{zeroPicks: boolean, rosterKnown: boolean,
 *   coastal: {roster: number, allowed: number}, darkSky: {roster: number, allowed: number}}}
 *   null on an unshaped body (Pro, Admin, enforcement off), where nothing here applies
 */
export function comingUpOutsideFacts(locations, allowedLocationIds) {
  if (!isShaped(allowedLocationIds)) return null;
  const roster = Array.isArray(locations) ? locations : [];
  const allowed = new Set(allowedLocationIds);
  const count = (predicate) => {
    const matching = roster.filter(predicate);
    return { roster: matching.length, allowed: matching.filter((l) => allowed.has(l.id)).length };
  };
  return {
    zeroPicks: allowedLocationIds.length === 0,
    rosterKnown: roster.length > 0,
    coastal: count(isCoastalLocation),
    darkSky: count(isDarkSkyLocation),
  };
}

/**
 * The subject a Coming up entry's door row is about, or null when the entry has no such row. A door that is
 * withheld (no forecast on its date) has no row, so no line.
 *
 * @param {{action?: {kind?: string}, dayChips?: *, actionWithheld?: boolean}} entry
 * @returns {?('coastal'|'darkSky')}
 */
export function entrySubject(entry) {
  if (entry?.actionWithheld && !entry?.dayChips) return null;
  if (entry?.action?.kind === COASTAL_KIND) return 'coastal';
  if (entry?.action?.kind === DARK_SKY_KIND) return 'darkSky';
  return null;
}

/**
 * The line under an entry's door row, or null when no line is owed (an unshaped body, or the reader holds a matching
 * place). Nothing is said while the roster has not loaded, since "none of your sub-regions" would then be a guess.
 *
 * @param {'coastal'|'darkSky'} subject
 * @param {?object} facts from {@link comingUpOutsideFacts}
 * @param {boolean} [single] the entry is one day, so "This day opens" rather than "These days open"
 * @returns {?string}
 */
export function outsideLineText(subject, facts, single = false) {
  if (!facts) return null;
  const coastal = subject === 'coastal';
  if (facts.zeroPicks) {
    return `Choose your sub-regions in Settings to see ratings at ${coastal ? 'coastal' : 'dark-sky'} spots.`;
  }
  if (!facts.rosterKnown || facts[subject].allowed > 0) return null;
  if (coastal) {
    return `None of your sub-regions are on the coast. ${single ? 'This day opens' : 'These days open'} the map with coastal spots shown outside your sub-regions.`;
  }
  return `None of your sub-regions are dark-sky sites. ${single ? 'This night opens' : 'These nights open'} the map with dark-sky spots shown outside your sub-regions.`;
}
