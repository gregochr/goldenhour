import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RunsView from '../components/runs/RunsView.jsx';
import { cycleDetail, jobDetail, runsPage } from './runsFixtures.js';
import {
  calendarDay, calendarResponse, callService, callsResponse, cycleDayRow, dayResponse, dayRow, evalRow,
  pastDayResponse, pastSummary, scheduledRow, todaySummary,
} from './runsDayFixtures.js';

// The Runs Day view (runs-deep-dive-plan.md D2) end to end below ManageView: the served rows, groups and estimates,
// the summary, the date bar and its keys, the calendar disclosure, the strip, the filter chips, the expanded row and
// its external calls, and every state. The network is mocked at the API modules.

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
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null, loading: false }) }));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import {
  fetchRuns, fetchJobRun, fetchRunDay, fetchRunCalendar, fetchRunCalls,
} from '../api/runsApi.js';
import { fetchPipelineRunDetail } from '../api/pipelineRunApi.js';
import { getApiCalls, getBatchSummary, getDispositionBreakdown } from '../api/metricsApi';
import { getRegions } from '../api/batchApi';
import { fetchLocations } from '../api/forecastApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { useIsMobile } from '../hooks/useIsMobile.js';

const noop = () => {};

function renderView(props = {}) {
  return render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} {...props} />);
}

/** Today's rows: three finished, two still to come (one a chained child with an estimated start). */
const TODAY_ROWS = [
  dayRow({ id: 'job-1', jobRunId: 1, name: 'Tide data', startedAt: '2026-11-02T02:00:00Z', durationSeconds: 52, costGbp: 0.01 }),
  cycleDayRow({
    id: 'cycle-568',
    cycleId: 568,
    type: 'OVERNIGHT',
    typeLabel: 'Overnight cycle',
    name: 'Overnight cycle',
    subline: 'Cycle #568 · nightly · 5 batches',
    startedAt: '2026-11-02T02:56:42Z',
    endedAt: '2026-11-02T03:40:03Z',
    durationSeconds: 2601,
    slow: true,
    costGbp: 1.38,
    costUsd: 1.85,
    facts: {
      startedAt: '2026-11-02T02:56:42Z',
      finishedAt: '2026-11-02T03:40:03Z',
      fasterThanPercent: 3,
      batches: 5,
      requests: 216,
      answersReadyAt: '2026-11-02T03:44:00Z',
      expectedStart: null,
      expectedFinish: null,
    },
  }),
  scheduledRow({
    id: 'sched-afternoon-1',
    type: 'AFTERNOON',
    typeLabel: 'Afternoon refresh',
    name: 'Afternoon refresh',
    subline: 'Sun-relative · 4h 10m before sunset (16:49)',
    startedAt: '2026-11-02T12:39:00Z',
    durationSeconds: 930,
    costGbp: 0.7,
    facts: {
      startedAt: null, finishedAt: null, fasterThanPercent: null, batches: null, requests: null, answersReadyAt: null,
      expectedStart: '2026-11-02T12:39:00Z', expectedFinish: '2026-11-02T12:54:30Z',
    },
  }),
  scheduledRow({
    id: 'sched-briefing-2',
    type: 'BRIEFING',
    typeLabel: 'Daily briefing',
    name: 'Daily briefing',
    subline: 'Runs at the end of Afternoon refresh',
    startedAt: '2026-11-02T12:56:00Z',
    estimated: true,
    durationSeconds: 41,
    costGbp: 0.04,
    facts: {
      startedAt: null, finishedAt: null, fasterThanPercent: null, batches: null, requests: null, answersReadyAt: null,
      expectedStart: '2026-11-02T12:56:00Z', expectedFinish: '2026-11-02T12:56:41Z',
    },
  }),
];

/** Serves any date: today as given, every other date as a past day of the same rows. */
function serveDays(todayRows = TODAY_ROWS, pastRows = [dayRow()]) {
  fetchRunDay.mockImplementation((date) => Promise.resolve(
    date ? pastDayResponse(pastRows, { date }) : dayResponse(todayRows),
  ));
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/runs';
  window.localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T09:26:00Z'));
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
  fetchRuns.mockResolvedValue(runsPage([]));
  serveDays();
  fetchRunCalendar.mockResolvedValue(calendarResponse([
    calendarDay('2026-11-01', { spendGbp: 7.43, attention: 2 }),
    calendarDay('2026-11-02', { spendGbp: 3.7 }),
    calendarDay('2026-10-30', { spendGbp: 0 }),
  ]));
  fetchRunCalls.mockResolvedValue(callsResponse());
  fetchJobRun.mockResolvedValue(jobDetail({ id: 1 }));
  fetchPipelineRunDetail.mockResolvedValue(cycleDetail());
  getApiCalls.mockResolvedValue({ data: [] });
  getBatchSummary.mockRejectedValue(new Error('not a batch'));
  getDispositionBreakdown.mockResolvedValue({ data: { jobRunId: 0, totalCount: 0, countsByDisposition: {}, entries: [] } });
  getRegions.mockResolvedValue([]);
  fetchLocations.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

/** Waits for today's rows. */
const loaded = () => screen.findByTestId('runs-day-row-cycle-568');

describe('Day view: today\'s rows', () => {
  it('splits into FINISHED and STILL TO RUN TODAY, in the served (time) order', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-group-finished')).toHaveTextContent(/^Finished\s*·\s*2$/i);
    expect(screen.getByTestId('runs-day-group-upcoming')).toHaveTextContent(/Still to run today\s*·\s*2\s*·\s*times after a cycle are estimates/i);
    const order = within(screen.getByTestId('runs-day-list')).getAllByRole('button', { name: /./ })
      .map((b) => b.dataset.testid).filter((id) => id?.startsWith('runs-day-row-'));
    expect(order).toEqual([
      'runs-day-row-job-1', 'runs-day-row-cycle-568', 'runs-day-row-sched-afternoon-1', 'runs-day-row-sched-briefing-2',
    ]);
  });

  it('shows a cycle with its CYCLE tag, subline, duration, cost and Done', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-cycle-tag-cycle-568')).toHaveTextContent('CYCLE');
    expect(screen.getByTestId('runs-day-name-cycle-568')).toHaveTextContent('Overnight cycle');
    expect(screen.getByTestId('runs-day-subline-cycle-568')).toHaveTextContent('Cycle #568 · nightly · 5 batches');
    expect(screen.getByTestId('runs-day-start-cycle-568')).toHaveTextContent('02:56');
    expect(screen.getByTestId('runs-day-duration-cycle-568')).toHaveTextContent('43m 21s');
    expect(screen.getByTestId('runs-day-cost-cycle-568')).toHaveTextContent('£1.38');
    expect(screen.getByTestId('runs-day-status-cycle-568')).toHaveTextContent('Done');
    expect(screen.queryByTestId('runs-day-cycle-tag-job-1')).toBeNull();
  });

  it('flags a slow run "slow · top 10%" under its duration, and no other', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-slow-cycle-568')).toHaveTextContent('slow · top 10%');
    expect(screen.queryByTestId('runs-day-slow-job-1')).toBeNull();
  });

  it('prefixes a scheduled row\'s duration and cost with ~, and an estimated start too', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-start-sched-afternoon-1')).toHaveTextContent(/^12:39$/);
    expect(screen.getByTestId('runs-day-start-sched-briefing-2')).toHaveTextContent(/^~12:56$/);
    expect(screen.getByTestId('runs-day-duration-sched-afternoon-1')).toHaveTextContent('~15m 30s');
    expect(screen.getByTestId('runs-day-cost-sched-afternoon-1')).toHaveTextContent('~£0.70');
    expect(screen.getByTestId('runs-day-status-sched-afternoon-1')).toHaveTextContent('Scheduled');
  });

  it('draws a scheduled row\'s status as a dashed hollow dot, and a finished one filled', async () => {
    renderView();
    await loaded();
    const hollow = screen.getByTestId('runs-day-status-sched-afternoon-1').firstChild;
    expect(hollow.className).toContain('border-dashed');
    const filled = screen.getByTestId('runs-day-status-job-1').firstChild;
    expect(filled.className).toContain('bg-plex-ok');
  });

  it('colours Partial amber, Failed coral and Running tide; the words always show', async () => {
    serveDays([
      dayRow({ id: 'job-1', jobRunId: 1, status: 'PARTIAL', note: 'Some regions errored', noteTone: 'WARN' }),
      dayRow({ id: 'job-2', jobRunId: 2, status: 'FAILED', note: 'Did not finish', noteTone: 'ERROR' }),
      dayRow({ id: 'job-3', jobRunId: 3, status: 'RUNNING', endedAt: null, durationSeconds: null, startedAt: '2026-11-02T09:00:00Z' }),
    ]);
    renderView();
    expect((await screen.findByTestId('runs-day-status-job-1')).className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-day-status-job-1')).toHaveTextContent('Partial');
    expect(screen.getByTestId('runs-day-status-job-2').className).toContain('text-plex-coral');
    expect(screen.getByTestId('runs-day-status-job-2')).toHaveTextContent('Failed');
    expect(screen.getByTestId('runs-day-status-job-3').className).toContain('text-tide');
    expect(screen.getByTestId('runs-day-note-job-1').className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-day-note-job-2').className).toContain('text-plex-coral');
    expect(screen.queryByTestId('runs-day-note-job-3')).toBeNull();
  });

  it('marks an unknown or accruing cost "so far" and never shows a zero for it', async () => {
    serveDays([dayRow({
      id: 'job-1', jobRunId: 1, status: 'RUNNING', endedAt: null, durationSeconds: null, costMicroDollars: null,
      costComplete: false, costGbp: null, costUsd: null,
    })]);
    renderView();
    const cost = await screen.findByTestId('runs-day-cost-job-1');
    expect(cost).toHaveTextContent('—');
    expect(cost).toHaveTextContent('so far');
  });

  it('shows the compact duration and cost line under the name (it is the only place below 600px)', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-compact-cycle-568')).toHaveTextContent('43m 21s · £1.38');
    expect(screen.getByTestId('runs-day-compact-cycle-568').className).toContain('@min-[600px]:hidden');
  });

  it('lays its columns out with container queries (list width, not viewport)', async () => {
    renderView();
    await loaded();
    const row = screen.getByTestId('runs-day-row-cycle-568');
    expect(row.className).toContain('@min-[600px]:grid-cols-');
    expect(row.className).toContain('@min-[900px]:grid-cols-[62px_minmax(0,1fr)_110px_72px_92px]');
    expect(screen.getByTestId('runs-day-cost-cycle-568').className).toContain('@min-[900px]:block');
    expect(screen.getByTestId('runs-day-list').className).toContain('@container');
  });
});

