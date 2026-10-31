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
vi.mock('../components/runs/RunsView.jsx', () => ({ default: () => <div>Runs view</div> }));
vi.mock('../components/ModelSelectionView.jsx', () => ({ default: () => <div>Run Config</div> }));
vi.mock('../components/SchedulerView.jsx', () => ({ default: () => <div>Scheduler</div> }));
vi.mock('../components/PipelineRunsView.jsx', () => ({ default: () => <div>Pipeline Runs</div> }));

describe('ManageView — the Sub-regions tab is gone (the Lite limit lives on Operations → People → Users)', () => {
  beforeEach(() => {
    window.location.hash = '';
  });

  it('carries no Sub-regions tab in any group', () => {
    render(<ManageView onComplete={vi.fn()} />);
    ['runs', 'tuning', 'places', 'people', 'testing'].forEach((group) => {
      fireEvent.click(screen.getByTestId(`manage-group-${group}`));
      expect(screen.queryByTestId('manage-tab-subregions')).toBeNull();
      expect(screen.queryByText('Sub-regions')).toBeNull();
    });
  });

  it('an old #manage/subregions link falls back to Runs → Runs', () => {
    window.location.hash = '#manage/subregions';
    render(<ManageView onComplete={vi.fn()} />);

    expect(screen.getByText('Runs view', { selector: 'div' })).toBeInTheDocument();
    expect(screen.getByTestId('manage-tab-runs')).toBeInTheDocument();
    expect(screen.queryByLabelText('Picks per Lite reader')).toBeNull();
  });
});
