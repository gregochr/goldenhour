import { describe, it, expect } from 'vitest';
import {
  buildHeatSpots, buildHeatWindowStates, forecastScoreRows, stoodPointsFor,
} from '../utils/heatSpots.js';
import {
  STAND_DOWN_CAUSE_WORD, standDownCauseWord, stoodPlaceAriaLabel, stoodPlaceCauseWord,
} from '../utils/standDown.js';
import {
  standDownFooterCount, standDownRegions, weatherStoodOf, windowHasEvidence,
} from '../utils/mapStandDown.js';
import { buildRegionVerdictIndex, keyOf } from '../utils/mapVerdict.js';

/**
 * The Map's stand-down ground (big-seas-plan.md F3): the reason a stood place keeps, the list a
 * window hands the hatch, the region label's three-part qualification, the cause word, and the
 * footer's count. Pure filters over served rows; nothing here rates anything.
 */

const DATE = '2026-10-24';
const EVENTS = [{ date: DATE, targetType: 'SUNSET' }];

const loc = (id, name, regionName, lat = 55 + id / 10) => ({
  id, name, lat, lon: -1.5, regionName, locationType: ['LANDSCAPE'],
});

const rowOf = (id, name, fields) => ({
  locationId: id, locationName: name, date: DATE, targetType: 'SUNSET', rating: null, ...fields,
});

describe('buildHeatSpots — a stood place keeps its reason', () => {
  const locations = [loc(1, 'A', 'North'), loc(2, 'B', 'North'), loc(3, 'C', 'North'),
    loc(4, 'D', 'North'), loc(5, 'E', 'North'), loc(6, 'F', 'North')];

  it.each([
    ['a string', 'HIGH_CLOUD', 'HIGH_CLOUD'],
    ['a padded string', '  PRECIPITATION  ', 'PRECIPITATION'],
    ['an object\'s name', { name: 'LOW_VISIBILITY' }, 'LOW_VISIBILITY'],
    ['an object\'s reason field', { reason: 'HIGH_CLOUD' }, 'HIGH_CLOUD'],
    ['an object with neither', { other: 1 }, 'GENERIC'],
  ])('normalises %s the way hasTriageReason reads it', (_label, served, expected) => {
    const [spot] = buildHeatSpots(locations.slice(0, 1), [rowOf(1, 'A', { triageReason: served })], EVENTS);
    expect(spot.states).toEqual(['stood']);
    expect(spot.reasons).toEqual([expected]);
  });

  it('carries no reason for a rated, unrated or blank-reason place', () => {
    const spots = buildHeatSpots(locations.slice(0, 3), [
      rowOf(1, 'A', { rating: 4 }),
      rowOf(3, 'C', { triageReason: '   ' }),
    ], EVENTS);
    expect(spots.map((s) => s.reasons)).toEqual([[null], [null], [null]]);
  });

  it('a rating beats a triage reason: the place is rated and reasonless', () => {
    const [spot] = buildHeatSpots(locations.slice(0, 1), [rowOf(1, 'A', { rating: 3, triageReason: 'HIGH_CLOUD' })], EVENTS);
    expect(spot.states).toEqual(['rated']);
    expect(spot.reasons).toEqual([null]);
  });
});

describe('buildHeatWindowStates — the stood list', () => {
  const locations = [loc(1, 'A', 'North'), loc(2, 'B', 'South'), loc(3, 'C', 'South')];
  const rows = [
    rowOf(1, 'A', { triageReason: 'HIGH_CLOUD' }),
    rowOf(2, 'B', { triageReason: 'TIDE_MISALIGNED' }),
    rowOf(3, 'C', { rating: 4 }),
  ];
  const entry = buildHeatWindowStates(buildHeatSpots(locations, rows, EVENTS), EVENTS).get(`${DATE}:SUNSET`);

  it('lists every stood place with its reason, region NAME and join key — tide included', () => {
    expect(entry.stood).toEqual([
      {
        id: 1, name: 'A', lat: 55.1, lng: -1.5, reason: 'HIGH_CLOUD', rid: 'North', key: '#1',
      },
      {
        id: 2, name: 'B', lat: 55.2, lng: -1.5, reason: 'TIDE_MISALIGNED', rid: 'South', key: '#2',
      },
    ]);
  });

  it('stoodPointsFor tolerates a missing spot list', () => {
    expect(stoodPointsFor(null, 0)).toEqual([]);
  });

  it('a PAST window gets its stood list from the forecast rows', () => {
    const past = [{
      id: 1,
      name: 'A',
      forecastsByDate: new Map([[DATE, { sunset: { rating: null, triageReason: 'PRECIPITATION' } }]]),
    }];
    const pastRows = forecastScoreRows(past, EVENTS);
    const pastEntry = buildHeatWindowStates(buildHeatSpots(locations, pastRows, EVENTS), EVENTS)
      .get(`${DATE}:SUNSET`);
    expect(pastEntry.stood.map((p) => [p.key, p.reason])).toEqual([['#1', 'PRECIPITATION']]);
  });
});

