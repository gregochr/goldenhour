import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import ChangePasswordPage from '../components/ChangePasswordPage.jsx';
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

const renderWithAuth = (component) =>
  render(<AuthProvider>{component}</AuthProvider>);

describe('ChangePasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  it('renders the change password form with current, new and confirm password fields', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByText(/change your password/i)).toBeInTheDocument();
    expect(screen.getByTestId('cp-current-password')).toBeInTheDocument();
    expect(screen.getByTestId('cp-new-password')).toBeInTheDocument();
    expect(screen.getByTestId('cp-confirm-password')).toBeInTheDocument();
  });

  it('shows password complexity requirements', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(screen.getByText(/uppercase letter/i)).toBeInTheDocument();
    expect(screen.getByText(/number/i)).toBeInTheDocument();
    expect(screen.getByText(/special character/i)).toBeInTheDocument();
  });

  it('sits in the auth shell with an h2 heading under the lockup h1', () => {
    renderWithAuth(<ChangePasswordPage />);

    expect(screen.getByTestId('auth-shell')).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 2, name: 'Change your password' })).toBeInTheDocument();
    expect(screen.getByText('You must set a new password before accessing the app.'))
      .toHaveClass('text-plex-text-secondary');
  });

  it('asks for the current password above the new one, with the temporary-password copy', () => {
    renderWithAuth(<ChangePasswordPage />);
    const current = screen.getByLabelText('Current password');

    expect(current).toBe(screen.getByTestId('cp-current-password'));
    expect(current).toHaveAttribute('id', 'cp-current');
    expect(current).toHaveAttribute('autocomplete', 'current-password');
    expect(screen.getByTestId('cp-new-password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByTestId('cp-confirm-password')).toHaveAttribute('autocomplete', 'new-password');
    expect(screen.getByTestId('cp-current-hint')).toHaveTextContent(
      'Enter the temporary password you signed in with, then choose a new one.',
    );
    const order = [...document.querySelectorAll('input')].map((i) => i.dataset.testid);
    expect(order).toEqual(['cp-current-password', 'cp-new-password', 'cp-confirm-password']);
  });

  it('keeps the current password out of the checklist: six items, all still unmet when only it is typed', () => {
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-current-password'), { target: { value: 'Temp-Pass1!' } });

    const items = [...screen.getByTestId('cp-checklist').querySelectorAll('li')];
    expect(items).toHaveLength(6);
    expect(items.map((li) => li.textContent.charAt(0))).toEqual(['○', '○', '○', '○', '○', '○']);
  });

  it('uses the shared checklist: ○ for unmet, ✓ in plex-ok for met, live as the user types', () => {
    renderWithAuth(<ChangePasswordPage />);
    const items = () => [...screen.getByTestId('cp-checklist').querySelectorAll('li')];

    expect(items().map((li) => li.textContent.charAt(0))).toEqual(['○', '○', '○', '○', '○', '○']);

    fireEvent.change(screen.getByTestId('cp-new-password'), { target: { value: 'Abcdef1!' } });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), { target: { value: 'Abcdef1!' } });

    expect(items().map((li) => li.textContent.charAt(0))).toEqual(['✓', '✓', '✓', '✓', '✓', '✓']);
    items().forEach((li) => expect(li).toHaveClass('text-plex-ok'));
  });

  it('gives each password field its own, distinctly named show/hide eye and the .field class', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByRole('button', { name: 'Show current password' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show password confirmation' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Show new password' }));

    expect(screen.getByTestId('cp-new-password')).toHaveAttribute('type', 'text');
    expect(screen.getByTestId('cp-confirm-password')).toHaveAttribute('type', 'password');
    expect(screen.getByTestId('cp-current-password')).toHaveAttribute('type', 'password');
    expect(screen.getByTestId('cp-current-password')).toHaveClass('field');
    expect(screen.getByTestId('cp-new-password')).toHaveClass('field');
    expect(screen.getByTestId('cp-confirm-password')).toHaveClass('field');
  });

  it('shows a failed change in the error token', async () => {
    vi.spyOn(AuthApi, 'changePassword').mockRejectedValue({ response: { data: { error: 'Too weak' } } });
    renderWithAuth(<ChangePasswordPage />);
    fireEvent.change(screen.getByTestId('cp-current-password'), { target: { value: 'Temp-Pass1!' } });
    fireEvent.change(screen.getByTestId('cp-new-password'), { target: { value: 'Abcdef1!' } });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), { target: { value: 'Abcdef1!' } });

    fireEvent.click(screen.getByTestId('cp-submit'));

    const alert = await screen.findByTestId('cp-error');
    expect(alert).toHaveTextContent('Too weak');
    expect(alert).toHaveClass('text-plex-error');
  });

  it('keeps Sign out a secondary-ink text button', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByRole('button', { name: 'Sign out' })).toHaveClass('text-plex-text-secondary');
  });

  it('disables submit button until all requirements are met', () => {
    renderWithAuth(<ChangePasswordPage />);
    const submitButton = screen.getByTestId('cp-submit');
    const newPasswordInput = screen.getByTestId('cp-new-password');

    // Initially disabled
    expect(submitButton).toBeDisabled();

    // Type weak password
    fireEvent.change(newPasswordInput, {
      target: { value: '123' },
    });
    expect(submitButton).toBeDisabled();

    // Type complex password
    fireEvent.change(newPasswordInput, {
      target: { value: 'Complex123!' },
    });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), {
      target: { value: 'Complex123!' },
    });

    // The new password alone is not enough: the current one must be typed too
    expect(submitButton).toBeDisabled();
    fireEvent.change(screen.getByTestId('cp-current-password'), { target: { value: 'Temp-Pass1!' } });

    // Should be enabled once all requirements met and confirmation matches
    expect(submitButton).not.toBeDisabled();
  });

  it('calls changePassword API with current, new and turnstile token when form is submitted', async () => {
    vi.spyOn(AuthApi, 'changePassword').mockResolvedValue({});
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-current-password'), {
      target: { value: 'Temp-Pass1!' },
    });
    fireEvent.change(screen.getByTestId('cp-new-password'), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), {
      target: { value: 'NewPass123!' },
    });

    fireEvent.click(screen.getByTestId('cp-submit'));

    await waitFor(() => {
      // The two password values are not swapped: current first, new second.
      expect(AuthApi.changePassword).toHaveBeenCalledWith('Temp-Pass1!', 'NewPass123!', 'test-turnstile-token');
    });
  });

  it('shows a wrong-current-password 400 by its error text after exactly one POST', async () => {
    vi.spyOn(AuthApi, 'changePassword').mockRejectedValue({
      response: { status: 400, data: { error: 'Current password is incorrect' } },
    });
    renderWithAuth(<ChangePasswordPage />);
    fireEvent.change(screen.getByTestId('cp-current-password'), { target: { value: 'Wrong-Pass1!' } });
    fireEvent.change(screen.getByTestId('cp-new-password'), { target: { value: 'NewPass123!' } });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), { target: { value: 'NewPass123!' } });

    fireEvent.click(screen.getByTestId('cp-submit'));

    const alert = await screen.findByTestId('cp-error');
    expect(alert).toHaveTextContent('Current password is incorrect');
    expect(AuthApi.changePassword).toHaveBeenCalledTimes(1);
    expect(AuthApi.changePassword).toHaveBeenCalledWith('Wrong-Pass1!', 'NewPass123!', 'test-turnstile-token');
  });

  it('shows error message on API failure', async () => {
    vi.spyOn(AuthApi, 'changePassword').mockRejectedValue({
      response: { data: { message: 'Failed to update password.' } },
    });
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-current-password'), {
      target: { value: 'Temp-Pass1!' },
    });
    fireEvent.change(screen.getByTestId('cp-new-password'), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), {
      target: { value: 'NewPass123!' },
    });

    fireEvent.click(screen.getByTestId('cp-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('cp-error')).toBeInTheDocument();
    });
  });

  it('prevents form submission if passwords do not match', () => {
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-new-password'), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), {
      target: { value: 'DifferentPass123!' },
    });

    const submitButton = screen.getByTestId('cp-submit');
    expect(submitButton).toBeDisabled();
  });

  it('renders the Turnstile widget', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByTestId('turnstile-widget')).toBeInTheDocument();
    expect(window.turnstile.render).toHaveBeenCalled();
  });
});

