import { describe, it, expect } from 'vitest';
// Edge behaviour of the Plan->Map door's pure resolution: clock and day labels, malformed input, the
// exact shape of every headline and handoff, and the best-spot / neighbour-outlier arithmetic.
import {
  normalizeMapTrigger, resolveDoor, doorHeadline, doorFromTrigger, outlierKeysOf, spotsNamed,
} from '../utils/mapDoor.js';

const DATE = '2026-07-04'; // a Saturday
const TODAY = '2026-07-01';
const TOMORROW = '2026-07-02';

function loc(name, regionName, rating, extra = {}) {
  const { time = `${DATE}T20:49:00`, event = 'sunset', lat = 54, lon = -1, ...rest } = extra;
  return {
    name,
    regionName,
    lat,
    lon,
    enabled: true,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[DATE, { [event]: { rating, solarEventTime: time } }]]),
    ...rest,
  };
}

const ctx = (locations, extra = {}) => ({
  locations,
  briefingScores: new Map(),
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
  ...extra,
});

const region = (r, extra = {}) => ({ kind: 'region', region: r, date: DATE, eventType: 'SUNSET', ...extra });
const event = (extra = {}) => ({ kind: 'event', date: DATE, eventType: 'SUNSET', ...extra });
const location = (name, extra = {}) => ({
  kind: 'location', locationName: name, date: DATE, eventType: 'SUNSET', ...extra,
});

describe('location door: clock and day wording', () => {
  it('reads a UTC-suffixed solar time as the same instant, in London time', () => {
    const l = loc('Spot', 'A', 3, { time: `${DATE}T20:49:00Z` });
    expect(resolveDoor(location('Spot'), ctx([l])).subLine).toBe('Sat sunset · 21:49');
  });

  it('shows no clock for an unparseable solar time', () => {
    const l = loc('Spot', 'A', 3, { time: 'not-a-date' });
    expect(resolveDoor(location('Spot'), ctx([l])).subLine).toBe('Sat sunset');
  });

  it('shows no clock when the spot has no forecast for the date', () => {
    const l = { ...loc('Spot', 'A', 3), forecastsByDate: new Map() };
    expect(resolveDoor(location('Spot'), ctx([l])).subLine).toBe('Sat sunset');
  });

  it('labels the day Today, Tomorrow, or its short weekday', () => {
    const l = loc('Spot', 'A', 3, { time: null });
    const sub = (todayStr, tomorrowStr) => resolveDoor(location('Spot'), ctx([l], { todayStr, tomorrowStr })).subLine;
    expect(sub(DATE, TOMORROW)).toBe('Today sunset');
    expect(sub(TODAY, DATE)).toBe('Tomorrow sunset');
    expect(sub(TODAY, TOMORROW)).toBe('Sat sunset');
  });

  it('words each event type, and says nothing for a missing one', () => {
    const l = loc('Spot', 'A', 3, { time: null });
    const sub = (eventType) => resolveDoor(location('Spot', { eventType }), ctx([l])).subLine;
    expect(sub('SUNRISE')).toBe('Sat sunrise');
    expect(sub('SUNSET')).toBe('Sat sunset');
    expect(sub('HIGH_WATER')).toBe('Sat high_water');
    expect(sub(undefined)).toBe('Sat');
    expect(sub(null)).toBe('Sat');
  });

  it('puts the clock after a middot, only when there is one', () => {
    const withTime = loc('Spot', 'A', 3);
    const noTime = loc('Spot', 'A', 3, { time: null });
    expect(resolveDoor(location('Spot'), ctx([withTime])).subLine).toBe('Sat sunset · 21:49');
    expect(resolveDoor(location('Spot'), ctx([noTime])).subLine).toBe('Sat sunset');
  });

  it('leaves the day out when the trigger has no date', () => {
    const l = loc('Spot', 'A', 3, { time: null });
    expect(resolveDoor(location('Spot', { date: undefined }), ctx([l])).subLine).toBe('sunset');
  });

  it('finds the named spot among several for the clock', () => {
    const first = loc('First', 'A', 3, { time: `${DATE}T18:00:00` });
    const second = loc('Second', 'A', 3, { time: `${DATE}T20:49:00` });
    expect(resolveDoor(location('Second'), ctx([first, second])).subLine).toBe('Sat sunset · 21:49');
  });

  it('reads the sunrise forecast for a sunrise door, not the sunset', () => {
    const l = {
      ...loc('Spot', 'A', 3),
      forecastsByDate: new Map([[DATE, {
        sunrise: { rating: 2, solarEventTime: `${DATE}T04:30:00` },
        sunset: { rating: 5, solarEventTime: `${DATE}T20:49:00` },
      }]]),
    };
    expect(resolveDoor(location('Spot', { eventType: 'SUNRISE' }), ctx([l])).subLine).toBe('Sat sunrise · 05:30');
  });

  it('shows no clock for a high-water door', () => {
    const l = loc('Spot', 'A', 3);
    expect(resolveDoor(location('Spot', { eventType: 'HIGH_WATER' }), ctx([l])).subLine).toBe('Sat high_water');
  });

  it('survives a spot with no forecast table, a table without a getter, or a day without the event', () => {
    const none = { ...loc('Spot', 'A', 3), forecastsByDate: undefined };
    const plain = { ...loc('Spot', 'A', 3), forecastsByDate: {} };
    const sunriseOnly = {
      ...loc('Spot', 'A', 3),
      forecastsByDate: new Map([[DATE, { sunrise: { rating: 1, solarEventTime: `${DATE}T04:30:00` } }]]),
    };
    expect(resolveDoor(location('Spot'), ctx([none])).subLine).toBe('Sat sunset');
    expect(resolveDoor(location('Spot'), ctx([plain])).subLine).toBe('Sat sunset');
    expect(resolveDoor(location('Spot'), ctx([sunriseOnly])).subLine).toBe('Sat sunset');
    expect(resolveDoor(location('Spot', { eventType: 'SUNRISE' }), ctx([none])).subLine).toBe('Sat sunrise');
    expect(resolveDoor(location('Spot', { eventType: 'SUNRISE' }), ctx([plain])).subLine).toBe('Sat sunrise');
    const sunsetOnly = {
      ...loc('Spot', 'A', 3),
      forecastsByDate: new Map([[DATE, { sunset: { rating: 1, solarEventTime: `${DATE}T20:49:00` } }]]),
    };
    expect(resolveDoor(location('Spot', { eventType: 'SUNRISE' }), ctx([sunsetOnly])).subLine).toBe('Sat sunrise');
  });

  it('has an exact location handoff and no focus', () => {
    const r = resolveDoor(location('Spot'), ctx([loc('Spot', 'A', 3)]));
    expect(r.title).toBe('Spot');
    expect(r.focus).toBeNull();
    expect(r.handoff).toEqual({ eventType: 'SUNSET', locationName: 'Spot', date: DATE });
  });
});

