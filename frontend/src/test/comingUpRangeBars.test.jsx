import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  describe, it, expect, afterEach, beforeAll, afterAll, vi,
} from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';
import { buildEntryView, rangeBarHeight } from '../utils/comingUpFeed.js';

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
    { date: '2026-10-25', rangeMetres: 4.0, peak: false, rangePercentile: 12 },
    { date: '2026-10-26', rangeMetres: 4.5, peak: false, rangePercentile: 5 },
    { date: '2026-10-27', rangeMetres: 5.0, peak: true, rangePercentile: 2 },
    { date: '2026-10-29', rangeMetres: 4.0, peak: false, rangePercentile: 12 },
  ],
  rangeAverageMetres: 3.4,
  ...over,
});
const withoutPercentiles = () => entry().dayRanges.map(({ date, rangeMetres, peak }) => ({ date, rangeMetres, peak }));

const draw = (e, today = TODAY, dates = null) => render(
  <WindowComingUpEntry entry={buildEntryView(e, today, null, dates)} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
);
const cols = (c) => [...c.querySelectorAll('.wf-cu-day-col')];
const barHeights = (c) => cols(c).map((col) => {
  const bar = col.querySelector('.wf-cu-day-range-bar');
  return bar ? bar.style.getPropertyValue('--wf-cu-bar-h') : null;
});
const avgH = (c) => c.querySelector('.wf-cu-days').style.getPropertyValue('--wf-cu-avg-h');

