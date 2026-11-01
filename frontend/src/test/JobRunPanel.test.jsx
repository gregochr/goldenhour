import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import JobRunPanel from '../components/runs/JobRunPanel.jsx';
import { batchInfo, jobDetail } from './runsFixtures.js';

// The job-run panel is where the old JobRunDetail (the per-call breakdown) and JobRunsGrid (the row's scope, notes and
// strategies) went. Each test below is one of theirs, moved: the figures, wording and rules that survive are asserted
// exactly as before; only the places they now live changed (a Scope list, a Calls section, Diagnostics disclosures).

vi.mock('../api/metricsApi', () => ({
  getApiCalls: vi.fn(),
  getBatchSummary: vi.fn(),
  getDispositionBreakdown: vi.fn(),
}));

import { getApiCalls, getBatchSummary, getDispositionBreakdown } from '../api/metricsApi';

const ANTHROPIC_CALL = {
  id: 1,
  service: 'ANTHROPIC',
  durationMs: 2000,
  costMicroDollars: 500_000,
  inputTokens: 100,
  outputTokens: 50,
  cacheCreationInputTokens: 0,
  cacheReadInputTokens: 0,
  succeeded: true,
  errorMessage: null,
  targetDate: '2026-01-01',
  targetType: 'SUNSET',
  evaluationModel: 'HAIKU',
};

const OPEN_METEO_CALL = {
  id: 2,
  service: 'OPEN_METEO',
  durationMs: 300,
  costMicroDollars: 0,
  inputTokens: 0,
  outputTokens: 0,
  cacheCreationInputTokens: 0,
  cacheReadInputTokens: 0,
  succeeded: true,
  errorMessage: null,
  targetDate: null,
  targetType: null,
  evaluationModel: null,
};

const onClose = vi.fn();

/** Renders the panel for a served job run; {@code runOver} edits the run, {@code top} the served fields beside it. */
function renderPanel(runOver = {}, top = {}, props = {}) {
  return render(
    <JobRunPanel detail={jobDetail(runOver, top)} loading={false} onClose={onClose} {...props} />,
  );
}

/** Opens a Diagnostics disclosure row ({@code failed}, {@code cache} or {@code fallback}). */
function openDisclosure(id) {
  fireEvent.click(screen.getByTestId(`diagnostics-${id}-toggle`));
}

beforeEach(() => {
  vi.clearAllMocks();
  getBatchSummary.mockRejectedValue(new Error('not found'));
  getApiCalls.mockResolvedValue({ data: [] });
  // Default: no dispositions for the job run (non-batch runs and the cycle's non-primary buckets), which keeps the
  // section silent.
  getDispositionBreakdown.mockResolvedValue({ data: {
    jobRunId: 0, totalCount: 0, countsByDisposition: {}, entries: [],
  } });
});

describe('JobRunPanel: loading, refusal and error states', () => {
  it('shows Loading while the calls are pending', () => {
    getApiCalls.mockReturnValue(new Promise(() => {})); // never resolves
    renderPanel();
    expect(within(screen.getByTestId('job-run-calls')).getByText('Loading...')).toBeInTheDocument();
  });

  it('shows Loading, and no sections, while the run itself has not arrived', () => {
    render(<JobRunPanel detail={null} loading onClose={onClose} />);
    expect(screen.getByTestId('job-run-panel-state')).toHaveTextContent('Loading...');
    expect(screen.queryByTestId('job-run-scope')).toBeNull();
  });

  it('says so when the server has no such job run', () => {
    render(<JobRunPanel detail={null} loading={false} notFound onClose={onClose} />);
    expect(screen.getByTestId('job-run-panel-state')).toHaveTextContent('This job run no longer exists.');
  });

  it('shows the load error when the run could not be read', () => {
    render(<JobRunPanel detail={null} loading={false} error="Boom" onClose={onClose} />);
    expect(screen.getByTestId('job-run-panel-state')).toHaveTextContent('Boom');
  });

  it('shows an error message when the calls request rejects', async () => {
    getApiCalls.mockRejectedValue(new Error('Network failure'));
    renderPanel();
    expect(await screen.findByText('Error: Network failure')).toBeInTheDocument();
  });

  it("shows the server's error sentence, not axios's generic message, when the request is refused", async () => {
    getApiCalls.mockRejectedValue(Object.assign(
      new Error('Request failed with status code 404'),
      { response: { status: 404, data: { error: 'Job run not found' } } },
    ));
    renderPanel();
    expect(await screen.findByText('Error: Job run not found')).toBeInTheDocument();
    expect(screen.queryByText(/Request failed with status code 404/)).toBeNull();
  });

  it('uses fallback error text when the rejection has no message', async () => {
    getApiCalls.mockRejectedValue({});
    renderPanel();
    expect(await screen.findByText('Error: Failed to load API calls')).toBeInTheDocument();
  });
});

describe('JobRunPanel: the calls request', () => {
  it('asks for the calls of exactly this job run id', async () => {
    renderPanel({ id: 99 });
    await screen.findByText('No API calls recorded');
    expect(getApiCalls).toHaveBeenCalledTimes(1);
    expect(getApiCalls).toHaveBeenCalledWith(99);
  });

  it('does not ask for the batch summary of a run that is not a batch', async () => {
    renderPanel({ runType: 'TIDE' });
    await screen.findByText('No API calls recorded');
    expect(getBatchSummary).not.toHaveBeenCalled();
  });
});

