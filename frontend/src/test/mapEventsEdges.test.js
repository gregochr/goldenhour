/**
 * Edge behaviour of `utils/mapEvents.js`: malformed and missing inputs, defaults, tie-breaks and
 * exact row shapes that the main suite (`mapEvents.test.js`) does not pin.
 */
import { describe, it, expect } from 'vitest';
import {
  buildMapEvents, bestOfNight, pastWindowBest, buildPastSolarWindows, solarHorizonDates,
  nightPreviewDates, isPastSolarWindow, solarRowPredicate, isNightOffered, isForwardableRow,
  nextClockBoundary,
} from '../utils/mapEvents.js';

const TODAY = '2026-09-02';
const TOMORROW = '2026-09-03';
const YESTERDAY = '2026-09-01';
const NOW = new Date('2026-09-02T12:00:00Z');

const served = (date, targetType, extra = {}) => ({
  date, targetType, label: `${date} ${targetType}`, time: '19:45', bestRating: 4, confidenceTier: 'high', ...extra,
});

const build = (extra = {}) => buildMapEvents({
  todayStr: TODAY, tomorrowStr: TOMORROW, now: NOW, formatTimeUk: (v) => (v ? v.slice(11, 16) : null), ...extra,
});

describe('bestOfNight and pastWindowBest', () => {
  it('keeps a zero rating, and ignores non-finite or non-numeric ones', () => {
    expect(bestOfNight([{ stars: 0 }])).toBe(0);
    expect(bestOfNight([{ stars: null }, { stars: 0 }, { stars: -1 }])).toBe(0);
    expect(bestOfNight([{ stars: '5' }])).toBeNull();
    expect(bestOfNight([{ stars: Infinity }, { stars: NaN }, { stars: 2 }])).toBe(2);
    expect(bestOfNight([{ stars: 3 }, null, { stars: 2 }, { stars: 3 }])).toBe(3);
    expect(bestOfNight(null)).toBeNull();
    expect(bestOfNight('3')).toBeNull();
  });

  it('reads a painted zero as the best when nothing is higher', () => {
    expect(pastWindowBest([{ scores: [0] }], 0)).toBe(0);
    expect(pastWindowBest([{ scores: [0] }, { scores: [-1] }], 0)).toBe(0);
    expect(pastWindowBest([{ scores: [2] }, { scores: [5] }, { scores: [3] }], 0)).toBe(5);
    expect(pastWindowBest([{ scores: [5] }, { scores: [2] }], 0)).toBe(5);
  });
});

