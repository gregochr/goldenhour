import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../api/axiosClient.js';
import { setPasswordForNewUser, register } from '../api/authApi.js';

vi.mock('../api/axiosClient.js', () => ({ default: { post: vi.fn() } }));

describe('authApi request bodies', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.post.mockResolvedValue({ data: { ok: true } });
  });

  it('setPasswordForNewUser posts exactly {token, password} — no turnstileToken key at all', async () => {
    const data = await setPasswordForNewUser('verify-token', 'Abcdef1!');

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/set-password', {
      token: 'verify-token',
      password: 'Abcdef1!',
    });
    expect(Object.keys(apiClient.post.mock.calls[0][1])).toEqual(['token', 'password']);
    expect(data).toEqual({ ok: true });
  });

  it('setPasswordForNewUser ignores a stray third argument rather than sending it', async () => {
    await setPasswordForNewUser('verify-token', 'Abcdef1!', 'stale-turnstile');

    expect(Object.keys(apiClient.post.mock.calls[0][1])).toEqual(['token', 'password']);
  });

  it('register sends marketingEmailOptIn as the string "false" when the box is unticked', async () => {
    await register('newuser', 'new@example.com', 'tok', false, true);

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/register', {
      username: 'newuser',
      email: 'new@example.com',
      turnstileToken: 'tok',
      marketingEmailOptIn: 'false',
      termsAccepted: 'true',
    });
  });
});
