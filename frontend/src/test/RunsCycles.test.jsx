import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, renderHook, screen, fireEvent, act, waitFor, within } from '@testing-library/react';
import RunsView from '../components/runs/RunsView.jsx';
import useCycleDetail from '../hooks/useCycleDetail.js';
import { setRewind } from '../utils/rewind.js';
import {
  cycleBatch, cycleDetail, cyclePhase, cycleRun, cycleItem, jobDetail, jobItem, runsPage,
} from './runsFixtures.js';

// The R2b cycle surfaces end to end: the live strip, the cycle panel and its sections, drilling into a batch and back,
// the shared cycle fetch and its poll, and the live count the view reports. The network is mocked at the API modules.

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
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null, loading: false }) }));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import { fetchRuns, fetchJobRun } from '../api/runsApi.js';
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

const LIVE_PAGE = () => runsPage([cycleItem(), jobItem({ id: 9001 })], { runningCount: 1, liveCycleId: 412 });

/** A finished intraday cycle with every phase run. */
const finishedDetail = (over = {}) => cycleDetail({
  run: cycleRun({
    status: 'COMPLETED', currentPhase: null, waitingOn: null, completedAt: '2026-10-31T16:58:00Z', durationSeconds: 6480,
  }),
  phases: [
    cyclePhase({ phase: 'STABILITY_RECLASSIFY', sequenceOrder: 1, detail: '63 stable, 10 re-classified', durationSeconds: 121 }),
    cyclePhase({ phase: 'FORECAST_BATCH_SUBMIT', sequenceOrder: 2 }),
    cyclePhase({
      phase: 'FORECAST_BATCH_WAIT', sequenceOrder: 3, detail: 'all 4 batches terminal', durationSeconds: 6540,
    }),
    cyclePhase({ phase: 'RETRY_FAILED', sequenceOrder: 4, detail: null, durationSeconds: 0 }),
    cyclePhase({
      phase: 'BRIEFING', sequenceOrder: 5, detail: 'built', durationSeconds: 372, startedAt: '2026-10-31T16:52:00Z',
    }),
  ],
  batches: [cycleBatch(), cycleBatch({ id: 8, jobRunId: 9877, anthropicBatchId: 'msgbatch_01JBXK9K9K9K9K9K9K9K' })],
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/runs';
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
  fetchRuns.mockResolvedValue(runsPage([cycleItem(), jobItem({ id: 9001 })]));
  fetchPipelineRunDetail.mockResolvedValue(cycleDetail());
  fetchJobRun.mockResolvedValue(jobDetail(
    { id: 9876, runType: 'SCHEDULED_BATCH' },
    { group: 'BATCHES', name: 'Scheduled batch', status: 'DONE', batch: { anthropicBatchId: 'msgbatch_01JBXKQ7Q7Q7Q7Q7Q7Q7' } },
  ));
  getApiCalls.mockResolvedValue({ data: [] });
  getBatchSummary.mockRejectedValue(new Error('not a batch'));
  getDispositionBreakdown.mockResolvedValue({ data: { jobRunId: 0, totalCount: 0, countsByDisposition: {}, entries: [] } });
  getRegions.mockResolvedValue([]);
  fetchLocations.mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  setRewind(null);
});

