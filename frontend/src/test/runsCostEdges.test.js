import { describe, it, expect } from 'vitest';
import {
  chartSeries, costTypeLabel, signedGbp, signedPercent, typeSeries, verdictBody, whyText,
} from '../utils/runsCost.js';

// Boundary and branch pins for the Runs Cost view-model helpers: wording, signs, the verdict template's branches and
// the chart series' slicing. Fixtures are built inside each test.

/** A served RunCostResponse with one rising type; every field is overridable. */
function response(overrides = {}) {
  return {
    rangeDays: 30,
    totals: {
      lastGbp: 10, priorGbp: 5, perDayLast: 0.33, perDayPrior: 0.17, deltaGbp: 5, ...(overrides.totals ?? {}),
    },
    verdict: {
      direction: 'UP', percent: 100, topType: 'EVENING', topLabel: 'Evening refresh', topDeltaGbp: 4,
      perRunMultiple: null, runsAsOften: false, askLocations: null, skyEval: null, ...(overrides.verdict ?? {}),
    },
    types: overrides.types ?? [{ type: 'EVENING', priorRuns: 4, runsChangePercent: 25 }],
    days: [],
  };
}

describe('costTypeLabel', () => {
  it('prefers the label the server served for the type', () => {
    const types = [{ type: 'EVENING', label: 'Late refresh' }, { type: 'SKY_EVAL', label: 'Server sky' }];
    expect(costTypeLabel('SKY_EVAL', types)).toBe('Server sky');
    expect(costTypeLabel('EVENING', types)).toBe('Late refresh');
  });

  it('falls back to the built-in words when the server did not serve that type', () => {
    expect(costTypeLabel('SKY_EVAL', [{ type: 'EVENING', label: 'Late refresh' }])).toBe('Sky eval');
    expect(costTypeLabel('ASK_READY')).toBe('Ask ready answers');
  });

  it('falls back to the built-in words when the served row has no label', () => {
    expect(costTypeLabel('EVENING', [{ type: 'EVENING' }])).toBe('Evening refresh');
  });

  it('says an unknown type as its raw name', () => {
    expect(costTypeLabel('MYSTERY', [])).toBe('MYSTERY');
  });

  it('always says Other as "Other", whatever the server labelled it', () => {
    expect(costTypeLabel('OTHER', [{ type: 'OTHER', label: 'Misc spend' }])).toBe('Other');
  });
});

describe('signedPercent and signedGbp', () => {
  it('give an em dash for none', () => {
    expect(signedPercent(null)).toBe('—');
    expect(signedPercent(undefined)).toBe('—');
    expect(signedGbp(null)).toBe('—');
    expect(signedGbp(undefined)).toBe('—');
  });

  it('say zero as a plus, a negative with a true minus and a positive with a plus', () => {
    expect(signedPercent(0)).toBe('+0%');
    expect(signedPercent(-3)).toBe('−3%');
    expect(signedPercent(14)).toBe('+14%');
    expect(signedGbp(0)).toBe('+£0.00');
    expect(signedGbp(-0.1)).toBe('−£0.10');
    expect(signedGbp('7.1')).toBe('+£7.10');
  });
});

describe('verdictBody', () => {
  it('tells the totals, and nothing about a contributor when spend did not rise', () => {
    const data = response({ totals: { deltaGbp: 0 } });
    expect(verdictBody(data)).toEqual([
      { text: '£10.00', bold: true },
      { text: ' over the last 30 days against ' },
      { text: '£5.00', bold: true },
      { text: ' before, £0.33 a day instead of £0.17.' },
    ]);
  });

  it('names no contributor when the rise has no positive top delta', () => {
    const data = response({ verdict: { topDeltaGbp: 0 } });
    expect(verdictBody(data)).toHaveLength(4);
  });

  it('names no contributor when the total fell, even with a positive top delta', () => {
    const data = response({ totals: { deltaGbp: -2 }, verdict: { topDeltaGbp: 1 } });
    expect(verdictBody(data)).toHaveLength(4);
  });

  it('names no contributor when the server named no top type', () => {
    const data = response({ verdict: { topType: null } });
    expect(verdictBody(data)).toHaveLength(4);
  });

  it('names a contributor that ran as often as before, with its per-run multiple', () => {
    const data = response({ verdict: { runsAsOften: true, perRunMultiple: 1.234 } });
    expect(verdictBody(data).slice(4)).toEqual([
      { text: ' ' },
      { text: 'Evening refresh', bold: true },
      { text: ' accounts for £4.00 of the £5.00 rise. ' },
      { text: 'It runs as often as before, but each run costs 1.2× as much' },
      { text: '.' },
    ]);
  });

  it('says only that it runs as often as before when there is no multiple', () => {
    const data = response({ verdict: { runsAsOften: true } });
    expect(verdictBody(data).slice(7)).toEqual([{ text: 'It runs as often as before' }, { text: '.' }]);
  });

  it('says a contributor that did not exist before is new, with and without a multiple', () => {
    const fresh = [{ type: 'EVENING', priorRuns: 0, runsChangePercent: null }];
    expect(verdictBody(response({ types: fresh, verdict: { perRunMultiple: 2 } })).slice(7))
      .toEqual([{ text: 'New this period, cost per run 2.0×' }, { text: '.' }]);
    expect(verdictBody(response({ types: fresh })).slice(7))
      .toEqual([{ text: 'New this period' }, { text: '.' }]);
  });

  it('does not call a contributor new when it had prior runs', () => {
    const data = response({ verdict: { perRunMultiple: 3 } });
    expect(verdictBody(data).slice(7)).toEqual([{ text: 'Runs +25%, cost per run 3.0×' }, { text: '.' }]);
  });

  it('reads the contributor\'s own row when several types are listed', () => {
    const types = [
      { type: 'OVERNIGHT', priorRuns: 0, runsChangePercent: null },
      { type: 'EVENING', priorRuns: 4, runsChangePercent: 25 },
    ];
    expect(verdictBody(response({ types })).slice(7)).toEqual([{ text: 'Runs +25%' }, { text: '.' }]);
  });

  it('says the runs change alone when there is no multiple', () => {
    expect(verdictBody(response()).slice(7)).toEqual([{ text: 'Runs +25%' }, { text: '.' }]);
  });

  it('copes with a top type the served rows do not list', () => {
    const data = response({ types: [], verdict: { perRunMultiple: 2 } });
    expect(verdictBody(data).slice(7)).toEqual([{ text: 'Runs —, cost per run 2.0×' }, { text: '.' }]);
  });

  it('explains an Ask contributor by how many locations each run now covers', () => {
    const data = response({ verdict: { askLocations: { last: 12, prior: 7 }, perRunMultiple: 2 } });
    expect(verdictBody(data).slice(7)).toEqual([
      { text: 'Runs +25%, cost per run 2.0×' },
      { text: ', because each run now covers 12 locations on average, up from 7.' },
    ]);
  });

  it('adds the Sky eval share, told as new when it has no prior', () => {
    const skyEval = { sharePercent: 9, gbp: 0.9, changePercent: null };
    const runs = verdictBody(response({ totals: { deltaGbp: 0 }, verdict: { skyEval } }));
    expect(runs.slice(4)).toEqual([
      { text: ' ' },
      { text: 'Sky eval', bold: true },
      { text: ', the test harness, is 9% of spend (£0.90, new).' },
    ]);
  });

  it('adds the Sky eval share with its change on the period before', () => {
    const skyEval = { sharePercent: 9, gbp: 0.9, changePercent: -12 };
    const runs = verdictBody(response({ totals: { deltaGbp: 0 }, verdict: { skyEval } }));
    expect(runs[6]).toEqual({ text: ', the test harness, is 9% of spend (£0.90, −12% on the period before).' });
  });
});

