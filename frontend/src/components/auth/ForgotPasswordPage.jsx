import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { requestPasswordReset } from '../../api/authApi.js';
import TurnstileWidget from '../TurnstileWidget.jsx';
import AuthShell from './AuthShell.jsx';
import AuthHeading from './AuthHeading.jsx';

/** The one sentence the server answers for every valid address, known or not (plan §2 #12). */
const DONE_MESSAGE = "If that email has an account, we've sent a reset link.";

/**
 * The request half of the password reset: an email field, the Turnstile check (with the bounded
 * server-side fallback when the script cannot load, as on sign-in) and the done state.
 *
 * <p>The done state is shown for every 200, and says the same thing whether or not the address has
 * an account — the page must not be a way to find out which addresses do. It prints its own
 * constant rather than the response's {@code message}, so a reworded server sentence cannot make
 * the two cases differ on screen. A 400 (an invalid address, a failed challenge) and a 429 show the
 * server's sentence, and the challenge is re-armed.
 */
export default function ForgotPasswordPage({ onBackToLogin }) {
  const [email, setEmail] = useState('');
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);
  const [turnstileLoadFailed, setTurnstileLoadFailed] = useState(false);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [done, setDone] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    if (pending || (!turnstileToken && !turnstileLoadFailed)) return;
    setError('');
    setPending(true);
    try {
      await requestPasswordReset(email.trim(), turnstileToken);
      setDone(true);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Something went wrong. Please try again.');
      setTurnstileToken('');
      setTurnstileResetKey((k) => k + 1);
    } finally {
      setPending(false);
    }
  }

  if (done) {
    return (
      <AuthShell>
        <div className="card flex flex-col gap-4" data-testid="forgot-done">
          <AuthHeading>Reset your password</AuthHeading>
          <p className="text-sm text-plex-text-secondary" role="status" data-testid="forgot-done-message">
            {DONE_MESSAGE}
          </p>
          <button
            type="button"
            className="btn-primary"
            onClick={onBackToLogin}
            data-testid="forgot-back"
          >
            Back to sign in
          </button>
        </div>
      </AuthShell>
    );
  }

  return (
    <AuthShell>
      <form onSubmit={handleSubmit} className="card flex flex-col gap-4" data-testid="forgot-form">
        <div>
          <AuthHeading>Reset your password</AuthHeading>
          <p className="text-xs text-plex-text-secondary mt-1">
            Enter your email and we&apos;ll send you a link.
          </p>
        </div>

        <div>
          <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="forgot-email">
            Email
          </label>
          <input
            id="forgot-email"
            type="email"
            data-testid="forgot-email"
            autoComplete="email"
            className="field"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={pending}
            required
          />
        </div>

        {!turnstileLoadFailed && (
          <TurnstileWidget
            key={turnstileResetKey}
            onVerify={(token) => setTurnstileToken(token)}
            onExpire={() => setTurnstileToken('')}
            onLoadFail={() => setTurnstileLoadFailed(true)}
          />
        )}
        {turnstileLoadFailed && (
          <p className="text-xs text-plex-text-secondary" role="status" data-testid="turnstile-unavailable">
            Security check unavailable — we&apos;ll verify your sign-in another way.
          </p>
        )}

        {error && (
          <p className="text-xs text-plex-error" role="alert" data-testid="forgot-error">
            {error}
          </p>
        )}

        <button
          type="submit"
          data-testid="forgot-submit"
          className="btn-primary"
          disabled={pending || (!turnstileToken && !turnstileLoadFailed)}
        >
          {pending ? 'Sending…' : 'Send reset link'}
        </button>

        <button
          type="button"
          className="text-xs text-plex-text-secondary hover:text-plex-text text-center"
          onClick={onBackToLogin}
          data-testid="forgot-back"
        >
          Back to sign in
        </button>
      </form>
    </AuthShell>
  );
}

ForgotPasswordPage.propTypes = {
  onBackToLogin: PropTypes.func.isRequired,
};
