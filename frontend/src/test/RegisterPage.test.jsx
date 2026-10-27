import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RegisterPage from '../components/RegisterPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';
import * as SettingsApi from '../api/settingsApi.js';

vi.mock('../api/authApi.js');
vi.mock('../api/settingsApi.js');
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn().mockResolvedValue({ enforced: false, limit: 3, picks: [], options: [], suggested: [], status: 'FIRST_CHOICE' }), saveSubRegions: vi.fn() }));

function installTurnstileMock() {
  window.turnstile = {
    render: vi.fn((_container, opts) => {
      if (opts.callback) opts.callback('test-turnstile-token');
      return 'widget-id-1';
    }),
    remove: vi.fn(),
    reset: vi.fn(),
  };
}

const renderPage = (props = {}) =>
  render(
    <AuthProvider>
      <RegisterPage onBackToLogin={vi.fn()} {...props} />
    </AuthProvider>,
  );

describe('RegisterPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  it('renders the registration form', () => {
    renderPage();
    expect(screen.getByTestId('register-form')).toBeInTheDocument();
    expect(screen.getByTestId('reg-username')).toBeInTheDocument();
    expect(screen.getByTestId('reg-email')).toBeInTheDocument();
  });

  it('shows waitlist form when registration returns 403', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 403, data: { error: 'Early access is currently full' } },
    });

    renderPage();

    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'new@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-step')).toBeInTheDocument();
    });

    expect(screen.getByText('Early access is full')).toBeInTheDocument();
    expect(screen.getByTestId('waitlist-email')).toHaveValue('new@example.com');
    expect(screen.getByTestId('waitlist-submit')).toBeInTheDocument();
  });

  it('pre-fills waitlist email from registration email', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 403, data: { error: 'Early access is currently full' } },
    });

    renderPage();

    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'prefilled@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-email')).toHaveValue('prefilled@example.com');
    });
  });

  it('submits waitlist email and shows success message', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 403, data: { error: 'Early access is currently full' } },
    });
    vi.spyOn(AuthApi, 'submitWaitlist').mockResolvedValue({ message: "You're on the list" });

    renderPage();

    // Trigger 403 to get to waitlist step
    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'wait@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-step')).toBeInTheDocument();
    });

    // Submit waitlist
    await user.click(screen.getByTestId('waitlist-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-success')).toBeInTheDocument();
    });

    expect(AuthApi.submitWaitlist).toHaveBeenCalledWith('wait@example.com', 'test-turnstile-token');
  });

  it('shows error on waitlist submit failure', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 403, data: { error: 'Early access is currently full' } },
    });
    vi.spyOn(AuthApi, 'submitWaitlist').mockRejectedValue({
      response: { status: 500, data: { error: 'Server error' } },
    });

    renderPage();

    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'fail@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-step')).toBeInTheDocument();
    });

    await user.click(screen.getByTestId('waitlist-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-error')).toBeInTheDocument();
      expect(screen.getByText('Server error')).toBeInTheDocument();
    });
  });

  it('does not show waitlist for non-403 errors', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 409, data: { error: 'Username already exists' } },
    });

    renderPage();

    await user.type(screen.getByTestId('reg-username'), 'taken');
    await user.type(screen.getByTestId('reg-email'), 'new@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('reg-error')).toBeInTheDocument();
      expect(screen.getByText('Username already exists')).toBeInTheDocument();
    });

    // Waitlist step should NOT be rendered
    expect(screen.queryByTestId('waitlist-step')).not.toBeInTheDocument();
  });

  it('shows "Back to sign in" link on waitlist step', async () => {
    const onBack = vi.fn();
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({
      response: { status: 403, data: { error: 'Full' } },
    });

    render(
      <AuthProvider>
        <RegisterPage onBackToLogin={onBack} />
      </AuthProvider>,
    );

    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'back@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-step')).toBeInTheDocument();
    });

    await user.click(screen.getByText('Back to sign in'));
    expect(onBack).toHaveBeenCalledOnce();
  });
});

