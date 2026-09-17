import {
  describe, it, expect, beforeAll,
} from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compile } from 'tailwindcss';

/**
 * The focus rules of the shared `.btn`, `.btn-primary` and `.btn-secondary` classes — every
 * `.btn*` in the app, so a regression here reaches the admin views, the sign-in, registration and
 * change-password pages, the settings dialog and `ConfirmDialog`, among others.
 *
 * <p>Two defects, both measured in a browser before they were fixed. <b>Forced colours:</b> the
 * focus ring is a `box-shadow`, which forced-colours mode (Windows High Contrast) removes, and the
 * classes said `focus:outline-none` — `outline-style: none` since Tailwind 4 — so a focused button
 * showed nothing there (Chromium's and Firefox's emulation: not one pixel changed round the button).
 * In Tailwind 3 that utility was a transparent outline the mode repaints; the v4 migration kept its
 * name and lost that, and `outline-hidden` is v4's name for it. <b>Mouse presses:</b>
 * `.btn-primary`'s and `.btn`'s rings were keyed on `focus:`, and Chromium and Firefox focus a
 * pressed button, so every click drew the ring and it stayed until focus moved on.
 *
 * <p>jsdom renders no CSS, so the rules are pinned as text, in the three places a regression can
 * come from: the rules as written; what Tailwind compiles them to, because the forced-colours
 * outline exists only inside `outline-hidden`'s own definition, and a Tailwind upgrade has already
 * changed one of these utilities under an unchanged name; and the call sites, whose utilities sit in
 * a later cascade layer than these component classes and so beat them.
 */

// The path goes through a parameter: Vite rewrites a LITERAL `new URL('…', import.meta.url)` into an
// asset URL, which is not a file path.
const pathOf = (rel) => fileURLToPath(new URL(rel, import.meta.url));
const read = (rel) => readFileSync(pathOf(rel), 'utf8');

const CLASSES = ['.btn', '.btn-primary', '.btn-secondary'];
/** A `.btn*` class in a selector, and not a longer class that merely starts the same way. */
const BTN_SELECTOR = /\.btn(-primary|-secondary)?(?![\w-])/;
/** A `.btn*` class name in a class list. */
const BTN_CLASS = /^btn(-primary|-secondary)?$/;
/** Any Tailwind outline or ring utility, under any variant. */
const OUTLINE_OR_RING_UTILITY = /(^|:)(outline|ring)(-|$)/;

/** Skips the quoted string opening at `i`; returns the index of its closing quote. */
function skipQuoted(text, i) {
  for (let j = i + 1; j < text.length; j += 1) {
    if (text[j] === '\\') j += 1;
    else if (text[j] === text[i]) return j;
  }
  throw new Error(`unterminated string at ${i}`);
}

/** Skips the template literal opening at `i`, `${…}` included; returns the index of its closing backtick. */
function skipTemplate(text, i) {
  for (let j = i + 1; j < text.length; j += 1) {
    if (text[j] === '\\') j += 1;
    else if (text[j] === '`') return j;
    else if (text[j] === '$' && text[j + 1] === '{') j = skipBraces(text, j + 1);
  }
  throw new Error(`unterminated template literal at ${i}`);
}

/** Skips the `{…}` opening at `i`, strings and templates inside it included; returns the index of its `}`. */
function skipBraces(text, i) {
  let depth = 0;
  for (let j = i; j < text.length; j += 1) {
    const ch = text[j];
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) return j;
    } else if (ch === '"' || ch === '\'') j = skipQuoted(text, j);
    else if (ch === '`') j = skipTemplate(text, j);
  }
  throw new Error(`unbalanced braces at ${i}`);
}

/**
 * CSS as a tree of blocks — `{ prelude, declarations, children }` — so a rule is found by its WHOLE
 * prelude at any depth: inside `@layer components`, and inside the nesting Tailwind compiles to.
 * Comments must be stripped first; this file's comments carry both braces and class names.
 */
