import React, { useActionState, useState } from 'react';
import { useAuth } from '../context/AuthContext.jsx';
import TurnstileWidget from './TurnstileWidget.jsx';
import AuthShell from './auth/AuthShell.jsx';
import AuthHeading from './auth/AuthHeading.jsx';
import PasswordInput from './auth/PasswordInput.jsx';
import PasswordChecklist from './auth/PasswordChecklist.jsx';

/**
 * Full-page form shown when a user's {@code passwordChangeRequired} flag is set.
 * The user must set a new password meeting complexity rules before entering the app.
 */
export default function ChangePasswordPage() {
  const { changePassword, logout } = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);
  // The Turnstile script never loaded. The server answers an empty token with a bounded fallback
  // (login-registration plan §2 #4), so the reader is not stranded behind a disabled button.
  const [turnstileLoadFailed, setTurnstileLoadFailed] = useState(false);

  const checks = [
    { label: 'At least 8 characters', ok: newPassword.length >= 8 },
    { label: 'One uppercase letter', ok: /[A-Z]/.test(newPassword) },
    { label: 'One lowercase letter', ok: /[a-z]/.test(newPassword) },
    { label: 'One number', ok: /[0-9]/.test(newPassword) },
    { label: 'One special character', ok: /[^A-Za-z0-9]/.test(newPassword) },
    { label: 'Passwords match', ok: confirmPassword.length > 0 && newPassword === confirmPassword },
  ];

  // The current password is not part of the new password's checklist: the server verifies it, and the
  // form only insists that something was typed.
  const allPassed = checks.every((c) => c.ok) && currentPassword.length > 0;

  const [error, submitAction, isPending] = useActionState(async () => {
    if (!allPassed || (!turnstileToken && !turnstileLoadFailed)) return '';
    try {
      await changePassword(currentPassword, newPassword, turnstileToken);
      return '';
    } catch (err) {
      setTurnstileToken('');
      setTurnstileResetKey((k) => k + 1);
      return err?.response?.data?.error ?? 'Failed to change password. Please try again.';
    }
  }, '');

  return (
    <AuthShell>
      <form
        action={submitAction}
        className="card flex flex-col gap-4"
      >
        <div>
          <AuthHeading>Change your password</AuthHeading>
          <p className="text-xs text-plex-text-secondary mt-1">
            You must set a new password before accessing the app.
          </p>
          <p className="text-xs text-plex-text-secondary mt-1" data-testid="cp-current-hint">
            Enter the temporary password you signed in with, then choose a new one.
          </p>
        </div>

        <div>
          <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="cp-current">
            Current password
          </label>
          <PasswordInput
            id="cp-current"
            testId="cp-current-password"
            autoComplete="current-password"
            eyeLabel="current password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            disabled={isPending}
          />
        </div>

        <div>
          <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="cp-new">
            New password
          </label>
          <PasswordInput
            id="cp-new"
            testId="cp-new-password"
            autoComplete="new-password"
            eyeLabel="new password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            disabled={isPending}
          />
        </div>

        <div>
          <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="cp-confirm">
            Confirm new password
          </label>
          <PasswordInput
            id="cp-confirm"
            testId="cp-confirm-password"
            autoComplete="new-password"
            eyeLabel="password confirmation"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            disabled={isPending}
          />
        </div>

        <PasswordChecklist checks={checks} testId="cp-checklist" />

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
          <p className="text-xs text-plex-error" role="alert" data-testid="cp-error">
            {error}
          </p>
        )}

        <button
          type="submit"
          data-testid="cp-submit"
          className="btn-primary"
          disabled={isPending || !allPassed || (!turnstileToken && !turnstileLoadFailed)}
        >
          {isPending ? 'Saving…' : 'Set new password'}
        </button>

        <button
          type="button"
          className="text-xs text-plex-text-secondary hover:text-plex-text text-center"
          onClick={logout}
        >
          Sign out
        </button>
      </form>
    </AuthShell>
  );
}
