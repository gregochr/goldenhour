import { describe, it, expect } from 'vitest';
import { parseCacheDiagnostics } from '../utils/cacheDiagnostics.js';

describe('cacheDiagnostics edges', () => {
  it('rejects a non-string whose text form would be valid diagnostics', () => {
    // JSON.parse coerces its argument with String(); an array of one JSON string stringifies to it.
    expect(parseCacheDiagnostics(['{"status":"MISS","reason":"r"}'])).toBeNull();
    expect(parseCacheDiagnostics({ toString: () => '{"status":"MISS"}' })).toBeNull();
  });

  it('still reads the same text when it is a string', () => {
    expect(parseCacheDiagnostics('{"status":"MISS","reason":"r"}')).toEqual({
      status: 'MISS', reason: 'r', missedInputTokens: null,
    });
  });

  it('is null for JSON scalars and a status of the wrong type', () => {
    expect(parseCacheDiagnostics('"MISS"')).toBeNull();
    expect(parseCacheDiagnostics('7')).toBeNull();
    expect(parseCacheDiagnostics('{"status":5}')).toBeNull();
  });
});
