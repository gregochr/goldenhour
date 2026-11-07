import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import UserReaderPanel from '../components/users/UserReaderPanel.jsx';
import {
  getUserSubRegions, resetUserPassword, updateUserEnabled, deleteUser, resendVerification, clearUserSubRegionLock,
} from '../api/userApi.js';

vi.mock('../api/userApi.js', () => ({
  getUserSubRegions: vi.fn(),
  resetUserPassword: vi.fn(),
  updateUserEmail: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserEnabled: vi.fn(),
  deleteUser: vi.fn(),
  resendVerification: vi.fn(),
  clearUserSubRegionLock: vi.fn(),
  saveUserSubRegions: vi.fn(),
}));

const PICKS = [{ areaId: 10, name: 'Berwick & Holy Island', regionName: 'Northumberland' }];

function makeUser(overrides = {}) {
  return {
    id: 1,
    username: 'anna_r',
    email: 'anna@example.com',
    role: 'LITE_USER',
    enabled: true,
    createdAt: '2026-10-19T09:00:00',
    lastActiveAt: '',
    termsAcceptedAt: '2026-10-19T09:00:00Z',
    termsVersion: 'April 2026',
    homePostcode: 'NE61 1AA',
    marketingEmailOptIn: false,
    awaitingVerification: false,
    verificationSentAt: null,
    ...overrides,
  };
}

function makeState(overrides = {}) {
  return { enforced: true, limit: 3, picks: PICKS, options: [], status: 'OPEN', ...overrides };
}

function renderPanel(props = {}) {
  const handlers = {
    onModeChange: vi.fn(),
    onClose: vi.fn(),
    onUsersChanged: vi.fn().mockResolvedValue(undefined),
    onTempPassword: vi.fn(),
    onToast: vi.fn(),
    onDeleted: vi.fn(),
  };
  const merged = { user: makeUser(), mode: 'view', ...handlers, ...props };
  const view = render(<UserReaderPanel {...merged} />);
  return { ...view, handlers, merged };
}

async function confirm(title, button) {
  const dialog = await screen.findByRole('dialog', { name: title });
  fireEvent.click(within(dialog).getByRole('button', { name: button }));
}

