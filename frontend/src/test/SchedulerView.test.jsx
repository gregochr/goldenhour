import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { setRewind } from '../utils/rewind.js';
import {
  NIGHTLY, inHours, job, lastRunCycle, pollingJob, solarJob, tideJob,
} from './schedulerFixtures.js';
import { jobItem } from './runsFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
  fetchJobRuns: vi.fn(),
}));
// jsdom has no layout: the split breakpoint (1000px) is decided here, so the list stays reachable beside the panel.
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchSchedulerJobs,
  updateJobSchedule,
  pauseJob,
  resumeJob,
  triggerJob,
  fetchSolarPreview,
  fetchJobRuns,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

// The Scheduler tab end to end below ManageView: the served jobs on the list-and-panel shell, the panel by hash, and
// what moved out of the old cards (Edit, Pause, Resume, Run now) into the panel. The network is mocked at the API module.

const TIDE = tideJob({
  lastFireTime: new Date(Date.now() - 180_000).toISOString(),
  nextFireTime: new Date(Date.now() + 86_400_000).toISOString(),
});
const POLLING = pollingJob({ status: 'PAUSED' });
const BASE_JOBS = [TIDE, POLLING];

function mount(jobs = BASE_JOBS) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  return render(<SchedulerView />);
}

/** Opens a job's panel by pressing its row. */
async function openPanel(jobKey) {
  fireEvent.click(await screen.findByTestId(`scheduler-row-${jobKey}`));
  return screen.findByTestId('scheduler-panel');
}

/** The scheduler row keys in the order they sit in the list. */
const rowOrder = () => screen.getAllByTestId(/^scheduler-row-/).map((row) => row.dataset.testid.replace('scheduler-row-', ''));

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  fetchSolarPreview.mockRejectedValue(new Error('preview not under test here'));
  fetchJobRuns.mockResolvedValue([]);
});

afterEach(() => {
  setRewind(null);
});

