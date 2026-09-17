import {
  describe, it, expect, beforeAll,
} from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compile } from 'tailwindcss';

/**
 * The focus outline of every text input, `<select>` and `<textarea>` that pairs a focus ring
 * (`ring-*`, a `box-shadow`) or a focus-time border-colour change with `outline-none` — 28 call
 * sites across the auth pages, the admin management views, `SortableHeader`'s filter input, the
 * Plan-overlay drive-time `<select>` and the aurora simulation form.
 *
 * <p>Same defect as the shared `.btn*` classes (see `docs/engineering/frontend-test-standards.md`'s
 * neighbour, and `changelog.d/` for the button fix): forced-colours mode (Windows High Contrast)
 * removes every `box-shadow`, so the ring is invisible there, and `focus:outline-none` — Tailwind
 * 4's name for `outline-style: none`, since the v3→v4 migration (`3e45d18b`, 2026-03-01) kept the
 * v3 name for what v4 calls `outline-hidden` — leaves nothing standing in for it. A focus-time
 * border-colour change is lost too: forced colours repaints border colours regardless of what the
 * author specified. Measured on the built sign-in page (headless Chromium 151 / Firefox 153,
 * `forced-colors: active`, pixel-diffing a 14px frame round the field, blurred vs focused):
 * Chromium already repaints the field's own 1px border on focus (a native, CSS-independent
 * behaviour — 806 of 24,948 pixels), so it degrades rather than vanishes; Firefox changes not one
 * pixel (0/24,948) and is left with only the caret. `outline-hidden` fixes both: Chromium rises to
 * 2,422 (the native repaint plus a real outline), Firefox from 0 to 1,617. Normal (non-forced)
 * rendering is pixel-identical before and after (0/24,948 in both engines) — `outline-hidden` is
 * `outline-style: none` outside forced colours, same as `outline-none`.
 *
 * <p><b>The variant stays `focus:`, not `focus-visible:`.</b> Unlike the shared buttons — which
 * needed `focus-visible:` because Chromium and Firefox focus a *button* on a mouse press, drawing a
 * ring no pointer user asked for — these fields already ring on `focus:`, and that is unchanged
 * existing behaviour, not a defect this task fixes. Measured with a real Tab-driven keyboard focus
 * and a real mouse click against Tailwind's own compiled output (`compile().build([...])`, the
 * project's real theme, no full app scan needed): a `<select>` does not universally get
 * `:focus-visible` on a mouse click — Firefox's does not (`:focus-visible` false there), Chromium's
 * does. Scoping the new outline to `focus-visible:` would have left Firefox's mouse-clicked
 * `<select>` exactly as broken as before (0/15,624 changed) while keyboard focus worked, a defect
 * that would have shipped invisibly since the sign-in page (the only page this project measures
 * live) has no `<select>`. `focus:outline-hidden` shows it either way (1,085/15,624 changed, both
 * origins, both engines) and changes nothing else, since `focus:ring-*`/`focus:border-*` already
 * fire on any focus.
 *
 * <p>jsdom renders no CSS, so the rules are pinned as text: every call site that pairs an outline
 * utility with a focus ring or a focus border-colour change uses `outline-hidden`, never
 * `outline-none`, and only under `focus:`; and what Tailwind's own compiler makes of the bare
 * utility (not `@apply`'d — these are plain `className` utilities, not a shared component class),
 * because the forced-colours outline lives entirely inside `outline-hidden`'s own definition and a
 * Tailwind upgrade has already changed one of these utilities under an unchanged name.
 *
 * <p>Excluded, per CLAUDE.md: the three dialog roots (`shared/Modal.jsx`, `MapOverlay.jsx`,
 * `BottomSheet.jsx`) whose `focus:outline-none` sits on a container `useDialogFocus` focuses
 * programmatically, where no indicator is wanted. ⚠️ These are not "the three `useDialogFocus`
 * consumers" — `useDialogFocus.js`'s own docblock names a fourth, `RegionsJump.jsx`, which carries
 * no `outline` utility at all (confirmed: `grep outline` on that file returns nothing), so it has
 * nothing to exempt and nothing this scan would flag. Adding one there later needs the same
 * exemption these three already have, not a new category.
 */