describe('standDownCauseWord — one shared reason, else weather', () => {
  it.each([
    [['HIGH_CLOUD'], 'cloud'],
    [['PRECIPITATION', 'PRECIPITATION'], 'rain'],
    [['LOW_VISIBILITY'], 'visibility'],
    [['GENERIC'], 'weather'],
    [[null], 'weather'],
    [['SOMETHING_NEW'], 'weather'],
    [[], 'weather'],
  ])('%j reads %s', (reasons, word) => {
    expect(standDownCauseWord(reasons)).toBe(word);
  });

  it('any mix of two or more distinct reasons reads weather — no majority vote', () => {
    expect(standDownCauseWord(['PRECIPITATION', 'PRECIPITATION', 'HIGH_CLOUD'])).toBe('weather');
  });

  it('has no word for the tide', () => {
    expect(STAND_DOWN_CAUSE_WORD.TIDE_MISALIGNED).toBeUndefined();
  });
});

describe('weatherStoodOf / windowHasEvidence', () => {
  it('drops TIDE_MISALIGNED only', () => {
    const list = [{ reason: 'HIGH_CLOUD' }, { reason: 'TIDE_MISALIGNED' }, { reason: null }];
    expect(weatherStoodOf(list)).toEqual([{ reason: 'HIGH_CLOUD' }, { reason: null }]);
    expect(weatherStoodOf(undefined)).toEqual([]);
  });

  it('counts a rated or a stood place as evidence, an unrated or na one as none', () => {
    expect(windowHasEvidence(new Map([['#1', 'stood']]))).toBe(true);
    expect(windowHasEvidence(new Map([['#1', 'rated']]))).toBe(true);
    expect(windowHasEvidence(new Map([['#1', 'unrated'], ['#2', 'na']]))).toBe(false);
    expect(windowHasEvidence(null)).toBe(false);
  });
});

describe('standDownRegions — when a region earns its label', () => {
  const stood = (rid, reason, n = 1) => Array.from({ length: n }, (_, i) => ({
    rid, reason, lat: 55 + i / 10, lng: -1.5, key: `#${rid}${i}`,
  }));
  const spotsIn = (rid, ids) => ids.map((id) => ({ id, name: `${rid}${id}`, rid }));
  const index = (regionName, meanRating) => buildRegionVerdictIndex([{
    date: DATE,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{
        regionName, meanRating, displayVerdict: 'MAYBE', slots: [{ canopy: false }],
      }],
    }],
  }]);
  const base = {
    served: true, regionVerdictIndex: null, date: DATE, targetType: 'SUNSET',
  };

  it('qualifies a region with a stood place, no rated place and no served mean', () => {
    const out = standDownRegions({
      ...base, stood: stood('North', 'HIGH_CLOUD', 2), spots: spotsIn('North', [1, 2]), states: new Map(),
    });
    expect(out.map((r) => [r.rid, r.cause, r.points.length])).toEqual([['North', 'cloud', 2]]);
  });

  it('one rated place disqualifies it, even a 1-star one', () => {
    const states = new Map([['#1', 'rated']]);
    const out = standDownRegions({
      ...base, stood: stood('North', 'HIGH_CLOUD'), spots: spotsIn('North', [1]), states,
    });
    expect(out).toEqual([]);
  });

  it('a rated place in ANOTHER region leaves this one qualified', () => {
    const out = standDownRegions({
      ...base,
      stood: stood('North', 'PRECIPITATION'),
      spots: [...spotsIn('North', [1]), ...spotsIn('South', [2])],
      states: new Map([['#2', 'rated']]),
    });
    expect(out.map((r) => r.rid)).toEqual(['North']);
  });

  it('a finite served mean disqualifies a served window', () => {
    const out = standDownRegions({
      ...base,
      stood: stood('North', 'HIGH_CLOUD'),
      spots: [],
      states: new Map(),
      regionVerdictIndex: index('North', 2.5),
    });
    expect(out).toEqual([]);
  });

  it('an entry with no finite mean, or no entry at all, counts as no served mean', () => {
    const nullMean = standDownRegions({
      ...base, stood: stood('North', 'HIGH_CLOUD'), spots: [], states: new Map(), regionVerdictIndex: index('North', null),
    });
    const absent = standDownRegions({
      ...base, stood: stood('North', 'HIGH_CLOUD'), spots: [], states: new Map(), regionVerdictIndex: index('Elsewhere', 4),
    });
    expect(nullMean).toHaveLength(1);
    expect(absent).toHaveLength(1);
  });

  it.each([[NaN], ['2.5'], [null], [undefined], [Infinity]])('a non-finite or non-numeric mean (%j) counts as no mean', (mean) => {
    const out = standDownRegions({
      ...base,
      stood: stood('North', 'HIGH_CLOUD'),
      spots: [],
      states: new Map(),
      regionVerdictIndex: new Map([[keyOf(DATE, 'SUNSET', 'North'), { meanRating: mean }]]),
    });
    expect(out).toHaveLength(1);
  });

  it('a PAST window has no served mean to consult', () => {
    const out = standDownRegions({
      ...base, served: false, stood: stood('North', 'HIGH_CLOUD'), spots: [], states: new Map(), regionVerdictIndex: index('North', 4),
    });
    expect(out).toHaveLength(1);
  });

  it('names the single shared cause, and any mix as weather', () => {
    const shared = standDownRegions({
      ...base, stood: stood('North', 'LOW_VISIBILITY', 3), spots: [], states: new Map(),
    });
    const mixed = standDownRegions({
      ...base,
      stood: [...stood('North', 'PRECIPITATION', 2), ...stood('North', 'HIGH_CLOUD', 1)],
      spots: [],
      states: new Map(),
    });
    expect(shared[0].cause).toBe('visibility');
    expect(mixed[0].cause).toBe('weather');
  });

  it('is empty with nothing stood', () => {
    expect(standDownRegions({ ...base, stood: [], spots: [], states: new Map() })).toEqual([]);
    expect(standDownRegions({ ...base, stood: null, spots: null, states: null })).toEqual([]);
  });
});

