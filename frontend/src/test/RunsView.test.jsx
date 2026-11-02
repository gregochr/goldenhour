import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RunsView from '../components/runs/RunsView.jsx';
import { setRewind } from '../utils/rewind.js';
import {
  batchInfo, cycleDetail, jobDetail, runsPage,
} from './runsFixtures.js';
import {
  cycleDayRow, dayResponse, dayRow, pastDayResponse,
} from './runsDayFixtures.js';

// The Runs tab end to end below ManageView, on the Day view: the panels by hash, Start a run, the progress panel, the
// address, the real clock, polling, and the rules that keep an open panel honest. The Day view's own rows, strip,
// calendar and states are in RunsDayView.test.jsx. The network is mocked at the API modules.

vi.mock('../api/runsApi.js', () => ({
  fetchRuns: vi.fn(), fetchJobRun: vi.fn(), fetchRunDay: vi.fn(), fetchRunCalendar: vi.fn(), fetchRunCalls: vi.fn(),
  fetchRunCost: vi.fn(), fetchRunTiming: vi.fn(),
}));
vi.mock('../api/pipelineRunApi.js', () => ({ fetchPipelineRunDetail: vi.fn() }));
vi.mock('../api/metricsApi', () => ({
  getApiCalls: vi.fn(), getBatchSummary: vi.fn(), getDispositionBreakdown: vi.fn(),
}));
vi.mock('../api/batchApi', () => ({ getRegions: vi.fn(), submitScheduledBatch: vi.fn(), submitJfdiBatch: vi.fn() }));
vi.mock('../api/forecastApi', () => ({ refreshTideData: vi.fn(), backfillTideData: vi.fn(), fetchLocations: vi.fn() }));
vi.mock('../api/auroraApi', () => ({ enrichBortle: vi.fn() }));
vi.mock('../api/briefingApi.js', () => ({ runBriefing: vi.fn() }));
vi.mock('../api/runProgressApi', () => ({ subscribeToRunProgress: vi.fn(() => () => {}) }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ isAdmin: true, role: 'ADMIN' }) }));
vi.mock('../components/AuroraForecastModal', () => ({
  default: ({ onClose }) => <div role="dialog" aria-label="Aurora forecast"><button type="button" onClick={onClose}>close</button></div>,
}));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null, loading: false }) }));
// jsdom has no layout: the split breakpoint (1000px) is decided here, wide unless a test says otherwise.
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import { fetchRuns, fetchJobRun, fetchRunDay, fetchRunCalls } from '../api/runsApi.js';
import { fetchPipelineRunDetail } from '../api/pipelineRunApi.js';
import { getApiCalls, getBatchSummary, getDispositionBreakdown } from '../api/metricsApi';
import { getRegions } from '../api/batchApi';
import { enrichBortle } from '../api/auroraApi';
import { runBriefing } from '../api/briefingApi.js';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { subscribeToRunProgress } from '../api/runProgressApi';
import {
  TWO_FAILURES, NO_FAILURES, completeEvent, createFeeds,
} from './runProgressFixtures.js';
import { fetchLocations } from '../api/forecastApi';

const noop = () => {};

/** The intervals the Runs view sets, captured so a test can fire one without waiting 60 real seconds. */
let intervals;

function captureIntervals() {
  intervals = [];
  const realSet = globalThis.setInterval;
  const realClear = globalThis.clearInterval;
  vi.spyOn(globalThis, 'setInterval').mockImplementation((fn, ms, ...rest) => {
    if (ms === 60000 || ms === 15000) {
      const handle = { fn, ms, cleared: false, id: 9_000_000 + intervals.length };
      intervals.push(handle);
      return handle.id;
    }
    return realSet(fn, ms, ...rest);
  });
  vi.spyOn(globalThis, 'clearInterval').mockImplementation((id) => {
    const handle = intervals.find((h) => h.id === id);
    if (handle) handle.cleared = true;
    else realClear(id);
  });
}

/** The polls currently running (not yet cleared): the live head's, and the Day view's while it is on today. */
const livePolls = () => intervals.filter((h) => !h.cleared);

/** Fires every running poll once, as their timers would. */
async function tick() {
  await act(async () => { livePolls().forEach((h) => h.fn()); });
}

const FIRST_DAY = [
  cycleDayRow({ status: 'RUNNING', endedAt: null, durationSeconds: null }),
  dayRow({ id: 'job-9001', jobRunId: 9001 }),
  dayRow({
    id: 'job-9002', jobRunId: 9002, type: 'BRIEFING', typeLabel: 'Daily briefing', name: 'Briefing refresh',
    startedAt: '2026-11-02T13:22:00Z', durationSeconds: 48,
  }),
];

