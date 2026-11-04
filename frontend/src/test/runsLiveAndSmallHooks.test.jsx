import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

vi.mock('../api/runsApi.js', () => ({ fetchRuns: vi.fn() }));
vi.mock('../api/schedulerApi.js', () => ({ fetchJobDetail: vi.fn() }));
vi.mock('../api/forecastApi.js', () => ({ fetchLocations: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn() }));
vi.mock('../api/areaApi.js', () => ({ fetchAreas: vi.fn(), fetchLocationAliases: vi.fn() }));

import { fetchRuns } from '../api/runsApi.js';
import { fetchJobDetail } from '../api/schedulerApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import { fetchRegions } from '../api/regionApi.js';
import { fetchAreas, fetchLocationAliases } from '../api/areaApi.js';
import useRunsLive, { RUNS_POLL_MS, RUNS_LIVE_POLL_MS } from '../hooks/useRunsLive.js';
import useJobDetail from '../hooks/useJobDetail.js';
import useRovingSlots from '../hooks/useRovingSlots.js';
import useLocationsData from '../hooks/useLocationsData.js';

/** A promise the test settles by hand, to order responses. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

beforeEach(() => {
  vi.resetAllMocks();
});

describe('useRunsLive', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    vi.setSystemTime(new Date('2026-11-04T12:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  /** Settles the pending promise chain without moving the clock. */
  const settle = () => act(async () => { await Promise.resolve(); });

  it('loads the head (size=1) on mount and exposes it with a timestamp and poll count', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 0, liveCycleId: null });
    const { result } = renderHook(() => useRunsLive());
    await settle();

    expect(fetchRuns).toHaveBeenCalledWith({ size: 1 });
    expect(result.current.meta).toEqual({ runningCount: 0, liveCycleId: null });
    expect(result.current.error).toBeNull();
    expect(result.current.updatedAt).toEqual(new Date('2026-11-04T12:00:00Z'));
    expect(result.current.pollCount).toBe(1);
  });

  it('polls every minute while nothing is running', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 0 });
    renderHook(() => useRunsLive());
    await settle();
    expect(fetchRuns).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS * 3); });
    expect(fetchRuns).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(RUNS_POLL_MS - RUNS_LIVE_POLL_MS * 3); });
    expect(fetchRuns).toHaveBeenCalledTimes(2);
  });

  it('polls every 15 seconds once a run is running', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 2, liveCycleId: 7 });
    const { result } = renderHook(() => useRunsLive());
    await settle();
    expect(result.current.meta.liveCycleId).toBe(7);
    // Moving to the faster cadence restarts the poll, which fetches straight away: mount + restart.
    expect(fetchRuns).toHaveBeenCalledTimes(2);

    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS); });
    expect(fetchRuns).toHaveBeenCalledTimes(3);
    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS); });
    expect(fetchRuns).toHaveBeenCalledTimes(4);
    expect(result.current.pollCount).toBe(4);
  });

  it('skips a poll tick while paused, once something has loaded', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 1 });
    let paused = false;
    renderHook(() => useRunsLive({ isPaused: () => paused }));
    await settle();
    const loaded = fetchRuns.mock.calls.length; // mount, plus the restart when the cadence went to 15 s

    paused = true;
    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS * 2); });
    expect(fetchRuns).toHaveBeenCalledTimes(loaded);

    paused = false;
    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS); });
    expect(fetchRuns).toHaveBeenCalledTimes(loaded + 1);
  });

  it('makes the first load even when paused', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 0 });
    const { result } = renderHook(() => useRunsLive({ isPaused: () => true }));
    await settle();
    expect(fetchRuns).toHaveBeenCalledTimes(1);
    expect(result.current.meta).toEqual({ runningCount: 0 });
  });

  it('reports the server message on failure and keeps what was on screen', async () => {
    fetchRuns.mockResolvedValue({ runningCount: 1, liveCycleId: 3 });
    const { result } = renderHook(() => useRunsLive());
    await settle();
    const polls = result.current.pollCount;

    fetchRuns.mockRejectedValue({ response: { data: { error: 'Runs table locked' } }, message: 'x' });
    await act(async () => { vi.advanceTimersByTime(RUNS_LIVE_POLL_MS); });

    expect(result.current.error).toBe('Runs table locked');
    expect(result.current.meta).toEqual({ runningCount: 1, liveCycleId: 3 });
    expect(result.current.pollCount).toBe(polls);
  });

  it('falls back to the error message, then to a generic line', async () => {
    fetchRuns.mockRejectedValueOnce(new Error('Network Error'));
    const { result } = renderHook(() => useRunsLive());
    await settle();
    expect(result.current.error).toBe('Network Error');

    fetchRuns.mockRejectedValueOnce({});
    await act(async () => { await result.current.refresh(); });
    expect(result.current.error).toBe('Failed to load runs');
  });

  it('clears the error on the next successful load', async () => {
    fetchRuns.mockRejectedValueOnce(new Error('boom'));
    const { result } = renderHook(() => useRunsLive());
    await settle();
    expect(result.current.error).toBe('boom');

    fetchRuns.mockResolvedValueOnce({ runningCount: 0 });
    await act(async () => { await result.current.refresh(); });
    expect(result.current.error).toBeNull();
    expect(result.current.meta).toEqual({ runningCount: 0 });
  });

  it('never lets an older answer land over a newer one', async () => {
    const first = deferred();
    const second = deferred();
    fetchRuns.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result } = renderHook(() => useRunsLive());

    let refreshed;
    act(() => { refreshed = result.current.refresh(); });
    await act(async () => { second.resolve({ runningCount: 0, tag: 'newer' }); await refreshed; });
    expect(result.current.meta.tag).toBe('newer');

    await act(async () => { first.resolve({ runningCount: 9, tag: 'older' }); await first.promise; });
    expect(result.current.meta.tag).toBe('newer');
    expect(result.current.pollCount).toBe(1);
  });
});

