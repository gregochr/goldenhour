import { describe, it, expect } from 'vitest';
import {
  alignedCount, buildWindowTideIndex, nextAlignedRow, siblingEventTime, stripModel, tideViewSignature,
} from '../utils/mapTideFit.js';
import { EVENT_KIND } from '../utils/mapEvents.js';

/**
 * Edge behaviour of the Map tab's tide-fit derivations: the viewport signature's exact key, the
 * tide index's tolerance of malformed days and summaries, the forward scan's null members, and
 * the strip model's next-fit search (earliest of several spots, defaults, the evIndex guard).
 * Companion to mapTideFit.test.js; each case pins an answer the happy-path tests never ask for.
 */

const solar = (date, eventType, tide = { locationName: 'Rep' }) => (
  { kind: EVENT_KIND.SOLAR, date, eventType, tide }
);

const bounds = () => {
  const make = (s, w, n, e) => ({
    pad(r) {
      const dLat = (n - s) * r;
      const dLng = (e - w) * r;
      return make(s - dLat, w - dLng, n + dLat, e + dLng);
    },
    contains([lat, lng]) {
      return lat >= s && lat <= n && lng >= w && lng <= e;
    },
  });
  return make(50, -2, 51, -1);
};

const coastal = (name, o = {}) => ({
  name, lat: 50.5, lng: -1.5, coastal: true, tideTier: 'miss', tideTypes: ['HIGH'], ...o,
});

/** Index where each name is aligned HIGH on the given SUNSET dates. */
const indexOf = (byName) => {
  const m = new Map();
  Object.entries(byName).forEach(([name, dates]) => {
    dates.forEach((d) => m.set(`${name}|${d}|SUNSET`, { aligned: true, state: 'HIGH' }));
  });
  return { byId: new Map(), byName: m };
};

describe('tideViewSignature', () => {
  it('reads 1 when the gate pool holds a coastal place in view and 0 when it does not', () => {
    const inView = [coastal('A', { id: 1 })];
    const inland = [coastal('B', { id: 2, coastal: false })];
    expect(tideViewSignature(bounds(), inView, [])).toBe('1|');
    expect(tideViewSignature(bounds(), inland, [])).toBe('0|');
  });

  it('joins the in-view strip places by id, falling back to name for a spot with no id', () => {
    const strip = [coastal('Alpha', { id: 7 }), coastal('Beta'), coastal('Far', { id: 9, lat: 60 })];
    expect(tideViewSignature(bounds(), [], strip)).toBe('0|7\u0000Beta');
  });

  it('is 0| with nothing for a null bounds report', () => {
    expect(tideViewSignature(null, [coastal('A')], [coastal('A')])).toBe('0|');
  });
});

describe('buildWindowTideIndex tolerance', () => {
  it('skips null, tideless and targetless summaries without throwing, keeping the valid one', () => {
    const tide = { locationName: 'Rep' };
    const days = [{
      date: '2026-09-10',
      eventSummaries: [
        null,
        { targetType: 'SUNRISE' },
        { targetType: 'SUNRISE', window: {} },
        { window: { tide } },
        { targetType: 'SUNSET', window: { tide, eventTime: '2026-09-10T19:00:00' } },
      ],
    }];
    const index = buildWindowTideIndex(days, (iso) => `fmt(${iso})`);
    expect([...index.keys()]).toEqual(['2026-09-10:SUNSET']);
    expect(index.get('2026-09-10:SUNSET')).toEqual({
      tide, eventTime: '2026-09-10T19:00:00', time: 'fmt(2026-09-10T19:00:00)',
    });
  });

  it('a dated day with no eventSummaries contributes nothing', () => {
    expect(buildWindowTideIndex([{ date: '2026-09-10' }, null, { eventSummaries: [] }]).size).toBe(0);
  });

  it('without a formatter the time is null even when an event instant is served', () => {
    const tide = {};
    const index = buildWindowTideIndex([{
      date: '2026-09-10',
      eventSummaries: [{ targetType: 'SUNSET', window: { tide, eventTime: '2026-09-10T19:00:00' } }],
    }]);
    expect(index.get('2026-09-10:SUNSET')).toEqual({
      tide, eventTime: '2026-09-10T19:00:00', time: null,
    });
  });
});

