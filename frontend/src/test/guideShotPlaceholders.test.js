import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Pins the weather warnings screenshot placeholders (docs/engineering/weather-warnings-screenshots.md).
 * A placeholder is a `<figure class="shot" id="shot-...">` holding a `.ph` block; when the real capture
 * lands the block becomes an `<img>`, so the figure id must survive (the overview page links to it).
 */
const GUIDE_DIR = resolve(process.cwd(), '../landing/guide');
const pages = readdirSync(GUIDE_DIR).filter((f) => f.endsWith('.html')).map((f) => f.replace(/\.html$/, ''));
const source = (page) => readFileSync(resolve(GUIDE_DIR, `${page}.html`), 'utf8');
const shotIds = (html) => [...html.matchAll(/<figure[^>]*\sid="(shot-[^"]+)"/g)].map((m) => m[1]);

const PLACEHOLDERS = {
  'weather-warnings': ['shot-warn-stale', 'shot-warn-ask'],
  'at-a-glance': ['shot-warn-lanes'],
  'coming-up': ['shot-warn-coming-up'],
};
/** Placeholders replaced by a real capture: the figure id stays, the `.ph` block becomes a WebP the page can load. */
const REAL_IMAGES = {
  map: { 'shot-warn-map-chips': 'warn-map-chips.webp', 'shot-warn-callout': 'warn-callout.webp' },
  'at-a-glance': { 'shot-open': 'open-map.webp' },
};
const REAL_CASES = Object.entries(REAL_IMAGES).flatMap(([page, m]) => Object.entries(m).map(([id, file]) => [page, id, file]));
const CASES = Object.entries(PLACEHOLDERS).flatMap(([page, ids]) => ids.map((id) => [page, id]));

describe('guide screenshot ids', () => {
  it.each(pages)('%s: every shot-* id is unique', (page) => {
    const ids = shotIds(source(page));
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('keeps warning shot ids unique across all pages', () => {
    const all = pages.flatMap((p) => shotIds(source(p))).filter((id) => id.startsWith('shot-warn-'));
    expect(all.length).toBe(CASES.length + REAL_CASES.filter(([, id]) => id.startsWith('shot-warn-')).length);
    expect(new Set(all).size).toBe(all.length);
  });
});

describe('weather warnings placeholders', () => {
  it.each(CASES)('%s: %s is a labelled placeholder with a caption', (page, id) => {
    const re = new RegExp(
      `<figure class="shot[^"]*" id="${id}"><div class="ph"><span>Screenshot · [^<]+</span></div>`
      + '<figcaption>([^<]+)</figcaption></figure>',
    );
    const m = source(page).match(re);
    expect(m, `${page}.html lacks a complete placeholder ${id}`).not.toBeNull();
    expect(m[1].length).toBeGreaterThan(20);
    expect(m[1]).not.toMatch(/all[- ]clear|no warnings/i);
  });

  it('links from the overview to shots that exist', () => {
    const links = [...source('weather-warnings').matchAll(/href="([a-z-]+)\.html#(shot-warn-[^"]+)"/g)];
    expect(links.length).toBeGreaterThanOrEqual(4);
    links.forEach(([, page, id]) => expect(shotIds(source(page))).toContain(id));
  });

  it('adds image files only for the captured shots', () => {
    const captured = REAL_CASES.map(([, , file]) => file);
    const warnFiles = readdirSync(resolve(GUIDE_DIR, 'shots')).filter((f) => /warn/i.test(f));
    expect(warnFiles.sort()).toEqual(captured.filter((f) => /warn/i.test(f)).sort());
  });
});

describe('captured screenshots', () => {
  it.each(REAL_CASES)('%s: %s is a real image with alt text and no placeholder', (page, id, file) => {
    const html = source(page);
    const re = new RegExp(`<figure class="shot[^"]*" id="${id}"><img src="shots/${file}" width="(\\d+)" height="(\\d+)" alt="([^"]{40,})">`);
    const m = html.match(re);
    expect(m, `${page}.html lacks a real image for ${id}`).not.toBeNull();
    expect(existsSync(resolve(GUIDE_DIR, 'shots', file))).toBe(true);
    expect(m[3]).not.toMatch(/all[- ]clear|seeded|not a forecast/i);
    expect(html).not.toMatch(new RegExp(`id="${id}"><div class="ph">`));
  });
});
