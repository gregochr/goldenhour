import {
  describe, it, expect, afterEach,
} from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, URL as NodeURL } from 'node:url';
import {
  STOPS_VERDICT, RAMP_MIN, RAMP_MAX, rampRgb, rampHex, rgb, setMode, getMode,
  rampGradientCss, ANCHORS, starFromScore,
} from '../utils/scoreRamp.js';

/**
 * The ramp is the single source of the v2 colour language — the canvas kernel reads the triples,
 * the DOM consumers read the hex. Both come from one literal per stop, so these tests exist to
 * pin the literals themselves (a mistyped hex is invisible on a blurred field) and the
 * interpolation between them (a stop reordering or an off-by-one in the segment search would
 * still produce plausible colours).
 */
/**
 * `scoreFromPercent` was the linear lo/hi map Stage 1 shipped; Stage 4 found the two 0–100
 * metrics bimodal and superseded it with `starFromScore` + `ANCHORS`, and this stage deletes it.
 * A plain "does scoreRamp.js still export it" check only guards the one file that removed it —
 * this instead sweeps every source file, so a stray import left behind anywhere else fails loudly
 * rather than silently referencing a name that no longer exists. Kept alongside, not instead of,
 * the direct "scoreRamp.js itself no longer exports it" check: a function left defined-and-exported
 * but simply unreferenced would pass the sweep but not that check.
 */
