import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AuthProvider, useAuth } from '../context/AuthContext.jsx';

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
  const { token, role, username, mustChangePassword, marketingEmailOptIn } = useAuth();
  return (
    <div>
      <span data-testid="token">{token ?? 'null'}</span>
      <span data-testid="role">{role ?? 'null'}</span>
      <span data-testid="username">{username ?? 'null'}</span>
      <span data-testid="must-change">{String(mustChangePassword)}</span>
      <span data-testid="marketing">{String(marketingEmailOptIn)}</span>
    </div>
  );
}

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
  });
});
