import { describe, it, expect } from 'vitest';
import { eclipseSpotLine, formatRaceTime, raceModel, raceSentence } from '../utils/dawnRace.js';

const D = (hhmm) => `2026-09-28T${hhmm}:00`;
const ms = (hhmm) => Date.parse(`${D(hhmm)}Z`);

const dawnSight = (over = {}) => ({
  race: 'DAWN',
  stops: [
    { key: 'NAUTICAL_DAWN', time: D('05:00') },
    { key: 'CIVIL_DAWN', time: D('05:40') },
    { key: 'SUNRISE', time: D('06:20') },
    { key: 'GOLDEN_MORNING_END', time: D('07:00') },
  ],
  umbraStart: D('05:10'),
  umbraEnd: D('06:30'),
  maximum: D('05:50'),
  moonset: D('06:00'),
  moonrise: null,
  setsInShadow: true,
  risesInShadow: false,
  moonAltAtMax: 4,
  ...over,
});

const duskSight = (over = {}) => ({
  race: 'DUSK',
  stops: [
    { key: 'GOLDEN_EVENING_START', time: D('18:00') },
    { key: 'SUNSET', time: D('19:00') },
    { key: 'CIVIL_DUSK', time: D('19:30') },
    { key: 'NAUTICAL_DUSK', time: D('20:10') },
  ],
  umbraStart: D('18:20'),
  umbraEnd: D('19:40'),
  maximum: D('19:00'),
  moonset: null,
  moonrise: D('19:10'),
  setsInShadow: false,
  risesInShadow: true,
  moonAltAtMax: 3,
  ...over,
});

describe('formatRaceTime', () => {
  it('is empty for null and the UTC digits otherwise', () => {
    expect(formatRaceTime(null)).toBe('');
    expect(formatRaceTime(undefined)).toBe('');
    expect(formatRaceTime(ms('06:54'))).toBe('06:54');
  });
});

describe('raceModel rejections', () => {
  it('is null for no sight or no race', () => {
    expect(raceModel(null)).toBeNull();
    expect(raceModel({ race: null })).toBeNull();
    expect(raceModel({ race: 'NOON' })).toBeNull();
  });
  it('is null when the stop list is not an array, wrong length or wrongly keyed', () => {
    expect(raceModel(dawnSight({ stops: undefined }))).toBeNull();
    expect(raceModel(dawnSight({ stops: dawnSight().stops.slice(1) }))).toBeNull();
    expect(raceModel(dawnSight({ stops: duskSight().stops }))).toBeNull();
  });
  it('is null when an instant is missing or unreadable', () => {
    expect(raceModel(dawnSight({ umbraStart: null }))).toBeNull();
    expect(raceModel(dawnSight({ umbraEnd: undefined }))).toBeNull();
    expect(raceModel(dawnSight({ maximum: '' }))).toBeNull();
    const stops = dawnSight().stops.map((s, i) => (i === 3 ? { ...s, time: 'nonsense' } : s));
    expect(raceModel(dawnSight({ stops }))).toBeNull();
  });
  it('is null when the track would have no width', () => {
    const stops = [
      { key: 'GOLDEN_EVENING_START', time: D('18:00') },
      { key: 'SUNSET', time: D('23:00') },
      { key: 'CIVIL_DUSK', time: D('19:00') },
      { key: 'NAUTICAL_DUSK', time: D('20:00') },
    ];
    expect(raceModel(duskSight({ stops, umbraStart: D('21:00'), umbraEnd: D('19:00'), moonrise: null }))).toBeNull();
  });
});

describe('raceModel DAWN', () => {
  it('clips the umbra at moonset and hatches from moonset to the umbra end', () => {
    const model = raceModel(dawnSight());
    expect(model.trackStart).toBe(ms('05:00'));
    expect(model.trackEnd).toBe(ms('06:35'));
    expect(model.umbra).toEqual({ from: ms('05:10'), to: ms('06:00') });
    expect(model.hatch).toEqual({ from: ms('06:00'), to: ms('06:30') });
    expect(model.markers.map((m) => [m.key, m.role, m.label])).toEqual([
      ['TRACK_START', 'trackStart', null],
      ['UMBRA_START', 'umbraLabel', 'enters shadow'],
      ['MAXIMUM', 'maximum', 'max'],
      ['SUNRISE', 'raceEvent', 'sunrise'],
      ['MOONSET', 'horizon', 'moonset'],
    ]);
    expect(model.ticks.map((t) => [t.key, t.label])).toEqual([
      ['NAUTICAL_DAWN', 'nautical dawn'], ['CIVIL_DAWN', 'civil dawn'], ['GOLDEN_MORNING_END', 'golden hour ends'],
    ]);
    expect(model.position(null)).toBeNull();
    expect(model.position(ms('04:00'))).toBe(0);
    expect(model.position(ms('08:00'))).toBe(1);
  });

  it('floors the track start to the hour of the earlier of umbra start and first stop', () => {
    expect(raceModel(dawnSight({ umbraStart: D('04:40') })).trackStart).toBe(ms('04:00'));
  });

  it('has no hatch when it does not set in shadow', () => {
    expect(raceModel(dawnSight({ setsInShadow: false })).hatch).toBeNull();
  });

  it('with no moonset keeps the full umbra, no hatch and no horizon marker', () => {
    const model = raceModel(dawnSight({ moonset: null }));
    expect(model.umbra).toEqual({ from: ms('05:10'), to: ms('06:30') });
    expect(model.hatch).toBeNull();
    expect(model.markers.some((m) => m.role === 'horizon')).toBe(false);
  });

  it('clamps the hatch to the umbra end when the pad runs past it', () => {
    expect(raceModel(dawnSight({ moonset: D('06:25') })).hatch).toEqual({ from: ms('06:25'), to: ms('06:30') });
  });
});

