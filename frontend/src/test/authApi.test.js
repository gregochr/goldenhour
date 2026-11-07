import { describe, it, expect, vi, beforeEach } from 'vitest';
import apiClient from '../api/axiosClient.js';
import {
  setPasswordForNewUser, register, requestPasswordReset, resetPassword, verifyEmail, logout, submitWaitlist,
  login, refreshAccessToken, changePassword, updateMarketingEmailOptIn, resendVerification,
} from '../api/authApi.js';

vi.mock('../api/axiosClient.js', () => ({ default: { post: vi.fn(), put: vi.fn() }, SKIP_AUTH_REFRESH: 'skipAuthRefresh' }));

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

  it('verifyEmail posts the emailed token and returns the rotated setupToken the server answers with', async () => {
    apiClient.post.mockResolvedValue({ data: { userId: 7, verified: true, setupToken: 'setup-tok' } });

    const data = await verifyEmail('mailed-token');

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/verify-email', { token: 'mailed-token' }, SKIP);
    expect(data.setupToken).toBe('setup-tok');
  });

  it('logout posts the captured refresh token and opts out of the refresh cycle', async () => {
    await logout('the-refresh');

    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/logout', { refreshToken: 'the-refresh' }, SKIP);
  });

  it('register sends inviteToken when one is given, and no inviteToken key at all when not', async () => {
    await register('newuser', 'new@example.com', 'tok', false, true, 'invite-raw');
    expect(apiClient.post).toHaveBeenLastCalledWith('/api/auth/register', {
      username: 'newuser',
      email: 'new@example.com',
      turnstileToken: 'tok',
      marketingEmailOptIn: 'false',
      termsAccepted: 'true',
      inviteToken: 'invite-raw',
    }, SKIP);

    await register('newuser', 'new@example.com', 'tok', false, true, null);
    expect(Object.keys(apiClient.post.mock.calls[1][1])).not.toContain('inviteToken');
  });

  it('submitWaitlist posts the address and its Turnstile token', async () => {
    await submitWaitlist('wait@example.com', 'wtok');

    expect(apiClient.post).toHaveBeenCalledWith('/api/waitlist',
      { email: 'wait@example.com', turnstileToken: 'wtok' }, SKIP);
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

  it('login posts credentials with the Turnstile token and opts out of the refresh cycle', async () => {
    const data = await login('ann', 'pw', 'ts-1');
    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/login',
      { username: 'ann', password: 'pw', turnstileToken: 'ts-1' }, SKIP);
    expect(data).toEqual({ ok: true });
  });

  it('refreshAccessToken posts only the refresh token and opts out of the refresh cycle', async () => {
    const data = await refreshAccessToken('r-1');
    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/refresh', { refreshToken: 'r-1' }, SKIP);
    expect(data).toEqual({ ok: true });
  });

  it('changePassword posts both passwords and Turnstile WITHOUT the skip flag (it needs the bearer token)', async () => {
    const data = await changePassword('old', 'new', 'ts-2');
    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/change-password',
      { currentPassword: 'old', newPassword: 'new', turnstileToken: 'ts-2' });
    expect(apiClient.post.mock.calls[0]).toHaveLength(2);
    expect(data).toEqual({ ok: true });
  });

  it('updateMarketingEmailOptIn PUTs the boolean as given, false included', async () => {
    apiClient.put.mockResolvedValue({ data: { optIn: false } });
    expect(await updateMarketingEmailOptIn(false)).toEqual({ optIn: false });
    expect(apiClient.put).toHaveBeenCalledWith('/api/auth/marketing-emails', { optIn: false });
  });

  it('resendVerification posts the email and opts out of the refresh cycle', async () => {
    const data = await resendVerification('a@b.c');
    expect(apiClient.post).toHaveBeenCalledWith('/api/auth/resend-verification', { email: 'a@b.c' }, SKIP);
    expect(data).toEqual({ ok: true });
  });
});