describe('Day view: a past day', () => {
  beforeEach(() => {
    window.location.hash = '#manage/runs?view=day&date=2026-11-01';
    serveDays(TODAY_ROWS, [
      dayRow({ id: 'job-1', jobRunId: 1, status: 'PARTIAL', note: 'Some regions errored', noteTone: 'WARN' }),
      cycleDayRow(),
    ]);
  });

  it('is one list with no FINISHED or STILL TO RUN headers', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-job-1');
    expect(screen.queryByTestId('runs-day-group-finished')).toBeNull();
    expect(screen.queryByTestId('runs-day-group-upcoming')).toBeNull();
  });

  it('serves the past summary: runs, attention, spend against the average, the slowest cycle', async () => {
    renderView();
    await screen.findByTestId('runs-day-row-job-1');
    expect(screen.getByTestId('runs-summary-runs-value')).toHaveTextContent('14');
    expect(screen.getByTestId('runs-summary-runs-sub')).toHaveTextContent('3 cycles · 11 jobs');
    expect(screen.getByTestId('runs-summary-attention-value')).toHaveTextContent('2');
    expect(screen.getByTestId('runs-summary-attention-value').className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-summary-attention-sub')).toHaveTextContent('1 partial · 1 failed');
    expect(screen.getByTestId('runs-summary-spend-value')).toHaveTextContent('£7.43');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('+71% vs 30-day avg £4.36');
    expect(screen.getByTestId('runs-summary-spend-sub').className).toContain('text-verdict-marginal');
    expect(screen.getByTestId('runs-summary-slowest-value')).toHaveTextContent('28m 41s');
    expect(screen.getByTestId('runs-summary-slowest-sub')).toHaveTextContent('Afternoon refresh · median 15m 30s');
  });

  it('greens a day more than 10% under the average, and says so when there is no average', async () => {
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], { summary: pastSummary({ vsAvgPercent: -22 }) }));
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('-22% vs 30-day avg');
    expect(screen.getByTestId('runs-summary-spend-sub').className).toContain('text-plex-ok');
  });

  it('reads "No 30-day average yet" and "No cycles ran" when the server has neither', async () => {
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], {
      summary: pastSummary({
        vsAvgPercent: null, avg30Gbp: null, slowestCycle: null, needAttention: 0, partial: 0, failed: 0,
      }),
    }));
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('No 30-day average yet');
    expect(screen.getByTestId('runs-summary-slowest-value')).toHaveTextContent('—');
    expect(screen.getByTestId('runs-summary-slowest-sub')).toHaveTextContent('No cycles ran');
    expect(screen.getByTestId('runs-summary-attention-sub')).toHaveTextContent('Nothing needed attention');
    expect(screen.getByTestId('runs-summary-attention-value').className).not.toContain('text-verdict-marginal');
  });

  it('says a spend with an unknown cost in it is not final', async () => {
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], { summary: pastSummary({ spendComplete: false }) }));
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('some costs not final');
  });
});