describe('raceModel DUSK', () => {
  it('clips the umbra at moonrise and hatches from the clamped start to moonrise', () => {
    const model = raceModel(duskSight());
    expect(model.trackStart).toBe(ms('18:45'));
    expect(model.trackEnd).toBe(ms('21:00'));
    expect(model.umbra).toEqual({ from: ms('19:10'), to: ms('19:40') });
    expect(model.hatch).toEqual({ from: ms('18:45'), to: ms('19:10') });
    expect(model.markers.map((m) => [m.key, m.label])).toEqual([
      ['TRACK_START', null], ['UMBRA_END', 'leaves shadow'], ['MAXIMUM', 'max'], ['SUNSET', 'sunset'], ['MOONRISE', 'moonrise'],
    ]);
    expect(model.ticks.map((t) => t.label)).toEqual(['golden hour starts', 'civil dusk', 'nautical dusk']);
  });

  it('never starts the hatch before the umbra begins', () => {
    expect(raceModel(duskSight({ umbraStart: D('19:05') })).hatch.from).toBe(ms('19:05'));
  });

  it('with no moonrise keeps the whole umbra, no hatch and no horizon marker', () => {
    const model = raceModel(duskSight({ moonrise: null }));
    expect(model.umbra.from).toBe(ms('18:20'));
    expect(model.hatch).toBeNull();
    expect(model.markers.some((m) => m.role === 'horizon')).toBe(false);
  });

  it('has no hatch when it does not rise in shadow', () => {
    expect(raceModel(duskSight({ risesInShadow: false })).hatch).toBeNull();
  });
});

describe('raceSentence', () => {
  it('is empty without a model', () => {
    expect(raceSentence(null)).toBe('');
    expect(raceSentence({ race: 'DAWN', stops: [] })).toBe('');
  });
  it('words a dawn race that sets in shadow', () => {
    expect(raceSentence(dawnSight()))
      .toBe('In shadow from 05:10, maximum 05:50 with the moon 4° up, sunrise 06:20, sets 06:00 still in shadow.');
  });
  it('omits the set clause when the moon does not set in shadow or has no moonset', () => {
    expect(raceSentence(dawnSight({ setsInShadow: false })))
      .toBe('In shadow from 05:10, maximum 05:50 with the moon 4° up, sunrise 06:20.');
    expect(raceSentence(dawnSight({ moonset: null })))
      .toBe('In shadow from 05:10, maximum 05:50 with the moon 4° up, sunrise 06:20.');
  });
  it('words a dusk race that rises in shadow', () => {
    expect(raceSentence(duskSight()))
      .toBe('Rises 19:10 already in shadow, maximum 19:00 with the moon 3° up, sunset 19:00, leaves shadow 19:40.');
  });
  it('words a dusk race that does not rise in shadow', () => {
    expect(raceSentence(duskSight({ risesInShadow: false })))
      .toBe('In shadow, maximum 19:00 with the moon 3° up, sunset 19:00, leaves shadow 19:40.');
    expect(raceSentence(duskSight({ moonrise: null })))
      .toBe('In shadow, maximum 19:00 with the moon 3° up, sunset 19:00, leaves shadow 19:40.');
  });
});

describe('eclipseSpotLine', () => {
  const base = { moonAltAtMax: 12, moonAzCardinal: 'WSW' };
  it('is null without a sight, altitude or bearing', () => {
    expect(eclipseSpotLine(null)).toBeNull();
    expect(eclipseSpotLine({ moonAzCardinal: 'W' })).toBeNull();
    expect(eclipseSpotLine({ moonAltAtMax: 3 })).toBeNull();
  });
  it('words a set in shadow and a rise in shadow', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: D('06:05') }))
      .toBe('moon 12° up WSW at max · sets 06:05 in shadow');
    expect(eclipseSpotLine({ ...base, risesInShadow: true, moonrise: D('19:15') }))
      .toBe('moon 12° up WSW at max · rises 19:15 in shadow');
  });
  it('falls through when the set or rise time is unreadable', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: 'nonsense' }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
    expect(eclipseSpotLine({ ...base, risesInShadow: true, moonrise: 'nonsense' }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
  });
  it('falls through when the flag is set but the time is absent', () => {
    expect(eclipseSpotLine({ ...base, setsInShadow: true, moonset: null }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
    expect(eclipseSpotLine({ ...base, risesInShadow: true }))
      .toBe('moon 12° up WSW at max · above the horizon throughout');
  });
  it('words a below-horizon and an on-the-horizon moon without a visibility claim at zero', () => {
    expect(eclipseSpotLine({ moonAltAtMax: -2, moonAzCardinal: 'S' }))
      .toBe('moon 2° below the horizon at max · not visible from here');
    expect(eclipseSpotLine({ moonAltAtMax: 0, moonAzCardinal: 'S' })).toBe('moon on the horizon at max');
  });
});
