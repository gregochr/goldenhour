import { EVENT_KIND } from './mapEvents.js';
import { eventWord } from './windowFirstCards.js';

/**
 * The Map tab's landing card — which windows it compares, what it calls them, and what it says
 * when neither is worth the drive (`docs/engineering/map-landing-plan.md` §3 L4,
 * `docs/design/map-landing/README.md` §4).
 *
 * <p>The card exists to answer one question on a cold open: <em>should I go tonight or in the
 * morning</em>. That is a comparison of <b>two windows</b>, which is why it is not a week summary
 * and why every rule below is about keeping those two windows and the sentences around them
 * talking about the same thing.
 *
 * <h2>Pure, and extracted rather than left in the component</h2>
 *
 * <p>Same correction the doors series and L1 were both given at review: the row selection, the
 * header derivation, the pick suppression and the all-Poor branch are the whole of this feature's
 * logic, and none of it is reachable through a component test without a mounted map. It lives here,
 * where a test can drive it directly; `components/map/MapLandingCard.jsx` renders what this returns
 * and decides nothing.
 *
 * <h2>Nothing here derives a verdict, a pick or a pastness</h2>
 *
 * <p>Verdicts arrive from {@code utils/mapVerdict.buildEvVerdicts} (served
 * {@code BriefingRegion.displayVerdict}s, scope-narrowed), picks from each row's served
 * {@code pickKind}, and "is this window still ahead of me" from the row's {@code served} flag,
 * which is the briefing's own {@code PlanWindowProjector.hasPassed} answer arriving on the wire.
 * This module computes no forecast quantity.
 *
 * <p>⚠️ <b>It does not merely select, either, and an earlier revision of this line said it did.</b>
 * {@code allPoor} FOLDS two scope-narrowed verdicts into a sentence no server field states —
 * <em>"Neither is worth the drive."</em> — and {@code nextWorthIt} searches on the same
 * client-scoped tier. Both are per-user claims, because the scope is the reader's own planning
 * area. That is the shape of CLAUDE.md's licensed per-user-join class, and like
 * {@code utils/mapVerdict.js}'s tally it is <b>not yet a named member of it</b>: this comment
 * claims a case, not a permission. ⚠️ Plan §3 L7 step 2 as written adds only <em>mapVerdict's
 * tally</em> to that bullet, which would sweep straight past this — widened at L4.
 */

/** How many windows the card compares. Two — see the module doc; it is the question's shape. */
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
 * the past. Taking "the first two solar rows" would open the card on a window the reader cannot
 * reach, and check 6 ("no pick line names a window index below the first row") rests on this gate.
 *
 * <p>{@code served} rather than {@code scored}: they differ for a served window nothing is rated
 * in, which is still a window ahead of you and still the right thing to compare — it simply
 * carries no verdict, and the card renders its cell empty.
 *
 * <p><b>An AWAY (travel) window is dropped too.</b> {@code buildHeatStripCards}' contract is "every
 * window the strip must show, travel days included", so an away day arrives here as an ordinary
 * served row — and the card would have opened on <em>"Tomorrow — sunrise or sunset?"</em> over two
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
 * The card's header, derived from the rows it is showing — never hard-coded.
 *
 * <ul>
 *   <li>two rows on one date → {@code "Tomorrow — sunrise or sunset?"}</li>
 *   <li>two rows on different dates → {@code "Tonight, or tomorrow?"}</li>
 *   <li>one row → {@code "Your next window"} — there is no comparison to pose</li>
 *   <li>no rows → {@code ''}, and the card does not render at all</li>
 * </ul>
 *
 * <p>⚠️ <b>A header naming windows that are not on screen was a real defect</b> (README §4), which
 * is why this takes the rows rather than the clock. It is also why it is exported: the pill menu's
 * reopen row prints this same string, and `MapView` derives it once for both so the two cannot
 * drift.
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

/**
 * The forecast's picks that are NOT on either row — one quiet line each.
 *
 * <p>Two suppressions, both from the design:
 * <ul>
 *   <li><b>A pick on a row is not also a line.</b> It rides its row as a medallion; repeating it
 *       below would be the same answer twice.</li>
 *   <li><b>A pick earlier than the first row is dropped outright</b> — "a window that has passed is
 *       not an answer" (README §4). The comparison is strictly forward, so a Best bet the reader
 *       has already missed is noise on a card whose whole job is what to do next.</li>
 * </ul>
 *
 * <p>Ordered best-then-also rather than chronologically, matching the design: the stronger pick
 * reads first, and in practice they can never interleave with the rows anyway (picks are solar and
 * the rows are consecutive solar windows, so an elsewhere pick is always later than both).
 *
 * @param {object} args
 * @param {Array<object>} args.events the EV list
 * @param {Array<{index: number}>} args.rows from {@link landingRows}
 * @returns {Array<{row: object, index: number, kind: string}>}
 */
export function elsewherePicks({ events, rows }) {
  const list = Array.isArray(events) ? events : [];
  const shown = Array.isArray(rows) ? rows : [];
  if (shown.length === 0) return [];
  const firstIndex = shown[0].index;
  const onRows = new Set(shown.map((entry) => entry.index));
  const found = [];
  for (let index = 0; index < list.length; index += 1) {
    const row = list[index];
    if (row?.kind !== EVENT_KIND.SOLAR || !row.pickKind) continue;
    if (index <= firstIndex || onRows.has(index)) continue;
    found.push({ row, index, kind: row.pickKind });
  }
  found.sort((a, b) => (a.kind === b.kind ? a.index - b.index : (a.kind === 'best' ? -1 : 1)));
  return found;
}