describe('rating: ratings by event and briefing key', () => {
  it('rates a sunrise door from the sunrise forecast', () => {
    const mk = (name, sunrise, sunset) => ({
      ...loc(name, 'A', 1),
      forecastsByDate: new Map([[DATE, { sunrise: { rating: sunrise }, sunset: { rating: sunset } }]]),
    });
    const locs = [mk('Dawn', 5, 1), mk('Dusk', 2, 5)];
    expect(resolveDoor(region('A', { eventType: 'SUNRISE' }), ctx(locs)).handoff.locationName).toBe('Dawn');
    expect(resolveDoor(region('A', { eventType: 'SUNSET' }), ctx(locs)).handoff.locationName).toBe('Dusk');
  });

  it('survives rating lookups on missing days, events and tables', () => {
    const noTable = { ...loc('NoTable', 'A', 1), forecastsByDate: undefined };
    const noGetter = { ...loc('NoGetter', 'A', 1), forecastsByDate: {} };
    const otherDay = { ...loc('OtherDay', 'A', 1), forecastsByDate: new Map([['2030-01-01', {}]]) };
    const rated = loc('Rated', 'A', 4);
    for (const eventType of ['SUNRISE', 'SUNSET']) {
      const r = resolveDoor(region('A', { eventType }), ctx([noTable, noGetter, otherDay, rated]));
      expect(r.title).toBe('A');
      expect(r.handoff.eventType).toBe(eventType);
    }
    const sunsetOnly = resolveDoor(region('A', { eventType: 'SUNRISE' }), ctx([rated]));
    // Nothing is rated for a sunrise, so the first spot is the one opened.
    expect(sunsetOnly.handoff).toEqual({ eventType: 'SUNRISE', locationName: 'Rated', date: DATE, filterAction: null });
  });

  it('takes the briefing score of the matching key, not the first key in the map', () => {
    const a = loc('Alpha', 'R', 1);
    const b = loc('Beta', 'R', 1);
    const scores = new Map([
      [`R|${DATE}|SUNSET|Alpha`, { rating: 2 }],
      [`R|${DATE}|SUNSET|Beta`, { rating: 5 }],
      [`R|${DATE}|SUNRISE|Beta`, { rating: 1 }],
    ]);
    const r = resolveDoor(region('R'), ctx([a, b], { briefingScores: scores }));
    expect(r.handoff.locationName).toBe('Beta');
  });

  it('ignores a null briefing-score map and an empty one', () => {
    const a = loc('Alpha', 'R', 2);
    const b = loc('Beta', 'R', 4);
    expect(resolveDoor(region('R'), ctx([a, b], { briefingScores: null })).handoff.locationName).toBe('Beta');
    expect(resolveDoor(region('R'), ctx([a, b], { briefingScores: new Map() })).handoff.locationName).toBe('Beta');
  });

  it('gives a high-water door no rating, so the sunset ratings do not pick its spot', () => {
    const r = resolveDoor(region('R', { eventType: 'HIGH_WATER' }), ctx([loc('Alpha', 'R', 1), loc('Beta', 'R', 5)]));
    expect(r.handoff).toEqual({ eventType: 'HIGH_WATER', locationName: 'Alpha', date: DATE, filterAction: null });
  });
});

