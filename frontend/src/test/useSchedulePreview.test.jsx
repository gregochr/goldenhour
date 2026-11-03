import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('../api/schedulerApi', () => ({ previewSchedule: vi.fn() }));

import { previewSchedule } from '../api/schedulerApi';
import useSchedulePreview, { PREVIEW_DEBOUNCE_MS } from '../hooks/useSchedulePreview.js';

// The editors' debounced dry run (scheduler-redesign-plan.md S4b): one request after the proposal stops changing,
// and an answer to a question that is no longer the latest is never shown.

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const body = (offset) => ({ scheduleType: 'SOLAR_OFFSET', solarEvent: 'SUNRISE', solarOffsetMinutes: offset });

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useSchedulePreview', () => {
  it('sends nothing for a null proposal', async () => {
    const { result } = renderHook(() => useSchedulePreview('j', null));
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS * 3); });
    expect(previewSchedule).not.toHaveBeenCalled();
    expect(result.current).toEqual({ status: 'idle', preview: null });
  });

  it('sends one request after the proposal stops changing, not one per change', async () => {
    previewSchedule.mockResolvedValue({ valid: true, marker: 'final' });
    const { result, rerender } = renderHook(({ b }) => useSchedulePreview('j', b), { initialProps: { b: body(100) } });
    for (const offset of [105, 110, 115, 120]) {
      await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS - 100); });
      rerender({ b: body(offset) });
    }
    expect(previewSchedule).not.toHaveBeenCalled();
    expect(result.current.status).toBe('pending');

    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });

    expect(previewSchedule).toHaveBeenCalledTimes(1);
    expect(previewSchedule.mock.calls[0][0]).toBe('j');
    expect(previewSchedule.mock.calls[0][1]).toEqual(body(120));
    expect(result.current).toEqual({ status: 'ready', preview: { valid: true, marker: 'final' } });
  });

  it('ignores a stale response that lands after a newer one', async () => {
    const first = deferred();
    const second = deferred();
    previewSchedule.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const { result, rerender } = renderHook(({ b }) => useSchedulePreview('j', b), { initialProps: { b: body(100) } });
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });
    rerender({ b: body(200) });
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });
    expect(previewSchedule).toHaveBeenCalledTimes(2);

    await act(async () => { second.resolve({ valid: true, marker: 'newer' }); });
    await act(async () => { first.resolve({ valid: true, marker: 'older' }); });

    expect(result.current.preview.marker).toBe('newer');
  });

  it('aborts the request a newer proposal supersedes', async () => {
    previewSchedule.mockReturnValue(new Promise(() => {}));
    const { rerender } = renderHook(({ b }) => useSchedulePreview('j', b), { initialProps: { b: body(100) } });
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });
    const { signal } = previewSchedule.mock.calls[0][2];
    expect(signal.aborted).toBe(false);
    rerender({ b: body(200) });
    expect(signal.aborted).toBe(true);
  });

  it('reports a failed request as failed', async () => {
    previewSchedule.mockRejectedValue(new Error('down'));
    const { result } = renderHook(() => useSchedulePreview('j', body(100)));
    await act(async () => { await vi.advanceTimersByTimeAsync(PREVIEW_DEBOUNCE_MS); });
    expect(result.current).toEqual({ status: 'failed', preview: null });
  });
});
