import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Guards the marketing site's one menu (docs/engineering/landing-guide-plan.md, L6) and the home page's
 * images (L3). Reads `landing/` from disk, as `landingTypography.test.js` does.
 */
const LANDING = resolve(__dirname, '../../../landing');
const REGISTER = 'https://app.photocast.online/register';

const pages = [
  ...readdirSync(LANDING).filter((f) => f.endsWith('.html')).map((f) => ({ rel: f, depth: 0 })),
  ...readdirSync(resolve(LANDING, 'guide')).filter((f) => f.endsWith('.html')).map((f) => ({ rel: `guide/${f}`, depth: 1 })),
].map((p) => ({ ...p, html: readFileSync(resolve(LANDING, p.rel), 'utf8') }));

const mastheads = pages.filter((p) => p.html.includes('<header class="mast">'));

function menuOf(html) {
  const header = html.slice(html.indexOf('<header class="mast">'), html.indexOf('</header>'));
  const nav = header.match(/<nav(?![^>]*secbar)[^>]*>([\s\S]*?)<\/nav>/)[1];
  return [...nav.matchAll(/<a\b([^>]*)>([\s\S]*?)<\/a>/g)].map((m) => ({
    text: m[2].trim(),
    href: m[1].match(/href="([^"]*)"/)[1],
  }));
}

function expectedMenu({ rel, depth }) {
  const up = depth ? '../' : '';
  const index = rel === 'index.html' ? '' : `${up}index.html`;
  return [
    { text: 'Plan a shoot', href: `${index}#plan` },
    { text: 'Pricing', href: `${index}#pricing` },
    { text: 'Guide', href: depth ? 'index.html' : 'guide/index.html' },
    { text: 'FAQ', href: `${up}faq.html` },
    { text: 'Start free', href: REGISTER },
  ];
}

describe('landing masthead menu', () => {
  it('finds the mastheads this test is meant to cover', () => {
    expect(mastheads.length).toBeGreaterThanOrEqual(18);
    expect(mastheads.map((p) => p.rel)).toEqual(expect.arrayContaining(['index.html', 'faq.html', 'guide/index.html']));
  });

  it.each(mastheads.map((p) => [p.rel, p]))('%s has the five menu items in order, linked for its folder', (_rel, page) => {
    expect(menuOf(page.html)).toEqual(expectedMenu(page));
  });

  it('keeps the phone section bar on the home page only', () => {
    pages.forEach((p) => {
      expect(p.html.includes('class="secbar"'), p.rel).toBe(p.rel === 'index.html');
    });
  });

  it('points every home page section-bar link at a section that exists', () => {
    const home = pages.find((p) => p.rel === 'index.html').html;
    const bar = home.match(/<nav class="secbar"[^>]*>([\s\S]*?)<\/nav>/)[1];
    const hrefs = [...bar.matchAll(/href="#([^"]+)"/g)].map((m) => m[1]);
    expect(hrefs).toEqual(['plan', 'field', 'features', 'pricing', 'faq']);
    hrefs.forEach((id) => expect(home).toContain(`id="${id}"`));
  });
});

describe('landing/index.html images', () => {
  const home = pages.find((p) => p.rel === 'index.html').html;

  it('gives every image non-empty alt text', () => {
    const imgs = [...home.matchAll(/<img\b[^>]*>/g)].map((m) => m[0]);
    expect(imgs.length).toBeGreaterThan(0);
    imgs.forEach((tag) => expect(tag).toMatch(/\balt="[^"]+"/));
  });

  it('has every local image and zoom target on disk', () => {
    const refs = [...home.matchAll(/<(?:img src|a class="zoom" href)="([^"#?]+)"/g)].map((m) => m[1]);
    expect(refs.length).toBeGreaterThan(0);
    refs.filter((r) => !/^https?:/.test(r)).forEach((r) => {
      expect(existsSync(resolve(LANDING, r)), r).toBe(true);
    });
  });
});
