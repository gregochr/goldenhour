import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act, within } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
}));

import {
  fetchSchedulerJobs,
  updateJobSchedule,
  pauseJob,
  resumeJob,
  triggerJob,
} from '../api/schedulerApi';

const MOCK_JOBS = [
  {
    id: 1,
    jobKey: 'tide_refresh',
    displayName: 'Tide Refresh',
    description: 'Refreshes tide extremes weekly',
    scheduleType: 'CRON',
    cronExpression: '0 0 2 * * MON',
    fixedDelayMs: 0,
    initialDelayMs: 0,
    status: 'ACTIVE',
    lastFireTime: new Date(Date.now() - 180_000).toISOString(),
    lastCompletionTime: '',
    nextFireTime: new Date(Date.now() + 86400_000).toISOString(),
    configSource: '',
    updatedAt: new Date().toISOString(),
  },
  {
    id: 2,
    jobKey: 'aurora_polling',
    displayName: 'Aurora Polling',
    description: 'Polls NOAA SWPC for aurora activity',
    scheduleType: 'FIXED_DELAY',
    cronExpression: '',
    fixedDelayMs: 300000,
    initialDelayMs: 60000,
    status: 'PAUSED',
    lastFireTime: '',
    lastCompletionTime: '',
    nextFireTime: '',
    configSource: '',
    updatedAt: new Date().toISOString(),
  },
];

