import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import ModelSelectionView from '../components/ModelSelectionView.jsx';

// Mock the API modules
vi.mock('../api/modelsApi', () => ({
  getAvailableModels: vi.fn(),
  setActiveModel: vi.fn(),
  updateOptimisationStrategy: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
}));

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

import { getAvailableModels, setActiveModel, updateOptimisationStrategy } from '../api/modelsApi';
import { fetchLocations } from '../api/forecastApi';
import { useAuth } from '../context/AuthContext.jsx';

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', enabled: true, locationType: ['LANDSCAPE'] },
  { id: 2, name: 'Bamburgh', enabled: true, locationType: ['SEASCAPE'] },
  { id: 3, name: 'Farne Islands', enabled: true, locationType: ['WILDLIFE'] },
  { id: 4, name: 'Disabled', enabled: false, locationType: ['LANDSCAPE'] },
];

const MOCK_DATA = {
  available: ['HAIKU', 'SONNET', 'OPUS'],
  configs: {
    VERY_SHORT_TERM: 'HAIKU',
    SHORT_TERM: 'HAIKU',
    LONG_TERM: 'HAIKU',
    BRIEFING_BEST_BET: 'HAIKU',
    AURORA_EVALUATION: 'HAIKU',
    BATCH_NEAR_TERM: 'SONNET',
    BATCH_FAR_TERM: 'HAIKU',
  },
  // The two strategies that survived V153. Laid out so each test has what it needs: both OFF on
  // the default tab (so toggling produces an "enabled" message), both ON with an UNSET sentinel
  // parameter on SHORT_TERM (so the parameter fallback is visible), and a stored parameter plus a
  // mixed ON/OFF pair on LONG_TERM.
  optimisationStrategies: {
    VERY_SHORT_TERM: [
      { strategyType: 'SENTINEL_SAMPLING', enabled: false, paramValue: 2 },
      { strategyType: 'TIDE_ALIGNMENT', enabled: false, paramValue: null },
    ],
    SHORT_TERM: [
      { strategyType: 'SENTINEL_SAMPLING', enabled: true, paramValue: null },
      { strategyType: 'TIDE_ALIGNMENT', enabled: true, paramValue: null },
    ],
    LONG_TERM: [
      { strategyType: 'SENTINEL_SAMPLING', enabled: true, paramValue: 4 },
      { strategyType: 'TIDE_ALIGNMENT', enabled: false, paramValue: null },
    ],
  },
};

