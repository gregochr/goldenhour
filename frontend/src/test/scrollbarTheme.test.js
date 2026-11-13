import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The themed scrollbars, read from the stylesheet as text (jsdom loads no CSS). They live in
 * `@layer base` so the hidden-scrollbar scrollers' own unlayered rules still win.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Returns the text between the braces opened at `open` (the index of a `{`). */
function blockFrom(text, open) {
  let depth = 1;
  let i = open + 1;
  while (depth > 0 && i < text.length) {
    if (text[i] === '{') depth += 1;
    else if (text[i] === '}') depth -= 1;
    i += 1;
  }
  return text.slice(open + 1, i - 1);
}

/** Every `@layer <name> { ... }` body, with the offset of the at-rule. */
function layers(name) {
  const found = [];
  const re = new RegExp(`@layer\\s+${name}\\s*\\{`, 'g');
  let m = re.exec(css);
  while (m !== null) {
    found.push({ start: m.index, body: blockFrom(css, m.index + m[0].length - 1) });
    m = re.exec(css);
  }
  return found;
}

describe('themed scrollbars', () => {
  const base = layers('base').map((l) => l.body).join('\n');

  it('themes the thumb on :root and thins every scroller, in the base layer', () => {
    expect(base).toMatch(/:root\s*\{\s*scrollbar-color:\s*var\(--color-plex-text-faint\)\s+transparent;\s*\}/);
    expect(base).toMatch(/\*\s*\{\s*scrollbar-width:\s*thin;\s*\}/);
  });

  it('falls back to the webkit pseudo-elements only where scrollbar-color is unsupported', () => {
    const at = base.indexOf('@supports not (scrollbar-color: auto)');
    expect(at).toBeGreaterThan(-1);
    const fallback = blockFrom(base, base.indexOf('{', at));
    expect(fallback).toMatch(/::-webkit-scrollbar\s*\{[^}]*width:\s*8px;/);
    expect(fallback).toMatch(/::-webkit-scrollbar-thumb\s*\{[^}]*background-color:\s*var\(--color-plex-text-faint\);/);
    expect(fallback).toMatch(/border-radius:\s*999px;/);
    // Nothing webkit-scrollbar-related outside the @supports.
    const outside = base.replace(fallback, '');
    expect(outside).not.toMatch(/::-webkit-scrollbar/);
  });

  it('leaves the hidden-scrollbar scrollers unlayered so their own rules still win', () => {
    const tabs = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .find(([, sel, body]) => sel.split(',').some((s) => s.trim() === '.wf-tabs') && body.includes('scrollbar-width'));
    expect(tabs).toBeDefined();
    expect(tabs[2]).toMatch(/scrollbar-width:\s*none;/);
    const idx = css.indexOf(tabs[0]);
    const insideLayer = [...layers('base'), ...layers('components'), ...layers('utilities')]
      .some((l) => idx > l.start && idx < l.start + l.body.length + 20);
    expect(insideLayer).toBe(false);
  });
});
