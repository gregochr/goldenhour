import { dayLabelFor, eventWord } from './windowFirstCards.js';
import { shortDow } from './locationSheet.js';

/**
 * The quiet line under an Ask answer's summary that puts the SERVER'S figure beside the model's prose
 * (`docs/engineering/ask-window-context-plan.md` §2.4 "Carried, not quoted", §6 Q4 — W2).
 *
 * <p>An answer carries {@code windows}: the whole rating distribution ({@code AskSnapshot.Spread}) of
 * every solar window its prose may have quoted, as the server folded it over the question's scope. The
 * prompt lets the model say "of 180 rated places, 2 are 4★"; nothing fact-checks that sentence, so what
 * keeps a quoted count honest is that the reader can see the served count beside it — and that the
 * freshness check (W1) withdraws an answer whose figures have moved. This module turns each carried
 * window into one line of words, {@code Sat sunset · 180 rated across every region · 2 at 4★+}, and
 * nothing else: every number is read off the served record, none is derived, nothing is summed across
 * windows, and no threshold is read. It is filter/map/select over served facts (CLAUDE.md's
 * Backend-heavy bullet), not a new class of client derivation.
 *
 * <p>⚠️ <b>This is not the Plan card's five-bar histogram.</b> That one is gated on the reader's own
 * reach pool, so two histograms with two pools on one screen would read as a contradiction; a line that
 * names its own population ("across every region", "in Northumberland") does not.
 *
 * <p>⚠️ <b>"at 4★+", never "game on"</b>: the owner's word is a criterion in the prompt, not served
 * copy.
 *
 * <p>Rules, each pinned by a test:
 * <ul>
 *   <li>{@code windows} absent or empty (every answer stored before W1, and every can't-answer): no
 *       lines at all.</li>
 *   <li>While any carried window is rated, a window with {@code rated} 0 draws no line (the owner's
 *       rule for W2: the line exists to stand beside a count the prose may quote, and a window with
 *       nothing to count beside a window with something is better silent than a second claim).</li>
 *   <li>When NO carried window is rated, EVERY drawable window says so in its own line — "Sat sunset
 *       · nothing rated across every region" — and names what the server recorded for it: {@code N
 *       regions stood down} (a weather decision) and {@code N not scored} (no record), read verbatim
 *       off {@code spread.regions}. A stood-down window and an unscored one must not read alike:
 *       the server keeps the two apart (`CoverageReason`, the honesty filter) and the owner's
 *       "all blue: stop" weekend is the stood-down case. Nothing is folded across windows.</li>
 *   <li>{@code go} (5★) is named only when it is above zero.</li>
 *   <li>A window id that is not a solar one ({@code yyyy-MM-dd_sunrise} / {@code _sunset}) cannot be
 *       named honestly and draws nothing; the server never carries one (a night has no spread), so
 *       this is fail-soft, not a feature.</li>
 * </ul>
 *
 * <p>Each line is two strings: {@code text} to draw ("Sat", the glyph) and {@code spoken} for the
 * screen reader ("Saturday", "4 stars or more"), built together so they cannot disagree — the long
 * weekday is the rule for every spoken string the app builds ({@code pickLabel}, the Plan card's
 * sentence), and "Sat"/"Sun" are voiced as words.
 */

/** The server's name for the whole-catalogue population ({@code AskSnapshot.Spread.EVERY_REGION}). */
export const EVERY_REGION = 'every region';

/** The lowest star the line calls out, the server's {@code Spread.GAME_ON_FROM}: a twin constant, copy only. */
const GAME_ON_FROM = 4;

const SOLAR_WINDOW_ID = /^(\d{4}-\d{2}-\d{2})_(sunrise|sunset)$/;

/**
 * A solar window id's parts: {@code {date, targetType}}, or null for an id that is not one (a night's
 * {@code _astro}/{@code _aurora}, or anything malformed).
 *
 * @param {*} windowId
 * @returns {?{date: string, targetType: 'SUNRISE'|'SUNSET'}}
 */
export function parseSolarWindowId(windowId) {
  const m = typeof windowId === 'string' ? SOLAR_WINDOW_ID.exec(windowId) : null;
  return m ? { date: m[1], targetType: m[2].toUpperCase() } : null;
}

/** A non-negative whole number read off the served record, else null. */
function count(value) {
  return Number.isInteger(value) && value >= 0 ? value : null;
}

