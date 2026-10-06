import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
import apiClient from '../api/axiosClient.js';
import {
  ASK_TIMEOUT_MS, ask, AskApiError, getAskSettings, getReady,
} from '../api/askApi.js';
import { apiErrorMessage } from '../utils/apiError.js';
import { cantResponse, ownResponse, readyResponse, settings } from './askFixtures.js';

/** An axios failure carrying the response the server sent. */
const refused = (status, body) => Object.assign(new Error(`Request failed with status code ${status}`), {
  response: { status, data: body },
});

beforeEach(() => vi.resetAllMocks());

describe('askApi — the three endpoints', () => {
  it('reads the Ready list for a scope, the scope sent as text', async () => {
    apiClient.get.mockResolvedValue({ data: readyResponse() });

    const body = await getReady(3);

    expect(apiClient.get).toHaveBeenCalledWith('/api/ask/ready', { params: { scope: '3' } });
    expect(body.questions).toHaveLength(4);
  });

  it('defaults the Ready scope to all', async () => {
    apiClient.get.mockResolvedValue({ data: readyResponse() });

    await getReady();

    expect(apiClient.get).toHaveBeenCalledWith('/api/ask/ready', { params: { scope: 'all' } });
  });

  it('posts a typed question to /api/ask and returns the body untouched', async () => {
    apiClient.post.mockResolvedValue({ data: ownResponse() });
    const body = { question: 'Where is good?', regionIds: [3], view: 'map', windowId: '2026-10-05_sunset' };

    const answer = await ask(body);

    expect(apiClient.post).toHaveBeenCalledWith('/api/ask', body, { timeout: ASK_TIMEOUT_MS });
    expect(answer).toEqual(ownResponse());
  });

  it('puts a ceiling on a typed question, a little over the server’s own 30 s deadline', () => {
    expect(ASK_TIMEOUT_MS).toBe(40_000);
  });

  it('reads the allowance from the personal-data prefix', async () => {
    apiClient.get.mockResolvedValue({ data: settings({ used: 1, left: 2 }) });

    expect(await getAskSettings()).toEqual(settings({ used: 1, left: 2 }));
    expect(apiClient.get).toHaveBeenCalledWith('/api/user/settings/ask');
  });

  it('serves a cant reply as a normal 200, not an error', async () => {
    apiClient.post.mockResolvedValue({ data: cantResponse() });

    expect((await ask({ question: 'Is the car park busy?', regionIds: [], view: 'plan' })).kind).toBe('cant');
  });
});

describe('askApi — every refusal in §2.9 arrives as {status, code, error}', () => {
  it.each([
    [400, 'INVALID', 'The question could not be used.'],
    [429, 'RATE_LIMITED', 'Slow down a moment.'],
    [429, 'ALLOWANCE_EXHAUSTED', 'You’ve used today’s questions. Ready questions are still available.'],
    [429, 'DAILY_LIMIT', 'That’s the most questions we can take from you today.'],
    [502, 'ENGINE_FAILED', 'Couldn’t answer just now. No question used.'],
    [503, 'TYPED_UNAVAILABLE', 'Typed questions are unavailable right now.'],
    [401, 'UNAUTHENTICATED', 'Please sign in again.'],
  ])('%i %s', async (status, code, error) => {
    apiClient.post.mockRejectedValue(refused(status, { error, code }));

    const failure = await ask({ question: 'x', regionIds: [], view: 'plan' }).catch((e) => e);

    expect(failure).toBeInstanceOf(AskApiError);
    expect(failure).toMatchObject({ status, code, error });
  });

  it('makes the server sentence the Error’s message, so the app’s shared helper reads it', async () => {
    apiClient.post.mockRejectedValue(refused(429, { error: 'Slow down a moment.', code: 'RATE_LIMITED' }));

    const failure = await ask({ question: 'x', regionIds: [], view: 'plan' }).catch((e) => e);

    expect(apiErrorMessage(failure)).toBe('Slow down a moment.');
    expect(failure.message).toBe('Slow down a moment.');
  });

  it('404 (Ask switched off) carries a status and no code', async () => {
    apiClient.post.mockRejectedValue(refused(404, ''));

    const failure = await ask({ question: 'x', regionIds: [], view: 'plan' }).catch((e) => e);

    expect(failure).toMatchObject({ status: 404, code: null, error: null });
  });

  it('a proxy’s HTML error page is not mistaken for a sentence meant for the reader', async () => {
    apiClient.get.mockRejectedValue(refused(502, '<html>Bad gateway</html>'));

    const failure = await getReady('all').catch((e) => e);

    expect(failure).toMatchObject({ status: 502, code: null, error: null });
  });

  it('no response at all is a null status, never a guessed one', async () => {
    apiClient.post.mockRejectedValue(new Error('Network Error'));

    const failure = await ask({ question: 'x', regionIds: [], view: 'plan' }).catch((e) => e);

    expect(failure).toMatchObject({ status: null, code: null, error: null });
    expect(failure.message).toBe('Network Error');
  });

  it('a blank error sentence is treated as none', async () => {
    apiClient.get.mockRejectedValue(refused(400, { error: '   ', code: '' }));

    const failure = await getAskSettings().catch((e) => e);

    expect(failure).toMatchObject({ status: 400, code: null, error: null });
  });
});
