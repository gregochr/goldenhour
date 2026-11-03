import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within, cleanup } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { setRewind } from '../utils/rewind.js';
import { NIGHTLY, catalogue, jobDetail, solarJob, summaryFor, tideJob, pollingJob } from './schedulerFixtures.js';
import { dayData, dayFire, dayJob, dayRepeatingJob } from './schedulerDayFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerDay: vi.fn(),
  fetchSchedulerJobs: vi.fn(),
  fetchSchedulerSummary: vi.fn(),
  fetchJobDetail: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
  previewSchedule: vi.fn(),
}));
vi.mock('../api/skyRatingEvalApi', () => ({
  getSkyRatingEvalSchedule: vi.fn(),
  setSkyRatingEvalScheduled: vi.fn(),
}));
vi.mock('../api/modelsApi', () => ({ getAvailableModels: vi.fn() }));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import {
  fetchSchedulerDay, fetchSchedulerJobs, fetchSchedulerSummary, fetchJobDetail, fetchSolarPreview,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { MIN_PLOT_WIDTH } from '../components/scheduler/day/DayPicture.jsx';

// The Scheduler's Day's jobs tab end to end below SchedulerView (scheduler-redesign-plan.md S5a). The network is mocked
// at the API module; "now" is pinned to noon on Tue 3 Nov 2026 (a GMT day) wherever a test needs a clock.

const NOON = '2026-11-03T12:00:00Z';
const SOLAR = solarJob({ catalogue: catalogue({ alias: 'The main one', usesClaude: true }) });
const TIDE = tideJob();
const POLLING = pollingJob();
const JOBS = [SOLAR, TIDE, POLLING];

/** A served day for another date: 24 hours of GMT unless the axis is given. */
const otherDay = (date, overrides = {}) => dayData({
  date,
  isToday: false,
  dayStart: `${date}T00:00:00Z`,
  dayEnd: `${new Date(Date.parse(`${date}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 19)}Z`,
  sunrise: `${date}T07:05:00Z`,
  sunset: `${date}T16:28:00Z`,
  ...overrides,
});

function mount({ jobs = JOBS, day = dayData(), hidden = false } = {}) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  fetchSchedulerSummary.mockResolvedValue(summaryFor(jobs));
  fetchSchedulerDay.mockResolvedValue(day);
  return render(hidden ? <div hidden><SchedulerView /></div> : <SchedulerView />);
}

const clickPrev = () => fireEvent.click(screen.getByTestId('scheduler-day-prev'));

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler?view=day';
  try { window.localStorage.removeItem('photocast.scheduler.view'); } catch { /* none */ }
  window.scrollTo = vi.fn();
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
  fetchSolarPreview.mockRejectedValue(new Error('preview not under test here'));
  fetchJobDetail.mockResolvedValue(jobDetail());
});

afterEach(() => {
  vi.useRealTimers();
  setRewind(null);
  cleanup();
});

describe('the Day\'s jobs tab: the default and the address', () => {
  it('is the tab a bare address opens, with nothing remembered', async () => {
    window.location.hash = '#manage/scheduler';
    mount();
    expect(await screen.findByTestId('scheduler-day')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Day's jobs/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('scheduler-all-jobs')).not.toBeInTheDocument();
  });

  it('lets the hash win over a remembered tab, and mounts only the active tab', async () => {
    window.localStorage.setItem('photocast.scheduler.view', 'day');
    window.location.hash = '#manage/scheduler?view=all';
    mount();
    expect(await screen.findByTestId('scheduler-all-jobs')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day')).not.toBeInTheDocument();
    expect(fetchSchedulerDay).not.toHaveBeenCalled();
  });

  it('opens an old #manage/scheduler/{jobKey} link with the panel over the Day\'s jobs tab', async () => {
    window.location.hash = '#manage/scheduler/tide_refresh';
    mount({ day: dayData({ jobs: [dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh' })] }) });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-day')).toBeInTheDocument();
  });

  it('shows the viewed day beside the tab: Today, then the chosen day', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-day-picture')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-tab-day-word')).toHaveTextContent('Today');
    fetchSchedulerDay.mockResolvedValue(otherDay('2026-10-30'));
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-30';
    expect(await screen.findByText('Fri 30 Oct', { selector: '[data-testid="scheduler-tab-day-word"]' })).toBeInTheDocument();
  });
});

describe('the Day picture', () => {
  it('draws a row per scheduled job with a mark and a status dot per fire', async () => {
    mount({
      day: dayData({
        jobs: [
          dayJob({ fires: [dayFire({ at: '2026-11-03T01:00:00Z' }), dayFire({ at: '2026-11-03T13:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })] }),
          dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [dayFire({ at: '2026-11-03T02:00:00Z' })] }),
          dayRepeatingJob(),
        ],
      }),
    });
    expect(await screen.findByTestId(`scheduler-row-${NIGHTLY}`)).toHaveTextContent('Nightly batch evaluation');
    expect(screen.getByTestId('scheduler-row-tide_refresh')).toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`)).toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-1`)).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-dot-tide_refresh-0')).toBeInTheDocument();
    // Repeating jobs are not drawn as rows in S5a.
    expect(screen.queryByTestId('scheduler-row-aurora_polling')).not.toBeInTheDocument();
  });

  it('draws every status in its colour: ok, partial, failed, running, skipped, and hollow for missed and to run', async () => {
    const statuses = ['SUCCEEDED', 'PARTIAL', 'FAILED', 'RUNNING', 'SKIPPED', 'MISSED', 'SCHEDULED'];
    mount({
      day: dayData({
        jobs: [dayJob({
          fires: statuses.map((status, i) => dayFire({ at: `2026-11-03T0${i + 1}:00:00Z`, status, completedAt: null })),
        })],
      }),
    });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    const dot = (i) => screen.getByTestId(`scheduler-dot-${NIGHTLY}-${i}`);
    expect(dot(0)).toHaveClass('fill-plex-ok', 'stroke-plex-ok');
    expect(dot(1)).toHaveClass('fill-verdict-marginal');
    expect(dot(2)).toHaveClass('fill-plex-coral');
    expect(dot(3)).toHaveClass('fill-tide');
    expect(dot(4)).toHaveClass('fill-plex-text-faint');
    expect(dot(5)).toHaveClass('fill-transparent', 'stroke-plex-coral');
    expect(dot(6)).toHaveClass('fill-transparent', 'stroke-plex-text-muted');
    statuses.forEach((status, i) => {
      expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-${i}`)).toHaveAttribute('data-status', status);
    });
  });

  it('draws a short fire as a diamond, a long one as a bar, and a fire crowded by the next as a tick', async () => {
    mount({
      day: dayData({
        jobs: [
          dayJob({ fires: [dayFire()] }),
          dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [dayFire({ at: '2026-11-03T02:00:00Z', startedAt: '2026-11-03T02:00:00Z', completedAt: '2026-11-03T03:30:00Z' })] }),
          dayJob({ jobKey: 'aurora_polling', displayName: 'Crowded', fires: [dayFire({ at: '2026-11-03T04:00:00Z' }), dayFire({ at: '2026-11-03T04:00:30Z' })] }),
        ],
      }),
    });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`)).toHaveAttribute('data-shape', 'diamond');
    expect(screen.getByTestId('scheduler-fire-tide_refresh-0')).toHaveAttribute('data-shape', 'bar');
    expect(screen.getByTestId('scheduler-fire-aurora_polling-0')).toHaveAttribute('data-shape', 'tick');
  });

  it('draws future fires at 30% and past ones in full', async () => {
    mount({
      day: dayData({
        jobs: [dayJob({ fires: [dayFire(), dayFire({ at: '2026-11-03T13:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })] })],
      }),
    });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`)).toHaveAttribute('data-future', 'false');
    expect(screen.getByTestId(`scheduler-mark-${NIGHTLY}-0`)).toHaveAttribute('opacity', '1');
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-1`)).toHaveAttribute('data-future', 'true');
    expect(screen.getByTestId(`scheduler-mark-${NIGHTLY}-1`)).toHaveAttribute('opacity', '0.3');
  });

  it('colours a sun-relative row with the sun token and a cron row with tide', async () => {
    mount({
      day: dayData({ jobs: [dayJob(), dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh' })] }),
    });
    await screen.findByTestId(`scheduler-mark-${NIGHTLY}-0`);
    expect(screen.getByTestId(`scheduler-mark-${NIGHTLY}-0`)).toHaveClass('fill-scheduler-sun');
    expect(screen.getByTestId('scheduler-mark-tide_refresh-0')).toHaveClass('fill-tide');
  });

  it('draws a paused job with a dashed outline and no fill, and not a future dot', async () => {
    mount({
      jobs: [solarJob({ status: 'PAUSED' }), TIDE],
      day: dayData({ jobs: [dayJob({ fires: [dayFire(), dayFire({ at: '2026-11-03T13:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })] })] }),
    });
    await screen.findByTestId(`scheduler-mark-${NIGHTLY}-0`);
    const mark = screen.getByTestId(`scheduler-mark-${NIGHTLY}-0`);
    expect(mark).toHaveAttribute('stroke-dasharray', '2 2');
    expect(mark).toHaveClass('fill-none');
    expect(screen.getByTestId(`scheduler-dot-${NIGHTLY}-0`)).toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-dot-${NIGHTLY}-1`)).not.toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveAccessibleName(/paused/);
  });

  it('keeps a paused job\'s row even when it has no fires that day', async () => {
    mount({ jobs: [solarJob({ status: 'PAUSED' }), TIDE], day: dayData({ jobs: [dayJob({ fires: [] })] }) });
    expect(await screen.findByTestId(`scheduler-row-${NIGHTLY}`)).toHaveAccessibleName('Nightly batch evaluation, no fires, paused');
  });

  it('puts a gold £ after a Claude job\'s name and only there', async () => {
    mount({
      day: dayData({ jobs: [dayJob(), dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh' })] }),
    });
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(within(row).getByTestId(`scheduler-pound-${NIGHTLY}`)).toHaveTextContent('£');
    expect(within(row).getByTestId(`scheduler-pound-${NIGHTLY}`)).toHaveClass('text-skyeval-version');
    expect(screen.queryByTestId('scheduler-pound-tide_refresh')).not.toBeInTheDocument();
  });

  it('marks the marks decorative and gives each row label a name, its last status, its time and how long it took', async () => {
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(row).toHaveAttribute('role', 'button');
    expect(row).toHaveAttribute('tabindex', '0');
    expect(row).toHaveAccessibleName('Nightly batch evaluation, last run ok at 01:00, took 55s, uses Claude');
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`)).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('the sun and Now lines', () => {
  it('draws Sunrise and Sunset lines labelled in UK time, and Now on today', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-day-sunrise')).toHaveTextContent('Sunrise 07:03');
    expect(screen.getByTestId('scheduler-day-sunset')).toHaveTextContent('Sunset 16:30');
    expect(screen.getByTestId('scheduler-day-now')).toHaveTextContent('Now 12:00');
  });

  it('shades the night either side of the day', async () => {
    mount();
    await screen.findByTestId('scheduler-day-sunrise');
    expect(screen.getAllByTestId('scheduler-day-night')).toHaveLength(2);
  });

  it('draws no Now line on another day', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    mount({ day: otherDay('2026-11-02') });
    expect(await screen.findByTestId('scheduler-day-sunrise')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-now')).not.toBeInTheDocument();
  });

  it('leaves out a sun line whose event does not occur', async () => {
    mount({ day: dayData({ sunrise: null }) });
    expect(await screen.findByTestId('scheduler-day-sunset')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-sunrise')).not.toBeInTheDocument();
    expect(screen.getAllByTestId('scheduler-day-night')).toHaveLength(1);
  });

  it('labels the gridlines every three hours in UK time on a clock-change day', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-25';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-27T12:00:00Z'));
    mount({
      day: otherDay('2026-10-25', { dayStart: '2026-10-24T23:00:00Z', dayEnd: '2026-10-26T00:00:00Z', now: '2026-10-27T12:00:00Z', zoneLabelAtStart: 'BST', zoneLabelAtEnd: 'GMT' }),
    });
    expect(await screen.findByTestId('scheduler-day-grid-03:00')).toBeInTheDocument();
    ['00:00', '06:00', '12:00', '18:00', '24:00'].forEach((label) => {
      expect(screen.getByTestId(`scheduler-day-grid-${label}`)).toBeInTheDocument();
    });
    expect(screen.getByTestId('scheduler-day-zone')).toHaveTextContent('UK time (BST to GMT)');
  });
});

describe('opening a job from the picture', () => {
  it('opens the panel on Enter and on Space, and returns focus to the row when it closes', async () => {
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    row.focus();
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
    expect(window.location.hash).toContain(`#manage/scheduler/${NIGHTLY}`);
    fireEvent.click(screen.getByTestId('scheduler-panel-close'));
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveFocus());

    const again = screen.getByTestId(`scheduler-row-${NIGHTLY}`);
    fireEvent.keyDown(again, { key: ' ' });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('opens the panel when a row label or a mark is clicked', async () => {
    mount();
    fireEvent.click(await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`));
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('ignores other keys on a row label', async () => {
    mount();
    fireEvent.keyDown(await screen.findByTestId(`scheduler-row-${NIGHTLY}`), { key: 'a' });
    expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument();
  });
});

describe('the day navigation', () => {
  it('goes back and forward a day, writing ?date= with replaceState and asking for that day', async () => {
    mount();
    await screen.findByTestId('scheduler-day-picture');
    const before = window.history.length;
    fetchSchedulerDay.mockResolvedValue(otherDay('2026-11-02'));
    clickPrev();
    await waitFor(() => expect(fetchSchedulerDay).toHaveBeenLastCalledWith('2026-11-02'));
    expect(window.location.hash).toBe('#manage/scheduler?view=day&date=2026-11-02');
    expect(window.history.length).toBe(before);
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('YesterdayMon 2 Nov');

    fetchSchedulerDay.mockResolvedValue(dayData());
    fireEvent.click(screen.getByTestId('scheduler-day-next'));
    await waitFor(() => expect(window.location.hash).toBe('#manage/scheduler?view=day'));
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('TodayTue 3 Nov');
  });

  it('returns to today with the Today button, which is disabled on today', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-30';
    mount({ day: otherDay('2026-10-30') });
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-today')).toBeEnabled();
    fetchSchedulerDay.mockResolvedValue(dayData());
    fireEvent.click(screen.getByTestId('scheduler-day-today'));
    await waitFor(() => expect(window.location.hash).toBe('#manage/scheduler?view=day'));
    expect(await screen.findByTestId('scheduler-day-now')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-day-today')).toBeDisabled();
  });

  it('stops at seven days back and seven days ahead of the served today', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-27';
    mount({ day: otherDay('2026-10-27') });
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-prev')).toBeDisabled();
    expect(screen.getByTestId('scheduler-day-next')).toBeEnabled();
    cleanup();

    window.location.hash = '#manage/scheduler?view=day&date=2026-11-10';
    mount({ day: otherDay('2026-11-10') });
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-next')).toBeDisabled();
    expect(screen.getByTestId('scheduler-day-prev')).toBeEnabled();
  });

  it('replaces an out-of-range, invalid or today date in the address with today', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-12-25';
    mount();
    await screen.findByTestId('scheduler-day-now');
    await waitFor(() => expect(window.location.hash).toBe('#manage/scheduler?view=day'));
    expect(fetchSchedulerDay).toHaveBeenLastCalledWith(null);
    cleanup();

    window.location.hash = '#manage/scheduler?view=day&date=2026-02-31';
    mount();
    await screen.findByTestId('scheduler-day-now');
    expect(window.location.hash).toBe('#manage/scheduler?view=day');
    cleanup();

    window.location.hash = '#manage/scheduler?view=day&date=2026-11-03';
    mount();
    await screen.findByTestId('scheduler-day-now');
    await waitFor(() => expect(window.location.hash).toBe('#manage/scheduler?view=day'));
  });

  it('falls back to today when the server refuses the date (400)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-05';
    fetchSchedulerDay.mockRejectedValueOnce({ response: { status: 400 } });
    mount();
    expect(await screen.findByTestId('scheduler-day-now')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/scheduler?view=day');
  });

  it('follows Back and Forward: a changed ?date= in the address moves the day', async () => {
    mount();
    await screen.findByTestId('scheduler-day-picture');
    fetchSchedulerDay.mockResolvedValue(otherDay('2026-11-01'));
    act(() => { window.location.hash = '#manage/scheduler?view=day&date=2026-11-01'; });
    await waitFor(() => expect(fetchSchedulerDay).toHaveBeenLastCalledWith('2026-11-01'));
  });
});

describe('the summary bar and the link into Runs', () => {
  it('counts the scheduled fires by outcome and tallies the repeating jobs\' failures', async () => {
    mount({
      day: dayData({
        jobs: [
          dayJob({ fires: [dayFire(), dayFire({ at: '2026-11-03T02:00:00Z' }), dayFire({ at: '2026-11-03T03:00:00Z', status: 'PARTIAL' }), dayFire({ at: '2026-11-03T13:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })] }),
          dayRepeatingJob(),
        ],
        repeatingTally: { failedRuns: 8, jobsWithFailures: 5 },
      }),
    });
    expect(await screen.findByTestId('scheduler-day-count-succeeded')).toHaveTextContent('2 succeeded');
    expect(screen.getByTestId('scheduler-day-count-partial')).toHaveTextContent('1 partial');
    expect(screen.getByTestId('scheduler-day-count-to-run')).toHaveTextContent('1 still to run');
    expect(screen.queryByTestId('scheduler-day-count-failed')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-day-repeating-tally')).toHaveTextContent('Repeating jobs: 8 failed runs across 5 jobs');
  });

  it('says "no failures" for a clean repeating day, and uses the singular for one', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-day-repeating-tally')).toHaveTextContent('Repeating jobs: no failures');
    cleanup();
    mount({ day: dayData({ repeatingTally: { failedRuns: 1, jobsWithFailures: 1 } }) });
    expect(await screen.findByTestId('scheduler-day-repeating-tally')).toHaveTextContent('1 failed run across 1 job');
  });

  it('links today into the Runs Day view as "today"', async () => {
    mount();
    const link = await screen.findByTestId('scheduler-day-runs-link');
    expect(link).toHaveTextContent('Open today in Runs →');
    expect(link).toHaveAttribute('href', '#manage/runs?view=day&date=2026-11-03');
  });

  it('words the link "yesterday" and by date for older days', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    mount({ day: otherDay('2026-11-02') });
    expect(await screen.findByTestId('scheduler-day-runs-link')).toHaveTextContent('Open yesterday in Runs →');
    cleanup();
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-30';
    mount({ day: otherDay('2026-10-30') });
    const link = await screen.findByTestId('scheduler-day-runs-link');
    expect(link).toHaveTextContent('Open Fri 30 Oct in Runs →');
    expect(link).toHaveAttribute('href', '#manage/runs?view=day&date=2026-10-30');
  });

  it('hides the link for a future day', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-04';
    mount({ day: otherDay('2026-11-04') });
    expect(await screen.findByTestId('scheduler-day-summary')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-runs-link')).not.toBeInTheDocument();
  });

  it('draws the legend with the sun, cron, paused, £ and status entries (and not the S5b marks)', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-day-legend')).toBeInTheDocument();
    ['sun', 'cron', 'paused', 'claude', 'ok', 'partial', 'failed', 'missed', 'to-run'].forEach((key) => {
      expect(screen.getByTestId(`scheduler-legend-${key}`)).toBeInTheDocument();
    });
    expect(screen.queryByTestId('scheduler-legend-repeating')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-legend-watchdog')).not.toBeInTheDocument();
  });
});

describe('Needs a look', () => {
  const problems = (n) => Array.from({ length: n }, (_, i) => ({
    status: 'FAILED', at: `2026-11-03T${String(i + 1).padStart(2, '0')}:30:00Z`, summary: `timed out ${i + 1}`,
  }));

  it('lists the failed, partial and missed scheduled fires in time order with their reasons', async () => {
    mount({
      day: dayData({
        jobs: [dayJob({
          fires: [
            dayFire({ at: '2026-11-03T03:00:00Z', status: 'PARTIAL', summary: '1 batch expired' }),
            dayFire({ at: '2026-11-03T05:00:00Z' }),
            dayFire({ at: '2026-11-03T09:00:00Z', status: 'MISSED', summary: null }),
          ],
        })],
      }),
    });
    expect(await screen.findByTestId('scheduler-look')).toHaveTextContent('Needs a look · 2');
    expect(screen.getByTestId('scheduler-look-time-0')).toHaveTextContent('03:00');
    expect(screen.getByTestId('scheduler-look-0')).toHaveAttribute('data-status', 'PARTIAL');
    expect(screen.getByTestId('scheduler-look-summary-0')).toHaveTextContent('1 batch expired');
    expect(screen.getByTestId('scheduler-look-1')).toHaveAttribute('data-status', 'MISSED');
    expect(screen.queryByTestId('scheduler-look-2')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-look-more')).not.toBeInTheDocument();
  });

  it('draws nothing for a clean day', async () => {
    mount();
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.queryByTestId('scheduler-look')).not.toBeInTheDocument();
  });

  it('includes the repeating jobs\' problems, shows eight, and says "and n more" with the server\'s omitted ones', async () => {
    mount({
      day: dayData({ jobs: [dayJob(), dayRepeatingJob({ problems: problems(9), problemsOmitted: 41 })] }),
    });
    expect(await screen.findByTestId('scheduler-look')).toHaveTextContent('Needs a look · 50');
    expect(screen.getAllByTestId(/^scheduler-look-\d+$/)).toHaveLength(8);
    expect(screen.getByTestId('scheduler-look-open-0')).toHaveTextContent('Aurora Polling');
    expect(screen.getByTestId('scheduler-look-more')).toHaveTextContent('and 42 more');
  });

  it('says "and 2 more" for ten problems', async () => {
    mount({ day: dayData({ jobs: [dayRepeatingJob({ problems: problems(10) })] }) });
    expect(await screen.findByTestId('scheduler-look-more')).toHaveTextContent('and 2 more');
  });

  it('opens a job\'s panel from its row by keyboard, repeating jobs included', async () => {
    mount({ day: dayData({ jobs: [dayRepeatingJob({ jobKey: 'aurora_polling', problems: problems(1) })] }) });
    const open = await screen.findByTestId('scheduler-look-open-0');
    open.focus();
    expect(open).toHaveFocus();
    fireEvent.click(open);
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
    expect(window.location.hash).toContain('#manage/scheduler/aurora_polling');
  });

  it('offers "Run detail →" only for a job whose runs Runs records, linking to that day', async () => {
    mount({
      day: dayData({
        jobs: [
          dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [dayFire({ status: 'FAILED', summary: 'x' })] }),
          dayRepeatingJob({ problems: problems(1) }),
        ],
      }),
    });
    await screen.findByTestId('scheduler-look');
    const rows = screen.getAllByTestId(/^scheduler-look-\d+$/);
    const tide = rows.find((row) => row.dataset.jobKey === 'tide_refresh');
    const polling = rows.find((row) => row.dataset.jobKey === 'aurora_polling');
    expect(within(tide).getByText('Run detail →')).toHaveAttribute('href', '#manage/runs?view=day&date=2026-11-03');
    expect(within(polling).queryByText('Run detail →')).not.toBeInTheDocument();
  });
});

describe('Not on this day', () => {
  it('names the jobs that do not fire, each with its next fire, and opens a job\'s panel', async () => {
    mount({
      day: dayData({
        notOnThisDay: [
          { jobKey: 'aurora_polling', displayName: 'Aurora Polling', nextFireAt: '2026-11-09T02:00:00Z' },
          { jobKey: 'tide_refresh', displayName: 'Tide Refresh', nextFireAt: null },
        ],
      }),
    });
    const line = await screen.findByTestId('scheduler-not-on-day');
    expect(line).toHaveTextContent('Not on today: Aurora Polling (next Mon 9 Nov 02:00), Tide Refresh (no upcoming fire).');
    fireEvent.click(within(screen.getByTestId('scheduler-not-on-day-aurora_polling')).getByRole('button'));
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('is not drawn when every job fires', async () => {
    mount();
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.queryByTestId('scheduler-not-on-day')).not.toBeInTheDocument();
  });

  it('names the day by its date on an older day', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOON));
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-30';
    mount({
      day: otherDay('2026-10-30', { notOnThisDay: [{ jobKey: 'sky_rating_eval', displayName: 'Sky Rating Eval', nextFireAt: '2026-11-09T02:00:00Z' }] }),
    });
    expect(await screen.findByTestId('scheduler-not-on-day')).toHaveTextContent('Not on Fri 30 Oct:');
  });
});

describe('the clock (D13)', () => {
  /** Fakes Date and the interval timers only, so waitFor and the promises keep running. */
  function pin(iso) {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(iso));
  }
  const advance = (ms) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

  it('polls the day every 30 seconds while the day is today', async () => {
    pin(NOON);
    mount();
    await screen.findByTestId('scheduler-day-now');
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(1);
    expect(fetchSchedulerDay).toHaveBeenLastCalledWith(null);
    await advance(30_000);
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(2);
    await advance(30_000);
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(3);
  });

  it('does not poll a day that is not today', async () => {
    pin(NOON);
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    mount({ day: otherDay('2026-11-02') });
    await screen.findByTestId('scheduler-day-picture');
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(1);
    await advance(120_000);
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(1);
  });

  it('skips a poll tick while the Operations pane is hidden, after the first load', async () => {
    pin(NOON);
    mount({ hidden: true });
    await waitFor(() => expect(fetchSchedulerDay).toHaveBeenCalledTimes(1));
    await screen.findByTestId('scheduler-day-picture');
    await advance(90_000);
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(1);
  });

  it('advances the Now line between polls from the served time', async () => {
    pin(NOON);
    mount();
    expect(await screen.findByTestId('scheduler-day-now')).toHaveTextContent('Now 12:00');
    fetchSchedulerDay.mockReturnValue(new Promise(() => {})); // the next poll never answers
    await advance(30_000);
    await advance(30_000);
    expect(screen.getByTestId('scheduler-day-now')).toHaveTextContent('Now 12:01');
  });

  it('takes "today" from the served clock, not this machine\'s', async () => {
    pin('2026-11-03T12:00:00Z');
    // The server says it is the 4th already (this machine is a day slow): the tab follows the server.
    mount({
      day: dayData({
        date: '2026-11-04', now: '2026-11-04T12:00:00Z', dayStart: '2026-11-04T00:00:00Z', dayEnd: '2026-11-05T00:00:00Z',
        sunrise: '2026-11-04T07:05:00Z', sunset: '2026-11-04T16:28:00Z',
      }),
    });
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('TodayWed 4 Nov');
    expect(screen.getByTestId('scheduler-day-now')).toHaveTextContent('Now 12:00');
  });

  it('rolls over at midnight for a reader following today, asking for the new day', async () => {
    pin('2026-11-03T23:59:00Z');
    mount({ day: dayData({ now: '2026-11-03T23:59:00Z' }) });
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('TodayTue 3 Nov');
    fetchSchedulerDay.mockResolvedValue(dayData({
      date: '2026-11-04', now: '2026-11-04T00:00:30Z', dayStart: '2026-11-04T00:00:00Z', dayEnd: '2026-11-05T00:00:00Z',
      sunrise: '2026-11-04T07:05:00Z', sunset: '2026-11-04T16:28:00Z',
    }));
    await advance(60_000);
    await waitFor(() => expect(screen.getByTestId('scheduler-day-label')).toHaveTextContent('TodayWed 4 Nov'));
    expect(screen.getByTestId('scheduler-day-now')).toHaveTextContent('Now 00:00');
    expect(window.location.hash).toBe('#manage/scheduler?view=day');
  });

  it('does not move a reader who chose another day when midnight passes', async () => {
    pin('2026-11-03T23:59:00Z');
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-01';
    mount({ day: otherDay('2026-11-01') });
    await screen.findByTestId('scheduler-day-label');
    await advance(120_000);
    expect(screen.getByTestId('scheduler-day-label')).toHaveTextContent('Sun 1 Nov');
    expect(fetchSchedulerDay).toHaveBeenCalledTimes(1);
  });

  it('is not moved by Rewind: Today, the Now line and the request stay on the real clock', async () => {
    pin(NOON);
    setRewind('2026-01-15T09:00:00Z');
    mount();
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('TodayTue 3 Nov');
    expect(screen.getByTestId('scheduler-day-now')).toHaveTextContent('Now 12:00');
    expect(screen.getByTestId('scheduler-day-prev')).toBeEnabled();
    expect(screen.getByTestId('scheduler-day-today')).toBeDisabled();
    expect(fetchSchedulerDay).toHaveBeenCalledWith(null);
    // A date in the address is judged against the real today, not the rewound one.
    cleanup();
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    mount({ day: otherDay('2026-11-02') });
    expect(await screen.findByTestId('scheduler-day-label')).toHaveTextContent('YesterdayMon 2 Nov');
  });
});

describe('the states', () => {
  it('says it is loading until the day arrives', async () => {
    fetchSchedulerJobs.mockResolvedValue(JOBS);
    fetchSchedulerSummary.mockResolvedValue(summaryFor(JOBS));
    let resolve;
    fetchSchedulerDay.mockReturnValue(new Promise((r) => { resolve = r; }));
    render(<SchedulerView />);
    expect(await screen.findByTestId('scheduler-day-loading')).toHaveTextContent('Loading the day');
    expect(screen.queryByTestId('scheduler-day-picture')).not.toBeInTheDocument();
    await act(async () => { resolve(dayData()); });
    expect(await screen.findByTestId('scheduler-day-picture')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-loading')).not.toBeInTheDocument();
  });

  it('says so when no scheduled job fires that day', async () => {
    mount({ day: dayData({ jobs: [] }) });
    expect(await screen.findByTestId('scheduler-day-empty')).toHaveTextContent('No jobs are scheduled on this day.');
    expect(screen.queryByTestId('scheduler-day-picture')).not.toBeInTheDocument();
  });

  it('shows an error when the day cannot be read, and recovers on the next read', async () => {
    fetchSchedulerJobs.mockResolvedValue(JOBS);
    fetchSchedulerSummary.mockResolvedValue(summaryFor(JOBS));
    fetchSchedulerDay.mockRejectedValue(new Error('down'));
    render(<SchedulerView />);
    expect(await screen.findByTestId('scheduler-day-error')).toHaveTextContent('Failed to load the day');
    expect(screen.queryByTestId('scheduler-day-loading')).not.toBeInTheDocument();
    // The next read (the 30 second poll, here the previous-day button) succeeds and the banner goes.
    fetchSchedulerDay.mockResolvedValue(otherDay('2026-11-02'));
    clickPrev();
    expect(await screen.findByTestId('scheduler-day-picture')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-error')).not.toBeInTheDocument();
  });
});

describe('the picture\'s width', () => {
  let observed;
  const RealObserver = global.ResizeObserver;

  beforeEach(() => {
    observed = [];
    global.ResizeObserver = class {
      constructor(callback) { this.callback = callback; observed.push(this); }
      observe() {}
      unobserve() {}
      disconnect() {}
    };
  });

  afterEach(() => {
    global.ResizeObserver = RealObserver;
  });

  it('is never narrower than the minimum, whatever the container measures', async () => {
    mount();
    const svg = await screen.findByTestId('scheduler-day-svg');
    expect(Number(svg.getAttribute('width'))).toBe(MIN_PLOT_WIDTH);
    act(() => { observed.forEach((o) => o.callback([{ contentRect: { width: 100 } }])); });
    expect(Number(screen.getByTestId('scheduler-day-svg').getAttribute('width'))).toBe(MIN_PLOT_WIDTH);
  });

  it('follows the container when it is wider', async () => {
    mount();
    await screen.findByTestId('scheduler-day-svg');
    act(() => { observed.forEach((o) => o.callback([{ contentRect: { width: 912.6 } }])); });
    expect(Number(screen.getByTestId('scheduler-day-svg').getAttribute('width'))).toBe(912);
    // The Now line at noon is the middle of the axis.
    expect(Number(screen.getByTestId('scheduler-day-now').querySelector('line').getAttribute('x1'))).toBe(456);
  });
});

describe('on a phone, and on the desktop it must leave alone', () => {
  it('puts the picture in a horizontal scroller about 720px wide with a sticky label column', async () => {
    useIsMobile.mockReturnValue(true);
    mount();
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-scroller')).toHaveClass('overflow-x-auto');
    expect(screen.getByTestId('scheduler-day-picture')).toHaveClass('min-w-[720px]');
    expect(screen.getByTestId('scheduler-day-labels')).toHaveClass('sticky', 'left-0');
    // The controls and legend wrap, and the nav buttons are 44px.
    expect(screen.getByTestId('scheduler-day-nav')).toHaveClass('flex-wrap');
    expect(screen.getByTestId('scheduler-day-legend')).toHaveClass('flex-wrap');
    expect(screen.getByTestId('scheduler-day-prev').className).toContain('max-[639px]:size-11');
    // The rows still open the panel.
    fireEvent.keyDown(screen.getByTestId(`scheduler-row-${NIGHTLY}`), { key: 'Enter' });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('draws no scroller minimum and no sticky column on a desktop', async () => {
    useIsMobile.mockReturnValue(false);
    mount();
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-picture')).not.toHaveClass('min-w-[720px]');
    expect(screen.getByTestId('scheduler-day-labels')).not.toHaveClass('sticky');
    expect(screen.getByTestId('scheduler-day-labels')).toHaveClass('w-[200px]');
    expect(screen.getByTestId('scheduler-day-scroller')).not.toHaveClass('rounded-lg');
  });
});
