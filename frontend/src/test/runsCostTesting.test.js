import { describe, it, expect } from 'vitest';
import {
  TEST_SWATCH, allTimeBody, chartSeries, allTimeHeadline, compactTokens, isGithubUrl, promptTestCostText,
  promptTestMeta, promptTestOutcome, testingDayMap, testingLine, testingOf, testingSeries, testingWhyText, verdictHeadline,
} from '../utils/runsCost.js';
import {
  costAllTime, costResponse, promptTestRun, testingBlock, testingType,
} from './runsCostTimingFixtures.js';

const text = (runs) => runs.map((r) => r.text).join('');

describe('the headline flag', () => {
  const up = { direction: 'UP', percent: 14 };
  const down = { direction: 'DOWN', percent: -17 };

  it('says "App spend" only when asked, and the plain wording is untouched', () => {
    expect(verdictHeadline(up, 30, true)).toBe('Yes. Spend is up 14% on the previous 30 days.');
    expect(verdictHeadline(down, 30, true, false)).toBe('No. Spend is down 17% on the previous 30 days.');
    expect(verdictHeadline(up, 30, true, true)).toBe('Yes. App spend is up 14% on the previous 30 days.');
    expect(verdictHeadline(down, 30, true, true)).toBe('No. App spend is down 17% on the previous 30 days.');
    expect(verdictHeadline({ direction: 'FLAT', percent: 3 }, 30, true, true))
      .toBe('No. App spend is roughly flat (+3%) on the previous 30 days.');
  });

  it('words the no-spend and no-comparison headlines for the app when flagged', () => {
    expect(verdictHeadline(up, 7, false)).toBe('No spend recorded in the last 7 days.');
    expect(verdictHeadline(up, 7, false, true)).toBe('No app spend recorded in the last 7 days.');
    expect(verdictHeadline({ direction: 'FLAT', percent: null }, 7, true))
      .toBe('No comparison yet: nothing was spent in the 7 days before.');
    expect(verdictHeadline({ direction: 'FLAT', percent: null }, 7, true, true))
      .toBe('No comparison yet: nothing was spent on the app in the 7 days before.');
  });

  it('flags the all-time headline and body the same way', () => {
    const data = costAllTime(10);
    expect(allTimeHeadline(data)).toBe('£600.00 spent since 23 Oct 2026.');
    expect(allTimeHeadline(data, true)).toBe('£600.00 of app spend since 23 Oct 2026.');
    const none = costAllTime(10, { totals: { ...data.totals, lastGbp: 0 } });
    expect(allTimeHeadline(none)).toBe('No spend recorded since 23 Oct 2026.');
    expect(allTimeHeadline(none, true)).toBe('No app spend recorded since 23 Oct 2026.');
    expect(text(allTimeBody(data))).toContain('a day on average over 10 days');
    expect(text(allTimeBody(data, true))).toContain('a day on average for the app over 10 days');
  });
});

describe('testingOf', () => {
  it('is null with no block, no totals or no types, and the block otherwise', () => {
    const data = costResponse();
    expect(testingOf(data)).toBeNull();
    expect(testingOf(undefined)).toBeNull();
    expect(testingOf({ ...data, testing: null })).toBeNull();
    expect(testingOf({ ...data, testing: { types: [], days: [], totals: {} } })).toBeNull();
    expect(testingOf({ ...data, testing: { types: [testingType('SKY')], days: [] } })).toBeNull();
    const block = testingBlock(data);
    expect(testingOf({ ...data, testing: block })).toBe(block);
  });
});

describe('testingLine', () => {
  it('reads "Plus £3.40 on prompt tests (14 runs, was £1.62)"', () => {
    const data = costResponse();
    data.testing = testingBlock(data);
    expect(text(testingLine(data))).toBe('Plus £3.40 on prompt tests (14 runs, was £1.62)');
    expect(testingLine(data)[1]).toEqual({ text: '£3.40', bold: true });
  });

  it('uses the singular for one run and adds the unknown count', () => {
    const data = costResponse();
    data.testing = testingBlock(data, {}, {
      totals: { ...testingBlock(data).totals, runs: 1, unknownRuns: 2 },
    });
    expect(text(testingLine(data))).toBe('Plus £3.40 on prompt tests (1 run, was £1.62) · 2 usage unknown');
  });

  it('all time says "since" and has no "was"', () => {
    const data = costAllTime(10);
    data.testing = testingBlock(data, {}, { totals: { ...testingBlock(data).totals, unknownRuns: 1 } });
    expect(text(testingLine(data))).toBe('Plus £3.40 on prompt tests since 23 Oct 2026 · 1 usage unknown');
  });
});

describe('testingWhyText', () => {
  it('is "Runs 9, was 4 · £/run +7%" and drops the £/run part when it cannot be worked out', () => {
    expect(testingWhyText(testingType('SKY'))).toBe('Runs 9, was 4 · £/run +7%');
    expect(testingWhyText(testingType('ASK'))).toBe('Runs 5, was 3 · £/run −12%');
    expect(testingWhyText(testingType('SKY', { perRunChangePercent: null, priorRuns: 0 }))).toBe('Runs 9, was 0');
  });

  it('appends the number of runs whose usage is unknown', () => {
    expect(testingWhyText(testingType('SKY', { unknownRuns: 3 }))).toBe('Runs 9, was 4 · £/run +7% · 3 usage unknown');
  });
});