describe('buildPastSolarWindows', () => {
  const day = (sunrise, sunset) => ({ sunrise, sunset });
  const row = (time) => ({ rating: 3, solarEventTime: time });

  it('tolerates a null location and a day entry that is null', () => {
    const locations = [
      null,
      { forecastsByDate: new Map([[YESTERDAY, null]]) },
      { forecastsByDate: new Map([[YESTERDAY, day(row('2026-09-01T05:30:00'), null)]]) },
    ];
    expect(buildPastSolarWindows(locations, TODAY)).toEqual([
      { date: YESTERDAY, targetType: 'SUNRISE', earliestEventTime: '2026-09-01T05:30:00' },
    ]);
  });

  it('takes the earliest instant across locations, wherever it appears', () => {
    const at = (time) => ({ forecastsByDate: new Map([[YESTERDAY, day(null, row(time))]]) });
    const laterFirst = buildPastSolarWindows([at('2026-09-01T19:30:00'), at('2026-09-01T19:10:00')], TODAY);
    const earlierFirst = buildPastSolarWindows([at('2026-09-01T19:10:00'), at('2026-09-01T19:30:00')], TODAY);
    expect(laterFirst[0].earliestEventTime).toBe('2026-09-01T19:10:00');
    expect(earlierFirst[0].earliestEventTime).toBe('2026-09-01T19:10:00');
  });

  it('keeps the first spelling when two rows hold the same instant', () => {
    const at = (time) => ({ forecastsByDate: new Map([[YESTERDAY, day(null, row(time))]]) });
    const r = buildPastSolarWindows([at('2026-09-01T19:10:00'), at('2026-09-01T19:10:00Z')], TODAY);
    expect(r[0].earliestEventTime).toBe('2026-09-01T19:10:00');
  });

  it('leaves the time null when no row carries a parseable one', () => {
    const at = (time) => ({ forecastsByDate: new Map([[YESTERDAY, day(null, row(time))]]) });
    expect(buildPastSolarWindows([at('nonsense')], TODAY)[0].earliestEventTime).toBeNull();
    expect(buildPastSolarWindows([at(null)], TODAY)[0].earliestEventTime).toBeNull();
    const mixed = buildPastSolarWindows([at('nonsense'), at('2026-09-01T19:10:00')], TODAY);
    expect(mixed[0].earliestEventTime).toBe('2026-09-01T19:10:00');
  });

  it('returns windows chronologically, sunrise before sunset, whatever order the rows arrive', () => {
    const a = { forecastsByDate: new Map([['2026-09-01', day(null, row('2026-09-01T19:10:00'))]]) };
    const b = { forecastsByDate: new Map([['2026-09-01', day(row('2026-09-01T05:30:00'), null)]]) };
    const c = { forecastsByDate: new Map([['2026-08-31', day(null, row('2026-08-31T19:10:00'))]]) };
    const keys = (locs) => buildPastSolarWindows(locs, TODAY).map((w) => `${w.date}:${w.targetType}`);
    expect(keys([a, b, c])).toEqual(['2026-08-31:SUNSET', '2026-09-01:SUNRISE', '2026-09-01:SUNSET']);
    expect(keys([c, b, a])).toEqual(['2026-08-31:SUNSET', '2026-09-01:SUNRISE', '2026-09-01:SUNSET']);
    expect(keys([b, a, c])).toEqual(['2026-08-31:SUNSET', '2026-09-01:SUNRISE', '2026-09-01:SUNSET']);
  });
});

describe('solarHorizonDates and nightPreviewDates', () => {
  it('sorts the union and tolerates a null window', () => {
    expect(solarHorizonDates({
      solarWindows: [null, { date: '2026-09-05' }, { date: YESTERDAY }, {}],
      forecastDates: ['2026-09-04', YESTERDAY, TODAY],
      todayStr: TODAY,
    })).toEqual([TODAY, '2026-09-04', '2026-09-05']);
  });

  it('adds only past-dated offered nights, never today itself when it is off the horizon', () => {
    // Today is not on the horizon here; a night dated today is not over, but it is not "past-dated" either.
    expect(nightPreviewDates('ASTRO', [TODAY], { horizonDates: [], todayStr: TODAY })).toEqual([]);
    expect(nightPreviewDates('ASTRO', [YESTERDAY], {
      horizonDates: [], todayStr: TODAY, currentNightDate: YESTERDAY,
    })).toEqual([YESTERDAY]);
  });
});

describe('isPastSolarWindow default clock', () => {
  const base = { pastWindow: { earliestEventTime: '2026-09-02T05:00:00' }, inForecastDomain: true, todayStr: TODAY };

  it('is not past today without an elapsed test, and is past on an earlier date', () => {
    expect(isPastSolarWindow({ ...base, date: TODAY })).toBe(false);
    expect(isPastSolarWindow({ ...base, date: YESTERDAY })).toBe(true);
    expect(isPastSolarWindow({ ...base, date: TODAY, isEventTimePast: () => true })).toBe(true);
    expect(isPastSolarWindow({ ...base, date: TOMORROW, isEventTimePast: () => true })).toBe(false);
  });
});

