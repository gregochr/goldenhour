import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SchedulerView from '../components/SchedulerView.jsx';
import { jobDetail, pollingJob, schedulePreview, solarJob, tideJob } from './schedulerFixtures.js';

vi.mock('../api/schedulerApi', () => ({
  fetchSchedulerJobs: vi.fn(),
  updateJobSchedule: vi.fn(),
  pauseJob: vi.fn(),
  resumeJob: vi.fn(),
  triggerJob: vi.fn(),
  fetchSolarPreview: vi.fn(),
  switchScheduleType: vi.fn(),
  fetchSchedulerSummary: vi.fn(),
  fetchJobDetail: vi.fn(),
  previewSchedule: vi.fn(),
}));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchSchedulerJobs, fetchSchedulerSummary, fetchJobDetail, fetchSolarPreview, pauseJob, previewSchedule,
  switchScheduleType, updateJobSchedule,
} from '../api/schedulerApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

// The save toast (scheduler-redesign-plan.md D16): "Saved. Next fire Tomorrow 12:27." read from the job as re-read
// after the save, and the saves from each editor reaching the right endpoint with the right body.

const ACTIVE_POLLING = pollingJob({ status: 'ACTIVE', nextFireTime: '', nextFires: [] });

async function openPanel(jobKey) {
  fireEvent.click(await screen.findByTestId(`scheduler-row-${jobKey}`));
  return screen.findByTestId('scheduler-panel');
}

/** Opens the fixed-delay editor on the polling job, picks 10 minutes and presses Save. */
async function saveDelay(reloadedJob) {
  fetchSchedulerJobs.mockResolvedValue([ACTIVE_POLLING]);
  render(<SchedulerView />);
  await openPanel('aurora_polling');
  fireEvent.click(screen.getByTestId('edit-btn-aurora_polling'));
  fireEvent.click(screen.getByTestId('fixed-delay-aurora_polling-600000'));
  // The re-read after the save serves the new next fire.
  fetchSchedulerJobs.mockResolvedValue([reloadedJob]);
  fireEvent.click(screen.getByTestId('save-btn-aurora_polling'));
}

beforeEach(() => {
  vi.clearAllMocks();
  window.location.hash = '#manage/scheduler';
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-03T10:00:00Z'));
  useIsSplitLayout.mockReturnValue(true);
  fetchSchedulerSummary.mockResolvedValue(null);
  fetchJobDetail.mockResolvedValue(jobDetail());
  fetchSolarPreview.mockRejectedValue(new Error('not under test'));
  previewSchedule.mockResolvedValue(schedulePreview());
  updateJobSchedule.mockResolvedValue({});
});

afterEach(() => {
  vi.useRealTimers();
});