/**
 * The first WORTH_IT solar window strictly after {@code afterIndex}, or null.
 *
 * <p>⚠️ <b>{@code WORTH_IT} only — never a Maybe.</b> This feeds a sentence that has just told the
 * reader the next two windows are not worth the drive; offering one the same sentence would also
 * have to call not worth the drive is the exact failure the design names. The prototype searches
 * {@code 'go'} alone for this reason and then prints a fallback line claiming nothing "clears
 * Maybe" — a claim its own search never tested. That line is not ported (map-landing-plan.md
 * §4 #15); with no such window the sentence simply ends.
 *
 * <p>⚠️ <b>Gated on {@code served} like the rows are, and it did not start that way.</b> The
 * briefing serves up to ten solar windows in {@code briefing.days} but renders only
 * {@code PlanRenderLimits.MAX_VISIBLE_EVENTS} of them, and {@code buildRegionVerdictIndex} folds
 * the DAYS — so about four windows carry a served verdict while appearing in the EV list as
 * unrendered D-13 fillers with no time and no star. Ungated, this line could offer a destination
 * the card would have refused to show as a row and the Plan tab does not draw at all. It also made
 * the model's {@code afterIndex} a live rather than an equivalent mutant: with a non-served window
 * BETWEEN the two rows, measuring from the first row instead of the last returns something earlier
 * than row 2 — which a review lens measured against this module and this file's own comment had
 * wrongly excused as structural.
 *
 * @param {object} args
 * @param {Array<object>} args.events the EV list
 * @param {?Map<string, object>} [args.verdicts]
 * @param {number} args.afterIndex the LAST shown row's index — strictly later than both rows
 * @returns {?{row: object, index: number, verdict: object}}
 */
export function nextWorthIt({ events, verdicts = null, afterIndex }) {
  const list = Array.isArray(events) ? events : [];
  for (let index = afterIndex + 1; index < list.length; index += 1) {
    const row = list[index];
    if (row?.kind !== EVENT_KIND.SOLAR || !row.served || row.away) continue;
    const verdict = verdicts?.get(row.id) ?? null;
    if (verdict?.tier === 'WORTH_IT') return { row, index, verdict };
  }
  return null;
}

/**
 * Everything the card draws, in one call — the seam `MapLandingCard` renders and tests drive.
 *
 * <p><b>The all-Poor branch</b> fires only when every shown row carries a STAND_DOWN verdict. A row
 * with <em>no</em> verdict is not Poor: an unrated window and a bad one are different claims, and
 * "neither is worth the drive" over a window nothing was scored in would be an assertion the
 * payload does not support.
 *
 * <p><b>The elsewhere picks are withheld in that branch; the row medallions are not.</b> The picks
 * and the "next up" line occupy the same slot in the design's layout, and a Worth it window the
 * card is pointing at is a better answer than a Best bet on a week it has just called poor. A
 * served pick on a shown row stays, because it is a fact about that window and "best bet" has never
 * meant "worth it".
 *
 * <p>⚠️ <b>This is NOT a divergence from the prototype, and an earlier revision of this paragraph
 * asserted one and then explained it.</b> `map-tab-v4.js` drops its row medallions only under
 * {@code S.dull}, its demo switch; on the COMPUTED all-poor path it keeps them and renders `.lno`
 * instead of the elsewhere lines — exactly this. A review lens read the prototype and I had not.
 *
 * <p>⚠️ <b>What IS worth stating is the axis the medallion and the verdict answer on.</b> The pick
 * is server-owned and roster-wide; the verdict beside it is narrowed to the reader's scope
 * segment. So a row can read `◎ Best bet` next to `Poor · everywhere in your area` — two true
 * claims about two different questions (when is the best window this week, versus how good is this
 * window near you). The window pill has carried the same pair since L2; the card is the first
 * surface to show it unasked. Recorded rather than resolved: withholding a pick under a narrow
 * scope would be a client re-derivation of a served pick, which plan §2.12 bans outright.
 *
 * @param {object} args
 * @param {Array<object>} args.events the EV list
 * @param {?Map<string, object>} [args.verdicts]
 * @returns {{rows: Array<object>, header: string, allPoor: boolean, lead: ?string,
 *          nextUp: ?object, picks: Array<object>}}
 */
export function landingCardModel({ events, verdicts = null }) {
  const rows = landingRows({ events, verdicts });
  const allPoor = rows.length > 0 && rows.every((entry) => entry.verdict?.tier === 'STAND_DOWN');
  return {
    rows,
    header: landingHeader(rows),
    allPoor,
    // Two rows are a comparison ("neither"); one is not. The design writes only the two-row form,
    // and the one-row form is the same claim without the word that would misdescribe it.
    lead: allPoor ? (rows.length > 1 ? 'Neither is worth the drive.' : 'Not worth the drive.') : null,
    nextUp: allPoor
      ? nextWorthIt({ events, verdicts, afterIndex: rows[rows.length - 1].index })
      : null,
    picks: allPoor ? [] : elsewherePicks({ events, rows }),
  };
}
