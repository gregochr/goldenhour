import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import LoginPage from '../components/LoginPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');

function installTurnstileMock() {
  window.turnstile = {
    render: vi.fn((_container, opts) => {
      // Auto-solve: immediately invoke callback with a dummy token
      if (opts.callback) opts.callback('test-turnstile-token');
      return 'widget-id-1';
    }),
    remove: vi.fn(),
    reset: vi.fn(),
  };
}

const renderWithAuth = (component) =>
  render(<AuthProvider>{component}</AuthProvider>);

describe('LoginPage', () => {
  // React 19 entangles async actions process-wide: while one is in flight, the state a LATER
  // useActionState returns waits on it. So a login promise that never settles does not just affect
  // its own test — every subsequent submit in this file stays stuck on "Signing in…" and the error
  // it returns never commits. That is what made "shows error message when login fails" fail
  // whenever the shuffler put it after the loading test. Any test that leaves a login pending
  // parks its resolver here so the action scope is released even when an assertion throws first.
  let releasePendingLogin = null;

  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
    releasePendingLogin = null;
  });

  afterEach(() => {
    releasePendingLogin?.();
    delete window.turnstile;
  });

  it('renders the login form with username and password fields', () => {
    renderWithAuth(<LoginPage />);
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
    expect(screen.getByTestId('login-username')).toBeInTheDocument();
    expect(screen.getByTestId('login-password')).toBeInTheDocument();
  });

  it('shows password when toggle is clicked', async () => {
    const user = userEvent.setup();
    renderWithAuth(<LoginPage />);
    const passwordInput = screen.getByTestId('login-password');
    const toggleButton = screen.getByRole('button', { name: /show|hide/i });

    expect(passwordInput).toHaveAttribute('type', 'password');
    await user.click(toggleButton);
    expect(passwordInput).toHaveAttribute('type', 'text');
  });

  it('calls login with username, password, and turnstile token on form submit', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'login').mockResolvedValue(undefined);
    renderWithAuth(<LoginPage />);

    await user.type(screen.getByTestId('login-username'), 'testuser');
    await user.type(screen.getByTestId('login-password'), 'password123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(AuthApi.login).toHaveBeenCalledWith('testuser', 'password123', 'test-turnstile-token');
    });
  });

  it('shows error message when login fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'login').mockRejectedValue(new Error('Invalid credentials'));
    renderWithAuth(<LoginPage />);

    await user.type(screen.getByTestId('login-username'), 'baduser');
    await user.type(screen.getByTestId('login-password'), 'wrongpass');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByText('Invalid username or password.')).toBeInTheDocument();
    });
  });

  it('disables submit button while loading', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'login').mockReturnValue(new Promise((resolve) => {
      releasePendingLogin = () => resolve(undefined);
    }));
    renderWithAuth(<LoginPage />);

    await user.type(screen.getByTestId('login-username'), 'testuser');
    await user.type(screen.getByTestId('login-password'), 'password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled();
    });

    // Let the login finish before the test ends — see the note on releasePendingLogin above.
    releasePendingLogin();
    await screen.findByRole('button', { name: 'Sign in' });
  });

  it('renders the Turnstile widget', () => {
    renderWithAuth(<LoginPage />);
    expect(screen.getByTestId('turnstile-widget')).toBeInTheDocument();
    expect(window.turnstile.render).toHaveBeenCalled();
  });

  it('submit button is disabled when Turnstile has not yet verified', () => {
    window.turnstile = {
      render: vi.fn(() => 'widget-id'), // does not call callback — token stays empty
      remove: vi.fn(),
    };
    renderWithAuth(<LoginPage />);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();
  });

  it('shows session expired message after goldenhour:session-expired event', () => {
    renderWithAuth(<LoginPage />);
    expect(screen.queryByTestId('session-expired-message')).not.toBeInTheDocument();

    act(() => {
      window.dispatchEvent(new Event('goldenhour:session-expired'));
    });

    expect(screen.getByTestId('session-expired-message')).toBeInTheDocument();
    expect(screen.getByText('Your session expired. Please sign in again.')).toBeInTheDocument();
  });

  describe('visual foundation (login-registration-plan.md §2 #9)', () => {
    it('sits in the auth shell: one h1 (the lockup) and an h2 "Sign in" inside the card', () => {
      renderWithAuth(<LoginPage />);

      expect(screen.getByTestId('auth-shell')).toBeInTheDocument();
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      const heading = screen.getByRole('heading', { level: 2, name: 'Sign in' });
      expect(heading.closest('form')).toHaveClass('card');
    });

    it('draws the session-expired line INSIDE the card, ABOVE the heading, left-aligned in secondary ink', () => {
      renderWithAuth(<LoginPage />);
      act(() => {
        window.dispatchEvent(new Event('goldenhour:session-expired'));
      });

      const line = screen.getByTestId('session-expired-message');
      const heading = screen.getByRole('heading', { level: 2, name: 'Sign in' });
      expect(line.closest('form')).toBe(heading.closest('form'));
      expect(line.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(line).toHaveClass('text-plex-text-secondary');
      expect(line.className).not.toMatch(/amber|text-center/);
    });

    it('gives both inputs the shared .field class and the password the eye', () => {
      renderWithAuth(<LoginPage />);

      expect(screen.getByTestId('login-username')).toHaveClass('field');
      expect(screen.getByTestId('login-password')).toHaveClass('field', 'pr-10');
      expect(screen.getByTestId('login-password')).toHaveAttribute('name', 'password');
      expect(screen.getByTestId('login-password')).toHaveAttribute('autocomplete', 'current-password');
    });

    it('shows a failed sign-in in the error token', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'login').mockRejectedValue(new Error('x'));
      renderWithAuth(<LoginPage />);

      await user.type(screen.getByTestId('login-username'), 'u');
      await user.type(screen.getByTestId('login-password'), 'p');
      await user.click(screen.getByTestId('login-submit'));

      const alert = await screen.findByTestId('login-error');
      expect(alert).toHaveClass('text-plex-error');
      expect(alert).toHaveAttribute('role', 'alert');
    });

    it('keeps the register link a secondary-ink text button that calls onRegister', async () => {
      const user = userEvent.setup();
      const onRegister = vi.fn();
      renderWithAuth(<LoginPage onRegister={onRegister} />);

      const link = screen.getByTestId('login-register-link');
      expect(link).toHaveClass('text-plex-text-secondary');
      await user.click(link);
      expect(onRegister).toHaveBeenCalledOnce();
    });
  });

  describe('when Turnstile fails to render (e.g. domain not whitelisted)', () => {
    it('words the fallback line in secondary ink, not an amber warning', () => {
      renderWithAuth(<LoginPage />);

      const line = screen.getByTestId('turnstile-unavailable');
      expect(line).toHaveTextContent("Security check unavailable — we'll verify your sign-in another way.");
      expect(line).toHaveClass('text-xs', 'text-plex-text-secondary');
      expect(line.className).not.toMatch(/amber/);
    });

    beforeEach(() => {
      window.turnstile = {
        render: vi.fn(() => { throw new Error('Domain not whitelisted'); }),
        remove: vi.fn(),
      };
    });

    it('hides the Turnstile widget and shows a fallback message', () => {
      renderWithAuth(<LoginPage />);
      expect(screen.queryByTestId('turnstile-widget')).not.toBeInTheDocument();
      expect(screen.getByTestId('turnstile-unavailable')).toBeInTheDocument();
    });

    it('enables the submit button so the user is not stuck', () => {
      renderWithAuth(<LoginPage />);
      expect(screen.getByRole('button', { name: 'Sign in' })).not.toBeDisabled();
    });

    it('submits with an empty token and lets the backend respond', async () => {
      vi.spyOn(AuthApi, 'login').mockResolvedValue(undefined);
      const user = userEvent.setup();
      renderWithAuth(<LoginPage />);

      await user.type(screen.getByTestId('login-username'), 'testuser');
      await user.type(screen.getByTestId('login-password'), 'password123');
      await user.click(screen.getByRole('button', { name: 'Sign in' }));

      await waitFor(() => {
        expect(AuthApi.login).toHaveBeenCalledWith('testuser', 'password123', '');
      });
    });
  });

  describe('sign-in by username or email, and the forgot-password link (login-registration-plan.md §2 #10, #12)', () => {
    it('labels the first field "Username or email", keeping its id, test id, name and autocomplete', () => {
      renderWithAuth(<LoginPage />);

      const input = screen.getByLabelText('Username or email');
      expect(input).toBe(screen.getByTestId('login-username'));
      expect(input).toHaveAttribute('id', 'login-username');
      expect(input).toHaveAttribute('name', 'username');
      expect(input).toHaveAttribute('autocomplete', 'username');
      expect(screen.queryByText('Username')).not.toBeInTheDocument();
    });

    it('sends an email address on the wire field that is still called username', async () => {
      const user = userEvent.setup();
      vi.spyOn(AuthApi, 'login').mockResolvedValue(undefined);
      renderWithAuth(<LoginPage />);

      await user.type(screen.getByTestId('login-username'), 'Chris@Example.com');
      await user.type(screen.getByTestId('login-password'), 'password123');
      await user.click(screen.getByTestId('login-submit'));

      await waitFor(() => {
        expect(AuthApi.login).toHaveBeenCalledWith('Chris@Example.com', 'password123', 'test-turnstile-token');
      });
    });

    it('draws "Forgot password?" on the password label\'s row, right of it, in the gold 12px link style', () => {
      renderWithAuth(<LoginPage onForgotPassword={vi.fn()} />);

      const link = screen.getByTestId('login-forgot-link');
      const label = screen.getByText('Password', { selector: 'label' });
      expect(link).toHaveTextContent('Forgot password?');
      expect(link).toHaveClass('text-xs', 'text-plex-gold');
      expect(link).toHaveAttribute('type', 'button');
      // One flex row holds both, label first.
      expect(link.parentElement).toBe(label.parentElement);
      expect(label.parentElement).toHaveClass('flex', 'justify-between', 'items-baseline');
      expect(label.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('calls onForgotPassword once on press and does not submit the form', async () => {
      const user = userEvent.setup();
      const onForgotPassword = vi.fn();
      vi.spyOn(AuthApi, 'login').mockResolvedValue(undefined);
      renderWithAuth(<LoginPage onForgotPassword={onForgotPassword} />);

      await user.type(screen.getByTestId('login-username'), 'u');
      await user.type(screen.getByTestId('login-password'), 'p');
      await user.click(screen.getByTestId('login-forgot-link'));

      expect(onForgotPassword).toHaveBeenCalledOnce();
      expect(AuthApi.login).not.toHaveBeenCalled();
    });

    it('draws no link when the gate hands in no handler', () => {
      renderWithAuth(<LoginPage />);

      expect(screen.queryByTestId('login-forgot-link')).not.toBeInTheDocument();
      expect(screen.queryByText('Forgot password?')).not.toBeInTheDocument();
    });

    it('keeps the link usable and submit enabled when the Turnstile script cannot load', () => {
      window.turnstile = {
        render: vi.fn(() => { throw new Error('Domain not whitelisted'); }),
        remove: vi.fn(),
      };
      renderWithAuth(<LoginPage onForgotPassword={vi.fn()} />);

      expect(screen.getByTestId('login-forgot-link')).toBeEnabled();
      expect(screen.getByTestId('login-submit')).toBeEnabled();
    });
  });
});