describe('solarRowPredicate with malformed windows', () => {
  it('stays "unknown" (true) when every window is malformed or absent', () => {
    expect(solarRowPredicate({ todayStr: TODAY })('2030-01-01', 'SUNSET')).toBe(true);
    expect(solarRowPredicate({ solarWindows: [{}], todayStr: TODAY })('2030-01-01', 'SUNSET')).toBe(true);
    expect(solarRowPredicate({ solarWindows: [{ date: TODAY }], todayStr: TODAY })('2030-01-01', 'SUNSET')).toBe(true);
    expect(solarRowPredicate({ solarWindows: [{ targetType: 'SUNSET' }], todayStr: TODAY })('2030-01-01', 'SUNSET'))
      .toBe(true);
    expect(solarRowPredicate({ solarWindows: [null], todayStr: TODAY })('2030-01-01', 'SUNSET')).toBe(true);
  });

  it('does not let a past window without a type make that date a past row', () => {
    const p = solarRowPredicate({
      forecastDates: [YESTERDAY], pastSolarWindows: [{ date: YESTERDAY }, null, { targetType: 'SUNSET' }],
      todayStr: TODAY,
    });
    expect(p(YESTERDAY, undefined)).toBe(false);
    expect(p(YESTERDAY, 'SUNSET')).toBe(false);
  });

  it('counts a past window with date and type as a row', () => {
    const p = solarRowPredicate({
      forecastDates: [YESTERDAY], pastSolarWindows: [{ date: YESTERDAY, targetType: 'SUNSET' }], todayStr: TODAY,
    });
    expect(p(YESTERDAY, 'SUNSET')).toBe(true);
    expect(p(YESTERDAY, 'SUNRISE')).toBe(false);
  });
});

describe('isNightOffered', () => {
  it('survives an undefined event type with no night on screen', () => {
    expect(isNightOffered(undefined, YESTERDAY, { todayStr: TODAY })).toBe(false);
  });
});

