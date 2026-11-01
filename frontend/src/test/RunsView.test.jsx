import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor, within } from '@testing-library/react';
import RunsView from '../components/runs/RunsView.jsx';
import { setRewind } from '../utils/rewind.js';
import {
  batchInfo, cycleDetail, cycleItem, jobDetail, jobItem, runsPage, summary,
} from './runsFixtures.js';

// The Runs tab end to end below ManageView: the served merge, the chips and summary, paging and polling, the panel by
// hash, and the rules that keep an open panel and the real clock honest. The network is mocked at the API modules.

vi.mock('../api/runsApi.js', () => ({ fetchRuns: vi.fn(), fetchJobRun: vi.fn() }));
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

import { fetchRuns, fetchJobRun } from '../api/runsApi.js';
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
import { mergeHead } from '../hooks/useRunsList.js';
import { fetchLocations } from '../api/forecastApi';

const noop = () => {};

/** The intervals the Runs list sets, captured so a test can fire one without waiting 60 real seconds. */
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

/** The poll currently running (not yet cleared), by its period. */
const livePoll = () => intervals.filter((h) => !h.cleared).at(-1);

/** Fires the live poll once, as its timer would. */
async function tick() {
  await act(async () => { livePoll().fn(); });
}

const FIRST_PAGE = [
  cycleItem(),
  jobItem({ id: 9001 }),
  jobItem({
    id: 9002, group: 'BRIEFING', runType: 'BRIEFING', name: 'Briefing refresh', status: 'DONE', succeeded: 1, failed: 0,
    startedAt: '2026-10-31T13:22:00Z', durationSeconds: 48,
  }),
];

function renderView(props = {}) {
  return render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} {...props} />);
}

/** What the request for the latest call carried. */
const lastRequest = () => fetchRuns.mock.calls.at(-1)[0];

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/runs';
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  captureIntervals();
  fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE));
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

describe('Runs: the served list', () => {
  it('renders each served row with its name, status words, result and cost', async () => {
    renderView();
    expect(await screen.findByTestId('runs-row-cycle-412')).toBeInTheDocument();
    expect(screen.getByTestId('runs-name-cycle-412')).toHaveTextContent('Afternoon refresh');
    expect(screen.getByTestId('runs-status-cycle-412')).toHaveTextContent('Running');
    expect(screen.getByTestId('runs-result-cycle-412')).toHaveTextContent('2 of 4 batches');
    expect(screen.getByTestId('runs-cost-cycle-412')).toHaveTextContent('\u00a30.6083');
    expect(screen.getByTestId('runs-status-job-9001')).toHaveTextContent('Done');
    expect(screen.getByTestId('runs-result-job-9001')).toHaveTextContent('61 / 61');
    expect(screen.getByTestId('runs-result-job-9002')).toHaveTextContent('Built');
  });

  it('tags a cycle CYCLE, and a job run nothing', async () => {
    renderView();
    expect(await screen.findByTestId('runs-cycle-tag-cycle-412')).toHaveTextContent('CYCLE');
    expect(screen.queryByTestId('runs-cycle-tag-job-9001')).toBeNull();
  });

  it('keeps a cycle and a job run of the same number as two rows', async () => {
    fetchRuns.mockResolvedValue(runsPage([cycleItem({ id: 7 }), jobItem({ id: 7 })]));
    renderView();
    expect(await screen.findByTestId('runs-row-cycle-7')).toBeInTheDocument();
    expect(screen.getByTestId('runs-row-job-7')).toBeInTheDocument();
  });

  it('shows the scope line and the note in its tone', async () => {
    renderView();
    expect(await screen.findByTestId('runs-scope-cycle-412')).toHaveTextContent('Cycle #412 \u00b7 intraday \u00b7 4 batches');
    expect(screen.getByTestId('runs-note-cycle-412')).toHaveTextContent('Waiting on 2 of 4 batches');
    expect(screen.getByTestId('runs-note-cycle-412').className).toContain('text-tide');
  });

  it('colours a warning amber and an error coral, and draws no note for none', async () => {
    fetchRuns.mockResolvedValue(runsPage([
      jobItem({ id: 1, status: 'PARTIAL', note: '14 requests errored', noteTone: 'WARN' }),
      jobItem({ id: 2, status: 'FAILED', note: 'Did not finish', noteTone: 'ERROR' }),
      jobItem({ id: 3 }),
    ]));
    renderView();
    expect((await screen.findByTestId('runs-note-job-1')).className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-note-job-2').className).toContain('text-plex-coral');
    expect(screen.queryByTestId('runs-note-job-3')).toBeNull();
  });

  it('draws the Running dot with its halo and the Done dot without', async () => {
    renderView();
    const running = (await screen.findByTestId('runs-status-cycle-412')).querySelector('span');
    expect(running.className).toContain('shadow-[0_0_0_3px');
    expect(screen.getByTestId('runs-status-job-9001').querySelector('span').className).not.toContain('shadow-');
  });

  it('reads "Showing {n} of {total}"', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, { total: 64, nextCursor: 'c1' }));
    renderView();
    expect(await screen.findByTestId('runs-showing')).toHaveTextContent('Showing 3 of 64');
  });

  it('says "No runs in this range." for an empty page', async () => {
    fetchRuns.mockResolvedValue(runsPage([]));
    renderView();
    expect(await screen.findByTestId('runs-empty')).toHaveTextContent('No runs in this range.');
    expect(screen.getByTestId('runs-empty').textContent).not.toContain('next overnight');
  });

  it('adds when the next overnight cycle starts, in UK time, when the server names it', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { nextOvernightAt: '2026-07-01T00:30:00Z' }));
    renderView();
    // 00:30Z in July is 01:30 in London.
    expect(await screen.findByTestId('runs-empty'))
      .toHaveTextContent('No runs in this range. The next overnight cycle starts 01:30.');
  });

  it('shows the load error and no list', async () => {
    fetchRuns.mockRejectedValue(Object.assign(new Error('Request failed'), {
      response: { status: 500, data: { error: 'The runs could not be read' } },
    }));
    renderView();
    expect(await screen.findByTestId('runs-error')).toHaveTextContent('The runs could not be read');
    expect(screen.queryByTestId('runs-row-cycle-412')).toBeNull();
  });

  it('has no search box (R-D3)', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(screen.queryByRole('searchbox')).toBeNull();
  });
});

