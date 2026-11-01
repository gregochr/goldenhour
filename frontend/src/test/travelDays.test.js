import { describe, it, expect } from 'vitest';
import {
  calendarCells,
  cellLabel,
  formatDay,
  groupRanges,
  isPastRange,
  lengthDays,
  lengthText,
  placeServerError,
  rangeForDay,
  rangeText,
  validateRange,
  whenText,
} from '../utils/travelDays.js';

// The pure maths under the Travel days tab. "Now" is always passed in (the real clock, never Rewind: R-D7).

const SUN_1_NOV = new Date('2026-11-01T12:00:00Z');
const MON_2_NOV = new Date('2026-11-02T10:00:00Z');

describe('calendarCells', () => {
  it('starts on this week\'s Monday and runs five weeks, Monday first', () => {
    // 1 Nov 2026 is a Sunday: its week began on Monday 26 Oct.
    const cells = calendarCells(SUN_1_NOV);
    expect(cells).toHaveLength(35);
    expect(cells[0].iso).toBe('2026-10-26');
    expect(cells[0].weekday).toBe('Mon');
    expect(cells[6].iso).toBe('2026-11-01');
    expect(cells[6].weekday).toBe('Sun');
    expect(cells[34].iso).toBe('2026-11-29');
    expect(cells.every((cell, i) => cell.weekday === ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][i % 7])).toBe(true);
  });

  it('starts on today when today is a Monday', () => {
    const cells = calendarCells(MON_2_NOV);
    expect(cells[0].iso).toBe('2026-11-02');
    expect(cells[0].isToday).toBe(true);
    expect(cells[34].iso).toBe('2026-12-06');
  });

  it('labels the 1st of a month month-first ("1 Nov") and every other day with its number', () => {
    const cells = calendarCells(SUN_1_NOV);
    expect(cells.find((c) => c.iso === '2026-11-01').label).toBe('1 Nov');
    expect(cells.find((c) => c.iso === '2026-10-26').label).toBe('26');
    expect(cells.find((c) => c.iso === '2026-11-02').label).toBe('2');
    const december = calendarCells(MON_2_NOV);
    expect(december.find((c) => c.iso === '2026-12-01').label).toBe('1 Dec');
  });

  it('marks exactly today, and the days before it as past', () => {
    const cells = calendarCells(SUN_1_NOV);
    expect(cells.filter((c) => c.isToday).map((c) => c.iso)).toEqual(['2026-11-01']);
    expect(cells.filter((c) => c.isPast).map((c) => c.iso)).toEqual([
      '2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31',
    ]);
  });

  it('reads today on the UK calendar: 23:30 UTC in summer is already tomorrow in London', () => {
    const cells = calendarCells(new Date('2026-06-30T23:30:00Z'));
    expect(cells.find((c) => c.isToday).iso).toBe('2026-07-01');
    expect(cells[0].iso).toBe('2026-06-29');
  });

  it('steps whole days across the October clock change', () => {
    const cells = calendarCells(new Date('2026-10-20T12:00:00Z'));
    expect(cells.map((c) => c.iso).filter((iso) => iso >= '2026-10-24' && iso <= '2026-10-27'))
      .toEqual(['2026-10-24', '2026-10-25', '2026-10-26', '2026-10-27']);
    expect(new Set(cells.map((c) => c.iso)).size).toBe(35);
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
