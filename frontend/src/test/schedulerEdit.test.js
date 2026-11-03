import { describe, it, expect } from 'vitest';
import {
  SLIDER_BOUNDS, cronEditorModel, delayOptions, formatOffsetSpan, isOnGrid, neighboursText, parseClockCron,
  parseTimesInput, savedMessage, snapToGrid,
} from '../utils/schedulerEdit.js';

// The pure helpers behind the Scheduler's schedule editors (scheduler-redesign-plan.md S4b).

describe('the slider grid', () => {
  it('is always 30 minutes to 8 hours in 5-minute steps', () => {
    expect(SLIDER_BOUNDS).toEqual({ min: 30, max: 480, step: 5 });
  });

  it('snaps to the nearest step, clamped to the range', () => {
    expect(snapToGrid(37)).toBe(35);
    expect(snapToGrid(38)).toBe(40);
    expect(snapToGrid(503)).toBe(480);
    expect(snapToGrid(0)).toBe(30);
    expect(snapToGrid(255)).toBe(255);
  });

  it('knows which offsets the grid can hold', () => {
    expect([255, 30, 480].every(isOnGrid)).toBe(true);
    expect([37, 503, 0, 25].some(isOnGrid)).toBe(false);
  });

  it('writes a stored span in the compact words', () => {
    expect(formatOffsetSpan(243)).toBe('4h 3m');
    expect(formatOffsetSpan(240)).toBe('4h');
    expect(formatOffsetSpan(37)).toBe('37m');
    expect(formatOffsetSpan(0)).toBe('0m');
  });
});

describe('parseClockCron', () => {
  it('reads daily and weekly clock times', () => {
    expect(parseClockCron('0 15 6,18 * * *')).toEqual({ minute: 15, hours: [6, 18], days: null });
    expect(parseClockCron('0 0 2 * * MON')).toEqual({ minute: 0, hours: [2], days: ['MON'] });
    expect(parseClockCron('0 30 4 * * MON,WED')).toEqual({ minute: 30, hours: [4], days: ['MON', 'WED'] });
    expect(parseClockCron('0 0 4 * * 0')).toEqual({ minute: 0, hours: [4], days: ['SUN'] });
  });

  it.each([
    '0 20 * * * *',
    '0 0 4 1 * *',
    '0 0 4 * 6 *',
    '0 */5 * * * *',
    '0 0 4 * * MON-FRI',
    '0 0 25 * * *',
    'nonsense',
    '',
  ])('keeps the raw field for %j', (cron) => {
    expect(parseClockCron(cron)).toBeNull();
  });
});

describe('cronEditorModel', () => {
  it('shows a Europe/London cron as it is', () => {
    expect(cronEditorModel({ cronExpression: '0 15 6,18 * * *', timeZone: 'Europe/London' }))
      .toEqual({ times: ['06:15', '18:15'], days: null });
  });

  it('converts a UTC cron to UK time at the offset of its next fire (BST)', () => {
    const job = { cronExpression: '0 0 1 * * *', timeZone: 'UTC', nextFires: ['2026-07-01T00:00:00Z'] };
    expect(cronEditorModel(job)).toEqual({ times: ['02:00'], days: null });
  });

  it('converts a UTC cron to UK time in winter (GMT)', () => {
    const job = { cronExpression: '0 0 1 * * *', timeZone: 'UTC', nextFireTime: '2026-12-01T01:00:00Z' };
    expect(cronEditorModel(job)).toEqual({ times: ['01:00'], days: null });
  });

  it('moves a weekly UTC job to the UK day when the conversion crosses midnight', () => {
    const job = { cronExpression: '0 30 23 * * MON', timeZone: 'UTC', nextFires: ['2026-07-06T23:30:00Z'] };
    expect(cronEditorModel(job)).toEqual({ times: ['00:30'], days: ['TUE'] });
  });

  it('converts every hour at the ONE offset in force at the next fire, across the October clock change', () => {
    // 00:30Z on 25 Oct 2026 is still BST (the clocks go back at 01:00Z): both hours use +1, not +1 and +0.
    const before = { cronExpression: '0 30 0,12 * * *', timeZone: 'UTC', nextFires: ['2026-10-25T00:30:00Z'] };
    expect(cronEditorModel(before)).toEqual({ times: ['01:30', '13:30'], days: null });
    const after = { cronExpression: '0 30 0,12 * * *', timeZone: 'UTC', nextFires: ['2026-10-26T00:30:00Z'] };
    expect(cronEditorModel(after)).toEqual({ times: ['00:30', '12:30'], days: null });
  });

  it('is null for a cron that is not clock times', () => {
    expect(cronEditorModel({ cronExpression: '0 20 * * * *', timeZone: 'UTC' })).toBeNull();
  });
});