describe('Runs: the live strip', () => {
  it('is absent when the server names no running cycle', async () => {
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    expect(screen.queryByTestId('runs-live-strip')).toBeNull();
    expect(fetchPipelineRunDetail).not.toHaveBeenCalled();
  });

  it('shows the cycle running, its start and the wait phase with the batches it waits on', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T16:38:00Z'));
    renderView();
    expect(await screen.findByTestId('runs-live-title')).toHaveTextContent('Afternoon refresh is running');
    expect(screen.getByTestId('runs-live-meta')).toHaveTextContent('Cycle #412 · started 15:00');
    expect(screen.getByTestId('runs-live-line'))
      .toHaveTextContent('Wait for completion · waiting on 2 of 4 batches · 1h 38m so far');
  });

  it('draws the five-segment bar for an intraday cycle: done go, the current one tide, the rest not yet', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    renderView();
    const bar = await screen.findByTestId('runs-live-bar');
    expect(bar).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('runs-live-seg-STABILITY_RECLASSIFY').className).toContain('bg-plex-ok');
    expect(screen.getByTestId('runs-live-seg-FORECAST_BATCH_WAIT').className).toContain('from-tide');
    expect(screen.getByTestId('runs-live-seg-RETRY_FAILED').className).toContain('bg-plex-border');
    expect(screen.getByTestId('runs-live-seg-BRIEFING').className).toContain('bg-plex-border');
    expect(bar.children).toHaveLength(5);
  });

  it('leaves the waiting clause out outside the wait phase', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    fetchPipelineRunDetail.mockResolvedValue(cycleDetail({
      run: cycleRun({ currentPhase: 'FORECAST_BATCH_SUBMIT' }),
      phases: [cyclePhase({ status: 'RUNNING', completedAt: null, durationSeconds: null })],
    }));
    renderView();
    const line = await screen.findByTestId('runs-live-line');
    expect(line).toHaveTextContent('Submit batches');
    expect(line).not.toHaveTextContent('waiting on');
  });

  it('stops the pulse for reduced motion', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    renderView();
    expect((await screen.findByTestId('runs-live-pulse')).className).toContain('motion-reduce:animate-none');
  });

  it('Open selects the cycle: its address and its panel', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    renderView();
    fireEvent.click(await screen.findByTestId('runs-live-open'));
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(await screen.findByTestId('cycle-panel-title')).toHaveTextContent('Afternoon refresh');
  });

  it('shares ONE fetch with the open cycle panel', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    renderView();
    fireEvent.click(await screen.findByTestId('runs-live-open'));
    await screen.findByTestId('cycle-panel-title');
    await act(async () => {});
    expect(screen.getByTestId('runs-live-strip')).toBeInTheDocument();
    expect(fetchPipelineRunDetail.mock.calls.filter(([id]) => id === 412)).toHaveLength(1);
  });

  it('disappears when the cycle is no longer running', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    fetchPipelineRunDetail.mockResolvedValue(finishedDetail());
    renderView();
    await screen.findByTestId('runs-row-cycle-412');
    await act(async () => {});
    expect(screen.queryByTestId('runs-live-strip')).toBeNull();
  });

  it('does not follow a set rewind: the elapsed time is the real clock\'s', async () => {
    fetchRuns.mockResolvedValue(LIVE_PAGE());
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T16:38:00Z'));
    setRewind('2026-01-15T09:00:00Z');
    renderView();
    expect(await screen.findByTestId('runs-live-line')).toHaveTextContent('1h 38m so far');
  });
});

