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

const PHASES = ['answer', 'plan', 'cant', 'error'];

/**
 * Every array literal in {@code source} (a {@code new Set([...])} holds one) whose quoted members include
 * all four settled phases, in any order, and are not the whole phase enum. Order-insensitive on purpose: a restated list is a restated list
 * whichever way round it was typed.
 */
function listsNamingEveryPhase(source) {
  const found = [];
  for (const literal of source.matchAll(/\[([^[\]]*)\]/g)) {
    const members = new Set([...literal[1].matchAll(/(['"`])(.*?)\1/g)].map((m) => m[2]));
    // The full phase enum (a PropTypes.oneOf) also names all four, but it names empty and busy too: it is
    // every phase, not the settled ones.
    const everyPhase = members.has('empty') || members.has('busy');
    if (!everyPhase && PHASES.every((phase) => members.has(phase))) found.push(literal[0]);
  }
  return found;
}

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
    expect(listsNamingEveryPhase(source)).toEqual([]);
    expect(source).not.toMatch(/PEEK_SETTLED_PHASES/);
  });
});

describe('the restated-list detector', () => {
  it.each([
    ["an array in the canonical order", "const A = ['answer', 'plan', 'cant', 'error'];"],
    ["an array in another order", "const A = [\"error\", 'cant',\n  'plan', 'answer'];"],
    ["a Set", "const A = new Set(['plan', 'error', 'answer', 'cant']);"],
  ])('catches %s', (_, source) => {
    expect(listsNamingEveryPhase(source)).toHaveLength(1);
  });

  it('ignores the full phase enum, which also names empty and busy', () => {
    expect(listsNamingEveryPhase("oneOf(['empty', 'busy', 'answer', 'plan', 'cant', 'error'])")).toEqual([]);
  });

  it('ignores a list that names only some of the phases', () => {
    expect(listsNamingEveryPhase("const A = ['answer', 'cant', 'error'];")).toEqual([]);
  });
});
