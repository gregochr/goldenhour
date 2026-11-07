import { describe, it, expect } from 'vitest';
import {
  buildEclipseIndex, buildLocationSheet, buildScoreIndex, buildSlotIndex, buildTideAlignmentIndex,
  lookupForWindow, slotsOf, windowsOf,
} from '../utils/locationSheet.js';

/**
 * Edge behaviour of the location sheet's joins: malformed payload members (null days, summaries,
 * slots, rows, badges), first-wins collisions in the tide join, the handoff and lead-line rules, and
 * the defaults of `buildLocationSheet`. Companion to locationSheet.test.js; each case pins an answer
 * the join gives to input the happy-path tests never send.
 */

const TAIL = '2026-08-19|SUNSET';

const day = (summary, date = '2026-08-19') => ({ date, eventSummaries: [summary] });
const sunset = (o = {}) => ({ targetType: 'SUNSET', ...o });
const card = (o = {}) => ({
  key: '2026-08-19:SUNSET', date: '2026-08-19', targetType: 'SUNSET', dow: 'Wed', sunrise: false,
  label: 'Tonight Sunset', time: '20:00', verdictLabel: 'Worth it', away: false, ...o,
});
const scoreRow = (o = {}) => ({
  locationId: 1, locationName: 'Bamburgh', date: '2026-08-19', targetType: 'SUNSET', rating: 3, ...o,
});
const SPOT = { id: 1, name: 'Bamburgh', regionName: 'North East' };

describe('slotsOf', () => {
  it('answers an empty list for a missing summary, an empty one and one with empty lists', () => {
    expect(slotsOf(null)).toEqual([]);
    expect(slotsOf(undefined)).toEqual([]);
    expect(slotsOf({})).toEqual([]);
    expect(slotsOf({ regions: [], unregioned: [] })).toEqual([]);
    expect(slotsOf({ regions: [] })).toEqual([]);
  });

  it('gives a region with no slot list, or a null region, no slots', () => {
    expect(slotsOf({ regions: [null, {}] })).toEqual([]);
  });

  it('lists regioned slots with their region first, then unregioned ones with a null region', () => {
    const region = { confidence: 'high', slots: [{ locationId: 1 }] };
    expect(slotsOf({ regions: [region], unregioned: [{ locationId: 2 }] })).toEqual([
      { slot: { locationId: 1 }, region },
      { slot: { locationId: 2 }, region: null },
    ]);
  });
});

describe('windowsOf', () => {
  it('skips null days, dateless days and null or targetless summaries', () => {
    const good = sunset();
    const windows = windowsOf([
      null,
      { eventSummaries: [sunset()] },
      { date: '2026-08-19' },
      { date: '2026-08-19', eventSummaries: [null, { regions: [] }, good] },
    ]);
    expect(windows).toEqual([{ date: '2026-08-19', summary: good, tail: TAIL }]);
  });

  it('answers an empty list for a payload that is not an array', () => {
    expect(windowsOf(null)).toEqual([]);
    expect(windowsOf({ date: '2026-08-19' })).toEqual([]);
  });
});

describe('buildSlotIndex', () => {
  it('indexes only a slot that has a solar event time, and tolerates a null slot', () => {
    const idx = buildSlotIndex([day(sunset({
      regions: [{ confidence: 'high', slots: [null, { locationId: 1, locationName: 'A' }] }],
      unregioned: [null, { locationId: 2, locationName: 'B', solarEventTime: '2026-08-19T19:41:00' }],
    }))]);
    expect([...idx.byId.keys()]).toEqual([`2|${TAIL}`]);
    expect(idx.byId.get(`2|${TAIL}`)).toEqual({
      eventTime: '2026-08-19T19:41:00', confidence: null, displayVerdict: null,
    });
  });
});

describe('buildEclipseIndex', () => {
  it('skips a null slot and a slot with no eclipse, keeping the served object verbatim', () => {
    const eclipse = { phase: 'TOTAL' };
    const idx = buildEclipseIndex([day(sunset({
      unregioned: [null, { locationId: 1, locationName: 'A' }, { locationId: 2, locationName: 'B', eclipse }],
    }))]);
    expect([...idx.byId.keys()]).toEqual([`2|${TAIL}`]);
    expect(idx.byId.get(`2|${TAIL}`)).toBe(eclipse);
  });
});

