/**
 * Tests for `utils/mapEvents.js` — the Map tab's single chronological event list
 * (map-tab-v2-plan.md §3 P6).
 *
 * Covers: chronological ordering with night-after-sunset, the aurora presence rule (rows only
 * where results exist) and its LITE absence, D-13 beyond-briefing solar rows, D-14's clip of every
 * night that is over, the served-vs-client-max discipline (solar never re-derives; night rows take a
 * licensed client max), and the empty-briefing degrade.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  buildMapEvents, findEvIndex, isForwardableRow, isNightOffered, nightLabel, nightPreviewDates, EVENT_KIND,
  solarHorizonDates, solarRowPredicate,
} from '../utils/mapEvents.js';
import { ukDateStr, ukDateStrOffset, resolveMapDate } from '../utils/mapDates.js';

const TODAY = '2026-09-02';
const TOMORROW = '2026-09-03';

/**
 * A served solar window, in the shape `WindowFirstMapPane`'s `heat.windows` builds.
 *
 * Any key not defaulted below is spread through verbatim, so a caller can supply the fields the
 * pane forwards without this helper needing a line per field — the verdict channel
 * (`verdict`/`verdictLabel`/`regionName`/`pick`, map-landing-plan.md §3 L1) arrives that way.
 * Spread LAST so an explicit override always wins over a default.
 */
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
    ...overrides,
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

  it('marks a filler row NOT served, and a briefing row served — the pastness gate\'s own field', () => {
    // ⚠️ `served` is what the landing card gates on, and it is a different question from `scored`.
    // The briefing withdraws an ELAPSED window (`PlanWindowProjector.hasPassed`); the filler branch
    // is gated only on `date >= todayStr`, so from this morning's sunrise until midnight the list
    // still leads with a filler SUNRISE row for a window hours in the past. "First two solar rows"
    // would open the card on a window the reader cannot reach.
    const FAR = '2026-09-06';
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET')],
      forecastDates: [TODAY, FAR],
    });

    expect(events.find((e) => e.date === TODAY && e.eventType === 'SUNSET').served).toBe(true);
    expect(events.filter((e) => e.date === FAR).every((e) => e.served === false)).toBe(true);
    // A filler is a date the briefing carried no window for at all, so it carries no away state.
    expect(events.filter((e) => e.date === FAR).every((e) => e.away === false)).toBe(true);
  });

  it('carries a served window\'s TRAVEL flag through, never inventing one', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      solarWindows: [
        { ...solarWindow(TODAY, 'SUNRISE'), away: true },
        solarWindow(TODAY, 'SUNSET'),
      ],
      forecastDates: [TODAY],
    });

    expect(events.find((e) => e.eventType === 'SUNRISE').away).toBe(true);
    expect(events.find((e) => e.eventType === 'SUNSET').away).toBe(false);
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

/**
 * D-14 — a night that is over is not a row (map-tab-v2-plan.md §5; owner decision, 2026-09-14).
 *
 * <p>The astro and aurora available-date endpoints answer with every night ever stored and nothing
 * prunes either table, so the unclipped list opened on the whole history: weekday-only labels with
 * no month, a "—" best, and a `‹` that walked back into it. Every case names the night in progress
 * explicitly, because yesterday is the one date the calendar cannot settle — between UK midnight and
 * dawn it IS the night in progress.
 */