describe('Runs: the header', () => {
  it('shows "Updated {HH:MM} · refreshes every minute" once the list has loaded', async () => {
    renderView();
    expect(await screen.findByTestId('runs-updated')).toHaveTextContent(/^Updated \d\d:\d\d \u00b7 refreshes every minute$/);
  });

  it('Refresh fetches the head again', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    const before = fetchRuns.mock.calls.length;
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(fetchRuns.mock.calls.length).toBe(before + 1));
  });

  it('has a Runs heading that can take focus', async () => {
    renderView();
    const heading = await screen.findByRole('heading', { name: 'Runs' });
    expect(heading).toHaveAttribute('tabindex', '-1');
  });
});

describe('Runs: the summary', () => {
  it('shows the four cells from the served aggregates', async () => {
    renderView();
    expect(await screen.findByTestId('runs-summary-runs-value')).toHaveTextContent('64');
    expect(screen.getByTestId('runs-summary-runs-sub')).toHaveTextContent('21 cycles \u00b7 43 other');
    expect(screen.getByTestId('runs-summary-attention-value')).toHaveTextContent('3');
    expect(screen.getByTestId('runs-summary-attention-sub')).toHaveTextContent('1 degraded \u00b7 1 partial \u00b7 1 failed');
    expect(screen.getByTestId('runs-summary-spend-value')).toHaveTextContent('\u00a318.41');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('$23.31');
    expect(screen.getByTestId('runs-summary-median-value')).toHaveTextContent('2h 19m');
    expect(screen.getByTestId('runs-summary-median-sub')).toHaveTextContent('last 7');
  });

  it('colours Need attention amber when above zero, and not when zero', async () => {
    renderView();
    expect((await screen.findByTestId('runs-summary-attention-value')).className).toContain('text-verdict-marginal');
  });

  it('drops the zero parts of the attention sub-line, and is not amber at zero', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, {
      summary: summary({ needAttention: 1, degraded: 0, partial: 1, failed: 0 }),
    }));
    renderView();
    expect(await screen.findByTestId('runs-summary-attention-sub')).toHaveTextContent(/^1 partial$/);

    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, {
      summary: summary({ needAttention: 0, degraded: 0, partial: 0, failed: 0 }),
    }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(screen.getByTestId('runs-summary-attention-value')).toHaveTextContent('0'));
    expect(screen.getByTestId('runs-summary-attention-value').className).not.toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-summary-attention-sub').textContent).toBe('');
  });

  it('reads the median as an em dash with no overnight sample', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, {
      summary: summary({ medianOvernightSeconds: null, medianOvernightSample: 0 }),
    }));
    renderView();
    expect(await screen.findByTestId('runs-summary-median-value')).toHaveTextContent('\u2014');
    expect(screen.getByTestId('runs-summary-median-sub').textContent).toBe('');
  });

  it('lets a sub-line wrap rather than truncate it', async () => {
    renderView();
    expect((await screen.findByTestId('runs-summary-attention-sub')).className).not.toContain('truncate');
  });

  it('shows spend under a penny in pence', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, { summary: summary({ spendMicroDollars: 10_000 }) }));
    renderView();
    expect(await screen.findByTestId('runs-summary-spend-value')).toHaveTextContent('0.79p');
  });

  it('has two columns when the list is 640px wide or less (a container query, not the viewport)', async () => {
    renderView();
    const grid = await screen.findByTestId('runs-summary');
    expect(grid.className).toContain('@max-[640px]:grid-cols-2');
    expect(grid.parentElement.className).toContain('@container');
  });
});

