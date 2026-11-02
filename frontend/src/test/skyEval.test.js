import { describe, it, expect } from 'vitest';
import {
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
      const series = buildAccuracySeries(sessions, models, null, startMs, endMs);
      const sonnet = series.find((s) => s.model === 'SONNET_55');
      expect(sonnet.points.map((p) => p.pct)).toEqual([97.5, 99.4]);
      expect(series.find((s) => s.model === 'HAIKU')).toBeUndefined();
    });

    it('marks manual points and keeps the session trigger and version for the tooltip', () => {
      const sonnet = buildAccuracySeries(sessions, models, null, startMs, endMs).find((s) => s.model === 'SONNET_55');
      expect(sonnet.points.map((p) => p.manual)).toEqual([false, true]);
      expect(sonnet.points[1].runCount).toBe(2);
      expect(sonnet.points[1].version).toBe('a91b0e2aaaa');
    });

    it('emphasises production models by default, and draws them last', () => {
      const series = buildAccuracySeries(sessions, models, null, startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['OPUS', false], ['SONNET_55', true]]);
    });

    it('emphasises only the highlighted model when one is chosen', () => {
      const series = buildAccuracySeries(sessions, models, 'OPUS', startMs, endMs);
      expect(series.map((s) => [s.model, s.emphasised])).toEqual([['SONNET_55', false], ['OPUS', true]]);
    });

    it('clamps a rate below the axis floor to the floor', () => {
      const low = [{
        trigger: 'SCHEDULED', startedAt: '2026-10-25T04:00:00Z', version: null,
        byModel: [{ model: 'OPUS', runCount: 1, inBandPct: 40 }],
      }];
      const series = buildAccuracySeries(low, models, null, startMs, endMs);
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
