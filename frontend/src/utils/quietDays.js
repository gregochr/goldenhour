/**
 * Quiet days (docs/engineering/screen-spec.md D5): a day on which nothing is worth a trip folds to one line,
 * `Wed 25 · nothing above 2★ ▸`, and opens in place.
 *
 * <p>A licensed client derivation (architecture-decisions.md "Backend-heavy"): a threshold over served ratings the
 * reader can already see on the cards, and only a presentation fold, never a verdict, a pick or a re-rank. It
 * changes no rating, and it hides nothing silently: the folded line counts the day's topic lanes, a warning lane or a
 * watched date keeps the day open, and an unscored window never counts as quiet.
 *
 * <p>{@code card.bestRating} is the figure the card prints, a whole star. A null never folds, which is why a Lite
 * reader (whose windows all serve a null best) never sees a fold, and why an away window, a not-yet-scored window and
 * a day with no card at all stay open.
 */

/** No quiet day opened yet: one stable empty set, shared by the shell's state and the strip's default prop. */
export const NO_QUIET_OPEN = new Set();

/** The highest rating a quiet day may reach: "nothing above 2★". */
export const QUIET_AT_MOST = 2;

/**
 * Whether a lane touches a date.
 *
 * @param {{dates?: Array<string>}} lane a lane
 * @param {string} date an ISO date
 * @returns {boolean} true when the lane sits on that day
 */
function onDay(lane, date) {
  return Array.isArray(lane?.dates) && lane.dates.includes(date);
}

/**
 * Whether a matrix day folds.
 *
 * @param {{date: string, am: ?object, pm: ?object, amEmpty: ?string, pmEmpty: ?string}} day the matrix day
 *        (`buildWindowMatrix`); a missing window with no `amEmpty`/`pmEmpty` reason keeps the day open
 * @param {{warningLanes?: Array<object>, watchedLanes?: Array<object>, topicLanes?: Array<object>}} [lanes]
 *        the strip's lanes: warnings, watched dates drawn as a lane of their own, and the topic lanes (a topic lane
 *        that a watched date decorates carries {@code watched})
 * @returns {boolean} true when every window the day shows is served and at most 2★, and no warning or watched lane
 *          sits on it
 */
export function isQuietDay(day, lanes = {}) {
  const windows = [day?.am, day?.pm].filter(Boolean);
  if (windows.length === 0) return false;
  // A missing window counts only when the matrix EXPLAINS it (this morning has gone, past the end of the forecast):
  // an unexplained hole is a window nobody can account for, so the day stays open.
  if ((!day.am && !day.amEmpty) || (!day.pm && !day.pmEmpty)) return false;
  const allQuiet = windows.every((card) => typeof card.bestRating === 'number' && card.bestRating <= QUIET_AT_MOST);
  if (!allQuiet) return false;
  const { warningLanes = [], watchedLanes = [], topicLanes = [] } = lanes;
  if (warningLanes.some((lane) => onDay(lane, day.date))) return false;
  if (watchedLanes.some((lane) => onDay(lane, day.date))) return false;
  return !topicLanes.some((lane) => lane.watched && onDay(lane, day.date));
}

/**
 * How many topic lanes sit on a day: the count a folded phone line carries, so nothing is hidden silently.
 *
 * @param {string} date an ISO date
 * @param {Array<object>} topicLanes the topic lanes
 * @returns {number} the lanes on that day
 */
export function topicLaneCount(date, topicLanes) {
  return (topicLanes || []).filter((lane) => onDay(lane, date)).length;
}
