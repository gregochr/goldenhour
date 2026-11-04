import { describe, it, expect } from 'vitest';
import {
  CELL_FILL, GRID_RUNS, HISTORY, buildFixtureHistory, cellState, cellTitle, defaultFixtureModel, dotKind,
  fixtureModels, fixtureRows, historyWindow, historyX, historyY, modelRunColumns,
  CHART, barWidthPct, buildAccuracySeries, changeTone, chartWindow, chartX, chartY, comparedTone, estimateCheck,
  formatAgo, formatClockUk, formatCostMultiple, formatDayUk, formatGbp, formatPct, formatPts, formatSeconds,
  formatShortDateUk, isKnownTab, laneBarState, laneShortLabel, modelColour, paginateSessions, passTone,
  pointTitle, swatchClass, timeTicks, triggerLabel, versionMarkers,
} from '../utils/skyEval.js';

describe('formatting', () => {
  it('prints percentages with one decimal, dropping ".0", and 100 for anything that rounds to it', () => {
    expect(formatPct(97.5)).toBe('97.5%');
    expect(formatPct(95)).toBe('95%');
    expect(formatPct(99.96)).toBe('100%');
    expect(formatPct(99.94)).toBe('99.9%');
    expect(formatPct(null)).toBe('—');
  });

  it('prints points with a real minus sign', () => {
    expect(formatPts(1.9)).toBe('+1.9 pts');
    expect(formatPts(-5.2)).toBe('−5.2 pts');
    expect(formatPts(-1)).toBe('−1 pts');
    expect(formatPts(0)).toBe('+0 pts');
    expect(formatPts(null)).toBe('—');
  });

  it('prints pounds, cost multiples and durations', () => {
    expect(formatGbp(0.284)).toBe('£0.28');
    expect(formatGbp(null)).toBe('—');
    expect(formatCostMultiple(0.15)).toBe('0.15× cost');
    expect(formatCostMultiple(3.27)).toBe('3.3× cost');
    expect(formatCostMultiple(null)).toBe('');
    expect(formatSeconds(135)).toBe('2m 15s');
    expect(formatSeconds(45.4)).toBe('45s');
    expect(formatSeconds(125)).toBe('2m 05s');
    expect(formatSeconds(null)).toBe('—');
  });

  it('measures ages against the supplied now', () => {
    const now = new Date('2026-11-02T09:26:00Z');
    expect(formatAgo('2026-11-02T09:25:40Z', now)).toBe('just now');
    expect(formatAgo('2026-11-02T08:36:00Z', now)).toBe('50m ago');
    expect(formatAgo('2026-11-01T12:26:00Z', now)).toBe('21h ago');
    expect(formatAgo('2026-10-25T09:26:00Z', now)).toBe('8d ago');
    expect(formatAgo(null, now)).toBe('');
  });

  it('renders days and clock times on the UK calendar, whatever the reader\'s zone', () => {
    // 23:30 UTC on 1 Nov is 23:30 GMT; 04:00 UTC on 20 Jun is 05:00 BST.
    expect(formatDayUk('2026-11-02T08:36:00Z')).toBe('Mon 2 Nov');
    expect(formatClockUk('2026-06-20T04:00:00Z')).toBe('05:00');
    expect(formatShortDateUk('2026-06-20T03:00:00')).toBe('20 Jun');
    expect(formatShortDateUk(Date.UTC(2026, 5, 19, 23, 30))).toBe('20 Jun');
    expect(formatShortDateUk(null)).toBe('');
  });

  it('shows the stored SCHEDULED trigger as WEEKLY', () => {
    expect(triggerLabel('SCHEDULED')).toBe('WEEKLY');
    expect(triggerLabel('MANUAL')).toBe('MANUAL');
  });

  it('shortens a lane label for the All models table', () => {
    expect(laneShortLabel('T and T+1 forecasts')).toBe('T and T+1');
    expect(laneShortLabel('T+2 and T+3 look-ahead')).toBe('T+2 and T+3');
  });
});