/** Waitlist invitations and the waitlist step's own CAPTCHA (audit-followups-plan.md, W). */
describe('RegisterPage waitlist invitations', () => {
  const full = { response: { status: 403, data: { error: 'Early access is currently full' } } };

  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  async function fillAndSubmit(user) {
    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'new@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));
  }

  it('sends the invite token from the link with the registration', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
    renderPage({ inviteToken: 'invite-raw' });

    await fillAndSubmit(user);

    await waitFor(() => {
      expect(AuthApi.register).toHaveBeenCalledWith(
        'newuser', 'new@example.com', 'test-turnstile-token', false, true, 'invite-raw',
      );
    });
  });

  it('sends null (no invitation) when the page was not opened from an invitation', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
    renderPage();

    await fillAndSubmit(user);

    await waitFor(() => {
      expect(AuthApi.register).toHaveBeenCalledWith(
        'newuser', 'new@example.com', 'test-turnstile-token', false, true, null,
      );
    });
  });

  it('an invitation that is refused still lands on the waitlist step', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    renderPage({ inviteToken: 'stale' });

    await fillAndSubmit(user);

    expect(await screen.findByTestId('waitlist-step')).toBeInTheDocument();
  });

  it('the waitlist step promises an emailed invitation, and shows it again once submitted', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    vi.spyOn(AuthApi, 'submitWaitlist').mockResolvedValue({ message: "You're on the list" });
    renderPage();

    await fillAndSubmit(user);
    const step = await screen.findByTestId('waitlist-step');
    expect(step).toHaveTextContent("we'll email you an invitation when a place opens");

    await user.click(screen.getByTestId('waitlist-submit'));
    expect(await screen.findByTestId('waitlist-success'))
      .toHaveTextContent("We'll email you an invitation when a place opens.");
  });

  it('mounts exactly one Turnstile on the waitlist step (the register one is gone) and sends its token', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    vi.spyOn(AuthApi, 'submitWaitlist').mockResolvedValue({ message: 'ok' });
    renderPage();

    await fillAndSubmit(user);
    await screen.findByTestId('waitlist-step');

    expect(screen.getAllByTestId('turnstile-widget')).toHaveLength(1);
    await user.click(screen.getByTestId('waitlist-submit'));
    await waitFor(() => {
      expect(AuthApi.submitWaitlist).toHaveBeenCalledWith('new@example.com', 'test-turnstile-token');
    });
  });

  it('keeps the waitlist submit disabled until the challenge produces a token', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    let waitlistCallback;
    window.turnstile = {
      render: vi.fn((_container, opts) => {
        // The register step's widget answers at once; the waitlist step's stays pending.
        if (window.turnstile.render.mock.calls.length === 1) opts.callback('register-token');
        else waitlistCallback = opts.callback;
        return 'widget-id';
      }),
      remove: vi.fn(),
      reset: vi.fn(),
    };
    renderPage();

    await fillAndSubmit(user);
    await screen.findByTestId('waitlist-step');

    expect(screen.getByTestId('waitlist-submit')).toBeDisabled();
    waitlistCallback('waitlist-token');
    await waitFor(() => expect(screen.getByTestId('waitlist-submit')).toBeEnabled());
  });

  it('says so, and keeps submit disabled, when the waitlist Turnstile cannot load (no fallback)', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    vi.spyOn(AuthApi, 'submitWaitlist').mockResolvedValue({ message: 'ok' });
    let renders = 0;
    window.turnstile = {
      render: vi.fn((_container, opts) => {
        renders += 1;
        if (renders === 1) {
          opts.callback('register-token');
          return 'widget-id';
        }
        throw new Error('domain not whitelisted');
      }),
      remove: vi.fn(),
      reset: vi.fn(),
    };
    renderPage();

    await fillAndSubmit(user);

    expect(await screen.findByTestId('waitlist-turnstile-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('waitlist-submit')).toBeDisabled();
    expect(AuthApi.submitWaitlist).not.toHaveBeenCalled();
  });

  it('a 429 from the waitlist shows the server sentence and asks for a fresh challenge', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue(full);
    vi.spyOn(AuthApi, 'submitWaitlist').mockRejectedValue({
      response: { status: 429, data: { error: 'Too many attempts. Please wait a few minutes and try again.' } },
    });
    renderPage();

    await fillAndSubmit(user);
    await screen.findByTestId('waitlist-step');
    const rendersBefore = window.turnstile.render.mock.calls.length;
    await user.click(screen.getByTestId('waitlist-submit'));

    expect(await screen.findByText('Too many attempts. Please wait a few minutes and try again.'))
      .toBeInTheDocument();
    await waitFor(() => expect(window.turnstile.render.mock.calls.length).toBeGreaterThan(rendersBefore));
  });
});

