import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { getJobRuns, getApiCalls, getBatchSummary, getDispositionBreakdown } from '../api/metricsApi.js';

describe('metricsApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.get.mockResolvedValue({ data: 'resp' });
  });

  describe('getJobRuns', () => {
    it('defaults to page 0, size 20 and no runType', () => {
      getJobRuns();
      expect(apiClient.get).toHaveBeenCalledWith('/api/metrics/job-runs?page=0&size=20');
    });

    it('puts runType first when given', () => {
      getJobRuns('SCHEDULED_BATCH', 2, 50);
      expect(apiClient.get).toHaveBeenCalledWith('/api/metrics/job-runs?runType=SCHEDULED_BATCH&page=2&size=50');
    });

    it('omits an empty runType', () => {
      getJobRuns('', 1, 5);
      expect(apiClient.get).toHaveBeenCalledWith('/api/metrics/job-runs?page=1&size=5');
    });

    it('returns the axios response, not just its data', async () => {
      expect(await getJobRuns()).toEqual({ data: 'resp' });
    });
  });

  it.each([
    ['getApiCalls', getApiCalls, '/api/metrics/api-calls?jobRunId=12'],
    ['getBatchSummary', getBatchSummary, '/api/metrics/batch-summary?jobRunId=12'],
    ['getDispositionBreakdown', getDispositionBreakdown, '/api/metrics/disposition-breakdown?jobRunId=12'],
  ])('%s GETs by jobRunId and returns the axios response', async (_n, fn, url) => {
    expect(await fn(12)).toEqual({ data: 'resp' });
    expect(apiClient.get).toHaveBeenCalledWith(url);
  });
});