describe('SchedulerView: the list', () => {
  it('renders a row per job, with its status in words', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-row-tide_refresh')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-status-tide_refresh')).toHaveTextContent('Active');
    expect(screen.getByTestId('scheduler-status-aurora_polling')).toHaveTextContent('Paused');
  });

  it('says "Off in config", not "Disabled by Config", for a job its config switches off', async () => {
    mount([job({ status: 'DISABLED_BY_CONFIG', configSource: 'aurora.enabled' })]);
    expect(await screen.findByTestId(`scheduler-status-${NIGHTLY}`)).toHaveTextContent('Off in config');
  });

  it('heads the list "Scheduler" with the job count and the UK time label', async () => {
    mount();
    const meta = await screen.findByTestId('scheduler-meta');
    expect(screen.getByTestId('scheduler-heading')).toHaveTextContent('Scheduler');
    expect(meta.textContent).toMatch(/^2 jobs · UK time \((BST|GMT)\)$/);
  });

  it('labels the header BST in summer and GMT in winter, from the real clock', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-07-10T10:00:00Z'));
      const { unmount } = mount();
      expect(await screen.findByTestId('scheduler-meta')).toHaveTextContent('UK time (BST)');
      unmount();
      vi.setSystemTime(new Date('2026-01-15T10:00:00Z'));
      mount();
      expect(await screen.findByTestId('scheduler-meta')).toHaveTextContent('UK time (GMT)');
    } finally {
      vi.useRealTimers();
    }
  });

  it('holds no control in a row: Edit, Pause, Resume and Run now live in the panel', async () => {
    mount();
    const row = await screen.findByTestId('scheduler-row-tide_refresh');
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryByTestId('edit-btn-tide_refresh')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pause-btn-tide_refresh')).not.toBeInTheDocument();
    expect(screen.queryByTestId('trigger-btn-tide_refresh')).not.toBeInTheDocument();
    expect(screen.queryByTestId('resume-btn-aurora_polling')).not.toBeInTheDocument();
  });

  it('sorts by next fire, soonest first, with paused and off rows last', async () => {
    mount([
      job({ id: 1, jobKey: 'late', displayName: 'Late', nextFireTime: inHours(48) }),
      job({ id: 2, jobKey: 'paused_early', displayName: 'Paused early', status: 'PAUSED', nextFireTime: inHours(1) }),
      job({ id: 3, jobKey: 'soon', displayName: 'Soon', nextFireTime: inHours(2) }),
      job({ id: 4, jobKey: 'off', displayName: 'Off', status: 'DISABLED_BY_CONFIG', nextFireTime: '' }),
      job({ id: 5, jobKey: 'middle', displayName: 'Middle', nextFireTime: inHours(10) }),
    ]);
    await screen.findByTestId('scheduler-row-soon');
    expect(rowOrder()).toEqual(['soon', 'middle', 'late', 'off', 'paused_early']);
  });

  it('reverses the active rows on a second press of Next and keeps paused and off rows last', async () => {
    mount([
      job({ id: 1, jobKey: 'late', displayName: 'Late', nextFireTime: inHours(48) }),
      job({ id: 2, jobKey: 'paused', displayName: 'Paused', status: 'PAUSED', nextFireTime: '' }),
      job({ id: 3, jobKey: 'soon', displayName: 'Soon', nextFireTime: inHours(2) }),
    ]);
    fireEvent.click(await screen.findByTestId('scheduler-sort-next'));
    expect(rowOrder()).toEqual(['late', 'soon', 'paused']);
    expect(screen.getByTestId('scheduler-sort-next')).toHaveAttribute('aria-label', 'Next, sorted descending');
  });

  it('sorts by job name from the Job header', async () => {
    mount([
      job({ id: 1, jobKey: 'b', displayName: 'Bravo', nextFireTime: inHours(1) }),
      job({ id: 2, jobKey: 'a', displayName: 'Alpha', nextFireTime: inHours(5) }),
    ]);
    fireEvent.click(await screen.findByTestId('scheduler-sort-name'));
    expect(rowOrder()).toEqual(['a', 'b']);
  });

  it('dims a paused row and an off row', async () => {
    mount([TIDE, POLLING, job({ id: 9, jobKey: 'off', displayName: 'Off', status: 'DISABLED_BY_CONFIG' })]);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByTestId('scheduler-row-tide_refresh').className).not.toContain('text-plex-text-muted');
    expect(screen.getByTestId('scheduler-row-aurora_polling').className).toContain('text-plex-text-muted');
    expect(screen.getByTestId('scheduler-row-off').className).toContain('text-plex-text-muted');
  });

  it('shows no Next time for a paused job', async () => {
    mount([job({ status: 'PAUSED', nextFireTime: inHours(5) })]);
    expect(await screen.findByTestId(`scheduler-next-${NIGHTLY}`)).toHaveTextContent('—');
  });

  it('shows the Next time and how far off it is, and never "just now" for the future', async () => {
    mount();
    const cell = await screen.findByTestId('scheduler-next-tide_refresh');
    // ~24h ahead: "in 23h" or "in 1d"
    expect(screen.getByTestId('next-fire-tide_refresh').textContent).toMatch(/in \d+[hd]/);
    expect(cell.textContent).not.toContain('just now');
  });

  it('shows the human-readable cron, with UK time and no raw millisecond values', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-schedule-tide_refresh')).toHaveTextContent(
      /Every Monday at \d{2}:\d{2} (BST|GMT)/,
    );
    expect(screen.getByTestId('scheduler-schedule-aurora_polling')).toHaveTextContent('Every 5 minutes');
    expect(screen.getByTestId('scheduler-row-aurora_polling').textContent).not.toContain('300000ms');
  });

  it('shows an hourly fixed delay in words', async () => {
    mount([pollingJob({ fixedDelayMs: 3600000 })]);
    expect(await screen.findByTestId('scheduler-schedule-aurora_polling')).toHaveTextContent('Every hour');
  });

  it('names the schedule type under the words', async () => {
    mount([TIDE, POLLING, solarJob()]);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByTestId('scheduler-row-tide_refresh')).toHaveTextContent('Cron');
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveTextContent('Fixed delay');
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent('4 h 15 min before sunrise');
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent('Sun-relative');
  });

  it('reads an unknown schedule type as unrecognised, in the list and in the panel', async () => {
    mount([job({ scheduleType: 'WEEKLY_MAGIC', cronExpression: '', nextFireTime: '' })]);
    expect(await screen.findByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent(
      'Unrecognised schedule type: WEEKLY_MAGIC',
    );
    await openPanel(NIGHTLY);
    expect(screen.getByTestId(`unrecognised-${NIGHTLY}`)).toHaveTextContent('Unrecognised schedule type: WEEKLY_MAGIC');
    expect(screen.queryByTestId(`edit-btn-${NIGHTLY}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`switch-btn-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('an empty schedule type is unrecognised too, never an empty fixed delay', async () => {
    mount([job({ scheduleType: '', cronExpression: '' })]);
    await openPanel(NIGHTLY);
    expect(screen.getByTestId(`unrecognised-${NIGHTLY}`)).toHaveTextContent('(none)');
    expect(screen.queryByTestId(`edit-btn-${NIGHTLY}`)).not.toBeInTheDocument();
  });

  it('shows an error banner, and not "No scheduled jobs.", when the first load fails', async () => {
    fetchSchedulerJobs.mockRejectedValue(new Error('Server error'));
    render(<SchedulerView />);
    expect(await screen.findByText(/Failed to load scheduler jobs/)).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-empty')).not.toBeInTheDocument();
  });

  it('says so when there are no jobs', async () => {
    mount([]);
    expect(await screen.findByTestId('scheduler-empty')).toHaveTextContent('No scheduled jobs.');
  });
});

describe('SchedulerView: the Last run column', () => {
  it('shows the last run\'s time and status when the server attributes one', async () => {
    mount([job({ runGroup: 'CYCLES', lastRun: lastRunCycle(), lastFireTime: inHours(-3) })]);
    const cell = await screen.findByTestId(`scheduler-last-${NIGHTLY}`);
    // 3 hours ago: Today or, just after midnight UK, Yesterday
    expect(cell).toHaveTextContent(/(Today|Yesterday) \d{2}:\d{2}/);
    expect(cell).toHaveTextContent('Degraded');
    // the last run wins over the last fire time
    expect(cell).not.toHaveTextContent('fired');
  });

  it('falls back to the last fire time, marked "fired", for a job with no attributable run', async () => {
    mount();
    const cell = await screen.findByTestId('scheduler-last-tide_refresh');
    expect(cell).toHaveTextContent('fired');
    expect(screen.getByTestId('last-fire-tide_refresh')).toHaveTextContent('3 min ago');
  });

  it('shows an em dash for a job that has never fired and has no run', async () => {
    mount([POLLING]);
    expect(await screen.findByTestId('scheduler-last-aurora_polling')).toHaveTextContent('—');
  });

  it('shows a running last run with the Running words', async () => {
    mount([job({ runGroup: 'CYCLES', lastRun: lastRunCycle({ status: 'RUNNING', endedAt: null }) })]);
    expect(await screen.findByTestId(`scheduler-last-${NIGHTLY}`)).toHaveTextContent('Running');
  });
});

describe('SchedulerView: the chips', () => {
  const MIXED = [TIDE, POLLING, solarJob(), job({ id: 7, jobKey: 'off', displayName: 'Off', status: 'DISABLED_BY_CONFIG' })];

  it('counts each chip and narrows the list by schedule type', async () => {
    mount(MIXED);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.getByTestId('scheduler-chip-all-count')).toHaveTextContent('4');
    expect(screen.getByTestId('scheduler-chip-solar-offset-count')).toHaveTextContent('1');
    expect(screen.getByTestId('scheduler-chip-cron-count')).toHaveTextContent('2');
    expect(screen.getByTestId('scheduler-chip-fixed-delay-count')).toHaveTextContent('1');

    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    expect(rowOrder().sort()).toEqual(['off', 'tide_refresh']);
    fireEvent.click(screen.getByTestId('scheduler-chip-solar-offset'));
    expect(rowOrder()).toEqual([NIGHTLY]);
    fireEvent.click(screen.getByTestId('scheduler-chip-all'));
    expect(rowOrder()).toHaveLength(4);
  });

  it('ANDs the status chips with the type chips, and a second press clears the status', async () => {
    mount(MIXED);
    await screen.findByTestId('scheduler-row-tide_refresh');
    fireEvent.click(screen.getByTestId('scheduler-chip-paused'));
    expect(rowOrder()).toEqual(['aurora_polling']);
    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    expect(screen.getByTestId('scheduler-empty')).toHaveTextContent('No jobs match these filters.');
    fireEvent.click(screen.getByTestId('scheduler-chip-paused'));
    expect(rowOrder().sort()).toEqual(['off', 'tide_refresh']);
    fireEvent.click(screen.getByTestId('scheduler-chip-disabled-by-config'));
    expect(rowOrder()).toEqual(['off']);
  });

  it('shows the Fixed delay chip only when some job has that type', async () => {
    mount([TIDE]);
    await screen.findByTestId('scheduler-row-tide_refresh');
    expect(screen.queryByTestId('scheduler-chip-fixed-delay')).not.toBeInTheDocument();
  });

  it('closes an open panel when a chip filters its job out, and leaves it when the job still matches', async () => {
    mount(MIXED);
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('scheduler-chip-cron'));
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('scheduler-chip-fixed-delay'));
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
    expect(window.location.hash).toBe('#manage/scheduler');
  });
});

