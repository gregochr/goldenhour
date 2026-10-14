import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  render, screen, within, cleanup,
} from '@testing-library/react';
import WindowComingUpEntry from '../components/WindowComingUpEntry.jsx';
import {
  buildEntryView, MAX_CHIP_DAYS, MAX_MARKED_CHIP_DAYS,
} from '../utils/comingUpFeed.js';

afterEach(cleanup);

const TODAY = '2026-10-20';

/** A merged spring run (25-30 Oct, true peak 27) with a supermoon night on the 26th. */
const merged = (over = {}) => ({
  id: 'spring-tide:2026-10-25:2026-10-30',
  type: 'spring-tide',
  startDate: '2026-10-25',
  endDate: '2026-10-30',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run, on a supermoon',
  kindTag: 'Almanac',
  facts: [],
  action: { label: 'Show coastal spots for 27 Oct →', kind: 'coastal-spots', date: '2026-10-27' },
  dayMarks: [
    { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
    { date: '2026-10-27', label: 'peak', family: 'coastal' },
  ],
  ...over,
});

const unmerged = () => merged({ title: 'Spring tide run', dayMarks: undefined });

describe('buildEntryView — day marks', () => {
  it('threads each chip’s served marks by date, and an empty list for an unmarked day', () => {
    const chips = buildEntryView(merged(), TODAY, null, null).dayChips;
    expect(chips.map((c) => c.marks)).toEqual([
      [], [{ label: 'full moon', family: 'sun-moon' }], [{ label: 'peak', family: 'coastal' }], [], [], [],
    ]);
  });

  it('keeps two marks on one date in served order', () => {
    const view = buildEntryView(merged({
      dayMarks: [
        { date: '2026-10-26', label: 'peak', family: 'coastal' },
        { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
      ],
    }), TODAY, null, null);
    expect(view.dayChips[1].marks.map((m) => m.label)).toEqual(['peak', 'full moon']);
  });

  it('serves null marks on every chip of an unmerged entry, and of one with an empty list', () => {
    expect(buildEntryView(unmerged(), TODAY, null, null).dayChips.every((c) => c.marks === null)).toBe(true);
    expect(buildEntryView(merged({ dayMarks: [] }), TODAY, null, null)
      .dayChips.every((c) => c.marks === null)).toBe(true);
  });

  it('allows a marked run of 14 days but an unmarked one only 10', () => {
    const span = (days) => ({ startDate: '2026-10-25', endDate: new Date(Date.UTC(2026, 9, 25 + days - 1)).toISOString().slice(0, 10) });
    expect(MAX_MARKED_CHIP_DAYS).toBe(14);
    expect(buildEntryView(merged(span(14)), TODAY, null, null).dayChips).toHaveLength(14);
    expect(buildEntryView(merged(span(15)), TODAY, null, null).dayChips).toBeNull();
    expect(buildEntryView(unmerged(), TODAY, null, null).dayChips).toHaveLength(6);
    expect(buildEntryView({ ...unmerged(), ...span(MAX_CHIP_DAYS + 1) }, TODAY, null, null).dayChips).toBeNull();
    expect(buildEntryView({ ...unmerged(), ...span(MAX_CHIP_DAYS) }, TODAY, null, null).dayChips)
      .toHaveLength(MAX_CHIP_DAYS);
  });
});

const draw = (entry) => render(
  <WindowComingUpEntry
    entry={buildEntryView(entry, TODAY, null, null)}
    onGoToPlan={vi.fn()}
    onShowOnMap={vi.fn()}
  />,
);
const chipOn = (date) => screen.getAllByTestId('coming-up-day-chip').find((c) => c.dataset.date === date);

describe('WindowComingUpEntry — day marks', () => {
  it('draws each mark as its own family-tagged block span, with no separator text', () => {
    draw(merged({
      dayMarks: [
        { date: '2026-10-26', label: 'peak', family: 'coastal' },
        { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
      ],
    }));
    const marks = within(chipOn('2026-10-26')).getAllByText(/peak|full moon/);
    expect(marks.map((m) => [m.textContent, m.dataset.family])).toEqual([
      ['peak', 'coastal'], ['full moon', 'sun-moon'],
    ]);
    expect(within(chipOn('2026-10-26')).getByTestId('coming-up-day-chip-sub').textContent)
      .toBe('peakfull moon');
  });

  it('draws no third line on an unmarked day of a merged entry', () => {
    draw(merged());
    expect(within(chipOn('2026-10-25')).queryByTestId('coming-up-day-chip-sub')).toBeNull();
  });

  it('names the marks in the aria-label, in order', () => {
    draw(merged({
      dayMarks: [
        { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
        { date: '2026-10-27', label: 'peak', family: 'coastal' },
        { date: '2026-10-27', label: 'full moon', family: 'sun-moon' },
      ],
    }));
    expect(chipOn('2026-10-26')).toHaveAccessibleName('Show coastal spots for Mon 26 Oct, full moon');
    expect(chipOn('2026-10-27')).toHaveAccessibleName('Show coastal spots for Tue 27 Oct, peak day, full moon');
    expect(chipOn('2026-10-28')).toHaveAccessibleName('Show coastal spots for Wed 28 Oct');
  });

  it('keeps the marks in the gone and no-forecast names', () => {
    const view = buildEntryView(merged(), '2026-10-27', null, ['2026-10-27', '2026-10-28']);
    render(<WindowComingUpEntry entry={view} onGoToPlan={vi.fn()} onShowOnMap={vi.fn()} />);
    expect(chipOn('2026-10-26')).toHaveAccessibleName('Mon 26 Oct, full moon — gone');
    cleanup();
    const noForecast = buildEntryView(merged(), TODAY, null, ['2026-10-25']);
    render(<WindowComingUpEntry entry={noForecast} onGoToPlan={vi.fn()} onShowOnMap={vi.fn()} />);
    expect(chipOn('2026-10-27')).toHaveAccessibleName('Tue 27 Oct, peak day — no forecast yet');
  });

  it('marks the group data-marked only on a merged entry', () => {
    const { unmount } = draw(merged());
    expect(screen.getByTestId('coming-up-day-chips')).toHaveAttribute('data-marked', 'true');
    unmount();
    draw(unmerged());
    expect(screen.getByTestId('coming-up-day-chips')).not.toHaveAttribute('data-marked');
  });

  it('shows no "peak" anywhere on a merged partial-coverage run with no served peak mark', () => {
    // The action date is the run's figures day; with no true peak there is no peak mark.
    draw(merged({ dayMarks: [{ date: '2026-10-26', label: 'full moon', family: 'sun-moon' }] }));
    expect(screen.getByTestId('coming-up-day-chips')).not.toHaveTextContent(/peak/i);
    screen.getAllByTestId('coming-up-day-chip').forEach((c) => {
      expect(c.getAttribute('aria-label')).not.toMatch(/peak/i);
    });
  });

  it('leaves an unmerged run byte-identical: the peak word and ", peak day" come from the action date', () => {
    draw(unmerged());
    const peak = chipOn('2026-10-27');
    expect(within(peak).getByTestId('coming-up-day-chip-sub').textContent).toBe('peak');
    expect(peak).toHaveAccessibleName('Show coastal spots for Tue 27 Oct, peak day');
    expect(peak).toHaveAttribute('data-peak', 'true');
  });

  it('puts a month word first on a marked chip that starts a new month', () => {
    draw(merged({
      startDate: '2026-10-30',
      endDate: '2026-11-03',
      dayMarks: [{ date: '2026-11-01', label: 'full moon', family: 'sun-moon' }],
      action: { label: 'x', kind: 'coastal-spots', date: '2026-11-01' },
    }));
    expect(within(chipOn('2026-11-01')).getByTestId('coming-up-day-chip-sub').textContent)
      .toBe('Novfull moon');
  });

  it('styles no chip as the peak on a merged partial-coverage run, though the action date sits on one', () => {
    draw(merged({ dayMarks: [{ date: '2026-10-26', label: 'full moon', family: 'sun-moon' }] }));
    screen.getAllByTestId('coming-up-day-chip').forEach((c) => {
      expect(c).toHaveAttribute('data-peak', 'false');
      expect(c).not.toHaveAttribute('data-peak-family');
    });
  });

  it('puts the peak styling on the tide’s day, not the moon night, when a supermoon hosts', () => {
    draw(merged({
      type: 'supermoon',
      family: 'sun-moon',
      title: 'Supermoon, on a spring tide run',
      action: { label: 'Show dark-sky spots →', kind: 'coastal-spots', date: '2026-10-26' },
      dayMarks: [
        { date: '2026-10-26', label: 'full moon', family: 'sun-moon' },
        { date: '2026-10-27', label: 'peak', family: 'coastal' },
      ],
    }));
    expect(chipOn('2026-10-26')).toHaveAttribute('data-peak', 'false');
    expect(chipOn('2026-10-27')).toHaveAttribute('data-peak', 'true');
    expect(chipOn('2026-10-27')).toHaveAttribute('data-peak-family', 'coastal');
  });
});
