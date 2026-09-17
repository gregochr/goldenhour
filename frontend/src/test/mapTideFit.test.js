/**
 * Tests for `utils/mapTideFit.js` — the Map tab's tide-fit derivations
 * (`docs/engineering/tide-window-plan.md` T3, §1 #12, §5 #7/#8).
 *
 * Covers: the tier read (`tierOf`), the served-facts scan for the next window a location fits
 * (`nextAlignedRow`), and the strip's per-render model (`stripModel`) — visibility, the
 * coastal-and-in-view narrowing via `bounds.pad(0.12)`, the dimmed/matched split, the dominant-want
 * tally's tie-break in both directions, and the earliest-next-fit scan across every currently
 * dimmed spot wanting that want.
 */
import { describe, it, expect } from 'vitest';
import {
  tierOf, nextAlignedRow, stripModel, siblingEventTime,
} from '../utils/mapTideFit.js';
import { EVENT_KIND } from '../utils/mapEvents.js';

const DATE_1 = '2026-09-10';
const DATE_2 = '2026-09-11';
const DATE_3 = '2026-09-12';

/** One EV row, in the shape `buildMapEvents` returns. */
function solarRow(date, eventType, tide = null) {
  return { kind: EVENT_KIND.SOLAR, date, eventType, tide };
}

function nightRow(date) {
  return { kind: EVENT_KIND.ASTRO, date, eventType: 'ASTRO', tide: null };
}

/** A `buildTideAlignmentIndex`-shaped index built from a flat list of `{id, name, date, eventType, aligned, state?}`. */
function tideIndex(entries) {
  const byId = new Map();
  const byName = new Map();
  for (const e of entries) {
    const tail = `${e.date}|${e.eventType}`;
    const value = { aligned: e.aligned, state: e.state ?? null };
    if (e.id != null) byId.set(`${e.id}|${tail}`, value);
    if (e.name != null) byName.set(`${e.name}|${tail}`, value);
  }
  return { byId, byName };
}

/** A map-tab label spot, in the shape `MapView.spotOf` builds since T3. */
function spot(name, { lat, lng, coastal = true, tideTier = null, tideTypes = [] } = {}) {
  return { name, lat, lng, coastal, tideTier, tideTypes };
}

/**
 * A rectangular Leaflet-`LatLngBounds`-shaped stand-in: `pad(ratio)` grows each edge by `ratio`
 * times that dimension's span, `contains([lat, lng])` is a plain box test — enough to prove
 * `stripModel`'s own `bounds.pad(0.12)` call without pulling in Leaflet.
 */
function rectBounds(south, west, north, east) {
  return {
    pad(ratio) {
      const latPad = (north - south) * ratio;
      const lngPad = (east - west) * ratio;
      return rectBounds(south - latPad, west - lngPad, north + latPad, east + lngPad);
    },
    contains([lat, lng]) {
      return lat >= south && lat <= north && lng >= west && lng <= east;
    },
  };
}

describe('tierOf', () => {
  it('reads match off an aligned fact', () => {
    expect(tierOf({ aligned: true })).toBe('match');
  });

  it('reads miss off a served, non-aligned fact', () => {
    expect(tierOf({ aligned: false })).toBe('miss');
  });

  it('reads null for no fact — not a coastal slot with a served tide state, never a guessed miss', () => {
    expect(tierOf(null)).toBeNull();
    expect(tierOf(undefined)).toBeNull();
  });
});

