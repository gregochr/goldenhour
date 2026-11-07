import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import AuroraForecastModal from '../components/AuroraForecastModal.jsx';

vi.mock('../api/auroraApi.js', () => ({
  getAuroraForecastPreview: vi.fn(),
  runAuroraForecast: vi.fn(),
}));

import { getAuroraForecastPreview } from '../api/auroraApi.js';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const SIM_PREVIEW = {
  simulated: true,
  nights: [
    {
      date: '2026-03-21',
      label: 'Tonight',
      gScale: 'G2',
      recommended: true,
      summary: 'Kp 6',
      eligibleLocations: 1,
    },
    {
      date: '2026-03-22',
      label: 'Tomorrow',
      gScale: null,
      recommended: false,
      summary: 'Quiet',
      eligibleLocations: 0,
    },
  ],
};

describe('AuroraForecastModal branches', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('flags simulated data on each night and in a footer note, and hides the quiet-night hint', async () => {
    getAuroraForecastPreview.mockResolvedValue(SIM_PREVIEW);
    render(<AuroraForecastModal onClose={vi.fn()} onComplete={vi.fn()} />);
    expect(await screen.findByTestId('night-row-2026-03-21')).toBeInTheDocument();
    expect(screen.getAllByText(/SIM/)).toHaveLength(2);
    expect(screen.getByText(/Using simulated geomagnetic data\. Weather/)).toBeInTheDocument();
    expect(screen.queryByText(/Not recommended/)).not.toBeInTheDocument();
  });

  it('uses the singular for a single eligible location and omits the count for zero', async () => {
    getAuroraForecastPreview.mockResolvedValue(SIM_PREVIEW);
    render(<AuroraForecastModal onClose={vi.fn()} onComplete={vi.fn()} />);
    const first = await screen.findByTestId('night-row-2026-03-21');
    expect(first).toHaveTextContent('1 location');
    expect(first).not.toHaveTextContent('1 locations');
    expect(screen.getByTestId('night-row-2026-03-22')).not.toHaveTextContent('location');
  });

  it('ignores a preview that arrives after unmount', async () => {
    const d = deferred();
    getAuroraForecastPreview.mockReturnValue(d.promise);
    const { unmount } = render(<AuroraForecastModal onClose={vi.fn()} onComplete={vi.fn()} />);
    unmount();
    await act(async () => { d.resolve(SIM_PREVIEW); });
    expect(getAuroraForecastPreview).toHaveBeenCalledTimes(1);
  });

  it('ignores a failure that arrives after unmount', async () => {
    const d = deferred();
    getAuroraForecastPreview.mockReturnValue(d.promise);
    const { unmount } = render(<AuroraForecastModal onClose={vi.fn()} onComplete={vi.fn()} />);
    unmount();
    await act(async () => { d.reject(new Error('down')); });
    expect(getAuroraForecastPreview).toHaveBeenCalledTimes(1);
  });
});
