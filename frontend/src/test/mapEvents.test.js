/**
 * Tests for `utils/mapEvents.js` — the Map tab's single chronological event list
 * (map-tab-v2-plan.md §3 P6).
 *
 * Covers: chronological ordering with night-after-sunset, the aurora presence rule (rows only
 * where results exist) and its LITE absence, D-13 beyond-briefing solar rows, the served-vs-
 * client-max discipline (solar never re-derives; night rows take a licensed client max), and the
 * empty-briefing degrade.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  buildMapEvents, findEvIndex, nightLabel, EVENT_KIND, solarHorizonDates,
} from '../utils/mapEvents.js';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';

const TODAY = '2026-09-02';
const TOMORROW = '2026-09-03';

/** A served solar window, in the shape `WindowFirstMapPane`'s `heat.windows` builds. */
function solarWindow(date, targetType, overrides = {}) {
  return {
    key: `${date}:${targetType}`,
    date,
    targetType,
    label: overrides.label ?? `${date} ${targetType}`,
    time: overrides.time ?? (targetType === 'SUNRISE' ? '06:30' : '19:45'),
    bestRating: 'bestRating' in overrides ? overrides.bestRating : 4,
    confidenceTier: overrides.confidenceTier ?? 'high',
    badges: overrides.badges ?? [],
  };
}

const baseArgs = () => ({
  solarWindows: [],
  forecastDates: [],
  todayStr: TODAY,
  tomorrowStr: TOMORROW,
  astroAvailableDates: [],
  astroConditionsByDate: new Map(),
  auroraAvailableDates: [],
  auroraResultsByDate: new Map(),
  isLite: false,
  formatTimeUk: (v) => (v ? v.slice(11, 16) : null),
});

describe('buildMapEvents — ordering', () => {
  it('is chronological across days, sunrise before sunset within a day', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [
        solarWindow(TODAY, 'SUNSET'),
        solarWindow(TODAY, 'SUNRISE'),
        solarWindow(TOMORROW, 'SUNRISE'),
        solarWindow(TOMORROW, 'SUNSET'),
      ],
      forecastDates: [TODAY, TOMORROW],
    });
    expect(events.map((e) => `${e.date}:${e.eventType}`)).toEqual([
      `${TODAY}:SUNRISE`, `${TODAY}:SUNSET`, `${TOMORROW}:SUNRISE`, `${TOMORROW}:SUNSET`,
    ]);
  });

  it('sorts a night event AFTER that day\'s sunset — it happens later', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET')],
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 4, nightStart: `${TODAY}T21:45:00` }]]]),
    });
    expect(events.map((e) => e.eventType)).toEqual(['SUNRISE', 'SUNSET', 'ASTRO']);
  });

  it('places the NEXT day\'s sunrise immediately after tonight\'s astro row — no re-sort by kind (adversarial review, browser-pass #14)', () => {
    // The array itself is the stepper's whole contract (`WindowControl.test.jsx` proves the
    // steppers walk it verbatim) — so this pins the property one level down: tonight's night row
    // sits between today's sunset and TOMORROW's sunrise, never re-grouped so every night row
    // floats to one end of the list or every solar row sorts ahead of every night row.
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [
        solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET'),
        solarWindow(TOMORROW, 'SUNRISE'), solarWindow(TOMORROW, 'SUNSET'),
      ],
      forecastDates: [TODAY, TOMORROW],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 4, nightStart: `${TODAY}T21:45:00` }]]]),
    });
    expect(events.map((e) => `${e.date}:${e.eventType}`)).toEqual([
      `${TODAY}:SUNRISE`, `${TODAY}:SUNSET`, `${TODAY}:ASTRO`,
      `${TOMORROW}:SUNRISE`, `${TOMORROW}:SUNSET`,
    ]);
  });

  it('orders astro before aurora on the same night (the app\'s own question order)', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: `${TODAY}T21:45:00` }]]]),
      auroraAvailableDates: [TODAY],
      auroraResultsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 2, nightStart: `${TODAY}T21:45:00` }]]]),
    });
    const nightRows = events.filter((e) => e.kind !== EVENT_KIND.SOLAR);
    expect(nightRows.map((e) => e.kind)).toEqual([EVENT_KIND.ASTRO, EVENT_KIND.AURORA]);
  });
});

