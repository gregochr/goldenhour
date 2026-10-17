import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  appNow, getRewind, getRewindTo, setRewind, subscribeRewind,
} from '../utils/rewind.js';

/**
 * The rewind store: one module-level instant and one clock read for the whole client.
 */
describe('utils/rewind', () => {
  afterEach(() => {
    setRewind(null);
    vi.useRealTimers();
  });

  it('is live by default: no rewind, and appNow is the wall clock', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-04T09:30:00Z'));
    expect(getRewind()).toBeNull();
    expect(getRewindTo()).toBeNull();
    expect(appNow().toISOString()).toBe('2026-10-04T09:30:00.000Z');
  });

  it('appNow answers with the rewound instant while a rewind is set, and the wall clock again after', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-04T09:30:00Z'));
    setRewind('2026-10-04T05:58:00Z', { date: '2026-10-04', eventType: 'SUNRISE' });
    expect(appNow().toISOString()).toBe('2026-10-04T05:58:00.000Z');
    expect(getRewindTo()).toBe('2026-10-04T05:58:00Z');
    expect(getRewind()).toEqual({ to: '2026-10-04T05:58:00Z', focus: { date: '2026-10-04', eventType: 'SUNRISE' } });
    setRewind(null);
    expect(appNow().toISOString()).toBe('2026-10-04T09:30:00.000Z');
    expect(getRewind()).toBeNull();
  });

  it('a rewind without a focus carries focus: null, not undefined', () => {
    setRewind('2026-10-04T05:58:00Z');
    expect(getRewind()).toEqual({ to: '2026-10-04T05:58:00Z', focus: null });
  });

  it('refuses an instant it cannot read rather than rewinding to NaN', () => {
    expect(() => setRewind('yesterday at six')).toThrow(/not an instant/);
    expect(getRewind()).toBeNull();
  });

  it('notifies subscribers on set and on clear, and stops after unsubscribe', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeRewind(listener);
    setRewind('2026-10-04T05:58:00Z');
    setRewind(null);
    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
    setRewind('2026-10-04T05:58:00Z');
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
