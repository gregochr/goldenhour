/**
 * Bodies of `GET /api/user/settings/sub-regions` for the sub-region picker tests (S4). Two regions,
 * four and two sub-regions, so a limit of 3 can be reached inside one region or across both.
 */

export const OPTIONS = [
  {
    regionId: 1,
    regionName: 'Northumberland & Tyneside',
    subRegions: [
      { areaId: 10, name: 'Berwick & Holy Island', locationCount: 12, exampleLocations: ['Berwick Pier', 'Lindisfarne Castle'] },
      { areaId: 11, name: 'Bamburgh to Howick', locationCount: 13, exampleLocations: ['Bamburgh Castle', 'Dunstanburgh'] },
      { areaId: 12, name: 'Alnwick & the Coquet Coast', locationCount: 7, exampleLocations: ['Alnmouth', 'Warkworth Castle'] },
      { areaId: 13, name: 'Newcastle & Durham', locationCount: 12, exampleLocations: ['Durham Cathedral', 'Tyne Bridges'] },
    ],
  },
  {
    regionId: 2,
    regionName: 'The Lake District',
    subRegions: [
      { areaId: 20, name: 'Keswick & Borrowdale', locationCount: 10, exampleLocations: ['Castlerigg', "Friar's Crag"] },
      { areaId: 21, name: 'The Langdales', locationCount: 9, exampleLocations: ['Blea Tarn', 'Elterwater'] },
    ],
  },
];

const NAMES = new Map(OPTIONS.flatMap((r) => r.subRegions.map((s) => [s.areaId, { name: s.name, regionName: r.regionName }])));

/** Picks for the given area ids, as the GET reports them. */
export function picksOf(...ids) {
  return ids.map((areaId) => ({ areaId, name: NAMES.get(areaId).name, regionName: NAMES.get(areaId).regionName }));
}

/**
 * A GET body, with enforcement on and a limit of 3. Override any field; `picks` takes area ids.
 *
 * @param {object} [overrides]
 * @param {number[]} [overrides.picks]
 */
export function makeState({ picks = [], ...rest } = {}) {
  return {
    enforced: true,
    limit: 3,
    picks: picksOf(...picks),
    options: OPTIONS,
    suggested: [],
    status: 'FREE_UNTIL',
    ...rest,
  };
}

/** A Lite reader in their first week, holding picks 12 and 13. */
export const FREE_WEEK = makeState({ picks: [12, 13], status: 'FREE_UNTIL', freeUntil: '2026-11-02' });

/** After the first week with no lock running: a swap is allowed and would start one ending 25 November. */
export const OPEN = makeState({ picks: [12, 13], status: 'OPEN', nextChangeOnIfSavedToday: '2026-11-25' });

/** Locked until 25 November, holding the limit. */
export const LOCKED_FULL = makeState({ picks: [12, 13, 20], status: 'LOCKED', nextChangeOn: '2026-11-25' });

/** Locked until 25 November with one place under the limit. */
export const LOCKED_ROOM = makeState({ picks: [12, 13], status: 'LOCKED', nextChangeOn: '2026-11-25' });

/** The limit was lowered to 2 under a reader holding 3. */
export const OVER_LIMIT = makeState({
  picks: [12, 13, 20], limit: 2, status: 'OPEN', overLimit: true, nextChangeOnIfSavedToday: '2026-11-25',
});

/** Nothing chosen yet, with suggestions and the grace end a save today would set. */
export const FIRST_CHOICE = makeState({
  picks: [], status: 'FIRST_CHOICE', suggested: [12, 13, 20], freeUntilIfChosenToday: '2026-11-02',
});

/** A Pro reader: nothing to choose, no options. */
export const PRO = {
  enforced: true, limit: 3, picks: [], options: [], suggested: [], status: 'NOT_APPLICABLE',
};

/** The shipped state: enforcement off, whoever is asking. */
export const FLAG_OFF = { ...FIRST_CHOICE, enforced: false };
