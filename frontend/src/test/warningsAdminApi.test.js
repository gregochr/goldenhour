import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { getWarningsStatus, simulateWarning } from '../api/warningsAdminApi.js';

describe('warningsAdminApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    apiClient.get.mockResolvedValue({ data: 'status' });
    apiClient.post.mockResolvedValue({ data: 'stored' });
  });

  it('getWarningsStatus reads the admin status and returns the body', async () => {
    expect(await getWarningsStatus()).toBe('status');
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/warnings/status');
  });

  it('simulateWarning posts the request to the admin endpoint and returns the stored warning', async () => {
    const request = { level: 'Red', weatherTypes: ['SNOW'], durationHours: 6, latitude: 54.5, longitude: -2.2 };
    expect(await simulateWarning(request)).toBe('stored');
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/warnings/simulate', request);
  });
});
