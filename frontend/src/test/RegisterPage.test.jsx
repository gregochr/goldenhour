import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import RegisterPage from '../components/RegisterPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');

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
    await user.type(screen.getByTestId('reg-confirm-email'), 'new@example.com');
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'prefilled@example.com');
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'wait@example.com');
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

    expect(AuthApi.submitWaitlist).toHaveBeenCalledWith('wait@example.com');
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'fail@example.com');
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'new@example.com');
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'back@example.com');
    await user.click(screen.getByTestId('reg-terms-accepted'));
    await user.click(screen.getByTestId('reg-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-step')).toBeInTheDocument();
    });

    await user.click(screen.getByText('Back to sign in'));
    expect(onBack).toHaveBeenCalledOnce();
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
    await user.type(screen.getByTestId('reg-confirm-email'), 'new@example.com');
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
    const hint = screen.getByText('3-30 characters, letters, numbers, hyphens, or underscores');
    expect(hint).toHaveClass('text-plex-text-secondary');

    await user.type(screen.getByTestId('reg-username'), 'a');

    expect(hint).toHaveClass('text-plex-error');
    expect(hint).not.toHaveClass('text-plex-text-secondary');
    expect(screen.getByTestId('reg-username')).toHaveClass('field-error');
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
      vi.spyOn(AuthApi, 'verifyEmail').mockResolvedValue(undefined);
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
      expect(AuthApi.setPasswordForNewUser).toHaveBeenCalledWith('tok', 'Abcdef1!', 'test-turnstile-token');
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

      const card = await screen.findByTestId('success-step');
      expect(card).not.toHaveClass('text-center');
      expect(h2('Welcome to PhotoCast!')).toBeInTheDocument();
      expect(screen.getByText('Redirecting…')).toBeInTheDocument();
    });
  });
});
