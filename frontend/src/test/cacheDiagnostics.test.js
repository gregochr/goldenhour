import { describe, it, expect } from 'vitest';
import {
  cacheBadge,
  parseCacheDiagnostics,
  tallyCacheDiagnostics,
} from '../utils/cacheDiagnostics.js';

const MISS = '{"status":"MISS","reason":"messages_changed","missedInputTokens":1234}';

describe('parseCacheDiagnostics', () => {
  it('reads what the backend writes', () => {
    expect(parseCacheDiagnostics(MISS)).toEqual({
      status: 'MISS',
      reason: 'messages_changed',
      missedInputTokens: 1234,
    });
    expect(parseCacheDiagnostics('{"status":"PENDING"}')).toEqual({
      status: 'PENDING',
      reason: null,
      missedInputTokens: null,
    });
  });

  it('is null for none, an empty string, malformed JSON and JSON of another shape', () => {
    expect(parseCacheDiagnostics(null)).toBeNull();
    expect(parseCacheDiagnostics(undefined)).toBeNull();
    expect(parseCacheDiagnostics('')).toBeNull();
    expect(parseCacheDiagnostics('{oops')).toBeNull();
    expect(parseCacheDiagnostics('[]')).toBeNull();
    expect(parseCacheDiagnostics('null')).toBeNull();
    expect(parseCacheDiagnostics('{"reason":"x"}')).toBeNull();
    expect(parseCacheDiagnostics(42)).toBeNull();
  });

  it('ignores a reason or token estimate of the wrong type', () => {
    expect(parseCacheDiagnostics('{"status":"MISS","reason":7,"missedInputTokens":"many"}')).toEqual({
      status: 'MISS',
      reason: null,
      missedInputTokens: null,
    });
  });
});

describe('cacheBadge', () => {
  it('is null when the call carries no diagnostics', () => {
    expect(cacheBadge({ cacheDiagnostics: null, cacheReadInputTokens: 4726 })).toBeNull();
    expect(cacheBadge(undefined)).toBeNull();
  });

  it('shows the cache read, then the miss reason with its token estimate', () => {
    expect(cacheBadge({ cacheDiagnostics: MISS, cacheReadInputTokens: 4726 }))
      .toBe('cache: read 4,726 · miss — messages_changed (1,234 tok)');
  });

  it('shows a write when the call wrote the cache, and both when it did both', () => {
    expect(cacheBadge({ cacheDiagnostics: MISS, cacheCreationInputTokens: 900 }))
      .toBe('cache: write 900 · miss — messages_changed (1,234 tok)');
    expect(cacheBadge({
      cacheDiagnostics: MISS,
      cacheReadInputTokens: 4000,
      cacheCreationInputTokens: 726,
    })).toBe('cache: read 4,000 · write 726 · miss — messages_changed (1,234 tok)');
  });

  it('names a pending comparison and a reason with no estimate or no name', () => {
    expect(cacheBadge({ cacheDiagnostics: '{"status":"PENDING"}' })).toBe('cache: pending');
    expect(cacheBadge({ cacheDiagnostics: '{"status":"MISS","reason":"unavailable"}' }))
      .toBe('cache: miss — unavailable');
    expect(cacheBadge({ cacheDiagnostics: '{"status":"MISS"}' })).toBe('cache: miss — unknown');
  });
});

describe('tallyCacheDiagnostics', () => {
  it('counts each reason, most common first, ties by name, ignoring calls with none', () => {
    const calls = [
      { cacheDiagnostics: MISS },
      { cacheDiagnostics: MISS },
      { cacheDiagnostics: null },
      { cacheDiagnostics: '{"status":"MISS","reason":"system_changed"}' },
      { cacheDiagnostics: '{"status":"PENDING"}' },
      { cacheDiagnostics: 'not json' },
    ];
    expect(tallyCacheDiagnostics(calls)).toEqual([
      { label: 'messages_changed', count: 2 },
      { label: 'pending', count: 1 },
      { label: 'system_changed', count: 1 },
    ]);
  });

  it('is empty when no call carries diagnostics', () => {
    expect(tallyCacheDiagnostics([{ cacheDiagnostics: null }, {}])).toEqual([]);
  });
});
