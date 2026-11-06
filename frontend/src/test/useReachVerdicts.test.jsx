import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import useReachVerdicts from '../hooks/useReachVerdicts.js';
import { getReachVerdicts } from '../api/settingsApi.js';

vi.mock('../api/settingsApi.js', () => ({ getReachVerdicts: vi.fn() }));

/**
 * The fetch behind the reach-scoped headline (reach-scoped-card-plan R2 step 7).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's page calls an endpoint that answers 403, a tier tap costs a
 * request (or shows a flash of the roster-wide word while it runs), a response computed from an older briefing is
 * painted over a newer one, or a failed request paints a different headline instead of none.
 */

const TIERS = { 45: [{ date: '2026-08-04', targetType: 'SUNSET', displayVerdict: 'STAND_DOWN' }], 90: [], 150: [] };

function body(version, overrides = {}) {
  return { measured: true, briefingVersion: version, tiers: TIERS, ...overrides };
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

describe('useReachVerdicts', () => {
  beforeEach(() => {
    getReachVerdicts.mockReset();
  });

  it('fetches once for a version and home, and answers the tiers when the version matches', async () => {
    getReachVerdicts.mockResolvedValue(body('v1'));

    const { result } = renderHook(() => useReachVerdicts({ enabled: true, briefingVersion: 'v1', originId: null }));

    expect(result.current).toBeNull();
    await waitFor(() => expect(result.current).toBe(TIERS));
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    expect(getReachVerdicts).toHaveBeenCalledWith(null);
  });

  it.each([
    ['not enabled (Lite, drive-locked, shaped body or an unmeasured reader)', { enabled: false, briefingVersion: 'v1' }],
    ['the briefing carries no version', { enabled: true, briefingVersion: null }],
    ['the briefing version is undefined (an older backend)', { enabled: true, briefingVersion: undefined }],
  ])('never asks when %s', async (_n, args) => {
    const { result } = renderHook(() => useReachVerdicts({ originId: null, ...args }));

    await act(async () => { await Promise.resolve(); });
    expect(getReachVerdicts).not.toHaveBeenCalled();
    expect(result.current).toBeNull();
  });

  it('does not refetch on a re-render, which is what a tier tap is to the hook', async () => {
    getReachVerdicts.mockResolvedValue(body('v1'));
    const { result, rerender } = renderHook(
      (props) => useReachVerdicts(props),
      { initialProps: { enabled: true, briefingVersion: 'v1', originId: null } },
    );
    await waitFor(() => expect(result.current).toBe(TIERS));

    rerender({ enabled: true, briefingVersion: 'v1', originId: null });
    rerender({ enabled: true, briefingVersion: 'v1', originId: null });

    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    expect(result.current).toBe(TIERS);
  });

  it('asks again for a new briefing version, and answers null in between rather than the old tiers', async () => {
    const second = deferred();
    getReachVerdicts.mockResolvedValueOnce(body('v1')).mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(
      (props) => useReachVerdicts(props),
      { initialProps: { enabled: true, briefingVersion: 'v1', originId: null } },
    );
    await waitFor(() => expect(result.current).toBe(TIERS));

    rerender({ enabled: true, briefingVersion: 'v2', originId: null });

    expect(result.current).toBeNull();
    await waitFor(() => expect(getReachVerdicts).toHaveBeenCalledTimes(2));
    const next = { ...TIERS };
    await act(async () => { second.resolve(body('v2', { tiers: next })); });
    expect(result.current).toBe(next);
  });

  it('asks for the away origin\'s region, and again when the origin changes', async () => {
    getReachVerdicts.mockImplementation((origin) => Promise.resolve(body('v1', { tiers: { origin } })));
    const { result, rerender } = renderHook(
      (props) => useReachVerdicts(props),
      { initialProps: { enabled: true, briefingVersion: 'v1', originId: 7 } },
    );
    await waitFor(() => expect(result.current).toEqual({ origin: 7 }));
    expect(getReachVerdicts).toHaveBeenLastCalledWith(7);

    rerender({ enabled: true, briefingVersion: 'v1', originId: null });
    await waitFor(() => expect(result.current).toEqual({ origin: null }));
    expect(getReachVerdicts).toHaveBeenLastCalledWith(null);
    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
  });

  it('discards an answer for another version and refetches ONCE', async () => {
    getReachVerdicts.mockResolvedValueOnce(body('old')).mockResolvedValueOnce(body('v1'));

    const { result } = renderHook(() => useReachVerdicts({ enabled: true, briefingVersion: 'v1', originId: null }));

    await waitFor(() => expect(result.current).toBe(TIERS));
    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
  });

  it('gives up after the second mismatch: no tiers, and no third request', async () => {
    getReachVerdicts.mockResolvedValue(body('old'));

    const { result } = renderHook(() => useReachVerdicts({ enabled: true, briefingVersion: 'v1', originId: null }));

    await waitFor(() => expect(getReachVerdicts).toHaveBeenCalledTimes(2));
    await act(async () => { await Promise.resolve(); });
    expect(result.current).toBeNull();
    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
  });

  it.each([
    ['a rejection', () => getReachVerdicts.mockRejectedValue(new Error('boom'))],
    ['a synchronous throw (a missing export in a double)', () => getReachVerdicts.mockImplementation(() => {
      throw new Error('no such export');
    })],
    ['an unmeasured answer', () => getReachVerdicts.mockResolvedValue(body('v1', { measured: false }))],
    ['an empty body', () => getReachVerdicts.mockResolvedValue(null)],
    ['a body with no tiers', () => getReachVerdicts.mockResolvedValue({ measured: true, briefingVersion: 'v1' })],
  ])('answers null, never a different headline, on %s', async (_n, arrange) => {
    arrange();

    const { result } = renderHook(() => useReachVerdicts({ enabled: true, briefingVersion: 'v1', originId: null }));

    await waitFor(() => expect(getReachVerdicts).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });
    expect(result.current).toBeNull();
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
  });

  it('drops an answer that lands after the question changed', async () => {
    const slow = deferred();
    getReachVerdicts.mockReturnValueOnce(slow.promise).mockResolvedValueOnce(body('v2'));
    const { result, rerender } = renderHook(
      (props) => useReachVerdicts(props),
      { initialProps: { enabled: true, briefingVersion: 'v1', originId: null } },
    );

    rerender({ enabled: true, briefingVersion: 'v2', originId: null });
    await waitFor(() => expect(result.current).toBe(TIERS));
    await act(async () => { slow.resolve(body('v1', { tiers: { stale: true } })); });

    expect(result.current).toBe(TIERS);
  });

  it('stops answering the moment it is disabled (a demotion, a shaped body, a cleared home)', async () => {
    getReachVerdicts.mockResolvedValue(body('v1'));
    const { result, rerender } = renderHook(
      (props) => useReachVerdicts(props),
      { initialProps: { enabled: true, briefingVersion: 'v1', originId: null } },
    );
    await waitFor(() => expect(result.current).toBe(TIERS));

    rerender({ enabled: false, briefingVersion: 'v1', originId: null });

    expect(result.current).toBeNull();
  });
});
