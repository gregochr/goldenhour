import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../api/axiosClient.js';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

describe('waitlistApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getWaitlist reads the admin list', async () => {
    apiClient.get.mockResolvedValue({ data: [{ id: 1 }] });

    expect(await getWaitlist()).toEqual([{ id: 1 }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/waitlist');
  });

  it('inviteWaitlistEntry posts to that row\'s invite route', async () => {
    apiClient.post.mockResolvedValue({ data: { message: 'Invitation sent' } });

    expect(await inviteWaitlistEntry(7)).toEqual({ message: 'Invitation sent' });
    expect(apiClient.post).toHaveBeenCalledWith('/api/admin/waitlist/7/invite');
  });

  it('removeWaitlistEntry deletes that row', async () => {
    apiClient.delete.mockResolvedValue({});

    await removeWaitlistEntry(7);

    expect(apiClient.delete).toHaveBeenCalledWith('/api/admin/waitlist/7');
  });
});