describe('Runs: the cycle panel', () => {
  async function openCycle(detail = cycleDetail(), items = [cycleItem()]) {
    fetchPipelineRunDetail.mockResolvedValue(detail);
    fetchRuns.mockResolvedValue(runsPage(items));
    window.location.hash = '#manage/runs/cycle/412';
    renderView();
    return screen.findByTestId('cycle-panel');
  }

  it('opens from the hash with the name, a meta line and a labelled panel that is never a dialog', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T16:38:00Z'));
    await openCycle();
    expect(screen.getByTestId('cycle-panel-title')).toHaveTextContent('Afternoon refresh');
    expect(screen.getByTestId('cycle-panel-meta'))
      .toHaveTextContent('Cycle #412 · intraday · Running · started 15:00 (1h 38m ago)');
    expect(screen.getByRole('complementary', { name: 'Cycle 412' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.querySelector('[aria-modal="true"]')).toBeNull();
  });

  it('names the cycle by its type when its row is not on the loaded page', async () => {
    await openCycle(cycleDetail(), []);
    expect(screen.getByTestId('cycle-panel-title')).toHaveTextContent('Afternoon refresh');
  });

  it('shows the waiting box with the still-submitted ids, the submit time and when it was checked', async () => {
    await openCycle();
    const box = screen.getByTestId('pipeline-run-detail-waiting');
    expect(box).toHaveTextContent('Waiting on 2 of 4 batches');
    expect(box).toHaveTextContent('msgbatch_01JBX…3F and msgbatch_01JBX…2D, submitted 15:03.');
    expect(box).toHaveTextContent('Checked just now.');
  });

  it('draws no waiting box once the cycle has finished', async () => {
    await openCycle(finishedDetail());
    expect(screen.queryByTestId('pipeline-run-detail-waiting')).toBeNull();
  });

  it.each([
    ['DEGRADED', 'pipeline-run-detail-degraded', 'pipeline-run-detail-failure', 'Degraded'],
    ['FAILED', 'pipeline-run-detail-failure', 'pipeline-run-detail-degraded', 'Failed'],
  ])('shows a %s cycle\'s failure reason in its own box and not the other', async (status, shown, hidden, word) => {
    await openCycle(finishedDetail({
      run: cycleRun({
        status, currentPhase: null, waitingOn: null, completedAt: '2026-10-31T16:58:00Z', durationSeconds: 300,
        failureReason: '3 of 3 forecast batch submissions failed (510 requests not submitted)',
      }),
    }));
    expect(screen.getByTestId(shown)).toHaveTextContent(word);
    expect(screen.getByTestId(shown)).toHaveTextContent('3 of 3 forecast batch submissions failed');
    expect(screen.queryByTestId(hidden)).toBeNull();
  });

  it.each(['RUNNING', 'COMPLETED'])('a %s cycle never shows a failure box', async (status) => {
    await openCycle(cycleDetail({ run: cycleRun({ status, failureReason: null }) }));
    expect(screen.queryByTestId('pipeline-run-detail-failure')).toBeNull();
    expect(screen.queryByTestId('pipeline-run-detail-degraded')).toBeNull();
  });

  describe('phases', () => {
    it('lists them in sequenceOrder however they arrive, with the labels', async () => {
      const detail = finishedDetail();
      await openCycle({ ...detail, phases: [...detail.phases].reverse() });
      const order = within(screen.getByTestId('cycle-phases')).getAllByRole('listitem')
        .map((li) => li.dataset.testid);
      expect(order).toEqual([
        'cycle-phase-STABILITY_RECLASSIFY', 'cycle-phase-FORECAST_BATCH_SUBMIT', 'cycle-phase-FORECAST_BATCH_WAIT',
        'cycle-phase-RETRY_FAILED', 'cycle-phase-BRIEFING',
      ]);
      expect(screen.getByTestId('cycle-phase-STABILITY_RECLASSIFY')).toHaveTextContent('Re-classify (cost-gate)');
      expect(screen.getByTestId('cycle-phase-BRIEFING')).toHaveTextContent('Briefing (gloss + best-bet)');
    });

    it('reads an unknown phase as its own key', async () => {
      const detail = finishedDetail();
      await openCycle({
        ...detail,
        phases: [...detail.phases, cyclePhase({ phase: 'FUTURE_PHASE', sequenceOrder: 6, detail: null })],
      });
      expect(screen.getByTestId('cycle-phase-FUTURE_PHASE')).toHaveTextContent('FUTURE_PHASE');
    });

    it('marks done, current, and not-yet phases, and says what the not-yet ones run after', async () => {
      await openCycle();
      expect(screen.getByTestId('cycle-phase-STABILITY_RECLASSIFY')).toHaveAttribute('data-state', 'completed');
      expect(screen.getByTestId('cycle-phase-FORECAST_BATCH_WAIT')).toHaveAttribute('data-state', 'running');
      const retry = screen.getByTestId('cycle-phase-RETRY_FAILED');
      expect(retry).toHaveAttribute('data-state', 'pending');
      expect(retry).toHaveTextContent('Runs after Wait');
      expect(screen.getByTestId('cycle-phase-BRIEFING')).toHaveTextContent('Runs after Wait');
    });

    it('shows each started phase\'s clock time and detail, and its duration', async () => {
      await openCycle();
      expect(screen.getByTestId('cycle-phase-detail-FORECAST_BATCH_SUBMIT')).toHaveTextContent('15:02 · 4 batches, 212 requests');
      expect(screen.getByTestId('cycle-phase-duration-FORECAST_BATCH_SUBMIT')).toHaveTextContent('1m');
      expect(screen.getByTestId('cycle-phase-duration-STABILITY_RECLASSIFY')).toHaveTextContent('2m 4s');
    });

    it('shows the running phase\'s elapsed time "so far", in tide', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-10-31T16:38:00Z'));
      await openCycle();
      const elapsed = screen.getByTestId('cycle-phase-elapsed-FORECAST_BATCH_WAIT');
      expect(elapsed).toHaveTextContent('1h 35m');
      expect(elapsed).toHaveTextContent('so far');
      expect(elapsed.className).toContain('text-tide');
    });

    it('meta reads "{done} of {n}" while running and the total duration when finished', async () => {
      await openCycle();
      expect(screen.getByTestId('cycle-phases-meta')).toHaveTextContent('2 of 5');
    });

    it('meta is the total duration once the cycle has finished', async () => {
      await openCycle(finishedDetail());
      expect(screen.getByTestId('cycle-phases-meta')).toHaveTextContent('1h 48m');
      expect(screen.queryByText(/Runs after/)).toBeNull();
    });

    it('says "Nothing to retry" for a completed retry phase with no detail', async () => {
      await openCycle(finishedDetail());
      expect(screen.getByTestId('cycle-phase-detail-RETRY_FAILED')).toHaveTextContent('Nothing to retry');
    });

    it('does not invent a re-classify phase for a nightly cycle that has none', async () => {
      await openCycle(cycleDetail({
        run: cycleRun({ cycleType: 'NIGHTLY' }),
        phases: [cyclePhase({ sequenceOrder: 1 })],
      }));
      expect(screen.queryByTestId('cycle-phase-STABILITY_RECLASSIFY')).toBeNull();
    });
  });

  describe('the comparison', () => {
    const PICK_A = {
      headline: 'Coast tonight', region: 'North Yorkshire Coast', eventDate: '2026-10-31', eventType: 'sunset',
      claudeAverageRating: 4.2,
    };
    const NIGHTLY_A = {
      headline: 'Hills tonight', region: 'Northumberland', eventDate: '2026-10-31', eventType: 'sunset',
      claudeAverageRating: 3.1,
    };

    it('is absent without a comparison', async () => {
      await openCycle();
      expect(screen.queryByTestId('cross-run-comparison')).toBeNull();
    });

    it('says Plan changed, shows the intraday headline, Was {nightly} struck through and the dimensions capitalised', async () => {
      await openCycle(cycleDetail({
        comparison: {
          baselineRunId: 408,
          baselineTriggerTime: '2026-10-31T01:08:00Z',
          diffs: [
            { rank: 1, changed: true, changedDimensions: ['REGION', 'RATING'], intraday: PICK_A, nightly: NIGHTLY_A },
            {
              rank: 2, changed: false, changedDimensions: [],
              intraday: { region: 'Lake District', eventDate: '2026-11-01', eventType: 'sunrise', claudeAverageRating: 3.8 },
              nightly: { region: 'Lake District', eventDate: '2026-11-01', eventType: 'sunrise', claudeAverageRating: 3.8 },
            },
          ],
        },
      }));
      expect(screen.getByTestId('cross-run-verdict')).toHaveTextContent('Plan changed');
      expect(screen.getByTestId('cross-run-pick-1')).toHaveTextContent('Coast tonight');
      const was = screen.getByTestId('cross-run-was-1');
      expect(was).toHaveTextContent('Was Hills tonight');
      expect(was.className).toContain('line-through');
      expect(screen.getByTestId('cross-run-changed-1')).toHaveTextContent('Region, rating');
      expect(screen.getByTestId('cross-run-diff-1')).toHaveTextContent('Plan A');
      expect(screen.getByTestId('cross-run-diff-2')).toHaveTextContent('Plan B');
      expect(screen.getByTestId('cross-run-pick-2')).toHaveTextContent('Lake District · Sunrise · Sun 1 Nov · ★3.8');
      expect(screen.queryByTestId('cross-run-was-2')).toBeNull();
      expect(screen.getByTestId('cross-run-baseline')).toHaveTextContent('Baseline: nightly cycle #408 · 01:08');
    });

    it('says No change when no rank changed', async () => {
      await openCycle(cycleDetail({
        comparison: {
          baselineRunId: 408, baselineTriggerTime: '2026-10-31T01:08:00Z',
          diffs: [{ rank: 1, changed: false, changedDimensions: [], intraday: PICK_A, nightly: PICK_A }],
        },
      }));
      expect(screen.getByTestId('cross-run-verdict')).toHaveTextContent('No change');
    });

    it('draws a rank beyond 2 as "Plan 3" and a none pick as the stay-home wording', async () => {
      await openCycle(cycleDetail({
        comparison: {
          baselineRunId: 408, baselineTriggerTime: '2026-10-31T01:08:00Z',
          diffs: [
            { rank: 3, changed: true, changedDimensions: ['PRESENCE'], intraday: null, nightly: NIGHTLY_A },
          ],
        },
      }));
      expect(screen.getByTestId('cross-run-diff-3')).toHaveTextContent('Plan 3');
      expect(screen.getByTestId('cross-run-pick-3')).toHaveTextContent('Stay home');
      expect(screen.getByTestId('cross-run-changed-3')).toHaveTextContent('Added/dropped');
    });

    it('draws a changed pick with no named dimension as "Changed"', async () => {
      await openCycle(cycleDetail({
        comparison: {
          baselineRunId: 408, baselineTriggerTime: '2026-10-31T01:08:00Z',
          diffs: [{ rank: 1, changed: true, changedDimensions: [], intraday: PICK_A, nightly: NIGHTLY_A }],
        },
      }));
      expect(screen.getByTestId('cross-run-changed-1')).toHaveTextContent('Changed');
    });
  });

  describe('batches', () => {
    it('lists the batches with the meta, the figures, the status vocabulary and a retry tag', async () => {
      await openCycle(cycleDetail({
        batches: [
          cycleBatch(),
          cycleBatch({ id: 8, jobRunId: 9877, status: 'FAILED', succeededCount: 1, erroredCount: 5, retry: true }),
          cycleBatch({ id: 9, jobRunId: 9878, status: 'EXPIRED' }),
          cycleBatch({ id: 10, jobRunId: 9879, status: 'CANCELLED' }),
          cycleBatch({ id: 11, jobRunId: 9880, status: 'SUBMITTED', succeededCount: null, erroredCount: null, endedAt: null }),
        ],
      }));
      expect(screen.getByTestId('cycle-batches-meta')).toHaveTextContent('5 · 320 requests');
      expect(screen.getByTestId('cycle-batch-line-7')).toHaveTextContent('64 requests · 64 / 0 ok/err · 15:02');
      expect(screen.getByTestId('cycle-batch-line-11')).toHaveTextContent('— / — ok/err');
      expect(screen.getByTestId('cycle-batch-id-7')).toHaveTextContent('msgbatch_01JBX…Q7');
      expect(screen.getByTestId('cycle-batch-status-7')).toHaveTextContent('Completed');
      expect(screen.getByTestId('cycle-batch-status-8')).toHaveTextContent('Failed');
      expect(screen.getByTestId('cycle-batch-status-9')).toHaveTextContent('Expired');
      expect(screen.getByTestId('cycle-batch-status-10')).toHaveTextContent('Cancelled');
      expect(screen.getByTestId('cycle-batch-status-11')).toHaveTextContent('Submitted');
      expect(screen.getByTestId('pipeline-batch-retry-badge-8')).toHaveTextContent('RETRY');
      expect(screen.queryByTestId('pipeline-batch-retry-badge-7')).toBeNull();
    });

    it('says so when no batch was submitted', async () => {
      await openCycle(finishedDetail({ batches: [] }));
      expect(screen.getByTestId('pipeline-no-batches'))
        .toHaveTextContent('No batches were submitted. The cycle went straight to the briefing.');
    });

    it('draws a batch with no job run as plain text, not a button', async () => {
      await openCycle(cycleDetail({ batches: [cycleBatch({ id: 7, jobRunId: null })] }));
      const row = screen.getByTestId('pipeline-batch-row-7');
      expect(row.tagName).not.toBe('BUTTON');
      expect(within(row).queryByRole('button')).toBeNull();
    });

    it('draws a batch with a job run as a button of at least 48px', async () => {
      await openCycle();
      const row = screen.getByTestId('pipeline-batch-row-7');
      expect(row.tagName).toBe('BUTTON');
      expect(row.className).toContain('min-h-12');
    });
  });

  describe('the footer', () => {
    it('shows the batch cost from the cycle\'s row, "so far" while running, and the completed time', async () => {
      await openCycle(cycleDetail(), [cycleItem({ costMicroDollars: 770_000, costGbpRate: 0.79 })]);
      const cost = screen.getByTestId('cycle-cost');
      expect(screen.getByTestId('cycle-footer')).toHaveTextContent('Batch cost so far');
      expect(cost).toHaveTextContent('£0.6083');
      expect(cost).toHaveTextContent('$0.77');
      expect(screen.getByTestId('cycle-completed')).toHaveTextContent('—');
    });

    it('reads "Batch cost" and the completed time once finished', async () => {
      await openCycle(finishedDetail(), [cycleItem({ status: 'DONE', costMicroDollars: 770_000, costGbpRate: 0.79 })]);
      expect(screen.getByTestId('cycle-footer')).toHaveTextContent(/Batch cost(?! so far)/);
      expect(screen.getByTestId('cycle-completed')).toHaveTextContent('31 Oct 2026');
    });

    it('shows an em dash for the cost when the row is not on the loaded page', async () => {
      await openCycle(cycleDetail(), []);
      expect(screen.getByTestId('cycle-cost')).toHaveTextContent('—');
    });
  });

  describe('states', () => {
    it('shows the loading line, then the cycle', async () => {
      let resolve;
      fetchPipelineRunDetail.mockReturnValue(new Promise((r) => { resolve = r; }));
      window.location.hash = '#manage/runs/cycle/412';
      renderView();
      expect(await screen.findByTestId('cycle-panel-state')).toHaveTextContent('Loading');
      await act(async () => { resolve(cycleDetail()); });
      expect(await screen.findByTestId('cycle-panel-meta')).toBeInTheDocument();
    });

    it('shows the server\'s sentence when the cycle cannot load', async () => {
      fetchPipelineRunDetail.mockRejectedValue(Object.assign(
        new Error('Request failed with status code 500'),
        { response: { status: 500, data: { error: 'An unexpected error occurred' } } },
      ));
      window.location.hash = '#manage/runs/cycle/412';
      renderView();
      const state = await screen.findByTestId('cycle-panel-state');
      expect(state).toHaveTextContent('An unexpected error occurred');
      expect(state).not.toHaveTextContent('Request failed with status code 500');
    });

    it('a cycle the server has no row for leaves the bare tab address', async () => {
      fetchPipelineRunDetail.mockRejectedValue(Object.assign(new Error('nope'), { response: { status: 404 } }));
      window.location.hash = '#manage/runs/cycle/999';
      renderView();
      await waitFor(() => expect(window.location.hash).toBe('#manage/runs'));
      expect(screen.queryByTestId('cycle-panel')).toBeNull();
    });

    it('Refresh reads the cycle again', async () => {
      await openCycle();
      expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1);
      fireEvent.click(screen.getByTestId('cycle-panel-refresh'));
      await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2));
    });

    it('keeps the cycle on screen, with the error, when a later read fails', async () => {
      await openCycle();
      fetchPipelineRunDetail.mockRejectedValue(new Error('down'));
      fireEvent.click(screen.getByTestId('cycle-panel-refresh'));
      expect(await screen.findByTestId('cycle-panel-error')).toHaveTextContent('down');
      expect(screen.getByTestId('cycle-panel-meta')).toBeInTheDocument();
    });
  });

  it('does not follow a set rewind: "started" and its relative time are the real clock\'s', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-31T16:38:00Z'));
    setRewind('2026-01-15T09:00:00Z');
    await openCycle();
    expect(screen.getByTestId('cycle-panel-meta')).toHaveTextContent('started 15:00 (1h 38m ago)');
  });

  it('closes from its x to the bare tab address', async () => {
    await openCycle();
    fireEvent.click(screen.getByTestId('cycle-panel-close'));
    expect(screen.queryByTestId('cycle-panel')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('Escape closes it', async () => {
    await openCycle();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('cycle-panel')).toBeNull());
  });
});

