import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import {
  getAuroraStatus, enrichBortle, simulateAurora, clearSimulation, getAuroraLocations,
  getAuroraForecastPreview, runAuroraForecast, getAuroraForecastResults,
  getAuroraForecastAvailableDates, getAuroraForecastViewline, getAuroraViewline,
} from '../api/auroraApi.js';

const httpError = (status) => Object.assign(new Error(`HTTP ${status}`), { response: { status } });

// The reads that go through getMemoised ask for the body as text and parse it themselves.
const AS_TEXT = { responseType: 'text' };

describe('auroraApi', () => {
  beforeEach(() => vi.resetAllMocks());

  describe('plain pass-throughs', () => {
    it.each([
      ['enrichBortle', () => enrichBortle(), '/api/aurora/admin/enrich-bortle'],
      ['clearSimulation', () => clearSimulation(), '/api/aurora/admin/simulate/clear'],
    ])('%s POSTs with no body and returns the data', async (_n, call, url) => {
      apiClient.post.mockResolvedValue({ data: { ok: 1 } });
      expect(await call()).toEqual({ ok: 1 });
      expect(apiClient.post).toHaveBeenCalledWith(url);
    });

    it('simulateAurora POSTs the request unchanged', async () => {
      const req = { kp: 7, ovationProbability: 45, bzNanoTesla: -12, gScale: null };
      apiClient.post.mockResolvedValue({ data: { active: true } });
      expect(await simulateAurora(req)).toEqual({ active: true });
      expect(apiClient.post).toHaveBeenCalledWith('/api/aurora/admin/simulate', req);
    });

    it('getAuroraForecastPreview GETs the preview', async () => {
      apiClient.get.mockResolvedValue({ data: { nights: [] } });
      expect(await getAuroraForecastPreview()).toEqual({ nights: [] });
      expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/forecast/preview');
    });

    it('runAuroraForecast POSTs the chosen nights wrapped in an object', async () => {
      apiClient.post.mockResolvedValue({ data: { started: true } });
      expect(await runAuroraForecast(['2026-11-04'])).toEqual({ started: true });
      expect(apiClient.post).toHaveBeenCalledWith('/api/aurora/forecast/run', { nights: ['2026-11-04'] });
    });

    it('getAuroraForecastResults passes the date as a query param', async () => {
      apiClient.get.mockResolvedValue({ data: [{ id: 1 }] });
      expect(await getAuroraForecastResults('2026-11-04')).toEqual([{ id: 1 }]);
      expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/forecast/results', { params: { date: '2026-11-04' } });
    });
  });

  describe('getAuroraLocations', () => {
    it('defaults to Bortle 4 and one star', async () => {
      apiClient.get.mockResolvedValue({ data: [] });
      await getAuroraLocations();
      expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/locations', { params: { maxBortle: 4, minStars: 1 } });
    });

    it('takes explicit filters, and keeps one default when only the other is given', async () => {
      apiClient.get.mockResolvedValue({ data: ['x'] });
      expect(await getAuroraLocations({ maxBortle: 2 })).toEqual(['x']);
      expect(apiClient.get).toHaveBeenLastCalledWith('/api/aurora/locations', { params: { maxBortle: 2, minStars: 1 } });
      await getAuroraLocations({ minStars: 3 });
      expect(apiClient.get).toHaveBeenLastCalledWith('/api/aurora/locations', { params: { maxBortle: 4, minStars: 3 } });
    });
  });

  describe('getAuroraStatus', () => {
    it('reads /api/aurora/status as text and returns the parsed body', async () => {
      apiClient.get.mockResolvedValue({ data: '{"state":"ACTIVE"}' });
      expect(await getAuroraStatus()).toEqual({ state: 'ACTIVE' });
      expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/status', AS_TEXT);
    });

    it.each([401, 403])('resolves null for a %i (no aurora UI for that reader)', async (status) => {
      apiClient.get.mockRejectedValue(httpError(status));
      expect(await getAuroraStatus()).toBeNull();
    });

    it('rethrows any other failure', async () => {
      const err = httpError(500);
      apiClient.get.mockRejectedValue(err);
      await expect(getAuroraStatus()).rejects.toBe(err);
    });
  });

  describe('getAuroraForecastAvailableDates', () => {
    it('GETs the dates and returns them', async () => {
      apiClient.get.mockResolvedValue({ data: ['2026-11-03'] });
      expect(await getAuroraForecastAvailableDates()).toEqual(['2026-11-03']);
      expect(apiClient.get).toHaveBeenCalledWith('/api/aurora/forecast/results/available-dates');
    });

    it.each([401, 403])('resolves an empty list for a %i', async (status) => {
      apiClient.get.mockRejectedValue(httpError(status));
      expect(await getAuroraForecastAvailableDates()).toEqual([]);
    });

    it('rethrows any other failure, including a network error with no response', async () => {
      const err = new Error('network');
      apiClient.get.mockRejectedValue(err);
      await expect(getAuroraForecastAvailableDates()).rejects.toBe(err);
    });
  });

  describe.each([
    ['getAuroraViewline', getAuroraViewline, '/api/aurora/viewline'],
    ['getAuroraForecastViewline', getAuroraForecastViewline, '/api/aurora/viewline/forecast'],
  ])('%s', (_name, fn, url) => {
    it('reads its own URL as text and returns the parsed body', async () => {
      apiClient.get.mockResolvedValue({ data: '{"points":[1,2]}' });
      expect(await fn()).toEqual({ points: [1, 2] });
      expect(apiClient.get).toHaveBeenCalledWith(url, AS_TEXT);
    });

    it.each([401, 403])('resolves null for a %i', async (status) => {
      apiClient.get.mockRejectedValue(httpError(status));
      expect(await fn()).toBeNull();
    });

    it('rethrows any other failure', async () => {
      const err = httpError(503);
      apiClient.get.mockRejectedValue(err);
      await expect(fn()).rejects.toBe(err);
    });
  });
});
