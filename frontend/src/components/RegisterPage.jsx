import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import { register, resendVerification, verifyEmail, setPasswordForNewUser } from '../api/authApi.js';

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
 * Privacy policy modal overlay.
 */
function PrivacyModal({ onClose }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 px-4">
      <div className="w-full max-w-lg max-h-[80vh] overflow-y-auto bg-plex-surface border border-plex-border rounded-lg shadow-xl">
        <div className="flex items-center justify-between border-b border-plex-border px-6 py-4">
          <h2 className="text-lg font-semibold text-plex-text">Privacy Policy</h2>
          <button
            onClick={onClose}
            className="text-plex-text-secondary hover:text-plex-text text-xl leading-none"
            aria-label="Close"
            data-testid="privacy-close"
          >
            &times;
          </button>
        </div>
        <div className="px-6 py-4 text-sm text-plex-text-secondary leading-relaxed space-y-4">
          <section>
            <h3 className="text-plex-text font-medium mb-1">Data We Collect</h3>
            <p>We collect the email address and username you provide during registration.</p>
          </section>
          <section>
            <h3 className="text-plex-text font-medium mb-1">How We Use Your Data</h3>
            <p>Your data is used solely to provide the PhotoCast service: generating forecasts, sending notifications, and personalising your experience. We do not sell or share your data with third parties.</p>
          </section>
          <section>
            <h3 className="text-plex-text font-medium mb-1">Your Rights (GDPR)</h3>
            <p>You have the right to access, correct, or delete your personal data at any time. You may also request a copy of all data we hold about you. Contact us to exercise these rights.</p>
          </section>
          <section>
            <h3 className="text-plex-text font-medium mb-1">Contact Us</h3>
            <p>For any privacy-related questions, please email privacy@photocast.online.</p>
          </section>
        </div>
      </div>
    </div>
  );
}

PrivacyModal.propTypes = {
  onClose: PropTypes.func.isRequired,
};

/**
 * Multi-step self-registration component with internal state machine.
 */
export default function RegisterPage({ verifyToken, onBackToLogin }) {
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
  const [showPrivacy, setShowPrivacy] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  const turnstileRef = useRef(null);
  const turnstileWidgetId = useRef(null);

  // Render Turnstile widget when on REGISTER step.
  // The script loads async/defer, so poll until window.turnstile is available.
  useEffect(() => {
    if (step !== STEPS.REGISTER || !turnstileRef.current) return;

    let cancelled = false;

    function renderWidget() {
      if (cancelled || !turnstileRef.current) return;
      if (turnstileWidgetId.current != null) {
        window.turnstile.remove(turnstileWidgetId.current);
      }
      turnstileWidgetId.current = window.turnstile.render(turnstileRef.current, {
        sitekey: '0x4AAAAAABb1234MKu3B4bPj',
        theme: 'dark',
        callback: (token) => setTurnstileToken(token),
        'expired-callback': () => setTurnstileToken(''),
        'error-callback': () => setTurnstileToken(''),
      });
    }

    if (typeof window.turnstile !== 'undefined') {
      renderWidget();
    } else {
      // Poll every 200ms for up to 10s until the script loads
      let elapsed = 0;
      const interval = setInterval(() => {
        elapsed += 200;
        if (typeof window.turnstile !== 'undefined') {
          clearInterval(interval);
          renderWidget();
        } else if (elapsed >= 10000) {
          clearInterval(interval);
        }
      }, 200);
      // Clean up interval on unmount
      const cleanup = () => clearInterval(interval);
      return () => {
        cancelled = true;
        cleanup();
        if (turnstileWidgetId.current != null && window.turnstile) {
          window.turnstile.remove(turnstileWidgetId.current);
          turnstileWidgetId.current = null;
        }
      };
    }

    return () => {
      cancelled = true;
      if (turnstileWidgetId.current != null && window.turnstile) {
        window.turnstile.remove(turnstileWidgetId.current);
        turnstileWidgetId.current = null;
      }
    };
  }, [step]);

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
      await register(username, email, turnstileToken);
      setStep(STEPS.CHECK_EMAIL);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Registration failed. Please try again.');
      // Reset Turnstile on failure so user can retry
      setTurnstileToken('');
      if (turnstileWidgetId.current != null && window.turnstile) {
        window.turnstile.reset(turnstileWidgetId.current);
      }
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
    if (!allPassed) return;
    setError('');
    setLoading(true);
    try {
      const data = await setPasswordForNewUser(userId, password);
      completeRegistration(data);
      setStep(STEPS.SUCCESS);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to set password. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  // Auto-reload after success
  useEffect(() => {
    if (step === STEPS.SUCCESS) {
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
          <p className="text-base text-plex-text-secondary mt-2">AI Forecasts for Fiery Skies & Golden Light</p>
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

            <div ref={turnstileRef} data-testid="turnstile-widget" className="flex justify-center" />

            {error && (
              <p className="text-xs text-red-400" role="alert" data-testid="reg-error">{error}</p>
            )}

            <button type="submit" data-testid="reg-submit" className="btn-primary" disabled={loading || !turnstileToken}>
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

            <div className="text-center">
              <button
                type="button"
                className="text-xs text-plex-text-muted hover:text-plex-text underline"
                onClick={() => setShowPrivacy(true)}
                data-testid="reg-privacy-link"
              >
                Privacy Policy
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

            {error && (
              <p className="text-xs text-red-400" role="alert" data-testid="sp-error">{error}</p>
            )}

            <button
              type="submit"
              data-testid="sp-submit"
              className="btn-primary"
              disabled={loading || !allPassed}
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

        {showPrivacy && <PrivacyModal onClose={() => setShowPrivacy(false)} />}
      </div>
    </div>
  );
}

RegisterPage.propTypes = {
  verifyToken: PropTypes.string,
  onBackToLogin: PropTypes.func.isRequired,
};

RegisterPage.defaultProps = {
  verifyToken: null,
};
