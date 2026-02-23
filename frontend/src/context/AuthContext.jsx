import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { login as apiLogin, logout as apiLogout, changePassword as apiChangePassword } from '../api/authApi.js';

const TOKEN_KEY = 'goldenhour_token';
const REFRESH_KEY = 'goldenhour_refresh';
const ROLE_KEY = 'goldenhour_role';
const MUST_CHANGE_KEY = 'goldenhour_must_change';

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
  const [mustChangePassword, setMustChangePassword] = useState(
    () => readStorage(MUST_CHANGE_KEY) === 'true'
  );

  const login = useCallback(async (username, password) => {
    const data = await apiLogin(username, password);
    localStorage.setItem(TOKEN_KEY, data.accessToken);
    localStorage.setItem(REFRESH_KEY, data.refreshToken);
    localStorage.setItem(ROLE_KEY, data.role);
    localStorage.setItem(MUST_CHANGE_KEY, String(data.passwordChangeRequired ?? false));
    setToken(data.accessToken);
    setRefreshToken(data.refreshToken);
    setRole(data.role);
    setMustChangePassword(data.passwordChangeRequired ?? false);
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
    setToken(null);
    setRefreshToken(null);
    setRole(null);
    setMustChangePassword(false);
  }, []);

  const changePassword = useCallback(async (newPassword) => {
    await apiChangePassword(newPassword);
    localStorage.removeItem(MUST_CHANGE_KEY);
    setMustChangePassword(false);
  }, []);

  const value = useMemo(() => ({
    token,
    refreshToken,
    role,
    mustChangePassword,
    login,
    logout,
    changePassword,
    isAdmin: role === 'ADMIN',
  }), [token, refreshToken, role, mustChangePassword, login, logout, changePassword]);

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
