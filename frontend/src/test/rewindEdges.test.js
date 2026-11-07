import { describe, it, expect, afterEach, vi } from 'vitest';
import { appNow, getRewind, getRewindTo, setRewind, subscribeRewind } from '../utils/rewind.js';

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('setRewind — clearing', () => {
  it('clears on null and on undefined alike, without complaint', () => {
    setRewind('2026-11-02T10:00:00Z');
    expect(getRewindTo()).toBe('2026-11-02T10:00:00Z');
    expect(() => setRewind(undefined)).not.toThrow();
    expect(getRewind()).toBeNull();
    setRewind('2026-11-02T10:00:00Z');
    setRewind(null);
    expect(getRewind()).toBeNull();
    expect(getRewindTo()).toBeNull();
  });

  it('tells subscribers when it clears with undefined', () => {
    const listener = vi.fn();
    const off = subscribeRewind(listener);
    setRewind(undefined);
    expect(listener).toHaveBeenCalledTimes(1);
    off();
    setRewind(undefined);
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('refuses a value that is not an instant, and keeps the previous rewind', () => {
    setRewind('2026-11-02T10:00:00Z');
    expect(() => setRewind('nonsense')).toThrow('setRewind: not an instant: nonsense');
    expect(getRewindTo()).toBe('2026-11-02T10:00:00Z');
  });

  it('keeps the focus, and defaults it to null', () => {
    setRewind('2026-11-02T10:00:00Z', { id: 4 });
    expect(getRewind()).toEqual({ to: '2026-11-02T10:00:00Z', focus: { id: 4 } });
    setRewind('2026-11-02T10:00:00Z');
    expect(getRewind().focus).toBeNull();
  });
});

describe('appNow', () => {
  it('is the rewound instant while one is set, else the real clock', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-07T12:00:00Z'));
    expect(appNow().toISOString()).toBe('2026-11-07T12:00:00.000Z');
    setRewind('2026-11-02T10:00:00Z');
    expect(appNow().toISOString()).toBe('2026-11-02T10:00:00.000Z');
  });
});
