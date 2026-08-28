import { describe, it, expect } from 'vitest';
import { buildHandoff, lastPlanDateStr, PLAN_OWNED_DAYS } from '../utils/comingUpHandoff.js';

const TODAY = '2026-08-08';

describe('lastPlanDateStr', () => {
  it('is three days ahead of today — a four-day window', () => {
    expect(lastPlanDateStr(TODAY)).toBe('2026-08-11');
  });

  it('agrees with PLAN_OWNED_DAYS', () => {
    expect(PLAN_OWNED_DAYS).toBe(4);
  });

  it('crosses a month boundary correctly', () => {
    expect(lastPlanDateStr('2026-08-30')).toBe('2026-09-02');
  });
});

describe('buildHandoff — degraded states', () => {
  it('degrades to the label-only row when hotTopics has not arrived (null)', () => {
    const result = buildHandoff(TODAY, null);
    expect(result.summary).toBeNull();
    expect(result.topics).toEqual([]);
    expect(result.windowLabel).toContain('Now —');
  });

  it('degrades to the label-only row when hotTopics is undefined', () => {
    const result = buildHandoff(TODAY, undefined);
    expect(result.summary).toBeNull();
  });

  it('renders nothing useful when todayStr itself is not yet known', () => {
    const result = buildHandoff('', []);
    expect(result).toEqual({ windowLabel: '', summary: null, topics: [] });
  });
});

describe('buildHandoff — an arrived, empty topic list', () => {
  it('says so explicitly rather than looking the same as "not arrived yet"', () => {
    const result = buildHandoff(TODAY, []);
    expect(result.summary).toBe('No topics live on those four days');
    expect(result.topics).toEqual([]);
  });
});

describe('buildHandoff — topic filtering, de-duping and naming', () => {
  it('counts one topic and uses singular wording', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-09' },
    ]);
    expect(result.summary).toBe('One topic lives on those four days');
    expect(result.topics).toEqual([
      { type: 'DUST', name: 'Saharan dust', color: '#f97316' },
    ]);
  });

  it('counts three distinct topics and uses plural wording, spelled out', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-09' },
      { type: 'AURORA', label: 'Aurora possible', date: '2026-08-10' },
      { type: 'KING_TIDE', label: 'King tide', date: '2026-08-11' },
    ]);
    expect(result.summary).toBe('Three topics live on those four days');
    expect(result.topics).toHaveLength(3);
  });

  it('de-dupes by type — the same topic firing on two of the four days gets one swatch', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-09' },
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-10' },
    ]);
    expect(result.topics).toHaveLength(1);
    expect(result.summary).toBe('One topic lives on those four days');
  });

  it('excludes a topic dated before today', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-07' },
    ]);
    expect(result.topics).toEqual([]);
    expect(result.summary).toBe('No topics live on those four days');
  });

  it('excludes a topic dated beyond Plan\'s last day', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: '2026-08-12' },
    ]);
    expect(result.topics).toEqual([]);
  });

  it('includes a topic on the boundary dates — today and the last Plan day', () => {
    const result = buildHandoff(TODAY, [
      { type: 'DUST', label: 'Saharan dust', date: TODAY },
      { type: 'AURORA', label: 'Aurora possible', date: '2026-08-11' },
    ]);
    expect(result.topics.map((t) => t.type)).toEqual(['DUST', 'AURORA']);
  });

  it('falls back to the raw type as the name when a topic has no label', () => {
    const result = buildHandoff(TODAY, [
      { type: 'MYSTERY_TOPIC', date: TODAY },
    ]);
    expect(result.topics[0].name).toBe('MYSTERY_TOPIC');
  });

  it('gives an unrecognised type the default swatch colour rather than throwing', () => {
    const result = buildHandoff(TODAY, [
      { type: 'MYSTERY_TOPIC', label: 'A new kind of topic', date: TODAY },
    ]);
    expect(result.topics[0].color).toBe('#9CA3AF');
  });

  it('skips a topic with no type or no date rather than crashing on it', () => {
    const result = buildHandoff(TODAY, [
      { type: null, date: TODAY },
      { type: 'DUST', date: null },
      { label: 'no type at all', date: TODAY },
    ]);
    expect(result.topics).toEqual([]);
  });
});
