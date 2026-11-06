import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  formatPct, formatPts, formatCostMultiple, formatSeconds, formatAgo, formatDayUk,
  comparedTone, barWidthPct, versionMarkers, buildAccuracySeries, laneShortLabel,
  paginateSessions, SESSIONS_SHOWN, modelRunColumns, fixtureRows, cellTitle, historyX,
  dotKind, historyWindow, buildFixtureHistory, chartX, chartY, historyY,
} from '../utils/skyEval.js';

/**
 * Boundary and ordering behaviour of the Sky Eval helpers that {@code skyEval.test.js} leaves open:
 * the exact edges of each formatter's thresholds, the inclusive ends of the chart windows, the tie
 * rules of the two sorts, and the geometry the rating-history chart draws.
 */

const DAY = 24 * 60 * 60 * 1000;

afterEach(() => {
  vi.restoreAllMocks();
});

describe('skyEval edges — formatters at their thresholds', () => {
  it('prints anything from 99.95 up as 100%, including a figure past 100', () => {
    expect(formatPct(99.95)).toBe('100%');
    expect(formatPct(100.4)).toBe('100%');
    expect(formatPct(99.94)).toBe('99.9%');
  });

  it('keeps a real decimal and drops only a trailing .0', () => {
    expect(formatPct(50)).toBe('50%');
    expect(formatPct(70.5)).toBe('70.5%');
    expect(formatPts(5)).toBe('+5 pts');
    expect(formatPts(-1.5)).toBe('−1.5 pts');
  });

  it('uses two decimals strictly below a multiple of one', () => {
    expect(formatCostMultiple(0.15)).toBe('0.15× cost');
    expect(formatCostMultiple(1)).toBe('1.0× cost');
    expect(formatCostMultiple(3.33)).toBe('3.3× cost');
  });

  it('switches to minutes at exactly sixty seconds', () => {
    expect(formatSeconds(59)).toBe('59s');
    expect(formatSeconds(60)).toBe('1m 00s');
    expect(formatSeconds(135)).toBe('2m 15s');
  });

  it('reads exactly one minute as 1m ago and exactly an hour as 1h ago', () => {
    const now = new Date('2026-10-20T12:00:00Z');
    expect(formatAgo(new Date(now.getTime() - 59000).toISOString(), now)).toBe('just now');
    expect(formatAgo(new Date(now.getTime() - 60000).toISOString(), now)).toBe('1m ago');
    expect(formatAgo(new Date(now.getTime() - 59 * 60000).toISOString(), now)).toBe('59m ago');
    expect(formatAgo(new Date(now.getTime() - 60 * 60000).toISOString(), now)).toBe('1h ago');
  });

  it('gives an empty day label for an unusable instant', () => {
    expect(formatDayUk(null)).toBe('');
    expect(formatDayUk('not a date')).toBe('');
  });

  it('drops the comma some ICU versions put after the weekday, only at the start', () => {
    vi.spyOn(Date.prototype, 'toLocaleString').mockReturnValue('Mon, 2 Nov');
    expect(formatDayUk('2026-11-02T09:00:00Z')).toBe('Mon 2 Nov');
  });

  it('treats a difference of exactly half a point as neutral', () => {
    expect(comparedTone(0.5)).toBe('neutral');
    expect(comparedTone(-0.5)).toBe('neutral');
    expect(comparedTone(0.6)).toBe('better');
    expect(comparedTone(-0.6)).toBe('worse');
  });

  it('gives an empty bar for a missing figure, undefined as well as null', () => {
    expect(barWidthPct(undefined)).toBe(0);
    expect(barWidthPct(null)).toBe(0);
    expect(barWidthPct(85)).toBe(50);
  });

  it('shows every session when asked, however many there are', () => {
    const sessions = Array.from({ length: SESSIONS_SHOWN + 2 }, (_, i) => ({ id: i }));
    expect(paginateSessions(sessions, true)).toEqual({ shown: sessions, hidden: 0 });
    expect(paginateSessions(sessions, false).hidden).toBe(2);
  });

  it('only keeps the first three words of a lane label, and strips "forecasts" only at the end', () => {
    expect(laneShortLabel('T and T+1 forecasts')).toBe('T and T+1');
    expect(laneShortLabel('A forecasts B C')).toBe('A forecasts B');
  });
});