describe('buildMapEvents — D-14: a night that is over is not a row', () => {
  const YESTERDAY = '2026-09-01';
  const OLDER = '2026-04-12';
  const nightIds = (events) => events.filter((e) => e.kind !== EVENT_KIND.SOLAR).map((e) => e.id);

  it('offers tonight and every later night, astro and aurora alike', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: TODAY,
      astroAvailableDates: [TODAY, TOMORROW],
      auroraAvailableDates: [TODAY, TOMORROW],
    });
    expect(nightIds(events)).toEqual([
      `astro:${TODAY}:ASTRO`, `aur:${TODAY}:AURORA`, `astro:${TOMORROW}:ASTRO`, `aur:${TOMORROW}:AURORA`,
    ]);
  });

  it('drops yesterday\'s night once the night in progress has moved on to tonight — after dawn', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: TODAY,
      astroAvailableDates: [YESTERDAY, TODAY],
      auroraAvailableDates: [YESTERDAY, TODAY],
    });
    expect(nightIds(events)).toEqual([`astro:${TODAY}:ASTRO`, `aur:${TODAY}:AURORA`]);
  });

  it('keeps yesterday\'s night while it IS the night in progress — UK midnight to dawn', () => {
    // The one past date a night row may carry: the dark window that opened at yesterday's dusk is
    // still running, and the backend's `currentNightDate` is what says so.
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: YESTERDAY,
      astroAvailableDates: [YESTERDAY, TODAY],
      auroraAvailableDates: [YESTERDAY, TODAY],
    });
    expect(nightIds(events)).toEqual([
      `astro:${YESTERDAY}:ASTRO`, `aur:${YESTERDAY}:AURORA`, `astro:${TODAY}:ASTRO`, `aur:${TODAY}:AURORA`,
    ]);
  });

  it('drops anything older than yesterday while the night in progress is yesterday', () => {
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: YESTERDAY,
      astroAvailableDates: [OLDER, YESTERDAY],
      auroraAvailableDates: [OLDER],
    });
    expect(nightIds(events)).toEqual([`astro:${YESTERDAY}:ASTRO`]);
  });

  it('drops a night a STALE night in progress still names — only yesterday can be in progress', () => {
    // The status provider keeps the last status when a later fetch fails, so `currentNightDate` can
    // name a night days old. Believed, that night came back to the head of the list. The backend
    // only ever names today or yesterday, and `mapDates.isNightOver` now holds it to that.
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: OLDER,
      astroAvailableDates: [OLDER, YESTERDAY, TODAY],
      auroraAvailableDates: [OLDER],
    });
    expect(nightIds(events)).toEqual([`astro:${TODAY}:ASTRO`]);
  });

  it('judges by the calendar when no night in progress is named — yesterday is over from UK midnight', () => {
    // LITE's case: aurora status, which carries `currentNightDate`, is PRO/ADMIN-only, so there is
    // nothing to say the night is still running. Omitted outright, as a caller predating the
    // argument would; the calendar then drops yesterday's night even in the small hours.
    const events = buildMapEvents({ ...baseArgs(), astroAvailableDates: [YESTERDAY, TODAY] });
    expect(nightIds(events)).toEqual([`astro:${TODAY}:ASTRO`]);
  });

  it('reduces a stored history of 200 nights to the nights not yet over — the production shape', () => {
    const history = Array.from({ length: 200 }, (_, i) => (
      ukDateStrOffset(-(i + 1), new Date(`${TODAY}T12:00:00Z`))
    ));
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: TODAY,
      forecastDates: [TODAY, TOMORROW],
      astroAvailableDates: [...history, TODAY, TOMORROW],
      auroraAvailableDates: [...history, TODAY],
    });
    // The list opens on today's first window rather than on four hundred rows of history, and a
    // past date contributes no row of any kind.
    expect(events[0].id).toBe(`solar:${TODAY}:SUNRISE`);
    expect(events.filter((e) => e.date < TODAY)).toEqual([]);
    expect(nightIds(events)).toEqual([`astro:${TODAY}:ASTRO`, `aur:${TODAY}:AURORA`, `astro:${TOMORROW}:ASTRO`]);
  });

  it('leaves solar rows alone — a served window survives on a date whose night does not', () => {
    // The clip is on night rows only. A served window is a row unconditionally (the D-13 block
    // above), and this date's stored night must not take it down with it.
    const events = buildMapEvents({
      ...baseArgs(),
      currentNightDate: TODAY,
      solarWindows: [solarWindow(YESTERDAY, 'SUNSET')],
      astroAvailableDates: [YESTERDAY],
    });
    expect(events.map((e) => e.id)).toEqual([`solar:${YESTERDAY}:SUNSET`]);
  });

  it('agrees with resolveMapDate on which nights are over — one answer, `mapDates.isNightOver`', () => {
    // Scoped to what the list and the parent share: whether a night is over. It is NOT a claim that
    // the list never offers a night the parent would refuse — the parent also requires the date to be
    // a forecast date, and D-14's exception offers the ended night on screen; each of those is kept
    // local by the forwarding rule instead (next test). Every date is put in the domain here so that
    // only the night question is asked. Driven through BOTH functions from one set of inputs.
    for (const nightDate of [null, OLDER, YESTERDAY, TODAY]) {
      for (const date of [OLDER, YESTERDAY, TODAY, TOMORROW]) {
        const offered = buildMapEvents({
          ...baseArgs(), currentNightDate: nightDate, astroAvailableDates: [date],
        }).some((e) => e.kind === EVENT_KIND.ASTRO);
        const accepted = resolveMapDate({
          selectedDate: date,
          selectedIsNight: true,
          autoDate: null,
          allDates: [...new Set([date, TODAY])].sort(),
          todayStr: TODAY,
          nightDate,
        }) === date;
        expect({ nightDate, date, offered }).toEqual({ nightDate, date, offered: accepted });
      }
    }
  });

  it('forwards a row to the parent exactly when the parent would accept it — every other row stays local', () => {
    // The guard against #803's "row that goes nowhere": `isForwardableRow` is `resolveMapDate`'s
    // acceptance rule stated on a row, so no row is handed over that the parent then declines.
    // Built over every kind of row the list can offer — an in-domain night, an out-of-domain night,
    // the night in progress, D-14's ended night on screen, and solar fillers — in every state of the
    // night in progress, and asked of both functions from one set of inputs.
    const NEXT_WEEK = '2026-09-09';
    const forecastDates = [YESTERDAY, TODAY, TOMORROW];
    for (const currentNightDate of [null, YESTERDAY, TODAY]) {
      const events = buildMapEvents({
        ...baseArgs(),
        forecastDates,
        currentNightDate,
        astroAvailableDates: [YESTERDAY, TODAY, NEXT_WEEK],
        nightOnScreen: { eventType: 'ASTRO', date: YESTERDAY },
      });
      expect(events.some((e) => e.id === `astro:${YESTERDAY}:ASTRO`)).toBe(true);
      for (const row of events) {
        const forwarded = isForwardableRow(row, { todayStr: TODAY, currentNightDate });
        const accepted = resolveMapDate({
          selectedDate: row.date,
          selectedIsNight: row.kind !== EVENT_KIND.SOLAR,
          autoDate: null,
          allDates: forecastDates,
          todayStr: TODAY,
          nightDate: currentNightDate,
        }) === row.date;
        expect({ currentNightDate, id: row.id, forwarded }).toEqual({ currentNightDate, id: row.id, forwarded: accepted });
      }
    }
  });

  describe('the one exception — the night the map is already showing', () => {
    it('keeps its row once it is over, so the pill can still name what the map is painting', () => {
      const events = buildMapEvents({
        ...baseArgs(),
        currentNightDate: TODAY,
        astroAvailableDates: [YESTERDAY, TODAY],
        nightOnScreen: { eventType: 'ASTRO', date: YESTERDAY },
      });
      expect(nightIds(events)).toEqual([`astro:${YESTERDAY}:ASTRO`, `astro:${TODAY}:ASTRO`]);
    });

    it('keeps that night alone even when a LATER ended night is stored — a match, not a range', () => {
      // The mirror of the next case, with the non-member on the other side of the one on screen: an
      // equality relaxed to `date >= onScreen` survived every other fixture here, because each one
      // stored only nights older than the night on screen.
      const events = buildMapEvents({
        ...baseArgs(),
        currentNightDate: TODAY,
        astroAvailableDates: [OLDER, YESTERDAY],
        nightOnScreen: { eventType: 'ASTRO', date: OLDER },
      });
      expect(nightIds(events)).toEqual([`astro:${OLDER}:ASTRO`]);
    });

    it('keeps that night alone — another night just as over is still dropped', () => {
      const events = buildMapEvents({
        ...baseArgs(),
        currentNightDate: TODAY,
        astroAvailableDates: [OLDER, YESTERDAY],
        nightOnScreen: { eventType: 'ASTRO', date: YESTERDAY },
      });
      expect(nightIds(events)).toEqual([`astro:${YESTERDAY}:ASTRO`]);
    });

    it('keeps that kind alone — an aurora night on screen keeps no astro row for its date', () => {
      const events = buildMapEvents({
        ...baseArgs(),
        currentNightDate: TODAY,
        astroAvailableDates: [YESTERDAY],
        auroraAvailableDates: [YESTERDAY],
        nightOnScreen: { eventType: 'AURORA', date: YESTERDAY },
      });
      expect(nightIds(events)).toEqual([`aur:${YESTERDAY}:AURORA`]);
    });

    it('cannot invent a row for a night with nothing stored', () => {
      const events = buildMapEvents({
        ...baseArgs(),
        currentNightDate: TODAY,
        astroAvailableDates: [TODAY],
        nightOnScreen: { eventType: 'ASTRO', date: YESTERDAY },
      });
      expect(nightIds(events)).toEqual([`astro:${TODAY}:ASTRO`]);
    });

    it('cannot give LITE an aurora row — it keeps rows, it does not lift the role rule', () => {
      const events = buildMapEvents({
        ...baseArgs(),
        isLite: true,
        currentNightDate: TODAY,
        auroraAvailableDates: [YESTERDAY, TODAY],
        nightOnScreen: { eventType: 'AURORA', date: YESTERDAY },
      });
      expect(nightIds(events)).toEqual([]);
    });

    it('is what lets findEvIndex find the ended night, where it would otherwise report no row', () => {
      // `MapView` derives the active row with `findEvIndex(events, eventType, nightDate)`, and -1 is
      // what puts "No forecast" on the pill.
      const args = { ...baseArgs(), currentNightDate: TODAY, astroAvailableDates: [YESTERDAY, TODAY] };
      expect(findEvIndex(buildMapEvents(args), 'ASTRO', YESTERDAY)).toBe(-1);
      const kept = buildMapEvents({ ...args, nightOnScreen: { eventType: 'ASTRO', date: YESTERDAY } });
      expect(findEvIndex(kept, 'ASTRO', YESTERDAY)).toBe(0);
    });
  });
});