describe('standDownFooterCount — the scope pool\'s weather stand-downs', () => {
  const stoodList = [
    { key: '#1', reason: 'HIGH_CLOUD' },
    { key: '#2', reason: 'TIDE_MISALIGNED' },
    { key: '#3', reason: 'PRECIPITATION' },
  ];

  it('counts the stood places inside the pool, tide excluded', () => {
    const pool = [{ id: 1, name: 'A' }, { id: 2, name: 'B' }, { id: 9, name: 'Z' }];
    expect(standDownFooterCount(pool, stoodList)).toBe(1);
  });

  it('is zero with an empty pool or no stood list', () => {
    expect(standDownFooterCount([], stoodList)).toBe(0);
    expect(standDownFooterCount([{ id: 1, name: 'A' }], undefined)).toBe(0);
  });

  it('joins on the name when there is no id', () => {
    expect(standDownFooterCount([{ id: null, name: 'Q' }], [{ key: '@Q', reason: null }])).toBe(1);
  });
});

describe('stoodPlaceCauseWord and stoodPlaceAriaLabel — one stood place', () => {
  it.each([
    ['HIGH_CLOUD', 'cloud'],
    ['PRECIPITATION', 'rain'],
    ['LOW_VISIBILITY', 'visibility'],
  ])('%s reads %s', (reason, word) => {
    expect(stoodPlaceCauseWord(reason)).toBe(word);
  });

  it.each([['GENERIC'], [null], [undefined], ['SOMETHING_NEW'], ['TIDE_MISALIGNED']])(
    '%s names no cause (never "weather", which a Lite tide stand-down would otherwise claim)',
    (reason) => {
      expect(stoodPlaceCauseWord(reason)).toBeNull();
    },
  );

  it('words the accessible name, with "poor visibility" for the visibility cause', () => {
    expect(stoodPlaceAriaLabel('Tynemouth', 'LOW_VISIBILITY')).toBe('Tynemouth, stood down: poor visibility');
    expect(stoodPlaceAriaLabel('Tynemouth', 'HIGH_CLOUD')).toBe('Tynemouth, stood down: cloud');
    expect(stoodPlaceAriaLabel('Tynemouth', 'PRECIPITATION')).toBe('Tynemouth, stood down: rain');
    expect(stoodPlaceAriaLabel('Tynemouth', 'GENERIC')).toBe('Tynemouth, stood down');
    expect(stoodPlaceAriaLabel('Tynemouth', 'SOMETHING_NEW')).toBe('Tynemouth, stood down');
  });
});

describe('stoodPointsFor — the place name rides on the point', () => {
  it('carries the name and id of each stood place', () => {
    const spots = buildHeatSpots(
      [loc(1, 'A', 'North')],
      [rowOf(1, 'A', { triageReason: 'LOW_VISIBILITY' })],
      EVENTS,
    );
    const [point] = stoodPointsFor(spots, 0);
    expect(point).toMatchObject({ id: 1, name: 'A', reason: 'LOW_VISIBILITY', key: '#1' });
  });
});