describe('Runs: drilling into a batch', () => {
  async function openBatch() {
    window.location.hash = '#manage/runs/cycle/412';
    renderView();
    const row = await screen.findByTestId('pipeline-batch-row-7');
    fireEvent.click(row);
    return screen.findByTestId('job-run-panel');
  }

  it('a batch row opens the job-run panel at the nested address, with a "Cycle #412" row above it', async () => {
    await openBatch();
    expect(window.location.hash).toBe('#manage/runs/cycle/412/job/9876');
    expect(fetchJobRun).toHaveBeenCalledWith(9876);
    expect(await screen.findByTestId('job-run-panel-title')).toHaveTextContent('msgbatch_01JBX');
    expect(screen.getByTestId('job-run-back')).toHaveTextContent('Cycle #412');
    expect(screen.getByRole('complementary', { name: 'Job run 9876' })).toBeInTheDocument();
  });

  it('opens a nested address pasted cold, with the cycle behind it', async () => {
    window.location.hash = '#manage/runs/cycle/412/job/9876';
    renderView();
    expect(await screen.findByTestId('job-run-panel')).toBeInTheDocument();
    expect(fetchJobRun).toHaveBeenCalledWith(9876);
    expect(screen.getByTestId('job-run-back')).toBeInTheDocument();
  });

  it('keeps the cycle\'s row highlighted while a batch is open', async () => {
    await openBatch();
    expect(screen.getByTestId('runs-row-cycle-412')).toHaveAttribute('aria-current', 'true');
  });

  it('the back row returns to the cycle and puts focus on that batch\'s row', async () => {
    await openBatch();
    fireEvent.click(screen.getByTestId('job-run-back'));
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    expect(await screen.findByTestId('cycle-panel')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('pipeline-batch-row-7')).toHaveFocus());
  });

  it('Escape returns to the cycle (it does not close everything), focus on the batch row', async () => {
    await openBatch();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(await screen.findByTestId('cycle-panel')).toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/runs/cycle/412');
    await waitFor(() => expect(screen.getByTestId('pipeline-batch-row-7')).toHaveFocus());
  });

  it('the browser\'s Back (a hash change to the cycle) also puts focus on the batch row', async () => {
    await openBatch();
    act(() => {
      window.location.hash = '#manage/runs/cycle/412';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });
    await waitFor(() => expect(screen.getByTestId('pipeline-batch-row-7')).toHaveFocus());
  });

  it('the x closes everything, leaving the bare tab address', async () => {
    await openBatch();
    fireEvent.click(screen.getByTestId('job-run-panel-close'));
    expect(screen.queryByTestId('job-run-panel')).toBeNull();
    expect(screen.queryByTestId('cycle-panel')).toBeNull();
    expect(window.location.hash).toBe('#manage/runs');
  });

  it('a standalone job run has no back row', async () => {
    window.location.hash = '#manage/runs/job/9001';
    fetchJobRun.mockResolvedValue(jobDetail({ id: 9001 }));
    renderView();
    await screen.findByTestId('job-run-panel');
    expect(screen.queryByTestId('job-run-back')).toBeNull();
  });

  describe('on a phone', () => {
    beforeEach(() => {
      useIsMobile.mockReturnValue(true);
    });

    it('the layout\'s back bar is labelled "Cycle #412" and returns to the cycle; no second back row is drawn', async () => {
      await openBatch();
      expect(screen.getByTestId('runs-split-back')).toHaveAttribute('aria-label', 'Back to Cycle #412');
      expect(screen.getByTestId('runs-split-back')).toHaveTextContent('Cycle #412');
      expect(screen.queryByTestId('job-run-back')).toBeNull();
      fireEvent.click(screen.getByTestId('runs-split-back'));
      expect(window.location.hash).toBe('#manage/runs/cycle/412');
      expect(await screen.findByTestId('cycle-panel')).toBeInTheDocument();
    });

    it('a cycle\'s back bar says Runs and carries Refresh; the panel draws no second Refresh', async () => {
      window.location.hash = '#manage/runs/cycle/412';
      renderView();
      await screen.findByTestId('cycle-panel');
      expect(screen.getByTestId('runs-split-back')).toHaveTextContent('Runs');
      expect(screen.queryByTestId('cycle-panel-refresh')).toBeNull();
      fireEvent.click(screen.getByTestId('cycle-back-refresh'));
      await waitFor(() => expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2));
    });
  });
});