describe('nextAlignedRow', () => {
  it('finds the first later solar row where the location is served aligned', () => {
    const evRows = [
      solarRow(DATE_1, 'SUNSET'),
      solarRow(DATE_2, 'SUNRISE'),
      solarRow(DATE_2, 'SUNSET'),
      solarRow(DATE_3, 'SUNRISE'),
    ];
    const idx = tideIndex([
      { name: 'Bamburgh', date: DATE_2, eventType: 'SUNRISE', aligned: false },
      { name: 'Bamburgh', date: DATE_2, eventType: 'SUNSET', aligned: true },
      { name: 'Bamburgh', date: DATE_3, eventType: 'SUNRISE', aligned: true },
    ]);
    const found = nextAlignedRow(evRows, idx, { id: null, name: 'Bamburgh' }, 0);
    expect(found).toBe(evRows[2]);
  });

  it('skips a night row in between, and returns -1 when nothing later fits', () => {
    const evRows = [
      solarRow(DATE_1, 'SUNSET'),
      nightRow(DATE_1),
      solarRow(DATE_2, 'SUNRISE'),
    ];
    const idx = tideIndex([
      { name: 'Bamburgh', date: DATE_2, eventType: 'SUNRISE', aligned: false },
    ]);
    expect(nextAlignedRow(evRows, idx, { id: null, name: 'Bamburgh' }, 0)).toBe(-1);
  });

  it('never returns the row at fromIndex itself, even if it is aligned', () => {
    const evRows = [solarRow(DATE_1, 'SUNSET')];
    const idx = tideIndex([{ name: 'Bamburgh', date: DATE_1, eventType: 'SUNSET', aligned: true }]);
    expect(nextAlignedRow(evRows, idx, { id: null, name: 'Bamburgh' }, 0)).toBe(-1);
  });

  it('looks up id-first, falling back to name exactly like lookupForWindow', () => {
    const evRows = [solarRow(DATE_1, 'SUNSET'), solarRow(DATE_2, 'SUNSET')];
    const idx = tideIndex([{ id: 7, date: DATE_2, eventType: 'SUNSET', aligned: true }]);
    expect(nextAlignedRow(evRows, idx, { id: 7, name: 'Anything' }, 0)).toBe(evRows[1]);
    expect(nextAlignedRow(evRows, idx, { id: null, name: 'Anything' }, 0)).toBe(-1);
  });

  it('with a want, requires the alignment to be to THAT water, not to any of the spot\'s wants', () => {
    const evRows = [
      solarRow(DATE_1, 'SUNSET'),
      solarRow(DATE_2, 'SUNRISE'),
      solarRow(DATE_2, 'SUNSET'),
    ];
    // Aligned in both later rows — via LOW first, via HIGH second.
    const idx = tideIndex([
      { name: 'Both', date: DATE_2, eventType: 'SUNRISE', aligned: true, state: 'LOW' },
      { name: 'Both', date: DATE_2, eventType: 'SUNSET', aligned: true, state: 'HIGH' },
    ]);
    expect(nextAlignedRow(evRows, idx, { name: 'Both' }, 0, 'HIGH')).toBe(evRows[2]);
    expect(nextAlignedRow(evRows, idx, { name: 'Both' }, 0, 'LOW')).toBe(evRows[1]);
    // No want: the any-want reading the callout's own jump uses — the first aligned row wins.
    expect(nextAlignedRow(evRows, idx, { name: 'Both' }, 0)).toBe(evRows[1]);
    // A want nothing later satisfies is -1, never a fall-back to the bare flag.
    expect(nextAlignedRow(evRows, idx, { name: 'Both' }, 0, 'MID')).toBe(-1);
  });

  it('returns -1 for a non-array evRows rather than throwing', () => {
    expect(nextAlignedRow(null, tideIndex([]), { id: null, name: 'X' }, 0)).toBe(-1);
  });
});

describe('stripModel — visibility', () => {
  const bounds = rectBounds(0, 0, 1, 1);
  const spots = [spot('Bamburgh', { lat: 0.5, lng: 0.5, tideTier: 'miss', tideTypes: ['HIGH'] })];

  it('is visible on a solar row carrying served tide with a coastal spot in view', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    expect(stripModel({ row, spots, bounds }).visible).toBe(true);
  });

  it('is NOT visible on a night row, even with a served tide fact and a coastal spot in view', () => {
    const row = { ...nightRow(DATE_1), tide: { locationName: 'Bamburgh' } };
    expect(stripModel({ row, spots, bounds }).visible).toBe(false);
  });

  it('is NOT visible on a solar row with no served window tide', () => {
    const row = solarRow(DATE_1, 'SUNSET', null);
    expect(stripModel({ row, spots, bounds }).visible).toBe(false);
  });

  it('is NOT visible with no coastal spot in the padded viewport', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const inland = [spot('Keswick', { lat: 0.5, lng: 0.5, coastal: false })];
    expect(stripModel({ row, spots: inland, bounds }).visible).toBe(false);
  });

  it('returns the all-empty shape when not visible, never a partially-filled one', () => {
    const row = solarRow(DATE_1, 'SUNSET', null);
    expect(stripModel({ row, spots, bounds })).toEqual({
      visible: false,
      representative: null,
      namedCoastal: [],
      dimmed: [],
      matched: [],
      dominantWant: null,
      dominantWantCount: 0,
      nextFitRow: -1,
    });
  });
});

