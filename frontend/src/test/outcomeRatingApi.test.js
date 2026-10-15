import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), put: vi.fn() } }));
import apiClient from '../api/axiosClient.js';
import { fetchMyRatings, putRating } from '../api/outcomeRatingApi.js';

beforeEach(() => vi.resetAllMocks());

describe('outcomeRatingApi', () => {
  it('reads the caller\'s ratings for a date range, the range sent as query params', async () => {
    apiClient.get.mockResolvedValue({ data: [{ locationId: 7, rating: 4 }] });

    const rows = await fetchMyRatings('2026-06-13', '2026-06-15');

    expect(apiClient.get).toHaveBeenCalledWith('/api/outcome/ratings', {
      params: { from: '2026-06-13', to: '2026-06-15' },
    });
    expect(rows).toEqual([{ locationId: 7, rating: 4 }]);
  });

  it('PUTs exactly the slot and the star, and returns the stored row', async () => {
    apiClient.put.mockResolvedValue({ data: { locationId: 7, rating: 3, updatedAt: 'x' } });

    const stored = await putRating({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: 3, extra: 'dropped',
    });

    expect(apiClient.put).toHaveBeenCalledWith('/api/outcome/ratings', {
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: 3,
    });
    expect(stored.rating).toBe(3);
  });

  it('sends "didn\'t see it" as an explicit null, never as an absent field', async () => {
    apiClient.put.mockResolvedValue({ data: {} });

    await putRating({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNRISE', rating: null,
    });

    const body = apiClient.put.mock.calls[0][1];
    expect(Object.keys(body)).toContain('rating');
    expect(body.rating).toBeNull();
  });

  it('lets a refusal through to the caller (the hook rolls back on any rejection)', async () => {
    apiClient.put.mockRejectedValue(Object.assign(new Error('403'), { response: { status: 403 } }));
    await expect(putRating({
      locationId: 7, eventDate: '2026-06-14', eventType: 'SUNSET', rating: 3,
    })).rejects.toThrow('403');
  });
});