/**
 * R2b (login-registration-plan.md §2 #4 frontend paragraph): when the Turnstile script cannot
 * load, the first-login password gate takes the sign-in page's fallback — the widget unmounts, the
 * line says so, and submit is enabled with an empty token for the server's bounded fallback.
 */
describe('ChangePasswordPage Turnstile fallback', () => {
  const FALLBACK = "Security check unavailable — we'll verify your sign-in another way.";

  beforeEach(() => {
    vi.clearAllMocks();
    window.turnstile = {
      render: vi.fn(() => { throw new Error('Domain not whitelisted'); }),
      remove: vi.fn(),
    };
  });

  afterEach(() => {
    delete window.turnstile;
  });

  const fillValidPasswords = () => {
    fireEvent.change(screen.getByTestId('cp-current-password'), { target: { value: 'Temp-Pass1!' } });
    fireEvent.change(screen.getByTestId('cp-new-password'), { target: { value: 'NewPass123!' } });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), { target: { value: 'NewPass123!' } });
  };

  it('unmounts the widget and shows the sign-in sentence in secondary ink', () => {
    renderWithAuth(<ChangePasswordPage />);

    const line = screen.getByTestId('turnstile-unavailable');
    expect(line).toHaveTextContent(FALLBACK);
    expect(line).toHaveClass('text-xs', 'text-plex-text-secondary');
    expect(line).toHaveAttribute('role', 'status');
    expect(line.className).not.toMatch(/amber|red/);
    expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
    expect(screen.queryByText(/couldn't load/)).not.toBeInTheDocument();
  });

  it('enables submit once the password rules pass, with no token', () => {
    renderWithAuth(<ChangePasswordPage />);
    const submit = screen.getByTestId('cp-submit');

    expect(submit).toBeDisabled();
    fillValidPasswords();
    expect(submit).toBeEnabled();
  });

  it('still refuses a password that fails the rules', () => {
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-new-password'), { target: { value: 'weak' } });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), { target: { value: 'weak' } });

    expect(screen.getByTestId('cp-submit')).toBeDisabled();
  });

  it('submits changePassword with an empty token', async () => {
    vi.spyOn(AuthApi, 'changePassword').mockResolvedValue({});
    renderWithAuth(<ChangePasswordPage />);
    fillValidPasswords();

    fireEvent.click(screen.getByTestId('cp-submit'));

    await waitFor(() => expect(AuthApi.changePassword).toHaveBeenCalledTimes(1));
    expect(AuthApi.changePassword).toHaveBeenCalledWith('Temp-Pass1!', 'NewPass123!', '');
  });

  it("renders the server's 429 sentence in the error slot and keeps the form usable", async () => {
    vi.spyOn(AuthApi, 'changePassword').mockRejectedValue({
      response: { status: 429, data: { error: 'Too many attempts. Please wait a few minutes and try again.' } },
    });
    renderWithAuth(<ChangePasswordPage />);
    fillValidPasswords();

    fireEvent.click(screen.getByTestId('cp-submit'));

    const alert = await screen.findByTestId('cp-error');
    expect(alert).toHaveTextContent('Too many attempts. Please wait a few minutes and try again.');
    expect(alert).toHaveClass('text-plex-error');
    expect(screen.getByTestId('turnstile-unavailable')).toBeInTheDocument();
    expect(screen.getByTestId('cp-submit')).toBeEnabled();
  });

  it('does not show the fallback line, and still requires a token, while the widget works', () => {
    window.turnstile = { render: vi.fn(() => 'widget-id-1'), remove: vi.fn() };
    renderWithAuth(<ChangePasswordPage />);
    fillValidPasswords();

    expect(screen.queryByTestId('turnstile-unavailable')).not.toBeInTheDocument();
    expect(screen.getByTestId('turnstile-widget')).toBeInTheDocument();
    expect(screen.getByTestId('cp-submit')).toBeDisabled();
  });
});