describe('testing series and totals', () => {
  it('testingSeries has one value per app day, in the app day order, zero where the server had none', () => {
    const data = costResponse();
    const [first, , third] = data.days.map((d) => d.date);
    data.testing = testingBlock(data, { [first]: { SKY: 0.5 }, [third]: { SKY: 1, ASK: 2 } });
    const sky = testingSeries(data, 'SKY');
    expect(sky).toHaveLength(data.days.length);
    expect(sky.slice(0, 3)).toEqual([0.5, 0, 1]);
    expect(testingSeries(data, 'ASK').slice(0, 3)).toEqual([0, 0, 2]);
    data.testing.days = data.testing.days.slice(1);
    expect(testingSeries(data, 'SKY')[0]).toBe(0);
  });

  it('testingDayMap keys the served days by date, and is empty without a block', () => {
    const data = costResponse();
    data.testing = testingBlock(data);
    const map = testingDayMap(data);
    expect(map.size).toBe(data.days.length);
    expect(map.get(data.days[2].date).date).toBe(data.days[2].date);
    expect(map.get('1999-01-01')).toBeUndefined();
    expect(testingDayMap(costResponse()).size).toBe(0);
  });

  it('the 7-day average series is the app\'s whether or not a testing block rides along', () => {
    const plain = costResponse();
    const withBlock = { ...plain, testing: testingBlock(plain, { [plain.days.at(-1).date]: { SKY: 50 } }) };
    expect(chartSeries(withBlock, null)).toEqual(chartSeries(plain, null));
    expect(chartSeries(withBlock, ['OVERNIGHT']).average).toEqual(chartSeries(plain, ['OVERNIGHT']).average);
  });

  it('the swatch classes name their colour token in full', () => {
    expect(TEST_SWATCH.SKY).toContain('var(--color-test-sky)');
    expect(TEST_SWATCH.ASK).toContain('var(--color-test-ask)');
  });
});

describe('prompt test run wording', () => {
  it('maps every outcome to its words', () => {
    expect(promptTestOutcome(promptTestRun({ conclusion: 'success' }))).toEqual({ text: 'passed', tone: 'ok' });
    expect(promptTestOutcome(promptTestRun({ conclusion: 'failure', failed: 3 }))).toEqual({ text: '3 failed', tone: 'bad' });
    expect(promptTestOutcome(promptTestRun({ conclusion: 'failure', failed: 0 }))).toEqual({ text: 'failed', tone: 'bad' });
    expect(promptTestOutcome(promptTestRun({ conclusion: 'failure', failed: null }))).toEqual({ text: 'failed', tone: 'bad' });
    expect(promptTestOutcome(promptTestRun({ conclusion: 'cancelled' }))).toEqual({ text: 'cancelled', tone: 'quiet' });
    expect(promptTestOutcome(promptTestRun({ conclusion: 'timed_out' }))).toEqual({ text: 'timed_out', tone: 'quiet' });
    expect(promptTestOutcome(promptTestRun({ conclusion: null }))).toEqual({ text: 'running', tone: 'quiet' });
  });

  it('compactTokens: plain under a thousand, k to a million, M above', () => {
    expect(compactTokens(800)).toBe('800');
    expect(compactTokens(38000)).toBe('38k');
    expect(compactTokens(612400)).toBe('612k');
    expect(compactTokens(1250000)).toBe('1.3M');
  });

  it('promptTestMeta is the handoff line, with the cached share of everything read', () => {
    expect(promptTestMeta(promptTestRun())).toBe('42 cases · 0 failed · 612k in · 38k out · 71% cached · attempt 1');
  });

  it('leaves out what the server did not have: unknown usage has no tokens, no cached share without cache reads', () => {
    expect(promptTestMeta(promptTestRun({
      cases: null, failed: null, inputTotal: null, cachedPercent: null, output: null, attempt: 2, usageState: 'UNKNOWN',
    }))).toBe('attempt 2');
    expect(promptTestMeta(promptTestRun({ cases: 1, cachedPercent: 0, inputTotal: 900, output: 80 })))
      .toBe('1 case · 0 failed · 900 in · 80 out · attempt 1');
    expect(promptTestMeta(promptTestRun({ cachedPercent: null, inputTotal: 5000, output: null })))
      .toBe('42 cases · 0 failed · 5k in · attempt 1');
  });

  it('the cost is pounds, or "usage unknown" / "unpriced", never £0', () => {
    expect(promptTestCostText(promptTestRun())).toBe('£0.41');
    expect(promptTestCostText(promptTestRun({ usageState: 'UNKNOWN', costGbp: null }))).toBe('usage unknown');
    expect(promptTestCostText(promptTestRun({ usageState: 'UNPRICED', costGbp: null }))).toBe('unpriced');
  });

  it('isGithubUrl accepts only an https://github.com/ address', () => {
    expect(isGithubUrl('https://github.com/o/r/actions/runs/1')).toBe(true);
    expect(isGithubUrl('http://github.com/o/r')).toBe(false);
    expect(isGithubUrl('https://evil.example/https://github.com/')).toBe(false);
    expect(isGithubUrl('javascript:alert(1)')).toBe(false);
    expect(isGithubUrl(null)).toBe(false);
    expect(isGithubUrl(undefined)).toBe(false);
  });
});
