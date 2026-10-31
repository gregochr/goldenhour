import { describe, it, expect } from 'vitest';
import {
  anthropicBatchId, batchSummaryLine, formatClockUk, formatRunStarted, formatSeconds, formatSpendGbp, formatSpendUsd,
  groupLabel, midTruncate, runDurationText, runResultText, runRowId, runRowLabel, runScopeText,
} from '../utils/runsView.js';
import { cycleItem, jobItem } from './runsFixtures.js';

// The suite runs on the UK clock (TZ pinned), and every "now" below is passed in: the helpers never read the rewound
// clock, so a test can hold them to a pinned real "now".

/** Noon on Saturday 31 October 2026. The clocks went back on the 25th, so UK time is GMT and equals UTC. */
const NOW = new Date('2026-10-31T12:00:00Z');

describe('formatRunStarted', () => {
  it('reads "Today HH:MM" for a run started earlier on the UK day', () => {
    expect(formatRunStarted('2026-10-31T06:00:00Z', NOW)).toBe('Today 06:00');
  });

  it('reads "Yesterday HH:MM" for the day before', () => {
    expect(formatRunStarted('2026-10-30T19:40:00Z', NOW)).toBe('Yesterday 19:40');
  });

  it('reads the weekday, date and time for anything older', () => {
    expect(formatRunStarted('2026-10-29T11:02:00Z', NOW)).toBe('Thu 29 Oct 11:02');
  });

  it('reads a bare LocalDateTime as UTC', () => {
    expect(formatRunStarted('2026-10-31T06:00:00', NOW)).toBe('Today 06:00');
  });

  it('puts a late-evening BST instant on the UK day, not the UTC day', () => {
    // 23:30Z on 1 Aug is 00:30 on 2 Aug in London.
    const now = new Date('2026-08-02T09:00:00Z');
    expect(formatRunStarted('2026-08-01T23:30:00Z', now)).toBe('Today 00:30');
  });

  it('is empty for no instant', () => {
    expect(formatRunStarted(null, NOW)).toBe('');
  });
});

describe('formatClockUk', () => {
  it('reads UK time, with BST applied', () => {
    expect(formatClockUk('2026-07-01T14:02:00Z')).toBe('15:02');
  });

  it('takes a Date', () => {
    expect(formatClockUk(new Date('2026-12-01T09:05:00Z'))).toBe('09:05');
  });
});

describe('formatSeconds', () => {
  it.each([
    [48, '48s'],
    [72, '1m 12s'],
    [3120, '52m'],
    [9660, '2h 41m'],
    [8340, '2h 19m'],
  ])('%i seconds reads %s', (seconds, text) => {
    expect(formatSeconds(seconds)).toBe(text);
  });

  it('is an em dash for none', () => {
    expect(formatSeconds(null)).toBe('—');
    expect(formatSeconds(undefined)).toBe('—');
  });
});

describe('runDurationText', () => {
  it('reads "{x} so far" for a running run, from the pinned real clock', () => {
    const item = jobItem({ status: 'RUNNING', startedAt: '2026-10-31T10:22:00Z', durationSeconds: null });
    expect(runDurationText(item, NOW)).toBe('1h 38m so far');
  });

  it('reads the served duration once finished', () => {
    expect(runDurationText(jobItem({ durationSeconds: 72 }), NOW)).toBe('1m 12s');
  });

  it('is an em dash when the server serves none (an ASK run never has one)', () => {
    expect(runDurationText(jobItem({ durationSeconds: null, status: 'DONE' }), NOW)).toBe('—');
  });

  it('is an em dash, not a negative time, for a run that started after "now" (clock skew)', () => {
    const item = jobItem({ status: 'RUNNING', startedAt: '2026-10-31T13:00:00Z', durationSeconds: null });
    expect(runDurationText(item, NOW)).toBe('—');
  });
});

