import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { getWaitlist } from '../api/waitlistApi.js';
import useHashSelection from '../hooks/useHashSelection.js';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const { stub } = vi.hoisted(() => ({
  stub: (name) => ({ default: () => <div data-testid="stub-view">{name}</div> }),
}));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/LocationManagementView.jsx', () => stub('Locations'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/PipelineRunsView.jsx', () => stub('Pipeline Runs'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));
vi.mock('../components/RewindView.jsx', () => stub('Rewind'));
vi.mock('../components/JobRunsMetricsView.jsx', () => stub('Job Runs'));
// The Tides stub is a REAL consumer of the shared hash hook: it shows what it would have selected, so a listener
// that stripped `/{id}` would be seen as an empty selection.
vi.mock('../components/TideManagementView.jsx', () => ({
  default: function TidesProbe() {
    const { selectedId } = useHashSelection('tides');
    return <div data-testid="stub-view">Tides selected: {selectedId ?? 'none'}</div>;
  },
}));

/** Moves the hash the way a cross-tab link does, then lets the browser's event fire. */
function goTo(hash) {
  act(() => {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
}

describe('ManageView — following the hash while mounted', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
  });

  it('moves to the tab a cross-tab link names, in its own group, keeping /{id}', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs');

    goTo('#manage/tides/12');

    expect(screen.getByTestId('stub-view')).toHaveTextContent('Tides selected: 12');
    // The listener set state directly: setActiveTab would have rewritten the hash to manage/tides.
    expect(window.location.hash).toBe('#manage/tides/12');
    expect(screen.getByTestId('manage-group-places').className).toContain('text-plex-gold');
    expect(screen.getByTestId('manage-tab-tides')).toBeInTheDocument();
  });

  it('follows a bare tab hash too', () => {
    render(<ManageView onComplete={vi.fn()} />);
    goTo('#manage/waitlist');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Waitlist');
    expect(screen.getByTestId('manage-group-people').className).toContain('text-plex-gold');
  });

  it('ignores a hash that is not a Manage tab, and never falls back to Pipeline Runs', () => {
    window.location.hash = '#manage/regions';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Regions');

    for (const hash of ['#invite=abc', '#reset', '#', '#plan/today']) {
      goTo(hash);
      expect(screen.getByTestId('stub-view')).toHaveTextContent('Regions');
    }
  });

  it('ignores a manage hash naming an unknown tab', () => {
    window.location.hash = '#manage/regions';
    render(<ManageView onComplete={vi.fn()} />);
    goTo('#manage/nonsense/3');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Regions');
    goTo('#manage');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Regions');
  });

  it('still writes the hash when a tab is clicked', () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-group-people'));
    fireEvent.click(screen.getByTestId('manage-tab-waitlist'));
    expect(window.location.hash).toBe('#manage/waitlist');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Waitlist');
  });

  it('stops listening when it unmounts', () => {
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(<ManageView onComplete={vi.fn()} />);
    unmount();
    expect(remove).toHaveBeenCalledWith('hashchange', expect.any(Function));
    remove.mockRestore();
  });
});