function renderView(props = {}) {
  return render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} {...props} />);
}

/** Opens a Day row's detail panel the way a reader does: expand the row, then follow "Open run detail". */
async function openRun(id) {
  fireEvent.click(await screen.findByTestId(`runs-day-row-${id}`));
  fireEvent.click(await screen.findByTestId(`runs-row-${id}`));
}

/** Chooses a Start a run option and presses Start. */
async function chooseAndStart(key) {
  fireEvent.click(await screen.findByTestId(`start-option-${key}`));
  await act(async () => { fireEvent.click(screen.getByTestId('start-run-submit')); });
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/runs';
  window.localStorage.clear();
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  captureIntervals();
  fetchRuns.mockResolvedValue(runsPage([]));
  fetchRunDay.mockResolvedValue(dayResponse(FIRST_DAY));
  fetchRunCalls.mockResolvedValue({ runId: 'job-9001', logged: true, services: [] });
  fetchJobRun.mockResolvedValue(jobDetail({ id: 9001 }));
  fetchPipelineRunDetail.mockResolvedValue(cycleDetail());
  getApiCalls.mockResolvedValue({ data: [] });
  getBatchSummary.mockRejectedValue(new Error('not a batch'));
  getDispositionBreakdown.mockResolvedValue({ data: { jobRunId: 0, totalCount: 0, countsByDisposition: {}, entries: [] } });
  getRegions.mockResolvedValue([]);
  fetchLocations.mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
  setRewind(null);
});

describe('Runs: the header', () => {
  it('shows "Updated {HH:MM} · refreshes every minute" once the day has loaded', async () => {
    renderView();
    expect(await screen.findByTestId('runs-updated')).toHaveTextContent(/^Updated \d\d:\d\d \u00b7 refreshes every minute$/);
  });

  it('Refresh fetches the live head and the day again (one refresh for both)', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    const liveBefore = fetchRuns.mock.calls.length;
    const dayBefore = fetchRunDay.mock.calls.length;
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(fetchRuns.mock.calls.length).toBe(liveBefore + 1));
    expect(fetchRunDay.mock.calls.length).toBe(dayBefore + 1);
  });

  it('has a Runs heading that can take focus', async () => {
    renderView();
    const heading = await screen.findByRole('heading', { name: 'Runs' });
    expect(heading).toHaveAttribute('tabindex', '-1');
  });

  it('pushes Refresh and Start a run to the right of a wrapped header', async () => {
    renderView();
    const group = (await screen.findByTestId('runs-refresh-btn')).parentElement;
    expect(group.className).toContain('ml-auto');
    expect(group).toContainElement(screen.getByTestId('start-run-btn'));
  });
});

