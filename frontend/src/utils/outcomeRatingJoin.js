import { rowKey } from '../hooks/useOutcomeRatings.js';

/**
 * The reader's own outcome ratings, joined onto the Map tab's past windows and spots
 * (`docs/engineering/outcome-rating-plan.md` §2 #6, O4b).
 *
 * <p>⚠️ <b>Two NAMED members of CLAUDE.md's Backend-heavy bullet's per-user-join class</b> — the
 * fourth, the Coming up badge's own ("what is new <em>to you</em>"): {@link ratedCountByWindow} (how
 * many of the reader's own records fall on a past window) and {@link myRatingOf} (the reader's own
 * record for one spot in the window on screen, which rides {@code labelSpots} as {@code
 * spot.myRating}). Both join records from a never-cached, per-user endpoint
 * ({@code GET /api/outcome/ratings}) onto a window and a spot, so neither has a servable answer on
 * the ETag-shared briefing payload — the identical reasoning as {@code comingUpArrivals}. Members:
 * those two, nothing else ({@link ratingsKnownFor}, {@link badgeText}, {@link badgeAccessibleClause}
 * and {@link NOT_SEEN} are helpers: a guard and lexical maps, the already-licensed filter/map class).
 * Neither derives a rating, a verdict or a server rule; both are counts and
 * look-ups over the reader's own rows.
 *
 * <p>⚠️ <b>A count or a badge states a fact, so both stay silent when the fact is unknown.</b> The
 * hook's {@code covers(date)} is true after a FAILED read with {@code mine} empty (the rating block
 * stays usable then) and, for a LITE reader, for every date after the first read; an empty map there
 * says nothing about what was rated. So every function here reads {@link ratingsKnownFor}: the
 * server was actually asked about the date ({@code wasRead}, range-based for every role) AND the
 * newest read did not fail. A just-landed older day (not yet covered), a failed read and a rewind (no hook at
 * all) therefore draw no count and no badge — never a false "Not rated".
 */

/** The value {@link myRatingOf} returns for a record whose {@code rating} is null ("didn't see it"). */
export const NOT_SEEN = 'NOT_SEEN';

/**
 * Whether the reader's ratings for an event date have been read successfully.
 *
 * @param {?{wasRead: function(string): boolean, readFailed: ?boolean}} ratings the hook's return value
 * @param {string} date `YYYY-MM-DD`
 * @returns {boolean}
 */
export function ratingsKnownFor(ratings, date) {
  return Boolean(ratings) && !ratings.readFailed && ratings.wasRead(date) === true;
}

/**
 * The number of locations the reader has rated for each past window, "didn't see it" INCLUDED (the
 * prototype's {@code myCount} counts any record) — the Map of {@code WindowControl}'s
 * {@code ratedCounts}: EV row id to count.
 *
 * <p>A window whose date the reader's ratings are not {@link ratingsKnownFor known} is ABSENT from
 * the result (not zero), which is how the control knows to print nothing. A record for a place the
 * roster no longer holds (disabled, deleted) is not counted: the reader cannot find it on the map.
 *
 * @param {object} args
 * @param {?object} args.ratings `useOutcomeRatings`' return value, or null (rewound, no hook)
 * @param {Array<{date: string, targetType: string}>} args.windows the past windows to count, with
 *        today's two windows welcome (an extra id is harmless)
 * @param {Array<{id: ?(number|string)}>} args.locations the roster the records are joined to
 * @returns {?Map<string, number>} row id to count; null when there are no ratings at all
 */
export function ratedCountByWindow({ ratings, windows, locations }) {
  if (!ratings) return null;
  const roster = new Set(locations.filter((l) => l.id != null).map((l) => String(l.id)));
  const byWindow = new Map();
  for (const key of ratings.mine.keys()) {
    const [locationId, date, eventType] = key.split('|');
    if (!roster.has(locationId)) continue;
    const id = `solar:${date}:${eventType}`;
    byWindow.set(id, (byWindow.get(id) ?? 0) + 1);
  }
  const counts = new Map();
  for (const { date, targetType } of windows) {
    if (!ratingsKnownFor(ratings, date)) continue;
    const id = `solar:${date}:${targetType}`;
    counts.set(id, byWindow.get(id) ?? 0);
  }
  return counts;
}

/**
 * The reader's own record for one spot in one window: {@code 1..5}, {@link NOT_SEEN}, or null (no
 * record, or not {@link ratingsKnownFor known}). Callers pass it only for a PAST window — a served
 * one has no outcome to rate.
 *
 * @param {?object} ratings `useOutcomeRatings`' return value, or null
 * @param {?(number|string)} locationId
 * @param {string} date
 * @param {string} eventType `SUNRISE` | `SUNSET`
 * @returns {?(number|string)}
 */
export function myRatingOf(ratings, locationId, date, eventType) {
  if (locationId == null || !ratingsKnownFor(ratings, date)) return null;
  const record = ratings.mine.get(rowKey(locationId, date, eventType));
  if (!record) return null;
  if (record.rating == null) return NOT_SEEN;
  return Number.isInteger(record.rating) && record.rating >= 1 && record.rating <= 5
    ? record.rating
    : null;
}

/**
 * The badge's printed text: {@code 3★} or {@code –} for "not seen".
 *
 * @param {number|string} myRating a value {@link myRatingOf} returned
 * @returns {string}
 */
export function badgeText(myRating) {
  return myRating === NOT_SEEN ? '–' : `${myRating}★`;
}

/**
 * The clause appended to a pin's or chip's accessible name (an {@code aria-label} REPLACES the
 * content, and the badge itself is {@code aria-hidden}), with its leading comma, or '' for none.
 *
 * @param {?(number|string)} myRating
 * @returns {string}
 */
export function badgeAccessibleClause(myRating) {
  if (myRating == null) return '';
  return myRating === NOT_SEEN
    ? ', you marked this not seen'
    : `, you rated ${myRating} out of 5`;
}