describe('Day view: today\'s summary', () => {
  it('serves Finished, Need attention, Spend so far with the projection, and Next up', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-summary-finished-value')).toHaveTextContent('5');
    expect(screen.getByTestId('runs-summary-finished-sub')).toHaveTextContent('8 still to run today');
    expect(screen.getByTestId('runs-summary-attention-value')).toHaveTextContent('0');
    expect(screen.getByTestId('runs-summary-attention-sub')).toHaveTextContent('Nothing so far');
    expect(screen.getByTestId('runs-summary-spend-value')).toHaveTextContent('£1.80');
    expect(screen.getByTestId('runs-summary-spend-sub')).toHaveTextContent('~£3.83 by midnight · 30-day avg £4.36');
    expect(screen.getByTestId('runs-summary-next-value')).toHaveTextContent('12:39');
    // Served next-up 12:39Z against the pinned real 09:26Z: 3h 13m.
    expect(screen.getByTestId('runs-summary-next-sub')).toHaveTextContent('Afternoon refresh · in 3h 13m');
  });

  it('names the runs that need attention, amber', async () => {
    fetchRunDay.mockResolvedValue(dayResponse(TODAY_ROWS, {
      summary: todaySummary({ needAttention: 2, attentionNames: ['Tide data', 'Briefing'] }),
    }));
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-summary-attention-sub')).toHaveTextContent('Tide data, Briefing');
    expect(screen.getByTestId('runs-summary-attention-value').className).toContain('text-verdict-marginal');
  });

  it('has an em dash and "Nothing else today" when nothing is left to run, and "~" on an estimated next', async () => {
    fetchRunDay.mockResolvedValue(dayResponse(TODAY_ROWS, { summary: todaySummary({ nextUp: null, stillToRun: 0 }) }));
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-summary-next-value')).toHaveTextContent('—');
    expect(screen.getByTestId('runs-summary-next-sub')).toHaveTextContent('Nothing else today');
    expect(screen.getByTestId('runs-summary-finished-sub')).toHaveTextContent('nothing else to run today');
  });

  it('puts "~" before an estimated next-up time', async () => {
    fetchRunDay.mockResolvedValue(dayResponse(TODAY_ROWS, {
      summary: todaySummary({ nextUp: { name: 'Daily briefing', at: '2026-11-02T12:56:00Z', estimated: true } }),
    }));
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-summary-next-value')).toHaveTextContent('~12:56');
  });

  it('drops to two columns when the list column is 700px or narrower (a container query)', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-summary').className).toContain('@max-[700px]:grid-cols-2');
  });
});

describe('Day view: the date bar', () => {
  it('shows "Today" and the date, with Next and Today disabled and no "ago"', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Today');
    expect(screen.getByTestId('runs-day-date-text')).toHaveTextContent('Mon 2 Nov');
    expect(screen.getByTestId('runs-day-next')).toBeDisabled();
    expect(screen.getByTestId('runs-day-today')).toBeDisabled();
    expect(screen.queryByTestId('runs-day-ago')).toBeNull();
  });

  it('steps back a calendar day, asks for it, and reads "Yesterday" and "1 day ago"', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-11-01');
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Yesterday');
    expect(screen.getByTestId('runs-day-ago')).toHaveTextContent('1 day ago');
    expect(screen.getByTestId('runs-view-tab-day-word')).toHaveTextContent('Yesterday');
    expect(window.location.hash).toBe('#manage/runs?date=2026-11-01');
  });

  it('reads the weekday for an older day, and steps across the 25 October clock change by date', async () => {
    window.location.hash = '#manage/runs?date=2026-10-26';
    serveDays(TODAY_ROWS, [dayRow()]);
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Monday');
    expect(screen.getByTestId('runs-day-date-text')).toHaveTextContent('26 Oct');
    expect(screen.getByTestId('runs-day-ago')).toHaveTextContent('7 days ago');
    // Back over 25 Oct (a 25-hour day) lands on the 25th, then the 24th: dates, never plus or minus 24 hours.
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await waitFor(() => expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-25'));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await waitFor(() => expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-24'));
  });

  it('steps forward onto today as "no date" (follow today), dropping date from the address', async () => {
    window.location.hash = '#manage/runs?date=2026-11-01';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    fireEvent.click(screen.getByTestId('runs-day-next'));
    await loaded();
    expect(fetchRunDay).toHaveBeenLastCalledWith(null);
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('Today returns home from any day', async () => {
    window.location.hash = '#manage/runs?date=2026-10-20';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    fireEvent.click(screen.getByTestId('runs-day-today'));
    await loaded();
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Today');
  });

  it('disables Previous on the earliest day', async () => {
    window.location.hash = '#manage/runs?date=2026-09-20';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-day-prev')).toBeDisabled();
  });

  it('has 44px phone targets and hides the keyboard hint on a phone', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-prev').className).toContain('max-[639px]:size-11');
    expect(screen.getByTestId('runs-day-hint').className).toContain('max-[639px]:hidden');
  });
});

describe('Day view: the keys (F1)', () => {
  const press = (key, init = {}, target = document.body) => fireEvent.keyDown(target, { key, ...init });

  it('Left steps back a day, Right steps forward, T jumps to today', async () => {
    renderView();
    await loaded();
    press('ArrowLeft');
    await screen.findByTestId('runs-day-row-job-9001');
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-11-01');
    press('ArrowLeft');
    await waitFor(() => expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-31'));
    press('ArrowRight');
    await waitFor(() => expect(fetchRunDay).toHaveBeenLastCalledWith('2026-11-01'));
    press('t');
    await loaded();
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Today');
  });

  it('does not step past today or before the earliest day', async () => {
    renderView();
    await loaded();
    const calls = fetchRunDay.mock.calls.length;
    press('ArrowRight');
    press('T');
    expect(fetchRunDay.mock.calls.length).toBe(calls);
  });

  it('does nothing while focus is in an input', async () => {
    renderView();
    await loaded();
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    press('ArrowLeft', {}, field);
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    field.remove();
  });

  it('does nothing when a modifier is held, or the key was already handled', async () => {
    renderView();
    await loaded();
    press('ArrowLeft', { ctrlKey: true });
    press('ArrowLeft', { metaKey: true });
    press('ArrowLeft', { altKey: true });
    const handled = new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true });
    handled.preventDefault();
    document.body.dispatchEvent(handled);
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
  });

  it('does nothing while a visible dialog is open', async () => {
    renderView();
    await loaded();
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    document.body.appendChild(dialog);
    press('ArrowLeft');
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    dialog.remove();
  });

  it('does nothing while a run\'s panel is open', async () => {
    window.location.hash = '#manage/runs/job/1';
    renderView();
    await screen.findByTestId('job-run-panel');
    await loaded();
    press('ArrowLeft');
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
  });

  it('does nothing when the Operations pane is hidden behind the Map (arrows belong to the Map\'s window)', async () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />, { container: host });
    await loaded();
    host.hidden = true;
    // A Map window is open: a real visible dialog outside the host, plus the hidden Runs pane.
    const mapWindow = document.createElement('div');
    mapWindow.setAttribute('role', 'dialog');
    document.body.appendChild(mapWindow);
    press('ArrowLeft');
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    // The hidden pane alone is enough too: with the dialog gone, the arrow still does not move the date.
    mapWindow.remove();
    press('ArrowLeft');
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    host.remove();
  });

  it('is stood down by the view tabs\' own arrows (they handle and prevent them)', async () => {
    renderView();
    await loaded();
    fireEvent.keyDown(screen.getByTestId('runs-view-tab-day'), { key: 'ArrowRight' });
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    expect(await screen.findByTestId('runs-cost-loading')).toBeInTheDocument();
  });
});