function parseCss(css) {
  const root = { prelude: '', declarations: [], children: [] };
  const stack = [root];
  let start = 0;
  for (let i = 0; i < css.length; i += 1) {
    const ch = css[i];
    if (ch === '"' || ch === '\'') {
      i = skipQuoted(css, i);
    } else if (ch === ';' || ch === '{' || ch === '}') {
      const text = css.slice(start, i).trim();
      if (ch === '{') {
        const block = { prelude: text, declarations: [], children: [] };
        stack.at(-1).children.push(block);
        stack.push(block);
      } else {
        if (text) stack.at(-1).declarations.push(text);
        if (ch === '}') stack.pop();
      }
      start = i + 1;
    }
  }
  if (stack.length !== 1) throw new Error(`${stack.length - 1} block(s) never closed`);
  return root;
}

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');
const allBlocks = (block) => block.children.flatMap((child) => [child, ...allBlocks(child)]);

function onlyBlock(tree, prelude) {
  const found = allBlocks(tree).filter((block) => block.prelude === prelude);
  expect(found, `exactly one block for ${prelude}`).toHaveLength(1);
  return found[0];
}

/** Every declaration in a block and its descendants, with the preludes of the blocks it sits in. */
const declarationsIn = (block, path = []) => [
  ...block.declarations.map((declaration) => ({ path, declaration })),
  ...block.children.flatMap((child) => declarationsIn(child, [...path, child.prelude])),
];

/** The utilities a class rule `@apply`s. */
function appliedUtilities(tree, selector) {
  const applies = onlyBlock(tree, selector).declarations.filter((d) => d.startsWith('@apply '));
  expect(applies, `${selector} has one @apply`).toHaveLength(1);
  return applies[0].slice('@apply '.length).trim().split(/\s+/);
}

/**
 * The value of every `className=` attribute in a source file: a quoted string, or a `{…}`
 * expression walked with the strings and template literals inside it — a ternary inside a template
 * is how `ConfirmDialog` picks `btn-primary`.
 */
function classNameValues(source) {
  const values = [];
  for (const match of source.matchAll(/\bclassName=/g)) {
    const start = match.index + match[0].length;
    if (source[start] === '"' || source[start] === '\'') {
      values.push({ form: 'string', text: source.slice(start + 1, skipQuoted(source, start)) });
    } else if (source[start] === '{') {
      values.push({ form: 'expression', text: source.slice(start + 1, skipBraces(source, start)) });
    }
  }
  return values;
}

describe('the shared button classes — their focus utilities as written', () => {
  let tree;
  beforeAll(() => {
    tree = parseCss(stripComments(read('../index.css')));
  });

  it.each(CLASSES)('%s keys no utility on focus:, which a mouse press satisfies', (selector) => {
    expect(appliedUtilities(tree, selector).filter((u) => /(^|:)focus:/.test(u))).toEqual([]);
  });

  it.each(CLASSES)('%s has focus-visible:outline-hidden as its only outline utility', (selector) => {
    // `outline-none` beside the ring is the forced-colours defect, and ANY other outline utility here
    // is a chance to undo `outline-hidden` — so the list is pinned whole.
    expect(appliedUtilities(tree, selector).filter((u) => /(^|:)outline(-|$)/.test(u)))
      .toEqual(['focus-visible:outline-hidden']);
  });

  it.each(CLASSES)('%s draws its ring on focus-visible, 2px wide at a 2px offset', (selector) => {
    // The band `outline-hidden`'s forced-colours outline occupies too (2px, at a 2px offset), which
    // is what lets index.css say the indicator lands where the ring would have.
    const ring = appliedUtilities(tree, selector).filter((u) => /(^|:)ring(-|$)/.test(u));
    expect(ring).toEqual(expect.arrayContaining(['focus-visible:ring-2', 'focus-visible:ring-offset-2']));
    expect(ring.filter((u) => !u.startsWith('focus-visible:'))).toEqual([]);
  });

  it.each(['.btn-primary', '.btn-secondary'])('%s rings in gold', (selector) => {
    // Moving `.btn-primary`'s colour from `focus:` to `focus-visible:` is the edit most easily done
    // by halves, and the ring would fall back to `currentColor`: gray-900, on a dark page.
    expect(appliedUtilities(tree, selector)).toContain('focus-visible:ring-plex-gold');
  });

  it('no other rule in index.css gives these classes an outline or a box-shadow', () => {
    const touching = allBlocks(tree)
      .filter((block) => BTN_SELECTOR.test(block.prelude) && !CLASSES.includes(block.prelude))
      .flatMap((block) => declarationsIn(block, [block.prelude]))
      .filter(({ declaration }) => /^(outline|box-shadow)/.test(declaration)
        || (declaration.startsWith('@apply ') && declaration.split(/\s+/).some((u) => OUTLINE_OR_RING_UTILITY.test(u))));
    expect(touching).toEqual([]);
  });
});