describe('tones', () => {
  it('colours a pass rate ink at 97, amber at 92, coral below', () => {
    expect(passTone(97)).toBe('ink');
    expect(passTone(96.9)).toBe('amber');
    expect(passTone(92)).toBe('amber');
    expect(passTone(91.9)).toBe('coral');
    expect(passTone(null)).toBe('none');
  });

  it('flags a change of 3 points either way', () => {
    expect(changeTone(-3)).toBe('bad');
    expect(changeTone(-2.9)).toBe('neutral');
    expect(changeTone(3)).toBe('good');
    expect(changeTone(null)).toBe('neutral');
  });

  it('colours the compared difference green when better and amber when worse', () => {
    expect(comparedTone(5.3)).toBe('better');
    expect(comparedTone(-2.5)).toBe('worse');
    expect(comparedTone(0.2)).toBe('neutral');
  });

  it('maps 70 to 100 percent onto the bar width, clamped', () => {
    expect(barWidthPct(70)).toBe(0);
    expect(barWidthPct(85)).toBe(50);
    expect(barWidthPct(100)).toBe(100);
    expect(barWidthPct(60)).toBe(0);
    expect(barWidthPct(null)).toBe(0);
  });

  it('gives a model its palette colour and a neutral fallback for any other', () => {
    expect(modelColour('SONNET_55')).toBe('oklch(.74 .14 340)');
    expect(modelColour('SOMETHING_NEW')).toBe('oklch(.7 .02 70)');
    expect(swatchClass('OPUS')).toBe('bg-[oklch(.82_.12_85)]');
    expect(swatchClass('SOMETHING_NEW')).toBe('bg-[oklch(.7_.02_70)]');
  });

  it('maps a lane model status to its bar state', () => {
    expect(laneBarState({ status: 'HELD' })).toBe('held');
    expect(laneBarState({ status: 'DROPPED' })).toBe('dropped');
    expect(laneBarState({ status: 'UNCHECKED' })).toBe('unchecked');
    expect(laneBarState({ status: 'NO_BASELINE' })).toBe('baseline');
    expect(laneBarState(undefined)).toBe('none');
  });

  it('knows the three tabs', () => {
    expect(isKnownTab('runs')).toBe(true);
    expect(isKnownTab('<script>')).toBe(false);
    expect(isKnownTab(null)).toBe(false);
  });
});

describe('estimateCheck', () => {
  const stats = [
    { model: 'A', costPerRatingGbp: 0.01, secondsPerRating: 2 },
    { model: 'B', costPerRatingGbp: 0.002, secondsPerRating: 0.5 },
    { model: 'C', costPerRatingGbp: null, secondsPerRating: null },
  ];

  it('sums cost across models and takes the slowest model for the time', () => {
    // 10 fixtures x 3 runs = 30 ratings per model: A = 0.30 and 60 s, B = 0.06 and 15 s.
    const e = estimateCheck(['A', 'B'], stats, 10, 3);
    expect(e.ratings).toBe(60);
    expect(e.costGbp).toBeCloseTo(0.36, 10);
    expect(e.seconds).toBe(60);
  });

  it('scales with the fixture subset and the repeats', () => {
    const e = estimateCheck(['B'], stats, 1, 8);
    expect(e.ratings).toBe(8);
    expect(e.costGbp).toBeCloseTo(0.016, 10);
    expect(e.seconds).toBe(4);
  });

  it('says "no estimate" (null) when a chosen model has no history, rather than a false figure', () => {
    const e = estimateCheck(['A', 'C'], stats, 10, 3);
    expect(e.costGbp).toBeNull();
    expect(e.seconds).toBeNull();
    expect(e.ratings).toBe(60);
  });

  it('has no estimate for an empty selection', () => {
    expect(estimateCheck([], stats, 10, 3)).toEqual({ ratings: 0, costGbp: null, seconds: null });
  });
});