describe('buildTideAlignmentIndex', () => {
  const fact = (o = {}) => ({ locationId: 1, locationName: 'A', tideState: 'HIGH', ...o });
  const withFacts = (facts, slots = {}) => [day(sunset({ window: { tideFacts: facts }, ...slots }))];

  it('is empty for a window with no window object, no facts or an empty fact list', () => {
    expect(buildTideAlignmentIndex([day(sunset())]).byId.size).toBe(0);
    expect(buildTideAlignmentIndex([day(sunset({ window: {} }))]).byId.size).toBe(0);
    expect(buildTideAlignmentIndex(withFacts([])).byId.size).toBe(0);
  });

  it('skips a null fact and a fact with no tide state', () => {
    const idx = buildTideAlignmentIndex(withFacts([null, fact({ locationId: 2, tideState: null }), fact()]));
    expect([...idx.byId.keys()]).toEqual([`1|${TAIL}`]);
  });

  it('joins the sky rating from the first slot for an id, ignoring null slots and later duplicates', () => {
    const idx = buildTideAlignmentIndex(withFacts([fact()], {
      unregioned: [
        null,
        { locationId: 1, locationName: 'First', skyRating: 5 },
        { locationId: 1, locationName: 'Second', skyRating: 2 },
      ],
    }));
    expect(idx.byId.get(`1|${TAIL}`).skyRating).toBe(5);
  });

  it('joins by name when the fact has no id, the first slot with that name winning', () => {
    const idx = buildTideAlignmentIndex(withFacts([fact({ locationId: null, locationName: 'Shared' })], {
      unregioned: [
        { locationId: null, locationName: null, skyRating: 1 },
        { locationId: 7, locationName: 'Shared', skyRating: 4 },
        { locationId: 8, locationName: 'Shared', skyRating: 2 },
      ],
    }));
    expect(idx.byName.get(`Shared|${TAIL}`).skyRating).toBe(4);
  });

  it('reads no sky rating when the only slot with that id has none', () => {
    const idx = buildTideAlignmentIndex(withFacts([fact()], {
      unregioned: [{ locationId: 1, locationName: 'A' }],
    }));
    expect(idx.byId.get(`1|${TAIL}`).skyRating).toBeNull();
  });
});

describe('buildScoreIndex', () => {
  it('skips a null row, a dateless row and a targetless row', () => {
    const idx = buildScoreIndex([
      null,
      scoreRow({ locationId: 2, date: null }),
      scoreRow({ locationId: 3, targetType: undefined }),
      scoreRow({ locationId: 4 }),
    ]);
    expect([...idx.byId.keys()]).toEqual([`4|${TAIL}`]);
  });

  it('trims a summary, and reads a blank one as none', () => {
    const idx = buildScoreIndex([
      scoreRow({ locationId: 1, summary: '  Clear to the west.  ' }),
      scoreRow({ locationId: 2, summary: '   ' }),
    ]);
    expect(idx.byId.get(`1|${TAIL}`).summary).toBe('Clear to the west.');
    expect(idx.byId.get(`2|${TAIL}`).summary).toBeNull();
  });
});

describe('lookupForWindow', () => {
  it('never treats a null id as a key: it falls through to the name', () => {
    const byId = new Map([[`null|${TAIL}`, { who: 'id' }]]);
    const byName = new Map([[`Bamburgh|${TAIL}`, { who: 'name' }]]);
    expect(lookupForWindow({ byId, byName }, null, 'Bamburgh', '2026-08-19', 'SUNSET')).toEqual({ who: 'name' });
    expect(lookupForWindow({ byId, byName }, null, '', '2026-08-19', 'SUNSET')).toBeNull();
  });
});

