import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ChangePasswordPage from '../components/ChangePasswordPage.jsx';
import { AuthProvider } from '../context/AuthContext.jsx';
import * as AuthApi from '../api/authApi.js';

vi.mock('../api/authApi.js');

const renderWithAuth = (component) =>
  render(<AuthProvider>{component}</AuthProvider>);

describe('ChangePasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the change password form with new and confirm password fields', () => {
    renderWithAuth(<ChangePasswordPage />);
    expect(screen.getByText(/change your password/i)).toBeInTheDocument();
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

    // Should be enabled once all requirements met and confirmation matches
    expect(submitButton).not.toBeDisabled();
  });

  it('calls changePassword API when form is submitted', async () => {
    AuthApi.changePassword = vi.fn().mockResolvedValue({});
    renderWithAuth(<ChangePasswordPage />);

    fireEvent.change(screen.getByTestId('cp-new-password'), {
      target: { value: 'NewPass123!' },
    });
    fireEvent.change(screen.getByTestId('cp-confirm-password'), {
      target: { value: 'NewPass123!' },
    });

    fireEvent.click(screen.getByTestId('cp-submit'));

    await waitFor(() => {
      expect(AuthApi.changePassword).toHaveBeenCalledWith('NewPass123!');
    });
  });

  it('shows error message on API failure', async () => {
    AuthApi.changePassword = vi.fn().mockRejectedValue({
      response: { data: { message: 'Failed to update password.' } },
    });
    renderWithAuth(<ChangePasswordPage />);

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
});
