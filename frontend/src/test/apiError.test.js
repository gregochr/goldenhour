import { describe, it, expect } from 'vitest';
import { apiErrorMessage } from '../utils/apiError.js';

const refused = (data, message = 'Request failed with status code 400') => ({
  message,
  response: { status: 400, data },
});

describe('apiErrorMessage', () => {
  it("returns the server's error sentence over the fallback and axios's generic message", () => {
    const err = refused({ error: "'Gosforth Nature Reserve' is not a sky location" });
    expect(apiErrorMessage(err, 'Failed to start forecast run'))
      .toBe("'Gosforth Nature Reserve' is not a sky location");
  });

  it('returns message when only message is present', () => {
    expect(apiErrorMessage(refused({ message: 'Pass ?confirm=true' }), 'fallback'))
      .toBe('Pass ?confirm=true');
  });

  it('prefers error when both keys are present', () => {
    expect(apiErrorMessage(refused({ error: 'Confirmation required', message: 'Pass ?confirm=true' }), 'fb'))
      .toBe('Confirmation required');
  });

  it("returns the caller's fallback, not err.message, when the server said neither", () => {
    expect(apiErrorMessage(refused({}), 'Failed to save outcome.')).toBe('Failed to save outcome.');
  });

  it('returns err.message when there is no fallback and the server said nothing', () => {
    expect(apiErrorMessage(refused({}))).toBe('Request failed with status code 400');
  });

  it('tolerates an error with no response (a network failure)', () => {
    expect(apiErrorMessage({ message: 'Network Error' }, 'Failed to load')).toBe('Failed to load');
    expect(apiErrorMessage({ message: 'Network Error' })).toBe('Network Error');
  });

  it('never shows a string body, such as a proxy error page', () => {
    const err = refused('<html><body>502 Bad Gateway</body></html>');
    expect(apiErrorMessage(err, 'Failed to load')).toBe('Failed to load');
  });

  it('ignores a null body', () => {
    expect(apiErrorMessage(refused(null), 'Failed to load')).toBe('Failed to load');
  });

  it('skips an empty or blank error string and falls through to message, then the fallback', () => {
    expect(apiErrorMessage(refused({ error: '', message: 'second key' }), 'fb')).toBe('second key');
    expect(apiErrorMessage(refused({ error: '   ' }), 'fb')).toBe('fb');
    expect(apiErrorMessage(refused({ error: '' }), 'fb')).toBe('fb');
  });

  it('ignores a non-string error value', () => {
    expect(apiErrorMessage(refused({ error: { code: 1 } }), 'fb')).toBe('fb');
  });

  it('survives a null or undefined error', () => {
    expect(apiErrorMessage(null, 'fb')).toBe('fb');
    expect(apiErrorMessage(undefined)).toBeUndefined();
  });
});