describe('buildMapEvents — aurora presence rule', () => {
  it('omits aurora entirely for a night with no stored results — "empty six nights in seven" (README)', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      auroraAvailableDates: [], // no results anywhere
    });
    expect(events.some((e) => e.kind === EVENT_KIND.AURORA)).toBe(false);
  });

  it('includes an aurora row only for the specific night results exist for', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY, TOMORROW],
      auroraAvailableDates: [TODAY],
      auroraResultsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: `${TODAY}T21:00:00` }]]]),
    });
    const auroraDates = events.filter((e) => e.kind === EVENT_KIND.AURORA).map((e) => e.date);
    expect(auroraDates).toEqual([TODAY]);
  });

  it('LITE accounts see no aurora rows at all, even when results exist', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      auroraAvailableDates: [TODAY],
      auroraResultsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 5, nightStart: `${TODAY}T21:00:00` }]]]),
      isLite: true,
    });
    expect(events.some((e) => e.kind === EVENT_KIND.AURORA)).toBe(false);
  });

  it('LITE accounts still see astro rows — the LITE restriction is aurora-only', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 4, nightStart: `${TODAY}T21:00:00` }]]]),
      isLite: true,
    });
    expect(events.some((e) => e.kind === EVENT_KIND.ASTRO)).toBe(true);
  });
});

describe('buildMapEvents — D-13 beyond-briefing solar rows', () => {
  it('adds unscored sunrise+sunset rows for a forecast date the briefing never rendered', () => {
    const FAR = '2026-09-06';
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET')],
      forecastDates: [TODAY, FAR],
    });
    const far = events.filter((e) => e.date === FAR);
    expect(far.map((e) => e.eventType)).toEqual(['SUNRISE', 'SUNSET']);
    expect(far.every((e) => e.scored === false && e.bestRating === null)).toBe(true);
  });

  it('never invents a solar row for a date outside forecastDates entirely', () => {
    // An astro-only admin backfill date with no colour forecast at all — the map's own domain
    // (forecastDates) must gate whether a solar row exists, not merely whether a night row does.
    const OUT_OF_RANGE = '2026-09-20';
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [OUT_OF_RANGE],
      astroConditionsByDate: new Map([[OUT_OF_RANGE, [{ locationName: 'A', stars: 3, nightStart: `${OUT_OF_RANGE}T21:00:00` }]]]),
    });
    expect(events.some((e) => e.kind === EVENT_KIND.SOLAR && e.date === OUT_OF_RANGE)).toBe(false);
    expect(events.some((e) => e.kind === EVENT_KIND.ASTRO && e.date === OUT_OF_RANGE)).toBe(true);
    // And that astro row records it is NOT in the forwardable domain.
    expect(events.find((e) => e.kind === EVENT_KIND.ASTRO).inForecastDomain).toBe(false);
  });

  it('marks a served (in-briefing) row inForecastDomain true when its date is in forecastDates', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET')],
      forecastDates: [TODAY],
    });
    expect(events[0].inForecastDomain).toBe(true);
  });

  it('marks a SERVED row inForecastDomain true even when forecastDates omits its date (adversarial review, minor #7 follow-up)', () => {
    // A window the briefing actually rendered is, definitionally, a real forecast date — the
    // briefing is built from `GET /api/forecast`'s own data. `forecastDates` is a separate prop
    // and the two can be out of sync (as this fixture deliberately is); gating a served row on
    // `forecastDates` membership alone would make some real, rendered windows non-forwardable.
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET')],
      forecastDates: [], // deliberately does NOT include TODAY
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.inForecastDomain).toBe(true);
  });

  it('does NOT extend that same leniency to night rows — results existing is not evidence of a colour forecast', () => {
    // The mirror of the test above: a night row's own presence must never be read as proof its
    // date has a colour forecast — that gap is exactly what the EV-ownership rule exists to name.
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [], // no colour forecast for TODAY at all
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: `${TODAY}T21:00:00` }]]]),
    });
    expect(events.find((e) => e.kind === EVENT_KIND.ASTRO).inForecastDomain).toBe(false);
  });
});

