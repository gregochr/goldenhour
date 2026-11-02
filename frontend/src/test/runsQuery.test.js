import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  dayFilterForGroup, normalizeRunsQuery, readRunsQuery, writeRunsQuery,
} from '../utils/runsQuery.js';

// The Runs tab's address helper (runs-deep-dive-plan.md DD-9, F4): read, merge and write the hash query.

const NOW = new Date('2026-11-02T09:26:00Z');

beforeEach(() => {
  window.location.hash = '#manage/runs';
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('readRunsQuery', () => {
  it('defaults to the Day view, today and no filter', () => {
    expect(readRunsQuery(NOW)).toEqual({ view: 'day', date: null, filter: 'all' });
  });

  it('reads the view, a past date and a filter', () => {
    window.location.hash = '#manage/runs?view=timing&date=2026-11-01&filter=ask_ready';
    expect(readRunsQuery(NOW)).toEqual({ view: 'timing', date: '2026-11-01', filter: 'ask_ready' });
  });

  it('reads the query beside a panel path', () => {
    window.location.hash = '#manage/runs/job/12?view=cost';
    expect(readRunsQuery(NOW).view).toBe('cost');
  });

  it('treats today, a future date and a malformed one as "today"', () => {
    for (const date of ['2026-11-02', '2026-11-03', '2999-01-01', 'banana', '2026-13-45']) {
      window.location.hash = `#manage/runs?date=${date}`;
      expect(readRunsQuery(NOW).date).toBeNull();
    }
  });

  it('rewrites a date equal to today (or later) out of the address', () => {
    window.location.hash = '#manage/runs?view=day&date=2026-11-02';
    expect(readRunsQuery(NOW).date).toBeNull();
    expect(window.location.hash).toBe('#manage/runs?view=day');
  });

  it('ignores an unknown view or filter', () => {
    window.location.hash = '#manage/runs?view=spend&filter=nope';
    expect(readRunsQuery(NOW)).toEqual({ view: 'day', date: null, filter: 'all' });
  });

  it('falls back to the remembered view, but the hash wins', () => {
    window.localStorage.setItem('photocast.runs.view', 'cost');
    expect(readRunsQuery(NOW).view).toBe('cost');
    window.location.hash = '#manage/runs?view=day';
    expect(readRunsQuery(NOW).view).toBe('day');
  });

  it('survives storage that throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(readRunsQuery(NOW).view).toBe('day');
  });
});

describe('legacy ?range=&kind=&attention= (mapped once, rewritten out)', () => {
  it.each([
    ['kind=cycles', 'filter=cycles'],
    ['kind=BRIEFING', 'filter=briefing'],
    ['kind=aurora', 'filter=other'],
    ['kind=tide', 'filter=other'],
    ['kind=batches', 'filter=other'],
    ['kind=other', 'filter=other'],
    ['kind=all', ''],
    ['range=today', ''],
    ['kind=cycles&attention=true', 'filter=attention'],
    ['attention=1', 'filter=attention'],
    ['range=month&kind=tide&attention=false', 'filter=other'],
  ])('%s becomes %s', (legacy, expected) => {
    window.location.hash = `#manage/runs?${legacy}`;
    normalizeRunsQuery();
    expect(window.location.hash).toBe(expected === '' ? '#manage/runs' : `#manage/runs?${expected}`);
  });

  it('keeps the path and the other keys, and does not overwrite an explicit filter', () => {
    window.location.hash = '#manage/runs/cycle/9?view=day&kind=tide&filter=cycles';
    normalizeRunsQuery();
    expect(window.location.hash).toBe('#manage/runs/cycle/9?view=day&filter=cycles');
  });

  it('leaves an address with none of them alone, adding no history entry', () => {
    window.location.hash = '#manage/runs?view=day';
    const entries = window.history.length;
    normalizeRunsQuery();
    expect(window.location.hash).toBe('#manage/runs?view=day');
    expect(window.history.length).toBe(entries);
  });
});

describe('writeRunsQuery', () => {
  it('merges into the query, keeping the panel path, with no history entry', () => {
    window.location.hash = '#manage/runs/job/5?view=day';
    const entries = window.history.length;
    writeRunsQuery({ date: '2026-11-01' });
    expect(window.location.hash).toBe('#manage/runs/job/5?view=day&date=2026-11-01');
    writeRunsQuery({ filter: 'cycles' });
    expect(window.location.hash).toBe('#manage/runs/job/5?view=day&date=2026-11-01&filter=cycles');
    expect(window.history.length).toBe(entries);
  });

  it('removes a key set to null or empty, and the default filter', () => {
    window.location.hash = '#manage/runs?date=2026-11-01&filter=cycles';
    writeRunsQuery({ date: null });
    expect(window.location.hash).toBe('#manage/runs?filter=cycles');
    writeRunsQuery({ filter: 'all' });
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('remembers the view, and never fails when storage throws', () => {
    writeRunsQuery({ view: 'cost' });
    expect(window.localStorage.getItem('photocast.runs.view')).toBe('cost');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => writeRunsQuery({ view: 'timing' })).not.toThrow();
    expect(window.location.hash).toBe('#manage/runs?view=timing');
  });

  it('fires no hashchange', () => {
    const onChange = vi.fn();
    window.addEventListener('hashchange', onChange);
    writeRunsQuery({ view: 'cost' });
    window.removeEventListener('hashchange', onChange);
    expect(onChange).not.toHaveBeenCalled();
  });
});

describe('dayFilterForGroup (the Scheduler\'s "All in Runs")', () => {
  it.each([
    ['CYCLES', 'cycles'], ['BRIEFING', 'briefing'], ['TIDE', 'other'], ['AURORA', 'other'], ['BATCHES', 'other'],
    ['OTHER', 'other'], [null, 'all'], [undefined, 'all'],
  ])('%s opens %s', (group, expected) => {
    expect(dayFilterForGroup(group)).toBe(expected);
  });
});
