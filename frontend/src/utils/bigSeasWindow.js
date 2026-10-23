import { parseUtcInstant } from './conversions.js';

/**
 * Whether a served big-seas day's window is still ahead of {@code now} (its {@code windowEnd} is
 * strictly after it). The ONE test the line ({@code bigSeasFor}) and the week menu's bolt
 * ({@code buildMapEvents}' stamp) share, so the two cannot disagree about a day that has gone. An
 * unparseable end reads as not ahead.
 *
 * @param {?{windowEnd: ?string}} day a served {@code BigSeasDay}
 * @param {Date} now the app clock
 * @returns {boolean}
 */
export function bigSeasWindowAhead(day, now) {
  const end = parseUtcInstant(day?.windowEnd);
  return end != null && end.getTime() > now.getTime();
}
