/**
 * The two date questions the map asks, and the one place each is answered.
 *
 * <p>They are genuinely different questions, and conflating them is what this module exists to
 * stop:
 *
 * <ul>
 *   <li><b>What day is it?</b> — a calendar question, answered on the <b>UK</b> calendar.
 *       {@link ukDateStr}.</li>
 *   <li><b>Which night are we in?</b> — <em>not</em> a calendar question, and no timezone answers
 *       it. {@link resolveAuroraNight}.</li>
 * </ul>
 */

/** The one calendar this app has. Every forecast date on the wire is keyed to it. */
const UK_ZONE = 'Europe/London';

/**
 * Built once. {@code en-CA} purely because its locale format is ISO {@code YYYY-MM-DD}; the
 * {@code timeZone} is what does the work.
 */
const UK_DATE_FORMAT = new Intl.DateTimeFormat('en-CA', { timeZone: UK_ZONE });

/** Milliseconds in a day. Only ever applied to UTC-noon anchors, which have no DST. */
const DAY_MS = 86400000;

/**
 * Anchors a {@code YYYY-MM-DD} string at 12:00 UTC.
 *
 * <p>Noon rather than midnight is <b>defensive, not load-bearing</b>. Every read-back here is
 * {@code getUTC*}, so no local field is ever consulted and midnight would give identical answers;
 * the margin exists so that a later edit reaching for {@code getFullYear()} instead cannot silently
 * shift a date by a day. Arithmetic between two of these anchors is exact whole days regardless.
 *
 * @param {string} dateStr - ISO date, YYYY-MM-DD
 * @param {number} [days]  - whole days to add; may be negative
 * @returns {number} epoch milliseconds at 12:00 UTC on the resulting date
 */
function utcNoon(dateStr, days = 0) {
  const [year, month, day] = dateStr.split('-').map(Number);
  return Date.UTC(year, month - 1, day + days, 12);
}

/** Formats a UTC-noon anchor back to {@code YYYY-MM-DD}. */
function fromUtcNoon(ms) {
  const d = new Date(ms);
  const month = String(d.getUTCMonth() + 1).padStart(2, '0');
  const day = String(d.getUTCDate()).padStart(2, '0');
  return `${d.getUTCFullYear()}-${month}-${day}`;
}

/**
 * Today, on the UK calendar, as {@code YYYY-MM-DD}.
 *
 * <p><b>{@code Europe/London}, not the browser's zone and not UTC.</b> This app's data is UK data:
 * every forecast date on the wire is keyed to the backend's {@code ForecastHorizon.today}, which is
 * the {@code Europe/London} civil date. So "what day is it" has one correct answer here, and it is
 * not a property of where the reader's device happens to be.
 *
 * <p>The map has now been wrong in both of the other two ways, which is why this is stated at
 * length:
 *
 * <ul>
 *   <li><b>UTC</b> (the original {@code toISOString().slice(0, 10)} in {@code App.jsx} and
 *       {@code DateStrip.jsx}) is right only in GMT. Under BST it is a day behind from 23:00–00:00
 *       UTC — the hour after UK midnight — so the strip labelled <em>yesterday's</em> chip "Today".
 *       Measured: at {@code 2026-08-13T23:30:00Z} it gives {@code 2026-08-13} where the UK is
 *       already on the 14th.</li>
 *   <li><b>The browser's zone</b> (what replaced it, and what {@code computeAutoSelection} and
 *       {@code getNextEventType} had always used) is right only while the device is in the UK. On
 *       {@code America/New_York} a UK evening reads as the previous day <em>all day</em>, not for
 *       an hour — an unbounded error where UTC's was capped at one hour. That is the UK-user-abroad
 *       case, and a device left on the wrong zone.</li>
 * </ul>
 *
 * <p>Naming the zone outright is the only form with no such caveat, and it is what this codebase
 * already does everywhere it formats a UK <em>time</em> ({@code conversions.js}).
 *
 * <p>⚠️ <b>The hybrid form must not come back.</b> {@code DailyBriefing} and
 * {@code WindowFirstBriefingContext} resolve the backend's "today"/"tomorrow" tokens and feed the
 * result to {@code setSelectedDate}; both used to carry a private {@code londonDate(offset)} that
 * stepped the <em>browser's</em> calendar ({@code d.setDate(d.getDate() + offset)}) and only then
 * formatted in London. Formatting in the right zone is not enough — the day step has to happen on
 * the same calendar as the format, or the two disagree across a DST boundary. Measured with
 * {@code TZ=UTC}: at {@code 2026-10-24T23:30:00Z} it returned {@code 2026-10-25} for <em>both</em>
 * today and tomorrow, so nothing was labelled "Tomorrow" and "Best Bet — tomorrow's sunset" opened
 * the map on today; at {@code 2026-03-28T23:30:00Z} it skipped the 28th to the 30th. Under
 * {@code TZ=Europe/London} both came out right, which is why no test caught it for as long as the
 * suite was UK-pinned. Both now call {@link ukDateStr} / {@link ukDateStrOffset}, which step the UK
 * date itself.
 *
 * @param {Date} [now] - the instant to read; injectable so tests can pin it
 * @returns {string} the UK calendar date as YYYY-MM-DD
 */
export function ukDateStr(now = new Date()) {
  return UK_DATE_FORMAT.format(now);
}

/**
 * The UK date {@code days} days from {@code now}, as {@code YYYY-MM-DD}.
 *
 * <p>Steps the UK <em>date string</em> rather than the instant, so it cannot be dragged onto the
 * wrong day by the reader's zone, and cannot lose or gain a day across a DST boundary — the day
 * the clocks change is 23 or 25 hours long, so adding 24 h of milliseconds to an instant is not a
 * day.
 *
 * @param {number} days - offset in days; may be negative
 * @param {Date} [now]  - the instant to read; injectable so tests can pin it
 * @returns {string} the offset UK calendar date as YYYY-MM-DD
 */
export function ukDateStrOffset(days, now = new Date()) {
  return fromUtcNoon(utcNoon(ukDateStr(now), days));
}

/**
 * Whole days from today (UK) to {@code dateStr} — negative for past dates.
 *
 * <p>Exists so that every "Today"/"Tomorrow" label in the app answers to the same calendar as the
 * date strip it sits beside. {@code formatDateLabel} and {@code HotTopicStrip}'s
 * {@code leadDayWord} both derived this from browser-local calendar fields, which is how a card
 * could read "Tomorrow" over a date the strip was calling "Today".
 *
 * @param {string} dateStr - ISO date, YYYY-MM-DD
 * @param {Date} [now]     - the instant "today" is read from; injectable for tests
 * @returns {number} whole days ahead (0 = today, 1 = tomorrow, -1 = yesterday)
 */
export function ukDayOffset(dateStr, now = new Date()) {
  return Math.round((utcNoon(dateStr) - utcNoon(ukDateStr(now))) / DAY_MS);
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
 * <p>Falls back to the UK calendar date when the field is absent — a LITE user (status is null), a
 * failed fetch, or a backend deployed before the field existed. A calendar date is the wrong answer
 * for a night, but it is the same wrong answer the map gave before the field existed, so the
 * degrade is "no worse than before" rather than a guess.
 *
 * @param {object|null} auroraStatus - the shared aurora status payload, or null
 * @param {Date} [now]               - the instant behind the fallback; injectable for tests
 * @returns {string} the current night's date as YYYY-MM-DD
 */
export function resolveAuroraNight(auroraStatus, now = new Date()) {
  return auroraStatus?.currentNightDate ?? ukDateStr(now);
}
