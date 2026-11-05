import { describe, it, expect } from 'vitest';
import {
  anthropicBatchId,
  batchSummaryLine,
  formatClockUk,
  groupLabel,
  midTruncate,
  runDurationText,
  runResultText,
  runRowId,
  runRowLabel,
  runScopeText,
} from '../utils/runsView.js';

const NOW = new Date('2026-11-04T12:00:00Z');

describe('groupLabel', () => {
  it('words the known groups', () => {
    expect(groupLabel('CYCLES')).toBe('Cycle');
    expect(groupLabel('BATCHES')).toBe('Batch');
  });
  it('passes an unknown group through and is empty for none', () => {
    expect(groupLabel('MYSTERY')).toBe('MYSTERY');
    expect(groupLabel(null)).toBe('');
    expect(groupLabel(undefined)).toBe('');
    expect(groupLabel('')).toBe('');
  });
});

describe('formatClockUk', () => {
  it('is empty for none and the UK clock otherwise', () => {
    expect(formatClockUk(null)).toBe('');
    expect(formatClockUk('garbage')).toBe('');
    expect(formatClockUk('2026-07-01T14:02:00Z')).toBe('15:02');
  });
});

describe('runDurationText for a running run', () => {
  it('is an em dash when the start is unreadable or in the future', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: null }, NOW)).toBe('—');
    expect(runDurationText({ status: 'RUNNING', startedAt: '2026-11-04T13:00:00Z' }, NOW)).toBe('—');
  });
  it('reads the elapsed time so far', () => {
    expect(runDurationText({ status: 'RUNNING', startedAt: '2026-11-04T11:58:48Z' }, NOW)).toBe('1m 12s so far');
  });
  it('uses the served duration for a finished run', () => {
    expect(runDurationText({ status: 'DONE', durationSeconds: 3600 + 41 * 60 }, NOW)).toBe('1h 41m');
    expect(runDurationText({ status: 'DONE', durationSeconds: null }, NOW)).toBe('—');
  });
});

describe('runResultText', () => {
  it('words a cycle, defaulting missing counts to zero', () => {
    expect(runResultText({ kind: 'CYCLE', batchesDone: 2, batchesTotal: 4 })).toBe('2 of 4 batches');
    expect(runResultText({ kind: 'CYCLE' })).toBe('0 of 0 batches');
  });
  it('words a running batch, with a question mark before the count is known', () => {
    const batch = { kind: 'JOB', status: 'RUNNING', runType: 'SCHEDULED_BATCH', succeeded: 3 };
    expect(runResultText({ ...batch, locationsProcessed: 29 })).toBe('3 / 29');
    expect(runResultText({ kind: 'JOB', status: 'RUNNING', runType: 'SCHEDULED_BATCH' })).toBe('0 / ?');
  });
  it('words a built briefing and an aurora evaluation', () => {
    expect(runResultText({ kind: 'JOB', group: 'BRIEFING', status: 'DONE' })).toBe('Built');
    expect(runResultText({ kind: 'JOB', runType: 'AURORA_EVALUATION', succeeded: 5 })).toBe('5 scored');
    expect(runResultText({ kind: 'JOB', runType: 'AURORA_EVALUATION' })).toBe('0 scored');
  });
  it('words ok over total, and an em dash for 0/0', () => {
    expect(runResultText({ kind: 'JOB', succeeded: 8, failed: 2 })).toBe('8 / 10');
    expect(runResultText({ kind: 'JOB', succeeded: 4 })).toBe('4 / 4');
    expect(runResultText({ kind: 'JOB' })).toBe('—');
  });
});

describe('batchSummaryLine', () => {
  const base = {
    horizonRange: 'T+1', eventTypes: ['SUNRISE', 'SUNSET'], locationCount: 29, evaluationModel: 'HAIKU', regionCount: 7,
  };
  it('is empty for none', () => {
    expect(batchSummaryLine(null)).toBe('');
    expect(batchSummaryLine(undefined)).toBe('');
  });
  it('leaves out the events when there are none', () => {
    const line = batchSummaryLine({ ...base, eventTypes: [] });
    expect(line.startsWith('T+1 · 29 locations · ')).toBe(true);
    expect(line.endsWith(' · 7 regions')).toBe(true);
    expect(batchSummaryLine({ ...base, eventTypes: null }).startsWith('T+1 · 29 locations · ')).toBe(true);
  });
  it('lists the events lower-cased and suffixes extended thinking once', () => {
    const plain = batchSummaryLine(base);
    expect(plain.startsWith('T+1 · sunrise, sunset · 29 locations · ')).toBe(true);
    expect(plain).not.toContain('(ET)');
    const thinking = batchSummaryLine({ ...base, extendedThinking: true });
    expect(thinking).toContain(' (ET) · 7 regions');
  });
});

