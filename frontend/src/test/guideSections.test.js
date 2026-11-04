import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  GUIDE_PAGES, GUIDE_SECTIONS, TAB_GUIDE, guideUrl, pageIdFromMessage, cleanGuideTitle,
} from '../utils/guide.js';

/**
 * Pins the app to the guide pages it opens (docs/engineering/user-guide-plan.md, G2). The pages live in
 * `landing/guide/`, outside `frontend/`, and are read from disk the way `landingNginxConf.test.js` reads
 * `../landing/nginx.conf`. A guide edit that renames or removes an id the app opens fails the PR that
 * makes it, because CI runs the frontend suite on a `landing/guide/` change.
 */
const GUIDE_DIR = resolve(process.cwd(), '../landing/guide');
const files = readdirSync(GUIDE_DIR).filter((f) => f.endsWith('.html'));
const pageSource = (page) => readFileSync(resolve(GUIDE_DIR, `${page}.html`), 'utf8');
const hasId = (page, id) => new RegExp(`\\sid="${id}"`).test(pageSource(page));

describe('GUIDE_PAGES', () => {
  it('names exactly the pages that exist in landing/guide', () => {
    expect(files.map((f) => f.replace(/\.html$/, '')).sort()).toEqual(Object.keys(GUIDE_PAGES).sort());
  });

  it('is frozen, so a door cannot invent a page', () => {
    expect(Object.isFrozen(GUIDE_PAGES)).toBe(true);
    expect(Object.isFrozen(GUIDE_SECTIONS)).toBe(true);
  });
});

describe('GUIDE_SECTIONS', () => {
  const entries = Object.entries(GUIDE_SECTIONS);

  it.each(entries)('%s: the page exists and carries the section id', (_name, { page, section }) => {
    expect(Object.keys(GUIDE_PAGES)).toContain(page);
    expect(hasId(page, section), `${page}.html has no id="${section}"`).toBe(true);
  });

  it('is the 18 sections the plan\'s contract lists, no more and no fewer', () => {
    expect(entries.map(([, { page, section }]) => `${page}#${section}`).sort()).toEqual([
      'ask-photocast#typed', 'at-a-glance#bestbet', 'at-a-glance#lens', 'coming-up#new', 'coming-up#tides',
      'coming-up#topics', 'map#filters', 'map#hot-topics', 'map#tide', 'map#window', 'night-sky#alerts',
      'night-sky#aurora-map', 'reading-the-forecast#colour', 'reading-the-forecast#fiery',
      'reading-the-forecast#fixed', 'settings#drive', 'settings#radius', 'settings#sub-regions',
    ]);
  });
});

describe('TAB_GUIDE', () => {
  it('maps the three guided tabs to existing pages and has no Operations entry', () => {
    expect(TAB_GUIDE).toEqual({ plan: 'at-a-glance', 'coming-up': 'coming-up', map: 'map' });
    Object.values(TAB_GUIDE).forEach((page) => expect(Object.keys(GUIDE_PAGES)).toContain(page));
    expect(TAB_GUIDE.operations).toBeUndefined();
  });
});

describe('guideUrl', () => {
  it('builds the embedded and plain forms with an optional section', () => {
    expect(guideUrl('map', 'filters', { embed: true })).toBe('https://photocast.online/guide/map.html?embed=1#filters');
    expect(guideUrl('map', '#filters')).toBe('https://photocast.online/guide/map.html#filters');
    expect(guideUrl('settings')).toBe('https://photocast.online/guide/settings.html');
  });

  it('falls back to the hub for a page it does not know', () => {
    expect(guideUrl('not-a-page')).toBe('https://photocast.online/guide/index.html');
    expect(guideUrl('__proto__')).toBe('https://photocast.online/guide/index.html');
  });
});

describe('pageIdFromMessage', () => {
  it('reads the last path segment guide.js posts', () => {
    expect(pageIdFromMessage('map.html')).toBe('map');
    expect(pageIdFromMessage('')).toBe('index');
    expect(pageIdFromMessage('index.html')).toBe('index');
  });

  it('rejects anything that is not a guide page', () => {
    expect(pageIdFromMessage('evil.html')).toBeNull();
    expect(pageIdFromMessage('constructor')).toBeNull();
    expect(pageIdFromMessage(42)).toBeNull();
  });
});

describe('cleanGuideTitle', () => {
  it('trims the trailing full stop and caps the length', () => {
    expect(cleanGuideTitle('Map.')).toBe('Map');
    expect(cleanGuideTitle(`${'x'.repeat(200)}.`)).toHaveLength(80);
  });

  it('gives nothing for a non-string or an empty one', () => {
    expect(cleanGuideTitle({})).toBeNull();
    expect(cleanGuideTitle('  .  ')).toBeNull();
  });
});
