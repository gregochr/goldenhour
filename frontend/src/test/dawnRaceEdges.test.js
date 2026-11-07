import { describe, it, expect, beforeEach, vi } from 'vitest';
import { buildRuleGradient } from '../components/shared/MastheadLight.jsx';

// The module is loaded afresh in every test, not once at file import. Its constants (the hour and
// 15-minute widths, the served stop keys, the tick wording) are evaluated when it loads; a load
// that happens before any test starts is "static" to Stryker and its mutants are not run against
// this file. Loading inside the test lifecycle makes these assertions the ones that judge them.
let raceModel;
let eclipseSpotLine;

beforeEach(async () => {
  vi.resetModules();
  ({ raceModel, eclipseSpotLine } = await import('../utils/dawnRace.js'));
});

const MIN_MS = 60 * 1000;

function ms(iso) {
  return new Date(`${iso}Z`).getTime();
}

function dawnSight(overrides = {}) {
  return {
    race: 'DAWN',
    moonAltAtMax: 7,
    moonAzCardinal: 'WSW',
    maximum: '2026-08-28T05:13:00',
    umbraStart: '2026-08-28T03:34:00',
    umbraEnd: '2026-08-28T06:52:00',
    moonset: '2026-08-28T06:16:00',
    moonrise: null,
    setsInShadow: true,
    risesInShadow: false,
    stops: [
      { key: 'NAUTICAL_DAWN', time: '2026-08-28T03:09:00' },
      { key: 'CIVIL_DAWN', time: '2026-08-28T05:24:00' },
      { key: 'SUNRISE', time: '2026-08-28T06:11:00' },
      { key: 'GOLDEN_MORNING_END', time: '2026-08-28T06:41:00' },
    ],
    ...overrides,
  };
}

function duskSight(overrides = {}) {
  return {
    race: 'DUSK',
    moonAltAtMax: 5,
    moonAzCardinal: 'ENE',
    maximum: '2028-12-31T17:42:00',
    umbraStart: '2028-12-31T15:07:00',
    umbraEnd: '2028-12-31T19:52:00',
    moonset: null,
    moonrise: '2028-12-31T15:53:00',
    setsInShadow: false,
    risesInShadow: true,
    stops: [
      { key: 'GOLDEN_EVENING_START', time: '2028-12-31T15:35:00' },
      { key: 'SUNSET', time: '2028-12-31T16:00:00' },
      { key: 'CIVIL_DUSK', time: '2028-12-31T16:38:00' },
      { key: 'NAUTICAL_DUSK', time: '2028-12-31T17:16:00' },
    ],
    ...overrides,
  };
}

