import { describe, it, expect } from 'vitest';
import { planFigures } from '../utils/askPlan.js';
import { buildPickCards } from '../utils/askModel.js';
import {
  buildLocationSheet, buildScoreIndex, buildSlotIndex, departureWithDay,
  lightWindows, lookupForWindow,
} from '../utils/locationSheet.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { tideIndexOf } from './tideFactsFixture.js';
import {
  briefing, pick, ROSEBERRY, SALTBURN, WHITBY,
} from './askFixtures.js';

/**
 * "Plan this"'s four figures (F5, plan §2.8) against the location sheet's own output for the SAME
 * fixture. This is the strongest pin the plan allows: both sides are built here from one briefing and
 * one score-row list, through the sheet's real {@code buildLocationSheet}, and compared value for value.
 * A figure that was re-derived in {@code askPlan.js} (a copy of the light windows, a second departure)
 * would pass a test of its own output and fail this one the first time the sheet changed.
 */

const TONIGHT = '2026-10-05';
const TOMORROW = '2026-10-06';

/** The reader's HOME reach map, as the provider's {@code reachById} serves it. */
const home = (entries) => new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes, distanceMiles: 10 }]));

/** Score rows for Whitby's two windows, golden and blue hour as the backend serves them (UTC). */
function scoreRows() {
  return [
    {
      locationId: WHITBY, locationName: 'Whitby', date: TONIGHT, targetType: 'SUNSET', rating: 5,
      summary: 'Clear to the west.',
      goldenHourStart: '2026-10-05T16:50:00', goldenHourEnd: '2026-10-05T17:41:00',
      blueHourStart: '2026-10-05T17:41:00', blueHourEnd: '2026-10-05T18:15:00',
    },
    {
      locationId: WHITBY, locationName: 'Whitby', date: TOMORROW, targetType: 'SUNRISE', rating: 4,
      goldenHourStart: '2026-10-06T05:58:00', goldenHourEnd: '2026-10-06T06:30:00',
      blueHourStart: '2026-10-06T05:20:00', blueHourEnd: '2026-10-06T05:58:00',
    },
  ];
}

/** The window descriptors the sheet is built from — the two the fixture briefing carries. */
const windows = () => [
  {
    key: `${TONIGHT}:SUNSET`, date: TONIGHT, targetType: 'SUNSET', dow: 'Mon', sunrise: false,
    label: 'Tonight Sunset', time: '18:41', verdictLabel: 'Worth it', away: false,
  },
  {
    key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', dow: 'Tue', sunrise: true,
    label: 'Tomorrow Sunrise', time: '06:58', verdictLabel: 'Worth it', away: false,
  },
];

/** Both sides of the comparison, built from one briefing and one list of rows. */
function both({ pickOver = {}, reach = home([[WHITBY, 95]]), rows = scoreRows(), days = briefing().days } = {}) {
  const scoreIndex = buildScoreIndex(rows);
  const [card] = buildPickCards([pick(pickOver)], days, reach);
  const sheet = buildLocationSheet(
    { id: card.locationId, name: card.name, regionName: card.regionName },
    windows(),
    { scoreIndex, slotIndex: buildSlotIndex(days), reachById: reach },
  );
  const row = sheet.rows.find((r) => r.key === `${card.date}:${card.targetType}`);
  return { card, sheet, row, figures: planFigures(card, scoreIndex), scoreIndex, days };
}