/**
 * Browser-pass finding: right after UK midnight, the live app led its EV list with "Tuesday
 * Sunrise / Sunset —" — a filler row for a date that had already elapsed, because `forecastDates`
 * can still carry yesterday's key for a tick after rollover. The clock is pinned here (never
 * let-the-wall-clock-in rule) and `todayStr`/`forecastDates` are both derived from the SAME frozen
 * instant via the production UK-calendar helpers (`mapDates.js`), so this proves the fix against
 * the real notion of "today" the app itself uses — not a hand-picked string that merely happens to
 * sort correctly.
 */
describe('buildMapEvents — D-13 filler clips to the UK civil today (browser-pass finding)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 00:30 UK on a January date — safely after UK midnight, GMT so no BST ambiguity.
    vi.setSystemTime(new Date('2026-01-15T00:30:00Z'));
  });
  afterEach(() => { vi.useRealTimers(); });

  it('yields NO row for a forecastDates entry before the UK civil today', () => {
    const today = ukDateStr();
    const yesterday = ukDateStrOffset(-1);
    const events = buildMapEvents({
      ...baseArgs(),
      todayStr: today,
      tomorrowStr: ukDateStrOffset(1),
      forecastDates: [yesterday, today],
    });
    expect(events.some((e) => e.date === yesterday)).toBe(false);
    expect(events.filter((e) => e.date === today)).toHaveLength(2); // sunrise + sunset filler
  });

  it('still renders a SERVED window for a past date — the gate is on the FILLER branch only', () => {
    // A served row is never gated by this: the briefing only ever renders current/future events,
    // so its presence is already evidence the date belongs on screen. (Not expected in production,
    // but the module must not invent a second reason to hide server-supplied data.)
    const today = ukDateStr();
    const yesterday = ukDateStrOffset(-1);
    const events = buildMapEvents({
      ...baseArgs(),
      todayStr: today,
      tomorrowStr: ukDateStrOffset(1),
      solarWindows: [solarWindow(yesterday, 'SUNSET')],
      forecastDates: [yesterday, today],
    });
    expect(events.some((e) => e.date === yesterday && e.eventType === 'SUNSET')).toBe(true);
  });

  it('renders today\'s own filler rows normally — the gate excludes only what is strictly earlier', () => {
    const today = ukDateStr();
    const events = buildMapEvents({
      ...baseArgs(), todayStr: today, tomorrowStr: ukDateStrOffset(1), forecastDates: [today],
    });
    expect(events.map((e) => e.eventType)).toEqual(['SUNRISE', 'SUNSET']);
  });
});

describe('buildMapEvents — served-vs-client-max discipline', () => {
  // Both fixtures below also carry a served SUNRISE window, so `forecastDates` including TODAY
  // does not silently add a D-13 filler row for the type under test — the SUNSET row's own index
  // is otherwise not stable, since an unserved SUNRISE would push it to position 1.
  it('a solar row\'s bestRating is the served figure verbatim, never recomputed', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET', { bestRating: 2 })],
      forecastDates: [TODAY],
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.bestRating).toBe(2);
    expect(sunset.scored).toBe(true);
  });

  it('a solar row with a served null bestRating is unscored, not defaulted to anything', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET', { bestRating: null })],
      forecastDates: [TODAY],
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.bestRating).toBeNull();
    expect(sunset.scored).toBe(false);
  });

  it('a night row takes the CLIENT MAX over that night\'s served stars — the one licensed re-derivation', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [
        { locationName: 'A', stars: 2, nightStart: `${TODAY}T21:00:00` },
        { locationName: 'B', stars: 4, nightStart: `${TODAY}T21:00:00` },
        { locationName: 'C', stars: 3, nightStart: `${TODAY}T21:00:00` },
      ]]]),
    });
    const astro = events.find((e) => e.kind === EVENT_KIND.ASTRO);
    expect(astro.bestRating).toBe(4);
    expect(astro.scored).toBe(true);
  });

  it('a night row with an empty result list is unscored', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, []]]),
    });
    const astro = events.find((e) => e.kind === EVENT_KIND.ASTRO);
    expect(astro.bestRating).toBeNull();
    expect(astro.scored).toBe(false);
  });

  it('the night-row max is always a whole star (never interpolated) — every input is itself an integer', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [
        { locationName: 'A', stars: 1, nightStart: `${TODAY}T21:00:00` },
        { locationName: 'B', stars: 5, nightStart: `${TODAY}T21:00:00` },
      ]]]),
    });
    const astro = events.find((e) => e.kind === EVENT_KIND.ASTRO);
    expect(Number.isInteger(astro.bestRating)).toBe(true);
    expect(astro.bestRating).toBe(5);
  });

  it('a non-finite served rating (a malformed row) degrades to unscored, not NaN', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET', { bestRating: Number.NaN })],
      forecastDates: [TODAY],
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.bestRating).toBeNull();
    expect(sunset.scored).toBe(false);
  });
});

