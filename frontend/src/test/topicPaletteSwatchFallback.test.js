import { describe, it, expect } from 'vitest';
import { DEFAULT_SWATCH_COLOR, SWATCH_COLOR, topicStyle } from '../utils/topicPalette.js';

// A topic added to the type table without a swatch colour must still draw (in the neutral grey) rather than render
// an undefined colour. SWATCH_COLOR is an exported, mutable table, so the drift is reproducible here: the entry is
// removed for the duration of the assertion and put back in a finally.
describe('topicStyle swatch fallback', () => {
  it('uses the neutral colour for a known type that has no swatch entry, keeping its own name and glyph', () => {
    const saved = SWATCH_COLOR.MIST;
    expect(saved).toBeDefined();
    expect(topicStyle('MIST').colour).toBe(saved);
    try {
      delete SWATCH_COLOR.MIST;
      const style = topicStyle('mist');
      expect(style.colour).toBe(DEFAULT_SWATCH_COLOR);
      expect(style.name).toBe('Mist');
      expect(style.chipName).toBe('Mist');
    } finally {
      SWATCH_COLOR.MIST = saved;
    }
    expect(topicStyle('MIST').colour).toBe(saved);
  });
});
