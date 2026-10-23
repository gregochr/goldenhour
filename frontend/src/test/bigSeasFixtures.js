/**
 * Shared fixtures for the big-seas tests (big-seas plan F1). Fixed dates, never the wall clock.
 *
 * <p>Saturday 24 Oct 2026 is still BST (UTC+1) and Sunday 25 Oct is GMT: the clocks go back at
 * 01:00 UTC on the 25th. Times are naive UTC strings like the served {@code BigSeasDay}, so the
 * Saturday window 14:30Z-17:15Z reads 15:30-18:15 on the UK clock and the Sunday window
 * 06:49Z-09:00Z reads 06:49-09:00.
 */

/** Saturday: aligned with the SUNSET (17:47 BST), high water 16:58 BST, 49 minutes before. */
export const SATURDAY = {
  date: '2026-10-24',
  windowStart: '2026-10-24T14:30:00',
  windowEnd: '2026-10-24T17:15:00',
  hwTime: '2026-10-24T15:58:00',
  alignedEvent: 'SUNSET',
  gapMinutes: 49,
  gapSide: 'BEFORE',
  waveHeightM: 4.1,
  waveDirectionDeg: 15,
  wavePeriodS: 11,
  structures: [
    { locationId: 243, label: 'Roker Pier', structure: 'PIER', waveHeightM: 4.3, waveDirectionDeg: 15, wavePeriodS: 11, facesDeg: 45, hwTime: '2026-10-24T15:58:00', aligned: true },
    { locationId: 28, label: "St Mary's", structure: 'LIGHTHOUSE', waveHeightM: 4.1, waveDirectionDeg: 15, wavePeriodS: 11, facesDeg: 90, hwTime: '2026-10-24T16:02:00', aligned: true },
    { locationId: 21, label: 'Amble piers', structure: 'PIER', waveHeightM: 3.9, waveDirectionDeg: 15, wavePeriodS: 10, facesDeg: 45, hwTime: '2026-10-24T15:40:00', aligned: true },
  ],
  otherWindows: 0,
};

/** Sunday: aligned with the SUNRISE, high water 42 minutes AFTER it, one structure. */
export const SUNDAY = {
  date: '2026-10-25',
  windowStart: '2026-10-25T06:49:00',
  windowEnd: '2026-10-25T09:00:00',
  hwTime: '2026-10-25T07:31:00',
  alignedEvent: 'SUNRISE',
  gapMinutes: 42,
  gapSide: 'AFTER',
  waveHeightM: 3.4,
  waveDirectionDeg: 40,
  wavePeriodS: 9,
  structures: [
    { locationId: 243, label: 'Roker Pier', structure: 'PIER', waveHeightM: 3.4, waveDirectionDeg: 40, wavePeriodS: 9, facesDeg: 45, hwTime: '2026-10-25T07:31:00', aligned: true },
  ],
  otherWindows: 0,
};

/** A day with big seas but no solar event inside the window: `alignedEvent` and the gap omitted. */
export const MONDAY_NOT_ALIGNED = {
  date: '2026-10-26',
  windowStart: '2026-10-26T10:00:00',
  windowEnd: '2026-10-26T12:30:00',
  hwTime: '2026-10-26T11:15:00',
  waveHeightM: 3.2,
  waveDirectionDeg: 350,
  wavePeriodS: 8.5,
  structures: SATURDAY.structures,
  otherWindows: 1,
};

/** One solar EV row, shaped like `buildMapEvents`' output for the fields the line reads. */
export function solarRow(date, eventType, extra = {}) {
  return {
    id: `solar:${date}:${eventType}`,
    kind: 'solar',
    eventType,
    date,
    label: date,
    dayLabel: date,
    time: eventType === 'SUNRISE' ? '07:00' : '17:45',
    bestRating: 3,
    scored: true,
    badges: [],
    ...extra,
  };
}

/** One night EV row (astro) on a date. */
export function astroRow(date, extra = {}) {
  return {
    id: `astro:${date}:ASTRO`,
    kind: 'astro',
    eventType: 'ASTRO',
    date,
    label: date,
    dayLabel: date,
    time: '20:00',
    bestRating: null,
    scored: false,
    badges: [],
    ...extra,
  };
}

/** A week of rows: Thu 22 to Mon 26, sunrise then sunset then (on the 24th) a night. */
export function weekEvents() {
  return [
    solarRow('2026-10-22', 'SUNRISE'),
    solarRow('2026-10-22', 'SUNSET'),
    solarRow('2026-10-23', 'SUNRISE'),
    solarRow('2026-10-23', 'SUNSET'),
    solarRow('2026-10-24', 'SUNRISE'),
    solarRow('2026-10-24', 'SUNSET'),
    astroRow('2026-10-24'),
    solarRow('2026-10-25', 'SUNRISE'),
    solarRow('2026-10-25', 'SUNSET'),
    solarRow('2026-10-26', 'SUNRISE'),
  ];
}

/** Thursday 22 Oct 2026, 08:00Z: before everything above. */
export const THURSDAY_MORNING = new Date('2026-10-22T08:00:00Z');
