import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
import apiClient from '../api/axiosClient.js';
import { getAuroraNights } from '../api/auroraApi.js';

beforeEach(() => vi.resetAllMocks());

describe('auroraApi.getAuroraNights (aurora-pro-door-plan.md §2.2)', () => {
  it('reads the served nights and the end of the current night', async () => {
    apiClient.get.mockResolvedValue({
      data: { nights: ['2026-10-25', '2026-10-26'], currentNightEndsAt: '2026-10-26T05:41:00Z' },
    });

    const answer = await getAuroraNights();

    expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/nights');
    expect(answer).toEqual({ nights: ['2026-10-25', '2026-10-26'], currentNightEndsAt: '2026-10-26T05:41:00Z' });
  });

  it('resolves an empty answer on any error rather than throwing', async () => {
    apiClient.get.mockRejectedValue(Object.assign(new Error('boom'), { response: { status: 500 } }));

    await expect(getAuroraNights()).resolves.toEqual({ nights: [], currentNightEndsAt: null });
  });

  it('resolves an empty answer for a 403 too, and for a body that is not the shape', async () => {
    apiClient.get.mockRejectedValueOnce(Object.assign(new Error('no'), { response: { status: 403 } }));
    await expect(getAuroraNights()).resolves.toEqual({ nights: [], currentNightEndsAt: null });

    apiClient.get.mockResolvedValueOnce({ data: null });
    await expect(getAuroraNights()).resolves.toEqual({ nights: [], currentNightEndsAt: null });
  });
});