describe('Day view: the calendar disclosure (F2)', () => {
  async function openCalendar() {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-date'));
    return screen.findByTestId('runs-calendar');
  }

  it('is a labelled region opened by a button with aria-expanded and aria-controls, never a dialog', async () => {
    const region = await openCalendar();
    const button = screen.getByTestId('runs-day-date');
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(button).toHaveAttribute('aria-controls', region.id);
    expect(region).toHaveAttribute('role', 'region');
    expect(region).toHaveAccessibleName('Choose a day');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.querySelector('[aria-modal="true"]')).toBeNull();
  });

  it('asks for the shown month and shows November 2026 with the date button back at aria-expanded false on close', async () => {
    await openCalendar();
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('November 2026');
    await waitFor(() => expect(fetchRunCalendar).toHaveBeenCalledWith('2026-11'));
    fireEvent.click(screen.getByTestId('runs-day-date'));
    expect(screen.queryByTestId('runs-calendar')).toBeNull();
    expect(screen.getByTestId('runs-day-date')).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows a spend bar and an amber attention dot from the served days, and a tooltip with the figures', async () => {
    await openCalendar();
    const cell = await screen.findByTestId('runs-cal-cell-2026-11-01');
    await waitFor(() => expect(screen.getByTestId('runs-cal-dot-2026-11-01')).toBeInTheDocument());
    expect(cell).toHaveAttribute('title', 'Sun 1 Nov · 14 runs · £7.43 · 2 need attention');
    expect(within(cell).getByTestId('runs-cal-bar')).toHaveAttribute('data-share', '1.000');
    const smaller = screen.getByTestId('runs-cal-cell-2026-11-02');
    expect(within(smaller).getByTestId('runs-cal-bar').dataset.share).toBe((3.7 / 7.43).toFixed(3));
    expect(screen.queryByTestId('runs-cal-dot-2026-11-02')).toBeNull();
  });

  it('marks today, fills the selected day, and disables the days after today', async () => {
    await openCalendar();
    const today = screen.getByTestId('runs-cal-cell-2026-11-02');
    expect(today).toHaveAttribute('aria-current', 'date');
    expect(today).toHaveAttribute('aria-selected', 'true');
    expect(today.className).toContain('bg-plex-text');
    const future = screen.getByTestId('runs-cal-cell-2026-11-03');
    expect(future).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(future);
    expect(screen.getByTestId('runs-calendar')).toBeInTheDocument();
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
  });

  it('picks a day: loads it, closes, and puts focus back on the date button', async () => {
    await openCalendar();
    fireEvent.click(screen.getByTestId('runs-cal-cell-2026-11-01'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-11-01');
    expect(screen.queryByTestId('runs-calendar')).toBeNull();
    expect(screen.getByTestId('runs-day-date')).toHaveFocus();
  });

  it('Jump to today picks today', async () => {
    window.location.hash = '#manage/runs?date=2026-10-30';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    fireEvent.click(screen.getByTestId('runs-day-date'));
    fireEvent.click(await screen.findByTestId('runs-cal-jump-today'));
    await loaded();
    expect(screen.getByTestId('runs-day-word')).toHaveTextContent('Today');
  });

  it('pages months, clamped to the earliest day\'s month and the current month', async () => {
    await openCalendar();
    expect(screen.getByTestId('runs-cal-next')).toBeDisabled();
    fireEvent.click(screen.getByTestId('runs-cal-prev'));
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('October 2026');
    await waitFor(() => expect(fetchRunCalendar).toHaveBeenCalledWith('2026-10'));
    fireEvent.click(screen.getByTestId('runs-cal-prev'));
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('September 2026');
    expect(screen.getByTestId('runs-cal-prev')).toBeDisabled();
  });

  it('closes on Escape first: the open panel stays open', async () => {
    window.location.hash = '#manage/runs/job/1';
    renderView();
    await screen.findByTestId('job-run-panel');
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-date'));
    const grid = await screen.findByTestId('runs-cal-grid');
    grid.focus();
    fireEvent.keyDown(grid, { key: 'Escape' });
    expect(screen.queryByTestId('runs-calendar')).toBeNull();
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
    expect(screen.getByTestId('runs-day-date')).toHaveFocus();
    // A second Escape is the panel's.
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
  });

  it('closes on an outside press, but not on a press inside it or on its button', async () => {
    await openCalendar();
    fireEvent.pointerDown(screen.getByTestId('runs-cal-grid'));
    expect(screen.getByTestId('runs-calendar')).toBeInTheDocument();
    fireEvent.pointerDown(screen.getByTestId('runs-day-date'));
    expect(screen.getByTestId('runs-calendar')).toBeInTheDocument();
    fireEvent.pointerDown(screen.getByTestId('runs-heading'));
    expect(screen.queryByTestId('runs-calendar')).toBeNull();
  });

  it('closes when the Operations pane is hidden', async () => {
    const host = document.createElement('div');
    document.body.appendChild(host);
    render(<RunsView activeRunId={null} onActiveRunChange={noop} onActiveRunClear={noop} />, { container: host });
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-date'));
    await screen.findByTestId('runs-calendar');
    await act(async () => { host.hidden = true; });
    await waitFor(() => expect(screen.queryByTestId('runs-calendar')).toBeNull());
    host.remove();
  });

  it('moves a cursor with the arrow keys, handles them (so the day keys never also fire), and picks with Enter', async () => {
    const user = userEvent.setup({ advanceTimers: () => {} });
    await openCalendar();
    const grid = screen.getByTestId('runs-cal-grid');
    expect(grid).toHaveFocus();
    await user.keyboard('{ArrowLeft}');
    expect(grid).toHaveAttribute('aria-activedescendant', expect.stringContaining('2026-11-01'));
    await user.keyboard('{ArrowUp}');
    expect(grid).toHaveAttribute('aria-activedescendant', expect.stringContaining('2026-10-25'));
    expect(screen.getByTestId('runs-cal-month')).toHaveTextContent('October 2026');
    // The day keys (Left/Right) did not step the day: only the calendar's cursor moved.
    expect(fetchRunDay).toHaveBeenCalledTimes(1);
    await user.keyboard('{Enter}');
    await screen.findByTestId('runs-day-row-job-9001');
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-25');
  });

  it('never moves the cursor past today', async () => {
    const user = userEvent.setup({ advanceTimers: () => {} });
    await openCalendar();
    await user.keyboard('{ArrowRight}{ArrowDown}');
    expect(screen.getByTestId('runs-cal-grid')).toHaveAttribute('aria-activedescendant', expect.stringContaining('2026-11-02'));
  });

  it('says so, with Retry, when the month cannot be loaded, and still lets a day be picked', async () => {
    fetchRunCalendar.mockRejectedValueOnce(new Error('down'));
    await openCalendar();
    expect(await screen.findByTestId('runs-cal-error')).toHaveTextContent('Couldn\'t load this month.');
    fireEvent.click(screen.getByTestId('runs-cal-retry'));
    await waitFor(() => expect(screen.queryByTestId('runs-cal-error')).toBeNull());
    expect(fetchRunCalendar).toHaveBeenCalledTimes(2);
  });

  it('renders inline and full width on a phone (no popover positioning), with 44px targets', async () => {
    useIsMobile.mockReturnValue(true);
    const region = await openCalendar();
    expect(region.className).toContain('min-[640px]:absolute');
    expect(region.className).not.toContain(' absolute ');
    expect(screen.getByTestId('runs-cal-prev').className).toContain('max-[639px]:size-11');
  });
});

describe('Day view: review fixes', () => {
  const press = (key, init = {}, target = document.body) => fireEvent.keyDown(target, { key, ...init });

  it('moves the calendar cursor into the month the month buttons page to (aria-activedescendant is a rendered cell)', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-date'));
    const grid = await screen.findByTestId('runs-cal-grid');
    fireEvent.click(screen.getByTestId('runs-cal-prev'));
    const active = grid.getAttribute('aria-activedescendant');
    expect(active).toContain('2026-10-');
    expect(document.getElementById(active)).not.toBeNull();
    fireEvent.click(screen.getByTestId('runs-cal-next'));
    expect(document.getElementById(grid.getAttribute('aria-activedescendant'))).not.toBeNull();
  });

  it('lets the calendar take the full row on a phone', async () => {
    useIsMobile.mockReturnValue(true);
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-date'));
    const region = await screen.findByTestId('runs-calendar');
    expect(region.className).toContain('max-[639px]:w-full');
    expect(region.className).toContain('max-[639px]:basis-full');
  });

  it('puts focus on the date button when Next, Today or Previous disable themselves', async () => {
    window.location.hash = '#manage/runs?date=2026-11-01';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    const next = screen.getByTestId('runs-day-next');
    next.focus();
    fireEvent.click(next);
    await loaded();
    expect(screen.getByTestId('runs-day-date')).toHaveFocus();
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-9001');
    const today = screen.getByTestId('runs-day-today');
    today.focus();
    fireEvent.click(today);
    await loaded();
    expect(screen.getByTestId('runs-day-date')).toHaveFocus();
  });

  it('closes the calendar and restores focus to the date button when T is pressed inside it', async () => {
    window.location.hash = '#manage/runs?date=2026-10-30';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    fireEvent.click(screen.getByTestId('runs-day-date'));
    const grid = await screen.findByTestId('runs-cal-grid');
    press('t', {}, grid);
    expect(screen.queryByTestId('runs-calendar')).toBeNull();
    expect(screen.getByTestId('runs-day-date')).toHaveFocus();
  });

  it('names a block\'s status in words unless it is Done', async () => {
    serveDays([dayRow({ id: 'job-2', jobRunId: 2, status: 'FAILED', name: 'Tide data' }), dayRow({ id: 'job-3', jobRunId: 3 })]);
    renderView();
    expect(await screen.findByTestId('runs-strip-block-job-2')).toHaveAccessibleName(/Tide data \(Failed\)/);
    expect(screen.getByTestId('runs-strip-block-job-3').getAttribute('aria-label')).not.toContain('(Done)');
  });

  it('scrolls again when the block of an already-open row is pressed', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-strip-block-cycle-568'));
    fireEvent.click(screen.getByTestId('runs-strip-block-cycle-568'));
    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('runs-day-row-cycle-568')).toHaveAttribute('aria-expanded', 'true');
    delete Element.prototype.scrollIntoView;
  });

  it('draws the strip at the card\'s measured pixel width, so text and blocks are real pixels', async () => {
    let notify;
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb) { notify = cb; }

      observe() {}

      disconnect() {}
    });
    renderView();
    await loaded();
    // The observer is constructed in an effect, which may land after the day list is on screen.
    await waitFor(() => expect(notify).toBeTypeOf('function'));
    await act(async () => { notify([{ contentRect: { width: 420 } }]); });
    expect(screen.getByTestId('runs-strip-svg')).toHaveAttribute('viewBox', expect.stringMatching(/^0 0 420 \d+$/));
    vi.unstubAllGlobals();
  });

  it('lists each group under its own heading, not as header rows inside one list', async () => {
    renderView();
    await loaded();
    const finished = screen.getByTestId('runs-day-group-finished');
    expect(finished.tagName).toBe('H4');
    const section = finished.closest('section');
    expect(section).toHaveAttribute('aria-labelledby', finished.id);
    expect(within(section).getAllByRole('listitem').length).toBe(2);
    expect(screen.getByTestId('runs-day-group-upcoming').closest('section')).not.toBe(section);
  });

  it('right-aligns "Open run detail" on the facts row', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-job-1'));
    expect(screen.getByTestId('runs-row-job-1').className).toContain('ml-auto');
  });

  it('does not say "refreshes every minute" on a past day, nor show the old day\'s time while a new date loads', async () => {
    renderView();
    expect(await screen.findByTestId('runs-updated')).toHaveTextContent('refreshes every minute');
    fetchRunDay.mockReturnValueOnce(new Promise(() => {}));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-loading');
    expect(screen.queryByTestId('runs-updated')).toBeNull();
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], { date: '2026-10-31' }));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.getByTestId('runs-updated')).toHaveTextContent(/^Updated \d\d:\d\d$/);
  });

  it('prevents the default only when a day key actually moves the date', async () => {
    renderView();
    await loaded();
    const atToday = new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true });
    document.body.dispatchEvent(atToday);
    expect(atToday.defaultPrevented).toBe(false);
    const moves = new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true, cancelable: true });
    document.body.dispatchEvent(moves);
    expect(moves.defaultPrevented).toBe(true);
    await screen.findByTestId('runs-day-row-job-9001');
  });

  it('re-reads the Day when the live head\'s running count changes (a finished cycle stops saying Running)', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 1, liveCycleId: null }));
    renderView();
    await loaded();
    const before = fetchRunDay.mock.calls.length;
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 0, liveCycleId: null }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(fetchRunDay.mock.calls.length).toBeGreaterThan(before + 1));
  });

  it('drops a failure that arrives after a newer answer', async () => {
    renderView();
    await loaded();
    let failOld;
    fetchRunDay.mockReturnValueOnce(new Promise((_resolve, reject) => { failOld = reject; }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    fetchRunDay.mockResolvedValueOnce(dayResponse(TODAY_ROWS));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await act(async () => {});
    await act(async () => { failOld(new Error('late')); });
    expect(screen.queryByTestId('runs-day-stale')).toBeNull();
    expect(screen.queryByTestId('runs-day-error')).toBeNull();
  });
});

