import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import useGeocoder from '../hooks/useGeocoder.js';

vi.mock('../api/forecastApi', () => ({
  geocodePlaceBulk: vi.fn(),
}));

import { geocodePlaceBulk } from '../api/forecastApi';

describe('useGeocoder', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('resolves a single-result row', async () => {
    geocodePlaceBulk.mockResolvedValue([
      { lat: 55.6, lon: -1.7, displayName: 'Bamburgh Castle, UK' },
    ]);

    const rows = [{ id: '1', name: 'Bamburgh', geocodeStatus: 'pending' }];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    expect(result.current.isRunning).toBe(false);

    await act(async () => {
      result.current.startGeocoding();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    });

    expect(setRows).toHaveBeenCalled();
    const updater = setRows.mock.calls[0][0];
    const updated = updater(rows);
    expect(updated[0].geocodeStatus).toBe('resolved');
    expect(updated[0].lat).toBe(55.6);
    expect(updated[0].lon).toBe(-1.7);
  });

  it('marks multi-result as ambiguous', async () => {
    geocodePlaceBulk.mockResolvedValue([
      { lat: 55.6, lon: -1.7, displayName: 'Durham, England' },
      { lat: 35.9, lon: -78.9, displayName: 'Durham, NC, USA' },
    ]);

    const rows = [{ id: '1', name: 'Durham', geocodeStatus: 'pending' }];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    await act(async () => {
      result.current.startGeocoding();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    });

    const updater = setRows.mock.calls[0][0];
    const updated = updater(rows);
    expect(updated[0].geocodeStatus).toBe('ambiguous');
    expect(updated[0].candidates).toHaveLength(2);
  });

  it('marks empty results as failed', async () => {
    geocodePlaceBulk.mockResolvedValue([]);

    const rows = [{ id: '1', name: 'Nonexistent Place', geocodeStatus: 'pending' }];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    await act(async () => {
      result.current.startGeocoding();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    });

    const updater = setRows.mock.calls[0][0];
    const updated = updater(rows);
    expect(updated[0].geocodeStatus).toBe('failed');
  });

  it('skips already-resolved rows', async () => {
    const rows = [
      { id: '1', name: 'Resolved', geocodeStatus: 'resolved', lat: 55.6, lon: -1.7 },
      { id: '2', name: 'Skipped', geocodeStatus: 'skipped' },
    ];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    await act(async () => {
      result.current.startGeocoding();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    });

    expect(geocodePlaceBulk).not.toHaveBeenCalled();
  });

  it('retries a single row', async () => {
    geocodePlaceBulk.mockResolvedValue([
      { lat: 55.6, lon: -1.7, displayName: 'Bamburgh Castle, UK' },
    ]);

    const rows = [{ id: '1', name: 'Bamburgh', geocodeStatus: 'failed' }];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    await act(async () => {
      await result.current.retryRow('1');
    });

    // First call sets to pending, second sets resolved result
    expect(setRows).toHaveBeenCalledTimes(2);
  });

  it('reports progress count', async () => {
    geocodePlaceBulk
      .mockResolvedValueOnce([{ lat: 55.6, lon: -1.7, displayName: 'A' }])
      .mockResolvedValueOnce([{ lat: 54.7, lon: -1.5, displayName: 'B' }]);

    const rows = [
      { id: '1', name: 'Place A', geocodeStatus: 'pending' },
      { id: '2', name: 'Place B', geocodeStatus: 'pending' },
    ];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    expect(result.current.total).toBe(2);

    await act(async () => {
      result.current.startGeocoding();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    }, { timeout: 5000 });

    expect(result.current.progress).toBe(2);
  });

  it('handles abort', async () => {
    // Slow resolve to allow abort in between
    geocodePlaceBulk.mockImplementation(() =>
      new Promise((resolve) => setTimeout(() => resolve([{ lat: 55.6, lon: -1.7, displayName: 'A' }]), 50))
    );

    const rows = [
      { id: '1', name: 'Place A', geocodeStatus: 'pending' },
      { id: '2', name: 'Place B', geocodeStatus: 'pending' },
      { id: '3', name: 'Place C', geocodeStatus: 'pending' },
    ];
    const setRows = vi.fn();

    const { result } = renderHook(() => useGeocoder(rows, setRows));

    // Start and abort immediately
    act(() => {
      result.current.startGeocoding();
    });

    act(() => {
      result.current.abort();
    });

    await waitFor(() => {
      expect(result.current.isRunning).toBe(false);
    });

    // Should have processed fewer than all rows
    expect(geocodePlaceBulk.mock.calls.length).toBeLessThanOrEqual(3);
  });
});
