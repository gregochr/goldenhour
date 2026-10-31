import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/subRegionPolicyApi.js', () => ({
  getSubRegionPolicy: vi.fn().mockResolvedValue({
    liteLimit: 3, maxLimit: 24, liteUsersOverLimit: 0, candidateLimit: 3, updatedAt: null, updatedBy: null,
  }),
  saveSubRegionPolicy: vi.fn(),
}));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock('../components/UserManagementView.jsx', () => ({ default: () => <div>Users</div> }));
vi.mock('../components/LocationManagementView.jsx', () => ({ default: () => <div>Locations</div> }));
vi.mock('../components/RegionManagementView.jsx', () => ({ default: () => <div>Regions</div> }));
vi.mock('../components/TideManagementView.jsx', () => ({ default: () => <div>Tides</div> }));
vi.mock('../components/JobRunsMetricsView.jsx', () => ({ default: () => <div>Job Runs</div> }));
vi.mock('../components/ModelSelectionView.jsx', () => ({ default: () => <div>Run Config</div> }));
vi.mock('../components/SchedulerView.jsx', () => ({ default: () => <div>Scheduler</div> }));
vi.mock('../components/PipelineRunsView.jsx', () => ({ default: () => <div>Pipeline Runs</div> }));

describe('ManageView — Sub-regions tab', () => {
  beforeEach(() => {
    window.location.hash = '';
  });

  it('is a People sub-tab and mounts the Lite pick limit control', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-group-people'));

    const tab = screen.getByTestId('manage-tab-subregions');
    expect(tab).toHaveTextContent('Sub-regions');
    fireEvent.click(tab);

    expect(await screen.findByLabelText('Picks per Lite reader')).toHaveValue(3);
    expect(window.location.hash).toBe('#manage/subregions');
  });

  it('#manage/subregions deep-links straight to it, and the Runs group does not carry the tab', async () => {
    window.location.hash = '#manage/subregions';
    render(<ManageView onComplete={vi.fn()} />);

    expect(await screen.findByLabelText('Picks per Lite reader')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('manage-group-runs'));
    expect(screen.queryByTestId('manage-tab-subregions')).toBeNull();
  });
});