describe('stripModel — bounds.pad(0.12)', () => {
  // A raw viewport of [0,1]x[0,1]: pad(ratio) grows each edge by `ratio` times that span. The two
  // boundary tests below are placed either side of the EXACT 0.12 edge (north = 1 + 0.12 = 1.12)
  // rather than deep inside a wide margin, so together they bound the ratio `stripModel` actually
  // uses to [0.10, 0.14) — tight enough that a mutation of the `0.12` literal to any other value in
  // common use (0.05, 0.1, 0.15, 0.2, 0.5, 1.0) fails at least one of the pair (a loose pair using,
  // say, lat 1.05/2 would still pass for any ratio in (0.05, 1.0), which proves padding happens at
  // all but not that it is specifically 12%).
  const bounds = rectBounds(0, 0, 1, 1);
  const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });

  it('includes a coastal spot just inside the true 0.12 edge (north 1.12) but outside the raw bounds', () => {
    const spots = [spot('JustInside', { lat: 1.10, lng: 0.5, tideTier: 'match' })];
    const model = stripModel({ row, spots, bounds });
    expect(model.visible).toBe(true);
    expect(model.namedCoastal.map((s) => s.name)).toEqual(['JustInside']);
  });

  it('excludes a coastal spot just outside the true 0.12 edge', () => {
    const spots = [spot('JustOutside', { lat: 1.14, lng: 0.5, tideTier: 'match' })];
    expect(stripModel({ row, spots, bounds }).visible).toBe(false);
  });

  it('excludes a coastal spot outside even a generously padded viewport', () => {
    const spots = [spot('WayOutside', { lat: 2, lng: 0.5, tideTier: 'match' })];
    expect(stripModel({ row, spots, bounds }).visible).toBe(false);
  });

  it('excludes an in-view spot that is not coastal', () => {
    const spots = [
      spot('Bamburgh', { lat: 0.5, lng: 0.5, coastal: true, tideTier: 'match' }),
      spot('Keswick', { lat: 0.5, lng: 0.6, coastal: false }),
    ];
    const model = stripModel({ row, spots, bounds });
    expect(model.namedCoastal.map((s) => s.name)).toEqual(['Bamburgh']);
  });
});

describe('stripModel — dimmed/matched split and representative', () => {
  const bounds = rectBounds(0, 0, 1, 1);
  const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });

  it('splits the in-view coastal pool by tier, and states the served window tide\'s own location', () => {
    const spots = [
      spot('MatchOne', { lat: 0.1, lng: 0.1, tideTier: 'match', tideTypes: ['HIGH'] }),
      spot('MissOne', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('MissTwo', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['LOW'] }),
    ];
    const model = stripModel({ row, spots, bounds });
    expect(model.representative).toBe('Bamburgh');
    expect(model.matched.map((s) => s.name)).toEqual(['MatchOne']);
    expect(model.dimmed.map((s) => s.name)).toEqual(['MissOne', 'MissTwo']);
  });

  it('states null when the served window tide carries no representative name', () => {
    const spots = [spot('MissOne', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['LOW'] })];
    const noName = solarRow(DATE_1, 'SUNSET', {});
    expect(stripModel({ row: noName, spots, bounds }).representative).toBeNull();
  });
});

