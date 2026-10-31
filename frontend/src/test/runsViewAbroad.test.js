/**
 * The Runs list's "Today" and "Yesterday" words, read from a device that is NOT in the UK.
 *
 * <p>This is where the old {@code metricsTodayFilterAbroad.test.jsx} went. The client no longer filters runs by day (the
 * server does, on UK days), but it still decides which word a start time wears, and that must be the UK calendar:
 * <ul>
 *   <li><b>Two calendars in one comparison.</b> The device's date against a UTC date agree on a UK machine in winter and
 *       nowhere else.</li>
 *   <li><b>A bare instant read as local time.</b> A job run's own {@code startedAt} is a {@code LocalDateTime} and
 *       arrives without a zone, so {@code new Date("2026-08-13T06:00:00")} is hours adrift here.</li>
 * </ul>
 *
 * <p>⚠️ Under {@code TZ=UTC} both bugs are invisible, which is why this file pins its own zone.
 */
process.env.TZ = 'America/New_York';

import { describe, it, expect } from 'vitest';
import { formatClockUk, formatRunStarted, runDurationText } from '../utils/runsView.js';
import { jobItem } from './runsFixtures.js';

/** 00:30 UK on 14 August (BST): New York and UTC are still on the 13th, the UK is on the 14th. */
const UK_SMALL_HOURS = new Date('2026-08-13T23:30:00Z');

describe('the zone fixture itself', () => {
  it('is pinned west of the UK, or neither defect below can show', () => {
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe('America/New_York');
  });

  it('sits on an instant where the device and the UK disagree about the date', () => {
    expect(UK_SMALL_HOURS.toLocaleDateString('en-CA')).toBe('2026-08-13');
  });
});

describe('formatRunStarted, from a device four hours west of the UK', () => {
  it('calls a run started after UK midnight "Today" once the UK day has rolled', () => {
    // 00:10 UK on the 14th is 23:10Z on the 13th: the device's date and the UTC date are both the 13th.
    expect(formatRunStarted('2026-08-13T23:10:00Z', UK_SMALL_HOURS)).toBe('Today 00:10');
  });

  it('calls a run from the previous UK evening "Yesterday" once the UK day has rolled', () => {
    // 22:00 UK on the 13th. The same boundary, from the other side: a "count everything" bug would pass the test above.
    expect(formatRunStarted('2026-08-13T21:00:00Z', UK_SMALL_HOURS)).toBe('Yesterday 22:00');
  });

  it('reads a bare instant as UTC, not as the device\'s own time', () => {
    // `new Date('2026-08-13T21:00:00')` on this zone would be 01:00Z on the 14th, four hours out.
    expect(formatRunStarted('2026-08-13T21:00:00', UK_SMALL_HOURS)).toBe('Yesterday 22:00');
    expect(formatClockUk('2026-08-13T21:00:00')).toBe('22:00');
  });

  it('measures elapsed time from the real instant, not from a mis-parsed one', () => {
    const item = jobItem({ status: 'RUNNING', startedAt: '2026-08-13T21:00:00', durationSeconds: null });
    // 21:00Z to 23:30Z is exactly 2h 30m, whatever zone the device is in.
    expect(runDurationText(item, UK_SMALL_HOURS)).toBe('2h 30m so far');
  });
});