describe('SchedulerView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchSchedulerJobs.mockResolvedValue(MOCK_JOBS);
  });

  it('renders job cards after loading', async () => {
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toBeInTheDocument();
      expect(screen.getByTestId('scheduler-job-aurora_polling')).toBeInTheDocument();
    });
  });

  it('shows correct status pills', async () => {
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('status-pill-tide_refresh')).toHaveTextContent('Active');
      expect(screen.getByTestId('status-pill-aurora_polling')).toHaveTextContent('Paused');
    });
  });

  it('pause calls API and refreshes jobs', async () => {
    pauseJob.mockResolvedValue({});
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('pause-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('pause-btn-tide_refresh'));

    await waitFor(() => {
      expect(pauseJob).toHaveBeenCalledWith('tide_refresh');
      // fetchSchedulerJobs called once on mount + once after pause
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2);
    });
  });

  it('resume calls API and refreshes jobs', async () => {
    resumeJob.mockResolvedValue({});
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('resume-btn-aurora_polling'));
    fireEvent.click(screen.getByTestId('resume-btn-aurora_polling'));

    await waitFor(() => {
      expect(resumeJob).toHaveBeenCalledWith('aurora_polling');
    });
  });

  it('Run Now shows Triggered confirmation', async () => {
    triggerJob.mockResolvedValue({ status: 'triggered' });
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('trigger-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('trigger-btn-tide_refresh'));

    await waitFor(() => {
      expect(screen.getByTestId('trigger-btn-tide_refresh')).toHaveTextContent(
        'Triggered',
      );
    });
  });

  it('DISABLED_BY_CONFIG disables Resume and Run Now', async () => {
    fetchSchedulerJobs.mockResolvedValue([
      { ...MOCK_JOBS[1], status: 'DISABLED_BY_CONFIG', configSource: 'aurora.enabled' },
    ]);
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('resume-btn-aurora_polling')).toBeDisabled();
      expect(screen.getByTestId('trigger-btn-aurora_polling')).toBeDisabled();
    });
  });

  it('inline edit shows input on Edit click and hides on Cancel', async () => {
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));

    expect(screen.getByTestId('edit-input-tide_refresh')).toBeInTheDocument();
    expect(screen.getByTestId('cancel-btn-tide_refresh')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('cancel-btn-tide_refresh'));

    expect(screen.queryByTestId('edit-input-tide_refresh')).not.toBeInTheDocument();
  });

  it('human-readable cron description renders correctly', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      // Time is converted from UTC to UK local (BST in summer, GMT in winter)
      expect(screen.getByTestId('scheduler-job-tide_refresh').textContent).toMatch(
        /Every Monday at \d{2}:\d{2} (BST|GMT)/,
      );
    });
  });

  it('human-readable fixed delay description renders correctly', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-aurora_polling')).toHaveTextContent(
        'Every 5 minutes',
      );
    });
  });

  it('fixed delay jobs do not show raw millisecond values', async () => {
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('scheduler-job-aurora_polling'));
    expect(screen.getByTestId('scheduler-job-aurora_polling').textContent).not.toContain('300000ms');
  });

  it('fixed delay hourly job shows human-readable description', async () => {
    fetchSchedulerJobs.mockResolvedValue([
      { ...MOCK_JOBS[1], fixedDelayMs: 3600000 },
    ]);
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-aurora_polling')).toHaveTextContent(
        'Every hour',
      );
    });
  });

  it('shows config source for disabled jobs', async () => {
    fetchSchedulerJobs.mockResolvedValue([
      { ...MOCK_JOBS[1], status: 'DISABLED_BY_CONFIG', configSource: 'aurora.enabled' },
    ]);
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-aurora_polling')).toHaveTextContent(
        'aurora.enabled',
      );
    });
  });

  it('shows relative time for last fire', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('last-fire-tide_refresh')).toHaveTextContent('3 min ago');
    });
  });

  it('shows future relative time for next fire', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      // tide_refresh nextFireTime is ~24h in the future — expect "in 23h" or "in 1d"
      expect(screen.getByTestId('next-fire-tide_refresh').textContent).toMatch(/in \d+[hd]/);
    });
  });

  it('next fire does not show "just now" for future timestamps', async () => {
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('next-fire-tide_refresh'));
    expect(screen.getByTestId('next-fire-tide_refresh').textContent).not.toContain('just now');
  });

  it('save inline edit calls updateJobSchedule with correct payload for CRON', async () => {
    updateJobSchedule.mockResolvedValue({});
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));

    const input = screen.getByTestId('edit-input-tide_refresh');
    fireEvent.change(input, { target: { value: '0 0 3 * * TUE' } });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => {
      expect(updateJobSchedule).toHaveBeenCalledWith('tide_refresh', {
        cronExpression: '0 0 3 * * TUE',
      });
    });
  });

  it('save inline edit calls updateJobSchedule with fixedDelayMs for FIXED_DELAY', async () => {
    updateJobSchedule.mockResolvedValue({});
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('edit-btn-aurora_polling'));
    fireEvent.click(screen.getByTestId('edit-btn-aurora_polling'));

    const input = screen.getByTestId('edit-input-aurora_polling');
    fireEvent.change(input, { target: { value: '600000' } });
    fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));

    await waitFor(() => {
      expect(updateJobSchedule).toHaveBeenCalledWith('aurora_polling', {
        fixedDelayMs: 600000,
      });
    });
  });

  it('shows error banner when API call fails', async () => {
    pauseJob.mockRejectedValue(new Error('Network error'));
    render(<SchedulerView />);

    await waitFor(() => screen.getByTestId('pause-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('pause-btn-tide_refresh'));

    await waitFor(() => {
      expect(screen.getByText(/Failed to pause tide_refresh/)).toBeInTheDocument();
    });
  });

  it('shows error banner when fetchSchedulerJobs fails', async () => {
    fetchSchedulerJobs.mockRejectedValue(new Error('Server error'));
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByText(/Failed to load scheduler jobs/)).toBeInTheDocument();
    });
  });

  it('shows "Triggered ✓" (with checkmark) after triggering a job', async () => {
    triggerJob.mockResolvedValue({});
    render(<SchedulerView />);
    await waitFor(() => screen.getByTestId('trigger-btn-tide_refresh'));

    fireEvent.click(screen.getByTestId('trigger-btn-tide_refresh'));

    await waitFor(() => {
      expect(screen.getByTestId('trigger-btn-tide_refresh').textContent).toBe('Triggered \u2713');
    });
  });

  it('cron hint updates live as the user edits the expression', async () => {
    render(<SchedulerView />);
    await waitFor(() => screen.getByTestId('edit-btn-tide_refresh'));

    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));

    const input = screen.getByTestId('edit-input-tide_refresh');
    // Change to a daily expression
    fireEvent.change(input, { target: { value: '0 0 6 * * *' } });

    await waitFor(() => {
      // Time shown in UK local time (BST in summer, GMT in winter) — not UTC
      expect(screen.getByTestId('scheduler-job-tide_refresh').textContent).toMatch(
        /Daily at \d{2}:\d{2} (BST|GMT)/,
      );
    });
  });

  it('cron hint is not shown when editing a FIXED_DELAY job', async () => {
    const { within } = await import('@testing-library/react');
    render(<SchedulerView />);
    await waitFor(() => screen.getByTestId('edit-btn-aurora_polling'));

    fireEvent.click(screen.getByTestId('edit-btn-aurora_polling'));

    // aurora_polling is FIXED_DELAY — no cron hint should appear within that card
    const card = screen.getByTestId('scheduler-job-aurora_polling');
    expect(within(card).queryByText(/Daily at/)).not.toBeInTheDocument();
    expect(within(card).queryByText(/Every .* at/)).not.toBeInTheDocument();
  });

  it('polls for job updates at the configured interval', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    render(<SchedulerView />);

    // Wait for initial load to complete
    await vi.waitFor(() => {
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(1);
    });

    // Advance past the 30s polling interval
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });

    await vi.waitFor(() => {
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(2);
    });

    // Advance another interval
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });

    await vi.waitFor(() => {
      expect(fetchSchedulerJobs).toHaveBeenCalledTimes(3);
    });

    vi.useRealTimers();
  });
});

