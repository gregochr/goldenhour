import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { fetchFavourites, setFavourite } from '../api/favouritesApi.js';

/** The favourites endpoints (F3's wire shape): which verb and path each call makes, and that each returns the body. */
describe('favouritesApi', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the whole merged list', async () => {
    apiClient.get.mockResolvedValue({ data: { places: [], areas: [], locationIds: [] } });
    await expect(fetchFavourites()).resolves.toEqual({ places: [], areas: [], locationIds: [] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/user/settings/favourites');
  });

  it('stars with POST and un-stars with DELETE, on the places and areas paths', async () => {
    apiClient.post.mockResolvedValue({ data: { places: [1] } });
    apiClient.delete.mockResolvedValue({ data: { places: [] } });

    await expect(setFavourite('place', 4, true)).resolves.toEqual({ places: [1] });
    expect(apiClient.post).toHaveBeenCalledWith('/api/user/settings/favourites/places/4');
    await setFavourite('area', 9, true);
    expect(apiClient.post).toHaveBeenLastCalledWith('/api/user/settings/favourites/areas/9');
    await expect(setFavourite('place', 4, false)).resolves.toEqual({ places: [] });
    expect(apiClient.delete).toHaveBeenCalledWith('/api/user/settings/favourites/places/4');
    await setFavourite('area', 9, false);
    expect(apiClient.delete).toHaveBeenLastCalledWith('/api/user/settings/favourites/areas/9');
  });
});
