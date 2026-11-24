import { describe, it, expect, vi, afterEach } from 'vitest';
import { clearStaleManageAddress } from '../utils/manageAddress.js';

afterEach(() => {
  vi.restoreAllMocks();
  window.history.replaceState(null, '', '/');
});

describe('clearStaleManageAddress', () => {
  it('replaces an Operations address with the bare path, in place, copying the state', () => {
    window.history.replaceState({ phLayer: 1 }, '', '/?x=1#manage/runs?view=cost');
    const before = window.history.length;
    expect(clearStaleManageAddress()).toBe(true);
    expect(window.location.hash).toBe('');
    expect(window.location.search).toBe('?x=1');
    expect(window.history.state).toEqual({ phLayer: 1 });
    expect(window.history.length).toBe(before);
  });

  it('leaves the map\'s own address and any other fragment alone', () => {
    window.history.replaceState(null, '', '#map/2026-11-24/sunrise');
    expect(clearStaleManageAddress()).toBe(false);
    expect(window.location.hash).toBe('#map/2026-11-24/sunrise');
  });

  it('survives a replaceState that throws', () => {
    window.history.replaceState(null, '', '#manage/runs');
    vi.spyOn(window.history, 'replaceState').mockImplementation(() => { throw new Error('SecurityError'); });
    expect(clearStaleManageAddress()).toBe(false);
  });
});
