import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { login as apiLogin, logout as apiLogout, changePassword as apiChangePassword, refreshAccessToken as apiRefresh } from '../api/authApi.js';

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
const ROLE_KEY = 'goldenhour_role';
const MUST_CHANGE_KEY = 'goldenhour_must_change';
const REFRESH_EXPIRES_KEY = 'goldenhour_refresh_expires';
const USERNAME_KEY = 'goldenhour_username';
const MARKETING_OPT_IN_KEY = 'goldenhour_marketing_opt_in';

const AuthContext = createContext(null);

/**
 * Reads a stored value from localStorage, returning null if missing or invalid.
 *
 * @param {string} key - localStorage key.
 * @returns {string|null}
 */
function readStorage(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * Provides authentication state and actions to the component tree.
 *
 * @param {object} props
 * @param {React.ReactNode} props.children
 */
export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => readStorage(TOKEN_KEY));
  const [refreshToken, setRefreshToken] = useState(() => readStorage(REFRESH_KEY));
  const [role, setRole] = useState(() => readStorage(ROLE_KEY));
  const [username, setUsername] = useState(() => readStorage(USERNAME_KEY));
  const [mustChangePassword, setMustChangePassword] = useState(
    () => readStorage(MUST_CHANGE_KEY) === 'true'
  );
  const [refreshExpiresAt, setRefreshExpiresAt] = useState(() => readStorage(REFRESH_EXPIRES_KEY));
  const [marketingEmailOptIn, setMarketingEmailOptIn] = useState(
    () => readStorage(MARKETING_OPT_IN_KEY) !== 'false'
  );

  const login = useCallback(async (username, password, turnstileToken) => {
    const data = await apiLogin(username, password, turnstileToken);
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    localStorage.setItem(REFRESH_KEY, data.refreshToken);
    localStorage.setItem(ROLE_KEY, data.role);
    localStorage.setItem(MUST_CHANGE_KEY, String(data.passwordChangeRequired ?? false));
    if (data.username) localStorage.setItem(USERNAME_KEY, data.username);
    if (data.refreshExpiresAt) localStorage.setItem(REFRESH_EXPIRES_KEY, data.refreshExpiresAt);
    if (data.marketingEmailOptIn != null) localStorage.setItem(MARKETING_OPT_IN_KEY, String(data.marketingEmailOptIn));
    setToken(data.accessToken);
    setRefreshToken(data.refreshToken);
    setRole(data.role);
    if (data.username) setUsername(data.username);
    setMustChangePassword(data.passwordChangeRequired ?? false);
    if (data.refreshExpiresAt) setRefreshExpiresAt(data.refreshExpiresAt);
    if (data.marketingEmailOptIn != null) setMarketingEmailOptIn(data.marketingEmailOptIn);
  }, []);

  const logout = useCallback(async () => {
    const storedRefresh = readStorage(REFRESH_KEY);
    try {
      if (storedRefresh) {
        await apiLogout(storedRefresh);
      }
    } catch {
      // Ignore errors on logout — clean up locally regardless
    }
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_KEY);
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(MUST_CHANGE_KEY);
    localStorage.removeItem(USERNAME_KEY);
    localStorage.removeItem(REFRESH_EXPIRES_KEY);
    localStorage.removeItem(MARKETING_OPT_IN_KEY);
    setToken(null);
    setRefreshToken(null);
    setRole(null);
    setUsername(null);
    setMustChangePassword(false);
    setRefreshExpiresAt(null);
    setMarketingEmailOptIn(true);
  }, []);

  const changePassword = useCallback(async (newPassword, turnstileToken) => {
    await apiChangePassword(newPassword, turnstileToken);
    localStorage.removeItem(MUST_CHANGE_KEY);
    setMustChangePassword(false);
  }, []);

  const completeRegistration = useCallback((data) => {
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    localStorage.setItem(REFRESH_KEY, data.refreshToken);
    localStorage.setItem(ROLE_KEY, data.role);
    localStorage.setItem(MUST_CHANGE_KEY, 'false');
    if (data.username) localStorage.setItem(USERNAME_KEY, data.username);
    if (data.refreshExpiresAt) localStorage.setItem(REFRESH_EXPIRES_KEY, data.refreshExpiresAt);
    if (data.marketingEmailOptIn != null) localStorage.setItem(MARKETING_OPT_IN_KEY, String(data.marketingEmailOptIn));
    setToken(data.accessToken);
    setRefreshToken(data.refreshToken);
    setRole(data.role);
    if (data.username) setUsername(data.username);
    setMustChangePassword(false);
    if (data.refreshExpiresAt) setRefreshExpiresAt(data.refreshExpiresAt);
    if (data.marketingEmailOptIn != null) setMarketingEmailOptIn(data.marketingEmailOptIn);
  }, []);

  const refreshSession = useCallback(async () => {
    const stored = readStorage(REFRESH_KEY);
    if (!stored) return;
    const data = await apiRefresh(stored);
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    if (data.refreshToken) localStorage.setItem(REFRESH_KEY, data.refreshToken);
    if (data.refreshExpiresAt) {
      localStorage.setItem(REFRESH_EXPIRES_KEY, data.refreshExpiresAt);
      setRefreshExpiresAt(data.refreshExpiresAt);
    }
    setToken(data.accessToken);
    if (data.refreshToken) setRefreshToken(data.refreshToken);
  }, []);

  const sessionDaysRemaining = useMemo(() => {
    if (!refreshExpiresAt) return null;
    const diff = new Date(refreshExpiresAt) - Date.now();
    if (diff <= 0) return 0;
    return Math.round(diff / (1000 * 60 * 60 * 24));
  }, [refreshExpiresAt]);

  const value = useMemo(() => ({
    token,
    refreshToken,
    role,
    username,
    mustChangePassword,
    sessionDaysRemaining,
    marketingEmailOptIn,
    login,
    logout,
    changePassword,
    completeRegistration,
    refreshSession,
    isAdmin: role === 'ADMIN',
  }), [token, refreshToken, role, username, mustChangePassword, sessionDaysRemaining, marketingEmailOptIn, login, logout, changePassword, completeRegistration, refreshSession]);

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

AuthProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

/**
 * Hook to access authentication state and actions.
 *
 * @returns {{ token: string|null, role: string|null, mustChangePassword: boolean,
 *             login: Function, logout: Function, changePassword: Function, isAdmin: boolean }}
 */
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return ctx;
}