describe('buildEntryView — range scale', () => {
  it('threads served ranges, percentiles and the average, scaling over run and average', () => {
    const view = buildEntryView(entry(), TODAY, null, null);
    expect(view.dayChips.map((c) => c.rangeMetres)).toEqual([4.0, 4.5, 5.0, null, 4.0, null]);
    expect(view.dayChips.map((c) => c.rangePercentile)).toEqual([12, 5, 2, null, 12, null]);
    expect(view.dayChips.map((c) => c.rangePeak)).toEqual([false, false, true, false, false, false]);
    expect(view.rangeAverage).toBe(3.4);
    expect(view.rangeScale).toEqual({
      min: 4.0, max: 5.0, lo: 3.4, hi: 5.0, hasPercentiles: true,
    });
  });

  it('reads percentiles as all-or-none and the average as optional', () => {
    const view = buildEntryView(
      entry({ dayRanges: withoutPercentiles(), rangeAverageMetres: undefined }), TODAY, null, null,
    );
    expect(view.rangeScale).toEqual({
      min: 4.0, max: 5.0, lo: 4.0, hi: 5.0, hasPercentiles: false,
    });
    expect(view.rangeAverage).toBeNull();
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

describe('rangeBarHeight', () => {
  const scale = (lo, hi) => ({ lo, hi });
  it('maps lo..hi onto 13..32px, rounded', () => {
    expect(rangeBarHeight(3.4, scale(3.4, 5.0))).toBe(13);
    expect(rangeBarHeight(5.0, scale(3.4, 5.0))).toBe(32);
    expect(rangeBarHeight(4.5, scale(3.4, 5.0))).toBe(26);
  });
  it('is 18px when the scale is flat', () => {
    expect(rangeBarHeight(4, scale(4, 4))).toBe(18);
  });
});

describe('Coming up range bars', () => {
  it('sizes bars 13..32px over run and average; a missing day has no bar', () => {
    const { container } = draw(entry());
    expect(barHeights(container)).toEqual(['20px', '26px', '32px', null, '20px', null]);
    expect(avgH(container)).toBe('13px');
  });

  it('places the average line inside the run (4.5 over 4.0-5.0) and above it (5.6)', () => {
    const inside = draw(entry({ rangeAverageMetres: 4.5 }));
    expect(avgH(inside.container)).toBe('23px');
    expect(barHeights(inside.container)).toEqual(['13px', '23px', '32px', null, '13px', null]);
    cleanup();
    const above = draw(entry({ rangeAverageMetres: 5.6 }));
    expect(avgH(above.container)).toBe('32px');
    expect(barHeights(above.container)[2]).toBe('25px');
  });

  it('draws 18px bars when every value, and the average, are equal', () => {
    const { container } = draw(entry({
      dayRanges: [
        { date: '2026-10-25', rangeMetres: 4.0, peak: false, rangePercentile: 40 },
        { date: '2026-10-26', rangeMetres: 4.0, peak: true, rangePercentile: 40 },
      ],
      rangeAverageMetres: 4.0,
    }));
    expect(barHeights(container).filter(Boolean)).toEqual(['18px', '18px']);
    expect(avgH(container)).toBe('18px');
  });

  it('prints top N% above the bar and the metres under the chip, empty for a day with none', () => {
    const { container } = draw(entry());
    expect(cols(container).map((c) => c.querySelector('.wf-cu-day-range-label').textContent))
      .toEqual(['top 12%', 'top 5%', 'top 2%', '', 'top 12%', '']);
    expect(cols(container).map((c) => c.querySelector('.wf-cu-day-range-metres').textContent))
      .toEqual(['4.0\u00A0m', '4.5\u00A0m', '5.0\u00A0m', '', '4.0\u00A0m', '']);
    cols(container).forEach((col) => {
      const kids = [...col.children];
      expect(kids.map((k) => k.className.split(' ')[0])).toEqual([
        'wf-cu-day-range-label', 'wf-cu-day-range-track', 'wf-cu-day', 'wf-cu-day-range-metres',
      ]);
    });
  });

  it('tiers: peak from the served flag, big at <= 5%, rest otherwise; big never on the peak', () => {
    const { container } = draw(entry());
    expect(cols(container).map((c) => c.getAttribute('data-tier')))
      .toEqual(['rest', 'big', 'peak', null, 'rest', null]);
    cleanup();
    // The peak at 3% stays "peak", not "big".
    const both = draw(entry({
      dayRanges: [
        { date: '2026-10-26', rangeMetres: 4.5, peak: false, rangePercentile: 6 },
        { date: '2026-10-27', rangeMetres: 5.0, peak: true, rangePercentile: 3 },
      ],
    }));
    expect(cols(both.container).map((c) => c.getAttribute('data-tier')).filter(Boolean))
      .toEqual(['rest', 'peak']);
  });

  it('draws no label row and no big tier without percentiles', () => {
    const { container } = draw(entry({ dayRanges: withoutPercentiles() }));
    expect(container.querySelector('.wf-cu-day-range-label')).toBeNull();
    expect(container.querySelector('.wf-cu-days')).not.toHaveAttribute('data-labelled');
    expect(cols(container).map((c) => c.getAttribute('data-tier')))
      .toEqual(['rest', 'rest', 'peak', null, 'rest', null]);
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
    expect(screen.queryByRole('button', { name: /percent of the year/ })).toBeNull();
  });

  it('draws no line and no AVG when the average is null', () => {
    const { container } = draw(entry({ rangeAverageMetres: undefined }));
    expect(container.querySelector('.wf-cu-days')).not.toHaveAttribute('data-avg');
    expect(avgH(container)).toBe('');
    expect(container.querySelector('.wf-cu-days-axis-avg')).toBeNull();
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
    expect(container.querySelector('.wf-cu-days-axis-range')).not.toBeNull();
  });

  it('puts the aria-hidden axis first in the group and prints no key', () => {
    const { container } = draw(entry());
    const group = container.querySelector('[role="group"]');
    expect(group.firstElementChild).toBe(screen.getByTestId('coming-up-range-axis'));
    expect(group.firstElementChild).toHaveAttribute('aria-hidden', 'true');
    expect(group.firstElementChild.textContent).toBe('AVGrange');
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
    expect(container).not.toHaveTextContent('Bars show each day');
    expect(container).not.toHaveTextContent('Dashed line is the average');
    // The bars and their printed metres are untouched.
    expect(container.querySelectorAll('[data-testid="coming-up-range-bar"]').length).toBeGreaterThan(0);
  });

  it('hides only the label, track, metres and axis; every chip button is found by name', () => {
    const { container } = draw(entry());
    cols(container).forEach((col) => {
      expect(col).not.toHaveAttribute('aria-hidden');
      ['.wf-cu-day-range-label', '.wf-cu-day-range-track', '.wf-cu-day-range-metres'].forEach((s) => {
        expect(col.querySelector(s)).toHaveAttribute('aria-hidden', 'true');
      });
    });
    expect(screen.getAllByRole('button')).toHaveLength(6);
    expect(screen.getByRole('button', {
      name: 'Show coastal spots for Tue 27 Oct, peak day, 5.0 metre range, top 2 percent of the year',
    })).toBeInTheDocument();
    expect(screen.getByRole('button', {
      name: 'Show coastal spots for Mon 26 Oct, full moon, 4.5 metre range, top 5 percent of the year',
    })).toBeInTheDocument();
  });

  it('titles a column with the range and the multiple of the average only when there is one', () => {
    const { container } = draw(entry());
    expect(cols(container).map((c) => c.getAttribute('title'))).toEqual([
      '4.0 m range · 1.2× average', '4.5 m range · 1.3× average',
      '5.0 m range · 1.5× average', null, '4.0 m range · 1.2× average', null,
    ]);
    cleanup();
    const none = draw(entry({ rangeAverageMetres: undefined }));
    expect(cols(none.container)[0].getAttribute('title')).toBe('4.0 m range');
  });

  it('treats one missing percentile as none at all: no label row, no percent in any name', () => {
    const ranges = entry().dayRanges.map((r, i) => (i === 1 ? { ...r, rangePercentile: undefined } : r));
    const { container } = draw(entry({ dayRanges: ranges }));
    expect(container.querySelector('.wf-cu-day-range-label')).toBeNull();
    expect(screen.queryByRole('button', { name: /percent of the year/ })).toBeNull();
    expect(cols(container).map((c) => c.getAttribute('data-tier')))
      .toEqual(['rest', 'rest', 'peak', null, 'rest', null]);
  });

  it('adds no multiple to the title when the average is 0', () => {
    const { container } = draw(entry({ rangeAverageMetres: 0 }));
    expect(cols(container)[0].getAttribute('title')).toBe('4.0 m range');
  });

  it('draws a ranged row’s dimmed note under the row; an unranged one stays inside', () => {
    const view = buildEntryView(entry(), '2026-10-26', null, ['2026-10-26', '2026-10-27', '2026-10-28']);
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    const row = container.querySelector('.wf-cu-days');
    const note = screen.getByTestId('coming-up-day-chips-note');
    expect(row.contains(note)).toBe(false);
    expect(note.previousElementSibling).toBe(row);
    expect(note.nextElementSibling).toBeNull();
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
    cleanup();
    const plain = buildEntryView(
      entry({ dayRanges: undefined, rangeAverageMetres: undefined }),
      '2026-10-26', null, ['2026-10-26', '2026-10-27', '2026-10-28'],
    );
    const { container: c2 } = render(
      <WindowComingUpEntry entry={plain} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    expect(c2.querySelector('.wf-cu-days').contains(screen.getByTestId('coming-up-day-chips-note'))).toBe(true);
  });

  it('follows the served range peak on an UNMERGED run whose door date differs', () => {
    const { container } = draw(entry({ dayMarks: undefined }));
    expect(container.querySelector('.wf-cu-day[data-peak="true"]').dataset.date).toBe('2026-10-26');
    expect(container.querySelector('.wf-cu-day-col[data-tier="peak"] .wf-cu-day').dataset.date)
      .toBe('2026-10-27');
  });

  it('carries the range clause in the gone and no-forecast names; a gone peak is still a peak tier', () => {
    const view = buildEntryView(entry(), '2026-10-28', null, ['2026-10-28', '2026-10-29', '2026-10-30']);
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    expect(screen.getByRole('button', {
      name: 'Sun 25 Oct, 4.0 metre range, top 12 percent of the year — gone',
    })).toBeInTheDocument();
    const peak = container.querySelector('.wf-cu-day-col[data-tier="peak"]');
    expect(peak).toHaveAttribute('data-live', 'false');
    expect(peak.querySelector('.wf-cu-day-range-pill').textContent).toBe('top 2%');
  });

  it('draws no columns or axis with fewer than two served values', () => {
    const { container } = draw(entry({ dayRanges: [{ date: '2026-10-27', rangeMetres: 5, peak: true }] }));
    expect(container.querySelector('.wf-cu-day-col')).toBeNull();
    expect(container.querySelector('.wf-cu-days')).not.toHaveAttribute('data-ranged');
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
    expect(screen.queryByTestId('coming-up-range-axis')).toBeNull();
    expect(screen.queryByRole('button', { name: /metre range/ })).toBeNull();
  });

  it('draws nothing ranged on an entry with no dayRanges', () => {
    const { container } = draw(entry({ dayRanges: undefined, rangeAverageMetres: undefined }));
    expect(container.querySelector('.wf-cu-day-col')).toBeNull();
    expect(container.querySelector('.wf-cu-days')).not.toHaveAttribute('data-ranged');
    expect(screen.queryByTestId('coming-up-range-key')).toBeNull();
  });
});

/**
 * A spring run 8-10 Nov with a near-miss shoulder day each side (7 and 11 Nov), as the server
 * serves it: `shoulder: true` on those two ranges only (tide-source-integrity-plan.md P3).
 */
const shoulderEntry = (over = {}) => ({
  id: 'spring-tide:2026-11-08:2026-11-10',
  type: 'spring-tide',
  startDate: '2026-11-08',
  endDate: '2026-11-10',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run',
  kindTag: 'Almanac',
  facts: [],
  action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: '2026-11-09' },
  dayRanges: [
    { date: '2026-11-07', rangeMetres: 4.3, peak: false, rangePercentile: 9, shoulder: true },
    { date: '2026-11-08', rangeMetres: 4.6, peak: false, rangePercentile: 4 },
    { date: '2026-11-09', rangeMetres: 5.0, peak: true, rangePercentile: 2 },
    { date: '2026-11-10', rangeMetres: 4.7, peak: false, rangePercentile: 3 },
    { date: '2026-11-11', rangeMetres: 4.4, peak: false, rangePercentile: 8, shoulder: true },
  ],
  rangeAverageMetres: 3.4,
  ...over,
});

describe('shoulder days beside a tide run', () => {
  const NOV = '2026-11-01';

  it('walks the extended span, flags the shoulders and leaves the run days unflagged', () => {
    const view = buildEntryView(shoulderEntry(), NOV, null, null);
    expect(view.dayChips.map((c) => c.date)).toEqual([
      '2026-11-07', '2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11',
    ]);
    expect(view.dayChips.map((c) => c.shoulder)).toEqual([true, false, false, false, true]);
    // Only the run days carry the action date's peak; the shoulders are never it.
    expect(view.dayChips.filter((c) => c.peak).map((c) => c.date)).toEqual(['2026-11-09']);
  });

  it('puts the month word on the right chip when a shoulder crosses a month boundary', () => {
    const view = buildEntryView(shoulderEntry({
      id: 'spring-tide:2026-10-30:2026-10-31',
      startDate: '2026-10-30',
      endDate: '2026-10-31',
      action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: '2026-10-30' },
      dayRanges: [
        { date: '2026-10-29', rangeMetres: 4.2, peak: false, rangePercentile: 9, shoulder: true },
        { date: '2026-10-30', rangeMetres: 4.8, peak: true, rangePercentile: 3 },
        { date: '2026-10-31', rangeMetres: 4.6, peak: false, rangePercentile: 4 },
        { date: '2026-11-01', rangeMetres: 4.3, peak: false, rangePercentile: 8, shoulder: true },
      ],
    }), '2026-10-20', null, null);
    expect(view.dayChips.map((c) => c.monthWord)).toEqual([null, null, null, 'Nov']);
    expect(view.dayChips[3].dateLabel).toBe('Sun 1 Nov');
  });

  it('drops a shoulder before today while the run is in progress, and keeps the one after', () => {
    const view = buildEntryView(shoulderEntry(), '2026-11-08', null, null);
    expect(view.dayChips.map((c) => c.date)).toEqual([
      '2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11',
    ]);
    expect(view.dayChips.some((c) => c.gone && c.shoulder)).toBe(false);
  });

  it('counts only run days against the chip caps', () => {
    const days = Array.from({ length: 10 }, (_, i) => `2026-11-${String(8 + i).padStart(2, '0')}`);
    const ranges = [
      { date: '2026-11-07', rangeMetres: 4.0, peak: false, shoulder: true },
      ...days.map((date, i) => ({ date, rangeMetres: 4.5 + i * 0.01, peak: i === 4 })),
      { date: '2026-11-18', rangeMetres: 4.0, peak: false, shoulder: true },
    ];
    const ten = buildEntryView(shoulderEntry({
      startDate: days[0],
      endDate: days[9],
      action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: days[4] },
      dayRanges: ranges,
    }), NOV, null, null);
    // Ten run days is the cap; the two shoulders ride on top of it.
    expect(ten.dayChips).toHaveLength(12);
    const eleven = buildEntryView(shoulderEntry({
      startDate: days[0],
      endDate: '2026-11-18',
      action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: days[4] },
      dayRanges: ranges,
    }), NOV, null, null);
    expect(eleven.dayChips).toBeNull();
  });

  it('includes the shoulders in the range scale', () => {
    const tall = shoulderEntry().dayRanges.map((r) => (r.date === '2026-11-07' ? { ...r, rangeMetres: 5.4 } : r));
    const view = buildEntryView(shoulderEntry({ dayRanges: tall }), NOV, null, null);
    expect(view.rangeScale).toMatchObject({ min: 4.4, max: 5.4, hasPercentiles: true });
  });

  it('draws a shoulder as an outlined, unranked, un-peaked column with its own names', () => {
    const { container } = draw(shoulderEntry(), NOV);
    const columns = cols(container);
    expect(columns.map((c) => c.getAttribute('data-tier')))
      .toEqual(['shoulder', 'big', 'peak', 'big', 'shoulder']);
    expect(columns.map((c) => c.getAttribute('data-shoulder'))).toEqual(['true', null, null, null, 'true']);
    const shoulder = columns[0];
    // No "top N%" pill, and the label row is kept so the bars stay level with the run's.
    expect(shoulder.querySelector('.wf-cu-day-range-label').textContent).toBe('');
    expect(shoulder.querySelector('.wf-cu-day-range-pill')).toBeNull();
    expect(shoulder.querySelector('.wf-cu-day').getAttribute('data-peak')).toBe('false');
    expect(shoulder.querySelector('.wf-cu-day-range-metres').textContent).toBe('4.3 m');
    expect(shoulder.getAttribute('title')).toBe('4.3 m range · just short of spring size');
    expect(screen.getByRole('button', {
      name: 'Show coastal spots for Sat 7 Nov, 4.3 metre range, just short of spring size',
    })).toBeInTheDocument();
    // The run's own days keep their rank in the name and the multiple in the tooltip.
    expect(columns[2].getAttribute('title')).toBe('5.0 m range · 1.5× average');
    expect(screen.getByRole('button', {
      name: 'Show coastal spots for Mon 9 Nov, peak day, 5.0 metre range, top 2 percent of the year',
    })).toBeInTheDocument();
    expect(container.querySelectorAll('.wf-cu-day-range-pill')).toHaveLength(3);
  });

  it('says what the outline means in the row note, only while a shoulder is shown', () => {
    draw(shoulderEntry(), NOV);
    expect(screen.getByTestId('coming-up-day-chips-note').textContent)
      .toBe('outlined · just short of spring');
    cleanup();
    // The shoulder before today is dropped, the one after stays, so the note stays.
    draw(shoulderEntry(), '2026-11-08');
    expect(screen.getByTestId('coming-up-day-chips-note')).toBeInTheDocument();
    cleanup();
    draw(shoulderEntry({ dayRanges: shoulderEntry().dayRanges.map((r) => ({ ...r, shoulder: undefined })) }), NOV);
    expect(screen.queryByTestId('coming-up-day-chips-note')).toBeNull();
  });

  it('joins the outline note to the dimmed note when a day is also dimmed', () => {
    draw(shoulderEntry(), NOV, ['2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11']);
    expect(screen.getByTestId('coming-up-day-chips-note').textContent)
      .toBe('dimmed · no forecast yet — outlined · just short of spring');
  });

  it('keeps a dimmed shoulder dimmed in its own way: live false still applies', () => {
    const { container } = draw(shoulderEntry(), NOV, ['2026-11-08', '2026-11-09', '2026-11-10']);
    const columns = cols(container);
    expect(columns[0]).toHaveAttribute('data-live', 'false');
    expect(columns[0]).toHaveAttribute('data-tier', 'shoulder');
    expect(screen.getByRole('button', {
      name: 'Sat 7 Nov, 4.3 metre range, just short of spring size — no forecast yet',
    })).toBeInTheDocument();
  });

  it('keeps the full-moon mark and its colour family on a merged supermoon shoulder chip', () => {
    const { container } = draw(shoulderEntry({
      title: 'Spring tide run, on a supermoon',
      dayMarks: [
        { date: '2026-11-07', label: 'full moon', family: 'sun-moon' },
        { date: '2026-11-09', label: 'peak', family: 'coastal' },
      ],
    }), NOV);
    const column = cols(container)[0];
    expect(column).toHaveAttribute('data-tier', 'shoulder');
    const mark = column.querySelector('.wf-cu-day-mark');
    expect(mark.textContent).toBe('full moon');
    expect(mark).toHaveAttribute('data-family', 'sun-moon');
    expect(screen.getByRole('button', {
      name: 'Show coastal spots for Sat 7 Nov, full moon, 4.3 metre range, just short of spring size',
    })).toBeInTheDocument();
  });

  it('trusts a served shoulder inside a merged card’s union span, and names it in the crumb', () => {
    const onShowOnMap = vi.fn();
    const e = shoulderEntry({
      title: 'Spring tide run, on a supermoon',
      startDate: '2026-11-07',
      dayMarks: [
        { date: '2026-11-07', label: 'full moon', family: 'sun-moon' },
        { date: '2026-11-09', label: 'peak', family: 'coastal' },
      ],
    });
    const view = buildEntryView(e, NOV, null, null);
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={onShowOnMap} />,
    );
    const first = cols(container)[0];
    expect(first).toHaveAttribute('data-tier', 'shoulder');
    expect(first.querySelector('.wf-cu-day-range-pill')).toBeNull();
    expect(first.querySelector('.wf-cu-day-mark')).toHaveAttribute('data-family', 'sun-moon');
    const name = first.querySelector('.wf-cu-day').getAttribute('aria-label');
    expect(name).toContain('just short of spring size');
    expect(name).not.toMatch(/percent of the year/);
    expect(screen.getByTestId('coming-up-day-chips-note').textContent)
      .toBe('outlined · just short of spring');
    first.querySelector('.wf-cu-day').click();
    expect(onShowOnMap).toHaveBeenCalledWith(expect.objectContaining({
      date: '2026-11-07', label: 'Spring tide run, on a supermoon · Sat 7 Nov · just short of spring',
    }));
  });

  it('draws the outline with no fill and no glow in the stylesheet', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const rule = css.match(/\.wf-cu-day-col\[data-tier="shoulder"\] \.wf-cu-day-range-bar \{([^}]*)\}/);
    expect(rule).not.toBeNull();
    expect(rule[1]).toMatch(/background: transparent;/);
    expect(rule[1]).toMatch(/border: 1px solid/);
    expect(rule[1]).not.toMatch(/box-shadow/);
    // Nothing recolours the mark by tier.
    expect(css).not.toMatch(/data-tier="shoulder"\][^{]*\.wf-cu-day-mark/);
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
          if (/\.wf-cu-day-(col|range)|\.wf-cu-day \{|\.wf-cu-days\[data-ranged/.test(`${sel} {`)
            && !sel.startsWith('@')) {
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

  it('keeps the label-block variable for the axis and leaves the ranged row unaligned', () => {
    expect(css).not.toMatch(/\.wf-cu-days\[data-ranged="true"\] \{ align-items: stretch; \}/);
    expect(css).toMatch(
      /\[data-labelled="true"\] \{\s*--wf-cu-label-block: calc\(var\(--wf-cu-label-h\) \+ var\(--wf-cu-range-gap\)\);/,
    );
    expect(css).toMatch(/--wf-cu-label-block: 0px;/);
    // Nothing outside the min-width block aligns the ranged row (the phone stack is unchanged).
    expect(css.replace(/@media \(min-width: 640px\) \{[\s\S]*?\n\}\n/, '')).not.toMatch(
      /\.wf-cu-days\[data-ranged="true"\][^{]*\{[^}]*align-items/,
    );
    const { container } = draw(entry());
    expect(container.querySelector('.wf-cu-days')).toHaveAttribute('data-ranged', 'true');
  });

  it('fixes the row heights and the track at 32px', () => {
    expect(css).toMatch(/--wf-cu-track-h: 34px;/);
    expect(css).toMatch(/--wf-cu-label-h: 13px;/);
    expect(css).toMatch(/\.wf-cu-day-range-label \{\s*height: var\(--wf-cu-label-h\);/);
    expect(css).toMatch(/\.wf-cu-day-range-metres \{\s*height: var\(--wf-cu-metres-h\);/);
  });

  it('pins the pill: padding 1px 3px, nowrap labels that may overhang, no column growth', () => {
    expect(css).toMatch(/\.wf-cu-day-range-pill \{[^}]*padding: 1px 3px;/);
    expect(css).toMatch(/\.wf-cu-day-range-label \{[^}]*white-space: nowrap;/);
    expect(css).not.toMatch(/\.wf-cu-day-range-label \{[^}]*overflow: hidden/);
    expect(css).toMatch(/\.wf-cu-day \{[^}]*\n {2}width: 40px;/);
    expect(css).toMatch(/\.wf-cu-day-col \{\s*width: 40px;/);
    expect(css).toMatch(
      /@media \(max-width: 639px\) \{[^@]*\.wf-cu-day \{ width: 38px; \}\s*\.wf-cu-day-col \{ width: 38px; \}/,
    );
  });

  it('resolves tier colours: peak solid and primary, big coastal label, rest secondary', () => {
    const { container } = draw(entry());
    const col = (tier) => container.querySelector(`.wf-cu-day-col[data-tier="${tier}"]`);
    expect(cs(col('peak').querySelector('.wf-cu-day-range-metres')).color).toBe('var(--color-plex-text)');
    expect(cs(col('peak').querySelector('.wf-cu-day-range-pill')).color).toBe('rgb(14, 11, 9)');
    expect(cs(col('big').querySelector('.wf-cu-day-range-label')).color).toBe('var(--color-topic-coastal)');
    expect(cs(col('rest').querySelector('.wf-cu-day-range-label')).color)
      .toBe('var(--color-plex-text-secondary)');
    expect(cs(col('rest').querySelector('.wf-cu-day-range-metres')).color)
      .toBe('var(--color-plex-text-secondary)');
    expect(rawCss).toMatch(/\.wf-cu-day-range-bar \{[^}]*color-mix\(in oklch, var\(--color-topic-coastal\) 45%, transparent\)/);
    expect(rawCss).toMatch(/\[data-tier="big"\] \.wf-cu-day-range-bar \{\s*background: color-mix\(in oklch, var\(--color-topic-coastal\) 80%/);
    expect(rawCss).toMatch(/\[data-tier="peak"\] \.wf-cu-day-range-bar \{\s*width: 22px;[^}]*box-shadow: 0 0 10px color-mix\(in oklch, var\(--color-topic-coastal\) 55%/);
  });

  it('turns a gone or no-forecast peak into an outline pill with secondary metres', () => {
    const view = buildEntryView(entry(), '2026-10-28', null, ['2026-10-28', '2026-10-29', '2026-10-30']);
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    const peak = container.querySelector('.wf-cu-day-col[data-tier="peak"]');
    const pill = cs(peak.querySelector('.wf-cu-day-range-pill'));
    expect(pill.backgroundColor).toBe('rgba(0, 0, 0, 0)');
    expect(pill.color).toBe('var(--color-plex-text-secondary)');
    expect(pill.boxShadow).toMatch(/inset 0 0 0 1px/);
    expect(cs(peak.querySelector('.wf-cu-day-range-metres')).color).toBe('var(--color-plex-text-secondary)');
  });

  it('mutes a gone big label and stops a gone peak bar glowing', () => {
    expect(css).toMatch(
      /\.wf-cu-day-col\[data-tier="big"\]\[data-live="false"\] \.wf-cu-day-range-label \{\s*color: var\(--color-plex-text-secondary\);/,
    );
    expect(css).toMatch(
      /\.wf-cu-day-col\[data-tier="peak"\]\[data-live="false"\] \.wf-cu-day-range-bar \{ box-shadow: none; \}/,
    );
    const view = buildEntryView(
      entry({
        dayRanges: [
          { date: '2026-10-26', rangeMetres: 4.5, peak: false, rangePercentile: 4 },
          { date: '2026-10-27', rangeMetres: 5.0, peak: true, rangePercentile: 2 },
        ],
      }),
      '2026-10-27', null, ['2026-10-27'],
    );
    const { container } = render(
      <WindowComingUpEntry entry={view} onGoToPlan={() => {}} onShowOnMap={() => {}} />,
    );
    const big = container.querySelector('.wf-cu-day-col[data-tier="big"]');
    expect(big).toHaveAttribute('data-live', 'false');
    expect(cs(big.querySelector('.wf-cu-day-range-label')).color).toBe('var(--color-plex-text-secondary)');
    const peak = container.querySelector('.wf-cu-day-col[data-tier="peak"]');
    expect(peak).toHaveAttribute('data-live', 'true');
    expect(cs(peak.querySelector('.wf-cu-day-range-bar')).boxShadow).toMatch(/0 0 10px/);
  });

  it('makes a ranged lead a full-width heading above the row from 640px; no centring rules remain', () => {
    expect(css).toMatch(
      /@media \(min-width: 640px\) \{\s*\.wf-cu-days\[data-ranged="true"\] > \.wf-cu-days-lead \{ flex-basis: 100%; padding: 0; display: block; \}/,
    );
    expect(css).not.toMatch(/max-width: 899px\) \{\s*\.wf-cu-days/);
    expect(css).not.toMatch(/\[data-ranged="true"\] > \.wf-cu-days-note/);
  });

  it('draws the average per column under the bar, dashed, and GrayText in forced colours', () => {
    expect(css).toMatch(
      /\.wf-cu-days\[data-avg="true"\] \.wf-cu-day-range-track::before \{[^}]*z-index: -1;[^}]*right: -6px;[^}]*bottom: var\(--wf-cu-avg-h\);[^}]*border-top: 1px dashed/,
    );
    expect(css).toMatch(/\.wf-cu-day-range-track \{[^}]*isolation: isolate;/);
    expect(rawCss).toMatch(
      /@media \(forced-colors: active\) \{[\s\S]*?\.wf-cu-day-range-track::before \{ border-top-color: GrayText; \}/,
    );
    expect(rawCss).toMatch(/\.wf-cu-day-range-bar \{ forced-color-adjust: none; \}/);
    expect(rawCss).toMatch(/\.wf-cu-day-col\[data-tier="peak"\] .wf-cu-day-range-pill \{ outline: 1px solid CanvasText;/);
  });

  it('shows the axis only from 640px', () => {
    expect(css).toMatch(/\.wf-cu-days-axis \{ display: none; \}/);
    expect(css).toMatch(/@media \(min-width: 640px\) \{[\s\S]*?\.wf-cu-days-axis \{\s*display: block;/);
  });
});
