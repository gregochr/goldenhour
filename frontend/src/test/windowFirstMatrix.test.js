import { describe, it, expect } from 'vitest';
import { buildWindowMatrix, EMPTY_CELL_COPY, MATRIX_ROW } from '../utils/windowFirstMatrix.js';

/**
 * The Plan matrix's grid maths — day columns, the two rows, and which holes get words.
 *
 * <p>Every fixture here is a shape the payload can actually produce: the backend caps the rendered
 * events at six, orders them chronologically and drops the ones that have already elapsed, so a
 * four-day span with a missing first sunrise and a missing last sunset is the ORDINARY week rather
 * than an edge case. The dates are fixed and deliberately not today's — a clock-derived fixture is
 * how this suite has been bitten twice.
 */

/** A thumbnail descriptor, as `buildHeatStripCards` shapes the fields this util reads. */
function card(date, targetType, overrides = {}) {
  return {
    key: `${date}:${targetType}`,
    date,
    targetType,
    dow: new Date(`${date}T12:00:00Z`)
      .toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' }),
    away: false,
    ...overrides,
  };
}

const TODAY = '2026-08-04';

/** The ordinary six-across-four week: today's sunrise gone, the last sunset past the cap. */
const ORDINARY = [
  card('2026-08-04', 'SUNSET'),
  card('2026-08-05', 'SUNRISE'),
  card('2026-08-05', 'SUNSET'),
  card('2026-08-06', 'SUNRISE'),
  card('2026-08-06', 'SUNSET'),
  card('2026-08-07', 'SUNRISE'),
];

describe('buildWindowMatrix — the grid', () => {
  it('gives one column per distinct day, in the payload\'s own order', () => {
    // No sort here: the events arrive chronological and re-ordering them would be a second opinion
    // about an ordering the backend owns.
    const { columns, days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(columns).toBe(4);
    expect(days.map((d) => d.date))
      .toEqual(['2026-08-04', '2026-08-05', '2026-08-06', '2026-08-07']);
  });

  it('puts sunrise in the am slot and sunset in the pm slot of the same day', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days[1].am.key).toBe('2026-08-05:SUNRISE');
    expect(days[1].pm.key).toBe('2026-08-05:SUNSET');
  });

  it('carries the weekday and the day-of-month for the column head', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days[0].dow).toBe('Tue');
    expect(days[0].dn).toBe(4);
    expect(days[3].dn).toBe(7);
  });

  it('marks the today column by date, never by position', () => {
    // Position was the prototype's rule (`c===0?' tdy'`), and it is wrong the moment every one of
    // today's windows has passed: the first column is then tomorrow, and gold on it would say the
    // reader is looking at today.
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days.map((d) => d.today)).toEqual([true, false, false, false]);
  });

  it('marks no column today when today has no rendered window left', () => {
    const { days } = buildWindowMatrix(ORDINARY.slice(1), TODAY);
    expect(days.some((d) => d.today)).toBe(false);
  });

  it('publishes the two rows the cells are placed into', () => {
    expect(MATRIX_ROW).toEqual({ header: 1, sunrise: 2, sunset: 3 });
  });
});

