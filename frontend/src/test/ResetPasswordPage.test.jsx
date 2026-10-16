import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ResetPasswordPage from '../components/auth/ResetPasswordPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');

const SESSION = {
  accessToken: 'new-access', refreshToken: 'new-refresh', role: 'PRO_USER', username: 'chris',
  refreshExpiresAt: '2026-12-01T00:00:00Z', marketingEmailOptIn: false, passwordChangeRequired: false,
};
const GOOD = 'Abcdef1!';

const renderPage = (props = {}) => {
  const onDone = vi.fn();
  const onRequestNewLink = vi.fn();
  render(
    <AuthProvider>
      <ResetPasswordPage token="reset-tok" onDone={onDone} onRequestNewLink={onRequestNewLink} {...props} />
    </AuthProvider>,
  );
  return { onDone, onRequestNewLink };
};

async function fill(user, password = GOOD, confirm = password) {
  await user.type(screen.getByTestId('reset-new-password'), password);
  await user.type(screen.getByTestId('reset-confirm-password'), confirm);
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.history.replaceState({}, '', '/reset?token=reset-tok');
  });

  afterEach(() => {
    delete window.turnstile;
    localStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  it('renders the spec copy and makes no request on mount (a prefetch cannot spend the token)', () => {
    renderPage();

    expect(screen.getByRole('heading', { level: 2, name: 'Set a new password' })).toBeInTheDocument();
    expect(screen.getByText('Choose a new password for your account.')).toBeInTheDocument();
    expect(screen.getByLabelText('New password')).toBe(screen.getByTestId('reset-new-password'));
    expect(screen.getByLabelText('Confirm new password')).toBe(screen.getByTestId('reset-confirm-password'));
    expect(screen.getByTestId('reset-submit')).toHaveTextContent('Set new password');
    expect(AuthApi.resetPassword).not.toHaveBeenCalled();
    expect(AuthApi.requestPasswordReset).not.toHaveBeenCalled();
  });

  it('uses new-password autocomplete, the shared .field class and two distinguishable eyes', () => {
    renderPage();

    ['reset-new-password', 'reset-confirm-password'].forEach((id) => {
      const input = screen.getByTestId(id);
      expect(input).toHaveAttribute('autocomplete', 'new-password');
      expect(input.className).toBe('field pr-10');
      expect(input).toHaveAttribute('type', 'password');
    });
    expect(screen.getByRole('button', { name: 'Show new password' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show password confirmation' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Show password' })).not.toBeInTheDocument();
  });

  it('keeps submit disabled until every checklist rule passes, rule by rule', async () => {
    const user = userEvent.setup();
    renderPage();
    const submit = screen.getByTestId('reset-submit');
    const rule = (label) => screen.getByText(label).closest('li');
    expect(submit).toBeDisabled();
    expect(screen.getByTestId('reset-checklist').querySelectorAll('li')).toHaveLength(6);

    await user.type(screen.getByTestId('reset-new-password'), 'abc');
    expect(rule('At least 8 characters')).toHaveAttribute('data-ok', 'false');
    expect(rule('One lowercase letter')).toHaveAttribute('data-ok', 'true');
    expect(rule('One uppercase letter')).toHaveAttribute('data-ok', 'false');
    expect(rule('One number')).toHaveAttribute('data-ok', 'false');
    expect(rule('One special character')).toHaveAttribute('data-ok', 'false');
    expect(submit).toBeDisabled();

    await user.type(screen.getByTestId('reset-new-password'), 'DEF1!');
    ['At least 8 characters', 'One uppercase letter', 'One lowercase letter', 'One number', 'One special character']
      .forEach((l) => expect(rule(l)).toHaveAttribute('data-ok', 'true'));
    expect(rule('Passwords match')).toHaveAttribute('data-ok', 'false');
    expect(submit).toBeDisabled();

    await user.type(screen.getByTestId('reset-confirm-password'), 'abcDEF1');
    expect(submit).toBeDisabled();
    await user.type(screen.getByTestId('reset-confirm-password'), '!');
    expect(rule('Passwords match')).toHaveAttribute('data-ok', 'true');
    expect(submit).toBeEnabled();
  });

  it('a mismatch keeps submit disabled even when the first password passes every other rule', async () => {
    const user = userEvent.setup();
    renderPage();

    await fill(user, GOOD, 'Abcdef1?');

    expect(screen.getByTestId('reset-submit')).toBeDisabled();
  });

  it('on success: posts the URL token and password, signs in, replaces the URL with / and calls onDone', async () => {
    const user = userEvent.setup();
    AuthApi.resetPassword.mockResolvedValue(SESSION);
    const { onDone, onRequestNewLink } = renderPage();

    await fill(user);
    await user.click(screen.getByTestId('reset-submit'));

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(AuthApi.resetPassword).toHaveBeenCalledOnce();
    expect(AuthApi.resetPassword).toHaveBeenCalledWith('reset-tok', GOOD);
    expect(localStorage.getItem('goldenhour_token')).toBe('new-access');
    expect(localStorage.getItem('goldenhour_refresh')).toBe('new-refresh');
    expect(localStorage.getItem('goldenhour_role')).toBe('PRO_USER');
    expect(localStorage.getItem('goldenhour_username')).toBe('chris');
    expect(localStorage.getItem('goldenhour_must_change')).toBe('false');
    expect(window.location.pathname).toBe('/');
    expect(window.location.search).toBe('');
    expect(onRequestNewLink).not.toHaveBeenCalled();
  });

  it.each([
    'This reset link is invalid',
    'This reset link has already been used',
    'This reset link has expired',
  ])('shows "%s" with a Request a new link button, and stores no session', async (sentence) => {
    const user = userEvent.setup();
    AuthApi.resetPassword.mockRejectedValue({ response: { status: 400, data: { error: sentence } } });
    const { onDone } = renderPage();

    await fill(user);
    await user.click(screen.getByTestId('reset-submit'));

    const alert = await screen.findByTestId('reset-error');
    expect(alert).toHaveTextContent(sentence);
    expect(alert).toHaveAttribute('role', 'alert');
    expect(screen.getByTestId('reset-new-link')).toHaveTextContent('Request a new link');
    // The submit button that held focus is gone; the one next step takes it.
    expect(screen.getByTestId('reset-new-link')).toHaveFocus();
    // A dead link cannot succeed, so the form goes with it.
    expect(screen.queryByTestId('reset-new-password')).not.toBeInTheDocument();
    expect(screen.queryByTestId('reset-submit')).not.toBeInTheDocument();
    expect(onDone).not.toHaveBeenCalled();
    expect(localStorage.getItem('goldenhour_token')).toBeNull();
    // The URL is left alone until the reader chooses.
    expect(window.location.pathname).toBe('/reset');
  });

  it('Request a new link replaces the URL with / and calls onRequestNewLink, not onDone', async () => {
    const user = userEvent.setup();
    AuthApi.resetPassword.mockRejectedValue({
      response: { status: 400, data: { error: 'This reset link has expired' } },
    });
    const { onDone, onRequestNewLink } = renderPage();
    await fill(user);
    await user.click(screen.getByTestId('reset-submit'));

    await user.click(await screen.findByTestId('reset-new-link'));

    expect(onRequestNewLink).toHaveBeenCalledOnce();
    expect(onDone).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/');
    expect(window.location.search).toBe('');
  });

  it('a password the server refuses (not a link error) keeps the form, offers no new link and lets the reader retry', async () => {
    const user = userEvent.setup();
    AuthApi.resetPassword.mockRejectedValueOnce({
      response: { status: 400, data: { error: 'Password must not be a common password' } },
    });
    AuthApi.resetPassword.mockResolvedValueOnce(SESSION);
    const { onDone } = renderPage();
    await fill(user);

    await user.click(screen.getByTestId('reset-submit'));

    expect(await screen.findByTestId('reset-error')).toHaveTextContent('Password must not be a common password');
    expect(screen.queryByTestId('reset-new-link')).not.toBeInTheDocument();
    expect(screen.getByTestId('reset-new-password')).toHaveValue(GOOD);
    expect(window.location.pathname).toBe('/reset');
    expect(screen.getByTestId('reset-submit')).toBeEnabled();

    await user.click(screen.getByTestId('reset-submit'));
    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(AuthApi.resetPassword).toHaveBeenCalledTimes(2);
  });

  it('falls back to a generic sentence on a network failure and keeps the form', async () => {
    const user = userEvent.setup();
    AuthApi.resetPassword.mockRejectedValue(new Error('Network Error'));
    renderPage();
    await fill(user);

    await user.click(screen.getByTestId('reset-submit'));

    expect(await screen.findByTestId('reset-error')).toHaveTextContent('Failed to set password. Please try again.');
    expect(screen.queryByTestId('reset-new-link')).not.toBeInTheDocument();
  });

  it('disables the fields and says "Saving…" while the request is in flight, and submits once', async () => {
    const user = userEvent.setup();
    let release;
    AuthApi.resetPassword.mockReturnValue(new Promise((resolve) => { release = () => resolve(SESSION); }));
    const { onDone } = renderPage();
    await fill(user);

    await user.click(screen.getByTestId('reset-submit'));

    await waitFor(() => expect(screen.getByTestId('reset-submit')).toHaveTextContent('Saving…'));
    expect(screen.getByTestId('reset-submit')).toBeDisabled();
    expect(screen.getByTestId('reset-new-password')).toBeDisabled();
    expect(AuthApi.resetPassword).toHaveBeenCalledOnce();
    release();
    await waitFor(() => expect(onDone).toHaveBeenCalled());
  });

  it('asks for no Turnstile token at all (the emailed token is the proof)', () => {
    window.turnstile = { render: vi.fn(), remove: vi.fn() };
    renderPage();

    expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
    expect(window.turnstile.render).not.toHaveBeenCalled();
  });
});