describe('runResultText', () => {
  it('reads a cycle as "{done} of {total} batches"', () => {
    expect(runResultText(cycleItem({ batchesDone: 2, batchesTotal: 4 }))).toBe('2 of 4 batches');
  });

  it('reads a running batch as "{succeeded} / {locationsProcessed}"', () => {
    const item = jobItem({ runType: 'SCHEDULED_BATCH', group: 'BATCHES', status: 'RUNNING', succeeded: 5, locationsProcessed: 29 });
    expect(runResultText(item)).toBe('5 / 29');
  });

  it('uses a question mark for a running batch that does not know its size yet', () => {
    const item = jobItem({ runType: 'SCHEDULED_BATCH', status: 'RUNNING', succeeded: 0, locationsProcessed: 0 });
    expect(runResultText(item)).toBe('0 / ?');
  });

  it('reads a finished briefing as "Built"', () => {
    expect(runResultText(jobItem({ group: 'BRIEFING', runType: 'BRIEFING', status: 'DONE' }))).toBe('Built');
  });

  it('does not read a failed briefing as built', () => {
    expect(runResultText(jobItem({ group: 'BRIEFING', runType: 'BRIEFING', status: 'FAILED', succeeded: 0, failed: 3 })))
      .toBe('0 / 3');
  });

  it('reads an aurora evaluation as "{succeeded} scored"', () => {
    expect(runResultText(jobItem({ group: 'AURORA', runType: 'AURORA_EVALUATION', succeeded: 24 }))).toBe('24 scored');
  });

  it('reads anything else as "{ok} / {total}"', () => {
    expect(runResultText(jobItem({ succeeded: 198, failed: 14 }))).toBe('198 / 212');
  });

  it('is an em dash for 0 of 0', () => {
    expect(runResultText(jobItem({ succeeded: 0, failed: 0 }))).toBe('—');
  });
});

describe('runScopeText', () => {
  it('reads a cycle as "Cycle #{id} · {type} · {n} batches"', () => {
    expect(runScopeText(cycleItem({ id: 412, cycleType: 'INTRADAY', batchCount: 4 }))).toBe('Cycle #412 · intraday · 4 batches');
  });

  it('says "1 batch" for one', () => {
    expect(runScopeText(cycleItem({ batchCount: 1 }))).toContain('1 batch');
    expect(runScopeText(cycleItem({ batchCount: 1 }))).not.toContain('1 batches');
  });

  it('says "0 batches" for a cycle that went straight to the briefing', () => {
    expect(runScopeText(cycleItem({ batchCount: 0 }))).toContain('0 batches');
  });

  it('tells a manual run from a scheduled one', () => {
    expect(runScopeText(jobItem({ triggeredManually: true, locationsProcessed: 0 }))).toBe('Manual');
    expect(runScopeText(jobItem({ triggeredManually: false, locationsProcessed: 0 }))).toBe('Scheduled');
  });

  it('adds the location count of a non-batch run', () => {
    expect(runScopeText(jobItem({ triggeredManually: false, locationsProcessed: 61 }))).toBe('Scheduled · 61 locations');
  });

  it('adds the scope of a batch, from its summary', () => {
    const item = jobItem({
      runType: 'SCHEDULED_BATCH',
      triggeredManually: true,
      batchSummary: {
        horizonRange: 'T to T+3', eventTypes: ['SUNRISE', 'SUNSET'], evaluationModel: 'HAIKU', locationCount: 212, regionCount: 7,
        extendedThinking: false,
      },
    });
    expect(runScopeText(item)).toBe('Manual · T to T+3 · sunrise, sunset · 212 locations · Haiku 4.5 · 7 regions');
  });

  it('says "No detail available" for a finished batch with no scope', () => {
    expect(runScopeText(jobItem({ runType: 'SCHEDULED_BATCH', status: 'DONE', batchSummary: null, triggeredManually: false })))
      .toBe('Scheduled · No detail available');
  });

  it('does not say it while the batch is still running', () => {
    expect(runScopeText(jobItem({ runType: 'SCHEDULED_BATCH', status: 'RUNNING', batchSummary: null, triggeredManually: false })))
      .toBe('Scheduled');
  });

  it('does not say it for a run that is not a batch', () => {
    expect(runScopeText(jobItem({ runType: 'TIDE', batchSummary: null, locationsProcessed: 0, triggeredManually: false })))
      .not.toContain('No detail');
  });
});

