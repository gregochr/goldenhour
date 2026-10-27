import React, { useCallback, useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { useAuth } from '../context/AuthContext.jsx';
import { register, resendVerification, verifyEmail, setPasswordForNewUser, submitWaitlist } from '../api/authApi.js';
import TurnstileWidget from './TurnstileWidget.jsx';
import AuthShell from './auth/AuthShell.jsx';
import AuthHeading from './auth/AuthHeading.jsx';
import PasswordInput from './auth/PasswordInput.jsx';
import PasswordChecklist from './auth/PasswordChecklist.jsx';
import PostcodeCapture from './auth/PostcodeCapture.jsx';
import { markPostcodePromptDismissed } from '../api/settingsApi.js';
import { getSubRegions } from '../api/subRegionApi.js';
import SubRegionStepCard from './subregions/SubRegionStepCard.jsx';
import { persistSubRegions } from '../utils/subRegionSave.js';
import { pickerApplies, SAVE_FAILED } from '../utils/subRegionCopy.js';
import { apiErrorMessage } from '../utils/apiError.js';
const STEPS = { REGISTER: 'REGISTER', CHECK_EMAIL: 'CHECK_EMAIL', VERIFY: 'VERIFY', SET_PASSWORD: 'SET_PASSWORD', HOME: 'HOME', SUB_REGIONS: 'SUB_REGIONS', SUCCESS: 'SUCCESS', WAITLIST: 'WAITLIST' };

/**
 * The steps a reader counts through when the sub-region step is shown, in order: the registration form,
 * the emailed link, the password, home, sub-regions, the welcome. The step skips itself (flag off, or a
 * Pro or Admin default role), and when it is shown it is always the fifth of these six, so the kicker is
 * read off this list rather than typed.
 */
const SUB_REGION_FLOW = ['REGISTER', 'VERIFY_EMAIL', 'SET_PASSWORD', 'HOME', 'SUB_REGIONS', 'WELCOME'];
const SUB_REGIONS_KICKER = `Step ${SUB_REGION_FLOW.indexOf('SUB_REGIONS') + 1} of ${SUB_REGION_FLOW.length}`;

/**
 * Multi-step self-registration component with internal state machine.
 *
 * <p>The password step signs the new account in ({@code completeRegistration}) and the flow carries
 * on with a token in place: {@code HOME} (the postcode, through {@code PostcodeCapture}), then the
 * {@code SUB_REGIONS} when enforcement is on and the account is Lite (sub-regions-plan §5.2: it skips
 * itself otherwise, so with the flag off the flow is exactly what it was), then the
 * Welcome step, whose two seconds end in {@code finishRegistration()} — no page reload. The page
 * stays mounted through those steps only because {@code AuthGate} reads {@code registrationPending}
 * (login-registration plan §2 #2); {@code RegisterPage} rendered alone cannot show that, which is why
 * the gate has its own test.
 */
export default function RegisterPage({ verifyToken = null, inviteToken = null, onBackToLogin }) {
  const { completeRegistration, finishRegistration, suppressPostcodePrompt } = useAuth();
  const [step, setStep] = useState(verifyToken ? STEPS.VERIFY : STEPS.REGISTER);
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  // The ROTATED setup token returned by verify, not the emailed link and not the user id, identifies the
  // account when the password is set. React state only: never storage, never the URL.
  const [setupToken, setSetupToken] = useState(null);
  // True once a verify has succeeded. StrictMode fires the verify effect twice in dev (a double click can
  // in prod): the second call loses the server's compare-and-set and fails with "already used", which
  // must not overwrite the password step the first one opened.
  const verifiedRef = useRef(false);
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [resendCooldown, setResendCooldown] = useState(0);
  // Marketing consent is an active opt-in (UK PECR/GDPR): unticked until the reader ticks it.
  const [marketingOptIn, setMarketingOptIn] = useState(false);
  const [termsAccepted, setTermsAccepted] = useState(false);
  const [turnstileToken, setTurnstileToken] = useState('');
  const [turnstileResetKey, setTurnstileResetKey] = useState(0);
  // The Turnstile script never loaded (blocked, or the domain is not whitelisted). Unlike sign-in,
  // registration has NO server-side fallback (login-registration plan §4 #1): the form says so and
  // the submit stays disabled.
  const [turnstileLoadFailed, setTurnstileLoadFailed] = useState(false);
  const [waitlistEmail, setWaitlistEmail] = useState('');
  const [waitlistSubmitted, setWaitlistSubmitted] = useState(false);
  // The waitlist step has its OWN Turnstile: the register step's token is spent by the failed register
  // call, and the two widgets are never mounted together. Strict like registration: no token, no submit.
  const [waitlistTurnstileToken, setWaitlistTurnstileToken] = useState('');
  const [waitlistTurnstileResetKey, setWaitlistTurnstileResetKey] = useState(0);
  const [waitlistTurnstileLoadFailed, setWaitlistTurnstileLoadFailed] = useState(false);
  // The SUB_REGIONS step: the GET's answer, the ids chosen so far (client state; nothing is saved until
  // Continue), and whether the reader has edited them, which stops a returning Back from re-suggesting.
  const [subState, setSubState] = useState(null);
  const [subSelected, setSubSelected] = useState([]);
  const subEditedRef = useRef(false);
  const [subError, setSubError] = useState('');
  const [subBusy, setSubBusy] = useState(false);

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
      verifiedRef.current = true;
      setSetupToken(data?.setupToken ?? null);
      setError('');
      setStep(STEPS.SET_PASSWORD);
    } catch (err) {
      if (verifiedRef.current) return; // a late failure of a duplicate verify, after SET_PASSWORD opened
      setError(err?.response?.data?.error ?? 'Verification failed. The link may be invalid or expired.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (verifyToken) {
      (async () => {
        await handleVerify(verifyToken);
      })();
    }
  }, [verifyToken, handleVerify]);

  const usernamePattern = /^[a-zA-Z0-9_-]{3,30}$/;
  const usernameValid = usernamePattern.test(username);
  const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  const emailValid = emailPattern.test(email);

  async function handleRegister(event) {
    event.preventDefault();
    if (!emailValid) {
      setError('Please enter a valid email address.');
      return;
    }
    if (!usernameValid) {
      setError('Username must be 3-30 characters: letters, numbers, hyphens, or underscores.');
      return;
    }
    if (!turnstileToken) {
      setError('Please complete the verification challenge.');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await register(username, email, turnstileToken, marketingOptIn, termsAccepted, inviteToken);
      setStep(STEPS.CHECK_EMAIL);
    } catch (err) {
      if (err?.response?.status === 403) {
        setWaitlistEmail(email);
        setWaitlistTurnstileToken('');
        setStep(STEPS.WAITLIST);
      } else {
        setError(err?.response?.data?.error ?? 'Registration failed. Please try again.');
        setTurnstileToken('');
        setTurnstileResetKey((k) => k + 1);
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
      const data = await setPasswordForNewUser(setupToken, password);
      completeRegistration(data);
      setStep(STEPS.HOME);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to set password. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  async function handleWaitlistSubmit(event) {
    event.preventDefault();
    setError('');
    setLoading(true);
    try {
      await submitWaitlist(waitlistEmail, waitlistTurnstileToken);
      setWaitlistSubmitted(true);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Something went wrong. Please try again.');
      setWaitlistTurnstileToken('');
      setWaitlistTurnstileResetKey((k) => k + 1);
    } finally {
      setLoading(false);
    }
  }

  // After HOME (saved or skipped): ask the server whether there is anything to choose. Skipped silently
  // when enforcement is off, the account is Pro or Admin, or the read fails: a reader is never held at
  // registration for a step that cannot be shown. The pre-selection is the server's suggestion (nearest
  // to home) and is recalculated on a return from Back unless the reader has already edited it.
  async function continueAfterHome() {
    let state = null;
    try {
      state = await getSubRegions();
    } catch {
      // Nothing to choose, as far as we can tell.
    }
    if (!pickerApplies(state)) {
      setStep(STEPS.SUCCESS);
      return;
    }
    setSubState(state);
    if (!subEditedRef.current) {
      setSubSelected(state.picks.length > 0 ? state.picks.map((p) => p.areaId) : state.suggested);
    }
    setSubError('');
    setStep(STEPS.SUB_REGIONS);
  }

  async function handleSubRegionsContinue() {
    setSubError('');
    setSubBusy(true);
    try {
      await persistSubRegions(subSelected);
      setStep(STEPS.SUCCESS);
    } catch (err) {
      setSubError(apiErrorMessage(err, SAVE_FAILED));
    } finally {
      setSubBusy(false);
    }
  }

  // "Skip for now" IS the reader's dismissal of the postcode prompt. Suppressed first, in memory, so
  // a PUT that fails cannot bring the prompt overlay back over the Welcome or the app this session
  // (the server flag stays due and the next page load asks again — the safe direction); then the
  // dismissal is recorded, a failure ignored, and the Welcome shows.
  async function handleSkipHome() {
    suppressPostcodePrompt();
    try {
      await markPostcodePromptDismissed();
    } catch {
      // The reader is signed in and skipping; nothing here is worth stopping them for.
    }
    await continueAfterHome();
  }

  // Clear the verification token from the URL, and end the pending registration two seconds after the
  // Welcome shows. The gate then mounts the app on the session that is already stored — no reload.
  useEffect(() => {
    if (step === STEPS.SUCCESS) {
      window.history.replaceState({}, '', window.location.pathname);
      const timer = setTimeout(finishRegistration, 2000);
      return () => clearTimeout(timer);
    }
  }, [step, finishRegistration]);

  return (
    <AuthShell wide={step === STEPS.SUB_REGIONS}>
        {/* REGISTER step */}
        {step === STEPS.REGISTER && (
          <form onSubmit={handleRegister} className="card flex flex-col gap-4" data-testid="register-form">
            <AuthHeading>Create an account</AuthHeading>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reg-email">Email</label>
              <input
                id="reg-email"
                type="email"
                data-testid="reg-email"
                autoComplete="email"
                aria-describedby="reg-email-hint"
                className={`field ${email.length > 0 && !emailValid ? 'field-error' : ''}`}
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
                required
              />
              <p
                id="reg-email-hint"
                className={`text-xs mt-1 ${email.length > 0 && !emailValid ? 'text-plex-error' : 'text-plex-text-secondary'}`}
              >
                {email.length > 0 && !emailValid
                  ? 'Please enter a valid email address'
                  : "We'll send a link to confirm it."}
              </p>
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="reg-username">Username</label>
              <input
                id="reg-username"
                type="text"
                data-testid="reg-username"
                autoComplete="username"
                aria-describedby="reg-username-hint"
                className={`field ${username.length > 0 && !usernameValid ? 'field-error' : ''}`}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                disabled={loading}
                required
              />
              <p
                id="reg-username-hint"
                className={`text-xs mt-1 ${username.length > 0 && !usernameValid ? 'text-plex-error' : 'text-plex-text-secondary'}`}
              >
                3–30 letters, numbers, - or _
              </p>
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
              {/* All three `{' '}`s in this span are LOAD-BEARING in a browser: the text and both
                  links are plain inline content, so removing any one glues two words in the
                  checkbox's accessible name ("…Conditions andPrivacy Policy") — measured for one
                  on 2026-09-11 and for all three on 2026-09-14, confirmed against Chromium's
                  native accessibility tree. */}
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

            {!turnstileLoadFailed && (
              <TurnstileWidget
                key={`reg-${turnstileResetKey}`}
                onVerify={(token) => setTurnstileToken(token)}
                onExpire={() => setTurnstileToken('')}
                onLoadFail={() => setTurnstileLoadFailed(true)}
              />
            )}
            {turnstileLoadFailed && (
              <p className="text-xs text-plex-text-secondary" role="status" data-testid="turnstile-unavailable">
                Security check couldn&apos;t load. Try another browser, or turn off your ad-blocker for this page.
              </p>
            )}

            {error && (
              <p className="text-xs text-plex-error" role="alert" data-testid="reg-error">{error}</p>
            )}

            <button
              type="submit"
              data-testid="reg-submit"
              className="btn-primary"
              disabled={loading || !turnstileToken || !termsAccepted}
              aria-describedby={termsAccepted ? undefined : 'reg-terms-hint'}
            >
              {loading ? 'Creating account…' : 'Create account'}
            </button>
            {!termsAccepted && (
              <p
                id="reg-terms-hint"
                className="text-xs text-plex-text-secondary text-center -mt-2"
                data-testid="reg-terms-hint"
              >
                Agree to the terms to continue
              </p>
            )}

            <div className="text-center">
              <button
                type="button"
                className="text-xs text-plex-text-secondary hover:text-plex-text"
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
          <div className="card flex flex-col gap-4" data-testid="check-email">
            <AuthHeading>Check your email</AuthHeading>
            <p className="text-sm text-plex-text-secondary">
              We&apos;ve sent a verification link to <strong className="text-plex-text">{email}</strong>
            </p>
            <p className="text-xs text-plex-text-secondary">
              Click the link in the email to verify your address and complete registration.
            </p>

            {error && (
              <p className="text-xs text-plex-error" role="alert" data-testid="resend-error">{error}</p>
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
              className="text-xs text-plex-text-secondary hover:text-plex-text"
              onClick={onBackToLogin}
            >
              Back to sign in
            </button>
          </div>
        )}

        {/* VERIFY step (loading spinner) */}
        {step === STEPS.VERIFY && (
          <div className="card flex flex-col gap-4" data-testid="verify-step">
            {loading && (
              <p className="text-sm text-plex-text-secondary animate-pulse">Verifying your email…</p>
            )}
            {!loading && error && (
              <>
                <AuthHeading>Verification failed</AuthHeading>
                <p className="text-xs text-plex-text-secondary" data-testid="verify-error">{error}</p>
                <button
                  type="button"
                  className="text-xs text-plex-text-secondary hover:text-plex-text underline"
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
              <AuthHeading>Set your password</AuthHeading>
              <p className="text-xs text-plex-text-secondary mt-1">
                Your email has been verified. Choose a password to complete registration.
              </p>
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="sp-password">Password</label>
              <PasswordInput
                id="sp-password"
                testId="sp-password"
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>

            <div>
              <label className="block text-xs text-plex-text-secondary mb-1" htmlFor="sp-confirm">Confirm password</label>
              <PasswordInput
                id="sp-confirm"
                testId="sp-confirm"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={loading}
              />
            </div>

            <PasswordChecklist checks={checks} testId="sp-checklist" />

            {error && (
              <p className="text-xs text-plex-error" role="alert" data-testid="sp-error">{error}</p>
            )}

            <button
              type="submit"
              data-testid="sp-submit"
              className="btn-primary"
              disabled={loading || !allPassed}
            >
              {loading ? 'Setting up…' : 'Complete registration'}
            </button>
          </form>
        )}

        {/* HOME step — the account exists and is signed in; the postcode is optional */}
        {step === STEPS.HOME && (
          <div className="card" data-testid="home-step">
            <PostcodeCapture
              heading="Where's home?"
              sub="Your postcode sets the sunrise and sunset times shown at the top of the app."
              primaryLabel="Save and continue"
              secondaryLabel="Skip for now"
              onSaved={continueAfterHome}
              onSkip={handleSkipHome}
              // A component prop, not the DOM attribute: PostcodeCapture honours it only under a fine pointer
              // (plan §5 #10), which is the very hazard the rule exists for.
              // eslint-disable-next-line jsx-a11y/no-autofocus
              autoFocus
              testIdPrefix="home"
            />
          </div>
        )}

        {/* SUB_REGIONS step — only when enforcement is on and the account picks (skipped otherwise) */}
        {step === STEPS.SUB_REGIONS && subState && (
          <SubRegionStepCard
            state={subState}
            selected={subSelected}
            onChange={(ids) => { subEditedRef.current = true; setSubSelected(ids); }}
            kicker={SUB_REGIONS_KICKER}
            sub={`Pick up to ${subState.limit} areas. You'll see forecast ratings for every location inside them.`}
            onContinue={handleSubRegionsContinue}
            onBack={() => setStep(STEPS.HOME)}
            error={subError}
            busy={subBusy}
            testIdPrefix="sub-regions-step"
          />
        )}

        {/* WAITLIST step */}
        {step === STEPS.WAITLIST && (
          <div className="card flex flex-col gap-4" data-testid="waitlist-step">
            {!waitlistSubmitted ? (
              <form onSubmit={handleWaitlistSubmit} className="flex flex-col gap-4">
                <AuthHeading>Early access is full</AuthHeading>
                <p className="text-xs text-plex-text-secondary">
                  We&apos;re currently at capacity. Leave your email and we&apos;ll email you an invitation when a
                  place opens.
                </p>
                <input
                  type="email"
                  data-testid="waitlist-email"
                  className="field"
                  value={waitlistEmail}
                  onChange={(e) => setWaitlistEmail(e.target.value)}
                  placeholder="you@example.com"
                  disabled={loading}
                  required
                />
                {!waitlistTurnstileLoadFailed && (
                  <TurnstileWidget
                    key={`wait-${waitlistTurnstileResetKey}`}
                    onVerify={(token) => setWaitlistTurnstileToken(token)}
                    onExpire={() => setWaitlistTurnstileToken('')}
                    onLoadFail={() => setWaitlistTurnstileLoadFailed(true)}
                  />
                )}
                {waitlistTurnstileLoadFailed && (
                  <p className="text-xs text-plex-text-secondary" role="status" data-testid="waitlist-turnstile-unavailable">
                    Security check couldn&apos;t load. Try another browser, or turn off your ad-blocker for this page.
                  </p>
                )}
                {error && (
                  <p className="text-xs text-plex-error" role="alert" data-testid="waitlist-error">{error}</p>
                )}
                <button
                  type="submit"
                  className="btn-primary"
                  data-testid="waitlist-submit"
                  disabled={loading || !waitlistTurnstileToken}
                >
                  {loading ? 'Submitting…' : 'Notify me'}
                </button>
              </form>
            ) : (
              <>
                <AuthHeading>You&apos;re on the list</AuthHeading>
                <p className="text-xs text-plex-text-secondary" data-testid="waitlist-success">
                  We&apos;ll email you an invitation when a place opens.
                </p>
              </>
            )}
            <button
              type="button"
              className="text-xs text-plex-text-secondary hover:text-plex-text"
              onClick={onBackToLogin}
            >
              Back to sign in
            </button>
          </div>
        )}

        {/* SUCCESS step */}
        {step === STEPS.SUCCESS && (
          <div className="card flex flex-col gap-4" data-testid="success-step">
            <AuthHeading>Welcome to PhotoCast!</AuthHeading>
            <p className="text-xs text-plex-text-secondary animate-pulse">Redirecting…</p>
          </div>
        )}

    </AuthShell>
  );
}

RegisterPage.propTypes = {
  verifyToken: PropTypes.string,
  inviteToken: PropTypes.string,
  onBackToLogin: PropTypes.func.isRequired,
};