describe('buildMapEvents — empty briefing', () => {
  it('returns an empty list when nothing is served and there is no forecast domain', () => {
    expect(buildMapEvents(baseArgs())).toEqual([]);
  });

  it('still builds D-13 rows off forecastDates alone, with no briefing data at all', () => {
    const events = buildMapEvents({ ...baseArgs(), forecastDates: [TODAY] });
    expect(events.map((e) => e.eventType)).toEqual(['SUNRISE', 'SUNSET']);
    expect(events.every((e) => e.scored === false)).toBe(true);
  });
});

describe('buildMapEvents — night labels', () => {
  it('names the same day "Tonight"', () => {
    expect(nightLabel(TODAY, TODAY, TOMORROW)).toBe('Tonight');
  });

  it('names tomorrow "Tomorrow night"', () => {
    expect(nightLabel(TOMORROW, TODAY, TOMORROW)).toBe('Tomorrow night');
  });

  it('names a further day "<Weekday> night"', () => {
    // 2026-09-05 is a Saturday.
    expect(nightLabel('2026-09-05', TODAY, TOMORROW)).toBe('Saturday night');
  });
});

/**
 * `dayLabel` — the design bundle's `dayOnly` rule (map-tab-v2.js ~:104): the kind chip already
 * reads SUNRISE/SUNSET, so the day text beside it must not repeat the word. `label` itself is
 * untouched, because the pin tooltip and the callout strip cell's `title` have no kind chip and
 * still need the full form.
 */
describe('buildMapEvents — dayLabel strips the trailing kind word (kind-chip dedup)', () => {
  it('strips a lead served label\'s trailing capitalised event word: "Tonight Sunset" -> "Tonight"', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET', { label: 'Tonight Sunset' })],
      forecastDates: [TODAY],
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.label).toBe('Tonight Sunset');
    expect(sunset.dayLabel).toBe('Tonight');
  });

  it('strips a non-lead served label\'s trailing lower-case event word: "Today sunrise" -> "Today"', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE', { label: 'Today sunrise' })],
      forecastDates: [TODAY],
    });
    const sunrise = events.find((e) => e.eventType === 'SUNRISE');
    expect(sunrise.label).toBe('Today sunrise');
    expect(sunrise.dayLabel).toBe('Today');
  });

  it('strips a D-13 filler row\'s own label the same way: "Thursday sunset" -> "Thursday"', () => {
    const FAR = '2026-09-10'; // a Thursday
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY, FAR],
    });
    const far = events.find((e) => e.date === FAR && e.eventType === 'SUNSET');
    expect(far.label).toBe('Thursday sunset');
    expect(far.dayLabel).toBe('Thursday');
  });

  it('a night row\'s dayLabel is identical to its label — no event word to strip', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 4, nightStart: `${TODAY}T21:45:00` }]]]),
    });
    const astro = events.find((e) => e.kind === EVENT_KIND.ASTRO);
    expect(astro.label).toBe('Tonight');
    expect(astro.dayLabel).toBe('Tonight');
  });

  it('falls back to the untouched label when stripping would leave nothing', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET', { label: 'Sunset' })],
      forecastDates: [TODAY],
    });
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.label).toBe('Sunset');
    expect(sunset.dayLabel).toBe('Sunset');
  });
});