// ---------------------------------------------------------------------------
// The "Triggered ✓" confirmation — who owns its dismiss timer
//
// ⚠️ Frozen fake clock, NOT `shouldAdvanceTime`. That option ties the fake clock to real time, so
// the wall time spent getting the confirmation on screen comes straight out of the 2s window being
// measured. Pumping with a zero advance settles the mount fetch and the trigger call while the
// clock stays exactly where it is. Every fake-clock test restores real timers in a `finally`, so an
// assertion that throws cannot leave the next test on a frozen clock.
//
// The `vi.getTimerCount()).toBe(0)` assertions count EVERYTHING on the fake clock, the poll
// interval included — which the unmount clears, so zero means nothing outlived the screen. A
// failure there can therefore also mean the interval's cleanup went missing; the message says so.
// ---------------------------------------------------------------------------
describe('SchedulerView — the Run Now confirmation', () => {
  /** Resolvers handed out by `deferTrigger` that the test has not released yet. */
  let unreleased = [];

  beforeEach(() => {
    vi.clearAllMocks();
    // ⚠️ `clearAllMocks` does not empty a `mockReturnValueOnce` queue, and a queued Once outranks
    // both the default below and a test's own `mockRejectedValue`. A test that fails before
    // consuming a deferred call would hand it to the NEXT test's click — measured once, when a
    // leftover call reached the unrelated error test as a success instead of its rejection.
    // `mockReset` empties the queue.
    triggerJob.mockReset();
    fetchSchedulerJobs.mockResolvedValue(MOCK_JOBS);
    triggerJob.mockResolvedValue({ status: 'triggered' });
  });

  afterEach(() => {
    // The net for a test that throws before releasing its calls: a promise left pending is a
    // pending async continuation, and nothing in this file should outlive its own test.
    unreleased.forEach((release) => release());
    unreleased = [];
  });

  /** Flushes pending promises without moving the fake clock at all. */
  async function pump() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  /** The tide_refresh card's trigger button, found through its role and accessible name. */
  function runNowButton(name) {
    return within(screen.getByTestId('scheduler-job-tide_refresh')).getByRole('button', { name });
  }

  /**
   * Hands the test the resolver for one trigger call, so the call is genuinely in flight until the
   * test says otherwise — and so it always settles. A promise that never settles poisons later
   * tests (frontend-test-standards.md, "What NOT to do").
   */
  function deferTrigger() {
    let release;
    triggerJob.mockReturnValueOnce(new Promise((resolve) => {
      release = () => resolve({ status: 'triggered' });
    }));
    unreleased.push(release);
    return () => release();
  }

  /** The message every "nothing left on the clock" assertion carries. */
  const OUTLIVED = 'a timer outlived the screen (a dismiss timer, or the poll interval)';

  it('holds the confirmation until TRIGGER_CONFIRM_MS elapses, then restores Run Now', async () => {
    vi.useFakeTimers();
    try {
      render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledWith('tide_refresh');
      expect(runNowButton('Triggered ✓')).toBeDisabled();

      // Just short of the delay it must still be up: without this the constant is pinned only
      // from above, and shortening it would flash the confirmation past with every other
      // assertion here still green.
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1999);
      });
      expect(runNowButton('Triggered ✓')).toBeDisabled();

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1);
      });
      expect(runNowButton('Run Now')).toBeEnabled();
      expect(
        within(screen.getByTestId('scheduler-job-tide_refresh')).queryByRole('button', {
          name: 'Triggered ✓',
        }),
      ).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ⚠️ The arm-after-await hole. `ManageView` renders this screen behind
   * `activeTab === 'scheduler'`, so switching tab while `triggerJob` is in flight unmounts it
   * before any dismiss timer exists. The old handler armed its timer AFTER the await, so an unmount
   * cleanup had nothing to cancel and the continuation then armed a timer nothing owned — in jsdom
   * its callback can fire after teardown, an unhandled error vitest fails the run on while every
   * test reports passing. A test that unmounts after the call settles cannot see this.
   *
   * The poll interval is cleared by the unmount, so after the call settles nothing at all may be
   * pending on the clock.
   */
  it('arms no timer when the trigger call settles after the screen unmounts', async () => {
    vi.useFakeTimers();
    try {
      const release = deferTrigger();
      const { unmount } = render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      // The call must genuinely be in flight, or the unmount below proves nothing.
      expect(triggerJob).toHaveBeenCalledTimes(1);

      unmount();
      release();
      await pump();

      expect(vi.getTimerCount(), OUTLIVED).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * The ordinary path out: the confirmation is showing and the reader changes tab. The dismiss
   * timer is pending at that moment, and the unmount must cancel it rather than leave it to fire
   * against a torn-down tree.
   */
  it('cancels the pending dismiss timer when the screen unmounts mid-confirmation', async () => {
    vi.useFakeTimers();
    try {
      const { unmount } = render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(runNowButton('Triggered ✓')).toBeDisabled();
      // The dismiss timer must actually be pending, beside the poll interval, or the zero below
      // would also pass for a component that never armed one. Not `toBe(2)`: an idle timer the
      // other card may hold is harmless and must not fail this.
      expect(vi.getTimerCount()).toBeGreaterThan(1);

      unmount();
      expect(vi.getTimerCount(), OUTLIVED).toBe(0);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ⚠️ One click, one run. The button used to disable itself only once the call RESOLVED, so the
   * second press of a double-click landed on an enabled button and sent a second
   * `POST .../trigger` — and the backend's `triggerNow` queues an immediate run per call, so a
   * briefing or a tide refresh ran twice. It is now disabled from the click until the call
   * settles, still reading "Run Now" (the confirmation must not claim a run before the server has
   * accepted it).
   *
   * The `toBeDisabled()` before the second press is what carries this test. A browser never
   * dispatches the user's click to a disabled button; jsdom does, and it is React's own filter on a
   * disabled button's `onClick` that drops it here, so the second press cannot fail where the
   * disabled assertion passed. It stays to pin the count a double-click must produce. `act` flushes
   * every lane, so this cannot tell an ordinary `setPending` from one moved into a transition — the
   * component's doc comment carries that rule.
   */
  it('sends no second trigger call when Run Now is pressed again while the first is in flight', async () => {
    vi.useFakeTimers();
    try {
      const release = deferTrigger();
      render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      expect(triggerJob).toHaveBeenCalledTimes(1);
      expect(runNowButton('Run Now')).toBeDisabled();

      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledTimes(1);
      // Still in flight: nothing has resolved it, so nothing may have confirmed it.
      expect(runNowButton('Run Now')).toBeDisabled();

      release();
      await pump();
      expect(runNowButton('Triggered ✓')).toBeDisabled();
      expect(triggerJob).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * The in-flight guard must not become a one-shot. The error test below pins that a failed call
   * leaves the button ENABLED; this pins that pressing it then actually sends. A guard that latched
   * per mount (a ref set on the first press, beside `pending`) passes every other test in this file
   * while leaving the error banner asking for a retry the button silently swallows.
   */
  it('sends the retry after a failed trigger call', async () => {
    vi.useFakeTimers();
    try {
      triggerJob.mockRejectedValueOnce(new Error('Network error'));
      render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(screen.getByText('Failed to trigger tide_refresh')).toBeInTheDocument();

      // The retry resolves (the default mock).
      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(triggerJob).toHaveBeenCalledTimes(2);
      expect(runNowButton('Triggered ✓')).toBeDisabled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports a failed trigger in the error banner, never as a confirmation', async () => {
    triggerJob.mockRejectedValue(new Error('Network error'));
    render(<SchedulerView />);

    await screen.findByTestId('scheduler-job-tide_refresh');
    fireEvent.click(runNowButton('Run Now'));

    expect(await screen.findByText('Failed to trigger tide_refresh')).toBeInTheDocument();
    expect(runNowButton('Run Now')).toBeEnabled();
  });

  /**
   * The confirmation moved from one parent map keyed by job into per-card state, so this pins
   * that it is still PER JOB: lifting it back to a single flag, or keying the cards by index,
   * would light every card's button at once.
   */
  it('confirms only the job that was triggered', async () => {
    vi.useFakeTimers();
    try {
      render(<SchedulerView />);
      await pump();

      fireEvent.click(runNowButton('Run Now'));
      await pump();
      expect(runNowButton('Triggered ✓')).toBeDisabled();

      const other = within(screen.getByTestId('scheduler-job-aurora_polling'));
      expect(other.getByRole('button', { name: 'Run Now' })).toBeEnabled();
      expect(other.queryByRole('button', { name: 'Triggered ✓' })).toBeNull();
    } finally {
      vi.useRealTimers();
    }
  });
});

// ---------------------------------------------------------------------------
// describeCron() — UK time conversion (exact-value, mutation-killing)
//
// vi.useFakeTimers({ toFake: ['Date'] }) pins Date to April 2026 so that
// Europe/London is deterministically on BST (UTC+1). A winter test pins to
// January 2026 (GMT). setInterval/setTimeout are NOT faked so polling works.
// ---------------------------------------------------------------------------
describe('describeCron() — UK time conversion', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-04-13T10:00:00Z')); // BST: London = UTC+1
    fetchSchedulerJobs.mockResolvedValue(MOCK_JOBS);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('converts a single UTC hour to BST (+1)', async () => {
    // 04:00 UTC → 05:00 BST
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 4 * * *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 05:00 BST',
      );
    });
  });

  it('converts multiple comma-separated UTC hours to BST — all shifted +1', async () => {
    // 04:00, 14:00, 22:00 UTC → 05:00, 15:00, 23:00 BST
    fetchSchedulerJobs.mockResolvedValue([{
      ...MOCK_JOBS[0], cronExpression: '0 0 4,14,22 * * *',
    }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 05:00, 15:00, 23:00 BST',
      );
    });
  });

  it('wraps midnight UTC (00:00) to 01:00 BST', async () => {
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 0 * * *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 01:00 BST',
      );
    });
  });

  it('converts weekly cron and uses the correct day name', async () => {
    // 02:00 UTC MON → 03:00 BST Every Monday
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 2 * * MON' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Every Monday at 03:00 BST',
      );
    });
  });

  it.each([
    ['TUE', 'Tuesday'],
    ['WED', 'Wednesday'],
    ['THU', 'Thursday'],
    ['FRI', 'Friday'],
    ['SAT', 'Saturday'],
    ['SUN', 'Sunday'],
  ])('maps dow %s to full name "%s"', async (dow, name) => {
    fetchSchedulerJobs.mockResolvedValue([{
      ...MOCK_JOBS[0], cronExpression: `0 0 3 * * ${dow}`,
    }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        `Every ${name} at 04:00 BST`,
      );
    });
  });

  it('preserves non-zero minutes — 07:30 UTC → 08:30 BST', async () => {
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 30 7 * * *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 08:30 BST',
      );
    });
  });

  it('returns the raw expression when day-of-month is not *', async () => {
    // dom=1 → the guard fails, falls through to raw cron — kills dom guard removal mutant
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 4 1 * *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent('0 0 4 1 * *');
    });
  });

  it('returns the raw expression when month is not *', async () => {
    // mon=6 → the guard fails, falls through to raw cron — kills mon guard removal mutant
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 4 * 6 *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent('0 0 4 * 6 *');
    });
  });

  it('shows GMT label and no offset in winter (January)', async () => {
    vi.setSystemTime(new Date('2026-01-15T10:00:00Z')); // GMT: London = UTC+0
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 4 * * *' }]);
    render(<SchedulerView />);
    await waitFor(() => {
      // In GMT, 04:00 UTC == 04:00 local — label is GMT, not BST
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 04:00 GMT',
      );
    });
  });

  it('edit-mode cron hint also converts to UK time', async () => {
    fetchSchedulerJobs.mockResolvedValue([{ ...MOCK_JOBS[0], cronExpression: '0 0 4 * * *' }]);
    render(<SchedulerView />);
    await waitFor(() => screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));

    fireEvent.change(screen.getByTestId('edit-input-tide_refresh'), {
      target: { value: '0 0 3 * * *' },
    });

    await waitFor(() => {
      // 03:00 UTC → 04:00 BST — the hint in edit mode uses describeCron too
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Daily at 04:00 BST',
      );
    });
  });
});
