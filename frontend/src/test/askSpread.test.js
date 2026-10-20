/**
 * The words of the line under an Ask answer's summary (`utils/askSpread.js`; ask-window-context plan §2.4,
 * §6 Q4 — W2): the server's figure beside the model's prose. Every number must be the served one, the
 * population must be named, "game on" must never appear, and a window nobody has rated must draw no line.
 */

import { describe, it, expect } from 'vitest';
import {
  EVERY_REGION, parseSolarWindowId, populationPhrase, spreadLines,
} from '../utils/askSpread.js';

/** A served `AskSnapshot.Spread`, the W1 wire shape, over the whole catalogue: the owner's weekend. */
const spread = (over = {}) => ({
  over: EVERY_REGION,
  rated: 180,
  bands: [20, 60, 98, 2, 0],
  gameOn: 2,
  gameOnPct: 1,
  go: 0,
  below4: 178,
  stars1to2: 80,
  unrated: 40,
  stoodDown: 12,
  regions: { counted: 6, thin: 1 },
  pockets: [{ region: 'Northumberland', gameOn: 2, mean: 3.1 }],
  ...over,
});

// 2026-10-17 is a Saturday, 2026-10-18 a Sunday: the weekend the plan was written from.
const SAT_PM = '2026-10-17_sunset';
const SUN_AM = '2026-10-18_sunrise';

describe('parseSolarWindowId', () => {
  it('reads the date and event off a solar id', () => {
    expect(parseSolarWindowId(SAT_PM)).toEqual({ date: '2026-10-17', targetType: 'SUNSET' });
    expect(parseSolarWindowId(SUN_AM)).toEqual({ date: '2026-10-18', targetType: 'SUNRISE' });
  });

  it.each(['2026-10-17_astro', '2026-10-17_aurora', '2026-10-17', 'sunset', '', null, 7, undefined])(
    'is null for %j, which names no solar window',
    (id) => {
      expect(parseSolarWindowId(id)).toBeNull();
    },
  );
});

describe('populationPhrase', () => {
  it('says "across every region" for the whole catalogue and "in …" for a scope the server has named', () => {
    expect(populationPhrase(EVERY_REGION)).toBe('across every region');
    expect(populationPhrase('Northumberland')).toBe('in Northumberland');
    expect(populationPhrase('Lake District and Northumberland')).toBe('in Lake District and Northumberland');
  });

  it('is null when the server named no population', () => {
    expect(populationPhrase('')).toBeNull();
    expect(populationPhrase('  ')).toBeNull();
    expect(populationPhrase(null)).toBeNull();
    expect(populationPhrase(undefined)).toBeNull();
  });
});

describe('spreadLines — one line per rated window, in the order carried', () => {
  it('reads the day word, the event, the rated count, the population and the 4★+ count off the record', () => {
    const [line] = spreadLines([{ windowId: SAT_PM, spread: spread() }]);

    expect(line.text).toBe('Sat sunset · 180 rated across every region · 2 at 4★+');
    expect(line.windowId).toBe(SAT_PM);
  });

  it('speaks the same figures as counts, never as a star glyph', () => {
    const [line] = spreadLines([{ windowId: SAT_PM, spread: spread({ go: 1 }) }]);

    expect(line.spoken).toBe('Sat sunset: 180 rated across every region, 2 at 4 stars or more, 1 at 5 stars');
    expect(line.spoken).not.toContain('★');
  });

  it('names a scope of one region and of several as the server joined them', () => {
    const one = spreadLines([{ windowId: SAT_PM, spread: spread({ over: 'Northumberland' }) }]);
    const two = spreadLines([{ windowId: SAT_PM, spread: spread({ over: 'Lake District and Northumberland' }) }]);

    expect(one[0].text).toBe('Sat sunset · 180 rated in Northumberland · 2 at 4★+');
    expect(two[0].text).toBe('Sat sunset · 180 rated in Lake District and Northumberland · 2 at 4★+');
  });

  it('adds the 5★ count only when there is one', () => {
    const none = spreadLines([{ windowId: SAT_PM, spread: spread({ go: 0 }) }]);
    const some = spreadLines([{ windowId: SAT_PM, spread: spread({ gameOn: 9, go: 3 }) }]);

    expect(none[0].text).not.toContain('5★');
    expect(some[0].text).toBe('Sat sunset · 180 rated across every region · 9 at 4★+ · 3 at 5★');
  });

  it('says "none at 4★+" for a window with no game-on place, and never the owner’s word', () => {
    const [line] = spreadLines([{ windowId: SUN_AM, spread: spread({ gameOn: 0, gameOnPct: 0 }) }]);

    expect(line.text).toBe('Sun sunrise · 180 rated across every region · none at 4★+');
    expect(line.text.toLowerCase()).not.toContain('game on');
    expect(line.spoken.toLowerCase()).not.toContain('game on');
  });

  it('keeps the server’s order and draws every rated window', () => {
    const lines = spreadLines([
      { windowId: SUN_AM, spread: spread({ rated: 12, gameOn: 0 }) },
      { windowId: SAT_PM, spread: spread() },
    ]);

    expect(lines.map((l) => l.windowId)).toEqual([SUN_AM, SAT_PM]);
    expect(lines.map((l) => l.text)).toEqual([
      'Sun sunrise · 12 rated across every region · none at 4★+',
      'Sat sunset · 180 rated across every region · 2 at 4★+',
    ]);
  });

  it('says "1 rated" for one — a count, with no plural to get wrong', () => {
    expect(spreadLines([{ windowId: SAT_PM, spread: spread({ rated: 1, gameOn: 1 }) }])[0].text)
      .toBe('Sat sunset · 1 rated across every region · 1 at 4★+');
  });

  it('omits the population when the server named none, rather than inventing one', () => {
    expect(spreadLines([{ windowId: SAT_PM, spread: spread({ over: '' }) }])[0].text)
      .toBe('Sat sunset · 180 rated · 2 at 4★+');
  });
});