describe('scoreFromPercent is fully deleted', () => {
  const walk = (dir) => readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) return walk(p);
    return /\.(js|jsx)$/.test(entry.name) ? [p] : [];
  });

  // Matches `import { scoreFromPercent } from '...'`, `import Foo, { scoreFromPercent } ...` and
  // `export { scoreFromPercent } from '...'` (a barrel re-export) alike — anything between the
  // `import`/`export` keyword and the brace block is unconstrained but for `;`/`{`, so a default
  // import ahead of the named one does not defeat the match.
  const IMPORTS_SCORE_FROM_PERCENT = /\b(?:import|export)\b[^;{]*\{[^}]*\bscoreFromPercent\b[^}]*\}/;

  // Explicitly `node:url`'s `URL`, not the global one: jsdom's own `URL` resolves a relative
  // reference against `document.location` rather than the given base, silently turning a
  // `file:` URL into `http://localhost:3000/...` and making `fileURLToPath` throw below.
  const srcDir = fileURLToPath(new NodeURL('..', import.meta.url));
  const thisFile = fileURLToPath(import.meta.url);
  const offenders = walk(srcDir).filter((f) => {
    // Only flags an actual import/re-export of the name — this file's own doc comments and
    // string literals mention "scoreFromPercent" by name deliberately (including as regex-test
    // fixture strings below) and must not trip this, hence the self-exclusion.
    if (f === thisFile) return false;
    // Read straight through rather than stat-then-read: `walk` already returns only
    // non-directory entries, so the stat was redundant, and check-then-use on a path is a
    // file-system race CodeQL flags (js/file-system-race). Doing the operation and letting it
    // throw is both correct and the sanctioned shape.
    return IMPORTS_SCORE_FROM_PERCENT.test(readFileSync(f, 'utf8'));
  });

  it('has no surviving import anywhere in the frontend source', () => {
    expect(offenders).toEqual([]);
  });

  it('is no longer exported by scoreRamp.js itself', async () => {
    const scoreRamp = await import('../utils/scoreRamp.js');
    expect(scoreRamp.scoreFromPercent).toBeUndefined();
  });

  it('the sweep regex catches a default+named import and a barrel re-export', () => {
    // Pinning the regex's own reach: these are the two forms the plain `import\s*\{...\}` pattern
    // this test started with could not see.
    expect(IMPORTS_SCORE_FROM_PERCENT.test("import Foo, { scoreFromPercent } from '../utils/scoreRamp.js';")).toBe(true);
    expect(IMPORTS_SCORE_FROM_PERCENT.test("export { scoreFromPercent } from '../utils/scoreRamp.js';")).toBe(true);
  });
});

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

  describe('starFromScore', () => {
    /**
     * `starFromScore` replaces the deleted linear `scoreFromPercent` map: the two 0–100 metrics
     * are bimodal, so a frozen piecewise table per metric (`ANCHORS`) is used instead of a
     * two-point lo/hi map. See `docs/engineering/heat-scale-unification-plan.md` Stage 4.
     */
    it('maps every fiery anchor to its own star value exactly', () => {
      ANCHORS.fiery.forEach(([value, score]) => {
        expect(starFromScore(value, 'fiery')).toBe(score);
      });
    });

    it('maps every golden anchor to its own star value exactly', () => {
      ANCHORS.golden.forEach(([value, score]) => {
        expect(starFromScore(value, 'golden')).toBe(score);
      });
    });

    it('interpolates linearly at the midpoint of a fiery segment', () => {
      // Midpoint of [35, 2.4]-[50, 2.8]: 2.4 + 0.4 * (7.5/15) = 2.6.
      expect(starFromScore(42.5, 'fiery')).toBeCloseTo(2.6, 10);
    });

    it('interpolates linearly at the midpoint of a golden segment', () => {
      // Midpoint of [40, 2.4]-[55, 3]: 2.4 + 0.6 * (7.5/15) = 2.7.
      expect(starFromScore(47.5, 'golden')).toBeCloseTo(2.7, 10);
    });

    it('clamps below 0 to a star of 1', () => {
      expect(starFromScore(-1, 'fiery')).toBe(1);
      expect(starFromScore(-100, 'golden')).toBe(1);
    });

    it('clamps above 100 to a star of 5', () => {
      expect(starFromScore(101, 'fiery')).toBe(5);
      expect(starFromScore(1000, 'golden')).toBe(5);
    });

    /**
     * The most important assertion here. The reference kernel's loop falls out the bottom to a
     * trailing `return 5` for a value that fails every `<=` comparison — which is exactly what a
     * NaN does. Painting a missing reading as the ramp's hottest colour is the false-confidence
     * failure `rampRgb`'s own non-finite guard already exists to prevent; this must match it.
     */
    it('resolves a non-finite value to 1, never to 5', () => {
      [NaN, undefined, Infinity, -Infinity, 'abc', {}, []].forEach((bad) => {
        expect(starFromScore(bad, 'fiery')).toBe(1);
        expect(starFromScore(bad, 'golden')).toBe(1);
      });
    });

    it('gives fiery and golden genuinely different answers for the same reading', () => {
      // v=80 sits in fiery's [72,4]-[85,4.7] segment and golden's [70,3.8]-[85,4.6] segment.
      const fiery = starFromScore(80, 'fiery');
      const golden = starFromScore(80, 'golden');
      expect(fiery).toBeCloseTo(4.4308, 3);
      expect(golden).toBeCloseTo(4.3333, 3);
      expect(fiery).not.toBeCloseTo(golden, 3);
    });

    it('throws for an unrecognised metric rather than silently falling back to fiery', () => {
      expect(() => starFromScore(50, 'bogus')).toThrow();
      expect(() => starFromScore(50, undefined)).toThrow();
    });

    it('throws for a metric string that collides with an Object.prototype member', () => {
      // `ANCHORS[metric]` alone walks the prototype chain: 'toString', 'constructor' and
      // 'hasOwnProperty' all resolve to a truthy non-array value on a plain object, which would
      // defeat a bare truthiness guard and fall through the interpolation loop's empty body to
      // its trailing `return 5` — an unthrown top-of-ramp result for a metric that was never
      // recognised. `Object.hasOwn` is what closes this.
      ['toString', 'constructor', 'hasOwnProperty', '__proto__'].forEach((metric) => {
        expect(() => starFromScore(50, metric)).toThrow();
      });
    });

    describe('the anchor tables are monotonic in both axes', () => {
      Object.entries(ANCHORS).forEach(([metric, anchors]) => {
        it(`${metric}: value and score both strictly increase anchor to anchor`, () => {
          for (let i = 1; i < anchors.length; i += 1) {
            const [prevValue, prevScore] = anchors[i - 1];
            const [value, score] = anchors[i];
            expect(value).toBeGreaterThan(prevValue);
            expect(score).toBeGreaterThan(prevScore);
          }
        });

        it(`${metric}: starFromScore is non-decreasing across the whole 0-100 domain`, () => {
          let prev = starFromScore(0, metric);
          for (let v = 1; v <= 100; v += 1) {
            const cur = starFromScore(v, metric);
            expect(cur).toBeGreaterThanOrEqual(prev);
            prev = cur;
          }
        });
      });
    });
  });

  describe('rampGradientCss', () => {
    afterEach(() => setMode('verdict'));

    it('positions verdict stops at their scores, which happen to equal their indices', () => {
      // Five evenly spaced stops, so score- and index-positioning coincide. This is exactly why
      // the index bug was invisible: every verdict-mode assertion passes either way.
      const css = rampGradientCss();
      ['0.0%', '25.0%', '50.0%', '75.0%', '100.0%'].forEach((pos) => {
        expect(css).toContain(pos);
      });
    });

    it('positions temperature stops by SCORE, not by index', () => {
      setMode('temp');
      const css = rampGradientCss();
      // (score - 1) / 4 * 100 for each of the eight uneven stops.
      [['#3A5C70', '0.0%'], ['#506878', '30.0%'], ['#928C80', '45.0%'], ['#C49440', '50.0%'],
        ['#C99230', '55.0%'], ['#DF6B2A', '72.5%'], ['#DE4826', '82.5%'], ['#C82820', '100.0%']]
        .forEach(([hex, pos]) => expect(css).toContain(`${hex} ${pos}`));
    });

    it('never places the 2.2 stop at its index position', () => {
      // The regression this guards: index positioning puts 2.2 at 14%, 16pp from where it belongs.
      setMode('temp');
      expect(rampGradientCss()).not.toContain('#506878 14');
    });
  });
});
