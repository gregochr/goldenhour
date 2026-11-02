import { describe, it, expect } from 'vitest';
import {
  chartSeries, signedGbp, signedPercent, typeSeries, verdictBody, verdictHeadline, whyText,
} from '../utils/runsCost.js';
import { formatGbp } from '../utils/runsDay.js';
import { costDay, costResponse, costType } from './runsCostTimingFixtures.js';

// The Cost view's wording: the verdict sentence is the handoff's template filled from the served fields.

const text = (runs) => runs.map((r) => r.text).join('');

describe('verdictHeadline', () => {
  const headline = (direction, percent, hasSpend = true) => verdictHeadline({ direction, percent }, 30, hasSpend);

  it('says yes with the served percent for a rise', () => {
    expect(headline('UP', 14)).toBe('Yes. Spend is up 14% on the previous 30 days.');
  });

  it('says no for a fall, with the percent unsigned', () => {
    expect(headline('DOWN', -12)).toBe('No. Spend is down 12% on the previous 30 days.');
  });

  it.each([[4, '+4%'], [-4, '−4%'], [0, '+0%']])('says roughly flat with the signed percent (%i)', (percent, signed) => {
    expect(headline('FLAT', percent)).toBe(`No. Spend is roughly flat (${signed}) on the previous 30 days.`);
  });

  it('does not invent a percent when the prior period spent nothing', () => {
    expect(headline('FLAT', null)).toBe('No comparison yet: nothing was spent in the 30 days before.');
  });

  it('says so when nothing was spent in either period', () => {
    expect(headline('FLAT', null, false)).toBe('No spend recorded in the last 30 days.');
  });
});

describe('verdictBody', () => {
  it('joins the served fields into the handoff\'s sentences, figures and names in bold', () => {
    const runs = verdictBody(costResponse());
    expect(text(runs)).toBe(
      '£130.73 over the last 14 days against £114.83 before, £4.36 a day instead of £3.83. '
      + 'Ask ready answers accounts for £7.10 of the £15.91 rise. '
      + 'It runs as often as before, but each run costs 1.3× as much, '
      + 'because each run now covers 24 locations on average, up from 18. '
      + 'Sky eval, the test harness, is 13% of spend (£16.39, +35% on the period before).',
    );
    expect(runs.filter((r) => r.bold).map((r) => r.text)).toEqual(['£130.73', '£114.83', 'Ask ready answers', 'Sky eval']);
  });

  it('says the run count moved when the top type does not run as often as before', () => {
    const data = costResponse({
      types: [costType('SKY_EVAL', { runsChangePercent: 25 })],
      verdict: { ...costResponse().verdict, topType: 'SKY_EVAL', topLabel: 'Sky eval', runsAsOften: false, askLocations: null },
    });
    expect(text(verdictBody(data))).toContain('Sky eval accounts for £7.10 of the £15.91 rise. Runs +25%, cost per run 1.3×.');
  });

  it('names no contributor when spend did not rise, and adds the Sky eval share only when served', () => {
    const base = costResponse();
    const data = costResponse({
      totals: { ...base.totals, deltaGbp: -3, deltaPercent: -2 },
      verdict: { ...base.verdict, direction: 'DOWN', skyEval: null },
    });
    expect(text(verdictBody(data))).toBe('£130.73 over the last 14 days against £114.83 before, £4.36 a day instead of £3.83.');
  });

  it('calls a Sky eval with no prior spend new', () => {
    const base = costResponse();
    const data = costResponse({ verdict: { ...base.verdict, skyEval: { sharePercent: 9, gbp: 2, changePercent: null } } });
    expect(text(verdictBody(data))).toContain('is 9% of spend (£2.00, new).');
  });

  it('drops the cost-per-run clause when the server has none to give', () => {
    const base = costResponse();
    const data = costResponse({ verdict: { ...base.verdict, perRunMultiple: null, askLocations: null } });
    expect(text(verdictBody(data))).toContain('It runs as often as before.');
  });
});