/**
 * The successor to the deleted `DateStripToday.test.jsx` (adversarial review, browser-pass #15),
 * which pinned "Today"/"Tomorrow" chip labelling through the UK-civil small-hours window under
 * BST. `DateStrip` is gone; this module — specifically {@link nightLabel} and `buildMapEvents`'s
 * own D-13 filler labels — is the successor's label source (map-tab-v2-plan.md §3 P6). Both are
 * exercised here through the REAL `ukDateStr`/`ukDateStrOffset` (never a hand-picked string that
 * merely happens to sort correctly), under the exact instant the deleted suite used, so this
 * proves the fix against the app's own notion of "today" rather than a reimplementation of it.
 */
describe('buildMapEvents — "Today"/"Tomorrow" follow the UK civil date through BST (browser-pass #15)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 00:30 BST on 14 Aug — UTC still reads 13 Aug. The exact instant `DateStripToday.test.jsx`
    // used to catch the UTC-vs-UK-civil defect (measured: `2026-08-13T23:30:00Z` gave the UTC
    // date '2026-08-13' where the UK was already on the 14th).
    vi.setSystemTime(new Date('2026-08-13T23:30:00Z'));
  });
  afterEach(() => { vi.useRealTimers(); });

  it('nightLabel calls the UK-civil today "Tonight", not the UTC date', () => {
    const today = ukDateStr(); // '2026-08-14' — the UK date, one day ahead of raw UTC
    const tomorrow = ukDateStrOffset(1);
    expect(today).toBe('2026-08-14');
    expect(nightLabel(today, today, tomorrow)).toBe('Tonight');
  });

  it('nightLabel calls the UK-civil tomorrow "Tomorrow night"', () => {
    const today = ukDateStr();
    const tomorrow = ukDateStrOffset(1);
    expect(nightLabel(tomorrow, today, tomorrow)).toBe('Tomorrow night');
  });

  it('a D-13 filler row for the UK-civil today reads "Today sunrise"/"Today sunset", not the UTC date\'s label', () => {
    // Lower-case event word — matches `windowFirstStrip.js`'s own non-lead served form
    // (`${day} ${eventWord}`), so a filler row is not the only place on the map tab that
    // capitalises it.
    const today = ukDateStr();
    const tomorrow = ukDateStrOffset(1);
    const events = buildMapEvents({
      ...baseArgs(), todayStr: today, tomorrowStr: tomorrow, forecastDates: [today],
    });
    expect(events.map((e) => e.label)).toEqual(['Today sunrise', 'Today sunset']);
  });

  it('a D-13 filler row for the UK-civil tomorrow reads "Tomorrow sunrise"/"Tomorrow sunset"', () => {
    const today = ukDateStr();
    const tomorrow = ukDateStrOffset(1);
    const events = buildMapEvents({
      ...baseArgs(), todayStr: today, tomorrowStr: tomorrow, forecastDates: [tomorrow],
    });
    expect(events.map((e) => e.label)).toEqual(['Tomorrow sunrise', 'Tomorrow sunset']);
  });
});

describe('buildMapEvents — astro roster note', () => {
  it('carries a dark-sky-only note on astro rows, since the astro roster is bortle-enriched by construction', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: `${TODAY}T21:00:00` }]]]),
    });
    expect(events.find((e) => e.kind === EVENT_KIND.ASTRO).rosterNote).toMatch(/dark-sky/i);
  });

  it('carries no roster note on aurora rows', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      forecastDates: [TODAY],
      auroraAvailableDates: [TODAY],
      auroraResultsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 3, nightStart: `${TODAY}T21:00:00` }]]]),
    });
    expect(events.find((e) => e.kind === EVENT_KIND.AURORA).rosterNote).toBeNull();
  });
});

