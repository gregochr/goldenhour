/**
 * Tests for the PAST solar windows of `utils/mapEvents.js` — the outcome-rating series' O3a
 * (docs/engineering/outcome-rating-plan.md §2 #2, §3 O3a steps 1–3).
 *
 * A solar window is `past` when the briefing serves NO window for it, it is in the forecast domain,
 * forecast rows exist for it, and its roster-earliest event time has elapsed by the afterglow rule
 * (`isEventTimePast`, the same instant and thirty minutes `PlanWindowProjector.hasPassed` withdraws
 * the served window on). The elapsed test is what keeps a future sunset from wearing PAST whenever
 * the briefing lags (Codex, #1185 round 6) — most cases below turn on it.
 *
 * The clock is injected, never read: `isEventTimePast` here is a fixed-"now" stand-in for
 * `briefingDisplay.isEventTimePast` with the same 30-minute allowance and strict comparison.
 */
import { describe, it, expect } from 'vitest';
import {
  buildMapEvents, buildPastSolarWindows, isForwardableRow, isPastSolarWindow, solarRowPredicate,
} from '../utils/mapEvents.js';
import { withScopedSolarTimes } from '../utils/mapScopedTime.js';

const TODAY = '2026-10-15';
const TOMORROW = '2026-10-16';
const YESTERDAY = '2026-10-14';
const TWO_AGO = '2026-10-13';

const AFTERGLOW_MS = 30 * 60 * 1000;
/** The app clock as a fixed instant; the same strict "more than the afterglow behind" test. */
const elapsedAt = (nowIso) => (eventTime) => (
  Boolean(eventTime) && new Date(`${eventTime}Z`).getTime() + AFTERGLOW_MS < new Date(nowIso).getTime()
);

const fmt = (v) => (v ? v.slice(11, 16) : null);

function served(date, targetType, overrides = {}) {
  return {
    key: `${date}:${targetType}`,
    date,
    targetType,
    label: `${date} ${targetType}`,
    time: targetType === 'SUNRISE' ? '07:30' : '18:10',
    bestRating: 4,
    confidenceTier: 'high',
    badges: [],
    ...overrides,
  };
}

function args(overrides = {}) {
  return {
    solarWindows: [],
    forecastDates: [],
    pastSolarWindows: [],
    todayStr: TODAY,
    tomorrowStr: TOMORROW,
    isEventTimePast: elapsedAt('2026-10-15T09:00:00Z'),
    formatTimeUk: fmt,
    ...overrides,
  };
}

const past = (date, targetType, earliestEventTime) => ({ date, targetType, earliestEventTime });

describe('isPastSolarWindow', () => {
  const base = {
    servedWindow: null,
    pastWindow: past(TODAY, 'SUNRISE', '2026-10-15T06:30:00'),
    inForecastDomain: true,
    date: TODAY,
    todayStr: TODAY,
    isEventTimePast: elapsedAt('2026-10-15T09:00:00Z'),
  };

  it('is past when unserved, in the domain, with rows and an elapsed earliest time', () => {
    expect(isPastSolarWindow(base)).toBe(true);
  });

  it('is never past while the briefing serves the window', () => {
    expect(isPastSolarWindow({ ...base, servedWindow: { date: TODAY } })).toBe(false);
  });

  it('is never past outside the forecast domain', () => {
    expect(isPastSolarWindow({ ...base, inForecastDomain: false })).toBe(false);
  });

  it('is never past with no forecast rows for the window', () => {
    expect(isPastSolarWindow({ ...base, pastWindow: null })).toBe(false);
  });

  it('is never past on a future date, whatever the clock says', () => {
    expect(isPastSolarWindow({ ...base, date: TOMORROW, isEventTimePast: () => true })).toBe(false);
  });

  it('a date before today passes the elapsed test trivially, even with no event time at all', () => {
    expect(isPastSolarWindow({
      ...base, date: YESTERDAY, pastWindow: past(YESTERDAY, 'SUNSET', null), isEventTimePast: () => false,
    })).toBe(true);
  });

  it('today with no known event time is NOT past — nothing stands in for a time', () => {
    expect(isPastSolarWindow({ ...base, pastWindow: past(TODAY, 'SUNRISE', null) })).toBe(false);
  });

  it('turns on the afterglow: one minute short is not past, one minute over is', () => {
    // Earliest 06:30; the afterglow ends 07:00 exactly. `hasPassed` is strict: AT 07:00 it has not
    // passed, at 07:01 it has.
    const at = (nowIso) => isPastSolarWindow({ ...base, isEventTimePast: elapsedAt(nowIso) });
    expect(at('2026-10-15T06:59:00Z')).toBe(false);
    expect(at('2026-10-15T07:00:00Z')).toBe(false);
    expect(at('2026-10-15T07:01:00Z')).toBe(true);
  });
});

