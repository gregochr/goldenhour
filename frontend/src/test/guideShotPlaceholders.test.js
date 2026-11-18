import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
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
  'weather-warnings': ['shot-warn-popup', 'shot-warn-stale', 'shot-warn-ask'],
  'at-a-glance': ['shot-warn-lanes'],
  map: ['shot-warn-map-chips', 'shot-warn-callout'],
  'coming-up': ['shot-warn-coming-up'],
};
const CASES = Object.entries(PLACEHOLDERS).flatMap(([page, ids]) => ids.map((id) => [page, id]));

describe('guide screenshot ids', () => {
  it.each(pages)('%s: every shot-* id is unique', (page) => {
    const ids = shotIds(source(page));
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('keeps warning shot ids unique across all pages', () => {
    const all = pages.flatMap((p) => shotIds(source(p))).filter((id) => id.startsWith('shot-warn-'));
    expect(all.length).toBe(CASES.length);
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

  it('adds no image files for the placeholders', () => {
    expect(readdirSync(resolve(GUIDE_DIR, 'shots')).filter((f) => /warn/i.test(f))).toEqual([]);
  });
});
