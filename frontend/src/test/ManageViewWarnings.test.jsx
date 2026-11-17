import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/warningsAdminApi.js', () => ({
  getWarningsStatus: vi.fn().mockResolvedValue({
    enabled: false,
    apiKeyConfigured: false,
    feedUrlConfigured: false,
    lastAttempt: null,
    lastSuccessAt: null,
    stale: true,
    storedVersions: 0,
    simulatedVersions: 0,
    unknownTypes: {},
    activeWarnings: [],
  }),
  simulateWarning: vi.fn(),
}));
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

describe('ManageView — Warnings tab', () => {
  beforeEach(() => {
    window.location.hash = '';
  });

  it('is a Testing sub-tab that mounts the warnings panel and sets its hash', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-group-testing'));
    const tab = screen.getByTestId('manage-tab-warnings');
    expect(tab).toHaveTextContent('Warnings');
    fireEvent.click(tab);
    expect(await screen.findByTestId('warnings-admin-panel')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/warnings');
  });

  it('#manage/warnings deep-links straight to it, in the Testing group', async () => {
    window.location.hash = '#manage/warnings';
    render(<ManageView onComplete={vi.fn()} />);
    expect(await screen.findByTestId('warnings-admin-panel')).toBeInTheDocument();
    expect(screen.getByTestId('manage-group-testing').className).toContain('text-plex-gold');
  });
});
