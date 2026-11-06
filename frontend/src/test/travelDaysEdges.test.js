import { describe, it, expect } from 'vitest';
import {
  cellClasses, cellLabel, draftSpan, formatDay, groupRanges, placeServerError, rangeForDay, sameMonth,
  validateRange,
} from '../utils/travelDays.js';

/**
 * Edge behaviour of the Travel days view-model: sort tie-breaks, the earliest-start pick, the draft order, the
 * cell class cascade and the server-error placement. Companion to travelDays.test.js; each case pins an answer
 * the happy-path tests never ask for.
 */

const NOW = new Date('2026-11-06T12:00:00Z');
const range = (id, startDate, endDate = startDate, o = {}) => ({ id, startDate, endDate, ...o });

const permutations = (items) => (items.length <= 1
  ? [items]
  : items.flatMap((item, i) => permutations([...items.slice(0, i), ...items.slice(i + 1)])
    .map((rest) => [item, ...rest])));

const idsOf = (list) => list.map((r) => r.id);

describe('formatDay without a clock', () => {
  it('never adds a year, whatever year the date is in', () => {
    expect(formatDay('2020-05-04')).toBe('Mon 4 May');
  });
});

describe('groupRanges ordering', () => {
  const upcoming = [
    range(9, '2026-12-01'), range(3, '2026-11-10'), range(5, '2026-11-10'), range(1, '2026-11-20'),
  ];
  const past = [
    range(1, '2026-10-01'), range(3, '2026-09-10'), range(5, '2026-09-10'), range(9, '2026-08-20'),
  ];

  it('sorts upcoming soonest first, ties by id ascending, from every input order', () => {
    permutations(upcoming).forEach((input) => {
      expect(idsOf(groupRanges(input, NOW).upcoming)).toEqual([3, 5, 1, 9]);
    });
  });

  it('sorts past newest first, ties by id descending, from every input order', () => {
    permutations(past).forEach((input) => {
      expect(idsOf(groupRanges(input, NOW).past)).toEqual([1, 5, 3, 9]);
    });
  });

  it('puts a range that ended yesterday in past and one ending today in upcoming', () => {
    const g = groupRanges([range(1, '2026-11-04', '2026-11-05'), range(2, '2026-11-05', '2026-11-06')], NOW);
    expect(idsOf(g.past)).toEqual([1]);
    expect(idsOf(g.upcoming)).toEqual([2]);
  });
});

describe('rangeForDay overlap', () => {
  it('picks the earliest start even when it comes later in the list', () => {
    const ranges = [range(1, '2026-11-03', '2026-11-09'), range(2, '2026-11-01', '2026-11-09')];
    expect(rangeForDay(ranges, '2026-11-05').id).toBe(2);
  });

  it('keeps the earlier-listed range when two share a start', () => {
    const a = range(1, '2026-11-01', '2026-11-09');
    const b = range(2, '2026-11-01', '2026-11-09');
    expect(rangeForDay([a, b], '2026-11-05').id).toBe(1);
    expect(rangeForDay([b, a], '2026-11-05').id).toBe(2);
  });
});

describe('sameMonth', () => {
  it('is false for the same month number in different years, and for different months in one year', () => {
    expect(sameMonth({ year: 2026, month0: 10 }, { year: 2027, month0: 10 })).toBe(false);
    expect(sameMonth({ year: 2026, month0: 10 }, { year: 2026, month0: 9 })).toBe(false);
    expect(sameMonth({ year: 2026, month0: 10 }, { year: 2026, month0: 10 })).toBe(true);
  });
});

describe('draftSpan', () => {
  it('normalises a missing end to null, not undefined or an empty string', () => {
    expect(draftSpan({ start: '2026-11-02' })).toStrictEqual({ start: '2026-11-02', end: null });
    expect(draftSpan({ start: '2026-11-02', end: '' })).toStrictEqual({ start: '2026-11-02', end: null });
  });

  it('orders a backwards draft and leaves a forwards one alone', () => {
    expect(draftSpan({ start: '2026-11-05', end: '2026-11-02' }))
      .toStrictEqual({ start: '2026-11-02', end: '2026-11-05' });
    expect(draftSpan({ start: '2026-11-02', end: '2026-11-05' }))
      .toStrictEqual({ start: '2026-11-02', end: '2026-11-05' });
    expect(draftSpan(null)).toBeNull();
  });
});