describe('buildMapEvents — past rows', () => {
  it('emits a past row for T-1 and T-2 with the pastSolarWindows, each selectable', () => {
    const events = buildMapEvents(args({
      forecastDates: [TWO_AGO, YESTERDAY, TODAY],
      solarWindows: [served(TODAY, 'SUNSET')],
      pastSolarWindows: [
        past(TWO_AGO, 'SUNRISE', '2026-10-13T06:20:00'),
        past(TWO_AGO, 'SUNSET', '2026-10-13T17:20:00'),
        past(YESTERDAY, 'SUNRISE', '2026-10-14T06:22:00'),
        past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00'),
        past(TODAY, 'SUNRISE', '2026-10-15T06:24:00'),
      ],
    }));

    expect(events.map((e) => `${e.date}:${e.eventType}:${e.past === true}`)).toEqual([
      `${TWO_AGO}:SUNRISE:true`, `${TWO_AGO}:SUNSET:true`,
      `${YESTERDAY}:SUNRISE:true`, `${YESTERDAY}:SUNSET:true`,
      `${TODAY}:SUNRISE:true`, `${TODAY}:SUNSET:false`,
    ]);
  });

  it('shapes a past row: unserved, unscored, in the domain, no pick, no badges, past labels', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNRISE', '2026-10-14T06:22:00')],
    }));

    expect(row).toMatchObject({
      id: `solar:${YESTERDAY}:SUNRISE`,
      kind: 'solar',
      eventType: 'SUNRISE',
      date: YESTERDAY,
      past: true,
      served: false,
      scored: false,
      inForecastDomain: true,
      bestRating: null,
      badges: [],
      pickKind: null,
      label: 'Yesterday sunrise',
      dayLabel: 'Yesterday',
      time: '06:22',
    });
  });

  it('labels with the past vocabulary: Today, Yesterday, then a short weekday and day', () => {
    const events = buildMapEvents(args({
      forecastDates: [TWO_AGO, YESTERDAY, TODAY],
      pastSolarWindows: [
        past(TWO_AGO, 'SUNSET', '2026-10-13T17:20:00'),
        past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00'),
        past(TODAY, 'SUNRISE', '2026-10-15T06:24:00'),
      ],
    }));
    expect(events.filter((e) => e.past).map((e) => e.dayLabel)).toEqual(['Tue 13', 'Yesterday', 'Today']);
  });

  it('takes the base time from the earliest event time even with no briefing at all', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00')],
    }));
    expect(row.time).toBe('17:18');
  });

  it('leaves the time blank when no row carried one — never guessed', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', null)],
    }));
    expect(row.past).toBe(true);
    expect(row.time).toBe('');
  });

  it('served → not past, even with a past entry and an elapsed time', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [TODAY],
      solarWindows: [served(TODAY, 'SUNRISE')],
      pastSolarWindows: [past(TODAY, 'SUNRISE', '2026-10-15T06:24:00')],
    }));
    expect(row.past).toBeUndefined();
    expect(row.served).toBe(true);
  });

  it('unserved today with the earliest time one minute short of the afterglow → a filler, NOT past', () => {
    // 06:24 + 30 min = 06:54; at 06:54:00 exactly the window has not passed (strict).
    const [row] = buildMapEvents(args({
      forecastDates: [TODAY],
      pastSolarWindows: [past(TODAY, 'SUNRISE', '2026-10-15T06:24:00')],
      isEventTimePast: elapsedAt('2026-10-15T06:54:00Z'),
    }));
    expect(row.past).toBeUndefined();
    expect(row.served).toBe(false);
    expect(row.label).toBe('Today sunrise');
  });

  it('…and one minute over → past', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [TODAY],
      pastSolarWindows: [past(TODAY, 'SUNRISE', '2026-10-15T06:24:00')],
      isEventTimePast: elapsedAt('2026-10-15T06:55:00Z'),
    }));
    expect(row.past).toBe(true);
  });

  it('unserved with NO briefing and a future time → a filler, never past (Codex #1185 r6)', () => {
    const events = buildMapEvents(args({
      forecastDates: [TODAY],
      solarWindows: [],
      pastSolarWindows: [past(TODAY, 'SUNSET', '2026-10-15T17:10:00')],
      isEventTimePast: elapsedAt('2026-10-15T09:00:00Z'),
    }));
    const sunset = events.find((e) => e.eventType === 'SUNSET');
    expect(sunset.past).toBeUndefined();
    expect(sunset.served).toBe(false);
  });

  it('a date with rows but date > today stays a plain filler (past entries never reach the future)', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [TOMORROW],
      pastSolarWindows: [past(TOMORROW, 'SUNRISE', '2026-10-16T06:26:00')],
      isEventTimePast: () => true,
    }));
    expect(row.past).toBeUndefined();
    expect(row.served).toBe(false);
  });

  it('offers no row for a past date with no entry for that event', () => {
    // T-1 has sunset rows only: its sunrise is not a row (nothing was forecast for it).
    const events = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00')],
    }));
    expect(events.map((e) => e.eventType)).toEqual(['SUNSET']);
  });

  it('offers no past row for a date outside the forecast domain', () => {
    expect(buildMapEvents(args({
      forecastDates: [],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00')],
    }))).toEqual([]);
  });

  it('keeps ordering: by date, sunrise before sunset, past before served', () => {
    const events = buildMapEvents(args({
      forecastDates: [YESTERDAY, TODAY, TOMORROW],
      solarWindows: [served(TOMORROW, 'SUNRISE'), served(TODAY, 'SUNSET')],
      pastSolarWindows: [
        past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00'),
        past(YESTERDAY, 'SUNRISE', '2026-10-14T06:22:00'),
        past(TODAY, 'SUNRISE', '2026-10-15T06:24:00'),
      ],
    }));
    expect(events.map((e) => `${e.date}:${e.eventType}`)).toEqual([
      `${YESTERDAY}:SUNRISE`, `${YESTERDAY}:SUNSET`, `${TODAY}:SUNRISE`, `${TODAY}:SUNSET`,
      `${TOMORROW}:SUNRISE`, `${TOMORROW}:SUNSET`,
    ]);
  });

  it('is unchanged when no pastSolarWindows are supplied (the input is optional)', () => {
    const events = buildMapEvents({
      solarWindows: [served(TODAY, 'SUNSET')],
      forecastDates: [YESTERDAY, TODAY],
      todayStr: TODAY,
      tomorrowStr: TOMORROW,
      formatTimeUk: fmt,
    });
    expect(events.map((e) => `${e.date}:${e.eventType}`)).toEqual([
      `${TODAY}:SUNRISE`, `${TODAY}:SUNSET`,
    ]);
    expect(events.some((e) => e.past)).toBe(false);
  });
});

