import React, { useActionState, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import TurnstileWidget from './TurnstileWidget.jsx';
import AuthShell from './auth/AuthShell.jsx';
import AuthHeading from './auth/AuthHeading.jsx';
import PasswordInput from './auth/PasswordInput.jsx';

/**
 * Full-page login form rendered when the user has no valid session.
 */
export default function LoginPage({ onRegister = null }) {
  const { login, sessionExpired } = useAuth();
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);

  const [turnstileLoadFailed, setTurnstileLoadFailed] = useState(false);

  const [error, submitAction, isPending] = useActionState(async (_prev, formData) => {
    const token = formData.get('turnstile-token');
    if (!token && !turnstileLoadFailed) return 'Please complete the verification challenge.';
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
    <AuthShell>
      <form
        action={submitAction}
        className="card flex flex-col gap-4"
      >
        {sessionExpired && (
          <p className="text-sm text-plex-text-secondary" data-testid="session-expired-message">
            Your session expired. Please sign in again.
          </p>
        )}

        <AuthHeading>Sign in</AuthHeading>

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
            className="field"
            disabled={isPending}
            required
          />
        </div>

        <div>
          <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="login-password">
            Password
          </label>
          <PasswordInput
            id="login-password"
            name="password"
            testId="login-password"
            autoComplete="current-password"
            disabled={isPending}
          />
        </div>

        <input type="hidden" name="turnstile-token" value={turnstileToken} />
        {!turnstileLoadFailed && (
          <TurnstileWidget
            key={turnstileResetKey}
            onVerify={(token) => setTurnstileToken(token)}
            onExpire={() => setTurnstileToken('')}
            onLoadFail={() => setTurnstileLoadFailed(true)}
          />
        )}
        {turnstileLoadFailed && (
          <p className="text-xs text-plex-text-secondary" data-testid="turnstile-unavailable">
            Security check unavailable — we&apos;ll verify your sign-in another way.
          </p>
        )}

        {error && (
          <p className="text-xs text-plex-error" role="alert" data-testid="login-error">
            {error}
          </p>
        )}

        <button
          type="submit"
          data-testid="login-submit"
          className="btn-primary"
          disabled={isPending || (!turnstileToken && !turnstileLoadFailed)}
        >
          {isPending ? 'Signing in…' : 'Sign in'}
        </button>

        {onRegister && (
          <div className="text-center">
            <button
              type="button"
              className="text-xs text-plex-text-secondary hover:text-plex-text"
              onClick={onRegister}
              data-testid="login-register-link"
            >
              Don&apos;t have an account? Create one
            </button>
          </div>
        )}
      </form>
    </AuthShell>
  );
}

LoginPage.propTypes = {
  onRegister: PropTypes.func,
};
