import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
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
      expect(screen.getByTestId('scheduler-job-tide_refresh')).toHaveTextContent(
        'Every Monday at 02:00 UTC',
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
