import { describe, it, expect } from 'vitest';
import {
  addMonthsClamped,
  cellClasses,
  cellLabel,
  formatDay,
  draftOverlaps,
  draftSpan,
  draftText,
  groupRanges,
  isPastRange,
  joinFlags,
  lengthDays,
  lengthText,
  monthCells,
  monthLabel,
  monthOf,
  placeServerError,
  rangeForDay,
  rangeText,
  shiftMonth,
  validateRange,
  whenText,
} from '../utils/travelDays.js';

// The pure maths under the Travel days tab. "Now" is always passed in (the real clock, never Rewind: R-D7).

const SUN_1_NOV = new Date('2026-11-01T12:00:00Z');
const MON_2_NOV = new Date('2026-11-02T10:00:00Z');

const NOV = { year: 2026, month0: 10 };

describe('monthCells', () => {
  it('always runs six weeks (42 cells), Monday first, from the Monday on or before the 1st', () => {
    // 1 Nov 2026 is a Sunday: its week began on Monday 26 Oct.
    const cells = monthCells(NOV, SUN_1_NOV);
    expect(cells).toHaveLength(42);
    expect(cells[0].iso).toBe('2026-10-26');
    expect(cells[0].weekday).toBe('Mon');
    expect(cells[6].iso).toBe('2026-11-01');
    expect(cells[6].weekday).toBe('Sun');
    expect(cells[41].iso).toBe('2026-12-06');
    expect(cells.every((cell, i) => cell.col === i % 7)).toBe(true);
    expect(cells.every((cell, i) => cell.weekday === ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][i % 7])).toBe(true);
  });

  it('starts on the 1st itself when the month begins on a Monday', () => {
    // 1 Jun 2026 is a Monday.
    const cells = monthCells({ year: 2026, month0: 5 }, MON_2_NOV);
    expect(cells[0].iso).toBe('2026-06-01');
    expect(cells[0].inMonth).toBe(true);
    expect(cells[41].iso).toBe('2026-07-12');
  });

  it('marks the days outside the month, so the view can dim them', () => {
    const cells = monthCells(NOV, MON_2_NOV);
    expect(cells.filter((c) => c.inMonth)).toHaveLength(30);
    expect(cells[0].inMonth).toBe(false);
    expect(cells.find((c) => c.iso === '2026-11-01').inMonth).toBe(true);
    expect(cells.find((c) => c.iso === '2026-12-01').inMonth).toBe(false);
  });

  it('wraps the year in both directions', () => {
    const december = monthCells({ year: 2026, month0: 11 }, MON_2_NOV);
    expect(december[0].iso).toBe('2026-11-30');
    expect(december[41].iso).toBe('2027-01-10');
    const january = monthCells({ year: 2027, month0: 0 }, MON_2_NOV);
    expect(january[0].iso).toBe('2026-12-28');
    expect(january.find((c) => c.iso === '2026-12-31').inMonth).toBe(false);
  });

  it('marks exactly today, and the days before it as past', () => {
    const cells = monthCells(NOV, SUN_1_NOV);
    expect(cells.filter((c) => c.isToday).map((c) => c.iso)).toEqual(['2026-11-01']);
    expect(cells.filter((c) => c.isPast).map((c) => c.iso)).toEqual([
      '2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31',
    ]);
  });

  it('reads today on the UK calendar: 23:30 UTC in summer is already tomorrow in London', () => {
    const cells = monthCells({ year: 2026, month0: 6 }, new Date('2026-06-30T23:30:00Z'));
    expect(cells.find((c) => c.isToday).iso).toBe('2026-07-01');
  });

  it('steps whole days across the October clock change', () => {
    const cells = monthCells({ year: 2026, month0: 9 }, new Date('2026-10-20T12:00:00Z'));
    expect(cells.map((c) => c.iso).filter((iso) => iso >= '2026-10-24' && iso <= '2026-10-27'))
      .toEqual(['2026-10-24', '2026-10-25', '2026-10-26', '2026-10-27']);
    expect(new Set(cells.map((c) => c.iso)).size).toBe(42);
  });
});

describe('month helpers', () => {
  it('reads the month of a date, and pages it across the year ends', () => {
    expect(monthOf('2026-11-30')).toEqual(NOV);
    expect(shiftMonth({ year: 2026, month0: 11 }, 1)).toEqual({ year: 2027, month0: 0 });
    expect(shiftMonth({ year: 2027, month0: 0 }, -1)).toEqual({ year: 2026, month0: 11 });
    expect(shiftMonth(NOV, 14)).toEqual({ year: 2028, month0: 0 });
  });

  it('names the month for the header', () => {
    expect(monthLabel(NOV)).toEqual({ month: 'November', year: '2026' });
  });

  it('keeps the day number a month on, clamped to the last day', () => {
    expect(addMonthsClamped('2026-01-31', 1)).toBe('2026-02-28');
    expect(addMonthsClamped('2028-01-31', 1)).toBe('2028-02-29');
    expect(addMonthsClamped('2026-03-31', -1)).toBe('2026-02-28');
    expect(addMonthsClamped('2026-12-15', 1)).toBe('2027-01-15');
    expect(addMonthsClamped('2026-01-15', -1)).toBe('2025-12-15');
  });
});

