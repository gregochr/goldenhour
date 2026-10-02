import { describe, it, expect } from 'vitest';
import {
  retryNotOfferedLine, retryStartedNote, RETRY_NOT_OFFERED_KEY, RETRY_NOT_OFFERED_LIGHT_POLLUTION,
  RETRY_NOT_OFFERED_GENERIC,
} from '../utils/runOutcome.js';

describe('retryNotOfferedLine', () => {
  it('names the API key for API_KEY_REJECTED and for the earlier payload that named no reason', () => {
    expect(retryNotOfferedLine({ retryable: false, retryBlockedReason: 'API_KEY_REJECTED' }))
      .toBe('Retry is not offered: fix the API key, then start the run again.');
    expect(retryNotOfferedLine({ retryable: false })).toBe(RETRY_NOT_OFFERED_KEY);
    expect(retryNotOfferedLine(null)).toBe(RETRY_NOT_OFFERED_KEY);
  });

  it('points a light-pollution run at Refresh Light Pollution', () => {
    expect(retryNotOfferedLine({ retryBlockedReason: 'LIGHT_POLLUTION' }))
      .toBe('Retry is not offered: press Refresh Light Pollution again.');
    expect(retryNotOfferedLine({ retryBlockedReason: 'LIGHT_POLLUTION' }))
      .toBe(RETRY_NOT_OFFERED_LIGHT_POLLUTION);
  });

  it('uses the generic line for NOT_FORECAST_SLOTS and for a reason it does not know', () => {
    expect(retryNotOfferedLine({ retryBlockedReason: 'NOT_FORECAST_SLOTS' })).toBe(RETRY_NOT_OFFERED_GENERIC);
    expect(retryNotOfferedLine({ retryBlockedReason: 'SOMETHING_NEW' }))
      .toBe('Retry is not offered for this run.');
  });
});

describe('retryStartedNote', () => {
  it('says how many slots were started, singular and plural', () => {
    expect(retryStartedNote({ slots: 1, skipped: [] })).toBe('Retrying 1 slot.');
    expect(retryStartedNote({ slots: 2, skipped: [] })).toBe('Retrying 2 slots.');
    expect(retryStartedNote({ slots: 7 })).toBe('Retrying 7 slots.');
  });

  it('names each failed slot that was left out, with the server\'s reason', () => {
    const note = retryStartedNote({
      slots: 1,
      skipped: [
        {
          locationName: 'West Fell', date: '2026-10-03', targetType: 'SUNRISE',
          reason: 'The place is disabled or no longer exists.',
        },
        {
          locationName: 'Hide', date: '2026-10-04', targetType: 'SUNSET',
          reason: 'The place is no longer a sky location.',
        },
      ],
    });

    expect(note).toBe('Retrying 1 slot. Left out: West Fell 2026-10-03 sunrise '
      + '(The place is disabled or no longer exists); Hide 2026-10-04 sunset '
      + '(The place is no longer a sky location).');
  });

  it('names at most three left-out slots, then says how many more', () => {
    const slot = (n) => ({
      locationName: `Place ${n}`, date: '2026-10-03', targetType: 'SUNSET', reason: 'The place is gone.',
    });

    expect(retryStartedNote({ slots: 1, skipped: [1, 2, 3].map(slot) })).toBe(
      'Retrying 1 slot. Left out: Place 1 2026-10-03 sunset (The place is gone); '
      + 'Place 2 2026-10-03 sunset (The place is gone); Place 3 2026-10-03 sunset (The place is gone).');
    expect(retryStartedNote({ slots: 1, skipped: [1, 2, 3, 4, 5].map(slot) })).toBe(
      'Retrying 1 slot. Left out: Place 1 2026-10-03 sunset (The place is gone); '
      + 'Place 2 2026-10-03 sunset (The place is gone); Place 3 2026-10-03 sunset (The place is gone); '
      + 'and 2 more.');
  });

  it('never prints "null" or "undefined" for a part the server did not send', () => {
    const note = retryStartedNote({
      slots: 2,
      skipped: [{ locationName: 'West Fell', date: null, targetType: 'SUNRISE', reason: 'Gone.' },
        { locationName: null, date: '2026-10-03', targetType: undefined, reason: null },
        {}],
    });

    expect(note).toBe('Retrying 2 slots. Left out: West Fell sunrise (Gone); A place 2026-10-03; A place.');
    expect(note).not.toMatch(/null|undefined/);
  });

  it('claims nothing when the answer carries no slot count (an older server)', () => {
    expect(retryStartedNote({ status: 'Retry run started', jobRunId: 6 })).toBeNull();
    expect(retryStartedNote(undefined)).toBeNull();
    expect(retryStartedNote({ slots: '2' })).toBeNull();
  });
});
