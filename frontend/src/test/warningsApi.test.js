import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

import apiClient from '../api/axiosClient.js';
import { getWarnings } from '../api/warningsApi.js';
import { response, warning } from './warningsFixture.js';

const httpError = (status) => Object.assign(new Error(`HTTP ${status}`), { response: { status } });

describe('warningsApi.getWarnings', () => {
  beforeEach(() => vi.resetAllMocks());

  it('GETs /api/warnings as text and returns the parsed body', async () => {
    const body = response([warning()]);
    apiClient.get.mockResolvedValue({ data: JSON.stringify(body) });
    expect(await getWarnings()).toEqual(body);
    expect(apiClient.get).toHaveBeenCalledWith('/api/warnings', { responseType: 'text' });
  });

  it('answers the same object for an unchanged body (the provider does not re-render)', async () => {
    const text = JSON.stringify(response([]));
    apiClient.get.mockResolvedValue({ data: text });
    const first = await getWarnings();
    apiClient.get.mockResolvedValue({ data: text });
    expect(await getWarnings()).toBe(first);
  });

  it('answers null for an empty body', async () => {
    apiClient.get.mockResolvedValue({ data: null });
    expect(await getWarnings()).toBeNull();
  });

  it.each([401, 403, 404])('answers null on a %i, never an all-clear', async (status) => {
    apiClient.get.mockRejectedValue(httpError(status));
    expect(await getWarnings()).toBeNull();
  });

  it('rethrows any other failure so the caller keeps what it had', async () => {
    apiClient.get.mockRejectedValue(httpError(500));
    await expect(getWarnings()).rejects.toThrow('HTTP 500');
    apiClient.get.mockRejectedValue(new Error('network'));
    await expect(getWarnings()).rejects.toThrow('network');
  });
});