/**
 * `isNightOffered` — the list's own membership rule, exported so `MapView`'s preview fetch covers
 * exactly the past-dated rows the list offers. Asked here of the same inputs as `buildMapEvents`,
 * so a preview built on it cannot drift from the rows it exists to fill.
 */
describe('isNightOffered — the rule the list and the preview fetch share', () => {
  const YESTERDAY = '2026-09-01';
  const OLDER = '2026-04-12';

  it('answers exactly as the list does, for every night and every night in progress', () => {
    for (const currentNightDate of [null, OLDER, YESTERDAY, TODAY]) {
      for (const nightOnScreen of [null, { eventType: 'ASTRO', date: OLDER }]) {
        for (const date of [OLDER, YESTERDAY, TODAY, TOMORROW]) {
          const inList = buildMapEvents({
            ...baseArgs(), currentNightDate, nightOnScreen, astroAvailableDates: [date],
          }).some((e) => e.kind === EVENT_KIND.ASTRO);
          const offered = isNightOffered('ASTRO', date, { todayStr: TODAY, currentNightDate, nightOnScreen });
          expect({ currentNightDate, date, offered }).toEqual({ currentNightDate, date, offered: inList });
        }
      }
    }
  });
});

/**
 * `isForwardableRow` — which picked rows `MapView` hands to `App` and which it keeps local. The
 * agreement with `resolveMapDate` over every row the list can build is the D-14 block's; these pin
 * each arm on its own, so a failure names the arm.
 */
