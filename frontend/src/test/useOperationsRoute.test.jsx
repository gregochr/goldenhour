import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import useOperationsRoute, { isManageHash } from '../hooks/useOperationsRoute.js';

/** Puts the page at a path and fragment with no event (the address a fresh load would start from). */
function setAddress(path = '/', hash = '') {
  window.history.replaceState({ keep: 'state' }, '', `${path}${hash}`);
}

describe('isManageHash', () => {
  it('matches #manage, #manage/..., #manage?... and nothing that merely starts with the letters', () => {
    expect(isManageHash('#manage')).toBe(true);
    expect(isManageHash('#manage/runs')).toBe(true);
    expect(isManageHash('#manage/runs/start')).toBe(true);
    expect(isManageHash('#manage?x=1')).toBe(true);
    expect(isManageHash('#managers')).toBe(false);
    expect(isManageHash('#map/2026-11-24/sunrise')).toBe(false);
    expect(isManageHash('')).toBe(false);
    expect(isManageHash(undefined)).toBe(false);
  });
});

describe('useOperationsRoute', () => {
  beforeEach(() => setAddress('/'));
  afterEach(() => setAddress('/'));

  it('is closed on a bare address', () => {
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    expect(result.current.isOpen).toBe(false);
  });

  it('opens at load for an admin whose address already says #manage/runs', () => {
    setAddress('/', '#manage/runs');
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    expect(result.current.isOpen).toBe(true);
  });

  it('open() sets the Runs hash by default and the view follows the hashchange', async () => {
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    await act(async () => {
      result.current.open();
      await new Promise((resolve) => { setTimeout(resolve, 0); });
    });
    expect(window.location.hash).toBe('#manage/runs');
    expect(result.current.isOpen).toBe(true);
  });

  it('open(hash) opens at a given address (the aurora banner\'s Start a run)', async () => {
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    await act(async () => {
      result.current.open('manage/runs/start');
      await new Promise((resolve) => { setTimeout(resolve, 0); });
    });
    expect(window.location.hash).toBe('#manage/runs/start');
    expect(result.current.isOpen).toBe(true);
  });

  it('close() pushes the bare path (one new entry, no marker) and closes at once', () => {
    setAddress('/forecast', '#manage/runs');
    const before = window.history.length;
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    act(() => result.current.close());
    expect(result.current.isOpen).toBe(false);
    expect(window.location.hash).toBe('');
    expect(window.location.pathname).toBe('/forecast');
    expect(window.history.state).toBeNull();
    expect(window.history.length).toBe(before + 1);
  });

  it('follows Back and Forward: a popstate onto or off a #manage address opens and closes the view', () => {
    setAddress('/');
    const { result } = renderHook(() => useOperationsRoute({ allowed: true }));
    act(() => {
      window.history.replaceState(null, '', '/#manage/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(result.current.isOpen).toBe(true);
    act(() => {
      window.history.replaceState(null, '', '/');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    expect(result.current.isOpen).toBe(false);
  });

  it('never opens for a reader who may not, and clears #manage from the address keeping history.state', () => {
    setAddress('/', '#manage/runs');
    const { result } = renderHook(() => useOperationsRoute({ allowed: false }));
    expect(result.current.isOpen).toBe(false);
    expect(window.location.hash).toBe('');
    expect(window.history.state).toEqual({ keep: 'state' });
  });

  it('clears a #manage hash typed while the page is up, for a reader who may not', async () => {
    const { result } = renderHook(() => useOperationsRoute({ allowed: false }));
    await act(async () => {
      window.location.hash = 'manage/runs';
      await new Promise((resolve) => { setTimeout(resolve, 0); });
    });
    expect(result.current.isOpen).toBe(false);
    expect(window.location.hash).toBe('');
  });

  it('open() does nothing for a reader who may not', async () => {
    const { result } = renderHook(() => useOperationsRoute({ allowed: false }));
    await act(async () => {
      result.current.open();
      await new Promise((resolve) => { setTimeout(resolve, 0); });
    });
    expect(window.location.hash).toBe('');
    expect(result.current.isOpen).toBe(false);
  });

  it('leaves another fragment alone, whoever the reader is', () => {
    setAddress('/', '#map/2026-11-24/sunrise');
    renderHook(() => useOperationsRoute({ allowed: false }));
    expect(window.location.hash).toBe('#map/2026-11-24/sunrise');
  });
});
