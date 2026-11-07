import { describe, it, expect } from 'vitest';
import { cycleStatusView, runStatusView } from '../utils/runStatus.js';

describe('runStatus edges', () => {
  it('reads an empty or missing status as a dash and an unknown one as itself', () => {
    expect(runStatusView('')).toEqual({ label: '—', tone: 'off', halo: false });
    expect(runStatusView(null)).toEqual({ label: '—', tone: 'off', halo: false });
    expect(runStatusView(undefined)).toEqual({ label: '—', tone: 'off', halo: false });
    expect(runStatusView('ODD')).toEqual({ label: 'ODD', tone: 'off', halo: false });
  });

  it('reads a COMPLETED pipeline run as Done and passes the rest through', () => {
    expect(cycleStatusView('COMPLETED')).toEqual(runStatusView('DONE'));
    expect(cycleStatusView('COMPLETED').label).toBe('Done');
    expect(cycleStatusView('FAILED').label).toBe('Failed');
    expect(cycleStatusView('RUNNING').halo).toBe(true);
  });
});
