import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';
import { buildEntryView } from '../utils/comingUpFeed.js';
import { labelInNameResult } from './labelInNameCheck.js';

afterEach(cleanup);

/*
 * WCAG 2.5.3 Label in Name, through axe's own rule (see labelInNameCheck.js): the Coming up day chips
 * and the Ask pick chip. axe glues sibling text and counts aria-hidden children as visible, so each
 * variant is checked as drawn.
 */

const TODAY = '2026-10-20';

/** A spring run crossing into November, so one chip draws its month (`monthWord`). */
const run = (over = {}) => ({
  id: 'spring-tide:2026-10-29:2026-11-02',
  type: 'spring-tide',
  startDate: '2026-10-29',
  endDate: '2026-11-02',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run',
  kindTag: 'Almanac',
  facts: [],
  action: { label: 'Show coastal spots for 30 Oct →', kind: 'coastal-spots', date: '2026-10-30' },
  ...over,
});

const MARKED = [
  { date: '2026-10-30', label: 'peak', family: 'coastal' },
  { date: '2026-10-31', label: 'peak', family: 'coastal' },
  { date: '2026-10-31', label: 'full moon', family: 'sun-moon' },
  { date: '2026-11-01', label: 'full moon', family: 'sun-moon' },
];

const chipsFor = (entry, today = TODAY, forecastDates = null) => {
  render(
    <WindowComingUpEntry
      entry={buildEntryView(entry, today, null, forecastDates)}
      onGoToPlan={vi.fn()}
      onShowOnMap={vi.fn()}
    />,
  );
  return screen.getAllByTestId('coming-up-day-chip');
};

const nameOf = (chips, date) => chips.find((c) => c.dataset.date === date).getAttribute('aria-label');

describe('Coming up day chips — label in name', () => {
  it.each([
    ['an unmerged run (month and peak words in one sub-line)', run(), TODAY, null],
    ['an unmerged run whose peak is on the month-word chip ("Nov · peak")', run({
      action: { label: 'x', kind: 'coastal-spots', date: '2026-11-01' },
    }), TODAY, null],
    ['a merged run with marks and a month word', run({ dayMarks: MARKED }), TODAY, null],
    ['a merged run, today, with the weekday replaced by Today', run({ dayMarks: MARKED }), '2026-10-30', null],
    ['a merged run with a gone day', run({ dayMarks: MARKED }), '2026-10-31', null],
    ['a merged run whose days have no forecast yet', run({ dayMarks: MARKED }), TODAY, ['2026-10-29']],
  ])('every chip passes axe: %s', async (_name, entry, today, forecastDates) => {
    const chips = chipsFor(entry, today, forecastDates);
    expect(chips.length).toBeGreaterThan(3);
    for (const chip of chips) {
      const result = await labelInNameResult(chip);
      expect({ date: chip.dataset.date, ...result }).toEqual({
        date: chip.dataset.date, violations: 0, passes: 1, incomplete: 0,
      });
    }
  });

  it('names a marked chip with the visible run first and the month after the marks', () => {
    const chips = chipsFor(run({ dayMarks: MARKED }));
    expect(nameOf(chips, '2026-10-30')).toBe('Show coastal spots for Fri 30, peak day, Oct');
    expect(nameOf(chips, '2026-10-31')).toBe('Show coastal spots for Sat 31, peak, full moon, Oct');
    // The month is drawn on this chip, so the name keeps "Sun 1 Nov" together.
    expect(nameOf(chips, '2026-11-01')).toBe('Show coastal spots for Sun 1 Nov, full moon');
    // No sub-line: "Thu 29" is a prefix of "Thu 29 Oct".
    expect(nameOf(chips, '2026-10-29')).toBe('Show coastal spots for Thu 29 Oct');
  });

  it('keeps the month beside the day when the chip draws it', () => {
    const chips = chipsFor(run({ action: { label: 'x', kind: 'coastal-spots', date: '2026-11-01' } }));
    expect(chips.find((c) => c.dataset.date === '2026-11-01')).toHaveTextContent('Sun 1 Nov · peak');
    expect(nameOf(chips, '2026-11-01')).toBe('Show coastal spots for Sun 1 Nov, peak day');
  });

  it('names an unmerged peak chip the same way', () => {
    const chips = chipsFor(run());
    expect(nameOf(chips, '2026-10-30')).toBe('Show coastal spots for Fri 30, peak day, Oct');
  });
});