/**
 * The auth visual foundation (login-registration-plan.md §2 #9, R1): every step wears the shared
 * heading, the left-aligned card and the status tokens, with no change to fields, order or copy.
 */
describe('RegisterPage visual foundation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
    localStorage.clear();
  });

  const fillAndSubmit = async (user) => {
    await user.type(screen.getByTestId('reg-username'), 'newuser');
    await user.type(screen.getByTestId('reg-email'), 'new@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));
  };

  const h2 = (name) => screen.getByRole('heading', { level: 2, name });

  it('sits in the auth shell under the one h1, with an h2 card heading on the form step', () => {
    renderPage();

    expect(screen.getByTestId('auth-shell')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(h2('Create an account')).toHaveClass('font-serif', 'text-[22px]');
  });

  it('colours an invalid username with the error token and its hint with secondary ink', async () => {
    const user = userEvent.setup();
    renderPage();
    const hint = screen.getByText('3–30 letters, numbers, - or _');
    expect(hint).toHaveClass('text-plex-text-secondary');

    await user.type(screen.getByTestId('reg-username'), 'a');

    expect(hint).toHaveClass('text-plex-error');
    expect(hint).not.toHaveClass('text-plex-text-secondary');
    expect(screen.getByTestId('reg-username')).toHaveClass('field-error');
  });

  it('marks an invalid email with field-error and swaps its hint for the error sentence in the error token', async () => {
    const user = userEvent.setup();
    renderPage();
    const hint = screen.getByText("We'll send a link to confirm it.");
    expect(hint).toHaveClass('text-plex-text-secondary');

    await user.type(screen.getByTestId('reg-email'), 'nope');

    expect(screen.getByTestId('reg-email')).toHaveClass('field', 'field-error');
    const error = screen.getByText('Please enter a valid email address');
    expect(error).toBe(hint);
    expect(error).toHaveClass('text-plex-error');
    expect(error).not.toHaveClass('text-plex-text-secondary');
    expect(screen.queryByText("We'll send a link to confirm it.")).not.toBeInTheDocument();
  });

  it('shows a registration failure in the error token', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({ response: { status: 409, data: { error: 'Taken' } } });
    renderPage();
    await fillAndSubmit(user);

    const alert = await screen.findByTestId('reg-error');
    expect(alert).toHaveTextContent('Taken');
    expect(alert).toHaveClass('text-plex-error');
  });

  it('left-aligns the Check your email card and keeps its copy', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
    renderPage();
    await fillAndSubmit(user);

    const card = await screen.findByTestId('check-email');
    expect(h2('Check your email')).toBeInTheDocument();
    expect(card).not.toHaveClass('text-center');
    expect(screen.getByText('new@example.com')).toBeInTheDocument();
    expect(screen.getByText(/Click the link in the email to verify your address/)).toHaveClass('text-plex-text-secondary');
  });

  it('left-aligns the waitlist cards, both the form and the confirmation', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'register').mockRejectedValue({ response: { status: 403, data: {} } });
    vi.spyOn(AuthApi, 'submitWaitlist').mockResolvedValue(undefined);
    renderPage();
    await fillAndSubmit(user);

    const card = await screen.findByTestId('waitlist-step');
    expect(h2('Early access is full')).toBeInTheDocument();
    expect(card).not.toHaveClass('text-center');

    await user.click(screen.getByTestId('waitlist-submit'));
    await screen.findByTestId('waitlist-success');
    expect(h2("You're on the list")).toBeInTheDocument();
    expect(screen.getByTestId('waitlist-step')).not.toHaveClass('text-center');
  });

  it('left-aligns the Verify card; a failed verification shows an h2 and the message in secondary ink', async () => {
    vi.spyOn(AuthApi, 'verifyEmail').mockRejectedValue({ response: { data: { error: 'Link expired' } } });
    renderPage({ verifyToken: 'tok' });

    const card = screen.getByTestId('verify-step');
    expect(card).not.toHaveClass('text-center');
    expect(await screen.findByTestId('verify-error')).toHaveTextContent('Link expired');
    expect(h2('Verification failed')).toBeInTheDocument();
    expect(screen.getByTestId('verify-error')).toHaveClass('text-plex-text-secondary');
  });

  describe('the set-password step', () => {
    const toSetPassword = async () => {
      vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue({ userId: 7, verified: true, setupToken: 'setup-tok' });
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');
    };

    it('renders the shared checklist, unmet as ○ until each rule is met, then ✓ in plex-ok', async () => {
      const user = userEvent.setup();
      await toSetPassword();
      const checklist = screen.getByTestId('sp-checklist');
      const items = () => [...checklist.querySelectorAll('li')];
      expect(items().map((li) => li.textContent)).toEqual([
        '○At least 8 characters', '○One uppercase letter', '○One lowercase letter',
        '○One number', '○One special character', '○Passwords match',
      ]);
      items().forEach((li) => expect(li).toHaveClass('text-plex-text-secondary'));

      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');

      expect(items().map((li) => li.textContent.charAt(0))).toEqual(['✓', '✓', '✓', '✓', '✓', '✓']);
      items().forEach((li) => expect(li).toHaveClass('text-plex-ok'));
    });

    it('marks only the rules actually met', async () => {
      const user = userEvent.setup();
      await toSetPassword();

      await user.type(screen.getByTestId('sp-password'), 'abcdefgh');

      const met = [...screen.getByTestId('sp-checklist').querySelectorAll('li')]
        .filter((li) => li.getAttribute('data-ok') === 'true')
        .map((li) => li.textContent.slice(1));
      expect(met).toEqual(['At least 8 characters', 'One lowercase letter']);
    });

    it('gives each password field its own eye, toggling independently', async () => {
      const user = userEvent.setup();
      await toSetPassword();
      const [first, second] = screen.getAllByRole('button', { name: 'Show password' });

      await user.click(first);

      expect(screen.getByTestId('sp-password')).toHaveAttribute('type', 'text');
      expect(screen.getByTestId('sp-confirm')).toHaveAttribute('type', 'password');
      expect(second).toHaveAttribute('aria-label', 'Show password');
    });

    it('has an h2 heading, secondary-ink help text and a disabled submit until every rule passes', async () => {
      await toSetPassword();

      expect(h2('Set your password')).toBeInTheDocument();
      expect(screen.getByText(/Your email has been verified/)).toHaveClass('text-plex-text-secondary');
      expect(screen.getByTestId('sp-submit')).toBeDisabled();
    });

    it('shows a failed set-password in the error token and keeps the form', async () => {
      const user = userEvent.setup();
      await toSetPassword();
      vi.spyOn(AuthApi, 'setPasswordForNewUser').mockRejectedValue({ response: { data: { error: 'Nope' } } });
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');

      await user.click(screen.getByTestId('sp-submit'));

      const error = await screen.findByTestId('sp-error');
      expect(error).toHaveTextContent('Nope');
      expect(error).toHaveClass('text-plex-error');
      expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledWith('setup-tok', 'Abcdef1!');
    });

    it('left-aligns the Success card with the Welcome heading and the unchanged copy', async () => {
      const user = userEvent.setup();
      await toSetPassword();
      vi.spyOn(AuthApi, 'setPasswordForNewUser').mockResolvedValue({
        accessToken: 'a', refreshToken: 'r', role: 'LITE_USER', username: 'newuser',
      });
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');

      await user.click(screen.getByTestId('sp-submit'));
      // The password step now leads to HOME; Skip for now is the way on to the Welcome.
      SettingsApi.markPostcodePromptDismissed.mockResolvedValue({});
      await user.click(await screen.findByTestId('home-secondary'));

      const card = await screen.findByTestId('success-step');
      expect(card).not.toHaveClass('text-center');
      expect(h2('Welcome to PhotoCast!')).toBeInTheDocument();
      expect(screen.getByText('Redirecting…')).toBeInTheDocument();
    });
  });
});

