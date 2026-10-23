import { describe, it, expect } from 'vitest';
import { buildTopicIndex, topicInScope, windowTopics } from '../utils/windowFirstTopics.js';

/**
 * {@code topicInScope} — the scope rule factored out of {@code windowTopics}
 * (hot-topics-by-day-plan §1.4) so the Coming up lanes and the Map call the same filter. The
 * existing {@code windowFirstTopics.test.js} pins {@code windowTopics}' own output unchanged; this
 * file tests the helper directly, and that the two cannot disagree.
 */

const tide = (regions) => ({ type: 'KING_TIDE', label: 'King tide', regions });

describe('topicInScope', () => {
  it('keeps a region-scoped topic with at least one region in scope', () => {
    expect(topicInScope(tide(['Coast', 'Dales']), ['Coast'])).toBe(true);
  });

  it('drops a region-scoped topic whose regions are all out of scope', () => {
    expect(topicInScope(tide(['Dales']), ['Coast'])).toBe(false);
  });

  it('keeps a region-scoped topic with an empty or missing regions list — "not stated", not "nowhere"', () => {
    expect(topicInScope(tide([]), ['Coast'])).toBe(true);
    expect(topicInScope({ type: 'KING_TIDE' }, ['Coast'])).toBe(true);
  });

  it('exempts every whole-sky type by type, whatever its populated regions say', () => {
    for (const type of ['AURORA', 'NLC', 'METEOR', 'SUPERMOON', 'EQUINOX', 'ECLIPSE', 'LUNAR_ECLIPSE']) {
      expect(topicInScope({ type, regions: ['Elsewhere'] }, ['Coast'])).toBe(true);
    }
  });

  it('is fail-open for an unrecognised type', () => {
    expect(topicInScope({ type: 'SOLAR_HALO', regions: ['Elsewhere'] }, ['Coast'])).toBe(true);
  });

  it('filters nothing when the scope is empty or not an array — the scope is not known yet', () => {
    expect(topicInScope(tide(['Dales']), [])).toBe(true);
    expect(topicInScope(tide(['Dales']), undefined)).toBe(true);
    expect(topicInScope(tide(['Dales']), null)).toBe(true);
  });

  it('compares region names byte-identically, never normalised', () => {
    expect(topicInScope(tide(['Coast']), ['coast'])).toBe(false);
    expect(topicInScope(tide(['Coast']), ['Coast '])).toBe(false);
  });

  it('keeps a null topic, as windowTopics keeps a badge whose join found none', () => {
    expect(topicInScope(null, ['Coast'])).toBe(true);
  });

  it('agrees with windowTopics on which topics survive', () => {
    const inScope = tide(['Coast']);
    const outOfScope = { type: 'INVERSION', label: 'Inversion', regions: ['Dales'], date: '2026-08-08', eventType: 'SUNSET' };
    const topics = [
      { ...inScope, date: '2026-08-08', eventType: 'SUNSET' }, outOfScope,
    ];
    const index = buildTopicIndex(topics);
    const badges = [
      { type: 'KING_TIDE', label: 'King tide' }, { type: 'INVERSION', label: 'Inversion' },
    ];
    const kept = windowTopics('2026-08-08:SUNSET', badges, index, ['Coast']).map((r) => r.badge.type);
    expect(kept).toEqual(['KING_TIDE']);
    expect(topicInScope(topics[0], ['Coast'])).toBe(true);
    expect(topicInScope(outOfScope, ['Coast'])).toBe(false);
  });
});