describe('Runs: closing a cycle on a filter change', () => {
  async function openAndWait(hash) {
    window.location.hash = hash;
    renderView();
    await screen.findByTestId('runs-chip-cycles');
    await screen.findByTestId(hash.includes('/job/') ? 'job-run-panel' : 'cycle-panel');
  }

  it('stays when its own group still matches the chip (Cycles), closes when it does not (Batches)', async () => {
    await openAndWait('#manage/runs/cycle/412');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    expect(screen.getByTestId('cycle-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-chip-batches'));
    await waitFor(() => expect(screen.queryByTestId('cycle-panel')).toBeNull());
  });

  it('closes a running cycle under Need attention, but keeps a degraded one', async () => {
    await openAndWait('#manage/runs/cycle/412');
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await waitFor(() => expect(screen.queryByTestId('cycle-panel')).toBeNull());
  });

  it('keeps a degraded cycle under Need attention', async () => {
    fetchPipelineRunDetail.mockResolvedValue(finishedDetail({
      run: cycleRun({ status: 'DEGRADED', currentPhase: null, waitingOn: null, failureReason: 'one batch failed' }),
    }));
    await openAndWait('#manage/runs/cycle/412');
    fireEvent.click(screen.getByTestId('runs-chip-attention'));
    await act(async () => {});
    expect(screen.getByTestId('cycle-panel')).toBeInTheDocument();
  });

  it('tests a nested batch by its PARENT cycle: kind Cycles keeps it, Batches closes it', async () => {
    await openAndWait('#manage/runs/cycle/412/job/9876');
    fireEvent.click(screen.getByTestId('runs-chip-cycles'));
    await act(async () => {});
    expect(screen.getByTestId('job-run-panel')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('runs-chip-batches'));
    await waitFor(() => expect(screen.queryByTestId('job-run-panel')).toBeNull());
  });

  it('a range change never closes it', async () => {
    await openAndWait('#manage/runs/cycle/412');
    fireEvent.click(screen.getByTestId('runs-range-today'));
    await act(async () => {});
    expect(screen.getByTestId('cycle-panel')).toBeInTheDocument();
  });
});

