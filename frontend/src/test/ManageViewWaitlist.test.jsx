import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({
  getWaitlist: vi.fn(),
  inviteWaitlistEntry: vi.fn(),
  removeWaitlistEntry: vi.fn(),
}));

// ManageView renders a tree of admin views; the transport stays mocked for
// any view that is not stubbed below.
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

// Mock all heavy child views to keep tests focused on ManageView tab logic
vi.mock('../components/UserManagementView.jsx', () => ({
  default: () => <div data-testid="user-management-view">Users</div>,
}));
vi.mock('../components/LocationManagementView.jsx', () => ({
  default: () => <div>Locations</div>,
}));
vi.mock('../components/RegionManagementView.jsx', () => ({
  default: () => <div>Regions</div>,
}));
vi.mock('../components/TideManagementView.jsx', () => ({
  default: () => <div>Tides</div>,
}));
vi.mock('../components/JobRunsMetricsView.jsx', () => ({
  default: () => <div>Job Runs</div>,
}));
vi.mock('../components/ModelSelectionView.jsx', () => ({
  default: () => <div>Run Config</div>,
}));
vi.mock('../components/SchedulerView.jsx', () => ({
  default: () => <div>Scheduler</div>,
}));
vi.mock('../components/PipelineRunsView.jsx', () => ({
  default: () => <div>Pipeline Runs</div>,
}));

/** Waitlist lives under People; the landing group is Runs. */
const openWaitlist = () => {
  fireEvent.click(screen.getByTestId('manage-group-people'));
  fireEvent.click(screen.getByTestId('manage-tab-waitlist'));
};

describe('ManageView — Waitlist tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
  });

  it('shows Waitlist tab in the People group', () => {
    getWaitlist.mockResolvedValue([]);
    window.location.hash = 'manage/waitlist';

    render(<ManageView onComplete={vi.fn()} />);

    expect(screen.getByTestId('manage-tab-waitlist')).toBeInTheDocument();
    expect(screen.getByTestId('manage-tab-waitlist')).toHaveTextContent('Waitlist');
  });

  it('renders WaitlistManagementView when Waitlist tab is clicked', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-05T10:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<ManageView onComplete={vi.fn()} />);

    openWaitlist();

    await waitFor(() => {
      expect(screen.getByTestId('waitlist')).toBeInTheDocument();
    });
  });

  it('shows count badge on Waitlist tab after entries are loaded', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
      { id: 3, email: 'e@f.com', submittedAt: '2026-04-03T00:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<ManageView onComplete={vi.fn()} />);

    openWaitlist();

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-count-badge')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('3');
  });

  it('does not show count badge when waitlist is empty', async () => {
    getWaitlist.mockResolvedValue([]);

    render(<ManageView onComplete={vi.fn()} />);

    openWaitlist();

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument();
  });

  it('navigates to Waitlist via URL hash', () => {
    window.location.hash = 'manage/waitlist';
    getWaitlist.mockReturnValue(new Promise(() => {}));

    render(<ManageView onComplete={vi.fn()} />);

    // The waitlist content area should be rendered (loading state confirms tab is active)
    expect(screen.getByText('Loading waitlist...')).toBeInTheDocument();
  });

  it('shows the count on the People group button at landing, without opening the Waitlist tab', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
    ];
    getWaitlist.mockResolvedValue(entries);

    render(<ManageView onComplete={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-group-badge')).toHaveTextContent('2');
    });
    // Still on the landing sub-view (Pipeline Runs); the sub-tab badge is not on screen
    expect(screen.getByText('Pipeline Runs', { selector: 'div' })).toBeInTheDocument();
    expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument();
  });

  it('Waitlist tab is in the People group, not Runs', () => {
    getWaitlist.mockReturnValue(new Promise(() => {}));

    render(<ManageView onComplete={vi.fn()} />);

    // Runs is active by default - Waitlist tab is not offered
    expect(screen.queryByTestId('manage-tab-waitlist')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('manage-group-people'));
    expect(screen.getByTestId('manage-tab-waitlist')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('manage-group-runs'));
    expect(screen.queryByTestId('manage-tab-waitlist')).not.toBeInTheDocument();
  });

  it('the group badge persists when switching between groups', async () => {
    getWaitlist.mockResolvedValue([{ id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' }]);

    render(<ManageView onComplete={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-group-badge')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('manage-group-places'));
    expect(screen.getByTestId('waitlist-group-badge')).toHaveTextContent('1');

    fireEvent.click(screen.getByTestId('manage-group-people'));
    expect(screen.getByTestId('waitlist-group-badge')).toHaveTextContent('1');
    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('1');
  });

  it('does not show badge when eager fetch fails', async () => {
    getWaitlist.mockRejectedValue(new Error('Network error'));

    render(<ManageView onComplete={vi.fn()} />);

    // Give time for the failed request to settle
    await waitFor(() => {
      expect(getWaitlist).toHaveBeenCalled();
    });

    expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument();
  });

  it('Invite from the Waitlist tab calls the invite API for that row and shows the dates from the reload', async () => {
    const user = userEvent.setup();
    const fresh = { id: 5, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00', invitedAt: null, inviteExpiresAt: null };
    const invited = { ...fresh, invitedAt: '2026-04-09T08:00:00', inviteExpiresAt: '2026-04-16T08:00:00' };
    getWaitlist.mockResolvedValueOnce([fresh]).mockResolvedValueOnce([fresh]).mockResolvedValue([invited]);
    inviteWaitlistEntry.mockResolvedValue({ message: 'Invitation sent' });

    render(<ManageView onComplete={vi.fn()} />);
    openWaitlist();
    await user.click(await screen.findByTestId('waitlist-row-5'));
    await user.click(screen.getByTestId('waitlist-invite'));

    await waitFor(() => expect(inviteWaitlistEntry).toHaveBeenCalledWith(5));
    expect(await screen.findByTestId('waitlist-invited')).toHaveTextContent('expires 16 Apr 2026');
  });

  it('removing the last entry clears both badges', async () => {
    const user = userEvent.setup();
    getWaitlist.mockResolvedValue([{ id: 5, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' }]);
    removeWaitlistEntry.mockResolvedValue(undefined);

    render(<ManageView onComplete={vi.fn()} />);
    await waitFor(() => expect(screen.getByTestId('waitlist-group-badge')).toHaveTextContent('1'));
    openWaitlist();
    await user.click(await screen.findByTestId('waitlist-row-5'));
    await user.click(screen.getByTestId('waitlist-remove'));
    await user.click(screen.getByTestId('waitlist-remove-confirm'));

    await waitFor(() => expect(removeWaitlistEntry).toHaveBeenCalledWith(5));
    await waitFor(() => expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument());
    expect(screen.queryByTestId('waitlist-group-badge')).not.toBeInTheDocument();
  });
});