describe('verdict honesty', () => {
  it('names no contributor when the top type fell, even if the total rose', () => {
    const base = costResponse();
    const data = costResponse({ verdict: { ...base.verdict, topDeltaGbp: -20 } });
    const body = text(verdictBody(data));
    expect(body).not.toContain('accounts for');
    expect(body).not.toContain('£-');
  });

  it('says "New this period" when the top type had no prior runs', () => {
    const base = costResponse();
    const data = costResponse({
      types: [costType('ASK_READY', { priorRuns: 0, runsChangePercent: null }), ...base.types.slice(1)],
      verdict: { ...base.verdict, runsAsOften: false },
    });
    const body = text(verdictBody(data));
    expect(body).toContain('New this period, cost per run 1.3×');
    expect(body).not.toContain('Runs —');
  });

  it('formats a negative amount with a true minus before the pound sign', () => {
    expect(formatGbp(-20)).toBe('\u2212£20.00');
    expect(formatGbp(0)).toBe('£0.00');
  });
});

describe('whyText', () => {
  it('shows the run and per-run changes, with Ask ready answers\' locations', () => {
    expect(whyText(costType('AFTERNOON', { runsChangePercent: 3, perRunChangePercent: 14 }))).toBe('Runs +3% · £/run +14%');
    expect(whyText(costType('ASK_READY', {
      runsChangePercent: 0, perRunChangePercent: 35, avgLocationsLast: 24, avgLocationsPrior: 18,
    }))).toBe('Runs +0% · £/run +35% · 24 locations per run, was 18');
  });

  it('uses a true minus and a dash for an unknown change', () => {
    expect(whyText(costType('OTHER', { runsChangePercent: -6, perRunChangePercent: null }))).toBe('Runs −6% · £/run —');
  });

  it('says New for a type with no prior runs and No runs for one with none at all', () => {
    expect(whyText(costType('EVENING', { priorRuns: 0, runsChangePercent: null }))).toBe('New');
    expect(whyText(costType('EVENING', { priorRuns: 0, lastRuns: 0 }))).toBe('No runs');
  });
});

describe('figures', () => {
  it('signs pounds and percents with a true minus', () => {
    expect(signedGbp(7.1)).toBe('+£7.10');
    expect(signedGbp(-0.1)).toBe('−£0.10');
    expect(signedPercent(null)).toBe('—');
  });
});

describe('chartSeries', () => {
  it('shows the last N days and averages each over the 7 days up to it, the first ones over the days before', () => {
    const base = costResponse();
    // 28 days: the first 21 spend £1, the last 7 £8, so the 22nd (the shown period's 8th) averages 6 x 1 + 8.
    const days = base.days.map((d, i) => costDay(d.date, { OTHER: i < 21 ? 1 : 8 }));
    const { shown, average } = chartSeries({ ...base, days }, null);
    expect(shown).toHaveLength(14);
    expect(shown[0].date).toBe('2026-10-19');
    expect(average).toHaveLength(14);
    expect(average[0]).toBe(1);
    expect(average[6]).toBe(1);
    expect(average[7]).toBeCloseTo((6 + 8) / 7);
    expect(average[13]).toBe(8);
  });

  it('follows the isolated type', () => {
    const base = costResponse();
    const days = base.days.map((d) => costDay(d.date, { ASK_READY: 2, OVERNIGHT: 3 }));
    expect(chartSeries({ ...base, days }, null).average[13]).toBe(5);
    expect(chartSeries({ ...base, days }, 'ASK_READY').average[13]).toBe(2);
  });

  it('gives a type\'s daily series over both periods', () => {
    const base = costResponse();
    expect(typeSeries(base, 'ASK_READY')).toHaveLength(28);
    expect(typeSeries(base, 'ASK_READY')[0]).toBe(2);
  });
});
