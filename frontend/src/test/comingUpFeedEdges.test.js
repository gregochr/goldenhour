import { describe, it, expect } from 'vitest';
import { buildChronology, buildEntryView } from '../utils/comingUpFeed.js';

/**
 * Edge behaviour of the Coming up feed's view builder that the main suite leaves unpinned: the
 * day-chip walk across served shoulder days, the default action, the passthrough fields, and an
 * unknown filter chip. Every fixture is built inside its `it`.
 */

const TODAY = '2026-08-09';

/** A wire `ComingUpEntry` for a three-day coastal run, 16-18 Aug. */
const entry = (over = {}) => ({
  id: 'spring-tide:2026-08-16:2026-08-18',
  type: 'spring-tide',
  startDate: '2026-08-16',
  endDate: '2026-08-18',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run',
  kindTag: 'Almanac',
  facts: [],
  action: { label: 'Show coastal spots for 16 Aug', kind: 'coastal-spots', date: '2026-08-16' },
  ...over,
});

const datesOf = (view) => view.dayChips.map((c) => c.date);

describe('buildChronology — a filter id that names no chip', () => {
  it('matches nothing and does not throw', () => {
    const entries = [entry(), entry({ id: 'x', family: 'night-sky' })];
    expect(buildChronology(entries, TODAY, 'no-such-chip', null, null)).toEqual([]);
  });

  it('still lists everything under All, and keeps a family chip to its own family', () => {
    const entries = [entry(), entry({ id: 'x', family: 'night-sky' })];
    expect(buildChronology(entries, TODAY, 'all', null, null)[0].entries).toHaveLength(2);
    const coastal = buildChronology(entries, TODAY, 'coastal', null, null);
    expect(coastal[0].entries.map((v) => v.id)).toEqual(['spring-tide:2026-08-16:2026-08-18']);
  });
});

describe('buildEntryView — an entry with no served action', () => {
  it('falls back to an empty label, no kind and the start date, and gets no door', () => {
    const view = buildEntryView(entry({ action: undefined }), TODAY, null, null);
    expect(view.action).toEqual({ label: '', kind: null, date: '2026-08-16' });
    expect(view.dayChips).toBeNull();
    expect(view.interactive).toBe(false);
    expect(view.doorNoun).toBeNull();
  });
});

describe('buildEntryView — served fields ride through unchanged', () => {
  it('passes superlative, metric, threshold and aside straight through', () => {
    const view = buildEntryView(entry({
      superlative: 'Biggest of the year',
      metric: { label: 'Range', value: '9.1 m' },
      threshold: 'Above 8.5 m',
      aside: 'An extra line',
      prose: 'Why it matters',
    }), TODAY, null, null);
    expect(view.superlative).toBe('Biggest of the year');
    expect(view.metric).toEqual({ label: 'Range', value: '9.1 m' });
    expect(view.threshold).toBe('Above 8.5 m');
    expect(view.aside).toBe('An extra line');
    expect(view.prose).toBe('Why it matters');
    expect(view.isFeature).toBe(true);
  });

  it('serves null for each when the wire omits them', () => {
    const view = buildEntryView(entry(), TODAY, null, null);
    expect(view.superlative).toBeNull();
    expect(view.metric).toBeNull();
    expect(view.threshold).toBeNull();
    expect(view.isFeature).toBe(false);
  });
});

describe('buildEntryView — a map entry over the chip cap keeps its single door', () => {
  it('has no door noun, no chips and a withheld note when the forecast lacks the date', () => {
    const view = buildEntryView(entry({
      startDate: '2026-08-10',
      endDate: '2026-08-30',
      action: { label: 'Show coastal spots', kind: 'coastal-spots', date: '2026-08-10' },
    }), TODAY, null, ['2026-08-11']);
    expect(view.dayChips).toBeNull();
    expect(view.doorNoun).toBeNull();
    expect(view.actionWithheld).toBe(true);
    expect(view.interactive).toBe(false);
  });
});

describe('buildEntryView — day chips', () => {
  it('chips a multi-day run whose action carries no date, with no peak day', () => {
    const view = buildEntryView(entry({
      action: { label: 'Show coastal spots', kind: 'coastal-spots' },
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-16', '2026-08-17', '2026-08-18']);
    expect(view.dayChips.some((c) => c.peak)).toBe(false);
    expect(view.doorNoun).toBe('Coastal spots');
  });

  it('gives a single dateless day no chips', () => {
    const view = buildEntryView(entry({
      endDate: '2026-08-16',
      action: { label: 'Show coastal spots', kind: 'coastal-spots' },
    }), TODAY, null, null);
    expect(view.dayChips).toBeNull();
  });

  it('walks a shoulder before and one after the run even when the wire lists the later one first', () => {
    const view = buildEntryView(entry({
      dayRanges: [
        { date: '2026-08-19', shoulder: true, rangeMetres: 6.1 },
        { date: '2026-08-16', rangeMetres: 7.2 },
        { date: '2026-08-15', shoulder: true, rangeMetres: 6.4 },
      ],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual([
      '2026-08-15', '2026-08-16', '2026-08-17', '2026-08-18', '2026-08-19',
    ]);
    expect(view.dayChips.map((c) => c.shoulder)).toEqual([true, false, false, false, true]);
  });

  it('keeps every run day when the only shoulder is after the run', () => {
    const view = buildEntryView(entry({
      dayRanges: [{ date: '2026-08-19', shoulder: true }],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-16', '2026-08-17', '2026-08-18', '2026-08-19']);
  });

  it('keeps every run day when the only shoulder is before the run', () => {
    const view = buildEntryView(entry({
      dayRanges: [{ date: '2026-08-15', shoulder: true }],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-15', '2026-08-16', '2026-08-17', '2026-08-18']);
  });

  it('skips a day between a non-adjacent shoulder and the run rather than drawing it as a run day', () => {
    const view = buildEntryView(entry({
      dayRanges: [
        { date: '2026-08-14', shoulder: true },
        { date: '2026-08-20', shoulder: true },
      ],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual([
      '2026-08-14', '2026-08-16', '2026-08-17', '2026-08-18', '2026-08-20',
    ]);
  });

  it('skips a gap day before the run when only the earlier shoulder is non-adjacent', () => {
    const view = buildEntryView(entry({
      dayRanges: [{ date: '2026-08-13', shoulder: true }],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-13', '2026-08-16', '2026-08-17', '2026-08-18']);
  });

  it('skips a gap day after the run when only the later shoulder is non-adjacent', () => {
    const view = buildEntryView(entry({
      dayRanges: [{ date: '2026-08-21', shoulder: true }],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-16', '2026-08-17', '2026-08-18', '2026-08-21']);
  });

  it('drops a shoulder before today but keeps one that falls on today', () => {
    const view = buildEntryView(entry({
      startDate: '2026-08-10',
      endDate: '2026-08-12',
      action: { label: 'x', kind: 'coastal-spots', date: '2026-08-10' },
      dayRanges: [
        { date: '2026-08-08', shoulder: true },
        { date: '2026-08-09', shoulder: true },
      ],
    }), TODAY, null, null);
    expect(datesOf(view)).toEqual(['2026-08-09', '2026-08-10', '2026-08-11', '2026-08-12']);
    expect(view.dayChips[0]).toMatchObject({ today: true, gone: false, shoulder: true, live: true });
  });
});