describe('SchedulerView: the panel by hash', () => {
  it('opens a job\'s panel from its row and writes the job key to the hash', async () => {
    mount();
    const panel = await openPanel('tide_refresh');
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh');
    expect(within(panel).getByTestId('scheduler-panel-title')).toHaveTextContent('Tide Refresh');
    expect(screen.getByTestId('scheduler-panel-meta')).toHaveTextContent(/^Cron · Active · next /);
  });

  it('opens the panel named by a pasted hash', async () => {
    window.location.hash = '#manage/scheduler/aurora_polling';
    mount();
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Aurora Polling');
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveAttribute('aria-current', 'true');
  });

  it('a pasted key that names no job leaves the bare tab address, replacing the entry', async () => {
    window.location.hash = '#manage/scheduler/no_such_job';
    const before = window.history.length;
    mount();
    await screen.findByTestId('scheduler-row-tide_refresh');
    await waitFor(() => expect(window.location.hash).toBe('#manage/scheduler'));
    expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument();
    expect(window.history.length).toBe(before);
  });

  it('does not clear a pasted key just because the first load failed', async () => {
    window.location.hash = '#manage/scheduler/tide_refresh';
    fetchSchedulerJobs.mockRejectedValue(new Error('down'));
    render(<SchedulerView />);
    await screen.findByText(/Failed to load scheduler jobs/);
    expect(window.location.hash).toBe('#manage/scheduler/tide_refresh');
  });

  it('closes on the ×, and on Esc, back to the bare tab address', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('scheduler-panel-close'));
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
    expect(window.location.hash).toBe('#manage/scheduler');

    await openPanel('tide_refresh');
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });

  it('is never a dialog: the panel is an aside named for the job', async () => {
    mount();
    await openPanel('tide_refresh');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Tide Refresh job' })).toBeInTheDocument();
  });

  it('puts the job\'s description and its cron in the Schedule section', async () => {
    mount();
    const panel = await openPanel('tide_refresh');
    expect(panel).toHaveTextContent('Refreshes tide extremes weekly');
    const schedule = within(panel).getByTestId('scheduler-panel-schedule');
    expect(schedule).toHaveTextContent('0 0 2 * * MON');
    expect(schedule).toHaveTextContent(/Every Monday at \d{2}:\d{2} (BST|GMT)/);
  });

  it('shows no Next fires, and no timing notes, for a plain cron job that is no cycle', async () => {
    mount();
    await openPanel('tide_refresh');
    expect(screen.queryByTestId('scheduler-panel-next-fires')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-panel-timing')).not.toBeInTheDocument();
  });
});

