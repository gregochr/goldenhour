import { render, screen, act, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AuthProvider, useAuth } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');
vi.mock('../api/forecastApi.js', () => ({ clearForecastDetailCache: vi.fn() }));

const KEYS = {
  token: 'goldenhour_token',
  refresh: 'goldenhour_refresh',
  role: 'goldenhour_role',
  username: 'goldenhour_username',
  mustChange: 'goldenhour_must_change',
  refreshExpires: 'goldenhour_refresh_expires',
  marketing: 'goldenhour_marketing_opt_in',
};

const FULL = {
  accessToken: 'access-1',
  refreshToken: 'refresh-1',
  role: 'PRO_USER',
  username: 'chris',
  refreshExpiresAt: '2026-12-01T00:00:00Z',
  marketingEmailOptIn: false,
  passwordChangeRequired: false,
};

/** Surfaces every field the provider derives, plus buttons for the actions under test. */
function Probe({ loginArgs = ['chris', 'pw', 'ts'], session = FULL }) {
  const auth = useAuth();
  return (
    <div>
      <span data-testid="token">{auth.token ?? 'null'}</span>
      <span data-testid="refresh">{auth.refreshToken ?? 'null'}</span>
      <span data-testid="role">{auth.role ?? 'null'}</span>
      <span data-testid="username">{auth.username ?? 'null'}</span>
      <span data-testid="must-change">{String(auth.mustChangePassword)}</span>
      <span data-testid="marketing">{String(auth.marketingEmailOptIn)}</span>
      <span data-testid="days">{String(auth.sessionDaysRemaining)}</span>
      <span data-testid="expired">{String(auth.sessionExpired)}</span>
      <span data-testid="admin">{String(auth.isAdmin)}</span>
      <button onClick={() => auth.login(...loginArgs).catch(() => {})}>do-login</button>
      <button onClick={() => auth.refreshSession().catch(() => {})}>do-refresh</button>
      <button onClick={() => auth.signInWithTokens(session)}>do-sign-in</button>
      <button onClick={() => auth.logout()}>do-logout</button>
    </div>
  );
}

const renderProbe = (props) =>
  render(
    <AuthProvider>
      <Probe {...props} />
    </AuthProvider>,
  );

const text = (id) => screen.getByTestId(id).textContent;
const click = async (name) => {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name }));
  });
};

beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('AuthContext.login', () => {
  it('passes the credentials and Turnstile token to the API, then stores and exposes the whole session', async () => {
    AuthApi.login.mockResolvedValue({ ...FULL, role: 'ADMIN' });
    renderProbe();

    await click('do-login');

    expect(AuthApi.login).toHaveBeenCalledWith('chris', 'pw', 'ts');
    expect(text('token')).toBe('access-1');
    expect(text('refresh')).toBe('refresh-1');
    expect(text('role')).toBe('ADMIN');
    expect(text('admin')).toBe('true');
    expect(text('username')).toBe('chris');
    expect(text('marketing')).toBe('false');
    expect(localStorage.getItem(KEYS.token)).toBe('access-1');
    expect(localStorage.getItem(KEYS.refresh)).toBe('refresh-1');
    expect(localStorage.getItem(KEYS.role)).toBe('ADMIN');
    expect(localStorage.getItem(KEYS.username)).toBe('chris');
    expect(localStorage.getItem(KEYS.refreshExpires)).toBe('2026-12-01T00:00:00Z');
    expect(localStorage.getItem(KEYS.marketing)).toBe('false');
    expect(localStorage.getItem(KEYS.mustChange)).toBe('false');
  });

  it('is not admin for a non-admin role', async () => {
    AuthApi.login.mockResolvedValue(FULL);
    renderProbe();
    await click('do-login');
    expect(text('admin')).toBe('false');
  });

  it('sets the first-login gate when the server demands a password change', async () => {
    AuthApi.login.mockResolvedValue({ ...FULL, passwordChangeRequired: true });
    renderProbe();

    await click('do-login');

    expect(text('must-change')).toBe('true');
    expect(localStorage.getItem(KEYS.mustChange)).toBe('true');
  });

  it('treats a missing passwordChangeRequired as false and writes no optional keys it was not given', async () => {
    AuthApi.login.mockResolvedValue({ accessToken: 'a', refreshToken: 'r', role: 'LITE_USER' });
    renderProbe();

    await click('do-login');

    expect(text('must-change')).toBe('false');
    expect(text('username')).toBe('null');
    expect(text('days')).toBe('null');
    expect(text('marketing')).toBe('true');
    expect(localStorage.getItem(KEYS.mustChange)).toBe('false');
    expect(localStorage.getItem(KEYS.username)).toBeNull();
    expect(localStorage.getItem(KEYS.refreshExpires)).toBeNull();
    expect(localStorage.getItem(KEYS.marketing)).toBeNull();
  });

  it('ends the expired-session state', async () => {
    AuthApi.login.mockResolvedValue(FULL);
    renderProbe();
    act(() => {
      window.dispatchEvent(new Event('goldenhour:session-expired'));
    });
    expect(text('expired')).toBe('true');

    await click('do-login');

    expect(text('expired')).toBe('false');
  });

  it('stores nothing when the API rejects the credentials', async () => {
    AuthApi.login.mockRejectedValue(new Error('401'));
    renderProbe();

    await click('do-login');

    expect(text('token')).toBe('null');
    expect(localStorage.getItem(KEYS.token)).toBeNull();
  });
});