describe('whyText', () => {
  it('says "No runs" only for a type with no runs in either period', () => {
    expect(whyText({ lastRuns: 0, priorRuns: 0 })).toBe('No runs');
  });

  it('does not say "No runs" for a type that stopped running this period', () => {
    expect(whyText({ lastRuns: 0, priorRuns: 5, runsChangePercent: -100, perRunChangePercent: null }))
      .toBe('Runs −100% · £/run —');
  });

  it('says "New" for a type with no prior runs', () => {
    expect(whyText({ lastRuns: 3, priorRuns: 0 })).toBe('New');
  });

  it('shows the runs and per-run changes', () => {
    expect(whyText({ lastRuns: 4, priorRuns: 4, runsChangePercent: 0, perRunChangePercent: 31 }))
      .toBe('Runs +0% · £/run +31%');
  });

  it('appends the locations only when both averages are served', () => {
    const row = { lastRuns: 4, priorRuns: 4, runsChangePercent: 0, perRunChangePercent: 31 };
    expect(whyText({ ...row, avgLocationsLast: 12, avgLocationsPrior: 7 }))
      .toBe('Runs +0% · £/run +31% · 12 locations per run, was 7');
    expect(whyText({ ...row, avgLocationsLast: 12, avgLocationsPrior: null })).toBe('Runs +0% · £/run +31%');
    expect(whyText({ ...row, avgLocationsLast: null, avgLocationsPrior: 7 })).toBe('Runs +0% · £/run +31%');
    expect(whyText({ ...row, avgLocationsLast: 0, avgLocationsPrior: 0 }))
      .toBe('Runs +0% · £/run +31% · 0 locations per run, was 0');
  });
});

describe('chartSeries and typeSeries', () => {
  /** Ten served days costing 1..10 pounds, with EVENING at half and ASK_READY on alternate days only. */
  function days() {
    return Array.from({ length: 10 }, (_, i) => ({
      totalGbp: i + 1,
      byType: { EVENING: (i + 1) / 2, ...(i % 2 === 0 ? { ASK_READY: 1 } : {}) },
    }));
  }

  it('shows the last N days and their rolling averages, taken over all served days', () => {
    const { shown, average } = chartSeries({ rangeDays: 3, days: days() }, null);
    expect(shown.map((d) => d.totalGbp)).toEqual([8, 9, 10]);
    expect(average).toEqual([5, 6, 7]);
  });

  it('draws the isolated type, treating a day without it as zero', () => {
    const { average } = chartSeries({ rangeDays: 4, days: days() }, ['ASK_READY']);
    expect(average).toHaveLength(4);
    expect(average[0]).toBeCloseTo(4 / 7);
    expect(average[1]).toBeCloseTo(3 / 7);
  });

  it('treats a day without any per-type breakdown as zero for an isolated type', () => {
    const { average } = chartSeries({ rangeDays: 2, days: [{ totalGbp: 3 }, { totalGbp: 5 }] }, ['EVENING']);
    expect(average).toEqual([0, 0]);
  });

  it('reads the total as a number when it is served as text', () => {
    const { average } = chartSeries({ rangeDays: 2, days: [{ totalGbp: '2.5' }, { totalGbp: '3.5' }] }, null);
    expect(average).toEqual([2.5, 3]);
  });

  it('gives one value of a type per served day, missing ones zero', () => {
    expect(typeSeries({ days: days() }, 'ASK_READY')).toEqual([1, 0, 1, 0, 1, 0, 1, 0, 1, 0]);
    expect(typeSeries({ days: [{ totalGbp: 1 }, { byType: { EVENING: '2' } }] }, 'EVENING')).toEqual([0, 2]);
  });
});