describe('useJobDetail', () => {
  it('does nothing and is not loading when no job is open', () => {
    const { result } = renderHook(() => useJobDetail(null));
    expect(fetchJobDetail).not.toHaveBeenCalled();
    expect(result.current).toEqual({ detail: null, loading: false, error: false });
  });

  it('is loading until the job detail arrives, then serves it', async () => {
    fetchJobDetail.mockResolvedValue({ typicalSeconds: 42, spend30d: 1.5 });
    const { result } = renderHook(() => useJobDetail('nightly'));
    expect(result.current.loading).toBe(true);
    expect(result.current.detail).toBeNull();

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(fetchJobDetail).toHaveBeenCalledWith('nightly');
    expect(result.current.detail).toEqual({ typicalSeconds: 42, spend30d: 1.5 });
    expect(result.current.error).toBe(false);
  });

  it('serves a null answer as no detail rather than undefined', async () => {
    fetchJobDetail.mockResolvedValue(undefined);
    const { result } = renderHook(() => useJobDetail('nightly'));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.detail).toBeNull();
    expect(result.current.error).toBe(false);
  });

  it('reports an error, with no detail, when the request fails', async () => {
    fetchJobDetail.mockRejectedValue(new Error('500'));
    const { result } = renderHook(() => useJobDetail('nightly'));
    await waitFor(() => expect(result.current.error).toBe(true));
    expect(result.current.detail).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('reports an error when the fetch throws synchronously', async () => {
    fetchJobDetail.mockImplementation(() => { throw new Error('sync'); });
    const { result } = renderHook(() => useJobDetail('nightly'));
    await waitFor(() => expect(result.current.error).toBe(true));
  });

  it('never shows one job\'s detail against another job, and drops a late answer for a job left', async () => {
    const slow = deferred();
    fetchJobDetail.mockReturnValueOnce(slow.promise).mockResolvedValueOnce({ typicalSeconds: 7 });
    const { result, rerender } = renderHook(({ key }) => useJobDetail(key), { initialProps: { key: 'a' } });

    rerender({ key: 'b' });
    await waitFor(() => expect(result.current.detail).toEqual({ typicalSeconds: 7 }));

    await act(async () => { slow.resolve({ typicalSeconds: 999 }); await slow.promise; });
    expect(result.current.detail).toEqual({ typicalSeconds: 7 });
  });

  it('goes back to loading for the new job while its detail is fetched', async () => {
    const second = deferred();
    fetchJobDetail.mockResolvedValueOnce({ typicalSeconds: 1 }).mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(({ key }) => useJobDetail(key), { initialProps: { key: 'a' } });
    await waitFor(() => expect(result.current.detail).toEqual({ typicalSeconds: 1 }));

    rerender({ key: 'b' });
    expect(result.current).toEqual({ detail: null, loading: true, error: false });
    await act(async () => { second.resolve({ typicalSeconds: 2 }); await second.promise; });
    await waitFor(() => expect(result.current.detail).toEqual({ typicalSeconds: 2 }));
  });
});

describe('useRovingSlots', () => {
  /** Builds a container of focusable slots the hook can move focus between. */
  function mount(count) {
    const hook = renderHook(({ n }) => useRovingSlots(n), { initialProps: { n: count } });
    const container = document.createElement('div');
    const slots = Array.from({ length: count }, (_, i) => {
      const el = document.createElement('button');
      el.setAttribute('data-slot', String(i));
      container.appendChild(el);
      return el;
    });
    document.body.appendChild(container);
    hook.result.current.containerRef.current = container;
    return { ...hook, container, slots };
  }

  const key = (props, k, extra = {}) => {
    const event = { key: k, ctrlKey: false, metaKey: false, altKey: false, preventDefault: vi.fn(), ...extra };
    act(() => props.onKeyDown(event));
    return event;
  };

  afterEach(() => { document.body.innerHTML = ''; });

  it('puts only the first slot in the tab order to begin with', () => {
    const { result } = mount(4);
    const tabs = [0, 1, 2, 3].map((i) => result.current.slotProps(i).tabIndex);
    expect(tabs).toEqual([0, -1, -1, -1]);
    expect(result.current.slotProps(2)['data-slot']).toBe(2);
  });

  it('moves the tab stop and focus with ArrowRight, and stops at the last slot', () => {
    const { result, slots } = mount(3);
    const event = key(result.current.slotProps(0), 'ArrowRight');
    expect(event.preventDefault).toHaveBeenCalled();
    expect(result.current.slotProps(1).tabIndex).toBe(0);
    expect(result.current.slotProps(0).tabIndex).toBe(-1);
    expect(document.activeElement).toBe(slots[1]);

    key(result.current.slotProps(1), 'ArrowRight');
    key(result.current.slotProps(2), 'ArrowRight');
    expect(result.current.slotProps(2).tabIndex).toBe(0);
    expect(document.activeElement).toBe(slots[2]);
  });

  it('moves left with ArrowLeft, and stops at the first slot', () => {
    const { result, slots } = mount(3);
    key(result.current.slotProps(0), 'End');
    key(result.current.slotProps(2), 'ArrowLeft');
    expect(document.activeElement).toBe(slots[1]);
    key(result.current.slotProps(1), 'ArrowLeft');
    key(result.current.slotProps(0), 'ArrowLeft');
    expect(result.current.slotProps(0).tabIndex).toBe(0);
    expect(document.activeElement).toBe(slots[0]);
  });

  it('jumps to the ends with Home and End', () => {
    const { result, slots } = mount(5);
    key(result.current.slotProps(2), 'End');
    expect(document.activeElement).toBe(slots[4]);
    key(result.current.slotProps(4), 'Home');
    expect(document.activeElement).toBe(slots[0]);
  });

  it.each(['ctrlKey', 'metaKey', 'altKey'])('leaves a %s chord alone (the browser\'s, not ours)', (mod) => {
    const { result } = mount(3);
    const event = key(result.current.slotProps(0), 'ArrowRight', { [mod]: true });
    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(result.current.slotProps(0).tabIndex).toBe(0);
  });

  it('ignores other keys without swallowing them', () => {
    const { result } = mount(3);
    const event = key(result.current.slotProps(0), 'a');
    expect(event.preventDefault).not.toHaveBeenCalled();
    expect(result.current.slotProps(0).tabIndex).toBe(0);
  });

  it('makes a slot the tab stop when it receives focus by pointer', () => {
    const { result } = mount(3);
    act(() => result.current.slotProps(2).onFocus());
    expect(result.current.slotProps(2).tabIndex).toBe(0);
    expect(result.current.slotProps(0).tabIndex).toBe(-1);
  });

  it('keeps a tab stop when the count shrinks below the active slot', () => {
    const { result, rerender } = mount(5);
    act(() => result.current.slotProps(4).onFocus());
    rerender({ n: 2 });
    expect(result.current.slotProps(1).tabIndex).toBe(0);
    expect(result.current.slotProps(0).tabIndex).toBe(-1);
  });

  it('keeps slot 0 as the stop for an empty chart', () => {
    const { result } = renderHook(() => useRovingSlots(0));
    expect(result.current.slotProps(0).tabIndex).toBe(0);
  });

  it('still moves the tab stop when there is no container to focus in', () => {
    const { result } = renderHook(() => useRovingSlots(3));
    key(result.current.slotProps(0), 'ArrowRight');
    expect(result.current.slotProps(1).tabIndex).toBe(0);
  });
});

describe('useLocationsData', () => {
  const LOCS = [{ id: 1, name: 'Bamburgh' }];
  const REGIONS = [{ id: 9, name: 'Northumberland' }];
  const AREAS = [
    { id: 1, kind: 'SUB_REGION', name: 'Coast' },
    { id: 2, kind: 'DRIVE_TIME', name: 'Close' },
  ];

  it('reads everything at mount and exposes sub-regions separately from areas', async () => {
    fetchAreas.mockResolvedValue(AREAS);
    fetchLocations.mockResolvedValue(LOCS);
    fetchRegions.mockResolvedValue(REGIONS);
    fetchLocationAliases.mockResolvedValue({ 1: ['Bamburgh Castle'] });
    const { result } = renderHook(() => useLocationsData());
    expect(result.current.loading).toBe(true);
    expect(result.current.areas).toBeNull();
    expect(result.current.subRegions).toEqual([]);

    await waitFor(() => expect(result.current.loading).toBe(false));
    await waitFor(() => expect(result.current.aliasesByLocation).not.toBeNull());
    expect(result.current.locations).toEqual(LOCS);
    expect(result.current.regions).toEqual(REGIONS);
    expect(result.current.areas).toEqual(AREAS);
    expect(result.current.subRegions).toEqual([AREAS[0]]);
    expect(result.current.aliasesByLocation).toEqual({ 1: ['Bamburgh Castle'] });
    expect(result.current.loadError).toBe(false);
    expect(result.current.aliasesFailed).toBe(false);
  });

  it('flags a load error when the locations or regions fail, and still stops loading', async () => {
    fetchAreas.mockResolvedValue(AREAS);
    fetchLocations.mockRejectedValue(new Error('500'));
    fetchRegions.mockResolvedValue(REGIONS);
    fetchLocationAliases.mockResolvedValue({});
    const { result } = renderHook(() => useLocationsData());

    await waitFor(() => expect(result.current.loadError).toBe(true));
    expect(result.current.loading).toBe(false);
    expect(result.current.locations).toEqual([]);
  });

  it('treats the areas and the aliases as best-effort', async () => {
    fetchAreas.mockRejectedValue(new Error('500'));
    fetchLocations.mockResolvedValue(LOCS);
    fetchRegions.mockResolvedValue(REGIONS);
    fetchLocationAliases.mockRejectedValue(new Error('500'));
    const { result } = renderHook(() => useLocationsData());

    await waitFor(() => expect(result.current.aliasesFailed).toBe(true));
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.loadError).toBe(false);
    expect(result.current.areas).toBeNull();
    expect(result.current.aliasesByLocation).toBeNull();
    expect(result.current.locations).toEqual(LOCS);
  });

  it('reads a null alias answer as an empty map', async () => {
    fetchAreas.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([]);
    fetchRegions.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue(null);
    const { result } = renderHook(() => useLocationsData());
    await waitFor(() => expect(result.current.aliasesByLocation).toEqual({}));
  });

  it('re-reads the locations and areas on request, and keeps the old ones when the re-read fails', async () => {
    fetchAreas.mockResolvedValue(AREAS);
    fetchLocations.mockResolvedValue(LOCS);
    fetchRegions.mockResolvedValue(REGIONS);
    fetchLocationAliases.mockResolvedValue({});
    const { result } = renderHook(() => useLocationsData());
    await waitFor(() => expect(result.current.loading).toBe(false));

    fetchLocations.mockResolvedValueOnce([{ id: 2, name: 'Craster' }]);
    await act(async () => { await result.current.refreshLocations(); });
    expect(result.current.locations).toEqual([{ id: 2, name: 'Craster' }]);

    fetchAreas.mockResolvedValueOnce([{ id: 5, kind: 'SUB_REGION', name: 'Hills' }]);
    await act(async () => { await result.current.refreshAreas(); });
    expect(result.current.subRegions.map((s) => s.name)).toEqual(['Hills']);

    fetchLocations.mockRejectedValueOnce(new Error('down'));
    await act(async () => { await result.current.refreshLocations(); });
    expect(result.current.locations).toEqual([{ id: 2, name: 'Craster' }]);

    fetchAreas.mockRejectedValueOnce(new Error('down'));
    await act(async () => { await result.current.refreshAreas(); });
    expect(result.current.subRegions.map((s) => s.name)).toEqual(['Hills']);
  });
});
