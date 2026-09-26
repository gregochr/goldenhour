import { EVENT_KIND } from './mapEvents.js';

/**
 * The Map tab's phone peek sheet — pure logic only (map-mobile-sheet-plan.md §3 M2 task 3,
 * `docs/design/map-mobile-sheet/README.md` "Peek sheet").
 *
 * <p>Everything here is a filter/map/select over already-served facts (CLAUDE.md's Backend-heavy
 * bullet, §4 #11 of the plan) — never a derived verdict, level or fit. `otherWindow` scans the
 * pill's own roster and the pill's own verdicts; it computes nothing about either.
 */

/**
 * The "Other windows" peek button's value — the first row after the ACTIVE one whose tier is not
 * `STAND_DOWN`, wrapping past the end of the list back to the rows before the active one, and
 * falling back to the very next row (whatever its tier) when every other row is `STAND_DOWN`.
 *
 * <p>Forward-first, not the prototype's `find(...) ?? o[0]` verbatim — that scanned the WHOLE list
 * from index 0, which could return the active row's OWN neighbour-before-it while skipping a
 * nearer non-Poor row after it. Scanning from the row after `activeIndex` is what makes the value
 * name what the `›` stepper actually reaches next.
 *
 * <p>Night rows are eligible — they carry no verdict at all (`verdicts` is built only for solar
 * rows, `utils/mapVerdict.js#buildEvVerdicts`), so a missing entry reads as "not `STAND_DOWN`" and
 * passes the filter exactly like an unscored solar row does. The caller renders a night row's cell
 * differently (`{bestRating}★ best` rather than a verdict word — the design's own §4 #3 rule), but
 * this function does not need to know that: it only picks the row.
 *
 * @param {Array<{id: string, kind: string}>} events the EV list (`utils/mapEvents.js`'s rows)
 * @param {number} activeIndex index of the window currently on screen; -1 when nothing matches
 * @param {?Map<string, {tier: string}>} verdicts row id → verdict, solar rows only
 * @returns {?object} the chosen row, or null when there is no other window to name
 */
export function otherWindow(events, activeIndex, verdicts) {
  const list = Array.isArray(events) ? events : [];
  const n = list.length;
  if (n < 2) return null;
  // Normalise an out-of-range/-1 index rather than special-casing it: treating it as "the row
  // before index 0" makes the forward scan start at index 0, which is the only sane reading of
  // "next" when nothing on screen matches the roster at all.
  const active = ((activeIndex % n) + n) % n;
  const tierOf = (row) => verdicts?.get(row.id)?.tier ?? null;
  for (let step = 1; step < n; step += 1) {
    const idx = (active + step) % n;
    if (tierOf(list[idx]) !== 'STAND_DOWN') return list[idx];
  }
  // Every OTHER row is STAND_DOWN — the all-Poor fallback: the very next row, whatever its tier.
  return list[(active + 1) % n];
}

/** Whether `row` is a night row (astro or aurora) — the peek's own "no verdict, show a star
 * figure instead" branch, keyed by KIND rather than by a `windowVerdict` miss (the same rule
 * `utils/mapVerdict.js#buildEvVerdicts` states for itself). */
export function isNightRow(row) {
  return row?.kind !== EVENT_KIND.SOLAR;
}