describe('Runs: opening a run', () => {
  it('opens a job run\'s panel by hash, loaded by its id', async () => {
    renderView();
    await openRun('job-9001');
    expect(window.location.hash).toBe('#manage/runs/job/9001');
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    expect(fetchJobRun).toHaveBeenCalledWith(9001);
    expect(screen.getByTestId('job-run-panel-title')).toHaveTextContent('Tide refresh');
  });

  it('marks the open row, and gives the panel a name but never a dialog role', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    expect(screen.getByTestId('runs-day-row-job-9001')).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('complementary', { name: 'Job run 9001' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.querySelector('[aria-modal="true"]')).toBeNull();
  });

  it('opens a deep link whose run is not on the loaded page', async () => {
    window.location.hash = '#manage/runs/job/4444';
    fetchJobRun.mockResolvedValue(jobDetail({ id: 4444 }, { name: 'Far-off run' }));
    renderView();
    expect(await screen.findByTestId('job-run-panel-title')).toHaveTextContent('Far-off run');
    expect(fetchJobRun).toHaveBeenCalledWith(4444);
  });

  it('opens a cycle row in the cycle panel, not as a job run', async () => {
    renderView();
    await openRun('cycle-412');
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(await screen.findByTestId('cycle-panel-title')).toHaveTextContent('Afternoon refresh');
    expect(screen.getByRole('complementary', { name: 'Cycle #412' })).toBeInTheDocument();
    expect(fetchPipelineRunDetail).toHaveBeenCalledWith(412);
    expect(fetchJobRun).not.toHaveBeenCalled();
  });

  it('opens a job run from the keyboard: Enter expands the row, Enter on "Open run detail" opens the panel', async () => {
    const user = userEvent.setup();
    renderView();
    (await screen.findByTestId('runs-day-row-job-9001')).focus();
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('runs-day-row-job-9001')).toHaveAttribute('aria-expanded', 'true');
    screen.getByTestId('runs-row-job-9001').focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('hands focus back to the "Open run detail" link when the panel closes', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    fireEvent.click(screen.getByTestId('job-run-panel-close'));
    await waitFor(() => expect(screen.getByTestId('runs-row-job-9001')).toHaveFocus());
  });

  it('returns focus to a cycle\'s link the same way (F8)', async () => {
    renderView();
    await openRun('cycle-412');
    await screen.findByTestId('cycle-panel');
    fireEvent.click(screen.getByTestId('cycle-panel-close'));
    await waitFor(() => expect(screen.getByTestId('runs-row-cycle-412')).toHaveFocus());
  });

  it('closes the panel from its x, leaving the bare tab address', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    fireEvent.click(screen.getByTestId('job-run-panel-close'));
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('closes the panel on Escape', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
  });

  it('follows the hash when it moves (Back, a pasted link)', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    fetchJobRun.mockResolvedValue(jobDetail({ id: 9002 }, { name: 'Briefing refresh', group: 'BRIEFING' }));
    act(() => {
      window.location.hash = '#manage/runs/job/9002';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(await screen.findByTestId('job-run-panel-title')).toHaveTextContent('Briefing refresh');
    act(() => {
      window.location.hash = '#manage/runs';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
  });

  it('leaves the bare tab address for a hash naming no job run, replacing the entry', async () => {
    window.location.hash = '#manage/runs/job/999999';
    fetchJobRun.mockRejectedValue({ response: { status: 404 } });
    renderView();
    await waitFor(() => expect(window.location.hash).toBe('#manage/runs'));
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
  });

  it('shows the load error in the panel, and keeps the address', async () => {
    window.location.hash = '#manage/runs/job/9001';
    fetchJobRun.mockRejectedValue(Object.assign(new Error('x'), { response: { status: 500, data: { error: 'Server broke' } } }));
    renderView();
    expect(await screen.findByTestId('job-run-panel-state')).toHaveTextContent('Server broke');
    expect(window.location.hash).toBe('#manage/runs/job/9001');
  });

  it('refetches the open job run on each list poll, without blanking it', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    const before = fetchJobRun.mock.calls.length;
    fetchJobRun.mockResolvedValue(jobDetail({ id: 9001 }, { name: 'Tide refresh', status: 'FAILED', note: 'Did not finish', noteTone: 'ERROR' }));

    await tick();

    await waitFor(() => expect(fetchJobRun.mock.calls.length).toBe(before + 1));
    expect(await screen.findByTestId('job-run-note')).toHaveTextContent('Did not finish');
    expect(screen.getByTestId('job-run-panel-title')).toHaveTextContent('Tide refresh');
  });

  it('opens a batch panel titled by its batch id', async () => {
    window.location.hash = '#manage/runs/job/9876';
    fetchJobRun.mockResolvedValue(jobDetail(
      { id: 9876, runType: 'SCHEDULED_BATCH' },
      { group: 'BATCHES', name: 'Scheduled batch', batch: batchInfo({ anthropicBatchId: 'msgbatch_01JBXKQ7Q7Q7Q7Q7Q7Q7' }) },
    ));
    renderView();
    expect(await screen.findByTestId('job-run-panel-title')).toHaveTextContent('msgbatch_01JBX\u2026Q7');
  });
});

describe('Runs: Start a run', () => {
  it('opens the Start a run panel at #manage/runs/start', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    fireEvent.click(screen.getByTestId('start-run-btn'));
    expect(window.location.hash).toBe('#manage/runs/start');
    expect(await screen.findByTestId('start-run-panel-title')).toHaveTextContent('Start a run');
    expect(screen.getByTestId('start-option-scheduled-batch')).toBeInTheDocument();
  });

  it('opens it straight from the hash (the aurora banner\'s door)', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    expect(await screen.findByTestId('start-run-panel-title')).toBeInTheDocument();
    expect(screen.getByTestId('aurora-simulate-btn')).toBeInTheDocument();
  });

  it('closes on Escape, but not while a modal (the aurora forecast\'s) is open over it', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    fireEvent.click(await screen.findByTestId('start-option-aurora-forecast'));
    fireEvent.click(screen.getByTestId('start-run-submit'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.getByTestId('start-run-panel-title')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'close' }));
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('does not close on Escape typed in a text field outside the panel\'s own form', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    await screen.findByTestId('start-run-panel-title');
    const field = document.createElement('input');
    field.type = 'text';
    document.body.appendChild(field);
    field.focus();
    fireEvent.keyDown(field, { key: 'Escape' });
    expect(screen.getByTestId('start-run-panel-title')).toBeInTheDocument();
    field.remove();
  });

  it('opening a job run replaces the Start panel (one panel at a time)', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    await openRun('job-9001');
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
  });

  it('reloads the live head and the day when a start adds a run, closes the panel and toasts the server\'s line', async () => {
    window.location.hash = '#manage/runs/start';
    runBriefing.mockResolvedValue({ status: 'Briefing refreshed.' });
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    const before = fetchRuns.mock.calls.length;
    const dayBefore = fetchRunDay.mock.calls.length;

    fireEvent.click(screen.getByTestId('start-option-briefing'));
    await act(async () => { fireEvent.click(screen.getByTestId('start-run-submit')); });

    await waitFor(() => expect(fetchRuns.mock.calls.length).toBe(before + 1));
    expect(fetchRunDay.mock.calls.length).toBe(dayBefore + 1);
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
    expect(screen.getByTestId('sub-region-toast')).toHaveTextContent('Briefing refreshed.');
  });

  it('toasts "Started." when the server\'s response carries no status', async () => {
    window.location.hash = '#manage/runs/start';
    runBriefing.mockResolvedValue({});
    renderView();
    fireEvent.click(await screen.findByTestId('start-option-briefing'));
    await act(async () => { fireEvent.click(screen.getByTestId('start-run-submit')); });
    expect(screen.getByTestId('sub-region-toast')).toHaveTextContent('Started.');
  });
});

