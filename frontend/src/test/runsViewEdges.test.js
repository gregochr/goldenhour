import { describe, it, expect } from 'vitest';
import {
  formatSeconds, formatSpendGbp, formatSpendUsd, runDurationText, runResultText, runRowLabel,
} from '../utils/runsView.js';

// Boundary pins for the Runs view-model helpers: the zero edges of the duration text, which items count as a batch in
// flight, and the penny / cent boundaries of the Spend figures. Every "now" is passed in.

const NOW = new Date('2026-10-31T12:00:00Z');

describe('formatSeconds', () => {
  it('says zero seconds as "0s", not as no value', () => {
    expect(formatSeconds(0)).toBe('0s');
  });

  it('gives an em dash for a negative, NaN, null or undefined length', () => {
    expect(formatSeconds(-1)).toBe('—');
    expect(formatSeconds(-0.5)).toBe('—');
    expect(formatSeconds(NaN)).toBe('—');
    expect(formatSeconds(null)).toBe('—');
    expect(formatSeconds(undefined)).toBe('—');
  });

  it('switches unit exactly at a minute and at an hour', () => {
    expect(formatSeconds(59.9)).toBe('59s');
    expect(formatSeconds(60)).toBe('1m');
    expect(formatSeconds(72)).toBe('1m 12s');
    expect(formatSeconds(3599)).toBe('59m 59s');
    expect(formatSeconds(3600)).toBe('1h 0m');
    expect(formatSeconds(9660)).toBe('2h 41m');
  });
});

describe('runDurationText', () => {
  it('says "0s so far" for a run that started this very second', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: '2026-10-31T12:00:00Z' }, NOW)).toBe('0s so far');
  });

  it('gives an em dash for a run that appears to start in the future', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: '2026-10-31T12:00:01Z' }, NOW)).toBe('—');
  });

  it('gives an em dash for a running run with no start', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: null }, NOW)).toBe('—');
  });

  it('counts a running run up to now, and a finished run by its served length', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: '2026-10-31T11:58:48Z' }, NOW)).toBe('1m 12s so far');
    expect(runDurationText({ status: 'DONE', startedAt: '2026-10-31T11:00:00Z', durationSeconds: 90 }, NOW))
      .toBe('1m 30s');
    expect(runDurationText({ status: 'DONE', durationSeconds: null }, NOW)).toBe('—');
  });
});

describe('runResultText: which items count as a batch in flight', () => {
  it('shows "succeeded / locationsProcessed" for a running scheduled batch job, with "?" for none yet', () => {
    const item = {
      kind: 'JOB', status: 'RUNNING', runType: 'SCHEDULED_BATCH', succeeded: 3, failed: 4, locationsProcessed: 10,
    };
    expect(runResultText(item)).toBe('3 / 10');
    expect(runResultText({ ...item, locationsProcessed: 0 })).toBe('3 / ?');
  });

  it('shows "ok / total" for a running job that is not a scheduled batch', () => {
    const item = { kind: 'JOB', status: 'RUNNING', runType: 'TIDE_REFRESH', succeeded: 3, failed: 4, locationsProcessed: 10 };
    expect(runResultText(item)).toBe('3 / 7');
  });

  it('shows "ok / total" for a finished scheduled batch job', () => {
    const item = { kind: 'JOB', status: 'DONE', runType: 'SCHEDULED_BATCH', succeeded: 3, failed: 4, locationsProcessed: 10 };
    expect(runResultText(item)).toBe('3 / 7');
  });

  it('shows "ok / total" for a running scheduled batch that is not a job item', () => {
    const item = { kind: 'OTHER', status: 'RUNNING', runType: 'SCHEDULED_BATCH', succeeded: 3, failed: 4, locationsProcessed: 10 };
    expect(runResultText(item)).toBe('3 / 7');
  });

  it('shows "ok / total" for a job item that is a scheduled batch but not running', () => {
    const item = { kind: 'JOB', status: 'FAILED', runType: 'SCHEDULED_BATCH', succeeded: 1, failed: 1, locationsProcessed: 9 };
    expect(runResultText(item)).toBe('1 / 2');
  });
});

describe('formatSpendGbp', () => {
  it('gives an em dash for no spend, zero spend, a negative spend or no rate', () => {
    expect(formatSpendGbp(undefined, 0.8)).toBe('—');
    expect(formatSpendGbp(null, 0.8)).toBe('—');
    expect(formatSpendGbp(0, 0.8)).toBe('—');
    expect(formatSpendGbp(-5_000_000, 0.8)).toBe('—');
    expect(formatSpendGbp(5_000_000, 0)).toBe('—');
    expect(formatSpendGbp(5_000_000, null)).toBe('—');
  });

  it('shows pounds to the penny', () => {
    expect(formatSpendGbp(23_310_000, 0.789)).toBe('£18.39');
  });

  it('shows exactly a penny in pounds and anything under it in pence', () => {
    expect(formatSpendGbp(1_000_000, 0.01)).toBe('£0.01');
    expect(formatSpendGbp(500_000, 0.01)).toBe('0.50p');
  });
});

describe('formatSpendUsd', () => {
  it('gives an empty string for no, zero or negative spend', () => {
    expect(formatSpendUsd(undefined)).toBe('');
    expect(formatSpendUsd(null)).toBe('');
    expect(formatSpendUsd(0)).toBe('');
    expect(formatSpendUsd(-1_000_000)).toBe('');
  });

  it('shows dollars to the cent', () => {
    expect(formatSpendUsd(23_310_000)).toBe('$23.31');
    expect(formatSpendUsd(1)).toBe('$0.00');
  });
});

describe('runRowLabel', () => {
  it('leaves a row with no note without a trailing "undefined"', () => {
    const item = {
      kind: 'JOB', id: 9, name: 'Tide refresh', startedAt: '2026-10-31T06:00:00Z', status: 'DONE',
      succeeded: 2, failed: 0, durationSeconds: 90,
    };
    expect(runRowLabel(item, 'Done', NOW)).toBe('Tide refresh, Today 06:00, Done, 2 / 2, 1m 30s');
  });

  it('drops a part that is empty, such as the start of a run with no start time', () => {
    const item = {
      kind: 'JOB', id: 9, name: 'Tide refresh', startedAt: null, status: 'DONE', succeeded: 2, failed: 0, durationSeconds: 90,
    };
    expect(runRowLabel(item, 'Done', NOW)).toBe('Tide refresh, Done, 2 / 2, 1m 30s');
  });

  it('appends the note when there is one', () => {
    const item = {
      kind: 'JOB', id: 9, name: 'Tide refresh', startedAt: '2026-10-31T06:00:00Z', status: 'DONE',
      succeeded: 2, failed: 0, durationSeconds: 90, note: 'Retried once',
    };
    expect(runRowLabel(item, 'Done', NOW)).toBe('Tide refresh, Today 06:00, Done, 2 / 2, 1m 30s, Retried once');
  });
});
