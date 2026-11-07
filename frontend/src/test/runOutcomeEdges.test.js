import { describe, it, expect } from 'vitest';
import { completedWithFailures, failedOutright, stoppedEarly } from '../utils/runOutcome.js';

describe('runOutcome edges', () => {
  it('a PARTIAL run with failures and nothing completed or triaged still completed with failures', () => {
    expect(completedWithFailures({ status: 'PARTIAL', failed: 2, completed: 0, triaged: 0 })).toBe(true);
  });

  it('a status-less run with failures and only triaged places completed with failures', () => {
    expect(completedWithFailures({ failed: 1, completed: 0, triaged: 3 })).toBe(true);
  });

  it('a status-less run with failures and only completed places completed with failures', () => {
    expect(completedWithFailures({ failed: 1, completed: 4, triaged: 0 })).toBe(true);
  });

  it('a status-less run with failures and nothing standing is not completed with failures', () => {
    expect(completedWithFailures({ failed: 1, completed: 0, triaged: 0 })).toBe(false);
  });

  it('a reason with no status and a completed place is not outright failed but stopped early', () => {
    expect(failedOutright({ reason: 'x', completed: 2 })).toBe(false);
    expect(stoppedEarly({ reason: 'x', completed: 2 })).toBe(true);
  });

  it('a reason with no status and no completed place is failed outright', () => {
    expect(failedOutright({ reason: 'x', completed: 0 })).toBe(true);
    expect(stoppedEarly({ reason: 'x', completed: 0 })).toBe(false);
  });
});