describe('Runs: an open panel is the reader\'s alone (F7)', () => {
  /** Opens job run 4444 by deep link, a run that is NOT on the day on screen. */
  async function openFarRun() {
    window.location.hash = '#manage/runs/job/4444';
    fetchJobRun.mockResolvedValue(jobDetail({ id: 4444 }, { group: 'TIDE', status: 'DONE', name: 'Far-off run' }));
    renderView();
    await screen.findByTestId('job-run-panel');
    await screen.findByTestId('runs-day-row-cycle-412');
  }

  it.each(['cycles', 'attention', 'other'])('stays open when the %s chip is pressed', async (chip) => {
    await openFarRun();
    fireEvent.click(screen.getByTestId(`runs-chip-${chip}`));
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('stays open when the day changes', async () => {
    await openFarRun();
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()]));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('stays open through a data refresh, whatever the day now holds', async () => {
    await openFarRun();
    fetchRunDay.mockResolvedValue(dayResponse([]));
    await tick();
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('does not close the Start panel when a chip is pressed', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    await screen.findByTestId('start-run-panel-title');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(screen.getByTestId('start-run-panel-title')).toBeInTheDocument();
  });
});

describe('Runs: the address (F4)', () => {
  beforeEach(() => {
    // The real "now" is pinned (Date only, so timers still run): "Yesterday" and "today" are read from it.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-02T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps the view and the day through opening a panel, closing it and a reload', async () => {
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()]));
    window.location.hash = '#manage/runs?view=day&date=2026-11-01';
    const first = renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    expect(window.location.hash).toBe('#manage/runs/job/9001?view=day&date=2026-11-01');
    fireEvent.click(screen.getByTestId('job-run-panel-close'));
    expect(window.location.hash).toBe('#manage/runs?view=day&date=2026-11-01');

    first.unmount();
    renderView();
    await waitFor(() => expect(fetchRunDay).toHaveBeenLastCalledWith('2026-11-01'));
    expect(await screen.findByTestId('runs-day-word')).toHaveTextContent('Yesterday');
  });

  it('writes the filter and the view into the hash with replaceState (no history entry)', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    const entries = window.history.length;
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(window.location.hash).toBe('#manage/runs?filter=cycles');
    fireEvent.click(screen.getByTestId('runs-view-tab-cost'));
    expect(window.location.hash).toBe('#manage/runs?filter=cycles&view=cost');
    expect(window.history.length).toBe(entries);
    expect(window.localStorage.getItem('photocast.runs.view')).toBe('cost');
  });

  it('opens the remembered view when the address names none', async () => {
    window.localStorage.setItem('photocast.runs.view', 'timing');
    renderView();
    expect(await screen.findByTestId('runs-timing-loading')).toBeInTheDocument();
  });

  it('maps the old ?range=&kind=&attention= links to a Day filter once, and rewrites them out', async () => {
    window.location.hash = '#manage/runs?range=today&kind=aurora';
    renderView();
    await screen.findByTestId('runs-chip-all');
    expect(window.location.hash).toBe('#manage/runs?filter=other');
    expect(screen.getByTestId('runs-chip-other')).toHaveAttribute('aria-pressed', 'true');
  });

  it('maps attention=true to the Need attention chip', async () => {
    window.location.hash = '#manage/runs?kind=cycles&attention=true';
    renderView();
    await screen.findByTestId('runs-chip-all');
    expect(screen.getByTestId('runs-chip-attention')).toHaveAttribute('aria-pressed', 'true');
    expect(window.location.hash).toBe('#manage/runs?filter=attention');
  });

  it('writes no date for today, and asks the server for today without one', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    expect(fetchRunDay).toHaveBeenCalledWith(null);
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('clamps a date the server refuses to the earliest day, replacing the entry', async () => {
    window.location.hash = '#manage/runs?view=day&date=2020-01-01';
    fetchRunDay.mockImplementation((date) => (date
      ? Promise.reject({ response: { status: 400, data: { error: 'date is before the earliest day' } } })
      : Promise.resolve(dayResponse(FIRST_DAY, { earliestDay: '2026-09-20' }))));
    renderView();
    await waitFor(() => expect(window.location.hash).toBe('#manage/runs?view=day&date=2026-09-20'));
  });

  it('drops a future date from the address without asking the server for it', async () => {
    window.location.hash = '#manage/runs?view=day&date=2999-01-01';
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    expect(fetchRunDay).not.toHaveBeenCalledWith('2999-01-01');
    expect(fetchRunDay).toHaveBeenCalledWith(null);
  });
});

