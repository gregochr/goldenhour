import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import {
  fetchRegions, fetchRegionDriveTimes, setRegionBase, addRegion, updateRegion, setRegionEnabled,
} from '../api/regionApi.js';

describe('regionApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    for (const verb of ['get', 'post', 'put']) apiClient[verb].mockResolvedValue({ data: 'body' });
  });

  it.each([
    ['fetchRegions', () => fetchRegions(), 'get', '/api/regions', undefined],
    ['fetchRegionDriveTimes', () => fetchRegionDriveTimes(), 'get', '/api/regions/drive-times', undefined],
    ['setRegionBase', () => setRegionBase(4, { latitude: 1, longitude: 2 }), 'put', '/api/regions/4/base',
      { latitude: 1, longitude: 2 }],
    ['addRegion', () => addRegion({ name: 'Lakes' }), 'post', '/api/regions', { name: 'Lakes' }],
    ['updateRegion', () => updateRegion(4, { name: 'Peaks' }), 'put', '/api/regions/4', { name: 'Peaks' }],
    ['setRegionEnabled', () => setRegionEnabled(4, false), 'put', '/api/regions/4/enabled', { enabled: false }],
  ])('%s uses %s %s with the expected body and returns the data', async (_n, call, verb, url, body) => {
    expect(await call()).toBe('body');
    const args = apiClient[verb].mock.calls[0];
    expect(args).toEqual(body === undefined ? [url] : [url, body]);
  });

  it('propagates a failure', async () => {
    apiClient.get.mockRejectedValue(new Error('500'));
    await expect(fetchRegions()).rejects.toThrow('500');
  });
});
