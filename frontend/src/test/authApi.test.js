import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../api/axiosClient.js';
import { setPasswordForNewUser, register, requestPasswordReset, resetPassword } from '../api/authApi.js';

vi.mock('../api/axiosClient.js', () => ({ default: { post: vi.fn() }, SKIP_AUTH_REFRESH: 'skipAuthRefresh' }));

const SKIP = { skipAuthRefresh: true };

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
    }, SKIP);
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
    }, SKIP);
  });

  it('requestPasswordReset posts {email, turnstileToken} to /forgot-password and returns the body', async () => {
    apiClient.post.mockResolvedValue({ data: { message: 'sent' } });

    const data = await requestPasswordReset('a@example.com', 'tok');

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/forgot-password', {
      email: 'a@example.com',
      turnstileToken: 'tok',
    }, SKIP);
    expect(data).toEqual({ message: 'sent' });
  });

  it('resetPassword posts exactly {token, password} to /reset-password — no Turnstile key', async () => {
    const data = await resetPassword('reset-token', 'Abcdef1!');

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/reset-password', {
      token: 'reset-token',
      password: 'Abcdef1!',
    }, SKIP);
    expect(Object.keys(apiClient.post.mock.calls[0][1])).toEqual(['token', 'password']);
    expect(data).toEqual({ ok: true });
  });
});
