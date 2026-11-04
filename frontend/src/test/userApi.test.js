import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import {
  getUsers, createUser, resetUserPassword, updateUserEmail, updateUserRole, updateUserEnabled,
  deleteUser, resendVerification, getUserSubRegions, saveUserSubRegions, clearUserSubRegionLock,
} from '../api/userApi.js';

describe('userApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getUsers GETs the list and returns the body', async () => {
    apiClient.get.mockResolvedValue({ data: [{ id: 1 }] });
    expect(await getUsers()).toEqual([{ id: 1 }]);
    expect(apiClient.get).toHaveBeenCalledWith('/api/users');
  });

  it('createUser POSTs the payload unchanged', async () => {
    const user = { username: 'a', password: 'p', email: 'a@b.c', role: 'PRO_USER' };
    apiClient.post.mockResolvedValue({ data: { id: 9 } });
    expect(await createUser(user)).toEqual({ id: 9 });
    expect(apiClient.post).toHaveBeenCalledWith('/api/users', user);
  });

  it('resetUserPassword PUTs to the user id with no body', async () => {
    apiClient.put.mockResolvedValue({ data: { temporaryPassword: 'tmp' } });
    expect(await resetUserPassword(4)).toEqual({ temporaryPassword: 'tmp' });
    expect(apiClient.put).toHaveBeenCalledWith('/api/users/4/reset-password');
  });

  it.each([
    ['updateUserEmail', updateUserEmail, 'a@b.c', '/api/users/7/email', { email: 'a@b.c' }],
    ['updateUserRole', updateUserRole, 'LITE_USER', '/api/users/7/role', { role: 'LITE_USER' }],
    ['updateUserEnabled', updateUserEnabled, false, '/api/users/7/enabled', { enabled: false }],
  ])('%s PUTs the wrapped value and returns the body', async (_n, fn, value, url, body) => {
    apiClient.put.mockResolvedValue({ data: { message: 'ok' } });
    expect(await fn(7, value)).toEqual({ message: 'ok' });
    expect(apiClient.put).toHaveBeenCalledWith(url, body);
  });

  it('deleteUser DELETEs the user and returns the body', async () => {
    apiClient.delete.mockResolvedValue({ data: { message: 'gone' } });
    expect(await deleteUser(3)).toEqual({ message: 'gone' });
    expect(apiClient.delete).toHaveBeenCalledWith('/api/users/3');
  });

  it('resendVerification POSTs to the user id', async () => {
    apiClient.post.mockResolvedValue({ data: { message: 'sent' } });
    expect(await resendVerification(3)).toEqual({ message: 'sent' });
    expect(apiClient.post).toHaveBeenCalledWith('/api/users/3/resend-verification');
  });

  it('getUserSubRegions GETs the reader state', async () => {
    apiClient.get.mockResolvedValue({ data: { picks: [1] } });
    expect(await getUserSubRegions(5)).toEqual({ picks: [1] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/users/5/sub-regions');
  });

  it('saveUserSubRegions PUTs the area ids, an empty list included', async () => {
    apiClient.put.mockResolvedValue({ data: { picks: [] } });
    expect(await saveUserSubRegions(5, [])).toEqual({ picks: [] });
    expect(apiClient.put).toHaveBeenCalledWith('/api/users/5/sub-regions', { areaIds: [] });
    await saveUserSubRegions(5, [2, 3]);
    expect(apiClient.put).toHaveBeenLastCalledWith('/api/users/5/sub-regions', { areaIds: [2, 3] });
  });

  it('clearUserSubRegionLock DELETEs the lock and resolves to nothing', async () => {
    apiClient.delete.mockResolvedValue({ data: { ignored: true } });
    expect(await clearUserSubRegionLock(5)).toBeUndefined();
    expect(apiClient.delete).toHaveBeenCalledWith('/api/users/5/sub-regions/lock');
  });

  it('propagates a failure rather than swallowing it', async () => {
    apiClient.get.mockRejectedValue(new Error('403'));
    await expect(getUsers()).rejects.toThrow('403');
  });
});