describe('Runs: the live banner stays on every view', () => {
  const LIVE = () => runsPage([], { runningCount: 1, liveCycleId: 412 });

  it.each(['day', 'cost', 'timing'])('is above the view tabs on the %s view', async (view) => {
    fetchRuns.mockResolvedValue(LIVE());
    window.location.hash = `#manage/runs?view=${view}`;
    renderView();
    const strip = await screen.findByTestId('runs-live-strip');
    const tabs = screen.getByTestId('runs-view-tabs');
    expect(strip.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('is titled by the cycle type when the Day on screen holds no row for it', async () => {
    fetchRuns.mockResolvedValue(LIVE());
    fetchRunDay.mockResolvedValue(dayResponse([]));
    renderView();
    expect(await screen.findByTestId('runs-live-title')).toHaveTextContent('Afternoon refresh is running');
  });

  it('is absent when the server names no running cycle', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    expect(screen.queryByTestId('runs-live-strip')).toBeNull();
  });
});

describe('Runs: the real clock, never the rewind clock (R-D7)', () => {
  beforeEach(() => {
    // The real "now" is pinned (Date only, so timers still run); Rewind will say a different day.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-02T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps the Day tab\'s "Yesterday" on the real day when a rewind is set', async () => {
    setRewind('2026-01-15T09:00:00Z');
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()]));
    window.location.hash = '#manage/runs?view=day&date=2026-11-01';
    renderView();
    expect(await screen.findByTestId('runs-day-word')).toHaveTextContent('Yesterday');
    expect(screen.getByTestId('runs-view-tab-day-word')).toHaveTextContent('Yesterday');
  });

  it('keeps a running run\'s "so far" and the next-up wait on the real clock when a rewind is set', async () => {
    setRewind('2026-01-15T09:00:00Z');
    fetchRunDay.mockResolvedValue(dayResponse([
      cycleDayRow({ status: 'RUNNING', endedAt: null, durationSeconds: null, startedAt: '2026-11-02T10:22:00Z' }),
    ]));
    renderView();
    // 10:22Z to the real 12:00Z.
    expect(await screen.findByTestId('runs-day-duration-cycle-412')).toHaveTextContent('1h 38m so far');
    // The served next-up is 12:39Z: 39 minutes after the real 12:00Z.
    expect(screen.getByTestId('runs-summary-next-sub')).toHaveTextContent('in 39m');
  });

  it('reads the same with no rewind set', async () => {
    fetchRunDay.mockResolvedValue(dayResponse([
      cycleDayRow({ status: 'RUNNING', endedAt: null, durationSeconds: null, startedAt: '2026-11-02T10:22:00Z' }),
    ]));
    renderView();
    expect(await screen.findByTestId('runs-day-duration-cycle-412')).toHaveTextContent('1h 38m so far');
  });
});

