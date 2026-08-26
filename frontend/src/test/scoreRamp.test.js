import {
  describe, it, expect, afterEach,
} from 'vitest';
import {
  STOPS_VERDICT, RAMP_MIN, RAMP_MAX, rampRgb, rampHex, rgb, setMode, getMode, scoreFromPercent,
} from '../utils/scoreRamp.js';

/**
 * The ramp is the single source of the v2 colour language — the canvas kernel reads the triples,
 * the DOM consumers read the hex. Both come from one literal per stop, so these tests exist to
 * pin the literals themselves (a mistyped hex is invisible on a blurred field) and the
 * interpolation between them (a stop reordering or an off-by-one in the segment search would
 * still produce plausible colours).
 */
describe('scoreRamp', () => {
  // MODE is module state, not a per-test fixture — a test that calls setMode('temp') and forgets
  // to undo it would leak into every test that runs after it, in this file or another.
  afterEach(() => {
    setMode('verdict');
  });

  describe('the five stops', () => {
    it('is the design\'s verdict-anchored palette, in ascending score order', () => {
      expect(STOPS_VERDICT.map((s) => s.score)).toEqual([1, 2, 3, 4, 5]);
      expect(STOPS_VERDICT.map((s) => s.hex)).toEqual([
        '#B03A2A',
        '#C8452F', // --color-verdict-standdown
        '#E0A542', // --color-verdict-marginal
        '#B0BE74',
        '#8AAE72', // --color-verdict-go
      ]);
    });

    it('exposes its own ends as RAMP_MIN / RAMP_MAX', () => {
      expect(RAMP_MIN).toBe(1);
      expect(RAMP_MAX).toBe(5);
    });

    it('returns each stop exactly, with no interpolation drift at the join', () => {
      expect(rampRgb(1)).toEqual([176, 58, 42]);
      expect(rampRgb(2)).toEqual([200, 69, 47]);
      expect(rampRgb(3)).toEqual([224, 165, 66]);
      expect(rampRgb(4)).toEqual([176, 190, 116]);
      expect(rampRgb(5)).toEqual([138, 174, 114]);
    });

    it('derives the same colours as hex, so the canvas and the DOM cannot disagree', () => {
      STOPS_VERDICT.forEach((stop) => {
        expect(rampHex(stop.score)).toBe(stop.hex);
      });
    });
  });

  describe('interpolation', () => {
    it('is linear at the midpoint of every segment', () => {
      // Each channel is the arithmetic mean of the bracketing stops, rounded.
      expect(rampRgb(1.5)).toEqual([188, 64, 45]); // (176+200)/2, (58+69)/2, (42+47)/2
      expect(rampRgb(2.5)).toEqual([212, 117, 57]);
      expect(rampRgb(3.5)).toEqual([200, 178, 91]); // rounds .5 up: (165+190)/2 = 177.5
      expect(rampRgb(4.5)).toEqual([157, 182, 115]);
    });

    it('moves monotonically along a segment rather than jumping at the stop', () => {
      // A quarter of the way from 4 to 5 is a quarter of the way from #B0BE74 to #8AAE72.
      expect(rampRgb(4.25)).toEqual([167, 186, 116]);
      expect(rampRgb(4.75)).toEqual([148, 178, 115]);
    });

    it('returns integers — the kernel writes them straight into ImageData', () => {
      [1.1, 2.3, 3.7, 4.9].forEach((score) => {
        rampRgb(score).forEach((channel) => {
          expect(Number.isInteger(channel)).toBe(true);
          expect(channel).toBeGreaterThanOrEqual(0);
          expect(channel).toBeLessThanOrEqual(255);
        });
      });
    });
  });

  describe('clamping', () => {
    it('clamps below the bottom stop rather than extrapolating into a colour with no meaning', () => {
      expect(rampRgb(0)).toEqual([176, 58, 42]);
      expect(rampRgb(-4)).toEqual([176, 58, 42]);
      expect(rampHex(0)).toBe('#B03A2A');
    });

    it('clamps above the top stop', () => {
      expect(rampRgb(6)).toEqual([138, 174, 114]);
      expect(rampRgb(99)).toEqual([138, 174, 114]);
      expect(rampHex(6)).toBe('#8AAE72');
    });
  });

  describe('a score that is not a number', () => {
    /**
     * The rule: an unknown reading resolves to the BOTTOM of the ramp, never the top.
     *
     * A plain clamp does not give you this, and the failure is silent and inverted. `Math.max(1,
     * Math.min(5, NaN))` is NaN, and NaN then fails every `<=` comparison — so a segment search
     * falls out of its loop and returns the LAST stop. An undefined score, a missing key, or an
     * out-of-range window index would paint the same 5★ GO green as the best forecast of the week,
     * at full coverage alpha and with no mark of doubt. That is the exact false-confidence failure
     * the whole confidence channel exists to prevent, and it needs no bad data to reach — a
     * six-window array read at index 6 is enough.
     */
    it('resolves a non-finite score to the bottom of the ramp, never the top', () => {
      [NaN, undefined, Infinity, -Infinity, 'abc', {}, []].forEach((bad) => {
        expect(rampRgb(bad)).toEqual([176, 58, 42]);
        expect(rampHex(bad)).toBe('#B03A2A');
      });
    });

    it('treats null and empty string the same way, by the same rule rather than by coercion luck', () => {
      // These two coerce to 0 and would clamp to the bottom anyway — asserted so that a future
      // guard written only for NaN cannot split the two behaviours apart.
      expect(rampRgb(null)).toEqual([176, 58, 42]);
      expect(rampRgb('')).toEqual([176, 58, 42]);
    });

    it('still reads a numeric string as its number', () => {
      // Number.isFinite('3') is false, so a numeric string takes the bottom rather than reading as
      // 3. Pinned deliberately: the ramp's contract is "a number", and a caller passing a string
      // has a bug that should show as a suspiciously red field rather than as a plausible amber.
      expect(rampRgb('3')).toEqual([176, 58, 42]);
    });
  });

  describe('rgb', () => {
    it('renders a triple as an rgba string, opaque when no alpha is given', () => {
      expect(rgb(rampRgb(5))).toBe('rgba(138,174,114,1)');
      expect(rgb([138, 174, 114], 0.4)).toBe('rgba(138,174,114,0.4)');
    });

    it('keeps an explicit zero alpha rather than defaulting it away', () => {
      expect(rgb([1, 2, 3], 0)).toBe('rgba(1,2,3,0)');
    });
  });

  describe('ownership of the returned triple', () => {
    it('hands back a fresh array every time, so a consumer cannot corrupt the ramp', () => {
      // The kernel writes these values straight into ImageData and the DOM consumers pass them
      // around; if any code path returned the module's own storage, one careless mutation would
      // silently repaint every surface that reads the ramp.
      const first = rampRgb(5);
      first[0] = 0;
      expect(rampRgb(5)).toEqual([138, 174, 114]);

      const clamped = rampRgb(99);
      clamped[0] = 0;
      expect(rampRgb(99)).toEqual([138, 174, 114]);

      const unknown = rampRgb(NaN);
      unknown[0] = 0;
      expect(rampRgb(NaN)).toEqual([176, 58, 42]);
    });
  });

  describe('rampHex format', () => {
    it('is a six-digit upper-case hex string, zero-padded', () => {
      // A channel below 16 must not collapse to five digits — '#8aae72'.length is load-bearing
      // for anything doing string comparison against a token literal.
      // The zero-pad itself is unreachable through the ramp's own domain — its darkest channel is
      // 42 (0x2A) — so this asserts the format across the range rather than claiming to exercise it.
      [0, 1, 1.5, 2.5, 3.5, 4.5, 5, 99, NaN].forEach((score) => {
        expect(rampHex(score)).toMatch(/^#[0-9A-F]{6}$/);
      });
      expect(rampHex(1)).toHaveLength(7);
    });
  });

  describe('mode', () => {
    it('defaults to verdict', () => {
      expect(getMode()).toBe('verdict');
    });

    it('every whole star returns today\'s colour in the default mode — the zero-visual-change proof', () => {
      expect(rampHex(1)).toBe('#B03A2A');
      expect(rampHex(2)).toBe('#C8452F');
      expect(rampHex(3)).toBe('#E0A542');
      expect(rampHex(4)).toBe('#B0BE74');
      expect(rampHex(5)).toBe('#8AAE72');
    });

    it('switches to the temperature ramp, whose 2★ and 4★ are interpolated rather than stops', () => {
      setMode('temp');
      expect(getMode()).toBe('temp');
      // 2★ falls between the 1 and 2.2 stops, and 4★ between the 3.9 and 4.3 stops — neither is a
      // stop in its own right, which is exactly what pins the uneven spacing: a regularised ramp
      // would put a stop at 2 and 4 and these values would not move under interpolation error.
      expect(rampHex(1)).toBe('#3A5C70');
      expect(rampHex(2)).toBe('#4C6677');
      expect(rampHex(3)).toBe('#C49440');
      expect(rampHex(4)).toBe('#DF6229');
      expect(rampHex(5)).toBe('#C82820');
    });

    it('falls back to verdict for an unrecognised mode string, rather than selecting temp by accident', () => {
      setMode('temp');
      setMode('bogus');
      expect(getMode()).toBe('verdict');
      expect(rampHex(1)).toBe('#B03A2A');
    });

    it('clamps outside 1–5 in temp mode the same way verdict does', () => {
      setMode('temp');
      expect(rampHex(0)).toBe('#3A5C70');
      expect(rampHex(-4)).toBe('#3A5C70');
      expect(rampHex(6)).toBe('#C82820');
      expect(rampHex(99)).toBe('#C82820');
    });

    it('sends a non-finite score to the bottom of the ramp in temp mode too', () => {
      setMode('temp');
      [NaN, undefined, Infinity, -Infinity, 'abc', {}, []].forEach((bad) => {
        expect(rampHex(bad)).toBe('#3A5C70');
      });
    });
  });

  describe('scoreFromPercent', () => {
    it('maps lo to 1 and hi to 5, as numbers rather than colours', () => {
      expect(scoreFromPercent(10, 10, 90)).toBe(1);
      expect(scoreFromPercent(90, 10, 90)).toBe(5);
    });

    it('clamps beyond both ends of the domain', () => {
      expect(scoreFromPercent(0, 10, 90)).toBe(1);
      expect(scoreFromPercent(100, 10, 90)).toBe(5);
    });

    it('maps the midpoint to the middle of the score range', () => {
      expect(scoreFromPercent(50, 10, 90)).toBe(3);
    });
  });
});
