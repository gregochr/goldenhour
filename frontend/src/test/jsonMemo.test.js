import { describe, it, expect, vi, beforeEach } from 'vitest';
import { clearJsonMemo, getMemoised, parseMemoised, parseErrorBody } from '../utils/jsonMemo.js';
import { clearSwrCache } from '../utils/swrCache.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getAuroraStatus, getAuroraAlert, getAuroraViewline } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import apiClient from '../api/axiosClient.js';

vi.mock('../api/axiosClient.js', () => ({ default: { get: vi.fn() } }));

const reply = (data, status = 200) => ({ data, status });

beforeEach(() => {
  clearJsonMemo();
  apiClient.get.mockReset();
});

describe('parseMemoised', () => {
  it('returns the identical object for identical raw text', () => {
    const a = parseMemoised('/u', '{"a":1}');
    const b = parseMemoised('/u', '{"a":1}');
    expect(a).toEqual({ a: 1 });
    expect(Object.is(a, b)).toBe(true);
  });

  it('returns a new object when the text changes, then remembers that one', () => {
    const a = parseMemoised('/u', '{"a":1}');
    const b = parseMemoised('/u', '{"a":2}');
    expect(Object.is(a, b)).toBe(false);
    expect(b).toEqual({ a: 2 });
    expect(Object.is(b, parseMemoised('/u', '{"a":2}'))).toBe(true);
  });

  it('keeps a separate memo per URL', () => {
    const a = parseMemoised('/one', '{"a":1}');
    const b = parseMemoised('/two', '{"a":1}');
    expect(Object.is(a, b)).toBe(false);
  });

  it('passes through non-strings, empty bodies and non-JSON text as before', () => {
    const obj = { a: 1 };
    expect(parseMemoised('/u', obj)).toBe(obj);
    expect(parseMemoised('/u', '')).toBe('');
    expect(parseMemoised('/u', '<html>portal</html>')).toBe('<html>portal</html>');
  });

  it('is cleared by clearJsonMemo and by clearSwrCache (logout)', () => {
    const a = parseMemoised('/u', '{"a":1}');
    clearSwrCache();
    const b = parseMemoised('/u', '{"a":1}');
    expect(Object.is(a, b)).toBe(false);
    clearJsonMemo();
    expect(Object.is(b, parseMemoised('/u', '{"a":1}'))).toBe(false);
  });
});

describe('parseErrorBody / getMemoised', () => {
  it('re-parses a text error body to JSON', () => {
    const err = { response: { status: 500, data: '{"message":"boom"}' } };
    expect(parseErrorBody(err).response.data).toEqual({ message: 'boom' });
  });

  it('leaves a non-JSON error body and a missing response alone', () => {
    expect(parseErrorBody({ response: { data: 'oops' } }).response.data).toBe('oops');
    const noResponse = new Error('network');
    expect(parseErrorBody(noResponse)).toBe(noResponse);
  });

  it('asks for text and rethrows with a parsed error body', async () => {
    apiClient.get.mockRejectedValue({ response: { status: 500, data: '{"code":"X"}' } });
    await expect(getMemoised(apiClient, '/u')).rejects.toMatchObject({ response: { data: { code: 'X' } } });
    expect(apiClient.get).toHaveBeenCalledWith('/u', { responseType: 'text' });
  });
});

describe('the polled API functions', () => {
  const cases = [
    ['getDailyBriefing', getDailyBriefing, '/api/briefing'],
    ['getAllEvaluationScores', getAllEvaluationScores, '/api/briefing/evaluate/scores'],
    ['getAuroraStatus', getAuroraStatus, '/api/aurora/status'],
    ['getAuroraAlert', getAuroraAlert, '/api/aurora/alert'],
    ['getNlcSighting', getNlcSighting, '/api/nlc/sighting'],
    ['getAuroraViewline', getAuroraViewline, '/api/aurora/viewline'],
  ];

  it.each(cases)('%s returns the identical object for an identical reply and a new one for a changed reply',
    async (_name, fn, url) => {
      apiClient.get.mockImplementation(async () => reply('{"level":"MODERATE","n":1}'));
      const first = await fn();
      const second = await fn();
      expect(first).toEqual({ level: 'MODERATE', n: 1 });
      expect(Object.is(first, second)).toBe(true);
      expect(apiClient.get).toHaveBeenCalledWith(url, expect.objectContaining({ responseType: 'text' }));

      apiClient.get.mockImplementation(async () => reply('{"level":"MODERATE","n":2}'));
      const third = await fn();
      expect(Object.is(first, third)).toBe(false);
      expect(third).toEqual({ level: 'MODERATE', n: 2 });
    });

  it('getDailyBriefing still answers null for a 204', async () => {
    apiClient.get.mockResolvedValue(reply('', 204));
    expect(await getDailyBriefing()).toBeNull();
  });

  it('getDailyBriefing still rejects with a parsed error body', async () => {
    apiClient.get.mockRejectedValue({ response: { status: 500, data: '{"message":"down"}' } });
    await expect(getDailyBriefing()).rejects.toMatchObject({ response: { data: { message: 'down' } } });
  });

  it('getAuroraStatus still answers null for a 403', async () => {
    apiClient.get.mockRejectedValue({ response: { status: 403, data: '{"message":"no"}' } });
    expect(await getAuroraStatus()).toBeNull();
  });

  it('does not hand one account\'s parsed object to the next after a logout', async () => {
    apiClient.get.mockImplementation(async () => reply('{"level":"IDLE"}'));
    const mine = await getAuroraStatus();
    clearSwrCache();
    const next = await getAuroraStatus();
    expect(next).toEqual(mine);
    expect(Object.is(mine, next)).toBe(false);
  });
});
