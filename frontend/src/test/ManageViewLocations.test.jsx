import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { getWaitlist } from '../api/waitlistApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsWide } from '../hooks/useIsWide.js';
import ManageView from '../components/ManageView.jsx';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));
vi.mock('../hooks/useIsWide.js', () => ({ useIsWide: vi.fn() }));
vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));
vi.mock('../api/forecastApi.js', () => ({ fetchLocations: vi.fn() }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const { stub } = vi.hoisted(() => ({
  stub: (name) => ({ default: () => <div data-testid="stub-view">{name}</div> }),
}));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/TideManagementView.jsx', () => stub('Tides'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/PipelineRunsView.jsx', () => stub('Pipeline Runs'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));
vi.mock('../components/RewindView.jsx', () => stub('Rewind'));
vi.mock('../components/JobRunsMetricsView.jsx', () => stub('Job Runs'));
// The Locations view stands in with a button that reports a count, as the real view does once it has loaded.
vi.mock('../components/LocationManagementView.jsx', () => ({
  default: ({ onAttentionCountChange }) => (
    <button type="button" data-testid="report-count" onClick={() => onAttentionCountChange(7)}>Locations view</button>
  ),
}));

const FAILING = { id: 1, name: 'Amble', enabled: true, consecutiveFailures: 2 };
const AUTO_DISABLED = { id: 2, name: 'Bamburgh', enabled: false, consecutiveFailures: 0, disabledReason: 'Auto-disabled' };
const HEALTHY = { id: 3, name: 'Durham', enabled: true, consecutiveFailures: 0 };
const MANUAL_OFF = { id: 4, name: 'Blea Tarn', enabled: false, consecutiveFailures: 0 };

function setLayout(layout) {
  useIsMobile.mockReturnValue(layout === 'phone');
  useIsWide.mockReturnValue(layout === 'wide');
}

describe('ManageView — the Locations attention count', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '#manage/regions';
    getWaitlist.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([FAILING, AUTO_DISABLED, HEALTHY, MANUAL_OFF]);
    setLayout('tablet');
  });

  it('shows an amber count after Locations in the sub-tab bar, counting failing and turned-off places', async () => {
    render(<ManageView onComplete={vi.fn()} />);

    const badge = await screen.findByTestId('locations-attention-badge');
    expect(badge).toHaveTextContent('2');
    expect(badge).toHaveClass('text-verdict-marginal');
    // Plain amber text after the label, not a filled pill.
    expect(badge.className).not.toMatch(/\bbg-|rounded-full/);
    expect(screen.getByTestId('manage-tab-locations')).toContainElement(badge);
    expect(screen.queryByTestId('manage-tab-regions').querySelector('[data-testid$="-badge"]')).toBeNull();
  });

  it('shows no count when nothing needs attention', async () => {
    fetchLocations.mockResolvedValue([HEALTHY, MANUAL_OFF]);
    render(<ManageView onComplete={vi.fn()} />);

    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    expect(screen.queryByTestId('locations-attention-badge')).not.toBeInTheDocument();
  });

  it('shows no count when the locations could not be read', async () => {
    fetchLocations.mockRejectedValue(new Error('down'));
    render(<ManageView onComplete={vi.fn()} />);

    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    expect(screen.queryByTestId('locations-attention-badge')).not.toBeInTheDocument();
  });

  it('shows it in the sidebar on a wide screen', async () => {
    setLayout('wide');
    render(<ManageView onComplete={vi.fn()} />);

    const badge = await screen.findByTestId('locations-attention-badge');
    expect(screen.getByTestId('manage-sidebar')).toContainElement(badge);
  });

  it('shows it in the phone menu as a bracketed count', async () => {
    setLayout('phone');
    render(<ManageView onComplete={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('option', { name: 'Locations (2)' })).toBeInTheDocument());
    expect(screen.getByRole('option', { name: 'Regions' })).toBeInTheDocument();
  });

  it('reads the locations itself when another tab is showing, and not when Locations is (the view reports it)', async () => {
    const first = render(<ManageView onComplete={vi.fn()} />);
    await waitFor(() => expect(fetchLocations).toHaveBeenCalledTimes(1));
    first.unmount();

    fetchLocations.mockClear();
    window.location.hash = '#manage/locations';
    render(<ManageView onComplete={vi.fn()} />);
    await screen.findByTestId('report-count');

    expect(fetchLocations).not.toHaveBeenCalled();
    expect(screen.queryByTestId('locations-attention-badge')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('report-count'));
    await waitFor(() => expect(screen.getByTestId('locations-attention-badge')).toHaveTextContent('7'));
  });

  it('follows the count the Locations view reports', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    await screen.findByTestId('locations-attention-badge');
    fireEvent.click(screen.getByTestId('manage-tab-locations'));

    fireEvent.click(await screen.findByTestId('report-count'));

    await waitFor(() => expect(screen.getByTestId('locations-attention-badge')).toHaveTextContent('7'));
  });
});