describe('cellClasses cascade', () => {
  const base = {
    away: false, selected: false, draft: false, today: false, past: false, outside: false,
    joinLeft: false, joinRight: false, cursor: false,
  };
  const classes = (o) => cellClasses({ ...base, ...o }).split(' ');

  it('builds the exact class string for a plain cell, single-spaced with no empties', () => {
    expect(cellClasses(base)).toBe(
      'flex min-h-9 cursor-pointer select-none items-center justify-center font-mono text-[12px] '
      + 'max-[639px]:min-h-11 rounded-l-[5px] rounded-r-[5px] bg-plex-surface-light text-plex-text-secondary '
      + 'opacity-100 hover:bg-white/8',
    );
  });

  it('adds the focus outline only for the cursor cell, and drops empty ring and hover entries', () => {
    expect(cellClasses({ ...base, cursor: true, today: true, away: true })).toBe(
      'flex min-h-9 cursor-pointer select-none items-center justify-center font-mono text-[12px] '
      + 'max-[639px]:min-h-11 rounded-l-[5px] rounded-r-[5px] bg-tide/55 text-plex-text opacity-100 '
      + 'inset-ring-[1.5px] inset-ring-plex-text '
      + 'group-focus-visible:outline-2 group-focus-visible:outline-offset-1 group-focus-visible:outline-plex-text',
    );
  });

  it.each(['away', 'selected', 'draft', 'today'])('uses the strong text colour when only %s is set', (flag) => {
    const c = classes({ [flag]: true });
    expect(c).toContain('text-plex-text');
    expect(c).not.toContain('text-plex-text-secondary');
  });

  it('uses the secondary text colour when none is set', () => {
    expect(classes({})).toContain('text-plex-text-secondary');
  });

  it.each(['away', 'selected', 'draft'])('has no hover class when only %s is set', (flag) => {
    expect(classes({ [flag]: true })).not.toContain('hover:bg-white/8');
  });

  it('keeps the hover class on a today-only cell', () => {
    expect(classes({ today: true })).toContain('hover:bg-white/8');
  });

  it('joins sides: a left join pulls the cell over and squares its left edge, a right join squares the right', () => {
    const left = classes({ joinLeft: true });
    expect(left).toContain('-ml-[3px]');
    expect(left).toContain('rounded-l-none');
    expect(left).not.toContain('rounded-l-[5px]');
    expect(left).toContain('rounded-r-[5px]');
    const right = classes({ joinRight: true });
    expect(right).toContain('rounded-r-none');
    expect(right).not.toContain('rounded-r-[5px]');
    expect(right).toContain('rounded-l-[5px]');
  });

  it('resolves background, opacity and ring in the stated cascade', () => {
    expect(classes({ away: true, selected: true, draft: true })).toContain('bg-tide/18');
    expect(classes({ away: true, selected: true })).toContain('bg-tide/90');
    expect(classes({ away: true })).toContain('bg-tide/55');
    expect(classes({ past: true, outside: true })).toContain('opacity-45');
    expect(classes({ outside: true })).toContain('opacity-35');
    expect(classes({ draft: true, past: true, outside: true })).toContain('opacity-100');
    expect(classes({ draft: true, today: true })).toContain('inset-ring-tide/90');
    expect(classes({ today: true })).toContain('inset-ring-plex-text');
  });
});

describe('cellLabel', () => {
  it('names an away day without a note plainly', () => {
    expect(cellLabel({ iso: '2026-11-02', isToday: false }, range(1, '2026-11-02'), undefined, {}))
      .toBe('Mon 2 Nov, away');
  });
});

describe('validateRange', () => {
  const MSG = 'Both a start and end date are required';

  it('flags only the field that is missing', () => {
    expect(validateRange({ startDate: '', endDate: '2026-11-02' })).toStrictEqual({ startDate: MSG });
    expect(validateRange({ startDate: '2026-11-02', endDate: '' })).toStrictEqual({ endDate: MSG });
    expect(validateRange({ startDate: '', endDate: '' })).toStrictEqual({ startDate: MSG, endDate: MSG });
  });
});

describe('placeServerError', () => {
  const serverError = (error, status = 400) => ({ response: { status, data: { error } } });
  const fallback = { field: null, message: 'fb' };

  it('returns the fallback when there is no error, no response or no body', () => {
    expect(placeServerError(null, 'fb')).toStrictEqual(fallback);
    expect(placeServerError(undefined, 'fb')).toStrictEqual(fallback);
    expect(placeServerError({}, 'fb')).toStrictEqual(fallback);
    expect(placeServerError({ response: { status: 400 } }, 'fb')).toStrictEqual(fallback);
    expect(placeServerError({ response: { status: 400, data: null } }, 'fb')).toStrictEqual(fallback);
  });

  it('returns the fallback for a non-400, a non-string or a blank sentence', () => {
    expect(placeServerError(serverError('note must be at most 200 characters', 500), 'fb')).toStrictEqual(fallback);
    expect(placeServerError(serverError(42), 'fb')).toStrictEqual(fallback);
    expect(placeServerError(serverError('   '), 'fb')).toStrictEqual(fallback);
    expect(placeServerError(serverError(''), 'fb')).toStrictEqual(fallback);
  });

  it('places each known sentence under its field', () => {
    expect(placeServerError(serverError('note must be at most 200 characters'), 'fb'))
      .toStrictEqual({ field: 'note', message: 'note must be at most 200 characters' });
    expect(placeServerError(serverError('endDate must not be before startDate'), 'fb'))
      .toStrictEqual({ field: 'endDate', message: 'endDate must not be before startDate' });
    expect(placeServerError(serverError('startDate and endDate are required'), 'fb'))
      .toStrictEqual({ field: 'startDate', message: 'startDate and endDate are required' });
  });

  it('matches a field name only at the start of the sentence, and only as a whole word', () => {
    ['the note is bad', 'the endDate is bad', 'the startDate is bad', 'notebook full', 'startDates clash',
      'Something else broke'].forEach((sentence) => {
      expect(placeServerError(serverError(sentence), 'fb')).toStrictEqual({ field: null, message: sentence });
    });
  });
});
