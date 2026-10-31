import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock('../components/UserManagementView.jsx', () => ({ default: () => <div data-testid="users-stub">Users</div> }));
vi.mock('../components/LocationManagementView.jsx', () => ({ default: () => <div>Locations</div> }));
vi.mock('../components/RegionManagementView.jsx', () => ({ default: () => <div>Regions</div> }));
vi.mock('../components/TideManagementView.jsx', () => ({ default: () => <div>Tides</div> }));
vi.mock('../components/JobRunsMetricsView.jsx', () => ({ default: () => <div>Job Runs</div> }));
vi.mock('../components/ModelSelectionView.jsx', () => ({ default: () => <div>Run Config</div> }));
vi.mock('../components/SchedulerView.jsx', () => ({ default: () => <div>Scheduler</div> }));
vi.mock('../components/PipelineRunsView.jsx', () => ({ default: () => <div>Pipeline Runs</div> }));

describe('ManageView — the Sub-regions tab is gone (the Lite limit lives on Data → Users)', () => {
  beforeEach(() => {
    window.location.hash = '';
  });

  it('carries no Sub-regions tab in the Data or the Operations group', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.queryByTestId('manage-tab-subregions')).toBeNull();

    fireEvent.click(screen.getByTestId('manage-group-operations'));
    expect(screen.queryByTestId('manage-tab-subregions')).toBeNull();
    expect(screen.queryByText('Sub-regions')).toBeNull();
  });

  it('an old #manage/subregions link lands on Data → Users', () => {
    window.location.hash = '#manage/subregions';
    render(<ManageView onComplete={vi.fn()} />);

    expect(screen.getByTestId('users-stub')).toBeInTheDocument();
    expect(screen.getByTestId('manage-tab-users')).toBeInTheDocument();
    expect(screen.queryByLabelText('Picks per Lite reader')).toBeNull();
  });
});