describe('AuthContext.refreshSession', () => {
  it('does nothing, and calls no API, when there is no stored refresh token', async () => {
    renderProbe();

    await click('do-refresh');

    expect(AuthApi.refreshAccessToken).not.toHaveBeenCalled();
    expect(text('token')).toBe('null');
  });

  it('exchanges the stored refresh token and adopts the rotated tokens and new expiry', async () => {
    localStorage.setItem(KEYS.token, 'old-access');
    localStorage.setItem(KEYS.refresh, 'old-refresh');
    AuthApi.refreshAccessToken.mockResolvedValue({
      accessToken: 'new-access',
      refreshToken: 'new-refresh',
      refreshExpiresAt: '2030-01-01T00:00:00Z',
    });
    renderProbe();

    await click('do-refresh');

    expect(AuthApi.refreshAccessToken).toHaveBeenCalledWith('old-refresh');
    expect(text('token')).toBe('new-access');
    expect(text('refresh')).toBe('new-refresh');
    expect(localStorage.getItem(KEYS.token)).toBe('new-access');
    expect(localStorage.getItem(KEYS.refresh)).toBe('new-refresh');
    expect(localStorage.getItem(KEYS.refreshExpires)).toBe('2030-01-01T00:00:00Z');
    expect(text('days')).not.toBe('null');
  });

  it('keeps the existing refresh token and expiry when the response carries only an access token', async () => {
    localStorage.setItem(KEYS.token, 'old-access');
    localStorage.setItem(KEYS.refresh, 'old-refresh');
    localStorage.setItem(KEYS.refreshExpires, '2030-01-01T00:00:00Z');
    AuthApi.refreshAccessToken.mockResolvedValue({ accessToken: 'new-access' });
    renderProbe();

    await click('do-refresh');

    expect(text('token')).toBe('new-access');
    expect(text('refresh')).toBe('old-refresh');
    expect(localStorage.getItem(KEYS.refresh)).toBe('old-refresh');
    expect(localStorage.getItem(KEYS.refreshExpires)).toBe('2030-01-01T00:00:00Z');
  });

  it('leaves the session untouched when the refresh is rejected', async () => {
    localStorage.setItem(KEYS.token, 'old-access');
    localStorage.setItem(KEYS.refresh, 'old-refresh');
    AuthApi.refreshAccessToken.mockRejectedValue(new Error('401'));
    renderProbe();

    await click('do-refresh');

    expect(text('token')).toBe('old-access');
    expect(localStorage.getItem(KEYS.token)).toBe('old-access');
  });
});

describe('AuthContext.signInWithTokens with a sparse session', () => {
  it('writes only the keys it was given and does not disturb stored optional values', () => {
    localStorage.setItem(KEYS.username, 'chris');
    localStorage.setItem(KEYS.marketing, 'false');
    renderProbe({ session: { accessToken: 'a2', refreshToken: 'r2', role: 'PRO_USER' } });

    act(() => {
      fireEvent.click(screen.getByRole('button', { name: 'do-sign-in' }));
    });

    expect(text('token')).toBe('a2');
    expect(text('username')).toBe('chris');
    expect(text('marketing')).toBe('false');
    expect(localStorage.getItem(KEYS.refreshExpires)).toBeNull();
    expect(localStorage.getItem(KEYS.username)).toBe('chris');
  });
});

describe('AuthContext.logout', () => {
  it('makes no API call when there is no refresh token to revoke', async () => {
    localStorage.setItem(KEYS.token, 'only-access');
    renderProbe();

    await click('do-logout');

    expect(AuthApi.logout).not.toHaveBeenCalled();
    expect(text('token')).toBe('null');
    expect(localStorage.getItem(KEYS.token)).toBeNull();
  });

  it('restores the marketing default to opted-in', async () => {
    localStorage.setItem(KEYS.marketing, 'false');
    renderProbe();
    expect(text('marketing')).toBe('false');

    await click('do-logout');

    expect(text('marketing')).toBe('true');
    expect(localStorage.getItem(KEYS.marketing)).toBeNull();
  });
});

describe('AuthContext.sessionDaysRemaining', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-04T12:00:00Z'));
  });

  it('is null with no stored expiry', () => {
    renderProbe();
    expect(text('days')).toBe('null');
  });

  it('rounds the days until the refresh token expires', () => {
    localStorage.setItem(KEYS.refreshExpires, '2026-11-14T12:00:00Z');
    renderProbe();
    expect(text('days')).toBe('10');
  });

  it('is 0, not negative, once the expiry has passed', () => {
    localStorage.setItem(KEYS.refreshExpires, '2026-11-01T00:00:00Z');
    renderProbe();
    expect(text('days')).toBe('0');
  });
});

describe('AuthContext storage resilience', () => {
  it('starts signed out, rather than throwing, when localStorage cannot be read', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage disabled');
    });
    renderProbe();
    expect(text('token')).toBe('null');
    expect(text('role')).toBe('null');
    expect(text('marketing')).toBe('true');
  });

  it('still routes to the change-password gate in memory when the flag cannot be persisted', () => {
    renderProbe();
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });

    act(() => {
      window.dispatchEvent(new Event('goldenhour:password-change-required'));
    });

    expect(text('must-change')).toBe('true');
  });

  it('reads an opt-out of marketing email from storage', () => {
    localStorage.setItem(KEYS.marketing, 'false');
    renderProbe();
    expect(text('marketing')).toBe('false');
  });
});