describe('roster filter', () => {
  it('drops disabled spots and spots without coordinates, but keeps a spot at 0,0', () => {
    const locs = [
      loc('Off', 'A', 5, { enabled: false }),
      loc('NoLat', 'A', 5, { lat: null }),
      loc('NoLon', 'A', 5, { lon: null }),
      loc('Equator', 'A', 1, { lat: 0, lon: 0 }),
      { ...loc('Implicit', 'A', 2), enabled: undefined },
    ];
    const r = resolveDoor(event({ locationNames: ['Off', 'NoLat', 'NoLon', 'Equator', 'Implicit'] }), ctx(locs));
    expect(r.focus.names).toEqual(['Equator', 'Implicit']);
  });

  it('defaults to an empty roster and empty scores when the context carries none', () => {
    const r = resolveDoor(region('R'), { todayStr: TODAY, tomorrowStr: TOMORROW });
    expect(r.title).toBe('R');
    expect(r.handoff).toEqual({ eventType: 'SUNSET', region: 'R', date: DATE, filterAction: null });
  });
});

describe('coming-up door', () => {
  const comingUp = (extra = {}) => ({ kind: 'coming-up', date: DATE, ...extra });

  it('counts distinct regions, not matching spots, and says "region" for exactly one', () => {
    const locs = [loc('A1', 'North', 3), loc('A2', 'North', 3)];
    const r = resolveDoor(comingUp(), ctx(locs));
    expect(r.subLine).toBe('1 region');
    const two = resolveDoor(comingUp(), ctx([...locs, loc('B1', 'South', 3)]));
    expect(two.subLine).toBe('2 regions');
  });

  it('has no region line when no matching spot carries a region name', () => {
    const r = resolveDoor(comingUp(), ctx([loc('A1', undefined, 3), loc('A2', '', 3)]));
    expect(r.subLine).toBeNull();
  });

  it('handles roster spots without a locationType when filtering by one', () => {
    const noType = { ...loc('Bare', 'R', 3), locationType: undefined };
    const sea = { ...loc('Sea', 'R', 3), locationType: ['SEASCAPE'] };
    const r = resolveDoor(comingUp({ filterAction: 'SEASCAPE' }), ctx([noType, sea]));
    expect(r.focus.names).toEqual(['Sea']);
  });

  it('fits every spot without names when nothing filters, and carries the date through', () => {
    const r = resolveDoor(comingUp({ label: 'Soon' }), ctx([loc('A', 'R', 3, { lat: 1, lon: 2 })]));
    expect(r.title).toBe('Soon');
    expect(r.focus).toEqual({ points: [[1, 2]] });
    expect(r.handoff).toEqual({ filterAction: undefined, darkSky: false, date: DATE });
  });

  it('keeps only dark-sky spots up to the threshold, with names', () => {
    const locs = [
      loc('Dark', 'R', 3, { bortleClass: 4 }),
      loc('Bright', 'R', 3, { bortleClass: 5 }),
      loc('Unknown', 'R', 3, { bortleClass: null }),
    ];
    const r = resolveDoor(comingUp({ darkSky: true, filterAction: null }), ctx(locs));
    expect(r.title).toBe('Dark-sky spots');
    expect(r.focus.names).toEqual(['Dark']);
    expect(r.handoff).toEqual({ filterAction: null, darkSky: true, date: DATE });
  });
});

