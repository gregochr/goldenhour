import { describe, it, expect } from 'vitest';
import { peekTargetHeight } from '../utils/askPeek.js';

describe('askPeek edges', () => {
  it('heads for the open height when the mode is a section, even with no section flag', () => {
    expect(peekTargetHeight('section', false)).toBe(356);
  });

  it('heads for the open height when a section is open, whatever the mode', () => {
    expect(peekTargetHeight('collapsed', true)).toBe(356);
  });

  it('heads for the Ask height when expanded and the resting height otherwise', () => {
    expect(peekTargetHeight('expanded', true)).toBe(470);
    expect(peekTargetHeight('collapsed', false)).toBe(74);
    expect(peekTargetHeight('minimised', false)).toBe(74);
  });
});
