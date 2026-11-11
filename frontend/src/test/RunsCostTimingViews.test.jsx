import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import RunsView from '../components/runs/RunsView.jsx';
import { cycleDetail, jobDetail, runsPage } from './runsFixtures.js';
import { dayResponse, pastDayResponse } from './runsDayFixtures.js';
import {
  costDay, costDays, costResponse, costType, overnightTiming, timingResponse, timingRuns, timingStats,
} from './runsCostTimingFixtures.js';

// The Runs Cost trends and Timing views (runs-deep-dive-plan.md D4) end to end below ManageView: the verdict, the
// chart's isolate and click-through, the table, the timing controls, stat strip, histogram, planner, ready-by card and
// scatter, every F13 state, and the address (DD-9). The network is mocked at the API modules.

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
  fetchRuns, fetchJobRun, fetchRunDay, fetchRunCost, fetchRunTiming, fetchRunCalendar, fetchRunCalls,
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

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/runs';
  window.localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T09:26:00Z'));
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
  fetchRuns.mockResolvedValue(runsPage([]));
  fetchRunDay.mockImplementation((date) => Promise.resolve(date ? pastDayResponse([], { date }) : dayResponse([])));
  fetchRunCost.mockResolvedValue(costResponse());
  fetchRunTiming.mockResolvedValue(timingResponse());
  fetchRunCalendar.mockResolvedValue({ month: '2026-11', days: [], maxSpendGbp: 0, earliestDay: '2026-09-20' });
  fetchRunCalls.mockResolvedValue({ runId: 'x', logged: true, services: [] });
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
});

const textOf = (testId) => screen.getByTestId(testId).textContent.replace(/\s+/g, ' ').trim();

