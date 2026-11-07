import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  ANCHORS, STOPS_TEMP, STOPS_VERDICT, rampHex, rampRgb, resolveMode, setMode, starFromScore,
} from '../utils/scoreRamp.js';

afterEach(() => {
  setMode('verdict');
});

describe('scoreRamp tables and parsing — edge by edge', () => {
  it('holds the temperature stops exactly, uneven spacing included', () => {
    expect(STOPS_TEMP).toEqual([
      { score: 1, hex: '#3A5C70' },
      { score: 2.2, hex: '#506878' },
      { score: 2.8, hex: '#928C80' },
      { score: 3, hex: '#C49440' },
      { score: 3.2, hex: '#C99230' },
      { score: 3.9, hex: '#DF6B2A' },
      { score: 4.3, hex: '#DE4826' },
      { score: 5, hex: '#C82820' },
    ]);
  });

  it('returns each temperature stop\'s own hex and rgb at its score', () => {
    setMode('temp');
    for (const stop of STOPS_TEMP) {
      expect(rampHex(stop.score)).toBe(stop.hex);
    }
    expect(rampRgb(1)).toEqual([0x3a, 0x5c, 0x70]);
    expect(rampRgb(5)).toEqual([0xc8, 0x28, 0x20]);
  });

  it('returns each verdict stop\'s own hex and rgb at its score', () => {
    for (const stop of STOPS_VERDICT) {
      expect(rampHex(stop.score)).toBe(stop.hex);
    }
    expect(rampRgb(3)).toEqual([0xe0, 0xa5, 0x42]);
  });

  it('interpolates between two temperature stops channel by channel', () => {
    setMode('temp');
    // Halfway between 1 (#3A5C70) and 2.2 (#506878) is score 1.6.
    expect(rampRgb(1.6)).toEqual([Math.round((0x3a + 0x50) / 2), Math.round((0x5c + 0x68) / 2), 0x74]);
  });

  it('clamps scores outside 1 to 5 and sends a non-finite score to the bottom stop', () => {
    expect(rampHex(0)).toBe('#B03A2A');
    expect(rampHex(9)).toBe('#8AAE72');
    expect(rampHex(NaN)).toBe('#B03A2A');
    expect(rampHex(Infinity)).toBe('#B03A2A');
  });

  it('resolves an explicit "temp" to temp and an explicit "verdict" to verdict', () => {
    expect(resolveMode('temp')).toBe('temp');
    expect(resolveMode('verdict')).toBe('verdict');
    expect(resolveMode(null)).toBe('temp');
    expect(resolveMode(undefined)).toBe('temp');
    expect(resolveMode('sepia')).toBe('verdict');
  });
});

describe('scoreRamp anchors — frozen tables', () => {
  it('holds both anchor tables exactly', () => {
    expect(ANCHORS).toEqual({
      fiery: [[0, 1], [20, 1.9], [35, 2.4], [50, 2.8], [60, 3.2], [72, 4], [85, 4.7], [100, 5]],
      golden: [[0, 1], [25, 1.9], [40, 2.4], [55, 3], [70, 3.8], [85, 4.6], [100, 5]],
    });
    expect(Object.keys(ANCHORS)).toEqual(['fiery', 'golden']);
  });

  it.each([
    ['fiery', 0, 1], ['fiery', 20, 1.9], ['fiery', 35, 2.4], ['fiery', 50, 2.8], ['fiery', 60, 3.2],
    ['fiery', 72, 4], ['fiery', 85, 4.7], ['fiery', 100, 5],
    ['golden', 0, 1], ['golden', 25, 1.9], ['golden', 40, 2.4], ['golden', 55, 3], ['golden', 70, 3.8],
    ['golden', 85, 4.6], ['golden', 100, 5],
  ])('%s at its anchor %s gives exactly %s', (metric, value, stars) => {
    expect(starFromScore(value, metric)).toBe(stars);
  });

  it('interpolates inside a segment and clamps outside 0 to 100', () => {
    expect(starFromScore(10, 'fiery')).toBeCloseTo(1.45, 10);
    expect(starFromScore(80, 'fiery')).toBeCloseTo(4.4308, 3);
    expect(starFromScore(80, 'golden')).toBeCloseTo(4.3333, 3);
    expect(starFromScore(-10, 'fiery')).toBe(1);
    expect(starFromScore(150, 'golden')).toBe(5);
    expect(starFromScore(NaN, 'golden')).toBe(1);
  });

  it('throws for an unknown metric, including a prototype member name', () => {
    expect(() => starFromScore(50, 'toString')).toThrow("unknown metric 'toString'");
    expect(() => starFromScore(50, 'nope')).toThrow("unknown metric 'nope'");
  });
});

describe('scoreRamp module-level tables, read from a freshly evaluated module', () => {
  it('evaluates STOPS_TEMP and its parsed rgb triples at load', async () => {
    vi.resetModules();
    const fresh = await import('../utils/scoreRamp.js');
    expect(fresh.STOPS_TEMP).toEqual([
      { score: 1, hex: '#3A5C70' },
      { score: 2.2, hex: '#506878' },
      { score: 2.8, hex: '#928C80' },
      { score: 3, hex: '#C49440' },
      { score: 3.2, hex: '#C99230' },
      { score: 3.9, hex: '#DF6B2A' },
      { score: 4.3, hex: '#DE4826' },
      { score: 5, hex: '#C82820' },
    ]);
    fresh.setMode('temp');
    expect(fresh.rampRgb(2.2)).toEqual([0x50, 0x68, 0x78]);
    expect(fresh.rampRgb(3.9)).toEqual([0xdf, 0x6b, 0x2a]);
    expect(fresh.rampHex(5)).toBe('#C82820');
  });

  it('parses the verdict hex literals into rgb triples at load', async () => {
    vi.resetModules();
    const fresh = await import('../utils/scoreRamp.js');
    expect(fresh.rampRgb(1)).toEqual([0xb0, 0x3a, 0x2a]);
    expect(fresh.rampRgb(2)).toEqual([0xc8, 0x45, 0x2f]);
    expect(fresh.rampRgb(4)).toEqual([0xb0, 0xbe, 0x74]);
    expect(fresh.rampRgb(5)).toEqual([0x8a, 0xae, 0x72]);
  });

  it('evaluates both ANCHORS tables at load', async () => {
    vi.resetModules();
    const fresh = await import('../utils/scoreRamp.js');
    expect(fresh.ANCHORS).toEqual({
      fiery: [[0, 1], [20, 1.9], [35, 2.4], [50, 2.8], [60, 3.2], [72, 4], [85, 4.7], [100, 5]],
      golden: [[0, 1], [25, 1.9], [40, 2.4], [55, 3], [70, 3.8], [85, 4.6], [100, 5]],
    });
    expect(fresh.starFromScore(50, 'fiery')).toBe(2.8);
    expect(fresh.starFromScore(55, 'golden')).toBe(3);
    expect(fresh.starFromScore(10, 'fiery')).toBeCloseTo(1.45, 10);
    expect(fresh.starFromScore(62.5, 'golden')).toBeCloseTo(3.4, 10);
  });
});