describe('nextAlignedRow null members', () => {
  it('skips a null row inside the list and still returns the later aligned one', () => {
    const rows = [solar('2026-09-10', 'SUNSET'), null, solar('2026-09-11', 'SUNSET')];
    const idx = indexOf({ A: ['2026-09-11'] });
    expect(nextAlignedRow(rows, idx, { id: null, name: 'A' }, 0)).toBe(rows[2]);
  });

  it('a null or undefined location key finds nothing and does not throw', () => {
    const rows = [solar('2026-09-10', 'SUNSET'), solar('2026-09-11', 'SUNSET')];
    const idx = indexOf({ A: ['2026-09-11'] });
    expect(nextAlignedRow(rows, idx, null, 0)).toBe(-1);
    expect(nextAlignedRow(rows, idx, undefined, 0)).toBe(-1);
  });
});

describe('stripModel next-fit search', () => {
  const ROWS = () => [
    solar('2026-09-10', 'SUNSET'),
    solar('2026-09-11', 'SUNSET'),
    solar('2026-09-12', 'SUNSET'),
    solar('2026-09-13', 'SUNSET'),
  ];

  it('picks the earliest fit across spots, whatever order they are listed in, ignoring a spot with none', () => {
    const rows = ROWS();
    const spots = [
      coastal('Late'), coastal('Never'), coastal('Early'), coastal('Middle'),
    ];
    const idx = indexOf({
      Late: ['2026-09-13'], Early: ['2026-09-11'], Middle: ['2026-09-12'],
    });
    const m = stripModel({ row: rows[0], spots, bounds: bounds(), evRows: rows, evIndex: 0, idx });
    expect(m.nextFitRow).toBe(rows[1]);
    expect(m.nextFitAny).toBe(rows[1]);
    expect(m.dominantWant).toBe('HIGH');
    expect(m.dominantWantCount).toBe(4);
  });

  it('a spot with no fit listed first never shadows a later spot that has one', () => {
    const rows = ROWS();
    const spots = [coastal('Never'), coastal('Early')];
    const idx = indexOf({ Early: ['2026-09-12'] });
    const m = stripModel({ row: rows[0], spots, bounds: bounds(), evRows: rows, evIndex: 0, idx });
    expect(m.nextFitRow).toBe(rows[2]);
  });

  it('finds nothing when no wanting spot fits any later row', () => {
    const rows = ROWS();
    const m = stripModel({
      row: rows[0], spots: [coastal('Never')], bounds: bounds(), evRows: rows, evIndex: 0, idx: indexOf({}),
    });
    expect(m.nextFitRow).toBe(-1);
    expect(m.nextFitAny).toBe(-1);
  });

  it('looks the spot up by its id first when it has one', () => {
    const rows = ROWS();
    const idx = { byId: new Map([['7|2026-09-11|SUNSET', { aligned: true, state: 'HIGH' }]]), byName: new Map() };
    const m = stripModel({
      row: rows[0], spots: [coastal('Alpha', { id: 7 })], bounds: bounds(), evRows: rows, evIndex: 0, idx,
    });
    expect(m.nextFitRow).toBe(rows[1]);
  });

  it('with evIndex omitted there is no scan position, so nothing is found even where rows fit', () => {
    const rows = ROWS();
    const idx = indexOf({ A: ['2026-09-10', '2026-09-12'] });
    const m = stripModel({ row: rows[0], spots: [coastal('A')], bounds: bounds(), evRows: rows, idx });
    expect(m.visible).toBe(true);
    expect(m.nextFitRow).toBe(-1);
    expect(m.nextFitAny).toBe(-1);
  });

  it('an explicit negative evIndex is the same: no scan, even from the first row', () => {
    const rows = ROWS();
    const idx = indexOf({ A: ['2026-09-10'] });
    const m = stripModel({
      row: rows[0], spots: [coastal('A')], bounds: bounds(), evRows: rows, evIndex: -1, idx,
    });
    expect(m.nextFitRow).toBe(-1);
    expect(m.nextFitAny).toBe(-1);
  });

  it('omitted evRows or a null evRows list finds nothing', () => {
    const row = solar('2026-09-10', 'SUNSET');
    const idx = indexOf({ A: ['2026-09-11'] });
    const base = { row, spots: [coastal('A')], bounds: bounds(), evIndex: 0, idx };
    expect(stripModel(base).nextFitRow).toBe(-1);
    expect(stripModel({ ...base, evRows: null }).nextFitAny).toBe(-1);
  });

  it('a dimmed spot with no tideTypes is not counted as wanting; others still are', () => {
    const rows = ROWS();
    const spots = [
      coastal('NoWants', { tideTypes: undefined }),
      coastal('NullWants', { tideTypes: null }),
      coastal('Wants'),
    ];
    const idx = indexOf({ Wants: ['2026-09-12'] });
    const m = stripModel({ row: rows[0], spots, bounds: bounds(), evRows: rows, evIndex: 0, idx });
    expect(m.dominantWant).toBe('HIGH');
    expect(m.dominantWantCount).toBe(1);
    expect(m.nextFitRow).toBe(rows[2]);
  });

  it('with only want-less dimmed spots there is no dominant want and no next fit', () => {
    const rows = ROWS();
    const m = stripModel({
      row: rows[0], spots: [coastal('NoWants', { tideTypes: undefined })], bounds: bounds(),
      evRows: rows, evIndex: 0, idx: indexOf({ NoWants: ['2026-09-11'] }),
    });
    expect(m.dominantWant).toBeNull();
    expect(m.dominantWantCount).toBe(0);
    expect(m.nextFitRow).toBe(-1);
  });

  it('no row at all is the invisible default, with no representative', () => {
    const m = stripModel({ row: null, spots: [coastal('A')], bounds: bounds() });
    expect(m.visible).toBe(false);
    expect(m.representative).toBeNull();
  });

  it('a visible row whose tide has no locationName has a null representative', () => {
    const m = stripModel({ row: solar('2026-09-10', 'SUNSET', {}), spots: [coastal('A')], bounds: bounds() });
    expect(m.visible).toBe(true);
    expect(m.representative).toBeNull();
    expect(m.dominantWantCount).toBe(1);
  });

  it('called with no spots or bounds it is invisible and does not throw', () => {
    expect(stripModel({ row: solar('2026-09-10', 'SUNSET') }).visible).toBe(false);
  });
});