describe('stripModel — dominant want tie-break', () => {
  const bounds = rectBounds(0, 0, 1, 1);
  const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });

  it('breaks a HIGH/LOW tie toward HIGH', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('B', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('C', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('D', { lat: 0.4, lng: 0.4, tideTier: 'miss', tideTypes: ['LOW'] }),
    ];
    expect(stripModel({ row, spots, bounds }).dominantWant).toBe('HIGH');
  });

  it('breaks a LOW/MID tie toward LOW — a second direction, so the order cannot be read off one test', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('B', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('C', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['MID'] }),
      spot('D', { lat: 0.4, lng: 0.4, tideTier: 'miss', tideTypes: ['MID'] }),
    ];
    expect(stripModel({ row, spots, bounds }).dominantWant).toBe('LOW');
  });

  it('takes the outright majority when there is no tie', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('B', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('C', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('D', { lat: 0.4, lng: 0.4, tideTier: 'miss', tideTypes: ['HIGH'] }),
    ];
    expect(stripModel({ row, spots, bounds }).dominantWant).toBe('LOW');
  });

  // `dominantWantCount` (T6, tide-window-plan.md §3 T6 #4) — the footer's "N of them want" clause
  // reads this figure, so it must count only the spots actually wanting the WINNING water, not
  // every dimmed spot.
  it('dominantWantCount is the size of the wanting population, not the whole dimmed pool', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('B', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('C', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['LOW'] }),
      spot('D', { lat: 0.4, lng: 0.4, tideTier: 'miss', tideTypes: ['HIGH'] }),
    ];
    const model = stripModel({ row, spots, bounds });
    expect(model.dominantWant).toBe('LOW');
    expect(model.dominantWantCount).toBe(3);
    expect(model.dimmed.length).toBe(4);
  });

  it('dominantWantCount equals dimmed.length when every miss shares the one want', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('B', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['HIGH'] }),
    ];
    const model = stripModel({ row, spots, bounds });
    expect(model.dominantWantCount).toBe(model.dimmed.length);
  });

  it('dominantWantCount is 0 when nothing is dimmed', () => {
    const spots = [spot('MatchOnly', { lat: 0.1, lng: 0.1, tideTier: 'match', tideTypes: ['HIGH'] })];
    expect(stripModel({ row, spots, bounds }).dominantWantCount).toBe(0);
  });

  it('counts a two-value want once in each of its own buckets', () => {
    const spots = [
      spot('A', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH', 'LOW'] }),
    ];
    // One dimmed spot wanting {HIGH, LOW}: each bucket reads 1, so HIGH wins the tie.
    expect(stripModel({ row, spots, bounds }).dominantWant).toBe('HIGH');
  });

  it('is null when nothing is dimmed', () => {
    const spots = [spot('MatchOnly', { lat: 0.1, lng: 0.1, tideTier: 'match', tideTypes: ['HIGH'] })];
    expect(stripModel({ row, spots, bounds }).dominantWant).toBeNull();
  });
});