/**
 * R2b (login-registration-plan.md §3 R2b, §2 #4, §2 #12): the registration form drops "Confirm
 * email", puts email first with its hints, opts marketing OUT by default, says why the button is
 * disabled, keeps ONE Turnstile (set-password has none), and falls back honestly when the script
 * cannot load.
 */
describe('RegisterPage registration form (R2b)', () => {
  const REGISTER_FALLBACK =
    "Security check couldn't load. Try another browser, or turn off your ad-blocker for this page.";
  const LOGIN_FALLBACK = "Security check unavailable — we'll verify your sign-in another way.";

  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  /** A Turnstile whose script is present but whose render throws, as for a non-whitelisted domain. */
  const installBrokenTurnstile = () => {
    window.turnstile = {
      render: vi.fn(() => { throw new Error('Domain not whitelisted'); }),
      remove: vi.fn(),
    };
  };

  /** A Turnstile that renders but never produces a token (the challenge is still pending). */
  const installPendingTurnstile = () => {
    window.turnstile = { render: vi.fn(() => 'widget-id-1'), remove: vi.fn() };
  };

  describe('the fields', () => {
    it('asks for Email first, then Username, and nothing else to type', () => {
      const { container } = renderPage();

      const inputs = [...container.querySelectorAll('input')]
        .filter((i) => i.type !== 'checkbox')
        .map((i) => i.id);
      expect(inputs).toEqual(['reg-email', 'reg-username']);
      expect(screen.getByLabelText('Email')).toBe(screen.getByTestId('reg-email'));
      expect(screen.getByLabelText('Username')).toBe(screen.getByTestId('reg-username'));
    });

    it('restores the email hint once a typo is corrected', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.type(screen.getByTestId('reg-email'), 'nope');
      expect(screen.queryByText("We'll send a link to confirm it.")).not.toBeInTheDocument();
      await user.type(screen.getByTestId('reg-email'), '@example.com');

      const hint = screen.getByText("We'll send a link to confirm it.");
      expect(hint).toHaveClass('text-plex-text-secondary');
      expect(hint).not.toHaveClass('text-plex-error');
      expect(screen.getByTestId('reg-email')).not.toHaveClass('field-error');
    });

    it('has no Confirm email field, label or mismatch message', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.type(screen.getByTestId('reg-email'), 'a@example.com');

      expect(screen.queryByTestId('reg-confirm-email')).not.toBeInTheDocument();
      expect(screen.queryByText(/confirm email/i)).not.toBeInTheDocument();
      expect(screen.queryByText(/do not match/i)).not.toBeInTheDocument();
    });

    it('puts the two hints under their fields, in secondary ink, wired with aria-describedby', () => {
      renderPage();

      const emailHint = screen.getByText("We'll send a link to confirm it.");
      const usernameHint = screen.getByText('3–30 letters, numbers, - or _');
      expect(emailHint).toHaveClass('text-xs', 'text-plex-text-secondary');
      expect(usernameHint).toHaveClass('text-xs', 'text-plex-text-secondary');
      expect(screen.getByTestId('reg-email')).toHaveAttribute('aria-describedby', emailHint.id);
      expect(screen.getByTestId('reg-username')).toHaveAttribute('aria-describedby', usernameHint.id);
      // The email hint belongs to the email field, the username hint to the username field.
      expect(screen.getByTestId('reg-email').parentElement).toContainElement(emailHint);
      expect(screen.getByTestId('reg-username').parentElement).toContainElement(usernameHint);
    });

    it('validates the email before the username when both are wrong', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'register');
      renderPage();

      await user.type(screen.getByTestId('reg-email'), 'nope');
      await user.type(screen.getByTestId('reg-username'), 'a');
      // fireEvent.submit skips the browser's own constraint validation, which would otherwise
      // swallow the click on a type=email field holding "nope" before the handler ever ran.
      fireEvent.submit(screen.getByTestId('register-form'));

      expect(await screen.findByTestId('reg-error')).toHaveTextContent('Please enter a valid email address.');
      expect(AuthApi.register).not.toHaveBeenCalled();

      await user.clear(screen.getByTestId('reg-email'));
      await user.type(screen.getByTestId('reg-email'), 'ok@example.com');
      fireEvent.submit(screen.getByTestId('register-form'));

      expect(await screen.findByText(/Username must be 3-30 characters/)).toBeInTheDocument();
      expect(AuthApi.register).not.toHaveBeenCalled();
    });
  });

  describe('marketing consent', () => {
    it('is unticked by default', () => {
      renderPage();
      expect(screen.getByTestId('reg-marketing-opt-in')).not.toBeChecked();
    });

    it('registers with marketingEmailOptIn false unless the reader ticks it', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
      renderPage();

      await user.type(screen.getByTestId('reg-email'), 'new@example.com');
      await user.type(screen.getByTestId('reg-username'), 'newuser');
      await user.click(screen.getByTestId('reg-terms-accepted'));
      await user.click(screen.getByTestId('reg-submit'));

      await waitFor(() => {
        expect(AuthApi.register).toHaveBeenCalledWith(
          'newuser', 'new@example.com', 'test-turnstile-token', false, true, null,
        );
      });
    });

    it('registers with marketingEmailOptIn true once the reader ticks it', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
      renderPage();

      await user.type(screen.getByTestId('reg-email'), 'new@example.com');
      await user.type(screen.getByTestId('reg-username'), 'newuser');
      await user.click(screen.getByTestId('reg-terms-accepted'));
      await user.click(screen.getByTestId('reg-marketing-opt-in'));
      await user.click(screen.getByTestId('reg-submit'));

      await waitFor(() => {
        expect(AuthApi.register).toHaveBeenCalledWith(
          'newuser', 'new@example.com', 'test-turnstile-token', true, true, null,
        );
      });
    });
  });

  describe('the disabled reason', () => {
    it('says why Create account is disabled, under the button, while terms are unticked', () => {
      renderPage();

      const hint = screen.getByTestId('reg-terms-hint');
      expect(hint).toHaveTextContent('Agree to the terms to continue');
      expect(hint).toHaveClass('text-xs', 'text-plex-text-secondary', 'text-center', '-mt-2');
      expect(screen.getByTestId('reg-submit')).toBeDisabled();
      expect(screen.getByTestId('reg-submit')).toHaveAttribute('aria-describedby', hint.id);
      // Directly under the button, above the "Already have an account?" link.
      expect(screen.getByTestId('reg-submit').nextElementSibling).toBe(hint);
    });

    it('disappears when the terms are ticked and comes back when they are unticked', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId('reg-terms-accepted'));
      expect(screen.queryByTestId('reg-terms-hint')).not.toBeInTheDocument();
      expect(screen.queryByText('Agree to the terms to continue')).not.toBeInTheDocument();
      expect(screen.getByTestId('reg-submit')).toBeEnabled();
      expect(screen.getByTestId('reg-submit')).not.toHaveAttribute('aria-describedby');

      await user.click(screen.getByTestId('reg-terms-accepted'));
      expect(screen.getByText('Agree to the terms to continue')).toBeInTheDocument();
      expect(screen.getByTestId('reg-submit')).toBeDisabled();
    });

    it('says nothing about terms once they are ticked even while the security check is still pending', async () => {
      installPendingTurnstile();
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId('reg-terms-accepted'));

      expect(screen.getByTestId('reg-submit')).toBeDisabled();
      expect(screen.queryByTestId('reg-terms-hint')).not.toBeInTheDocument();
    });
  });

  describe('Turnstile', () => {
    it('renders exactly one widget on the form', () => {
      renderPage();

      expect(screen.getAllByTestId('turnstile-widget')).toHaveLength(1);
      expect(window.turnstile.render).toHaveBeenCalledTimes(1);
    });

    it('on a load failure unmounts the widget and shows the REGISTER sentence, not the sign-in one', async () => {
      installBrokenTurnstile();
      renderPage();

      const line = await screen.findByTestId('turnstile-unavailable');
      expect(line).toHaveTextContent(REGISTER_FALLBACK);
      expect(line).toHaveClass('text-xs', 'text-plex-text-secondary');
      expect(line).toHaveAttribute('role', 'status');
      expect(line.className).not.toMatch(/amber|red/);
      expect(screen.queryByText(LOGIN_FALLBACK)).not.toBeInTheDocument();
      expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
    });

    it('on a load failure KEEPS Create account disabled and never calls register', async () => {
      installBrokenTurnstile();
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'register').mockResolvedValue(undefined);
      renderPage();
      await screen.findByTestId('turnstile-unavailable');

      await user.type(screen.getByTestId('reg-email'), 'new@example.com');
      await user.type(screen.getByTestId('reg-username'), 'newuser');
      await user.click(screen.getByTestId('reg-terms-accepted'));
      await user.click(screen.getByTestId('reg-submit'));
      await user.type(screen.getByTestId('reg-username'), '{Enter}');

      expect(screen.getByTestId('reg-submit')).toBeDisabled();
      expect(AuthApi.register).not.toHaveBeenCalled();
    });

    it('does not show the fallback sentence while the widget is working', () => {
      renderPage();
      expect(screen.queryByTestId('turnstile-unavailable')).not.toBeInTheDocument();
    });
  });

  describe('the set-password step needs no security check', () => {
    const toSetPassword = async () => {
      vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue({ userId: 7, verified: true, setupToken: 'setup-tok' });
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');
    };

    it('mounts no Turnstile widget, loads no script, and enables submit on the rules alone', async () => {
      installPendingTurnstile();
      document.querySelectorAll('script[src*="challenges.cloudflare.com"]').forEach((el) => el.remove());
      const user = userEvent.setup();
      await toSetPassword();

      expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
      expect(window.turnstile.render).not.toHaveBeenCalled();
      expect(document.querySelector('script[src*="challenges.cloudflare.com"]')).toBeNull();
      expect(screen.getByTestId('sp-submit')).toBeDisabled();

      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');

      expect(screen.getByTestId('sp-submit')).toBeEnabled();
    });

    it('posts the token and password only, with no third argument', async () => {
      installPendingTurnstile();
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'setPasswordForNewUser').mockResolvedValue({
        accessToken: 'a', refreshToken: 'r', role: 'LITE_USER', username: 'newuser',
      });
      await toSetPassword();

      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
      await user.click(screen.getByTestId('sp-submit'));

      await waitFor(() => expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledTimes(1));
      expect(AuthApi.setPasswordForNewUser.mock.calls[0]).toEqual(['setup-tok', 'Abcdef1!']);
    });

    it('keeps submit disabled until every rule passes, including the confirmation matching', async () => {
      const user = userEvent.setup();
      await toSetPassword();

      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');

      // The confirmation does not match yet.
      expect(screen.getByTestId('sp-submit')).toBeDisabled();
    });
  });

  describe('the rotated setup token', () => {
    const fillAndSubmit = async (user) => {
      await user.type(screen.getByTestId('sp-password'), 'Abcdef1!');
      await user.type(screen.getByTestId('sp-confirm'), 'Abcdef1!');
      await user.click(screen.getByTestId('sp-submit'));
    };

    it('a double-fired verify (StrictMode, a double click) whose SECOND call is refused as already used '
      + 'still lands on the password step with no error, and posts the setup token', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'verifyEmail')
        .mockResolvedValueOnce({ userId: 7, verified: true, setupToken: 'setup-tok' })
        .mockRejectedValue({ response: { data: { error: 'This link has already been used' } } });
      vi.spyOn(AuthApi, 'setPasswordForNewUser').mockResolvedValue({
        accessToken: 'a', refreshToken: 'r', role: 'LITE_USER', username: 'newuser',
      });
      render(
        <React.StrictMode>
          <AuthProvider>
            <RegisterPage verifyToken="tok" onBackToLogin={vi.fn()} />
          </AuthProvider>
        </React.StrictMode>,
      );

      await screen.findByTestId('set-password-form');
      await waitFor(() => expect(AuthApi.verifyEmail).toHaveBeenCalledTimes(2));
      expect(screen.queryByTestId('verify-error')).not.toBeInTheDocument();
      expect(screen.queryByTestId('sp-error')).not.toBeInTheDocument();
      expect(screen.getByTestId('set-password-form')).toBeInTheDocument();

      await fillAndSubmit(user);
      await waitFor(() => expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledTimes(1));
      expect(AuthApi.setPasswordForNewUser.mock.calls[0]).toEqual(['setup-tok', 'Abcdef1!']);
    });

    it('a failure that lands BEFORE the winning verify resolves is cleared when the success arrives', async () => {
      let resolveWinner;
      vi.spyOn(AuthApi, 'verifyEmail')
        .mockReturnValueOnce(new Promise((resolve) => { resolveWinner = resolve; }))
        .mockRejectedValue({ response: { data: { error: 'This link has already been used' } } });
      render(
        <React.StrictMode>
          <AuthProvider>
            <RegisterPage verifyToken="tok" onBackToLogin={vi.fn()} />
          </AuthProvider>
        </React.StrictMode>,
      );
      await screen.findByTestId('verify-error');

      resolveWinner({ userId: 7, verified: true, setupToken: 'setup-tok' });

      await screen.findByTestId('set-password-form');
      expect(screen.queryByTestId('verify-error')).not.toBeInTheDocument();
    });

    it('keeps the setup token in React state only: never in storage or the URL', async () => {
      vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue({ userId: 7, verified: true, setupToken: 'setup-tok' });
      renderPage({ verifyToken: 'tok' });
      await screen.findByTestId('set-password-form');

      expect(JSON.stringify({ ...localStorage })).not.toContain('setup-tok');
      expect(JSON.stringify({ ...sessionStorage })).not.toContain('setup-tok');
      expect(window.location.href).not.toContain('setup-tok');
    });
  });
});
