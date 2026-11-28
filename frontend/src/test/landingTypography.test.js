import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';

/**
 * Guards the marketing site's typography rule (docs/engineering/landing-guide-plan.md, T1): Newsreader is for
 * headings, the wordmark and the hero's one italic tagline; every sentence is IBM Plex Sans. Reads `landing/`
 * from disk, as `landingNginxConf.test.js` does.
 */
const LANDING = resolve(__dirname, '../../../landing');

const SERIF_ALLOWED = new Set([
  ':root',
  'h1', 'h2', 'h3', 'h4',
  '.mark .wm',
  '.foot .tag',
  '.hero .sub',
  '.price .amt',
  '.g-next span',
  '.qa h2', '.qa h3',
]);

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

/** Flattens a stylesheet into {selector, body} rules, descending into @media/@supports but skipping @font-face. */
function parseRules(css) {
  const text = css.replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let i = 0;
  while (i < text.length) {
    const open = text.indexOf('{', i);
    if (open < 0) break;
    const head = text.slice(i, open).trim();
    let depth = 1;
    let j = open + 1;
    while (j < text.length && depth > 0) {
      if (text[j] === '{') depth += 1;
      else if (text[j] === '}') depth -= 1;
      j += 1;
    }
    const body = text.slice(open + 1, j - 1);
    if (head.startsWith('@')) {
      if (/^@(media|supports|layer)/.test(head)) rules.push(...parseRules(body));
    } else {
      rules.push({ selector: head.replace(/\s+/g, ' '), body });
    }
    i = j;
  }
  return rules;
}

const files = walk(LANDING);
const sheets = [];
files.filter((f) => f.endsWith('.css')).forEach((f) => sheets.push({ file: f, css: readFileSync(f, 'utf8') }));
files.filter((f) => f.endsWith('.html')).forEach((f) => {
  const html = readFileSync(f, 'utf8');
  for (const m of html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)) sheets.push({ file: f, css: m[1] });
});

const SERIF_VALUE = /Newsreader|var\(--disp\)|var\(--serif\)/;

describe('landing typography', () => {
  it('finds the landing stylesheets', () => {
    expect(sheets.length).toBeGreaterThan(2);
    expect(sheets.some((s) => s.file.endsWith('photocast.css'))).toBe(true);
  });

  it('sets a serif face only on allow-listed selectors', () => {
    const offenders = [];
    for (const { file, css } of sheets) {
      for (const { selector, body } of parseRules(css)) {
        const serifDecl = body.split(';').some((d) => {
          const [prop, ...rest] = d.split(':');
          return /^\s*font(-family)?\s*$/.test(prop) && SERIF_VALUE.test(rest.join(':'));
        });
        if (serifDecl) {
          selector.split(',').map((s) => s.trim()).forEach((part) => {
            if (!SERIF_ALLOWED.has(part)) offenders.push(`${file}: ${part}`);
          });
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('keeps the allow-list honest: every allowed selector is still used', () => {
    const used = new Set();
    for (const { css } of sheets) {
      for (const { selector, body } of parseRules(css)) {
        if (/font-family\s*:[^;]*(Newsreader|var\(--disp\))/.test(body)) {
          selector.split(',').forEach((s) => used.add(s.trim()));
        }
      }
    }
    for (const sel of SERIF_ALLOWED) {
      if (sel !== ':root') expect(used.has(sel), sel).toBe(true);
    }
  });

  it('has no inline font-family in landing HTML', () => {
    const inline = files.filter((f) => f.endsWith('.html'))
      .filter((f) => /style\s*=\s*"[^"]*font(-family)?\s*:/i.test(readFileSync(f, 'utf8')));
    expect(inline).toEqual([]);
  });

  it('no longer defines or uses the --serif token anywhere under landing/', () => {
    const hits = files.filter((f) => /\.(css|html|js|md)$/.test(f))
      .filter((f) => readFileSync(f, 'utf8').includes('--serif'));
    expect(hits).toEqual([]);
  });
});