describe('Day view: the address moves under it (Back, an inbound link)', () => {
  it('follows Back: open a panel, step the date, Back, and the shown date matches the hash', async () => {
    window.location.hash = '#manage/runs?view=day&date=2026-10-30';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    const first = window.location.hash;
    act(() => {
      window.location.hash = '#manage/runs/job/9001?view=day&date=2026-10-30';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    fireEvent.click(screen.getByTestId('runs-day-next'));
    await waitFor(() => expect(screen.getByTestId('runs-day-date-text')).toHaveTextContent('31 Oct'));
    act(() => {
      window.history.replaceState(null, '', first);
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    await waitFor(() => expect(screen.getByTestId('runs-day-date-text')).toHaveTextContent('30 Oct'));
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-30');
  });

  it('honours an inbound ?view=day&date=... while mounted, and collapses the open row', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-cycle-568'));
    act(() => {
      window.location.hash = '#manage/runs?view=day&date=2026-10-29';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    await screen.findByTestId('runs-day-row-job-9001');
    expect(fetchRunDay).toHaveBeenLastCalledWith('2026-10-29');
    expect(screen.queryByTestId('runs-day-detail-cycle-568')).toBeNull();
  });

  it('ignores a hash for another tab', async () => {
    renderView();
    await loaded();
    const calls = fetchRunDay.mock.calls.length;
    act(() => {
      window.location.hash = '#manage/scheduler?date=2026-10-29';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    expect(fetchRunDay.mock.calls.length).toBe(calls);
  });
});

describe('Day view: expanding a row', () => {
  it('toggles on click, one open at a time, with aria-expanded', async () => {
    renderView();
    await loaded();
    const first = screen.getByTestId('runs-day-row-job-1');
    const second = screen.getByTestId('runs-day-row-cycle-568');
    expect(first).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(first);
    expect(first).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('runs-day-detail-job-1')).toBeInTheDocument();
    fireEvent.click(second);
    expect(first).toHaveAttribute('aria-expanded', 'false');
    expect(second).toHaveAttribute('aria-expanded', 'true');
    expect(screen.queryByTestId('runs-day-detail-job-1')).toBeNull();
    fireEvent.click(second);
    expect(second).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens from the keyboard (Enter and Space on the focused row)', async () => {
    const user = userEvent.setup({ advanceTimers: () => {} });
    renderView();
    await loaded();
    screen.getByTestId('runs-day-row-job-1').focus();
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('runs-day-row-job-1')).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard(' ');
    expect(screen.getByTestId('runs-day-row-job-1')).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows a finished cycle\'s facts: times to the second, cost in both currencies, speed, batches, answers ready', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-cycle-568'));
    expect(screen.getByTestId('runs-fact-started')).toHaveTextContent('02:56:42');
    expect(screen.getByTestId('runs-fact-finished')).toHaveTextContent('03:40:03');
    expect(screen.getByTestId('runs-fact-cost')).toHaveTextContent('£1.38 · $1.85');
    expect(screen.getByTestId('runs-fact-speed')).toHaveTextContent('faster than 3% of last 30');
    expect(screen.getByTestId('runs-fact-batches')).toHaveTextContent('5 · 216 requests');
    expect(screen.getByTestId('runs-fact-answers-ready')).toHaveTextContent('03:44');
  });

  it('leaves out the facts the server did not serve (no speed under ten earlier runs, no answers)', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-job-1'));
    expect(screen.queryByTestId('runs-fact-speed')).toBeNull();
    expect(screen.queryByTestId('runs-fact-batches')).toBeNull();
    expect(screen.queryByTestId('runs-fact-answers-ready')).toBeNull();
  });

  it('shows a scheduled row\'s expectations, no detail link and no external calls', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-sched-afternoon-1'));
    expect(screen.getByTestId('runs-fact-expected-start')).toHaveTextContent('12:39');
    expect(screen.getByTestId('runs-fact-expected-finish')).toHaveTextContent('~12:54');
    expect(screen.getByTestId('runs-fact-estimate-note')).toHaveTextContent('Estimate: median of last 30 runs');
    expect(screen.queryByTestId('runs-row-sched-afternoon-1')).toBeNull();
    expect(screen.queryByTestId('runs-calls')).toBeNull();
    expect(fetchRunCalls).not.toHaveBeenCalled();
  });

  it('marks an estimated expected start with ~', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-sched-briefing-2'));
    expect(screen.getByTestId('runs-fact-expected-start')).toHaveTextContent('~12:56');
  });

  it('has an "Open run detail" link only for a cycle or a job run, to the existing panel address', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-cycle-568'));
    expect(screen.getByTestId('runs-row-cycle-568')).toHaveAttribute('href', '#manage/runs/cycle/568');
    expect(screen.getByTestId('runs-row-cycle-568')).toHaveTextContent('Open run detail');
    fireEvent.click(screen.getByTestId('runs-day-row-job-1'));
    expect(screen.getByTestId('runs-row-job-1')).toHaveAttribute('href', '#manage/runs/job/1');
  });

  it('opens the cycle panel from the link, and marks that row current', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-cycle-568'));
    fireEvent.click(screen.getByTestId('runs-row-cycle-568'));
    expect(window.location.hash).toBe('#manage/runs/cycle/568');
    expect(await screen.findByTestId('cycle-panel')).toBeInTheDocument();
    expect(screen.getByTestId('runs-day-row-cycle-568')).toHaveAttribute('aria-current', 'true');
  });

  it('collapses when the day changes', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-day-row-cycle-568'));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.queryByTestId('runs-day-detail-cycle-568')).toBeNull();
  });
});