describe('Runs: polling the day and the live head', () => {
  it('polls every 60 seconds while nothing is running', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    expect(livePolls().map((h) => h.ms)).toEqual([60000, 60000]);
  });

  it('polls the live head every 15 seconds while a run is running', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 1, liveCycleId: 412 }));
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    await waitFor(() => expect(livePolls().map((h) => h.ms)).toContain(15000));
  });

  it('goes back to 60 seconds when the last run finishes', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 1, liveCycleId: 412 }));
    renderView();
    await waitFor(() => expect(livePolls().map((h) => h.ms)).toContain(15000));
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 0, liveCycleId: null }));
    await tick();
    await waitFor(() => expect(livePolls().map((h) => h.ms)).not.toContain(15000));
  });

  it('does not poll a past day (it cannot change)', async () => {
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()]));
    window.location.hash = '#manage/runs?view=day&date=2026-11-01';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    const before = fetchRunDay.mock.calls.length;
    await tick();
    expect(fetchRunDay.mock.calls.length).toBe(before);
  });

  it('keeps what is on screen when a poll fails', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    fetchRunDay.mockRejectedValue(new Error('down'));
    await tick();
    expect(screen.getByTestId('runs-day-row-cycle-412')).toBeInTheDocument();
    expect(await screen.findByTestId('runs-day-stale')).toBeInTheDocument();
  });

  it('reports the running count to ManageView on each live refresh', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 2, liveCycleId: null }));
    const onLiveCountChange = vi.fn();
    renderView({ onLiveCountChange });
    await waitFor(() => expect(onLiveCountChange).toHaveBeenCalledWith(2));
    const calls = onLiveCountChange.mock.calls.length;
    await tick();
    await waitFor(() => expect(onLiveCountChange.mock.calls.length).toBeGreaterThan(calls));
  });

  it('refetches the open job run on each poll, without blanking it', async () => {
    renderView();
    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    const before = fetchJobRun.mock.calls.length;
    fetchJobRun.mockResolvedValue(jobDetail({ id: 9001 }, { name: 'Tide refresh', status: 'FAILED', note: 'Did not finish', noteTone: 'ERROR' }));

    await tick();

    await waitFor(() => expect(fetchJobRun.mock.calls.length).toBeGreaterThan(before));
    expect(await screen.findByTestId('job-run-note')).toHaveTextContent('Did not finish');
    expect(screen.getByTestId('job-run-panel-title')).toHaveTextContent('Tide refresh');
  });

  it('stops polling when the view goes', async () => {
    const view = renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    view.unmount();
    expect(livePolls()).toHaveLength(0);
  });
});

describe('Runs: polling stands down while the Operations pane is hidden', () => {
  /** Renders the view inside a host that can be hidden, as the Operations pane is while Map or Plan is showing. */
  function renderInHost(hidden) {
    const host = document.createElement('div');
    document.body.appendChild(host);
    host.hidden = hidden;
    render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />, { container: host });
    return host;
  }

  it('skips a tick while a hidden ancestor holds the pane, and resumes when it is shown', async () => {
    const host = renderInHost(true);
    await waitFor(() => expect(screen.getByTestId('runs-day-row-cycle-412')).toBeInTheDocument());
    const liveLoaded = fetchRuns.mock.calls.length;
    const dayLoaded = fetchRunDay.mock.calls.length;

    await tick();
    expect(fetchRuns.mock.calls.length).toBe(liveLoaded);
    expect(fetchRunDay.mock.calls.length).toBe(dayLoaded);

    host.hidden = false;
    await tick();
    expect(fetchRuns.mock.calls.length).toBe(liveLoaded + 1);
    expect(fetchRunDay.mock.calls.length).toBe(dayLoaded + 1);
    host.remove();
  });

  it('still loads the first day and the live head when the view mounts hidden', async () => {
    const host = renderInHost(true);
    await waitFor(() => expect(fetchRuns).toHaveBeenCalledTimes(1));
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    host.remove();
  });

  it('is driven by the shared visible-page poll: it stops while the browser tab is hidden', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-cycle-412');
    const before = livePolls();
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(before.every((h) => h.cleared)).toBe(true);
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
  });
});

