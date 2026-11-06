import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import { fetchDrivePointPlaces, fetchUnroutableLocations } from '../api/driveTimesAdminApi.js';

describe('driveTimesAdminApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('GETs the admin unroutable path and returns the body', async () => {
    const rows = [{ locationId: 7, name: 'Helvellyn' }];
    apiClient.get.mockResolvedValue({ data: rows });

    await expect(fetchUnroutableLocations()).resolves.toBe(rows);

    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/drive-times/unroutable');
  });

  it('GETs the admin places lookup with the query and the location as params, and returns the candidates', async () => {
    const places = [{ name: 'Glenridding', detail: 'Village, Cumberland', lat: 54.5442, lon: -2.9505, distanceKm: 4.6 }];
    apiClient.get.mockResolvedValue({ data: places });

    await expect(fetchDrivePointPlaces('Glen', 54.5275, -3.0162)).resolves.toBe(places);

    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/drive-times/places', {
      params: { q: 'Glen', lat: 54.5275, lon: -3.0162 },
    });
  });
});