describe('Day view: the external calls table', () => {
  async function expand(id = 'cycle-568') {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId(`runs-day-row-${id}`));
  }

  it('is fetched on expand, reads "Loading calls…", then the table with the served rows', async () => {
    let release;
    fetchRunCalls.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    await expand();
    expect(fetchRunCalls).toHaveBeenCalledWith('cycle-568');
    expect(screen.getByTestId('runs-calls-loading')).toHaveTextContent('Loading calls…');
    await act(async () => {
      release(callsResponse([
        callService(),
        callService({
          service: 'anthropic', label: 'Anthropic', purpose: 'Message Batches · sky scoring', calls: 216, batches: 5,
          requests: 216, durationMs: 2_211_000, costMicroDollars: 1_750_000, costGbp: 1.38, costUsd: 1.85,
        }),
      ]));
    });
    expect(screen.getByTestId('runs-calls')).toHaveTextContent(/External calls\s*·\s*2 services/i);
    const meteo = screen.getByTestId('runs-calls-row-open-meteo');
    expect(meteo).toHaveTextContent('Open-Meteo');
    expect(meteo).toHaveTextContent('Forecast · cloud, visibility, wind');
    expect(meteo).toHaveTextContent('27');
    expect(meteo).toHaveTextContent('304ms');
    expect(meteo).toHaveTextContent('free');
    expect(meteo).toHaveTextContent('OK');
    const anthropic = screen.getByTestId('runs-calls-row-anthropic');
    expect(anthropic).toHaveTextContent('5 batches · 216 req');
    expect(anthropic).toHaveTextContent('36m 51s');
    expect(anthropic).toHaveTextContent('£1.38');
  });

  it('shows error detail under the purpose, amber for some errors and coral for all, with the status words', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([
      callService({
        service: 'anthropic', label: 'Anthropic', purpose: 'Messages · ready answers', errors: 3, status: 'ERRORS',
        errorSummary: '3 × 529 overloaded after retries',
      }),
      callService({
        service: 'light-pollution', label: 'Light pollution', purpose: 'Bortle', errors: 4, status: 'FAILED',
        errorSummary: '401 · API key not configured',
      }),
    ]));
    await expand();
    const ok = await screen.findByTestId('runs-calls-row-anthropic');
    expect(ok).toHaveTextContent('Errors');
    expect(within(ok).getByTestId('runs-calls-error-summary').className).toContain('text-verdict-marginal');
    const failed = screen.getByTestId('runs-calls-row-light-pollution');
    expect(failed).toHaveTextContent('Failed');
    expect(within(failed).getByTestId('runs-calls-error-summary').className).toContain('text-plex-coral');
  });

  it('says a failed fetch is a failed fetch, distinct from a run whose calls errored, and retries', async () => {
    fetchRunCalls.mockRejectedValueOnce(new Error('Network down'));
    await expand();
    expect(await screen.findByTestId('runs-calls-error')).toHaveTextContent('Couldn\'t load the external calls');
    fireEvent.click(screen.getByTestId('runs-calls-retry'));
    expect(await screen.findByTestId('runs-calls-table')).toBeInTheDocument();
    expect(fetchRunCalls).toHaveBeenCalledTimes(2);
  });

  it('says nothing was logged when the run has no calls', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([]));
    await expand();
    expect(await screen.findByTestId('runs-calls-empty')).toHaveTextContent('No external calls were logged for this run.');
  });

  it('says calls are not logged for a Sky eval, without asking the server', async () => {
    serveDays([evalRow()]);
    renderView();
    fireEvent.click(await screen.findByTestId('runs-day-row-eval-5'));
    expect(screen.getByTestId('runs-calls-unlogged')).toHaveTextContent('Calls are not logged for Sky eval runs.');
    expect(fetchRunCalls).not.toHaveBeenCalled();
    expect(screen.queryByTestId('runs-row-eval-5')).toBeNull();
  });

  it('says the same when the server answers logged: false', async () => {
    fetchRunCalls.mockResolvedValue(callsResponse([], { logged: false }));
    await expand();
    expect(await screen.findByTestId('runs-calls-unlogged')).toBeInTheDocument();
  });

  it('hides the Time column below 640px of list width (a container query)', async () => {
    await expand();
    await screen.findByTestId('runs-calls-table');
    const time = within(screen.getByTestId('runs-calls-row-open-meteo')).getByText('304ms');
    expect(time.className).toContain('hidden');
    expect(time.className).toContain('@min-[640px]:table-cell');
  });
});

