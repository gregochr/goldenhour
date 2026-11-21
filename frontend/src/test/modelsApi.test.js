import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), put: vi.fn() },
}));

import apiClient from '../api/axiosClient.js';
import { getAvailableModels, setActiveModel } from '../api/modelsApi.js';

describe('modelsApi', () => {
  let errorSpy;
  beforeEach(() => {
    vi.clearAllMocks();
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => errorSpy.mockRestore());

  it('getAvailableModels GETs /api/models and returns the body', async () => {
    apiClient.get.mockResolvedValue({ data: { available: [{ name: 'HAIKU' }] } });
    expect(await getAvailableModels()).toEqual({ available: [{ name: 'HAIKU' }] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/models');
  });

  it('setActiveModel PUTs runType and model', async () => {
    apiClient.put.mockResolvedValue({ data: { runType: 'BATCH_NEAR_TERM', active: 'SONNET_55' } });
    expect(await setActiveModel('BATCH_NEAR_TERM', 'SONNET_55'))
      .toEqual({ runType: 'BATCH_NEAR_TERM', active: 'SONNET_55' });
    expect(apiClient.put).toHaveBeenCalledWith('/api/models/active', { runType: 'BATCH_NEAR_TERM', model: 'SONNET_55' });
  });

  it.each([
    ['getAvailableModels', () => getAvailableModels(), 'get', 'Failed to fetch available models:'],
    ['setActiveModel', () => setActiveModel('X', 'Y'), 'put', 'Failed to set active model:'],
  ])('%s logs and rethrows the original error', async (_n, call, verb, message) => {
    const err = new Error('boom');
    apiClient[verb].mockRejectedValue(err);
    await expect(call()).rejects.toBe(err);
    expect(errorSpy).toHaveBeenCalledWith(message, err);
  });
});
