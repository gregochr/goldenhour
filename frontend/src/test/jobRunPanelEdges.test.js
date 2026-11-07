import { describe, it, expect } from 'vitest';
import {
  evaluationRows, failedCallHeading, formatIsoDay, scopeItems, strategyTags,
} from '../utils/jobRunPanel.js';

// Edge pins for the job-run panel's view-model: what counts as a known error type or status, which calls are
// evaluations, the date-only formatter's anchors, strategy tags and each Scope row's presence rules.

/** Finds one Scope item by its test id. */
function item(result, testId) {
  return result.items.find((i) => i.testId === testId);
}

describe('failedCallHeading', () => {
  it('ignores an empty error type and falls back to the status', () => {
    expect(failedCallHeading({ service: 'WEATHER', errorType: '', statusCode: 500 })).toBe('WEATHER · 500');
  });

  it('ignores null and undefined error types', () => {
    expect(failedCallHeading({ service: 'WEATHER', errorType: null, statusCode: 502 })).toBe('WEATHER · 502');
    expect(failedCallHeading({ service: 'WEATHER', statusCode: 503 })).toBe('WEATHER · 503');
  });

  it('ignores an empty, null or missing status', () => {
    expect(failedCallHeading({ service: 'WEATHER', statusCode: '' })).toBe('WEATHER');
    expect(failedCallHeading({ service: 'WEATHER', statusCode: null })).toBe('WEATHER');
    expect(failedCallHeading({ service: 'WEATHER' })).toBe('WEATHER');
  });

  it('keeps a status of zero, which is a value', () => {
    expect(failedCallHeading({ service: 'WEATHER', statusCode: 0 })).toBe('WEATHER · 0');
  });

  it('prefers the error type, and never prints a bare status for a Claude row', () => {
    expect(failedCallHeading({ service: 'ANTHROPIC', errorType: 'anthropic_401', statusCode: 401 }))
      .toBe('ANTHROPIC · anthropic_401');
    expect(failedCallHeading({ service: 'ANTHROPIC', statusCode: 500 })).toBe('ANTHROPIC');
  });
});

describe('evaluationRows', () => {
  it('counts only Anthropic calls, however fully the other services are filled in', () => {
    const base = { targetDate: '2026-10-31', targetType: 'SUNSET', evaluationModel: 'HAIKU' };
    const rows = evaluationRows([
      { ...base, service: 'WEATHER' },
      { ...base, service: 'ANTHROPIC' },
      { ...base, service: 'ANTHROPIC' },
    ]);
    expect(rows).toEqual([{ date: '2026-10-31', event: 'SUNSET', model: 'HAIKU', count: 2 }]);
  });

  it('leaves out an Anthropic call missing its date, event or model', () => {
    const full = { service: 'ANTHROPIC', targetDate: '2026-10-31', targetType: 'SUNSET', evaluationModel: 'HAIKU' };
    expect(evaluationRows([
      { ...full, targetDate: null }, { ...full, targetType: null }, { ...full, evaluationModel: '' },
    ])).toEqual([]);
  });

  it('sorts by date, then event, then model', () => {
    const call = (targetDate, targetType, evaluationModel) => ({ service: 'ANTHROPIC', targetDate, targetType, evaluationModel });
    const rows = evaluationRows([
      call('2026-11-01', 'SUNRISE', 'HAIKU'), call('2026-10-31', 'SUNSET', 'SONNET'),
      call('2026-10-31', 'SUNSET', 'HAIKU'), call('2026-10-31', 'SUNRISE', 'SONNET'),
    ]);
    expect(rows.map((r) => `${r.date} ${r.event} ${r.model}`)).toEqual([
      '2026-10-31 SUNRISE SONNET', '2026-10-31 SUNSET HAIKU', '2026-10-31 SUNSET SONNET', '2026-11-01 SUNRISE HAIKU',
    ]);
  });
});

describe('formatIsoDay', () => {
  it('reads an ISO date as a weekday and day-month', () => {
    expect(formatIsoDay('2026-10-31')).toBe('Sat 31 Oct');
  });

  it.each(['2026-10-31x', 'x2026-10-31', '2026-10-3', 'soon'])('returns %s as it came', (value) => {
    expect(formatIsoDay(value)).toBe(value);
  });

  it('gives an empty string for no date', () => {
    expect(formatIsoDay(null)).toBe('');
    expect(formatIsoDay(undefined)).toBe('');
  });
});

