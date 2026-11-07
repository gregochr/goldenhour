import { describe, it, expect } from 'vitest';
import { movementChip, topMovers } from '../utils/movement.js';

describe('movementChip — only a finite number is a movement', () => {
  it.each([[null], [undefined], [Number.NaN], [Infinity], [-Infinity], ['1.2'], [{}]])(
    'is null for %s',
    (delta) => {
      expect(movementChip(delta)).toBeNull();
    },
  );

  it('rounds to one decimal before choosing a direction', () => {
    expect(movementChip(0.04).shortSpoken).toBe('unchanged');
    expect(movementChip(-0.04).shortSpoken).toBe('unchanged');
    expect(movementChip(0.06)).toMatchObject({ mark: '▲0.1', shortSpoken: 'up 0.1 stars' });
    expect(movementChip(-0.06)).toMatchObject({ mark: '▼0.1', shortSpoken: 'down 0.1 stars' });
  });

  it('spells the full spoken form with the unit', () => {
    expect(movementChip(1.3).spoken).toBe('up 1.3 stars at the last forecast run');
    expect(movementChip(-2).spoken).toBe('down 2.0 stars at the last forecast run');
    expect(movementChip(0).spoken).toBe('unchanged at the last forecast run');
  });

  it('gives up, down and flat three different tones', () => {
    const tones = [movementChip(1).tone, movementChip(-1).tone, movementChip(0).tone];
    expect(new Set(tones).size).toBe(3);
  });
});

describe('topMovers — holes in the card list', () => {
  const card = (key, delta, regionName = 'North') => ({
    key, label: key, movement: { delta, regionName },
  });

  it('skips a null or undefined card and a card with no movement without throwing', () => {
    const movers = topMovers([null, undefined, { key: 'x', label: 'x' }, card('a', 1)]);
    expect(movers.map((m) => m.key)).toEqual(['a']);
  });

  it('is empty for a missing list', () => {
    expect(topMovers(null)).toEqual([]);
    expect(topMovers(undefined)).toEqual([]);
  });

  it('drops flat movers, ranks by size either way and keeps the first two', () => {
    const movers = topMovers([card('flat', 0), card('small', 0.5), card('big-down', -2), card('mid', 1)]);
    expect(movers.map((m) => m.key)).toEqual(['big-down', 'mid']);
    expect(movers[0].regionName).toBe('North');
    expect(movers[0].chip.mark).toBe('▼2.0');
  });
});
