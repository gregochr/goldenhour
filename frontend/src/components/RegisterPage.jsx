import React, { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import { register, resendVerification, verifyEmail, setPasswordForNewUser } from '../api/authApi.js';
import TurnstileWidget from './TurnstileWidget.jsx';
const STEPS = { REGISTER: 'REGISTER', CHECK_EMAIL: 'CHECK_EMAIL', VERIFY: 'VERIFY', SET_PASSWORD: 'SET_PASSWORD', SUCCESS: 'SUCCESS' };

/** Checks a single complexity rule and returns a styled list item. */
function CheckItem({ ok, label }) {
  return (
    <li className={`flex items-center gap-1.5 text-xs ${ok ? 'text-green-400' : 'text-plex-text-muted'}`}>
      <span className="w-3 text-center">{ok ? '\u2713' : '\u2717'}</span>
      {label}
    </li>
  );
}

CheckItem.propTypes = {
  ok: PropTypes.bool.isRequired,
  label: PropTypes.string.isRequired,
};

/**
 * Multi-step self-registration component with internal state machine.
 */
export default function RegisterPage({ verifyToken = null, onBackToLogin }) {
  const { completeRegistration } = useAuth();
  const [step, setStep] = useState(verifyToken ? STEPS.VERIFY : STEPS.REGISTER);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [confirmEmail, setConfirmEmail] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [userId, setUserId] = useState(null);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [resendCooldown, setResendCooldown] = useState(0);
  const [marketingOptIn, setMarketingOptIn] = useState(true);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);
  const [spTurnstileToken, setSpTurnstileToken] = useState('');
  const [spTurnstileResetKey, setSpTurnstileResetKey] = useState(0);

  // Resend cooldown timer
  useEffect(() => {
    if (resendCooldown <= 0) return;
    const timer = setTimeout(() => setResendCooldown((c) => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendCooldown]);

  // Auto-verify when verifyToken is provided
  const handleVerify = useCallback(async (token) => {
    setStep(STEPS.VERIFY);
    setLoading(true);
    setError('');
    try {
      const data = await verifyEmail(token);
      setUserId(data.userId);
      setStep(STEPS.SET_PASSWORD);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Verification failed. The link may be invalid or expired.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (verifyToken) {
      handleVerify(verifyToken);
    }
  }, [verifyToken, handleVerify]);

  const usernamePattern = /^[a-zA-Z0-9_-]{3,30}$/;
  const usernameValid = usernamePattern.test(username);
  const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const emailValid = emailPattern.test(email);
  const emailsMatch = confirmEmail.length > 0 && email === confirmEmail;

  async function handleRegister(event) {
    event.preventDefault();
    if (!usernameValid) {
      setError('Username must be 3-30 characters: letters, numbers, hyphens, or underscores.');
      return;
    }
    if (!emailValid) {
      setError('Please enter a valid email address.');
      return;
    }
    if (email !== confirmEmail) {
      setError('Email addresses do not match.');
      return;
    }
    if (!turnstileToken) {
      setError('Please complete the verification challenge.');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await register(username, email, turnstileToken, marketingOptIn, termsAccepted);
      setStep(STEPS.CHECK_EMAIL);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Registration failed. Please try again.');
      setTurnstileToken('');
      setTurnstileResetKey((k) => k + 1);
    } finally {
      setLoading(false);
    }
  }

  async function handleResend() {
    setError('');
    try {
      await resendVerification(email);
      setResendCooldown(60);
    } catch (err) {
      if (err?.response?.status === 429) {
        setResendCooldown(60);
        setError('Too many requests. Please wait before trying again.');
      } else {
        setError(err?.response?.data?.error ?? 'Failed to resend. Please try again.');
      }
    }
  }

  const checks = [
    { label: 'At least 8 characters', ok: password.length >= 8 },
    { label: 'One uppercase letter', ok: /[A-Z]/.test(password) },
    { label: 'One lowercase letter', ok: /[a-z]/.test(password) },
    { label: 'One number', ok: /[0-9]/.test(password) },
    { label: 'One special character', ok: /[^A-Za-z0-9]/.test(password) },
    { label: 'Passwords match', ok: confirmPassword.length > 0 && password === confirmPassword },
  ];
  const allPassed = checks.every((c) => c.ok);

  async function handleSetPassword(event) {
    event.preventDefault();
    if (!allPassed || !spTurnstileToken) return;
    setError('');
    setLoading(true);
    try {
      const data = await setPasswordForNewUser(userId, password, spTurnstileToken);
      completeRegistration(data);
      setStep(STEPS.SUCCESS);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to set password. Please try again.');
      setSpTurnstileToken('');
      setSpTurnstileResetKey((k) => k + 1);
    } finally {
      setLoading(false);
    }
  }

  // Clear verification token from URL and auto-reload after success
  useEffect(() => {
    if (step === STEPS.SUCCESS) {
      window.history.replaceState({}, '', window.location.pathname);
      const timer = setTimeout(() => window.location.reload(), 2000);
      return () => clearTimeout(timer);
    }
  }, [step]);

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

        {/* REGISTER step */}
        {step === STEPS.REGISTER && (
          <form onSubmit={handleRegister} className="card flex flex-col gap-4" data-testid="register-form">
            <p className="text-sm font-semibold text-plex-text">Create an account</p>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reg-username">Username</label>
              <input
                id="reg-username"
                type="text"
                data-testid="reg-username"
                autoComplete="username"
                className={`w-full bg-plex-surface-light border rounded px-3 py-2 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold ${
                  username.length > 0 && !usernameValid ? 'border-red-700' : 'border-plex-border'
                }`}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={loading}
                required
              />
              <p className={`text-xs mt-1 ${username.length > 0 && !usernameValid ? 'text-red-400' : 'text-plex-text-muted'}`}>
                3-30 characters, letters, numbers, hyphens, or underscores
              </p>
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reg-email">Email</label>
              <input
                id="reg-email"
                type="email"
                data-testid="reg-email"
                autoComplete="email"
                className={`w-full bg-plex-surface-light border rounded px-3 py-2 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold ${
                  email.length > 0 && !emailValid ? 'border-red-700' : 'border-plex-border'
                }`}
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
                required
              />
              {email.length > 0 && !emailValid && (
                <p className="text-xs text-red-400 mt-1">Please enter a valid email address</p>
              )}
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reg-confirm-email">Confirm email</label>
              <input
                id="reg-confirm-email"
                type="email"
                data-testid="reg-confirm-email"
                autoComplete="email"
                className={`w-full bg-plex-surface-light border rounded px-3 py-2 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold ${
                  confirmEmail.length > 0 && !emailsMatch ? 'border-red-700' : 'border-plex-border'
                }`}
                value={confirmEmail}
                onChange={(e) => setConfirmEmail(e.target.value)}
                disabled={loading}
                required
              />
              {confirmEmail.length > 0 && !emailsMatch && (
                <p className="text-xs text-red-400 mt-1">Email addresses do not match</p>
              )}
            </div>

            <label className="flex items-start gap-2 cursor-pointer text-xs text-plex-text-secondary">
              <input
                type="checkbox"
                data-testid="reg-terms-accepted"
                checked={termsAccepted}
                onChange={(e) => setTermsAccepted(e.target.checked)}
                disabled={loading}
                className="mt-0.5 accent-plex-gold"
              />
              <span>
                I agree to the{' '}
                <a href="https://photocast.online/terms.html" target="_blank" rel="noopener noreferrer" className="text-plex-gold hover:underline">
                  Terms &amp; Conditions
                </a>{' '}
                and{' '}
                <a href="https://photocast.online/privacy.html" target="_blank" rel="noopener noreferrer" className="text-plex-gold hover:underline">
                  Privacy Policy
                </a>
              </span>
            </label>

            <label className="flex items-start gap-2 cursor-pointer text-xs text-plex-text-secondary">
              <input
                type="checkbox"
                data-testid="reg-marketing-opt-in"
                checked={marketingOptIn}
                onChange={(e) => setMarketingOptIn(e.target.checked)}
                disabled={loading}
                className="mt-0.5 accent-plex-gold"
              />
              <span>Send me occasional emails about new features and photography tips</span>
            </label>

            <TurnstileWidget
              key={`reg-${turnstileResetKey}`}
              onVerify={(token) => setTurnstileToken(token)}
              onExpire={() => setTurnstileToken('')}
            />

            {error && (
              <p className="text-xs text-red-400" role="alert" data-testid="reg-error">{error}</p>
            )}

            <button type="submit" data-testid="reg-submit" className="btn-primary" disabled={loading || !turnstileToken || !termsAccepted}>
              {loading ? 'Creating account...' : 'Create account'}
            </button>

            <div className="text-center">
              <button
                type="button"
                className="text-xs text-plex-text-muted hover:text-plex-text"
                onClick={onBackToLogin}
                data-testid="reg-back-to-login"
              >
                Already have an account? Sign in
              </button>
            </div>

          </form>
        )}

        {/* CHECK_EMAIL step */}
        {step === STEPS.CHECK_EMAIL && (
          <div className="card flex flex-col gap-4 text-center" data-testid="check-email">
            <p className="text-sm font-semibold text-plex-text">Check your email</p>
            <p className="text-sm text-plex-text-secondary">
              We&apos;ve sent a verification link to <strong className="text-plex-text">{email}</strong>
            </p>
            <p className="text-xs text-plex-text-muted">
              Click the link in the email to verify your address and complete registration.
            </p>

            {error && (
              <p className="text-xs text-red-400" role="alert" data-testid="resend-error">{error}</p>
            )}

            <button
              type="button"
              className="btn-secondary text-xs"
              onClick={handleResend}
              disabled={resendCooldown > 0}
              data-testid="resend-button"
            >
              {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : 'Resend verification email'}
            </button>

            <button
              type="button"
              className="text-xs text-plex-text-muted hover:text-plex-text"
              onClick={onBackToLogin}
            >
              Back to sign in
            </button>
          </div>
        )}

        {/* VERIFY step (loading spinner) */}
        {step === STEPS.VERIFY && (
          <div className="card flex flex-col gap-4 text-center" data-testid="verify-step">
            {loading && (
              <p className="text-sm text-plex-text-secondary animate-pulse">Verifying your email...</p>
            )}
            {!loading && error && (
              <>
                <p className="text-sm font-semibold text-red-400">Verification failed</p>
                <p className="text-xs text-plex-text-secondary" data-testid="verify-error">{error}</p>
                <button
                  type="button"
                  className="text-xs text-plex-text-muted hover:text-plex-text underline"
                  onClick={onBackToLogin}
                >
                  Request a new verification email
                </button>
              </>
            )}
          </div>
        )}

        {/* SET_PASSWORD step */}
        {step === STEPS.SET_PASSWORD && (
          <form onSubmit={handleSetPassword} className="card flex flex-col gap-4" data-testid="set-password-form">
            <div>
              <p className="text-sm font-semibold text-plex-text">Set your password</p>
              <p className="text-xs text-plex-text-secondary mt-1">
                Your email has been verified. Choose a password to complete registration.
              </p>
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="sp-password">Password</label>
              <div className="relative">
                <input
                  id="sp-password"
                  type={showPassword ? 'text' : 'password'}
                  data-testid="sp-password"
                  autoComplete="new-password"
                  className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  required
                />
                <button
                  type="button"
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                  </svg>
                </button>
              </div>
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="sp-confirm">Confirm password</label>
              <div className="relative">
                <input
                  id="sp-confirm"
                  type={showConfirm ? 'text' : 'password'}
                  data-testid="sp-confirm"
                  autoComplete="new-password"
                  className="w-full bg-plex-surface-light border border-plex-border rounded px-3 py-2 pr-10 text-sm text-plex-text placeholder-plex-text-muted focus:outline-none focus:ring-1 focus:ring-plex-gold"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  disabled={loading}
                  required
                />
                <button
                  type="button"
                  aria-label={showConfirm ? 'Hide password' : 'Show password'}
                  onClick={() => setShowConfirm((v) => !v)}
                  className="absolute inset-y-0 right-0 flex items-center px-3 text-plex-text-secondary hover:text-plex-text"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                    <path d="M10 3C5 3 1.73 7.11 1.05 8.87a1 1 0 000 .26C1.73 10.89 5 15 10 15s8.27-4.11 8.95-5.87a1 1 0 000-.26C18.27 7.11 15 3 10 3zm0 10a4 4 0 110-8 4 4 0 010 8zm0-6a2 2 0 100 4 2 2 0 000-4z" />
                  </svg>
                </button>
              </div>
            </div>

            <ul className="flex flex-col gap-1 pl-1">
              {checks.map((c) => (
                <CheckItem key={c.label} ok={c.ok} label={c.label} />
              ))}
            </ul>

            <TurnstileWidget
              key={`sp-${spTurnstileResetKey}`}
              onVerify={(token) => setSpTurnstileToken(token)}
              onExpire={() => setSpTurnstileToken('')}
            />

            {error && (
              <p className="text-xs text-red-400" role="alert" data-testid="sp-error">{error}</p>
            )}

            <button
              type="submit"
              data-testid="sp-submit"
              className="btn-primary"
              disabled={loading || !allPassed || !spTurnstileToken}
            >
              {loading ? 'Setting up...' : 'Complete registration'}
            </button>
          </form>
        )}

        {/* SUCCESS step */}
        {step === STEPS.SUCCESS && (
          <div className="card flex flex-col gap-4 text-center" data-testid="success-step">
            <p className="text-sm font-semibold text-plex-text">Welcome to PhotoCast!</p>
            <p className="text-xs text-plex-text-secondary animate-pulse">Redirecting...</p>
          </div>
        )}

      </div>
    </div>
  );
}

RegisterPage.propTypes = {
  verifyToken: PropTypes.string,
  onBackToLogin: PropTypes.func.isRequired,
};
