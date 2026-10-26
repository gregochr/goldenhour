import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';
import WaitlistManagementView from '../components/WaitlistManagementView.jsx';

vi.mock('../api/waitlistApi.js', () => ({
  getWaitlist: vi.fn(),
  inviteWaitlistEntry: vi.fn(),
  removeWaitlistEntry: vi.fn(),
}));

describe('WaitlistManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders waitlist entries in a table ordered oldest first', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-05T10:00:00' },
      { id: 2, email: 'bob@example.com', submittedAt: '2026-04-07T14:30:00' },
    ];
    getWaitlist.mockResolvedValue(entries);
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-email-0')).toHaveTextContent('alice@example.com');
    expect(screen.getByTestId('waitlist-email-1')).toHaveTextContent('bob@example.com');
    expect(onCountChange).toHaveBeenCalledWith(2);
  });

  it('shows row numbers starting from 1', async () => {
    const entries = [
      { id: 10, email: 'first@example.com', submittedAt: '2026-04-01T08:00:00' },
      { id: 20, email: 'second@example.com', submittedAt: '2026-04-02T09:00:00' },
      { id: 30, email: 'third@example.com', submittedAt: '2026-04-03T10:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const rows = screen.getByTestId('waitlist-table').querySelectorAll('tbody tr');
    expect(rows[0].querySelector('td').textContent).toBe('1');
    expect(rows[1].querySelector('td').textContent).toBe('2');
    expect(rows[2].querySelector('td').textContent).toBe('3');
  });

  it('formats submitted date as readable date and time', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-08T14:32:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-submitted-0')).toBeInTheDocument();
    });

    // en-GB locale: "8 Apr 2026, 14:32" (exact format varies by env)
    const text = screen.getByTestId('waitlist-submitted-0').textContent;
    expect(text).toContain('Apr');
    expect(text).toContain('2026');
  });

  it('shows empty state message when no entries exist', async () => {
    getWaitlist.mockResolvedValue([]);
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-empty')).toHaveTextContent('No waitlist entries yet.');
    expect(screen.queryByTestId('waitlist-table')).not.toBeInTheDocument();
    expect(onCountChange).toHaveBeenCalledWith(0);
  });

  it('calls onCountChange with correct count after fetch', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
      { id: 3, email: 'e@f.com', submittedAt: '2026-04-03T00:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    await waitFor(() => {
      expect(onCountChange).toHaveBeenCalledWith(3);
    });
  });

  it('fetches from the correct admin endpoint', async () => {
    getWaitlist.mockResolvedValue([]);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(getWaitlist).toHaveBeenCalled();
    });
  });

  it('shows loading state initially', () => {
    getWaitlist.mockReturnValue(new Promise(() => {})); // Never resolves

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    expect(screen.getByText('Loading waitlist...')).toBeInTheDocument();
  });

  it('row numbers are sequential and not database IDs', async () => {
    // IDs are deliberately non-sequential to prove row numbers come from index, not id
    const entries = [
      { id: 42, email: 'first@example.com', submittedAt: '2026-04-01T08:00:00' },
      { id: 99, email: 'second@example.com', submittedAt: '2026-04-02T09:00:00' },
      { id: 7, email: 'third@example.com', submittedAt: '2026-04-03T10:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const rows = screen.getByTestId('waitlist-table').querySelectorAll('tbody tr');
    // Row numbers should be 1, 2, 3 — NOT the database IDs 42, 99, 7
    expect(rows[0].querySelector('td').textContent).toBe('1');
    expect(rows[1].querySelector('td').textContent).toBe('2');
    expect(rows[2].querySelector('td').textContent).toBe('3');
  });

  it('renders table with correct column headers', async () => {
    getWaitlist.mockResolvedValue([
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
    ]);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });

    const headers = screen.getByTestId('waitlist-table').querySelectorAll('thead th');
    expect(headers).toHaveLength(4);
    expect(headers[0]).toHaveTextContent('#');
    expect(headers[1]).toHaveTextContent('Email');
    expect(headers[2]).toHaveTextContent('Submitted');
    expect(headers[3]).toHaveTextContent('Invitation');
  });

  it('handles API failure gracefully without crashing', async () => {
    getWaitlist.mockRejectedValue(new Error('Network error'));
    const onCountChange = vi.fn();

    render(<WaitlistManagementView onCountChange={onCountChange} />);

    // Should settle to empty state (no entries loaded), not crash
    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    // onCountChange should NOT have been called since the request failed
    expect(onCountChange).not.toHaveBeenCalled();
  });

  it('does not show table when no entries exist', async () => {
    getWaitlist.mockResolvedValue([]);

    render(<WaitlistManagementView onCountChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  describe('invitations and removal', () => {
    const FRESH = { id: 1, email: 'alice@example.com', submittedAt: '2026-04-05T10:00:00', invitedAt: null, inviteExpiresAt: null };
    const INVITED = {
      ...FRESH, invitedAt: '2026-04-09T08:00:00', inviteExpiresAt: '2026-04-16T08:00:00',
    };

    it('shows an Invite button and no invitation state for a row never invited', async () => {
      getWaitlist.mockResolvedValue([FRESH]);

      render(<WaitlistManagementView onCountChange={vi.fn()} />);

      expect(await screen.findByTestId('waitlist-invite-0')).toHaveTextContent('Invite');
      expect(screen.getByTestId('waitlist-invite-0')).not.toHaveTextContent('again');
      expect(screen.queryByTestId('waitlist-invited-0')).not.toBeInTheDocument();
    });

    it('shows "Invited <date> · expires <date>" and offers Invite again for an invited row', async () => {
      getWaitlist.mockResolvedValue([INVITED]);

      render(<WaitlistManagementView onCountChange={vi.fn()} />);

      const state = await screen.findByTestId('waitlist-invited-0');
      expect(state).toHaveTextContent(/Invited 9 Apr 2026 · expires 16 Apr 2026/);
      expect(screen.getByTestId('waitlist-invite-0')).toHaveTextContent('Invite again');
    });

    it('Invite calls the API for that row, then reloads so the dates come from the server', async () => {
      const user = userEvent.setup();
      getWaitlist.mockResolvedValueOnce([FRESH]).mockResolvedValueOnce([INVITED]);
      inviteWaitlistEntry.mockResolvedValue({ message: 'Invitation sent' });

      render(<WaitlistManagementView onCountChange={vi.fn()} />);
      await user.click(await screen.findByTestId('waitlist-invite-0'));

      await waitFor(() => expect(inviteWaitlistEntry).toHaveBeenCalledWith(1));
      expect(await screen.findByTestId('waitlist-invited-0')).toHaveTextContent('expires 16 Apr 2026');
      expect(screen.getByTestId('waitlist-notice')).toHaveTextContent('Invitation sent.');
    });

    it('a refused invitation shows the server sentence and changes nothing', async () => {
      const user = userEvent.setup();
      getWaitlist.mockResolvedValue([FRESH]);
      inviteWaitlistEntry.mockRejectedValue({ response: { status: 400, data: { error: 'Not ASCII' } } });

      render(<WaitlistManagementView onCountChange={vi.fn()} />);
      await user.click(await screen.findByTestId('waitlist-invite-0'));

      expect(await screen.findByTestId('waitlist-action-error')).toHaveTextContent('Not ASCII');
      expect(screen.queryByTestId('waitlist-invited-0')).not.toBeInTheDocument();
      expect(getWaitlist).toHaveBeenCalledTimes(1);
    });

    it('Remove asks first, and Keep backs out without calling the API', async () => {
      const user = userEvent.setup();
      getWaitlist.mockResolvedValue([FRESH]);

      render(<WaitlistManagementView onCountChange={vi.fn()} />);
      await user.click(await screen.findByTestId('waitlist-remove-0'));
      expect(removeWaitlistEntry).not.toHaveBeenCalled();
      await user.click(screen.getByTestId('waitlist-remove-cancel-0'));

      expect(screen.getByTestId('waitlist-remove-0')).toBeInTheDocument();
      expect(removeWaitlistEntry).not.toHaveBeenCalled();
    });

    it('confirming Remove deletes that row, drops it from the table and updates the count', async () => {
      const user = userEvent.setup();
      const other = { id: 2, email: 'bob@example.com', submittedAt: '2026-04-07T14:30:00' };
      getWaitlist.mockResolvedValue([FRESH, other]);
      removeWaitlistEntry.mockResolvedValue(undefined);
      const onCountChange = vi.fn();

      render(<WaitlistManagementView onCountChange={onCountChange} />);
      await user.click(await screen.findByTestId('waitlist-remove-0'));
      await user.click(screen.getByTestId('waitlist-remove-confirm-0'));

      await waitFor(() => expect(removeWaitlistEntry).toHaveBeenCalledWith(1));
      await waitFor(() => expect(screen.queryByText('alice@example.com')).not.toBeInTheDocument());
      expect(screen.getByTestId('waitlist-email-0')).toHaveTextContent('bob@example.com');
      expect(onCountChange).toHaveBeenLastCalledWith(1);
    });

    it('a failed Remove keeps the row and says so', async () => {
      const user = userEvent.setup();
      getWaitlist.mockResolvedValue([FRESH]);
      removeWaitlistEntry.mockRejectedValue(new Error('boom'));

      render(<WaitlistManagementView onCountChange={vi.fn()} />);
      await user.click(await screen.findByTestId('waitlist-remove-0'));
      await user.click(screen.getByTestId('waitlist-remove-confirm-0'));

      expect(await screen.findByTestId('waitlist-action-error')).toHaveTextContent('Could not remove');
      expect(screen.getByTestId('waitlist-email-0')).toHaveTextContent('alice@example.com');
    });
  });
});
