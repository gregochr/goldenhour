import { describe, it, expect } from 'vitest';
import {
  FLAT_MARK, MAX_MOVERS, TONE_DOWN, TONE_FLAT, TONE_UP, movementChip, topMovers,
} from '../utils/movement.js';

/**
 * Run-to-run movement — the vocabulary behind the strip's chips and its change line (plan §4.7).
 *
 * <p>The behaviour worth protecting here is the THREE-state rule: a number, a measured zero, and no
 * basis at all. Two of those render, one renders nothing, and collapsing the last two would put a
 * `—` on every thumbnail on the first serve after a deploy — claiming a stillness nobody measured.
 * Each state has its own test and each asserts the exact string the reader sees.
 */

describe('movementChip — the three states', () => {
  it('says "at" the last run, never "since" it', () => {
    // ⚠️ The delta is measured from the build BEFORE the last one, so "since the last forecast
    // run" names the one interval in which almost none of the movement happened — with builds
    // ~11h apart a ten-hour change would be attributed to the last fifty-two minutes. Three
    // independent review lenses raised it. The plan's own sample copy says "Since"; this is the
    // recorded deviation, and this test is why re-instating that word fails.
    expect(movementChip(0.6).spoken).toContain('at the last forecast run');
    expect(movementChip(0.6).spoken).not.toContain('since');
    expect(movementChip(0).spoken).not.toContain('since');
  });

  it('carries the UNIT, because the spoken form is a non-visual reader\'s whole answer', () => {
    // "up 0.6" of what? A sighted reader has the star column; the thumbnail's accessible name is
    // one sentence with no scale in it at all. The band's sibling figure already prints `4★`.
    expect(movementChip(0.6).spoken).toContain('0.6 stars');
    expect(movementChip(-0.3).shortSpoken).toBe('down 0.3 stars');
  });

  it('renders a rise as an up glyph with an UNSIGNED magnitude', () => {
    // The glyph carries the sign. `▲+0.6` states it twice and `▲0.6` is what the design draws.
    expect(movementChip(0.6)).toEqual({
      mark: '▲0.6',
      tone: TONE_UP,
      spoken: 'up 0.6 stars at the last forecast run',
      shortSpoken: 'up 0.6 stars',
    });
  });

  it('renders a fall as a down glyph, also unsigned', () => {
    expect(movementChip(-0.3)).toEqual({
      mark: '▼0.3',
      tone: TONE_DOWN,
      spoken: 'down 0.3 stars at the last forecast run',
      shortSpoken: 'down 0.3 stars',
    });
  });

  it('renders a MEASURED zero as the flat mark, never as nothing', () => {
    // "Did not move" is an answer. It is the state the next test must stay distinguishable from.
    expect(movementChip(0)).toEqual({
      mark: FLAT_MARK,
      tone: TONE_FLAT,
      spoken: 'unchanged at the last forecast run',
      shortSpoken: 'unchanged',
    });
  });

  it('renders NOTHING when there is no delta at all', () => {
    // Null is "no previous build, or this region was absent from it, or either side was unscored".
    // A `—` here would be a claim; silence is the honest degrade, and it is the ordinary state on
    // the first serve after a deploy.
    expect(movementChip(null)).toBeNull();
    expect(movementChip(undefined)).toBeNull();
  });

  it('renders nothing for a non-finite value rather than printing it', () => {
    // A payload is not a contract the render layer may assume. `NaN.toFixed(1)` is the string
    // "NaN", which would reach the chip as `▲NaN`.
    expect(movementChip(NaN)).toBeNull();
    expect(movementChip(Infinity)).toBeNull();
    expect(movementChip('0.6')).toBeNull();
  });

  it('rounds to one decimal, so a binary artefact cannot reach the chip', () => {
    // 3.7 - 3.1 in doubles. The backend rounds too; this is the second guard, and it is the one
    // that decides what is actually printed.
    expect(movementChip(0.5999999999999996).mark).toBe('▲0.6');
  });

  it('treats a delta that ROUNDS to zero as flat, not as a direction', () => {
    // `▲0.0` is an arrow claiming a direction over a magnitude that prints as nothing.
    expect(movementChip(0.04)).toMatchObject({ mark: FLAT_MARK, tone: TONE_FLAT });
    expect(movementChip(-0.04)).toMatchObject({ mark: FLAT_MARK, tone: TONE_FLAT });
  });

  it('rounds a delta at the half-way boundary UP in magnitude', () => {
    expect(movementChip(0.05).mark).toBe('▲0.1');
  });
});

describe('topMovers — what the change line names', () => {
  const card = (key, label, regionName, delta) => ({
    key, label, movement: delta == null ? null : { regionName, delta },
  });

  it('ranks by the SIZE of the move, not by its direction or its position', () => {
    const movers = topMovers([
      card('a', 'Tonight Sunset', 'Cumbria', 0.2),
      card('b', 'Tomorrow Sunrise', 'North East', -0.9),
      card('c', 'Tomorrow Sunset', 'Yorkshire', 0.5),
    ]);
    expect(movers.map((m) => m.key)).toEqual(['b', 'c']);
    expect(movers[0].chip.mark).toBe('▼0.9');
    expect(movers[0].regionName).toBe('North East');
  });

  it('names at most two — a line, not a list', () => {
    const movers = topMovers([
      card('a', 'A', 'R1', 1.0), card('b', 'B', 'R2', 0.9), card('c', 'C', 'R3', 0.8),
    ]);
    expect(movers).toHaveLength(MAX_MOVERS);
  });

  it('excludes a window that did not move', () => {
    // Its own thumbnail already carries the `—` eight pixels above, and "Tonight Sunset — in
    // Cumbria" is a sentence about nothing.
    expect(topMovers([card('a', 'Tonight Sunset', 'Cumbria', 0)])).toEqual([]);
  });

  it('excludes a window with no delta at all', () => {
    expect(topMovers([card('a', 'Tonight Sunset', 'Cumbria', null)])).toEqual([]);
  });

  it('breaks a tie chronologically, which is the strip\'s only ordering', () => {
    // The sort is stable and `cards` arrives in payload order, so two equal moves keep the order
    // the thumbnails are drawn in. A second ordering on this surface is what §4.7 forbids.
    const movers = topMovers([
      card('a', 'Tonight Sunset', 'Cumbria', 0.4),
      card('b', 'Tomorrow Sunrise', 'North East', -0.4),
    ]);
    expect(movers.map((m) => m.key)).toEqual(['a', 'b']);
  });

  it('returns nothing for an absent card list rather than throwing', () => {
    expect(topMovers(undefined)).toEqual([]);
    expect(topMovers([])).toEqual([]);
  });
});
