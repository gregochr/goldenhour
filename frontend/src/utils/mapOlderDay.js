import { shiftDateStr } from './mapDates.js';

/**
 * The Map tab's older-day fetch, as the pane decides it (outcome-rating-plan.md §2 #2 "Older-day
 * fetch", §3 O3b): whether the window control may offer to load one more stored past day, and which
 * day that is.
 *
 * <p>Pure functions over the dates the pane already holds — the already-licensed filter/map/select
 * class of CLAUDE.md's Backend-heavy bullet (a lookup of the day before the oldest known date and a
 * comparison against a fixed floor); nothing here reads a rating, a clock or a server rule.
 */

/**
 * How far back the Map tab may reach: today minus four days, the oldest day {@code GET
 * /api/forecast?date=} admits. ⚠️ A client constant that MIRRORS the backend's
 * {@code ForecastHorizon.MAX_PAST_DAYS} (4); the endpoint answers 400 beyond it, so the two move
 * together. The list itself already carries the nearer days ({@code SERVE_PAST_DAYS}, 2), which is
 * why the client never asks for a day it holds.
 */
export const MAX_PAST_DAYS = 4;

/**
 * Whether one more past day can be loaded, and which.
 *
 * <p>The next older date is the day BEFORE the oldest date the pane knows — the forecast list's
 * dates unioned with the days already fetched, a fetched day that came back empty included (it still
 * counts toward the floor, so an empty T-3 offers T-4 and never T-3 again). Nothing older than
 * {@code todayStr - MAX_PAST_DAYS} is ever offered, and nothing at all while the clock is rewound
 * (an admin's rewind stands the older-day fetch down; a rewound GET would be answered for the
 * rewound clock) or while no date is known to step back from.
 *
 * @param {object} args
 * @param {string[]} args.dates the forecast list's colour dates (`App.allDates`)
 * @param {Iterable<string>} [args.loadedPastDays] the older days already fetched, empty ones too
 * @param {string} args.todayStr the UK civil today as the pane reads it
 * @param {boolean} [args.rewound] whether an admin rewind is in force
 * @returns {{canLoadOlder: boolean, nextOlderDate: ?string}}
 */
export function olderDayState({ dates, loadedPastDays = [], todayStr, rewound = false }) {
  const none = { canLoadOlder: false, nextOlderDate: null };
  if (rewound || !todayStr) return none;
  let oldest = null;
  for (const d of [...(dates ?? []), ...loadedPastDays]) {
    if (d && (oldest === null || d < oldest)) oldest = d;
  }
  if (oldest === null) return none;
  // The endpoint admits only T-1 and older: a forecast that starts after today (the list's past days
  // all absent) steps back from today, never to it.
  if (oldest > todayStr) oldest = todayStr;
  const next = shiftDateStr(oldest, -1);
  if (next < shiftDateStr(todayStr, -MAX_PAST_DAYS)) return none;
  return { canLoadOlder: true, nextOlderDate: next };
}

/**
 * The older day the window control should say has no stored forecast, or null.
 *
 * <p>Only the MOST RECENTLY loaded day speaks (outcome-rating-plan.md §4 #15, amended 2026-10-17):
 * the Load row steps from the newest older day to the oldest, so that is the oldest loaded date. When
 * it came back with no rows the menus name it in the Load row's place, and the line stays at the T-4
 * floor so a disabled `‹` is explained. A later day that loads WITH rows replaces it. A pure select
 * over the two lists the hook already exposes (the already-licensed filter/map/select class); it reads
 * no rating, no clock and no server rule.
 *
 * @param {object} args
 * @param {Iterable<string>} [args.loadedPastDays] the older days already fetched, empty ones too
 * @param {Iterable<string>} [args.emptyPastDays] the subset of those that returned no rows
 * @returns {?string} the date, or null
 */
export function emptyOlderDay({ loadedPastDays = [], emptyPastDays = [] }) {
  let oldest = null;
  for (const d of loadedPastDays) {
    if (d && (oldest === null || d < oldest)) oldest = d;
  }
  if (oldest === null) return null;
  for (const d of emptyPastDays) {
    if (d === oldest) return oldest;
  }
  return null;
}