const pathOf = (rel) => fileURLToPath(new URL(rel, import.meta.url));
const read = (rel) => readFileSync(pathOf(rel), 'utf8');

// Relative to src/ — the containers useDialogFocus focuses programmatically; no indicator wanted.
// The three that currently HAVE an outline utility to exempt — useDialogFocus's fourth consumer,
// RegionsJump.jsx, has none, so it is not a fourth exemption, just not a call site at all (yet).
const EXEMPT_FILES = [
  'components/shared/Modal.jsx',
  'components/MapOverlay.jsx',
  'components/BottomSheet.jsx',
];

/**
 * An `outline-none`/`outline-hidden` utility under `focus:` or `focus-visible:` — deliberately NOT
 * a bare, unprefixed `outline-none` (`PlanErrorBoundary`'s programmatically-focused heading has one
 * with no ring or border-colour utility beside it at all: a different, unconditional "never show an
 * outline" declaration, not this task's "no outline standing in for the ring on focus" shape, and
 * outside the `focus:outline-none` grep this task named).
 */
const OUTLINE_UTILITY = /^focus(-visible)?:outline-(none|hidden)$/;
/** A focus ring or focus-time border-colour utility — the shape all 28 sites share. */
const RING_OR_FOCUS_BORDER = /^focus(-visible)?:(ring-|border-)/;

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
 * `source` with every `//` line comment and `/* … *\/` block comment blanked to spaces — length and
 * every offset unchanged, so a later `match.index`-based slice still lines up — while a quoted
 * string's or a template literal's own text is copied through untouched: a `//`/`/*` inside one is
 * data, never a comment. Only a template's `${…}` interpolations are scanned as code (recursively,
 * so a comment nested two templates deep is still found); the string skip reuses `skipQuoted` as-is.
 *
 * <p>Must run before `classNameAttributeValues` and `classVariableDefinitions` look for their
 * anchors, or two things break — both found by adversarial review, neither hypothetical. A comment
 * merely narrating old `className="...focus:outline-none..."` example syntax reads as a real call
 * site (the anchor regexes have no idea a comment is not code). And the sharper failure: a comment's
 * own apostrophe, nested inside a template literal's `${…}` — the exact shape at
 * `ModelTestView.jsx:654`'s row-selection ternary, which already carries a `//` comment there — is
 * misread by `skipQuoted` as *opening* a string, which then hunts for the next quote, swallows past
 * the real one, and desyncs the whole walk (`unterminated string`), failing every test in this
 * file's first `describe` at once. Reproduced by changing four characters of that real file's prose
 * ("gave no sign" → "didn't give a sign"); the fixture tests below pin the same shape directly.
 */
function blankComments(source) {
  const chars = [...source];
  const blank = (from, to) => { for (let k = from; k < to; k += 1) if (chars[k] !== '\n') chars[k] = ' '; };
  // 'code': a `//`/`/*` here is a real comment, a backtick opens a template. 'template': we are
  // inside a template literal's own text, where only `${` (push 'code') and the closing backtick
  // (pop) matter. Brace-depth is tracked only while nested inside a `${…}` (stack.length > 1) — that
  // is the one place a `}` must pop back to 'template' rather than just being ordinary code.
  const stack = ['code'];
  let i = 0;
  while (i < source.length) {
    const ch = source[i];
    if (stack.at(-1) === 'template') {
      if (ch === '\\') { i += 2; continue; }
      if (ch === '`') { stack.pop(); i += 1; continue; }
      if (ch === '$' && source[i + 1] === '{') { stack.push('code'); i += 2; continue; }
      i += 1;
      continue;
    }
    if (ch === '"' || ch === '\'') { i = skipQuoted(source, i) + 1; continue; }
    if (ch === '`') { stack.push('template'); i += 1; continue; }
    if (stack.length > 1 && ch === '{') { stack.push('code'); i += 1; continue; }
    if (stack.length > 1 && ch === '}') { stack.pop(); i += 1; continue; }
    if (ch === '/' && source[i + 1] === '/') {
      const start = i;
      while (i < source.length && source[i] !== '\n') i += 1;
      blank(start, i);
      continue;
    }
    if (ch === '/' && source[i + 1] === '*') {
      const start = i;
      i += 2;
      while (i < source.length && !(source[i] === '*' && source[i + 1] === '/')) i += 1;
      i = Math.min(i + 2, source.length);
      blank(start, i);
      continue;
    }
    i += 1;
  }
  return chars.join('');
}