describe('JobRunPanel: the header', () => {
  it('names the run, with its group, job run id and status in the meta', () => {
    renderPanel({ id: 42 });
    expect(screen.getByTestId('job-run-panel-title')).toHaveTextContent('Tide refresh');
    expect(screen.getByTestId('job-run-meta').textContent).toContain('Tide');
    expect(screen.getByTestId('job-run-meta').textContent).toContain('job run #42');
    expect(screen.getByTestId('job-run-meta').textContent).toContain('Done');
  });

  it('shows the start and end times in UK time in the meta', () => {
    renderPanel({ startedAt: '2026-07-01T14:02:00', completedAt: '2026-07-01T15:21:00' });
    // 14:02Z and 15:21Z are 15:02 and 16:21 in BST.
    expect(screen.getByTestId('job-run-meta').textContent).toContain('15:02 → 16:21');
  });

  it('titles a batch run by its Anthropic batch id, in mono, cut in the middle when long', () => {
    renderPanel(
      { runType: 'SCHEDULED_BATCH' },
      { group: 'BATCHES', name: 'Scheduled batch', batch: batchInfo({ anthropicBatchId: 'msgbatch_01JBXKQ7Q7Q7Q7Q7Q7Q7' }) },
    );
    const title = screen.getByTestId('job-run-panel-title');
    expect(title).toHaveTextContent('msgbatch_01JBX…Q7');
    expect(title.className).toContain('font-mono');
  });
});

