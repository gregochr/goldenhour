import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach, beforeAll, afterAll,
} from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';
import { buildEntryView } from '../utils/comingUpFeed.js';

afterEach(cleanup);

const TODAY = '2026-10-20';

/** A merged spring run 25-30 Oct: the served range peak (27th) differs from the door's date (26th). */
const entry = (over = {}) => ({
  id: 'spring-tide:2026-10-25:2026-10-30',
  type: 'spring-tide',
  startDate: '2026-10-25',
  endDate: '2026-10-30',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run, on a supermoon',
  kindTag: 'Almanac',
  facts: [],
  action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: '2026-10-26' },
  dayMarks: [
    { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
    { date: '2026-10-27', label: 'peak', family: 'coastal' },
  ],
  dayRanges: [
    { date: '2026-10-25', rangeMetres: 4.0, peak: false },
    { date: '2026-10-26', rangeMetres: 4.5, peak: false },
    { date: '2026-10-27', rangeMetres: 5.0, peak: true },
    { date: '2026-10-29', rangeMetres: 4.0, peak: false },
  ],
  ...over,
});

const draw = (e) => render(
  <WindowComingUpEntry entry={buildEntryView(e, TODAY, null, null)} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
);
const barHeights = (c) => [...c.querySelectorAll('.wf-cu-day-col')].map((col) => {
  const bar = col.querySelector('.wf-cu-day-range-bar');
  return bar ? bar.style.getPropertyValue('--wf-cu-bar-h') : null;
});

describe('buildEntryView — range scale', () => {
  it('threads served ranges by date and scales over the served values', () => {
    const view = buildEntryView(entry(), TODAY, null, null);
    expect(view.dayChips.map((c) => c.rangeMetres)).toEqual([4.0, 4.5, 5.0, null, 4.0, null]);
    expect(view.dayChips.map((c) => c.rangePeak)).toEqual([false, false, true, false, false, false]);
    expect(view.rangeScale).toEqual({ min: 4.0, max: 5.0 });
  });

  it('has no scale with fewer than two served values, or none', () => {
    const one = buildEntryView(
      entry({ dayRanges: [{ date: '2026-10-27', rangeMetres: 5, peak: true }] }), TODAY, null, null,
    );
    expect(one.rangeScale).toBeNull();
    expect(buildEntryView(entry({ dayRanges: undefined }), TODAY, null, null).rangeScale).toBeNull();
    expect(buildEntryView({ ...entry(), dayRanges: undefined, dayMarks: undefined }, TODAY, null, null).rangeScale)
      .toBeNull();
  });
});

