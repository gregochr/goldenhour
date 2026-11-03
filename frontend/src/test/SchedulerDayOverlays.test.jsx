import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { setRewind } from '../utils/rewind.js';
import { EVENING, INTRADAY, NIGHTLY, catalogue, jobDetail, solarIntradayJob, solarJob, summaryFor, tideJob, pollingJob } from './schedulerFixtures.js';
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

// The Day picture's overlays end to end below SchedulerView (scheduler-redesign-plan.md S5b): the Repeating section,
// paused would-be fires, watchdog windows, dependency arrows, tooltips and the close-up. The network is mocked at the
// API module; "now" is the served noon on Tue 3 Nov 2026 (a GMT day). The plot is 360px wide (jsdom measures none),
// so x = minutes / 4.

const W = MIN_PLOT_WIDTH;
const xAt = (hours) => (hours / 24) * W;
const ZOOM_KEY = 'photocast.scheduler.dayZoom';

const SOLAR = solarJob({ catalogue: catalogue({ alias: 'The main one', usesClaude: true, resultsCollectedBy: ['aurora_polling'] }) });
const TIDE = tideJob({ catalogue: catalogue({ alias: 'The tide fetcher', staggeredBefore: [INTRADAY] }) });
const INTRADAY_JOB = solarIntradayJob({ catalogue: catalogue({ alias: 'The afternoon refresh', staggeredAfter: ['tide_refresh'] }) });
const POLLING = pollingJob({ catalogue: catalogue({ alias: 'The weather watcher', collectsResultsFrom: [NIGHTLY] }) });
const JOBS = [SOLAR, TIDE, INTRADAY_JOB, POLLING];

const fire = (at, overrides = {}) => dayFire({
  at, startedAt: at, completedAt: new Date(Date.parse(at) + 55_000).toISOString().replace('.000Z', 'Z'), ...overrides,
});

const POLL_ENTRY = dayRepeatingJob({
  ticks: null,
  problems: [
    { status: 'FAILED', at: '2026-11-03T03:00:00Z', summary: 'Anthropic API timed out' },
    { status: 'PARTIAL', at: '2026-11-03T04:00:00Z', summary: 'Completed with warnings' },
  ],
  schedule: { scheduleType: 'FIXED_DELAY', cronExpression: null, delayMs: 60_000, timeZone: null },
});

const DAY = dayData({
  jobs: [
    dayJob({ fires: [fire('2026-11-03T03:00:00Z', { summary: '3 of 3 batches submitted' })] }),
    dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [fire('2026-11-03T02:00:00Z')] }),
    dayJob({ jobKey: INTRADAY, displayName: 'Intraday refresh', fires: [fire('2026-11-03T02:30:00Z')] }),
    POLL_ENTRY,
  ],
});

function mount({ jobs = JOBS, day = DAY } = {}) {
  fetchSchedulerJobs.mockResolvedValue(jobs);
  fetchSchedulerSummary.mockResolvedValue(summaryFor(jobs));
  fetchSchedulerDay.mockResolvedValue(day);
  return render(<SchedulerView />);
}

const arrows = () => screen.queryAllByTestId(/^scheduler-arrow-/);
const hover = (testId) => fireEvent.mouseEnter(screen.getByTestId(testId));

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler?view=day';
  try {
    window.localStorage.removeItem('photocast.scheduler.view');
    window.localStorage.removeItem(ZOOM_KEY);
  } catch { /* none */ }
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