/**
 * CSS as a tree of blocks — `{ prelude, declarations, children }` — so a rule is found by its WHOLE
 * prelude at any depth, including the `@media` nesting Tailwind compiles a variant's forced-colours
 * branch into. Comments must be stripped first.
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

/**
 * The value of every `className=` attribute in a source file: a quoted string, or a `{…}`
 * expression walked with the strings and template literals inside it — a ternary inside a template
 * is how e.g. `RegisterPage` picks a red error border. `blankComments` runs first, so a comment
 * cannot fabricate an anchor or crash the walk (see its own docstring).
 */
function classNameAttributeValues(rawSource) {
  const source = blankComments(rawSource);
  const values = [];
  for (const match of source.matchAll(/\bclassName=/g)) {
    const start = match.index + match[0].length;
    if (source[start] === '"' || source[start] === '\'') {
      values.push(source.slice(start + 1, skipQuoted(source, start)));
    } else if (source[start] === '{') {
      values.push(source.slice(start + 1, skipBraces(source, start)));
    }
  }
  return values;
}

/**
 * The value of every `const someClass = '…'`/`` `…` `` class-list definition in a source file —
 * six of the 28 sites (`UserManagementView`, `RegionManagementView`, `LocationManagementView`) build
 * their class list once, as `inputClass`/`selectClass`/`inlineInputClass`/`inlineSelectClass`, and
 * apply it at several `className={inputClass}` call sites, where the JSX attribute itself carries
 * only the bare identifier, never the class text — `classNameAttributeValues` alone would miss all
 * six. Anchored on `const <name>Class = `, the naming convention every such definition in this
 * codebase already follows, rather than walking every quote in the file: a bare quote-walk would
 * also open on an apostrophe inside ordinary JSX text or a comment and misread the rest of the file.
 * `blankComments` runs first for the same reason `classNameAttributeValues` needs it.
 */
function classVariableDefinitions(rawSource) {
  const source = blankComments(rawSource);
  const values = [];
  for (const match of source.matchAll(/\bconst\s+\w*[Cc]lass\s*=\s*/g)) {
    const start = match.index + match[0].length;
    if (source[start] === '"' || source[start] === '\'') {
      values.push(source.slice(start + 1, skipQuoted(source, start)));
    } else if (source[start] === '`') {
      values.push(source.slice(start + 1, skipTemplate(source, start)));
    }
  }
  return values;
}

const classListsIn = (source) => [...classNameAttributeValues(source), ...classVariableDefinitions(source)];

