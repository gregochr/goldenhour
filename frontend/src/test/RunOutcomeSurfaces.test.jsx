import { describe, it, expect } from 'vitest';
import {
  needsAttention, failedOutright, stoppedEarly, completedWithFailures,
} from '../utils/runOutcome.js';

// Pure reads of a run-complete payload. The Job Runs panel decides from these (Light Pollution is
// the only run that shows progress), so a zero-task FAILED run (`status: FAILED, total: 0,
// failed: 0` plus a `reason`) must not read as a success.

const UNEXPECTED = 'The run stopped unexpectedly. See the server log.';

describe('runOutcome', () => {
  it('failedOutright is true only for FAILED: a reason on a PARTIAL or COMPLETE run is not outright', () => {
    expect(failedOutright({ status: 'FAILED' })).toBe(true);
    expect(failedOutright({ status: 'FAILED', reason: 'x', completed: 3 })).toBe(true);
    expect(failedOutright({ status: 'PARTIAL', reason: 'x', completed: 1 })).toBe(false);
    expect(failedOutright({ status: 'COMPLETE', reason: 'x' })).toBe(false);
    expect(failedOutright({ status: 'PARTIAL', failed: 3 })).toBe(false);
    expect(failedOutright({ status: 'COMPLETE', reason: null })).toBe(false);
    expect(failedOutright(null)).toBe(false);
  });

  it('with no status at all, a reason is outright only when nothing completed', () => {
    expect(failedOutright({ reason: 'x' })).toBe(true);
    expect(failedOutright({ reason: 'x', completed: 0 })).toBe(true);
    expect(failedOutright({ reason: 'x', completed: 2 })).toBe(false);
    expect(failedOutright({ completed: 0 })).toBe(false);
    expect(failedOutright({})).toBe(false);
  });

  it('stoppedEarly is a reason on a PARTIAL run (or a status-less one with completions), never FAILED or clean', () => {
    expect(stoppedEarly({ status: 'PARTIAL', reason: 'x' })).toBe(true);
    expect(stoppedEarly({ reason: 'x', completed: 2 })).toBe(true);
    expect(stoppedEarly({ status: 'PARTIAL', reason: null })).toBe(false);
    expect(stoppedEarly({ status: 'FAILED', reason: 'x' })).toBe(false);
    expect(stoppedEarly({ reason: 'x', completed: 0 })).toBe(false);
    expect(stoppedEarly({ status: 'COMPLETE', reason: 'x' })).toBe(false);
    expect(stoppedEarly(undefined)).toBe(false);
  });

  it('completedWithFailures is a failed place on a run that neither failed outright nor stopped early', () => {
    expect(completedWithFailures({ status: 'PARTIAL', completed: 1, failed: 1, reason: null })).toBe(true);
    expect(completedWithFailures({ status: 'COMPLETE', completed: 1, failed: 1 })).toBe(true);
    expect(completedWithFailures({ status: 'COMPLETE', completed: 2, failed: 0 })).toBe(false);
    expect(completedWithFailures({ status: 'FAILED', completed: 0, failed: 2 })).toBe(false);
    expect(completedWithFailures({ status: 'PARTIAL', completed: 1, failed: 1, reason: UNEXPECTED })).toBe(false);
    expect(completedWithFailures(null)).toBe(false);
  });

  it('completedWithFailures: no status, RUNNING, triaged-only, and PARTIAL with nothing completed', () => {
    // No status (a foreign payload): needs something that stood; nothing done is not "completed".
    expect(completedWithFailures({ completed: 1, triaged: 0, failed: 1 })).toBe(true);
    expect(completedWithFailures({ completed: 0, triaged: 0, failed: 2 })).toBe(false);
    // Still RUNNING is not a finished run.
    expect(completedWithFailures({ status: 'RUNNING', completed: 1, failed: 1 })).toBe(false);
    // Triaged places alone, with no failed place, are a clean run.
    expect(completedWithFailures({ status: 'COMPLETE', completed: 0, triaged: 3, failed: 0 })).toBe(false);
    // PARTIAL with nothing completed: triaged places stood, so it is completed with failures.
    expect(completedWithFailures({ status: 'PARTIAL', completed: 0, triaged: 2, failed: 1, reason: null })).toBe(true);
  });

  it('needsAttention reads the failed count, FAILED or PARTIAL status, and a reason', () => {
    expect(needsAttention({ failed: 1 })).toBe(true);
    expect(needsAttention({ status: 'FAILED', failed: 0 })).toBe(true);
    expect(needsAttention({ status: 'PARTIAL', failed: 0 })).toBe(true);
    expect(needsAttention({ status: 'COMPLETE', failed: 0, reason: 'x' })).toBe(true);
    expect(needsAttention({ status: 'COMPLETE', failed: 0, reason: null })).toBe(false);
    expect(needsAttention({})).toBe(false);
    expect(needsAttention(undefined)).toBe(false);
  });
});
