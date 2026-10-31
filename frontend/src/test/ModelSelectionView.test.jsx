import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import ModelSelectionView, { modelInfoFor, batchDescriptionFor } from '../components/ModelSelectionView.jsx';

// Mock the API modules
vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
  setActiveModel: vi.fn(),
  setExtendedThinking: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

import { getAvailableModels, setActiveModel, setExtendedThinking } from '../api/modelsApi';
import { useAuth } from '../context/AuthContext.jsx';

const MOCK_DATA = {
  available: ['HAIKU', 'SONNET', 'OPUS'],
  configs: {
    BRIEFING_BEST_BET: 'HAIKU',
    AURORA_EVALUATION: 'HAIKU',
    BATCH_NEAR_TERM: 'SONNET',
    BATCH_FAR_TERM: 'HAIKU',
  },
};

const ET_ENABLED = 'Extended thinking enabled for Briefing';
const SWITCHED = 'Briefing model switched to Sonnet 4.6';

describe('ModelSelectionView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // clearAllMocks leaves mockResolvedValueOnce queues in place, so a test that fails mid-way
    // would hand its unconsumed responses to the next test's first toggle.
    setExtendedThinking.mockReset();
    useAuth.mockReturnValue({ isAdmin: true });
    getAvailableModels.mockResolvedValue(MOCK_DATA);
  });

  describe('the transient success banner', () => {
    /** Flushes pending promises without moving the fake clock at all. */
    async function pump() {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
    }

    /**
     * ⚠️ Frozen clock, NOT `shouldAdvanceTime`. That option ties the fake clock to real time, so the
     * wall time spent getting the banner on screen comes straight out of the 3s window being
     * measured — with it, the "still showing at 2999ms" assertion below fails outright, because the
     * banner has already been dismissed by elapsed real time. Pumping with a zero advance settles
     * the mount fetches and the toggle's PUT while the clock stays exactly where it is.
     */
    it('holds the banner until the dismiss delay elapses, then clears it', async () => {
      vi.useFakeTimers();
      try {
        setExtendedThinking.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', extendedThinking: true });
        render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('toggle-extended-thinking'));
        await pump();
        expect(screen.getByText(ET_ENABLED)).toBeInTheDocument();

        // Just short of the delay it must still be up: without this the constant is pinned only
        // from above, and shortening it (3000 → 500) would flash the message past unnoticed with
        // every assertion here still green.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(2999);
        });
        expect(screen.getByText(ET_ENABLED)).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(1);
        });
        expect(screen.queryByText(ET_ENABLED)).not.toBeInTheDocument();
      } finally {
        vi.useRealTimers();
      }
    });

    /**
     * A newer message gets its own full window. The handlers used to arm an independent 3s timer
     * each, so a second toggle 2s after the first had its message wiped 1s later by the FIRST
     * timer. Keying the dismiss on `success` means a new message re-runs the effect, whose cleanup
     * cancels the old timer — so at 3.5s, past the first message's deadline, the second must still
     * be showing.
     */
    it('gives a newer message its full window rather than inheriting the older deadline', async () => {
      vi.useFakeTimers();
      try {
        setExtendedThinking.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', extendedThinking: true });
        setActiveModel.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', active: 'SONNET' });
        render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('toggle-extended-thinking'));
        await pump();
        expect(screen.getByText(ET_ENABLED)).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(2000);
        });
        fireEvent.click(screen.getByTestId('switch-BRIEFING_BEST_BET-SONNET'));
        await pump();
        expect(screen.getByText(SWITCHED)).toBeInTheDocument();

        // 3.5s in: the first message's deadline has passed, the second's has not.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.getByText(SWITCHED)).toBeInTheDocument();

        // 5s in: now the second message's own window has elapsed.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.queryByText(SWITCHED)).not.toBeInTheDocument();
      } finally {
        vi.useRealTimers();
      }
    });

    /**
     * ⚠️ The regression guard against moving the dismiss back into the handlers. They arm nothing
     * until *after* their network await, so a tab change mid-request (`ManageView` renders this
     * behind `activeTab === 'models'`, unmounting it synchronously) happens before any timer
     * exists — an unmount cleanup has nothing to cancel, and a handler-armed timer would then be
     * created with no owner. The effect idiom has no such window, because an effect never runs
     * after unmount. The first cut of this fix armed in the handler behind a cleanup and leaked
     * exactly here; a test that unmounts AFTER the request settles cannot see it.
     */
    it('does not arm a dismiss timer when the request settles after it unmounts', async () => {
      const realSetTimeout = globalThis.setTimeout;
      const scheduledAfterUnmount = [];
      let unmounted = false;
      let releaseRequest;

      setExtendedThinking.mockReturnValue(new Promise((resolve) => {
        releaseRequest = () => resolve({ runType: 'BRIEFING_BEST_BET', extendedThinking: true });
      }));

      const setSpy = vi.spyOn(globalThis, 'setTimeout').mockImplementation((fn, ms, ...rest) => {
        const id = realSetTimeout(fn, ms, ...rest);
        if (ms === 3000 && unmounted) scheduledAfterUnmount.push(id);
        return id;
      });

      try {
        const { unmount } = render(<ModelSelectionView />);
        fireEvent.click(await screen.findByTestId('toggle-extended-thinking'));
        // The request must genuinely be in flight, or a toggle that never reached the handler
        // would leave nothing to arm and pass this test for the wrong reason.
        expect(setExtendedThinking).toHaveBeenCalledTimes(1);
        unmount();
        unmounted = true;

        await act(async () => {
          releaseRequest();
          await Promise.resolve();
        });

        expect(scheduledAfterUnmount).toEqual([]);
      } finally {
        setSpy.mockRestore();
      }
    });

    /**
     * ⚠️ The regression this file had no cover for. The banner's auto-dismiss used to be a bare
     * `setTimeout(() => setSuccess(null), 3000)`, so unmounting inside that window left the callback
     * to run against a torn-down tree — `window is not defined` out of React's `dispatchSetState`.
     * Vitest fails a run on an unhandled error while still reporting every test as passing; it
     * surfaced once in six full-suite runs as an intermittent red build.
     *
     * Asserted by requiring the PENDING banner timer — the last one scheduled at the banner delay —
     * to be cleared BY THE UNMOUNT. The spy sees `globalThis.setTimeout`, so it has no caller
     * attribution, and the `ms === 3000` filter is what narrows it to this component's timers.
     * `cleared` is snapshotted immediately before unmounting, because asserting against the whole
     * run would also accept a component that cleared its timer at some earlier moment and left
     * nothing pending at unmount at all.
     *
     * ⚠️ Only the pending timer, not every timer ever scheduled. An earlier version demanded all of
     * them, which rejected a behaviourally identical implementation whose only difference was an
     * idle timer the effect had already cleared — an over-specified test "catching" a mutant that
     * changes nothing observable. What IS demanded is that nothing is left pending AFTER the
     * unmount: the dismiss is the only timer this tree arms, so a zero count means nothing the
     * component armed can fire into a torn-down tree — a replacement armed at some other delay
     * included, which the `ms === 3000` filter cannot see.
     *
     * ⚠️ On the fake clock and settled by `pump()`, like the first three tests in this block —
     * never a `findBy*` for the banner, which raced on real timers and would hang on this frozen
     * clock (Testing Library ends every wait on a `setTimeout(0)`, and here that is frozen too).
     * The race: the banner is on screen one scheduler task before its timer exists. Outside `act`,
     * the commit a resolved request makes leaves its passive effects to a later task, and React
     * always yields between the two because every commit requests a paint. A `findByText` is
     * satisfied by the commit, and Testing Library then resumes on a real `setTimeout(0)` that
     * races React's next `setImmediate` slice. If Node's millisecond clock ticked over before the
     * event loop came back round — heavy load makes that likely — the timer won, and the test read
     * an empty `scheduled`: `expected 0 to be greater than 0`, in 41 of 2,700 repetitions under CPU
     * load and 20 of 20 with that tick forced. An awaited `act` keeps flushing until React's queue
     * is empty, passive effects included, so nothing here depends on which macrotask wins — and on
     * a frozen clock the 3s dismiss cannot fire before the unmount, however slow the run.
     */
    it('cancels its pending dismiss timer when it unmounts', async () => {
      vi.useFakeTimers();
      // Taken after the clock is installed, so the spies wrap the fake functions the effect calls.
      const fakeSetTimeout = globalThis.setTimeout;
      const fakeClearTimeout = globalThis.clearTimeout;
      const scheduled = [];
      const cleared = [];

      const setSpy = vi.spyOn(globalThis, 'setTimeout').mockImplementation((fn, ms, ...rest) => {
        const id = fakeSetTimeout(fn, ms, ...rest);
        if (ms === 3000) scheduled.push(id);
        return id;
      });
      const clearSpy = vi.spyOn(globalThis, 'clearTimeout').mockImplementation((id) => {
        cleared.push(id);
        return fakeClearTimeout(id);
      });

      try {
        setExtendedThinking.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', extendedThinking: true });
        const { unmount } = render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('toggle-extended-thinking'));
        await pump();
        expect(screen.getByText(ET_ENABLED)).toBeInTheDocument();
        expect(scheduled.length).toBeGreaterThan(0);

        const clearedBeforeUnmount = cleared.length;
        unmount();

        const clearedByUnmount = cleared.slice(clearedBeforeUnmount);
        expect(clearedByUnmount).toContain(scheduled.at(-1));
        expect(vi.getTimerCount()).toBe(0);
      } finally {
        // Spies off BEFORE the clock: restored after it, they would put the fake `setTimeout` back
        // on the global once the clock itself is gone.
        setSpy.mockRestore();
        clearSpy.mockRestore();
        vi.useRealTimers();
      }
    });
  });

  it('renders model cards and the cost estimate for the default Briefing tab', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toHaveClass('text-plex-gold');
    expect(screen.getByTestId('cost-estimate-table')).toBeInTheDocument();
    expect(screen.getByText(/12 Claude calls per run/)).toBeInTheDocument();
  });

  it('renders config tabs, and none for the retired term run types', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-BATCH_FAR_TERM')).toBeInTheDocument();
    expect(screen.queryByTestId('config-tab-VERY_SHORT_TERM')).not.toBeInTheDocument();
    expect(screen.queryByTestId('config-tab-SHORT_TERM')).not.toBeInTheDocument();
    expect(screen.queryByTestId('config-tab-LONG_TERM')).not.toBeInTheDocument();
  });

  it('offers no optimisation strategy controls', async () => {
    render(<ModelSelectionView />);

    await screen.findByTestId('config-tab-BRIEFING_BEST_BET');

    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
    expect(screen.queryByTestId('strategy-toggle-SENTINEL_SAMPLING')).not.toBeInTheDocument();
    expect(screen.queryByTestId('strategy-toggle-TIDE_ALIGNMENT')).not.toBeInTheDocument();
  });

  it('shows error on API failure', async () => {
    getAvailableModels.mockRejectedValue(new Error('Network error'));

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Error loading models')).toBeInTheDocument();
    });
  });

  it('shows loading state initially', () => {
    getAvailableModels.mockReturnValue(new Promise(() => {})); // never resolves
    render(<ModelSelectionView />);
    expect(screen.getByText('Loading models...')).toBeInTheDocument();
  });

  it('switches to Briefing tab and shows custom cost estimate', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      expect(screen.getByText(/12 Claude calls per run/)).toBeInTheDocument();
    });

    // Briefing tab should not show strategy toggles (no strategies configured)
    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
  });

  it('switches to Aurora tab and shows custom cost estimate', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByText(/35 Claude calls per run/)).toBeInTheDocument();
    });

    // Aurora tab should not show strategy toggles
    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
  });

  it('shows tab-specific description for Briefing model cards', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      // Each model card shows the same tab-specific description (3 cards)
      expect(screen.getAllByText(/region-level triage data/)).toHaveLength(3);
    });
  });

  it('shows tab-specific description for Aurora model cards', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getAllByText(/aurora visibility conditions/)).toHaveLength(3);
    });
  });

  it('calls setActiveModel API when switching model on Briefing tab', async () => {
    setActiveModel.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', active: 'OPUS' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-BRIEFING_BEST_BET-OPUS')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-BRIEFING_BEST_BET-OPUS'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('BRIEFING_BEST_BET', 'OPUS');
    });
  });

  it('calls setActiveModel API when switching model on Aurora tab', async () => {
    setActiveModel.mockResolvedValue({ runType: 'AURORA_EVALUATION', active: 'SONNET' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-AURORA_EVALUATION-SONNET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-AURORA_EVALUATION-SONNET'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('AURORA_EVALUATION', 'SONNET');
    });
  });

  // ── Scheduled Batch tab ──────────────────────────────────────────────────────

  it('Scheduled Batch tab is visible for ADMIN users', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });
  });

  it('Scheduled Batch tab is NOT visible for non-ADMIN users', async () => {
    useAuth.mockReturnValue({ isAdmin: false });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('config-tab-BATCH_NEAR_TERM')).not.toBeInTheDocument();
  });

  it('offers Sonnet 5.5 as a selectable card, ordered after Sonnet 4.6, and switches to it', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      available: [
        { name: 'HAIKU', version: '4.5' },
        { name: 'SONNET', version: '4.6' },
        { name: 'SONNET_55', version: '5.5' },
        { name: 'OPUS', version: '4.6' },
      ],
    });
    setActiveModel.mockResolvedValue({ runType: 'AURORA_EVALUATION', active: 'SONNET_55' });

    render(<ModelSelectionView />);
    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    const sonnet46 = await screen.findByTestId('switch-AURORA_EVALUATION-SONNET');
    const sonnet55 = screen.getByTestId('switch-AURORA_EVALUATION-SONNET_55');
    expect(sonnet46).toHaveTextContent('Switch to Sonnet 4.6');
    expect(sonnet55).toHaveTextContent('Switch to Sonnet 5.5');
    expect(
      sonnet46.compareDocumentPosition(sonnet55) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    fireEvent.click(sonnet55);
    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('AURORA_EVALUATION', 'SONNET_55');
    });
  });

  it('shows a generic card, switch button and a dash cost row for an unknown future model', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      available: [{ name: 'HAIKU', version: '4.5' }, { name: 'HAIKU_60', version: '6.0' }],
    });
    setActiveModel.mockResolvedValue({ runType: 'AURORA_EVALUATION', active: 'HAIKU_60' });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-AURORA_EVALUATION'));

    const button = await screen.findByTestId('switch-AURORA_EVALUATION-HAIKU_60');
    expect(button).toHaveTextContent('Switch to HAIKU_60');
    const card = button.closest('.card');
    expect(card).toHaveTextContent('HAIKU_60');
    expect(card).toHaveTextContent('Typical cost: unknown');
    expect(card).not.toHaveTextContent('Recommended');
    expect(card).not.toHaveTextContent('NaN');

    const row = within(screen.getByTestId('cost-estimate-table')).getByText('HAIKU_60').closest('tr');
    expect(row).toHaveTextContent('—');
    expect(row).not.toHaveTextContent('NaN');

    fireEvent.click(button);
    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('AURORA_EVALUATION', 'HAIKU_60');
    });
  });

  it('Scheduled Batch tab gives an unknown model the generic description', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      available: [{ name: 'HAIKU_60', version: '6.0' }],
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));

    const button = await screen.findByTestId('switch-BATCH_NEAR_TERM-HAIKU_60');
    expect(button.closest('.card')).toHaveTextContent('Newer model; no description yet.');
  });

  describe('modelInfoFor and batchDescriptionFor', () => {
    it('return the hand-written entry for a known model', () => {
      expect(modelInfoFor('HAIKU').recommended).toBe(true);
      expect(batchDescriptionFor('OPUS')).toMatch(/Highest accuracy/);
    });

    it('return a generic, unrecommended, cost-less entry for an unknown model', () => {
      const info = modelInfoFor('HAIKU_60');
      expect(info).toMatchObject({
        name: 'HAIKU_60', recommended: false, typicalCostUsd: null, speed: 'Unknown', tokenRates: 'Unknown',
      });
      expect(info.description).toBe('Newer model; no description yet.');
      expect(batchDescriptionFor('HAIKU_60')).toBe('Newer model; no description yet.');
    });
  });

  it('Scheduled Batch tab label shows active model in brackets', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    // MOCK_DATA.configs.BATCH_NEAR_TERM = 'SONNET'
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toHaveTextContent('(Sonnet 4.6)');
  });

  it('Scheduled Batch tab shows a distinct description for each of the four models', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      available: ['HAIKU', 'SONNET', 'SONNET_55', 'OPUS'],
    });
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByText(/cost-efficient model for overnight batch runs/)).toBeInTheDocument();
    });

    expect(screen.getByText(/Recommended for scheduled batch runs/)).toBeInTheDocument();
    expect(screen.getByText(/Best reserved for key seasonal events only/)).toBeInTheDocument();

    // Each description appears exactly once — not the same text repeated across all three cards
    expect(screen.getAllByText(/cost-efficient model for overnight batch runs/)).toHaveLength(1);
    expect(screen.getAllByText(/Recommended for scheduled batch runs/)).toHaveLength(1);
    // The fourth model: Sonnet 5.5 has its own description, shown exactly once
    expect(screen.getAllByText(/Selectable newer Sonnet for scheduled batch runs/)).toHaveLength(1);
  });

  it('Scheduled Batch tab shows informational panel', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('batch-info-panel')).toBeInTheDocument();
    });

    expect(screen.getByText('How scheduled batch runs work')).toBeInTheDocument();
    expect(screen.getByText('SETTLED')).toBeInTheDocument();
    expect(screen.getByText('TRANSITIONAL')).toBeInTheDocument();
    expect(screen.getByText('UNSETTLED')).toBeInTheDocument();
  });

  it('informational panel is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByText(/35 Claude calls per run/)).toBeInTheDocument();
    });

    expect(screen.queryByTestId('batch-info-panel')).not.toBeInTheDocument();
  });

  it('Scheduled Batch tab shows batch cost table and no standard cost-estimate-table', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('batch-cost-table')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('cost-estimate-table')).not.toBeInTheDocument();
    // Spot-check cost ranges are present
    expect(screen.getAllByText('~£0.0002').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('~£0.02 – £0.08').length).toBeGreaterThanOrEqual(1);
    // Sonnet 5.5 has its own row, so it can be marked active when selected
    expect(within(screen.getByTestId('batch-cost-table')).getByText('Sonnet 5.5')).toBeInTheDocument();
  });

  it('Scheduled Batch cost table marks the Sonnet 5.5 row "(active)" when it is the active model', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BATCH_NEAR_TERM: 'SONNET_55' },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));

    const table = await screen.findByTestId('batch-cost-table');
    const row = within(table).getByText('Sonnet 5.5').closest('tr');
    expect(row).toHaveTextContent('(active)');
    expect(within(table).getByText('Sonnet 4.6').closest('tr')).not.toHaveTextContent('(active)');
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toHaveTextContent('(Sonnet 5.5)');
  });

  it('Extended Thinking: Sonnet 5.5 shows the no-effect note and suppresses the thinking-cost line', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BRIEFING_BEST_BET: 'SONNET_55' },
      extendedThinkingConfigs: { BRIEFING_BEST_BET: true },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BRIEFING_BEST_BET'));

    expect(await screen.findByTestId('extended-thinking-sonnet-55-note')).toHaveTextContent(
      'always thinks adaptively at low effort',
    );
    expect(screen.queryByText(/Active — adds ~10 000 thinking tokens/)).not.toBeInTheDocument();
  });

  it('Extended Thinking: Haiku 5.5 shows the no-effect note and suppresses the thinking-cost line', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BRIEFING_BEST_BET: 'HAIKU_55' },
      extendedThinkingConfigs: { BRIEFING_BEST_BET: true },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BRIEFING_BEST_BET'));

    expect(await screen.findByTestId('extended-thinking-haiku-55-note')).toHaveTextContent(
      'thinking disabled',
    );
    expect(screen.queryByText(/Active — adds ~10 000 thinking tokens/)).not.toBeInTheDocument();
  });

  it('Extended Thinking: Sonnet 4.6 still shows the thinking-cost line and names which Sonnet', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BRIEFING_BEST_BET: 'SONNET' },
      extendedThinkingConfigs: { BRIEFING_BEST_BET: true },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BRIEFING_BEST_BET'));

    expect(await screen.findByText(/Active — adds ~10 000 thinking tokens per call \(Sonnet 4\.6:/))
      .toBeInTheDocument();
    expect(screen.queryByTestId('extended-thinking-sonnet-55-note')).not.toBeInTheDocument();
  });

  it('batch cost table is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    // Default tab is BRIEFING_BEST_BET
    expect(screen.queryByTestId('batch-cost-table')).not.toBeInTheDocument();
  });

  it('model switch on Batch Near-Term tab sends BATCH_NEAR_TERM as runType', async () => {
    setActiveModel.mockResolvedValue({ runType: 'BATCH_NEAR_TERM', active: 'HAIKU' });

    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));

    await waitFor(() => {
      expect(screen.getByTestId('switch-BATCH_NEAR_TERM-HAIKU')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('switch-BATCH_NEAR_TERM-HAIKU'));

    await waitFor(() => {
      expect(setActiveModel).toHaveBeenCalledWith('BATCH_NEAR_TERM', 'HAIKU');
    });
  });
});