describe('batchSummaryLine', () => {
  const base = {
    horizonRange: 'T+1', eventTypes: ['SUNRISE'], evaluationModel: 'HAIKU', locationCount: 29, regionCount: 7,
    extendedThinking: false,
  };

  it('words the model, never the enum name', () => {
    expect(batchSummaryLine({ ...base, evaluationModel: 'SONNET_55' })).toContain('Sonnet 5.5');
    expect(batchSummaryLine({ ...base, evaluationModel: 'SONNET_55' })).not.toContain('SONNET_55');
  });

  it('appends "(ET)" for extended thinking, but never twice', () => {
    expect(batchSummaryLine({ ...base, evaluationModel: 'SONNET', extendedThinking: true })).toContain('Sonnet 4.6 (ET)');
    expect(batchSummaryLine({ ...base, evaluationModel: 'SONNET_ET', extendedThinking: true })).not.toContain('(ET) (ET)');
  });

  it('leaves out the event types when there are none', () => {
    expect(batchSummaryLine({ ...base, eventTypes: [] })).toBe('T+1 · 29 locations · Haiku 4.5 · 7 regions');
  });

  it('is empty for no summary', () => {
    expect(batchSummaryLine(null)).toBe('');
  });
});

describe('runRowLabel and runRowId', () => {
  it('reads the whole row for assistive tech', () => {
    const item = cycleItem({ name: 'Afternoon refresh', startedAt: '2026-10-31T15:00:00Z', note: 'Waiting on 2 of 4 batches' });
    expect(runRowLabel(item, 'Running', new Date('2026-10-31T16:00:00Z')))
      .toBe('Afternoon refresh cycle, Today 15:00, Running, 2 of 4 batches, 1h 0m so far, £0.6083, Waiting on 2 of 4 batches');
  });

  it('names a job run without "cycle", and leaves out a duration or cost it does not have', () => {
    const item = jobItem({ name: 'Tide refresh', startedAt: '2026-10-31T06:00:00Z', durationSeconds: null, costMicroDollars: 0 });
    expect(runRowLabel(item, 'Done', new Date('2026-10-31T16:00:00Z'))).toBe('Tide refresh, Today 06:00, Done, 61 / 61');
  });

  it('keeps a cycle and a job run of the same number apart', () => {
    expect(runRowId(cycleItem({ id: 7 }))).toBe('cycle-7');
    expect(runRowId(jobItem({ id: 7 }))).toBe('job-7');
  });
});

describe('midTruncate and anthropicBatchId', () => {
  it('cuts a long id in the middle and leaves a short one whole', () => {
    expect(midTruncate('msgbatch_01JBXKQ7Q7Q7Q7Q7Q7Q7')).toBe('msgbatch_01JBX…Q7');
    expect(midTruncate('msgbatch_abc')).toBe('msgbatch_abc');
    expect(midTruncate(null)).toBe('');
  });

  it('prefers the batch row\'s id, then a batch run\'s notes, and never a non-batch run\'s notes', () => {
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: from_notes' }, batch: { anthropicBatchId: 'from_row' } }))
      .toBe('from_row');
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'Anthropic batch: from_notes' }, batch: null }))
      .toBe('from_notes');
    expect(anthropicBatchId({ run: { runType: 'TIDE', notes: 'Anthropic batch: from_notes' }, batch: null })).toBeNull();
    expect(anthropicBatchId({ run: { runType: 'SCHEDULED_BATCH', notes: 'Disposition-only cycle' }, batch: null })).toBeNull();
  });
});

describe('spend and group words', () => {
  it('formats spend to the penny in pounds and the cent in dollars', () => {
    expect(formatSpendGbp(23_310_000, 0.79)).toBe('£18.41');
    expect(formatSpendUsd(23_310_000)).toBe('$23.31');
  });

  it('says spend under a penny in pence, as the old card and the Cost column do', () => {
    expect(formatSpendGbp(10_000, 0.79)).toBe('0.79p');
  });

  it('has no spend to show for zero, or for no rate', () => {
    expect(formatSpendGbp(0, 0.79)).toBe('—');
    expect(formatSpendGbp(1_000_000, null)).toBe('—');
    expect(formatSpendUsd(0)).toBe('');
  });

  it('words each group', () => {
    expect(['CYCLES', 'BATCHES', 'TIDE', 'BRIEFING', 'AURORA', 'OTHER'].map(groupLabel))
      .toEqual(['Cycle', 'Batch', 'Tide', 'Briefing', 'Aurora', 'Other']);
  });
});
