import React, { useActionState, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import TurnstileWidget from './TurnstileWidget.jsx';

/**
 * Full-page login form rendered when the user has no valid session.
 */
export default function LoginPage({ onRegister = null }) {
  const { login } = useAuth();
  const [showPassword, setShowPassword] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);

  const [error, submitAction, isPending] = useActionState(async (_prev, formData) => {
    const token = formData.get('turnstile-token');
    if (!token) return 'Please complete the verification challenge.';
    try {
      await login(formData.get('username'), formData.get('password'), token);
      return '';
    } catch (err) {
      setTurnstileToken('');
      setTurnstileResetKey((k) => k + 1);
      return err?.response?.data?.error ?? 'Invalid username or password.';
    }
  }, '');

  return (
    <div className="min-h-screen bg-plex-bg flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-4xl font-extrabold tracking-tight text-plex-text flex items-center justify-center gap-3">
            <img src="/logo.png" alt="" className="h-10 w-10" />
            PhotoCast
          </h1>
          <p className="text-base text-plex-text-secondary mt-2">AI Forecasts for Fiery Skies & Golden Light</p>
        </div>

        <form
          action={submitAction}
          className="card flex flex-col gap-4"
        >
          <p className="text-sm font-semibold text-plex-text">Sign in</p>

          <div>
            <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="login-username">
              Username
            </label>
            <input
              id="login-username"
              name="username"
              type="text"
              data-testid="login-username"
              autoComplete="username"
              className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
              disabled={isPending}
              required
            />
          </div>

          <div>
            <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="login-password">
              Password
            </label>
            <div className="relative">
              <input
                id="login-password"
                name="password"
                type={showPassword ? 'text' : 'password'}
                data-testid="login-password"
                autoComplete="current-password"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                disabled={isPending}
                required
              />
              <button
                type="button"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                onClick={() => setShowPassword((v) => !v)}
                className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
              >
                {showPassword ? (
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                    <path d="M3.28 2.22a.75.75 0 00-1.06 1.06l14.5 14.5a.75.75 0 101.06-1.06L3.28 2.22z" />
                  </svg>
                ) : (
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                  </svg>
                )}
              </button>
            </div>
          </div>

          <input type="hidden" name="turnstile-token" value={turnstileToken} />
          <TurnstileWidget
            key={turnstileResetKey}
            onVerify={(token) => setTurnstileToken(token)}
            onExpire={() => setTurnstileToken('')}
          />

          {error && (
            <p className="text-xs text-red-400" role="alert" data-testid="login-error">
              {error}
            </p>
          )}

          <button
            type="submit"
            data-testid="login-submit"
            className="btn-primary"
            disabled={isPending || !turnstileToken}
          >
            {isPending ? 'Signing in…' : 'Sign in'}
          </button>

          {onRegister && (
            <div className="text-center">
              <button
                type="button"
                className="text-xs text-plex-text-muted hover:text-plex-text"
                onClick={onRegister}
                data-testid="login-register-link"
              >
                Don&apos;t have an account? Create one
              </button>
            </div>
          )}
        </form>
      </div>
    </div>
  );
}

LoginPage.propTypes = {
  onRegister: PropTypes.func,
};