describe('isForwardableRow — the EV-ownership forwarding rule', () => {
  const YESTERDAY = '2026-09-01';
  const night = (date, overrides = {}) => ({
    id: `astro:${date}:ASTRO`, kind: EVENT_KIND.ASTRO, eventType: 'ASTRO', date, inForecastDomain: true, ...overrides,
  });
  const solar = (date, overrides = {}) => ({
    id: `solar:${date}:SUNSET`, kind: EVENT_KIND.SOLAR, eventType: 'SUNSET', date, inForecastDomain: true, ...overrides,
  });

  it('forwards the night in progress — App takes a past date that names one', () => {
    expect(isForwardableRow(night(YESTERDAY), { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(true);
  });

  it('keeps a night local once it is over — App would refuse it', () => {
    expect(isForwardableRow(night(YESTERDAY), { todayStr: TODAY, currentNightDate: TODAY })).toBe(false);
  });

  it('keeps a night local when no night in progress is known — the calendar degrade', () => {
    expect(isForwardableRow(night(YESTERDAY), { todayStr: TODAY })).toBe(false);
  });

  it('forwards tonight', () => {
    expect(isForwardableRow(night(TODAY), { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(true);
  });

  it('keeps a night local when its date is not a forecast date, however current', () => {
    expect(isForwardableRow(night(TOMORROW, { inForecastDomain: false }), { todayStr: TODAY })).toBe(false);
  });

  it('judges a SOLAR row by the calendar alone — a night in progress licenses no solar date', () => {
    // The night arm must not leak into the solar one: yesterday's SUNSET is over whatever the night
    // in progress is, which is `resolveMapDate`'s provenance rule for a solar choice.
    expect(isForwardableRow(solar(YESTERDAY), { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(false);
    expect(isForwardableRow(solar(TODAY), { todayStr: TODAY, currentNightDate: YESTERDAY })).toBe(true);
  });

  it('forwards nothing for a missing row', () => {
    expect(isForwardableRow(null, { todayStr: TODAY })).toBe(false);
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

describe('buildMapEvents — the served pick (map-landing-plan.md §3 L1)', () => {
  it('copies the served pick kind onto a solar row', () => {
    const [row] = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET', { pickKind: 'best' })],
    });

    expect(row.pickKind).toBe('best');
  });

  it('nulls it on a served window that is neither pick — the normal case', () => {
    const [row] = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET')],
    });

    expect(row.pickKind).toBeNull();
  });

  it('nulls it on a D-13 filler row, which had no served window to carry one', () => {
    const rows = buildMapEvents({ ...baseArgs(), forecastDates: [TODAY] });

    expect(rows).toHaveLength(2);
    for (const row of rows) {
      expect(row.scored).toBe(false);
      expect(row.pickKind).toBeNull();
    }
  });

  it('carries NO verdict on any solar row — the map derives its own from the reader\'s scope', () => {
    // ⚠️ The verdict is deliberately not on the EV row. It is a property of the scope segment, not
    // of the served window, and shipping the served word here as well would put two answers for one
    // window on one pill. `utils/mapVerdict.js` is the single channel.
    const [row] = buildMapEvents({
      ...baseArgs(),
      solarWindows: [solarWindow(TODAY, 'SUNSET', {
        verdict: 'WORTH_IT', verdictLabel: 'Worth it', regionName: 'Cumbria',
      })],
    });

    expect(row.verdict).toBeUndefined();
    expect(row.verdictLabel).toBeUndefined();
    expect(row.regionName).toBeUndefined();
  });

  it('gives a night row no pick either — only solar windows can be a forecast pick', () => {
    const rows = buildMapEvents({
      ...baseArgs(),
      astroAvailableDates: [TODAY],
      astroConditionsByDate: new Map([[TODAY, [{ locationName: 'Kielder', stars: 4 }]]]),
      auroraAvailableDates: [TODAY],
      auroraResultsByDate: new Map([[TODAY, [{ locationName: 'Kielder', stars: 3 }]]]),
    });

    const nights = rows.filter((r) => r.kind !== EVENT_KIND.SOLAR);
    expect(nights).toHaveLength(2);
    expect(nights.map((r) => r.bestRating)).toEqual([4, 3]);
    for (const row of nights) {
      expect(row.pickKind).toBeUndefined();
      expect(row.verdict).toBeUndefined();
    }
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

/**
 * `nightPreviewDates` — the preview fetch's dates for one kind: the solar horizon, plus the
 * past-dated nights D-14 still offers as rows. Without the second part those rows read "—" even
 * while selected (D-14's review, F3).
 */
describe('nightPreviewDates — the horizon plus the past nights the list still offers', () => {
  const YESTERDAY = '2026-09-01';
  const OLDER = '2026-04-12';
  const HORIZON = [TODAY, TOMORROW];

  it('keeps the horizon\'s own dates and drops a past night nobody offers', () => {
    expect(nightPreviewDates('ASTRO', [OLDER, YESTERDAY, TODAY, TOMORROW], {
      horizonDates: HORIZON, todayStr: TODAY, currentNightDate: TODAY,
    })).toEqual([TODAY, TOMORROW]);
  });

  it('adds the night in progress, which the horizon — starting at today — cannot hold', () => {
    expect(nightPreviewDates('AURORA', [OLDER, YESTERDAY, TODAY], {
      horizonDates: HORIZON, todayStr: TODAY, currentNightDate: YESTERDAY,
    })).toEqual([YESTERDAY, TODAY]);
  });

  it('adds the ended night on screen, so its row keeps its best after it ends', () => {
    expect(nightPreviewDates('ASTRO', [OLDER, YESTERDAY, TODAY], {
      horizonDates: HORIZON, todayStr: TODAY, currentNightDate: TODAY, endedNightOnScreen: YESTERDAY,
    })).toEqual([YESTERDAY, TODAY]);
  });

  it('never widens forward — a night beyond the horizon stays out, as PR #731\'s bound requires', () => {
    const NEXT_WEEK = '2026-09-09';
    expect(nightPreviewDates('ASTRO', [TODAY, NEXT_WEEK], {
      horizonDates: HORIZON, todayStr: TODAY, currentNightDate: TODAY,
    })).toEqual([TODAY]);
  });
});


/**
 * `solarRowPredicate` — the reusable half of the rule `buildMapEvents` applies when it decides
 * whether to emit a solar row at all.
 *
 * <p>The agreement suite is the important one: `MapView` gates every per-window rating read on
 * this predicate precisely so the star chips cannot answer for a window the pill has just called
 * "No forecast", and that guarantee is worth exactly as much as the two staying identical. Each
 * case is therefore driven through BOTH functions from one set of inputs, so a fixture cannot
 * pre-satisfy its own predicate.
 */
describe('solarRowPredicate', () => {
  const YESTERDAY = '2026-09-01';
  const DAY_AFTER = '2026-09-04';

  /** Every (date, targetType) `buildMapEvents` actually emitted a solar row for. */
  function emittedSolarKeys(args) {
    return new Set(
      buildMapEvents({ ...baseArgs(), ...args })
        .filter((r) => r.kind === EVENT_KIND.SOLAR)
        .map((r) => `${r.date}:${r.eventType}`),
    );
  }

  it.each([
    ['a served window', { solarWindows: [solarWindow(TODAY, 'SUNSET')] }],
    ['a forecast date, today-forward', { forecastDates: [TODAY, TOMORROW] }],
    ['a forecast date in the PAST', { forecastDates: [YESTERDAY, TODAY] }],
    ['a served window on a date outside the forecast domain', {
      solarWindows: [solarWindow(DAY_AFTER, 'SUNRISE')], forecastDates: [TODAY],
    }],
    ['both, overlapping', {
      solarWindows: [solarWindow(TODAY, 'SUNSET'), solarWindow(TOMORROW, 'SUNRISE')],
      forecastDates: [YESTERDAY, TODAY, TOMORROW],
    }],
  ])('agrees with buildMapEvents for %s', (_label, args) => {
    const emitted = emittedSolarKeys(args);
    const predicate = solarRowPredicate({ ...args, todayStr: TODAY });
    // Probed over a window wider than any fixture's own inputs, so a date neither side was told
    // about is asserted on too.
    for (const date of [YESTERDAY, TODAY, TOMORROW, DAY_AFTER]) {
      for (const targetType of ['SUNRISE', 'SUNSET']) {
        expect(
          predicate(date, targetType),
          `${date}:${targetType}`,
        ).toBe(emitted.has(`${date}:${targetType}`));
      }
    }
  });

  it('is false for a PAST date the forecast endpoint still serves — the reported defect', () => {
    // `GET /api/forecast` serves `today-2` onward, so `forecastDates` legitimately carries dates
    // `buildMapEvents` clips away. That gap is what let a stale run's stars survive on the map.
    const predicate = solarRowPredicate({
      forecastDates: [YESTERDAY, TODAY], todayStr: TODAY,
    });
    expect(predicate(YESTERDAY, 'SUNSET')).toBe(false);
    expect(predicate(TODAY, 'SUNSET')).toBe(true);
  });

  it('is true for an ELAPSED window that still has a row — never a "has it passed" test', () => {
    // Today's sunrise after sunrise: the briefing retires it, but D-13 keeps it in the map's own
    // domain and the `‹` stepper walks straight into it. Its rating is the real answer for it.
    const predicate = solarRowPredicate({ forecastDates: [TODAY], todayStr: TODAY });
    expect(predicate(TODAY, 'SUNRISE')).toBe(true);
  });

  it('distinguishes the two events of one date', () => {
    const predicate = solarRowPredicate({
      solarWindows: [solarWindow(DAY_AFTER, 'SUNSET')], forecastDates: [], todayStr: TODAY,
    });
    expect(predicate(DAY_AFTER, 'SUNSET')).toBe(true);
    expect(predicate(DAY_AFTER, 'SUNRISE')).toBe(false);
  });

  it('answers TRUE for everything when it has no domain at all — unknown is not "no"', () => {
    // An empty EV list draws no pill, so there is no second surface for a rating to contradict.
    // Suppressing here would blank every rating on a mount that has not been handed the props.
    const predicate = solarRowPredicate({ todayStr: TODAY });
    expect(predicate(YESTERDAY, 'SUNSET')).toBe(true);
    expect(predicate(DAY_AFTER, 'SUNRISE')).toBe(true);
  });

  it('starts gating as soon as EITHER input is non-empty', () => {
    // The fail-open is keyed on having nothing at all, never on one of the two being absent —
    // otherwise a pane that has its forecast domain but not yet its briefing would go ungated.
    expect(solarRowPredicate({ forecastDates: [TODAY], todayStr: TODAY })(YESTERDAY, 'SUNSET'))
      .toBe(false);
    expect(
      solarRowPredicate({ solarWindows: [solarWindow(TODAY, 'SUNSET')], todayStr: TODAY })(YESTERDAY, 'SUNSET'),
    ).toBe(false);
  });
});
