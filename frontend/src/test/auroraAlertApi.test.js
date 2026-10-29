import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
import apiClient from '../api/axiosClient.js';
import { getAuroraAlert } from '../api/auroraApi.js';

beforeEach(() => vi.resetAllMocks());

describe('auroraApi.getAuroraAlert (aurora-lite-alert-plan.md §2.2)', () => {
  it('reads the role-free alert from /api/aurora/alert', async () => {
    const body = { level: 'MODERATE', active: true, triggerType: 'forecast', allOvercast: false };
    apiClient.get.mockResolvedValue({ data: body });

    await expect(getAuroraAlert()).resolves.toEqual(body);
    expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/alert', { responseType: 'text' });
  });

  it('resolves null on any error, including a 401, 403 or 500', async () => {
    for (const status of [401, 403, 500]) {
      apiClient.get.mockRejectedValueOnce(Object.assign(new Error('x'), { response: { status } }));
      await expect(getAuroraAlert()).resolves.toBeNull();
    }
    apiClient.get.mockRejectedValueOnce(new Error('network'));
    await expect(getAuroraAlert()).resolves.toBeNull();
  });
});