describe('buildLocationSheet defaults and null spots', () => {
  it('makes no claim about what is scored unless told the ratings arrived', () => {
    const sheet = buildLocationSheet(SPOT, [card()], {});
    expect(sheet.lead).toBeNull();
    expect(sheet.rows[0].scoresKnown).toBe(false);
  });

  it('gives a sheet with no windows no lead line even when the ratings are known', () => {
    expect(buildLocationSheet(SPOT, [], { scoresKnown: true }).lead).toBeNull();
    expect(buildLocationSheet(SPOT, [null], { scoresKnown: true }).lead).toBeNull();
  });

  it('counts a window at four stars as strong and a three-star one as not', () => {
    const windows = [card(), card({ key: '2026-08-20:SUNRISE', date: '2026-08-20', targetType: 'SUNRISE' })];
    const scoreIndex = buildScoreIndex([
      scoreRow({ rating: 4 }), scoreRow({ date: '2026-08-20', targetType: 'SUNRISE', rating: 3 }),
    ]);
    expect(buildLocationSheet(SPOT, windows, { scoreIndex, scoresKnown: true }).lead)
      .toBe('The next 2 days here · 1 window at 4★+');
  });

  it('reads no drive from a reach entry keyed on null when the spot has no id', () => {
    const reachById = new Map([[null, { driveMinutes: 30, driveTo: 'Nowhere' }]]);
    const sheet = buildLocationSheet({ name: 'Bamburgh' }, [card()], { reachById });
    expect(sheet.driveMinutes).toBeNull();
    expect(sheet.driveTo).toBeNull();
  });

  it('builds a sheet for a null spot, with no region and a borrowed gloss left unread', () => {
    const sheet = buildLocationSheet(null, [card()], {
      regionGlossIndex: new Map([['2026-08-19|SUNSET|North East', { headline: 'h', detail: 'd' }]]),
    });
    expect(sheet.name).toBe('');
    expect(sheet.regionName).toBeNull();
    expect(sheet.rows[0].summary).toBeNull();
    expect(sheet.rows[0].summaryRegion).toBeNull();
  });

  it('marks nothing as outside the scope for a null spot', () => {
    const sheet = buildLocationSheet(null, [card()], { scopeRegionNames: ['Lake District'] });
    expect(sheet.outsideScope).toBe(false);
  });

  it('hands off the earliest of two equally rated windows', () => {
    const windows = [
      card({ key: 'a', date: '2026-08-19' }),
      card({ key: 'b', date: '2026-08-20' }),
      card({ key: 'c', date: '2026-08-21' }),
    ];
    const scoreIndex = buildScoreIndex([
      scoreRow({ date: '2026-08-19', rating: 4 }),
      scoreRow({ date: '2026-08-20', rating: 4 }),
      scoreRow({ date: '2026-08-21', rating: 2 }),
    ]);
    const sheet = buildLocationSheet(SPOT, windows, { scoreIndex, scoresKnown: true });
    expect(sheet.handoffKey).toBe('a');
    expect(sheet.bestKey).toBe('a');
  });
});

describe('buildLocationSheet meta row', () => {
  const metaOf = (location, windows = []) => buildLocationSheet(SPOT, windows, { location }).meta;

  it('names no subjects for a location whose type has no display word', () => {
    expect(metaOf({ locationType: [] })).toEqual([]);
    expect(metaOf({})).toEqual([]);
  });

  it('names the subjects, then the dark sky, then the coast', () => {
    expect(metaOf({ locationType: ['SEASCAPE'], bortleClass: 3, tideType: ['HIGH'] })).toEqual([
      { key: 'subjects', text: 'Seascape' },
      { key: 'darksky', text: 'Dark sky 3 · dark' },
      { key: 'coastal', text: 'Coastal · the tide matters here' },
    ]);
  });

  it('reads topics from the windows, trimmed and de-duplicated, skipping null windows and bad badges', () => {
    const windows = [
      null,
      card({ badges: [null, { label: 7 }, { label: '  Mist  ' }, { label: '' }, { label: 'Mist' }] }),
      card({ badges: [{ label: 'Eclipse' }, { label: 'Mist' }] }),
      card({}),
    ];
    expect(metaOf({}, windows)).toEqual([
      { key: 'topic:Mist', text: 'Mist' },
      { key: 'topic:Eclipse', text: 'Eclipse' },
    ]);
  });
});
