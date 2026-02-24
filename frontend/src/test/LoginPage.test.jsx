import { render, screen, fireEvent, waitFor } from '@testing-library/react';
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
    expect(screen.getByText('🌅 Golden Hour')).toBeInTheDocument();
    expect(screen.getByTestId('login-username')).toBeInTheDocument();
    expect(screen.getByTestId('login-password')).toBeInTheDocument();
  });

  it('shows password when toggle is clicked', () => {
    renderWithAuth(<LoginPage />);
    const passwordInput = screen.getByTestId('login-password');
    const toggleButton = screen.getByRole('button', { name: /show|hide/i });

    expect(passwordInput).toHaveAttribute('type', 'password');
    fireEvent.click(toggleButton);
    expect(passwordInput).toHaveAttribute('type', 'text');
  });

  it('calls login with username and password on form submit', async () => {
    AuthApi.login = vi.fn().mockResolvedValue(undefined);
    renderWithAuth(<LoginPage />);

    fireEvent.change(screen.getByTestId('login-username'), {
      target: { value: 'testuser' },
    });
    fireEvent.change(screen.getByTestId('login-password'), {
      target: { value: 'password123' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(AuthApi.login).toHaveBeenCalledWith('testuser', 'password123');
    });
  });

  it('shows error message when login fails', async () => {
    AuthApi.login = vi.fn().mockRejectedValue(new Error('Invalid credentials'));
    renderWithAuth(<LoginPage />);

    fireEvent.change(screen.getByTestId('login-username'), {
      target: { value: 'baduser' },
    });
    fireEvent.change(screen.getByTestId('login-password'), {
      target: { value: 'wrongpass' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByText('Invalid username or password.')).toBeInTheDocument();
    });
  });

  it('disables submit button while loading', async () => {
    AuthApi.login = vi.fn(() => new Promise(() => {})); // never resolves
    renderWithAuth(<LoginPage />);

    fireEvent.change(screen.getByTestId('login-username'), {
      target: { value: 'testuser' },
    });
    fireEvent.change(screen.getByTestId('login-password'), {
      target: { value: 'password' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /signing in/i })).toBeDisabled();
    });
  });
});