describe('UserReaderPanel — failure paths and edge renderings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getUserSubRegions.mockResolvedValue(makeState());
  });

  it('shows a fixed message when resetting the password fails', async () => {
    resetUserPassword.mockRejectedValue(new Error('smtp down'));
    const { handlers } = renderPanel();

    fireEvent.click(await screen.findByTestId('reset-password-1'));
    await confirm("Reset anna_r's password?", 'Reset password');

    expect(await screen.findByTestId('panel-action-error')).toHaveTextContent('Failed to reset password for anna_r.');
    expect(handlers.onTempPassword).not.toHaveBeenCalled();
    expect(screen.getByTestId('reset-password-1')).toBeEnabled();
  });

  it('shows the server message when disabling fails', async () => {
    updateUserEnabled.mockRejectedValue({ response: { data: { error: 'Cannot disable the last admin.' } } });
    renderPanel();

    fireEvent.click(await screen.findByTestId('disable-user-1'));
    await confirm('Disable anna_r?', 'Disable');

    expect(await screen.findByTestId('panel-action-error')).toHaveTextContent('Cannot disable the last admin.');
  });

  it('falls back to a plain message when enabling fails, and does not toast', async () => {
    updateUserEnabled.mockRejectedValue(new Error('boom'));
    const { handlers } = renderPanel({ user: makeUser({ enabled: false }) });

    fireEvent.click(await screen.findByTestId('enable-user-1'));

    expect(await screen.findByTestId('panel-action-error')).toHaveTextContent('Failed to enable anna_r.');
    expect(updateUserEnabled).toHaveBeenCalledWith(1, true);
    expect(handlers.onToast).not.toHaveBeenCalled();
  });

  it('shows a message and does not report deletion when the delete fails', async () => {
    deleteUser.mockRejectedValue(new Error('boom'));
    const { handlers } = renderPanel();

    fireEvent.click(await screen.findByTestId('delete-user-1'));
    await confirm('Delete anna_r?', 'Delete account');

    expect(await screen.findByTestId('panel-action-error')).toHaveTextContent('Failed to delete anna_r.');
    expect(handlers.onDeleted).not.toHaveBeenCalled();
  });

  it('shows a message when resending the verification email fails, and re-enables the button', async () => {
    resendVerification.mockRejectedValue(new Error('boom'));
    const { handlers } = renderPanel({
      user: makeUser({ awaitingVerification: true, enabled: false, verificationSentAt: null }),
    });

    fireEvent.click(await screen.findByTestId('resend-verify-1'));

    expect(await screen.findByTestId('panel-action-error'))
      .toHaveTextContent('Failed to resend verification for anna_r.');
    expect(handlers.onToast).not.toHaveBeenCalled();
    expect(screen.getByTestId('resend-verify-1')).toBeEnabled();
  });

  it('shows the server message when clearing a lock fails', async () => {
    getUserSubRegions.mockResolvedValue(makeState({ status: 'LOCKED', nextChangeOn: '2026-11-14' }));
    clearUserSubRegionLock.mockRejectedValue({ response: { data: { message: 'Lock already gone.' } } });
    const { handlers } = renderPanel();

    fireEvent.click(await screen.findByTestId('clear-lock'));
    await confirm("Clear anna_r's lock?", 'Clear lock');

    expect(await screen.findByTestId('panel-action-error')).toHaveTextContent('Lock already gone.');
    expect(handlers.onToast).not.toHaveBeenCalled();
  });

  it('reports a load error when the sub-region refetch after clearing a lock fails', async () => {
    getUserSubRegions
      .mockResolvedValueOnce(makeState({ status: 'LOCKED', nextChangeOn: '2026-11-14' }))
      .mockRejectedValueOnce(new Error('gone'));
    clearUserSubRegionLock.mockResolvedValue({});
    renderPanel();

    fireEvent.click(await screen.findByTestId('clear-lock'));
    await confirm("Clear anna_r's lock?", 'Clear lock');

    expect(await screen.findByText("Couldn't load this reader's sub-regions.")).toBeInTheDocument();
    expect(screen.queryByTestId('panel-pick-count')).not.toBeInTheDocument();
  });

  it('shows an em dash when the reader has no email', async () => {
    renderPanel({ user: makeUser({ email: '' }) });

    expect(await screen.findByText('Email')).toBeInTheDocument();
    expect(screen.getByText('Email').nextSibling).toHaveTextContent('—');
  });

  it('says Admin covers every sub-region for an admin reader', async () => {
    renderPanel({ user: makeUser({ role: 'ADMIN' }) });

    expect(await screen.findByTestId('panel-covers-all')).toHaveTextContent('Admin covers every sub-region.');
    expect(screen.getByTestId('panel-kept-picks')).toHaveTextContent('Berwick & Holy Island');
  });

  it('lists a pick that has no region name', async () => {
    getUserSubRegions.mockResolvedValue(makeState({ picks: [{ areaId: 30, name: 'Orphan Coast' }] }));
    renderPanel();

    expect(await screen.findByText('Orphan Coast')).toBeInTheDocument();
  });

  it('shows the terms date alone when no terms version is recorded', async () => {
    renderPanel({ user: makeUser({ termsVersion: null }) });

    expect(await screen.findByTestId('terms-accepted-1')).toHaveTextContent(/^Accepted 19 Oct 2026$/);
  });

  describe('edit mode before the sub-region state has arrived', () => {
    it('hides the role-change note, because the pick count is not yet known', async () => {
      getUserSubRegions.mockReturnValue(new Promise(() => {}));
      renderPanel({ mode: 'edit', user: makeUser({ role: 'PRO_USER' }) });

      fireEvent.change(screen.getByTestId('edit-role-1'), { target: { value: 'LITE_USER' } });

      expect(screen.getByTestId('edit-role-1')).toHaveValue('LITE_USER');
      expect(screen.queryByTestId('role-change-note')).not.toBeInTheDocument();
    });
  });

  describe('a stale sub-region response after the reader changed', () => {
    it('ignores a late success for the previous reader', async () => {
      let resolveFirst;
      getUserSubRegions.mockImplementation((id) => (id === 1
        ? new Promise((res) => { resolveFirst = res; })
        : Promise.resolve(makeState({ picks: [{ areaId: 40, name: 'Second Reader Coast', regionName: 'R' }] }))));
      const { rerender, merged } = renderPanel();

      rerender(<UserReaderPanel {...merged} user={makeUser({ id: 2, username: 'bob' })} />);
      expect(await screen.findByText('Second Reader Coast')).toBeInTheDocument();
      resolveFirst(makeState({ picks: [{ areaId: 41, name: 'First Reader Coast', regionName: 'R' }] }));

      await waitFor(() => expect(getUserSubRegions).toHaveBeenCalledTimes(2));
      expect(screen.queryByText('First Reader Coast')).not.toBeInTheDocument();
      expect(screen.getByText('Second Reader Coast')).toBeInTheDocument();
    });

    it('ignores a late failure for the previous reader', async () => {
      let rejectFirst;
      getUserSubRegions.mockImplementation((id) => (id === 1
        ? new Promise((_, rej) => { rejectFirst = rej; })
        : Promise.resolve(makeState({ picks: [{ areaId: 40, name: 'Second Reader Coast', regionName: 'R' }] }))));
      const { rerender, merged } = renderPanel();

      rerender(<UserReaderPanel {...merged} user={makeUser({ id: 2, username: 'bob' })} />);
      expect(await screen.findByText('Second Reader Coast')).toBeInTheDocument();
      rejectFirst(new Error('late'));

      await waitFor(() => expect(getUserSubRegions).toHaveBeenCalledTimes(2));
      expect(screen.queryByText("Couldn't load this reader's sub-regions.")).not.toBeInTheDocument();
    });
  });
});
