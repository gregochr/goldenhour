import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';

/**
 * The big-seas chip's stylesheet contract, asserted against the REAL `index.css` (big-seas plan F2).
 * jsdom resolves no `var()` and applies no media queries, so what is asserted is the declared
 * mechanism: the type, the teal tokens (never a raw hex), the aligned ring, the phone size and the
 * forced-colours fallback (a 1px border, and the ring as a 2px outline since box-shadows vanish).
 */

const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** The declaration body of the first rule whose selector list STARTS with {@code selector}. */
function ruleBody(selector, from = 0) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(`(?:^|[}\\s])${escaped}[^{}]*\\{([^}]*)\\}`, 'm').exec(CSS.slice(from));
  expect(match, `rule ${selector} not found`).not.toBeNull();
  return match[1];
}

/** The text of the first {@code @media} block starting with {@code query}, braces balanced. */
function mediaBlock(query, from = 0) {
  const start = CSS.indexOf(`@media ${query}`, from);
  expect(start, `@media ${query} not found`).toBeGreaterThan(-1);
  let depth = 0;
  for (let i = CSS.indexOf('{', start); i < CSS.length; i += 1) {
    if (CSS[i] === '{') depth += 1;
    if (CSS[i] === '}') {
      depth -= 1;
      if (depth === 0) return CSS.slice(start, i + 1);
    }
  }
  throw new Error('unbalanced');
}

const CHIP = '.wf-maplab-chip.wf-bsc';

describe('the big-seas chip stylesheet', () => {
  const chipStart = CSS.indexOf(`${CHIP} {`);

  it('is the handoff\'s plate: IBM Plex Mono 9.5px, the dark teal plate, a 1px ring from the tide token', () => {
    const body = ruleBody(CHIP);
    expect(body).toContain('font-family: var(--font-mono)');
    expect(body).toContain('font-size: 9.5px');
    expect(body).toContain('padding: 2px 6px 2px 4px');
    expect(body).toContain('background: rgba(14, 22, 24, 0.92)');
    expect(body).toMatch(/inset 0 0 0 1px color-mix\(in srgb, var\(--color-tide\) 70%, transparent\)/);
  });

  it('rings the aligned chip with the lighter tide token and a halo', () => {
    const body = ruleBody(`${CHIP}[data-aligned='true']`, chipStart);
    expect(body).toContain('inset 0 0 0 1.5px var(--color-tide-light)');
    expect(body).toContain('0 0 0 3px color-mix(in srgb, var(--color-tide) 18%, transparent)');
  });

  it('puts the height behind a left rule, in the pale tide token', () => {
    const body = ruleBody('.wf-bsc-h');
    expect(body).toContain('color: var(--color-tide-pale)');
    expect(body).toMatch(/border-left: 1px solid color-mix\(in srgb, var\(--color-tide\) 45%, transparent\)/);
    expect(body).toContain('font-weight: 600');
    expect(ruleBody('.wf-bsc-n')).toContain('font-weight: 600');
  });

  it('is 10px on a phone, and only there', () => {
    const phone = mediaBlock('(max-width: 639px)', chipStart);
    expect(phone).toContain(`${CHIP} {`);
    expect(phone).toContain('font-size: 10px');
  });

  it('in forced colours takes a 1px border, and the aligned ring becomes a 2px outline', () => {
    const forced = mediaBlock('(forced-colors: active)', chipStart);
    expect(forced).toMatch(/\.wf-maplab-chip\.wf-bsc \{[^}]*border: 1px solid CanvasText/);
    expect(forced).toMatch(/\.wf-maplab-chip\.wf-bsc\[data-aligned='true'\] \{[^}]*outline: 2px solid Highlight/);
  });

  it('uses no raw hex for the teals the design names (they are tokens)', () => {
    const block = CSS.slice(chipStart, CSS.indexOf('.wf-legend-swatch-seas {', chipStart));
    expect(block).not.toMatch(/#6FA8B0|#8FC0C7|#A9D6DC|111,\s*168,\s*176/i);
  });
});
