import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import UserEditAccount from '../components/users/UserEditAccount.jsx';
import { updateUserEmail, updateUserRole } from '../api/userApi.js';

vi.mock('../api/userApi.js', () => ({
  updateUserEmail: vi.fn(),
  updateUserRole: vi.fn(),
}));

function makeUser(overrides = {}) {
  return { id: 7, username: 'tom_h', email: 'tom@example.com', role: 'LITE_USER', enabled: true, ...overrides };
}

function renderEdit(userOverrides = {}) {
  const handlers = {
    onClose: vi.fn(), onCancel: vi.fn(), onSaved: vi.fn(), onPartial: vi.fn().mockResolvedValue(undefined),
  };
  render(<UserEditAccount user={makeUser(userOverrides)} pickCount={2} limit={3} {...handlers} />);
  return handlers;
}

function setEmail(value) {
  fireEvent.change(screen.getByTestId('edit-email-7'), { target: { value } });
}

describe('UserEditAccount — edge branches', () => {
  beforeEach(() => vi.clearAllMocks());

  it('starts with an empty email box for an account that has no email, and rejects saving it as required', async () => {
    renderEdit({ email: null });

    expect(screen.getByTestId('edit-email-7')).toHaveValue('');
    fireEvent.click(screen.getByTestId('save-edit-7'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Email address is required.');
    expect(updateUserEmail).not.toHaveBeenCalled();
  });

  it('rejects a malformed email without calling the server', async () => {
    renderEdit();
    setEmail('not-an-email');
    fireEvent.click(screen.getByTestId('save-edit-7'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Please enter a valid email address.');
    expect(updateUserEmail).not.toHaveBeenCalled();
  });

  it('saves a first email for an account that had none, and reports nothing when the server just says Updated', async () => {
    updateUserEmail.mockResolvedValue({ message: 'Updated' });
    const { onSaved } = renderEdit({ email: '' });
    setEmail('  new@example.com ');

    fireEvent.click(screen.getByTestId('save-edit-7'));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(null));
    expect(updateUserEmail).toHaveBeenCalledWith(7, 'new@example.com');
    expect(updateUserRole).not.toHaveBeenCalled();
  });

  it('passes on the server message when an email edit says something beyond Updated', async () => {
    updateUserEmail.mockResolvedValue({ message: 'The old verification link no longer works.' });
    const { onSaved } = renderEdit();
    setEmail('other@example.com');

    fireEvent.click(screen.getByTestId('save-edit-7'));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith('The old verification link no longer works.'));
  });

  it('treats an email response with no body as a plain save', async () => {
    updateUserEmail.mockResolvedValue(undefined);
    const { onSaved } = renderEdit();
    setEmail('other@example.com');

    fireEvent.click(screen.getByTestId('save-edit-7'));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(null));
  });

  it('saves only the role when the email is unchanged', async () => {
    updateUserRole.mockResolvedValue({});
    const { onSaved } = renderEdit();
    fireEvent.change(screen.getByTestId('edit-role-7'), { target: { value: 'PRO_USER' } });

    fireEvent.click(screen.getByTestId('save-edit-7'));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(null));
    expect(updateUserEmail).not.toHaveBeenCalled();
    expect(updateUserRole).toHaveBeenCalledWith(7, 'PRO_USER');
  });

  it('shows a generic error when a role-only save fails without a server message, and does not call onPartial', async () => {
    updateUserRole.mockRejectedValue(new Error('network'));
    const { onSaved, onPartial } = renderEdit();
    fireEvent.change(screen.getByTestId('edit-role-7'), { target: { value: 'PRO_USER' } });

    fireEvent.click(screen.getByTestId('save-edit-7'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to save changes.');
    expect(onPartial).not.toHaveBeenCalled();
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByTestId('save-edit-7')).toBeEnabled();
  });

  it('when the email saved but the role did not, refetches, keeps the email message and shows the role error', async () => {
    updateUserEmail.mockResolvedValue({ message: 'Verification re-sent.' });
    updateUserRole.mockRejectedValue({ response: { data: { error: 'Cannot demote the last admin.' } } });
    const { onSaved, onPartial } = renderEdit();
    setEmail('other@example.com');
    fireEvent.change(screen.getByTestId('edit-role-7'), { target: { value: 'PRO_USER' } });

    fireEvent.click(screen.getByTestId('save-edit-7'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot demote the last admin.');
    expect(onPartial).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('edit-notice')).toHaveTextContent('Verification re-sent.');
    expect(onSaved).not.toHaveBeenCalled();
  });
});
