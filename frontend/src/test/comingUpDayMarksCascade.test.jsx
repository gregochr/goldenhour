import React from 'react';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, beforeAll, afterAll, afterEach,
} from 'vitest';
import { render, cleanup } from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';

/**
 * What the Coming up day-chip rules resolve to, asserted against the real `index.css`
 * (supermoon-tide-run-plan S3). The rest of the suite runs with `css: false`, so the component
 * tests pin the DOM (`data-family`, `data-marked`, `data-peak`) and nothing pinned what those
 * attributes resolve to.
 *
 * <p>jsdom resolves SPECIFICITY and the cascade, but implements no layout, so this proves which
 * declaration won and nothing about geometry (whether "FULL MOON" wraps inside a 38-40px chip is a
 * browser claim). It also cannot resolve `var()` — the computed value is the declared token text —
 * so each assertion names the token the winning rule uses. Shorthands carrying a `var()` are
 * dropped by cssstyle, so the asserted properties are longhands.
 */
const CSS_PATH = resolve(process.cwd(), 'src/index.css');

/** Every rule whose selector mentions the day chip, comments stripped, brace-depth aware. */
function extractDayRules() {
  expect(existsSync(CSS_PATH), `index.css not found at ${CSS_PATH}`).toBe(true);
  const css = readFileSync(CSS_PATH, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const rules = [];
  let selectorStart = 0;
  let depth = 0;
  let blockStart = -1;
  for (let i = 0; i < css.length; i += 1) {
    if (css[i] === '{') {
      depth += 1;
      if (depth === 1) blockStart = i;
    } else if (css[i] === '}') {
      depth -= 1;
      if (depth === 0) {
        const selector = css.slice(selectorStart, blockStart).trim();
        if (/\.wf-cu-day|\.wf-cu-coin-name/.test(selector)) {
          rules.push(`${selector} ${css.slice(blockStart, i + 1)}`);
        }
        selectorStart = i + 1;
      }
    }
  }
  return rules.join('\n');
}

let styleEl;
let sliceText = '';
let rawCss = '';
beforeAll(() => {
  const slice = extractDayRules();
  sliceText = slice;
  rawCss = readFileSync(CSS_PATH, 'utf8');
  expect(slice).toContain('.wf-cu-day-mark[data-family="coastal"]');
  expect(slice).toContain('.wf-cu-days[data-marked="true"] .wf-cu-day[data-peak="true"]');
  expect(slice).toContain('.wf-cu-day[data-peak="true"] .wf-cu-day-dn');
  styleEl = document.createElement('style');
  styleEl.textContent = slice;
  document.head.appendChild(styleEl);
});
afterAll(() => styleEl?.remove());
afterEach(cleanup);

const chip = (date, dow, day, over = {}) => ({
  date, dow, day, monthWord: null, dateLabel: `${dow} ${day} Oct`, today: false, gone: false,
  peak: false, marks: null, live: true, ...over,
});

const FAMILIES = ['coastal', 'aurora', 'air', 'night-sky', 'sun-moon', 'dust', 'eclipse'];

const entryWith = (dayChips) => ({
  id: 'spring-tide:2026-10-25:2026-10-30',
  type: 'spring-tide',
  family: 'coastal',
  isForecast: false,
  rail: { dow: null, day: '25–30', month: 'Oct', isRange: true, countdown: 'in 11 days' },
  title: 'Spring tide run, on a supermoon',
  kindTag: 'Almanac',
  superlative: null,
  metric: null,
  prose: null,
  isFeature: false,
  facts: [],
  threshold: null,
  action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: '2026-10-27' },
  interactive: false,
  actionWithheld: false,
  actionNote: null,
  dayChips,
  doorNoun: 'Coastal spots',
  tide: null,
  coincidence: null,
  joinNote: null,
  isNew: false,
});

const draw = (dayChips) => render(
  <WindowComingUpEntry entry={entryWith(dayChips)} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
);
const cs = (el) => window.getComputedStyle(el);