describe('isForwardableRow edges', () => {
  it('forwards solar rows by the calendar, or by the past flag', () => {
    const solar = (date, extra = {}) => ({ kind: 'solar', inForecastDomain: true, date, ...extra });
    expect(isForwardableRow(solar(TOMORROW), { todayStr: TODAY })).toBe(true);
    expect(isForwardableRow(solar(YESTERDAY, { past: true }), { todayStr: TODAY })).toBe(true);
    expect(isForwardableRow(solar(YESTERDAY), { todayStr: TODAY })).toBe(false);
    expect(isForwardableRow({ kind: 'hw', inForecastDomain: true, date: TODAY }, { todayStr: TODAY })).toBe(true);
    expect(isForwardableRow({ kind: 'hw', inForecastDomain: true, date: YESTERDAY }, { todayStr: TODAY })).toBe(false);
  });

  it('judges a High-water row by the calendar, not by the night in progress', () => {
    const hw = { kind: 'hw', inForecastDomain: true, date: YESTERDAY };
    expect(isForwardableRow(hw, { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(false);
    const astro = { kind: 'astro', inForecastDomain: true, date: YESTERDAY };
    expect(isForwardableRow(astro, { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(true);
  });
});

describe('buildMapEvents defaults and malformed input', () => {
  it('produces nothing when it is told nothing', () => {
    expect(build()).toEqual([]);
  });

  it('offers aurora rows by default (not LITE)', () => {
    const rows = build({ auroraAvailableDates: [TODAY] });
    expect(rows.map((r) => r.kind)).toEqual(['aur']);
  });

  it('omits aurora for LITE', () => {
    expect(build({ auroraAvailableDates: [TODAY], isLite: true })).toEqual([]);
  });

  it('ignores null entries in the served and past window lists', () => {
    const rows = build({
      solarWindows: [null, {}, { date: TODAY }, served(TODAY, 'SUNSET')],
      pastSolarWindows: [null, {}, { date: TODAY }],
      forecastDates: [TODAY],
    });
    expect(rows.map((r) => r.id)).toEqual([`solar:${TODAY}:SUNRISE`, `solar:${TODAY}:SUNSET`]);
    expect(rows[1].served).toBe(true);
  });

  it('does not treat a window with a date but no type as served', () => {
    const rows = build({ solarWindows: [{ date: TOMORROW }, { targetType: 'SUNSET' }], forecastDates: [] });
    expect(rows).toEqual([]);
  });

  it('does not treat a past window with a date but no type as a past row', () => {
    const rows = build({
      pastSolarWindows: [{ date: YESTERDAY }, { targetType: 'SUNSET' }], forecastDates: [YESTERDAY],
    });
    expect(rows).toEqual([]);
  });

  it('tolerates a null day in bigSeas', () => {
    const rows = build({
      solarWindows: [served(TODAY, 'SUNRISE')],
      forecastDates: [TODAY],
      bigSeas: [null, { date: TODAY, alignedEvent: 'SUNRISE', windowEnd: '2026-09-02T18:00:00Z' }],
    });
    expect(rows[0].bigSeasAligned).toBe(true);
    expect(buildMapEvents({
      todayStr: TODAY, tomorrowStr: TOMORROW, now: NOW, bigSeas: [null], forecastDates: [TODAY],
    }).map((r) => r.id)).toEqual([`solar:${TODAY}:SUNRISE`, `solar:${TODAY}:SUNSET`]);
  });

  it('never stamps a non-solar row as big-seas aligned', () => {
    const rows = build({
      astroAvailableDates: [TODAY],
      bigSeas: [{ date: TODAY, alignedEvent: 'ASTRO', windowEnd: '2026-09-02T18:00:00Z' }],
    });
    expect(rows).toHaveLength(1);
    expect('bigSeasAligned' in rows[0]).toBe(false);
  });
});

describe('solar row shape', () => {
  it('carries the served fields, defaulting the missing ones', () => {
    const [row] = build({ solarWindows: [served(TODAY, 'SUNRISE', { label: 'Today sunrise', time: undefined })] });
    expect(row).toMatchObject({
      time: '', badges: [], away: false, pickKind: null, pickRegion: null, tide: null,
      dayLabel: 'Today', served: true, scored: true, bestRating: 4,
    });
  });

  it('carries the served time, pick region and confidence verbatim', () => {
    const [row] = build({
      solarWindows: [served(TOMORROW, 'SUNSET', {
        time: '19:45', pickKind: 'best', pickRegion: 'North', confidenceTier: 'low', badges: ['x'],
      })],
    });
    expect(row.time).toBe('19:45');
    expect(row.pickKind).toBe('best');
    expect(row.pickRegion).toBe('North');
    expect(row.confidence).toBe('low');
    expect(row.badges).toEqual(['x']);
  });

  it('reads only a finite number as a rating', () => {
    const rate = (bestRating) => build({ solarWindows: [served(TODAY, 'SUNSET', { bestRating })] })[0];
    expect(rate('4')).toMatchObject({ bestRating: null, scored: false });
    expect(rate(NaN)).toMatchObject({ bestRating: null, scored: false });
    expect(rate(Infinity)).toMatchObject({ bestRating: null, scored: false });
    expect(rate(0)).toMatchObject({ bestRating: 0, scored: true });
  });

  it('strips only a trailing sunrise/sunset word from the day text', () => {
    const dayText = (label) => build({ solarWindows: [served(TODAY, 'SUNSET', { label })] })[0].dayLabel;
    expect(dayText('Tonight Sunset')).toBe('Tonight');
    expect(dayText('Sat sunrise')).toBe('Sat');
    expect(dayText('Sat-sunset')).toBe('Sat-');
    expect(dayText('Sunset')).toBe('Sunset');
    expect(dayText('Sunset Sat')).toBe('Sunset Sat');
    expect(dayText('Sat sunset!')).toBe('Sat sunset!');
    expect(dayText('  Sat sunset')).toBe('Sat');
    expect(dayText('Sat SUNSET  ')).toBe('Sat');
  });

  it('gives a filler row an empty shape with no badges', () => {
    const [row] = build({ forecastDates: [TOMORROW] });
    expect(row).toMatchObject({
      served: false, scored: false, away: false, badges: [], time: '', tide: null, label: 'Tomorrow sunrise',
    });
  });

  it('lends a filler its tide and clock time only until the window has elapsed', () => {
    const tide = { curve: [] };
    const tideByWindow = new Map([[`${TODAY}:SUNRISE`, { tide, time: '06:10', eventTime: '2026-09-02T05:10:00' }]]);
    const lent = build({ forecastDates: [TODAY], tideByWindow, isEventTimePast: () => false })[0];
    expect(lent.tide).toBe(tide);
    const gone = build({ forecastDates: [TODAY], tideByWindow, isEventTimePast: () => true })[0];
    expect(gone.tide).toBeNull();
    const notAMap = build({ forecastDates: [TODAY], tideByWindow: { not: 'a map' } })[0];
    expect(notAMap.tide).toBeNull();
  });
});

describe('night rows', () => {
  const nights = (extra = {}) => build({
    astroAvailableDates: [TODAY],
    astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: '2026-09-02T21:45:00' }]]]),
    ...extra,
  });

  it('has no badges, and a time only when a formatter and a night start exist', () => {
    const [row] = nights();
    expect(row).toMatchObject({ badges: [], time: '21:45', scored: true, bestRating: 3 });
    expect(nights({ formatTimeUk: null })[0].time).toBe('');
    expect(nights({ formatTimeUk: () => 'ZZ' })[0].time).toBe('ZZ');
  });

  it('says nothing for a night with no rows or no night start, whatever the formatter', () => {
    const noStart = build({
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3 }]]]),
      formatTimeUk: () => 'ZZ',
    })[0];
    expect(noStart).toMatchObject({ time: '', nightStart: null, scored: true });
    const empty = build({
      astroAvailableDates: [TODAY], auroraAvailableDates: [TODAY], formatTimeUk: () => 'ZZ',
    });
    expect(empty).toHaveLength(2);
    empty.forEach((r) => expect(r).toMatchObject({ time: '', nightStart: null, scored: false, bestRating: null }));
  });

  it('takes the night start from the first row that has one', () => {
    const [row] = nights({
      astroConditionsByDate: new Map([[TODAY, [
        { stars: 1 }, null, { stars: 2, nightStart: '2026-09-02T22:00:00' }, { stars: 3, nightStart: '2026-09-02T23:00:00' },
      ]]]),
    });
    expect(row.nightStart).toBe('2026-09-02T22:00:00');
    expect(row.bestRating).toBe(3);
  });

  it('survives a non-array result list', () => {
    const [row] = nights({ astroConditionsByDate: new Map([[TODAY, { not: 'rows' }]]) });
    expect(row).toMatchObject({ scored: false, bestRating: null, nightStart: null });
  });
});

