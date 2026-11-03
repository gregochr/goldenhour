import { describe, it, expect } from 'vitest';
import {
  askPeekMode, PEEK_HEIGHT, peekRestingHeight, peekTargetHeight,
} from '../utils/askPeek.js';
import { SETTLED_PHASES } from '../utils/askModel.js';

/**
 * The phone peek sheet's Ask states, pure (`utils/askPeek.js`). The sheet's state is DERIVED from three
 * facts, so these are the table the derivation encodes; `AskPeekStateTable.test.jsx` is the same table
 * seen through `MapView`, and `askPeekCss.test.js` holds the heights to the stylesheet.
 */

const mode = (over) => askPeekMode({
  offered: true, expanded: false, section: null, phase: 'empty', ...over,
});

describe('askPeekMode', () => {
  it('is off whenever Ask is not offered, whatever else is true', () => {
    expect(mode({ offered: false })).toBe('off');
    expect(mode({ offered: false, expanded: true, phase: 'answer', section: 'win' })).toBe('off');
  });

  it('is collapsed with nothing settled and nothing open', () => {
    expect(mode({})).toBe('collapsed');
    expect(mode({ phase: 'busy' })).toBe('collapsed');
  });

  it.each(['answer', 'cant', 'error'])('is minimised with a %s settled and nothing open', (phase) => {
    expect(mode({ phase })).toBe('minimised');
  });

  it('is expanded while the Ask section is open — settled or not', () => {
    expect(mode({ expanded: true })).toBe('expanded');
    expect(mode({ expanded: true, phase: 'answer' })).toBe('expanded');
  });

  it('is "section" while another section is open, and the answer behind it is kept for when it closes', () => {
    expect(mode({ section: 'win' })).toBe('section');
    expect(mode({ section: 'lay', phase: 'answer' })).toBe('section');
    // …and closing it is the same derivation with `section: null`.
    expect(mode({ section: null, phase: 'answer' })).toBe('minimised');
  });

  it('puts the Ask section ahead of another open section (one gate: they cannot both be set in practice)', () => {
    expect(mode({ expanded: true, section: 'win' })).toBe('expanded');
  });
});

describe('the heights', () => {
  it('are the three figures of the compact plan: 74 at rest, 356 a section open, 470 the Ask section', () => {
    expect(PEEK_HEIGHT).toEqual({ resting: 74, open: 356, ask: 470 });
  });

  it('no longer carry the Ask row\'s 126 / 112 / 408 (C2 removed the row)', () => {
    expect(Object.values(PEEK_HEIGHT)).not.toContain(126);
    expect(Object.values(PEEK_HEIGHT)).not.toContain(112);
    expect(Object.values(PEEK_HEIGHT)).not.toContain(408);
  });

  it.each([
    ['off', false, 74],
    ['off', true, 356],
    ['collapsed', false, 74],
    ['minimised', false, 74],
    ['section', true, 356],
    ['expanded', false, 470],
    ['expanded', true, 470],
  ])('the target for %s (a section open: %s) is %ipx', (m, sectionOpen, px) => {
    expect(peekTargetHeight(m, sectionOpen)).toBe(px);
  });

  it('the resting height is 74px: the minimised line takes the buttons\' place', () => {
    expect(peekRestingHeight()).toBe(74);
  });

  it('the peek\'s settled phases are an answer, a plan (F5), a not-in-the-forecast reply and a failure: never empty, never busy', () => {
    expect([...SETTLED_PHASES].sort()).toEqual(['answer', 'cant', 'error', 'plan']);
    expect(SETTLED_PHASES).not.toContain('busy');
    expect(SETTLED_PHASES).not.toContain('empty');
  });

  it('a plan phase rests as the minimised line, like an answer', () => {
    expect(mode({ phase: 'plan' })).toBe('minimised');
  });
});