describe('region and event doors', () => {
  it('limits a region door to its own region', () => {
    const locs = [loc('Other', 'B', 5), loc('Mine', 'A', 2)];
    const r = resolveDoor(region('A'), ctx(locs));
    expect(r.title).toBe('A');
    expect(r.handoff.locationName).toBe('Mine');
  });

  it('treats a region door with no region as every region', () => {
    const locs = [loc('Alpha', 'A', 5), loc('Beta', 'B', 2)];
    const r = resolveDoor({ kind: 'region', region: null, date: DATE, eventType: 'SUNSET' }, ctx(locs));
    expect(r.title).toBe('Sat sunset');
    expect(r.focus.points).toHaveLength(2);
  });

  it('does not narrow an event door to a stray region field, but keeps its title', () => {
    const locs = [loc('Mine', 'A', 2), loc('Better', 'B', 5)];
    const r = resolveDoor(event({ region: 'A' }), ctx(locs));
    expect(r.title).toBe('A');
    expect(r.handoff.locationName).toBe('Better');
  });

  it('keeps the requested region as the title even when nothing matches it', () => {
    const r = resolveDoor(region('Nowhere'), ctx([loc('Mine', 'A', 2)]));
    expect(r.title).toBe('Nowhere');
    expect(r.handoff).toEqual({ eventType: 'SUNSET', region: 'Nowhere', date: DATE, filterAction: null });
  });

  it('pools only rated spots, so an unrated region does not make it multi-region', () => {
    const rated = loc('Rated', 'A', 4);
    const unrated = { ...loc('Unrated', 'B', 4), forecastsByDate: new Map() };
    const r = resolveDoor(event(), ctx([rated, unrated]));
    expect(r.title).toBe('A');
    expect(r.handoff.locationName).toBe('Rated');
  });

  it('counts distinct regions, not spots, so two spots in one region stay single-region', () => {
    const r = resolveDoor(event(), ctx([loc('One', 'A', 4), loc('Two', 'A', 3)]));
    expect(r.title).toBe('A');
    expect(r.handoff.locationName).toBe('One');
  });

  it('describes a multi-region door exactly', () => {
    const locs = [
      loc('Alpha', 'A', 4, { lat: 1, lon: 2, time: `${DATE}T20:49:00` }),
      loc('Beta', 'B', 3, { lat: 3, lon: 4 }),
    ];
    const r = resolveDoor(event(), ctx(locs));
    expect(r).toEqual({
      title: 'Sat sunset',
      subLine: '2 regions · 21:49',
      focus: { points: [[1, 2], [3, 4]] },
      handoff: { eventType: 'SUNSET', date: DATE },
    });
  });

  it('trims the multi-region title and drops the clock when there is none', () => {
    const locs = [
      loc('Alpha', 'A', 4, { time: null }),
      loc('Beta', 'B', 3, { time: null }),
    ];
    const r = resolveDoor(event({ date: undefined }), ctx(locs));
    expect(r.title).toBe('sunset');
    expect(r.subLine).toBe('2 regions');
  });
});

