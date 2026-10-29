/**
 * The memoised polled endpoints request `responseType: 'text'`, so axios leaves their error bodies as
 * strings and does so BEFORE the response interceptor runs. These tests go through the real client, its
 * interceptors and the real API functions, replacing only the transport adapter, and prove the
 * interceptor still sees the JSON `code` (the password-change redirect depends on it).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { AxiosError } from 'axios';
import apiClient from '../api/axiosClient.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';

const realAdapter = apiClient.defaults.adapter;

/** An adapter that fails the way a browser transport does: an AxiosError carrying the raw text body. */
function failing(status, bodyText) {
  return (config) => Promise.reject(new AxiosError(
    `Request failed with status code ${status}`,
    'ERR_BAD_REQUEST',
    config,
    null,
    { status, statusText: '', data: bodyText, headers: {}, config },
  ));
}

describe('error bodies on text-requested (memoised) endpoints', () => {
  let dispatchSpy;

  beforeEach(() => {
    dispatchSpy = vi.spyOn(window, 'dispatchEvent');
  });

  afterEach(() => {
    apiClient.defaults.adapter = realAdapter;
    dispatchSpy.mockRestore();
  });

  const passwordEvents = () => dispatchSpy.mock.calls
    .filter(([event]) => event.type === 'goldenhour:password-change-required');

  it('dispatches password-change-required for a 403 PASSWORD_CHANGE_REQUIRED text body', async () => {
    apiClient.defaults.adapter = failing(403, '{"code":"PASSWORD_CHANGE_REQUIRED","error":"change it"}');

    await expect(getDailyBriefing()).rejects.toMatchObject({
      response: { data: { code: 'PASSWORD_CHANGE_REQUIRED' } },
    });
    expect(passwordEvents()).toHaveLength(1);
  });

  it('does so for the other memoised endpoints too', async () => {
    apiClient.defaults.adapter = failing(403, '{"code":"PASSWORD_CHANGE_REQUIRED"}');

    await getAllEvaluationScores().catch(() => {});
    await getAuroraStatus().catch(() => {});
    expect(passwordEvents().length).toBeGreaterThanOrEqual(1);
  });

  it('leaves a non-JSON error body as text and raises nothing', async () => {
    apiClient.defaults.adapter = failing(502, '<html>Bad gateway</html>');

    await expect(getDailyBriefing()).rejects.toMatchObject({ response: { data: '<html>Bad gateway</html>' } });
    expect(passwordEvents()).toHaveLength(0);
  });
});
