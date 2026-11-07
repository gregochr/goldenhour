import { describe, it, expect } from 'vitest';
import { errorText } from '../utils/aliasText.js';

describe('errorText — a refusal with no body', () => {
  it('uses the fallback when the response has no data, or null data, or no response at all', () => {
    expect(errorText({ response: {} }, 'Try again')).toBe('Try again');
    expect(errorText({ response: { data: null } }, 'Try again')).toBe('Try again');
    expect(errorText({ response: { data: undefined } }, 'Try again')).toBe('Try again');
    expect(errorText({}, 'Try again')).toBe('Try again');
    expect(errorText(null, 'Try again')).toBe('Try again');
  });

  it('uses the server\'s sentence when there is one', () => {
    expect(errorText({ response: { data: { error: '"Alnwick" clashes with another location' } } }, 'Try again'))
      .toBe('"Alnwick" clashes with another location');
  });
});
