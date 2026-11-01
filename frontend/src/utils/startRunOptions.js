import { isSkyPromptCandidate } from './locationTypes.js';

/**
 * The Start a run panel's options (runs-redesign-plan.md §5.3), in display order, in the two radio groups (the
 * Testing group is the aurora simulation row, not an option). The copy is the plan's, verbatim. {@code startLabel}
 * is the footer button's text ("Start {option, lower case}", but "JFDI" keeps its capitals).
 */
export const START_GROUPS = [
  {
    id: 'forecast',
    label: 'Forecast',
    options: [
      {
        key: 'scheduled-batch', name: 'Scheduled batch', startLabel: 'Start scheduled batch',
        description: 'Same triage and stability gates as the overnight cycle.',
      },
      {
        key: 'jfdi-batch', name: 'JFDI batch', startLabel: 'Start JFDI batch',
        description: 'All locations, T to T+3, sunrise and sunset, no gates.',
      },
      {
        key: 'briefing', name: 'Briefing', startLabel: 'Start briefing',
        description: 'Rebuilds the briefing from current ratings.',
      },
      {
        key: 'aurora-forecast', name: 'Aurora forecast', startLabel: 'Start aurora forecast',
        description: 'Choose the nights to score.',
      },
    ],
  },
  {
    id: 'data',
    label: 'Data refresh',
    options: [
      { key: 'tide-data', name: 'Tide data', startLabel: 'Start tide data', description: null },
      {
        key: 'tide-history', name: 'Tide history', startLabel: 'Start tide history',
        description: '12 months for seascapes, in 7-day chunks. Skips dates already stored.',
      },
      {
        key: 'light-pollution', name: 'Light pollution', startLabel: 'Start light pollution',
        description: 'Locations without a Bortle rating.',
      },
    ],
  },
];

/** The sentence a 409 shows, whichever option was started. */
export const BUSY_MESSAGE = 'A run is already in progress. Wait for it to finish.';

/** Whether the option is a batch (its scope is the region checklist). */
export const isBatchOption = (key) => key === 'scheduled-batch' || key === 'jfdi-batch';

/**
 * The Tide data option's description: the count of coastal locations drops out until the location summary has loaded.
 *
 * @param {number|null} count the coastal location count, or null before the locations have loaded
 * @returns {string} the one-line description
 */
export function tideDataDescription(count) {
  return count == null ? 'Coastal locations from WorldTides.' : `${count} coastal locations from WorldTides.`;
}

/**
 * Returns true if the location may go to the SKY prompt.
 *
 * <p>The Start a run panel previews which locations a colour RUN will touch, so the predicate it mirrors is
 * `LocationEntity.hasColourTypes()` — the gate those engines filter on — and NOT the briefing's own candidacy test.
 * The two deliberately disagree about WOODLAND: a wood is a briefing candidate but has no sky to forecast.
 */
function hasColourTypes(loc) {
  return isSkyPromptCandidate(loc.locationType);
}

/**
 * Returns true if the location is pure-wildlife only (WILDLIFE but no colour types).
 *
 * <p>NOT `locationTypes.isWildlifeOnly`, and the two names mean DIFFERENT sets. This is the backend pure-wildlife rule
 * (a WILDLIFE+WOODLAND or WILDLIFE+BLUEBELL place counts, because no colour type is present). `isWildlifeOnly` is the
 * narrower `LocationEntity.isWildlifeOnly()` (WILDLIFE is the ONLY type), which decides who gets a comfort forecast.
 */
function isPureWildlife(loc) {
  const types = loc.locationType || [];
  return types.includes('WILDLIFE') && !hasColourTypes(loc);
}

/** Returns true if the location is coastal (has at least one tide type). */
export function isCoastal(loc) {
  const tides = loc.tideType || [];
  return tides.length > 0;
}

/**
 * Filters enabled locations by run type and groups them by region.
 *
 * @param {Array} locations - All locations from the API.
 * @param {string} runType - WEATHER or TIDE (anything else lists the colour-type locations).
 * @returns {{ groups: Array<{region: string, locations: string[]}>, total: number }}
 */
export function getLocationSummary(locations, runType) {
  const enabled = locations.filter((loc) => loc.enabled !== false);

  let filtered;
  if (runType === 'TIDE') {
    filtered = enabled.filter(isCoastal);
  } else if (runType === 'WEATHER') {
    filtered = enabled.filter(isPureWildlife);
  } else {
    filtered = enabled.filter(hasColourTypes);
  }

  // Group by region name (or "No region")
  const byRegion = {};
  for (const loc of filtered) {
    const regionName = loc.region?.name || 'No region';
    if (!byRegion[regionName]) byRegion[regionName] = [];
    byRegion[regionName].push(loc.name);
  }

  const groups = Object.entries(byRegion)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([region, names]) => ({ region, locations: names.sort() }));

  return { groups, total: filtered.length };
}

/**
 * The coastal SEASCAPE locations' summary: the scope of a tide history backfill.
 *
 * @param {Array} locations all locations from the API
 * @returns {{ groups: Array<{region: string, locations: string[]}>, total: number }}
 */
export function getSeascapeTideSummary(locations) {
  return getLocationSummary(locations.filter((loc) => (loc.locationType || []).includes('SEASCAPE')), 'TIDE');
}