describe('chart geometry', () => {
  const now = new Date('2026-11-02T09:26:00Z');
  const { startMs, endMs } = chartWindow(now);

  it('covers 125 days ending now', () => {
    expect(endMs).toBe(now.getTime());
    expect(endMs - startMs).toBe(125 * 24 * 3600 * 1000);
  });

  it('maps the window edges to the plot edges', () => {
    expect(chartX(startMs, startMs, endMs)).toBe(CHART.left);
    expect(chartX(endMs, startMs, endMs)).toBe(CHART.width - CHART.right);
  });

  it('maps 100 to the top of the plot, 70 to the bottom and clamps anything lower', () => {
    expect(chartY(100)).toBe(CHART.top);
    expect(chartY(70)).toBe(CHART.height - CHART.bottom);
    expect(chartY(40)).toBe(chartY(70));
    expect(chartY(85)).toBeCloseTo((chartY(100) + chartY(70)) / 2, 10);
  });

  it('places six ticks, anchored start, middle and end', () => {
    const ticks = timeTicks(startMs, endMs);
    expect(ticks).toHaveLength(6);
    expect(ticks.map((t) => t.anchor)).toEqual(['start', 'middle', 'middle', 'middle', 'middle', 'end']);
    expect(ticks[5].label).toBe('2 Nov');
  });

  it('keeps only versions inside the window, oldest first, with alternating label heights', () => {
    const versions = [
      { sha: 'aaaa1111', shortSha: 'aaaa111', message: 'newest', committedAt: '2026-11-02T07:00:00Z' },
      { sha: 'bbbb2222', shortSha: 'bbbb222', message: 'middle', committedAt: '2026-10-01T07:00:00Z' },
      { sha: 'cccc3333', shortSha: 'cccc333', message: 'ancient', committedAt: '2026-01-01T07:00:00Z' },
      { sha: 'dddd4444', shortSha: 'dddd444', message: 'undated', committedAt: null },
    ];
    const markers = versionMarkers(versions, startMs, endMs);
    expect(markers.map((m) => m.label)).toEqual(['bbbb', 'aaaa']);
    expect(markers[0].labelY).toBe(CHART.top - 18);
    expect(markers[1].labelY).toBe(CHART.top - 18 - 11);
    expect(markers[0].x).toBeLessThan(markers[1].x);
    expect(markers[1].title).toBe('aaaa111 · newest · Mon 2 Nov');
  });

  describe('accuracy series', () => {
    const models = [
      { model: 'SONNET_55', lanes: ['BATCH_NEAR_TERM'] },
      { model: 'OPUS', lanes: [] },
      { model: 'HAIKU', lanes: [] },
    ];
    const sessions = [
      {
        trigger: 'MANUAL', startedAt: '2026-11-02T08:00:00Z', version: 'a91b0e2aaaa',
        byModel: [{ model: 'SONNET_55', runCount: 2, inBandPct: 99.4 }],
      },
      {
        trigger: 'SCHEDULED', startedAt: '2026-10-25T04:00:00Z', version: null,
        byModel: [
          { model: 'SONNET_55', runCount: 1, inBandPct: 97.5 },
          { model: 'OPUS', runCount: 1, inBandPct: 98.8 },
          { model: 'HAIKU', runCount: 1, inBandPct: null },
        ],
      },
      {
        trigger: 'SCHEDULED', startedAt: '2025-01-01T04:00:00Z', version: null,
        byModel: [{ model: 'SONNET_55', runCount: 1, inBandPct: 50 }],
      },
    ];

    it('draws one point per session, oldest first, and drops null rates and sessions outside the window', () => {
      const series = buildAccuracySeries(sessions, models, [], startMs, endMs);
      const sonnet = series.find((s) => s.model === 'SONNET_55');
      expect(sonnet.points.map((p) => p.pct)).toEqual([97.5, 99.4]);
      expect(series.find((s) => s.model === 'HAIKU')).toBeUndefined();
    });

    it('marks manual points and keeps the session trigger and version for the tooltip', () => {
      const sonnet = buildAccuracySeries(sessions, models, [], startMs, endMs).find((s) => s.model === 'SONNET_55');
      expect(sonnet.points.map((p) => p.manual)).toEqual([false, true]);
      expect(sonnet.points[1].runCount).toBe(2);
      expect(sonnet.points[1].version).toBe('a91b0e2aaaa');
    });

    it('emphasises production models by default, and draws them last', () => {
      const series = buildAccuracySeries(sessions, models, [], startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['OPUS', false], ['SONNET_55', true]]);
    });

    it('emphasises only the highlighted model when one is chosen', () => {
      const series = buildAccuracySeries(sessions, models, ['OPUS'], startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['SONNET_55', false], ['OPUS', true]]);
    });

    it('treats a null highlight list as the production default', () => {
      const series = buildAccuracySeries(sessions, models, null, startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['OPUS', false], ['SONNET_55', true]]);
    });

    it('emphasises every highlighted model, not the rest, and draws them last', () => {
      const three = [{ ...models[0] }, { ...models[1] }, { model: 'HAIKU', lanes: [] }];
      const all = [
        {
          trigger: 'SCHEDULED', startedAt: '2026-10-25T04:00:00Z', version: null,
          byModel: [
            { model: 'OPUS', runCount: 1, inBandPct: 90 },
            { model: 'SONNET_55', runCount: 1, inBandPct: 95 },
            { model: 'HAIKU', runCount: 1, inBandPct: 85 },
          ],
        },
      ];
      const series = buildAccuracySeries(all, three, ['OPUS', 'SONNET_55'], startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['HAIKU', false], ['SONNET_55', true], ['OPUS', true]]);
    });

    it('clamps a rate below the axis floor to the floor', () => {
      const low = [{
        trigger: 'SCHEDULED', startedAt: '2026-10-25T04:00:00Z', version: null,
        byModel: [{ model: 'OPUS', runCount: 1, inBandPct: 40 }],
      }];
      const series = buildAccuracySeries(low, models, [], startMs, endMs);
      expect(series[0].points[0].y).toBe(chartY(70));
    });

    it('writes a point tooltip with model, rate, date, trigger, runs and version', () => {
      const p = { pct: 99.4, trigger: 'MANUAL', runCount: 2, startedAt: '2026-11-02T08:00:00Z', version: 'a91b0e2aaaa' };
      expect(pointTitle('SONNET_55', p)).toBe('Sonnet 5.5 · 99.4%\nMon 2 Nov · manual · 2 runs\nversion a91b0e2');
      expect(pointTitle('HAIKU', { ...p, trigger: 'SCHEDULED', runCount: 1, version: null }))
        .toBe('Haiku 4.5 · 99.4%\nMon 2 Nov · weekly\nversion unknown version');
    });
  });
});

