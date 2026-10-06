import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  askPeekMode, PEEK_HEIGHT, peekRestingHeight, peekTargetHeight, PEEK_SETTLED_PHASES,
} from '../utils/askPeek.js';

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
  it('are the figures of the plan: 74 plain, 126 collapsed, 112 minimised, 408 open with the row, 470 Ask', () => {
    expect(PEEK_HEIGHT).toEqual({
      plain: 74, collapsed: 126, minimised: 112, open: 356, openWithAsk: 408, ask: 470,
    });
  });

  it.each([
    ['off', false, 74],
    ['off', true, 356],
    ['collapsed', false, 126],
    ['minimised', false, 112],
    ['section', true, 408],
    ['expanded', false, 470],
  ])('the target for %s (a section open: %s) is %ipx', (m, sectionOpen, px) => {
    expect(peekTargetHeight(m, sectionOpen)).toBe(px);
  });

  it.each([
    [false, false, 74],
    [false, true, 74],
    [true, false, 126],
    [true, true, 112],
  ])('the resting height with Ask offered %s and an answer settled %s is %ipx', (offered, settled, px) => {
    expect(peekRestingHeight(offered, settled)).toBe(px);
  });

  // 126 = the three buttons' 74 + the Ask row's 44 + its 8px margin; both numbers live in the stylesheet.
  it('are arithmetically the row on top of the sheet that was', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const entry = /\.wf-map-peek-ask-entry\s*\{[^}]*height:\s*(\d+)px/.exec(css);
    const row = /\.wf-map-peek-ask-row\s*\{[^}]*margin:\s*0 10px (\d+)px/.exec(css);
    expect(entry, '.wf-map-peek-ask-entry height').not.toBeNull();
    expect(row, '.wf-map-peek-ask-row margin').not.toBeNull();
    expect(PEEK_HEIGHT.plain + Number(entry[1]) + Number(row[1])).toBe(PEEK_HEIGHT.collapsed);
  });

  it('the peek\'s settled phases are an answer, a plan (F5), a not-in-the-forecast reply and a failure: never empty, never busy', () => {
    expect([...PEEK_SETTLED_PHASES].sort()).toEqual(['answer', 'cant', 'error', 'plan']);
    expect(PEEK_SETTLED_PHASES).not.toContain('busy');
    expect(PEEK_SETTLED_PHASES).not.toContain('empty');
  });

  it('a plan phase rests as the minimised line, like an answer', () => {
    expect(mode({ phase: 'plan' })).toBe('minimised');
  });
});
