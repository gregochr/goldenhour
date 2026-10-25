import { EVENT_KIND } from './mapEvents.js';
import { eventWord } from './windowFirstCards.js';

/**
 * "Your next two solar windows" — which two the Map tab compares and what it calls them
 * (`docs/engineering/map-landing-plan.md` §3 L4, `docs/design/map-landing/README.md` §4). The landing
 * card that first drew this was removed by `docs/engineering/map-landing-nocard-plan.md` P1; its row
 * selection and heading stayed because the phone peek sheet's Windows section prints the heading
 * ({@code landingHeader(landingRows(...))} at the MapView level), and, from P2, the window pill reads
 * the same two rows.
 *
 * <p>The question the two windows pose is <em>should I go tonight or in the morning</em>. That is a
 * comparison of <b>two windows</b>, which is why it is not a week summary and why every rule below is
 * about keeping those two windows and the sentence around them talking about the same thing.
 *
 * <h2>Pure, and extracted rather than left in the component</h2>
 *
 * <p>The row selection and the header derivation are not reachable through a component test without
 * a mounted map. They live here, where a test can drive them directly.
 *
 * <h2>Nothing here derives a verdict, a pick or a pastness</h2>
 *
 * <p>Verdicts arrive from {@code utils/mapVerdict.buildEvVerdicts} (served
 * {@code BriefingRegion.displayVerdict}s, scope-narrowed), and "is this window still ahead of me"
 * comes from the row's {@code served} flag, which is the briefing's own
 * {@code PlanWindowProjector.hasPassed} answer arriving on the wire. This module computes no
 * forecast quantity.
 */

/** How many windows are compared. Two — see the module doc; it is the question's shape. */
export const LANDING_ROW_COUNT = 2;

/**
 * The day words that are NOT proper nouns, and so may be lower-cased inside a sentence.
 *
 * <p>The app's day vocabulary is a closed set: {@code dayLabelFor} returns `Today`, `Tomorrow` or a
 * full weekday name, and {@code buildWindowCards} adds `Tonight` as the lead sunset's kicker. The
 * design's header lower-cases the second day ("Tonight, or tomorrow?"), which is right for those
 * three and a grammar error for the fourth — "Thursday, or friday?". So the lowering is by
 * membership of this set rather than by position.
 */
const RELATIVE_DAY_WORDS = new Set(['today', 'tonight', 'tomorrow']);

/** A row's day-only label, with the same `label` fallback `WindowControl`'s pill applies. */
function dayLabelOf(row) {
  return row?.dayLabel ?? row?.label ?? '';
}

/** The second day in the header, lower-cased only where that is not a proper noun. */
function trailingDayLabel(row) {
  const label = dayLabelOf(row);
  return RELATIVE_DAY_WORDS.has(label.toLowerCase()) ? label.toLowerCase() : label;
}

/**
 * The next {@code count} SOLAR windows still ahead of the reader, with each one's verdict.
 *
 * <p>⚠️ <b>Gated on {@code row.served}, never on list position</b>, and the difference is a real
 * defect rather than a nicety. The briefing withdraws an elapsed window
 * ({@code PlanWindowProjector.hasPassed}), but {@code utils/mapEvents.js}'s D-13 <em>filler</em>
 * branch is gated only on {@code date >= todayStr} — so from the moment this morning's sunrise
 * passes until midnight, the EV list still leads with a filler SUNRISE row for a window hours in
 * the past. Taking "the first two solar rows" would start the pair on a window the reader cannot
 * reach, and check 6 ("no pick line names a window index below the first row") rests on this gate.
 *
 * <p>{@code served} rather than {@code scored}: they differ for a served window nothing is rated
 * in, which is still a window ahead of you and still the right thing to compare — it simply
 * carries no verdict.
 *
 * <p><b>An AWAY (travel) window is dropped too.</b> {@code buildHeatStripCards}' contract is "every
 * window the strip must show, travel days included", so an away day arrives here as an ordinary
 * served row — and the pair would have read <em>"Tomorrow — sunrise or sunset?"</em> over two
 * days the reader is not there for, as live buttons, while the Plan matrix drew the same two cards
 * as Away with no verdict. CLAUDE.md's matrix rule is that a travel day "is a div, never a button".
 * ⚠️ The window control's own dropdown still LISTS away rows; that is pre-existing and unchanged —
 * a list of every window is a different promise from "your next two".
 *
 * <p>The returned {@code index} is the row's position in the FULL EV list, not in this pair. Every
 * "strictly later than" rule below is stated against that index, and it is what the row's own
 * selection hands back.
 *
 * @param {object} args
 * @param {Array<object>} args.events the EV list from `utils/mapEvents.buildMapEvents`
 * @param {?Map<string, object>} [args.verdicts] row id → verdict, from `mapVerdict.buildEvVerdicts`
 * @param {number} [args.count]
 * @returns {Array<{row: object, index: number, verdict: ?object}>}
 */
export function landingRows({ events, verdicts = null, count = LANDING_ROW_COUNT }) {
  const out = [];
  const list = Array.isArray(events) ? events : [];
  for (let index = 0; index < list.length && out.length < count; index += 1) {
    const row = list[index];
    if (row?.kind !== EVENT_KIND.SOLAR || !row.served || row.away) continue;
    out.push({ row, index, verdict: verdicts?.get(row.id) ?? null });
  }
  return out;
}

/**
 * The heading over the rows, derived from the rows themselves — never hard-coded.
 *
 * <ul>
 *   <li>two rows on one date → {@code "Tomorrow — sunrise or sunset?"}</li>
 *   <li>two rows on different dates → {@code "Tonight, or tomorrow?"}</li>
 *   <li>one row → {@code "Your next window"} — there is no comparison to pose</li>
 *   <li>no rows → {@code ''}</li>
 * </ul>
 *
 * <p>⚠️ <b>A header naming windows that are not on screen was a real defect</b> (README §4), which
 * is why this takes the rows rather than the clock.
 *
 * <p>The day words are the app's own ("Today"/"Tonight"/"Tomorrow"/a weekday), not the bundle's
 * separate vocabulary — the pill six pixels away already names the same date, and two words for
 * one day is the defect this rule exists to avoid (map-landing-plan.md §4 #14).
 *
 * @param {Array<{row: object}>} rows from {@link landingRows}
 * @returns {string}
 */
export function landingHeader(rows) {
  const list = Array.isArray(rows) ? rows : [];
  if (list.length === 0) return '';
  if (list.length === 1) return 'Your next window';
  const [first, second] = list;
  if (first.row?.date === second.row?.date) {
    return `${dayLabelOf(first.row)} — ${eventWord(first.row?.eventType)} `
      + `or ${eventWord(second.row?.eventType)}?`;
  }
  return `${dayLabelOf(first.row)}, or ${trailingDayLabel(second.row)}?`;
}