describe('raceModel — the track’s exact edges', () => {
  it('floors a dawn track to the hour before its earliest instant and runs 15 minutes past the clipped end', () => {
    const model = raceModel(dawnSight());

    // earliest of umbraStart 03:34 / nautical dawn 03:09 is 03:09, floored to 03:00
    expect(model.trackStart).toBe(ms('2026-08-28T03:00:00'));
    // clipped end is moonset 06:16 (before umbraEnd 06:52); later than sunrise 06:11; plus 15 min
    expect(model.trackEnd).toBe(ms('2026-08-28T06:16:00') + 15 * MIN_MS);
  });

  it('floors on a whole hour to itself', () => {
    const model = raceModel(dawnSight({
      umbraStart: '2026-08-28T04:00:00',
      stops: [
        { key: 'NAUTICAL_DAWN', time: '2026-08-28T04:00:00' },
        { key: 'CIVIL_DAWN', time: '2026-08-28T05:24:00' },
        { key: 'SUNRISE', time: '2026-08-28T06:11:00' },
        { key: 'GOLDEN_MORNING_END', time: '2026-08-28T06:41:00' },
      ],
    }));

    expect(model.trackStart).toBe(ms('2026-08-28T04:00:00'));
  });

  it('ceils a dusk track to the hour after its latest instant and starts 15 minutes before the earlier edge', () => {
    const model = raceModel(duskSight());

    // later of umbraEnd 19:52 / nautical dusk 17:16 is 19:52, ceiled to 20:00
    expect(model.trackEnd).toBe(ms('2028-12-31T20:00:00'));
    // clipped umbra start is moonrise 15:53 (after umbraStart 15:07); sunset 16:00 is later; minus 15 min
    expect(model.trackStart).toBe(ms('2028-12-31T15:53:00') - 15 * MIN_MS);
  });

  it('ceils on a whole hour to itself', () => {
    const model = raceModel(duskSight({ umbraEnd: '2028-12-31T19:00:00' }));

    expect(model.trackEnd).toBe(ms('2028-12-31T19:00:00'));
  });

  it('draws nothing when the track would have no width, and something when it has one minute', () => {
    const stops = (sunset, nauticalDusk) => [
      { key: 'GOLDEN_EVENING_START', time: '2028-12-31T09:00:00' },
      { key: 'SUNSET', time: sunset },
      { key: 'CIVIL_DUSK', time: '2028-12-31T09:30:00' },
      { key: 'NAUTICAL_DUSK', time: nauticalDusk },
    ];
    const flat = duskSight({
      moonrise: null,
      risesInShadow: false,
      umbraStart: '2028-12-31T11:00:00',
      umbraEnd: '2028-12-31T10:00:00',
      stops: stops('2028-12-31T10:15:00', '2028-12-31T09:00:00'),
    });

    // trackStart = min(11:00, 10:15) - 15 min = 10:00 = trackEnd (ceil of 10:00)
    expect(raceModel(flat)).toBeNull();
    // a sunset one minute earlier starts the track at 09:59: one minute of width is enough to draw
    const wide = { ...flat, stops: stops('2028-12-31T10:14:00', '2028-12-31T09:00:00') };
    expect(raceModel(wide).trackEnd - raceModel(wide).trackStart).toBe(MIN_MS);
  });
});

describe('raceModel — position', () => {
  it('maps an instant to its exact fraction of the track, clamped to 0..1', () => {
    const model = raceModel(dawnSight());
    const span = model.trackEnd - model.trackStart;

    expect(model.position(model.trackStart)).toBe(0);
    expect(model.position(model.trackStart + span / 4)).toBeCloseTo(0.25, 10);
    expect(model.position(model.trackStart + span / 2)).toBeCloseTo(0.5, 10);
    expect(model.position(model.trackEnd)).toBe(1);
    expect(model.position(model.trackStart - span)).toBe(0);
    expect(model.position(model.trackEnd + span)).toBe(1);
    expect(model.position(null)).toBeNull();
    expect(model.position(undefined)).toBeNull();
  });
});

describe('raceModel — the served stop list is checked key for key', () => {
  it('refuses a race that is neither DAWN nor DUSK, whatever its stops', () => {
    expect(raceModel(duskSight({ race: 'NIGHT' }))).toBeNull();
    expect(raceModel(dawnSight({ race: 'dawn' }))).toBeNull();
    expect(raceModel(dawnSight({ race: null }))).toBeNull();
  });

  it('refuses stops that are not four', () => {
    const extra = [...dawnSight().stops, { key: 'EXTRA', time: '2026-08-28T07:00:00' }];

    expect(raceModel(dawnSight({ stops: extra }))).toBeNull();
    expect(raceModel(dawnSight({ stops: dawnSight().stops.slice(0, 3) }))).toBeNull();
    expect(raceModel(dawnSight({ stops: null }))).toBeNull();
  });

  it('refuses a stop list whose keys are the other direction’s', () => {
    expect(raceModel(dawnSight({ stops: duskSight().stops }))).toBeNull();
    expect(raceModel(duskSight({ stops: dawnSight().stops }))).toBeNull();
  });

  it('refuses a stop list where only the first key is right, or only the last is wrong', () => {
    const onlyFirst = dawnSight().stops.map((s, i) => (i === 0 ? s : { ...s, key: `BAD_${i}` }));
    const lastWrong = dawnSight().stops.map((s, i) => (i === 3 ? { ...s, key: 'BAD' } : s));
    const firstWrong = duskSight().stops.map((s, i) => (i === 0 ? { ...s, key: 'BAD' } : s));

    expect(raceModel(dawnSight({ stops: onlyFirst }))).toBeNull();
    expect(raceModel(dawnSight({ stops: lastWrong }))).toBeNull();
    expect(raceModel(duskSight({ stops: firstWrong }))).toBeNull();
  });

  it('refuses a stop list with a hole in it instead of throwing', () => {
    const holed = [null, ...dawnSight().stops.slice(1)];

    expect(raceModel(dawnSight({ stops: holed }))).toBeNull();
  });
});