describe('the shared button classes — what Tailwind compiles them to', () => {
  let compiled;
  beforeAll(async () => {
    // Tailwind's own compiler over the real stylesheet, with `@import "tailwindcss"` resolved to the
    // installed package: the build's answer, before Lightning CSS flattens the nesting.
    const tailwindCss = createRequire(pathOf('../../package.json')).resolve('tailwindcss/index.css');
    const loadStylesheet = async (id, base) => {
      const path = id === 'tailwindcss' ? tailwindCss : resolve(base, id);
      return { path, base: dirname(path), content: readFileSync(path, 'utf8') };
    };
    const compiler = await compile(read('../index.css'), { base: pathOf('..'), loadStylesheet });
    compiled = parseCss(stripComments(compiler.build([])));
  });

  it.each(CLASSES)('%s outlines only in forced colours: 2px solid transparent, at a 2px offset, on :focus-visible', (selector) => {
    // Outside the mode, `outline-style: none`; inside it, an outline the mode repaints in a system
    // colour. And no other outline declaration anywhere in the rule, so nothing can win over it.
    const outlines = declarationsIn(onlyBlock(compiled, selector))
      .filter(({ declaration }) => /^outline/.test(declaration))
      .map(({ path, declaration }) => [...path, declaration].join(' → '));
    expect(outlines).toEqual([
      '&:focus-visible → outline-style: none',
      '&:focus-visible → @media (forced-colors: active) → outline: 2px solid transparent',
      '&:focus-visible → @media (forced-colors: active) → outline-offset: 2px',
    ]);
  });
});

describe('the shared button classes — their call sites', () => {
  let sources;
  let sites;
  beforeAll(() => {
    const srcRoot = pathOf('../');
    sources = readdirSync(srcRoot, { recursive: true })
      .filter((file) => /\.jsx?$/.test(file) && !file.split(/[\\/]/).includes('test'))
      .map((file) => ({ file, source: readFileSync(join(srcRoot, file), 'utf8') }));
    sites = sources.flatMap(({ file, source }) => classNameValues(source)
      .map((value) => ({ file, ...value, classes: value.text.split(/[\s'"`{}]+/) }))
      .filter(({ classes }) => classes.some((c) => BTN_CLASS.test(c))));
  });

  it('finds every call site a line-by-line search finds, in both the string and the expression form', () => {
    // A walk that found nothing would pass the test below vacuously. Every line naming a `.btn*`
    // class after its own `className=` is one site the walk must also find; the walk also finds the
    // ones that break across lines, so it may find more, never fewer.
    const sameLine = sources.flatMap(({ source }) => source.split('\n'))
      .filter((line) => /\bclassName=.*(?<![\w-])btn(-primary|-secondary)?(?![\w-])/.test(line));
    expect(sameLine.length).toBeGreaterThan(0);
    expect(sites.length).toBeGreaterThanOrEqual(sameLine.length);
    expect(sites.map((site) => site.form)).toEqual(expect.arrayContaining(['string', 'expression']));
  });

  it('pairs no .btn* class with an outline or ring utility, which would beat the class from a later layer', () => {
    // Copying the neighbouring input's `focus:outline-none focus:ring-1` onto a button would take away
    // its forced-colours outline and put back the mouse-press ring, and the class rules could not see
    // it: utilities are a later cascade layer than components.
    const offending = sites
      .map(({ file, classes }) => ({ file, utilities: classes.filter((c) => OUTLINE_OR_RING_UTILITY.test(c)) }))
      .filter(({ utilities }) => utilities.length > 0);
    expect(offending).toEqual([]);
  });
});