/**
 * The population's words: "across every region" for the whole catalogue, "in {names}" for a scope
 * (the server has already joined the names: "Lake District and Northumberland").
 *
 * @param {*} over the served {@code over}
 * @returns {?string} null when the server named no population
 */
export function populationPhrase(over) {
  if (typeof over !== 'string' || over.trim() === '') return null;
  return over === EVERY_REGION ? `across ${over}` : `in ${over}`;
}

/** "4 stars or more" / "5 stars" — the spoken form of a star band. */
function starsSpoken(stars, orMore) {
  return `${stars} stars${orMore ? ' or more' : ''}`;
}

/** "1 region" / "4 regions". */
function regionsWord(n) {
  return `${n} ${n === 1 ? 'region' : 'regions'}`;
}

/** The full weekday ("Saturday") — {@code dayLabelFor} told no today or tomorrow, as {@code pickLabel} does. */
function longDow(dateStr) {
  return dayLabelFor(dateStr, null, null);
}

/**
 * One carried window as a line: what to draw and what to say.
 *
 * @param {{windowId: *, spread: *}} entry a served {@code AskAnswer.windows} element
 * @returns {?{windowId: string, rated: number, text: string, spoken: string, unratedText: string,
 *          unratedSpoken: string}} the rated line and the unrated one for this window (the caller
 *          chooses, over every window, which kind the answer draws); null when the entry cannot be
 *          drawn (not a solar window, or a spread with no readable {@code rated})
 */
function lineOf(entry) {
  const parsed = parseSolarWindowId(entry?.windowId);
  const spread = entry?.spread;
  if (!parsed || spread === null || typeof spread !== 'object') return null;
  const rated = count(spread.rated);
  if (rated === null) return null;
  const event = eventWord(parsed.targetType);
  const windowName = `${shortDow(parsed.date)} ${event}`;
  const windowSpoken = `${longDow(parsed.date)} ${event}`;
  const population = populationPhrase(spread.over);
  const withPopulation = (words) => (population ? `${words} ${population}` : words);
  const gameOn = count(spread.gameOn) ?? 0;
  const go = count(spread.go) ?? 0;
  const text = [
    windowName,
    withPopulation(`${rated} rated`),
    gameOn > 0 ? `${gameOn} at ${GAME_ON_FROM}★+` : `none at ${GAME_ON_FROM}★+`,
    ...(go > 0 ? [`${go} at 5★`] : []),
  ].join(' · ');
  const spoken = [
    `${windowSpoken}: ${withPopulation(`${rated} rated`)}`,
    gameOn > 0 ? `${gameOn} at ${starsSpoken(GAME_ON_FROM, true)}` : `none at ${starsSpoken(GAME_ON_FROM, true)}`,
    ...(go > 0 ? [`${go} at ${starsSpoken(5, false)}`] : []),
  ].join(', ');
  // The unrated line: why there is nothing to count, as the server recorded it for THIS window.
  const regions = spread.regions !== null && typeof spread.regions === 'object' ? spread.regions : {};
  const stoodDown = count(regions.stoodDown) ?? 0;
  const notScored = count(regions.notScored) ?? 0;
  const reasons = [
    ...(stoodDown > 0 ? [`${regionsWord(stoodDown)} stood down`] : []),
    ...(notScored > 0 ? [`${notScored} not scored`] : []),
  ];
  const unratedText = [windowName, withPopulation('nothing rated'), ...reasons].join(' · ');
  const unratedSpoken = [`${windowSpoken}: ${withPopulation('nothing rated')}`, ...reasons].join(', ');
  return {
    windowId: entry.windowId, rated, text, spoken, unratedText, unratedSpoken,
  };
}

/**
 * The lines to draw under an answer's summary, in the order the server carried the windows.
 *
 * @param {*} windows the answer's {@code windows}, as {@code normaliseAnswer} holds them
 * @returns {Array<{windowId: string, text: string, spoken: string}>} empty when there is nothing to
 *          say; the rated windows' lines while any is rated; else every drawable window's
 *          "nothing rated" line
 */
export function spreadLines(windows) {
  if (!Array.isArray(windows) || windows.length === 0) return [];
  const drawable = windows.map(lineOf).filter((line) => line !== null);
  const rated = drawable.filter((line) => line.rated > 0);
  if (rated.length > 0) {
    return rated.map(({ windowId, text, spoken }) => ({ windowId, text, spoken }));
  }
  return drawable.map(({ windowId, unratedText, unratedSpoken }) => ({
    windowId, text: unratedText, spoken: unratedSpoken,
  }));
}
