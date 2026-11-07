import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  dropInvalidDate, isSchedulerHash, readSchedulerQuery, writeSchedulerQuery,
} from '../utils/schedulerQuery.js';

const STORAGE_KEY = 'photocast.scheduler.view';

describe('schedulerQuery edges', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.location.hash = '#manage/scheduler';
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('is not the scheduler address under another area', () => {
    window.location.hash = '#other/scheduler';
    expect(isSchedulerHash()).toBe(false);
  });

  it('removes a key written as an empty string rather than leaving "key="', () => {
    window.location.hash = '#manage/scheduler?view=all&date=2026-11-02';
    writeSchedulerQuery({ date: '' });
    expect(window.location.hash).toBe('#manage/scheduler?view=all');
  });

  it('does not remember a view when the patch carries none', () => {
    writeSchedulerQuery({ date: '2026-11-03' });
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(window.location.hash).toBe('#manage/scheduler?date=2026-11-03');
  });

  it('writes a bare path with no trailing question mark when the query empties', () => {
    window.location.hash = '#manage/scheduler?date=2026-11-02';
    writeSchedulerQuery({ date: null });
    expect(window.location.hash).toBe('#manage/scheduler');
  });

  it('remembers the view it writes', () => {
    writeSchedulerQuery({ view: 'all' });
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('all');
    window.location.hash = '#manage/scheduler';
    expect(readSchedulerQuery().view).toBe('all');
  });

  it('ignores a remembered value that is not a known view', () => {
    window.localStorage.setItem(STORAGE_KEY, 'weekly');
    expect(readSchedulerQuery().view).toBe('day');
  });

  it('treats a date with stray characters around it as no date', () => {
    window.location.hash = '#manage/scheduler?date=2026-11-02x';
    expect(readSchedulerQuery().date).toBeNull();
    window.location.hash = '#manage/scheduler?date=x2026-11-02';
    expect(readSchedulerQuery().date).toBeNull();
    window.location.hash = '#manage/scheduler?date=';
    expect(readSchedulerQuery().date).toBeNull();
  });

  it('drops an empty date from the address', () => {
    window.location.hash = '#manage/scheduler?view=all&date=';
    dropInvalidDate();
    expect(window.location.hash).toBe('#manage/scheduler?view=all');
  });
});