describe('Runs: the live count it reports', () => {
  it('reports the served runningCount on its first load and on each refresh', async () => {
    const onLiveCountChange = vi.fn();
    fetchRuns.mockResolvedValue(runsPage([cycleItem()], { runningCount: 2, liveCycleId: null }));
    renderView({ onLiveCountChange });
    await waitFor(() => expect(onLiveCountChange).toHaveBeenLastCalledWith(2));

    fetchRuns.mockResolvedValue(runsPage([cycleItem()], { runningCount: 0 }));
    fireEvent.click(screen.getByTestId('runs-refresh-btn'));
    await waitFor(() => expect(onLiveCountChange).toHaveBeenLastCalledWith(0));
  });
});

describe('useCycleDetail: one fetch, one poll', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  it('two readers of one cycle share a single request and a single 15 second poll while it runs', async () => {
    renderHook(() => useCycleDetail(412));
    renderHook(() => useCycleDetail(412));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(3);
  });

  it('keeps polling after the first reader leaves', async () => {
    const first = renderHook(() => useCycleDetail(412));
    renderHook(() => useCycleDetail(412));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    first.unmount();
    // The second reader takes over: one read at once (a poll starting reads straight away), then one per 15 seconds.
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2);
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(3);
  });

  it('stops polling once the cycle is no longer RUNNING', async () => {
    fetchPipelineRunDetail.mockResolvedValue(finishedDetail());
    renderHook(() => useCycleDetail(412));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1);
  });

  it('skips a tick while the Operations pane is hidden', async () => {
    let paused = false;
    renderHook(() => useCycleDetail(412, { isPaused: () => paused }));
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    paused = true;
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(1);
    paused = false;
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(fetchPipelineRunDetail).toHaveBeenCalledTimes(2);
  });

  it('reads nothing for a null id', async () => {
    renderHook(() => useCycleDetail(null));
    await act(async () => { await vi.advanceTimersByTimeAsync(30_000); });
    expect(fetchPipelineRunDetail).not.toHaveBeenCalled();
  });
});
