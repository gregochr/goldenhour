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
 * <p>None in the block itself: the plan's four (`docs/engineering/outcome-rating-plan.md` §4 #17 —
 * the admin footer's no-run and minutes variants, the `‹` stepper's `Load {Dow} {d}` name, the
 * `Loading …` state) belong to the window control (O3b) and the admin list (O5a), which add them
 * BELOW a header of their own in this module, so the spec's strings above are never read as
 * anything else. The accessible names built here (`starLabel`, `spokenRating`) are the spec's own
 * aria-label and its "visually-hidden `3 out of 5`".
 */

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
