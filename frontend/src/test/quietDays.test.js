import { describe, it, expect } from 'vitest';
import {
  QUIET_AT_MOST, isFoldedQuiet, isQuietDay, topicLaneCount,
} from '../utils/quietDays.js';

/**
 * A matrix day holding the given windows (a bare rating, or null for a served-but-unscored window). A missing window is
 * EXPLAINED by default (the matrix's own copy); pass `explained: false` for an unexplained hole.
 */
function day(am, pm, date = '2026-11-25', { explained = true } = {}) {
  return {
    date,
    am: am === undefined ? null : { bestRating: am },
    pm: pm === undefined ? null : { bestRating: pm },
    amEmpty: am === undefined && explained ? 'this morning has gone' : null,
    pmEmpty: pm === undefined && explained ? 'past the end of the forecast' : null,
  };
}

describe('isQuietDay (screen-spec.md D5)', () => {
  it('is quiet when every window is served and at most 2 stars', () => {
    expect(QUIET_AT_MOST).toBe(2);
    expect(isQuietDay(day(2, 1))).toBe(true);
    expect(isQuietDay(day(1, 1))).toBe(true);
  });

  it('is not quiet when either window is above 2 stars', () => {
    expect(isQuietDay(day(3, 1))).toBe(false);
    expect(isQuietDay(day(1, 3))).toBe(false);
    expect(isQuietDay(day(5, 5))).toBe(false);
  });

  it('never folds a null: an unscored window, an away window or a Lite reader (every best rating null)', () => {
    expect(isQuietDay(day(null, 1))).toBe(false);
    expect(isQuietDay(day(1, null))).toBe(false);
    expect(isQuietDay(day(null, null))).toBe(false);
    expect(isQuietDay(day(null))).toBe(false);
  });

  it('judges only the windows the day shows: today with its sunrise gone is quiet on its sunset alone', () => {
    expect(isQuietDay(day(undefined, 2))).toBe(true);
    expect(isQuietDay(day(undefined, 4))).toBe(false);
    expect(isQuietDay(day(2, undefined))).toBe(true);
  });

  it('an UNEXPLAINED hole keeps the day open: a missing window folds only when the matrix says why it is missing', () => {
    expect(isQuietDay(day(undefined, 2, '2026-11-25', { explained: false }))).toBe(false);
    expect(isQuietDay(day(1, undefined, '2026-11-25', { explained: false }))).toBe(false);
    expect(isQuietDay(day(undefined, 2))).toBe(true);
    expect(isQuietDay(day(1, undefined))).toBe(true);
  });

  it('is not quiet with no window at all, or with no day', () => {
    expect(isQuietDay(day())).toBe(false);
    expect(isQuietDay(undefined)).toBe(false);
  });

  it('stays open with a warning lane on the day, and ignores one on another day', () => {
    const quiet = day(1, 2);
    expect(isQuietDay(quiet, { warningLanes: [{ dates: ['2026-11-25', '2026-11-26'] }] })).toBe(false);
    expect(isQuietDay(quiet, { warningLanes: [{ dates: ['2026-11-26'] }] })).toBe(true);
  });

  it('stays open with a watched lane of its own on the day', () => {
    const quiet = day(1, 2);
    expect(isQuietDay(quiet, { watchedLanes: [{ dates: ['2026-11-25'] }] })).toBe(false);
    expect(isQuietDay(quiet, { watchedLanes: [{ dates: ['2026-11-27'] }] })).toBe(true);
  });

  it('stays open when a topic lane on the day is decorated by a watched date, but not for a plain topic lane', () => {
    const quiet = day(1, 2);
    expect(isQuietDay(quiet, { topicLanes: [{ dates: ['2026-11-25'], watched: { plan: null } }] })).toBe(false);
    expect(isQuietDay(quiet, { topicLanes: [{ dates: ['2026-11-25'] }] })).toBe(true);
    expect(isQuietDay(quiet, { topicLanes: [{ dates: ['2026-11-26'], watched: { plan: null } }] })).toBe(true);
  });

  it('tolerates a lane with no dates', () => {
    expect(isQuietDay(day(1, 1), { warningLanes: [{}], watchedLanes: [null], topicLanes: [{ watched: {} }] })).toBe(true);
  });
});

describe('topicLaneCount', () => {
  it('counts the topic lanes on that day, so a folded line can say so', () => {
    const lanes = [{ dates: ['2026-11-25'] }, { dates: ['2026-11-25', '2026-11-26'] }, { dates: ['2026-11-27'] }];
    expect(topicLaneCount('2026-11-25', lanes)).toBe(2);
    expect(topicLaneCount('2026-11-27', lanes)).toBe(1);
    expect(topicLaneCount('2026-11-30', lanes)).toBe(0);
  });

  it('is zero for no lanes', () => {
    expect(topicLaneCount('2026-11-25', undefined)).toBe(0);
    expect(topicLaneCount('2026-11-25', [])).toBe(0);
  });
});

describe('isFoldedQuiet', () => {
  it('is true only for a quiet day the reader has not opened', () => {
    const quiet = new Set(['2026-11-25']);
    expect(isFoldedQuiet('2026-11-25', quiet, new Set())).toBe(true);
    expect(isFoldedQuiet('2026-11-25', quiet, new Set(['2026-11-25']))).toBe(false);
    expect(isFoldedQuiet('2026-11-26', quiet, new Set())).toBe(false);
  });
});
