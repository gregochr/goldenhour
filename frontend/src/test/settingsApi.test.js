import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import {
  getSettings, lookupPostcode, reverseLookupPostcode, saveHome, refreshDriveTimes,
  saveMapColourPreferences, saveMapTideMode, markComingUpSeen, getReach, markPostcodePromptDismissed,
  getReachVerdicts,
} from '../api/settingsApi.js';

const BASE = '/api/user/settings';

describe('settingsApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    for (const verb of ['get', 'post', 'put']) apiClient[verb].mockResolvedValue({ data: 'body' });
  });

  it.each([
    ['getSettings', () => getSettings(), 'get', BASE],
    ['getReach', () => getReach(), 'get', `${BASE}/reach`],
    ['refreshDriveTimes', () => refreshDriveTimes(), 'post', `${BASE}/drive-times/refresh`],
    ['markComingUpSeen', () => markComingUpSeen(), 'put', `${BASE}/coming-up-seen`],
  ])('%s uses %s %s with no body and returns the data', async (_n, call, verb, url) => {
    expect(await call()).toBe('body');
    expect(apiClient[verb].mock.calls[0]).toEqual([url]);
  });

  it.each([
    ['lookupPostcode', () => lookupPostcode('NE1 1AA'), 'post', `${BASE}/home/lookup`, { postcode: 'NE1 1AA' }],
    ['reverseLookupPostcode', () => reverseLookupPostcode(54.9, -1.6), 'post', `${BASE}/home/reverse`,
      { latitude: 54.9, longitude: -1.6 }],
    ['saveMapColourPreferences', () => saveMapColourPreferences('VIVID'), 'put', `${BASE}/map-colours`,
      { mapColourScale: 'VIVID' }],
    ['saveMapTideMode', () => saveMapTideMode('PREFERENCE'), 'put', `${BASE}/map-tide-mode`,
      { mapTideMode: 'PREFERENCE' }],
  ])('%s sends the wrapped body and returns the data', async (_n, call, verb, url, body) => {
    expect(await call()).toBe('body');
    expect(apiClient[verb]).toHaveBeenCalledWith(url, body);
  });

  describe('saveHome', () => {
    it('defaults the local radius to null', async () => {
      expect(await saveHome('NE1 1AA', 54.9, -1.6)).toBe('body');
      expect(apiClient.put).toHaveBeenCalledWith(`${BASE}/home`, {
        postcode: 'NE1 1AA', latitude: 54.9, longitude: -1.6, localRadiusMiles: null,
      });
    });

    it('sends a given radius', async () => {
      await saveHome('NE1 1AA', 54.9, -1.6, 25);
      expect(apiClient.put.mock.calls[0][1].localRadiusMiles).toBe(25);
    });
  });

  describe('getReachVerdicts', () => {
    it('GETs the reach-verdicts route with no query for home', async () => {
      expect(await getReachVerdicts()).toBe('body');
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/reach-verdicts`, { params: undefined });
      await getReachVerdicts(null);
      expect(apiClient.get).toHaveBeenLastCalledWith(`${BASE}/reach-verdicts`, { params: undefined });
    });

    it('sends the away origin\'s region id as ?origin=', async () => {
      await getReachVerdicts(7);
      expect(apiClient.get).toHaveBeenCalledWith(`${BASE}/reach-verdicts`, { params: { origin: 7 } });
    });
  });

  it('markPostcodePromptDismissed PUTs with no body and a 10 s timeout', async () => {
    expect(await markPostcodePromptDismissed()).toBe('body');
    expect(apiClient.put).toHaveBeenCalledWith(`${BASE}/postcode-prompt-dismissed`, undefined, { timeout: 10000 });
  });

  it('propagates a failure', async () => {
    apiClient.put.mockRejectedValue(new Error('500'));
    await expect(markPostcodePromptDismissed()).rejects.toThrow('500');
  });
});
