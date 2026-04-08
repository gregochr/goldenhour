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
