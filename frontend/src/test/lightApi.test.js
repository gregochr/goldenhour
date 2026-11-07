import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { getTodaysLight } from '../api/lightApi.js';

describe('getTodaysLight', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs the personal light endpoint and returns a body that has stops', async () => {
    const light = { label: 'Home', stops: [{ key: 'sunrise', position: 0.3 }] };
    apiClient.get.mockResolvedValue({ status: 200, data: light });
    expect(await getTodaysLight()).toBe(light);
    expect(apiClient.get).toHaveBeenCalledWith('/api/user/settings/light');
  });

  it.each([
    ['a 204 empty-string body', ''],
    ['a malformed 200 with no stops', { label: 'Home' }],
    ['an undefined body', undefined],
    ['a null body', null],
  ])('resolves to null for %s', async (_n, data) => {
    apiClient.get.mockResolvedValue({ data });
    expect(await getTodaysLight()).toBeNull();
  });

  it('lets a failure reject instead of folding it onto null', async () => {
    apiClient.get.mockRejectedValue(new Error('offline'));
    await expect(getTodaysLight()).rejects.toThrow('offline');
  });
});
