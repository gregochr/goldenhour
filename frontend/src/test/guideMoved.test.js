import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The Planning a shoot guide was split into planning / this-week / further-ahead
 * (docs/engineering/landing-guide-plan.md, G1). `landing/guide/moved.js` holds the one map of the ids that
 * left `planning.html`, and redirects an old `planning.html#id` to the page that holds it now.
 */
const GUIDE_DIR = resolve(process.cwd(), '../landing/guide');
const source = (page) => readFileSync(resolve(GUIDE_DIR, `${page}.html`), 'utf8');
const hasId = (page, id) => new RegExp(`\\sid="${id}"`).test(source(page));
const sectionIds = (page) => [...source(page).matchAll(/<section class="g-sec" id="([^"]+)"/g)].map((m) => m[1]);

/** Every `<section class="g-sec">` id `planning.html` had before the split. */
const ORIGINAL_SECTIONS = [
  'screen', 'top', 'lens', 'this-week', 'your-places', 'days', 'windows', 'open', 'bestbet', 'away',
  'further-ahead', 'topics', 'new', 'filters', 'entries', 'tides', 'actions', 'watch-a-date', 'find', 'warnings', 'tips',
];
const PAGES = ['planning', 'this-week', 'further-ahead'];

const { MOVED, resolveMoved } = (() => {
  const module = { exports: {} };
  new Function('module', readFileSync(resolve(GUIDE_DIR, 'moved.js'), 'utf8'))(module);
  return module.exports;
})();

describe('the moved-id map', () => {
  it.each(Object.entries(MOVED))('%s is on %s.html', (id, page) => {
    expect(PAGES).toContain(page);
    expect(page).not.toBe('planning');
    expect(hasId(page, id), `${page}.html has no id="${id}"`).toBe(true);
  });

  it.each(Object.keys(MOVED))('%s is no longer on planning.html', (id) => {
    expect(hasId('planning', id), `planning.html still has id="${id}"`).toBe(false);
  });
});

describe('the sections of the old planning.html', () => {
  it.each(ORIGINAL_SECTIONS)('%s is a section on exactly one of the three pages', (id) => {
    const holders = PAGES.filter((page) => sectionIds(page).includes(id));
    expect(holders).toHaveLength(1);
  });

  it('keeps the planning overview to the sections that stay, plus the new walk-through', () => {
    expect(sectionIds('planning')).toEqual(['screen', 'how', 'top', 'lens', 'away', 'find', 'warnings', 'tips']);
  });

  it('sends every moved section id (and only those) through the map', () => {
    const left = ORIGINAL_SECTIONS.filter((id) => !sectionIds('planning').includes(id));
    left.forEach((id) => expect(MOVED[id], id).toBeDefined());
    Object.keys(MOVED).filter((id) => !id.startsWith('shot-')).forEach((id) => expect(left).toContain(id));
  });

  it('puts every pinned shot figure on the page the map names, once', () => {
    const shots = PAGES.flatMap((p) => [...source(p).matchAll(/<figure[^>]*\sid="(shot-[^"]+)"/g)].map((m) => m[1]));
    expect(new Set(shots).size).toBe(shots.length);
    Object.entries(MOVED).filter(([id]) => id.startsWith('shot-')).forEach(([id, page]) => {
      expect(source(page)).toMatch(new RegExp(`<figure[^>]*\\sid="${id}"`));
    });
  });
});

describe('resolveMoved', () => {
  it('sends a moved id to its page and keeps the embed flag', () => {
    expect(resolveMoved('planning.html', '?embed=1', '#bestbet')).toBe('this-week.html?embed=1#bestbet');
    expect(resolveMoved('planning.html', '', '#watch-a-date')).toBe('further-ahead.html#watch-a-date');
    expect(resolveMoved('planning.html', '?embed=1&x=2', '#tides')).toBe('further-ahead.html?embed=1&x=2#tides');
  });

  it('leaves planning ids, no id and other pages alone', () => {
    expect(resolveMoved('planning.html', '', '#lens')).toBeNull();
    expect(resolveMoved('planning.html', '?embed=1', '')).toBeNull();
    expect(resolveMoved('planning.html', '', '#not-an-id')).toBeNull();
    expect(resolveMoved('planning.html', '', '#constructor')).toBeNull();
    expect(resolveMoved('this-week.html', '', '#bestbet')).toBeNull();
    expect(resolveMoved('map.html', '', '#tide')).toBeNull();
  });

  it('chains the two retired stubs to the page that now holds the id', () => {
    expect(resolveMoved('coming-up.html', '?embed=1', '')).toBe('further-ahead.html?embed=1#further-ahead');
    expect(resolveMoved('coming-up.html', '', '#list')).toBe('further-ahead.html#further-ahead');
    expect(resolveMoved('coming-up.html', '', '#watchlist')).toBe('this-week.html#your-places');
    expect(resolveMoved('at-a-glance.html', '', '#this-week')).toBe('this-week.html#this-week');
    expect(resolveMoved('at-a-glance.html', '', '#top')).toBe('planning.html#top');
    expect(resolveMoved('at-a-glance.html', '?embed=1', '')).toBe('planning.html?embed=1');
  });

  it('every id the stubs map lands on a page that has it', () => {
    const targets = ['#', '#list', '#watchlist'].concat(ORIGINAL_SECTIONS.map((id) => `#${id}`));
    ['coming-up.html', 'at-a-glance.html'].forEach((stub) => targets.forEach((hash) => {
      const to = resolveMoved(stub, '', hash === '#' ? '' : hash);
      const [page, id] = to.split('#');
      expect(PAGES).toContain(page.replace(/\.html$/, ''));
      if (id && !(stub === 'at-a-glance.html' && ['#list', '#watchlist'].includes(hash))) {
        expect(hasId(page.replace(/\.html$/, ''), id), `${stub}${hash} -> ${to}`).toBe(true);
      }
    }));
  });
});

describe('the page that loads moved.js', () => {
  it('loads it early in the head, and keeps the stubs on a meta refresh to a page that exists', () => {
    const head = source('planning').slice(0, source('planning').indexOf('</head>'));
    expect(head).toContain('<script src="moved.js"></script>');
    expect(head.indexOf('moved.js')).toBeLessThan(head.indexOf('guide.js'));
    expect(head).not.toContain('http-equiv="refresh"');
    expect(source('this-week')).not.toContain('moved.js');
  });
});
