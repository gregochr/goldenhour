import { describe, it, expect } from 'vitest';
import { phaseRows, waitingBatches } from '../utils/cycleView.js';

describe('cycleView edges', () => {
  it('a RUNNING cycle with no served phases lists every known phase as pending, from the first', () => {
    const rows = phaseRows({ run: { status: 'RUNNING', cycleType: 'INTRADAY' }, phases: [] });
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.every((r) => r.status === 'PENDING')).toBe(true);
    expect(rows[0].phase).toBe('STABILITY_RECLASSIFY');
    expect(rows.map((r) => r.sequenceOrder)).toEqual(rows.map((_, i) => i + 1));
  });

  it('a RUNNING NIGHTLY cycle with no served phases omits the re-classify phase', () => {
    const rows = phaseRows({ run: { status: 'RUNNING', cycleType: 'NIGHTLY' }, phases: [] });
    expect(rows.map((r) => r.phase)).not.toContain('STABILITY_RECLASSIFY');
    expect(rows.length).toBeGreaterThan(0);
  });

  it('appends only the phases after the last started one, numbered after it', () => {
    const rows = phaseRows({
      run: { status: 'RUNNING', cycleType: 'INTRADAY' },
      phases: [{ phase: 'FORECAST_BATCH_SUBMIT', sequenceOrder: 4, status: 'COMPLETE' }],
    });
    expect(rows[0].phase).toBe('FORECAST_BATCH_SUBMIT');
    expect(rows.map((r) => r.phase)).not.toContain('STABILITY_RECLASSIFY');
    expect(rows[1].phase).toBe('FORECAST_BATCH_WAIT');
    expect(rows[1].sequenceOrder).toBe(5);
    expect(rows[2].sequenceOrder).toBe(6);
  });

  it('names the earliest submitted batch even when the later one is listed first', () => {
    const batches = [
      { status: 'SUBMITTED', submittedAt: '2026-11-07T10:00:00Z' },
      { status: 'SUBMITTED', submittedAt: '2026-11-07T08:00:00Z' },
      { status: 'DONE', submittedAt: '2026-11-07T06:00:00Z' },
    ];
    const out = waitingBatches({ batches });
    expect(out.submittedAt).toBe('2026-11-07T08:00:00Z');
    expect(out.waiting).toHaveLength(2);
    expect(out.total).toBe(3);
  });

  it('sorts an unreadable submission time without throwing', () => {
    const batches = [
      { status: 'SUBMITTED', submittedAt: 'garbage' },
      { status: 'SUBMITTED', submittedAt: '2026-11-07T08:00:00Z' },
    ];
    expect(waitingBatches({ batches }).submittedAt).toBe('garbage');
    const reversed = [...batches].reverse();
    expect(waitingBatches({ batches: reversed }).submittedAt).toBe('garbage');
  });
});