describe('qualifying spots (locationNames)', () => {
  const locs = [
    loc('One', 'A', 4, { lat: 1, lon: 1 }),
    loc('Two', 'A', 3, { lat: 2, lon: 2 }),
    loc('Three', 'A', 5, { lat: 3, lon: 3 }),
  ];

  it('shows just the named spots, fitted to their points, with the label and day', () => {
    const r = resolveDoor(region('A', { locationNames: ['One', 'Two'], label: 'Elevated' }), ctx(locs));
    expect(r).toEqual({
      title: 'A',
      subLine: 'Elevated · Sat',
      focus: { points: [[1, 1], [2, 2]], names: ['One', 'Two'] },
      handoff: { eventType: 'SUNSET', date: DATE, filterAction: null },
    });
  });

  it('falls back to the label for a title, and to no sub-line when there is no label or day', () => {
    const labelled = resolveDoor(
      event({ locationNames: ['One', 'Two'], label: 'Elevated' }), ctx(locs),
    );
    expect(labelled.title).toBe('Elevated');
    expect(labelled.subLine).toBe('Elevated · Sat');
    const unlabelled = resolveDoor(event({ locationNames: ['One', 'Two'] }), ctx(locs));
    expect(unlabelled.title).toBe('On the map');
    expect(unlabelled.subLine).toBe('Sat');
    const bare = resolveDoor(event({ locationNames: ['One', 'Two'], date: undefined }), ctx(locs));
    expect(bare.subLine).toBeNull();
  });

  it('falls back to the whole pool when none of the named spots is on the roster', () => {
    const r = resolveDoor(region('A', { locationNames: ['Ghost'] }), ctx(locs));
    expect(r.focus.names).toEqual(['One', 'Two', 'Three']);
  });

  it('restricts the map to a single qualifying spot without points', () => {
    const r = resolveDoor(region('A', { locationNames: ['Two'], label: 'Solo' }), ctx(locs));
    expect(r.focus).toEqual({ names: ['Two'] });
    expect(r.subLine).toBe('Solo · Sat');
    expect(r.handoff.locationName).toBe('Two');
  });

  it('carries a filter action through the handoff', () => {
    const r = resolveDoor(region('A', { locationNames: ['One', 'Two'], filterAction: 'SEASCAPE' }), ctx(locs));
    expect(r.handoff.filterAction).toBe('SEASCAPE');
  });
});