describe('JobRunPanel: Scope, Anthropic batch id', () => {
  const BATCH_NOTES = { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: msgbatch_01HHz123' };

  it('shows the Anthropic batch id of a SCHEDULED_BATCH run from its notes', () => {
    renderPanel(BATCH_NOTES, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-batch-id')).toHaveTextContent('msgbatch_01HHz123');
  });

  it('strips the "Anthropic batch: " prefix from the displayed id', () => {
    renderPanel(BATCH_NOTES, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-batch-id').textContent).toBe('msgbatch_01HHz123');
  });

  it('shows the full notes too, since they are the run\'s own words', () => {
    renderPanel(BATCH_NOTES, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-notes')).toHaveTextContent('Anthropic batch: msgbatch_01HHz123');
  });

  it('renders the batch id in mono', () => {
    renderPanel({ ...BATCH_NOTES, notes: 'Anthropic batch: msgbatch_abc' }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-batch-id').className).toContain('font-mono');
  });

  it('prefers the served batch row\'s id over the notes', () => {
    renderPanel(BATCH_NOTES, { group: 'BATCHES', batch: batchInfo({ anthropicBatchId: 'msgbatch_served' }) });
    expect(screen.getByTestId('job-run-batch-id').textContent).toBe('msgbatch_served');
  });

  it('hides the batch id for a run that is not a batch, even when its notes look like one', () => {
    renderPanel({ runType: 'TIDE', notes: 'Anthropic batch: msgbatch_01HHz123' });
    expect(screen.queryByTestId('job-run-batch-id')).toBeNull();
  });

  it('hides the batch id for BRIEFING runs', () => {
    renderPanel({ runType: 'BRIEFING', notes: 'some notes' }, { group: 'BRIEFING' });
    expect(screen.queryByTestId('job-run-batch-id')).toBeNull();
  });

  it('hides the batch id when the run is a batch but has no notes and no batch row', () => {
    renderPanel({ runType: 'SCHEDULED_BATCH', notes: null }, { group: 'BATCHES' });
    expect(screen.queryByTestId('job-run-batch-id')).toBeNull();
  });

  it('words the batch status in the batch vocabulary', () => {
    renderPanel({ runType: 'SCHEDULED_BATCH' }, { group: 'BATCHES', batch: batchInfo({ status: 'EXPIRED' }) });
    expect(screen.getByTestId('job-run-batch-status')).toHaveTextContent('Expired');
  });
});

describe('JobRunPanel: Scope, Locations, Dates and Model', () => {
  it('shows Locations when locationsProcessed is above zero', () => {
    renderPanel({ locationsProcessed: 5 });
    expect(screen.getByTestId('job-run-locations')).toHaveTextContent('5');
  });

  it('hides Locations when locationsProcessed is 0', () => {
    renderPanel({ locationsProcessed: 0 });
    expect(screen.queryByText('Locations')).not.toBeInTheDocument();
  });

  it('says "{n} in {r} regions" for a batch with a scope', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      batchSummary: {
        horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'HAIKU', locationCount: 29, regionCount: 7,
        extendedThinking: false,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-locations')).toHaveTextContent('29 in 7 regions');
  });

  it('shows the Dates with the day count when both dates are present', () => {
    renderPanel({ minTargetDate: '2026-01-01', maxTargetDate: '2026-01-07' });
    // 7 days inclusive: Jan 1 to Jan 7.
    expect(screen.getByTestId('job-run-dates')).toHaveTextContent('Thu 1 Jan – Wed 7 Jan · 7 days');
  });

  it('says "1 day" when the min and max dates are the same', () => {
    renderPanel({ minTargetDate: '2026-04-14', maxTargetDate: '2026-04-14' });
    expect(screen.getByTestId('job-run-dates')).toHaveTextContent('Tue 14 Apr – Tue 14 Apr · 1 day');
  });

  it('hides Dates when the dates are absent', () => {
    renderPanel({ minTargetDate: null, maxTargetDate: null });
    expect(screen.queryByTestId('job-run-dates')).not.toBeInTheDocument();
  });

  it('appends the event types to the Dates, joined with a slash', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      minTargetDate: '2026-10-31',
      maxTargetDate: '2026-11-01',
      batchSummary: {
        horizonRange: 'T to T+1', eventTypes: ['SUNRISE', 'SUNSET'], evaluationModel: 'SONNET', locationCount: 14,
        regionCount: 5, extendedThinking: false,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-dates')).toHaveTextContent('Sat 31 Oct – Sun 1 Nov · 2 days · sunrise, sunset');
  });

  it('falls back to the horizon range when a batch has no target dates', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      batchSummary: {
        horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'HAIKU', locationCount: 29, regionCount: 7,
        extendedThinking: false,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-dates')).toHaveTextContent('T+1 · sunrise');
  });

  it('words a SONNET_55 batch as "Sonnet 5.5", never the enum name', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      evaluationModel: 'SONNET_55',
      batchSummary: {
        horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'SONNET_55', locationCount: 29, regionCount: 7,
        extendedThinking: false,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-model').textContent).toBe('Sonnet 5.5');
  });

  it('appends "(ET)" when extended thinking is on, but never twice', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      batchSummary: {
        horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'HAIKU', locationCount: 10, regionCount: 3,
        extendedThinking: true,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-model').textContent).toBe('Haiku 4.5 (ET)');
  });

  it('does not double "(ET)" for a model whose own label already carries it', () => {
    renderPanel({
      runType: 'SCHEDULED_BATCH',
      batchSummary: {
        horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'SONNET_ET', locationCount: 10, regionCount: 3,
        extendedThinking: true,
      },
    }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-model').textContent).toBe('Sonnet 4.6 (ET)');
  });
});

describe('JobRunPanel: Scope, strategies, completion and notes', () => {
  it('shows the strategies as tags, with EVALUATE_ALL read as JFDI', () => {
    renderPanel({ runType: 'SCHEDULED_BATCH', activeStrategies: 'EVALUATE_ALL, STABILITY' }, { group: 'BATCHES' });
    expect(screen.getByTestId('job-run-strategy-EVALUATE_ALL')).toHaveTextContent('JFDI');
    expect(screen.getByTestId('job-run-strategy-STABILITY')).toHaveTextContent('STABILITY');
  });

  it('hides Strategies when the run recorded none', () => {
    renderPanel({ activeStrategies: null });
    expect(screen.queryByTestId('job-run-strategies')).toBeNull();
  });

  it('shows the app version', () => {
    renderPanel({ appVersion: 'v2.11.16' });
    expect(screen.getByTestId('job-run-app-version')).toHaveTextContent('v2.11.16');
  });

  it('shows the full notes of any run, not only a failure line', () => {
    renderPanel({ notes: 'Disposition-only cycle: nothing to submit' });
    expect(screen.getByTestId('job-run-notes')).toHaveTextContent('Disposition-only cycle: nothing to submit');
  });

  it('shows the Completed time, and hides it for a run still going', () => {
    const { unmount } = renderPanel({ completedAt: '2026-10-31T06:01:12' });
    expect(screen.getByTestId('job-run-completed')).toHaveTextContent('31 Oct 2026');
    unmount();
    renderPanel({ completedAt: null }, { status: 'RUNNING' });
    expect(screen.queryByTestId('job-run-completed')).toBeNull();
  });

  it('shows "{succeeded} / {locationsProcessed} succeeded" for a batch still in progress', () => {
    renderPanel(
      { runType: 'SCHEDULED_BATCH', completedAt: null, succeeded: 5, locationsProcessed: 29 },
      { status: 'RUNNING', group: 'BATCHES' },
    );
    expect(screen.getByTestId('job-run-progress')).toHaveTextContent('5 / 29 succeeded');
  });

  it('shows no progress line once the batch has finished', () => {
    renderPanel({ runType: 'SCHEDULED_BATCH', succeeded: 29, locationsProcessed: 29 }, { group: 'BATCHES' });
    expect(screen.queryByTestId('job-run-progress')).toBeNull();
  });

  it('shows the served note in its tone, beside the status', () => {
    renderPanel({}, { status: 'PARTIAL', note: '14 requests errored', noteTone: 'WARN' });
    const note = screen.getByTestId('job-run-note');
    expect(note).toHaveTextContent('14 requests errored');
    expect(note).toHaveTextContent('Partial');
    expect(note.querySelector('p').className).toContain('text-verdict-marginal');
  });

  it('shows no note box when the server served none', () => {
    renderPanel();
    expect(screen.queryByTestId('job-run-note')).toBeNull();
  });

  it('points at the progress panel instead of opening a second subscription when it is the followed run', () => {
    renderPanel({}, {}, { isActiveRun: true });
    expect(screen.getByTestId('job-run-progress-note')).toHaveTextContent('progress panel on the Runs list');
    expect(screen.queryByTestId('run-progress-panel')).toBeNull();
  });

  it('shows no progress note for a run that is not being followed', () => {
    renderPanel();
    expect(screen.queryByTestId('job-run-progress-note')).toBeNull();
  });
});

describe('JobRunPanel: nothing to show', () => {
  it('says "No detail available" when the run has no scope, no calls and no dispositions', async () => {
    renderPanel({ completedAt: null, succeeded: 0 }, { status: 'DONE' });
    expect(await screen.findByTestId('job-run-no-detail')).toHaveTextContent('No detail available');
  });

  it('does not say it when the run has a scope to show', async () => {
    renderPanel({ completedAt: '2026-10-31T06:01:12' });
    await screen.findByText('No API calls recorded');
    expect(screen.queryByTestId('job-run-no-detail')).toBeNull();
  });

  it('does not say it while the calls are still loading', () => {
    getApiCalls.mockReturnValue(new Promise(() => {}));
    renderPanel({ completedAt: null });
    expect(screen.queryByTestId('job-run-no-detail')).toBeNull();
  });
});

describe('JobRunPanel: Calls', () => {
  it('shows "No API calls recorded" when the list is empty', async () => {
    renderPanel();
    expect(await screen.findByText('No API calls recorded')).toBeInTheDocument();
  });

  it('shows the service name, call count and average duration', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...OPEN_METEO_CALL, id: 1, durationMs: 400 },
      { ...OPEN_METEO_CALL, id: 2, durationMs: 600 },
    ] });
    renderPanel();
    expect(await screen.findByText('OPEN_METEO')).toBeInTheDocument();
    expect(screen.getByText('2 calls \u00b7 avg 500 ms')).toBeInTheDocument();
  });

  it('says "all ok" when every call of a service succeeded', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, succeeded: true }] });
    renderPanel();
    expect(await screen.findByText('all ok')).toBeInTheDocument();
  });

  it('marks an error rate below 5% amber', async () => {
    // 1 error out of 21 calls is about 4.76%.
    const calls = Array.from({ length: 20 }, (_, i) => ({ ...ANTHROPIC_CALL, id: i + 1, succeeded: true }));
    calls.push({ ...ANTHROPIC_CALL, id: 21, succeeded: false, errorMessage: 'timeout' });
    getApiCalls.mockResolvedValue({ data: calls });
    renderPanel();
    const badge = await screen.findByText(/^1 errors \(/);
    expect(badge.className).toContain('text-verdict-marginal');
    expect(badge.className).not.toContain('text-plex-coral');
  });

  it('marks an error rate of 5% or more coral', async () => {
    // 1 error out of 10 calls is 10%.
    const calls = Array.from({ length: 9 }, (_, i) => ({ ...ANTHROPIC_CALL, id: i + 1, succeeded: true }));
    calls.push({ ...ANTHROPIC_CALL, id: 10, succeeded: false, errorMessage: 'rate limit' });
    getApiCalls.mockResolvedValue({ data: calls });
    renderPanel();
    const badge = await screen.findByText(/^1 errors \(/);
    expect(badge.className).toContain('text-plex-coral');
    expect(badge.className).not.toContain('text-verdict-marginal');
  });

  it('shows the error percentage in the service row', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, succeeded: true },
      { ...ANTHROPIC_CALL, id: 2, succeeded: false, errorMessage: 'err' },
    ] });
    renderPanel();
    expect(await screen.findByText('1 errors (50.00%)')).toBeInTheDocument();
  });

  it('shows the cost in GBP with the USD small, per service', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, costMicroDollars: 500_000 }] });
    renderPanel();
    const row = await screen.findByTestId('job-run-service-ANTHROPIC');
    expect(row).toHaveTextContent('£0.4000');
    expect(row).toHaveTextContent('$0.5000');
  });
});