describe('joinFlags', () => {
  const MON_WED = { id: 1, startDate: '2026-11-02', endDate: '2026-11-04' };
  const CROSSING = { id: 2, startDate: '2026-11-08', endDate: '2026-11-10' };

  it('joins the days of one range inside a week row', () => {
    expect(joinFlags([MON_WED], '2026-11-02', 0)).toEqual({ joinLeft: false, joinRight: true });
    expect(joinFlags([MON_WED], '2026-11-03', 1)).toEqual({ joinLeft: true, joinRight: true });
    expect(joinFlags([MON_WED], '2026-11-04', 2)).toEqual({ joinLeft: true, joinRight: false });
  });

  it('breaks at the end of the week row: Sunday never joins right, Monday never joins left', () => {
    expect(joinFlags([CROSSING], '2026-11-08', 6)).toEqual({ joinLeft: false, joinRight: false });
    expect(joinFlags([CROSSING], '2026-11-09', 0)).toEqual({ joinLeft: false, joinRight: true });
  });

  it('does not join two touching ranges', () => {
    const second = { id: 3, startDate: '2026-11-05', endDate: '2026-11-06' };
    expect(joinFlags([MON_WED, second], '2026-11-04', 2)).toEqual({ joinLeft: true, joinRight: false });
    expect(joinFlags([MON_WED, second], '2026-11-05', 3)).toEqual({ joinLeft: false, joinRight: true });
  });

  it('joins nothing for a free day', () => {
    expect(joinFlags([MON_WED], '2026-11-05', 3)).toEqual({ joinLeft: false, joinRight: false });
  });
});

describe('the draft helpers', () => {
  it('puts a draft in date order and leaves a start-only draft with no end', () => {
    expect(draftSpan({ start: '2026-11-18', end: '2026-11-16' })).toEqual({ start: '2026-11-16', end: '2026-11-18' });
    expect(draftSpan({ start: '2026-11-16', end: '2026-11-18' })).toEqual({ start: '2026-11-16', end: '2026-11-18' });
    expect(draftSpan({ start: '2026-11-16', end: null })).toEqual({ start: '2026-11-16', end: null });
    expect(draftSpan(null)).toBeNull();
  });

  it('finds an overlap with any existing range, edges included', () => {
    const ranges = [{ id: 1, startDate: '2026-11-10', endDate: '2026-11-12' }];
    expect(draftOverlaps(ranges, { start: '2026-11-12', end: '2026-11-14' })).toBe(true);
    expect(draftOverlaps(ranges, { start: '2026-11-08', end: '2026-11-10' })).toBe(true);
    expect(draftOverlaps(ranges, { start: '2026-11-11', end: '2026-11-11' })).toBe(true);
    expect(draftOverlaps(ranges, { start: '2026-11-13', end: '2026-11-14' })).toBe(false);
    expect(draftOverlaps(ranges, { start: '2026-11-09', end: null })).toBe(false);
    expect(draftOverlaps(ranges, { start: '2026-11-11', end: null })).toBe(true);
    expect(draftOverlaps(ranges, null)).toBe(false);
  });

  it('writes a draft for one day, many days and a start only', () => {
    expect(draftText({ start: '2026-11-18', end: '2026-11-18' })).toEqual({ dates: 'Wed 18 Nov', detail: '1 day' });
    expect(draftText({ start: '2026-11-18', end: '2026-11-20' }))
      .toEqual({ dates: 'Wed 18 Nov → Fri 20 Nov', detail: '3 days' });
    expect(draftText({ start: '2026-11-18', end: null })).toEqual({ dates: 'Wed 18 Nov', detail: 'pick an end date' });
    expect(draftText({ start: '2027-01-04', end: '2027-01-05' }, MON_2_NOV).dates).toBe('Mon 4 Jan 2027 → Tue 5 Jan 2027');
  });
});

