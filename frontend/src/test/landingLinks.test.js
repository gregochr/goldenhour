import { describe, it, expect } from 'vitest';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

/**
 * Every internal `href="page.html#id"` between the landing pages and the guide pages resolves to a file that
 * exists and, when it names an id, to a page that carries it (docs/engineering/landing-guide-plan.md, G1:
 * a page split must not leave a link pointing at the old page). External links are skipped.
 */
const LANDING = resolve(__dirname, '../../../landing');
const pages = [
  ...readdirSync(LANDING).filter((f) => f.endsWith('.html')),
  ...readdirSync(resolve(LANDING, 'guide')).filter((f) => f.endsWith('.html')).map((f) => `guide/${f}`),
];
const html = (rel) => readFileSync(resolve(LANDING, rel), 'utf8');
const hasId = (rel, id) => new RegExp(`\\s(?:id|name)="${id}"`).test(html(rel));

describe('landing and guide internal links', () => {
  it('finds the pages it is meant to check', () => {
    expect(pages).toEqual(expect.arrayContaining(['index.html', 'guide/planning.html', 'guide/this-week.html', 'guide/further-ahead.html']));
  });

  it.each(pages)('%s: every html link resolves to a file and an id that exist', (rel) => {
    const problems = [];
    [...html(rel).matchAll(/href="([^"]*)"/g)].forEach(([, href]) => {
      if (/^([a-z]+:|\/\/)/i.test(href)) return;
      const [path, hash] = href.split('#');
      if (path && !path.endsWith('.html')) return;
      const target = path ? resolve(LANDING, dirname(rel), path) : resolve(LANDING, rel);
      const targetRel = target.slice(LANDING.length + 1);
      if (!existsSync(target)) {
        problems.push(`${href}: no such file`);
      } else if (hash && !hasId(targetRel, hash)) {
        problems.push(`${href}: no id "${hash}" on ${targetRel}`);
      }
    });
    expect(problems).toEqual([]);
  });
});