describe('JobRunPanel: Anthropic token breakdown', () => {
  it('shows the tokens in and out for ANTHROPIC calls with input tokens', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, inputTokens: 100, outputTokens: 50 }] });
    renderPanel();
    const line = await screen.findByText(/Tokens:/);
    expect(line.textContent).toContain('100');
    expect(line.textContent).toContain('50');
  });

  it('hides the token line for non-ANTHROPIC services', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, inputTokens: 100 }] });
    renderPanel();
    await screen.findByText('OPEN_METEO');
    expect(screen.queryByText(/Tokens:/)).not.toBeInTheDocument();
  });

  it('hides the token line for ANTHROPIC when inputTokens is 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, inputTokens: 0, outputTokens: 0 }] });
    renderPanel();
    await screen.findByText('Anthropic');
    expect(screen.queryByText(/Tokens:/)).not.toBeInTheDocument();
  });

  it('shows cache write tokens when cacheCreationInputTokens is above zero', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, cacheCreationInputTokens: 25 }] });
    renderPanel();
    expect(await screen.findByText(/cache write/)).toBeInTheDocument();
  });

  it('hides cache write tokens when cacheCreationInputTokens is 0', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, cacheCreationInputTokens: 0 }] });
    renderPanel();
    await screen.findByText(/Tokens:/);
    expect(screen.queryByText(/cache write/)).not.toBeInTheDocument();
  });

  it('shows cache read tokens when cacheReadInputTokens is above zero', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, cacheReadInputTokens: 30 }] });
    renderPanel();
    expect(await screen.findByText(/cache read/)).toBeInTheDocument();
  });
});

