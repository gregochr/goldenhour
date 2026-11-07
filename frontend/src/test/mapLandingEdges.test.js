/**
 * Edge behaviour of `utils/mapLanding.js`: malformed events, rows that carry no source row, and the
 * header's label fallbacks. Companion to mapLanding.test.js; each case pins an answer the
 * happy-path suite never asks for.
 */
import { describe, it, expect } from 'vitest';
import { betterNextWindow, landingHeader, landingRows } from '../utils/mapLanding.js';
import { EVENT_KIND } from '../utils/mapEvents.js';

const solar = (date, eventType, over = {}) => ({
  id: `solar:${date}:${eventType}`,
  kind: EVENT_KIND.SOLAR,
  eventType,
  date,
  dayLabel: 'Today',
  served: true,
  away: false,
  ...over,
});

describe('landingRows — malformed events', () => {
  it('skips null entries and keeps reading', () => {
    const events = [null, undefined, solar('2026-01-15', 'SUNSET')];
    const rows = landingRows({ events });
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ index: 2, verdict: null });
    expect(rows[0].row.id).toBe('solar:2026-01-15:SUNSET');
  });

  it('answers nothing for events that are not an array', () => {
    expect(landingRows({ events: 'solar' })).toEqual([]);
    expect(landingRows({ events: null })).toEqual([]);
  });

  it('stops at the requested count', () => {
    const events = [
      solar('2026-01-15', 'SUNSET'), solar('2026-01-16', 'SUNRISE'), solar('2026-01-16', 'SUNSET'),
    ];
    expect(landingRows({ events, count: 1 }).map((r) => r.index)).toEqual([0]);
    expect(landingRows({ events, count: 3 }).map((r) => r.index)).toEqual([0, 1, 2]);
  });
});

describe('landingHeader — rows with no source row', () => {
  it('treats two rows without a source row as the same day with no label', () => {
    expect(landingHeader([{}, {}])).toBe(' — sunset or sunset?');
  });

  it('words a same-day pair by each row\'s own event type', () => {
    const rows = [
      { row: { date: '2026-01-15', eventType: 'SUNSET', dayLabel: 'Today' } },
      { row: { date: '2026-01-15', eventType: 'SUNRISE', dayLabel: 'Today' } },
    ];
    expect(landingHeader(rows)).toBe('Today — sunset or sunrise?');
  });

  it('words a same-day pair whose first row is a sunrise', () => {
    const rows = [
      { row: { date: '2026-01-16', eventType: 'SUNRISE', dayLabel: 'Tomorrow' } },
      { row: { date: '2026-01-16', eventType: 'SUNSET', dayLabel: 'Tomorrow' } },
    ];
    expect(landingHeader(rows)).toBe('Tomorrow — sunrise or sunset?');
  });

  it('reads a second row with no source row as an empty trailing label', () => {
    expect(landingHeader([{ row: { date: '2026-01-15', dayLabel: 'Today' } }, {}])).toBe('Today, or ?');
  });

  it('falls back to the plain label when a row has no day label', () => {
    const rows = [
      { row: { date: '2026-01-15', label: 'Thu 15' } },
      { row: { date: '2026-01-16', label: 'Tonight' } },
    ];
    expect(landingHeader(rows)).toBe('Thu 15, or tonight?');
  });

  it('prefers the day label over the plain label', () => {
    const rows = [
      { row: { date: '2026-01-15', dayLabel: 'Today', label: 'Today sunset' } },
      { row: { date: '2026-01-16', dayLabel: 'Fri', label: 'Fri sunset' } },
    ];
    expect(landingHeader(rows)).toBe('Today, or Fri?');
  });
});

describe('betterNextWindow — non-array rows', () => {
  it('answers null for rows that are not an array', () => {
    expect(betterNextWindow({ rows: 'solar', activeIndex: 0 })).toBeNull();
    expect(betterNextWindow({ rows: null, activeIndex: 0 })).toBeNull();
  });
});
