import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { SETTLED_PHASES } from '../utils/askModel.js';
import { askPeekMode } from '../utils/askPeek.js';

/**
 * "Settled" — an answer worth keeping, minimising and clearing — has ONE list, `askModel.SETTLED_PHASES`.
 * It used to be two (`AskClearAnswer`'s and `askPeek`'s) that nothing pinned together, and adding the
 * plan phase was two edits. Behaviour proves the peek sheet reads it; the source checks prove no
 * consumer quietly grew a second list again (jsdom cannot tell two equal arrays from one).
 */

const read = (path) => readFileSync(resolve(process.cwd(), 'src', path), 'utf8');

/** Every module that decides "is something settled on screen". */
const CONSUMERS = [
  'components/ask/AskClearAnswer.jsx',
  'utils/askPeek.js',
  'components/map/MapPeekAsk.jsx',
  'components/MapView.jsx',
];

describe('SETTLED_PHASES', () => {
  it('is the conversation’s answer-bearing phases, and neither empty nor busy', () => {
    expect([...SETTLED_PHASES].sort()).toEqual(['answer', 'cant', 'error', 'plan']);
  });

  it('is what decides the peek sheet’s minimised line, phase by phase', () => {
    const modeFor = (phase) => askPeekMode({
      offered: true, expanded: false, section: null, phase,
    });
    for (const phase of ['empty', 'busy', 'answer', 'plan', 'cant', 'error']) {
      expect(modeFor(phase)).toBe(SETTLED_PHASES.includes(phase) ? 'minimised' : 'collapsed');
    }
  });

  it.each(CONSUMERS)('is imported by %s, not restated there', (path) => {
    const source = read(path);

    expect(source).toMatch(/import\s*\{[^}]*\bSETTLED_PHASES\b[^}]*\}\s*from\s*'[./]*(?:utils\/)?askModel\.js'/);
    expect(source).not.toMatch(/\[\s*'answer'\s*,\s*'plan'\s*,\s*'cant'\s*,\s*'error'\s*\]/);
    expect(source).not.toMatch(/PEEK_SETTLED_PHASES/);
  });
});