describe('JobRunPanel: Evaluations', () => {
  it('shows the section when ANTHROPIC calls are present', async () => {
    getApiCalls.mockResolvedValue({ data: [ANTHROPIC_CALL] });
    renderPanel();
    expect(await screen.findByTestId('job-run-evaluations')).toBeInTheDocument();
  });

  it('hides the section when there are no ANTHROPIC calls', async () => {
    getApiCalls.mockResolvedValue({ data: [OPEN_METEO_CALL] });
    renderPanel();
    await screen.findByText('OPEN_METEO');
    expect(screen.queryByTestId('job-run-evaluations')).not.toBeInTheDocument();
  });

  it('shows the date, event type and model of the ANTHROPIC call', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, targetDate: '2026-03-15', targetType: 'SUNRISE', evaluationModel: 'SONNET',
    }] });
    renderPanel();
    const section = await screen.findByTestId('job-run-evaluations');
    expect(within(section).getByText('2026-03-15')).toBeInTheDocument();
    expect(within(section).getByText('SUNRISE')).toBeInTheDocument();
    expect(within(section).getByText('Sonnet 4.6')).toBeInTheDocument();
  });

  it('sorts the rows by date, then event, then model', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, targetDate: '2026-01-02', targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 2, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 3, targetDate: '2026-01-01', targetType: 'SUNRISE', evaluationModel: 'HAIKU' },
    ] });
    renderPanel();
    const section = await screen.findByTestId('job-run-evaluations');
    const dates = within(section).getAllByText(/^2026-01-0[12]$/).map((el) => el.textContent);
    expect(dates).toEqual(['2026-01-01', '2026-01-01', '2026-01-02']);
    const events = within(section).getAllByText(/^SUN(RISE|SET)$/).map((el) => el.textContent);
    expect(events).toEqual(['SUNRISE', 'SUNSET', 'SUNSET']);
  });

  it('counts the calls per date, event and model', async () => {
    getApiCalls.mockResolvedValue({ data: [1, 2, 3].map((id) => ({
      ...ANTHROPIC_CALL, id, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: 'HAIKU',
    })) });
    renderPanel();
    const section = await screen.findByTestId('job-run-evaluations');
    expect(within(section).getAllByRole('row')).toHaveLength(2); // header + one combination
    expect(within(section).getByText('3')).toBeInTheDocument();
  });

  it('leaves out ANTHROPIC calls missing a date, an event or a model', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, targetDate: null, targetType: 'SUNSET', evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 2, targetDate: '2026-01-01', targetType: null, evaluationModel: 'HAIKU' },
      { ...ANTHROPIC_CALL, id: 3, targetDate: '2026-01-01', targetType: 'SUNSET', evaluationModel: null },
    ] });
    renderPanel();
    await screen.findByText('Anthropic');
    // All three are incomplete, so there is nothing to count and no section.
    expect(screen.queryByTestId('job-run-evaluations')).not.toBeInTheDocument();
  });
});

describe('JobRunPanel: Diagnostics, failed calls', () => {
  it('lists failed calls behind a disclosure with their count', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, id: 1, succeeded: false, errorMessage: 'Rate limit exceeded',
    }] });
    renderPanel();
    const toggle = await screen.findByTestId('diagnostics-failed-toggle');
    expect(toggle).toHaveTextContent('Failed calls');
    expect(toggle).toHaveTextContent('1');
    expect(screen.queryByText('Rate limit exceeded')).toBeNull();
    openDisclosure('failed');
    expect(screen.getByText('Rate limit exceeded')).toBeInTheDocument();
  });

  it('hides Diagnostics entirely when every call succeeded', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, succeeded: true }] });
    renderPanel();
    await screen.findByText('Anthropic');
    expect(screen.queryByTestId('job-run-diagnostics')).not.toBeInTheDocument();
  });

  it('shows the service alongside the error message', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...OPEN_METEO_CALL, id: 5, succeeded: false, errorMessage: 'Timeout after 30s',
    }] });
    renderPanel();
    await screen.findByTestId('diagnostics-failed-toggle');
    openDisclosure('failed');
    expect(screen.getByTestId('failed-call-heading').textContent).toBe('OPEN_METEO');
    expect(screen.getByText('Timeout after 30s')).toBeInTheDocument();
  });

  /** Renders a run whose one call failed, opens the failed-calls disclosure and returns the call's heading. */
  async function headingFor(call) {
    getApiCalls.mockResolvedValue({ data: [{ ...call, succeeded: false }] });
    renderPanel();
    await screen.findByTestId('diagnostics-failed-toggle');
    openDisclosure('failed');
    return screen.getByTestId('failed-call-heading').textContent;
  }

  it('shows the error type beside the service for a failed Claude call, without repeating the status', async () => {
    const heading = await headingFor({
      ...ANTHROPIC_CALL, id: 7, statusCode: 401, errorType: 'anthropic_401', errorMessage: 'invalid x-api-key',
    });
    expect(heading).toBe('ANTHROPIC · anthropic_401');
    expect(screen.getByText('invalid x-api-key')).toBeInTheDocument();
  });

  it('shows no status for a Claude row written before the real status was recorded (placeholder 500, no type)',
    async () => {
      const heading = await headingFor({
        ...ANTHROPIC_CALL, id: 10, statusCode: 500, errorType: null, errorMessage: 'old failure',
      });
      expect(heading).toBe('ANTHROPIC');
    });

  it('shows the status for a weather row, which is always a real HTTP status', async () => {
    const heading = await headingFor({
      ...OPEN_METEO_CALL, id: 11, statusCode: 503, errorType: null, errorMessage: 'unavailable',
    });
    expect(heading).toBe('OPEN_METEO · 503');
  });

  it('shows the error type for a batch row, which has a type and no status', async () => {
    const heading = await headingFor({
      ...ANTHROPIC_CALL, id: 12, statusCode: null, errorType: 'overloaded_error',
      customId: 'fc-1-2026-01-01-SUNSET', errorMessage: 'busy',
    });
    expect(heading).toBe('ANTHROPIC · overloaded_error');
  });

  it('shows the error type alone for a failure that had no status', async () => {
    const heading = await headingFor({
      ...ANTHROPIC_CALL, id: 8, statusCode: null, errorType: 'circuit_open', errorMessage: 'CircuitBreaker is OPEN',
    });
    expect(heading).toBe('ANTHROPIC · circuit_open');
  });

  it('shows the service alone when a failed call carries neither a status nor an error type', async () => {
    const heading = await headingFor({ ...OPEN_METEO_CALL, id: 9, errorMessage: 'Timeout after 30s' });
    expect(heading).toBe('OPEN_METEO');
  });
});