describe('ModelSelectionView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // clearAllMocks leaves mockResolvedValueOnce queues in place, so a test that fails mid-way
    // would hand its unconsumed responses to the next test's first toggle.
    updateOptimisationStrategy.mockReset();
    useAuth.mockReturnValue({ isAdmin: true });
    getAvailableModels.mockResolvedValue(MOCK_DATA);
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);
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
        updateOptimisationStrategy.mockResolvedValue({
          strategyType: 'TIDE_ALIGNMENT',
          enabled: true,
          paramValue: null,
        });
        render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        await pump();
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();

        // Just short of the delay it must still be up: without this the constant is pinned only
        // from above, and shortening it (3000 → 500) would flash the message past unnoticed with
        // every assertion here still green.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(2999);
        });
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(1);
        });
        expect(screen.queryByText('Tide Triage enabled')).not.toBeInTheDocument();
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
        updateOptimisationStrategy
          .mockResolvedValueOnce({ strategyType: 'TIDE_ALIGNMENT', enabled: true, paramValue: null })
          .mockResolvedValueOnce({ strategyType: 'SENTINEL_SAMPLING', enabled: true, paramValue: 2 });
        render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        await pump();
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(2000);
        });
        fireEvent.click(screen.getByTestId('strategy-toggle-SENTINEL_SAMPLING'));
        await pump();
        expect(screen.getByText('Sentinel Sampling enabled')).toBeInTheDocument();

        // 3.5s in: the first message's deadline has passed, the second's has not.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.getByText('Sentinel Sampling enabled')).toBeInTheDocument();

        // 5s in: now the second message's own window has elapsed.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.queryByText('Sentinel Sampling enabled')).not.toBeInTheDocument();
      } finally {
        vi.useRealTimers();
      }
    });

    /**
     * ⚠️ The case that makes each handler's `setSuccess(null)` load-bearing. The effect re-runs
     * only when `success` CHANGES, and the strategy message does not name its run type — so
     * enabling the same strategy on a second config tab produces the identical string. Without the
     * null committed between them the second `setSuccess` is a no-op, the effect never re-runs, and
     * the second message dies on the FIRST one's timer. The config tab switch does not touch
     * `success` (it only calls `setActiveTab`), so the banner genuinely survives into the second
     * toggle and this measures the null, not the tab change.
     */
    it('gives a repeated identical message a fresh window', async () => {
      vi.useFakeTimers();
      try {
        updateOptimisationStrategy.mockResolvedValue({
          strategyType: 'TIDE_ALIGNMENT',
          enabled: true,
          paramValue: null,
        });
        render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        await pump();
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(2000);
        });
        fireEvent.click(screen.getByTestId('config-tab-LONG_TERM'));
        fireEvent.click(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        await pump();
        expect(updateOptimisationStrategy).toHaveBeenLastCalledWith(
          'LONG_TERM', 'TIDE_ALIGNMENT', true, null
        );

        // 3.5s in: past the first message's deadline, inside the repeat's own window.
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();

        await act(async () => {
          await vi.advanceTimersByTimeAsync(1500);
        });
        expect(screen.queryByText('Tide Triage enabled')).not.toBeInTheDocument();
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

      updateOptimisationStrategy.mockReturnValue(new Promise((resolve) => {
        releaseRequest = () => resolve({
          strategyType: 'TIDE_ALIGNMENT',
          enabled: true,
          paramValue: null,
        });
      }));

      const setSpy = vi.spyOn(globalThis, 'setTimeout').mockImplementation((fn, ms, ...rest) => {
        const id = realSetTimeout(fn, ms, ...rest);
        if (ms === 3000 && unmounted) scheduledAfterUnmount.push(id);
        return id;
      });

      try {
        const { unmount } = render(<ModelSelectionView />);
        fireEvent.click(await screen.findByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        // The request must genuinely be in flight, or a toggle that never reached the handler
        // would leave nothing to arm and pass this test for the wrong reason.
        expect(updateOptimisationStrategy).toHaveBeenCalledTimes(1);
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
        updateOptimisationStrategy.mockResolvedValue({
          strategyType: 'TIDE_ALIGNMENT',
          enabled: true,
          paramValue: null,
        });
        const { unmount } = render(<ModelSelectionView />);
        await pump();

        fireEvent.click(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT'));
        await pump();
        expect(screen.getByText('Tide Triage enabled')).toBeInTheDocument();
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

  it('renders model cards and strategy toggles', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    expect(screen.getByText('Cost Optimisation')).toBeInTheDocument();
    expect(screen.getByTestId('cost-estimate-table')).toBeInTheDocument();
    // 2 enabled non-wildlife locations × 2 days × 2 targets = 8 calls
    expect(screen.getByText(/8 Claude calls per run/)).toBeInTheDocument();
  });

  it('renders config tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-VERY_SHORT_TERM')).toBeInTheDocument();
    });

    expect(screen.getByTestId('config-tab-SHORT_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-LONG_TERM')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-BRIEFING_BEST_BET')).toBeInTheDocument();
    expect(screen.getByTestId('config-tab-AURORA_EVALUATION')).toBeInTheDocument();
  });

  it('shows a toggle for each of the two strategies that can act', async () => {
    render(<ModelSelectionView />);

    expect(await screen.findByTestId('strategy-toggle-SENTINEL_SAMPLING')).toBeInTheDocument();
    expect(screen.getByTestId('strategy-toggle-TIDE_ALIGNMENT')).toBeInTheDocument();
  });

  /**
   * During rollout the frontend can meet a backend, or a row, that still names a retired type. The
   * panel must render nothing for it rather than an unlabelled row with a toggle that does nothing.
   */
  it('renders nothing for a strategy type it does not know', async () => {
    getAvailableModels.mockResolvedValue({
      ...MOCK_DATA,
      optimisationStrategies: {
        ...MOCK_DATA.optimisationStrategies,
        VERY_SHORT_TERM: [
          ...MOCK_DATA.optimisationStrategies.VERY_SHORT_TERM,
          { strategyType: 'EVALUATE_ALL', enabled: true, paramValue: null },
        ],
      },
    });
    render(<ModelSelectionView />);

    await screen.findByTestId('strategy-toggle-TIDE_ALIGNMENT');
    expect(screen.queryByTestId('strategy-row-EVALUATE_ALL')).not.toBeInTheDocument();
  });

  it("reports each toggle's stored state through aria-pressed, and shows ON or OFF", async () => {
    render(<ModelSelectionView />);

    fireEvent.click(await screen.findByTestId('config-tab-LONG_TERM'));

    const sentinel = screen.getByRole('button', { name: 'Sentinel Sampling' });
    const tide = screen.getByRole('button', { name: 'Tide Triage' });
    expect(sentinel).toHaveAttribute('aria-pressed', 'true');
    expect(sentinel).toHaveTextContent('ON');
    expect(tide).toHaveAttribute('aria-pressed', 'false');
    expect(tide).toHaveTextContent('OFF');
  });

  /**
   * The panel is headed "Cost Optimisation", which on its own implies it steers every run. It does
   * not: both strategies reach only runs started by hand, and the scheduled batches read neither.
   * The note names the real controls — an earlier cut said "runs started from Operations", which
   * both included batch buttons that ignore these and left out Run Forecast on a map location,
   * which uses them.
   */
  it('names the hand-started runs these apply to, and the batches they do not', async () => {
    render(<ModelSelectionView />);

    const note = await screen.findByTestId('strategy-scope-note');
    expect(note).toHaveTextContent('Applies only to forecast runs started by hand');
    expect(note).toHaveTextContent('Run Forecast on a map location');
    expect(note).toHaveTextContent('Scheduled batches, overnight and intraday, ignore these');
    expect(note).not.toHaveTextContent('Operations');
  });

  /**
   * A screen reader used to hear only "ON, button" or "OFF, button" — nothing saying which strategy,
   * and nothing of the scope caveat. Each toggle now carries its strategy's name, its state, and the
   * scope note as its description.
   */
  it("gives each toggle its strategy's name and the scope note as its description", async () => {
    render(<ModelSelectionView />);

    const tide = await screen.findByRole('button', { name: 'Tide Triage' });
    expect(tide).toHaveAccessibleDescription(/Applies only to forecast runs started by hand/);
    expect(screen.getByRole('button', { name: 'Sentinel Sampling' }))
      .toHaveAccessibleDescription(/Scheduled batches, overnight and intraday, ignore these/);
  });

  /**
   * The tide strategy was labelled "Weather/Tide Triage", described as applying four checks, and said
   * to give a slot "a canned 1★ result". Weather triage runs whether it is on or off, the switch only
   * adds the tide check, and a tide-triaged slot is stood down with no rating at all.
   */
  it('labels the tide strategy as tide-only and describes what it really does', async () => {
    render(<ModelSelectionView />);

    const row = await screen.findByTestId('strategy-row-TIDE_ALIGNMENT');
    expect(row).toHaveTextContent('Tide Triage');
    expect(row).not.toHaveTextContent('Weather/Tide');

    fireEvent.click(within(row).getByRole('button', { name: 'More info' }));
    const help = await screen.findByTestId('infotip-popover');
    expect(help).toHaveTextContent('Weather triage (cloud, rain, visibility) runs whether this is on or off');
    expect(help).toHaveTextContent('stood down without a Claude call');
    expect(help).not.toHaveTextContent('Four checks');
    expect(help).not.toHaveTextContent('1★');
  });

  it('calls the API when a strategy is toggled', async () => {
    updateOptimisationStrategy.mockResolvedValue({
      strategyType: 'TIDE_ALIGNMENT',
      enabled: true,
      paramValue: null,
    });
    render(<ModelSelectionView />);

    fireEvent.click(await screen.findByTestId('strategy-toggle-TIDE_ALIGNMENT'));

    await waitFor(() => {
      expect(updateOptimisationStrategy).toHaveBeenCalledWith(
        'VERY_SHORT_TERM', 'TIDE_ALIGNMENT', true, null
      );
    });
  });

  it('shows the sentinel threshold buttons, highlighting the stored value', async () => {
    render(<ModelSelectionView />);

    fireEvent.click(await screen.findByTestId('config-tab-LONG_TERM'));

    for (let i = 1; i <= 5; i++) {
      expect(screen.getByTestId(`param-SENTINEL_SAMPLING-${i}`)).toBeInTheDocument();
    }
    expect(screen.getByTestId('param-SENTINEL_SAMPLING-4')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('param-SENTINEL_SAMPLING-3')).toHaveAttribute('aria-pressed', 'false');
  });

  /**
   * With no stored value the buttons used to fall back to 3 — Skip Low-Rated's default. The
   * sentinel's is 2 (the backend's DEFAULT_SENTINEL_RATING_THRESHOLD), so the panel highlighted a
   * threshold the run was not using.
   */
  it("falls back to the sentinel's own default of 2 when no threshold is stored", async () => {
    render(<ModelSelectionView />);

    fireEvent.click(await screen.findByTestId('config-tab-SHORT_TERM'));

    expect(screen.getByTestId('param-SENTINEL_SAMPLING-2')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('param-SENTINEL_SAMPLING-3')).toHaveAttribute('aria-pressed', 'false');
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

  it('Scheduled Batch tab label shows active model in brackets', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toBeInTheDocument();
    });

    // MOCK_DATA.configs.BATCH_NEAR_TERM = 'SONNET'
    expect(screen.getByTestId('config-tab-BATCH_NEAR_TERM')).toHaveTextContent('(Sonnet)');
  });

  it('Scheduled Batch tab shows three distinct per-model descriptions', async () => {
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
    expect(screen.getByText('~£0.0002')).toBeInTheDocument();
    expect(screen.getByText('~£0.02 – £0.08')).toBeInTheDocument();
  });

  it('batch cost table is absent on non-batch tabs', async () => {
    render(<ModelSelectionView />);

    await waitFor(() => {
      expect(screen.getByText('Run Configuration')).toBeInTheDocument();
    });

    // Default tab is VERY_SHORT_TERM
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
