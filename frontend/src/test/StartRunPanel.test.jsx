import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import StartRunPanel from '../components/runs/StartRunPanel.jsx';

// ── Mocks ────────────────────────────────────────────────────────────────

vi.mock('../api/batchApi', () => ({
  getRegions: vi.fn(),
  submitScheduledBatch: vi.fn(),
  submitJfdiBatch: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  refreshTideData: vi.fn(),
  backfillTideData: vi.fn(),
  fetchLocations: vi.fn(),
}));

vi.mock('../api/auroraApi', () => ({
  enrichBortle: vi.fn(),
}));

vi.mock('../api/briefingApi.js', () => ({
  runBriefing: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: () => mockAuth,
}));

// The modals have their own tests; here only that the panel opens them, and what they report back.
vi.mock('../components/AuroraForecastModal', () => ({
  default: ({ onClose, onComplete }) => (
    <div role="dialog" aria-label="Aurora forecast">
      <button type="button" onClick={() => onComplete({ nights: [{ locationsScored: 4 }, { locationsScored: 2 }], estimatedCost: '$0.10' })}>
        finish
      </button>
      <button type="button" onClick={onClose}>close</button>
    </div>
  ),
}));
vi.mock('../components/AuroraSimulateModal', () => ({
  default: ({ onClose, onSuccess }) => (
    <div role="dialog" aria-label="Aurora simulation">
      <button type="button" onClick={() => onSuccess('Simulation on.')}>finish</button>
      <button type="button" onClick={onClose}>close</button>
    </div>
  ),
}));

vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: mockAurora, loading: false }),
}));

import { getRegions, submitScheduledBatch, submitJfdiBatch } from '../api/batchApi';
import { fetchLocations, refreshTideData, backfillTideData } from '../api/forecastApi';
import { enrichBortle } from '../api/auroraApi';
import { runBriefing } from '../api/briefingApi.js';

const MOCK_REGIONS = [
  { id: 1, name: 'Cumbria', locationCount: 5 },
  { id: 2, name: 'Ayrshire', locationCount: 3 },
  { id: 3, name: 'Borders', locationCount: 2 },
];

const BUSY = 'A run is already in progress. Wait for it to finish.';

let mockAuth;
let mockAurora;
const onRunStarted = vi.fn();
const onFinished = vi.fn();
const onClose = vi.fn();

const box = (testId) => screen.getByTestId(testId).querySelector('input');
const start = () => screen.getByTestId('start-run-submit');
const startIsDisabled = () => start().getAttribute('aria-disabled') === 'true';
const option = (key) => screen.getByTestId(`start-option-${key}`);

/** Chooses an option and presses Start, inside act so the handler's promise settles. */
async function chooseAndStart(key) {
  fireEvent.click(await screen.findByTestId(`start-option-${key}`));
  await act(async () => { fireEvent.click(start()); });
}

/** Waits for the regions fetch to land (the options render at once, from static markup). */
async function regionsLoaded() {
  await screen.findByTestId('batch-region-toggle-1');
}

function renderView() {
  return render(<StartRunPanel onRunStarted={onRunStarted} onFinished={onFinished} onClose={onClose} />);
}

beforeEach(() => {
  vi.resetAllMocks();
  mockAuth = { isAdmin: true, role: 'ADMIN' };
  mockAurora = null;
  getRegions.mockResolvedValue(MOCK_REGIONS);
  fetchLocations.mockResolvedValue([]);
});

// ── The options ───────────────────────────────────────────────────────────