describe('single region: best spot and neighbour outliers', () => {
  it('titles a mixed pool by the best-rated spot\'s region (later higher beats earlier)', () => {
    const locs = [loc('Named', 'Zone', 3), loc('Nameless', undefined, 5)];
    // regionsInvolved is {Zone}: single region; the top-rated spot has no region name.
    expect(resolveDoor(event(), ctx(locs)).title).toBe('Nameless');
  });

  it('titles a mixed pool by the earlier spot when the later is lower', () => {
    const locs = [loc('Nameless', undefined, 5), loc('Named', 'Zone', 3)];
    expect(resolveDoor(event(), ctx(locs)).title).toBe('Nameless');
    const flipped = [loc('Named', 'Zone', 5), loc('Nameless', undefined, 3)];
    expect(resolveDoor(event(), ctx(flipped)).title).toBe('Zone');
  });

  it('keeps the first of two equally rated spots', () => {
    const locs = [loc('Named', 'Zone', 4), loc('Nameless', undefined, 4)];
    expect(resolveDoor(event(), ctx(locs)).title).toBe('Zone');
    const swapped = [loc('Nameless', undefined, 4), loc('Named', 'Zone', 4)];
    expect(resolveDoor(event(), ctx(swapped)).title).toBe('Nameless');
  });

  it('uses the spot name as the title when no region is known, and a fallback when there is no spot', () => {
    expect(resolveDoor(event(), ctx([loc('Lonely', undefined, 3)])).title).toBe('Lonely');
    expect(resolveDoor(event(), ctx([])).title).toBe('On the map');
  });

  it('builds the sub-line from the label, or from day, event and clock', () => {
    const labelled = resolveDoor(region('A', { label: 'Mist' }), ctx([loc('S', 'A', 3)]));
    expect(labelled.subLine).toBe('Mist · Sat');
    const labelOnly = resolveDoor(region('A', { label: 'Mist', date: undefined }), ctx([loc('S', 'A', 3)]));
    expect(labelOnly.subLine).toBe('Mist');
    const plain = resolveDoor(region('A'), ctx([loc('S', 'A', 3)]));
    expect(plain.subLine).toBe('Sat sunset · 21:49');
    const noClock = resolveDoor(region('A'), ctx([loc('S', 'A', 3, { time: null })]));
    expect(noClock.subLine).toBe('Sat sunset');
  });

  const marked = (...names) => new Set(names.map((n) => `${n}|${DATE}|SUNSET`));

  it('names no marked outlier, falling to the next spot at or above the floor', () => {
    const locs = [loc('Outlier', 'A', 5), loc('Next', 'A', 3), loc('Low', 'A', 2)];
    const r = resolveDoor(region('A'), ctx(locs, { outlierKeys: marked('Outlier') }));
    expect(r.handoff.locationName).toBe('Next');
  });

  it('names nothing when the next spot is below the floor', () => {
    const locs = [loc('Outlier', 'A', 5), loc('Low', 'A', 2)];
    const r = resolveDoor(region('A'), ctx(locs, { outlierKeys: marked('Outlier') }));
    expect(r.handoff).toEqual({ eventType: 'SUNSET', region: 'A', date: DATE, filterAction: null });
  });

  it('names nothing when every rated spot is marked', () => {
    const locs = [loc('Outlier', 'A', 5)];
    const r = resolveDoor(region('A'), ctx(locs, { outlierKeys: marked('Outlier') }));
    expect(r.handoff.locationName).toBeUndefined();
    expect(r.handoff.region).toBe('A');
  });

  it('does not skip a marked spot rated below the best unmarked one', () => {
    // The marked spot (1 star) is not what was skipped: the 2-star spot is named even though it is under
    // the floor, because nothing at or above it was passed over.
    const locs = [loc('Outlier', 'A', 1), loc('Low', 'A', 2)];
    const r = resolveDoor(region('A'), ctx(locs, { outlierKeys: marked('Outlier') }));
    expect(r.handoff.locationName).toBe('Low');
  });

  it('skips a marked spot that ties the best unmarked one', () => {
    const locs = [loc('Outlier', 'A', 2), loc('Low', 'A', 2)];
    const r = resolveDoor(region('A'), ctx(locs, { outlierKeys: marked('Outlier') }));
    expect(r.handoff.locationName).toBeUndefined();
  });

  it('counts a marked spot with no rating as skipped, even though it is not in the pool', () => {
    const unrated = { ...loc('Outlier', 'A', 5), forecastsByDate: new Map() };
    const r = resolveDoor(region('A'), ctx([unrated, loc('Low', 'A', 2)], { outlierKeys: marked('Outlier') }));
    expect(r.handoff.locationName).toBeUndefined();
  });

  it('does not treat an unmarked unrated spot as skipped', () => {
    const unrated = { ...loc('Quiet', 'A', 5), forecastsByDate: new Map() };
    const r = resolveDoor(region('A'), ctx([unrated, loc('Low', 'A', 2)], { outlierKeys: marked('Elsewhere') }));
    expect(r.handoff.locationName).toBe('Low');
  });

  it('only a mark on this date and event counts', () => {
    const locs = [loc('Spot', 'A', 5)];
    const other = new Set([`Spot|${DATE}|SUNRISE`, `Spot|2030-01-01|SUNSET`]);
    expect(resolveDoor(region('A'), ctx(locs, { outlierKeys: other })).handoff.locationName).toBe('Spot');
  });

  it('picks the highest of several spots whatever their order', () => {
    const asc = [loc('A1', 'A', 2), loc('A2', 'A', 5), loc('A3', 'A', 3)];
    expect(resolveDoor(region('A'), ctx(asc)).handoff.locationName).toBe('A2');
    const first = [loc('A1', 'A', 5), loc('A2', 'A', 2), loc('A3', 'A', 3)];
    expect(resolveDoor(region('A'), ctx(first)).handoff.locationName).toBe('A1');
  });

  it('breaks a tie for the top spot in favour of the first', () => {
    const tied = [loc('A1', 'A', 4), loc('A2', 'A', 4)];
    expect(resolveDoor(region('A'), ctx(tied)).handoff.locationName).toBe('A1');
  });

  it('opens the first spot of an all-unrated region', () => {
    const bare = (n) => ({ ...loc(n, 'A', 1), forecastsByDate: new Map() });
    expect(resolveDoor(region('A'), ctx([bare('A1'), bare('A2')])).handoff.locationName).toBe('A1');
  });
});