describe('Runs: the tools', () => {
  it('asks for the 7-day range, all kinds and no attention filter first', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(fetchRuns).toHaveBeenCalledWith(expect.objectContaining({
      range: 'WEEK', kind: 'ALL', attention: false, size: 20,
    }));
    expect(screen.getByTestId('runs-range-week')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-chip-all')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-chip-attention')).toHaveAttribute('aria-pressed', 'false');
  });

  it.each([
    ['today', 'TODAY'],
    ['month', 'MONTH'],
    ['week', 'WEEK'],
  ])('the %s button asks the server for %s', async (button, range) => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId(`runs-range-${button}`));
    await waitFor(() => expect(lastRequest().range).toBe(range));
  });

  it.each([
    ['cycles', 'CYCLES'], ['batches', 'BATCHES'], ['tide', 'TIDE'], ['briefing', 'BRIEFING'], ['aurora', 'AURORA'],
    ['other', 'OTHER'], ['all', 'ALL'],
  ])('the %s chip asks the server for kind %s', async (chip, kind) => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    // Start from another kind, so the "All" chip is a change too.
    fireEvent.click(screen.getByTestId('runs-chip-tide'));
    await waitFor(() => expect(lastRequest().kind).toBe('TIDE'));
    fireEvent.click(screen.getByTestId(`runs-chip-${chip}`));
    await waitFor(() => expect(lastRequest().kind).toBe(kind));
    expect(screen.getByTestId(`runs-chip-${chip}`)).toHaveAttribute('aria-pressed', 'true');
  });

  it('Need attention asks for attention rows, and toggles back off', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(lastRequest().attention).toBe(true));
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(lastRequest().attention).toBe(false));
  });

  it('the kind and attention chips combine (AND)', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId('runs-chip-batches'));
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(lastRequest()).toEqual(expect.objectContaining({ kind: 'BATCHES', attention: true })));
  });

  it('shows each chip\'s count from the server, and the attention count', async () => {
    renderView();
    expect(await screen.findByTestId('runs-chip-cycles-count')).toHaveTextContent('21');
    expect(screen.getByTestId('runs-chip-batches-count')).toHaveTextContent('6');
    expect(screen.getByTestId('runs-chip-aurora-count')).toHaveTextContent('14');
    expect(screen.getByTestId('runs-chip-attention-count')).toHaveTextContent('3');
  });

  it('shows the new filter\'s rows, never the old filter\'s under it', async () => {
    let release;
    fetchRuns.mockResolvedValueOnce(runsPage(FIRST_PAGE));
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fetchRuns.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));

    fireEvent.click(screen.getByTestId('runs-chip-tide'));

    await screen.findByTestId('runs-loading');
    expect(screen.queryByTestId('runs-row-cycle-412')).toBeNull();
    await act(async () => { release(runsPage([jobItem({ id: 55, name: 'Tide only' })])); });
    expect(await screen.findByTestId('runs-row-job-55')).toBeInTheDocument();
  });

  it('drops a late answer for an old filter', async () => {
    let releaseOld;
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fetchRuns.mockReturnValueOnce(new Promise((resolve) => { releaseOld = resolve; }));
    fireEvent.click(screen.getByTestId('runs-chip-tide'));
    fetchRuns.mockResolvedValueOnce(runsPage([jobItem({ id: 77, name: 'Aurora only', group: 'AURORA' })]));
    fireEvent.click(screen.getByTestId('runs-chip-aurora'));
    await screen.findByTestId('runs-row-job-77');

    await act(async () => { releaseOld(runsPage([jobItem({ id: 66, name: 'Tide, too late' })])); });

    expect(screen.queryByTestId('runs-row-job-66')).toBeNull();
    expect(screen.getByTestId('runs-row-job-77')).toBeInTheDocument();
  });
});

describe('Runs: the inbound query', () => {
  it('starts from ?range=&kind=&attention= and lights the matching controls', async () => {
    window.location.hash = '#manage/runs?range=today&kind=aurora&attention=true';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(fetchRuns).toHaveBeenCalledWith(expect.objectContaining({ range: 'TODAY', kind: 'AURORA', attention: true }));
    expect(screen.getByTestId('runs-range-today')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-chip-aurora')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-chip-attention')).toHaveAttribute('aria-pressed', 'true');
  });

  it('upper-cases the hash\'s lower-case kind for the wire', async () => {
    window.location.hash = '#manage/runs?kind=batches';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(fetchRuns.mock.calls[0][0].kind).toBe('BATCHES');
  });

  it('reads the month range, and ignores a range or kind it does not know', async () => {
    window.location.hash = '#manage/runs?range=month&kind=bogus';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(fetchRuns.mock.calls[0][0]).toEqual(expect.objectContaining({ range: 'MONTH', kind: 'ALL' }));
  });

  it('never writes the filters into the hash, and drops the inbound query in place on the first press', async () => {
    window.location.hash = '#manage/runs?kind=tide';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    const before = window.history.length;
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    await waitFor(() => expect(lastRequest().kind).toBe('CYCLES'));
    // A reload or a shared link must not name a filter the screen no longer shows.
    expect(window.location.hash).toBe('#manage/runs');
    expect(window.history.length).toBe(before);
    fireEvent.click(screen.getByTestId('runs-range-month'));
    await waitFor(() => expect(lastRequest().range).toBe('MONTH'));
    expect(window.location.hash).toBe('#manage/runs');
    expect(window.history.length).toBe(before);
  });

  it.each([
    ['the range', 'runs-range-today'],
    ['a kind chip', 'runs-chip-batches'],
    ['Need attention', 'runs-chip-attention'],
  ])('drops the inbound query when %s is pressed', async (_name, testId) => {
    window.location.hash = '#manage/runs?range=month&kind=tide&attention=true';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId(testId));
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('leaves a hash with no query alone when a filter is pressed', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(window.location.hash).toBe('#manage/runs/start');
  });

  it('opens a row without the query once a filter was pressed', async () => {
    window.location.hash = '#manage/runs?kind=tide';
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId('runs-chip-all'));
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    expect(window.location.hash).toBe('#manage/runs/job/9001');
  });

  it('keeps the query when a row is opened', async () => {
    window.location.hash = '#manage/runs?kind=tide';
    renderView();
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    expect(window.location.hash).toBe('#manage/runs/job/9001?kind=tide');
  });
});