describe('Coming up range bars', () => {
  it('sizes the bars min 4px, max 26px, middle in proportion, a missing day with no bar', () => {
    const { container } = draw(entry());
    expect(barHeights(container)).toEqual(['4px', '15px', '26px', null, '4px', null]);
  });

  it('draws a middle value in proportion', () => {
    const { container } = draw(entry({
      dayRanges: [
        { date: '2026-10-25', rangeMetres: 4.0, peak: false },
        { date: '2026-10-26', rangeMetres: 4.25, peak: false },
        { date: '2026-10-27', rangeMetres: 5.0, peak: true },
      ],
    }));
    expect(barHeights(container)[1]).toBe('9.5px');
  });

  it('draws 15px bars when every value is equal', () => {
    const { container } = draw(entry({
      dayRanges: [
        { date: '2026-10-25', rangeMetres: 4.0, peak: false },
        { date: '2026-10-26', rangeMetres: 4.0, peak: true },
      ],
    }));
    expect(barHeights(container).filter(Boolean)).toEqual(['15px', '15px']);
  });

  it('marks the solid bar from the served range peak, not the chip peak', () => {
    const { container } = draw(entry());
    const solid = [...container.querySelectorAll('.wf-cu-day-range')]
      .map((r) => r.getAttribute('data-peak'));
    expect(solid).toEqual(['false', 'false', 'true', 'false', 'false', 'false']);
    expect(container.querySelectorAll('.wf-cu-day-range[data-peak="true"]')).toHaveLength(1);
  });

  it('shows the value to one decimal, empty for a day with none', () => {
    const { container } = draw(entry());
    expect([...container.querySelectorAll('.wf-cu-day-range-val')].map((v) => v.textContent))
      .toEqual(['4.0', '4.5', '5.0', '', '4.0', '']);
  });

  it('hides only the bar span from assistive tech; every chip button is still found by name', () => {
    const { container } = draw(entry());
    container.querySelectorAll('.wf-cu-day-col').forEach((col) => {
      expect(col).not.toHaveAttribute('aria-hidden');
      expect(col.querySelector('.wf-cu-day-range')).toHaveAttribute('aria-hidden', 'true');
    });
    expect(screen.getAllByRole('button')).toHaveLength(6);
    expect(screen.getByRole('button', { name: 'Show coastal spots for Tue 27 Oct, peak day, 5.0 metre range' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show coastal spots for Mon 26 Oct, full moon, 4.5 metre range' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Show coastal spots for Thu 29 Oct, 4.0 metre range' }))
      .toBeInTheDocument();
  });

  it('follows the served range peak on an UNMERGED run whose door date differs', () => {
    const { container } = draw(entry({ dayMarks: undefined }));
    // chip.peak (door date, 26th) draws the gold chip; the solid bar stays on the served 27th.
    expect(container.querySelector('.wf-cu-day[data-peak="true"]').dataset.date).toBe('2026-10-26');
    expect(container.querySelector('.wf-cu-day-range[data-peak="true"]').closest('.wf-cu-day-col')
      .querySelector('.wf-cu-day').dataset.date).toBe('2026-10-27');
  });

  it('carries the range clause in the gone and no-forecast names', () => {
    const todayFar = '2026-10-26';
    const view = buildEntryView(entry(), todayFar, null, ['2026-10-26', '2026-10-27', '2026-10-28']);
    render(<WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />);
    expect(screen.getByRole('button', { name: 'Sun 25 Oct, 4.0 metre range — gone' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Thu 29 Oct, 4.0 metre range — no forecast yet' }))
      .toBeInTheDocument();
  });

  it('draws no columns and no range clause with fewer than two served values', () => {
    const { container } = draw(entry({ dayRanges: [{ date: '2026-10-27', rangeMetres: 5, peak: true }] }));
    expect(container.querySelector('.wf-cu-day-col')).toBeNull();
    expect(container.querySelector('.wf-cu-day-range')).toBeNull();
    expect(screen.queryByRole('button', { name: /metre range/ })).toBeNull();
  });

  it('leaves an unmerged tide run with one value unchanged', () => {
    const { container } = draw({
      ...entry(), dayMarks: undefined, dayRanges: [{ date: '2026-10-27', rangeMetres: 5, peak: true }],
    });
    expect(container.querySelector('.wf-cu-day-col')).toBeNull();
    expect(container.querySelectorAll('.wf-cu-day')).toHaveLength(6);
  });
});

describe('Coming up range bars — what the rules resolve to', () => {
  const rawCss = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
  const css = rawCss.replace(/\/\*[\s\S]*?\*\//g, '');
  let styleEl;
  beforeAll(() => {
    const rules = [];
    let start = 0;
    let depth = 0;
    let open = -1;
    for (let i = 0; i < css.length; i += 1) {
      if (css[i] === '{') { depth += 1; if (depth === 1) open = i; } else if (css[i] === '}') {
        depth -= 1;
        if (depth === 0) {
          const sel = css.slice(start, open).trim();
          if (/\.wf-cu-day-(col|range)|\.wf-cu-day \{|\.wf-cu-days\[data-ranged/.test(`${sel} {`)) {
            rules.push(`${sel} ${css.slice(open, i + 1)}`);
          }
          start = i + 1;
        }
      }
    }
    styleEl = document.createElement('style');
    styleEl.textContent = rules.join('\n');
    document.head.appendChild(styleEl);
  });
  afterAll(() => styleEl?.remove());
  const cs = (el) => window.getComputedStyle(el);

  it('stretches the chip button to the column height', () => {
    const { container } = draw(entry());
    expect(cs(container.querySelector('.wf-cu-day-col > .wf-cu-day')).flex).toBe('1 0 auto');
  });

  it('aligns the lead and note with the chip row only when columns exist', () => {
    const { container } = draw(entry());
    expect(container.querySelector('.wf-cu-days')).toHaveAttribute('data-ranged', 'true');
    // Only from 640px: no rule outside a min-width media block touches the ranged row's alignment,
    // so the phone stack (flex-start) is unchanged.
    expect(css).toMatch(
      /@media \(min-width: 640px\) \{\s*\.wf-cu-days\[data-ranged="true"\] \{ align-items: stretch; \}[\s\S]*?padding-top: var\(--wf-cu-range-h\);/,
    );
    expect(css.replace(/@media \(min-width: 640px\) \{[\s\S]*?\n\}\n/, '')).not.toMatch(
      /\.wf-cu-days\[data-ranged="true"\][^{]*\{[^}]*align-items/,
    );
    expect(cs(container.querySelector('.wf-cu-days')).alignItems).not.toBe('flex-end');
    expect(css).toMatch(/--wf-cu-range-h: calc\(var\(--wf-cu-range-val-h\) \+ var\(--wf-cu-range-track-h\) \+ var\(--wf-cu-range-gap\)\);/);
    cleanup();
    const plain = draw(entry({ dayRanges: undefined })).container;
    expect(plain.querySelector('.wf-cu-days')).not.toHaveAttribute('data-ranged');
  });

  it('colours the bar 45% by default and solid on the peak column', () => {
    const { container } = draw(entry());
    const bars = container.querySelectorAll('.wf-cu-day-range-bar');
    expect(rawCss).toMatch(/\.wf-cu-day-range-bar \{[^}]*color-mix\(in oklch, var\(--color-topic-coastal\) 45%, transparent\)/);
    expect(rawCss).toMatch(/\[data-peak="true"\] \.wf-cu-day-range-bar \{ background: var\(--color-topic-coastal\); \}/);
    expect(bars).toHaveLength(4);
  });

  it('inks the value: primary on the peak, secondary elsewhere and on a gone or no-forecast day', () => {
    const view = buildEntryView(entry(), '2026-10-26', null, ['2026-10-26', '2026-10-27', '2026-10-28']);
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    const val = (date) => cs(container.querySelector(`.wf-cu-day[data-date="${date}"]`)
      .closest('.wf-cu-day-col').querySelector('.wf-cu-day-range-val')).color;
    expect(val('2026-10-27')).toBe('var(--color-plex-text)');
    expect(val('2026-10-26')).toBe('var(--color-plex-text-secondary)');
    expect(val('2026-10-25')).toBe('var(--color-plex-text-secondary)');
    expect(val('2026-10-29')).toBe('var(--color-plex-text-secondary)');
  });

  it('keeps the bar’s own colour in forced-colours mode', () => {
    expect(rawCss).toMatch(/@media \(forced-colors: active\) \{[^@]*\.wf-cu-day-range-bar \{ forced-color-adjust: none; \}/);
  });

  it('pins the column and chip widths at both breakpoints', () => {
    expect(css).toMatch(/\.wf-cu-day \{[^}]*\n {2}width: 40px;/);
    expect(css).toMatch(/\.wf-cu-day-col \{\s*width: 40px;/);
    expect(css).toMatch(
      /@media \(max-width: 639px\) \{[^@]*\.wf-cu-day \{ width: 38px; \}\s*\.wf-cu-day-col \{ width: 38px; \}/,
    );
  });

  it('sizes the value label to the note and the bar track fits the column', () => {
    expect(css).toMatch(/\.wf-cu-day-range-val \{\s*font-size: 9\.5px;/);
  });
});