describe('cellClasses', () => {
  const FREE = {
    away: false, selected: false, draft: false, today: false, past: false, outside: false,
    joinLeft: false, joinRight: false, cursor: false,
  };
  const has = (state, cls) => cellClasses({ ...FREE, ...state }).split(' ').includes(cls);

  it('gives a plain day the base look and nothing dimmed', () => {
    expect(has({}, 'bg-plex-surface-light')).toBe(true);
    expect(has({}, 'opacity-100')).toBe(true);
    expect(has({}, 'hover:bg-white/8')).toBe(true);
  });

  it('dims outside-month days to 35 and past days to 45, past winning when both', () => {
    expect(has({ outside: true }, 'opacity-35')).toBe(true);
    expect(has({ past: true }, 'opacity-45')).toBe(true);
    expect(has({ past: true, outside: true }, 'opacity-45')).toBe(true);
    expect(has({ past: true, outside: true }, 'opacity-35')).toBe(false);
  });

  it('draws a past draft day at full opacity', () => {
    expect(has({ past: true, draft: true }, 'opacity-100')).toBe(true);
    expect(has({ past: true, draft: true }, 'opacity-45')).toBe(false);
    expect(has({ outside: true, draft: true }, 'opacity-35')).toBe(false);
  });

  it('draws the draft ring, not the today ring, on a day that is both', () => {
    expect(has({ today: true }, 'inset-ring-plex-text')).toBe(true);
    expect(has({ today: true, draft: true }, 'inset-ring-tide/90')).toBe(true);
    expect(has({ today: true, draft: true }, 'inset-ring-plex-text')).toBe(false);
  });

  it('fills an away day that is also drafted with the draft fill, and a selected one with the strong tide', () => {
    expect(has({ away: true }, 'bg-tide/55')).toBe(true);
    expect(has({ away: true, selected: true }, 'bg-tide/90')).toBe(true);
    expect(has({ away: true, selected: true }, 'bg-tide/55')).toBe(false);
    expect(has({ away: true, draft: true }, 'bg-tide/18')).toBe(true);
    expect(has({ away: true, draft: true }, 'bg-tide/55')).toBe(false);
  });

  it('emits exactly one background, one opacity and one ring for every combination', () => {
    const bools = [false, true];
    for (const away of bools) for (const selected of bools) for (const draft of bools) {
      for (const today of bools) for (const past of bools) for (const outside of bools) {
        const classes = cellClasses({ ...FREE, away, selected, draft, today, past, outside }).split(' ');
        expect(classes.filter((c) => /^bg-(plex|tide)/.test(c))).toHaveLength(1);
        expect(classes.filter((c) => /^opacity-/.test(c))).toHaveLength(1);
        expect(classes.filter((c) => /^inset-ring-\[/.test(c))).toHaveLength(today || draft ? 1 : 0);
      }
    }
  });

  it('squares the joined sides and pulls a left-joined cell over the gap', () => {
    expect(has({ joinLeft: true }, 'rounded-l-none')).toBe(true);
    expect(has({ joinLeft: true }, '-ml-[3px]')).toBe(true);
    expect(has({ joinLeft: true }, 'rounded-r-[5px]')).toBe(true);
    expect(has({ joinRight: true }, 'rounded-r-none')).toBe(true);
    expect(has({}, 'rounded-l-[5px]')).toBe(true);
  });

  it('shows the keyboard cursor only while the grid is focus-visible', () => {
    expect(has({ cursor: true }, 'group-focus-visible:outline-2')).toBe(true);
    expect(has({}, 'group-focus-visible:outline-2')).toBe(false);
  });
});

describe('cellLabel', () => {
  const cell = { iso: '2026-11-02', isToday: false };
  it('reads the day, then away with its note', () => {
    expect(cellLabel(cell, { note: 'London — work' })).toBe('Mon 2 Nov, away (London — work)');
  });
  it('reads away alone when the range has no note, and plain for a free day', () => {
    expect(cellLabel(cell, { note: null })).toBe('Mon 2 Nov, away');
    expect(cellLabel(cell, null)).toBe('Mon 2 Nov');
  });
  it('says "today" on today\'s cell', () => {
    expect(cellLabel({ iso: '2026-11-02', isToday: true }, null)).toBe('Mon 2 Nov, today');
  });
  it('says "selected" for the open range\'s days and "in draft" for the draft\'s', () => {
    expect(cellLabel(cell, { note: null }, undefined, { selected: true })).toBe('Mon 2 Nov, away, selected');
    expect(cellLabel(cell, null, undefined, { inDraft: true })).toBe('Mon 2 Nov, in draft');
  });
});

describe('the Range, Length and When strings', () => {
  it('writes a range as "Mon 2 Nov → Wed 4 Nov" and a single day as one date', () => {
    expect(rangeText({ startDate: '2026-11-02', endDate: '2026-11-04' })).toBe('Mon 2 Nov → Wed 4 Nov');
    expect(rangeText({ startDate: '2026-11-02', endDate: '2026-11-02' })).toBe('Mon 2 Nov');
  });

  it('counts both ends: "3 days" and "1 day"', () => {
    expect(lengthDays('2026-11-02', '2026-11-04')).toBe(3);
    expect(lengthText('2026-11-02', '2026-11-04')).toBe('3 days');
    expect(lengthText('2026-11-02', '2026-11-02')).toBe('1 day');
    expect(lengthText('2026-10-30', '2026-11-02')).toBe('4 days');
  });

  it('says when: In n days, Tomorrow, Now (today inside the range) and Ended {d MMM}', () => {
    const at = (startDate, endDate) => whenText({ startDate, endDate }, MON_2_NOV);
    expect(at('2026-11-04', '2026-11-06')).toBe('In 2 days');
    expect(at('2026-11-03', '2026-11-03')).toBe('Tomorrow');
    expect(at('2026-11-02', '2026-11-02')).toBe('Now');
    expect(at('2026-10-30', '2026-11-03')).toBe('Now');
    expect(at('2026-10-26', '2026-10-28')).toBe('Ended 28 Oct');
  });

  it('formats a day with its weekday', () => {
    expect(formatDay('2026-11-02')).toBe('Mon 2 Nov');
  });
});

describe('groupRanges and isPastRange', () => {
  const ranges = [
    { id: 1, startDate: '2026-11-10', endDate: '2026-11-10' },
    { id: 2, startDate: '2026-10-01', endDate: '2026-10-02' },
    { id: 3, startDate: '2026-11-04', endDate: '2026-11-06' },
    { id: 4, startDate: '2026-10-20', endDate: '2026-10-21' },
    { id: 5, startDate: '2026-10-30', endDate: '2026-11-02' },
  ];

  it('treats a range ending today as upcoming and one that ended yesterday as past', () => {
    expect(isPastRange({ endDate: '2026-11-02' }, MON_2_NOV)).toBe(false);
    expect(isPastRange({ endDate: '2026-11-01' }, MON_2_NOV)).toBe(true);
  });

  it('puts upcoming soonest first and past newest first', () => {
    const { upcoming, past } = groupRanges(ranges, MON_2_NOV);
    expect(upcoming.map((r) => r.id)).toEqual([5, 3, 1]);
    expect(past.map((r) => r.id)).toEqual([4, 2]);
  });
});

describe('rangeForDay', () => {
  const ranges = [
    { id: 1, startDate: '2026-11-04', endDate: '2026-11-06' },
    { id: 2, startDate: '2026-11-05', endDate: '2026-11-08' },
  ];
  it('includes both ends, and returns null outside every range', () => {
    expect(rangeForDay(ranges, '2026-11-04').id).toBe(1);
    expect(rangeForDay(ranges, '2026-11-08').id).toBe(2);
    expect(rangeForDay(ranges, '2026-11-03')).toBeNull();
    expect(rangeForDay(ranges, '2026-11-09')).toBeNull();
  });
  it('takes the earliest start where two ranges overlap', () => {
    expect(rangeForDay(ranges, '2026-11-05').id).toBe(1);
  });
});

describe('validateRange', () => {
  it('asks for both dates, under whichever is empty', () => {
    expect(validateRange({ startDate: '', endDate: '' })).toEqual({
      startDate: 'Both a start and end date are required',
      endDate: 'Both a start and end date are required',
    });
    expect(validateRange({ startDate: '2026-11-02', endDate: '' })).toEqual({
      endDate: 'Both a start and end date are required',
    });
  });
  it('refuses an end before the start, under To, and accepts the same day', () => {
    expect(validateRange({ startDate: '2026-11-05', endDate: '2026-11-01' }))
      .toEqual({ endDate: 'End date must not be before start date' });
    expect(validateRange({ startDate: '2026-11-05', endDate: '2026-11-05' })).toEqual({});
  });
});

describe('placeServerError', () => {
  const refusal = (error, status = 400) => ({ response: { status, data: { error } } });
  it('puts a 400 under the field it names', () => {
    expect(placeServerError(refusal('note must be at most 200 characters'), 'x'))
      .toEqual({ field: 'note', message: 'note must be at most 200 characters' });
    expect(placeServerError(refusal('endDate must not be before startDate'), 'x').field).toBe('endDate');
    expect(placeServerError(refusal('startDate and endDate are required'), 'x').field).toBe('startDate');
  });
  it('gives a 500 or a network failure the fallback, with no field', () => {
    expect(placeServerError(refusal('boom', 500), 'Failed to add travel range'))
      .toEqual({ field: null, message: 'Failed to add travel range' });
    expect(placeServerError(new Error('Network Error'), 'Failed to save travel range'))
      .toEqual({ field: null, message: 'Failed to save travel range' });
  });
});
