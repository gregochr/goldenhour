import { describe, it, expect, beforeEach } from 'vitest';
import { clearJsonMemo, parseErrorBody, parseMemoised } from '../utils/jsonMemo.js';

beforeEach(() => clearJsonMemo());

describe('parseMemoised — non-strings pass through untouched', () => {
  it('returns an array as the very same array rather than parsing its string form', () => {
    const arr = [7];
    expect(parseMemoised('/k', arr)).toBe(arr);
    expect(parseMemoised('/k', arr)).toEqual([7]);
  });

  it('returns an empty string as-is and does not memoise it', () => {
    expect(parseMemoised('/k', '')).toBe('');
    expect(parseMemoised('/k', '{"a":1}')).toEqual({ a: 1 });
  });

  it('returns a non-JSON string as the string', () => {
    expect(parseMemoised('/k', 'not json')).toBe('not json');
  });
});

describe('parseErrorBody — which bodies are re-parsed', () => {
  it('leaves a non-string body alone (an array is not re-parsed through its string form)', () => {
    const body = [5];
    const err = { response: { data: body } };
    expect(parseErrorBody(err).response.data).toBe(body);
    expect(err.response.data).toEqual([5]);
  });

  it('parses a JSON string body in place', () => {
    const err = { response: { data: '{"error":"nope"}' } };
    expect(parseErrorBody(err)).toBe(err);
    expect(err.response.data).toEqual({ error: 'nope' });
  });

  it('leaves an empty-string body and a non-JSON body as they are', () => {
    const empty = { response: { data: '' } };
    parseErrorBody(empty);
    expect(empty.response.data).toBe('');
    const text = { response: { data: 'Bad Gateway' } };
    parseErrorBody(text);
    expect(text.response.data).toBe('Bad Gateway');
  });

  it('returns a rejection with no response, or a null/undefined rejection, without throwing', () => {
    const noResponse = new Error('Network Error');
    expect(parseErrorBody(noResponse)).toBe(noResponse);
    expect(parseErrorBody(null)).toBeNull();
    expect(parseErrorBody(undefined)).toBeUndefined();
  });
});
