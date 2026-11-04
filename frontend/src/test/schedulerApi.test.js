import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import {
  fetchSchedulerJobs, updateJobSchedule, previewSchedule, switchScheduleType, fetchSolarPreview,
  pauseJob, resumeJob, fetchSchedulerSummary, fetchJobDetail, triggerJob, fetchSchedulerDay,
} from '../api/schedulerApi.js';

const BASE = '/api/admin/scheduler';

describe('schedulerApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    for (const verb of ['get', 'post', 'put']) apiClient[verb].mockResolvedValue({ data: 'body' });
  });

  it.each([
    ['fetchSchedulerJobs', () => fetchSchedulerJobs(), 'get', `${BASE}/jobs`],
    ['fetchSolarPreview', () => fetchSolarPreview(), 'get', `${BASE}/solar-preview`],
    ['fetchSchedulerSummary', () => fetchSchedulerSummary(), 'get', `${BASE}/jobs/summary`],
    ['fetchJobDetail', () => fetchJobDetail('nightly'), 'get', `${BASE}/jobs/nightly/detail`],
    ['pauseJob', () => pauseJob('nightly'), 'post', `${BASE}/jobs/nightly/pause`],
    ['resumeJob', () => resumeJob('nightly'), 'post', `${BASE}/jobs/nightly/resume`],
    ['triggerJob', () => triggerJob('nightly'), 'post', `${BASE}/jobs/nightly/trigger`],
  ])('%s uses %s %s and returns the data', async (_n, call, verb, url) => {
    expect(await call()).toBe('body');
    expect(apiClient[verb]).toHaveBeenCalledTimes(1);
    expect(apiClient[verb].mock.calls[0]).toEqual([url]);
  });

  it('updateJobSchedule PUTs the schedule to the job key', async () => {
    const schedule = { cron: '0 0 * * *' };
    expect(await updateJobSchedule('nightly', schedule)).toBe('body');
    expect(apiClient.put).toHaveBeenCalledWith(`${BASE}/jobs/nightly/schedule`, schedule);
  });

  it('switchScheduleType PUTs the body to schedule-type', async () => {
    const body = { type: 'SOLAR' };
    expect(await switchScheduleType('nightly', body)).toBe('body');
    expect(apiClient.put).toHaveBeenCalledWith(`${BASE}/jobs/nightly/schedule-type`, body);
  });

  describe('previewSchedule', () => {
    it('POSTs the body and forwards the abort signal', async () => {
      const { signal } = new AbortController();
      expect(await previewSchedule('nightly', { cron: 'x' }, { signal })).toBe('body');
      expect(apiClient.post).toHaveBeenCalledWith(`${BASE}/jobs/nightly/schedule-preview`, { cron: 'x' }, { signal });
    });

    it('sends an undefined signal when none is given', async () => {
      await previewSchedule('nightly', { cron: 'x' });
      expect(apiClient.post).toHaveBeenCalledWith(`${BASE}/jobs/nightly/schedule-preview`, { cron: 'x' }, { signal: undefined });
    });
  });

  describe('fetchSchedulerDay', () => {
    it('sends no config when no date is given, so today is the server default', async () => {
      await fetchSchedulerDay();
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/day`, undefined);
    });

    it('sends the date as a query param', async () => {
      await fetchSchedulerDay('2026-11-03');
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/day`, { params: { date: '2026-11-03' } });
    });
  });

  it('propagates a failure', async () => {
    apiClient.post.mockRejectedValue(new Error('409'));
    await expect(triggerJob('nightly')).rejects.toThrow('409');
  });
});
