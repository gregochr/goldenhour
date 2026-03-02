import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import LoginPage from '../components/LoginPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');

const renderWithAuth = (component) =>
  render(<AuthProvider>{component}</AuthProvider>);

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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

  it('calls login with username and password on form submit', async () => {
    const user = userEvent.setup();
    vi.spyOn(AuthApi, 'login').mockResolvedValue(undefined);
    renderWithAuth(<LoginPage />);

    await user.type(screen.getByTestId('login-username'), 'testuser');
    await user.type(screen.getByTestId('login-password'), 'password123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(AuthApi.login).toHaveBeenCalledWith('testuser', 'password123');
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
});
