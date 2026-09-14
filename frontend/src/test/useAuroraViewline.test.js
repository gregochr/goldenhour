import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraViewline: vi.fn(),
  getAuroraForecastViewline: vi.fn(),
}));

import { getAuroraViewline, getAuroraForecastViewline } from '../api/auroraApi.js';

const liveViewline = {
  points: [{ longitude: -5, latitude: 54 }],
  summary: 'Visible as far south as northern England',
  southernmostLatitude: 54,
  active: true,
  isForecast: false,
};

const forecastViewline = {
  points: [{ longitude: -12, latitude: 56 }, { longitude: 4, latitude: 56 }],
  summary: 'Visible as far south as central Scotland',
  southernmostLatitude: 56,
  active: true,
  isForecast: true,
};

/** The forecast line after an escalation to a higher Kp — its cap moves south, so the two differ. */
const escalatedForecastViewline = {
  points: [{ longitude: -12, latitude: 52 }, { longitude: 4, latitude: 52 }],
  summary: 'Visible as far south as the Midlands',
  southernmostLatitude: 52,
  active: true,
  isForecast: true,
};

/** A later alert's live line — further south than `liveViewline`, so the two cannot be confused. */
const laterLiveViewline = {
  points: [{ longitude: -3, latitude: 52 }],
  summary: 'Visible as far south as the Midlands',
  southernmostLatitude: 52,
  active: true,
  isForecast: false,
};

/** Flush all pending microtasks (resolved promises). */
function flushPromises() {
  return act(() => new Promise((r) => setTimeout(r, 0)));
}

/** A request this test settles by hand, so it — not the scheduler — decides which lands first. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/**
 * Settles a hand-held request inside an AWAITED `act`, so the hook's write — if it makes one — has
 * run and committed before the test reads it. ⚠️ The `await` is the load-bearing part, not the async
 * callback. Measured with the write's guard deleted: an un-awaited `act(() => settle())` lets the
 * late-answer test below pass, because the late write lands after `act` has returned, while
 * `await act(() => settle())` — the same plain callback, awaited — fails it as this form does.
 */
async function land(settle) {
  await act(async () => { settle(); });
}

/**
 * Renders the hook and records what EVERY render handed out, not only the last. `rerender` flushes
 * effects inside `act`, so `result.current` only ever shows the state after them — a line handed out
 * by the render that made a change, before its effect had run, is visible only in `seen`.
 */
function renderLogged(initialProps) {
  const seen = [];
  const utils = renderHook((props) => {
    const out = useAuroraViewline(props.enabled, props.triggerType, props.forecastKp);
    seen.push({ ...props, viewline: out.viewline });
    return out;
  }, { initialProps });
  return { ...utils, seen };
}