describe('parseTimesInput', () => {
  it('reads comma-separated 24-hour times, padded, without repeats', () => {
    expect(parseTimesInput('6:15, 18:15 ,06:15')).toEqual({ ok: true, times: ['06:15', '18:15'] });
  });

  it('asks for at least one time', () => {
    expect(parseTimesInput('  , ')).toEqual({ ok: false, message: 'Enter at least one time, e.g. 06:15.' });
  });

  it('says which entry is not a time', () => {
    expect(parseTimesInput('06:15, soon').message).toContain('"soon" isn\'t a time');
  });

  it.each(['24:00', '12:60', '99:99'])('says %s is out of range', (bad) => {
    expect(parseTimesInput(bad).message).toContain('00:00 to 23:59');
  });
});

describe('delayOptions', () => {
  it('offers the five presets for a preset delay', () => {
    expect(delayOptions(300_000).map((o) => o.label)).toEqual(['1 min', '5 min', '10 min', '30 min', '1 hour']);
  });

  it('adds a delay that is not a preset, labelled with its duration, in its place by size', () => {
    const options = delayOptions(150_000);
    expect(options.map((o) => o.value)).toEqual(['60000', '150000', '300000', '600000', '1800000', '3600000']);
    expect(options[1].label).toBe('2 min 30 s');
    expect(delayOptions(7_200_000).at(-1).label).toBe('2 h');
  });
});

describe('neighboursText', () => {
  it('lists each neighbour with its UK time and gap', () => {
    const text = neighboursText([
      { displayName: 'Tide Refresh', at: '2026-11-04T02:00:00Z', minutesApart: 15 },
      { displayName: 'Aurora Batch', at: '2026-11-04T02:20:00Z', minutesApart: 5 },
    ]);
    expect(text).toBe('Neighbours: Tide Refresh 02:00 (15 min apart) · Aurora Batch 02:20 (5 min apart)');
  });

  it('says "at the same time" for a gap of 0', () => {
    expect(neighboursText([{ displayName: 'Tide Refresh', at: '2026-11-04T02:00:00Z', minutesApart: 0 }]))
      .toBe('Neighbours: Tide Refresh 02:00 (at the same time)');
  });

  it('says so when nothing is near', () => {
    expect(neighboursText([])).toBe('Nothing within 30 minutes.');
    expect(neighboursText(undefined)).toBe('Nothing within 30 minutes.');
  });
});

describe('savedMessage', () => {
  const NOW = new Date('2026-11-03T10:00:00Z');
  const active = (nextFires, nextFireTime = '') => ({ status: 'ACTIVE', nextFires, nextFireTime });

  it('quotes the served next fire as Today', () => {
    expect(savedMessage(active(['2026-11-03T18:15:00Z']), NOW)).toBe('Saved. Next fire Today 18:15.');
  });

  it('quotes it as Tomorrow', () => {
    expect(savedMessage(active(['2026-11-04T12:27:00Z']), NOW)).toBe('Saved. Next fire Tomorrow 12:27.');
  });

  it('quotes a later one by weekday and date', () => {
    expect(savedMessage(active(['2026-11-05T02:00:00Z']), NOW)).toBe('Saved. Next fire Thu 5 Nov 02:00.');
  });

  it('falls back to nextFireTime when nextFires is empty', () => {
    expect(savedMessage(active([], '2026-11-04T12:27:00Z'), NOW)).toBe('Saved. Next fire Tomorrow 12:27.');
  });

  it('says "Saved." alone with no next fire, or for a paused job', () => {
    expect(savedMessage(active([]), NOW)).toBe('Saved.');
    expect(savedMessage({ status: 'PAUSED', nextFires: [], nextFireTime: '2026-11-04T12:27:00Z' }, NOW)).toBe('Saved.');
    expect(savedMessage(null, NOW)).toBe('Saved.');
  });
});