describe('past rows', () => {
  const past = [{ date: YESTERDAY, targetType: 'SUNSET', earliestEventTime: '2026-09-01T18:30:00' }];

  it('is unserved, unscored, away-free and carries a clock time', () => {
    const rows = build({ forecastDates: [YESTERDAY], pastSolarWindows: past });
    const row = rows.find((r) => r.eventType === 'SUNSET');
    expect(row).toMatchObject({
      past: true, served: false, scored: false, away: false, badges: [], time: '18:30', inForecastDomain: true,
    });
  });

  it('is blank rather than guessed when the window has no time', () => {
    const rows = build({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [{ date: YESTERDAY, targetType: 'SUNSET', earliestEventTime: null }],
    });
    expect(rows.find((r) => r.eventType === 'SUNSET').time).toBe('');
  });

  it('stays a filler on the same day until its time has elapsed', () => {
    const today = [{ date: TODAY, targetType: 'SUNRISE', earliestEventTime: '2026-09-02T05:00:00' }];
    const before = build({ forecastDates: [TODAY], pastSolarWindows: today });
    expect(before[0].past).toBeUndefined();
    const after = build({ forecastDates: [TODAY], pastSolarWindows: today, isEventTimePast: () => true });
    expect(after[0].past).toBe(true);
  });
});