describe('paginateSessions', () => {
  const make = (n) => Array.from({ length: n }, (_, i) => ({ id: i }));

  it('shows ten and counts the rest', () => {
    expect(paginateSessions(make(20), false)).toEqual({ shown: make(10), hidden: 10 });
  });

  it('shows everything when asked, or when there are ten or fewer', () => {
    expect(paginateSessions(make(20), true).shown).toHaveLength(20);
    expect(paginateSessions(make(10), false)).toEqual({ shown: make(10), hidden: 0 });
  });
});

describe('Fixtures grid helpers', () => {
  const rf = (runId, fixture, o = {}) => ({
    runId, fixture, n: 8, inBand: 8, below: 0, above: 0, mean: 3, min: 3, max: 3, ...o,
  });
  const run = (id, model) => ({ id, model });
  const sess = (id, startedAt, version, trigger, runs) => ({
    id, startedAt, version, trigger, runs,
  });

  describe('cellState', () => {
    it('is absent without a row, or with no attempts', () => {
      expect(cellState(undefined)).toMatchObject({ kind: 'absent', fill: null, text: '·' });
      expect(cellState({ n: 0, below: 0, above: 0 })).toMatchObject({ kind: 'absent' });
    });

    it('is soft green at .2 when every rating is in band', () => {
      expect(cellState(rf(1, 'a'))).toEqual({
        kind: 'inband', fill: CELL_FILL.inband, fillOpacity: 0.2, text: '', misses: 0, failed: 0,
      });
    });

    it('is tide when mostly below, with .18 + .6 x the miss ratio', () => {
      const c = cellState(rf(1, 'a', { inBand: 4, below: 4 }));
      expect(c).toMatchObject({ kind: 'below', fill: '#6FA8B0', text: '↓4', misses: 4 });
      expect(c.fillOpacity).toBeCloseTo(0.18 + 0.6 * 0.5, 10);
    });

    it('is coral when mostly above, and names both directions when mixed', () => {
      const c = cellState(rf(1, 'a', { inBand: 4, below: 1, above: 3 }));
      expect(c).toMatchObject({ kind: 'above', fill: '#E8593F', text: '↓1↑3', misses: 4 });
      expect(c.fillOpacity).toBeCloseTo(0.18 + 0.6 * 0.5, 10);
    });

    it('is not "all in band" when attempts failed: a grey ✕k cell, opacity counting the failures', () => {
      const c = cellState(rf(1, 'a', { inBand: 6 }));
      expect(c).toMatchObject({ kind: 'failed', fill: CELL_FILL.failed, text: '✕2', misses: 0, failed: 2 });
      expect(c.fillOpacity).toBeCloseTo(0.18 + 0.6 * (2 / 8), 10);
    });

    it('keeps the miss colour with failures, counting both in the opacity and the text', () => {
      const c = cellState(rf(1, 'a', { inBand: 4, below: 2, above: 0 }));
      expect(c).toMatchObject({ kind: 'below', text: '↓2✕2', failed: 2 });
      expect(c.fillOpacity).toBeCloseTo(0.18 + 0.6 * (4 / 8), 10);
    });

    it('is full strength at .78 when every rating missed, and tide on a tie', () => {
      expect(cellState(rf(1, 'a', { inBand: 0, above: 8 })).fillOpacity).toBeCloseTo(0.78, 10);
      expect(cellState(rf(1, 'a', { inBand: 6, below: 1, above: 1 })).kind).toBe('below');
    });
  });

  describe('modelRunColumns', () => {
    const sessions = [
      sess(3, '2026-11-02T08:00:00Z', 'bbbb2222', 'MANUAL', [run(12, 'HAIKU_55'), run(20, 'SONNET_55')]),
      sess(2, '2026-10-25T04:00:00Z', 'aaaa1111', 'SCHEDULED', [run(11, 'HAIKU_55')]),
      sess(1, '2026-10-18T04:00:00Z', 'aaaa1111', 'SCHEDULED', [run(10, 'HAIKU_55')]),
    ];
    const rows = [rf(10, 'x'), rf(11, 'x'), rf(12, 'x'), rf(20, 'x')];

    it('keeps one model, oldest first, and flags the version change with its 4-character id', () => {
      const cols = modelRunColumns(sessions, rows, 'HAIKU_55');
      expect(cols.map((c) => c.runId)).toEqual([10, 11, 12]);
      expect(cols.map((c) => c.versionChanged)).toEqual([false, false, true]);
      expect(cols.map((c) => c.versionLabel)).toEqual(['', '', 'bbbb']);
      expect(cols.map((c) => c.manual)).toEqual([false, false, true]);
    });

    it('skips runs with no fixture rows, and keeps the newest 13', () => {
      expect(modelRunColumns(sessions, [rf(10, 'x')], 'HAIKU_55').map((c) => c.runId)).toEqual([10]);
      const many = Array.from({ length: 20 }, (_, i) => sess(
        i, `2026-10-${String(i + 1).padStart(2, '0')}T04:00:00Z`, 'v', 'SCHEDULED', [run(100 + i, 'OPUS')],
      ));
      const manyRows = many.map((s) => rf(s.runs[0].id, 'x'));
      const cols = modelRunColumns(many, manyRows, 'OPUS');
      expect(cols).toHaveLength(GRID_RUNS);
      expect(cols[0].runId).toBe(107);
      expect(cols[12].runId).toBe(119);
    });

    it('dates each run by its own startedAt, falling back to its session', () => {
      const s = [sess(1, '2026-10-18T04:00:00Z', 'v', 'MANUAL', [
        { id: 10, model: 'OPUS', startedAt: '2026-10-18T04:30:00Z' }, { id: 11, model: 'OPUS', startedAt: '2026-10-18T04:10:00Z' },
        { id: 12, model: 'OPUS' },
      ])];
      const cols = modelRunColumns(s, [rf(10, 'x'), rf(11, 'x'), rf(12, 'x')], 'OPUS');
      expect(cols.map((c) => c.runId)).toEqual([12, 11, 10]);
      expect(cols[1].startedAt).toBe('2026-10-18T04:10:00Z');
    });

    it('treats a blank version as unknown: a change to or from it has a border but no label', () => {
      const s = [
        sess(1, '2026-10-18T04:00:00Z', '', 'SCHEDULED', [run(10, 'OPUS')]),
        sess(2, '2026-10-25T04:00:00Z', 'cccc3333', 'SCHEDULED', [run(11, 'OPUS')]),
        sess(3, '2026-11-01T04:00:00Z', null, 'SCHEDULED', [run(12, 'OPUS')]),
      ];
      const cols = modelRunColumns(s, [rf(10, 'x'), rf(11, 'x'), rf(12, 'x')], 'OPUS');
      expect(cols.map((c) => c.versionChanged)).toEqual([false, true, true]);
      const stillUnknown = modelRunColumns([s[0], { ...s[2], id: 4 }], [rf(10, 'x'), rf(12, 'x')], 'OPUS');
      expect(stillUnknown.map((c) => c.versionChanged)).toEqual([false, false]);
      expect(cols.map((c) => c.versionLabel)).toEqual(['', 'cccc', '?']);
    });
  });

  describe('fixtureRows', () => {
    const fixtures = [{ name: 'a' }, { name: 'b' }, { name: 'c' }, { name: 'd' }];
    const mf = [
      { model: 'M', fixture: 'a', last4Misses: 1, last4Pct: 96 },
      { model: 'M', fixture: 'b', last4Misses: 5, last4Pct: 84 },
      { model: 'M', fixture: 'c', last4Misses: 1, last4Pct: 97 },
      { model: 'OTHER', fixture: 'd', last4Misses: 9, last4Pct: 50 },
    ];

    it('sorts by last-4 percentage ascending so failed calls count, then misses, then registry order', () => {
      const f = [{ name: 'a' }, { name: 'b' }, { name: 'c' }];
      const m = [
        { model: 'M', fixture: 'a', last4Misses: 2, last4Pct: 90 },
        { model: 'M', fixture: 'b', last4Misses: 0, last4Pct: 60 },
        { model: 'M', fixture: 'c', last4Misses: 2, last4Pct: 90 },
      ];
      expect(fixtureRows(f, m, 'M').map((r) => r.fixture.name)).toEqual(['b', 'a', 'c']);
    });

    it('sorts worst misses first, registry order on ties, fixtures with no figure last', () => {
      const rows = fixtureRows(fixtures, mf, 'M');
      expect(rows.map((r) => r.fixture.name)).toEqual(['b', 'a', 'c', 'd']);
      expect(rows[0]).toMatchObject({ misses: 5, pct: 84 });
      expect(rows[3]).toMatchObject({ misses: null, pct: null });
    });
  });

  describe('model choice', () => {
    it('lists the models with grid data, in served order', () => {
      const sessions = [sess(1, 't', 'v', 'MANUAL', [run(10, 'HAIKU'), run(11, 'OPUS'), run(12, 'SONNET_55')])];
      const models = [{ model: 'OPUS' }, { model: 'HAIKU' }, { model: 'SONNET_55' }];
      expect(fixtureModels(models, sessions, [rf(10, 'x'), rf(11, 'x')])).toEqual(['OPUS', 'HAIKU']);
    });

    it('prefers the wanted model, then the near-term lane model, then the first', () => {
      const lanes = [{ runType: 'BATCH_FAR_TERM', model: 'HAIKU' }, { runType: 'BATCH_NEAR_TERM', model: 'SONNET_55' }];
      expect(defaultFixtureModel('OPUS', ['SONNET_55', 'OPUS'], lanes)).toBe('OPUS');
      expect(defaultFixtureModel('GONE', ['HAIKU', 'SONNET_55'], lanes)).toBe('SONNET_55');
      expect(defaultFixtureModel(null, ['HAIKU', 'OPUS'], lanes)).toBe('HAIKU');
      expect(defaultFixtureModel(null, [], lanes)).toBeNull();
    });
  });

  describe('cellTitle', () => {
    const fixture = { name: 'copt-hill', bandLo: 1, bandHi: 2 };
    const col = { startedAt: '2026-11-02T08:00:00Z', manual: true, version: 'a91b0e2c4d' };

    it('gives fixture, date, version, in-band, below and above, mean and band', () => {
      expect(cellTitle(fixture, col, rf(1, 'x', {
        inBand: 5, below: 2, above: 1, mean: 2.456,
      }))).toBe('copt-hill, Mon 2 Nov (manual), a91b0e2: 5/8 in band, 2 below, 1 above, mean rating 2.5, band 1–2');
    });

    it('says so when the fixture was not in the run, and when the version is unknown', () => {
      expect(cellTitle(fixture, { ...col, manual: false, version: '' }, undefined))
        .toBe('copt-hill, Mon 2 Nov, unknown version: not in this run');
    });
  });

  describe('rating-history geometry', () => {
    it('maps ratings onto the .3 to 5.7 axis, higher ratings higher up', () => {
      expect(historyY(5.7)).toBeCloseTo(HISTORY.top, 10);
      expect(historyY(0.3)).toBeCloseTo(HISTORY.height - HISTORY.bottom, 10);
      expect(historyY(4)).toBeLessThan(historyY(2));
    });

    it('maps time across the plot, left to right', () => {
      expect(historyX(0, 0, 100)).toBe(HISTORY.left);
      expect(historyX(100, 0, 100)).toBe(HISTORY.width - HISTORY.right);
      expect(historyX(5, 5, 5)).toBe(HISTORY.left);
    });

    it('picks the dot colour by side: model when all in band, tide below, coral above', () => {
      expect(dotKind({ n: 8, inBand: 8, below: 0, above: 0 })).toBe('model');
      expect(dotKind({ n: 8, inBand: 4, below: 3, above: 1 })).toBe('below');
      expect(dotKind({ n: 8, inBand: 4, below: 1, above: 3 })).toBe('above');
      expect(dotKind({ n: 8, inBand: 6, below: 0, above: 0 })).toBe('failed');
    });

    it('widens the window to 30 days and pads two days before and one after', () => {
      const day = 86400000;
      const last = Date.parse('2026-11-02T00:00:00Z');
      expect(historyWindow([last - 3 * day, last])).toEqual({ startMs: last - 32 * day, endMs: last + day });
      expect(historyWindow([last - 60 * day, last])).toEqual({ startMs: last - 62 * day, endMs: last + day });
    });

    it('builds the band, one point per run the fixture is in, and the version lines inside the window', () => {
      const fixture = { name: 'x', bandLo: 3, bandHi: 5 };
      const cols = modelRunColumns(
        [
          sess(1, '2026-10-18T04:00:00Z', 'aaaa1111', 'SCHEDULED', [run(10, 'M')]),
          sess(2, '2026-11-01T04:00:00Z', 'bbbb2222', 'SCHEDULED', [run(11, 'M')]),
          sess(3, '2026-11-02T04:00:00Z', 'bbbb2222', 'SCHEDULED', [run(12, 'M')]),
        ],
        [
          rf(10, 'x', { mean: 4, min: 3, max: 5 }),
          rf(11, 'x', {
            inBand: 5, below: 3, mean: 2.6, min: 1, max: 4,
          }),
          rf(12, 'other'),
        ],
        'M',
      );
      const versions = [
        {
          sha: 'bbbb2222', shortSha: 'bbbb222', message: 'later', committedAt: '2026-10-30T09:00:00Z',
        },
        {
          sha: 'old', shortSha: 'old', message: null, committedAt: '2026-01-01T00:00:00Z',
        },
        {
          sha: 'aaaa1111', shortSha: 'aaaa111', message: 'first', committedAt: '2026-10-10T09:00:00Z',
        },
      ];
      const g = buildFixtureHistory(fixture, cols, versions);
      expect(g.points.map((p) => p.runId)).toEqual([10, 11]);
      expect(g.points.map((p) => p.kind)).toEqual(['model', 'below']);
      expect(g.points[0].y).toBe(historyY(4));
      expect(g.points[0].yMin).toBe(historyY(3));
      expect(g.points[0].yMax).toBe(historyY(5));
      expect(g.points[1].x).toBeGreaterThan(g.points[0].x);
      expect(g.band.y).toBe(historyY(5.5));
      expect(g.band.height).toBeCloseTo(historyY(2.5) - historyY(5.5), 10);
      expect(g.markers.map((m) => m.label)).toEqual(['aaaa', 'bbbb']);
      expect(g.markers[0].x).toBeLessThan(g.markers[1].x);
      expect(g.markers[0].labelY).not.toBe(g.markers[1].labelY);
      expect(g.ticks).toHaveLength(5);
    });

    it('leaves a point with no mean without a dot position', () => {
      const cols = modelRunColumns(
        [sess(1, '2026-10-18T04:00:00Z', 'v', 'SCHEDULED', [run(10, 'M')])],
        [rf(10, 'x', { mean: null, min: null, max: null })],
        'M',
      );
      const g = buildFixtureHistory({ name: 'x', bandLo: 1, bandHi: 2 }, cols, []);
      expect(g.points[0]).toMatchObject({ y: null, yMin: null, yMax: null });
    });
  });
});