describe('skyEval edges — the accuracy chart window', () => {
  const startMs = Date.parse('2026-10-01T00:00:00Z');
  const endMs = startMs + 10 * DAY;
  const at = (ms) => new Date(ms).toISOString();

  it('labels a version marker from its short id, falling back to the full sha', () => {
    const markers = versionMarkers([
      { sha: 'abcd1234', shortSha: 'zzzz9999', message: 'm', committedAt: at(startMs + DAY) },
      { sha: 'beef5678', shortSha: null, message: null, committedAt: at(startMs + 2 * DAY) },
    ], startMs, endMs);
    expect(markers.map((m) => m.label)).toEqual(['zzzz', 'beef']);
  });

  it('keeps a version exactly on either edge and drops one just outside', () => {
    const markers = versionMarkers([
      { sha: 'aaaa0001', shortSha: 'aaaa0001', committedAt: at(startMs - 1000) },
      { sha: 'bbbb0002', shortSha: 'bbbb0002', committedAt: at(startMs) },
      { sha: 'cccc0003', shortSha: 'cccc0003', committedAt: at(endMs) },
      { sha: 'dddd0004', shortSha: 'dddd0004', committedAt: at(endMs + 1000) },
    ], startMs, endMs);
    expect(markers.map((m) => m.sha)).toEqual(['bbbb0002', 'cccc0003']);
  });

  describe('accuracy series', () => {
    const models = [{ model: 'HAIKU', lanes: [] }];
    const session = (ms, pct, extra = {}) => ({
      startedAt: at(ms), trigger: 'SCHEDULED', version: 'abc', byModel: [{ model: 'HAIKU', inBandPct: pct, runCount: 1 }], ...extra,
    });

    it('draws a polyline through every point, as x,y pairs joined by spaces', () => {
      const [line] = buildAccuracySeries(
        [session(startMs + DAY, 80), session(startMs + 2 * DAY, 90)], models, null, startMs, endMs,
      );
      const p1 = `${chartX(startMs + DAY, startMs, endMs)},${chartY(80)}`;
      const p2 = `${chartX(startMs + 2 * DAY, startMs, endMs)},${chartY(90)}`;
      expect(line.polyline).toBe(`${p1} ${p2}`);
    });

    it('skips a session whose start cannot be read', () => {
      const [line] = buildAccuracySeries(
        [session(startMs + DAY, 80), { ...session(0, 70), startedAt: null }], models, null, startMs, endMs,
      );
      expect(line.points).toHaveLength(1);
    });

    it('keeps a session exactly on either edge and drops one just outside', () => {
      const [line] = buildAccuracySeries([
        session(startMs - 1000, 71), session(startMs, 72), session(endMs, 73), session(endMs + 1000, 74),
      ], models, null, startMs, endMs);
      expect(line.points.map((p) => p.pct)).toEqual([72, 73]);
    });

    it('draws no line for a model when a session carries no per-model figures', () => {
      const bare = { startedAt: at(startMs + DAY), trigger: 'SCHEDULED', version: 'abc' };
      expect(buildAccuracySeries([bare], models, null, startMs, endMs)).toEqual([]);
    });

    it('does not emphasise a model with no lanes field when nothing is highlighted', () => {
      const [line] = buildAccuracySeries(
        [session(startMs + DAY, 80)], [{ model: 'HAIKU' }], null, startMs, endMs,
      );
      expect(line.emphasised).toBe(false);
    });
  });
});

describe('skyEval edges — the Fixtures grid columns and rows', () => {
  it('orders runs that share a start time by run id, and reads an unusable start as 0', () => {
    const sessions = [{
      startedAt: null,
      trigger: 'SCHEDULED',
      version: 'aaa',
      runs: [{ id: 5, model: 'M' }, { id: 3, model: 'M' }, { id: 4, model: 'M' }],
    }];
    const runFixtures = [3, 4, 5].map((runId) => ({ runId, fixture: 'F', n: 1 }));
    const columns = modelRunColumns(sessions, runFixtures, 'M');
    expect(columns.map((c) => c.runId)).toEqual([3, 4, 5]);
    expect(columns.map((c) => c.ms)).toEqual([0, 0, 0]);
  });

  describe('fixtureRows', () => {
    const fixtures = ['a', 'b', 'c'].map((name) => ({ name, bandLo: 3, bandHi: 4 }));
    const figure = (fixture, last4Pct, last4Misses) => ({ model: 'M', fixture, last4Pct, last4Misses });
    const order = (rows) => rows.map((r) => r.fixture.name);

    it('puts the fixture with the most misses first when the percentages tie', () => {
      const rows = fixtureRows(fixtures, [figure('a', 90, 1), figure('b', 90, 3), figure('c', 90, 2)], 'M');
      expect(order(rows)).toEqual(['b', 'c', 'a']);
    });

    it('puts a fixture with a figure ahead of one with none, when neither has a percentage', () => {
      const withFigure = [figure('b', null, 0)];
      expect(order(fixtureRows(fixtures.slice(0, 2), withFigure, 'M'))).toEqual(['b', 'a']);
      expect(order(fixtureRows([fixtures[1], fixtures[0]], withFigure, 'M'))).toEqual(['b', 'a']);
    });

    it('keeps registry order for fixtures that tie on everything', () => {
      expect(order(fixtureRows(fixtures, [], 'M'))).toEqual(['a', 'b', 'c']);
      const same = fixtures.map((f) => figure(f.name, 80, 2));
      expect(order(fixtureRows(fixtures, same, 'M'))).toEqual(['a', 'b', 'c']);
    });
  });
});