describe('SchedulerView: editing a cron or fixed-delay schedule in the panel', () => {
  it('shows the input on Edit and hides it on Cancel, giving focus back to Edit', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));

    expect(screen.getByTestId('edit-input-tide_refresh')).toBeInTheDocument();
    expect(screen.getByTestId('cancel-btn-tide_refresh')).toBeInTheDocument();
    expect(screen.queryByTestId('edit-btn-tide_refresh')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('cancel-btn-tide_refresh'));
    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('edit-btn-tide_refresh')).toBe(document.activeElement));
  });

  it('Esc steps back from the editor first, and only then closes the panel', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    const input = screen.getByTestId('edit-input-tide_refresh');
    expect(input).toBe(document.activeElement);

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });

  it('saves a cron through updateJobSchedule with the cron alone, then reloads the jobs', async () => {
    updateJobSchedule.mockResolvedValue({});
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.change(screen.getByTestId('edit-input-tide_refresh'), { target: { value: '0 0 3 * * TUE' } });
    const loads = fetchSchedulerJobs.mock.calls.length;
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(updateJobSchedule).toHaveBeenCalledWith('tide_refresh', {
      cronExpression: '0 0 3 * * TUE',
    }));
    await waitFor(() => expect(fetchSchedulerJobs.mock.calls.length).toBeGreaterThan(loads));
    await waitFor(() => expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument());
  });

  it('saves a fixed delay as a number of milliseconds', async () => {
    updateJobSchedule.mockResolvedValue({});
    mount();
    await openPanel('aurora_polling');
    fireEvent.click(screen.getByTestId('edit-btn-aurora_polling'));
    fireEvent.change(screen.getByTestId('edit-input-aurora_polling'), { target: { value: '600000' } });
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));

    await waitFor(() => expect(updateJobSchedule).toHaveBeenCalledWith('aurora_polling', { fixedDelayMs: 600000 }));
  });

  it('says so beside the field, and keeps the editor open, when the save fails', async () => {
    updateJobSchedule.mockRejectedValue(new Error('400'));
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    expect(await screen.findByTestId('inline-edit-error-tide_refresh')).toHaveTextContent(
      'Failed to update schedule for tide_refresh',
    );
    expect(screen.getByTestId('edit-input-tide_refresh')).toBeInTheDocument();
  });

  it('updates the cron reading live as the expression is edited', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.change(screen.getByTestId('edit-input-tide_refresh'), { target: { value: '0 0 6 * * *' } });

    expect(screen.getByTestId('edit-reading-tide_refresh').textContent).toMatch(/Daily at \d{2}:\d{2} (BST|GMT)/);
  });

  it('shows no cron reading while editing a fixed-delay job', async () => {
    mount();
    await openPanel('aurora_polling');
    fireEvent.click(screen.getByTestId('edit-btn-aurora_polling'));

    expect(screen.queryByTestId('edit-reading-aurora_polling')).not.toBeInTheDocument();
    const panel = screen.getByTestId('scheduler-panel');
    expect(within(panel).queryByText(/Daily at/)).not.toBeInTheDocument();
    expect(within(panel).queryByText(/Every .* at/)).not.toBeInTheDocument();
  });

  it('opens a different job with no editor, and the editor is gone when the first job comes back', async () => {
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('scheduler-row-aurora_polling'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Aurora Polling'));
    expect(screen.queryByTestId('edit-input-aurora_polling')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    await waitFor(() => expect(screen.getByTestId('scheduler-panel-title')).toHaveTextContent('Tide Refresh'));
    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
  });
});

describe('SchedulerView: Pause, Resume and a job that is off in config', () => {
  it('Pause calls the API and reloads the jobs', async () => {
    pauseJob.mockResolvedValue({});
    mount();
    await openPanel('tide_refresh');
    expect(screen.getByTestId('scheduler-panel-actions')).toHaveTextContent('Skips its fires until you resume it.');
    fireEvent.click(screen.getByTestId('pause-btn-tide_refresh'));

    await waitFor(() => {
      expect(pauseJob).toHaveBeenCalledWith('tide_refresh');
      // fetchSchedulerJobs: once on mount, once after the pause
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2);
    });
  });

  it('Resume calls the API, and says when the job fires again', async () => {
    resumeJob.mockResolvedValue({});
    mount();
    await openPanel('aurora_polling');
    expect(screen.getByTestId('scheduler-panel-actions')).toHaveTextContent('Fires again from its next time.');
    fireEvent.click(screen.getByTestId('resume-btn-aurora_polling'));

    await waitFor(() => expect(resumeJob).toHaveBeenCalledWith('aurora_polling'));
  });

  it('reports a failed pause in the panel, where the reader is looking', async () => {
    pauseJob.mockRejectedValue(new Error('Network error'));
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('pause-btn-tide_refresh'));

    expect(await screen.findByTestId('scheduler-action-error')).toHaveTextContent('Failed to pause tide_refresh');
  });

  it('reports a failed resume the same way', async () => {
    resumeJob.mockRejectedValue(new Error('Network error'));
    mount();
    await openPanel('aurora_polling');
    fireEvent.click(screen.getByTestId('resume-btn-aurora_polling'));

    expect(await screen.findByTestId('scheduler-action-error')).toHaveTextContent('Failed to resume aurora_polling');
  });

  it('a job that is off in config shows what it requires, in mono, and every action is disabled', async () => {
    mount([
      pollingJob({ status: 'DISABLED_BY_CONFIG', configSource: 'aurora.enabled' }),
      job({ status: 'DISABLED_BY_CONFIG', configSource: 'photocast.cycle.enabled' }),
    ]);
    await openPanel('aurora_polling');

    const requires = screen.getByTestId('requires-aurora_polling');
    expect(requires).toHaveTextContent('Requires aurora.enabled');
    expect(within(requires).getByText('aurora.enabled').className).toContain('font-mono');
    expect(screen.getByTestId('resume-btn-aurora_polling')).toBeDisabled();
    expect(screen.getByTestId('trigger-btn-aurora_polling')).toBeDisabled();
    expect(screen.getByTestId('edit-btn-aurora_polling')).toBeDisabled();
  });

  it('disables the sun-relative switch of a cycle job that is off in config', async () => {
    mount([job({ status: 'DISABLED_BY_CONFIG', configSource: 'photocast.cycle.enabled' })]);
    await openPanel(NIGHTLY);

    expect(screen.getByTestId(`switch-btn-${NIGHTLY}`)).toBeDisabled();
    expect(screen.getByTestId(`edit-btn-${NIGHTLY}`)).toBeDisabled();
    expect(screen.getByTestId(`trigger-btn-${NIGHTLY}`)).toBeDisabled();
    expect(screen.getByTestId(`resume-btn-${NIGHTLY}`)).toBeDisabled();
  });
});