describe('JobRunPanel: Total cost', () => {
  it('shows the total cost, GBP large and USD small', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...ANTHROPIC_CALL, costMicroDollars: 1_000_000 }] });
    renderPanel({ exchangeRateGbpPerUsd: 0.8 });
    const total = await screen.findByTestId('job-run-total-cost');
    // £0.8000 at 1 dollar, 0.80 rate.
    expect(total).toHaveTextContent('Total cost');
    expect(total).toHaveTextContent('£0.8000');
    expect(total).toHaveTextContent('$1.0000');
  });

  it('hides the total when every cost is zero', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, costMicroDollars: 0 }] });
    renderPanel();
    await screen.findByText('OPEN_METEO');
    expect(screen.queryByTestId('job-run-total-cost')).not.toBeInTheDocument();
  });

  it('hides the total (and any USD) for a legacy record with no micro-dollar cost', async () => {
    // A legacy api_call_log row carries no micro-dollar cost at all; the `|| 0` fallback yields a zero grand total,
    // so the whole block, GBP and USD, stays hidden.
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, costMicroDollars: undefined }] });
    renderPanel();
    await screen.findByText('OPEN_METEO');
    expect(screen.queryByTestId('job-run-total-cost')).not.toBeInTheDocument();
    expect(screen.getByTestId('job-run-calls')).not.toHaveTextContent('$');
  });
});

describe('JobRunPanel: the batch summary row', () => {
  const BATCH = { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: msgbatch_abc123', locationsProcessed: 20 };

  it('shows the batch token summary when one is available', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 120000,
      totalOutputTokens: 8000,
      totalCacheReadTokens: 90000,
      totalCacheCreationTokens: 5000,
      estimatedCostMicroDollars: 35000,
      requestCount: 20,
      succeededCount: 18,
      erroredCount: 2,
      status: 'COMPLETED',
    } });
    renderPanel(BATCH, { group: 'BATCHES' });
    expect(await screen.findByText('Anthropic \u00b7 batch')).toBeInTheDocument();
    expect(screen.getByText(/20 requests: 18 succeeded, 2 errored/)).toBeInTheDocument();
    expect(screen.getByTestId('job-run-batch-row-status')).toHaveTextContent('Completed');
    expect(screen.getByText(/120,000/)).toBeInTheDocument();
    expect(screen.getByText(/8,000/)).toBeInTheDocument();
  });

  it('says there is no token data when the batch failed before processing', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 0,
      totalOutputTokens: 0,
      totalCacheReadTokens: 0,
      totalCacheCreationTokens: 0,
      estimatedCostMicroDollars: 0,
      requestCount: 10,
      succeededCount: 0,
      erroredCount: 0,
      status: 'FAILED',
    } });
    renderPanel(BATCH, { group: 'BATCHES' });
    expect(await screen.findByText('Anthropic \u00b7 batch')).toBeInTheDocument();
    expect(screen.getByText(/No token data/)).toBeInTheDocument();
    expect(screen.getByTestId('job-run-batch-row-status')).toHaveTextContent('Failed');
  });

  it('uses the batch cost for the total instead of the empty per-call total', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 50000,
      totalOutputTokens: 5000,
      totalCacheReadTokens: 0,
      totalCacheCreationTokens: 0,
      estimatedCostMicroDollars: 75000,
      requestCount: 10,
      succeededCount: 10,
      erroredCount: 0,
      status: 'COMPLETED',
    } });
    renderPanel({ ...BATCH, exchangeRateGbpPerUsd: 0.8 }, { group: 'BATCHES' });
    // £0.0600 appears in the batch row and in the total: the total is present only because of the batch cost.
    expect(await screen.findByTestId('job-run-total-cost')).toHaveTextContent('£0.0600');
    expect(screen.getAllByText(/£0\.0600/)).toHaveLength(2);
  });

  it('falls back to "No API calls recorded" when the batch summary request fails', async () => {
    getBatchSummary.mockRejectedValue(new Error('404'));
    renderPanel(BATCH, { group: 'BATCHES' });
    expect(await screen.findByText('No API calls recorded')).toBeInTheDocument();
  });
});