describe('Runs: a start that returns a run id', () => {
  beforeEach(() => {
    window.location.hash = '#manage/runs/start';
    enrichBortle.mockResolvedValue({ jobRunId: 77 });
    fetchJobRun.mockResolvedValue(jobDetail({ id: 77 }));
  });

  it.each([['at 1000px and up', true], ['below 1000px', false]])(
    'hands the run id over and opens that run\'s panel, replacing the Start panel, %s',
    async (_label, split) => {
      useIsSplitLayout.mockReturnValue(split);
      const onActiveRunChange = vi.fn();
      renderView({ onActiveRunChange });

      await chooseAndStart('light-pollution');

      expect(onActiveRunChange).toHaveBeenCalledWith(77);
      expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
      expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
      expect(window.location.hash).toBe('#manage/runs/job/77');
      expect(fetchJobRun).toHaveBeenCalledWith(77);
    },
  );

  it('keeps one progress panel mounted across the switch', async () => {
    useIsSplitLayout.mockReturnValue(false);
    const { rerender } = render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />);
    await chooseAndStart('light-pollution');
    rerender(<RunsView activeRunId={77} onActiveRunChange={noop} onActiveRunClear={noop} />);
    expect(screen.getAllByTestId('run-progress-panel')).toHaveLength(1);
  });

  it('reloads the list after handing the run over, for a batch that returns a run id', async () => {
    useIsSplitLayout.mockReturnValue(false);
    const { submitScheduledBatch } = await import('../api/batchApi');
    submitScheduledBatch.mockResolvedValue({ jobRunId: 88 });
    fetchJobRun.mockResolvedValue(jobDetail({ id: 88 }));
    const calls = [];
    const onActiveRunChange = vi.fn(() => calls.push('active'));
    renderView({ onActiveRunChange });
    await screen.findByTestId('start-option-scheduled-batch');
    fetchRuns.mockImplementation(() => { calls.push('reload'); return Promise.resolve(runsPage([])); });

    await chooseAndStart('scheduled-batch');

    await waitFor(() => expect(calls).toContain('reload'));
    expect(calls.indexOf('active')).toBeLessThan(calls.indexOf('reload'));
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs/job/88');
  });

  it('does not pull the reader off another panel they opened while the start was in flight', async () => {
    let finish;
    enrichBortle.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const onActiveRunChange = vi.fn();
    renderView({ onActiveRunChange });
    await chooseAndStart('light-pollution');

    await openRun('job-9001');
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    await act(async () => { finish({ jobRunId: 77 }); });

    expect(onActiveRunChange).toHaveBeenCalledWith(77);
    expect(window.location.hash).toBe('#manage/runs/job/9001');
  });

  it('opens no panel when the reader closed the Start panel and the run id arrives afterwards', async () => {
    let finish;
    enrichBortle.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const onActiveRunChange = vi.fn();
    renderView({ onActiveRunChange });
    await chooseAndStart('light-pollution');

    fireEvent.click(screen.getByTestId('start-run-panel-close'));
    expect(window.location.hash).toBe('#manage/runs');
    await act(async () => { finish({ jobRunId: 77 }); });

    expect(onActiveRunChange).toHaveBeenCalledWith(77);
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('shows no finish toast while the Operations pane is hidden', async () => {
    runBriefing.mockResolvedValue({ status: 'Briefing refreshed.' });
    render(<div hidden><RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} /></div>);
    await chooseAndStart('briefing');
    expect(screen.queryByTestId('sub-region-toast')).toBeNull();
  });

  it('does not close a panel the reader opened while a start with no id was in flight', async () => {
    let finish;
    runBriefing.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    renderView();
    await chooseAndStart('briefing');

    await openRun('job-9001');
    await screen.findByTestId('job-run-panel');
    await act(async () => { finish({ status: 'Briefing refreshed.' }); });

    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
    expect(screen.getByTestId('sub-region-toast')).toHaveTextContent('Briefing refreshed.');
  });
});

describe('Runs: Start a run keeps its guard and message when closed mid-request', () => {
  it('keeps every start disabled after closing and reopening, then enables them and toasts when it finishes', async () => {
    window.location.hash = '#manage/runs/start';
    let finish;
    runBriefing.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    renderView();
    await chooseAndStart('briefing');

    fireEvent.click(screen.getByTestId('start-run-panel-close'));
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
    fireEvent.click(screen.getByTestId('start-run-btn'));

    expect(await screen.findByTestId('start-run-panel-title')).toBeInTheDocument();
    expect(screen.getByTestId('start-option-briefing')).toBeDisabled();
    expect(screen.getByTestId('start-option-scheduled-batch')).toBeDisabled();
    expect(screen.getByTestId('start-run-submit')).toHaveAttribute('aria-disabled', 'true');

    await act(async () => { finish({ status: 'Briefing refreshed.' }); });
    // The reader reopened the panel while the briefing ran, so its finish closes it (it is the open panel again).
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
    expect(screen.getByTestId('sub-region-toast')).toHaveTextContent('Briefing refreshed.');
  });

  it('keeps the guard and a failure message through a close and reopen', async () => {
    window.location.hash = '#manage/runs/start';
    runBriefing.mockRejectedValue({ response: { status: 409 } });
    renderView();
    await chooseAndStart('briefing');
    fireEvent.click(screen.getByTestId('start-run-panel-close'));
    fireEvent.click(screen.getByTestId('start-run-btn'));
    expect(await screen.findByTestId('start-run-status')).toHaveTextContent('A run is already in progress. Wait for it to finish.');
    expect(screen.getByTestId('start-option-briefing')).toBeEnabled();
  });
});