describe('buildWindowMatrix — the holes, and which of them get words', () => {
  it('says this morning has gone when today is first and has lost its sunrise', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days[0].am).toBeNull();
    expect(days[0].amEmpty).toBe(EMPTY_CELL_COPY.morningGone);
  });

  it('says past the end of the forecast when the last day has lost its sunset', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days[3].pm).toBeNull();
    expect(days[3].pmEmpty).toBe(EMPTY_CELL_COPY.beyondForecast);
  });

  it('makes NEITHER claim about a hole in the middle of the span', () => {
    // The payload contract does not produce one, and a future one might. Degrade is silence: a cell
    // that cannot explain itself renders dashed and says nothing rather than borrowing a sentence
    // from one of the two holes that CAN be explained.
    const withHole = [
      card('2026-08-04', 'SUNSET'),
      card('2026-08-05', 'SUNSET'),
      card('2026-08-06', 'SUNRISE'),
      card('2026-08-06', 'SUNSET'),
    ];
    const { days } = buildWindowMatrix(withHole, TODAY);
    expect(days[1].am).toBeNull();
    expect(days[1].amEmpty).toBeNull();
  });

  it('withholds the morning claim when the first day is not today', () => {
    // Reachable: the backend drops elapsed events, so when every one of today's windows has passed
    // the first rendered day is tomorrow — and a missing sunrise THERE means something else
    // entirely, which the client cannot name.
    const tomorrowFirst = [
      card('2026-08-05', 'SUNSET'),
      card('2026-08-06', 'SUNRISE'),
      card('2026-08-06', 'SUNSET'),
    ];
    const { days } = buildWindowMatrix(tomorrowFirst, TODAY);
    expect(days[0].am).toBeNull();
    expect(days[0].amEmpty).toBeNull();
  });

  it('withholds the beyond claim from a day that is not the last', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days.slice(0, 3).every((d) => d.pmEmpty === null)).toBe(true);
  });

  it('leaves a forecast that starts at sunrise with no leading hole at all', () => {
    const fromDawn = [
      card('2026-08-04', 'SUNRISE'),
      card('2026-08-04', 'SUNSET'),
      card('2026-08-05', 'SUNRISE'),
      card('2026-08-05', 'SUNSET'),
    ];
    const { days } = buildWindowMatrix(fromDawn, TODAY);
    expect(days[0].am.key).toBe('2026-08-04:SUNRISE');
    expect(days[0].amEmpty).toBeNull();
    // Both windows present on the last day, so the trailing claim is withheld too.
    expect(days[1].pmEmpty).toBeNull();
  });
});

describe('buildWindowMatrix — solo days and away days', () => {
  it('marks a day holding exactly one window as solo, at either end of the span', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days.map((d) => d.solo)).toEqual([true, false, false, true]);
  });

  it('does not mark a day holding both windows as solo', () => {
    const { days } = buildWindowMatrix(ORDINARY, TODAY);
    expect(days[1].solo).toBe(false);
  });

  it('handles a single-window final day that is also the only day', () => {
    const { columns, days } = buildWindowMatrix([card('2026-08-04', 'SUNSET')], TODAY);
    expect(columns).toBe(1);
    expect(days[0].solo).toBe(true);
    expect(days[0].amEmpty).toBe(EMPTY_CELL_COPY.morningGone);
    // The one day is both first and last, so BOTH claims are available — and only the pm hole
    // exists, because the am one is filled by nothing.
    expect(days[0].pm.key).toBe('2026-08-04:SUNSET');
    expect(days[0].pmEmpty).toBeNull();
  });

  it('marks a day away only when EVERY window it renders is a travel window', () => {
    const mixed = [
      card('2026-08-05', 'SUNRISE', { away: true }),
      card('2026-08-05', 'SUNSET', { away: true }),
      card('2026-08-06', 'SUNRISE', { away: true }),
      card('2026-08-06', 'SUNSET'),
    ];
    const { days } = buildWindowMatrix(mixed, TODAY);
    expect(days[0].away).toBe(true);
    expect(days[1].away).toBe(false);
  });

  it('keeps an away day mid-span as its own column rather than closing the gap', () => {
    // A missing column would silently renumber the shape of the week — the reason the strip has
    // always drawn away cells.
    const withAway = [
      card('2026-08-04', 'SUNSET'),
      card('2026-08-05', 'SUNRISE', { away: true }),
      card('2026-08-05', 'SUNSET', { away: true }),
      card('2026-08-06', 'SUNRISE'),
    ];
    const { columns, days } = buildWindowMatrix(withAway, TODAY);
    expect(columns).toBe(3);
    expect(days[1].date).toBe('2026-08-05');
    expect(days[1].away).toBe(true);
  });
});

describe('buildWindowMatrix — nothing to draw', () => {
  it('answers an empty grid for no rendered events', () => {
    expect(buildWindowMatrix([], TODAY)).toEqual({ columns: 0, days: [] });
  });

  it('answers an empty grid for a null card list rather than throwing', () => {
    expect(buildWindowMatrix(null, TODAY)).toEqual({ columns: 0, days: [] });
  });

  it('skips a card with no date rather than opening a column keyed on undefined', () => {
    const { columns, days } = buildWindowMatrix(
      [{ key: 'x', targetType: 'SUNSET' }, card('2026-08-04', 'SUNSET')], TODAY,
    );
    expect(columns).toBe(1);
    expect(days[0].date).toBe('2026-08-04');
  });
});