describe('a window flagged past in heat.windows is not SERVED', () => {
  // O3c lists past windows in `heat.windows` for the field; "served" must keep meaning "the briefing
  // rendered it", or every past window would turn into an unlabelled served row.
  const pastEntry = { key: `${YESTERDAY}:SUNSET`, date: YESTERDAY, targetType: 'SUNSET', past: true, bestRating: 4 };

  it('buildMapEvents still builds the past row, not a served one', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      solarWindows: [pastEntry],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00')],
    }));
    expect(row).toMatchObject({ past: true, served: false, label: 'Yesterday sunset' });
  });

  it('solarRowPredicate does not count it as served either (the date still needs a past entry)', () => {
    expect(solarRowPredicate({
      solarWindows: [pastEntry], forecastDates: [YESTERDAY, TODAY], todayStr: TODAY,
    })(YESTERDAY, 'SUNSET')).toBe(false);
  });
});

describe('isForwardableRow — a past row is forwarded', () => {
  const opts = { todayStr: TODAY, currentNightDate: null };

  it('forwards a past solar row', () => {
    const [row] = buildMapEvents(args({
      forecastDates: [YESTERDAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:18:00')],
    }));
    expect(isForwardableRow(row, opts)).toBe(true);
  });

  it('still keeps a non-past solar row dated before today local', () => {
    expect(isForwardableRow({
      kind: 'solar', date: YESTERDAY, inForecastDomain: true,
    }, opts)).toBe(false);
  });

  it('a past row outside the forecast domain is still not forwarded', () => {
    expect(isForwardableRow({
      kind: 'solar', date: YESTERDAY, past: true, inForecastDomain: false,
    }, opts)).toBe(false);
  });
});

describe('solarRowPredicate — past windows make a row', () => {
  it('a date before today is a row only with a past entry for that event', () => {
    const predicate = solarRowPredicate({
      forecastDates: [YESTERDAY, TODAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', null)],
      todayStr: TODAY,
    });
    expect(predicate(YESTERDAY, 'SUNSET')).toBe(true);
    expect(predicate(YESTERDAY, 'SUNRISE')).toBe(false);
  });

  it('without the entry a past date still has no row — the old gate, unchanged', () => {
    const predicate = solarRowPredicate({ forecastDates: [YESTERDAY, TODAY], todayStr: TODAY });
    expect(predicate(YESTERDAY, 'SUNSET')).toBe(false);
  });

  it('an entry outside the forecast domain makes no row', () => {
    const predicate = solarRowPredicate({
      forecastDates: [TODAY],
      pastSolarWindows: [past(YESTERDAY, 'SUNSET', null)],
      todayStr: TODAY,
    });
    expect(predicate(YESTERDAY, 'SUNSET')).toBe(false);
  });

  it('agrees with buildMapEvents about which past windows are rows', () => {
    const pastSolarWindows = [
      past(TWO_AGO, 'SUNSET', '2026-10-13T17:20:00'), past(YESTERDAY, 'SUNRISE', null),
    ];
    const forecastDates = [TWO_AGO, YESTERDAY, TODAY];
    const predicate = solarRowPredicate({ forecastDates, pastSolarWindows, todayStr: TODAY });
    const rows = buildMapEvents(args({ forecastDates, pastSolarWindows }));
    for (const date of forecastDates.filter((d) => d < TODAY)) {
      for (const type of ['SUNRISE', 'SUNSET']) {
        expect(predicate(date, type)).toBe(rows.some((r) => r.date === date && r.eventType === type));
      }
    }
  });
});

describe('buildPastSolarWindows — the pane\'s input', () => {
  const loc = (entries) => ({ forecastsByDate: new Map(Object.entries(entries)) });

  it('lists each (date, event) with rows on or before today, earliest solar time among its rows', () => {
    const result = buildPastSolarWindows([
      loc({
        [YESTERDAY]: {
          sunrise: { solarEventTime: '2026-10-14T06:30:00' },
          sunset: { solarEventTime: '2026-10-14T17:20:00' },
        },
        [TODAY]: { sunrise: { solarEventTime: '2026-10-15T06:31:00' } },
      }),
      loc({
        [YESTERDAY]: { sunrise: { solarEventTime: '2026-10-14T06:22:00' } },
      }),
    ], TODAY);

    expect(result).toEqual([
      past(YESTERDAY, 'SUNRISE', '2026-10-14T06:22:00'),
      past(YESTERDAY, 'SUNSET', '2026-10-14T17:20:00'),
      past(TODAY, 'SUNRISE', '2026-10-15T06:31:00'),
    ]);
  });

  it('excludes future dates and a hide whose entry holds only hourly rows', () => {
    const result = buildPastSolarWindows([
      loc({ [TOMORROW]: { sunrise: { solarEventTime: '2026-10-16T06:33:00' } } }),
      loc({ [YESTERDAY]: { hourly: [{ solarEventTime: '2026-10-14T10:00:00' }] } }),
    ], TODAY);
    expect(result).toEqual([]);
  });

  it('records a window whose rows carry no time with a null earliest time', () => {
    expect(buildPastSolarWindows([
      loc({ [YESTERDAY]: { sunset: { rating: 3 } } }),
    ], TODAY)).toEqual([past(YESTERDAY, 'SUNSET', null)]);
  });

  it('tolerates a missing roster and a location with no forecast map', () => {
    expect(buildPastSolarWindows(undefined, TODAY)).toEqual([]);
    expect(buildPastSolarWindows([{}, { forecastsByDate: null }], TODAY)).toEqual([]);
  });
});

describe('withScopedSolarTimes — a past row\'s time narrows to the scope', () => {
  const locations = [
    { id: 1, name: 'North', forecastsByDate: new Map([[YESTERDAY, { sunset: { solarEventTime: '2026-10-14T17:08:00' } }]]) },
    { id: 2, name: 'South', forecastsByDate: new Map([[YESTERDAY, { sunset: { solarEventTime: '2026-10-14T17:21:00' } }]]) },
  ];
  const [row] = buildMapEvents(args({
    forecastDates: [YESTERDAY],
    pastSolarWindows: [past(YESTERDAY, 'SUNSET', '2026-10-14T17:08:00')],
  }));

  it('narrows to the scope pool\'s earliest, as for a served row', () => {
    const [narrowed] = withScopedSolarTimes({
      events: [row], spots: [{ id: 2, name: 'South' }], locations, formatTimeUk: fmt,
    });
    expect(narrowed.time).toBe('17:21');
    expect(narrowed.past).toBe(true);
  });

  it('leaves the base time when the scope pool is empty', () => {
    const [unchanged] = withScopedSolarTimes({ events: [row], spots: [], locations, formatTimeUk: fmt });
    expect(unchanged).toBe(row);
    expect(unchanged.time).toBe('17:08');
  });

  it('control: a non-past D-13 filler still keeps its lent time', () => {
    const [filler] = buildMapEvents(args({ forecastDates: [TOMORROW] }));
    const [same] = withScopedSolarTimes({
      events: [filler], spots: [{ id: 2, name: 'South' }], locations, formatTimeUk: fmt,
    });
    expect(same).toBe(filler);
  });
});