describe('Runs: focus when Start a run closes', () => {
  it('returns to the Start a run button', async () => {
    renderView();
    fireEvent.click(await screen.findByTestId('start-run-btn'));
    await screen.findByTestId('start-run-panel-title');

    fireEvent.click(screen.getByTestId('start-run-panel-close'));

    await waitFor(() => expect(screen.getByTestId('start-run-btn')).toHaveFocus());
  });
});

describe('Runs: where the live progress sits for the run that is open', () => {
  const progressCalls = () => screen.queryAllByTestId('run-progress-panel');

  beforeEach(() => {
    window.location.hash = '#manage/runs/job/77';
    fetchJobRun.mockResolvedValue(jobDetail({ id: 77 }));
  });

  it('below 1000px sits inside the job-run panel, once, under "Following this run live below."', async () => {
    useIsSplitLayout.mockReturnValue(false);
    renderView({ activeRunId: 77 });

    const note = await screen.findByTestId('job-run-progress-note');
    expect(note).toHaveTextContent('Following this run live below.');
    expect(note).toContainElement(screen.getByTestId('run-progress-panel'));
    expect(progressCalls()).toHaveLength(1);
    expect(screen.getByTestId('runs-list-pane')).not.toContainElement(screen.getByTestId('run-progress-panel'));
  });

  it('at 1000px and up stays above the list, once, and the panel points at it', async () => {
    useIsSplitLayout.mockReturnValue(true);
    renderView({ activeRunId: 77 });

    const note = await screen.findByTestId('job-run-progress-note');
    expect(note).toHaveTextContent('Following this run live in the progress panel on the Runs list.');
    expect(note).not.toContainElement(screen.getByTestId('run-progress-panel'));
    expect(screen.getByTestId('runs-list-pane')).toContainElement(screen.getByTestId('run-progress-panel'));
    expect(progressCalls()).toHaveLength(1);
  });

  it('below 1000px keeps the progress on the list when another run\'s panel is open', async () => {
    useIsSplitLayout.mockReturnValue(false);
    window.location.hash = '#manage/runs/job/9001';
    renderView({ activeRunId: 77 });
    await screen.findByTestId('job-run-panel');
    expect(screen.getByTestId('runs-list-pane')).toContainElement(screen.getByTestId('run-progress-panel'));
    expect(progressCalls()).toHaveLength(1);
  });

  it('below 1000px, a run that needs attention keeps its Dismiss inside the panel, and Dismiss clears it', async () => {
    useIsSplitLayout.mockReturnValue(false);
    const feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
    const onActiveRunClear = vi.fn();
    renderView({ activeRunId: 77, onActiveRunClear });
    await screen.findByTestId('run-progress-panel');

    await act(async () => {
      TWO_FAILURES.forEach((t) => feed.feeds[77].onTask(t));
      feed.feeds[77].onComplete(completeEvent(77, TWO_FAILURES));
    });
    const dismiss = await screen.findByRole('button', { name: 'Dismiss run progress' });
    expect(screen.getByTestId('job-run-progress-note')).toContainElement(dismiss);
    fireEvent.click(dismiss);
    expect(onActiveRunClear).toHaveBeenCalledTimes(1);
    expect(feed.subscriptions).toEqual([77]);
  });

  it('at 1000px and up, Dismiss works from above the list too', async () => {
    useIsSplitLayout.mockReturnValue(true);
    const feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
    const onActiveRunClear = vi.fn();
    renderView({ activeRunId: 77, onActiveRunClear });
    await screen.findByTestId('run-progress-panel');

    await act(async () => {
      TWO_FAILURES.forEach((t) => feed.feeds[77].onTask(t));
      feed.feeds[77].onComplete(completeEvent(77, TWO_FAILURES));
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss run progress' }));
    expect(onActiveRunClear).toHaveBeenCalledTimes(1);
  });

  it.each([['below 1000px', false], ['at 1000px and up', true]])('auto-clears a clean finish %s', async (_label, split) => {
    useIsSplitLayout.mockReturnValue(split);
    const feed = createFeeds();
    subscribeToRunProgress.mockImplementation(feed.subscribe);
    const onActiveRunClear = vi.fn();
    renderView({ activeRunId: 77, onActiveRunClear });
    await screen.findByTestId('run-progress-panel');

    await act(async () => {
      NO_FAILURES.forEach((t) => feed.feeds[77].onTask(t));
      feed.feeds[77].onComplete(completeEvent(77, NO_FAILURES));
    });

    expect(onActiveRunClear).toHaveBeenCalledTimes(1);
  });
});
