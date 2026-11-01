import { describe, it, expect } from 'vitest';
import { batchStatusView, needsAttentionStatus, noteToneClass, runStatusView } from '../utils/runStatus.js';

// A pure lookup: the server decides every status (R-D1); these pin the words, the dot's tone and the halo for each.

describe('runStatusView: every served run status', () => {
  it.each([
    ['RUNNING', 'Running', 'tide', true],
    ['DONE', 'Done', 'go', false],
    ['DEGRADED', 'Degraded', 'amber', false],
    ['PARTIAL', 'Partial', 'amber', false],
    ['FAILED', 'Failed', 'coral', false],
  ])('%s reads %s, tone %s, halo %s', (status, label, tone, halo) => {
    expect(runStatusView(status)).toEqual({ label, tone, halo });
  });

  it('gives Running and Degraded different hues (they were two shades of amber)', () => {
    expect(runStatusView('RUNNING').tone).not.toBe(runStatusView('DEGRADED').tone);
  });

  it('reads an unknown status as itself, in the quiet ink, rather than hiding it', () => {
    expect(runStatusView('SOMETHING_NEW')).toEqual({ label: 'SOMETHING_NEW', tone: 'off', halo: false });
  });

  it('reads a missing status as an em dash', () => {
    expect(runStatusView(null).label).toBe('—');
    expect(runStatusView(undefined).label).toBe('—');
  });
});

describe('batchStatusView: the batch vocabulary', () => {
  it.each([
    ['SUBMITTED', 'Submitted', 'tide', true],
    ['COMPLETED', 'Completed', 'go', false],
    ['FAILED', 'Failed', 'coral', false],
    ['EXPIRED', 'Expired', 'coral', false],
    ['CANCELLED', 'Cancelled', 'off', false],
  ])('%s reads %s, tone %s, halo %s', (status, label, tone, halo) => {
    expect(batchStatusView(status)).toEqual({ label, tone, halo });
  });

  it('reads an unknown batch status as itself', () => {
    expect(batchStatusView('PAUSED').label).toBe('PAUSED');
  });
});

describe('needsAttentionStatus', () => {
  it('is Degraded, Partial and Failed, as the server counts them', () => {
    expect(['DEGRADED', 'PARTIAL', 'FAILED'].map(needsAttentionStatus)).toEqual([true, true, true]);
  });

  it('is not Running or Done, or nothing', () => {
    expect(['RUNNING', 'DONE', null, undefined, 'WHATEVER'].map(needsAttentionStatus))
      .toEqual([false, false, false, false, false]);
  });
});

describe('noteToneClass', () => {
  it('colours info as tide, a warning as amber and an error as coral', () => {
    expect(noteToneClass('INFO')).toBe('text-tide');
    expect(noteToneClass('WARN')).toBe('text-verdict-marginal');
    expect(noteToneClass('ERROR')).toBe('text-plex-coral');
  });

  it('falls back to the secondary ink for no tone', () => {
    expect(noteToneClass(null)).toBe('text-plex-text-secondary');
  });
});
