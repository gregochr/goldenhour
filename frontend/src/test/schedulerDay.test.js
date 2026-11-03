import { describe, it, expect } from 'vitest';
import {
  buildRows,
  dayAxis,
  dayDateLabel,
  dayInRange,
  dayWord,
  fireDurationSeconds,
  fireStatusStyle,
  fireStartMs,
  fireWidthPx,
  formatFireDuration,
  gridlines,
  kindStyle,
  markShape,
  needsALook,
  nightBands,
  onAxis,
  repeatingShape,
  rowAriaLabel,
  rowHeightFor,
  runsLinkLabel,
  summaryCounts,
  timeToX,
  ukWallInstant,
} from '../utils/schedulerDay.js';
import { dayData, dayFire, dayJob, dayRepeatingJob } from './schedulerDayFixtures.js';

// The Day picture's pure geometry and view model (scheduler-redesign-plan.md D12, S5a).

const iso = (ms) => new Date(ms).toISOString();

/** The three kinds of UK day, as the server serves their axis instants. */
const NORMAL = { dayStart: '2026-11-03T00:00:00Z', dayEnd: '2026-11-04T00:00:00Z' };
const SPRING = { dayStart: '2026-03-29T00:00:00Z', dayEnd: '2026-03-29T23:00:00Z' };
const AUTUMN = { dayStart: '2026-10-24T23:00:00Z', dayEnd: '2026-10-26T00:00:00Z' };

describe('dayAxis and timeToX', () => {
  it('is the served instants, and null when either is missing or the span is empty', () => {
    expect(dayAxis(NORMAL)).toEqual({ startMs: Date.parse(NORMAL.dayStart), endMs: Date.parse(NORMAL.dayEnd) });
    expect(dayAxis({ dayStart: NORMAL.dayStart })).toBeNull();
    expect(dayAxis({ dayStart: NORMAL.dayEnd, dayEnd: NORMAL.dayStart })).toBeNull();
    expect(dayAxis(null)).toBeNull();
  });

  it('maps a 24-hour day linearly: noon is the middle', () => {
    const axis = dayAxis(NORMAL);
    expect(timeToX(Date.parse('2026-11-03T12:00:00Z'), axis, 1000)).toBe(500);
    expect(timeToX(axis.startMs, axis, 1000)).toBe(0);
    expect(timeToX(axis.endMs, axis, 1000)).toBe(1000);
  });

  it('maps the 23-hour spring day over 23 hours, so 12:00 UTC is 12/23 of the width', () => {
    const axis = dayAxis(SPRING);
    expect(timeToX(Date.parse('2026-03-29T12:00:00Z'), axis, 2300)).toBeCloseTo(1200, 6);
    expect(timeToX(axis.endMs, axis, 2300)).toBe(2300);
  });

  it('maps the 25-hour autumn day over 25 hours, the doubled hour taking its own width', () => {
    const axis = dayAxis(AUTUMN);
    // 01:00 BST (00:00Z) and 01:00 GMT (01:00Z) are an hour apart on the axis: 1/25 of the width.
    const first = timeToX(Date.parse('2026-10-25T00:00:00Z'), axis, 2500);
    const second = timeToX(Date.parse('2026-10-25T01:00:00Z'), axis, 2500);
    expect(second - first).toBeCloseTo(100, 6);
    expect(timeToX(axis.endMs, axis, 2500)).toBe(2500);
  });

  it('says whether an instant is on the axis', () => {
    const axis = dayAxis(NORMAL);
    expect(onAxis(Date.parse('2026-11-03T05:00:00Z'), axis)).toBe(true);
    expect(onAxis(Date.parse('2026-11-04T05:00:00Z'), axis)).toBe(false);
    expect(onAxis(null, axis)).toBe(false);
  });
});

describe('ukWallInstant', () => {
  it('is the UTC instant in winter and an hour earlier in summer', () => {
    expect(iso(ukWallInstant('2026-11-03', 6))).toBe('2026-11-03T06:00:00.000Z');
    expect(iso(ukWallInstant('2026-07-01', 6))).toBe('2026-07-01T05:00:00.000Z');
  });

  it('resolves the hours either side of the spring and autumn changes', () => {
    expect(iso(ukWallInstant('2026-03-29', 0))).toBe('2026-03-29T00:00:00.000Z');
    expect(iso(ukWallInstant('2026-03-29', 3))).toBe('2026-03-29T02:00:00.000Z');
    expect(iso(ukWallInstant('2026-10-25', 0))).toBe('2026-10-24T23:00:00.000Z');
    expect(iso(ukWallInstant('2026-10-25', 3))).toBe('2026-10-25T03:00:00.000Z');
  });
});