describe('spreadLines — a window nobody has rated', () => {
  it('draws no line for a rated-0 window beside a rated one', () => {
    const lines = spreadLines([
      { windowId: SAT_PM, spread: spread() },
      { windowId: SUN_AM, spread: spread({ rated: 0, bands: [0, 0, 0, 0, 0], gameOn: 0, go: 0 }) },
    ]);

    expect(lines).toHaveLength(1);
    expect(lines[0].windowId).toBe(SAT_PM);
  });

  it('says "nothing rated" once, with the population, when windows are carried and none is rated', () => {
    const lines = spreadLines([
      { windowId: SAT_PM, spread: spread({ rated: 0, bands: [], gameOn: 0, go: 0 }) },
      { windowId: SUN_AM, spread: spread({ rated: 0, bands: [], gameOn: 0, go: 0 }) },
    ]);

    expect(lines).toEqual([{ windowId: null, text: 'Nothing rated across every region', spoken: 'Nothing rated across every region' }]);
  });

  it('names the scope in that line too', () => {
    const lines = spreadLines([{ windowId: SAT_PM, spread: spread({ over: 'Northumberland', rated: 0, gameOn: 0 }) }]);

    expect(lines[0].text).toBe('Nothing rated in Northumberland');
  });
});

describe('spreadLines — nothing carried, nothing said', () => {
  it.each([undefined, null, [], 'x', {}])('is empty for windows %j', (windows) => {
    expect(spreadLines(windows)).toEqual([]);
  });

  it('draws nothing for a window it cannot name — a night id, a malformed id, no spread, no readable count', () => {
    expect(spreadLines([{ windowId: '2026-10-17_astro', spread: spread() }])).toEqual([]);
    expect(spreadLines([{ windowId: 'sunset', spread: spread() }])).toEqual([]);
    expect(spreadLines([{ windowId: SAT_PM, spread: null }])).toEqual([]);
    expect(spreadLines([{ windowId: SAT_PM, spread: spread({ rated: '180' }) }])).toEqual([]);
    expect(spreadLines([{ windowId: SAT_PM, spread: spread({ rated: -1 }) }])).toEqual([]);
    expect(spreadLines([null, undefined, 4])).toEqual([]);
  });

  it('does not let an undrawable window turn into a "nothing rated" claim', () => {
    // Nothing drawable at all: no line, rather than a sentence about windows that were never named.
    expect(spreadLines([{ windowId: '2026-10-17_astro', spread: spread({ rated: 0 }) }])).toEqual([]);
  });

  it('reads a 4★+ or 5★ count that is not a whole number as zero, never as a figure', () => {
    const [line] = spreadLines([{ windowId: SAT_PM, spread: spread({ gameOn: '2', go: 1.5 }) }]);

    expect(line.text).toBe('Sat sunset · 180 rated across every region · none at 4★+');
  });
});
