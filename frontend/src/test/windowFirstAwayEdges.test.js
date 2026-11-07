import { describe, it, expect } from 'vitest';
import { buildPaneItems } from '../utils/windowFirstAway.js';

describe('windowFirstAway edges', () => {
  it('builds nothing from a missing event list even when cards are supplied', () => {
    expect(buildPaneItems(null, [{ key: 'a' }], null)).toEqual([]);
    expect(buildPaneItems(undefined, [{ key: 'a' }], new Set())).toEqual([]);
  });

  it('pairs each live event with its card in order', () => {
    const cards = [{ key: 'c1' }, { key: 'c2' }];
    const items = buildPaneItems(
      [{ date: '2026-11-07' }, { date: '2026-11-08' }], cards, null,
    );
    expect(items.map((i) => i.key)).toEqual(['c1', 'c2']);
    expect(items.every((i) => i.kind === 'card')).toBe(true);
  });
});
