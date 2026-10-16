import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ForgotPasswordPage from '../components/auth/ForgotPasswordPage.jsx';
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

const DONE = "If that email has an account, we've sent a reset link.";

const renderPage = (props = {}) => {
  const onBackToLogin = vi.fn();
  render(
    <AuthProvider>
      <ForgotPasswordPage onBackToLogin={onBackToLogin} {...props} />
    </AuthProvider>,
  );
  return { onBackToLogin };
};

async function fillAndSubmit(user, email = 'chris@example.com') {
  await user.type(screen.getByTestId('forgot-email'), email);
  await user.click(screen.getByTestId('forgot-submit'));
}

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  it('renders the spec copy: heading, sub line, Email label, button and Back to sign in', () => {
    renderPage();

    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Reset your password' })).toBeInTheDocument();
    expect(screen.getByText("Enter your email and we'll send you a link.")).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBe(screen.getByTestId('forgot-email'));
    expect(screen.getByTestId('forgot-email')).toHaveAttribute('type', 'email');
    expect(screen.getByTestId('forgot-email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByTestId('forgot-submit')).toHaveTextContent('Send reset link');
    expect(screen.getByTestId('forgot-back')).toHaveTextContent('Back to sign in');
    expect(screen.queryByTestId('forgot-done')).not.toBeInTheDocument();
  });

  it('posts the trimmed email and the Turnstile token, then shows the done state', async () => {
    const user = userEvent.setup();
    AuthApi.requestPasswordReset.mockResolvedValue({ message: 'whatever the server says' });
    renderPage();

    await fillAndSubmit(user, '  chris@example.com ');

    await waitFor(() => expect(screen.getByTestId('forgot-done')).toBeInTheDocument());
    expect(AuthApi.requestPasswordReset).toHaveBeenCalledOnce();
    expect(AuthApi.requestPasswordReset).toHaveBeenCalledWith('chris@example.com', 'test-turnstile-token');
    // The page prints its own constant, not the response's wording.
    expect(screen.getByTestId('forgot-done-message')).toHaveTextContent(DONE);
    expect(screen.getByTestId('forgot-done-message')).toHaveAttribute('role', 'status');
    expect(screen.queryByTestId('forgot-form')).not.toBeInTheDocument();
    expect(screen.queryByText('whatever the server says')).not.toBeInTheDocument();
  });

  it('the done state offers Back to sign in, which calls onBackToLogin', async () => {
    const user = userEvent.setup();
    AuthApi.requestPasswordReset.mockResolvedValue({ message: 'x' });
    const { onBackToLogin } = renderPage();
    await fillAndSubmit(user);

    await user.click(await screen.findByTestId('forgot-back'));

    expect(onBackToLogin).toHaveBeenCalledOnce();
  });

  it('the form\'s own Back to sign in calls onBackToLogin and sends nothing', async () => {
    const user = userEvent.setup();
    const { onBackToLogin } = renderPage();

    await user.click(screen.getByTestId('forgot-back'));

    expect(onBackToLogin).toHaveBeenCalledOnce();
    expect(AuthApi.requestPasswordReset).not.toHaveBeenCalled();
  });

  it('shows the server\'s sentence for a 400 and stays on the form with the challenge re-armed', async () => {
    const user = userEvent.setup();
    AuthApi.requestPasswordReset.mockRejectedValue({ response: { status: 400, data: { error: 'Invalid email address' } } });
    renderPage();
    const renders = window.turnstile.render.mock.calls.length;

    await fillAndSubmit(user);

    const alert = await screen.findByTestId('forgot-error');
    expect(alert).toHaveTextContent('Invalid email address');
    expect(alert).toHaveAttribute('role', 'alert');
    expect(alert).toHaveClass('text-plex-error');
    expect(screen.queryByTestId('forgot-done')).not.toBeInTheDocument();
    expect(screen.getByTestId('forgot-email')).toHaveValue('chris@example.com');
    expect(window.turnstile.render.mock.calls.length).toBeGreaterThan(renders);
  });

  it('shows the rate-limit sentence for a 429 instead of the done state', async () => {
    const user = userEvent.setup();
    AuthApi.requestPasswordReset.mockRejectedValue({
      response: { status: 429, data: { error: 'Too many attempts. Please wait a few minutes and try again.' } },
    });
    renderPage();

    await fillAndSubmit(user);

    expect(await screen.findByTestId('forgot-error'))
      .toHaveTextContent('Too many attempts. Please wait a few minutes and try again.');
    expect(screen.queryByTestId('forgot-done')).not.toBeInTheDocument();
  });

  it('falls back to a generic sentence when the failure carries none (a network error)', async () => {
    const user = userEvent.setup();
    AuthApi.requestPasswordReset.mockRejectedValue(new Error('Network Error'));
    renderPage();

    await fillAndSubmit(user);

    expect(await screen.findByTestId('forgot-error')).toHaveTextContent('Something went wrong. Please try again.');
  });

  it('keeps submit disabled until the Turnstile check has passed, and sends nothing', async () => {
    window.turnstile = { render: vi.fn(() => 'widget-id'), remove: vi.fn() }; // never calls back
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByTestId('forgot-email'), 'chris@example.com');

    expect(screen.getByTestId('forgot-submit')).toBeDisabled();
    expect(AuthApi.requestPasswordReset).not.toHaveBeenCalled();
  });

  it('disables the field and the button, and says "Sending…", while the request is in flight', async () => {
    const user = userEvent.setup();
    let release;
    AuthApi.requestPasswordReset.mockReturnValue(new Promise((resolve) => { release = () => resolve({ message: 'x' }); }));
    renderPage();

    await fillAndSubmit(user);

    await waitFor(() => expect(screen.getByTestId('forgot-submit')).toHaveTextContent('Sending…'));
    expect(screen.getByTestId('forgot-submit')).toBeDisabled();
    expect(screen.getByTestId('forgot-email')).toBeDisabled();
    release();
    await screen.findByTestId('forgot-done');
  });

  describe('when the Turnstile script cannot load', () => {
    beforeEach(() => {
      window.turnstile = {
        render: vi.fn(() => { throw new Error('Domain not whitelisted'); }),
        remove: vi.fn(),
      };
    });

    it('says so in the fallback sentence, hides the widget and enables submit', () => {
      renderPage();

      expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
      expect(screen.getByTestId('turnstile-unavailable'))
        .toHaveTextContent("Security check unavailable — we'll verify your sign-in another way.");
      expect(screen.getByTestId('forgot-submit')).toBeEnabled();
    });

    it('submits with an empty token and lets the server apply its bounded fallback', async () => {
      const user = userEvent.setup();
      AuthApi.requestPasswordReset.mockResolvedValue({ message: 'x' });
      renderPage();

      await fillAndSubmit(user);

      await waitFor(() => {
        expect(AuthApi.requestPasswordReset).toHaveBeenCalledWith('chris@example.com', '');
      });
      expect(await screen.findByTestId('forgot-done')).toBeInTheDocument();
    });
  });
});
