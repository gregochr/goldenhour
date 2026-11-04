import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import {
  fetchAreas, createArea, updateArea, setAreaLocations, deleteArea, fetchUncoveredLocations,
  fetchLocationAliases, setLocationAliases,
} from '../api/areaApi.js';

describe('areaApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    for (const verb of ['get', 'post', 'put', 'delete']) apiClient[verb].mockResolvedValue({ data: 'body' });
  });

  it.each([
    ['fetchAreas', () => fetchAreas(), 'get', '/api/areas', undefined],
    ['fetchUncoveredLocations', () => fetchUncoveredLocations(), 'get', '/api/areas/uncovered-locations', undefined],
    ['fetchLocationAliases', () => fetchLocationAliases(), 'get', '/api/locations/aliases', undefined],
    ['createArea', () => createArea({ name: 'A' }), 'post', '/api/areas', { name: 'A' }],
    ['updateArea', () => updateArea(3, { name: 'B' }), 'put', '/api/areas/3', { name: 'B' }],
    ['setAreaLocations', () => setAreaLocations(3, [1, 2]), 'put', '/api/areas/3/locations', { locationIds: [1, 2] }],
    ['setLocationAliases', () => setLocationAliases(8, ['x', 'y']), 'put', '/api/locations/8/aliases',
      { aliases: ['x', 'y'] }],
  ])('%s uses %s %s with the expected body and returns the data', async (_n, call, verb, url, body) => {
    expect(await call()).toBe('body');
    const args = apiClient[verb].mock.calls[0];
    expect(args).toEqual(body === undefined ? [url] : [url, body]);
  });

  it('deleteArea DELETEs the area and resolves to nothing', async () => {
    expect(await deleteArea(3)).toBeUndefined();
    expect(apiClient.delete).toHaveBeenCalledWith('/api/areas/3');
  });

  it('setAreaLocations sends an empty list through to clear an area', async () => {
    await setAreaLocations(3, []);
    expect(apiClient.put).toHaveBeenCalledWith('/api/areas/3/locations', { locationIds: [] });
  });

  it('propagates a failure', async () => {
    apiClient.delete.mockRejectedValue(new Error('409'));
    await expect(deleteArea(3)).rejects.toThrow('409');
  });
});
