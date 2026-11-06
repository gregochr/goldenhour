import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import { fetchUnroutableLocations } from '../api/driveTimesAdminApi.js';

describe('driveTimesAdminApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('GETs the admin unroutable path and returns the body', async () => {
    const rows = [{ locationId: 7, name: 'Helvellyn' }];
    apiClient.get.mockResolvedValue({ data: rows });

    await expect(fetchUnroutableLocations()).resolves.toBe(rows);

    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/drive-times/unroutable');
  });
});
