/**
 * `hooks/useForecastDetail.js` — the lazy detail fetch the location sheet's facts block rides
 * (overlay convergence C2).
 *
 * What breaks if these fail: a failed fetch is remembered forever (no retry on the next open), an
 * unmounted sheet writes into the shared cache or sets state, or an already-cached detail paints a
 * loading frame before the facts.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import useForecastDetail from '../hooks/useForecastDetail.js';
import {
  cacheForecastDetail, clearForecastDetailCache, getForecastDetail, peekForecastDetail,
} from '../api/forecastApi.js';

vi.mock('../api/forecastApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getForecastDetail: vi.fn(),
}));

function Probe({ forecast }) {
  const { detail, detailLoading } = useForecastDetail(forecast);
  return (
    <div data-testid="probe" data-loading={String(detailLoading)} data-detail={detail ? detail.marker : ''} />
  );
}

const state = () => screen.getByTestId('probe').dataset;

beforeEach(() => {
  clearForecastDetailCache();
  getForecastDetail.mockReset();
});

describe('useForecastDetail', () => {
  it('reports loading, then the detail, and caches it', async () => {
    let release;
    getForecastDetail.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    render(<Probe forecast={{ id: 1 }} />);
    expect(state().loading).toBe('true');
    await act(async () => { release({ marker: 'full' }); });
    expect(state().loading).toBe('false');
    expect(state().detail).toBe('full');
    expect(peekForecastDetail(1)).toEqual({ cached: true, detail: { marker: 'full' } });
  });

  it('paints an already-cached detail on the FIRST render, with no loading frame and no request', () => {
    cacheForecastDetail(2, { marker: 'warm' });
    render(<Probe forecast={{ id: 2 }} />);
    expect(state().loading).toBe('false');
    expect(state().detail).toBe('warm');
    expect(getForecastDetail).not.toHaveBeenCalled();
  });

  it('a FAILED fetch stops the loading state, caches nothing, and is asked again on the next mount', async () => {
    getForecastDetail.mockRejectedValueOnce(new Error('500'));
    const first = render(<Probe forecast={{ id: 3 }} />);
    await act(async () => {});
    expect(state().loading).toBe('false');
    expect(state().detail).toBe('');
    expect(peekForecastDetail(3).cached).toBe(false);
    first.unmount();
    getForecastDetail.mockResolvedValueOnce({ marker: 'retry' });
    render(<Probe forecast={{ id: 3 }} />);
    await act(async () => {});
    expect(getForecastDetail).toHaveBeenCalledTimes(2);
    expect(state().detail).toBe('retry');
  });

  it('an unmounted instance writes nothing into the cache when its fetch lands late', async () => {
    let release;
    getForecastDetail.mockImplementation(() => new Promise((resolve) => { release = resolve; }));
    const view = render(<Probe forecast={{ id: 4 }} />);
    view.unmount();
    await act(async () => { release({ marker: 'late' }); });
    expect(peekForecastDetail(4).cached).toBe(false);
  });

  it('an unmounted instance does not record a late FAILURE either (a remount still asks)', async () => {
    let reject;
    getForecastDetail.mockImplementation(() => new Promise((_, rej) => { reject = rej; }));
    const view = render(<Probe forecast={{ id: 5 }} />);
    view.unmount();
    await act(async () => { reject(new Error('late')); });
    getForecastDetail.mockResolvedValue({ marker: 'ok' });
    render(<Probe forecast={{ id: 5 }} />);
    await act(async () => {});
    expect(state().detail).toBe('ok');
  });

  it('never fetches for a row that already carries its detail or has no id', () => {
    render(<Probe forecast={{ id: 6, lowCloud: 10, marker: 'inline' }} />);
    expect(state().detail).toBe('inline');
    render(<Probe forecast={{ marker: 'sparse' }} />);
    expect(getForecastDetail).not.toHaveBeenCalled();
  });
});