describe('outlierKeysOf', () => {
  it('collects only slots flagged exactly true that carry a name', () => {
    const briefing = {
      days: [{
        date: DATE,
        eventSummaries: [{
          targetType: 'SUNSET',
          regions: [{
            slots: [
              { locationName: 'Flagged', neighbourOutlier: true },
              { locationName: 'Truthy', neighbourOutlier: 'TRUE' },
              { neighbourOutlier: true },
              { locationName: 'Plain' },
            ],
          }],
        }],
      }],
    };
    expect([...outlierKeysOf(briefing)]).toEqual([`Flagged|${DATE}|SUNSET`]);
  });

  it('tolerates empty and null levels at every depth', () => {
    const briefing = {
      days: [
        null,
        { date: DATE },
        { date: DATE, eventSummaries: [null, { targetType: 'SUNSET' }] },
        { date: DATE, eventSummaries: [{ targetType: 'SUNSET', regions: [null, {}] }] },
        {
          date: DATE,
          eventSummaries: [{
            targetType: 'SUNSET',
            regions: [{ slots: [null, { locationName: 'Ok', neighbourOutlier: true }] }],
          }],
        },
      ],
    };
    expect([...outlierKeysOf(briefing)]).toEqual([`Ok|${DATE}|SUNSET`]);
    expect(outlierKeysOf(null).size).toBe(0);
    expect(outlierKeysOf({}).size).toBe(0);
  });
});

describe('spotsNamed', () => {
  it('returns the named spots in roster order and tolerates holes in the roster', () => {
    const roster = [{ name: 'A' }, null, { name: 'B' }, { name: 'C' }];
    expect(spotsNamed(roster, ['C', 'A'])).toEqual([{ name: 'A' }, { name: 'C' }]);
  });

  it('returns nothing for non-array names or an empty list', () => {
    const roster = [{ name: 'A' }];
    expect(spotsNamed(roster, null)).toEqual([]);
    expect(spotsNamed(roster, 'A')).toEqual([]);
    expect(spotsNamed(roster, [])).toEqual([]);
    expect(spotsNamed(null, ['A'])).toEqual([]);
  });
});

describe('normalizeMapTrigger edges', () => {
  it('turns a null or undefined first argument into a plain event trigger', () => {
    expect(normalizeMapTrigger(null, 'SUNSET')).toEqual({ kind: 'event', date: null, eventType: 'SUNSET' });
    expect(normalizeMapTrigger(undefined, 'SUNSET')).toEqual({ kind: 'event', date: undefined, eventType: 'SUNSET' });
  });

  it('turns a plain date string into an event trigger, or a location trigger with a name', () => {
    expect(normalizeMapTrigger(DATE, 'SUNRISE')).toEqual({ kind: 'event', date: DATE, eventType: 'SUNRISE' });
    expect(normalizeMapTrigger(DATE, 'SUNRISE', 'Spot')).toEqual({
      kind: 'location', locationName: 'Spot', date: DATE, eventType: 'SUNRISE',
    });
  });

  it('keeps the aurora date and nothing else', () => {
    expect(normalizeMapTrigger({ kind: 'aurora', date: DATE, extra: 1 })).toEqual({ kind: 'aurora', date: DATE });
  });

  it('keeps every region field, defaulting the optional ones to null', () => {
    const full = normalizeMapTrigger({
      region: 'R', date: DATE, eventType: 'SUNSET', locationNames: ['A'], label: 'L', filterAction: 'SEASCAPE',
    });
    expect(full).toEqual({
      kind: 'region', region: 'R', date: DATE, eventType: 'SUNSET',
      locationNames: ['A'], label: 'L', filterAction: 'SEASCAPE',
    });
    const bare = normalizeMapTrigger({ region: 'R', date: DATE, eventType: 'SUNSET' });
    expect(bare.locationNames).toBeNull();
    expect(bare.label).toBeNull();
    expect(bare.filterAction).toBeNull();
  });

  it('keeps the coming-up fields, defaulting them', () => {
    expect(normalizeMapTrigger({
      kind: 'coming-up', date: DATE, filterAction: 'SEASCAPE', darkSky: true, label: 'L',
    })).toEqual({ kind: 'coming-up', filterAction: 'SEASCAPE', darkSky: true, label: 'L', date: DATE });
    expect(normalizeMapTrigger({ kind: 'coming-up', date: DATE })).toEqual({
      kind: 'coming-up', filterAction: null, darkSky: false, label: null, date: DATE,
    });
  });

  it('adds a topic type to an event trigger only when asked', () => {
    expect(normalizeMapTrigger({ kind: 'event', date: DATE, eventType: 'SUNSET' })).toEqual({
      kind: 'event', date: DATE, eventType: 'SUNSET',
    });
    expect(normalizeMapTrigger({ kind: 'event', date: DATE, eventType: 'SUNSET', topicType: 'INVERSION' })).toEqual({
      kind: 'event', date: DATE, eventType: 'SUNSET', topicType: 'INVERSION',
    });
  });
});