describe('Runs: Load more', () => {
  it('asks for the next page with the cursor, and does not show a repeated row twice', async () => {
    fetchRuns
      .mockResolvedValueOnce(runsPage(FIRST_PAGE, { nextCursor: 'cursor-1', total: 5 }))
      // The next page repeats row 9002 (already shown) and adds two older ones.
      .mockResolvedValueOnce(runsPage([
        jobItem({ id: 9002, group: 'BRIEFING', runType: 'BRIEFING' }), jobItem({ id: 8000 }), jobItem({ id: 7999 }),
      ], { nextCursor: null, total: 5 }));
    renderView();

    fireEvent.click(await screen.findByTestId('runs-load-more'));

    await waitFor(() => expect(screen.getByTestId('runs-row-job-7999')).toBeInTheDocument());
    expect(fetchRuns).toHaveBeenLastCalledWith(expect.objectContaining({ before: 'cursor-1', size: 20 }));
    expect(screen.getByTestId('runs-row-job-8000')).toBeInTheDocument();
    expect(screen.getAllByTestId('runs-row-job-9002')).toHaveLength(1);
    expect(screen.getAllByTestId(/^runs-row-/)).toHaveLength(5);
    expect(screen.getByTestId('runs-showing')).toHaveTextContent('Showing 5 of 5');
  });

  it('hides Load more once there is no cursor', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(screen.queryByTestId('runs-load-more')).toBeNull();
  });

  it('disables Load more while it is in flight, and sends one request for two presses', async () => {
    let release;
    fetchRuns.mockResolvedValueOnce(runsPage(FIRST_PAGE, { nextCursor: 'cursor-1' }));
    renderView();
    const button = await screen.findByTestId('runs-load-more');
    fetchRuns.mockReturnValueOnce(new Promise((resolve) => { release = resolve; }));
    const before = fetchRuns.mock.calls.length;

    fireEvent.click(button);
    fireEvent.click(button);

    expect(fetchRuns.mock.calls.length).toBe(before + 1);
    expect(screen.getByTestId('runs-load-more')).toBeDisabled();
    await act(async () => { release(runsPage([], { nextCursor: null })); });
    expect(screen.queryByTestId('runs-load-more')).toBeNull();
  });

  it('sends the same filters with the cursor', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, { nextCursor: 'cursor-1' }));
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fireEvent.click(screen.getByTestId('runs-chip-batches'));
    fireEvent.click(await screen.findByTestId('runs-load-more'));
    await waitFor(() => expect(lastRequest().before).toBe('cursor-1'));
    expect(lastRequest()).toEqual(expect.objectContaining({ kind: 'BATCHES', range: 'WEEK' }));
  });
});

