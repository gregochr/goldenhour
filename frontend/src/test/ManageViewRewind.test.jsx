import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/rewindApi.js', () => ({
  getRewindEvents: vi.fn().mockResolvedValue({ now: '2026-10-04T09:30:00Z', briefingGeneratedAt: null, events: [] }),
}));
// ManageView renders a tree of admin views; the Runs group's default tab still fetches via
// apiClient, so keep the transport mocked (the shape `ManageViewWaitlist.test.jsx` uses).
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock('../components/UserManagementView.jsx', () => ({ default: () => <div>Users</div> }));
vi.mock('../components/LocationManagementView.jsx', () => ({ default: () => <div>Locations</div> }));
vi.mock('../components/RegionManagementView.jsx', () => ({ default: () => <div>Regions</div> }));
vi.mock('../components/TideManagementView.jsx', () => ({ default: () => <div>Tides</div> }));
vi.mock('../components/runs/RunsView.jsx', () => ({ default: () => <div>Runs view</div> }));
vi.mock('../components/ModelSelectionView.jsx', () => ({ default: () => <div>Run Config</div> }));
vi.mock('../components/SchedulerView.jsx', () => ({ default: () => <div>Scheduler</div> }));
vi.mock('../components/PipelineRunsView.jsx', () => ({ default: () => <div>Pipeline Runs</div> }));

describe('ManageView — Rewind tab', () => {
  beforeEach(() => {
    window.location.hash = '';
  });

  it('is the last Testing sub-tab and mounts the rewind view', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-group-testing'));
    const tab = screen.getByTestId('manage-tab-rewind');
    expect(tab).toHaveTextContent('Rewind');
    fireEvent.click(tab);
    expect(await screen.findByTestId('rewind-view')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/rewind');
  });

  it('lists Sky Eval as the only model-test screen (the other two were retired)', () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-group-tuning'));
    expect(screen.getByText('Sky Eval')).toBeInTheDocument();
    expect(screen.queryByText('Location Model Test')).not.toBeInTheDocument();
    expect(screen.queryByText('Briefing Model Test')).not.toBeInTheDocument();
  });

  it('#manage/rewind deep-links straight to it', async () => {
    window.location.hash = '#manage/rewind';
    render(<ManageView onComplete={vi.fn()} />);
    expect(await screen.findByTestId('rewind-view')).toBeInTheDocument();
  });
});
