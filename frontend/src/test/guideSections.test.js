import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  GUIDE_PAGES, GUIDE_SECTIONS, SCREEN_GUIDE, guideUrl, pageIdFromMessage, resolveGuideOrigin,
} from '../utils/guide.js';

/**
 * Pins the app to the guide pages it opens (docs/engineering/user-guide-plan.md, G2). The pages live in
 * `landing/guide/`, outside `frontend/`, and are read from disk the way `landingNginxConf.test.js` reads
 * `../landing/nginx.conf`. A guide edit that renames or removes an id the app opens fails the PR that
 * makes it, because CI runs the frontend suite on a `landing/guide/` change.
 */
const GUIDE_DIR = resolve(process.cwd(), '../landing/guide');
const allFiles = readdirSync(GUIDE_DIR).filter((f) => f.endsWith('.html'));
const pageSource = (page) => readFileSync(resolve(GUIDE_DIR, `${page}.html`), 'utf8');
/** `at-a-glance.html` and `coming-up.html` stay as redirect stubs (one-screen-plan.md O4a): links in the wild still land. */
const isStub = (f) => /http-equiv="refresh"/.test(pageSource(f.replace(/\.html$/, '')));
const files = allFiles.filter((f) => !isStub(f));
const stubs = allFiles.filter(isStub);
const hasId = (page, id) => new RegExp(`\\sid="${id}"`).test(pageSource(page));

