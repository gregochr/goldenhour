/**
 * The two date questions the map asks, and the one place each is answered.
 *
 * <p>They are genuinely different questions, and conflating them is what this module exists to
 * stop:
 *
 * <ul>
 *   <li><b>What day is it?</b> — a calendar question. {@link localDateStr}.</li>
 *   <li><b>Which night are we in?</b> — <em>not</em> a calendar question, and no timezone answers
 *       it. {@link resolveAuroraNight}.</li>
 * </ul>
 */

/**
 * Today, as the browser's own local date in {@code YYYY-MM-DD}.
 *
 * <p><b>Local, not UTC, and the difference is a real defect rather than a tidy-up.</b> The map's
 * date surface used to derive "today" two ways: {@code toISOString().slice(0, 10)} (UTC) in
 * {@code App.jsx} and {@code DateStrip.jsx}, and {@code toLocaleDateString('en-CA')} (local) in
 * {@code MapView}'s event-type and viewline logic and in {@code computeAutoSelection}. Under BST
 * those disagree from 23:00–00:00 UTC — that is 00:00–01:00 UK, the hour straight after UK
 * midnight — so the strip labelled yesterday's chip "Today" while the auto-selection had already
 * moved on. Measured, not assumed: at {@code 2026-08-13T23:30:00Z} in {@code Europe/London},
 * {@code toISOString} gives {@code 2026-08-13} and {@code toLocaleDateString('en-CA')} gives
 * {@code 2026-08-14}.
 *
 * <p>Local is the right basis because this is a UK app whose users are in the UK: it matches the
 * backend's {@code Europe/London} civil date ({@code ForecastHorizon.today}), which is what every
 * forecast date on the wire is keyed to. UTC agreed with it only in GMT, i.e. by accident for half
 * the year. {@code en-CA} is used purely because its locale format is ISO {@code YYYY-MM-DD}.
 *
 * @param {Date} [now] - the instant to read; injectable so tests can pin it
 * @returns {string} the local calendar date as YYYY-MM-DD
 */
export function localDateStr(now = new Date()) {
  return now.toLocaleDateString('en-CA');
}

/**
 * The local date {@code days} days from {@code now}, as YYYY-MM-DD.
 *
 * <p>Steps the date through local calendar fields rather than adding 24h of milliseconds, so it
 * stays correct across a DST boundary — the day the clocks change is 23 or 25 hours long.
 *
 * @param {number} days - offset in days; may be negative
 * @param {Date} [now]  - the instant to read; injectable so tests can pin it
 * @returns {string} the offset local calendar date as YYYY-MM-DD
 */
export function localDateStrOffset(days, now = new Date()) {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + days);
  return localDateStr(d);
}

/**
 * The date naming the dark window we are in — the night aurora results are stored under.
 *
 * <p><b>This is not today's date, and no choice of timezone would make it so.</b> A night runs from
 * dusk on {@code D} to dawn on {@code D+1}, so between midnight and dawn you are standing inside
 * the window named <em>yesterday</em>. Run an aurora forecast at 02:00 and the backend correctly
 * calls that night "Tonight" and stores its results under {@code D−1}; a map that defaulted to the
 * calendar date opened on {@code D} and showed nothing.
 *
 * <p>So the answer comes from the backend, which owns the rule
 * ({@code AuroraForecastRunService.currentNightDate()}, an instant test against nautical dawn) and
 * carries it on {@code GET /api/aurora/status}. It is deliberately <em>not</em> re-derived here:
 * duplicating solar geometry in the browser is how the two halves drift apart, and this rule
 * already has one home.
 *
 * <p>Falls back to the local calendar date when the field is absent — a LITE user (status is null),
 * a failed fetch, or a backend deployed before the field existed. That fallback is exactly the old
 * behaviour, so the degrade is "no worse than before" rather than a guess.
 *
 * @param {object|null} auroraStatus - the shared aurora status payload, or null
 * @param {Date} [now]               - the instant behind the fallback; injectable for tests
 * @returns {string} the current night's date as YYYY-MM-DD
 */
export function resolveAuroraNight(auroraStatus, now = new Date()) {
  return auroraStatus?.currentNightDate ?? localDateStr(now);
}