describe('SchedulerView: Recent runs', () => {
  const RUNS = [
    jobItem({ id: 77, startedAt: inHours(-2), durationSeconds: 72 }),
    lastRunCycle({ id: 412, startedAt: inHours(-30) }),
  ];

  it('lists a mapped job\'s runs, each linking to its Runs panel, and "All in Runs" filtered to its group', async () => {
    fetchJobRuns.mockResolvedValue(RUNS);
    mount();
    await openPanel('tide_refresh');

    const section = await screen.findByTestId('scheduler-panel-runs');
    await waitFor(() => expect(within(section).getAllByRole('link')).toHaveLength(3));
    expect(fetchJobRuns).toHaveBeenCalledWith('tide_refresh', 3);
    expect(screen.getByTestId('scheduler-run-job-77')).toHaveAttribute('href', '#manage/runs/job/77');
    expect(screen.getByTestId('scheduler-run-cycle-412')).toHaveAttribute('href', '#manage/runs/cycle/412');
    expect(screen.getByTestId('scheduler-run-job-77')).toHaveTextContent('Done');
    expect(screen.getByTestId('scheduler-run-cycle-412')).toHaveTextContent('Degraded');
    expect(screen.getByTestId('scheduler-run-job-77')).toHaveTextContent('1m 12s');
    expect(screen.getByTestId('scheduler-all-runs-tide_refresh')).toHaveAttribute('href', '#manage/runs?kind=tide');
  });

  it('filters "All in Runs" by the job\'s own group: cycles for a cycle job, aurora for the aurora batch', async () => {
    mount([solarJob(), job({ id: 8, jobKey: 'aurora_batch_evaluation', displayName: 'Aurora batch', runGroup: 'AURORA' })]);
    await openPanel(NIGHTLY);
    expect(await screen.findByTestId(`scheduler-all-runs-${NIGHTLY}`)).toHaveAttribute('href', '#manage/runs?kind=cycles');
    fireEvent.click(screen.getByTestId('scheduler-row-aurora_batch_evaluation'));
    expect(await screen.findByTestId('scheduler-all-runs-aurora_batch_evaluation')).toHaveAttribute(
      'href',
      '#manage/runs?kind=aurora',
    );
  });

  it('says so when a mapped job has no runs yet', async () => {
    mount();
    await openPanel('tide_refresh');
    expect(await screen.findByTestId('scheduler-runs-empty')).toHaveTextContent('No runs recorded yet.');
  });

  it('says so when the runs cannot be loaded', async () => {
    fetchJobRuns.mockRejectedValue(new Error('down'));
    mount();
    await openPanel('tide_refresh');
    expect(await screen.findByTestId('scheduler-runs-error')).toBeInTheDocument();
  });

  it('has no Recent runs section for a job the server cannot attribute runs to, and never asks', async () => {
    mount();
    await openPanel('aurora_polling');
    expect(screen.queryByTestId('scheduler-panel-runs')).not.toBeInTheDocument();
    expect(fetchJobRuns).not.toHaveBeenCalled();
  });

  it('refetches the open job\'s runs on each poll, without blanking them', async () => {
    fetchJobRuns.mockResolvedValue(RUNS);
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount();
      await openPanel('tide_refresh');
      await screen.findByTestId('scheduler-run-job-77');
      const before = fetchJobRuns.mock.calls.length;

      await act(async () => { vi.advanceTimersByTime(30_000); });

      await waitFor(() => expect(fetchJobRuns.mock.calls.length).toBeGreaterThan(before));
      expect(screen.getByTestId('scheduler-run-job-77')).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('SchedulerView: polling', () => {
  it('polls for job updates at the configured interval', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      mount();
      await vi.waitFor(() => expect(fetchSchedulerJobs).toHaveBeenCalledTimes(1));

      await act(async () => { vi.advanceTimersByTime(30_000); });
      await vi.waitFor(() => expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2));

      await act(async () => { vi.advanceTimersByTime(30_000); });
      await vi.waitFor(() => expect(fetchSchedulerJobs).toHaveBeenCalledTimes(3));
    } finally {
      // Restored here, not on a bare last line: an assertion above that throws must still hand fake timers back before
      // the next test (frontend-test-standards.md, "What NOT to do").
      vi.useRealTimers();
    }
  });

  it('skips a tick while the Operations pane is hidden, and resumes when it is shown', async () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    host.hidden = true;
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      fetchSchedulerJobs.mockResolvedValue(BASE_JOBS);
      render(<SchedulerView />, { container: host });
      await vi.waitFor(() => expect(fetchSchedulerJobs).toHaveBeenCalledTimes(1));

      await act(async () => { vi.advanceTimersByTime(30_000); });
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(1);

      host.hidden = false;
      await act(async () => { vi.advanceTimersByTime(30_000); });
      await vi.waitFor(() => expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2));
    } finally {
      vi.useRealTimers();
      host.remove();
    }
  });
});

