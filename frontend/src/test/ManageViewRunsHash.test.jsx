import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act, within, fireEvent } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';
import useHashSelection from '../hooks/useHashSelection.js';
import { fetchRuns } from '../api/runsApi.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsWide } from '../hooks/useIsWide.js';

// Module-level, like the Runs tab's own parser, so the hook's listener stays stable.
const parseRuns = (segments) => (
  segments.length === 2 && ['job', 'cycle'].includes(segments[0]) ? { [segments[0]]: Number(segments[1]) } : null
);

// The Runs hashes (runs-redesign-plan.md §4): the Job Runs tab became Runs and Pipeline Runs folded into it, so the old
// #manage/metrics and #manage/pipelineruns[/{id}] redirect in place, on mount and on hashchange; and the live count
// beside Runs follows the server's runningCount.

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));
vi.mock('../hooks/useIsWide.js', () => ({ useIsWide: vi.fn() }));
vi.mock('../api/runsApi.js', () => ({ fetchRuns: vi.fn() }));

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/forecastApi.js', () => ({ fetchLocations: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const { stub } = vi.hoisted(() => ({
  stub: (name) => ({ default: () => <div data-testid="stub-view">{name}</div> }),
}));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/LocationManagementView.jsx', () => stub('Locations'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/TideManagementView.jsx', () => stub('Tides'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));
vi.mock('../components/RewindView.jsx', () => stub('Rewind'));
// The Runs stub is a REAL consumer of the shared hash hook, as the Runs view is: it shows the selection it read.
vi.mock('../components/runs/RunsView.jsx', () => ({
  default: function RunsProbe({ onLiveCountChange }) {
    const { selectedId } = useHashSelection('runs', { parse: parseRuns });
    return (
      <div data-testid="stub-view">
        Runs selected: {selectedId ? JSON.stringify(selectedId) : 'none'}
        <button type="button" onClick={() => onLiveCountChange(3)}>report 3</button>
        <button type="button" onClick={() => onLiveCountChange(0)}>report 0</button>
      </div>
    );
  },
}));

