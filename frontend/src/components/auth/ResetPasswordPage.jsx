import React, { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../../context/AuthContext.jsx';
import { resetPassword } from '../../api/authApi.js';
import AuthShell from './AuthShell.jsx';
import AuthHeading from './AuthHeading.jsx';
import PasswordInput from './PasswordInput.jsx';
import PasswordChecklist from './PasswordChecklist.jsx';

/**
 * The three sentences the server answers a dead reset link with (plan §2 #12). Only these offer
 * "Request a new link": any other 400 (a complexity failure) is the password's fault, not the
 * link's, and a new link would not help.
 */
const LINK_ERRORS = [
  'This reset link is invalid',
  'This reset link has already been used',
  'This reset link has expired',
];

/**
 * The redeeming half of the password reset, mounted by {@code AuthGate} for {@code /reset#token=}
 * (or the older {@code /reset?token=}), signed in or not. Nothing is requested until the reader
 * submits: the page does not look the token up, so opening the link (or a mail scanner prefetching it) cannot spend it.
 *
 * <p>On success the server answers with a full session, so the reader is signed in on this device
 * ({@code signInWithTokens}); the page then replaces the URL with {@code /} itself and hands
 * control back through {@code onDone}. It owns that cleanup, rather than the gate's effect, because
 * the gate's effect belongs to the verification token alone (plan §2 #1).
 */
export default function ResetPasswordPage({ token, onDone, onRequestNewLink }) {
  const { signInWithTokens } = useAuth();
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);

  const checks = [
    { label: 'At least 8 characters', ok: password.length >= 8 },
    { label: 'One uppercase letter', ok: /[A-Z]/.test(password) },
    { label: 'One lowercase letter', ok: /[a-z]/.test(password) },
    { label: 'One number', ok: /[0-9]/.test(password) },
    { label: 'One special character', ok: /[^A-Za-z0-9]/.test(password) },
    { label: 'Passwords match', ok: confirmPassword.length > 0 && password === confirmPassword },
  ];
  const allPassed = checks.every((c) => c.ok);
  const linkDead = LINK_ERRORS.includes(error);
  const newLinkRef = useRef(null);

  // A dead link replaces the form, unmounting the submit button that held focus; hand it to the
  // one thing the reader can do next.
  useEffect(() => {
    if (linkDead) newLinkRef.current?.focus();
  }, [linkDead]);

  async function handleSubmit(event) {
    event.preventDefault();
    if (!allPassed || pending) return;
    setError('');
    setPending(true);
    try {
      const data = await resetPassword(token, password);
      signInWithTokens(data);
      window.history.replaceState({}, '', '/');
      onDone();
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to set password. Please try again.');
      setPending(false);
    }
  }

  function handleRequestNewLink() {
    window.history.replaceState({}, '', '/');
    onRequestNewLink();
  }

  return (
    <AuthShell>
      <form onSubmit={handleSubmit} className="card flex flex-col gap-4" data-testid="reset-form">
        <div>
          <AuthHeading>Set a new password</AuthHeading>
          <p className="text-xs text-plex-text-secondary mt-1">
            Choose a new password for your account.
          </p>
        </div>

        {linkDead ? (
          <>
            <p className="text-xs text-plex-error" role="alert" data-testid="reset-error">
              {error}
            </p>
            <button
              ref={newLinkRef}
              type="button"
              className="btn-primary"
              onClick={handleRequestNewLink}
              data-testid="reset-new-link"
            >
              Request a new link
            </button>
          </>
        ) : (
          <>
            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reset-new">
                New password
              </label>
              <PasswordInput
                id="reset-new"
                testId="reset-new-password"
                autoComplete="new-password"
                eyeLabel="new password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={pending}
              />
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reset-confirm">
                Confirm new password
              </label>
              <PasswordInput
                id="reset-confirm"
                testId="reset-confirm-password"
                autoComplete="new-password"
                eyeLabel="password confirmation"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={pending}
              />
            </div>

            <PasswordChecklist checks={checks} testId="reset-checklist" />

            {error && (
              <p className="text-xs text-plex-error" role="alert" data-testid="reset-error">
                {error}
              </p>
            )}

            <button
              type="submit"
              data-testid="reset-submit"
              className="btn-primary"
              disabled={pending || !allPassed}
            >
              {pending ? 'Saving…' : 'Set new password'}
            </button>
          </>
        )}
      </form>
    </AuthShell>
  );
}

ResetPasswordPage.propTypes = {
  token: PropTypes.string.isRequired,
  onDone: PropTypes.func.isRequired,
  onRequestNewLink: PropTypes.func.isRequired,
};
