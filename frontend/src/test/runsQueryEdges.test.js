import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  normalizeRunsQuery, readRunsQuery, runsDayHref, writeRunsQuery,
} from '../utils/runsQuery.js';

// Edge pins for the Runs tab's address helper: what counts as an ISO date (anchored, and checked by the date parser
// too), what counts as a numeric range, when the legacy rewrite touches the address, and the exact write rules.

const NOW = new Date('2026-11-02T09:26:00Z');
const KEY = 'photocast.runs.view';

beforeEach(() => {
  window.location.hash = '#manage/runs';
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe('a date is an ISO date, nothing more and nothing less', () => {
  it('reads a well-formed past date', () => {
    window.location.hash = '#manage/runs?date=2026-10-01';
    expect(readRunsQuery(NOW).date).toBe('2026-10-01');
  });

  it.each([
    ['trailing text the date parser tolerates', '2026-10-01('],
    ['a one-digit day the date parser tolerates', '2026-10-1('],
    ['leading text the date parser tolerates', '1(2026-10-01'],
    ['a month-first form', '10-01-2026'],
    ['a year alone', '2026'],
  ])('reads %s as no date', (_why, value) => {
    window.location.hash = `#manage/runs?date=${encodeURIComponent(value)}`;
    expect(readRunsQuery(NOW).date).toBeNull();
  });

  it('reads a date the parser rejects (month 13) as no date', () => {
    window.location.hash = '#manage/runs?date=2026-13-01';
    expect(readRunsQuery(NOW).date).toBeNull();
  });

  it('links a Day href only for an ISO date', () => {
    expect(runsDayHref('2026-10-01')).toBe('#manage/runs?view=day&date=2026-10-01');
    expect(runsDayHref('2026-10-01(')).toBe('#manage/runs?view=day');
    expect(runsDayHref('1(2026-10-01')).toBe('#manage/runs?view=day');
    expect(runsDayHref(undefined)).toBe('#manage/runs?view=day');
  });
});

describe('the legacy range is the word form only', () => {
  it.each([
    ['range=x5'],
    ['range=5x'],
    ['range=month'],
    ['range='],
  ])('drops %s from the address and reads no range', (legacy) => {
    window.location.hash = `#manage/runs?view=cost&${legacy}`;
    expect(readRunsQuery(NOW).range).toBeNull();
    expect(window.location.hash).toBe('#manage/runs?view=cost');
  });

  it.each(['5', '30', '120'])('keeps a numeric range of %s days', (days) => {
    window.location.hash = `#manage/runs?view=cost&range=${days}`;
    expect(readRunsQuery(NOW).range).toBe(Number(days));
    expect(window.location.hash).toBe(`#manage/runs?view=cost&range=${days}`);
  });

  it('drops a word-form range when it rides beside a numeric-looking neighbour key', () => {
    window.location.hash = '#manage/runs?range=12ab&kind=cycles';
    normalizeRunsQuery();
    expect(window.location.hash).toBe('#manage/runs?filter=cycles');
  });
});

describe('normalizeRunsQuery leaves an address with nothing legacy untouched', () => {
  it('does not rewrite the address at all', () => {
    window.location.hash = '#manage/runs?view=day&filter=cycles&range=30';
    const replace = vi.spyOn(window.history, 'replaceState');
    normalizeRunsQuery();
    expect(replace).not.toHaveBeenCalled();
  });

  it('does rewrite the address when something legacy is there', () => {
    window.location.hash = '#manage/runs?attention=true';
    const replace = vi.spyOn(window.history, 'replaceState');
    normalizeRunsQuery();
    expect(replace).toHaveBeenCalledTimes(1);
    expect(window.location.hash).toBe('#manage/runs?filter=attention');
  });

  it('removes the legacy keys but keeps a numeric range beside them', () => {
    window.location.hash = '#manage/runs?range=14&kind=tide';
    normalizeRunsQuery();
    expect(window.location.hash).toBe('#manage/runs?range=14&filter=other');
  });
});

describe('writeRunsQuery', () => {
  it('removes a key set to an empty string', () => {
    window.location.hash = '#manage/runs?date=2026-11-01';
    writeRunsQuery({ date: '' });
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('keeps zero, which is a value', () => {
    writeRunsQuery({ range: 0 });
    expect(window.location.hash).toBe('#manage/runs?range=0');
  });

  it('removes the default only for the key it is the default of', () => {
    writeRunsQuery({ date: 'all', type: 'ready' });
    expect(window.location.hash).toBe('#manage/runs?date=all&type=ready');
    writeRunsQuery({ filter: 'ready', measure: 'all' });
    expect(window.location.hash).toBe('#manage/runs?date=all&type=ready&filter=ready&measure=all');
  });

  it('removes the default filter and the default measure, and writes the other measure', () => {
    writeRunsQuery({ filter: 'cycles', measure: 'cycle' });
    expect(window.location.hash).toBe('#manage/runs?filter=cycles&measure=cycle');
    writeRunsQuery({ filter: 'all', measure: 'ready' });
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('remembers nothing when no view is written', () => {
    writeRunsQuery({ date: '2026-11-01' });
    expect(window.localStorage.getItem(KEY)).toBeNull();
  });

  it('remembers the view when one is written', () => {
    writeRunsQuery({ view: 'timing' });
    expect(window.localStorage.getItem(KEY)).toBe('timing');
  });
});