describe('runScopeText', () => {
  it('words a cycle with its type and a singular or plural batch count', () => {
    expect(runScopeText({ kind: 'CYCLE', id: 412, cycleType: 'INTRADAY', batchCount: 4 })).toBe('Cycle #412 · intraday · 4 batches');
    expect(runScopeText({ kind: 'CYCLE', id: 7, batchesTotal: 1 })).toBe('Cycle #7 · 1 batch');
    expect(runScopeText({ kind: 'CYCLE', id: 8 })).toBe('Cycle #8 · 0 batches');
  });
  it('words a batch job with its summary, or says there is no detail once finished', () => {
    const summary = { horizonRange: 'T+1', eventTypes: [], locationCount: 3, evaluationModel: 'HAIKU', regionCount: 1 };
    expect(runScopeText({ kind: 'JOB', runType: 'SCHEDULED_BATCH', batchSummary: summary }))
      .toBe(`Scheduled · ${batchSummaryLine(summary)}`);
    expect(runScopeText({ kind: 'JOB', runType: 'SCHEDULED_BATCH', status: 'DONE', triggeredManually: true }))
      .toBe('Manual · No detail available');
    expect(runScopeText({ kind: 'JOB', runType: 'SCHEDULED_BATCH', status: 'RUNNING' })).toBe('Scheduled');
  });
  it('words another job with its location count when there is one', () => {
    expect(runScopeText({ kind: 'JOB', runType: 'TIDE', locationsProcessed: 12 })).toBe('Scheduled · 12 locations');
    expect(runScopeText({ kind: 'JOB', runType: 'TIDE', locationsProcessed: 0 })).toBe('Scheduled');
    expect(runScopeText({ kind: 'JOB', runType: 'TIDE' })).toBe('Scheduled');
  });
});

describe('runRowLabel and runRowId', () => {
  it('reads the row, suffixing a cycle, dropping empty parts and appending a note', () => {
    const item = {
      kind: 'CYCLE', id: 412, name: 'Afternoon refresh', startedAt: '2026-11-04T11:00:00Z', status: 'DONE',
      batchesDone: 2, batchesTotal: 4, durationSeconds: 125, note: 'retried',
    };
    expect(runRowLabel(item, 'Done', NOW)).toBe('Afternoon refresh cycle, Today 11:00, Done, 2 of 4 batches, 2m 5s, retried');
  });
  it('keeps a job name as is and omits an absent note', () => {
    const item = { kind: 'JOB', id: 9, name: 'Tide run', startedAt: '2026-11-03T19:40:00Z', status: 'DONE', succeeded: 1, durationSeconds: 48 };
    expect(runRowLabel(item, 'Done', NOW)).toBe('Tide run, Yesterday 19:40, Done, 1 / 1, 48s');
  });
  it('ids a row by its kind', () => {
    expect(runRowId({ kind: 'CYCLE', id: 412 })).toBe('cycle-412');
    expect(runRowId({ kind: 'JOB', id: 9876 })).toBe('job-9876');
  });
});

describe('midTruncate', () => {
  it('is empty for none, whole when short, shortened when long', () => {
    expect(midTruncate(null)).toBe('');
    expect(midTruncate('')).toBe('');
    expect(midTruncate('msgbatch_short')).toBe('msgbatch_short');
    expect(midTruncate('12345678901234567890')).toBe('12345678901234567890');
    expect(midTruncate('msgbatch_01JBXKABCDEFGH7Q')).toBe('msgbatch_01JBX…7Q');
  });
});

describe('anthropicBatchId', () => {
  it('prefers the served batch row', () => {
    expect(anthropicBatchId({ batch: { anthropicBatchId: 'msgbatch_1' }, run: { notes: 'Anthropic batch: other' } })).toBe('msgbatch_1');
  });
  it('reads the id from a batch run\'s notes', () => {
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: msgbatch_2 ' } })).toBe('msgbatch_2');
  });
  it('is null when the notes carry no id, are not a string, or the run is not a batch', () => {
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch:    ' } })).toBeNull();
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'something else' } })).toBeNull();
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: null } })).toBeNull();
    expect(anthropicBatchId({ run: { runType: 'TIDE', notes: 'Anthropic batch: x' } })).toBeNull();
    expect(anthropicBatchId({})).toBeNull();
    expect(anthropicBatchId(null)).toBeNull();
  });
});
