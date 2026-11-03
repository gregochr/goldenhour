import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within, cleanup } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import {
  NIGHTLY, catalogue, day, inHours, job, jobDetail, lastFire, lastRunCycle, pollingJob, preview, previewJob, solarJob,
  summaryFor, tideJob,
} from './schedulerFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  fetchSchedulerSummary: vi.fn(),
  fetchJobDetail: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
}));
vi.mock('../api/skyRatingEvalApi', () => ({
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));
// jsdom has no layout: the split breakpoint (1000px) is decided here.
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchSchedulerJobs,
  fetchSchedulerSummary,
  fetchJobDetail,
  fetchSolarPreview,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

// The Scheduler's All jobs tab, its tabs and address, and the panel's read-only sections (scheduler-redesign-plan.md
// S4a), end to end below ManageView. The network is mocked at the API module.

const TIDE = tideJob({
  lastFireTime: new Date(Date.now() - 180_000).toISOString(),
  nextFireTime: new Date(Date.now() + 86_400_000).toISOString(),
});
const POLLING = pollingJob({ status: 'PAUSED' });

function mount(jobs = [TIDE, POLLING], summary = undefined) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  fetchSchedulerSummary.mockResolvedValue(summary === undefined ? summaryFor(jobs) : summary);
  return render(<SchedulerView />);
}

async function openPanel(jobKey) {
  fireEvent.click(await screen.findByTestId(`scheduler-row-${jobKey}`));
  return screen.findByTestId('scheduler-panel');
}

const rowOrder = () => screen.getAllByTestId(/^scheduler-row-/).map((row) => row.dataset.testid.replace('scheduler-row-', ''));

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  useIsSplitLayout.mockReturnValue(true);
  fetchSolarPreview.mockRejectedValue(new Error('preview not under test here'));
  fetchJobDetail.mockResolvedValue(jobDetail());
});

afterEach(() => {
  try { window.localStorage.removeItem('photocast.scheduler.view'); } catch { /* none */ }
});

describe('SchedulerView: the page header', () => {
  it('heads the page "Scheduler" with the served job count, the UK time label and the paused count', async () => {
    mount();
    const meta = await screen.findByTestId('scheduler-meta');
    expect(screen.getByTestId('scheduler-heading')).toHaveTextContent('Scheduler');
    expect(meta.textContent).toMatch(/^2 jobs · UK time \((BST|GMT)\) · 1 paused$/);
  });

  it('names the next scheduled job and its UK time, and still says "0 paused"', async () => {
    const jobs = [TIDE];
    mount(jobs, summaryFor(jobs, {
      nextScheduled: { jobKey: 'tide_refresh', displayName: 'Tide Refresh', at: '2026-07-10T14:15:00Z' },
    }));
    const meta = await screen.findByTestId('scheduler-meta');
    expect(meta.textContent).toMatch(/^1 job · UK time \((BST|GMT)\) · 0 paused · next scheduled: Tide Refresh at 1[45]:15$/);
  });

  it('draws no meta line at all after a failed first load', async () => {
    fetchSchedulerJobs.mockRejectedValue(new Error('down'));
    fetchSchedulerSummary.mockRejectedValue(new Error('down'));
    render(<SchedulerView />);
    await screen.findByTestId('scheduler-error');
    expect(screen.queryByTestId('scheduler-meta')).not.toBeInTheDocument();
  });

  it('still heads the page, from the jobs, when the summary cannot be read', async () => {
    fetchSchedulerSummary.mockRejectedValue(new Error('down'));
    fetchSchedulerJobs.mockResolvedValue([TIDE, POLLING]);
    render(<SchedulerView />);
    expect((await screen.findByTestId('scheduler-meta')).textContent).toMatch(/^2 jobs · UK time \((BST|GMT)\) · 1 paused$/);
  });
});