describe('The options', () => {
  it('draws every option in its group with its name and one line of description', async () => {
    renderView();

    const group = screen.getByRole('radiogroup', { name: 'Run to start' });
    expect(within(group).getAllByRole('radio').map((r) => r.getAttribute('data-testid'))).toEqual([
      'start-option-scheduled-batch', 'start-option-jfdi-batch', 'start-option-briefing', 'start-option-aurora-forecast',
      'start-option-tide-data', 'start-option-tide-history', 'start-option-light-pollution',
    ]);
    expect(within(within(group).getByRole('group', { name: 'Forecast' })).getAllByRole('radio')).toHaveLength(4);
    expect(within(within(group).getByRole('group', { name: 'Data refresh' })).getAllByRole('radio')).toHaveLength(3);
    expect(screen.getAllByRole('radiogroup')).toHaveLength(1);
    expect(option('scheduled-batch')).toHaveTextContent('Same triage and stability gates as the overnight cycle.');
    expect(option('jfdi-batch')).toHaveTextContent('All locations, T to T+3, sunrise and sunset, no gates.');
    expect(option('briefing')).toHaveTextContent('Rebuilds the briefing from current ratings.');
    expect(option('aurora-forecast')).toHaveTextContent('Choose the nights to score.');
    expect(option('tide-history')).toHaveTextContent('12 months for seascapes, in 7-day chunks. Skips dates already stored.');
    expect(option('light-pollution')).toHaveTextContent('Locations without a Bortle rating.');
  });

  it('starts with the scheduled batch chosen, and the scheduled batch\'s label on the button', async () => {
    renderView();
    expect(option('scheduled-batch')).toHaveAttribute('aria-checked', 'true');
    expect(option('jfdi-batch')).toHaveAttribute('aria-checked', 'false');
    expect(start()).toHaveTextContent('Start scheduled batch');
  });

  it('labels the button "Start {option}" for each option, keeping JFDI in capitals', async () => {
    renderView();
    const labels = {
      'jfdi-batch': 'Start JFDI batch', briefing: 'Start briefing', 'aurora-forecast': 'Start aurora forecast',
      'tide-data': 'Start tide data', 'tide-history': 'Start tide history', 'light-pollution': 'Start light pollution',
    };
    for (const [key, label] of Object.entries(labels)) {
      fireEvent.click(option(key));
      expect(start()).toHaveTextContent(label);
    }
  });

  it('drops the tide count from its description until the locations have loaded', async () => {
    fetchLocations.mockResolvedValue([
      { id: 1, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: { name: 'Northumberland' } },
      { id: 2, name: 'Craster', enabled: true, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: { name: 'Northumberland' } },
    ]);
    renderView();
    expect(option('tide-data')).toHaveTextContent('Coastal locations from WorldTides.');
    expect(await screen.findByText('2 coastal locations from WorldTides.')).toBeInTheDocument();
  });

  it('says "1 coastal location" in the singular', async () => {
    fetchLocations.mockResolvedValue([
      { id: 1, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: { name: 'Northumberland' } },
    ]);
    renderView();
    expect(await screen.findByText('1 coastal location from WorldTides.')).toBeInTheDocument();
  });

  it('keeps only one option chosen, and the others\' scope closed', async () => {
    renderView();
    await regionsLoaded();
    fireEvent.click(option('briefing'));
    expect(option('briefing')).toHaveAttribute('aria-checked', 'true');
    expect(option('scheduled-batch')).toHaveAttribute('aria-checked', 'false');
    expect(screen.queryByTestId('start-scope-scheduled-batch')).toBeNull();
    expect(screen.queryByTestId('batch-region-toggle-1')).toBeNull();
  });

  it('shows no start-time scope for a plain option', async () => {
    renderView();
    fireEvent.click(option('briefing'));
    expect(screen.queryByTestId('start-scope-briefing')).toBeNull();
    expect(screen.getByTestId('start-run-scope')).toBeEmptyDOMElement();
  });

  it('has no run buttons from the retired manual forecast runs', async () => {
    renderView();
    await screen.findByTestId('start-option-scheduled-batch');
    expect(screen.queryByTestId('run-very-short-term-btn')).toBeNull();
    expect(screen.queryByTestId('run-short-term-btn')).toBeNull();
    expect(screen.queryByTestId('run-long-term-btn')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('draws nothing but the frame for a reader who is not an admin', async () => {
    mockAuth = { isAdmin: false, role: 'PRO_USER' };
    renderView();
    expect(screen.getByTestId('start-run-panel-title')).toBeInTheDocument();
    expect(screen.queryByTestId('start-run-card')).toBeNull();
    expect(screen.queryByTestId('start-run-submit')).toBeNull();
  });
});

// ── Arrow keys ────────────────────────────────────────────────────────────

describe('Arrow keys', () => {
  it('puts the one tab stop on the chosen option, across both groups', async () => {
    renderView();
    expect(option('scheduled-batch')).toHaveAttribute('tabindex', '0');
    for (const key of ['jfdi-batch', 'briefing', 'aurora-forecast', 'tide-data', 'tide-history', 'light-pollution']) {
      expect(option(key)).toHaveAttribute('tabindex', '-1');
    }
  });

  it('moves the choice and the focus down and right, and wraps at the end of the whole list', async () => {
    renderView();
    option('scheduled-batch').focus();

    fireEvent.keyDown(option('scheduled-batch'), { key: 'ArrowDown' });
    expect(option('jfdi-batch')).toHaveAttribute('aria-checked', 'true');
    expect(option('jfdi-batch')).toHaveFocus();
    expect(option('jfdi-batch')).toHaveAttribute('tabindex', '0');
    expect(option('scheduled-batch')).toHaveAttribute('tabindex', '-1');

    fireEvent.keyDown(option('jfdi-batch'), { key: 'ArrowRight' });
    expect(option('briefing')).toHaveFocus();
    fireEvent.keyDown(option('briefing'), { key: 'ArrowDown' });
    // The next option after the last forecast one is the first data refresh one: one group of seven.
    fireEvent.keyDown(option('aurora-forecast'), { key: 'ArrowDown' });
    expect(option('tide-data')).toHaveAttribute('aria-checked', 'true');
    expect(option('tide-data')).toHaveFocus();
    fireEvent.keyDown(option('tide-data'), { key: 'ArrowDown' });
    fireEvent.keyDown(option('tide-history'), { key: 'ArrowDown' });
    fireEvent.keyDown(option('light-pollution'), { key: 'ArrowDown' });
    expect(option('scheduled-batch')).toHaveAttribute('aria-checked', 'true');
    expect(option('scheduled-batch')).toHaveFocus();
  });

  it('moves up and left, and wraps at the start', async () => {
    renderView();
    fireEvent.keyDown(option('scheduled-batch'), { key: 'ArrowUp' });
    expect(option('light-pollution')).toHaveAttribute('aria-checked', 'true');
    expect(option('light-pollution')).toHaveFocus();
    fireEvent.keyDown(option('light-pollution'), { key: 'ArrowLeft' });
    expect(option('tide-history')).toHaveAttribute('aria-checked', 'true');
  });

  it('Home goes to the first option and End to the last', async () => {
    renderView();
    fireEvent.keyDown(option('scheduled-batch'), { key: 'End' });
    expect(option('light-pollution')).toHaveAttribute('aria-checked', 'true');
    expect(option('light-pollution')).toHaveFocus();
    fireEvent.keyDown(option('light-pollution'), { key: 'Home' });
    expect(option('scheduled-batch')).toHaveAttribute('aria-checked', 'true');
    expect(option('scheduled-batch')).toHaveFocus();
  });

  it('ignores other keys', async () => {
    renderView();
    fireEvent.keyDown(option('scheduled-batch'), { key: 'a' });
    expect(option('scheduled-batch')).toHaveAttribute('aria-checked', 'true');
  });
});

describe('Radio semantics', () => {
  it('names each radio by its name and describes it by its description', async () => {
    renderView();
    const radio = screen.getByRole('radio', { name: 'JFDI batch' });
    expect(radio).toHaveAccessibleDescription('All locations, T to T+3, sunrise and sunset, no gates.');
  });

  it('wraps the chosen option\'s scope in a labelled group the radio controls, outside the radio itself', async () => {
    renderView();
    await regionsLoaded();
    const scope = screen.getByRole('group', { name: 'Scheduled batch scope' });
    expect(scope).toContainElement(screen.getByTestId('batch-region-toggle-1'));
    expect(option('scheduled-batch')).toHaveAttribute('aria-controls', scope.id);
    expect(option('scheduled-batch')).not.toContainElement(scope);
    expect(option('briefing')).not.toHaveAttribute('aria-controls');
  });
});

// ── Batch scope ───────────────────────────────────────────────────────────

describe('Batch scope', () => {
  it('expands the region checklist with every region checked, for the scheduled batch', async () => {
    renderView();
    await regionsLoaded();

    expect(screen.getByTestId('start-scope-scheduled-batch')).toBeInTheDocument();
    expect(box('batch-region-toggle-all')).toBeChecked();
    expect(box('batch-region-toggle-1')).toBeChecked();
    expect(box('batch-region-toggle-2')).toBeChecked();
    expect(box('batch-region-toggle-3')).toBeChecked();
  });

  it('expands the same checklist under the JFDI batch', async () => {
    renderView();
    fireEvent.click(option('jfdi-batch'));
    await regionsLoaded();
    expect(screen.getByTestId('start-scope-jfdi-batch')).toContainElement(screen.getByTestId('batch-region-toggle-1'));
  });

  it('shows each region name and location count, and the total in the "All regions" row', async () => {
    renderView();
    await regionsLoaded();

    expect(screen.getByTestId('batch-region-toggle-1')).toHaveTextContent('Cumbria');
    expect(screen.getByTestId('batch-region-count-1')).toHaveTextContent('5');
    expect(screen.getByTestId('batch-region-toggle-2')).toHaveTextContent('Ayrshire');
    expect(screen.getByTestId('batch-region-count-2')).toHaveTextContent('3');
    expect(screen.getByTestId('batch-region-toggle-3')).toHaveTextContent('Borders');
    expect(screen.getByTestId('batch-region-count-3')).toHaveTextContent('2');
    // 5 + 3 + 2 = 10 locations
    expect(screen.getByTestId('batch-region-toggle-all')).toHaveTextContent('All regions');
    expect(screen.getByTestId('batch-region-count-all')).toHaveTextContent('10 locations');
  });

  it('loads regions on mount from the API, once', async () => {
    renderView();
    await waitFor(() => expect(getRegions).toHaveBeenCalledTimes(1));
  });

  it('says what the footer will start: "{n} of {m} regions · {k} locations"', async () => {
    renderView();
    await regionsLoaded();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('3 of 3 regions · 10 locations');

    fireEvent.click(box('batch-region-toggle-1'));
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('2 of 3 regions · 5 locations');
  });

  it('unchecking a region reduces the selection', async () => {
    renderView();
    await regionsLoaded();

    fireEvent.click(box('batch-region-toggle-1'));

    expect(box('batch-region-toggle-1')).not.toBeChecked();
    expect(box('batch-region-toggle-all')).not.toBeChecked();
    expect(box('batch-region-toggle-all').indeterminate).toBe(true);
    expect(start()).toBeEnabled();
  });

  it('regions arriving after the panel opens are all selected, not none', async () => {
    // The race, made deterministic. The options render before getRegions() resolves, so an admin can choose a batch
    // against an empty roster. The old code snapshotted the selection at that instant, so the regions then rendered
    // UNCHECKED, and clicking one ADDED it rather than removing it. Submitting from that state sent an empty list.
    let resolveRegions;
    getRegions.mockReturnValue(new Promise((resolve) => { resolveRegions = resolve; }));
    renderView();
    await screen.findByTestId('start-option-scheduled-batch');
    expect(screen.getByText('Loading regions…')).toBeInTheDocument();

    await act(async () => { resolveRegions(MOCK_REGIONS); });

    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('3 of 3 regions');
    expect(box('batch-region-toggle-1')).toBeChecked();

    // And the toggle now REMOVES, which is the half that was inverted.
    fireEvent.click(box('batch-region-toggle-1'));
    expect(box('batch-region-toggle-1')).not.toBeChecked();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('2 of 3 regions');
  });

  it('"All regions" deselects all when all are selected, and selects all again when none are', async () => {
    renderView();
    await regionsLoaded();

    fireEvent.click(box('batch-region-toggle-all'));
    for (const id of [1, 2, 3]) expect(box(`batch-region-toggle-${id}`)).not.toBeChecked();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('0 of 3 regions · 0 locations');

    fireEvent.click(box('batch-region-toggle-all'));
    for (const id of [1, 2, 3]) expect(box(`batch-region-toggle-${id}`)).toBeChecked();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('3 of 3 regions');
  });

  it('re-checking a region adds it back', async () => {
    renderView();
    await regionsLoaded();

    fireEvent.click(box('batch-region-toggle-2'));
    expect(box('batch-region-toggle-2')).not.toBeChecked();
    fireEvent.click(box('batch-region-toggle-2'));
    expect(box('batch-region-toggle-2')).toBeChecked();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('3 of 3 regions');
  });

  it('says "1 of 1 region" for a single-region roster', async () => {
    getRegions.mockResolvedValue([MOCK_REGIONS[0]]);
    renderView();
    await regionsLoaded();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('1 of 1 region · 5 locations');
  });

  it('disables Start with nothing selected, because the API reads [] as ALL, and never sends it', async () => {
    // The footgun: ForecastTaskCollector:729 documents "null or empty means all regions", so an empty list on the
    // wire runs the WHOLE roster. Deselect everything and the screen says "0 of 3 regions" while the backend would
    // evaluate all three, at full batch cost.
    renderView();
    await regionsLoaded();
    fireEvent.click(box('batch-region-toggle-all'));

    expect(startIsDisabled()).toBe(true);
    fireEvent.click(start());
    expect(submitScheduledBatch).not.toHaveBeenCalled();
    expect(submitJfdiBatch).not.toHaveBeenCalled();

    fireEvent.click(option('jfdi-batch'));
    expect(startIsDisabled()).toBe(true);
    fireEvent.click(start());
    expect(submitJfdiBatch).not.toHaveBeenCalled();

    // Choosing another option is not held back by the batch selection.
    fireEvent.click(option('briefing'));
    expect(startIsDisabled()).toBe(false);
  });

  it('with no regions served, still starts "all" (null) rather than disabling', async () => {
    getRegions.mockResolvedValue([]);
    submitScheduledBatch.mockResolvedValue({ jobRunId: 5 });
    renderView();
    await screen.findByText('Every region will run.');
    await act(async () => { fireEvent.click(start()); });
    expect(submitScheduledBatch).toHaveBeenCalledWith(null);
  });
});

// ── Batch submission ──────────────────────────────────────────────────────

describe('Batch submission', () => {
  it('scheduled batch sends null regionIds when all regions are selected', async () => {
    submitScheduledBatch.mockResolvedValue({ jobRunId: 9 });
    renderView();
    await regionsLoaded();

    await act(async () => { fireEvent.click(start()); });

    expect(submitScheduledBatch).toHaveBeenCalledWith(null);
    expect(submitJfdiBatch).not.toHaveBeenCalled();
  });

  it('scheduled batch sends the specific regionIds when a subset is selected', async () => {
    submitScheduledBatch.mockResolvedValue({ jobRunId: 9 });
    renderView();
    await regionsLoaded();
    fireEvent.click(box('batch-region-toggle-3'));

    await act(async () => { fireEvent.click(start()); });

    expect(submitScheduledBatch).toHaveBeenCalledTimes(1);
    const passedIds = submitScheduledBatch.mock.calls[0][0];
    expect(passedIds).toHaveLength(2);
    expect(passedIds).toContain(1);
    expect(passedIds).toContain(2);
  });

  it('JFDI batch calls submitJfdiBatch with null when all are selected', async () => {
    submitJfdiBatch.mockResolvedValue({ jobRunId: 9 });
    renderView();
    await regionsLoaded();
    fireEvent.click(option('jfdi-batch'));

    await act(async () => { fireEvent.click(start()); });

    expect(submitJfdiBatch).toHaveBeenCalledWith(null);
    expect(submitScheduledBatch).not.toHaveBeenCalled();
  });

  it('JFDI batch sends the specific regionIds when a subset is selected', async () => {
    submitJfdiBatch.mockResolvedValue({ jobRunId: 9 });
    renderView();
    await regionsLoaded();
    fireEvent.click(option('jfdi-batch'));
    fireEvent.click(box('batch-region-toggle-all'));
    fireEvent.click(box('batch-region-toggle-2'));

    await act(async () => { fireEvent.click(start()); });

    expect(submitJfdiBatch).toHaveBeenCalledWith([2]);
  });

  it('hands the new run\'s id over, and does not toast, when the batch returns one', async () => {
    submitScheduledBatch.mockResolvedValue({ jobRunId: 41, batchId: 'batch-001', requestCount: 42 });
    renderView();
    await regionsLoaded();

    await act(async () => { fireEvent.click(start()); });

    expect(onRunStarted).toHaveBeenCalledWith(41);
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('finishes with the batch\'s own summary when it returns a batch id but no run id', async () => {
    submitScheduledBatch.mockResolvedValue({ batchId: 'batch-001', requestCount: 42 });
    renderView();
    await regionsLoaded();

    await act(async () => { fireEvent.click(start()); });

    expect(onFinished).toHaveBeenCalledWith('Scheduled batch submitted: 42 request(s), batch batch-001.');
    expect(onRunStarted).not.toHaveBeenCalled();
  });

  it('finishes with "Started." when the batch returns neither a run id nor a batch id', async () => {
    submitJfdiBatch.mockResolvedValue({});
    renderView();
    await regionsLoaded();
    fireEvent.click(option('jfdi-batch'));

    await act(async () => { fireEvent.click(start()); });

    expect(onFinished).toHaveBeenCalledWith('Started.');
  });

  it('says the busy sentence on a 409, and does not finish', async () => {
    submitScheduledBatch.mockRejectedValue({ response: { status: 409 } });
    renderView();
    await regionsLoaded();

    await act(async () => { fireEvent.click(start()); });

    expect(screen.getByTestId('start-run-status')).toHaveTextContent(BUSY);
    expect(onRunStarted).not.toHaveBeenCalled();
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('shows a generic error for a non-409 batch failure', async () => {
    submitJfdiBatch.mockRejectedValue({ response: { status: 500 } });
    renderView();
    await regionsLoaded();
    fireEvent.click(option('jfdi-batch'));

    await act(async () => { fireEvent.click(start()); });

    expect(screen.getByTestId('start-run-status')).toHaveTextContent('JFDI batch submission failed. Check the logs.');
    expect(screen.getByTestId('start-run-status')).not.toHaveTextContent('in progress');
  });

  it('Cancel closes the panel without submitting', async () => {
    renderView();
    await regionsLoaded();

    fireEvent.click(screen.getByTestId('start-run-cancel'));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(submitScheduledBatch).not.toHaveBeenCalled();
  });
});

// ── Briefing and the other starts ─────────────────────────────────────────

describe('Briefing, tide data, tide history and light pollution', () => {
  it('a briefing finishes with the server\'s status as the toast', async () => {
    runBriefing.mockResolvedValue({ status: 'Briefing refreshed.' });
    renderView();

    await chooseAndStart('briefing');

    expect(runBriefing).toHaveBeenCalledTimes(1);
    expect(onFinished).toHaveBeenCalledWith('Briefing refreshed.');
    expect(onRunStarted).not.toHaveBeenCalled();
  });

  it('a briefing with no status toasts "Started."', async () => {
    runBriefing.mockResolvedValue({});
    renderView();
    await chooseAndStart('briefing');
    expect(onFinished).toHaveBeenCalledWith('Started.');
  });

  it('tide data answers with a status and a run type, no run id, so it toasts the status', async () => {
    refreshTideData.mockResolvedValue({ status: 'Tide refresh started.', runType: 'TIDE' });
    renderView();

    await chooseAndStart('tide-data');

    expect(refreshTideData).toHaveBeenCalledTimes(1);
    expect(onFinished).toHaveBeenCalledWith('Tide refresh started.');
    expect(onRunStarted).not.toHaveBeenCalled();
  });

  it('tide history calls the backfill and toasts its status', async () => {
    backfillTideData.mockResolvedValue({ status: 'Backfill started.' });
    renderView();

    await chooseAndStart('tide-history');

    expect(backfillTideData).toHaveBeenCalledTimes(1);
    expect(onFinished).toHaveBeenCalledWith('Backfill started.');
  });

  it('tide history failure says so, as a failure', async () => {
    backfillTideData.mockRejectedValue({ response: { status: 500 } });
    renderView();
    await chooseAndStart('tide-history');
    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Tide backfill failed. Check the logs.');
  });

  it('light pollution hands its run id over when the server returns one', async () => {
    enrichBortle.mockResolvedValue({ jobRunId: 77 });
    renderView();

    await chooseAndStart('light-pollution');

    expect(onRunStarted).toHaveBeenCalledWith(77);
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('light pollution with no run id hands none over, and toasts "Started."', async () => {
    enrichBortle.mockResolvedValue({});
    renderView();

    await chooseAndStart('light-pollution');

    expect(onRunStarted).not.toHaveBeenCalled();
    expect(onFinished).toHaveBeenCalledWith('Started.');
  });

  it('says the key is not configured on a 400, and still reports other failures generically', async () => {
    enrichBortle.mockRejectedValueOnce({ response: { status: 400 } });
    renderView();
    await chooseAndStart('light-pollution');
    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Light pollution API key not configured.');

    enrichBortle.mockRejectedValueOnce({ response: { status: 500 } });
    await act(async () => { fireEvent.click(start()); });
    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Light pollution refresh failed.');
  });

  it('clears an old error when another option is chosen', async () => {
    enrichBortle.mockRejectedValue({ response: { status: 500 } });
    renderView();
    await chooseAndStart('light-pollution');
    expect(screen.getByTestId('start-run-status')).toBeInTheDocument();

    fireEvent.click(option('briefing'));

    expect(screen.getByTestId('start-run-status')).toHaveTextContent('');
    expect(screen.getByTestId('start-run-status-error')).toBeEmptyDOMElement();
  });
});

describe('The status line is a live region that is always mounted', () => {
  it('is mounted and empty before anything is written, as an alert and a status', async () => {
    renderView();
    expect(screen.getByRole('alert')).toBeEmptyDOMElement();
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('writes an error into the same mounted alert', async () => {
    runBriefing.mockRejectedValue({ response: { status: 500 } });
    renderView();
    const alert = screen.getByRole('alert');

    await chooseAndStart('briefing');

    expect(screen.getByRole('alert')).toBe(alert);
    expect(alert).toHaveTextContent('Briefing refresh failed. Check the logs.');
    expect(screen.getByRole('status')).toBeEmptyDOMElement();
  });

  it('writes the simulation\'s success into the mounted status, not the alert', async () => {
    renderView();
    const status = screen.getByRole('status');
    fireEvent.click(screen.getByTestId('aurora-simulate-btn'));
    await screen.findByRole('dialog', { name: 'Aurora simulation' });
    fireEvent.click(screen.getByRole('button', { name: 'finish' }));
    expect(screen.getByRole('status')).toBe(status);
    expect(status).toHaveTextContent('Simulation on.');
    expect(screen.getByRole('alert')).toBeEmptyDOMElement();
  });

  it('keeps focus on Start while the request is in flight and after it fails', async () => {
    let fail;
    runBriefing.mockReturnValue(new Promise((_resolve, reject) => { fail = reject; }));
    renderView();
    fireEvent.click(option('briefing'));
    start().focus();

    await act(async () => { fireEvent.click(start()); });
    expect(start()).toHaveFocus();
    expect(startIsDisabled()).toBe(true);

    await act(async () => { fail({ response: { status: 409 } }); });
    expect(start()).toHaveFocus();
    expect(document.body).not.toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent(BUSY);
  });

  it('ignores a second press of Start while a run is in flight', async () => {
    runBriefing.mockReturnValue(new Promise(() => {}));
    renderView();
    fireEvent.click(option('briefing'));
    await act(async () => { fireEvent.click(start()); });
    await act(async () => { fireEvent.click(start()); });
    expect(runBriefing).toHaveBeenCalledTimes(1);
  });
});

// ── Refused runs (409) ────────────────────────────────────────────────────
//
// The briefing and the tide refresh are refused by the backend while one is already running — the briefing typically
// behind the pipeline's own build, the tide refresh behind the schedule, the Scheduler's Run now or an earlier press
// here. A refusal is not a failure and has nothing in the logs to check, so the status line must say which it is.
// Each 409 test is paired with a non-409 one: a component that printed the "in progress" line for every error would
// pass the first alone.

describe('Refused runs', () => {
  const REFUSED = { response: { status: 409 } };
  // The sharpest non-409 inputs: an adjacent 4xx, and a network error with no response at all (which a dropped
  // optional chain in `err?.response?.status` would turn into a throw).
  const BAD_REQUEST = { response: { status: 400 } };
  const NO_RESPONSE = new Error('Network Error');

  it.each([
    ['briefing', () => runBriefing],
    ['tide-data', () => refreshTideData],
    ['tide-history', () => backfillTideData],
    ['light-pollution', () => enrichBortle],
  ])('says the busy sentence when the backend refuses a %s', async (key, api) => {
    api().mockRejectedValue(REFUSED);
    renderView();

    await chooseAndStart(key);

    expect(screen.getByTestId('start-run-status')).toHaveTextContent(BUSY);
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('still reports a network error on a briefing run as a failure', async () => {
    runBriefing.mockRejectedValue(NO_RESPONSE);
    renderView();

    await chooseAndStart('briefing');

    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Briefing refresh failed. Check the logs.');
    expect(screen.queryByText(/already in progress/)).toBeNull();
  });

  it('still reports a 400 on a tide refresh as a failure', async () => {
    refreshTideData.mockRejectedValue(BAD_REQUEST);
    renderView();

    await chooseAndStart('tide-data');

    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Tide data refresh failed. Check the logs.');
    expect(screen.queryByText(/already in progress/)).toBeNull();
  });
});

// ── Tide scope ────────────────────────────────────────────────────────────

describe('When the regions fail to load', () => {
  it('says so plainly, keeps Start usable, and sends null (every region)', async () => {
    getRegions.mockRejectedValue(new Error('down'));
    submitScheduledBatch.mockResolvedValue({ jobRunId: 3 });
    renderView();

    expect(await screen.findByText("Couldn't load regions; every region will run.")).toBeInTheDocument();
    expect(screen.queryByText('Loading regions…')).toBeNull();
    expect(startIsDisabled()).toBe(false);
    expect(screen.getByTestId('start-run-scope')).toBeEmptyDOMElement();

    await act(async () => { fireEvent.click(start()); });
    expect(submitScheduledBatch).toHaveBeenCalledWith(null);
  });
});

describe('When the locations fail to load', () => {
  it('says so for both tide options, and the run still starts', async () => {
    fetchLocations.mockRejectedValue(new Error('down'));
    refreshTideData.mockResolvedValue({ status: 'Tide refresh started.' });
    renderView();
    fireEvent.click(option('tide-data'));

    expect(await screen.findByText("Couldn't load locations; the run will still start.")).toBeInTheDocument();
    expect(screen.queryByText('Loading locations…')).toBeNull();
    fireEvent.click(option('tide-history'));
    expect(screen.getByText("Couldn't load locations; the run will still start.")).toBeInTheDocument();

    fireEvent.click(option('tide-data'));
    await act(async () => { fireEvent.click(start()); });
    expect(refreshTideData).toHaveBeenCalledTimes(1);
  });
});

describe('Tide scope', () => {
  const LOCATIONS = [
    { id: 1, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: { name: 'Northumberland' } },
    { id: 2, name: 'Durham', enabled: true, locationType: ['LANDSCAPE'], tideType: [], region: { name: 'Durham' } },
    { id: 3, name: 'Dunstanburgh', enabled: true, locationType: ['LANDSCAPE'], tideType: ['LOW'], region: { name: 'Northumberland' } },
    { id: 4, name: 'Off Spot', enabled: false, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: { name: 'Fife' } },
  ];

  it('lists the coastal locations grouped by region, read-only, with no region checkboxes or slot picker', async () => {
    fetchLocations.mockResolvedValue(LOCATIONS);
    renderView();
    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    await act(async () => {});

    fireEvent.click(option('tide-data'));

    const scope = within(screen.getByTestId('start-scope-tide-data'));
    expect(scope.getByText('Northumberland (2)')).toBeInTheDocument();
    expect(scope.getByText('Bamburgh, Dunstanburgh')).toBeInTheDocument();
    expect(scope.queryByText(/Durham/)).toBeNull();
    expect(scope.queryByText(/Off Spot/)).toBeNull();
    expect(scope.getByText('2 locations total')).toBeInTheDocument();
    expect(scope.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByTestId('confirm-dialog-slots')).toBeNull();
    expect(screen.queryByTestId('confirm-dialog-strategies')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('2 locations');
  });

  it('lists only the coastal seascapes for the tide history, and counts them in the footer', async () => {
    fetchLocations.mockResolvedValue(LOCATIONS);
    renderView();
    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    await act(async () => {});

    fireEvent.click(option('tide-history'));

    const scope = within(screen.getByTestId('start-scope-tide-history'));
    expect(scope.getByText('Bamburgh')).toBeInTheDocument();
    expect(scope.queryByText(/Dunstanburgh/)).toBeNull();
    expect(scope.getByText('1 location total')).toBeInTheDocument();
    expect(screen.getByTestId('start-run-scope')).toHaveTextContent('1 location');
  });

  it('says so when no location matches', async () => {
    fetchLocations.mockResolvedValue([]);
    renderView();
    await act(async () => {});
    fireEvent.click(option('tide-data'));
    expect(screen.getByText('No matching locations found.')).toBeInTheDocument();
  });

  it('says "Loading locations…" until the locations arrive, and shows no footer scope', async () => {
    fetchLocations.mockReturnValue(new Promise(() => {}));
    renderView();
    fireEvent.click(option('tide-data'));
    expect(screen.getByText('Loading locations…')).toBeInTheDocument();
    expect(screen.getByTestId('start-run-scope')).toBeEmptyDOMElement();
  });
});

// ── In flight ─────────────────────────────────────────────────────────────

describe('While a run started here is in flight', () => {
  it('disables every option, the checklist and the Start button, and enables them again after', async () => {
    let finish;
    runBriefing.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    renderView();
    await regionsLoaded();
    fireEvent.click(option('briefing'));

    await act(async () => { fireEvent.click(start()); });

    for (const key of [
      'scheduled-batch', 'jfdi-batch', 'briefing', 'aurora-forecast', 'tide-data', 'tide-history', 'light-pollution',
    ]) {
      expect(option(key)).toBeDisabled();
    }
    expect(startIsDisabled()).toBe(true);

    await act(async () => { finish({ status: 'ok' }); });
    expect(option('scheduled-batch')).toBeEnabled();
    expect(startIsDisabled()).toBe(false);
  });

  it('disables the region checkboxes too', async () => {
    let finish;
    submitScheduledBatch.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    renderView();
    await regionsLoaded();

    await act(async () => { fireEvent.click(start()); });

    expect(box('batch-region-toggle-1')).toBeDisabled();
    expect(box('batch-region-toggle-all')).toBeDisabled();
    await act(async () => { finish({ jobRunId: 1 }); });
    expect(box('batch-region-toggle-1')).toBeEnabled();
  });

  it('keeps the Simulate button usable, as the simulation is not a run', async () => {
    runBriefing.mockReturnValue(new Promise(() => {}));
    renderView();
    fireEvent.click(option('briefing'));
    await act(async () => { fireEvent.click(start()); });
    expect(screen.getByTestId('aurora-simulate-btn')).toBeEnabled();
  });
});

// ── The aurora options ────────────────────────────────────────────────────

describe('Aurora forecast and simulation', () => {
  it('opens the aurora forecast modal from Start, and toasts its result when it completes', async () => {
    renderView();
    fireEvent.click(option('aurora-forecast'));
    fireEvent.click(start());
    expect(await screen.findByRole('dialog', { name: 'Aurora forecast' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'finish' }));

    expect(onFinished).toHaveBeenCalledWith('Aurora forecast complete: 2 nights, 6 locations scored. Cost: $0.10.');
  });

  it('does not run anything when the aurora forecast modal is only closed', async () => {
    renderView();
    fireEvent.click(option('aurora-forecast'));
    fireEvent.click(start());
    fireEvent.click(await screen.findByRole('button', { name: 'close' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onFinished).not.toHaveBeenCalled();
  });

  it('shows the simulation row Off, and opens the simulation modal from Simulate…', async () => {
    renderView();
    expect(screen.getByTestId('aurora-simulation-state')).toHaveTextContent('Off.');
    expect(screen.getByTestId('start-group-testing')).toHaveTextContent('Injects fake NOAA data so the aurora UI can be tested.');
    fireEvent.click(screen.getByTestId('aurora-simulate-btn'));
    expect(await screen.findByRole('dialog', { name: 'Aurora simulation' })).toBeInTheDocument();
  });

  it('shows the simulation On when the aurora status says it is simulated', async () => {
    mockAurora = { simulated: true };
    renderView();
    expect(screen.getByTestId('aurora-simulation-state')).toHaveTextContent('On.');
  });

  it('reports the simulation modal\'s message in the panel and does not finish', async () => {
    renderView();
    fireEvent.click(screen.getByTestId('aurora-simulate-btn'));
    await screen.findByRole('dialog', { name: 'Aurora simulation' });

    fireEvent.click(screen.getByRole('button', { name: 'finish' }));

    expect(screen.getByTestId('start-run-status')).toHaveTextContent('Simulation on.');
    expect(onFinished).not.toHaveBeenCalled();
    expect(onRunStarted).not.toHaveBeenCalled();
  });
});

// ── The frame ─────────────────────────────────────────────────────────────

describe('The panel frame', () => {
  it('is titled "Start a run", with its subtitle, and closes from its ×', async () => {
    renderView();
    expect(screen.getByTestId('start-run-panel-title')).toHaveTextContent('Start a run');
    expect(screen.getByTestId('start-run-panel-subtitle'))
      .toHaveTextContent('Runs start straight away and appear at the top of the list.');
    fireEvent.click(screen.getByTestId('start-run-panel-close'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('names its three groups with real headings, in sentence case', async () => {
    renderView();
    const headings = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(headings).toEqual(['Forecast', 'Data refresh', 'Testing']);
  });

  it('is never a dialog itself, and carries no emoji labels', async () => {
    renderView();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('start-run-panel').textContent).not.toMatch(/[\u{1F300}-\u{1FAFF}⚙⟳]/u);
  });
});