/** Moves the hash the way a link does, then lets the browser's event fire. */
function goTo(hash) {
  act(() => {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '';
  useIsMobile.mockReturnValue(false);
  useIsWide.mockReturnValue(false);
  fetchRuns.mockResolvedValue({ runningCount: 0 });
});

describe('ManageView: #manage/metrics redirects to #manage/runs', () => {
  it('on mount, rewriting the address before the Runs view reads it', () => {
    window.location.hash = '#manage/metrics';
    render(<ManageView onComplete={vi.fn()} />);

    expect(window.location.hash).toBe('#manage/runs');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('on hashchange while mounted on another tab', () => {
    window.location.hash = '#manage/users';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Users');

    goTo('#manage/metrics');

    expect(window.location.hash).toBe('#manage/runs');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('keeps a path and a query: #manage/metrics/job/5?kind=tide becomes #manage/runs/job/5?kind=tide', () => {
    window.location.hash = '#manage/metrics/job/5?kind=tide';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/runs/job/5?kind=tide');
  });

  it('hands the Runs view the redirected selection when it is already mounted on Runs', () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: none');

    // An old link pasted while on the Runs tab: the Runs view read the old address as nothing, then ManageView
    // rewrote it, and replaceState fires no event of its own.
    goTo('#manage/metrics/job/5');

    expect(window.location.hash).toBe('#manage/runs/job/5');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: {"job":5}');
  });

  it('keeps a bare query: #manage/metrics?range=today', () => {
    window.location.hash = '#manage/metrics?range=today';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/runs?range=today');
  });

  it('rewrites the entry in place, adding no history entry', () => {
    window.location.hash = '#manage/users';
    render(<ManageView onComplete={vi.fn()} />);
    const before = window.history.length;

    goTo('#manage/metrics');

    // The hash assignment itself is one entry; the redirect adds none beyond it.
    expect(window.history.length).toBeLessThanOrEqual(before + 1);
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('does not touch a hash that merely starts with the same letters', () => {
    window.location.hash = '#manage/metricsx';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/metricsx');
  });

  it('does not touch a hash outside manage/', () => {
    window.location.hash = '#plan/metrics';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#plan/metrics');
  });
});

describe('ManageView: the Runs tab and the landing', () => {
  it('lists Runs first in its group, with no Pipeline Runs tab (it folded into Runs)', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-tab-runs')).toHaveTextContent('Runs');
    expect(screen.queryByTestId('manage-tab-pipelineruns')).toBeNull();
    expect(screen.queryByTestId('manage-tab-metrics')).toBeNull();
  });

  it('lands on Runs for a bare #manage', () => {
    window.location.hash = '#manage';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('follows #manage/runs/start and #manage/runs/job/5 as the Runs tab, keeping the path', () => {
    window.location.hash = '#manage/users';
    render(<ManageView onComplete={vi.fn()} />);

    goTo('#manage/runs/start');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
    expect(window.location.hash).toBe('#manage/runs/start');

    goTo('#manage/runs/job/5');
    expect(window.location.hash).toBe('#manage/runs/job/5');
  });
});

describe('ManageView: #manage/pipelineruns redirects to #manage/runs', () => {
  it('on mount, a cycle id becomes cycle/{id}, before the Runs view reads it', () => {
    window.location.hash = '#manage/pipelineruns/412';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: {"cycle":412}');
  });

  it('on hashchange from another tab', () => {
    window.location.hash = '#manage/users';
    render(<ManageView onComplete={vi.fn()} />);

    goTo('#manage/pipelineruns/412');

    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: {"cycle":412}');
  });

  it('hands the Runs view the redirected selection when it is already mounted on Runs', () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: none');

    goTo('#manage/pipelineruns/77');

    expect(window.location.hash).toBe('#manage/runs/cycle/77');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: {"cycle":77}');
  });

  it('the bare address and a non-numeric id both become the bare Runs tab', () => {
    window.location.hash = '#manage/pipelineruns';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/runs');

    goTo('#manage/pipelineruns/abc');
    expect(window.location.hash).toBe('#manage/runs');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected: none');
  });

  it('keeps a query', () => {
    window.location.hash = '#manage/pipelineruns/412?kind=cycles';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/runs/cycle/412?kind=cycles');
  });

  it('does not touch a hash that merely starts with the same letters', () => {
    window.location.hash = '#manage/pipelinerunsx';
    render(<ManageView onComplete={vi.fn()} />);
    expect(window.location.hash).toBe('#manage/pipelinerunsx');
  });

  it('rewrites the entry in place, adding no history entry beyond the assignment itself', () => {
    window.location.hash = '#manage/users';
    render(<ManageView onComplete={vi.fn()} />);
    const before = window.history.length;

    goTo('#manage/pipelineruns/9');

    expect(window.history.length).toBeLessThanOrEqual(before + 1);
    expect(window.location.hash).toBe('#manage/runs/cycle/9');
  });
});

describe('ManageView: the live count beside Runs', () => {
  // On another tab of the Runs group (so the sub-tab bar shows Runs): there the Runs view reports the count itself and ManageView sends no request of its own.
  beforeEach(() => {
    window.location.hash = '#manage/scheduler';
  });

  const layouts = [
    ['sidebar', () => { useIsWide.mockReturnValue(true); }],
    ['sub-tab bar', () => {}],
  ];

  it.each(layouts)('shows the server runningCount in the %s, once the poll lands', async (_name, setup) => {
    setup();
    fetchRuns.mockResolvedValue({ runningCount: 2 });
    render(<ManageView onComplete={vi.fn()} />);

    const badge = await screen.findByTestId('runs-live-count');
    expect(badge).toHaveTextContent('2');
    expect(badge).toHaveTextContent('2 running');
    expect(within(screen.getByTestId('manage-tab-runs')).getByTestId('runs-live-count')).toBe(badge);
    expect(fetchRuns).toHaveBeenCalledWith({ size: 1 });
  });

  it('shows it in the phone select as "Runs (2)"', async () => {
    useIsMobile.mockReturnValue(true);
    fetchRuns.mockResolvedValue({ runningCount: 2 });
    render(<ManageView onComplete={vi.fn()} />);
    expect(await screen.findByRole('option', { name: 'Runs (2)' })).toBeInTheDocument();
  });

  it('shows nothing at zero', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    await act(async () => {});
    expect(fetchRuns).toHaveBeenCalled();
    expect(screen.queryByTestId('runs-live-count')).toBeNull();
  });

  it('sends no request of its own on the Runs tab: only the Runs view reports there', async () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);
    await act(async () => {});
    expect(fetchRuns).not.toHaveBeenCalled();
  });

  it('keeps the count the Runs view reported when the reader leaves the Runs tab', async () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'report 3' }));
    expect(screen.getByTestId('runs-live-count')).toHaveTextContent('3');

    fireEvent.click(screen.getByTestId('manage-tab-scheduler'));
    expect(screen.getByTestId('runs-live-count')).toHaveTextContent('3');
  });

  it('follows the Runs view\'s own report, and clears when it reports none', async () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);
    await act(async () => {});
    expect(screen.queryByTestId('runs-live-count')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'report 3' }));
    expect(screen.getByTestId('runs-live-count')).toHaveTextContent('3');

    fireEvent.click(screen.getByRole('button', { name: 'report 0' }));
    expect(screen.queryByTestId('runs-live-count')).toBeNull();
  });

  it('keeps the last count when the poll fails', async () => {
    fetchRuns.mockResolvedValueOnce({ runningCount: 1 });
    render(<ManageView onComplete={vi.fn()} />);
    expect(await screen.findByTestId('runs-live-count')).toHaveTextContent('1');
    fetchRuns.mockRejectedValue(new Error('down'));
    await act(async () => { window.dispatchEvent(new Event('focus')); });
    expect(screen.getByTestId('runs-live-count')).toHaveTextContent('1');
  });
});