describe('Coming up day marks — what the rules resolve to', () => {
  it('colours every mark by its own family token, never by type', () => {
    const { container } = draw(FAMILIES.map((family, i) => chip(
      `2026-10-${25 + i}`, 'Mon', String(25 + i), { marks: [{ label: 'full moon', family }] },
    )));
    FAMILIES.forEach((family) => {
      const mark = container.querySelector(`.wf-cu-day-mark[data-family="${family}"]`);
      expect(cs(mark).color).toBe(`var(--color-topic-${family})`);
    });
  });

  it('keeps a mark coloured by family on a disabled (no-forecast) chip', () => {
    const { container } = draw([
      chip('2026-10-26', 'Mon', '26', { live: false, marks: [{ label: 'full moon', family: 'sun-moon' }] }),
    ]);
    const mark = container.querySelector('.wf-cu-day-mark');
    expect(container.querySelector('.wf-cu-day')).toBeDisabled();
    expect(cs(mark).color).toBe('var(--color-topic-sun-moon)');
  });

  it('colours the merged peak chip by the peak MARK’s family, not the host accent or gold', () => {
    const { container } = draw([
      chip('2026-10-26', 'Mon', '26', { marks: [{ label: 'full moon', family: 'sun-moon' }] }),
      chip('2026-10-27', 'Tue', '27', { peak: true, marks: [{ label: 'peak', family: 'coastal' }] }),
    ]);
    expect(container.querySelector('.wf-cu-days')).toHaveAttribute('data-marked', 'true');
    const peak = container.querySelector('[data-peak="true"]');
    expect(peak).toHaveAttribute('data-peak-family', 'coastal');
    expect(cs(peak).getPropertyValue('--wf-cu-peak')).toBe('var(--color-topic-coastal)');
    expect(cs(peak.querySelector('.wf-cu-day-dn')).color).toBe('var(--wf-cu-peak, var(--color-home))');
    // cssstyle cannot reify a border or background carrying var()/color-mix(), so those are pinned
    // in the extracted rule text.
    expect(sliceText).toMatch(/\[data-peak="true"\]\s*\{\s*border-color: var\(--wf-cu-peak, var\(--color-home\)\);/);
    expect(sliceText).toContain(':not(:disabled) {\n  background: color-mix(in oklch, var(--wf-cu-peak, var(--color-home)) 14%, transparent);');
    expect(sliceText).toContain(':hover:not(:disabled) {\n  background: color-mix(in oklch, var(--wf-cu-peak, var(--color-home)) 16%, transparent);');
  });

  it('draws marks and month words as blocks', () => {
    const { container } = draw([
      chip('2026-10-26', 'Mon', '26', { monthWord: 'Oct', marks: [{ label: 'full moon', family: 'sun-moon' }] }),
    ]);
    expect(cs(container.querySelector('.wf-cu-day-mark')).display).toBe('block');
    expect(cs(container.querySelector('.wf-cu-day-month')).display).toBe('block');
  });

  it('swaps the focus ring for the system colour in forced colours', () => {
    expect(rawCss).toMatch(
      /@media \(forced-colors: active\) \{\s*\.wf-cu-day:focus-visible \{ outline-color: Highlight; \}/,
    );
  });

  it('lets a coincidence line’s facts sit under its name on a phone', () => {
    expect(rawCss).toMatch(
      /@media \(max-width: 639px\) \{\s*\.wf-cu-coin-facts \{ margin-left: 0; flex-basis: 100%; \}/,
    );
  });

  it('leaves the peak number of an UNMERGED entry gold', () => {
    const { container } = draw([
      chip('2026-10-26', 'Mon', '26'),
      chip('2026-10-27', 'Tue', '27', { peak: true }),
    ]);
    expect(container.querySelector('.wf-cu-days')).not.toHaveAttribute('data-marked');
    const dn = container.querySelector('[data-peak="true"] .wf-cu-day-dn');
    expect(cs(dn).color).toBe('var(--color-home)');
  });

  it('mutes a dimmed merged peak number by colour, not opacity', () => {
    const { container } = draw([
      chip('2026-10-27', 'Tue', '27', { peak: true, live: false, marks: [{ label: 'peak', family: 'coastal' }] }),
    ]);
    const dn = container.querySelector('[data-peak="true"] .wf-cu-day-dn');
    expect(cs(dn).color).toBe('var(--color-plex-text-secondary)');
  });

  it('keeps a coincidence name on one line', () => {
    const el = document.createElement('span');
    el.className = 'wf-cu-coin-name';
    document.body.appendChild(el);
    expect(cs(el).whiteSpace).toBe('nowrap');
    el.remove();
  });
});