describe('Day view: the filter chips', () => {
  it('shows the served count on each chip', async () => {
    serveDays([
      ...TODAY_ROWS,
      dayRow({ id: 'job-7', jobRunId: 7, status: 'FAILED' }),
      dayRow({ id: 'job-8', jobRunId: 8, type: 'ASK_READY', name: 'Ask ready answers' }),
      evalRow({ startedAt: '2026-11-02T04:00:00Z' }),
    ]);
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-chip-all-count')).toHaveTextContent('7');
    expect(screen.getByTestId('runs-chip-cycles-count')).toHaveTextContent('2');
    expect(screen.getByTestId('runs-chip-ask_ready-count')).toHaveTextContent('1');
    expect(screen.getByTestId('runs-chip-briefing-count')).toHaveTextContent('1');
    expect(screen.getByTestId('runs-chip-sky_eval-count')).toHaveTextContent('1');
    expect(screen.getByTestId('runs-chip-other-count')).toHaveTextContent('2');
    expect(screen.getByTestId('runs-chip-attention-count')).toHaveTextContent('1');
  });

  it('keeps only the chip\'s rows, and All brings them back', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(screen.getByTestId('runs-chip-cycles')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('runs-day-row-job-1')).toBeNull();
    expect(screen.getByTestId('runs-day-row-cycle-568')).toBeInTheDocument();
    expect(screen.getByTestId('runs-day-row-sched-afternoon-1')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-chip-all'));
    expect(screen.getByTestId('runs-day-row-job-1')).toBeInTheDocument();
  });

  it('Need attention keeps Partial and Failed rows, and pressed again clears', async () => {
    serveDays([dayRow({ id: 'job-1', jobRunId: 1, status: 'PARTIAL' }), dayRow({ id: 'job-2', jobRunId: 2 })]);
    renderView();
    await screen.findByTestId('runs-day-row-job-1');
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    expect(screen.getByTestId('runs-day-row-job-1')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-day-row-job-2')).toBeNull();
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    expect(screen.getByTestId('runs-day-row-job-2')).toBeInTheDocument();
  });

  it('says so, with Show all, when the filter leaves no rows', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-chip-sky_eval'));
    expect(screen.getByTestId('runs-day-filter-empty')).toHaveTextContent('No runs match this filter.');
    fireEvent.click(screen.getByTestId('runs-day-filter-clear'));
    expect(screen.getByTestId('runs-day-row-cycle-568')).toBeInTheDocument();
  });

  it('says "No runs on this day." for an empty day, and keeps the date bar', async () => {
    serveDays([]);
    renderView();
    expect(await screen.findByTestId('runs-day-empty')).toHaveTextContent('No runs on this day.');
    expect(screen.getByTestId('runs-date-bar')).toBeInTheDocument();
  });
});

