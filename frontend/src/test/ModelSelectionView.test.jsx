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
  tokenRates: {
    HAIKU: { inputUsdPerMtok: 1, outputUsdPerMtok: 5 },
    SONNET: { inputUsdPerMtok: 3, outputUsdPerMtok: 15 },
    OPUS: { inputUsdPerMtok: 5, outputUsdPerMtok: 25 },
  },
  gbpPerUsd: 0.78,
  costProfiles: {
    BRIEFING_BEST_BET: {
      windowDays: 30,
      runsPerDay: 2.3,
      callsPerRun: 1,
      measured: { SONNET: { calls: 69, usdPerCall: 0.04, usdPerDay: 0.092 } },
      estimates: {
        HAIKU: { calls: 0, usdPerCall: 0.0125, usdPerDay: 0.02875 },
        OPUS: { calls: 0, usdPerCall: 0.065, usdPerDay: 0.1495 },
      },
    },
    AURORA_EVALUATION: {
      windowDays: 30,
      runsPerDay: 0.6,
      callsPerRun: 1,
      measured: { SONNET: { calls: 18, usdPerCall: 0.44, usdPerDay: 0.264 } },
      estimates: {
        HAIKU: { calls: 0, usdPerCall: 0.14, usdPerDay: 0.084 },
        OPUS: { calls: 0, usdPerCall: 0.73, usdPerDay: 0.438 },
      },
    },
    BATCH_NEAR_TERM: {
      windowDays: 30,
      runsPerDay: 6.9,
      callsPerRun: 143,
      measured: { SONNET: { calls: 29600, usdPerCall: 0.0028, usdPerDay: 2.76 } },
      estimates: {
        HAIKU: { calls: 0, usdPerCall: 0.0009, usdPerDay: 0.89 },
        OPUS: { calls: 0, usdPerCall: 0.0047, usdPerDay: 4.64 },
      },
    },
    BATCH_FAR_TERM: {
      windowDays: 30, runsPerDay: 0, callsPerRun: 0, measured: {}, estimates: {},
    },
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
    expect(screen.getByTestId('cost-table')).toBeInTheDocument();
    expect(screen.getByTestId('cost-table-summary')).toHaveTextContent(
      '2.3 runs a day, 1 call per run, last 30 days',
    );
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

  it('switches to Briefing tab and shows the served cost summary', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-BRIEFING_BEST_BET'));

    await waitFor(() => {
      expect(screen.getByTestId('cost-table-summary')).toHaveTextContent('2.3 runs a day');
    });

    // Briefing tab should not show strategy toggles (no strategies configured)
    expect(screen.queryByText('Cost Optimisation')).not.toBeInTheDocument();
  });

  it('switches to Aurora tab and shows the served cost summary', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));

    await waitFor(() => {
      expect(screen.getByTestId('cost-table-summary')).toHaveTextContent(
        '0.6 runs a day, 1 call per run, last 30 days',
      );
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
    expect(card).toHaveTextContent('Cost: unknown');
    expect(card).not.toHaveTextContent('Recommended');
    expect(card).not.toHaveTextContent('NaN');

    const row = within(screen.getByTestId('cost-table')).getByText('HAIKU_60').closest('tr');
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
      expect(modelInfoFor('HAIKU')).toEqual({ name: 'Haiku 4.5', speed: 'Fast' });
      expect(batchDescriptionFor('OPUS')).toBe('The highest-priced model per token.');
    });

    it('carry no price and no recommendation for any model', () => {
      expect(modelInfoFor('SONNET')).not.toHaveProperty('typicalCostUsd');
      expect(modelInfoFor('SONNET')).not.toHaveProperty('tokenRates');
      expect(modelInfoFor('SONNET')).not.toHaveProperty('recommended');
    });

    it('return a generic entry for an unknown model', () => {
      expect(modelInfoFor('HAIKU_60')).toEqual({ name: 'HAIKU_60', speed: 'Unknown' });
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
      expect(screen.getByText(/Fast, cost-efficient model\./)).toBeInTheDocument();
    });

    expect(screen.getByText(/Strong reasoning at low cost with batch pricing/)).toBeInTheDocument();
    expect(screen.getByText('The highest-priced model per token.')).toBeInTheDocument();

    // Each description appears exactly once — not the same text repeated across all cards
    expect(screen.getAllByText(/Fast, cost-efficient model\./)).toHaveLength(1);
    expect(screen.getAllByText(/Strong reasoning at low cost with batch pricing/)).toHaveLength(1);
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
      expect(screen.getByTestId('aurora-thinking-note')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('batch-info-panel')).not.toBeInTheDocument();
  });

  it('Batch tab shows the served cycle summary and per-request figures, not the old static table', async () => {
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));

    const table = await screen.findByTestId('cost-table');
    expect(screen.getByTestId('cost-table-summary')).toHaveTextContent(
      '6.9 cycles a day, 143 requests per cycle, last 30 days',
    );
    const sonnet = within(table).getByText('Sonnet 4.6').closest('tr');
    expect(sonnet).toHaveTextContent('£0.0022');
    expect(sonnet).toHaveTextContent('measured');
    expect(sonnet).toHaveTextContent('(active)');
    const haiku = within(table).getByText('Haiku 4.5').closest('tr');
    expect(haiku).toHaveTextContent('estimate');
    expect(screen.queryByTestId('batch-cost-table')).not.toBeInTheDocument();
    expect(screen.queryByText('~£0.0002')).not.toBeInTheDocument();
  });

  it('cost table marks the Sonnet 5.5 row "(active)" when it is the active model', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      available: ['HAIKU', 'SONNET', 'SONNET_55'],
      configs: { ...MOCK_DATA.configs, BATCH_NEAR_TERM: 'SONNET_55' },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));

    const table = await screen.findByTestId('cost-table');
    const row = within(table).getByText('Sonnet 5.5').closest('tr');
    expect(row).toHaveTextContent('(active)');
    expect(within(table).getByText('Sonnet 4.6').closest('tr')).not.toHaveTextContent('(active)');
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toHaveTextContent('(Sonnet 5.5)');
  });

  describe('served cost figures', () => {
    it('cards show the measured figure for a model that ran and an estimate for one that did not', async () => {
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      expect(screen.getByTestId('card-cost-SONNET')).toHaveTextContent('Cost: ~£0.031 per call (measured)');
      expect(screen.getByTestId('card-cost-HAIKU')).toHaveTextContent('Cost: ~£0.0098 per call (estimate)');
      expect(screen.getByTestId('card-cost-OPUS')).toHaveTextContent('(estimate)');
    });

    it('cards show the served token rates, converted at the served exchange rate', async () => {
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      expect(screen.getByTestId('card-rates-SONNET')).toHaveTextContent('£2.34/MTok in, £11.7/MTok out');
      expect(screen.getByTestId('card-rates-HAIKU')).toHaveTextContent('£0.78/MTok in, £3.9/MTok out');
    });

    it('the table lists per-call and per-day figures with their basis', async () => {
      render(<ModelSelectionView />);
      const table = await screen.findByTestId('cost-table');

      const sonnet = within(table).getByText('Sonnet 4.6').closest('tr');
      expect(sonnet).toHaveTextContent('£0.031');
      expect(sonnet).toHaveTextContent('£0.072');
      expect(sonnet).toHaveTextContent('measured');
      expect(within(table).getByText('Opus').closest('tr')).toHaveTextContent('estimate');
    });

    it('shows dollars when the backend sent no exchange rate', async () => {
      const withoutRate = { ...MOCK_DATA };
      delete withoutRate.gbpPerUsd;
      getAvailableModels.mockResolvedValue(withoutRate);
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      expect(screen.getByTestId('card-cost-SONNET')).toHaveTextContent('~$0.040 per call (measured)');
      expect(screen.getByTestId('card-rates-SONNET')).toHaveTextContent('$3/MTok in, $15/MTok out');
      expect(screen.getByTestId('cost-table')).not.toHaveTextContent('£');
    });

    it('says cost figures are unavailable when costProfiles is absent, and the cards still work', async () => {
      const withoutProfiles = { ...MOCK_DATA };
      delete withoutProfiles.costProfiles;
      getAvailableModels.mockResolvedValue(withoutProfiles);
      setActiveModel.mockResolvedValue({ runType: 'BRIEFING_BEST_BET', active: 'OPUS' });
      render(<ModelSelectionView />);

      expect(await screen.findByText('Cost figures unavailable')).toBeInTheDocument();
      expect(screen.queryByTestId('cost-table')).not.toBeInTheDocument();
      expect(screen.getByTestId('card-cost-SONNET')).toHaveTextContent('Cost: unknown');
      fireEvent.click(screen.getByTestId('switch-BRIEFING_BEST_BET-OPUS'));
      await waitFor(() => {
        expect(setActiveModel).toHaveBeenCalledWith('BRIEFING_BEST_BET', 'OPUS');
      });
    });

    it('says cost figures are unavailable when costProfiles is empty', async () => {
      getAvailableModels.mockResolvedValue({ ...MOCK_DATA, costProfiles: {} });
      render(<ModelSelectionView />);

      expect(await screen.findByText('Cost figures unavailable')).toBeInTheDocument();
    });

    it('says there were no calls when a tab has an empty window', async () => {
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId('config-tab-BATCH_FAR_TERM'));

      expect(await screen.findByText('No calls in the last 30 days')).toBeInTheDocument();
      expect(screen.queryByTestId('cost-table')).not.toBeInTheDocument();
    });

    it('carries none of the old hard-coded costs, rates or Recommended badge', async () => {
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      const text = document.body.textContent;
      expect(text).not.toContain('Recommended');
      expect(text).not.toContain('£0.80/MTok');
      expect(text).not.toContain('every 2 hours');
      expect(text).not.toContain('35 locations');
      expect(text).not.toContain('Typical cost');
    });

    it('footnotes every tab, with the batch and aurora additions only where they apply', async () => {
      render(<ModelSelectionView />);
      const footnote = await screen.findByTestId('cost-footnote');
      expect(footnote).toHaveTextContent('Costs are calculated from logged token counts at the configured prices.');
      expect(footnote).not.toHaveTextContent('Batch API discount');
      expect(footnote).not.toHaveTextContent('understate');

      fireEvent.click(screen.getByTestId('config-tab-AURORA_EVALUATION'));
      expect(screen.getByTestId('cost-footnote')).toHaveTextContent('understate');
      expect(screen.getByTestId('cost-footnote')).not.toHaveTextContent('Batch API discount');

      fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));
      expect(screen.getByTestId('cost-footnote')).toHaveTextContent(
        'Includes the Batch API discount and prompt caching. The cache-priming call is not logged',
      );
    });
  });

  describe('per-tab guards', () => {
    const BANNED = [
      /Recommended/,
      /Best reserved for key seasonal events/,
      /for overnight batch runs/,
      /not the recommended default/,
      /Lower quality reasoning/,
      /Highest accuracy/,
    ];
    const NOWHERE = ['12 calls', '10 000', '~£0.0002'];

    it.each(['BATCH_NEAR_TERM', 'BATCH_FAR_TERM'])('%s carries none of the retired advice or rankings', async (tab) => {
      getAvailableModels.mockResolvedValue({
        ...MOCK_DATA,
        available: ['HAIKU', 'HAIKU_55', 'SONNET', 'SONNET_55', 'OPUS'],
      });
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId(`config-tab-${tab}`));
      await screen.findByTestId('batch-info-panel');

      const text = document.body.textContent;
      BANNED.forEach((re) => expect(text).not.toMatch(re));
    });

    it.each(['BRIEFING_BEST_BET', 'AURORA_EVALUATION', 'BATCH_NEAR_TERM', 'BATCH_FAR_TERM'])(
      '%s carries none of the retired figures',
      async (tab) => {
        render(<ModelSelectionView />);
        fireEvent.click(await screen.findByTestId(`config-tab-${tab}`));
        await screen.findByTestId('cost-table-card');

        const text = document.body.textContent;
        NOWHERE.forEach((needle) => expect(text).not.toContain(needle));
      },
    );

    it('the STANDDOWN sentence stands alone', async () => {
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));
      const panel = await screen.findByTestId('batch-info-panel');

      expect(panel).not.toHaveTextContent('This is expected');
      expect(panel).not.toHaveTextContent('no spend on locations');
    });

    it('batch cards say per request, the other tabs per call', async () => {
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');
      expect(screen.getByTestId('card-cost-SONNET')).toHaveTextContent('per call (measured)');

      fireEvent.click(screen.getByTestId('config-tab-BATCH_NEAR_TERM'));
      expect(screen.getByTestId('card-cost-SONNET')).toHaveTextContent('~£0.0022 per request (measured)');
    });

    it('a non-empty costProfiles missing the active tab says figures are unavailable, not "no calls"', async () => {
      getAvailableModels.mockResolvedValue({
        ...MOCK_DATA,
        costProfiles: { AURORA_EVALUATION: MOCK_DATA.costProfiles.AURORA_EVALUATION },
      });
      render(<ModelSelectionView />);

      expect(await screen.findByText('Cost figures unavailable')).toBeInTheDocument();
      expect(screen.queryByText(/No calls in the last/)).not.toBeInTheDocument();
    });

    it('a model with no tokenRates entry shows unknown rates', async () => {
      getAvailableModels.mockResolvedValue({
        ...MOCK_DATA,
        tokenRates: { SONNET: MOCK_DATA.tokenRates.SONNET },
      });
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      expect(screen.getByTestId('card-rates-HAIKU')).toHaveTextContent('Token rates: unknown');
      expect(screen.getByTestId('card-rates-SONNET')).toHaveTextContent('£2.34/MTok in');
    });

    it('a known model missing from costProfiles renders a dash and does not crash', async () => {
      getAvailableModels.mockResolvedValue({
        ...MOCK_DATA,
        available: ['HAIKU', 'SONNET', 'SONNET_55', 'OPUS'],
      });
      render(<ModelSelectionView />);
      const table = await screen.findByTestId('cost-table');

      const row = within(table).getByText('Sonnet 5.5').closest('tr');
      expect(row).toHaveTextContent('—');
      expect(screen.getByTestId('card-cost-SONNET_55')).toHaveTextContent('Cost: unknown');
    });
  });

  describe('copy', () => {
    it('batch panel states the cycle, tier and STANDDOWN rules', async () => {
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId('config-tab-BATCH_FAR_TERM'));
      const panel = await screen.findByTestId('batch-info-panel');

      expect(panel).toHaveTextContent('in the nightly, intraday and evening cycles');
      expect(panel).toHaveTextContent('T+0 and T+1, at every stability; skipped only by triage or a fresh evaluation.');
      expect(panel).toHaveTextContent('Nightly cycle only: T+2 when settled or transitional, T+3 when settled');
      expect(panel).toHaveTextContent('up to 6 GO headline contenders');
      expect(panel).toHaveTextContent('Nightly cycle');
      expect(panel).toHaveTextContent('T+0 to T+3');
      expect(panel).toHaveTextContent('T+0 to T+2');
      expect(panel).toHaveTextContent('wind-gust variance');
      expect(panel).toHaveTextContent(
        "The intraday cycle covers today's sunset and tomorrow's sunrise and sunset; the evening cycle covers tomorrow's sunrise.",
      );
      expect(panel).toHaveTextContent(
        'If every sky location is stood down by triage and no woodland or bluebell site is due, the batch has zero requests and is not submitted.',
      );
    });

    it('batch panel no longer has the JFDI section, the morning promise or the model advice', async () => {
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId('config-tab-BATCH_NEAR_TERM'));
      const panel = await screen.findByTestId('batch-info-panel');

      expect(panel).not.toHaveTextContent('JFDI');
      expect(panel).not.toHaveTextContent('Run full forecast');
      expect(panel).not.toHaveTextContent('ready before your first morning check');
      expect(panel).not.toHaveTextContent('re-evaluated overnight anyway');
    });

    it('the About box keeps its facts and drops the two advice lines', async () => {
      render(<ModelSelectionView />);
      await screen.findByTestId('cost-table');

      expect(screen.getByText(/Each run type can use a different Claude model independently/)).toBeInTheDocument();
      expect(screen.getByText(/Wildlife-only locations/)).toBeInTheDocument();
      expect(screen.getByText(/Previous forecasts are preserved with their original model/)).toBeInTheDocument();
      expect(screen.queryByText(/Haiku offers excellent value/)).not.toBeInTheDocument();
      expect(screen.queryByText(/Use a more accurate model/)).not.toBeInTheDocument();
    });

    it('the Briefing thinking panel explains adaptive thinking and the 16,000 ceiling', async () => {
      render(<ModelSelectionView />);
      const panel = await screen.findByTestId('extended-thinking-panel');

      expect(panel).toHaveTextContent('Turns on adaptive thinking for Sonnet 4.6 and Opus 4.6.');
      expect(panel).toHaveTextContent('rises from 4,096 to 16,000');
      expect(panel).not.toHaveTextContent('10 000');
    });

    it('the Aurora tab explains how thinking and the reply budget are set, with no toggle', async () => {
      render(<ModelSelectionView />);
      fireEvent.click(await screen.findByTestId('config-tab-AURORA_EVALUATION'));
      const note = await screen.findByTestId('aurora-thinking-note');

      expect(note).toHaveTextContent('One call rates every viable location.');
      expect(note).toHaveTextContent('about 220 tokens per location, plus 4,096 for Sonnet 5.5');
      expect(screen.queryByTestId('toggle-extended-thinking')).not.toBeInTheDocument();
    });
  });

  it('Extended Thinking: Sonnet 5.5 shows the no-effect note and the 16,000 ceiling', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BRIEFING_BEST_BET: 'SONNET_55' },
      extendedThinkingConfigs: { BRIEFING_BEST_BET: true },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BRIEFING_BEST_BET'));

    expect(await screen.findByTestId('extended-thinking-sonnet-55-note')).toHaveTextContent(
      'always thinks adaptively at low effort, so this switch has no effect on it, and it always has the 16,000 ceiling.',
    );
  });

  it('Extended Thinking: Haiku 5.5 shows the no-effect note', async () => {
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
  });

  it('Extended Thinking: Sonnet 4.6 shows no no-effect note and no invented per-call cost', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      configs: { ...MOCK_DATA.configs, BRIEFING_BEST_BET: 'SONNET' },
      extendedThinkingConfigs: { BRIEFING_BEST_BET: true },
    });
    render(<ModelSelectionView />);
    fireEvent.click(await screen.findByTestId('config-tab-BRIEFING_BEST_BET'));

    const panel = await screen.findByTestId('extended-thinking-panel');
    expect(panel).not.toHaveTextContent('Active — adds');
    expect(panel).not.toHaveTextContent('£0.12');
    expect(screen.queryByTestId('extended-thinking-sonnet-55-note')).not.toBeInTheDocument();
    expect(screen.queryByTestId('extended-thinking-haiku-55-note')).not.toBeInTheDocument();
  });

  it('the batch info panel is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('batch-info-panel')).not.toBeInTheDocument();
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
