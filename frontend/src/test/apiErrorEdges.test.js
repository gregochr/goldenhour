import { describe, it, expect } from 'vitest';
import { apiErrorMessage } from '../utils/apiError.js';

describe('apiErrorMessage — an empty fallback is no fallback', () => {
  it('falls through to err.message when the fallback is an empty string', () => {
    const err = { message: 'Network Error' };
    expect(apiErrorMessage(err, '')).toBe('Network Error');
    expect(apiErrorMessage(err, undefined)).toBe('Network Error');
    expect(apiErrorMessage(err, 'Could not save')).toBe('Could not save');
  });

  it('is undefined when there is nothing at all to show', () => {
    expect(apiErrorMessage({}, '')).toBeUndefined();
    expect(apiErrorMessage(null)).toBeUndefined();
  });
});
