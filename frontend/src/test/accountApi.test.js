import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { delete: vi.fn() } }));
import apiClient from '../api/axiosClient.js';
import { deleteAccount } from '../api/accountApi.js';

beforeEach(() => vi.resetAllMocks());

describe('accountApi', () => {
  it('DELETEs /api/user/account with the password in the body, never in the URL', async () => {
    apiClient.delete.mockResolvedValue({ status: 204, data: '' });

    await deleteAccount('correct horse');

    expect(apiClient.delete).toHaveBeenCalledTimes(1);
    expect(apiClient.delete).toHaveBeenCalledWith('/api/user/account', {
      data: { password: 'correct horse' },
    });
  });

  it('resolves with nothing on a 204', async () => {
    apiClient.delete.mockResolvedValue({ status: 204, data: '' });
    await expect(deleteAccount('pw')).resolves.toBeUndefined();
  });

  it('lets a refusal reach the caller unchanged, so it can read the status and the sentence', async () => {
    const refusal = { response: { status: 403, data: { error: 'Password is incorrect' } } };
    apiClient.delete.mockRejectedValue(refusal);

    await expect(deleteAccount('wrong')).rejects.toBe(refusal);
  });
});