describe('JobRunPanel: fallback-parsed responses', () => {
  const FALLBACK_CALL = {
    ...ANTHROPIC_CALL,
    id: 7,
    customId: 'fc-42-2026-04-20-SUNRISE',
    errorType: 'regex_fallback',
    responseBody: '{"rating":4,"summary":"Clear horizon.",\'headline"}',
    succeeded: true,
  };

  it('shows the count on a disclosure row, and the customId once opened', async () => {
    getApiCalls.mockResolvedValue({ data: [ANTHROPIC_CALL, FALLBACK_CALL] });
    renderPanel();
    const toggle = await screen.findByTestId('diagnostics-fallback-toggle');
    expect(toggle).toHaveTextContent('Fallback-parsed responses');
    expect(toggle).toHaveTextContent('1');
    openDisclosure('fallback');
    expect(screen.getByTestId('fallback-parses')).toBeInTheDocument();
    expect(screen.getByText('fc-42-2026-04-20-SUNRISE')).toBeInTheDocument();
    // Collapsed by default: the raw text is not rendered until the call itself is opened.
    expect(screen.queryByTestId('fallback-parse-raw')).not.toBeInTheDocument();
  });

  it('reveals the raw response body (mono) when a call is opened', async () => {
    getApiCalls.mockResolvedValue({ data: [FALLBACK_CALL] });
    renderPanel();
    await screen.findByTestId('diagnostics-fallback-toggle');
    openDisclosure('fallback');
    fireEvent.click(screen.getByTestId('fallback-parse-toggle'));
    const raw = screen.getByTestId('fallback-parse-raw');
    expect(raw.textContent).toContain('Clear horizon.');
    expect(raw.className).toContain('font-mono');
  });

  it('hides the row entirely when there are no fallback parses', async () => {
    getApiCalls.mockResolvedValue({ data: [ANTHROPIC_CALL] });
    renderPanel();
    await screen.findByText('Anthropic');
    expect(screen.queryByTestId('diagnostics-fallback-toggle')).not.toBeInTheDocument();
    expect(screen.queryByText(/Fallback-parsed responses/)).not.toBeInTheDocument();
  });
});

describe('JobRunPanel: prompt-cache diagnostics', () => {
  const MISS = '{"status":"MISS","reason":"messages_changed","missedInputTokens":1234}';
  const DIAGNOSED_CALL = {
    ...ANTHROPIC_CALL,
    id: 11,
    customId: 'fc-42-2026-10-08-SUNRISE',
    cacheReadInputTokens: 4726,
    cacheDiagnostics: MISS,
  };

  it('shows a badge per diagnosed call, with the reason and what the cache did to its tokens', async () => {
    getApiCalls.mockResolvedValue({ data: [ANTHROPIC_CALL, DIAGNOSED_CALL] });
    renderPanel();
    const toggle = await screen.findByTestId('diagnostics-cache-toggle');
    expect(toggle).toHaveTextContent('Prompt-cache diagnostics');
    expect(toggle).toHaveTextContent('1');
    openDisclosure('cache');
    expect(screen.getAllByTestId('cache-diagnostics-row')).toHaveLength(1);
    expect(screen.getByText('fc-42-2026-10-08-SUNRISE')).toBeInTheDocument();
    expect(screen.getByTestId('cache-badge').textContent)
      .toBe('cache: read 4,726 · miss — messages_changed (1,234 tok)');
  });

  it('tallies reasons across every diagnosed call but lists only the first 25', async () => {
    const many = Array.from({ length: 30 }, (_, i) => ({
      ...DIAGNOSED_CALL, id: 100 + i, customId: `fc-${i}-2026-10-08-SUNRISE`,
    }));
    const systemChanged = {
      ...DIAGNOSED_CALL,
      id: 200,
      customId: 'fc-x-2026-10-08-SUNRISE',
      cacheDiagnostics: '{"status":"MISS","reason":"system_changed","missedInputTokens":4800}',
    };
    getApiCalls.mockResolvedValue({ data: [...many, systemChanged] });
    renderPanel();
    expect(await screen.findByTestId('diagnostics-cache-toggle')).toHaveTextContent('31');
    openDisclosure('cache');
    expect(screen.getAllByTestId('cache-diagnostics-row')).toHaveLength(25);
    expect(screen.getByTestId('cache-diagnostics-more').textContent).toBe('+6 more');
    const tally = screen.getByTestId('cache-diagnostics-tally').textContent;
    expect(tally).toContain('messages_changed ×30');
    expect(tally).toContain('system_changed ×1');
  });

  it('words a pending comparison, and a reason with no token estimate', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...DIAGNOSED_CALL, id: 1, cacheReadInputTokens: 0, cacheDiagnostics: '{"status":"PENDING"}' },
      {
        ...DIAGNOSED_CALL,
        id: 2,
        cacheReadInputTokens: 0,
        cacheCreationInputTokens: 4726,
        cacheDiagnostics: '{"status":"MISS","reason":"previous_message_not_found"}',
      },
    ] });
    renderPanel();
    await screen.findByTestId('diagnostics-cache-toggle');
    openDisclosure('cache');
    expect(screen.getAllByTestId('cache-badge').map((b) => b.textContent)).toEqual([
      'cache: pending',
      'cache: write 4,726 · miss — previous_message_not_found',
    ]);
  });

  it('hides the row when no call carries diagnostics, or the field is not valid JSON', async () => {
    getApiCalls.mockResolvedValue({ data: [
      ANTHROPIC_CALL,
      { ...ANTHROPIC_CALL, id: 3, cacheDiagnostics: null },
      { ...ANTHROPIC_CALL, id: 4, cacheDiagnostics: 'not json' },
    ] });
    renderPanel();
    await screen.findByText('Anthropic');
    expect(screen.queryByTestId('diagnostics-cache-toggle')).not.toBeInTheDocument();
    expect(screen.queryByText(/Prompt-cache diagnostics/)).not.toBeInTheDocument();
  });
});

