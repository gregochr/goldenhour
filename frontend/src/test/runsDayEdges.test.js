import { describe, it, expect } from 'vitest';
import {
  dayTabWord, formatMs, rowCostText, rowMatchesFilter, stripBlock, stripScale,
} from '../utils/runsDay.js';
import { formatSeconds } from '../utils/runsView.js';

const NOW = new Date('2026-11-02T12:00:00Z');

describe('rowMatchesFilter — each chip keeps only its own type', () => {
  it.each([
    ['ask_ready', 'ASK_READY'],
    ['briefing', 'BRIEFING'],
    ['sky_eval', 'SKY_EVAL'],
    ['other', 'OTHER'],
  ])('%s keeps %s and drops every other type', (filter, own) => {
    const types = ['OVERNIGHT', 'ASK_READY', 'BRIEFING', 'SKY_EVAL', 'OTHER'];
    types.forEach((type) => {
      expect(rowMatchesFilter({ type, status: 'COMPLETED' }, filter)).toBe(type === own);
    });
  });
});

describe('dayTabWord', () => {
  it('says Today for today and Yesterday for the day before', () => {
    expect(dayTabWord('2026-11-02', '2026-11-02', NOW)).toBe('Today');
    expect(dayTabWord('2026-11-01', '2026-11-02', NOW)).toBe('Yesterday');
  });

  it('says the weekday and date for anything older, and for a future day', () => {
    expect(dayTabWord('2026-10-26', '2026-11-02', NOW)).toBe('Mon 26 Oct');
    expect(dayTabWord('2026-11-03', '2026-11-02', NOW)).toBe('Tue 3 Nov');
  });
});

describe('rowCostText', () => {
  it('is an em dash for an unknown cost, scheduled or not (no stray tilde)', () => {
    expect(rowCostText({ costGbp: null, status: 'SCHEDULED' })).toBe('—');
    expect(rowCostText({ costGbp: undefined, status: 'COMPLETED' })).toBe('—');
  });

  it('puts a tilde before a scheduled estimate only', () => {
    expect(rowCostText({ costGbp: 0.7, status: 'SCHEDULED' })).toBe('~£0.70');
    expect(rowCostText({ costGbp: 0.7, status: 'COMPLETED' })).toBe('£0.70');
  });
});

describe('formatMs — the one-second boundary', () => {
  it('writes 999ms in milliseconds and a full second in seconds', () => {
    expect(formatMs(999)).toBe('999ms');
    expect(formatMs(1000)).toBe(formatSeconds(1));
    expect(formatMs(1000)).not.toBe('1000ms');
  });
});

describe('stripBlock — how a block ends', () => {
  const scale = stripScale({ dayStartUtc: '2026-11-02T00:00:00Z', dayEndUtc: '2026-11-03T00:00:00Z' });
  const base = { id: 'r1', type: 'EVENING', startedAt: '2026-11-02T06:00:00Z' };

  it('falls back to a sliver when the end is unparseable', () => {
    const block = stripBlock({ ...base, status: 'FAILED', endedAt: 'not a time', durationSeconds: 3600 }, scale, scale.end);
    expect(block.x0).toBeCloseTo(0.25, 6);
    expect(block.x1).toBe(block.x0);
  });

  it('is a sliver, with real numbers, when there is no end, no running status and no duration', () => {
    const block = stripBlock({ ...base, status: 'FAILED', endedAt: null }, scale, scale.end);
    expect(Number.isNaN(block.x1)).toBe(false);
    expect(block.x1).toBe(block.x0);
    const nullDuration = stripBlock({ ...base, status: 'FAILED', endedAt: null, durationSeconds: undefined }, scale, scale.end);
    expect(nullDuration.x1).toBe(nullDuration.x0);
  });

  it('ends a scheduled row after its served duration, and a zero duration is a sliver', () => {
    const scheduled = stripBlock({ ...base, status: 'SCHEDULED', endedAt: null, durationSeconds: 3600 }, scale, scale.end);
    expect(scheduled.x1).toBeCloseTo(7 / 24, 6);
    const zero = stripBlock({ ...base, status: 'SCHEDULED', endedAt: null, durationSeconds: 0 }, scale, scale.end);
    expect(zero.x1).toBe(zero.x0);
  });
});
