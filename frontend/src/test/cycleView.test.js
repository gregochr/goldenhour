import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  phaseLabel,
  phaseShort,
  phaseRows,
  waitingBatches,
  cycleMetaLine,
  elapsedText,
  totalRequests,
  pickLine,
  pickHeadline,
  rankLabel,
  cycleName,
  changedDimensionsText,
} from '../utils/cycleView.js';

describe('phase names', () => {
  it('labels known phases and falls back to the key for an unknown one', () => {
    expect(phaseLabel('FORECAST_BATCH_WAIT')).toBe('Wait for completion');
    expect(phaseLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('shortens known phases, falling back to the label and then the key', () => {
    expect(phaseShort('FORECAST_BATCH_SUBMIT')).toBe('Submit');
    expect(phaseShort('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('phaseRows', () => {
  const started = (phase, sequenceOrder, status = 'COMPLETED') => ({
    phase, sequenceOrder, status, startedAt: '2026-11-04T10:00:00Z',
  });

  it('returns the served phases sorted by sequenceOrder when the cycle is not running', () => {
    const rows = phaseRows({
      run: { status: 'COMPLETED' },
      phases: [started('BRIEFING', 3), started('FORECAST_BATCH_SUBMIT', 1), started('FORECAST_BATCH_WAIT', 2)],
    });
    expect(rows.map((r) => r.phase)).toEqual(['FORECAST_BATCH_SUBMIT', 'FORECAST_BATCH_WAIT', 'BRIEFING']);
  });

  it('copes with a detail that has no phases and no run', () => {
    expect(phaseRows({})).toEqual([]);
  });

  it('does not mutate the served phase list when sorting', () => {
    const phases = [started('BRIEFING', 2), started('FORECAST_BATCH_SUBMIT', 1)];
    phaseRows({ run: { status: 'COMPLETED' }, phases });
    expect(phases.map((p) => p.phase)).toEqual(['BRIEFING', 'FORECAST_BATCH_SUBMIT']);
  });

  it('appends the not-yet-started phases as PENDING while running, in cycle order', () => {
    const rows = phaseRows({
      run: { status: 'RUNNING', cycleType: 'INTRADAY' },
      phases: [started('STABILITY_RECLASSIFY', 1), started('FORECAST_BATCH_SUBMIT', 2, 'RUNNING')],
    });
    expect(rows.map((r) => [r.phase, r.status, r.sequenceOrder])).toEqual([
      ['STABILITY_RECLASSIFY', 'COMPLETED', 1],
      ['FORECAST_BATCH_SUBMIT', 'RUNNING', 2],
      ['FORECAST_BATCH_WAIT', 'PENDING', 3],
      ['RETRY_FAILED', 'PENDING', 4],
      ['BRIEFING', 'PENDING', 5],
    ]);
    const pending = rows[2];
    expect(pending).toMatchObject({ startedAt: null, completedAt: null, durationSeconds: null, detail: null });
  });

  it('draws re-classify as pending for a running cycle that has started nothing, unless it is NIGHTLY', () => {
    const intraday = phaseRows({ run: { status: 'RUNNING', cycleType: 'INTRADAY' }, phases: [] });
    expect(intraday.map((r) => r.phase)).toEqual([
      'STABILITY_RECLASSIFY', 'FORECAST_BATCH_SUBMIT', 'FORECAST_BATCH_WAIT', 'RETRY_FAILED', 'BRIEFING',
    ]);
    expect(intraday[0].sequenceOrder).toBe(1);

    const nightly = phaseRows({ run: { status: 'RUNNING', cycleType: 'NIGHTLY' }, phases: [] });
    expect(nightly.map((r) => r.phase)).toEqual([
      'FORECAST_BATCH_SUBMIT', 'FORECAST_BATCH_WAIT', 'RETRY_FAILED', 'BRIEFING',
    ]);
  });

  it('never re-adds a phase that has already been served, nor one before the last started', () => {
    const rows = phaseRows({
      run: { status: 'RUNNING', cycleType: 'EVENING' },
      phases: [started('FORECAST_BATCH_WAIT', 4, 'RUNNING')],
    });
    expect(rows.map((r) => r.phase)).toEqual(['FORECAST_BATCH_WAIT', 'RETRY_FAILED', 'BRIEFING']);
  });
});

describe('waitingBatches', () => {
  it('picks the SUBMITTED batches and the earliest submission time', () => {
    const batches = [
      { id: 1, status: 'SUBMITTED', submittedAt: '2026-11-04T11:00:00Z' },
      { id: 2, status: 'SUBMITTED', submittedAt: '2026-11-04T09:30:00Z' },
      { id: 3, status: 'COMPLETED', submittedAt: '2026-11-04T08:00:00Z' },
      { id: 4, status: 'SUBMITTED', submittedAt: null },
    ];
    const result = waitingBatches({ batches });
    expect(result.waiting.map((b) => b.id)).toEqual([1, 2, 4]);
    expect(result.total).toBe(4);
    expect(result.submittedAt).toBe('2026-11-04T09:30:00Z');
  });

  it('has no earliest time and a zero total when there are no batches', () => {
    expect(waitingBatches({})).toEqual({ waiting: [], total: 0, submittedAt: null });
  });

  it('has no earliest time when nothing is waiting', () => {
    const result = waitingBatches({ batches: [{ id: 1, status: 'COMPLETED', submittedAt: '2026-11-04T08:00:00Z' }] });
    expect(result.waiting).toEqual([]);
    expect(result.submittedAt).toBeNull();
  });
});

describe('clock-reading helpers', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-04T16:38:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('builds the cycle meta line with a clock time and a relative age', () => {
    const line = cycleMetaLine({ id: 412, cycleType: 'INTRADAY', triggerTime: '2026-11-04T15:00:00Z' }, 'Running');
    expect(line).toBe('Cycle #412 · intraday · Running · started 15:00 (1h 38m ago)');
  });

  it('drops the cycle type when there is none', () => {
    const line = cycleMetaLine({ id: 7, triggerTime: '2026-11-04T16:30:00Z' }, 'Done');
    expect(line).toBe('Cycle #7 · Done · started 16:30 (8m ago)');
  });

  it('drops the whole started clause when the trigger time is unusable', () => {
    expect(cycleMetaLine({ id: 9, cycleType: 'NIGHTLY', triggerTime: null }, 'Failed')).toBe(
      'Cycle #9 · nightly · Failed',
    );
  });

  it('reports elapsed time for a running thing and an empty string when its start is unknown', () => {
    expect(elapsedText('2026-11-04T15:00:00Z')).toBe('1h 38m');
    expect(elapsedText(null)).toBe('');
    expect(elapsedText('2026-11-04T17:00:00Z')).toBe('');
  });
});

describe('totalRequests', () => {
  it('sums the request counts, counting a missing one as zero', () => {
    expect(totalRequests([{ requestCount: 100 }, { requestCount: 24 }, {}])).toBe(124);
  });

  it('is zero for no batches at all', () => {
    expect(totalRequests(undefined)).toBe(0);
    expect(totalRequests([])).toBe(0);
  });
});

describe('pickLine / pickHeadline', () => {
  it('reads "No pick" for a rank present on only one side', () => {
    expect(pickLine(null)).toBe('No pick');
  });

  it('reads "Stay home" for a pick with no region', () => {
    expect(pickLine({ region: null })).toBe('Stay home');
  });

  it('writes region, capitalised event, UK date and rating', () => {
    expect(
      pickLine({
        region: 'Northumberland Coast', eventType: 'SUNSET', eventDate: '2026-10-31', claudeAverageRating: 4.2,
      }),
    ).toBe('Northumberland Coast · Sunset · Sat 31 Oct · ★4.2');
  });

  it('rounds the rating to one decimal and keeps a zero rating', () => {
    expect(pickLine({ region: 'Lakes', claudeAverageRating: 0 })).toBe('Lakes · ★0.0');
    expect(pickLine({ region: 'Lakes', claudeAverageRating: 3.96 })).toBe('Lakes · ★4.0');
  });

  it('leaves out the parts the pick does not carry', () => {
    expect(pickLine({ region: 'Lakes' })).toBe('Lakes');
  });

  it('returns an event date that is not a date as it came', () => {
    expect(pickLine({ region: 'Lakes', eventDate: 'next week' })).toBe('Lakes · next week');
  });

  it('prefers the headline and falls back to the line', () => {
    expect(pickHeadline({ headline: 'Go west', region: 'Lakes' })).toBe('Go west');
    expect(pickHeadline({ headline: '', region: 'Lakes' })).toBe('Lakes');
    expect(pickHeadline({ region: null })).toBe('Stay home');
    expect(pickHeadline(null)).toBe('No pick');
    expect(pickHeadline(undefined)).toBe('No pick');
  });
});

describe('rankLabel', () => {
  it('names the first two plans by letter and the rest by number', () => {
    expect(rankLabel(1)).toBe('Plan A');
    expect(rankLabel(2)).toBe('Plan B');
    expect(rankLabel(3)).toBe('Plan 3');
  });
});

describe('cycleName', () => {
  it('uses the list row name when the row is loaded', () => {
    expect(cycleName({ id: 5, cycleType: 'NIGHTLY' }, { name: 'Overnight cycle (Tue)' })).toBe('Overnight cycle (Tue)');
  });

  it.each([
    ['NIGHTLY', 'Overnight cycle'],
    ['INTRADAY', 'Afternoon refresh'],
    ['EVENING', 'Evening refresh'],
  ])('names a deep-linked %s cycle "%s"', (cycleType, name) => {
    expect(cycleName({ id: 5, cycleType })).toBe(name);
    expect(cycleName({ id: 5, cycleType }, { name: '' })).toBe(name);
  });

  it('falls back to the cycle number for an unknown type', () => {
    expect(cycleName({ id: 5, cycleType: 'WEEKLY' })).toBe('Cycle #5');
    expect(cycleName({ id: 6 })).toBe('Cycle #6');
  });
});

describe('changedDimensionsText', () => {
  it('words the changed dimensions in order, capitalising the first', () => {
    expect(changedDimensionsText({ changedDimensions: ['REGION', 'RATING'] })).toBe('Region, rating');
    expect(changedDimensionsText({ changedDimensions: ['PRESENCE'] })).toBe('Added/dropped');
  });

  it('passes an unknown dimension through', () => {
    expect(changedDimensionsText({ changedDimensions: ['EVENT', 'mood'] })).toBe('Event, mood');
  });

  it('reads "Changed" when no dimension is named', () => {
    expect(changedDimensionsText({ changedDimensions: [] })).toBe('Changed');
    expect(changedDimensionsText({})).toBe('Changed');
  });
});