describe('skyEval edges — cell titles and dot kinds', () => {
  const fixture = { name: 'F', bandLo: 3, bandHi: 4 };
  const column = { startedAt: '2026-10-01T12:00:00Z', manual: false, version: 'abcdef1234' };

  it('says a run without the fixture, or with no attempts, did not include it', () => {
    expect(cellTitle(fixture, column, undefined)).toBe('F, Thu 1 Oct, abcdef1: not in this run');
    expect(cellTitle(fixture, column, { n: 0 })).toBe('F, Thu 1 Oct, abcdef1: not in this run');
  });

  it('lists only the counts that are non-zero', () => {
    const rf = {
      n: 5, inBand: 3, below: 0, above: 2, mean: 3.14,
    };
    expect(cellTitle(fixture, column, rf)).toBe('F, Thu 1 Oct, abcdef1: 3/5 in band, 2 above, mean rating 3.1, band 3–4');
  });

  it('breaks a tie between below and above toward below', () => {
    expect(dotKind({
      n: 3, inBand: 1, below: 1, above: 1,
    })).toBe('below');
    expect(dotKind({
      n: 3, inBand: 1, below: 0, above: 2,
    })).toBe('above');
  });
});

describe('skyEval edges — the rating-history chart', () => {
  it('maps a moment linearly between the margins, and pins a collapsed axis to the left', () => {
    expect(historyX(150, 100, 200)).toBe(361);
    expect(historyX(100, 100, 200)).toBe(34);
    expect(historyX(5, 10, 10)).toBe(34);
  });

  it('has a unit window when there are no runs', () => {
    expect(historyWindow([])).toEqual({ startMs: 0, endMs: 1 });
  });

  describe('buildFixtureHistory', () => {
    const fixture = { name: 'F', bandLo: 3, bandHi: 4 };
    const last = Date.UTC(2026, 6, 31, 12);
    const start = last - 32 * DAY;
    const end = last + DAY;
    const at = (ms) => new Date(ms).toISOString();
    const column = (runId, ms, rf) => ({
      runId, ms, startedAt: at(ms), manual: false, version: '', byFixture: new Map([['F', rf]]),
    });
    const figures = {
      n: 3, inBand: 3, below: 0, above: 0, mean: 3.5, min: 3, max: 4,
    };
    const versions = [
      { sha: 'aaaa0001', shortSha: 'aaaa0001', message: null, committedAt: at(start - 1000) },
      {
        sha: 'abcd1234', shortSha: 'zzzz9999', message: 'first', committedAt: at(start),
      },
      {
        sha: 'mid00000', shortSha: 'mid00000', message: null, committedAt: at(Date.UTC(2026, 6, 15, 10)),
      },
      { sha: 'beef5678', shortSha: null, message: null, committedAt: at(end) },
      { sha: 'dddd0004', shortSha: 'dddd0004', message: null, committedAt: at(end + 1000) },
    ];
    const buildHistory = () => buildFixtureHistory(
      fixture,
      [column(1, last, figures), column(0, last - 40 * DAY, { n: 0, inBand: 0, below: 0, above: 0 })],
      versions,
    );

    it('draws a point only for a run that included the fixture', () => {
      const history = buildHistory();
      expect(history.points.map((p) => p.runId)).toEqual([1]);
      expect(history.points[0]).toMatchObject({
        x: historyX(last, start, end), y: historyY(3.5), yMin: historyY(3), yMax: historyY(4), kind: 'model',
      });
    });

    it('draws the band from the left margin to the right, around the band limits', () => {
      const history = buildHistory();
      expect(history.band).toEqual({
        x: 34, y: historyY(4.5), width: 654, height: historyY(2.5) - historyY(4.5),
      });
    });

    it('keeps the versions on either edge of the window, with alternating label heights', () => {
      const history = buildHistory();
      expect(history.markers).toEqual([
        {
          sha: 'abcd1234', label: 'zzzz', x: 34, labelY: 9, title: 'zzzz9999 · first · Mon 29 Jun',
        },
        {
          sha: 'mid00000', label: 'mid0', x: historyX(Date.UTC(2026, 6, 15, 10), start, end), labelY: 18, title: 'mid00000 · Wed 15 Jul',
        },
        {
          sha: 'beef5678', label: 'beef', x: 688, labelY: 9, title: 'beef5678 · Sat 1 Aug',
        },
      ]);
    });

    it('spaces five date ticks evenly with the end ticks anchored outward', () => {
      const history = buildHistory();
      expect(history.ticks).toEqual([
        { x: 34, label: '29 Jun', anchor: 'start' },
        { x: 197.5, label: '7 Jul', anchor: 'middle' },
        { x: 361, label: '16 Jul', anchor: 'middle' },
        { x: 524.5, label: '24 Jul', anchor: 'middle' },
        { x: 688, label: '1 Aug', anchor: 'end' },
      ]);
    });
  });
});