describe('GUIDE_PAGES', () => {
  it('names exactly the pages that exist in landing/guide', () => {
    expect(files.map((f) => f.replace(/\.html$/, '')).sort()).toEqual(Object.keys(GUIDE_PAGES).sort());
  });

  it('keeps exactly the two retired pages as redirect stubs, and names neither as a guide page', () => {
    expect(stubs.sort()).toEqual(['at-a-glance.html', 'coming-up.html']);
    expect(Object.keys(GUIDE_PAGES)).not.toContain('at-a-glance');
    expect(Object.keys(GUIDE_PAGES)).not.toContain('coming-up');
    expect(pageSource('at-a-glance')).toContain('content="3;url=planning.html"');
    expect(pageSource('coming-up')).toContain('content="3;url=planning.html#further-ahead"');
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

  it('is the 20 sections the plan\'s contract lists, no more and no fewer', () => {
    expect(entries.map(([, { page, section }]) => `${page}#${section}`).sort()).toEqual([
      'ask-photocast#typed', 'map#filters', 'map#hot-topics', 'map#tide', 'map#window', 'night-sky#alerts',
      'night-sky#aurora-map', 'planning#bestbet', 'planning#lens', 'planning#new', 'planning#tides',
      'planning#topics', 'reading-the-forecast#colour', 'reading-the-forecast#fiery',
      'reading-the-forecast#fixed', 'settings#drive', 'settings#radius', 'settings#sub-regions',
      'weather-warnings#fresh', 'weather-warnings#what',
    ]);
  });
});

describe('SCREEN_GUIDE', () => {
  it('maps the two guided screens to existing pages and has no Operations entry', () => {
    expect(SCREEN_GUIDE).toEqual({ plan: 'planning', map: 'map' });
    Object.values(SCREEN_GUIDE).forEach((page) => expect(Object.keys(GUIDE_PAGES)).toContain(page));
    expect(SCREEN_GUIDE.operations).toBeUndefined();
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

describe('resolveGuideOrigin', () => {
  it('normalises a configured origin (trailing slash, path)', () => {
    expect(resolveGuideOrigin('http://localhost:8090/', false)).toBe('http://localhost:8090');
    expect(resolveGuideOrigin('https://guide.example/x/y', true)).toBe('https://guide.example');
  });

  it('falls back to the default instead of throwing on a malformed value', () => {
    expect(resolveGuideOrigin('not a url', false)).toBe('https://photocast.online');
    expect(resolveGuideOrigin('not a url', true)).toBe('https://photocast.online');
    expect(resolveGuideOrigin('', false)).toBe('https://photocast.online');
    expect(resolveGuideOrigin(undefined, true)).toBe('https://photocast.online');
  });

  it('in a production build rejects a non-https origin; in dev it is allowed', () => {
    expect(resolveGuideOrigin('http://localhost:8090', true)).toBe('https://photocast.online');
    expect(resolveGuideOrigin('http://localhost:8090', false)).toBe('http://localhost:8090');
  });
});

describe('the Find and Your places guides (docs/engineering/find-and-favourites-plan.md, F5)', () => {
  const findIds = ['open', 'search', 'areas', 'typos', 'pick', 'bookmarks'];
  const placesIds = ['star', 'where', 'honest', 'ask', 'lite', 'private'];

  it.each(findIds)('finding-places carries the %s section, and the on-page contents links to it', (id) => {
    expect(hasId('finding-places', id)).toBe(true);
    expect(pageSource('finding-places')).toContain(`href="#${id}"`);
  });

  it.each(placesIds)('your-places carries the %s section, and the on-page contents links to it', (id) => {
    expect(hasId('your-places', id)).toBe(true);
    expect(pageSource('your-places')).toContain(`href="#${id}"`);
  });

  it('states the honesty rule, the cap, the tie-break only rule and that Ready answers do not use favourites', () => {
    const page = pageSource('your-places');
    expect(page).toContain('You can keep up to 25 places and dates.');
    expect(page).toMatch(/never changes a star rating, a verdict or a Best bet/);
    expect(page).toContain('Mary\'s Lighthouse is yours, and Bamburgh Castle also scores 4★');
    expect(page).toMatch(/Ready questions[^<]*do not use your places/);
  });

  it('the Pro pages and sections are flagged for the guide search, and Find itself is not', () => {
    expect(pageSource('your-places')).toMatch(/<h1>Your places<span class="pro">Pro<\/span><\/h1>/);
    expect(pageSource('your-places')).toMatch(/id="star"><h2>[^<]*<span class="pro">Pro<\/span>/);
    expect(pageSource('finding-places')).not.toMatch(/<h1>[^<]*<span class="pro">/);
  });

  it.each([
    ['planning', 'find'], ['map', 'find'], ['planning', 'your-places'], ['ask-photocast', 'favourites'],
  ])('%s has a #%s section that links to the new guides', (name, id) => {
    expect(hasId(name, id)).toBe(true);
    expect(pageSource(name)).toMatch(/href="(finding|your)-places\.html/);
  });

  it('is on the hub and in the search, and the pages around it link to it', () => {
    expect(pageSource('index')).toContain('href="finding-places.html"');
    expect(pageSource('index')).toContain('href="your-places.html"');
    const search = readFileSync(resolve(GUIDE_DIR, 'guide-search.js'), 'utf8');
    expect(search).toContain("'finding-places.html'");
    expect(search).toContain("'your-places.html'");
    expect(pageSource('ask-photocast')).toContain('href="finding-places.html"><small>Next');
    expect(pageSource('finding-places')).toContain('href="your-places.html"><small>Next');
    expect(pageSource('your-places')).toContain('href="settings.html"><small>Next');
    expect(pageSource('settings')).toContain('href="your-places.html"><small>← Previous');
  });
});

describe('the weather warnings guide (docs/engineering/weather-warnings-plan.md, W5)', () => {
  const page = pageSource('weather-warnings');
  const ids = ['what', 'levels', 'matrix', 'updated', 'where', 'ratings', 'fresh', 'act', 'credit'];

  it.each(ids)('carries the %s section, and the on-page contents links to it', (id) => {
    expect(hasId('weather-warnings', id)).toBe(true);
    expect(page).toContain(`href="#${id}"`);
  });

  it('carries the Met Office attribution, the official colours, the stale line and the two Met Office links', () => {
    expect(page).toContain('Met Office Weather Warnings © Crown Copyright 2026');
    expect(page).toContain('Powered by Met Office data');
    ['#ca0a37', '#ff9900', '#ffe923'].forEach((hex) => expect(page).toContain(hex));
    expect(page).toContain('https://www.metoffice.gov.uk/public/weather/warnings/');
    expect(page).toContain('https://www.metoffice.gov.uk/weather/warnings-and-advice/seasonal-advice');
    expect(page).toContain('Last checked');
    expect(page).toContain('never an all-clear');
  });

  it('uses no Met Office logo or icon image (the page has no pictures at all)', () => {
    expect(page).not.toMatch(/<img/i);
  });

  it.each([
    ['planning'], ['map'], ['reading-the-forecast'],
  ])('%s has a warnings section that links to the guide', (name) => {
    expect(hasId(name, 'warnings')).toBe(true);
    expect(pageSource(name)).toContain('href="#warnings"');
    expect(pageSource(name)).toContain('weather-warnings.html');
  });

  it('is on the hub and in the search, and the pages around it link to it', () => {
    expect(pageSource('index')).toContain('href="weather-warnings.html"');
    expect(readFileSync(resolve(GUIDE_DIR, 'guide-search.js'), 'utf8')).toContain("'weather-warnings.html'");
    expect(pageSource('map')).toContain('href="weather-warnings.html"><small>Next');
    expect(pageSource('night-sky')).toContain('href="weather-warnings.html"><small>← Previous');
  });
});

describe('the one-screen guides (docs/engineering/one-screen-plan.md, O4a)', () => {
  const ids = ['this-week', 'further-ahead', 'watch-a-date', 'your-places', 'screen'];
  /** Every id the two retired pages carried, so an old `#section` link still lands on planning.html. */
  const OLD_IDS = ['top', 'lens', 'days', 'windows', 'spread', 'open', 'bestbet', 'away', 'find', 'warnings', 'tips',
    'topics', 'new', 'filters', 'entries', 'tides', 'actions'];

  it.each([...ids, ...OLD_IDS])('planning carries the %s section', (id) => {
    expect(hasId('planning', id)).toBe(true);
  });

  it('the guide describes one screen: no tab bar, no Coming up tab, no "At a Glance", no Map tab', () => {
    files.forEach((f) => {
      const source = readFileSync(resolve(GUIDE_DIR, f), 'utf8');
      const description = source.match(/<meta name="description" content="([^"]*)"/)?.[1] ?? '';
      const text = `${description} ${source.replace(/<[^>]+>/g, ' ')}`
        .replace(/\b(new|browser|browser's) tab\b/gi, '');
      expect(text, `${f} still names the old screens`)
        .not.toMatch(/At a Glance|Map tab|Coming up tab|\bComing up\b|\bGlance\b/);
      expect(text, `${f} still talks about tabs`).not.toMatch(/\btabs?\b/i);
    });
  });

  it('every internal link points at a page and a section that exist', () => {
    const known = new Set(files.map((f) => f.replace(/\.html$/, '')));
    const problems = [];
    files.forEach((f) => {
      const html = readFileSync(resolve(GUIDE_DIR, f), 'utf8');
      [...html.matchAll(/href="([^"#]*)(?:#([^"]*))?"/g)].forEach(([, path, hash]) => {
        if (/^(https?:|mailto:)/.test(path)) return;
        const target = path === '' ? f.replace(/\.html$/, '') : path.replace(/\.html$/, '');
        if (path && (path.startsWith('..') || path.endsWith('.css') || path.endsWith('.png'))) return;
        if (path === 'index.html' || path === '') {
          if (path === 'index.html' && !known.has('index')) problems.push(`${f}: ${path}`);
        } else if (!known.has(target)) {
          problems.push(`${f}: ${path}`);
          return;
        }
        if (hash && !hasId(target, hash)) problems.push(`${f}: ${path}#${hash}`);
      });
    });
    expect(problems).toEqual([]);
  });
});

describe('moved.js, the redirect behind the two retired pages', () => {
  const code = readFileSync(resolve(GUIDE_DIR, 'moved.js'), 'utf8');
  /** Runs the script against a stubbed `location` and returns the URL it replaced to. */
  const redirectOf = (pathname, search, hash) => {
    let target = null;
    new Function('location', code)({ pathname, search, hash, replace: (url) => { target = url; } });
    return target;
  };

  it.each([
    ['/guide/coming-up.html', '', '', 'planning.html#further-ahead'],
    ['/guide/coming-up.html', '', '#list', 'planning.html#further-ahead'],
    ['/guide/coming-up.html', '', '#watchlist', 'planning.html#your-places'],
    ['/guide/coming-up.html', '?embed=1', '#tides', 'planning.html?embed=1#tides'],
    ['/guide/coming-up.html', '', '#topics', 'planning.html#topics'],
    ['/guide/at-a-glance.html', '', '', 'planning.html'],
    ['/guide/at-a-glance.html', '', '#bestbet', 'planning.html#bestbet'],
    ['/guide/at-a-glance.html', '?embed=1', '#lens', 'planning.html?embed=1#lens'],
    ['/guide/at-a-glance.html', '', '#list', 'planning.html#list'],
  ])('%s%s%s lands on %s', (pathname, search, hash, expected) => {
    expect(redirectOf(pathname, search, hash)).toBe(expected);
  });
});