describe('raceModel — ticks and gradient', () => {
  it('words the dawn ticks, leaving the sunrise to the race marker', () => {
    expect(raceModel(dawnSight()).ticks.map((t) => [t.key, t.label])).toEqual([
      ['NAUTICAL_DAWN', 'nautical dawn'],
      ['CIVIL_DAWN', 'civil dawn'],
      ['GOLDEN_MORNING_END', 'golden hour ends'],
    ]);
  });

  it('words the dusk ticks, leaving the sunset to the race marker', () => {
    expect(raceModel(duskSight()).ticks.map((t) => [t.key, t.label])).toEqual([
      ['GOLDEN_EVENING_START', 'golden hour starts'],
      ['CIVIL_DUSK', 'civil dusk'],
      ['NAUTICAL_DUSK', 'nautical dusk'],
    ]);
  });

  it('builds the dawn gradient from the night edge, the four stops by position, and the golden-hour edge', () => {
    const model = raceModel(dawnSight());
    const stops = dawnSight().stops;
    const expected = buildRuleGradient([
      { key: 'NIGHT_START', position: 0 },
      ...stops.map((s) => ({ key: s.key, position: model.position(ms(s.time)) * 100 })),
      { key: 'GOLDEN_MORNING_END', position: 100 },
    ]);

    expect(model.gradient).toBe(expected);
    expect(model.gradient.startsWith('linear-gradient(90deg,')).toBe(true);
    expect(model.gradient.match(/%/g)).toHaveLength(6);
  });

  it('builds the dusk gradient from the golden-hour edge, the four stops by position, and the night edge', () => {
    const model = raceModel(duskSight());
    const stops = duskSight().stops;
    const expected = buildRuleGradient([
      { key: 'GOLDEN_EVENING_START', position: 0 },
      ...stops.map((s) => ({ key: s.key, position: model.position(ms(s.time)) * 100 })),
      { key: 'NIGHT_END', position: 100 },
    ]);

    expect(model.gradient).toBe(expected);
    expect(model.gradient.match(/%/g)).toHaveLength(6);
  });
});

describe('eclipseSpotLine — a rise that is not in shadow', () => {
  it('does not claim a rise in shadow just because a moonrise time is served', () => {
    const line = eclipseSpotLine({
      moonAltAtMax: 12, moonAzCardinal: 'E', setsInShadow: false, risesInShadow: false,
      moonset: null, moonrise: '2028-12-31T15:53:00',
    });

    expect(line).toBe('moon 12° up E at max · above the horizon throughout');
  });

  it('claims it when the flag and the time are both served', () => {
    const line = eclipseSpotLine({
      moonAltAtMax: 12, moonAzCardinal: 'E', setsInShadow: false, risesInShadow: true,
      moonset: null, moonrise: '2028-12-31T15:53:00',
    });

    expect(line).toBe('moon 12° up E at max · rises 15:53 in shadow');
  });
});
