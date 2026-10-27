/**
 * Words for the sub-region picker (sub-regions-plan §5.4 S4). Everything the server decides arrives in
 * the GET: this file only spells it. No date arithmetic lives here: a date is formatted, never
 * computed (backend-heavy).
 */

const NUMBER_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten'];

/**
 * A count as a word for one to ten ("three"), as digits beyond that.
 *
 * @param {number} n
 * @returns {string}
 */
export function limitAsWord(n) {
  return Number.isInteger(n) && n >= 1 && n <= 10 ? NUMBER_WORDS[n] : String(n);
}

/**
 * A civil date from the server ("2026-11-02") as "2 November". Read as the UTC calendar day it names,
 * so the reader's own zone cannot move it.
 *
 * @param {string|null|undefined} civilDate ISO date, YYYY-MM-DD
 * @returns {string|null} the "D Month" form, or null when absent or malformed
 */
export function formatCivilDay(civilDate) {
  if (typeof civilDate !== 'string') return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(civilDate);
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', timeZone: 'UTC' });
}

/**
 * The line that says when picks can be changed freely, before and during the first week.
 *
 * @param {string|null|undefined} freeUntil the civil date the grace ends
 * @returns {string|null}
 */
export function freeUntilLine(freeUntil) {
  const day = formatCivilDay(freeUntil);
  return day ? `You can change these freely until ${day}. After that, once every 30 days.` : null;
}

/**
 * The rule line on the registration step and the sign-in prompt, from the GET alone.
 *
 * @param {import('../api/subRegionApi.js').SubRegionState} state
 * @returns {string|null}
 */
export function firstChoiceRuleLine(state) {
  const free = freeUntilLine(state.freeUntilIfChosenToday ?? state.freeUntil);
  if (free) return free;
  const next = formatCivilDay(state.nextChangeOnIfSavedToday);
  return next ? `Saving starts a 30-day lock. You can next change these on ${next}.` : null;
}

/**
 * The line under the picks in Settings, one per status (handoff A4).
 *
 * @param {import('../api/subRegionApi.js').SubRegionState} state
 * @returns {string|null}
 */
export function settingsStatusLine(state) {
  if (state.overLimit) {
    const n = state.picks.length;
    return `You have ${n} chosen, and the limit is now ${state.limit}. Remove ${n - state.limit} to carry on; this doesn't start a lock.`;
  }
  switch (state.status) {
    case 'FIRST_CHOICE':
      return firstChoiceRuleLine(state);
    case 'FREE_UNTIL':
      return freeUntilLine(state.freeUntil);
    case 'OPEN':
      return 'You can change your sub-regions now. Saving starts a 30-day lock.';
    case 'LOCKED': {
      const day = formatCivilDay(state.nextChangeOn);
      return day ? `You can next change these on ${day}.` : null;
    }
    default:
      return null;
  }
}

/**
 * Picks grouped by region, regions in the order first met.
 *
 * @param {Array<{areaId: number, name: string, regionName: string}>} picks
 * @returns {Array<{regionName: string, names: string[]}>}
 */
export function groupPicksByRegion(picks) {
  const groups = new Map();
  for (const pick of picks) {
    const key = pick.regionName ?? '';
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(pick.name);
  }
  return [...groups.entries()].map(([regionName, names]) => ({ regionName, names }));
}

/**
 * Whether the sub-region picker is in play at all: enforcement on, and a reader who picks.
 *
 * @param {import('../api/subRegionApi.js').SubRegionState|null|undefined} state
 * @returns {boolean}
 */
export function pickerApplies(state) {
  return Boolean(state) && state.enforced === true && state.status !== 'NOT_APPLICABLE';
}

/** The toast after a save that started no lock (the free week). */
export const SAVED_TOAST = 'Sub-regions saved.';

/** The toast when a save fails for any reason the server did not word. */
export const SAVE_FAILED = "Couldn't save your sub-regions. Try again.";

/**
 * The toast after a save, from the state the PUT returned.
 *
 * @param {import('../api/subRegionApi.js').SubRegionState} state
 * @returns {string}
 */
export function savedToast(state) {
  if (state.status === 'LOCKED') {
    const day = formatCivilDay(state.nextChangeOn);
    if (day) return `Sub-regions saved. You can next change them on ${day}.`;
  }
  return SAVED_TOAST;
}
