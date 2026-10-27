import {
  formatCivilDay, isLockedAdd, lockedAddLine, pickerApplies, settingsStatusLine,
} from './subRegionCopy.js';

/** What the Map's lines say is changed (Settings says "these"). */
const MAP_SUBJECT = 'your sub-regions';

/**
 * The Map's S4 action for a reader whose body the server shaped by their picks (sub-regions-plan §11 S5a-3,
 * decision 9): which button the status asks for, and the line that sits above it. Everything the server decides
 * arrives in the state; the one sentence spelled here is the at-the-limit lock line, because S4's own
 * {@code settingsStatusLine} words it for Settings ("You can next change these on ...") and the Map says it about
 * "your sub-regions". No rule lives here: the status picks the label, the date is the server's.
 *
 * @param {?import('../api/subRegionApi.js').SubRegionState} state the S4 GET body, or null while it is unread
 * @returns {?{label: ?string, line: ?string}} null when there is nothing to say (not loaded, not enforced, or a
 *   reader who does not pick); {@code label} null when no button is offered
 */
export function subRegionAction(state) {
  if (!pickerApplies(state)) return null;
  const line = settingsStatusLine(state, MAP_SUBJECT);
  if (state.overLimit) return { label: 'Change your sub-regions', line };
  switch (state.status) {
    case 'FIRST_CHOICE':
      return { label: 'Choose your sub-regions', line };
    case 'FREE_UNTIL':
    case 'OPEN':
      return { label: 'Change your sub-regions', line };
    case 'LOCKED': {
      if (!isLockedAdd(state)) {
        const day = formatCivilDay(state.nextChangeOn);
        return { label: null, line: day ? `You can next change ${MAP_SUBJECT} on ${day}.` : null };
      }
      return { label: 'Add a sub-region', line: lockedAddLine(state) };
    }
    default:
      return null;
  }
}

/**
 * A region's example places for its door: the first example place of the region's first two sub-regions (one
 * when the region has one sub-region, or when only one has an example), from the S4 options.
 *
 * @param {?import('../api/subRegionApi.js').SubRegionState} state the S4 GET body
 * @param {string} regionName the region's name
 * @returns {string[]} up to two names; empty when the options are not loaded or name no such region
 */
export function regionExamplePlaces(state, regionName) {
  const options = Array.isArray(state?.options) ? state.options : [];
  const region = options.find((r) => r.regionName === regionName);
  const subs = Array.isArray(region?.subRegions) ? region.subRegions.slice(0, 2) : [];
  return subs.map((s) => s?.exampleLocations?.[0]).filter(Boolean);
}
