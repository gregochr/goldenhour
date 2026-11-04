import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { getRegions, submitScheduledBatch, submitJfdiBatch } from '../api/batchApi.js';

describe('batchApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getRegions GETs the region list and unwraps data', async () => {
    apiClient.get.mockResolvedValue({ data: [{ id: 1, name: 'Lakes', locationCount: 4 }] });
    expect(await getRegions()).toEqual([{ id: 1, name: 'Lakes', locationCount: 4 }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/batches/regions');
  });

  it.each([
    ['submitScheduledBatch', submitScheduledBatch, '/api/admin/batches/submit-scheduled'],
    ['submitJfdiBatch', submitJfdiBatch, '/api/admin/batches/submit-jfdi'],
  ])('%s POSTs regionIds and unwraps data', async (_n, fn, url) => {
    apiClient.post.mockResolvedValue({ data: { batchId: 'b1', requestCount: 3 } });
    expect(await fn([1, 2])).toEqual({ batchId: 'b1', requestCount: 3 });
    expect(apiClient.post).toHaveBeenCalledWith(url, { regionIds: [1, 2] });
  });

  it.each([
    ['submitScheduledBatch', submitScheduledBatch],
    ['submitJfdiBatch', submitJfdiBatch],
  ])('%s sends null through as "all regions"', async (_n, fn) => {
    apiClient.post.mockResolvedValue({ data: {} });
    await fn(null);
    expect(apiClient.post.mock.calls[0][1]).toEqual({ regionIds: null });
  });
});
