import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  DEFAULT_SCHEDULER_VIEW, dropInvalidDate, isSchedulerHash, readSchedulerQuery, writeSchedulerQuery,
} from '../utils/schedulerQuery.js';

const STORAGE_KEY = 'photocast.scheduler.view';

beforeEach(() => {
  window.localStorage.clear();
  window.location.hash = '#manage/scheduler';
});

describe('readSchedulerQuery', () => {
  it('opens the default tab, Day\'s jobs, for a bare address with nothing remembered', () => {
    expect(DEFAULT_SCHEDULER_VIEW).toBe('day');
    expect(readSchedulerQuery()).toEqual({ view: 'day', date: null });
  });

  it('reads the view and a valid date from the query', () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    expect(readSchedulerQuery()).toEqual({ view: 'day', date: '2026-11-02' });
  });

  it('reads a query that sits after a job key', () => {
    window.location.hash = '#manage/scheduler/tide_refresh?view=all&date=2026-11-02';
    expect(readSchedulerQuery()).toEqual({ view: 'all', date: '2026-11-02' });
  });

  it('lets the hash win over the remembered tab, both ways', () => {
    window.localStorage.setItem(STORAGE_KEY, 'day');
    window.location.hash = '#manage/scheduler?view=all';
    expect(readSchedulerQuery().view).toBe('all');
    window.localStorage.setItem(STORAGE_KEY, 'all');
    window.location.hash = '#manage/scheduler?view=day';
    expect(readSchedulerQuery().view).toBe('day');
  });

  it('falls back to the remembered tab for an invalid view, and drops a malformed date', () => {
    window.localStorage.setItem(STORAGE_KEY, 'all');
    window.location.hash = '#manage/scheduler?view=bogus&date=yesterday';
    expect(readSchedulerQuery()).toEqual({ view: 'all', date: null });
  });

  it('rejects a date that is not on the calendar (2026-02-31) and keeps a real one', () => {
    window.location.hash = '#manage/scheduler?date=2026-02-31';
    expect(readSchedulerQuery().date).toBeNull();
    window.location.hash = '#manage/scheduler?date=2026-02-28';
    expect(readSchedulerQuery().date).toBe('2026-02-28');
  });

  it('survives storage that throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    try {
      expect(readSchedulerQuery().view).toBe(DEFAULT_SCHEDULER_VIEW);
    } finally {
      spy.mockRestore();
    }
  });
});

describe('writeSchedulerQuery', () => {
  it('writes the view with replaceState (no new history entry) and remembers it', () => {
    const before = window.history.length;
    writeSchedulerQuery({ view: 'day' });
    expect(window.location.hash).toBe('#manage/scheduler?view=day');
    expect(window.history.length).toBe(before);
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('day');
  });

  it('keeps the job key in the path and the other query keys, and removes a null key', () => {
    window.location.hash = '#manage/scheduler/tide_refresh?view=all&date=2026-11-02';
    writeSchedulerQuery({ view: 'day' });
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh?view=day&date=2026-11-02');
    writeSchedulerQuery({ date: null });
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh?view=day');
  });

  it('still writes the address when storage throws', () => {
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    try {
      writeSchedulerQuery({ view: 'day' });
      expect(window.location.hash).toBe('#manage/scheduler?view=day');
    } finally {
      spy.mockRestore();
    }
  });
});

describe('isSchedulerHash', () => {
  it.each([
    ['#manage/scheduler', true],
    ['#manage/scheduler/tide_refresh?view=day', true],
    ['#manage/runs', false],
    ['#plan', false],
  ])('reads %s as %s', (hash, expected) => {
    window.location.hash = hash;
    expect(isSchedulerHash()).toBe(expected);
  });
});

describe('dropInvalidDate', () => {
  it('removes a date that is not a date and keeps the rest of the address', () => {
    window.location.hash = '#manage/scheduler/tide_refresh?view=day&date=2026-02-31';
    dropInvalidDate();
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh?view=day');
  });

  it('leaves a real date, or none, alone', () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    dropInvalidDate();
    expect(window.location.hash).toBe('#manage/scheduler?view=day&date=2026-11-02');
    window.location.hash = '#manage/scheduler?view=all';
    dropInvalidDate();
    expect(window.location.hash).toBe('#manage/scheduler?view=all');
  });
});