describe('SchedulerView: the real clock, never the rewind clock (R-D7)', () => {
  beforeEach(() => {
    // The real "now" is pinned (Date only, so timers still run); Rewind will say a different day.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps "Today", the last fire\'s age and the next fire on the real day when a rewind is set', async () => {
    setRewind('2026-01-15T09:00:00Z');
    mount([job({
      runGroup: 'CYCLES',
      lastRun: lastRunCycle({ startedAt: '2026-10-31T06:00:00Z', endedAt: '2026-10-31T08:00:00Z' }),
      nextFireTime: '2026-10-31T17:30:00Z',
    }), tideJob({ lastFireTime: '2026-10-31T11:57:00Z', nextFireTime: '2026-11-02T06:00:00Z' })]);

    expect(await screen.findByTestId(`scheduler-last-${NIGHTLY}`)).toHaveTextContent('Today 06:00');
    expect(screen.getByTestId(`scheduler-next-${NIGHTLY}`)).toHaveTextContent('17:30');
    expect(screen.getByTestId(`next-fire-${NIGHTLY}`)).toHaveTextContent('in 5h');
    expect(screen.getByTestId('last-fire-tide_refresh')).toHaveTextContent('3 min ago');
    expect(screen.getByTestId('scheduler-next-tide_refresh')).toHaveTextContent('Mon 06:00');
    expect(screen.getByTestId('scheduler-meta')).toHaveTextContent('UK time (GMT)');
  });
});

describe('SchedulerView: describeCron through the list (UK time, exact values)', () => {
  // Date is pinned to April 2026 so Europe/London is deterministically on BST (UTC+1); a winter test pins January.
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-04-13T10:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function cronWords(overrides) {
    mount([tideJob(overrides)]);
    return (await screen.findByTestId('scheduler-schedule-tide_refresh')).textContent;
  }

  it('converts a single UTC hour to BST (+1)', async () => {
    expect(await cronWords({ cronExpression: '0 0 4 * * *' })).toContain('Daily at 05:00 BST');
  });

  it('converts multiple comma-separated UTC hours to BST, all shifted +1', async () => {
    expect(await cronWords({ cronExpression: '0 0 4,14,22 * * *' })).toContain('Daily at 05:00, 15:00, 23:00 BST');
  });

  it('wraps midnight UTC (00:00) to 01:00 BST', async () => {
    expect(await cronWords({ cronExpression: '0 0 0 * * *' })).toContain('Daily at 01:00 BST');
  });

  it('converts a weekly cron and uses the correct day name', async () => {
    expect(await cronWords({ cronExpression: '0 0 2 * * MON' })).toContain('Every Monday at 03:00 BST');
  });

  it.each([
    ['TUE', 'Tuesday'],
    ['WED', 'Wednesday'],
    ['THU', 'Thursday'],
    ['FRI', 'Friday'],
    ['SAT', 'Saturday'],
    ['SUN', 'Sunday'],
  ])('maps dow %s to full name "%s"', async (dow, name) => {
    expect(await cronWords({ cronExpression: `0 0 3 * * ${dow}` })).toContain(`Every ${name} at 04:00 BST`);
  });

  it('preserves non-zero minutes: 07:30 UTC is 08:30 BST', async () => {
    expect(await cronWords({ cronExpression: '0 30 7 * * *' })).toContain('Daily at 08:30 BST');
  });

  it('returns the raw expression when day-of-month is not *', async () => {
    expect(await cronWords({ cronExpression: '0 0 4 1 * *' })).toContain('0 0 4 1 * *');
  });

  it('returns the raw expression when month is not *', async () => {
    expect(await cronWords({ cronExpression: '0 0 4 * 6 *' })).toContain('0 0 4 * 6 *');
  });

  it('shows GMT, and no offset, in winter (January)', async () => {
    vi.setSystemTime(new Date('2026-01-15T10:00:00Z'));
    expect(await cronWords({ cronExpression: '0 0 4 * * *' })).toContain('Daily at 04:00 GMT');
  });

  it('the panel\'s live reading while editing also converts to UK time', async () => {
    mount([tideJob({ cronExpression: '0 0 4 * * *' })]);
    fireEvent.click(await screen.findByTestId('scheduler-row-tide_refresh'));
    fireEvent.click(await screen.findByTestId('edit-btn-tide_refresh'));
    fireEvent.change(screen.getByTestId('edit-input-tide_refresh'), { target: { value: '0 0 3 * * *' } });

    // 03:00 UTC is 04:00 BST
    expect(screen.getByTestId('edit-reading-tide_refresh')).toHaveTextContent('Daily at 04:00 BST');
  });

  it('shows a Europe/London cron as-is, labelled UK time, in BST', async () => {
    const words = await cronWords({ cronExpression: '0 0 21 * * *', timeZone: 'Europe/London' });
    expect(words).toContain('Daily at 21:00 UK time');
    expect(words).not.toContain('22:00');
  });

  it('shows a Europe/London cron unchanged in winter too', async () => {
    vi.setSystemTime(new Date('2026-01-13T10:00:00Z'));
    expect(await cronWords({ cronExpression: '0 0 21 * * *', timeZone: 'Europe/London' })).toContain(
      'Daily at 21:00 UK time',
    );
  });

  it('still converts a job whose timeZone is UTC', async () => {
    expect(await cronWords({ cronExpression: '0 0 4 * * *', timeZone: 'UTC' })).toContain('Daily at 05:00 BST');
  });
});