describe('Cost trends', () => {
  const openCost = async (hash = '#manage/runs?view=cost') => {
    window.location.hash = hash;
    renderView();
    return screen.findByTestId('runs-cost-view');
  };

  it('asks for the default 30 days and says so in the address only once a period is picked', async () => {
    await openCost();
    expect(fetchRunCost).toHaveBeenCalledWith(30);
    expect(window.location.hash).toBe('#manage/runs?view=cost');
    expect(screen.getByTestId('runs-cost-range-30')).toHaveAttribute('aria-pressed', 'true');
  });

  it('words the verdict from the served fields', async () => {
    await openCost();
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('Yes. Spend is up 14% on the previous 14 days.');
    expect(textOf('runs-cost-body')).toContain('£130.73 over the last 14 days against £114.83 before, £4.36 a day instead of £3.83.');
    expect(textOf('runs-cost-body')).toContain('each run now covers 24 locations on average, up from 18.');
    expect(textOf('runs-cost-body')).toContain('Sky eval, the test harness, is 13% of spend (£16.39, +35% on the period before).');
  });

  it('draws one stacked bar per shown day and a 7-day line, with today left out', async () => {
    await openCost();
    expect(screen.getAllByTestId(/^runs-cost-bar-/)).toHaveLength(14);
    expect(screen.queryByTestId('runs-cost-bar-2026-11-02')).toBeNull();
    expect(screen.getByTestId('runs-cost-bar-2026-11-01')).toBeInTheDocument();
    const types = [...screen.getByTestId('runs-cost-bar-2026-11-01').querySelectorAll('rect')].map((r) => r.dataset.type);
    expect(types).toEqual(['ASK_READY', 'SKY_EVAL', 'OVERNIGHT', 'AFTERNOON']);
    expect(screen.getByTestId('runs-cost-average').getAttribute('points').split(' ')).toHaveLength(14);
  });

  it('picks a type from its legend chip: the others drop to 12% and the line follows it', async () => {
    await openCost();
    const all = screen.getByTestId('runs-cost-average').getAttribute('points');
    fireEvent.click(screen.getByTestId('runs-cost-iso-sky_eval'));
    expect(screen.getByTestId('runs-cost-iso-sky_eval')).toHaveAttribute('aria-pressed', 'true');
    expect(window.location.hash).toBe('#manage/runs?view=cost&type=SKY_EVAL');
    const bar = screen.getByTestId('runs-cost-bar-2026-11-01');
    expect(bar.querySelector('[data-type="SKY_EVAL"]')).toHaveAttribute('opacity', '1');
    expect(bar.querySelector('[data-type="ASK_READY"]')).toHaveAttribute('opacity', '0.12');
    expect(screen.getByTestId('runs-cost-average').getAttribute('points')).not.toBe(all);
    fireEvent.click(screen.getByTestId('runs-cost-iso-all'));
    expect(screen.getByTestId('runs-cost-average').getAttribute('points')).toBe(all);
    expect(window.location.hash).toBe('#manage/runs?view=cost');
  });

  it('picks several types from chips and rows, writing a literal comma list to the address', async () => {
    await openCost();
    fireEvent.click(screen.getByTestId('runs-cost-iso-evening'));
    fireEvent.click(screen.getByTestId('runs-cost-iso-overnight'));
    fireEvent.click(screen.getByTestId('runs-cost-row-afternoon-toggle'));
    expect(window.location.hash).toBe('#manage/runs?view=cost&type=OVERNIGHT,AFTERNOON,EVENING');
    ['overnight', 'afternoon', 'evening'].forEach((key) => {
      expect(screen.getByTestId(`runs-cost-iso-${key}`)).toHaveAttribute('aria-pressed', 'true');
    });
    expect(screen.getByTestId('runs-cost-selection-total')).toHaveTextContent('Overnight cycle + Afternoon refresh + Evening refresh');
    fireEvent.click(screen.getByTestId('runs-cost-iso-evening'));
    expect(window.location.hash).toBe('#manage/runs?view=cost&type=OVERNIGHT,AFTERNOON');
  });

  it('restores a type list from the address', async () => {
    await openCost('#manage/runs?view=cost&type=OVERNIGHT,AFTERNOON,EVENING');
    expect(screen.getByTestId('runs-cost-iso-afternoon')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-iso-sky_eval')).toHaveAttribute('aria-pressed', 'false');
  });

  it('picks from a table row and clears on a second press', async () => {
    await openCost();
    fireEvent.click(screen.getByTestId('runs-cost-row-ask_ready-toggle'));
    expect(screen.getByTestId('runs-cost-iso-ask_ready')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-row-ask_ready-toggle')).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByTestId('runs-cost-row-ask_ready-toggle'));
    expect(screen.getByTestId('runs-cost-iso-all')).toHaveAttribute('aria-pressed', 'true');
  });

  it('restores a selection and a period from the address', async () => {
    await openCost('#manage/runs?view=cost&range=60&type=ASK_READY');
    expect(fetchRunCost).toHaveBeenCalledWith(60);
    expect(screen.getByTestId('runs-cost-range-60')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-iso-ask_ready')).toHaveAttribute('aria-pressed', 'true');
  });

  it('switches the period, fetching it and writing it to the address', async () => {
    await openCost();
    fireEvent.click(screen.getByTestId('runs-cost-range-14'));
    await waitFor(() => expect(fetchRunCost).toHaveBeenCalledWith(14));
    expect(window.location.hash).toBe('#manage/runs?view=cost&range=14');
  });

  it('asks for all time from the address and from the button, and writes range=all', async () => {
    const { costAllTime } = await import('./runsCostTimingFixtures.js');
    fetchRunCost.mockResolvedValue(costAllTime(30));
    await openCost('#manage/runs?view=cost&range=all');
    expect(fetchRunCost).toHaveBeenCalledWith('all');
    expect(screen.getByTestId('runs-cost-range-all')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('spent since');
    fireEvent.click(screen.getByTestId('runs-cost-range-90'));
    await waitFor(() => expect(fetchRunCost).toHaveBeenCalledWith(90));
    expect(window.location.hash).toBe('#manage/runs?view=cost&range=90');
    fireEvent.click(screen.getByTestId('runs-cost-range-all'));
    await waitFor(() => expect(window.location.hash).toBe('#manage/runs?view=cost&range=all'));
  });

  it('shows a tooltip with each type\'s pounds and the total on hover and focus', async () => {
    await openCost();
    fireEvent.mouseEnter(screen.getByTestId('runs-cost-day-2026-11-01'));
    const tip = screen.getByTestId('runs-cost-tooltip');
    expect(tip).toHaveTextContent('Sun 1 Nov');
    expect(tip).toHaveTextContent('Ask ready answers');
    expect(tip).toHaveTextContent('£2.00');
    expect(tip).toHaveTextContent('Total');
    expect(tip).toHaveTextContent('£5.00');
    fireEvent.mouseLeave(screen.getByTestId('runs-cost-day-2026-11-01'));
    expect(screen.queryByTestId('runs-cost-tooltip')).toBeNull();
    fireEvent.focus(screen.getByTestId('runs-cost-day-2026-10-30'));
    expect(screen.getByTestId('runs-cost-tooltip')).toHaveTextContent('Fri 30 Oct');
  });

  it('says "so far" in the tooltip for a day with a cost still open', async () => {
    const days = costDays(14, '2026-11-01', { OTHER: 1 });
    days[27] = costDay('2026-11-01', { OTHER: 1 }, { costComplete: false });
    fetchRunCost.mockResolvedValue(costResponse({ days }));
    await openCost();
    fireEvent.mouseEnter(screen.getByTestId('runs-cost-day-2026-11-01'));
    expect(screen.getByTestId('runs-cost-tooltip')).toHaveTextContent('£1.00 so far');
  });

  it('opens a clicked day in the Day view for that date, clearing the Cost parameters', async () => {
    await openCost('#manage/runs?view=cost&range=30&type=SKY_EVAL');
    fireEvent.click(screen.getByTestId('runs-cost-day-2026-10-29'));
    await screen.findByTestId('runs-day-view');
    expect(fetchRunDay).toHaveBeenCalledWith('2026-10-29');
    expect(window.location.hash).toBe('#manage/runs?view=day&date=2026-10-29');
  });

  it('resets the Day filter chip when a day is opened', async () => {
    await openCost('#manage/runs?view=cost&filter=cycles');
    fireEvent.click(screen.getByTestId('runs-cost-day-2026-10-29'));
    await screen.findByTestId('runs-day-view');
    expect(window.location.hash).toBe('#manage/runs?view=day&date=2026-10-29');
  });

  it('opens a day from the keyboard: arrows move between days (one tab stop), Enter opens', async () => {
    await openCost();
    const last = screen.getByTestId('runs-cost-day-2026-11-01');
    const first = screen.getByTestId('runs-cost-day-2026-10-19');
    expect(first).toHaveAttribute('tabindex', '0');
    expect(last).toHaveAttribute('tabindex', '-1');
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(screen.getByTestId('runs-cost-day-2026-10-20')).toHaveFocus();
    fireEvent.keyDown(screen.getByTestId('runs-cost-day-2026-10-20'), { key: 'Enter' });
    await screen.findByTestId('runs-day-view');
    expect(fetchRunDay).toHaveBeenCalledWith('2026-10-20');
  });

  it('lists a row per type with the change, the why text and a total with dollars at the served rate', async () => {
    await openCost();
    expect(screen.getByTestId('runs-cost-row-ask_ready-last')).toHaveTextContent('£27.66');
    expect(screen.getByTestId('runs-cost-row-ask_ready-change')).toHaveTextContent('+£7.10+35%');
    expect(screen.getByTestId('runs-cost-row-ask_ready-why')).toHaveTextContent('Runs +0% · £/run +35% · 24 locations per run, was 18');
    expect(screen.getByTestId('runs-cost-row-briefing-change')).toHaveTextContent('−£0.10−3%');
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('£130.73');
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$175.18 · £1 = $1.33');
    expect(screen.getByTestId('runs-cost-spark-ask_ready').querySelectorAll('rect')).toHaveLength(28);
    expect(screen.getByTestId('runs-cost-spark-ask_ready').querySelectorAll('rect[opacity="0.35"]')).toHaveLength(14);
  });

  it('shows the recorded dollars and the rate as £1 = $X, never "at 0.75 $/£", and omits a missing rate', async () => {
    await openCost();
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$175.18 · £1 = $1.33');
    expect(screen.getByTestId('runs-cost-total-usd').textContent).not.toContain('0.75');
    const base = costResponse();
    fetchRunCost.mockResolvedValue(costResponse({ totals: { ...base.totals, fxRate: null } }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(screen.getByTestId('runs-cost-total-usd').textContent).toBe('$175.18'));
  });

  it('keeps the tooltip inside the chart at the right edge', async () => {
    await openCost();
    fireEvent.mouseEnter(screen.getByTestId('runs-cost-day-2026-11-01'));
    const tx = Number(/translate\(([\d.]+)/.exec(screen.getByTestId('runs-cost-tooltip').getAttribute('transform'))[1]);
    expect(tx).toBeLessThanOrEqual(800 - 196 - 2);
    expect(tx).toBeGreaterThanOrEqual(46);
  });

  it('has single-line legend chips', async () => {
    await openCost();
    expect(screen.getByRole('group', { name: 'Show types' }).className).toContain(' flex-wrap');
    expect(screen.getByTestId('runs-cost-iso-evening').className).toContain('whitespace-nowrap');
  });

  it('marks a total and a type with a cost still open', async () => {
    const base = costResponse();
    fetchRunCost.mockResolvedValue(costResponse({
      totals: { ...base.totals, costComplete: false },
      types: [base.types[0], costType('SKY_EVAL', { costComplete: false }), ...base.types.slice(2)],
    }));
    await openCost();
    expect(screen.getByTestId('runs-cost-incomplete')).toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-total-last')).toHaveTextContent('so far');
    expect(screen.getByTestId('runs-cost-row-sky_eval-last')).toHaveTextContent('so far');
  });

  it('shows a loading skeleton, then an error with Retry that asks again', async () => {
    fetchRunCost.mockRejectedValueOnce(new Error('Cost is down'));
    window.location.hash = '#manage/runs?view=cost';
    renderView();
    expect(screen.getByTestId('runs-cost-loading')).toBeInTheDocument();
    expect(await screen.findByTestId('runs-cost-error')).toHaveTextContent('Cost is down');
    fireEvent.click(screen.getByTestId('runs-cost-error-retry'));
    expect(await screen.findByTestId('runs-cost-view')).toBeInTheDocument();
    expect(fetchRunCost).toHaveBeenCalledTimes(2);
  });

  it('keeps the answer on screen when a refresh fails, and says so', async () => {
    await openCost();
    fetchRunCost.mockRejectedValueOnce(new Error('timeout'));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    expect(await screen.findByTestId('runs-cost-stale')).toHaveTextContent('timeout');
    expect(screen.getByTestId('runs-cost-verdict')).toBeInTheDocument();
  });

  it('handles a period that spent nothing: no sentence to fake, an empty chart that says so', async () => {
    const base = costResponse();
    fetchRunCost.mockResolvedValue(costResponse({
      days: costDays(14, '2026-11-01', {}),
      types: [],
      totals: {
        ...base.totals, lastGbp: 0, priorGbp: 0, deltaGbp: 0, deltaPercent: null, lastUsd: 0, perDayLast: 0, perDayPrior: 0,
      },
      verdict: {
        direction: 'FLAT', percent: null, topType: null, topLabel: null, topDeltaGbp: null, totalDeltaGbp: 0,
        runsAsOften: false, perRunMultiple: null, askLocations: null, skyEval: null,
      },
    }));
    await openCost();
    expect(screen.getByTestId('runs-cost-headline')).toHaveTextContent('No spend recorded in the last 14 days.');
    expect(screen.queryByTestId('runs-cost-body')).toBeNull();
    expect(screen.getByTestId('runs-cost-empty')).toBeInTheDocument();
    expect(screen.getByTestId('runs-cost-total-usd')).toHaveTextContent('$0.00');
  });

  it('does not poll, and Refresh asks again', async () => {
    await openCost();
    expect(fetchRunCost).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(fetchRunCost).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId('runs-updated')).not.toHaveTextContent('refreshes every minute');
  });
});

describe('Timing', () => {
  const openTiming = async (hash = '#manage/runs?view=timing') => {
    window.location.hash = hash;
    renderView();
    return screen.findByTestId('runs-timing-view');
  };

  it('opens on the Afternoon refresh over 60 days by the cycle measure', async () => {
    await openTiming();
    await screen.findByTestId('runs-hist-card');
    expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'AFTERNOON', days: 60, measure: 'ready' });
    expect(screen.getByTestId('runs-timing-type-afternoon')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('runs-timing-range-60')).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows every type\'s served median on its chip', async () => {
    await openTiming();
    await screen.findByTestId('runs-hist-card');
    expect(screen.getByTestId('runs-timing-type-overnight-count')).toHaveTextContent('23m 13s');
    expect(screen.getByTestId('runs-timing-type-ask_ready-count')).toHaveTextContent('1m 46s');
    expect(screen.getByTestId('runs-timing-type-sky_eval-count')).toHaveTextContent('1h 34m');
    expect(screen.queryByTestId('runs-timing-type-other')).toBeNull();
  });

  it('fills the stat strip from the served statistics', async () => {
    await openTiming();
    await screen.findByTestId('runs-hist-card');
    expect(screen.getByTestId('runs-timing-median-value')).toHaveTextContent('19m 40s');
    expect(screen.getByTestId('runs-timing-p90-value')).toHaveTextContent('37m 30s');
    expect(screen.getByTestId('runs-timing-slowest-value')).toHaveTextContent('52m 50s');
    expect(screen.getByTestId('runs-timing-slowest-sub')).toHaveTextContent('Mon 26 Oct');
    expect(screen.getByTestId('runs-timing-fastest-value')).toHaveTextContent('7m 20s');
    expect(screen.getByTestId('runs-timing-count-value')).toHaveTextContent('10');
    expect(screen.getByTestId('runs-timing-count-sub')).toHaveTextContent('last 60 days · start to answers ready');
  });

  it('switches the type, fetching it and writing it to the address (the measure resets)', async () => {
    await openTiming('#manage/runs?view=timing&type=OVERNIGHT&measure=ready');
    await screen.findByTestId('runs-hist-card');
    expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'OVERNIGHT', days: 60, measure: 'ready' });
    fireEvent.click(screen.getByTestId('runs-timing-type-ask_ready'));
    await waitFor(() => expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'ASK_READY', days: 60, measure: 'cycle' }));
    expect(window.location.hash).toBe('#manage/runs?view=timing&type=ASK_READY');
  });

  it('rewrites a range this view does not offer to the one shown', async () => {
    await openTiming('#manage/runs?view=timing&range=14');
    await screen.findByTestId('runs-hist-card');
    expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'AFTERNOON', days: 60, measure: 'ready' });
    expect(window.location.hash).toBe('#manage/runs?view=timing&range=60');
  });

  it('switches the range', async () => {
    await openTiming();
    fireEvent.click(await screen.findByTestId('runs-timing-range-120'));
    await waitFor(() => expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'AFTERNOON', days: 120, measure: 'ready' }));
    expect(window.location.hash).toBe('#manage/runs?view=timing&range=120');
  });

  it('offers the measure for the three cycles only, and asks for it', async () => {
    await openTiming('#manage/runs?view=timing&type=EVENING');
    await screen.findByTestId('runs-hist-card');
    expect(screen.getByTestId('runs-timing-measure')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-timing-measure-cycle'));
    await waitFor(() => expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'EVENING', days: 60, measure: 'cycle' }));
    expect(window.location.hash).toContain('measure=cycle');
    for (const type of ['ASK_READY', 'BRIEFING', 'SKY_EVAL']) {
      fireEvent.click(screen.getByTestId(`runs-timing-type-${type.toLowerCase()}`));
      expect(screen.queryByTestId('runs-timing-measure')).toBeNull();
    }
  });

  it('never asks for "ready" on a type that cannot have it, even when the address says so', async () => {
    await openTiming('#manage/runs?view=timing&type=BRIEFING&measure=ready');
    await waitFor(() => expect(fetchRunTiming).toHaveBeenCalled());
    expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'BRIEFING', days: 60, measure: 'cycle' });
  });

  it('says how many cycles are left out of the "until answers ready" measure', async () => {
    fetchRunTiming.mockResolvedValue(overnightTiming({ measure: 'ready', withoutAnswersLink: 3 }));
    await openTiming('#manage/runs?view=timing&type=OVERNIGHT&measure=ready');
    expect(await screen.findByTestId('runs-timing-unlinked')).toHaveTextContent('3 cycles have no linked answers run');
    expect(screen.getByTestId('runs-timing-count-sub')).toHaveTextContent('start to answers ready');
  });

  describe('the histogram', () => {
    it('draws bars, a smoothed curve and the three markers, staggered', async () => {
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      const bars = screen.getAllByTestId('runs-hist-bar');
      // 10 runs spanning 2730s: a 120s bin, 25 bins at most.
      expect(bars.length).toBeGreaterThan(5);
      expect(bars.length).toBeLessThanOrEqual(25);
      expect(screen.getByTestId('runs-hist-kde')).toBeInTheDocument();
      expect(screen.getByTestId('runs-hist-title')).toHaveTextContent('How long Afternoon refresh takes');
      const ys = ['median', 'p90', 'slowest'].map((key) => {
        const marker = screen.getByTestId(`runs-hist-marker-${key}`);
        return Number(marker.querySelector('text').getAttribute('y'));
      });
      expect(ys).toEqual([...ys].sort((a, b) => a - b));
      expect(new Set(ys).size).toBe(3);
      expect(screen.getByTestId('runs-hist-marker-median')).toHaveTextContent('Median 19m 40s');
      expect(screen.getByTestId('runs-hist-marker-p90')).toHaveTextContent('9 in 10 37m 30s');
      expect(screen.getByTestId('runs-hist-marker-slowest')).toHaveTextContent('Slowest 52m 50s');
    });

    it('flips the slowest label to the left of its line near the right edge', async () => {
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      expect(screen.getByTestId('runs-hist-marker-slowest').querySelector('text')).toHaveAttribute('text-anchor', 'end');
      expect(screen.getByTestId('runs-hist-marker-median').querySelector('text')).toHaveAttribute('text-anchor', 'start');
    });

    it('shows the skew note only when the server says the shape is skewed', async () => {
      await openTiming();
      expect(await screen.findByTestId('runs-hist-skew')).toHaveTextContent('The shape leans right');
      fetchRunTiming.mockResolvedValue(timingResponse({ stats: timingStats({ skewed: false }) }));
      fireEvent.click(screen.getByTestId('runs-timing-range-30'));
      await waitFor(() => expect(screen.queryByTestId('runs-hist-skew')).toBeNull());
    });

    it('with no runs says so and draws nothing', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({
        runs: [],
        stats: timingStats({
          count: 0, medianSeconds: null, p90Seconds: null, slowest: null, fastest: null, firstHalfMedianSeconds: null,
          secondHalfMedianSeconds: null, changePercent: null, skewed: false,
        }),
      }));
      await openTiming();
      expect(await screen.findByTestId('runs-hist-empty')).toHaveTextContent('No runs measured in this range.');
      expect(screen.queryByTestId('runs-hist-svg')).toBeNull();
      expect(screen.getByTestId('runs-scatter-empty')).toBeInTheDocument();
      expect(screen.getByTestId('runs-timing-median-value')).toHaveTextContent('—');
      expect(screen.getByTestId('runs-timing-count-value')).toHaveTextContent('0');
      expect(screen.getByTestId('runs-planner-result')).toHaveTextContent('No runs measured in this range to replay.');
    });

    it('with one run draws a bar and the median but no curve and no 9-in-10 line', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({
        runs: timingRuns([900]),
        stats: timingStats({
          count: 1, medianSeconds: 900, p90Seconds: null, slowest: { seconds: 900, date: '2026-11-01' },
          fastest: { seconds: 900, date: '2026-11-01' }, firstHalfMedianSeconds: null, secondHalfMedianSeconds: 900,
          changePercent: null, skewed: false,
        }),
      }));
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      expect(screen.getAllByTestId('runs-hist-bar')).toHaveLength(1);
      expect(screen.queryByTestId('runs-hist-kde')).toBeNull();
      expect(screen.queryByTestId('runs-hist-marker-p90')).toBeNull();
      expect(screen.getByTestId('runs-hist-marker-median')).toBeInTheDocument();
      expect(screen.getByTestId('runs-timing-p90-value')).toHaveTextContent('—');
      expect(screen.getByTestId('runs-planner-suggestion-text')).toHaveTextContent('Not enough runs yet to suggest a start.');
      expect(screen.queryByTestId('runs-scatter-trend')).toBeNull();
    });
  });

  describe('chart geometry', () => {
    it('never draws more than 24 bins even when 3s bins would make 25', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ runs: timingRuns([1, 73]) }));
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      expect(screen.getAllByTestId('runs-hist-bar').length).toBeLessThanOrEqual(24);
    });

    it('scales the y axis to the curve when it peaks above the tallest bar', async () => {
      // Seven runs straddling a bin boundary: two bars of 3 and 4, the curve peaks above 4.
      fetchRunTiming.mockResolvedValue(timingResponse({ runs: timingRuns([114, 117, 119, 121, 123, 125, 127]) }));
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      const kdeTop = Math.min(...screen.getByTestId('runs-hist-kde').getAttribute('points').split(' ').map((p) => Number(p.split(',')[1])));
      expect(kdeTop).toBeGreaterThanOrEqual(44);
    });

    it('lets the last stat cell span both columns when the strip wraps to two', async () => {
      await openTiming();
      await screen.findByTestId('runs-hist-card');
      expect(screen.getByTestId('runs-timing-count').className).toContain('@max-[700px]:col-span-2');
    });

    it('keeps the type chips on one line', async () => {
      await openTiming();
      expect(screen.getByRole('group', { name: 'Run type' }).className).toContain(' flex-wrap');
      expect(screen.getByTestId('runs-timing-type-evening').className).toContain('whitespace-nowrap');
    });

    it('adds a 10px transparent hit circle per dot on a phone, carrying the press', async () => {
      useIsMobile.mockReturnValue(true);
      await openTiming();
      const hit = await screen.findByTestId('runs-scatter-dot-cycle-109');
      expect(hit.getAttribute('r')).toBe('10');
      expect(hit.getAttribute('class')).toContain('fill-transparent');
      fireEvent.click(hit);
      await screen.findByTestId('runs-day-view');
      expect(fetchRunDay).toHaveBeenCalledWith('2026-11-01');
    });
  });

  describe('the sunset planner (Afternoon refresh)', () => {
    it('opens on the served trigger and the 30-minute buffer', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      expect(screen.getByTestId('runs-planner-offset')).toHaveValue('250');
      expect(screen.getByTestId('runs-planner-offset-readout')).toHaveTextContent('4h 10m');
      expect(screen.getByTestId('runs-planner-buffer-30')).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('runs-planner-meta')).toHaveTextContent('replays the last 10 runs with a different start');
      expect(screen.getAllByTestId('runs-planner-dot')).toHaveLength(10);
    });

    it('replays the runs and words today\'s times from the served sunset', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      // Started 4h10m before sunset, every run is ready well before sunset - 30m.
      expect(textOf('runs-planner-result')).toBe(
        '10 of 10 runs (100%) would have had answers ready 30 min before sunset. '
        + 'Today that means starting at 12:39 (sunset 16:49), with answers around 12:58 typically and by 13:16 9 times in 10.',
      );
      expect(screen.getByTestId('runs-planner-result')).toHaveAttribute('data-tone', 'border-verdict-go');
    });

    it('words the planner from the cycle measure when it is the cycle alone', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ measure: 'cycle' }));
      await openTiming('#manage/runs?view=timing&measure=cycle');
      await screen.findByTestId('runs-planner');
      expect(textOf('runs-planner-result')).toContain('would have finished 30 min before sunset');
      expect(textOf('runs-planner-result')).toContain('with finishing around 12:58');
      expect(textOf('runs-planner')).toContain('Refresh finishes by');
      expect(textOf('runs-planner-result')).not.toContain('answers');
    });

    it('is accessible beyond colour: slider text, a live result, hollow rings for late runs', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      expect(screen.getByTestId('runs-planner-offset')).toHaveAttribute('aria-valuetext', '4h 10m before sunset');
      expect(screen.getByTestId('runs-planner-result')).toHaveAttribute('aria-live', 'polite');
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '35' } });
      expect(screen.getByTestId('runs-planner-offset')).toHaveAttribute('aria-valuetext', '35m before sunset');
      const late = screen.getAllByTestId('runs-planner-dot').find((d) => d.dataset.inTime === 'false');
      expect(late.getAttribute('class')).toContain('fill-transparent');
      expect(late.getAttribute('stroke-width')).toBe('1.5');
    });

    it('keeps the Start and Needed by labels apart when their lines are close', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      const labels = () => ({
        start: screen.getByText('Start', { selector: 'text' }),
        needed: screen.getByText('Needed by', { selector: 'text' }),
      });
      expect(labels().start).toHaveAttribute('text-anchor', 'middle');
      expect(labels().needed).toHaveAttribute('text-anchor', 'end');
      // 35 minutes before sunset with answers needed 30 before: the lines are a few pixels apart.
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '35' } });
      expect(labels().start).toHaveAttribute('text-anchor', 'end');
      expect(labels().needed).toHaveAttribute('text-anchor', 'start');
    });

    it('says the current trigger is outside the slider range, and resets to the real offset', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ trigger: { solarEvent: 'SUNSET', offsetMinutes: 600 } }));
      await openTiming();
      await screen.findByTestId('runs-planner');
      expect(screen.getByTestId('runs-planner-outside')).toHaveTextContent('10h before sunset, is outside the slider');
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '100' } });
      expect(screen.getByTestId('runs-planner-reset')).toHaveTextContent('Reset to current (10h, outside the slider range)');
      fireEvent.click(screen.getByTestId('runs-planner-reset'));
      expect(screen.getByTestId('runs-planner-offset')).toHaveValue('300');
    });

    it('re-counts when the slider moves, and goes coral when under 75% would be in time', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '55' } });
      expect(screen.getByTestId('runs-planner-offset-readout')).toHaveTextContent('55m');
      // Ready at -55 + minutes: in time (<= -30) for runs up to 25 minutes (1500s): 8 of the 10.
      expect(textOf('runs-planner-result')).toContain('8 of 10 runs (80%)');
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '35' } });
      expect(screen.getByTestId('runs-planner-result')).toHaveAttribute('data-tone', 'border-plex-coral');
      expect(screen.getAllByTestId('runs-planner-dot').filter((d) => d.dataset.inTime === 'false').length).toBeGreaterThan(0);
    });

    it('re-counts when the buffer changes ("sunset" needs the answers by sunset itself)', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      fireEvent.click(screen.getByTestId('runs-planner-buffer-0'));
      expect(screen.getByTestId('runs-planner-buffer-0')).toHaveAttribute('aria-pressed', 'true');
      expect(textOf('runs-planner-result')).toContain('would have had answers ready by sunset.');
      fireEvent.click(screen.getByTestId('runs-planner-buffer-60'));
      expect(textOf('runs-planner-result')).toContain('answers ready 60 min before sunset');
    });

    it('suggests the latest start that lands 9 in 10 (ceil5 of buffer + the served p90), Try it applies it, Reset undoes it', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      // 30 + 37.5 minutes, rounded up to 70.
      expect(screen.getByTestId('runs-planner-suggestion-text')).toHaveTextContent('Latest start that still lands 9 in 10: 1h 10m before sunset');
      expect(screen.queryByTestId('runs-planner-reset')).toBeNull();
      fireEvent.click(screen.getByTestId('runs-planner-try'));
      expect(screen.getByTestId('runs-planner-offset')).toHaveValue('70');
      expect(screen.queryByTestId('runs-planner-try')).toBeNull();
      expect(screen.getByTestId('runs-planner-reset')).toHaveTextContent('Reset to current (4h 10m)');
      fireEvent.click(screen.getByTestId('runs-planner-reset'));
      expect(screen.getByTestId('runs-planner-offset')).toHaveValue('250');
      expect(screen.getByTestId('runs-planner-try')).toBeInTheDocument();
    });

    it('opens the Scheduler on the job\'s panel with no prefill, and saves nothing', async () => {
      await openTiming();
      await screen.findByTestId('runs-planner');
      fireEvent.change(screen.getByTestId('runs-planner-offset'), { target: { value: '120' } });
      expect(screen.getByTestId('runs-planner-scheduler')).toHaveAttribute('href', '#manage/scheduler/intraday_forecast_refresh');
      expect(screen.getByTestId('runs-planner-scheduler')).toHaveTextContent('Open in Scheduler');
    });

    it('puts an out-of-range trigger on the slider', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ trigger: { solarEvent: 'SUNSET', offsetMinutes: 600 } }));
      await openTiming();
      await screen.findByTestId('runs-planner');
      expect(screen.getByTestId('runs-planner-offset')).toHaveValue('300');
      expect(screen.getByTestId('runs-planner-offset-readout')).toHaveTextContent('5h');
      expect(screen.queryByTestId('runs-planner-reset')).toBeNull();
    });

    it('hides the planner when the Afternoon job is on a fixed time, and says so', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ trigger: null, todaySunset: null }));
      await openTiming();
      expect(await screen.findByTestId('runs-ready-fixed')).toHaveTextContent(
        'Afternoon refresh has no sun-relative trigger, so there is nothing to plan around.',
      );
      expect(screen.queryByTestId('runs-planner')).toBeNull();
      expect(screen.getByTestId('runs-ready-scheduler')).toHaveAttribute('href', '#manage/scheduler/intraday_forecast_refresh');
    });

    it('is not shown for another type', async () => {
      fetchRunTiming.mockResolvedValue(overnightTiming());
      await openTiming('#manage/runs?view=timing&type=OVERNIGHT');
      await screen.findByTestId('runs-ready-card');
      expect(screen.queryByTestId('runs-planner')).toBeNull();
    });
  });

  describe('when results are ready (the fixed-time cycles) and the other types', () => {
    it('lists the Overnight cycle\'s start, typical, 9-in-10, worst and sunrise slack from the served instants', async () => {
      fetchRunTiming.mockResolvedValue(overnightTiming());
      await openTiming('#manage/runs?view=timing&type=OVERNIGHT');
      await screen.findByTestId('runs-ready-card');
      expect(screen.getByTestId('runs-ready-trigger')).toHaveTextContent('Fixed time · 02:55');
      // The labels and values are separate cells, so the text runs together.
      expect(textOf('runs-ready-facts')).toBe(
        'Starts02:55Typicalready 03:189 in 10 nightsready by 03:28Worstready 03:52Sunrise06:50 today · 3h 22m of slack at the 9-in-10 line',
      );
      expect(screen.getByTestId('runs-ready-note')).toHaveTextContent('fixed time');
      expect(screen.queryByTestId('runs-ready-basis')).toBeNull();
    });

    it('says so when the times are based on the cycle\'s own length', async () => {
      fetchRunTiming.mockResolvedValue(overnightTiming({ readyBasis: 'cycle' }));
      await openTiming('#manage/runs?view=timing&type=OVERNIGHT');
      expect(await screen.findByTestId('runs-ready-basis')).toHaveTextContent('No run has a linked Ask ready answers run yet');
    });

    it('says the slack is short when the 9-in-10 line is after sunrise', async () => {
      fetchRunTiming.mockResolvedValue(overnightTiming({ slackAtP90Minutes: -15 }));
      await openTiming('#manage/runs?view=timing&type=OVERNIGHT');
      expect(await screen.findByTestId('runs-ready-sunrise')).toHaveTextContent('15m short at the 9-in-10 line');
    });

    it('has no times to show when the cycle does not fire today', async () => {
      fetchRunTiming.mockResolvedValue(overnightTiming({
        startsAt: null, typicalReadyAt: null, p90ReadyAt: null, worstReadyAt: null, readyBasis: null, slackAtP90Minutes: null,
      }));
      await openTiming('#manage/runs?view=timing&type=OVERNIGHT');
      expect(await screen.findByTestId('runs-ready-nofire')).toHaveTextContent('No Overnight cycle is scheduled today');
      expect(screen.queryByTestId('runs-ready-facts')).toBeNull();
    });

    it('explains Ask ready answers and the briefing run after a cycle, and points to "Until answers ready"', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ type: 'ASK_READY', typeLabel: 'Ask ready answers', trigger: null, todaySunset: null }));
      await openTiming('#manage/runs?view=timing&type=ASK_READY');
      expect(await screen.findByTestId('runs-ready-explainer')).toHaveTextContent(
        'Ask ready answers runs after each cycle finishes, so its finish time depends on the cycle before it. Pick a cycle above and use Until answers ready',
      );
    });

    it('says the Sky eval is not part of a cycle', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ type: 'SKY_EVAL', typeLabel: 'Sky eval', trigger: null, todaySunset: null }));
      await openTiming('#manage/runs?view=timing&type=SKY_EVAL');
      expect(await screen.findByTestId('runs-ready-explainer')).toHaveTextContent('not part of any cycle');
    });
  });

  describe('is it getting slower?', () => {
    it('draws a dot per run, amber above the served 9-in-10, with a rolling median line', async () => {
      await openTiming();
      await screen.findByTestId('runs-scatter-card');
      const dots = screen.getAllByTestId(/^runs-scatter-dot-/);
      expect(dots).toHaveLength(10);
      // p90 is 2250s: only the 3170s run is above it (the dot at exactly the p90 is not).
      expect(dots.filter((d) => d.dataset.slow === 'true')).toHaveLength(1);
      expect(screen.getByTestId('runs-scatter-dot-cycle-109').dataset.slow).toBe('true');
      expect(screen.getByTestId('runs-scatter-rolling').getAttribute('points').split(' ')).toHaveLength(10);
    });

    it('states the served half medians and their change', async () => {
      await openTiming();
      expect(await screen.findByTestId('runs-scatter-trend')).toHaveTextContent('Median 18m 40s in the first 30 days, 19m 50s in the last 30 (+6%).');
    });

    it('adds the locations note for Ask ready answers', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ type: 'ASK_READY', typeLabel: 'Ask ready answers', trigger: null, todaySunset: null }));
      await openTiming('#manage/runs?view=timing&type=ASK_READY');
      expect(await screen.findByTestId('runs-scatter-trend')).toHaveTextContent('Ask ready answers scales with the number of locations it covers.');
    });

    it('shows a tooltip on hover and opens the run\'s day on a click', async () => {
      await openTiming();
      const dot = await screen.findByTestId('runs-scatter-dot-cycle-109');
      fireEvent.mouseEnter(dot);
      expect(screen.getByTestId('runs-scatter-tooltip')).toHaveTextContent('Sun 1 Nov 12:00');
      expect(screen.getByTestId('runs-scatter-tooltip')).toHaveTextContent('52m 50s · slower than 9 in 10');
      fireEvent.click(dot);
      await screen.findByTestId('runs-day-view');
      expect(fetchRunDay).toHaveBeenCalledWith('2026-11-01');
      expect(window.location.hash).toBe('#manage/runs?view=day&date=2026-11-01');
    });

    it('treats a run today as "today" (no date in the address)', async () => {
      fetchRunTiming.mockResolvedValue(timingResponse({ runs: timingRuns([900, 1000], '2026-11-02') }));
      await openTiming();
      fireEvent.click(await screen.findByTestId('runs-scatter-dot-cycle-101'));
      await screen.findByTestId('runs-day-view');
      expect(window.location.hash).toBe('#manage/runs?view=day');
    });
  });

  it('shows a loading skeleton, then an error with Retry', async () => {
    fetchRunTiming.mockRejectedValueOnce(new Error('Timing is down'));
    window.location.hash = '#manage/runs?view=timing';
    renderView();
    expect(screen.getByTestId('runs-timing-loading')).toBeInTheDocument();
    expect(screen.getByTestId('runs-timing-controls')).toBeInTheDocument();
    expect(await screen.findByTestId('runs-timing-error')).toHaveTextContent('Timing is down');
    fireEvent.click(screen.getByTestId('runs-timing-error-retry'));
    expect(await screen.findByTestId('runs-hist-card')).toBeInTheDocument();
  });

  it('does not show one type\'s answer under another while it loads', async () => {
    await openTiming();
    await screen.findByTestId('runs-hist-card');
    fetchRunTiming.mockReturnValue(new Promise(() => {}));
    fireEvent.click(screen.getByTestId('runs-timing-type-evening'));
    expect(screen.getByTestId('runs-timing-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('runs-hist-card')).toBeNull();
  });
});

