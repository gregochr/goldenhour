/**
 * The Ask entry prompt, by tab: the phone bar and the tablet and desktop field read it alike.
 *
 * <p>Plan's prompt follows the windows on the matrix: "this weekend" is offered only while the
 * matrix itself holds a Saturday or Sunday window that is not a travel day, mirroring the backend's
 * Ready catalogue split (BEST_WEEKEND vs BEST_SOON). Otherwise a reader on a Tuesday would be invited
 * to ask about a weekend the forecast does not reach. Coming up keeps its own fixed prompt. Any other
 * tab (the Map, where the tablet and desktop field is also drawn) takes the Plan rule.
 */

const WEEKEND_PROMPT = 'Ask about this weekend…';
const SOON_PROMPT = 'Ask about the next few days…';
const COMING_UP_PROMPT = 'Ask about rare events…';

/**
 * Whether an ISO date ('YYYY-MM-DD') falls on a Saturday or Sunday. Parsed as UTC so the weekday
 * never drifts with the reader's timezone.
 *
 * @param {string} iso the civil date
 * @returns {boolean}
 */
function isWeekendDate(iso) {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  if (!m) return false;
  const day = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))).getUTCDay();
  return day === 0 || day === 6;
}

/**
 * @param {string} tab the effective tab id
 * @param {Array<{date: string}>} [upcomingEvents] the matrix's event list
 * @param {Set<string>} [travelDayDates] dates the matrix does not offer
 * @returns {string} the bar's prompt
 */
export function askEntryPrompt(tab, upcomingEvents = [], travelDayDates = new Set()) {
  if (tab === 'coming-up') return COMING_UP_PROMPT;
  const weekend = (upcomingEvents ?? []).some(
    (e) => isWeekendDate(e?.date) && !travelDayDates?.has(e.date),
  );
  return weekend ? WEEKEND_PROMPT : SOON_PROMPT;
}
