import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import ManageView from '../components/ManageView.jsx';
import useHashSelection from '../hooks/useHashSelection.js';

// Module-level, like the Runs tab's own parser, so the hook's listener stays stable.
const parseRuns = (segments) => (
  segments.length === 2 && segments[0] === 'job' ? { job: Number(segments[1]) } : null
);

// The Runs hashes (runs-redesign-plan.md §4): the Job Runs tab became Runs, so the old #manage/metrics redirects in
// place, on mount and on hashchange; a cycle row opens Pipeline Runs on that cycle until the cycle panel is built.

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
  default: function RunsProbe() {
    const { selectedId } = useHashSelection('runs', { parse: parseRuns });
    return <div data-testid="stub-view">Runs selected: {selectedId ? JSON.stringify(selectedId) : 'none'}</div>;
  },
}));
// The Pipeline Runs stub shows the cycle ManageView handed it, so a hash that failed to open one is seen.
vi.mock('../components/PipelineRunsView.jsx', () => ({
  default: function PipelineProbe({ activeRunId, onCloseDetail, onSelectRun }) {
    return (
      <div data-testid="stub-view">
        Pipeline Runs open: {activeRunId ?? 'none'}
        <button type="button" onClick={() => onSelectRun(77)}>pick 77</button>
        <button type="button" onClick={onCloseDetail}>close detail</button>
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
  it('lists Runs first in its group, with Pipeline Runs kept for this phase', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-tab-runs')).toHaveTextContent('Runs');
    expect(screen.getByTestId('manage-tab-pipelineruns')).toHaveTextContent('Pipeline Runs');
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

describe('ManageView: a cycle row opens Pipeline Runs on that cycle (until R2b)', () => {
  it('opens the cycle named by #manage/pipelineruns/{id} on mount', () => {
    window.location.hash = '#manage/pipelineruns/412';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: 412');
  });

  it('opens it on hashchange from the Runs tab', () => {
    window.location.hash = '#manage/runs';
    render(<ManageView onComplete={vi.fn()} />);

    goTo('#manage/pipelineruns/412');

    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: 412');
    expect(window.location.hash).toBe('#manage/pipelineruns/412');
  });

  it('shows the bare list for the bare Pipeline Runs address', () => {
    window.location.hash = '#manage/pipelineruns';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: none');
  });

  it('ignores a non-numeric id', () => {
    window.location.hash = '#manage/pipelineruns/abc';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: none');
  });

  it('arriving on Pipeline Runs by a bare address keeps the cycle that was open', () => {
    window.location.hash = '#manage/pipelineruns/412';
    render(<ManageView onComplete={vi.fn()} />);
    goTo('#manage/runs');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs selected');

    goTo('#manage/pipelineruns');

    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: 412');
  });

  it('picking another cycle rewrites the cycle address in place, so a reload does not reopen the old one', () => {
    window.location.hash = '#manage/pipelineruns/412';
    render(<ManageView onComplete={vi.fn()} />);
    const before = window.history.length;

    act(() => { screen.getByRole('button', { name: 'pick 77' }).click(); });

    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: 77');
    expect(window.location.hash).toBe('#manage/pipelineruns/77');
    expect(window.history.length).toBe(before);
  });

  it('leaves a bare Pipeline Runs address alone when a cycle is picked', () => {
    window.location.hash = '#manage/pipelineruns';
    render(<ManageView onComplete={vi.fn()} />);
    act(() => { screen.getByRole('button', { name: 'pick 77' }).click(); });
    expect(window.location.hash).toBe('#manage/pipelineruns');
  });

  it('closing the detail leaves the bare address, so Back does not land on the cycle again', () => {
    window.location.hash = '#manage/pipelineruns/412';
    render(<ManageView onComplete={vi.fn()} />);

    act(() => { screen.getByRole('button', { name: 'close detail' }).click(); });

    expect(screen.getByTestId('stub-view')).toHaveTextContent('Pipeline Runs open: none');
    expect(window.location.hash).toBe('#manage/pipelineruns');
  });
});