describe('gridlines', () => {
  it('is nine lines for a normal day, 00:00 to 24:00 every three hours, labelled in UK time', () => {
    const lines = gridlines(dayAxis(NORMAL));
    expect(lines.map((l) => l.label)).toEqual(['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '24:00']);
    expect(lines.map((l) => iso(l.ms))).toEqual([
      '2026-11-03T00:00:00.000Z', '2026-11-03T03:00:00.000Z', '2026-11-03T06:00:00.000Z', '2026-11-03T09:00:00.000Z',
      '2026-11-03T12:00:00.000Z', '2026-11-03T15:00:00.000Z', '2026-11-03T18:00:00.000Z', '2026-11-03T21:00:00.000Z',
      '2026-11-04T00:00:00.000Z',
    ]);
  });

  it('keeps UK wall-clock labels on the 23-hour spring day, the lines an hour closer after the change', () => {
    const lines = gridlines(dayAxis(SPRING));
    expect(lines.map((l) => l.label)).toEqual(['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '24:00']);
    expect(iso(lines[0].ms)).toBe('2026-03-29T00:00:00.000Z');
    expect(iso(lines[1].ms)).toBe('2026-03-29T02:00:00.000Z'); // 03:00 BST: only two hours after 00:00 GMT
    expect(iso(lines[2].ms)).toBe('2026-03-29T05:00:00.000Z');
    expect(iso(lines[8].ms)).toBe('2026-03-29T23:00:00.000Z');
  });

  it('keeps UK wall-clock labels on the 25-hour autumn day, the first gap four hours long', () => {
    const lines = gridlines(dayAxis(AUTUMN));
    expect(lines.map((l) => l.label)).toEqual(['00:00', '03:00', '06:00', '09:00', '12:00', '15:00', '18:00', '21:00', '24:00']);
    expect(iso(lines[0].ms)).toBe('2026-10-24T23:00:00.000Z');
    expect(iso(lines[1].ms)).toBe('2026-10-25T03:00:00.000Z'); // 03:00 GMT: four hours after 00:00 BST
    expect(iso(lines[2].ms)).toBe('2026-10-25T06:00:00.000Z');
    expect(iso(lines[8].ms)).toBe('2026-10-26T00:00:00.000Z');
  });

  it('accepts a finer step for the close-up window', () => {
    expect(gridlines(dayAxis(NORMAL), 6).map((l) => l.label)).toEqual(['00:00', '06:00', '12:00', '18:00', '24:00']);
  });
});

describe('nightBands', () => {
  const axis = dayAxis(NORMAL);

  it('shades from the day start to sunrise and from sunset to the day end', () => {
    expect(nightBands(axis, '2026-11-03T07:03:00Z', '2026-11-03T16:30:00Z')).toEqual([
      { startMs: axis.startMs, endMs: Date.parse('2026-11-03T07:03:00Z') },
      { startMs: Date.parse('2026-11-03T16:30:00Z'), endMs: axis.endMs },
    ]);
  });

  it('leaves out a band whose sun event does not occur', () => {
    expect(nightBands(axis, null, '2026-11-03T16:30:00Z')).toHaveLength(1);
    expect(nightBands(axis, '2026-11-03T07:03:00Z', null)).toHaveLength(1);
    expect(nightBands(axis, null, null)).toEqual([]);
  });

  it('works on a clock-change day (the bands are instants)', () => {
    const spring = dayAxis(SPRING);
    const bands = nightBands(spring, '2026-03-29T05:25:00Z', '2026-03-29T18:00:00Z');
    expect(bands[0].endMs - bands[0].startMs).toBe((5 * 60 + 25) * 60_000);
    expect(bands[1].endMs - bands[1].startMs).toBe(5 * 3_600_000);
  });
});

describe('the mark thresholds', () => {
  it('draws a bar narrower than 6px as a diamond, and 6px or wider as a bar', () => {
    expect(markShape({ widthPx: 0 })).toBe('diamond');
    expect(markShape({ widthPx: 5.9 })).toBe('diamond');
    expect(markShape({ widthPx: 6 })).toBe('bar');
    expect(markShape({ widthPx: 40 })).toBe('bar');
  });

  it('draws a mark closer than 3px to its next neighbour as a tick, whatever its width', () => {
    expect(markShape({ widthPx: 0, gapNextPx: 2.9 })).toBe('tick');
    expect(markShape({ widthPx: 30, gapNextPx: 2 })).toBe('tick');
    expect(markShape({ widthPx: 0, gapNextPx: 3 })).toBe('diamond');
  });

  it('draws a bar that would overlap its next neighbour as a tick', () => {
    expect(markShape({ widthPx: 30, gapNextPx: 20 })).toBe('tick');
    expect(markShape({ widthPx: 30, gapNextPx: 30 })).toBe('bar');
  });

  it('draws the last fire of a crowded pair as a tick too: its previous neighbour is too close or reaches it', () => {
    // Two fires 2px apart: both ticks.
    expect(markShape({ widthPx: 0, gapNextPx: 2 })).toBe('tick');
    expect(markShape({ widthPx: 0, gapPrevPx: 2 })).toBe('tick');
    // A 30px bar then a fire 20px after it: the bar and the second are both ticks.
    expect(markShape({ widthPx: 30, gapNextPx: 20 })).toBe('tick');
    expect(markShape({ widthPx: 0, gapPrevPx: 20, prevWidthPx: 30 })).toBe('tick');
    expect(markShape({ widthPx: 0, gapPrevPx: 40, prevWidthPx: 30 })).toBe('diamond');
  });

  it('splits a repeating row into ticks or one band at 3px', () => {
    expect(repeatingShape(2.9)).toBe('band');
    expect(repeatingShape(3)).toBe('ticks');
  });
});

describe('fire durations and widths', () => {
  it('reads a duration from start to end, and none for a fire not yet finished or not yet run', () => {
    expect(fireDurationSeconds(dayFire())).toBe(55);
    expect(formatFireDuration(dayFire())).toBe('55s');
    expect(formatFireDuration(dayFire({ completedAt: '2026-11-03T01:02:05Z' }))).toBe('2m 5s');
    expect(formatFireDuration(dayFire({ completedAt: '2026-11-03T01:00:00.200Z' }))).toBe('<1s');
    expect(fireDurationSeconds(dayFire({ completedAt: null }))).toBeNull();
    expect(fireDurationSeconds(dayFire({ startedAt: null, completedAt: null }))).toBeNull();
    expect(formatFireDuration(dayFire({ completedAt: null }))).toBe('');
  });

  it('scales a bar by its share of the axis, and a 55 second fire is far under the diamond threshold', () => {
    const axis = dayAxis(NORMAL);
    const thirtyMinutes = dayFire({ completedAt: '2026-11-03T01:30:00Z' });
    expect(fireWidthPx(thirtyMinutes, axis, 1440)).toBeCloseTo(30, 6);
    expect(fireWidthPx(dayFire(), axis, 1000)).toBeLessThan(1);
    expect(fireWidthPx(dayFire({ completedAt: null }), axis, 1000)).toBe(0);
  });
});

describe('status and kind styles', () => {
  it('maps every served status to a colour, with the ones that did not happen hollow', () => {
    expect(fireStatusStyle('SUCCEEDED')).toMatchObject({ label: 'OK', fill: 'fill-plex-ok', hollow: false });
    expect(fireStatusStyle('PARTIAL')).toMatchObject({ label: 'Partial', fill: 'fill-verdict-marginal', hollow: false });
    expect(fireStatusStyle('FAILED')).toMatchObject({ label: 'Failed', fill: 'fill-plex-coral', hollow: false });
    expect(fireStatusStyle('RUNNING')).toMatchObject({ label: 'Running', fill: 'fill-tide', hollow: false });
    expect(fireStatusStyle('SKIPPED')).toMatchObject({ label: 'Skipped', hollow: false });
    expect(fireStatusStyle('MISSED')).toMatchObject({ label: 'Missed', stroke: 'stroke-plex-coral', hollow: true });
    expect(fireStatusStyle('SCHEDULED')).toMatchObject({ label: 'To run', hollow: true });
  });

  it('reads an unknown status as a quiet hollow mark carrying its own word', () => {
    expect(fireStatusStyle('WEIRD')).toMatchObject({ label: 'WEIRD', hollow: true });
  });

  it('colours sun-relative jobs with the sun token and the rest as cron (tide)', () => {
    expect(kindStyle('SOLAR_OFFSET').fill).toBe('fill-scheduler-sun');
    expect(kindStyle('CRON').fill).toBe('fill-tide');
  });

  it('draws a job whose type is not known (the list has not loaded) neutral, never as cron', () => {
    expect(kindStyle(undefined).fill).toBe('fill-plex-text-muted');
    expect(kindStyle(null).stroke).toBe('stroke-plex-text-muted');
    expect(kindStyle('NOPE').fill).not.toBe('fill-tide');
  });
});

describe('buildRows', () => {
  const jobs = [
    { jobKey: 'a_job', scheduleType: 'SOLAR_OFFSET', status: 'ACTIVE', catalogue: { usesClaude: true, alias: 'The A' } },
    { jobKey: 'b_job', scheduleType: 'CRON', status: 'PAUSED', catalogue: null },
  ];

  it('makes one row per scheduled job, sorted by first fire, joined to the job list', () => {
    const day = dayData({
      jobs: [
        dayJob({ jobKey: 'b_job', displayName: 'B job', fires: [dayFire({ at: '2026-11-03T05:00:00Z' })] }),
        dayJob({ jobKey: 'a_job', displayName: 'A job', fires: [dayFire({ at: '2026-11-03T03:00:00Z' })] }),
        dayRepeatingJob(),
      ],
    });
    const rows = buildRows(day, jobs);
    expect(rows.map((r) => r.jobKey)).toEqual(['a_job', 'b_job']);
    expect(rows[0]).toMatchObject({ scheduleType: 'SOLAR_OFFSET', paused: false, usesClaude: true, alias: 'The A' });
    expect(rows[1]).toMatchObject({ scheduleType: 'CRON', paused: true, usesClaude: false });
  });

  it('drops a job with no fires that day, but keeps a paused one so its row shows', () => {
    const day = dayData({
      jobs: [dayJob({ jobKey: 'a_job', fires: [] }), dayJob({ jobKey: 'b_job', fires: [] })],
    });
    expect(buildRows(day, jobs).map((r) => r.jobKey)).toEqual(['b_job']);
  });

  it('keeps a paused job the day does not list at all (the server serves a paused job nothing), with a hook for would-be fires', () => {
    const rows = buildRows(dayData({ jobs: [dayJob({ jobKey: 'a_job', displayName: 'A job' })] }), [
      ...jobs,
      { jobKey: 'weekly', displayName: 'Weekly thing', scheduleType: 'CRON', status: 'PAUSED', catalogue: null },
      { jobKey: 'poller', displayName: 'Poller', scheduleType: 'FIXED_DELAY', status: 'PAUSED', catalogue: null },
    ]);
    expect(rows.map((r) => r.jobKey).sort()).toEqual(['a_job', 'b_job', 'weekly']);
    expect(rows.find((r) => r.jobKey === 'weekly')).toMatchObject({ name: 'Weekly thing', fires: [], wouldBe: [], paused: true });
  });

  it('makes no rows from the list alone before the day has loaded', () => {
    expect(buildRows(null, jobs)).toEqual([]);
  });

  it('has no schedule type, colour source or £ for a job before the list has loaded', () => {
    const [row] = buildRows(dayData(), []);
    expect(row).toMatchObject({ scheduleType: null, usesClaude: false, paused: false });
  });

  it('sorts a job\'s own fires by time and copes with a job the list does not know', () => {
    const day = dayData({
      jobs: [dayJob({ jobKey: 'new_job', fires: [dayFire({ at: '2026-11-03T09:00:00Z' }), dayFire({ at: '2026-11-03T08:00:00Z' })] })],
    });
    const [row] = buildRows(day, []);
    expect(row.fires.map((f) => f.at)).toEqual(['2026-11-03T08:00:00Z', '2026-11-03T09:00:00Z']);
    expect(row).toMatchObject({ scheduleType: null, paused: false, usesClaude: false });
  });
});

describe('rowAriaLabel', () => {
  const base = { jobKey: 'a', name: 'Tide Refresh', paused: false, usesClaude: false };

  it('names the job, its last run, the time and how long it took', () => {
    const label = rowAriaLabel({ ...base, fires: [dayFire({ at: '2026-11-03T02:00:00Z', startedAt: '2026-11-03T02:00:00Z', completedAt: '2026-11-03T02:00:55Z' })] });
    expect(label).toBe('Tide Refresh, last run ok at 02:00, took 55s');
  });

  it('takes the newest fire that happened, not a future one or a missed one', () => {
    const label = rowAriaLabel({
      ...base,
      fires: [
        dayFire({ at: '2026-11-03T02:00:00Z', status: 'FAILED', completedAt: null }),
        dayFire({ at: '2026-11-03T14:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null }),
      ],
    });
    expect(label).toBe('Tide Refresh, last run failed at 02:00');
  });

  it('says when it fires next if nothing has run, and adds paused and uses Claude', () => {
    const label = rowAriaLabel({
      ...base, paused: true, usesClaude: true, fires: [dayFire({ at: '2026-11-03T14:00:00Z', status: 'SCHEDULED', startedAt: null, completedAt: null })],
    });
    expect(label).toBe('Tide Refresh, next fire 14:00, paused, uses Claude');
    expect(rowAriaLabel({ ...base, fires: [] })).toBe('Tide Refresh, no fires');
    expect(rowAriaLabel({ ...base, paused: true, fires: [] })).toBe('Tide Refresh, paused');
  });

  it('ends with the job\'s alias', () => {
    expect(rowAriaLabel({ ...base, usesClaude: true, alias: 'The tide fetch', fires: [] })).toBe('Tide Refresh, no fires, uses Claude, The tide fetch');
  });

  it('names a missed fire: after the last run, or alone when it is the only fire (never "no fires")', () => {
    const missed = dayFire({ at: '2026-11-03T04:00:00Z', status: 'MISSED', startedAt: null, completedAt: null });
    expect(rowAriaLabel({ ...base, fires: [dayFire({ at: '2026-11-03T01:00:00Z' }), missed] }))
      .toBe('Tide Refresh, last run ok at 01:00, took 55s, missed at 04:00');
    expect(rowAriaLabel({ ...base, fires: [missed] })).toBe('Tide Refresh, missed at 04:00');
  });
});

describe('summaryCounts', () => {
  it('counts the scheduled jobs\' fires by status and leaves the repeating jobs out', () => {
    const day = dayData({
      jobs: [
        dayJob({
          fires: [
            dayFire(), dayFire(), dayFire({ status: 'PARTIAL' }), dayFire({ status: 'FAILED' }), dayFire({ status: 'MISSED' }),
            dayFire({ status: 'RUNNING' }), dayFire({ status: 'SKIPPED' }), dayFire({ status: 'SCHEDULED' }),
          ],
        }),
        dayRepeatingJob({ counts: { SUCCEEDED: 99 } }),
      ],
    });
    expect(summaryCounts(day)).toEqual({ succeeded: 2, partial: 1, failed: 1, missed: 1, running: 1, skipped: 1, toRun: 1 });
  });

  it('is all zero for no day', () => {
    expect(summaryCounts(null)).toEqual({ succeeded: 0, partial: 0, failed: 0, missed: 0, running: 0, skipped: 0, toRun: 0 });
  });
});

describe('needsALook', () => {
  const problem = (hour, summary = 'boom') => ({ status: 'FAILED', at: `2026-11-03T${String(hour).padStart(2, '0')}:00:00Z`, summary });

  it('lists failed, partial and missed fires with the repeating problems, in time order', () => {
    const day = dayData({
      jobs: [
        dayJob({ fires: [dayFire({ at: '2026-11-03T03:00:00Z', status: 'PARTIAL', summary: 'one expired' }), dayFire({ at: '2026-11-03T05:00:00Z' }), dayFire({ at: '2026-11-03T09:00:00Z', status: 'MISSED' })] }),
        dayRepeatingJob({ problems: [problem(4)] }),
      ],
    });
    const { items, total, more } = needsALook(day);
    expect(items.map((i) => `${i.at.slice(11, 13)} ${i.status} ${i.repeating}`)).toEqual([
      '03 PARTIAL false', '04 FAILED true', '09 MISSED false',
    ]);
    expect(items[0].summary).toBe('one expired');
    expect([total, more]).toEqual([3, 0]);
  });

  it('shows the eight newest and counts the older ones, including problems the server left out', () => {
    const many = dayRepeatingJob({ problems: Array.from({ length: 9 }, (_, i) => problem(i + 1)), problemsOmitted: 41 });
    const { items, total, more } = needsALook(dayData({ jobs: [many] }));
    expect(items).toHaveLength(8);
    expect(items.map((i) => i.at.slice(11, 13))).toEqual(['02', '03', '04', '05', '06', '07', '08', '09']);
    expect(total).toBe(50);
    expect(more).toBe(42);
  });

  it('keeps an 18:00 failure that follows twenty earlier poller failures, shown in ascending time order', () => {
    const poller = dayRepeatingJob({ problems: Array.from({ length: 20 }, (_, i) => problem(i % 17 + 1, `poll ${i}`)) });
    const evening = dayJob({ jobKey: 'evening', displayName: 'Evening job', fires: [dayFire({ at: '2026-11-03T18:00:00Z', status: 'FAILED', summary: 'late' })] });
    const { items, more } = needsALook(dayData({ jobs: [poller, evening] }));
    expect(items).toHaveLength(8);
    expect(items[items.length - 1]).toMatchObject({ jobKey: 'evening', summary: 'late' });
    const times = items.map((i) => Date.parse(i.at));
    expect([...times].sort((a, b) => a - b)).toEqual(times);
    expect(more).toBe(13);
  });

  it('is empty for a clean day', () => {
    expect(needsALook(dayData())).toEqual({ items: [], total: 0, more: 0 });
  });
});

describe('day words and labels', () => {
  it('words the viewed day against today', () => {
    expect(dayWord('2026-11-03', '2026-11-03')).toBe('Today');
    expect(dayWord('2026-11-04', '2026-11-03')).toBe('Tomorrow');
    expect(dayWord('2026-11-02', '2026-11-03')).toBe('Yesterday');
    expect(dayWord('2026-10-30', '2026-11-03')).toBe('Fri 30 Oct');
    expect(dayDateLabel('2026-11-02')).toBe('Mon 2 Nov');
  });

  it('labels the link into Runs "today", "yesterday" or the date, and hides it for a future day', () => {
    expect(runsLinkLabel('2026-11-03', '2026-11-03')).toBe('today');
    expect(runsLinkLabel('2026-11-02', '2026-11-03')).toBe('yesterday');
    expect(runsLinkLabel('2026-10-30', '2026-11-03')).toBe('Fri 30 Oct');
    expect(runsLinkLabel('2026-11-04', '2026-11-03')).toBeNull();
    expect(runsLinkLabel('2026-11-10', '2026-11-03')).toBeNull();
  });

  it('reaches seven days either side of today and no further', () => {
    expect(dayInRange('2026-10-27', '2026-11-03')).toBe(true);
    expect(dayInRange('2026-10-26', '2026-11-03')).toBe(false);
    expect(dayInRange('2026-11-10', '2026-11-03')).toBe(true);
    expect(dayInRange('2026-11-11', '2026-11-03')).toBe(false);
  });

  it('steps across a clock-change boundary by calendar day', () => {
    expect(dayInRange('2026-10-25', '2026-10-30')).toBe(true);
    expect(dayWord('2026-10-26', '2026-10-25')).toBe('Tomorrow');
  });
});

describe('row heights', () => {
  it('is 24px on desktop and 44px on a phone', () => {
    expect(rowHeightFor(false)).toBe(24);
    expect(rowHeightFor(true)).toBe(44);
  });
});

describe('a running fire', () => {
  const axis = dayAxis(NORMAL);
  const running = dayFire({
    at: '2026-11-03T11:00:00Z', startedAt: '2026-11-03T11:00:00Z', completedAt: null, status: 'RUNNING',
  });

  it('grows from its start to Now on today', () => {
    const now = Date.parse('2026-11-03T12:00:00Z');
    expect(fireWidthPx(running, axis, 2400, { nowMs: now, isToday: true })).toBeCloseTo(100, 6);
  });

  it('is not grown to Now on another day', () => {
    const now = Date.parse('2026-11-03T12:00:00Z');
    expect(fireWidthPx(running, axis, 2400, { nowMs: now, isToday: false })).toBe(0);
  });

  it('starts where it actually started', () => {
    const late = dayFire({ ...running, at: '2026-11-03T10:00:00Z' });
    expect(fireStartMs(late)).toBe(Date.parse('2026-11-03T11:00:00Z'));
    expect(fireStartMs(dayFire())).toBe(Date.parse('2026-11-03T01:00:00Z'));
  });
});
