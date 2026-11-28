import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Keeps the landing pricing cards (five bullets each) in agreement with the guide hub's Lite and Pro table
 * (docs/engineering/landing-guide-plan.md, G2). Reads `landing/` from disk.
 */
const LANDING = resolve(__dirname, '../../../landing');
const home = readFileSync(resolve(LANDING, 'index.html'), 'utf8');
const hub = readFileSync(resolve(LANDING, 'guide/index.html'), 'utf8');

const strip = (h) => h.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();

function cardBullets(tier) {
  const card = home.split('<div class="price">').slice(1).find((c) => c.includes(`<div class="tier">${tier}</div>`));
  return [...card.split('</ul>')[0].matchAll(/<li>([\s\S]*?)<\/li>/g)].map((m) => strip(m[1]));
}

const rows = [...hub.slice(hub.indexOf('id="lite-pro"')).matchAll(/<tr><td>([\s\S]*?)<\/td><td>([\s\S]*?)<\/td><td>([\s\S]*?)<\/td>/g)]
  .map((m) => ({ feature: strip(m[1]), lite: strip(m[2]), pro: strip(m[3]) }));

function row(phrase) {
  const found = rows.filter((r) => r.feature.includes(phrase));
  expect(found, `table row for "${phrase}"`).toHaveLength(1);
  return found[0];
}

/** Bullet prefix on the landing card -> distinctive phrase of the hub table row it corresponds to. */
const LITE_MAP = {
  'Sunrise and sunset forecasts': 'verdicts and the forecast map',
  'Star ratings for up to 3': 'Star ratings (Lite',
  'This week:': 'This week:',
  'Further ahead:': 'Further ahead:',
  'Met Office weather warnings': 'Met Office weather warnings',
};
const PRO_MAP = {
  'Red Sky and Golden Hour': 'Red Sky and Golden Hour',
  'Tide at the light': 'Tide at the light',
  'Ask PhotoCast': 'Ask PhotoCast',
  'Aurora forecasts': 'Aurora forecasts',
};
const PRO_STAR_RATINGS = 'Star ratings for every sub-region';

function phraseFor(bullet, map) {
  const key = Object.keys(map).find((k) => bullet.startsWith(k));
  expect(key, `a mapped table row for bullet "${bullet}"`).toBeDefined();
  return map[key];
}

describe('landing pricing cards against the guide hub table', () => {
  const lite = cardBullets('Lite');
  const pro = cardBullets('Pro');

  it('has exactly five bullets on each card', () => {
    expect(lite).toHaveLength(5);
    expect(pro).toHaveLength(5);
  });

  it('matches every Lite bullet to a row whose Lite cell is a tick', () => {
    lite.forEach((b) => expect(row(phraseFor(b, LITE_MAP)).lite, b).toBe('✓'));
  });

  it('matches every Pro bullet to a Pro-only row, bar the sub-region star ratings', () => {
    pro.filter((b) => b !== PRO_STAR_RATINGS).forEach((b) => {
      const r = row(phraseFor(b, PRO_MAP));
      expect(r.pro, b).toBe('✓');
      expect(r.lite, b).toBe('—');
    });
    expect(pro).toContain(PRO_STAR_RATINGS);
    const stars = row('Star ratings (Lite');
    expect(stars.pro).toBe('✓');
    expect(stars.feature).toContain('up to three');
  });

  it('links "Compare Lite and Pro in full" at an id that exists on the hub', () => {
    const m = home.match(/<a href="guide\/index\.html#([^"]+)">Compare Lite and Pro in full →<\/a>/);
    expect(m).not.toBeNull();
    expect(hub).toContain(`id="${m[1]}"`);
  });
});