describe('alignedCount missing members', () => {
  it('a model with no namedCoastal reads null, not a throw', () => {
    expect(alignedCount({ dimmed: [] })).toBeNull();
  });

  it('a model with no dimmed list counts every named spot as aligned', () => {
    const model = { namedCoastal: [{}, {}] };
    expect(alignedCount(model)).toEqual({ kind: 'all', text: 'all 2 aligned', aligned: 2, total: 2 });
  });
});

describe('siblingEventTime edges', () => {
  it('a null or undefined date answers null even when a row lacks a date too', () => {
    const rows = [{ kind: EVENT_KIND.SOLAR, date: null, eventType: 'SUNRISE', time: '05:00' }];
    expect(siblingEventTime(rows, null, 'SUNRISE')).toBeNull();
    expect(siblingEventTime([{ kind: EVENT_KIND.SOLAR, eventType: 'SUNRISE', time: '05:00' }], undefined, 'SUNRISE'))
      .toBeNull();
  });

  it('a non-array row list answers null', () => {
    expect(siblingEventTime(null, '2026-09-10', 'SUNRISE')).toBeNull();
  });

  it('skips a null row ahead of the match', () => {
    const rows = [null, { kind: EVENT_KIND.SOLAR, date: '2026-09-10', eventType: 'SUNRISE', time: '05:44' }];
    expect(siblingEventTime(rows, '2026-09-10', 'SUNRISE')).toBe('05:44');
  });
});
