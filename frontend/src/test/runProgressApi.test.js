import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { retryFailed } from '../api/runProgressApi.js';
import { apiErrorMessage } from '../utils/apiError.js';

// The backend answers a retry it cannot start with 404 and an EMPTY body (ForecastController's
// retry-failed handler), and other refusals with {"error": "..."}. fetch does not reject on a
// refusal, so the function itself has to turn it into something the caller can read a status from.

const reply = (status, body) => ({
  ok: status >= 200 && status < 300,
  status,
  text: () => Promise.resolve(body === undefined ? '' : body),
  json: () => Promise.resolve(body === undefined ? null : JSON.parse(body)),
});

const rejection = async (promise) => {
  try {
    await promise;
  } catch (err) {
    return err;
  }
  throw new Error('expected the call to reject');
};

describe('retryFailed', () => {
  beforeEach(() => {
    localStorage.setItem('goldenhour_token', 'tok');
    vi.stubGlobal('fetch', vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('posts to the run\'s retry-failed route with the bearer token and returns the new run', async () => {
    const body = JSON.stringify({ status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 8 });
    fetch.mockResolvedValue(reply(202, body));

    await expect(retryFailed(7)).resolves.toEqual({
      status: 'Retry run started', runType: 'SHORT_TERM', jobRunId: 8,
    });
    expect(fetch).toHaveBeenCalledWith('/api/forecast/run/7/retry-failed', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ Authorization: 'Bearer tok' }),
    }));
  });

  it('rejects with status 404 and no server sentence when the 404 has an empty body', async () => {
    fetch.mockResolvedValue(reply(404));

    const err = await rejection(retryFailed(7));

    expect(err.status).toBe(404);
    expect(err.response).toEqual({ status: 404, data: null });
    expect(apiErrorMessage(err, 'fallback')).toBe('fallback');
  });

  it('rejects carrying the server\'s sentence when a refusal has a JSON error body', async () => {
    fetch.mockResolvedValue(reply(400, JSON.stringify({ error: 'Run 7 is still in progress' })));

    const err = await rejection(retryFailed(7));

    expect(err.status).toBe(400);
    expect(apiErrorMessage(err, 'fallback')).toBe('Run 7 is still in progress');
  });

  it('keeps the status and drops the body when a refusal body is not JSON', async () => {
    fetch.mockResolvedValue(reply(502, '<html>Bad gateway</html>'));

    const err = await rejection(retryFailed(7));

    expect(err.status).toBe(502);
    expect(err.response.data).toBeNull();
  });

  it('rejects with a readable message and no status when the request never gets an answer', async () => {
    fetch.mockRejectedValue(new TypeError('Failed to fetch'));

    const err = await rejection(retryFailed(7));

    expect(err.message).toBe('Could not reach the server.');
    expect(err.status).toBeUndefined();
    expect(err.cause).toBeInstanceOf(TypeError);
  });
});
