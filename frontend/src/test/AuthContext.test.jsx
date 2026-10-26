import { render, screen, act, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AuthProvider, useAuth } from '../context/AuthContext.jsx';
import { writeSwrCache, readSwrCache } from '../utils/swrCache.js';
import * as AuthApi from '../api/authApi.js';
import * as ForecastApi from '../api/forecastApi.js';

vi.mock('../api/authApi.js');

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
const ROLE_KEY = 'goldenhour_role';
const USERNAME_KEY = 'goldenhour_username';
const MUST_CHANGE_KEY = 'goldenhour_must_change';
const REFRESH_EXPIRES_KEY = 'goldenhour_refresh_expires';
const MARKETING_OPT_IN_KEY = 'goldenhour_marketing_opt_in';

/**
 * Test component that displays auth state for assertion.
 */
function AuthStateDisplay() {
  const { token, role, username, mustChangePassword, marketingEmailOptIn, logout } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? 'null'}</span>
      <span data-testid="role">{role ?? 'null'}</span>
      <span data-testid="username">{username ?? 'null'}</span>
      <span data-testid="must-change">{String(mustChangePassword)}</span>
      <span data-testid="marketing">{String(marketingEmailOptIn)}</span>
      <button data-testid="logout-btn" onClick={logout}>logout</button>
    </div>
  );
}

/**
 * Exposes the registration/reset actions of the context as buttons, and the pending flag as text.
 */
function SessionActions({ data }) {
  const {
    registrationPending, signInWithTokens, completeRegistration, finishRegistration, changePassword,
    logout, token, mustChangePassword, role, sessionExpired,
    postcodePromptSuppressed, suppressPostcodePrompt,
  } = useAuth();
  return (
    <div>
      <span data-testid="pending">{String(registrationPending)}</span>
      <span data-testid="suppressed">{String(postcodePromptSuppressed)}</span>
      <button data-testid="suppress" onClick={suppressPostcodePrompt}>suppress</button>
      <span data-testid="expired">{String(sessionExpired)}</span>
      <span data-testid="token">{token ?? 'null'}</span>
      <span data-testid="role">{role ?? 'null'}</span>
      <span data-testid="must-change">{String(mustChangePassword)}</span>
      <button data-testid="sign-in" onClick={() => signInWithTokens(data)}>sign in</button>
      <button data-testid="complete" onClick={() => completeRegistration(data)}>complete</button>
      <button data-testid="finish" onClick={finishRegistration}>finish</button>
      <button data-testid="change" onClick={() => changePassword('New1!pass', 'ts').catch(() => {})}>change</button>
      <button data-testid="logout" onClick={logout}>logout</button>
    </div>
  );
}

vi.mock('../api/forecastApi.js', () => ({ clearForecastDetailCache: vi.fn() }));

const SESSION = {
  accessToken: 'access-1', refreshToken: 'refresh-1', role: 'PRO_USER', username: 'chris',
  refreshExpiresAt: '2026-12-01T00:00:00Z', marketingEmailOptIn: true, passwordChangeRequired: false,
};

