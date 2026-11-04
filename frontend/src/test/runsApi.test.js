import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import {
  fetchRuns, fetchJobRun, fetchRunDay, fetchRunCalendar, fetchRunCalls, fetchRunCost, fetchRunTiming,
} from '../api/runsApi.js';

const BASE = '/api/admin/runs';

describe('runsApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    apiClient.get.mockResolvedValue({ data: 'body' });
  });

  describe('fetchRuns', () => {
    it('sends an empty params object when called with nothing', async () => {
      expect(await fetchRuns()).toBe('body');
      expect(apiClient.get).toHaveBeenCalledWith(BASE, { params: {} });
    });

    it('sends every option that is set', async () => {
      await fetchRuns({ range: '7d', kind: 'BATCH', attention: true, before: 'cursor-1', size: 25 });
      expect(apiClient.get).toHaveBeenCalledWith(BASE, {
        params: { range: '7d', kind: 'BATCH', attention: true, before: 'cursor-1', size: 25 },
      });
    });

    it.each([
      ['attention false', { attention: false }],
      ['empty range', { range: '' }],
      ['empty kind', { kind: '' }],
      ['null before', { before: null }],
      ['zero size', { size: 0 }],
    ])('omits %s', async (_n, opts) => {
      await fetchRuns(opts);
      expect(apiClient.get).toHaveBeenCalledWith(BASE, { params: {} });
    });
  });

  it('fetchJobRun GETs the job by id', async () => {
    expect(await fetchJobRun(42)).toBe('body');
    expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/job/42`);
  });

  describe('fetchRunDay', () => {
    it('sends no date by default', async () => {
      await fetchRunDay();
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/day`, { params: {} });
    });

    it('sends the date when given', async () => {
      await fetchRunDay('2026-11-03');
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/day`, { params: { date: '2026-11-03' } });
    });
  });

  it('fetchRunCalendar passes the month', async () => {
    await fetchRunCalendar('2026-11');
    expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/calendar`, { params: { month: '2026-11' } });
  });

  it('fetchRunCalls passes the run id', async () => {
    await fetchRunCalls('r-9');
    expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/calls`, { params: { runId: 'r-9' } });
  });

  describe('fetchRunCost', () => {
    it('omits days by default', async () => {
      await fetchRunCost();
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/cost`, { params: {} });
    });

    it('sends days when given', async () => {
      await fetchRunCost(30);
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/cost`, { params: { days: 30 } });
    });
  });

  describe('fetchRunTiming', () => {
    it('always sends type and omits days and measure when unset', async () => {
      await fetchRunTiming({ type: 'BATCH' });
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/timing`, { params: { type: 'BATCH' } });
    });

    it('adds days and measure when set', async () => {
      await fetchRunTiming({ type: 'BATCH', days: 14, measure: 'p95' });
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/timing`, { params: { type: 'BATCH', days: 14, measure: 'p95' } });
    });
  });

  it('propagates a failure', async () => {
    apiClient.get.mockRejectedValue(new Error('500'));
    await expect(fetchRunDay()).rejects.toThrow('500');
  });
});
