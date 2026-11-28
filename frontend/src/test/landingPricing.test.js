import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Keeps the landing pricing cards (five bullets each) in agreement with the guide hub's Lite and Pro table
 * (docs/engineering/landing-guide-plan.md, G2). Reads `landing/` from disk and parses it with the DOM.
 */
const LANDING = resolve(__dirname, '../../../landing');
const parse = (file) => new DOMParser().parseFromString(readFileSync(resolve(LANDING, file), 'utf8'), 'text/html');
const homeDoc = parse('index.html');
const hubDoc = parse('guide/index.html');
const text = (el) => el.textContent.replace(/\s+/g, ' ').trim();

function cardBullets(tier) {
  const card = [...homeDoc.querySelectorAll('.price')].find((c) => text(c.querySelector('.tier')) === tier);
  expect(card, `${tier} card`).toBeDefined();
  return [...card.querySelectorAll('ul li')].map(text);
}

const rows = [...hubDoc.querySelectorAll('#lite-pro tbody tr')].map((tr) => {
  const cells = [...tr.querySelectorAll('td')].map(text);
  return { feature: cells[0], lite: cells[1], pro: cells[2] };
});

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
    const link = [...homeDoc.querySelectorAll('a')].find((a) => text(a) === 'Compare Lite and Pro in full →');
    expect(link).toBeDefined();
    const [page, id] = link.getAttribute('href').split('#');
    expect(page).toBe('guide/index.html');
    expect(hubDoc.getElementById(id)).not.toBeNull();
  });
});