describe('blankComments — a comment cannot fabricate a call site or crash the walk', () => {
  // The real shape at ModelTestView.jsx:654-665: a `//` comment sits between a ternary's operator
  // and its first operand, inside a template literal's `${…}` interpolation. Adversarial review
  // reproduced a crash here by changing four characters of that file's actual prose — this pins the
  // same shape directly, as a standalone fixture, rather than depending on that file's wording never
  // changing again.
  const NESTED_COMMENT_TERNARY = [
    'function Row({ selected, id }) {',
    '  return (',
    '    <div',
    '      className={`w-full text-left px-3 py-2 rounded text-sm transition-colors ${',
    '        selected === id',
    '          // the picker didn\'t give a sign of what was selected',
    "          ? 'bg-plex-gold/20 border border-plex-gold text-plex-text'",
    "          : 'hover:bg-plex-surface-light text-plex-text-secondary'",
    '      }`}',
    '    />',
    '  );',
    '}',
  ].join('\n');

  it('does not throw on a // comment, apostrophe included, nested inside a template\'s ${…}', () => {
    expect(() => classNameAttributeValues(NESTED_COMMENT_TERNARY)).not.toThrow();
  });

  it('still finds the real ternary strings either side of that comment, and nothing from the comment itself', () => {
    const [value] = classNameAttributeValues(NESTED_COMMENT_TERNARY);
    expect(value).toContain('bg-plex-gold/20');
    expect(value).toContain('hover:bg-plex-surface-light');
    expect(value).not.toContain('picker');
  });

  it('does not let a comment merely narrating old className syntax read as a real call site', () => {
    const source = [
      'function Old() {',
      '  // Before this fix: <input className="w-full focus:outline-none focus:ring-1 focus:ring-plex-gold" />',
      '  return <input className="w-full focus:outline-hidden focus:ring-1 focus:ring-plex-gold" />;',
      '}',
    ].join('\n');
    const values = classNameAttributeValues(source);
    expect(values).toEqual(['w-full focus:outline-hidden focus:ring-1 focus:ring-plex-gold']);
  });

  it('does not let a /* */ comment narrating an old const class definition read as a real one either', () => {
    const source = [
      'function Old() {',
      "  /* const inputClass = 'focus:outline-none focus:ring-1'; */",
      "  const inputClass = 'focus:outline-hidden focus:ring-1';",
      '  return <input className={inputClass} />;',
      '}',
    ].join('\n');
    expect(classVariableDefinitions(source)).toEqual(['focus:outline-hidden focus:ring-1']);
  });

  it('leaves ordinary code, strings and templates with no comment completely unchanged, and blanks a trailing comment to spaces of the same length', () => {
    const code = 'const x = `a ${1 + 2} b`; const y = "it\'s fine";';
    const comment = ' // trailing note';
    const source = `${code}${comment}\nconst z = 1;`;
    const blanked = blankComments(source);
    expect(blanked.length).toBe(source.length);
    expect(blanked).toBe(`${code}${' '.repeat(comment.length)}\nconst z = 1;`);
    // The code itself — including the apostrophe inside a real string, and the template's own
    // interpolation — is byte-for-byte untouched.
    expect(blanked.startsWith(code)).toBe(true);
  });
});

