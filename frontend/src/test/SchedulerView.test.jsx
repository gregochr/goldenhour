import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
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
  {
    id: 3,
    jobKey: 'met_office_scrape',
    displayName: 'Met Office Scrape',
    description: 'Scrapes Met Office space weather',
    scheduleType: 'FIXED_DELAY',
    cronExpression: '',
    fixedDelayMs: 3600000,
    initialDelayMs: 300000,
    status: 'DISABLED_BY_CONFIG',
    lastFireTime: '',
    lastCompletionTime: '',
    nextFireTime: '',
    configSource: 'aurora.enabled',
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
      expect(screen.getByTestId('scheduler-job-met_office_scrape')).toBeInTheDocument();
    });
  });

  it('shows correct status pills', async () => {
    render(<SchedulerView />);
    await waitFor(() => {
      expect(screen.getByTestId('status-pill-tide_refresh')).toHaveTextContent('Active');
      expect(screen.getByTestId('status-pill-aurora_polling')).toHaveTextContent('Paused');
      expect(screen.getByTestId('status-pill-met_office_scrape')).toHaveTextContent(
        'Disabled by Config',
      );
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
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('resume-btn-met_office_scrape')).toBeDisabled();
      expect(screen.getByTestId('trigger-btn-met_office_scrape')).toBeDisabled();
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
    expect(screen.getByTestId('scheduler-job-met_office_scrape').textContent).not.toContain('3600000ms');
  });

  it('fixed delay hourly job shows human-readable description', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-met_office_scrape')).toHaveTextContent(
        'Every hour',
      );
    });
  });

  it('shows config source for disabled jobs', async () => {
    render(<SchedulerView />);

    await waitFor(() => {
      expect(screen.getByTestId('scheduler-job-met_office_scrape')).toHaveTextContent(
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