describe('the save toast', () => {
  it.each([
    ['Today', ['2026-11-03T18:15:00Z'], 'Saved. Next fire Today 18:15.'],
    ['Tomorrow', ['2026-11-04T12:27:00Z'], 'Saved. Next fire Tomorrow 12:27.'],
    ['a weekday and date', ['2026-11-05T02:00:00Z'], 'Saved. Next fire Thu 5 Nov 02:00.'],
  ])('quotes the reloaded job\'s next fire as %s', async (_label, nextFires, text) => {
    await saveDelay({ ...ACTIVE_POLLING, nextFires });

    expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent(text);
    expect(updateJobSchedule).toHaveBeenCalledWith('aurora_polling', { fixedDelayMs: 600000 });
  });

  it('says "Saved." alone when the reloaded job has no next fire', async () => {
    await saveDelay({ ...ACTIVE_POLLING, nextFires: [], nextFireTime: '' });

    const toast = await screen.findByTestId('sub-region-toast');
    expect(toast.textContent).toBe('Saved.');
  });

  it('shows no toast when the save fails', async () => {
    updateJobSchedule.mockRejectedValue(new Error('400'));
    await saveDelay({ ...ACTIVE_POLLING, nextFires: ['2026-11-04T12:27:00Z'] });

    expect(await screen.findByTestId('inline-edit-error-aurora_polling')).toBeInTheDocument();
    expect(screen.queryByTestId('sub-region-toast')).not.toBeInTheDocument();
  });

  it('follows a sun-relative save, reading the next fire from the reloaded job', async () => {
    switchScheduleType.mockResolvedValue({});
    fetchSchedulerJobs.mockResolvedValue([solarJob()]);
    render(<SchedulerView />);
    await openPanel('near_term_batch_evaluation');
    fireEvent.click(await screen.findByTestId('edit-btn-near_term_batch_evaluation'));
    fireEvent.change(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset'), { target: { value: '240' } });
    fetchSchedulerJobs.mockResolvedValue([solarJob({ solarOffsetMinutes: 240, nextFires: ['2026-11-04T12:27:00Z'] })]);
    fireEvent.click(screen.getByTestId('save-btn-near_term_batch_evaluation'));

    expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent('Saved. Next fire Tomorrow 12:27.');
  });

  it('saves the cron editor\'s server-built cron with Europe/London, then toasts', async () => {
    const job = tideJob({ cronExpression: '0 15 6,18 * * *', timeZone: 'Europe/London' });
    fetchSchedulerJobs.mockResolvedValue([job]);
    previewSchedule.mockResolvedValue(schedulePreview({ cronExpression: '0 15 7,18 * * *' }));
    render(<SchedulerView />);
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.change(screen.getByTestId('cron-times-tide_refresh'), { target: { value: '07:15, 18:15' } });
    await waitFor(() => expect(screen.getByTestId('save-btn-tide_refresh')).toBeEnabled(), { timeout: 2000 });
    fetchSchedulerJobs.mockResolvedValue([{ ...job, nextFires: ['2026-11-03T18:15:00Z'] }]);
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(updateJobSchedule).toHaveBeenCalledWith('tide_refresh', {
      cronExpression: '0 15 7,18 * * *', timeZone: 'Europe/London',
    }));
    expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent('Saved. Next fire Today 18:15.');
  });

  it('gives the raw cron field no time zone, so the job keeps its own', async () => {
    fetchSchedulerJobs.mockResolvedValue([tideJob({ cronExpression: '0 20 * * * *' })]);
    render(<SchedulerView />);
    await openPanel('tide_refresh');
    fireEvent.click(screen.getByTestId('edit-btn-tide_refresh'));
    fireEvent.change(screen.getByTestId('edit-input-tide_refresh'), { target: { value: '0 30 * * * *' } });
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(updateJobSchedule).toHaveBeenCalledWith('tide_refresh', {
      cronExpression: '0 30 * * * *',
    }));
  });

  it('shows no toast for a pause', async () => {
    pauseJob.mockResolvedValue({});
    fetchSchedulerJobs.mockResolvedValue([ACTIVE_POLLING]);
    render(<SchedulerView />);
    await openPanel('aurora_polling');
    fireEvent.click(await screen.findByTestId('pause-btn-aurora_polling'));

    await waitFor(() => expect(pauseJob).toHaveBeenCalled());
    expect(screen.queryByTestId('sub-region-toast')).not.toBeInTheDocument();
  });
});

describe('Escape from each editor', () => {
  async function openEditor(job) {
    fetchSchedulerJobs.mockResolvedValue([job]);
    render(<SchedulerView />);
    await openPanel(job.jobKey);
    fireEvent.click(screen.getByTestId(`edit-btn-${job.jobKey}`));
  }

  it('cancels the edit from the slider, leaving the panel open', async () => {
    await openEditor(solarJob());
    fireEvent.keyDown(screen.getByTestId('solar-edit-near_term_batch_evaluation-offset'), { key: 'Escape' });
    expect(screen.queryByTestId('solar-edit-near_term_batch_evaluation')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('cancels the edit from a day toggle', async () => {
    await openEditor(tideJob({ cronExpression: '0 0 2 * * MON', timeZone: 'Europe/London' }));
    fireEvent.keyDown(screen.getByTestId('cron-day-tide_refresh-mon'), { key: 'Escape' });
    expect(screen.queryByTestId('cron-edit-tide_refresh')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
  });

  it('cancels the edit from a delay preset', async () => {
    await openEditor(ACTIVE_POLLING);
    fireEvent.keyDown(screen.getByTestId('fixed-delay-aurora_polling-600000'), { key: 'Escape' });
    expect(screen.queryByTestId('fixed-delay-edit-aurora_polling')).not.toBeInTheDocument();
    expect(screen.getByTestId('scheduler-panel')).toBeInTheDocument();
  });
});