describe('SchedulerView: the groups', () => {
  it('groups the rows Sun-relative, Cron, Fixed delay, each with its count and subtitle, soonest first inside', async () => {
    mount([
      pollingJob({ nextFireTime: inHours(1) }),
      job({ id: 2, jobKey: 'cron_late', displayName: 'Cron late', nextFireTime: inHours(20) }),
      solarJob({ id: 3, jobKey: 'sun', displayName: 'Sun', nextFireTime: inHours(5) }),
      job({ id: 4, jobKey: 'cron_soon', displayName: 'Cron soon', nextFireTime: inHours(2) }),
    ]);
    await screen.findByTestId('scheduler-row-sun');
    expect(rowOrder()).toEqual(['sun', 'cron_soon', 'cron_late', 'aurora_polling']);
    expect(screen.getByTestId('scheduler-group-solar_offset')).toHaveTextContent('Sun-relative·1fires a set time before sunrise or sunset');
    expect(screen.getByTestId('scheduler-group-cron')).toHaveTextContent('Cron·2fixed clock times');
    expect(screen.getByTestId('scheduler-group-fixed_delay')).toHaveTextContent('Fixed delay·1repeats every few minutes, all day');
  });

  it('lists a job whose schedule type is not recognised in a trailing Other group', async () => {
    mount([job({ scheduleType: 'MYSTERY' }), tideJob()]);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(rowOrder()).toEqual(['tide_refresh', NIGHTLY]);
    expect(screen.getByTestId('scheduler-group-other')).toHaveTextContent('Other');
  });

  it('gives each group heading a heading role at level 3', async () => {
    mount();
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByRole('heading', { level: 3, name: /Cron/ })).toBeInTheDocument();
  });
});

describe('SchedulerView: the Last run column', () => {
  it('shows the last fire\'s status, how long ago it started and how long it took', async () => {
    mount([job({
      lastFire: lastFire({
        firedAt: inHours(-0.25),
        completedAt: new Date(Date.now() - 0.25 * 3_600_000 + 55_000).toISOString(),
      }),
    })]);
    const cell = await screen.findByTestId(`scheduler-last-${NIGHTLY}`);
    expect(cell).toHaveTextContent('OK');
    expect(screen.getByTestId(`last-fire-${NIGHTLY}`)).toHaveTextContent('15 min ago · 55s');
  });

  it.each([
    ['PARTIAL', 'Partial'], ['FAILED', 'Failed'], ['SKIPPED', 'Skipped'],
  ])('says %s in words', async (status, words) => {
    mount([job({ lastFire: lastFire({ status }) })]);
    expect(await screen.findByTestId(`scheduler-last-${NIGHTLY}`)).toHaveTextContent(words);
  });

  it('shows a running fire with the Running words and no duration', async () => {
    mount([job({ lastFire: lastFire({ status: 'RUNNING', completedAt: null }) })]);
    const cell = await screen.findByTestId(`scheduler-last-${NIGHTLY}`);
    expect(cell).toHaveTextContent('Running');
    expect(screen.getByTestId(`last-fire-${NIGHTLY}`).textContent).toMatch(/^\d+ min ago$/);
  });

  it('shows an em dash for a job that has never fired', async () => {
    mount([POLLING]);
    expect(await screen.findByTestId('scheduler-last-aurora_polling')).toHaveTextContent('—');
  });
});

describe('SchedulerView: the new chips', () => {
  it('shows the served counts for Paused, Off in config, Uses Claude and Last run failed, and filters by them', async () => {
    const claudeJob = solarJob({ catalogue: catalogue({ usesClaude: true }), lastFire: lastFire({ status: 'FAILED' }) });
    const jobs = [TIDE, POLLING, claudeJob, job({ id: 7, jobKey: 'off', displayName: 'Off', status: 'DISABLED_BY_CONFIG' })];
    mount(jobs);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByTestId('scheduler-chip-paused-count')).toHaveTextContent('1');
    expect(screen.getByTestId('scheduler-chip-disabled-by-config-count')).toHaveTextContent('1');
    expect(screen.getByTestId('scheduler-chip-claude-count')).toHaveTextContent('1');
    expect(screen.getByTestId('scheduler-chip-failed-count')).toHaveTextContent('1');

    fireEvent.click(screen.getByTestId('scheduler-chip-claude'));
    expect(screen.getByTestId('scheduler-chip-claude')).toHaveAttribute('aria-pressed', 'true');
    expect(rowOrder()).toEqual([NIGHTLY]);
    fireEvent.click(screen.getByTestId('scheduler-chip-claude'));
    fireEvent.click(screen.getByTestId('scheduler-chip-failed'));
    expect(rowOrder()).toEqual([NIGHTLY]);
  });

  it('takes the counts from the served summary, not from the jobs it was given', async () => {
    mount([TIDE], { ...summaryFor([TIDE]), usesClaude: 5 });
    expect(await screen.findByTestId('scheduler-chip-claude-count')).toHaveTextContent('5');
  });
});