describe('the Repeating section', () => {
  it('has a divider under the scheduled rows and one row per repeating job, each a button that opens the panel', async () => {
    mount();
    expect(await screen.findByTestId('scheduler-day-repeating-divider')).toHaveTextContent('REPEATING THROUGH THE DAY');
    const row = screen.getByTestId('scheduler-row-aurora_polling');
    expect(row).toHaveAttribute('role', 'button');
    expect(row).toHaveAccessibleName('Aurora Polling, every 1 min, 2 problems, The weather watcher');
    fireEvent.keyDown(row, { key: 'Enter' });
    expect(await screen.findByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('draws the served ticks as ticks, the past in full and the future faint, when they are 3px or more apart', async () => {
    const hourly = dayRepeatingJob({
      jobKey: 'hourly_poll',
      displayName: 'Hourly Poll',
      ticks: Array.from({ length: 24 }, (_, h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
      schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
    });
    mount({ day: dayData({ jobs: [dayJob(), hourly] }) });
    expect(await screen.findByTestId('scheduler-ticks-hourly_poll')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-day-row-hourly_poll')).toHaveAttribute('data-layout', 'ticks');
    expect(screen.getAllByTestId(/^scheduler-tick-hourly_poll-/)).toHaveLength(24);
    // Noon is Now: 00:00 to 12:00 are past, 13:00 to 23:00 are still to come.
    expect(screen.getByTestId('scheduler-tick-hourly_poll-12')).toHaveAttribute('data-future', 'false');
    expect(screen.getByTestId('scheduler-tick-hourly_poll-13')).toHaveAttribute('data-future', 'true');
    expect(screen.getByTestId('scheduler-tick-hourly_poll-13')).toHaveAttribute('fill-opacity', '0.35');
    expect(screen.getByTestId('scheduler-tick-hourly_poll-5')).not.toHaveAttribute('fill-opacity');
    expect(screen.getByTestId('scheduler-tick-hourly_poll-5')).toHaveClass('fill-scheduler-repeating');
    expect(Number(screen.getByTestId('scheduler-tick-hourly_poll-6').getAttribute('x'))).toBe(xAt(6) - 1);
    expect(screen.queryByTestId('scheduler-band-hourly_poll')).not.toBeInTheDocument();
  });

  it('draws one band at about 45% ink, across the whole axis, with "every 1 min", when the server listed no ticks', async () => {
    mount();
    const band = await screen.findByTestId('scheduler-band-aurora_polling');
    expect(screen.getByTestId('scheduler-day-row-aurora_polling')).toHaveAttribute('data-layout', 'band');
    expect(screen.getByTestId('scheduler-band-label-aurora_polling')).toHaveTextContent('every 1 min');
    const rect = within(band).getByTestId('scheduler-band-rect-aurora_polling');
    expect(rect).toHaveAttribute('width', String(W));
    expect(rect).toHaveAttribute('fill-opacity', '0.9');
    expect(rect).toHaveClass('fill-scheduler-repeating');
    expect(screen.queryByTestId('scheduler-ticks-aurora_polling')).not.toBeInTheDocument();
  });

  it('draws a band, not ticks, when served ticks would be closer than 3px, and says "every 5 min" from the schedule', async () => {
    const dense = dayRepeatingJob({
      jobKey: 'dense',
      displayName: 'Dense Poll',
      // Every 10 minutes: 2.5px apart across 360px.
      ticks: Array.from({ length: 144 }, (_, i) => new Date(Date.parse('2026-11-03T00:00:00Z') + i * 600_000).toISOString().replace('.000Z', 'Z')),
      schedule: { scheduleType: 'CRON', cronExpression: '0 */5 * * * *', delayMs: null, timeZone: 'UTC' },
    });
    mount({ day: dayData({ jobs: [dayJob(), dense] }) });
    expect(await screen.findByTestId('scheduler-band-dense')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-band-label-dense')).toHaveTextContent('every 5 min');
    expect(screen.queryByTestId('scheduler-tick-dense-0')).not.toBeInTheDocument();
  });

  it('overlays failed runs in coral and partial in amber at their times, with the reason in the tooltip', async () => {
    mount();
    const failed = await screen.findByTestId('scheduler-problem-aurora_polling-0');
    expect(failed).toHaveClass('fill-plex-coral');
    expect(Number(failed.getAttribute('x'))).toBe(xAt(3) - 1.5);
    const partial = screen.getByTestId('scheduler-problem-aurora_polling-1');
    expect(partial).toHaveClass('fill-verdict-marginal');
    expect(Number(partial.getAttribute('x'))).toBe(xAt(4) - 1.5);

    fireEvent.mouseEnter(failed);
    const tip = screen.getByRole('tooltip');
    expect(within(tip).getByTestId('scheduler-tip-name')).toHaveTextContent('Aurora Polling');
    expect(within(tip).getByTestId('scheduler-tip-status')).toHaveTextContent('Failed · 03:00');
    expect(within(tip).getByTestId('scheduler-tip-note')).toHaveTextContent('Anthropic API timed out');
  });

  it('says "Paused" for a paused repeating job instead of drawing its slots, and keeps its problems', async () => {
    mount({ jobs: [SOLAR, TIDE, INTRADAY_JOB, pollingJob({ status: 'PAUSED' })] });
    expect(await screen.findByTestId('scheduler-paused-aurora_polling')).toHaveTextContent('Paused');
    expect(screen.getByTestId('scheduler-day-row-aurora_polling')).toHaveAttribute('data-layout', 'paused');
    expect(screen.queryByTestId('scheduler-band-aurora_polling')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveAccessibleName(/paused/);
    expect(screen.getByTestId('scheduler-problem-aurora_polling-0')).toBeInTheDocument();
  });

  it('leaves out a repeating job that is off in config', async () => {
    mount({ jobs: [SOLAR, TIDE, INTRADAY_JOB, pollingJob({ status: 'DISABLED_BY_CONFIG' })] });
    expect(await screen.findByTestId(`scheduler-row-${NIGHTLY}`)).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-row-aurora_polling')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-repeating-divider')).not.toBeInTheDocument();
  });

  it('is not an "empty" day when only repeating jobs run', async () => {
    mount({ day: dayData({ jobs: [POLL_ENTRY] }) });
    expect(await screen.findByTestId('scheduler-band-aurora_polling')).toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-empty')).not.toBeInTheDocument();
  });
});

describe('a paused job\'s would-be fires', () => {
  const pausedDay = (extra = {}) => dayData({
    jobs: [dayJob({
      jobKey: 'dormant_account_sweep',
      displayName: 'Dormant Account Sweep',
      fires: [dayFire({ at: '2026-11-03T10:15:00Z', status: 'PAUSED', startedAt: null, completedAt: null, ...extra })],
    })],
  });
  const PAUSED_JOB = tideJob({ jobKey: 'dormant_account_sweep', displayName: 'Dormant Account Sweep', status: 'PAUSED', catalogue: catalogue({ alias: 'The account tidy-up' }) });

  it('draws a dashed outline diamond with its time, no fill, no dot, and not the muted "Paused" text', async () => {
    mount({ jobs: [PAUSED_JOB], day: pausedDay() });
    const group = await screen.findByTestId('scheduler-wouldbe-dormant_account_sweep-0');
    expect(group).toHaveAttribute('data-status', 'PAUSED');
    const mark = screen.getByTestId('scheduler-wouldbe-mark-dormant_account_sweep-0');
    expect(mark).toHaveAttribute('stroke-dasharray', '2 2');
    expect(mark).toHaveClass('fill-none');
    expect(mark.getAttribute('d')).toMatch(new RegExp(`^M${xAt(10.25)} `));
    expect(screen.getByTestId('scheduler-wouldbe-time-dormant_account_sweep-0')).toHaveTextContent('10:15');
    expect(screen.queryByTestId('scheduler-dot-dormant_account_sweep-0')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-fire-dormant_account_sweep-0')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-paused-dormant_account_sweep')).not.toBeInTheDocument();
  });

  it('lists the would-be time in the row\'s accessible name and never counts it as a run or a miss', async () => {
    mount({ jobs: [PAUSED_JOB], day: pausedDay() });
    expect(await screen.findByTestId('scheduler-row-dormant_account_sweep'))
      .toHaveAccessibleName('Dormant Account Sweep, paused, would-be fire at 10:15, The account tidy-up');
    ['succeeded', 'missed', 'to-run', 'failed'].forEach((count) => {
      expect(screen.queryByTestId(`scheduler-day-count-${count}`)).not.toBeInTheDocument();
    });
    expect(screen.queryByTestId('scheduler-needs-a-look')).not.toBeInTheDocument();
  });

  it('shows its tooltip on hover, saying it would have fired', async () => {
    mount({ jobs: [PAUSED_JOB], day: pausedDay() });
    await screen.findByTestId('scheduler-wouldbe-dormant_account_sweep-0');
    hover('scheduler-wouldbe-dormant_account_sweep-0');
    const tip = screen.getByRole('tooltip');
    expect(tip).toHaveTextContent('Dormant Account Sweep');
    expect(tip).toHaveTextContent('Paused · 10:15');
    expect(tip).toHaveTextContent('Would have fired, but the job is paused.');
    expect(tip).toHaveTextContent('The account tidy-up');
  });

  it('keeps the muted "Paused" text when the day has no would-be fire for it', async () => {
    mount({ jobs: [PAUSED_JOB], day: dayData({ jobs: [dayJob({ jobKey: 'dormant_account_sweep', displayName: 'Dormant Account Sweep', fires: [] })] }) });
    expect(await screen.findByTestId('scheduler-paused-dormant_account_sweep')).toHaveTextContent('Paused');
    expect(screen.queryByTestId('scheduler-wouldbe-dormant_account_sweep-0')).not.toBeInTheDocument();
  });
});

describe('watchdog windows', () => {
  const SOLAR_WINDOW = {
    jobKey: NIGHTLY, event: 'SUNRISE', eventAt: '2026-11-03T07:03:00Z', opensAt: '2026-11-03T06:43:00Z', closesAt: '2026-11-03T08:03:00Z',
    startGraceMinutes: 30, budgetMinutes: null,
  };
  // The watchdog's own deadline is 18:25 (90 minutes from a first batch at 16:55), not the 17:00 fire plus 90 minutes.
  const BUDGET = {
    jobKey: EVENING, event: null, eventAt: null, opensAt: null, closesAt: null, startGraceMinutes: 30, budgetMinutes: 90,
    deadlineAt: '2026-11-03T18:25:00Z',
  };

  it('draws a faint band from opensAt to closesAt with a red deadline line at closesAt, on the cycle\'s row', async () => {
    mount({ day: dayData({ jobs: [dayJob({ fires: [fire('2026-11-03T03:00:00Z')] })], watchdog: [SOLAR_WINDOW] }) });
    const band = await screen.findByTestId(`scheduler-watchdog-${NIGHTLY}`);
    expect(Number(band.getAttribute('x'))).toBeCloseTo(xAt(6 + 43 / 60), 5);
    expect(Number(band.getAttribute('width'))).toBeCloseTo(xAt(8 + 3 / 60) - xAt(6 + 43 / 60), 5);
    expect(band).toHaveAttribute('data-budget-only', 'false');
    const deadline = screen.getByTestId(`scheduler-watchdog-deadline-${NIGHTLY}`);
    expect(Number(deadline.getAttribute('x'))).toBeCloseTo(xAt(8 + 3 / 60) - 1, 5);
    expect(deadline).toHaveClass('fill-plex-coral');
    expect(screen.getByTestId(`scheduler-day-row-${NIGHTLY}`)).toContainElement(band);
  });

  it('reads "Watchdog window HH:MM-HH:MM" and the event in the tooltip, from the band and from the deadline line', async () => {
    mount({ day: dayData({ jobs: [dayJob()], watchdog: [SOLAR_WINDOW] }) });
    await screen.findByTestId(`scheduler-watchdog-${NIGHTLY}`);
    hover(`scheduler-watchdog-${NIGHTLY}`);
    let tip = screen.getByRole('tooltip');
    expect(within(tip).getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Watchdog window 06:43–08:03');
    expect(within(tip).getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Sunrise');
    fireEvent.mouseLeave(screen.getByTestId(`scheduler-watchdog-${NIGHTLY}`));
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    hover(`scheduler-watchdog-deadline-${NIGHTLY}`);
    tip = screen.getByRole('tooltip');
    expect(tip).toHaveTextContent('Watchdog window 06:43–08:03');
  });

  it('puts the watchdog text in the tooltip of the cycle\'s own fires', async () => {
    mount({ day: dayData({ jobs: [dayJob()], watchdog: [SOLAR_WINDOW] }) });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Watchdog window 06:43–08:03');
  });

  const eveningDay = (fires, entry = BUDGET) => dayData({
    jobs: [dayJob({ jobKey: EVENING, displayName: 'Evening refresh', fires })],
    watchdog: [entry],
  });

  it('draws the evening refresh\'s deadline at the served deadlineAt, not at its fire plus the budget', async () => {
    mount({ day: eveningDay([fire('2026-11-03T17:00:00Z')]) });
    const band = await screen.findByTestId(`scheduler-watchdog-${EVENING}`);
    expect(band).toHaveAttribute('data-budget-only', 'true');
    expect(Number(band.getAttribute('x'))).toBeCloseTo(xAt(17), 5);
    // 18:25, not 18:30.
    expect(Number(screen.getByTestId(`scheduler-watchdog-deadline-${EVENING}`).getAttribute('x'))).toBeCloseTo(xAt(18 + 25 / 60) - 1, 5);
    hover(`scheduler-watchdog-deadline-${EVENING}`);
    expect(screen.getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Watchdog window 17:00–18:25');
    expect(screen.getByTestId('scheduler-tip-watchdog')).toHaveTextContent('90 min budget from the first batch');
  });

  it('draws nothing for the evening refresh when no deadlineAt was served (no run yet)', async () => {
    mount({ day: eveningDay([fire('2026-11-03T17:00:00Z', { status: 'SCHEDULED', startedAt: null, completedAt: null })], { ...BUDGET, deadlineAt: null }) });
    await screen.findByTestId(`scheduler-row-${EVENING}`);
    expect(screen.queryByTestId(`scheduler-watchdog-${EVENING}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-watchdog-deadline-${EVENING}`)).not.toBeInTheDocument();
  });

  it('draws no window on a SKIPPED, MISSED or FAILED-without-a-run fire', async () => {
    const cases = [{ status: 'SKIPPED' }, { status: 'MISSED', startedAt: null, completedAt: null }, { status: 'FAILED', runLinked: false }];
    for (const overrides of cases) {
      mount({ day: eveningDay([fire('2026-11-03T17:00:00Z', overrides)]) });
      await screen.findByTestId(`scheduler-row-${EVENING}`);
      expect(screen.queryByTestId(`scheduler-watchdog-${EVENING}`)).not.toBeInTheDocument();
      cleanup();
    }
  });

  it('draws the window on a FAILED fire that has a run', async () => {
    mount({ day: eveningDay([fire('2026-11-03T17:00:00Z', { status: 'FAILED', runLinked: true })]) });
    expect(await screen.findByTestId(`scheduler-watchdog-${EVENING}`)).toBeInTheDocument();
  });

  it('puts the watchdog text only on the fire the window belongs to, whole day and close-up alike', async () => {
    const day = dayData({
      jobs: [dayJob({ fires: [fire('2026-11-03T03:00:00Z'), fire('2026-11-03T09:00:00Z')] })],
      watchdog: [SOLAR_WINDOW],
    });
    mount({ day });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    // The 03:00 fire is the one just before the 06:43 to 08:03 window; the 09:00 fire is after it.
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Watchdog window 06:43–08:03');
    fireEvent.mouseLeave(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`));
    hover(`scheduler-fire-${NIGHTLY}-1`);
    expect(screen.queryByTestId('scheduler-tip-watchdog')).not.toBeInTheDocument();
    fireEvent.mouseLeave(screen.getByTestId(`scheduler-fire-${NIGHTLY}-1`));

    fireEvent.click(screen.getByTestId('scheduler-day-zoom-closeup'));
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId('scheduler-tip-watchdog')).toHaveTextContent('Watchdog window 06:43–08:03');
  });

  it('draws none for a cycle with no row, or when the event does not occur that day', async () => {
    mount({
      day: dayData({
        jobs: [dayJob()],
        watchdog: [{ ...SOLAR_WINDOW, jobKey: 'ghost' }, { ...SOLAR_WINDOW, jobKey: INTRADAY, opensAt: null, closesAt: null, eventAt: null }],
      }),
    });
    await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(screen.queryByTestId('scheduler-watchdog-ghost')).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-watchdog-${INTRADAY}`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-watchdog-${NIGHTLY}`)).not.toBeInTheDocument();
  });
});

describe('dependency arrows', () => {
  it('draws a solid arrow for "results collected by" and a dashed one for the stagger link, and only those', async () => {
    mount();
    const collect = await screen.findByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`);
    expect(collect).toHaveAttribute('data-kind', 'collect');
    expect(collect).not.toHaveAttribute('stroke-dasharray');
    // From the end of the 03:00 fire (55 seconds wide) down to the band's row, landing 10px past it.
    const startX = xAt(3) + (55 / 86_400) * W;
    expect(collect.getAttribute('d')).toMatch(new RegExp(`^M${startX} `));
    expect(collect.getAttribute('d')).toMatch(new RegExp(` ${startX + 10} \\d+(\\.\\d+)?$`));
    const stagger = screen.getByTestId('scheduler-arrow-stagger-tide_refresh-intraday_forecast_refresh-0');
    expect(stagger).toHaveAttribute('data-kind', 'stagger');
    expect(stagger).toHaveAttribute('stroke-dasharray', '3 3');
    expect(arrows()).toHaveLength(2);
  });

  it('invents none: an inverse edge alone (the poller says it collects from the job, the job does not say so) draws nothing', async () => {
    mount({
      jobs: [solarJob({ catalogue: catalogue({ resultsCollectedBy: [] }) }), tideJob({ catalogue: catalogue() }), solarIntradayJob({ catalogue: catalogue() }), POLLING],
    });
    await screen.findByTestId('scheduler-band-aurora_polling');
    expect(arrows()).toHaveLength(0);
  });

  it('draws none for a link to a job that has no row that day, nor from a fire that never ran', async () => {
    mount({
      day: dayData({ jobs: [dayJob({ fires: [fire('2026-11-03T03:00:00Z', { status: 'MISSED', startedAt: null, completedAt: null })] })] }),
    });
    await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(arrows()).toHaveLength(0);
  });

  it('lands a collect arrow on the next tick of a ticked poller, and draws none when no tick follows', async () => {
    const ticked = (hours) => dayRepeatingJob({
      ticks: hours.map((h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
      schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
    });
    const day = (hours) => dayData({ jobs: [dayJob({ fires: [fire('2026-11-03T03:00:00Z')] }), ticked(hours)] });
    const jobs = [SOLAR, POLLING];
    mount({ jobs, day: day([1, 2, 4, 5]) });
    const arrow = await screen.findByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`);
    expect(arrow.getAttribute('d')).toMatch(new RegExp(` ${xAt(4)} \\d+(\\.\\d+)?$`));
    cleanup();
    mount({ jobs, day: day([1, 2]) });
    await screen.findByTestId('scheduler-ticks-aurora_polling');
    expect(arrows()).toHaveLength(0);
  });

  it('draws no arrow to a paused poller', async () => {
    mount({ jobs: [SOLAR, TIDE, INTRADAY_JOB, pollingJob({ status: 'PAUSED', catalogue: POLLING.catalogue })] });
    await screen.findByTestId('scheduler-paused-aurora_polling');
    expect(screen.queryByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`)).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-arrow-stagger-tide_refresh-intraday_forecast_refresh-0')).toBeInTheDocument();
  });

  it('is toggled by the Dependency arrows chip (aria-pressed, on by default)', async () => {
    mount();
    const chip = await screen.findByTestId('scheduler-arrows-chip');
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(chip).toHaveTextContent('Dependency arrows');
    expect(arrows()).toHaveLength(2);
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'false');
    expect(arrows()).toHaveLength(0);
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(arrows()).toHaveLength(2);
  });

  it('turns a selected job\'s arrows gold and dims the rows unrelated to it to 25%', async () => {
    mount();
    await screen.findByTestId('scheduler-arrows-chip');
    // Nothing selected: nothing dimmed, arrows are ink.
    expect(screen.getByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`)).toHaveAttribute('data-selected', 'false');
    expect(screen.getByTestId('scheduler-row-tide_refresh')).toHaveAttribute('data-dimmed', 'false');

    fireEvent.keyDown(screen.getByTestId(`scheduler-row-${NIGHTLY}`), { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    const gold = screen.getByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`);
    expect(gold).toHaveAttribute('data-selected', 'true');
    expect(gold).toHaveClass('stroke-skyeval-version');
    expect(gold).toHaveAttribute('opacity', '1');
    expect(gold.getAttribute('marker-end')).toMatch(/-ahh\)$/);
    // The linked poller and the job itself stay; the unrelated rows dim.
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveAttribute('data-dimmed', 'false');
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveAttribute('data-dimmed', 'false');
    ['tide_refresh', INTRADAY].forEach((key) => {
      expect(screen.getByTestId(`scheduler-row-${key}`)).toHaveAttribute('data-dimmed', 'true');
      expect(screen.getByTestId(`scheduler-row-${key}`)).toHaveClass('opacity-25');
      expect(screen.getByTestId(`scheduler-day-row-${key}`)).toHaveClass('opacity-25');
    });
    // The other link is faint, not gold.
    const other = screen.getByTestId('scheduler-arrow-stagger-tide_refresh-intraday_forecast_refresh-0');
    expect(other).toHaveAttribute('data-selected', 'false');
    expect(other).toHaveAttribute('opacity', '0.12');
    expect(screen.getByTestId(`scheduler-selected-${NIGHTLY}`)).toBeInTheDocument();
  });

  it('dims nothing when the selected job has no row on this day', async () => {
    window.location.hash = '#manage/scheduler/dormant_account_sweep?view=day';
    mount({ jobs: [...JOBS, tideJob({ jobKey: 'dormant_account_sweep', displayName: 'Dormant' })] });
    await screen.findByTestId('scheduler-panel');
    expect(screen.getByTestId('scheduler-row-tide_refresh')).toHaveAttribute('data-dimmed', 'false');
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveAttribute('data-dimmed', 'false');
  });
});

describe('tooltips', () => {
  it('shows name, status, time, duration, the note and the alias on hover of a mark, and hides on leave', async () => {
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    hover(`scheduler-fire-${NIGHTLY}-0`);
    const tip = screen.getByRole('tooltip');
    expect(within(tip).getByTestId('scheduler-tip-name')).toHaveTextContent('Nightly batch evaluation');
    expect(within(tip).getByTestId('scheduler-tip-status')).toHaveTextContent('OK · 03:00 · 55s');
    expect(within(tip).getByTestId('scheduler-tip-note')).toHaveTextContent('3 of 3 batches submitted');
    expect(within(tip).getByTestId('scheduler-tip-alias')).toHaveTextContent('The main one');
    // No "~£ per run": that figure is not in the data the picture has.
    expect(tip).not.toHaveTextContent('~£');
    fireEvent.mouseLeave(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`));
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('is an HTML layer outside the aria-hidden SVG, placed from the computed geometry', async () => {
    mount();
    await screen.findByTestId('scheduler-fire-tide_refresh-0');
    // Tide Refresh is the first row (centre y 48): the box is 12px right of the mark and 14px below the row's centre.
    hover('scheduler-fire-tide_refresh-0');
    const tip = screen.getByRole('tooltip');
    expect(tip.closest('svg')).toBeNull();
    expect(screen.getByTestId('scheduler-day-svg').contains(tip)).toBe(false);
    expect(tip.style.getPropertyValue('--tip-x')).toBe(`${xAt(2) + 12}px`);
    expect(tip.style.getPropertyValue('--tip-y')).toBe('62px');
  });

  it('flips the tooltip above a mark near the bottom of the picture', async () => {
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    // The third row (centre y 96): below it would run off the 180px picture, so it sits above.
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(Number.parseFloat(screen.getByRole('tooltip').style.getPropertyValue('--tip-y'))).toBeLessThan(96);
  });

  it('keeps the tooltip inside the plot near the right edge', async () => {
    mount({ day: dayData({ jobs: [dayJob({ fires: [fire('2026-11-03T23:00:00Z')] })] }) });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover(`scheduler-fire-${NIGHTLY}-0`);
    const x = Number.parseFloat(screen.getByRole('tooltip').style.getPropertyValue('--tip-x'));
    expect(x).toBeLessThanOrEqual(W - 260 - 4);
    expect(x).toBeGreaterThanOrEqual(4);
  });

  it('opens on keyboard focus of a row, which names it with aria-describedby, and closes on blur', async () => {
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    expect(row).not.toHaveAttribute('aria-describedby');
    act(() => { row.focus(); });
    const tip = await screen.findByRole('tooltip');
    expect(row).toHaveAttribute('aria-describedby', tip.id);
    expect(within(tip).getByTestId('scheduler-tip-name')).toHaveTextContent('Nightly batch evaluation');
    expect(within(tip).getByTestId('scheduler-tip-alias')).toHaveTextContent('The main one');
    act(() => { row.blur(); });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(row).not.toHaveAttribute('aria-describedby');
  });

  it('opens a repeating row\'s tooltip on focus: how often it runs and its problems', async () => {
    mount();
    const row = await screen.findByTestId('scheduler-row-aurora_polling');
    act(() => { row.focus(); });
    const tip = await screen.findByRole('tooltip');
    expect(tip).toHaveTextContent('Aurora Polling');
    expect(tip).toHaveTextContent('every 1 min');
    expect(tip).toHaveTextContent('2 problems today');
  });

  it('closes on Escape without closing the open panel; a second Escape closes the panel', async () => {
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    fireEvent.keyDown(row, { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    act(() => { row.focus(); });
    expect(await screen.findByRole('tooltip')).toBeInTheDocument();

    fireEvent.keyDown(row, { key: 'Escape' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();

    fireEvent.keyDown(row, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('scheduler-panel')).not.toBeInTheDocument());
  });

  it('closes a hover tooltip on Escape too, leaving the panel', async () => {
    mount();
    fireEvent.keyDown(await screen.findByTestId(`scheduler-row-${NIGHTLY}`), { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    hover('scheduler-fire-tide_refresh-0');
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
  });
});

describe('arrows: which fires link, and how far', () => {
  const nightDay = (status, extra = {}) => dayData({
    jobs: [
      dayJob({ fires: [fire('2026-11-03T03:00:00Z', { status, ...extra })] }),
      dayRepeatingJob({
        ticks: [1, 2, 3, 4, 5, 15, 16].map((h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
        schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
      }),
    ],
  });
  const collectArrow = () => screen.queryByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`);

  it('links only from fires that ran or will run: SUCCEEDED, PARTIAL, RUNNING, SCHEDULED', async () => {
    for (const status of ['SUCCEEDED', 'PARTIAL', 'RUNNING', 'SCHEDULED']) {
      mount({ jobs: [SOLAR, POLLING], day: nightDay(status) });
      await screen.findByTestId('scheduler-ticks-aurora_polling');
      expect(collectArrow(), status).toBeInTheDocument();
      cleanup();
    }
  });

  it('never links from a SKIPPED or FAILED fire, or a missed one', async () => {
    for (const status of ['SKIPPED', 'FAILED', 'MISSED']) {
      mount({ jobs: [SOLAR, POLLING], day: nightDay(status) });
      await screen.findByTestId('scheduler-ticks-aurora_polling');
      expect(collectArrow(), status).not.toBeInTheDocument();
      cleanup();
    }
  });

  it('drops a collect arrow whose next tick is more than one interval away: no jump from 03:00 to 15:00', async () => {
    // Hourly ticks, but none from 06:00 to 14:00: from the 03:00 fire the next tick is 04:00 (fine); from a fire at
    // 05:30 the next tick would be 15:00, ten hours on.
    const day = dayData({
      jobs: [
        dayJob({ fires: [fire('2026-11-03T03:00:00Z'), fire('2026-11-03T05:30:00Z')] }),
        dayRepeatingJob({
          ticks: [1, 2, 3, 4, 5, 15, 16].map((h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
          schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
        }),
      ],
    });
    mount({ jobs: [SOLAR, POLLING], day });
    await screen.findByTestId('scheduler-ticks-aurora_polling');
    expect(screen.getByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`)).toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-1`)).not.toBeInTheDocument();
  });

  it('allows a fire\'s own duration on top of the interval', async () => {
    // A 90-minute run ending 04:30; the next hourly tick is 05:00, well inside an hour plus the duration.
    const day = dayData({
      jobs: [
        dayJob({ fires: [fire('2026-11-03T03:00:00Z', { completedAt: '2026-11-03T04:30:00Z' })] }),
        dayRepeatingJob({
          ticks: [1, 2, 3, 4, 5].map((h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
          schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
        }),
      ],
    });
    mount({ jobs: [SOLAR, POLLING], day });
    await screen.findByTestId('scheduler-ticks-aurora_polling');
    expect(collectArrow()).toBeInTheDocument();
  });

  it('draws a stagger arrow only when the next fire is about the declared 30 minutes on, and not 2 hours on', async () => {
    const staggerDay = (intradayAt) => dayData({
      jobs: [
        dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [fire('2026-11-03T02:00:00Z')] }),
        dayJob({ jobKey: INTRADAY, displayName: 'Intraday refresh', fires: [fire(intradayAt)] }),
      ],
    });
    const stagger = () => screen.queryByTestId('scheduler-arrow-stagger-tide_refresh-intraday_forecast_refresh-0');
    mount({ day: staggerDay('2026-11-03T02:40:00Z') });
    await screen.findByTestId('scheduler-fire-tide_refresh-0');
    expect(stagger()).toBeInTheDocument();
    cleanup();
    mount({ day: staggerDay('2026-11-03T04:00:00Z') });
    await screen.findByTestId('scheduler-fire-tide_refresh-0');
    expect(stagger()).not.toBeInTheDocument();
  });
});

describe('tooltips: their life', () => {
  it('closes when the zoom changes', async () => {
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('scheduler-day-zoom-closeup'));
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('closes when the selected job changes', async () => {
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover('scheduler-fire-tide_refresh-0');
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId(`scheduler-row-${NIGHTLY}`), { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('closes when a click on a mark opens the panel, even for the job already selected', async () => {
    mount();
    fireEvent.keyDown(await screen.findByTestId(`scheduler-row-${NIGHTLY}`), { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByRole('tooltip')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`));
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('closes when a poll replaces the day, rather than showing words from the data that has gone', async () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date('2026-11-03T12:00:00Z'));
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByRole('tooltip')).toHaveTextContent('3 of 3 batches submitted');

    fetchSchedulerDay.mockResolvedValue(dayData({
      jobs: [dayJob({ fires: [fire('2026-11-03T03:00:00Z', { summary: 'A different outcome' })] })],
    }));
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
    // A fresh hover reads the current data.
    hover(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByRole('tooltip')).toHaveTextContent('A different outcome');
    expect(screen.getByRole('tooltip')).not.toHaveTextContent('3 of 3 batches submitted');
  });

  it('leaves Escape to the cron editor while a hover tooltip is open: the edit is cancelled, the panel stays', async () => {
    const rawCron = tideJob({ cronExpression: '0 20 * * * *', catalogue: catalogue({ alias: 'The tide fetcher' }) });
    mount({ jobs: [SOLAR, rawCron, INTRADAY_JOB, POLLING] });
    fireEvent.keyDown(await screen.findByTestId('scheduler-row-tide_refresh'), { key: 'Enter' });
    await screen.findByTestId('scheduler-panel');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    const input = screen.getByTestId('edit-input-tide_refresh');
    hover('scheduler-fire-tide_refresh-0');
    expect(screen.getByRole('tooltip')).toBeInTheDocument();

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('stands aside for Escape typed in a text field, and for an open dialog', async () => {
    mount();
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    hover(`scheduler-fire-${NIGHTLY}-0`);
    const field = document.createElement('input');
    field.type = 'text';
    document.body.appendChild(field);
    try {
      const notHandled = fireEvent.keyDown(field, { key: 'Escape' });
      expect(notHandled).toBe(true);
      expect(screen.getByRole('tooltip')).toBeInTheDocument();
    } finally {
      field.remove();
    }
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.appendChild(dialog);
    try {
      expect(fireEvent.keyDown(document.body, { key: 'Escape' })).toBe(true);
      expect(screen.getByRole('tooltip')).toBeInTheDocument();
    } finally {
      dialog.remove();
    }
    // With neither, Escape closes the tooltip and takes the key.
    expect(fireEvent.keyDown(document.body, { key: 'Escape' })).toBe(false);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('says "today" in a repeating row\'s tooltip only on today, else "on this day"', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-11-02';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-03T12:00:00Z'));
    mount({
      day: {
        ...DAY, date: '2026-11-02', isToday: false, dayStart: '2026-11-02T00:00:00Z', dayEnd: '2026-11-03T00:00:00Z', sunrise: '2026-11-02T07:05:00Z', sunset: '2026-11-02T16:28:00Z',
      },
    });
    const row = await screen.findByTestId('scheduler-row-aurora_polling');
    act(() => { row.focus(); });
    expect(await screen.findByRole('tooltip')).toHaveTextContent('2 problems on this day');
  });

  it('anchors a row label\'s tooltip to the visible part of the phone scroller, beside the sticky label column', async () => {
    useIsMobile.mockReturnValue(true);
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    screen.getByTestId('scheduler-day-scroller').scrollLeft = 120;
    act(() => { row.focus(); });
    const tip = await screen.findByRole('tooltip');
    // 120 scrolled, 8 in, 12 beside the anchor.
    expect(tip.style.getPropertyValue('--tip-x')).toBe('140px');
    expect(tip.className).toContain('max-[639px]:max-w-[200px]');
  });

  it('anchors it at the plot\'s left edge when nothing is scrolled', async () => {
    useIsMobile.mockReturnValue(true);
    mount();
    const row = await screen.findByTestId(`scheduler-row-${NIGHTLY}`);
    act(() => { row.focus(); });
    expect((await screen.findByRole('tooltip')).style.getPropertyValue('--tip-x')).toBe('20px');
  });
});

describe('the close-up', () => {
  const closeUp = () => fireEvent.click(screen.getByTestId('scheduler-day-zoom-closeup'));
  const wide = (overrides = {}) => dayData({
    sunrise: '2026-11-03T07:03:00Z',
    jobs: [
      dayJob({ fires: [fire('2026-11-03T03:00:00Z'), fire('2026-11-03T13:00:00Z', { status: 'SCHEDULED', startedAt: null, completedAt: null })] }),
      dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh', fires: [fire('2026-11-03T02:00:00Z')] }),
      dayJob({ jobKey: INTRADAY, displayName: 'Intraday refresh', fires: [fire('2026-11-03T14:00:00Z')] }),
      POLL_ENTRY,
    ],
    ...overrides,
  });

  it('offers Whole day and 02:00-05:00 close-up as a segmented control, the whole day chosen by default', async () => {
    mount({ day: wide() });
    const group = await screen.findByTestId('scheduler-day-zoom');
    expect(group).toHaveAttribute('role', 'group');
    expect(screen.getByTestId('scheduler-day-zoom-day')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('scheduler-day-zoom-closeup')).toHaveTextContent('02:00–05:00 close-up');
    expect(screen.getByTestId('scheduler-day-zoom-closeup')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('scheduler-day-picture')).toHaveAttribute('data-zoom', 'day');
  });

  it('puts 02:00 to 05:00 on the axis with a line every 30 minutes, and draws only the fires inside it', async () => {
    mount({ day: wide() });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    closeUp();
    expect(screen.getByTestId('scheduler-day-picture')).toHaveAttribute('data-zoom', 'closeup');
    ['02:00', '02:30', '03:00', '03:30', '04:00', '04:30', '05:00'].forEach((label) => {
      expect(screen.getByTestId(`scheduler-day-grid-${label}`)).toBeInTheDocument();
    });
    expect(screen.queryByTestId('scheduler-day-grid-24:00')).not.toBeInTheDocument();
    // 03:00 is a third of the way along; the 13:00 fire is not drawn at all.
    expect(screen.getByTestId(`scheduler-mark-${NIGHTLY}-0`)).toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-0`).querySelector('circle')).not.toBeNull();
    expect(screen.queryByTestId(`scheduler-fire-${NIGHTLY}-1`)).not.toBeInTheDocument();
    // A job with nothing in the window loses its row; the repeating jobs keep theirs.
    expect(screen.queryByTestId(`scheduler-row-${INTRADAY}`)).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-row-tide_refresh')).toBeInTheDocument();
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toBeInTheDocument();
  });

  it('clips the night shading, the sun lines, the Now line and the arrows to the window', async () => {
    mount({ day: wide() });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    expect(screen.getByTestId('scheduler-day-now')).toBeInTheDocument();
    closeUp();
    // Sunrise 07:03 and sunset 16:30 and Now (12:00) are outside 02:00-05:00.
    expect(screen.queryByTestId('scheduler-day-sunrise')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-sunset')).not.toBeInTheDocument();
    expect(screen.queryByTestId('scheduler-day-now')).not.toBeInTheDocument();
    // Night is the whole window (sunrise is after it) and no wider.
    const night = screen.getAllByTestId('scheduler-day-night');
    expect(night).toHaveLength(1);
    expect(Number(night[0].getAttribute('x'))).toBe(0);
    expect(Number(night[0].getAttribute('width'))).toBe(W);
    // The content is clipped to the plot, and the arrow from the 03:00 fire is still there, inside it.
    expect(screen.getByTestId('scheduler-day-content').getAttribute('clip-path')).toMatch(/^url\(#.+-clip\)$/);
    expect(screen.getByTestId(`scheduler-arrow-collect-${NIGHTLY}-aurora_polling-0`)).toBeInTheDocument();
  });

  it('widens the bars: a 30-minute fire is wider on the 3-hour close-up than on the 24-hour day, and starts a third in', async () => {
    const longFire = fire('2026-11-03T03:00:00Z', { completedAt: '2026-11-03T03:30:00Z' });
    mount({ day: wide({ jobs: [dayJob({ fires: [longFire] })] }) });
    const whole = await screen.findByTestId(`scheduler-mark-${NIGHTLY}-0`);
    expect(whole.tagName).toBe('rect');
    const wholeWidth = Number(whole.getAttribute('width'));
    expect(wholeWidth).toBeCloseTo((30 / 1440) * W, 5);
    closeUp();
    const zoomed = screen.getByTestId(`scheduler-mark-${NIGHTLY}-0`);
    expect(zoomed.tagName).toBe('rect');
    expect(Number(zoomed.getAttribute('x'))).toBeCloseTo(W / 3, 5);
    expect(Number(zoomed.getAttribute('width'))).toBeCloseTo((30 / 180) * W, 5);
    expect(Number(zoomed.getAttribute('width'))).toBeGreaterThan(wholeWidth * 5);
  });

  it('draws a ticked repeating job\'s ticks inside the window only', async () => {
    const hourly = dayRepeatingJob({
      jobKey: 'hourly_poll',
      displayName: 'Hourly Poll',
      ticks: Array.from({ length: 24 }, (_, h) => `2026-11-03T${String(h).padStart(2, '0')}:00:00Z`),
      schedule: { scheduleType: 'CRON', cronExpression: '0 0 * * * *', delayMs: null, timeZone: 'UTC' },
    });
    mount({ day: wide({ jobs: [dayJob(), hourly] }) });
    await screen.findByTestId('scheduler-ticks-hourly_poll');
    closeUp();
    // 02:00, 03:00, 04:00 and 05:00 are on the axis.
    expect(screen.getAllByTestId(/^scheduler-tick-hourly_poll-/)).toHaveLength(4);
    expect(Number(screen.getByTestId('scheduler-tick-hourly_poll-0').getAttribute('x'))).toBe(-1);
  });

  it('remembers the choice in localStorage (not in the address) and restores it on the next visit', async () => {
    mount({ day: wide() });
    await screen.findByTestId('scheduler-day-zoom');
    closeUp();
    expect(window.localStorage.getItem(ZOOM_KEY)).toBe('closeup');
    expect(window.location.hash).not.toContain('closeup');
    expect(window.location.hash).not.toContain('zoom');
    cleanup();

    mount({ day: wide() });
    expect(await screen.findByTestId('scheduler-day-picture')).toHaveAttribute('data-zoom', 'closeup');
    expect(screen.getByTestId('scheduler-day-zoom-closeup')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByTestId('scheduler-day-zoom-day'));
    expect(window.localStorage.getItem(ZOOM_KEY)).toBe('day');
    expect(screen.getByTestId('scheduler-day-picture')).toHaveAttribute('data-zoom', 'day');
    expect(screen.getByTestId(`scheduler-fire-${NIGHTLY}-1`)).toBeInTheDocument();
  });

  it('still works when localStorage throws', async () => {
    const original = Object.getOwnPropertyDescriptor(window, 'localStorage');
    mount({ day: wide() });
    await screen.findByTestId('scheduler-day-zoom');
    Object.defineProperty(window, 'localStorage', { configurable: true, get() { throw new Error('blocked'); } });
    try {
      closeUp();
      expect(screen.getByTestId('scheduler-day-picture')).toHaveAttribute('data-zoom', 'closeup');
    } finally {
      Object.defineProperty(window, 'localStorage', original);
    }
  });

  it('is 02:00 to 05:00 BST on the spring clock-change day (01:00Z to 04:00Z)', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-03-29';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-03-31T12:00:00Z'));
    mount({
      day: dayData({
        date: '2026-03-29',
        isToday: false,
        now: '2026-03-31T12:00:00Z',
        dayStart: '2026-03-29T00:00:00Z',
        dayEnd: '2026-03-29T23:00:00Z',
        zoneLabelAtStart: 'GMT',
        zoneLabelAtEnd: 'BST',
        sunrise: '2026-03-29T05:30:00Z',
        sunset: '2026-03-29T18:00:00Z',
        jobs: [dayJob({
          fires: [
            fire('2026-03-29T00:30:00Z'), // 00:30 GMT: before the window
            fire('2026-03-29T01:30:00Z'), // 02:30 BST: inside, a sixth of the way along
            fire('2026-03-29T04:30:00Z'), // 05:30 BST: after the window
          ],
        })],
      }),
    });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    closeUp();
    expect(screen.getByTestId('scheduler-day-grid-02:00')).toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-fire-${NIGHTLY}-0`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-fire-${NIGHTLY}-2`)).not.toBeInTheDocument();
    const inside = screen.getByTestId(`scheduler-mark-${NIGHTLY}-1`);
    expect(inside.getAttribute('d')).toMatch(new RegExp(`^M${W / 6} `));
    expect(screen.getByTestId(`scheduler-time-${NIGHTLY}-1`)).toHaveTextContent('02:30');
  });

  it('is 02:00 to 05:00 GMT on the autumn clock-change day (02:00Z to 05:00Z)', async () => {
    window.location.hash = '#manage/scheduler?view=day&date=2026-10-25';
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-27T12:00:00Z'));
    mount({
      day: dayData({
        date: '2026-10-25',
        isToday: false,
        now: '2026-10-27T12:00:00Z',
        dayStart: '2026-10-24T23:00:00Z',
        dayEnd: '2026-10-26T00:00:00Z',
        zoneLabelAtStart: 'BST',
        zoneLabelAtEnd: 'GMT',
        sunrise: '2026-10-25T06:30:00Z',
        sunset: '2026-10-25T16:00:00Z',
        jobs: [dayJob({
          fires: [
            fire('2026-10-25T01:30:00Z'), // 01:30 GMT (the second one): before the window
            fire('2026-10-25T02:30:00Z'), // 02:30 GMT: inside
            fire('2026-10-25T05:30:00Z'), // after
          ],
        })],
      }),
    });
    await screen.findByTestId(`scheduler-fire-${NIGHTLY}-0`);
    closeUp();
    expect(screen.queryByTestId(`scheduler-fire-${NIGHTLY}-0`)).not.toBeInTheDocument();
    expect(screen.queryByTestId(`scheduler-fire-${NIGHTLY}-2`)).not.toBeInTheDocument();
    expect(screen.getByTestId(`scheduler-mark-${NIGHTLY}-1`).getAttribute('d')).toMatch(new RegExp(`^M${W / 6} `));
  });
});

describe('on a phone, and on the desktop it must leave alone', () => {
  it('wraps the controls with 44px targets, keeps everything in the one scroller, and opens a tooltip on tap focus', async () => {
    useIsMobile.mockReturnValue(true);
    mount();
    const chip = await screen.findByTestId('scheduler-arrows-chip');
    expect(chip.className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('scheduler-day-legend')).toHaveClass('flex-wrap');
    expect(screen.getByTestId('scheduler-day-zoom-day').className).toContain('max-[639px]:min-h-11');
    expect(screen.getByTestId('scheduler-day-zoom-closeup').className).toContain('max-[639px]:min-h-11');
    // Both sections of rows are 44px tall and live in the one scroller with the sticky label column.
    const scroller = screen.getByTestId('scheduler-day-scroller');
    expect(scroller).toContainElement(screen.getByTestId('scheduler-day-repeating-divider'));
    expect(scroller).toContainElement(screen.getByTestId('scheduler-row-aurora_polling'));
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveClass('h-11');
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveClass('h-11');
    expect(screen.getByTestId('scheduler-day-labels')).toHaveClass('sticky');
    // Row 0's centre sits half a 44px row below the top padding; the first repeating row is a divider lower.
    const sched = [NIGHTLY, 'tide_refresh', INTRADAY];
    const first = screen.getByTestId('scheduler-day-row-aurora_polling').querySelector('rect');
    expect(Number(first.getAttribute('y'))).toBe(36 + sched.length * 44 + 24 + 22 - 12);
    // A tap focuses the label, which opens its tooltip.
    act(() => { screen.getByTestId(`scheduler-row-${NIGHTLY}`).focus(); });
    expect(await screen.findByRole('tooltip')).toBeInTheDocument();
  });

  it('keeps the desktop picture as it was: 24px rows, a 200px label column, no sticky column or minimum width', async () => {
    mount();
    await screen.findByTestId('scheduler-day-picture');
    expect(screen.getByTestId('scheduler-day-picture')).not.toHaveClass('min-w-[720px]');
    expect(screen.getByTestId('scheduler-day-labels')).toHaveClass('w-[200px]');
    expect(screen.getByTestId('scheduler-day-labels')).not.toHaveClass('sticky');
    expect(screen.getByTestId(`scheduler-row-${NIGHTLY}`)).toHaveClass('h-6');
    expect(screen.getByTestId('scheduler-row-aurora_polling')).toHaveClass('h-6');
    expect(screen.getByTestId('scheduler-day-scroller')).not.toHaveClass('rounded-lg');
  });

  it('keeps the scheduled-only picture exactly as tall as before when nothing repeats', async () => {
    mount({ day: dayData({ jobs: [dayJob(), dayJob({ jobKey: 'tide_refresh', displayName: 'Tide Refresh' })] }) });
    const svg = await screen.findByTestId('scheduler-day-svg');
    // TOP_PAD 36 + two 24px rows + BOTTOM_PAD 24, and no divider.
    expect(Number(svg.getAttribute('height'))).toBe(36 + 2 * 24 + 24);
    expect(screen.queryByTestId('scheduler-day-repeating-divider')).not.toBeInTheDocument();
  });
});
