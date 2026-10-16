/**
 * Every string the rating block draws (`OutcomeRatingBlock.jsx`), in one place — the
 * `WindowControl.PICK_TEXT` shape — so the copy can be read, and tested, without the component.
 *
 * <h2>Spec strings</h2>
 * <p>From `docs/design/outcome-rating/README.md` §3, verbatim. The design's neutrality rules bind
 * all of them: never say the forecast was right or wrong, no comparison between forecast and
 * observed, no effusive thanks.
 *
 * <h2>Plan-introduced strings</h2>
 * <p>None among the rating block's own strings. The plan's four (`docs/engineering/outcome-rating-plan.md`
 * §4 #17 — the admin footer's no-run and minutes variants, the `‹` stepper's `Load {Dow} {d}` name, the
 * `Loading …` state) are split: the window control's (O3b) live in `WindowControl`; the admin list's
 * (O5a) sit BELOW a header of their own at the end of this module, with the list's own two additions
 * (the failed-fetch line and its loading line), so the spec's strings are never read as anything else.
 * The accessible names built here (`starLabel`, `spokenRating`) are the spec's own aria-label and its
 * "visually-hidden `3 out of 5`".
 */

import { formatDayClockUk, formatInstantUk, parseUtcInstant } from '../../utils/conversions.js';
import { ukDateStr } from '../../utils/mapDates.js';

// ── The spec's strings ──────────────────────────────────────────────────────────────────────────

export const QUESTION = 'How was it?';
export const NOT_SEEN_BUTTON = 'Didn\'t see it';
export const CHANGE = 'Change';
export const NOT_SEEN_DONE = 'You marked this not seen';
export const RATED_PREFIX = 'You rated';
export const SAVE_FAILED = 'Not saved. Tap a star again.';
export const LITE_PROMPT = 'Rate how the sky turned out.';
export const LITE_LOCKED = 'Rating is part of Pro.';
export const REPLACE_NOT_SEEN = 'Tap to replace "not seen"';

/**
 * The right-hand meta of the unrated block: `Sunset was today 18:10` / `Sunrise was yesterday 06:55`
 * / `Sunset was Sat 11, 18:17`. The clock time is the window's own.
 *
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @param {?string} dayLabel the window's day label — `Today`, `Yesterday` or `Dow d`
 * @param {?string} time the window's clock time, `HH:MM`
 * @returns {string}
 */
export function unratedMeta(eventType, dayLabel, time) {
  const kind = eventType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
  const day = dayLabel ?? '';
  const named = day === 'Today' || day === 'Yesterday';
  let when;
  if (named) when = `${day.toLowerCase()}${time ? ` ${time}` : ''}`;
  else when = `${day}${day && time ? ', ' : ''}${time ?? ''}`;
  return when ? `${kind} was ${when}` : kind;
}

/**
 * `Tap to replace your 3★` — the meta while changing an existing star rating.
 *
 * @param {number} rating
 * @returns {string}
 */
export function replaceMeta(rating) {
  return `Tap to replace your ${rating}★`;
}

/**
 * The star button's accessible name: `Rate the sunset 4 out of 5`.
 *
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @param {number} n
 * @returns {string}
 */
export function starLabel(eventType, n) {
  return `Rate the ${eventType === 'SUNRISE' ? 'sunrise' : 'sunset'} ${n} out of 5`;
}

/**
 * The rated row's numeral, `3★`.
 *
 * @param {number} n
 * @returns {string}
 */
export function ratedNumeral(n) {
  return `${n}★`;
}

/**
 * The visually-hidden reading of a rating, `3 out of 5` — the one spoken form of the numeral.
 *
 * @param {number} n
 * @returns {string}
 */
export function spokenRating(n) {
  return `${n} out of 5`;
}

// ── The admin list: the spec's strings (README §3, the Admin row) ───────────────────────────────

export const ADMIN_EMPTY = 'No ratings yet.';
export const ADMIN_NOT_SEEN = 'Not seen';

/**
 * The collapsible box's header, `All ratings · 2`. The count is every row the server sent, the
 * caller's own included. Before the list has been fetched (it is fetched on the first expand) there is
 * no count to state, and the header says only `All ratings`.
 *
 * @param {?number} count rows in the list, or null while none has been fetched
 * @returns {string}
 */
export function adminHeader(count) {
  return count == null ? 'All ratings' : `All ratings · ${count}`;
}

/**
 * When a rating was last recorded: `HH:MM` when that is today on the UK clock, else `{Dow} HH:MM`
 * (`Sun 19:30`). "Today" is the UK civil date of the instant against the UK civil date now, never the
 * browser's, so a rating made at 23:30 UTC in BST (00:30 the next UK day) reads as that UK day's.
 *
 * @param {?string} updatedAt a backend instant
 * @param {Date} [now] injectable for tests; defaults to the app clock
 * @returns {string} empty when the instant is missing or unreadable
 */
export function adminStamp(updatedAt, now = undefined) {
  const at = parseUtcInstant(updatedAt);
  if (!at) return '';
  const clock = formatInstantUk(at, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  if (ukDateStr(at) === (now ? ukDateStr(now) : ukDateStr())) return clock;
  return `${formatInstantUk(at, { weekday: 'short' })} ${clock}`;
}

/**
 * The list's footer: the forecast run the ratings were recorded against and how long before the event
 * it was made, `Rated against the run at Sat 17 Oct, 16:05 · 2h before sunset`.
 *
 * <p>The gap is whole minutes first, then: an hour or more reads as whole hours rounded to the nearest
 * hour (59 min → `59 min`, 60 → `1h`, 89 → `1h`, 90 → `2h`); under an hour, minutes. A gap under a
 * minute, or an event time that is missing, states the run alone — "0 min before" would be a claim the
 * clock cannot back.
 *
 * @param {?string} forecastRunAt the run's instant, or null when none was recorded
 * @param {?string} eventTime the event's instant
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @returns {string}
 */
export function adminFooter(forecastRunAt, eventTime, eventType) {
  const kind = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const run = parseUtcInstant(forecastRunAt);
  if (!run) return adminNoRun(eventType);
  const when = formatDayClockUk(run);
  const event = parseUtcInstant(eventTime);
  const minutes = event ? Math.round((event.getTime() - run.getTime()) / 60000) : 0;
  // Plan-introduced (§4 #17): the tail-less wording for a gap under a minute or no event time, and the
  // `{n} min` variant below an hour — the spec states only the `{X}h` form.
  if (minutes < 1) return `Rated against the run at ${when}`;
  const span = minutes >= 60 ? `${Math.round(minutes / 60)}h` : `${minutes} min`;
  return `Rated against the run at ${when} · ${span} before ${kind}`;
}

// ── The admin list: strings this PLAN introduced (§4 #17), not the spec's ───────────────────────

/**
 * Plan-introduced: the footer when no forecast run was recorded before the event.
 *
 * @param {'SUNRISE'|'SUNSET'} eventType
 * @returns {string}
 */
export function adminNoRun(eventType) {
  return `No forecast run recorded before this ${eventType === 'SUNRISE' ? 'sunrise' : 'sunset'}`;
}

/** Plan-introduced: the quiet line when the list could not be fetched; the box stays openable. */
export const ADMIN_LOAD_FAILED = 'Couldn\'t load ratings.';

/** Plan-introduced: the line while the first fetch is out. */
export const ADMIN_LOADING = 'Loading …';