describe('SchedulerView: the All chip', () => {
  const jobs = [TIDE, POLLING, solarJob({ catalogue: catalogue({ usesClaude: true }), lastFire: lastFire({ status: 'FAILED' }) })];

  it('is lit only when no chip is on, and pressing it clears every chip', async () => {
    mount(jobs);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByTestId('scheduler-chip-all')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    fireEvent.click(screen.getByTestId('scheduler-chip-claude'));
    expect(screen.getByTestId('scheduler-chip-all')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(screen.getByTestId('scheduler-chip-paused'));
    fireEvent.click(screen.getByTestId('scheduler-chip-failed'));
    fireEvent.click(screen.getByTestId('scheduler-chip-all'));
    expect(screen.getByTestId('scheduler-chip-all')).toHaveAttribute('aria-pressed', 'true');
    ['cron', 'claude', 'paused', 'failed'].forEach((chip) => {
      expect(screen.getByTestId(`scheduler-chip-${chip}`)).toHaveAttribute('aria-pressed', 'false');
    });
    expect(rowOrder()).toHaveLength(3);
  });

  it('turns a type chip off when it is pressed again', async () => {
    mount(jobs);
    await screen.findByTestId('scheduler-row-tide_refresh');
    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    expect(screen.getByTestId('scheduler-chip-all')).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('SchedulerView: row names and the Day placeholder', () => {
  it('adds uses Claude and a last run that did not succeed to the row\'s accessible name', async () => {
    mount([solarJob({ catalogue: catalogue({ usesClaude: true }), lastFire: lastFire({ status: 'FAILED' }) })]);
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(row).toHaveAttribute('aria-label', expect.stringContaining('4h 15m before sunrise'));
    expect(row).toHaveAttribute('aria-label', expect.stringContaining('last run failed'));
    expect(row).toHaveAttribute('aria-label', expect.stringContaining('uses Claude'));
  });

  it('adds no last-run words for a run that succeeded', async () => {
    mount([job({ lastFire: lastFire() })]);
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(row.getAttribute('aria-label')).not.toContain('last run');
  });

  it('makes the Day\'s jobs tabpanel focusable', async () => {
    mount();
    await screen.findByTestId('scheduler-all-jobs');
    fireEvent.click(screen.getByRole('tab', { name: /Day's jobs/ }));
    expect(screen.getByRole('tabpanel')).toHaveAttribute('tabindex', '0');
  });
});

describe('SchedulerView: search', () => {
  const JOBS = [
    { ...TIDE, catalogue: catalogue({ alias: 'The tide filler', what: 'Fetches high and low tides.', purpose: 'Places' }) },
    {
      ...job({ id: 9, jobKey: 'mail', displayName: 'Mail sweep' }),
      catalogue: catalogue({ alias: 'The mailman', what: 'Emails people.', purpose: 'Accounts' }),
    },
  ];

  it.each([
    ['name', 'sweep', ['mail']],
    ['alias', 'mailman', ['mail']],
    ['what it does', 'high and low', ['tide_refresh']],
    ['purpose', 'places', ['tide_refresh']],
  ])('finds a job by its %s', async (_label, text, expected) => {
    mount(JOBS);
    await screen.findByTestId('scheduler-row-mail');
    fireEvent.change(screen.getByTestId('scheduler-search'), { target: { value: text } });
    expect(rowOrder()).toEqual(expected);
  });

  it('finds the jobs that use Claude by searching "Claude", though their text does not say it', async () => {
    mount([
      job({ catalogue: catalogue({ what: 'Forecasts.', usesClaude: true }) }),
      tideJob({ catalogue: catalogue({ what: 'Tides.' }) }),
    ]);
    await screen.findByTestId('scheduler-row-tide_refresh');
    fireEvent.change(screen.getByTestId('scheduler-search'), { target: { value: 'Claude' } });
    expect(rowOrder()).toEqual([NIGHTLY]);
  });

  it('says nothing matches, and closes the open panel when the search filters its job out', async () => {
    mount(JOBS);
    await openPanel('mail');
    fireEvent.change(screen.getByTestId('scheduler-search'), { target: { value: 'zzz' } });
    expect(screen.getByTestId('scheduler-empty')).toHaveTextContent('No jobs match these filters.');
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });
});

describe('SchedulerView: the All jobs rows', () => {
  const CLAUDE_JOB = job({
    catalogue: catalogue({ alias: 'The main one', what: 'Sends every coming sunrise to Claude.', purpose: 'Forecast', usesClaude: true }),
  });

  it('shows the name, the purpose tag, the £ Claude tag and the full paragraph', async () => {
    mount([CLAUDE_JOB]);
    await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(screen.getByTestId(`scheduler-purpose-${NIGHTLY}`)).toHaveTextContent('Forecast');
    expect(screen.getByTestId(`scheduler-claude-${NIGHTLY}`)).toHaveTextContent('£ Claude');
    expect(screen.getByTestId(`scheduler-what-${NIGHTLY}`)).toHaveTextContent('Sends every coming sunrise to Claude.');
  });

  it('draws a job with no catalogue entry by name alone: no tag, no paragraph', async () => {
    mount([job()]);
    await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(screen.getByTestId(`scheduler-name-${NIGHTLY}`)).toHaveTextContent('Nightly batch evaluation');
    expect(screen.queryByTestId(`scheduler-purpose-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-claude-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-what-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('shows no £ Claude tag for a job that does not call Claude', async () => {
    mount([job({ catalogue: catalogue() })]);
    await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(screen.queryByTestId(`scheduler-claude-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('adds today\'s fire time, from the solar preview, under a sun-relative job\'s schedule', async () => {
    fetchSolarPreview.mockResolvedValue(preview([previewJob({ today: day({ fireInstant: '2026-10-13T02:57:00Z' }) })]));
    mount([solarJob()]);
    await waitFor(() => expect(screen.getByTestId(`scheduler-cell-schedule-${NIGHTLY}`)).toHaveTextContent(/today 0[34]:57/));
  });

  it('opens the panel from the keyboard: Enter on a focused row', async () => {
    mount();
    fireEvent.keyDown(await screen.findByTestId('scheduler-row-tide_refresh'), { key: 'Enter' });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });
});

describe('SchedulerView: the tabs and the address', () => {
  it('opens on All jobs, with a tablist whose tabs carry their sub-labels', async () => {
    mount();
    await screen.findByTestId('scheduler-all-jobs');
    expect(screen.getByRole('tablist', { name: 'Scheduler views' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /All jobs/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: /Day's jobs/ })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByTestId('scheduler-tab-day-word')).toHaveTextContent('Today');
    expect(screen.getByTestId('scheduler-tab-all-word')).toHaveTextContent('2');
  });

  it('shows the Day\'s jobs placeholder and writes ?view=day with replaceState, adding no history entry', async () => {
    mount();
    await screen.findByTestId('scheduler-all-jobs');
    const before = window.history.length;
    fireEvent.click(screen.getByRole('tab', { name: /Day's jobs/ }));
    expect(screen.getByTestId('scheduler-day-placeholder')).toHaveTextContent('The day picture is coming soon.');
    expect(screen.queryByTestId('scheduler-all-jobs')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/scheduler?view=day');
    expect(window.history.length).toBe(before);
    fireEvent.click(screen.getByRole('tab', { name: /All jobs/ }));
    expect(window.location.hash).toBe('#manage/scheduler?view=all');
  });

  it('follows the arrow keys between the tabs', async () => {
    mount();
    await screen.findByTestId('scheduler-all-jobs');
    fireEvent.keyDown(screen.getByRole('tab', { name: /All jobs/ }), { key: 'ArrowLeft' });
    expect(screen.getByTestId('scheduler-day-placeholder')).toBeInTheDocument();
  });

  it('lets the hash win over a remembered tab, and falls back from an invalid view', async () => {
    window.localStorage.setItem('photocast.scheduler.view', 'day');
    window.location.hash = '#manage/scheduler?view=all';
    mount();
    expect(await screen.findByTestId('scheduler-all-jobs')).toBeInTheDocument();
    cleanup();
    window.location.hash = '#manage/scheduler?view=bogus';
    mount();
    // an invalid view falls back to the remembered tab
    expect(await screen.findByTestId('scheduler-day-placeholder')).toBeInTheDocument();
  });

  it('still opens when storage throws', async () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    try {
      mount();
      expect(await screen.findByTestId('scheduler-all-jobs')).toBeInTheDocument();
    } finally {
      spy.mockRestore();
    }
  });

  it('keeps ?view and ?date when a panel opens and closes, and opens the old #manage/scheduler/{key} links', async () => {
    window.location.hash = '#manage/scheduler?view=all&date=2026-11-02';
    mount();
    await openPanel('tide_refresh');
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh?view=all&date=2026-11-02');
    fireEvent.click(screen.getByTestId('scheduler-panel-close'));
    expect(window.location.hash).toBe('#manage/scheduler?view=all&date=2026-11-02');
    cleanup();
    window.location.hash = '#manage/scheduler/tide_refresh';
    mount();
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('draws the panel as a 520px drawer over the list at a desktop width, the list inert behind it', async () => {
    mount();
    await openPanel('tide_refresh');
    expect(screen.getByTestId('scheduler-split-scrim')).not.toHaveClass('min-[1000px]:hidden');
    expect(screen.getByTestId('scheduler-split-panel')).toHaveClass('w-[520px]');
    expect(screen.getByTestId('scheduler-split-list')).toHaveAttribute('inert');
  });

  it('closes the drawer on the scrim', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('scheduler-split-scrim'));
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });
});

describe('SchedulerView: the panel\'s read-only sections', () => {
  const CLAUDE = catalogue({
    alias: 'The main forecast',
    what: 'Sends every sunrise to Claude.',
    why: 'Fires by the sunrise rule.',
    purpose: 'Forecast',
    usesClaude: true,
    services: ['Open-Meteo', 'Anthropic Batch API'],
    resultsCollectedBy: ['batch_result_polling'],
    staggeredBefore: ['tide_refresh'],
    watchedBy: ['retired_job'],
  });
  const POLLER = job({
    id: 5,
    jobKey: 'batch_result_polling',
    displayName: 'Batch Result Polling',
    scheduleType: 'FIXED_DELAY',
    fixedDelayMs: 60000,
    catalogue: catalogue({ alias: 'The collector', collectsResultsFrom: [NIGHTLY], purpose: 'Batch plumbing' }),
    lastFire: lastFire({ summary: 'No batches in flight' }),
  });
  const MAIN = job({
    catalogue: CLAUDE,
    lastFire: lastFire({ summary: '3 of 3 batches submitted' }),
    nextFires: [inHours(1.5), inHours(26.5), inHours(50.5)],
    configSource: 'forecast.enabled',
  });

  it('heads the panel with the alias and the purpose tag, and says what the job does and why it runs then', async () => {
    mount([MAIN, POLLER]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId('scheduler-panel-meta')).toHaveTextContent('The main forecast')
    expect(screen.getByTestId('scheduler-panel-meta')).toHaveTextContent('Forecast');
    expect(screen.getByTestId('scheduler-panel-what-text')).toHaveTextContent('Sends every sunrise to Claude.');
    expect(screen.getByTestId('scheduler-panel-why')).toHaveTextContent('Fires by the sunrise rule.');
  });

  it('lists the next three fires with how far off each is', async () => {
    mount([MAIN]);
    await openPanel(NIGHTLY);
    const rows = within(screen.getByTestId('scheduler-panel-upcoming')).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(/^(Today|Tomorrow) \d{2}:\d{2}in 1h$/);
    expect(rows[1]).toHaveTextContent('in 1d');
  });

  it('shows the last run\'s status, when, duration and one-line result, with Open in Runs when a run is served', async () => {
    mount([job({
      catalogue: CLAUDE, runGroup: 'CYCLES', lastRun: lastRunCycle({ id: 412 }), lastFire: lastFire({ summary: '3 of 3 batches submitted' }),
    })]);
    await openPanel(NIGHTLY);
    // a cycle's fire ends when its batches are submitted, so "took" is not shown for it
    expect(screen.getByTestId('scheduler-panel-last-line')).toHaveTextContent(/OK.*15 min ago/);
    expect(screen.getByTestId('scheduler-panel-last-line')).not.toHaveTextContent('took');
    expect(screen.getByTestId('scheduler-panel-last-summary')).toHaveTextContent('3 of 3 batches submitted');
    expect(screen.getByTestId('scheduler-panel-open-run')).toHaveAttribute('href', '#manage/runs/cycle/412');
    expect(screen.getByTestId('scheduler-panel-open-run')).toHaveTextContent('Latest run in Runs');
    expect(screen.queryByTestId('scheduler-panel-no-runs')).not.toBeInTheDocument();
  });

  it('says how long a cycle typically takes, from the run-based detail', async () => {
    mount([MAIN]);
    await openPanel(NIGHTLY);
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-last-line')).toHaveTextContent('a cycle typically takes 1h 30m'));
  });

  it('says "took X · typically Y", in the one duration form, for a job whose typical comes from its fires', async () => {
    fetchJobDetail.mockResolvedValue(jobDetail({ typicalDurationSeconds: 125, typicalDurationBasis: 'fires', claudeCost30d: null }));
    mount([tideJob({
      catalogue: catalogue(),
      lastFire: lastFire({ firedAt: inHours(-1), completedAt: new Date(Date.now() - 3_600_000 + 125_000).toISOString() }),
    })]);
    await openPanel('tide_refresh');
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-last-line')).toHaveTextContent('took 2m 5s · typically 2m 5s'));
  });

  it('marks a manual fire', async () => {
    mount([tideJob({ catalogue: catalogue(), lastFire: lastFire({ trigger: 'MANUAL' }) })]);
    await openPanel('tide_refresh');
    expect(screen.getByTestId('scheduler-panel-trigger')).toHaveTextContent('manual');
  });

  it('shows no trigger marker for a scheduled fire', async () => {
    mount([tideJob({ catalogue: catalogue(), lastFire: lastFire({ trigger: 'SCHEDULED' }) })]);
    await openPanel('tide_refresh');
    expect(screen.queryByTestId('scheduler-panel-trigger')).not.toBeInTheDocument();
  });

  it('keeps the Runs link, and says no scheduler fire, when a run is served but the log has none', async () => {
    mount([job({ catalogue: catalogue(), runGroup: 'CYCLES', lastRun: lastRunCycle({ id: 9 }), lastFire: null })]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId('scheduler-panel-last-none')).toHaveTextContent('No scheduler fire in the last 14 days.');
    expect(screen.getByTestId('scheduler-panel-open-run')).toHaveAttribute('href', '#manage/runs/cycle/9');
  });

  it('refreshes the Last run with the poll while the panel is open', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount([tideJob({ catalogue: catalogue(), lastFire: lastFire({ summary: 'first result' }) })]);
      await openPanel('tide_refresh');
      expect(screen.getByTestId('scheduler-panel-last-summary')).toHaveTextContent('first result');
      fetchSchedulerJobs.mockResolvedValue([tideJob({ catalogue: catalogue(), lastFire: lastFire({ summary: 'second result' }) })]);
      await act(async () => { vi.advanceTimersByTime(30_000); });
      await waitFor(() => expect(screen.getByTestId('scheduler-panel-last-summary')).toHaveTextContent('second result'));
    } finally {
      vi.useRealTimers();
    }
  });

  it('says a paused job has no upcoming fires, where the next fires go', async () => {
    mount([tideJob({ catalogue: catalogue(), status: 'PAUSED', nextFires: [] })]);
    await openPanel('tide_refresh');
    expect(screen.getByTestId('scheduler-panel-paused-note')).toHaveTextContent('Paused: no upcoming fires.');
  });

  it('reads a sun-relative rule in compact words in the panel too', async () => {
    mount([solarJob({ catalogue: catalogue() })]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId(`solar-rule-${NIGHTLY}`)).toHaveTextContent('4h 15m before sunrise');
  });

  it('says individual runs are not listed in Runs yet for a job Runs does not record', async () => {
    mount([POLLER]);
    await openPanel('batch_result_polling');
    expect(screen.getByTestId('scheduler-panel-no-runs')).toHaveTextContent("Individual runs of this job aren't listed in Runs yet.");
    expect(screen.queryByTestId('scheduler-panel-open-run')).not.toBeInTheDocument();
  });

  it('says No run recorded yet for a job that has never fired', async () => {
    mount([job({ catalogue: catalogue() })]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId('scheduler-panel-last-none')).toBeInTheDocument();
  });

  it('shows the Claude cost: the 30-day figure and about what a run costs, fetched when the panel opens', async () => {
    let release;
    fetchJobDetail.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId('scheduler-panel-cost-loading')).toBeInTheDocument();
    release(jobDetail());
    expect(await screen.findByTestId('scheduler-panel-cost-figure')).toHaveTextContent('£12.50 over the last 30 days · about £0.42 a run');
    expect(fetchJobDetail).toHaveBeenCalledWith(NIGHTLY);
    expect(screen.queryByTestId('scheduler-panel-cost-notes')).not.toBeInTheDocument();
  });

  it('notes an unknown cost and manual runs', async () => {
    fetchJobDetail.mockResolvedValue(jobDetail({
      claudeCost30d: {
        gbp: 3, complete: false, runs: 2, includes: ['briefing', 'mystery'], inclManual: true, perRun: 1.5, reason: null,
      },
    }));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    const notes = await screen.findByTestId('scheduler-panel-cost-notes');
    expect(notes).toHaveTextContent('Some costs are still unknown');
    expect(notes).toHaveTextContent('Includes manual runs.');
    expect(notes).toHaveTextContent('Counts the briefing.');
  });

  it('words the counted runs in plain English, drops an unknown key and never shows a raw one', async () => {
    fetchJobDetail.mockResolvedValue(jobDetail({
      claudeCost30d: {
        gbp: 3, complete: true, runs: 2, includes: ['batches', 'briefing', 'askReady', 'evalRuns', 'rawKey'], inclManual: true,
        perRun: 1.5, reason: null,
      },
    }));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    const notes = await screen.findByTestId('scheduler-panel-cost-notes');
    expect(notes).toHaveTextContent(
      "Counts the batches, the briefing, Ask's ready answers and the eval runs. Includes manual runs.",
    );
    expect(notes).not.toHaveTextContent('rawKey');
    expect(notes).not.toHaveTextContent('askReady');
  });

  it('says no runs, not £0.00, when the cost covers none', async () => {
    fetchJobDetail.mockResolvedValue(jobDetail({
      claudeCost30d: { gbp: 0, complete: true, runs: 0, includes: [], inclManual: false, perRun: null, reason: null },
    }));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    expect(await screen.findByTestId('scheduler-panel-cost-no-runs')).toHaveTextContent('No runs in the last 30 days.');
    expect(screen.queryByTestId('scheduler-panel-cost-figure')).not.toBeInTheDocument();
  });

  it('keeps £0.00 when there were runs and the cost really is zero', async () => {
    fetchJobDetail.mockResolvedValue(jobDetail({
      claudeCost30d: { gbp: 0, complete: true, runs: 4, includes: [], inclManual: false, perRun: 0, reason: null },
    }));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    expect(await screen.findByTestId('scheduler-panel-cost-figure')).toHaveTextContent('£0.00 over the last 30 days');
  });

  it('says the cost could not be loaded when the detail fails', async () => {
    fetchJobDetail.mockRejectedValue(new Error('down'));
    mount([MAIN]);
    await openPanel(NIGHTLY);
    expect(await screen.findByTestId('scheduler-panel-cost-error')).toBeInTheDocument();
  });

  it('says a job costs nothing, and for a poller whose batch it is counted against', async () => {
    mount([MAIN, POLLER, tideJob({ catalogue: catalogue() })]);
    await openPanel('batch_result_polling');
    expect(screen.getByTestId('scheduler-panel-cost-none')).toHaveTextContent(
      "None. This job doesn't call Claude; batch costs are counted against the job that submitted them.",
    );
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-cost-none')).toHaveTextContent(/^None\. This job doesn't call Claude\.$/));
  });

  it('shows the services as chips, or says the job touches nothing outside PhotoCast', async () => {
    mount([MAIN, tideJob({ catalogue: catalogue() })]);
    await openPanel(NIGHTLY);
    const list = screen.getByTestId('scheduler-panel-services-list');
    expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Open-Meteo', 'Anthropic Batch API']);
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    expect(await screen.findByTestId('scheduler-panel-services-none')).toHaveTextContent('Nothing outside PhotoCast. Database only.');
  });

  it('lists each connected job under its label, switches the panel to it, and shows a retired key as plain text', async () => {
    mount([MAIN, POLLER, tideJob()]);
    await openPanel(NIGHTLY);
    expect(within(screen.getByTestId('scheduler-edge-resultsCollectedBy')).getByText('Results collected by')).toBeInTheDocument();
    expect(within(screen.getByTestId('scheduler-edge-staggeredBefore')).getByText('Followed 30 min later by')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-edge-watchedBy')).toHaveTextContent('retired_job');
    expect(screen.queryByTestId('scheduler-connected-retired_job')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-edge-watches')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Results collected by: Batch Result Polling, open its panel' }));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Batch Result Polling'));
    expect(window.location.hash).toContain('#manage/scheduler/batch_result_polling');
    expect(within(screen.getByTestId('scheduler-edge-collectsResultsFrom')).getByText('Collects results from')).toBeInTheDocument();
  });

  it('ends with the technical facts in mono: the key, the config it requires and the stored description', async () => {
    mount([MAIN]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId('scheduler-technical-key')).toHaveTextContent(NIGHTLY);
    expect(screen.getByTestId('scheduler-technical-config')).toHaveTextContent('forecast.enabled');
    expect(screen.getByTestId('scheduler-technical-description')).toHaveTextContent('The morning forecast cycle');
  });

  it('draws a job with no catalogue entry with its stored description and no plain-English sections', async () => {
    mount([job()]);
    await openPanel(NIGHTLY);
    expect(screen.queryByTestId('scheduler-panel-what')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-panel-cost')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-panel-services')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-panel-connected')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel-meta')).toHaveTextContent('Cron');
    expect(screen.getByTestId('scheduler-technical-description')).toBeInTheDocument();
  });

  it('fetches the detail once when the panel opens, and never on the poll', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount([MAIN]);
      await openPanel(NIGHTLY);
      await screen.findByTestId('scheduler-panel-cost-figure');
      await act(async () => { vi.advanceTimersByTime(30_000); });
      await waitFor(() => expect(fetchSchedulerSummary.mock.calls.length).toBeGreaterThan(1));
      expect(fetchJobDetail).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
