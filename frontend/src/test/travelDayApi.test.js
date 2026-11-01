import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { fetchTravelDays, updateTravelDay } from '../api/travelDayApi.js';

describe('travelDayApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('fetches the admin list as it always did, with no query', async () => {
    apiClient.get.mockResolvedValue({ data: [{ id: 1 }] });
    expect(await fetchTravelDays()).toEqual([{ id: 1 }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/travel-days', undefined);
  });

  it('asks for past ranges with includePast=true', async () => {
    apiClient.get.mockResolvedValue({ data: [] });
    await fetchTravelDays({ includePast: true });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/travel-days', { params: { includePast: true } });
  });

  it('updates a range with a PUT to its id', async () => {
    apiClient.put.mockResolvedValue({ data: { id: 5 } });
    const range = { startDate: '2026-11-02', endDate: '2026-11-03', note: null };
    expect(await updateTravelDay(5, range)).toEqual({ id: 5 });
    expect(apiClient.put).toHaveBeenCalledWith('/api/admin/travel-days/5', range);
  });
});
