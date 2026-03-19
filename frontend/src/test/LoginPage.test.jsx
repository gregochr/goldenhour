import { render, screen, waitFor } from '@testing-library/react';
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
  beforeEach(() => {
    vi.clearAllMocks();
    installTurnstileMock();
  });

  afterEach(() => {
    delete window.turnstile;
  });

  it('renders the login form with username and password fields', () => {
    renderWithAuth(<LoginPage />);
    expect(screen.getByText('PhotoCast')).toBeInTheDocument();
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
    vi.spyOn(AuthApi, 'login').mockReturnValue(new Promise(() => {})); // never resolves
    renderWithAuth(<LoginPage />);

    await user.type(screen.getByTestId('login-username'), 'testuser');
    await user.type(screen.getByTestId('login-password'), 'password');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled();
    });
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

  describe('when Turnstile fails to render (e.g. domain not whitelisted)', () => {
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
});