describe('the four figures equal the location sheet’s own row for the same fixture', () => {
  it('Leave home is the sheet row’s departure, day word and all', () => {
    const { figures, row } = both();

    expect(figures.leave).not.toBeNull();
    expect(figures.leave).toEqual(row.leave);
    // Grounded, so a pair of equal nulls cannot pass: 18:41 BST minus 1h35 drive minus 20 min setup.
    expect(figures.leave).toMatchObject({ time: '16:46', sameDay: true, dayWord: null });
  });

  it('Leave home names the day when the departure crosses midnight — the sheet’s wrap, not a guess', () => {
    // 05:58 UTC sunrise (06:58 BST) minus 8h drive minus setup leaves the evening before.
    const { figures, row } = both({
      pickOver: { date: TOMORROW, targetType: 'SUNRISE', windowId: '2026-10-06_sunrise' },
      reach: home([[WHITBY, 480]]),
    });

    expect(figures.leave).toEqual(row.leave);
    expect(figures.leave.sameDay).toBe(false);
    expect(figures.leave.dayWord).toBe('Mon');
  });

  it('Best light is the sheet row’s light line, in the order the event’s own side runs', () => {
    const sunset = both();
    const sunrise = both({ pickOver: { date: TOMORROW, targetType: 'SUNRISE', windowId: '2026-10-06_sunrise' } });

    expect(sunset.figures.light).toEqual(sunset.row.light);
    expect(sunset.figures.light.map((w) => w.label)).toEqual(['golden', 'blue']);
    expect(sunrise.figures.light).toEqual(sunrise.row.light);
    expect(sunrise.figures.light.map((w) => w.label)).toEqual(['blue', 'golden']);
    // UK clock times: the served UTC instants plus the hour BST adds.
    expect(sunset.figures.light[0].range).toBe('17:50–18:41');
  });

  it('Drive is the sheet’s drive in the same words', () => {
    const { figures, sheet } = both();

    expect(figures.drive).toBe(formatDriveDuration(sheet.driveMinutes));
    expect(figures.drive).toBe('1h 35min');
  });

  it('Tide is the tide-alignment index’s fact for that location and window', () => {
    const { card, days, figures } = both();
    const fact = lookupForWindow(tideIndexOf(days), card.locationId, card.name, card.date, card.targetType);

    expect(figures.tide).toEqual({
      tier: 'match', state: fact.state, shortfall: null, clause: 'high water, right here',
    });
    expect(fact.state).toBe('HIGH');
  });

  it('Tide reads a served miss with its shortfall, and nothing for an inland spot', () => {
    const saltburn = both({ pickOver: { locationId: SALTBURN, locationName: 'Saltburn' }, reach: home([[SALTBURN, 50]]) });
    const roseberry = both({
      pickOver: { locationId: ROSEBERRY, locationName: 'Roseberry Topping' },
      reach: home([[ROSEBERRY, 45]]),
    });

    expect(saltburn.figures.tide).toMatchObject({ tier: 'miss', shortfall: 'LOWER' });
    expect(roseberry.figures.tide).toBeNull();
  });
});

describe('what each figure does when its input is missing', () => {
  it('no drive time gives no departure and no drive — never a guess', () => {
    const { figures, row, sheet } = both({ reach: home([]) });

    expect(figures.leave).toBeNull();
    expect(figures.drive).toBeNull();
    // ...and the sheet agrees about the same state.
    expect(row.leave).toBeNull();
    expect(sheet.driveMinutes).toBeNull();
  });

  it('a drive of zero minutes is a real drive: the departure is the setup time before the light', () => {
    const { figures, row } = both({ reach: home([[WHITBY, 0]]) });

    expect(figures.leave).toEqual(row.leave);
    // 18:41 BST minus the 20 minute setup alone.
    expect(figures.leave.time).toBe('18:21');
    expect(figures.drive).toBe('0 min');
  });

  it('a known drive with no event time gives no departure — the sheet agrees', () => {
    const days = briefing().days;
    delete days[0].eventSummaries[0].regions[0].slots[0].solarEventTime;
    const { card, figures } = both({ days });

    expect(card).toBeUndefined;
    expect(figures.leave).toBeNull();
    expect(figures.drive).toBe('1h 35min');
  });

  it('no score row for the window gives no light — a missing fact is silence', () => {
    const { figures, row } = both({ rows: [] });

    expect(figures.light).toBeNull();
    expect(row.light).toBeNull();
  });

  it('a score row with neither golden nor blue hour gives no light', () => {
    const { figures } = both({
      rows: [{
        locationId: WHITBY, locationName: 'Whitby', date: TONIGHT, targetType: 'SUNSET', rating: 5,
      }],
    });

    expect(figures.light).toBeNull();
  });

  it('finds the score row by name when its id is absent, as the sheet does', () => {
    const rows = scoreRows().map((r) => ({ ...r, locationId: null }));
    const { figures, row } = both({ rows });

    expect(figures.light).toEqual(row.light);
    expect(figures.light).not.toBeNull();
  });
});

describe('the exports it stands on are the sheet’s own', () => {
  it('lightWindows is the one function, exported', () => {
    expect(lightWindows(null, 'SUNSET')).toBeNull();
    const [row] = scoreRows();
    const windowsOut = lightWindows({
      goldenHourStart: row.goldenHourStart,
      goldenHourEnd: row.goldenHourEnd,
      blueHourStart: row.blueHourStart,
      blueHourEnd: row.blueHourEnd,
    }, 'SUNSET');
    expect(windowsOut.map((w) => w.range)).toEqual(['17:50–18:41', '18:41–19:15']);
  });

  it('departureWithDay is leaveByParts plus the day word, null on the same terms', () => {
    expect(departureWithDay('2026-10-05T17:41:00', 95)).toMatchObject({ time: '16:46', dayWord: null });
    expect(departureWithDay('2026-10-05T17:41:00', null)).toBeNull();
    expect(departureWithDay(null, 95)).toBeNull();
  });
});
