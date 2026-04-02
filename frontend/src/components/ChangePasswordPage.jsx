import React, { useActionState, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import TurnstileWidget from './TurnstileWidget.jsx';

/** Checks a single complexity rule and returns a styled list item. */
function CheckItem({ ok, label }) {
  return (
    <li className={`flex items-center gap-1.5 text-xs ${ok ? 'text-green-400' : 'text-plex-text-muted'}`}>
      <span className="w-3 text-center">{ok ? '✓' : '✗'}</span>
      {label}
    </li>
  );
}

CheckItem.propTypes = {
  ok: PropTypes.bool.isRequired,
  label: PropTypes.string.isRequired,
};

/**
 * Full-page form shown when a user's {@code passwordChangeRequired} flag is set.
 * The user must set a new password meeting complexity rules before entering the app.
 */
export default function ChangePasswordPage() {
  const { changePassword, logout } = useAuth();
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);

  const checks = [
    { label: 'At least 8 characters', ok: newPassword.length >= 8 },
    { label: 'One uppercase letter', ok: /[A-Z]/.test(newPassword) },
    { label: 'One lowercase letter', ok: /[a-z]/.test(newPassword) },
    { label: 'One number', ok: /[0-9]/.test(newPassword) },
    { label: 'One special character', ok: /[^A-Za-z0-9]/.test(newPassword) },
    { label: 'Passwords match', ok: confirmPassword.length > 0 && newPassword === confirmPassword },
  ];

  const allPassed = checks.every((c) => c.ok);

  const [error, submitAction, isPending] = useActionState(async () => {
    if (!allPassed || !turnstileToken) return '';
    try {
      await changePassword(newPassword, turnstileToken);
      return '';
    } catch (err) {
      setTurnstileToken('');
      setTurnstileResetKey((k) => k + 1);
      return err?.response?.data?.error ?? 'Failed to change password. Please try again.';
    }
  }, '');

  const EyeOpen = (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
      <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
    </svg>
  );

  const EyeSlash = (
    <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
      <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
      <path d="M3.28 2.22a.75.75 0 00-1.06 1.06l14.5 14.5a.75.75 0 101.06-1.06L3.28 2.22z" />
    </svg>
  );

  return (
    <div className="min-h-screen bg-plex-bg flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <h1 className="text-4xl font-extrabold tracking-tight text-plex-text flex items-center justify-center gap-3">
            <img src="/logo.png" alt="" className="h-10 w-10" />
            PhotoCast
          </h1>
          <p className="text-base text-plex-text-secondary mt-2">AI sunrise, sunset, and aurora forecasting</p>
        </div>

        <form
          action={submitAction}
          className="card flex flex-col gap-4"
        >
          <div>
            <p className="text-sm font-semibold text-plex-text">Change your password</p>
            <p className="text-xs text-plex-text-secondary mt-1">
              You must set a new password before accessing the app.
            </p>
          </div>

          <div>
            <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="cp-new">
              New password
            </label>
            <div className="relative">
              <input
                id="cp-new"
                type={showNew ? 'text' : 'password'}
                data-testid="cp-new-password"
                autoComplete="new-password"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={isPending}
                required
              />
              <button
                type="button"
                aria-label={showNew ? 'Hide password' : 'Show password'}
                onClick={() => setShowNew((v) => !v)}
                className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
              >
                {showNew ? EyeSlash : EyeOpen}
              </button>
            </div>
          </div>

          <div>
            <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="cp-confirm">
              Confirm new password
            </label>
            <div className="relative">
              <input
                id="cp-confirm"
                type={showConfirm ? 'text' : 'password'}
                data-testid="cp-confirm-password"
                autoComplete="new-password"
                className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={isPending}
                required
              />
              <button
                type="button"
                aria-label={showConfirm ? 'Hide password' : 'Show password'}
                onClick={() => setShowConfirm((v) => !v)}
                className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
              >
                {showConfirm ? EyeSlash : EyeOpen}
              </button>
            </div>
          </div>

          <ul className="flex flex-col gap-1 pl-1">
            {checks.map((c) => (
              <CheckItem key={c.label} ok={c.ok} label={c.label} />
            ))}
          </ul>

          <TurnstileWidget
            key={turnstileResetKey}
            onVerify={(token) => setTurnstileToken(token)}
            onExpire={() => setTurnstileToken('')}
          />

          {error && (
            <p className="text-xs text-red-400" role="alert" data-testid="cp-error">
              {error}
            </p>
          )}

          <button
            type="submit"
            data-testid="cp-submit"
            className="btn-primary"
            disabled={isPending || !allPassed || !turnstileToken}
          >
            {isPending ? 'Saving…' : 'Set new password'}
          </button>

          <button
            type="button"
            className="text-xs text-plex-text-muted hover:text-plex-text text-center"
            onClick={logout}
          >
            Sign out
          </button>
        </form>
      </div>
    </div>
  );
}