describe('AuthContext', () => {
  beforeEach(() => {
    [TOKEN_KEY, REFRESH_KEY, ROLE_KEY, USERNAME_KEY, MUST_CHANGE_KEY, REFRESH_EXPIRES_KEY, MARKETING_OPT_IN_KEY].forEach(k => localStorage.removeItem(k));
  });

  afterEach(() => {
    [TOKEN_KEY, REFRESH_KEY, ROLE_KEY, USERNAME_KEY, MUST_CHANGE_KEY, REFRESH_EXPIRES_KEY, MARKETING_OPT_IN_KEY].forEach(k => localStorage.removeItem(k));
  });

  it('reads initial state from localStorage', () => {
    localStorage.setItem(TOKEN_KEY, 'test-jwt');
    localStorage.setItem(ROLE_KEY, 'ADMIN');
    localStorage.setItem(USERNAME_KEY, 'admin');

    render(
      <AuthProvider>
        <AuthStateDisplay />
      </AuthProvider>
    );

    expect(screen.getByTestId('token')).toHaveTextContent('test-jwt');
    expect(screen.getByTestId('role')).toHaveTextContent('ADMIN');
    expect(screen.getByTestId('username')).toHaveTextContent('admin');
  });

  it('renders null state when localStorage is empty', () => {
    render(
      <AuthProvider>
        <AuthStateDisplay />
      </AuthProvider>
    );

    expect(screen.getByTestId('token')).toHaveTextContent('null');
    expect(screen.getByTestId('role')).toHaveTextContent('null');
    expect(screen.getByTestId('username')).toHaveTextContent('null');
  });

  describe('session-expired event', () => {
    it('clears all auth state when goldenhour:session-expired fires', () => {
      localStorage.setItem(TOKEN_KEY, 'test-jwt');
      localStorage.setItem(REFRESH_KEY, 'test-refresh');
      localStorage.setItem(ROLE_KEY, 'ADMIN');
      localStorage.setItem(USERNAME_KEY, 'admin');
      localStorage.setItem(MUST_CHANGE_KEY, 'false');
      localStorage.setItem(REFRESH_EXPIRES_KEY, '2026-04-01T00:00:00Z');
      localStorage.setItem(MARKETING_OPT_IN_KEY, 'false');

      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      // Verify initial state is populated
      expect(screen.getByTestId('token')).toHaveTextContent('test-jwt');
      expect(screen.getByTestId('role')).toHaveTextContent('ADMIN');
      expect(screen.getByTestId('username')).toHaveTextContent('admin');
      expect(screen.getByTestId('marketing')).toHaveTextContent('false');

      // Fire the session-expired event
      act(() => {
        window.dispatchEvent(new Event('goldenhour:session-expired'));
      });

      // All auth state should be cleared
      expect(screen.getByTestId('token')).toHaveTextContent('null');
      expect(screen.getByTestId('role')).toHaveTextContent('null');
      expect(screen.getByTestId('username')).toHaveTextContent('null');
      expect(screen.getByTestId('must-change')).toHaveTextContent('false');
      expect(screen.getByTestId('marketing')).toHaveTextContent('true');
    });

    it('cleans up event listener on unmount', () => {
      const removeSpy = vi.spyOn(window, 'removeEventListener');

      const { unmount } = render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      unmount();

      expect(removeSpy).toHaveBeenCalledWith(
        'goldenhour:session-expired',
        expect.any(Function)
      );

      removeSpy.mockRestore();
    });

    it('clears the instant-paint SWR cache so it never leaks to the next session', () => {
      writeSwrCache('briefing:ADMIN', { best: 'Bamburgh' });
      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      act(() => {
        window.dispatchEvent(new Event('goldenhour:session-expired'));
      });

      expect(readSwrCache('briefing:ADMIN')).toBeNull();
    });
  });

  describe('logout', () => {
    it('clears the instant-paint SWR cache', async () => {
      writeSwrCache('briefing:ADMIN', { best: 'Bamburgh' });
      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      await act(async () => {
        fireEvent.click(screen.getByTestId('logout-btn'));
      });

      expect(readSwrCache('briefing:ADMIN')).toBeNull();
    });
  });

  describe('logout ordering', () => {
    it('clears storage and the in-memory token BEFORE the logout request resolves, and posts the captured '
      + 'refresh token', async () => {
      localStorage.setItem(TOKEN_KEY, 'jwt');
      localStorage.setItem(REFRESH_KEY, 'the-refresh');
      let releaseRequest;
      let storageDuringRequest;
      AuthApi.logout.mockImplementation(() => new Promise((resolve) => {
        storageDuringRequest = {
          token: localStorage.getItem(TOKEN_KEY), refresh: localStorage.getItem(REFRESH_KEY),
        };
        releaseRequest = resolve;
      }));
      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );
      expect(screen.getByTestId('token')).toHaveTextContent('jwt');

      await act(async () => {
        fireEvent.click(screen.getByTestId('logout-btn'));
      });

      // The request is still pending, and the session is already gone locally.
      expect(AuthApi.logout).toHaveBeenCalledWith('the-refresh');
      expect(storageDuringRequest).toEqual({ token: null, refresh: null });
      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(localStorage.getItem(REFRESH_KEY)).toBeNull();
      expect(screen.getByTestId('token')).toHaveTextContent('null');

      await act(async () => { releaseRequest(); });
    });

    it('still ends the session locally when the logout request fails', async () => {
      localStorage.setItem(TOKEN_KEY, 'jwt');
      localStorage.setItem(REFRESH_KEY, 'the-refresh');
      AuthApi.logout.mockRejectedValue(new Error('network'));
      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      await act(async () => {
        fireEvent.click(screen.getByTestId('logout-btn'));
      });

      expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
      expect(screen.getByTestId('token')).toHaveTextContent('null');
    });
  });

  describe('token-refreshed event', () => {
    it('re-syncs token from localStorage when goldenhour:token-refreshed fires', () => {
      localStorage.setItem(TOKEN_KEY, 'old-jwt');
      localStorage.setItem(REFRESH_KEY, 'old-refresh');

      render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      expect(screen.getByTestId('token')).toHaveTextContent('old-jwt');

      // The axios interceptor refreshes localStorage then notifies AuthContext.
      act(() => {
        localStorage.setItem(TOKEN_KEY, 'fresh-jwt');
        localStorage.setItem(REFRESH_KEY, 'fresh-refresh');
        window.dispatchEvent(new Event('goldenhour:token-refreshed'));
      });

      // In-memory token tracks the refreshed value — so consumers keyed on it
      // (the health SSE stream) reconnect with a valid token instead of stranding.
      expect(screen.getByTestId('token')).toHaveTextContent('fresh-jwt');
    });

    it('cleans up the token-refreshed listener on unmount', () => {
      const removeSpy = vi.spyOn(window, 'removeEventListener');

      const { unmount } = render(
        <AuthProvider>
          <AuthStateDisplay />
        </AuthProvider>
      );

      unmount();

      expect(removeSpy).toHaveBeenCalledWith(
        'goldenhour:token-refreshed',
        expect.any(Function)
      );

      removeSpy.mockRestore();
    });
  });

  describe('signInWithTokens, completeRegistration and registrationPending (login-registration-plan.md §2 #2)', () => {
    const renderActions = (data = SESSION) => render(
      <AuthProvider>
        <SessionActions data={data} />
      </AuthProvider>,
    );

    it('signInWithTokens stores the whole session and leaves registrationPending false', () => {
      renderActions();

      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });

      expect(screen.getByTestId('token')).toHaveTextContent('access-1');
      expect(screen.getByTestId('role')).toHaveTextContent('PRO_USER');
      expect(screen.getByTestId('pending')).toHaveTextContent('false');
      expect(localStorage.getItem(TOKEN_KEY)).toBe('access-1');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-1');
      expect(localStorage.getItem(ROLE_KEY)).toBe('PRO_USER');
      expect(localStorage.getItem(USERNAME_KEY)).toBe('chris');
      expect(localStorage.getItem(MUST_CHANGE_KEY)).toBe('false');
      expect(localStorage.getItem(REFRESH_EXPIRES_KEY)).toBe('2026-12-01T00:00:00Z');
      expect(localStorage.getItem(MARKETING_OPT_IN_KEY)).toBe('true');
    });

    it('signInWithTokens clears a stored first-login flag — a reset is the new password', () => {
      localStorage.setItem(MUST_CHANGE_KEY, 'true');
      renderActions();
      expect(screen.getByTestId('must-change')).toHaveTextContent('true');

      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });

      expect(screen.getByTestId('must-change')).toHaveTextContent('false');
      expect(localStorage.getItem(MUST_CHANGE_KEY)).toBe('false');
    });

    it('completeRegistration signs in AND sets registrationPending', () => {
      renderActions();

      act(() => { fireEvent.click(screen.getByTestId('complete')); });

      expect(screen.getByTestId('token')).toHaveTextContent('access-1');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-1');
      expect(screen.getByTestId('pending')).toHaveTextContent('true');
    });

    it('finishRegistration clears the flag and keeps the session', () => {
      renderActions();
      act(() => { fireEvent.click(screen.getByTestId('complete')); });

      act(() => { fireEvent.click(screen.getByTestId('finish')); });

      expect(screen.getByTestId('pending')).toHaveTextContent('false');
      expect(screen.getByTestId('token')).toHaveTextContent('access-1');
    });

    it('logout clears the flag', async () => {
      renderActions();
      act(() => { fireEvent.click(screen.getByTestId('complete')); });
      expect(screen.getByTestId('pending')).toHaveTextContent('true');

      await act(async () => { fireEvent.click(screen.getByTestId('logout')); });

      expect(screen.getByTestId('pending')).toHaveTextContent('false');
      expect(screen.getByTestId('token')).toHaveTextContent('null');
    });

    it('the session-expired event clears the flag, so the gate cannot be left on the registration page with no token', () => {
      renderActions();
      act(() => { fireEvent.click(screen.getByTestId('complete')); });
      expect(screen.getByTestId('pending')).toHaveTextContent('true');

      act(() => { window.dispatchEvent(new Event('goldenhour:session-expired')); });

      expect(screen.getByTestId('pending')).toHaveTextContent('false');
      expect(screen.getByTestId('token')).toHaveTextContent('null');
    });

    describe('postcodePromptSuppressed (login-registration-plan.md §2 #2, §5 #3)', () => {
      it('is false until suppressPostcodePrompt is called, and signing in does not set it', () => {
        renderActions();
        expect(screen.getByTestId('suppressed')).toHaveTextContent('false');

        act(() => { fireEvent.click(screen.getByTestId('complete')); });
        expect(screen.getByTestId('suppressed')).toHaveTextContent('false');

        act(() => { fireEvent.click(screen.getByTestId('suppress')); });
        expect(screen.getByTestId('suppressed')).toHaveTextContent('true');
      });

      it('survives finishRegistration — the app that mounts next must still honour it', () => {
        renderActions();
        act(() => { fireEvent.click(screen.getByTestId('complete')); });
        act(() => { fireEvent.click(screen.getByTestId('suppress')); });

        act(() => { fireEvent.click(screen.getByTestId('finish')); });

        expect(screen.getByTestId('suppressed')).toHaveTextContent('true');
      });

      it('is memory only: nothing is written to storage', () => {
        renderActions();
        const before = Object.keys(localStorage).length;

        act(() => { fireEvent.click(screen.getByTestId('suppress')); });

        expect(Object.keys(localStorage).length).toBe(before);
      });

      it('logout clears it, so the next account on this browser is prompted', async () => {
        renderActions();
        act(() => { fireEvent.click(screen.getByTestId('suppress')); });

        await act(async () => { fireEvent.click(screen.getByTestId('logout')); });

        expect(screen.getByTestId('suppressed')).toHaveTextContent('false');
      });

      it('the session-expired event clears it too', () => {
        renderActions();
        act(() => { fireEvent.click(screen.getByTestId('suppress')); });

        act(() => { window.dispatchEvent(new Event('goldenhour:session-expired')); });

        expect(screen.getByTestId('suppressed')).toHaveTextContent('false');
      });
    });

    it('signInWithTokens ends the expired-session state, so a reset from the expiry screen does not keep it', () => {
      renderActions();
      act(() => { window.dispatchEvent(new Event('goldenhour:session-expired')); });
      expect(screen.getByTestId('expired')).toHaveTextContent('true');

      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });

      expect(screen.getByTestId('expired')).toHaveTextContent('false');
    });

    it('clears no cache on a first sign-in with no stored account', () => {
      ForecastApi.clearForecastDetailCache.mockClear();
      writeSwrCache('briefing:PRO_USER', { best: 'Bamburgh' });
      renderActions();

      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });

      expect(readSwrCache('briefing:PRO_USER')).toEqual({ best: 'Bamburgh' });
      expect(ForecastApi.clearForecastDetailCache).not.toHaveBeenCalled();
    });

    it('keeps the previous account\'s instant-paint cache for the same account but drops it for another', () => {
      ForecastApi.clearForecastDetailCache.mockClear();
      localStorage.setItem(USERNAME_KEY, 'chris');
      writeSwrCache('briefing:PRO_USER', { best: 'Bamburgh' });
      renderActions();

      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });
      expect(readSwrCache('briefing:PRO_USER')).toEqual({ best: 'Bamburgh' });
      expect(ForecastApi.clearForecastDetailCache).not.toHaveBeenCalled();

      cleanup();
      localStorage.setItem(USERNAME_KEY, 'someone-else');
      renderActions();
      act(() => { fireEvent.click(screen.getByTestId('sign-in')); });
      expect(readSwrCache('briefing:PRO_USER')).toBeNull();
      expect(ForecastApi.clearForecastDetailCache).toHaveBeenCalledOnce();
      expect(localStorage.getItem(USERNAME_KEY)).toBe('chris');
    });
  });

  describe('changePassword stores the fresh session when the response carries one (plan §2 #13)', () => {
    const renderActions = () => render(
      <AuthProvider>
        <SessionActions data={SESSION} />
      </AuthProvider>,
    );

    it('with a {message}-only response (today) it clears the first-login flag and stores no tokens', async () => {
      AuthApi.changePassword.mockResolvedValue({ message: 'Password changed' });
      localStorage.setItem(MUST_CHANGE_KEY, 'true');
      localStorage.setItem(TOKEN_KEY, 'old-access');
      localStorage.setItem(REFRESH_KEY, 'old-refresh');
      renderActions();

      await act(async () => { fireEvent.click(screen.getByTestId('change')); });

      expect(AuthApi.changePassword).toHaveBeenCalledWith('New1!pass', 'ts');
      expect(screen.getByTestId('must-change')).toHaveTextContent('false');
      expect(localStorage.getItem(MUST_CHANGE_KEY)).toBeNull();
      expect(localStorage.getItem(TOKEN_KEY)).toBe('old-access');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('old-refresh');
      expect(screen.getByTestId('token')).toHaveTextContent('old-access');
    });

    it('with an auth response it replaces both tokens, in storage and in state', async () => {
      AuthApi.changePassword.mockResolvedValue({ ...SESSION, message: 'Password changed' });
      localStorage.setItem(MUST_CHANGE_KEY, 'true');
      localStorage.setItem(TOKEN_KEY, 'old-access');
      localStorage.setItem(REFRESH_KEY, 'old-refresh');
      renderActions();

      await act(async () => { fireEvent.click(screen.getByTestId('change')); });

      expect(localStorage.getItem(TOKEN_KEY)).toBe('access-1');
      expect(localStorage.getItem(REFRESH_KEY)).toBe('refresh-1');
      expect(screen.getByTestId('token')).toHaveTextContent('access-1');
      expect(screen.getByTestId('must-change')).toHaveTextContent('false');
      expect(screen.getByTestId('pending')).toHaveTextContent('false');
    });

    it('a rejected change stores nothing and leaves the first-login flag set', async () => {
      AuthApi.changePassword.mockRejectedValue(new Error('nope'));
      localStorage.setItem(MUST_CHANGE_KEY, 'true');
      localStorage.setItem(TOKEN_KEY, 'old-access');
      renderActions();

      await act(async () => { fireEvent.click(screen.getByTestId('change')); });

      expect(localStorage.getItem(MUST_CHANGE_KEY)).toBe('true');
      expect(localStorage.getItem(TOKEN_KEY)).toBe('old-access');
    });
  });
});