describe('stripModel — nextFitRow', () => {
  const bounds = rectBounds(0, 0, 1, 1);

  it('is the earliest later row where ANY currently-dimmed spot wanting the dominant want is aligned', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const evRows = [
      row,
      solarRow(DATE_2, 'SUNRISE'),
      solarRow(DATE_2, 'SUNSET'),
      solarRow(DATE_3, 'SUNRISE'),
    ];
    const spots = [
      spot('First', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('Second', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['HIGH'] }),
    ];
    // Second fits sooner (DATE_2 SUNRISE) than First (DATE_3 SUNRISE) — the earlier one wins.
    // Both alignments are to HIGH water — the dominant want — so both are real candidates.
    const idx = tideIndex([
      { name: 'First', date: DATE_3, eventType: 'SUNRISE', aligned: true, state: 'HIGH' },
      { name: 'Second', date: DATE_2, eventType: 'SUNRISE', aligned: true, state: 'HIGH' },
    ]);
    const model = stripModel({ row, spots, bounds, evRows, evIndex: 0, idx });
    expect(model.nextFitRow).toBe(evRows[1]);
  });

  it('is -1 when no currently-dimmed spot wanting the dominant want ever fits again', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const evRows = [row, solarRow(DATE_2, 'SUNRISE')];
    const spots = [spot('First', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] })];
    const idx = tideIndex([{ name: 'First', date: DATE_2, eventType: 'SUNRISE', aligned: false }]);
    const model = stripModel({ row, spots, bounds, evRows, evIndex: 0, idx });
    expect(model.nextFitRow).toBe(-1);
  });

  it('is -1 with no evRows/evIndex supplied, even with a dominant want in hand', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const spots = [spot('First', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] })];
    expect(stripModel({ row, spots, bounds }).nextFitRow).toBe(-1);
  });

  it('a {HIGH, LOW} spot aligned via LOW does not answer "next high water" — the scan fits the dominant want itself', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const evRows = [row, solarRow(DATE_2, 'SUNRISE'), solarRow(DATE_2, 'SUNSET')];
    // Two HIGH-only wanters make HIGH dominant; the two-value spot enters the scan because it
    // wants HIGH too — but its sooner alignment is to LOW water, and must not surface.
    const spots = [
      spot('HighOne', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('HighTwo', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('Both', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['HIGH', 'LOW'] }),
    ];
    const idx = tideIndex([
      { name: 'Both', date: DATE_2, eventType: 'SUNRISE', aligned: true, state: 'LOW' },
      { name: 'Both', date: DATE_2, eventType: 'SUNSET', aligned: true, state: 'HIGH' },
    ]);
    const model = stripModel({ row, spots, bounds, evRows, evIndex: 0, idx });
    expect(model.dominantWant).toBe('HIGH');
    expect(model.nextFitRow).toBe(evRows[2]);
  });

  it('a spot NOT wanting the dominant want is never consulted for the scan', () => {
    const row = solarRow(DATE_1, 'SUNSET', { locationName: 'Bamburgh' });
    const evRows = [row, solarRow(DATE_2, 'SUNRISE')];
    // Two HIGH wanters make HIGH dominant; the one LOW wanter's own (sooner!) fit must not surface.
    const spots = [
      spot('HighOne', { lat: 0.1, lng: 0.1, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('HighTwo', { lat: 0.2, lng: 0.2, tideTier: 'miss', tideTypes: ['HIGH'] }),
      spot('LowOne', { lat: 0.3, lng: 0.3, tideTier: 'miss', tideTypes: ['LOW'] }),
    ];
    const idx = tideIndex([
      { name: 'LowOne', date: DATE_2, eventType: 'SUNRISE', aligned: true, state: 'LOW' },
    ]);
    const model = stripModel({ row, spots, bounds, evRows, evIndex: 0, idx });
    expect(model.dominantWant).toBe('HIGH');
    expect(model.nextFitRow).toBe(-1);
  });
});

describe('siblingEventTime', () => {
  const evRows = [
    { kind: EVENT_KIND.SOLAR, date: DATE_1, eventType: 'SUNRISE', time: '05:44' },
    { kind: EVENT_KIND.SOLAR, date: DATE_1, eventType: 'SUNSET', time: '20:27' },
    // A NON-empty time, deliberately — an empty one would make the SOLAR-kind guard below
    // untestable, since `sibling?.time || null` would coerce an empty string to null on its own
    // and the test would pass whether or not the `kind === EVENT_KIND.SOLAR` filter still ran.
    { kind: EVENT_KIND.ASTRO, date: DATE_1, eventType: 'ASTRO', time: '23:58' },
    { kind: EVENT_KIND.SOLAR, date: DATE_2, eventType: 'SUNRISE', time: '' },
  ];

  it('reads the served time off the sibling solar row sharing the date', () => {
    expect(siblingEventTime(evRows, DATE_1, 'SUNRISE')).toBe('05:44');
    expect(siblingEventTime(evRows, DATE_1, 'SUNSET')).toBe('20:27');
  });

  it('returns null for a D-13 filler row whose time is the empty string', () => {
    expect(siblingEventTime(evRows, DATE_2, 'SUNRISE')).toBeNull();
  });

  it('returns null when no row exists for the date at all', () => {
    expect(siblingEventTime(evRows, DATE_3, 'SUNRISE')).toBeNull();
  });

  it('never matches a night row, even one sharing the date', () => {
    expect(siblingEventTime(evRows, DATE_1, 'ASTRO')).toBeNull();
  });

  it('returns null for a non-array evRows or a null date, rather than throwing', () => {
    expect(siblingEventTime(null, DATE_1, 'SUNRISE')).toBeNull();
    expect(siblingEventTime(evRows, null, 'SUNRISE')).toBeNull();
  });
});