describe('useAuroraViewline', () => {
  beforeEach(() => {
    // Reset rather than clear: a `mockReturnValueOnce` a failed test never consumed would otherwise
    // be the next test's first answer.
    vi.resetAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    getAuroraViewline.mockResolvedValue(liveViewline);
    getAuroraForecastViewline.mockResolvedValue(forecastViewline);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ---------------------------------------------------------------------------
  // Disabled state
  // ---------------------------------------------------------------------------

  it('returns null viewline when not enabled', async () => {
    const { result } = renderHook(() => useAuroraViewline(false));
    await flushPromises();

    expect(result.current.viewline).toBeNull();
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------------------
  // Realtime (live) path
  // ---------------------------------------------------------------------------

  it('calls getAuroraViewline for realtime triggerType', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(liveViewline);
  });

  it('calls getAuroraViewline when triggerType is null (backwards compat)', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, null));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(liveViewline);
  });

  it('polls every 5 minutes for realtime', async () => {
    renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraViewline).toHaveBeenCalledTimes(2);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraViewline).toHaveBeenCalledTimes(3);
  });

  // ---------------------------------------------------------------------------
  // Forecast path
  // ---------------------------------------------------------------------------

  it('calls getAuroraForecastViewline for forecast triggerType', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'forecast'));
    await flushPromises();

    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(result.current.viewline).toEqual(forecastViewline);
  });

  it('does not re-ask for a forecast line that has landed', async () => {
    renderHook(() => useAuroraViewline(true, 'forecast'));
    await flushPromises();

    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    // Still just the one call — the forecast line changes only with its Kp.
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
  });

  it('retries a failed forecast fetch on the poll cadence until one lands, then stops asking', async () => {
    // ⚠️ The forecast mode used to ask exactly once. Now that a change of trigger or Kp withholds the
    // line it had, one failed request would otherwise leave the map with no line for the whole alert.
    getAuroraForecastViewline.mockRejectedValueOnce(new Error('network'));
    const { result } = renderHook(() => useAuroraViewline(true, 'forecast', 6));
    await flushPromises();

    // The first request failed, so there is no line yet...
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
    expect(result.current.viewline).toBeNull();

    // ...the next tick asks again, and that answer is offered...
    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);
    expect(result.current.viewline).toEqual(forecastViewline);

    // ...and once one has landed, nothing asks again.
    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);
  });

  // ---------------------------------------------------------------------------
  // Cleanup and transitions
  // ---------------------------------------------------------------------------

  it('clears viewline when disabled after being enabled', async () => {
    const { result, rerender, seen } = renderLogged({ enabled: true, triggerType: 'realtime' });
    await flushPromises();

    expect(result.current.viewline).toEqual(liveViewline);

    seen.length = 0;
    rerender({ enabled: false, triggerType: 'realtime' });
    expect(result.current.viewline).toBeNull();
    // ⚠️ On EVERY render since, not only the last. The effect clears the held line too, and under
    // `act` that clear has landed before `result.current` is read — so the check above alone passes
    // with the render-time `enabled &&` deleted, while the first disabled render still handed out the
    // ended alert's line.
    expect(seen.length).toBeGreaterThan(0);
    expect(seen.filter((s) => s.viewline !== null)).toEqual([]);
  });

  it('stops polling when disabled', async () => {
    const { rerender } = renderHook(
      ({ enabled }) => useAuroraViewline(enabled, 'realtime'),
      { initialProps: { enabled: true } },
    );
    await flushPromises();

    expect(getAuroraViewline).toHaveBeenCalledTimes(1);

    rerender({ enabled: false });

    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();
    // No additional calls after disabling
    expect(getAuroraViewline).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------------------
  // Error resilience
  // ---------------------------------------------------------------------------

  it('retains existing viewline on fetch error', async () => {
    const { result } = renderHook(() => useAuroraViewline(true, 'realtime'));
    await flushPromises();

    expect(result.current.viewline).toEqual(liveViewline);

    // Next poll fails
    getAuroraViewline.mockRejectedValueOnce(new Error('network'));
    await act(async () => { vi.advanceTimersByTime(5 * 60 * 1000); });
    await flushPromises();

    // Still has the original viewline
    expect(result.current.viewline).toEqual(liveViewline);
  });

  // ---------------------------------------------------------------------------
  // A line is offered only as what it was fetched as
  // ---------------------------------------------------------------------------
  //
  // The live OVATION nowcast and the forecast lookup are two different lines, and a forecast line
  // is a different line again at a different Kp. A line held under one key must not be handed out
  // while another is in force — in ANY render, which is why several tests below read `seen` as well
  // as `result.current`.

  describe('a line is offered only as what it was fetched as', () => {
    it('drops a late LIVE answer once the alert has turned forecast-triggered', async () => {
      // ⚠️ The out-of-order case. The live request is still out when the trigger flips to forecast,
      // and it lands LAST. Unguarded, it overwrote the forecast line on hand — which, since each line
      // is held with its key, takes the forecast line off the map instead of putting the live one up.
      const liveInFlight = deferred();
      getAuroraViewline.mockReturnValueOnce(liveInFlight.promise);
      const { result, rerender } = renderHook(
        ({ triggerType }) => useAuroraViewline(true, triggerType),
        { initialProps: { triggerType: 'realtime' } },
      );
      // The live request really is out — without it there is no late answer to drop.
      expect(getAuroraViewline).toHaveBeenCalledTimes(1);

      rerender({ triggerType: 'forecast' });
      await flushPromises();
      // Control: the forecast line is the one on hand.
      expect(result.current.viewline).toEqual(forecastViewline);

      await land(() => liveInFlight.resolve(liveViewline));

      // Still the forecast line. Broken, the late live answer replaced it.
      expect(result.current.viewline).toEqual(forecastViewline);
    });

    it('offers no line while a changed trigger\'s first answer is out — in no render the other mode\'s', async () => {
      // ⚠️ The stale-window case. Nothing used to withhold the live line on the flip, so it stood in
      // for the forecast one until the forecast request landed.
      const forecastInFlight = deferred();
      getAuroraForecastViewline.mockReturnValueOnce(forecastInFlight.promise);
      const { result, rerender, seen } = renderLogged({ enabled: true, triggerType: 'realtime' });
      await flushPromises();
      // Control: the live line is on hand before the change.
      expect(result.current.viewline).toEqual(liveViewline);

      seen.length = 0;
      rerender({ enabled: true, triggerType: 'forecast' });

      // The forecast request really is out, and no render since the flip has offered a line —
      // including the one that made it, before any effect ran.
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
      expect(seen.length).toBeGreaterThan(0);
      expect(seen.filter((s) => s.viewline !== null)).toEqual([]);

      // ...and once it lands, the forecast line is what is offered.
      await land(() => forecastInFlight.resolve(forecastViewline));
      expect(result.current.viewline).toEqual(forecastViewline);
    });

    it('offers no line when a changed trigger\'s first fetch fails', async () => {
      getAuroraForecastViewline.mockRejectedValueOnce(new Error('network'));
      const { result, rerender } = renderHook(
        ({ triggerType }) => useAuroraViewline(true, triggerType),
        { initialProps: { triggerType: 'realtime' } },
      );
      await flushPromises();
      expect(result.current.viewline).toEqual(liveViewline);

      rerender({ triggerType: 'forecast' });
      await flushPromises();

      // The forecast request was made, and failed...
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(1);
      // ...leaving nothing until a retry lands. Broken, the live line was kept — for good, since
      // the forecast mode never asked again.
      expect(result.current.viewline).toBeNull();
    });

    it('offers no line for a new alert until its own first answer — in no render the previous alert\'s', async () => {
      const { result, rerender, seen } = renderLogged({ enabled: true, triggerType: 'realtime' });
      await flushPromises();
      // Control: the first alert's line.
      expect(result.current.viewline).toEqual(liveViewline);

      rerender({ enabled: false, triggerType: 'realtime' }); // that alert ends
      const secondInFlight = deferred();
      getAuroraViewline.mockReturnValueOnce(secondInFlight.promise);
      seen.length = 0;
      rerender({ enabled: true, triggerType: 'realtime' }); // a later one begins, on the very same key

      // Its request really is out, and no render since has offered a line. ⚠️ Including the first
      // one enabled again, before its effect ran: a clear made on the way IN rather than on the way
      // out still leaves `result.current` null after `act`, and fails only this.
      expect(getAuroraViewline).toHaveBeenCalledTimes(2);
      expect(seen.length).toBeGreaterThan(0);
      expect(seen.filter((s) => s.viewline !== null)).toEqual([]);

      await land(() => secondInFlight.resolve(laterLiveViewline));
      expect(result.current.viewline).toEqual(laterLiveViewline);
    });

    it('withholds the forecast line the moment its Kp changes, and asks for the new Kp\'s', async () => {
      // ⚠️ An escalation inside a forecast-triggered alert re-sets the Kp the forecast line is built
      // from without moving `enabled` or the mode. Keyed on those two alone, nothing asked again, and
      // the old line stood for the rest of the alert under an overlay label quoting the new Kp.
      const { result, rerender, seen } = renderLogged({ enabled: true, triggerType: 'forecast', forecastKp: 6 });
      await flushPromises();
      // Control: the Kp-6 line.
      expect(result.current.viewline).toEqual(forecastViewline);

      const escalated = deferred();
      getAuroraForecastViewline.mockReturnValueOnce(escalated.promise);
      seen.length = 0;
      rerender({ enabled: true, triggerType: 'forecast', forecastKp: 7 });

      // The Kp-7 line really is asked for, and no render since the escalation has offered the Kp-6 one.
      expect(getAuroraForecastViewline).toHaveBeenCalledTimes(2);
      expect(seen.length).toBeGreaterThan(0);
      expect(seen.filter((s) => s.viewline !== null)).toEqual([]);

      await land(() => escalated.resolve(escalatedForecastViewline));
      expect(result.current.viewline).toEqual(escalatedForecastViewline);
    });

    it('keeps its line, and does not refetch, when the trigger changes without changing the mode', async () => {
      // ⚠️ `null` and `'realtime'` both mean the live line (a status with no trigger recorded yet
      // reads null). A hook keyed on the raw string would withhold the line and re-ask for it on a
      // flip that asks for nothing different.
      const { result, rerender } = renderHook(
        ({ triggerType }) => useAuroraViewline(true, triggerType),
        { initialProps: { triggerType: null } },
      );
      await flushPromises();
      expect(result.current.viewline).toEqual(liveViewline);

      rerender({ triggerType: 'realtime' });

      expect(getAuroraViewline).toHaveBeenCalledTimes(1);
      expect(result.current.viewline).toEqual(liveViewline);
    });

    it('ignores the forecast Kp in live mode — the live line is neither withheld nor re-asked for', async () => {
      // The live endpoint reads no trigger state, so a Kp change asks for nothing different there.
      const { result, rerender } = renderLogged({ enabled: true, triggerType: 'realtime', forecastKp: 6 });
      await flushPromises();
      expect(result.current.viewline).toEqual(liveViewline);

      rerender({ enabled: true, triggerType: 'realtime', forecastKp: 7 });

      expect(getAuroraViewline).toHaveBeenCalledTimes(1);
      expect(result.current.viewline).toEqual(liveViewline);
    });
  });
});