// ---------------------------------------------------------------------------
// The "Started" confirmation: who owns its dismiss timer
//
// ⚠️ Frozen fake clock, NOT `shouldAdvanceTime`. That option ties the fake clock to real time, so the wall time spent
// getting the confirmation on screen comes straight out of the 2s window being measured. Pumping with a zero advance
// settles the mount fetch and the trigger call while the clock stays exactly where it is. Every fake-clock test
// restores real timers in a `finally`, so an assertion that throws cannot leave the next test on a frozen clock.
//
// The `vi.getTimerCount()` assertions count EVERYTHING on the fake clock, the poll interval included, which the
// unmount clears, so zero means nothing outlived the screen. A failure there can therefore also mean the interval's
// cleanup went missing; the message says so.
// ---------------------------------------------------------------------------
describe('SchedulerView: the Run now confirmation', () => {
  /** Resolvers handed out by `deferTrigger` that the test has not released yet. */
  let unreleased = [];

  beforeEach(() => {
    // ⚠️ `clearAllMocks` does not empty a `mockReturnValueOnce` queue, and a queued Once outranks both the default below
    // and a test's own `mockRejectedValue`. A test that fails before consuming a deferred call would hand it to the
    // NEXT test's click. `mockReset` empties the queue.
    triggerJob.mockReset();
    fetchSchedulerJobs.mockResolvedValue(BASE_JOBS);
    triggerJob.mockResolvedValue({ status: 'triggered' });
  });

  afterEach(() => {
    // The net for a test that throws before releasing its calls: a promise left pending is a pending async
    // continuation, and nothing in this file should outlive its own test.
    unreleased.forEach((release) => release());
    unreleased = [];
  });

  /** Flushes pending promises without moving the fake clock at all. */
  async function pump() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  /** Renders, opens the tide job's panel and settles. */
  async function openTide() {
    const view = render(<SchedulerView />);
    await pump();
    fireEvent.click(screen.getByTestId('scheduler-row-tide_refresh'));
    await pump();
    return view;
  }

  /** The tide panel's trigger button, found through its role and accessible name. */
  function runNowButton(name) {
    return within(screen.getByTestId('scheduler-panel')).getByRole('button', { name });
  }

  /**
   * Hands the test the resolver for one trigger call, so the call is genuinely in flight until the test says
   * otherwise, and so it always settles. A promise that never settles poisons later tests.
   */
  function deferTrigger() {
    let release;
    triggerJob.mockReturnValueOnce(new Promise((resolve) => {
      release = () => resolve({ status: 'triggered' });
    }));
    unreleased.push(release);
    return () => release();
  }

  /** The message every "nothing left on the clock" assertion carries. */
  const OUTLIVED = 'a timer outlived the screen (a dismiss timer, or the poll interval)';

  it('explains Run now per job, and says where the run will appear once it is triggered', async () => {
    vi.useFakeTimers();
    try {
      await openTide();
      expect(screen.getByTestId('run-now-text-tide_refresh')).toHaveTextContent('Runs the job straight away.');

      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(screen.getByTestId('run-now-text-tide_refresh')).toHaveTextContent(
        "Started. It'll appear in Runs in a moment.",
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('explains Run now for a cycle job as starting a cycle, with tonight\'s still running', async () => {
    vi.useFakeTimers();
    try {
      fetchSchedulerJobs.mockResolvedValue([job()]);
      render(<SchedulerView />);
      await pump();
      fireEvent.click(screen.getByTestId(`scheduler-row-${NIGHTLY}`));
      await pump();
      expect(screen.getByTestId(`run-now-text-${NIGHTLY}`)).toHaveTextContent(
        "Starts a cycle straight away. Tonight's still runs.",
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('holds the confirmation until TRIGGER_CONFIRM_MS elapses, then restores Run now', async () => {
    vi.useFakeTimers();
    try {
      await openTide();

      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledWith('tide_refresh');
      expect(runNowButton('Started ✓')).toBeDisabled();

      // Just short of the delay it must still be up: without this the constant is pinned only from above, and
      // shortening it would flash the confirmation past with every other assertion here still green.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1999);
      });
      expect(runNowButton('Started ✓')).toBeDisabled();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1);
      });
      expect(runNowButton('Run now')).toBeEnabled();
      expect(
        within(screen.getByTestId('scheduler-panel')).queryByRole('button', { name: 'Started ✓' }),
      ).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ⚠️ The arm-after-await hole. `ManageView` renders this screen behind `activeTab === 'scheduler'`, so switching tab
   * while `triggerJob` is in flight unmounts it before any dismiss timer exists. The old handler armed its timer AFTER
   * the await, so an unmount cleanup had nothing to cancel and the continuation then armed a timer nothing owned: in
   * jsdom its callback can fire after teardown, an unhandled error vitest fails the run on while every test reports
   * passing. A test that unmounts after the call settles cannot see this.
   *
   * The poll interval is cleared by the unmount, so after the call settles nothing at all may be pending on the clock.
   */
  it('arms no timer when the trigger call settles after the screen unmounts', async () => {
    vi.useFakeTimers();
    try {
      const release = deferTrigger();
      const { unmount } = await openTide();

      fireEvent.click(runNowButton('Run now'));
      // The call must genuinely be in flight, or the unmount below proves nothing.
      expect(triggerJob).toHaveBeenCalledTimes(1);

      unmount();
      release();
      await pump();

      expect(vi.getTimerCount(), OUTLIVED).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * The ordinary path out: the confirmation is showing and the reader changes tab. The dismiss timer is pending at that
   * moment, and the unmount must cancel it rather than leave it to fire against a torn-down tree.
   */
  it('cancels the pending dismiss timer when the screen unmounts mid-confirmation', async () => {
    vi.useFakeTimers();
    try {
      const { unmount } = await openTide();

      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(runNowButton('Started ✓')).toBeDisabled();
      // The dismiss timer must actually be pending, beside the poll interval, or the zero below would also pass for a
      // component that never armed one.
      expect(vi.getTimerCount()).toBeGreaterThan(1);

      unmount();
      expect(vi.getTimerCount(), OUTLIVED).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ⚠️ One click, one run. The button used to disable itself only once the call RESOLVED, so the second press of a
   * double-click landed on an enabled button and sent a second `POST .../trigger`, and the backend's `triggerNow`
   * queues an immediate run per call, so a briefing or a tide refresh ran twice. It is now disabled from the click
   * until the call settles, still reading "Run now" (the confirmation must not claim a run before the server has
   * accepted it).
   *
   * The `toBeDisabled()` before the second press is what carries this test. A browser never dispatches the user's click
   * to a disabled button; jsdom does, and it is React's own filter on a disabled button's `onClick` that drops it here.
   * It stays to pin the count a double-click must produce. `act` flushes every lane, so this cannot tell an ordinary
   * `setPending` from one moved into a transition; the component's doc comment carries that rule.
   */
  it('sends no second trigger call when Run now is pressed again while the first is in flight', async () => {
    vi.useFakeTimers();
    try {
      const release = deferTrigger();
      await openTide();

      fireEvent.click(runNowButton('Run now'));
      expect(triggerJob).toHaveBeenCalledTimes(1);
      expect(runNowButton('Run now')).toBeDisabled();

      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledTimes(1);
      // Still in flight: nothing has resolved it, so nothing may have confirmed it.
      expect(runNowButton('Run now')).toBeDisabled();

      release();
      await pump();
      expect(runNowButton('Started ✓')).toBeDisabled();
      expect(triggerJob).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * The in-flight guard must not become a one-shot. The error test below pins that a failed call leaves the button
   * ENABLED; this pins that pressing it then actually sends. A guard that latched per mount passes every other test
   * here while leaving the error asking for a retry the button silently swallows.
   */
  it('sends the retry after a failed trigger call', async () => {
    vi.useFakeTimers();
    try {
      triggerJob.mockRejectedValueOnce(new Error('Network error'));
      await openTide();

      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(screen.getByText('Failed to trigger tide_refresh')).toBeInTheDocument();

      // The retry resolves (the default mock).
      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledTimes(2);
      expect(runNowButton('Started ✓')).toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports a failed trigger in the panel, never as a confirmation', async () => {
    triggerJob.mockRejectedValue(new Error('Network error'));
    mount();
    await openPanel('tide_refresh');
    fireEvent.click(within(screen.getByTestId('scheduler-panel')).getByRole('button', { name: 'Run now' }));

    expect(await screen.findByText('Failed to trigger tide_refresh')).toBeInTheDocument();
    expect(within(screen.getByTestId('scheduler-panel')).getByRole('button', { name: 'Run now' })).toBeEnabled();
  });

  /**
   * The confirmation lives in the open panel's own state, so it is still PER JOB: opening another job's panel shows
   * its Run now at rest, and the first job's confirmation does not leak across.
   */
  it('confirms only the job that was triggered', async () => {
    vi.useFakeTimers();
    try {
      await openTide();
      fireEvent.click(runNowButton('Run now'));
      await pump();
      expect(runNowButton('Started ✓')).toBeDisabled();

      fireEvent.click(screen.getByTestId('scheduler-row-aurora_polling'));
      await pump();
      expect(runNowButton('Run now')).toBeEnabled();
      expect(
        within(screen.getByTestId('scheduler-panel')).queryByRole('button', { name: 'Started ✓' }),
      ).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});