describe('JobRunPanel: frame 03 wording and narrow-screen overflow', () => {
  it.each([
    ['ANTHROPIC', 'Anthropic'],
    ['OPEN_METEO_FORECAST', 'Open-Meteo'],
    ['OPEN_METEO_AIR_QUALITY', 'Open-Meteo air quality'],
    ['WORLD_TIDES', 'WorldTides'],
    ['LIGHT_POLLUTION', 'Light pollution'],
  ])('words the %s service as "%s"', async (service, label) => {
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, service }] });
    renderPanel();
    expect(await screen.findByTestId(`job-run-service-${service}`)).toHaveTextContent(label);
  });

  it('falls back to the raw name for a service it has no wording for', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, service: 'SOMETHING_NEW' }] });
    renderPanel();
    expect(await screen.findByText('SOMETHING_NEW')).toBeInTheDocument();
  });

  it('reads "{n} calls · avg {ms} ms" with a middle dot', async () => {
    getApiCalls.mockResolvedValue({ data: [{ ...OPEN_METEO_CALL, durationMs: 210 }] });
    renderPanel();
    expect(await screen.findByText('1 calls · avg 210 ms')).toBeInTheDocument();
  });

  it('gives one failure wording per row, never both "failures" and "errors"', async () => {
    getApiCalls.mockResolvedValue({ data: [
      { ...ANTHROPIC_CALL, id: 1, succeeded: true },
      { ...ANTHROPIC_CALL, id: 2, succeeded: false, errorMessage: 'err' },
    ] });
    renderPanel();
    const row = await screen.findByTestId('job-run-service-ANTHROPIC');
    expect(row).toHaveTextContent('1 errors (50.00%)');
    expect(row).not.toHaveTextContent('failures');
    expect(row).not.toHaveTextContent('all ok');
  });

  it('words the batch row "Anthropic · batch" with the batch status label, not the enum', async () => {
    getBatchSummary.mockResolvedValue({ data: {
      totalInputTokens: 1, totalOutputTokens: 1, totalCacheReadTokens: 0, totalCacheCreationTokens: 0,
      estimatedCostMicroDollars: 1000, requestCount: 2, succeededCount: 2, erroredCount: 0, status: 'EXPIRED',
    } });
    renderPanel({ runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: msgbatch_x' }, { group: 'BATCHES' });
    expect(await screen.findByText('Anthropic · batch')).toBeInTheDocument();
    expect(screen.getByTestId('job-run-batch-row-status')).toHaveTextContent('Expired');
    expect(screen.getByTestId('job-run-batch-row-status')).not.toHaveTextContent('EXPIRED');
  });

  it('lets a long failed-call message, a custom id and the panel note break rather than overflow', async () => {
    getApiCalls.mockResolvedValue({ data: [{
      ...ANTHROPIC_CALL, id: 5, succeeded: false, errorMessage: 'e'.repeat(300), customId: 'fc-'.repeat(40),
      errorType: 'regex_fallback',
    }] });
    renderPanel({}, { status: 'FAILED', note: 'n'.repeat(300), noteTone: 'ERROR' });
    await screen.findByTestId('diagnostics-failed-toggle');
    openDisclosure('failed');
    expect(screen.getByText('e'.repeat(300)).className).toContain('[overflow-wrap:anywhere]');
    expect(screen.getByText('n'.repeat(300)).className).toContain('[overflow-wrap:anywhere]');
    openDisclosure('fallback');
    expect(screen.getByText('fc-'.repeat(40)).className).toContain('break-all');
  });
});

describe('JobRunPanel: dispositions', () => {
  it('shows the Dispositions section when the run has candidates', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 42, totalCount: 2, countsByDisposition: { EVALUATED: 2 },
      entries: [],
    } });
    renderPanel({ id: 42 });
    expect(await screen.findByTestId('disposition-breakdown')).toBeInTheDocument();
    await waitFor(() => expect(getDispositionBreakdown).toHaveBeenCalledWith(42));
  });

  it('does not say "No detail available" for a run whose only content is its dispositions', async () => {
    getDispositionBreakdown.mockResolvedValue({ data: {
      jobRunId: 42, totalCount: 1, countsByDisposition: { EVALUATED: 1 }, entries: [],
    } });
    renderPanel({ id: 42, completedAt: null, succeeded: 0 });
    await screen.findByTestId('disposition-breakdown');
    expect(screen.queryByTestId('job-run-no-detail')).toBeNull();
  });
});