describe('doorFromTrigger edges', () => {
  it('keeps a region door\'s region when it also names a spot', () => {
    const d = doorFromTrigger(region('A'), ctx([loc('S', 'A', 4)]));
    expect(d.region).toBe('A');
    expect(d.locationName).toBe('S');
  });

  it('gives an event door no region even if the trigger carries a stray one', () => {
    const d = doorFromTrigger(event({ region: 'A' }), ctx([loc('S', 'A', 4), loc('T', 'B', 2)]));
    expect(d.region).toBeNull();
    expect(d.locationName).toBe('S');
  });

  it('carries a topic type only on an event door', () => {
    const locs = [loc('S', 'A', 4)];
    expect(doorFromTrigger(event({ topicType: 'INVERSION' }), ctx(locs)).topicType).toBe('INVERSION');
    expect('topicType' in doorFromTrigger(region('A', { topicType: 'INVERSION' }), ctx(locs))).toBe(false);
    expect('topicType' in doorFromTrigger(event(), ctx(locs))).toBe(false);
  });

  it('carries focus names from a single qualifying spot, which has no points', () => {
    const d = doorFromTrigger(region('A', { locationNames: ['S'] }), ctx([loc('S', 'A', 4), loc('T', 'A', 3)]));
    expect(d.focusNames).toEqual(['S']);
    expect('focusPoints' in d).toBe(false);
  });

  it('carries no focus fields when the resolution has none', () => {
    const d = doorFromTrigger(location('S'), ctx([loc('S', 'A', 4)]));
    expect('focusNames' in d).toBe(false);
    expect('focusPoints' in d).toBe(false);
  });

  it('carries points but not names for an unfiltered fit, and both for a named lens', () => {
    const locs = [loc('A', 'R1', 4, { lat: 1, lon: 2 }), loc('B', 'R2', 4, { lat: 3, lon: 4 })];
    const fit = doorFromTrigger(event(), ctx(locs));
    expect(fit.focusPoints).toEqual([[1, 2], [3, 4]]);
    expect('focusNames' in fit).toBe(false);
    const lens = doorFromTrigger({ kind: 'coming-up', date: DATE, filterAction: 'LANDSCAPE' }, ctx(locs));
    expect(lens.focusNames).toEqual(['A', 'B']);
    expect(lens.filterAction).toBe('LANDSCAPE');
    expect('darkSky' in lens).toBe(false);
  });

  it('flags dark-sky and an aurora night, and copies the lens fields that are present', () => {
    const dark = doorFromTrigger({ kind: 'coming-up', date: DATE, darkSky: true }, ctx([loc('A', 'R', 4, { bortleClass: 2 })]));
    expect(dark.darkSky).toBe(true);
    const aurora = doorFromTrigger({ kind: 'aurora', date: DATE }, ctx([]));
    expect(aurora.night).toEqual({ kind: 'aurora', date: DATE });
    expect(aurora.targetType).toBe('AURORA');
    const lens = doorFromTrigger(
      { ...event(), minRating: 0, limitMinutes: 90, inPlace: false, from: 'plan' }, ctx([loc('A', 'R', 4)]),
    );
    expect(lens).toMatchObject({ minRating: 0, limitMinutes: 90, inPlace: false, from: 'plan' });
    const plain = doorFromTrigger(event(), ctx([loc('A', 'R', 4)]));
    ['minRating', 'limitMinutes', 'inPlace', 'from', 'night'].forEach((k) => expect(k in plain).toBe(false));
  });

  it('reads the headline from the resolution', () => {
    expect(doorHeadline(location('S'), ctx([loc('S', 'A', 4)]))).toEqual({
      title: 'S', caption: 'Sat sunset · 21:49',
    });
    expect(doorHeadline({ kind: 'aurora', date: DATE }, ctx([]))).toEqual({ title: 'Aurora tonight', caption: null });
  });
});