describe('the views together', () => {
  it('clears one view\'s parameters when the view changes, and remembers the last view', async () => {
    window.location.hash = '#manage/runs?view=cost&range=60&type=SKY_EVAL';
    renderView();
    await screen.findByTestId('runs-cost-view');
    fireEvent.click(screen.getByTestId('runs-view-tab-timing'));
    await screen.findByTestId('runs-hist-card');
    expect(window.location.hash).toBe('#manage/runs?view=timing');
    // 60 and SKY_EVAL belonged to Cost: Timing opens on its own defaults.
    expect(fetchRunTiming).toHaveBeenCalledWith({ type: 'AFTERNOON', days: 60, measure: 'ready' });
    expect(window.localStorage.getItem('photocast.runs.view')).toBe('timing');
  });

  it('follows the address when Back moves it between views', async () => {
    window.location.hash = '#manage/runs?view=cost';
    renderView();
    await screen.findByTestId('runs-cost-view');
    window.location.hash = '#manage/runs?view=timing&type=EVENING';
    window.dispatchEvent(new HashChangeEvent('hashchange'));
    await screen.findByTestId('runs-hist-card');
    expect(screen.getByTestId('runs-timing-type-evening')).toHaveAttribute('aria-pressed', 'true');
  });

  it('keeps the live banner above both views', async () => {
    fetchRuns.mockResolvedValue(runsPage([], { runningCount: 1, liveCycleId: 412 }));
    window.location.hash = '#manage/runs?view=cost';
    renderView();
    await screen.findByTestId('runs-cost-view');
    expect(await screen.findByTestId('runs-live-strip')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-view-tab-timing'));
    expect(await screen.findByTestId('runs-live-strip')).toBeInTheDocument();
  });

  it('shows the timing on a phone without a horizontal page scroll (one column of cards)', async () => {
    useIsMobile.mockReturnValue(true);
    window.location.hash = '#manage/runs?view=timing';
    renderView();
    const lower = await screen.findByTestId('runs-timing-lower');
    expect(within(lower).getByTestId('runs-planner')).toBeInTheDocument();
    expect(lower.className).toContain('@max-[1000px]:grid-cols-1');
  });
});