describe('strategyTags', () => {
  it('gives none for no strategies', () => {
    expect(strategyTags(null)).toEqual([]);
    expect(strategyTags('')).toEqual([]);
  });

  it('trims the names, drops empty ones and reads EVALUATE_ALL as JFDI', () => {
    expect(strategyTags(' EVALUATE_ALL , ,SKIP_LOW, ')).toEqual([
      { name: 'EVALUATE_ALL', label: 'JFDI', jfdi: true },
      { name: 'SKIP_LOW', label: 'SKIP_LOW', jfdi: false },
    ]);
  });
});

describe('scopeItems: Locations', () => {
  it('reads the batch scope when it has both counts', () => {
    const result = scopeItems({ run: { batchSummary: { locationCount: 29, regionCount: 7 }, locationsProcessed: 3 } });
    expect(item(result, 'job-run-locations').value).toBe('29 in 7 regions');
  });

  it('falls back to the processed count when the batch scope lacks the region count', () => {
    const result = scopeItems({ run: { batchSummary: { locationCount: 29, regionCount: null }, locationsProcessed: 3 } });
    expect(item(result, 'job-run-locations').value).toBe('3');
  });

  it('falls back to the processed count when the batch scope lacks the location count', () => {
    const result = scopeItems({ run: { batchSummary: { locationCount: null, regionCount: 7 }, locationsProcessed: 3 } });
    expect(item(result, 'job-run-locations').value).toBe('3');
  });

  it('has no Locations row for zero processed and no scope', () => {
    expect(item(scopeItems({ run: { locationsProcessed: 0 } }), 'job-run-locations')).toBeUndefined();
  });
});

describe('scopeItems: Dates', () => {
  it('says a one-day span as "1 day" and a longer one as "N days", with the events', () => {
    const summary = { eventTypes: ['SUNRISE', 'SUNSET'] };
    const one = scopeItems({ run: { minTargetDate: '2026-10-31', maxTargetDate: '2026-10-31', batchSummary: summary } });
    expect(item(one, 'job-run-dates').value).toBe('Sat 31 Oct – Sat 31 Oct · 1 day · sunrise, sunset');
    const three = scopeItems({ run: { minTargetDate: '2026-10-31', maxTargetDate: '2026-11-02' } });
    expect(item(three, 'job-run-dates').value).toBe('Sat 31 Oct – Mon 2 Nov · 3 days');
  });

  it('adds no events suffix for an empty event list', () => {
    const result = scopeItems({
      run: { minTargetDate: '2026-10-31', maxTargetDate: '2026-11-01', batchSummary: { eventTypes: [] } },
    });
    expect(item(result, 'job-run-dates').value).toBe('Sat 31 Oct – Sun 1 Nov · 2 days');
  });

  it('falls back to the horizon range, with the events but never a stray separator', () => {
    const withEvents = scopeItems({ run: { batchSummary: { horizonRange: 'T+1', eventTypes: ['SUNRISE'] } } });
    expect(item(withEvents, 'job-run-dates').value).toBe('T+1 · sunrise');
    const bare = scopeItems({ run: { batchSummary: { horizonRange: 'T+1', eventTypes: [] } } });
    expect(item(bare, 'job-run-dates').value).toBe('T+1');
  });

  it('has no Dates row when only one end of the span is known', () => {
    expect(item(scopeItems({ run: { minTargetDate: '2026-10-31', maxTargetDate: null } }), 'job-run-dates')).toBeUndefined();
    expect(item(scopeItems({ run: { minTargetDate: null, maxTargetDate: '2026-11-05' } }), 'job-run-dates')).toBeUndefined();
    expect(item(scopeItems({ run: { minTargetDate: undefined, maxTargetDate: '2026-11-05' } }), 'job-run-dates')).toBeUndefined();
  });
});

describe('scopeItems: the mono rows', () => {
  it('sets the Anthropic batch id and the app version in monospace', () => {
    const result = scopeItems({
      run: { appVersion: '1.2.3', runType: 'SCHEDULED_BATCH' },
      batch: { anthropicBatchId: 'msgbatch_01ABC' },
    });
    expect(item(result, 'job-run-batch-id')).toEqual({
      label: 'Anthropic batch', value: 'msgbatch_01ABC', mono: true, testId: 'job-run-batch-id',
    });
    expect(item(result, 'job-run-app-version')).toEqual({
      label: 'App version', value: '1.2.3', mono: true, testId: 'job-run-app-version',
    });
  });

  it('does not set the other rows in monospace', () => {
    const result = scopeItems({ run: { notes: 'hello', locationsProcessed: 4 } });
    expect(item(result, 'job-run-notes').mono).toBeUndefined();
    expect(item(result, 'job-run-locations').mono).toBeUndefined();
  });
});
