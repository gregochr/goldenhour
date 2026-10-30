import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import JobRunsMetricsView from '../components/JobRunsMetricsView.jsx';

vi.mock('../api/batchApi', () => ({
  getRegions: vi.fn(),
  submitScheduledBatch: vi.fn(),
  submitJfdiBatch: vi.fn(),
}));

vi.mock('../api/metricsApi', () => ({
  getJobRuns: vi.fn(),
  getApiCalls: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  refreshTideData: vi.fn(),
  backfillTideData: vi.fn(),
  fetchLocations: vi.fn(),
}));

vi.mock('../api/auroraApi', () => ({
  enrichBortle: vi.fn(),
}));

vi.mock('../api/briefingApi.js', () => ({
  runBriefing: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => ({ isAdmin: true, role: 'ADMIN' }),
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: null, loading: false }),
}));

import { getRegions } from '../api/batchApi';
import { getJobRuns, getApiCalls } from '../api/metricsApi';
import { fetchLocations } from '../api/forecastApi';

const noop = () => {};

/** Builds runs with the given ids, started now so the 7-day view window keeps them. */
function runsWithIds(ids) {
  return ids.map((id) => ({
    id,
    runType: 'WEATHER',
    startedAt: new Date().toISOString(),
    durationMs: 1000,
    succeeded: 1,
    failed: 0,
  }));
}

const range = (from, to) => Array.from({ length: to - from + 1 }, (_, i) => from + i);

beforeEach(() => {
  vi.clearAllMocks();
  getRegions.mockResolvedValue([]);
  getApiCalls.mockResolvedValue({ data: [] });
  fetchLocations.mockResolvedValue([]);
});

describe('Job runs Load More', () => {
  it('requests the next page and does not render a repeated run twice', async () => {
    getJobRuns
      .mockResolvedValueOnce({ data: { content: runsWithIds(range(1, 20)) } })
      // Page 1 repeats id 20 (already shown) and adds 21 and 22.
      .mockResolvedValueOnce({ data: { content: runsWithIds([20, 21, 22]) } });

    render(<JobRunsMetricsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />);

    fireEvent.click(await screen.findByTestId('load-more-btn'));

    await waitFor(() => expect(screen.getByTestId('job-run-row-22')).toBeInTheDocument());
    expect(getJobRuns).toHaveBeenLastCalledWith(undefined, 1, 20);
    expect(screen.getByTestId('job-run-row-21')).toBeInTheDocument();
    expect(screen.getAllByTestId('job-run-row-20')).toHaveLength(1);
    expect(screen.getAllByTestId(/^job-run-row-/)).toHaveLength(22);
  });
});