describe('Runs: polling the head', () => {
  it('polls every 60 seconds while nothing is running', async () => {
    fetchRuns.mockResolvedValue(runsPage([jobItem()], { runningCount: 0 }));
    renderView();
    await screen.findByTestId('runs-row-job-9001');
    expect(livePoll().ms).toBe(60000);
  });

  it('polls every 15 seconds while a run is running', async () => {
    fetchRuns.mockResolvedValue(runsPage([cycleItem()], { runningCount: 1, liveCycleId: 412 }));
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    await waitFor(() => expect(livePoll().ms).toBe(15000));
  });

  it('goes back to 60 seconds when the last run finishes', async () => {
    fetchRuns.mockResolvedValue(runsPage([cycleItem()], { runningCount: 1 }));
    renderView();
    await waitFor(() => expect(livePoll()?.ms).toBe(15000));
    fetchRuns.mockResolvedValue(runsPage([cycleItem({ status: 'DONE' })], { runningCount: 0 }));
    await tick();
    await waitFor(() => expect(livePoll().ms).toBe(60000));
  });

  it('merges a fresh head over what is loaded: a new row on top, a changed row updated, older pages kept', async () => {
    fetchRuns
      .mockResolvedValueOnce(runsPage(
        [jobItem({ id: 30, startedAt: '2026-10-31T10:00:00Z', status: 'RUNNING', durationSeconds: null }),
          jobItem({ id: 20, startedAt: '2026-10-31T09:00:00Z' })],
        { nextCursor: 'cursor-1', total: 4 },
      ))
      .mockResolvedValueOnce(runsPage(
        [jobItem({ id: 10, startedAt: '2026-10-31T08:00:00Z' }), jobItem({ id: 5, startedAt: '2026-10-31T07:00:00Z' })],
        { nextCursor: null, total: 4 },
      ));
    renderView();
    fireEvent.click(await screen.findByTestId('runs-load-more'));
    await screen.findByTestId('runs-row-job-5');

    // The head poll: row 30 has finished, row 40 is new, and the head is a full page ending at 20.
    fetchRuns.mockResolvedValueOnce(runsPage(
      [jobItem({ id: 40, startedAt: '2026-10-31T11:00:00Z', name: 'Brand new' }),
        jobItem({ id: 30, startedAt: '2026-10-31T10:00:00Z', status: 'FAILED', note: 'Did not finish', noteTone: 'ERROR' }),
        jobItem({ id: 20, startedAt: '2026-10-31T09:00:00Z' })],
      { nextCursor: 'cursor-head', total: 5 },
    ));
    await tick();

    const ids = screen.getAllByTestId(/^runs-row-/).map((row) => row.getAttribute('data-testid'));
    expect(ids).toEqual(['runs-row-job-40', 'runs-row-job-30', 'runs-row-job-20', 'runs-row-job-10', 'runs-row-job-5']);
    expect(screen.getByTestId('runs-status-job-30')).toHaveTextContent('Failed');
    expect(screen.getByTestId('runs-showing')).toHaveTextContent('Showing 5 of 5');
  });

  it('drops no row and shows none twice across polls', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, { nextCursor: null }));
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    await tick();
    await tick();
    expect(screen.getAllByTestId(/^runs-row-/)).toHaveLength(3);
  });

  it('replaces the list with a head that is not a full page, so a row that left the range goes', async () => {
    renderView();
    await screen.findByTestId('runs-row-job-9002');
    fetchRuns.mockResolvedValueOnce(runsPage([cycleItem(), jobItem({ id: 9001 })], { nextCursor: null }));
    await tick();
    expect(screen.queryByTestId('runs-row-job-9002')).toBeNull();
  });

  it('keeps what is on screen when a poll fails', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    fetchRuns.mockRejectedValueOnce(new Error('offline'));
    await tick();
    expect(screen.getByTestId('runs-row-cycle-412')).toBeInTheDocument();
  });

  it('stops polling when the view goes', async () => {
    const { unmount } = renderView();
    await screen.findByTestId('runs-row-cycle-412');
    const poll = livePoll();
    unmount();
    expect(poll.cleared).toBe(true);
  });
});