describe('findEvIndex', () => {
  it('finds the row matching kind and date', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET')],
      forecastDates: [TODAY],
    });
    expect(findEvIndex(events, 'SUNSET', TODAY)).toBe(1);
  });

  it('returns -1 when nothing matches', () => {
    const events = buildMapEvents({ ...baseArgs(), forecastDates: [TODAY] });
    expect(findEvIndex(events, 'AURORA', TODAY)).toBe(-1);
  });

  /**
   * Doors D2 (`plan-to-map-doors-plan.md` §7 #1) — the app's own form of the increment's check 1
   * ("each door lands on the correct window, one whose EV index differs from its Plan index").
   * The app has no Plan index to compare against at all (`§4 #2` — a door crosses the seam as
   * `{date, targetType}`, never an index), so the equivalent proof is that `findEvIndex` still
   * resolves the right ROW when an interleaved astro row has pushed it off the position a plain
   * date+kind walk would expect. With `ordering`'s own "places the NEXT day's sunrise immediately
   * after tonight's astro row" test as the layout: TODAY's astro row sits between TODAY's sunset
   * (index 2) and TOMORROW's sunrise (index 3), so tomorrow's SUNSET — the row this test resolves
   * — sits at index 4, not the index 3 a caller counting solar rows alone would land on.
   */
  it('resolves tomorrow\'s sunset to the row with id solar:<date>:SUNSET even with an astro row interleaved before it', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [
        solarWindow(TODAY, 'SUNRISE'), solarWindow(TODAY, 'SUNSET'),
        solarWindow(TOMORROW, 'SUNRISE'), solarWindow(TOMORROW, 'SUNSET'),
      ],
      forecastDates: [TODAY, TOMORROW],
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'A', stars: 4, nightStart: `${TODAY}T21:45:00` }]]]),
    });
    // The interleave really did move it — the premise, not incidental.
    const index = findEvIndex(events, 'SUNSET', TOMORROW);
    expect(index).toBe(4);
    expect(events[index].id).toBe(`solar:${TOMORROW}:SUNSET`);
  });
});

/**
 * PR #731 review: `MapView.jsx`'s astro/aurora multi-date fetch used to hand the raw available-
 * dates list straight to `Promise.all` — and those endpoints answer with every distinct date ever
 * persisted (writers replace a rerun date's row rather than pruning it), so a long-lived database
 * fanned a single Map-tab mount out to hundreds of concurrent requests. `solarHorizonDates` is
 * the bound: the SAME domain `buildMapEvents` derives its D-13 filler rows from.
 */
describe('solarHorizonDates', () => {
  it('includes forecastDates on/after today', () => {
    expect(solarHorizonDates({ forecastDates: [TODAY, TOMORROW], todayStr: TODAY }))
      .toEqual([TODAY, TOMORROW]);
  });

  it('excludes a forecastDates entry before today', () => {
    const YESTERDAY = '2026-09-01';
    expect(solarHorizonDates({ forecastDates: [YESTERDAY, TODAY], todayStr: TODAY }))
      .toEqual([TODAY]);
  });

  it('includes a served solar window\'s date even when forecastDates omits it', () => {
    expect(solarHorizonDates({
      solarWindows: [{ date: TOMORROW }], forecastDates: [], todayStr: TODAY,
    })).toEqual([TOMORROW]);
  });

  it('excludes a served solar window\'s date when it is before today', () => {
    const YESTERDAY = '2026-09-01';
    expect(solarHorizonDates({
      solarWindows: [{ date: YESTERDAY }], forecastDates: [], todayStr: TODAY,
    })).toEqual([]);
  });

  it('deduplicates a date served by both solarWindows and forecastDates', () => {
    expect(solarHorizonDates({
      solarWindows: [{ date: TODAY }], forecastDates: [TODAY], todayStr: TODAY,
    })).toEqual([TODAY]);
  });

  it('caps a large forecastDates list to only the today-forward handful — the fan-out bound', () => {
    // 200 historical dates plus a small forward horizon — the shape the real available-dates
    // endpoints return on a long-lived database (every distinct date ever persisted).
    const historical = Array.from({ length: 200 }, (_, i) => (
      ukDateStrOffset(-(i + 1), new Date(`${TODAY}T12:00:00Z`))
    ));
    const forward = [TODAY, TOMORROW];
    const horizon = solarHorizonDates({ forecastDates: [...historical, ...forward], todayStr: TODAY });
    expect(horizon).toEqual(forward);
  });

  it('returns nothing when neither input is supplied', () => {
    expect(solarHorizonDates({ todayStr: TODAY })).toEqual([]);
  });
});