describe('daylight High-water row placement', () => {
  const hwDay = (date) => ({
    date, daylightHighWater: true, hwTime: `${date}T12:30:00`, windowEnd: '2026-09-09T13:00:00Z',
  });

  it('is offered on a date in the forecast domain with no solar row of its own', () => {
    // Yesterday is in the domain but neither window is served or past, so the date has no sibling row.
    const rows = build({ forecastDates: [YESTERDAY], bigSeas: [hwDay(YESTERDAY)] });
    expect(rows.map((r) => r.id)).toEqual([`hw:${YESTERDAY}:HIGH_WATER`]);
    expect(rows[0]).toMatchObject({
      away: false, served: false, scored: false, badges: [], inForecastDomain: true, time: '12:30',
    });
  });

  it('is offered on a date whose only solar row is a served sunset outside the forecast domain', () => {
    const rows = build({ solarWindows: [served(TOMORROW, 'SUNSET')], bigSeas: [hwDay(TOMORROW)] });
    expect(rows.map((r) => r.kind)).toEqual(['hw', 'solar']);
    expect(rows[0].inForecastDomain).toBe(true);
  });

  it('is not offered on a date only a night contributed', () => {
    const rows = build({ astroAvailableDates: [TODAY], bigSeas: [hwDay(TODAY)] });
    expect(rows.map((r) => r.kind)).toEqual(['astro']);
  });

  it('sits between that date\'s sunrise and sunset, once per date', () => {
    const rows = build({ forecastDates: [TODAY], bigSeas: [hwDay(TODAY), hwDay(TODAY)] });
    expect(rows.map((r) => r.eventType)).toEqual(['SUNRISE', 'HIGH_WATER', 'SUNSET']);
  });
});

describe('nextClockBoundary edges', () => {
  const AFTERGLOW = 30 * 60 * 1000;
  const nowMs = Date.parse('2026-01-15T12:00:00Z');

  it('falls back to the next UK midnight with no arguments but the clock', () => {
    const next = nextClockBoundary({ nowMs, afterglowMs: AFTERGLOW });
    expect(next).toBeGreaterThanOrEqual(Date.parse('2026-01-16T00:00:00Z'));
    expect(next).toBeLessThanOrEqual(Date.parse('2026-01-16T00:00:00Z') + 1);
  });

  it('tolerates non-array past windows and big-seas lists', () => {
    expect(nextClockBoundary({ nowMs, afterglowMs: AFTERGLOW, pastSolarWindows: null, bigSeas: null }))
      .toBeGreaterThanOrEqual(Date.parse('2026-01-16T00:00:00Z'));
    expect(build({ pastSolarWindows: null, bigSeas: 'nope', forecastDates: [TODAY] })).toHaveLength(2);
  });

  it('ignores an instant exactly at the clock', () => {
    const next = nextClockBoundary({
      nowMs, afterglowMs: AFTERGLOW, bigSeas: [{ windowEnd: '2026-01-15T12:00:00Z' }],
    });
    expect(next).toBeGreaterThan(Date.parse('2026-01-15T13:00:00Z'));
  });

  it('skips null or malformed entries in every source', () => {
    const next = nextClockBoundary({
      nowMs,
      afterglowMs: AFTERGLOW,
      tideByWindow: new Map([['a', null], ['b', { eventTime: 'nonsense' }], ['c', { eventTime: '2026-01-15T12:30:00' }]]),
      pastSolarWindows: [null, { earliestEventTime: 'nonsense' }],
      bigSeas: [null, { windowEnd: 'nonsense' }, {}],
    });
    expect(next).toBe(Date.parse('2026-01-15T13:00:00Z'));
  });

  it('picks the earliest of several boundaries whatever the order', () => {
    const bigSeas = [{ windowEnd: '2026-01-15T15:00:00Z' }, { windowEnd: '2026-01-15T14:00:00Z' }];
    expect(nextClockBoundary({ nowMs, afterglowMs: AFTERGLOW, bigSeas })).toBe(Date.parse('2026-01-15T14:00:00Z'));
    expect(nextClockBoundary({ nowMs, afterglowMs: AFTERGLOW, bigSeas: [...bigSeas].reverse() }))
      .toBe(Date.parse('2026-01-15T14:00:00Z'));
  });
});
