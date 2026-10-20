import { eventWord } from './windowFirstCards.js';
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
 * nothing else: every number is read off the served record, none is derived, and no threshold is
 * read. It is filter/map/select over served facts (CLAUDE.md's Backend-heavy bullet), not a new class
 * of client derivation.
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
 *   <li>A window with {@code rated} 0 draws no line; when the answer carries windows and NONE of them
 *       is rated, one line says so ("Nothing rated across every region").</li>
 *   <li>{@code go} (5★) is named only when it is above zero.</li>
 *   <li>A window id that is not a solar one ({@code yyyy-MM-dd_sunrise} / {@code _sunset}) cannot be
 *       named honestly and draws nothing; the server never carries one (a night has no spread), so
 *       this is fail-soft, not a feature.</li>
 * </ul>
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

/** "4 stars or more" / "5 stars" — the spoken form of a star band, the pick card's own words. */
function starsSpoken(stars, orMore) {
  return `${stars} stars${orMore ? ' or more' : ''}`;
}

/**
 * One carried window as a line: what to draw and what to say.
 *
 * @param {{windowId: *, spread: *}} entry a served {@code AskAnswer.windows} element
 * @returns {?{windowId: string, text: string, spoken: string, rated: number}} null when the entry
 *          cannot be drawn (not a solar window, or a spread with no readable {@code rated})
 */
function lineOf(entry) {
  const parsed = parseSolarWindowId(entry?.windowId);
  const spread = entry?.spread;
  if (!parsed || spread === null || typeof spread !== 'object') return null;
  const rated = count(spread.rated);
  if (rated === null) return null;
  const windowName = `${shortDow(parsed.date)} ${eventWord(parsed.targetType)}`;
  const population = populationPhrase(spread.over);
  const ratedWords = population ? `${rated} rated ${population}` : `${rated} rated`;
  const gameOn = count(spread.gameOn) ?? 0;
  const go = count(spread.go) ?? 0;
  const text = [
    windowName,
    ratedWords,
    gameOn > 0 ? `${gameOn} at ${GAME_ON_FROM}★+` : `none at ${GAME_ON_FROM}★+`,
    ...(go > 0 ? [`${go} at 5★`] : []),
  ].join(' · ');
  const spoken = [
    `${windowName}: ${ratedWords}`,
    gameOn > 0 ? `${gameOn} at ${starsSpoken(GAME_ON_FROM, true)}` : `none at ${starsSpoken(GAME_ON_FROM, true)}`,
    ...(go > 0 ? [`${go} at ${starsSpoken(5, false)}`] : []),
  ].join(', ');
  return {
    windowId: entry.windowId, text, spoken, rated,
  };
}

/**
 * The lines to draw under an answer's summary, in the order the server carried the windows.
 *
 * @param {*} windows the answer's {@code windows}, as {@code normaliseAnswer} holds them
 * @returns {Array<{windowId: ?string, text: string, spoken: string}>} empty when there is nothing to
 *          say; one "nothing rated" line (its {@code windowId} null) when windows are carried and none
 *          is rated
 */
export function spreadLines(windows) {
  if (!Array.isArray(windows) || windows.length === 0) return [];
  const drawable = windows.map(lineOf).filter((line) => line !== null);
  if (drawable.length === 0) return [];
  const rated = drawable.filter((line) => line.rated > 0);
  if (rated.length > 0) {
    return rated.map(({ windowId, text, spoken }) => ({ windowId, text, spoken }));
  }
  // Every carried window shares the question's scope, so the first one's population names them all.
  const population = populationPhrase(windows.find((w) => w?.spread?.over)?.spread.over);
  const text = population ? `Nothing rated ${population}` : 'Nothing rated';
  return [{ windowId: null, text, spoken: text }];
}