describe('Day view: the strip', () => {
  it('draws one block per row in its lane, with the served sunrise and sunset', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-strip-block-job-1')).toBeInTheDocument();
    expect(screen.getByTestId('runs-strip-block-cycle-568')).toBeInTheDocument();
    expect(screen.getByTestId('runs-strip-meta')).toHaveTextContent('sunrise 07:42 · sunset 16:49');
    expect(screen.getByTestId('runs-strip-title')).toHaveTextContent('Today so far, and what’s still to come');
    const svg = screen.getByTestId('runs-strip-svg');
    expect(svg).toHaveAttribute('viewBox', expect.stringMatching(/^0 0 1000 \d+$/));
    expect(screen.getByTestId('runs-strip-night-am')).toBeInTheDocument();
    expect(screen.getByTestId('runs-strip-night-pm')).toBeInTheDocument();
  });

  it('draws Now on today only, labelled with the served time', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-strip-now')).toHaveTextContent('Now 09:26');
  });

  it('has no Now line, and "The day at a glance", on a past day', async () => {
    window.location.hash = '#manage/runs?date=2026-11-01';
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.queryByTestId('runs-strip-now')).toBeNull();
    expect(screen.getByTestId('runs-strip-title')).toHaveTextContent('The day at a glance');
  });

  it('draws a scheduled block dashed and unfilled, a partial one with an amber ring, a failed one coral', async () => {
    serveDays([
      scheduledRow({ id: 'sched-1' }),
      dayRow({ id: 'job-2', jobRunId: 2, status: 'PARTIAL' }),
      dayRow({ id: 'job-3', jobRunId: 3, status: 'FAILED' }),
    ]);
    renderView();
    const scheduled = (await screen.findByTestId('runs-strip-block-sched-1')).querySelector('rect');
    expect(scheduled).toHaveAttribute('stroke-dasharray', '3 2');
    expect(scheduled.getAttribute('class')).toContain('fill-transparent');
    expect(screen.getByTestId('runs-strip-block-job-2').querySelector('rect').getAttribute('class')).toContain('stroke-verdict-marginal');
    expect(screen.getByTestId('runs-strip-block-job-3').querySelector('rect').getAttribute('class')).toContain('stroke-plex-coral');
  });

  it('colours a block by its type, from the --color-run-* tokens', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-strip-block-cycle-568').querySelector('rect').getAttribute('class')).toContain('fill-run-overnight');
    expect(screen.getByTestId('runs-strip-block-job-1').querySelector('rect').getAttribute('class')).toContain('fill-run-other');
    expect(screen.getByTestId('runs-day-row-cycle-568').querySelector('.bg-run-overnight')).not.toBeNull();
  });

  it('shows a tooltip with the name, start, duration, cost and note on hover and focus, and none after', async () => {
    renderView();
    await loaded();
    const block = screen.getByTestId('runs-strip-block-cycle-568');
    fireEvent.mouseEnter(block);
    const tip = screen.getByTestId('runs-strip-tooltip');
    expect(tip).toHaveTextContent('Overnight cycle');
    expect(tip).toHaveTextContent('02:56 · 43m 21s · £1.38');
    fireEvent.mouseLeave(block);
    expect(screen.queryByTestId('runs-strip-tooltip')).toBeNull();
    fireEvent.focus(block);
    expect(screen.getByTestId('runs-strip-tooltip')).toBeInTheDocument();
  });

  it('opens a row in the list when its block is pressed (click, Enter, Space), and scrolls it into view', async () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-strip-block-cycle-568'));
    expect(screen.getByTestId('runs-day-row-cycle-568')).toHaveAttribute('aria-expanded', 'true');
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
    fireEvent.keyDown(screen.getByTestId('runs-strip-block-job-1'), { key: 'Enter' });
    expect(screen.getByTestId('runs-day-row-job-1')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(screen.getByTestId('runs-strip-block-sched-afternoon-1'), { key: ' ' });
    expect(screen.getByTestId('runs-day-row-sched-afternoon-1')).toHaveAttribute('aria-expanded', 'true');
    delete Element.prototype.scrollIntoView;
  });

  it('clears a filter that hides the pressed block\'s row', async () => {
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    fireEvent.click(screen.getByTestId('runs-strip-block-job-1'));
    expect(screen.getByTestId('runs-chip-all')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-day-row-job-1')).toHaveAttribute('aria-expanded', 'true');
  });

  it('stretches the x axis to a 25-hour day: ticks every three hours on the UK clock, closing at 00:00', async () => {
    window.location.hash = '#manage/runs?date=2026-10-25';
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], {
      date: '2026-10-25',
      dayStartUtc: '2026-10-24T23:00:00Z',
      dayEndUtc: '2026-10-26T00:00:00Z',
      sunriseAt: '2026-10-25T06:41:00Z',
      sunsetAt: '2026-10-25T16:46:00Z',
    }));
    renderView();
    await screen.findByTestId('runs-day-row-job-9001');
    const labels = Array.from(screen.getByTestId('runs-strip-svg').querySelectorAll('text')).map((t) => t.textContent);
    // 25 hours from 00:00 BST: a tick every three real hours, so 02:00 GMT is the second (the clock went back at
    // 01:00 GMT), then 05:00 ... 23:00, and the closing 00:00. The axis spans the 25 hours, not 24.
    expect(labels.filter((l) => /^\d\d:00$/.test(l))).toEqual(
      ['00:00', '02:00', '05:00', '08:00', '11:00', '14:00', '17:00', '20:00', '23:00', '00:00'],
    );
  });

  it('scrolls horizontally inside its card on a phone, never the page', async () => {
    useIsMobile.mockReturnValue(true);
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-strip-scroll').className).toContain('overflow-x-auto');
    expect(screen.getByTestId('runs-strip-svg').getAttribute('class')).toContain('min-w-[720px]');
  });

  it('makes the phone strip a labelled, focusable region so a keyboard can scroll it', async () => {
    useIsMobile.mockReturnValue(true);
    renderView();
    await loaded();
    const strip = screen.getByTestId('runs-strip-scroll');
    expect(strip).toHaveAttribute('tabindex', '0');
    expect(strip).toHaveAttribute('role', 'region');
    expect(strip).toHaveAttribute('aria-label', 'Runs timeline, scrolls sideways');
    expect(strip.className).toContain('focus-visible:outline-plex-gold');
  });

  it('does not scroll on a wide screen', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-strip-scroll').className).not.toContain('overflow-x-auto');
    const strip = screen.getByTestId('runs-strip-scroll');
    expect(strip).not.toHaveAttribute('tabindex');
    expect(strip).not.toHaveAttribute('role');
    expect(strip).not.toHaveAttribute('aria-label');
    expect(strip.className).toBe('');
  });
});

describe('Day view: states (F13)', () => {
  it('shows a loading skeleton, with the date bar, while the day loads', async () => {
    fetchRunDay.mockReturnValue(new Promise(() => {}));
    renderView();
    const skeleton = await screen.findByTestId('runs-day-loading');
    expect(skeleton.className).toContain('motion-reduce:animate-none');
    expect(screen.getByTestId('runs-date-bar')).toBeInTheDocument();
  });

  it('shows the error with Retry, and Retry loads the day', async () => {
    fetchRunDay.mockRejectedValueOnce(Object.assign(new Error('x'), { response: { status: 500, data: { error: 'Server broke' } } }));
    renderView();
    expect(await screen.findByTestId('runs-day-error')).toHaveTextContent('Server broke');
    expect(screen.queryByTestId('runs-error')).toBeNull();
    fireEvent.click(screen.getByTestId('runs-day-retry'));
    await loaded();
  });

  it('keeps the day on screen with a line when a refresh fails', async () => {
    renderView();
    await loaded();
    fetchRunDay.mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    expect(await screen.findByTestId('runs-day-stale')).toHaveTextContent('Showing what was last loaded');
    expect(screen.getByTestId('runs-day-row-cycle-568')).toBeInTheDocument();
  });

  it('never shows the old day\'s rows under a new date, and drops a late answer for the old one', async () => {
    let releaseOld;
    renderView();
    await loaded();
    fetchRunDay.mockReturnValueOnce(new Promise((resolve) => { releaseOld = resolve; }));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    expect(await screen.findByTestId('runs-day-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-day-row-cycle-568')).toBeNull();
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow({ id: 'job-31', jobRunId: 31 })], { date: '2026-10-31' }));
    fireEvent.click(screen.getByTestId('runs-day-prev'));
    await screen.findByTestId('runs-day-row-job-31');
    await act(async () => { releaseOld(pastDayResponse([dayRow({ id: 'job-99', jobRunId: 99 })], { date: '2026-11-01' })); });
    expect(screen.queryByTestId('runs-day-row-job-99')).toBeNull();
    expect(screen.getByTestId('runs-day-row-job-31')).toBeInTheDocument();
  });

  it('drops the ~ rows when a poll says midnight has passed (isToday false)', async () => {
    renderView();
    await loaded();
    expect(screen.getByTestId('runs-day-start-sched-briefing-2')).toHaveTextContent('~12:56');
    fetchRunDay.mockResolvedValue(pastDayResponse([dayRow()], { date: '2026-11-02' }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await screen.findByTestId('runs-day-row-job-9001');
    expect(screen.queryByTestId('runs-day-start-sched-briefing-2')).toBeNull();
    expect(screen.queryByTestId('runs-day-group-upcoming')).toBeNull();
  });

  it('shows the Cost trends and Timing views (loading until served), with the banner still above', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 1, liveCycleId: 412 }));
    renderView();
    await loaded();
    fireEvent.click(screen.getByTestId('runs-view-tab-cost'));
    expect(await screen.findByTestId('runs-cost-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-day-view')).toBeNull();
    fireEvent.click(screen.getByTestId('runs-view-tab-timing'));
    expect(await screen.findByTestId('runs-timing-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('runs-live-strip')).toBeInTheDocument();
  });

  it('moves between the view tabs with the arrow keys (roving tab stop, selection follows)', async () => {
    renderView();
    await loaded();
    const day = screen.getByTestId('runs-view-tab-day');
    expect(day).toHaveAttribute('aria-selected', 'true');
    expect(day).toHaveAttribute('tabindex', '0');
    expect(screen.getByTestId('runs-view-tab-cost')).toHaveAttribute('tabindex', '-1');
    fireEvent.keyDown(day, { key: 'End' });
    expect(screen.getByTestId('runs-view-tab-timing')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('runs-view-tab-timing')).toHaveFocus();
  });
});
