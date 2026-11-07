import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { fetchAllTideStats, fetchTideStats } from '../api/tideApi.js';

describe('tideApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetchAllTideStats GETs the all-locations stats and returns the body', async () => {
    apiClient.get.mockResolvedValue({ data: { Bamburgh: { max: 5 } } });
    expect(await fetchAllTideStats()).toEqual({ Bamburgh: { max: 5 } });
    expect(apiClient.get).toHaveBeenCalledWith('/api/tides/stats/all');
  });

  it('fetchTideStats passes the location name as a query param (not in the path)', async () => {
    apiClient.get.mockResolvedValue({ status: 200, data: { max: 5 } });
    expect(await fetchTideStats('St Mary\'s & Co')).toEqual({ max: 5 });
    expect(apiClient.get).toHaveBeenCalledWith('/api/tides/stats', { params: { locationName: 'St Mary\'s & Co' } });
  });

  it('fetchTideStats returns null on a 204, whatever the body', async () => {
    apiClient.get.mockResolvedValue({ status: 204, data: '' });
    expect(await fetchTideStats('Nowhere')).toBeNull();
  });
});