describe('Runs: opening a run', () => {
  it('opens a job run\'s panel by hash, loaded by its id', async () => {
    renderView();
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    expect(window.location.hash).toBe('#manage/runs/job/9001');
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    expect(fetchJobRun).toHaveBeenCalledWith(9001);
    expect(screen.getByTestId('job-run-panel-title')).toHaveTextContent('Tide refresh');
  });

  it('marks the open row, and gives the panel a name but never a dialog role', async () => {
    renderView();
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    await screen.findByTestId('job-run-panel');
    expect(screen.getByTestId('runs-row-job-9001')).toHaveAttribute('aria-current', 'true');
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
    fireEvent.click(await screen.findByTestId('runs-row-cycle-412'));
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(await screen.findByTestId('cycle-panel-title')).toHaveTextContent('Afternoon refresh');
    expect(screen.getByRole('complementary', { name: 'Cycle #412' })).toBeInTheDocument();
    expect(fetchPipelineRunDetail).toHaveBeenCalledWith(412);
    expect(fetchJobRun).not.toHaveBeenCalled();
  });

  it('opens a job row from the keyboard (Enter)', async () => {
    renderView();
    const row = await screen.findByTestId('runs-row-job-9001');
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('closes the panel from its x, leaving the bare tab address', async () => {
    renderView();
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    await screen.findByTestId('job-run-panel');
    fireEvent.click(screen.getByTestId('job-run-panel-close'));
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('closes the panel on Escape', async () => {
    renderView();
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    await screen.findByTestId('job-run-panel');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
  });

  it('follows the hash when it moves (Back, a pasted link)', async () => {
    renderView();
    await screen.findByTestId('runs-row-job-9001');
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
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
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
    await screen.findByTestId('runs-row-cycle-412');
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
    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('start-run-panel-title')).toBeNull();
  });

  it('reloads the list head when a start adds a run, closes the panel and toasts the server\'s line', async () => {
    window.location.hash = '#manage/runs/start';
    runBriefing.mockResolvedValue({ status: 'Briefing refreshed.' });
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    const before = fetchRuns.mock.calls.length;

    fireEvent.click(screen.getByTestId('start-option-briefing'));
    await act(async () => { fireEvent.click(screen.getByTestId('start-run-submit')); });

    await waitFor(() => expect(fetchRuns.mock.calls.length).toBe(before + 1));
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

describe('Runs: an open panel and the filters', () => {
  /** Opens job run 4444 by deep link, a run that is NOT on the loaded page, with the given served group and status. */
  async function openFarRun(group, status) {
    window.location.hash = '#manage/runs/job/4444';
    fetchJobRun.mockResolvedValue(jobDetail({ id: 4444 }, { group, status, name: 'Far-off run' }));
    renderView();
    await screen.findByTestId('job-run-panel');
  }

  it('stays open when the kind chip names the run\'s own group, though the loaded page does not hold it', async () => {
    await openFarRun('TIDE', 'DONE');
    fireEvent.click(screen.getByTestId('runs-chip-tide'));
    await waitFor(() => expect(lastRequest().kind).toBe('TIDE'));
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('closes when the kind chip names another group', async () => {
    await openFarRun('TIDE', 'DONE');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    await waitFor(() => expect(screen.queryByTestId('job-run-panel')).toBeNull());
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('closes under Need attention when the run\'s own status is Done', async () => {
    await openFarRun('TIDE', 'DONE');
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(screen.queryByTestId('job-run-panel')).toBeNull());
  });

  it.each(['DEGRADED', 'PARTIAL', 'FAILED'])('stays open under Need attention when the run is %s', async (status) => {
    await openFarRun('TIDE', status);
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(lastRequest().attention).toBe(true));
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('stays open when only the range changes', async () => {
    await openFarRun('TIDE', 'DONE');
    fireEvent.click(screen.getByTestId('runs-range-today'));
    await waitFor(() => expect(lastRequest().range).toBe('TODAY'));
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('stays open through a data refresh, whatever the page now holds', async () => {
    await openFarRun('TIDE', 'DONE');
    fetchRuns.mockResolvedValue(runsPage([]));
    await tick();
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
  });

  it('does not close the Start panel when a filter changes', async () => {
    window.location.hash = '#manage/runs/start';
    renderView();
    await screen.findByTestId('start-run-panel-title');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    await waitFor(() => expect(lastRequest().kind).toBe('CYCLES'));
    expect(screen.getByTestId('start-run-panel-title')).toBeInTheDocument();
  });

  it('does not close a panel that has not loaded yet (the run\'s group is not known)', async () => {
    window.location.hash = '#manage/runs/job/4444';
    fetchJobRun.mockReturnValue(new Promise(() => {}));
    renderView();
    await screen.findByTestId('job-run-panel-state');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    await waitFor(() => expect(lastRequest().kind).toBe('CYCLES'));
    expect(screen.getByTestId('job-run-panel-state')).toBeInTheDocument();
  });
});

describe('Runs: the real clock, never the rewind clock (R-D7)', () => {
  beforeEach(() => {
    // The real "now" is pinned (Date only, so timers still run); Rewind will say a different day.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps "Today" and "Yesterday" on the real day when a rewind is set', async () => {
    setRewind('2026-01-15T09:00:00Z');
    fetchRuns.mockResolvedValue(runsPage([
      jobItem({ id: 1, startedAt: '2026-10-31T06:00:00Z' }),
      jobItem({ id: 2, startedAt: '2026-10-30T19:40:00Z' }),
      jobItem({ id: 3, startedAt: '2026-10-29T11:02:00Z' }),
    ]));
    renderView();
    expect(await screen.findByTestId('runs-started-job-1')).toHaveTextContent('Today 06:00');
    expect(screen.getByTestId('runs-started-job-2')).toHaveTextContent('Yesterday 19:40');
    expect(screen.getByTestId('runs-started-job-3')).toHaveTextContent('Thu 29 Oct 11:02');
  });

  it('keeps elapsed "so far" times on the real clock when a rewind is set', async () => {
    setRewind('2026-01-15T09:00:00Z');
    fetchRuns.mockResolvedValue(runsPage([
      cycleItem({ startedAt: '2026-10-31T10:22:00Z', durationSeconds: null }),
    ]));
    renderView();
    // 10:22Z to the real 12:00Z.
    expect(await screen.findByTestId('runs-duration-cycle-412')).toHaveTextContent('1h 38m so far');
  });

  it('reads the same with no rewind set', async () => {
    fetchRuns.mockResolvedValue(runsPage([jobItem({ id: 1, startedAt: '2026-10-31T06:00:00Z' })]));
    renderView();
    expect(await screen.findByTestId('runs-started-job-1')).toHaveTextContent('Today 06:00');
  });
});

describe('Runs: the breakpoint classes', () => {
  it('lays the list out as container-query rows: columns drop at 820px and cards begin at 540px', async () => {
    renderView();
    const grid = await screen.findByTestId('runs');
    expect(grid.className).toContain('@container');
    const row = screen.getByTestId('runs-row-cycle-412');
    expect(row.className).toContain('@max-[820px]:grid-cols-(--data-cols-lean)');
    expect(row.className).toContain('@max-[540px]:grid-cols-[minmax(0,1fr)_auto]');
  });

  it('marks Result, Duration and Cost secondary (they drop at 820px) and Started, Run and Status as kept', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    for (const key of ['result', 'duration', 'cost']) {
      expect(screen.getByTestId(`runs-cell-${key}-cycle-412`).className).toContain('@max-[820px]:hidden');
    }
    for (const key of ['started', 'status']) {
      expect(screen.getByTestId(`runs-cell-${key}-cycle-412`).className).not.toContain('@max-[820px]:hidden');
    }
    expect(screen.getByTestId('runs-cell-run-cycle-412').className).not.toContain('@max-[820px]:hidden');
  });

  it('gives the card its "{started} · {duration} · {cost}" line, shown only as a card', async () => {
    fetchRuns.mockResolvedValue(runsPage([jobItem({ id: 5, startedAt: '2026-10-29T11:02:00Z', durationSeconds: 72 })]));
    renderView();
    const cell = await screen.findByTestId('runs-cell-card-summary-job-5');
    expect(cell.className).toContain('hidden');
    expect(cell.className).toContain('@max-[540px]:block');
    // The cost is an em dash for a free run: the part is dropped, not joined as a dash.
    expect(cell.textContent).toBe('Thu 29 Oct 11:02 \u00b7 1m 12s');
  });

  it('shows the scope line in card mode too, as frame 11 does', async () => {
    renderView();
    expect((await screen.findByTestId('runs-scope-cycle-412')).className).not.toContain('hidden');
  });

  it('joins all three card parts when the run has a duration and a cost', async () => {
    fetchRuns.mockResolvedValue(runsPage([jobItem({ id: 6, startedAt: '2026-10-29T11:02:00Z', costMicroDollars: 1_000_000 })]));
    renderView();
    expect((await screen.findByTestId('runs-cell-card-summary-job-6')).textContent)
      .toBe('Thu 29 Oct 11:02 \u00b7 1m 12s \u00b7 \u00a30.7900');
  });

  it('clips a long note to two lines and lets it break anywhere on a narrow list', async () => {
    fetchRuns.mockResolvedValue(runsPage([jobItem({ id: 8, status: 'FAILED', note: 'x'.repeat(200), noteTone: 'ERROR' })]));
    renderView();
    const note = await screen.findByTestId('runs-note-job-8');
    expect(note.className).toContain('line-clamp-2');
    expect(note.className).toContain('[overflow-wrap:anywhere]');
    expect(note.className).toContain('min-w-0');
  });

  it('has 44px phone targets on Start a run, Load more and the chips', async () => {
    fetchRuns.mockResolvedValue(runsPage(FIRST_PAGE, { nextCursor: 'c' }));
    renderView();
    expect((await screen.findByTestId('start-run-btn')).className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('runs-load-more').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('runs-chip-cycles').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('runs-range-today').className).toContain('max-[639px]:min-h-11');
  });

  it('shows the grid header on wide lists (it hides itself as cards)', async () => {
    renderView();
    const header = await screen.findByTestId('runs-header');
    expect(header.className).toContain('@max-[540px]:hidden');
    expect(within(header).getByText('Started')).toBeInTheDocument();
    expect(within(header).getByText('Duration')).toBeInTheDocument();
  });
});

describe('Runs: row accessibility', () => {
  it('makes each row a button that reads the whole row', async () => {
    renderView();
    const row = await screen.findByTestId('runs-row-job-9001');
    expect(row).toHaveAttribute('role', 'button');
    expect(row).toHaveAttribute('tabindex', '0');
    expect(row.getAttribute('aria-label')).toContain('Tide refresh');
    expect(row.getAttribute('aria-label')).toContain('Done');
    expect(row.getAttribute('aria-label')).toContain('61 / 61');
  });

  it('puts no control inside a row', async () => {
    renderView();
    const row = await screen.findByTestId('runs-row-job-9001');
    expect(row.querySelector('button, a, input, select')).toBeNull();
  });
});

describe('mergeHead: a head that cannot be joined to what is loaded', () => {
  const row = (id, hour) => jobItem({ id, startedAt: `2026-10-31T${String(hour).padStart(2, '0')}:00:00Z` });

  it('replaces the list with the head, and its cursor, when no head row is among the loaded ones', () => {
    // More than a page of new rows arrived between polls: the loaded rows sit below a gap the head cannot span.
    const loaded = [row(1, 3), row(2, 2)];
    const head = { items: [row(30, 23), row(29, 22), row(28, 21)], nextCursor: 'head-cursor' };
    expect(mergeHead(loaded, 'old-cursor', head)).toEqual({ items: head.items, nextCursor: 'head-cursor' });
  });

  it('still joins when the head overlaps what is loaded', () => {
    const loaded = [row(3, 12), row(2, 11), row(1, 10)];
    const head = { items: [row(4, 13), row(3, 12)], nextCursor: 'head-cursor' };
    expect(mergeHead(loaded, 'old-cursor', head).items.map((r) => r.id)).toEqual([4, 3, 2, 1]);
    expect(mergeHead(loaded, 'old-cursor', head).nextCursor).toBe('old-cursor');
  });

  it('takes a head into an empty list', () => {
    const head = { items: [row(1, 1)], nextCursor: 'c' };
    expect(mergeHead([], null, head)).toEqual({ items: head.items, nextCursor: 'c' });
  });
});

describe('Runs: a poll after more than a page of new runs', () => {
  it('shows the new head, not the old rows under a gap', async () => {
    fetchRuns.mockResolvedValueOnce(runsPage(
      [jobItem({ id: 2, startedAt: '2026-10-31T02:00:00Z' }), jobItem({ id: 1, startedAt: '2026-10-31T01:00:00Z' })],
      { nextCursor: 'old' },
    ));
    renderView();
    await screen.findByTestId('runs-row-job-1');

    fetchRuns.mockResolvedValueOnce(runsPage(
      Array.from({ length: 20 }, (_, i) => jobItem({ id: 100 - i, startedAt: `2026-10-31T${String(23 - i).padStart(2, '0')}:30:00Z` })),
      { nextCursor: 'fresh', total: 22 },
    ));
    await tick();

    expect(screen.queryByTestId('runs-row-job-1')).toBeNull();
    expect(screen.getByTestId('runs-row-job-100')).toBeInTheDocument();
    expect(screen.getAllByTestId(/^runs-row-/)).toHaveLength(20);
    fetchRuns.mockResolvedValueOnce(runsPage([jobItem({ id: 7 })], { nextCursor: null }));
    fireEvent.click(screen.getByTestId('runs-load-more'));
    await waitFor(() => expect(lastRequest().before).toBe('fresh'));
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
    await waitFor(() => expect(screen.getByTestId('runs-row-cycle-412')).toBeInTheDocument());
    const loaded = fetchRuns.mock.calls.length;

    await tick();
    expect(fetchRuns.mock.calls.length).toBe(loaded);

    host.hidden = false;
    await tick();
    expect(fetchRuns.mock.calls.length).toBe(loaded + 1);
    host.remove();
  });

  it('still loads the first page when the view mounts hidden', async () => {
    const host = renderInHost(true);
    await waitFor(() => expect(fetchRuns).toHaveBeenCalledTimes(1));
    host.remove();
  });

  it('is driven by the shared visible-page poll: it stops while the browser tab is hidden', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    const before = livePoll();
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(before.cleared).toBe(true);
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
  });
});

describe('Runs: an older head answer never lands after a newer one', () => {
  it('drops the slow first response when a newer poll has already been shown', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    let releaseOld;
    fetchRuns.mockReturnValueOnce(new Promise((resolve) => { releaseOld = resolve; }));
    await tick();
    fetchRuns.mockResolvedValueOnce(runsPage([jobItem({ id: 61, name: 'Newer answer' })], { nextCursor: null }));
    await tick();
    await screen.findByTestId('runs-row-job-61');

    await act(async () => { releaseOld(runsPage([jobItem({ id: 62, name: 'Older answer' })], { nextCursor: null })); });

    expect(screen.queryByTestId('runs-row-job-62')).toBeNull();
    expect(screen.getByTestId('runs-row-job-61')).toBeInTheDocument();
  });
});

/** Chooses a Start a run option and presses Start. */
async function chooseAndStart(key) {
  fireEvent.click(await screen.findByTestId(`start-option-${key}`));
  await act(async () => { fireEvent.click(screen.getByTestId('start-run-submit')); });
}

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
    fetchRuns.mockImplementation(() => { calls.push('reload'); return Promise.resolve(runsPage(FIRST_PAGE)); });

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

    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
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

    fireEvent.click(await screen.findByTestId('runs-row-job-9001'));
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

describe('Runs: status words, header and loading line', () => {
  it('tints Degraded and Partial amber and Failed coral, Running tide and Done plain; the words always show', async () => {
    fetchRuns.mockResolvedValue(runsPage([
      jobItem({ id: 1, status: 'DEGRADED' }), jobItem({ id: 2, status: 'PARTIAL' }), jobItem({ id: 3, status: 'FAILED' }),
      jobItem({ id: 4, status: 'RUNNING', durationSeconds: null }), jobItem({ id: 5, status: 'DONE' }),
    ]));
    renderView();
    expect((await screen.findByTestId('runs-status-job-1')).className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-status-job-1')).toHaveTextContent('Degraded');
    expect(screen.getByTestId('runs-status-job-2').className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-status-job-3').className).toContain('text-plex-coral');
    expect(screen.getByTestId('runs-status-job-3')).toHaveTextContent('Failed');
    expect(screen.getByTestId('runs-status-job-4').className).toContain('text-tide');
    expect(screen.getByTestId('runs-status-job-5').className).toContain('text-plex-text');
  });

  it('says "Loading runs…" and stops the pulse for reduced motion', async () => {
    fetchRuns.mockReturnValue(new Promise(() => {}));
    renderView();
    const line = await screen.findByTestId('runs-loading');
    expect(line).toHaveTextContent('Loading runs…');
    expect(line.className).toContain('motion-reduce:animate-none');
  });

  it('pushes Refresh and Start a run to the right of a wrapped header', async () => {
    renderView();
    const group = (await screen.findByTestId('runs-refresh-btn')).parentElement;
    expect(group.className).toContain('ml-auto');
    expect(group).toContainElement(screen.getByTestId('start-run-btn'));
  });

  it('reads a row to assistive tech with "cycle", its duration and its cost', async () => {
    fetchRuns.mockResolvedValue(runsPage([cycleItem({ status: 'DONE', durationSeconds: 7200, costMicroDollars: 1_000_000 })]));
    renderView();
    const label = (await screen.findByTestId('runs-row-cycle-412')).getAttribute('aria-label');
    expect(label).toContain('Afternoon refresh cycle');
    expect(label).toContain('2h 0m');
    expect(label).toContain('£0.7900');
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
