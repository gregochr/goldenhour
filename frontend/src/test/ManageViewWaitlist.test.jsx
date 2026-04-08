import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import ManageView from '../components/ManageView.jsx';

vi.mock('axios');

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
vi.mock('../components/ModelTestView.jsx', () => ({
  default: () => <div>Model Test</div>,
}));
vi.mock('../components/BriefingModelTestView.jsx', () => ({
  default: () => <div>Briefing Model Test</div>,
}));
vi.mock('../components/PromptTestView.jsx', () => ({
  default: () => <div>Prompt Test</div>,
}));
vi.mock('../components/SchedulerView.jsx', () => ({
  default: () => <div>Scheduler</div>,
}));

describe('ManageView — Waitlist tab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
  });

  it('shows Waitlist tab in Data group', () => {
    axios.get.mockResolvedValue({ data: [] });

    render(<ManageView onComplete={vi.fn()} />);

    expect(screen.getByTestId('manage-tab-waitlist')).toBeInTheDocument();
    expect(screen.getByTestId('manage-tab-waitlist')).toHaveTextContent('Waitlist');
  });

  it('renders WaitlistManagementView when Waitlist tab is clicked', async () => {
    const entries = [
      { id: 1, email: 'alice@example.com', submittedAt: '2026-04-05T10:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<ManageView onComplete={vi.fn()} />);

    fireEvent.click(screen.getByTestId('manage-tab-waitlist'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-table')).toBeInTheDocument();
    });
  });

  it('shows count badge on Waitlist tab after entries are loaded', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
      { id: 3, email: 'e@f.com', submittedAt: '2026-04-03T00:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<ManageView onComplete={vi.fn()} />);

    fireEvent.click(screen.getByTestId('manage-tab-waitlist'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-count-badge')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('3');
  });

  it('does not show count badge when waitlist is empty', async () => {
    axios.get.mockResolvedValue({ data: [] });

    render(<ManageView onComplete={vi.fn()} />);

    fireEvent.click(screen.getByTestId('manage-tab-waitlist'));

    await waitFor(() => {
      expect(screen.getByTestId('waitlist-empty')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument();
  });

  it('navigates to Waitlist via URL hash', () => {
    window.location.hash = 'manage/waitlist';
    axios.get.mockReturnValue(new Promise(() => {}));

    render(<ManageView onComplete={vi.fn()} />);

    // The waitlist content area should be rendered (loading state confirms tab is active)
    expect(screen.getByText('Loading waitlist...')).toBeInTheDocument();
  });

  it('shows count badge on initial load without clicking the Waitlist tab', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
      { id: 2, email: 'c@d.com', submittedAt: '2026-04-02T00:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<ManageView onComplete={vi.fn()} />);

    // Badge should appear without navigating to the Waitlist tab
    await waitFor(() => {
      expect(screen.getByTestId('waitlist-count-badge')).toBeInTheDocument();
    });

    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('2');
    // Confirm we're still on the Users tab (default), not Waitlist
    expect(screen.getByTestId('user-management-view')).toBeInTheDocument();
  });

  it('Waitlist tab is in the Data group, not Operations', () => {
    axios.get.mockReturnValue(new Promise(() => {}));

    render(<ManageView onComplete={vi.fn()} />);

    // Data group is active by default — Waitlist tab should be visible
    expect(screen.getByTestId('manage-tab-waitlist')).toBeInTheDocument();

    // Switch to Operations group — Waitlist tab should disappear
    fireEvent.click(screen.getByTestId('manage-group-operations'));
    expect(screen.queryByTestId('manage-tab-waitlist')).not.toBeInTheDocument();
  });

  it('badge persists when switching between Data tabs', async () => {
    const entries = [
      { id: 1, email: 'a@b.com', submittedAt: '2026-04-01T00:00:00' },
    ];
    axios.get.mockResolvedValue({ data: entries });

    render(<ManageView onComplete={vi.fn()} />);

    // Wait for the eager fetch to populate the badge
    await waitFor(() => {
      expect(screen.getByTestId('waitlist-count-badge')).toBeInTheDocument();
    });

    // Switch to Locations tab — badge should still be on the Waitlist tab
    fireEvent.click(screen.getByTestId('manage-tab-locations'));
    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('1');

    // Switch to Regions tab — badge should still be visible
    fireEvent.click(screen.getByTestId('manage-tab-regions'));
    expect(screen.getByTestId('waitlist-count-badge')).toHaveTextContent('1');
  });

  it('does not show badge when eager fetch fails', async () => {
    axios.get.mockRejectedValue(new Error('Network error'));

    render(<ManageView onComplete={vi.fn()} />);

    // Give time for the failed request to settle
    await waitFor(() => {
      expect(axios.get).toHaveBeenCalledWith('/api/admin/waitlist');
    });

    expect(screen.queryByTestId('waitlist-count-badge')).not.toBeInTheDocument();
  });
});