describe('form-field focus outlines — their call sites', () => {
  let files;
  let sites;

  beforeAll(() => {
    const srcRoot = pathOf('../');
    files = readdirSync(srcRoot, { recursive: true })
      .filter((file) => /\.jsx?$/.test(file) && !file.split(/[\\/]/).includes('test'));
    sites = files.flatMap((file) => classListsIn(read(`../${file}`))
      .map((text) => ({ file, text, classes: text.split(/[\s'"`{}]+/).filter(Boolean) }))
      .filter(({ classes }) => classes.some((c) => OUTLINE_UTILITY.test(c))));
  });

  it('finds every focus:outline-(none|hidden) a line-by-line search finds', () => {
    // A walk that found nothing would pass every test below vacuously. `blankComments` first, same
    // as the two extractors above, so ModelTestView.jsx:640 — which names `focus:outline-none` only
    // in prose, inside a comment explaining why a *different* fix was needed there — is correctly
    // excluded regardless of where on its line the comment starts, not just a leading `// `.
    const sameLine = files.flatMap((file) => blankComments(read(`../${file}`)).split('\n'))
      .filter((line) => /\bfocus(-visible)?:outline-(none|hidden)\b/.test(line));
    expect(sameLine.length).toBeGreaterThan(0);
    expect(sites.length).toBeGreaterThanOrEqual(sameLine.length);
  });

  it('leaves focus:outline-none only on the three dialog roots useDialogFocus focuses programmatically', () => {
    const usingOutlineNone = sites
      .filter(({ classes }) => classes.some((c) => /^focus(-visible)?:outline-none$/.test(c)))
      .map(({ file }) => file);
    expect(new Set(usingOutlineNone)).toEqual(new Set(EXEMPT_FILES));
  });

  it('the three exempt dialog roots are still there and still use focus:outline-none', () => {
    // If one of these ever moves to outline-hidden (or is deleted), the exemption list above is
    // stale and this names exactly which of the two changed.
    for (const file of EXEMPT_FILES) {
      const classes = classListsIn(read(`../${file}`)).flatMap((text) => text.split(/[\s'"`{}]+/));
      expect(classes, file).toContain('focus:outline-none');
    }
  });

  it('every site pairing a focus ring or focus border-colour with an outline uses outline-hidden, on focus: not focus-visible:', () => {
    const formFieldSites = sites.filter(({ file }) => !EXEMPT_FILES.includes(file))
      .filter(({ classes }) => classes.some((c) => RING_OR_FOCUS_BORDER.test(c)));

    expect(formFieldSites.length).toBeGreaterThan(0);

    const offending = formFieldSites
      .map(({ file, classes }) => ({
        file,
        outlineUtilities: classes.filter((c) => OUTLINE_UTILITY.test(c)),
      }))
      .filter(({ outlineUtilities }) => !(outlineUtilities.length === 1 && outlineUtilities[0] === 'focus:outline-hidden'));
    expect(offending).toEqual([]);
  });

  it('reaches every component this task named', () => {
    // Not a count (a field added or removed shifts it) — a presence check per file, so a rebase
    // that silently drops one of the eleven edited files fails here by name.
    const expectedFiles = [
      'components/AuroraSimulateModal.jsx',
      'components/ChangePasswordPage.jsx',
      'components/LocationManagementView.jsx',
      'components/LoginPage.jsx',
      'components/MapView.jsx',
      'components/OutcomeModal.jsx',
      'components/RegionManagementView.jsx',
      'components/RegisterPage.jsx',
      'components/UserManagementView.jsx',
      'components/UserSettingsModal.jsx',
      'components/shared/SortableHeader.jsx',
    ];
    const filesWithOutlineHidden = new Set(
      sites.filter(({ classes }) => classes.includes('focus:outline-hidden')).map(({ file }) => file),
    );
    for (const file of expectedFiles) {
      expect(filesWithOutlineHidden, file).toContain(file);
    }
  });
});

describe('PlanErrorBoundary.jsx — the one deliberately un-fixed outline-none', () => {
  // Adversarial review found a real landmine here: `outline-hidden`'s forced-colours branch is
  // scoped to `:focus` only by its `focus:`/`focus-visible:` prefix, so swapping this file's BARE
  // `outline-none` to bare `outline-hidden` — the mechanical edit this PR made everywhere else —
  // would draw a PERMANENT forced-colours box around the heading, not a focus-only one. This pins
  // that the file stays bare (the known-good state) and never silently gains only half the fix.
  it('never carries outline-hidden without a focus:/focus-visible: prefix', () => {
    const classes = classNameAttributeValues(read('../components/PlanErrorBoundary.jsx'))
      .flatMap((text) => text.split(/\s+/).filter(Boolean));
    const bareOutlineHidden = classes.filter((c) => c === 'outline-hidden');
    expect(bareOutlineHidden).toEqual([]);
  });

  it('still has the bare outline-none this comment is about', () => {
    const classes = classNameAttributeValues(read('../components/PlanErrorBoundary.jsx'))
      .flatMap((text) => text.split(/\s+/).filter(Boolean));
    expect(classes).toContain('outline-none');
  });
});

describe('form-field focus outlines — what Tailwind compiles the bare utility to', () => {
  let compiled;

  beforeAll(async () => {
    // These are plain className utilities, never @apply'd into a component class, so the check
    // asks Tailwind's own compiler for the bare utility against the project's real theme —
    // `compiler.build([...])` generates on-demand utilities the way the real build's content scan
    // would, without needing a full source scan here.
    const tailwindCss = createRequire(pathOf('../../package.json')).resolve('tailwindcss/index.css');
    const loadStylesheet = async (id, base) => {
      const path = id === 'tailwindcss' ? tailwindCss : resolve(base, id);
      return { path, base: dirname(path), content: readFileSync(path, 'utf8') };
    };
    const compiler = await compile(read('../index.css'), { base: pathOf('..'), loadStylesheet });
    // Candidates generated on demand, the way the real build's content scan would from a
    // `className`, without needing a full source scan here — a plain utility, never `@apply`'d.
    compiled = parseCss(stripComments(compiler.build(['focus:outline-hidden', 'focus-visible:outline-hidden'])));
  });

  it.each(['.focus\\:outline-hidden:focus', '.focus-visible\\:outline-hidden:focus-visible'])(
    '%s is outline-style: none normally, and a transparent 2px outline at a 2px offset under forced-colors: active',
    (selector) => {
      const outlines = declarationsIn(onlyBlock(compiled, selector))
        .filter(({ declaration }) => /^outline/.test(declaration))
        .map(({ path, declaration }) => [...path, declaration].join(' → '));
      expect(outlines).toEqual([
        'outline-style: none',
        '@media (forced-colors: active) → outline: 2px solid transparent',
        '@media (forced-colors: active) → outline-offset: 2px',
      ]);
    },
  );
});
