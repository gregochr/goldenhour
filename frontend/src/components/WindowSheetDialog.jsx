import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import ProvisionalMark from './shared/ProvisionalMark.jsx';
import MovementMark from './MovementMark.jsx';
import WindowRowFieldMap, { CHIP_CANDIDATES } from './WindowRowFieldMap.jsx';
import WindowRegionRail from './WindowRegionRail.jsx';
import WindowProseSlot from './WindowProseSlot.jsx';
import WindowTopicRows from './WindowTopicRows.jsx';
import WindowAttributeRow from './WindowAttributeRow.jsx';
import WindowSpotStrip from './WindowSpotStrip.jsx';
import {
  activeFilterClauses, buildRegionRows, gateSpotsByRegion,
} from '../utils/windowFirstRegions.js';
import { windowTopics } from '../utils/windowFirstTopics.js';
import { badgeChannel, CONFIDENCE_VERDICTS } from '../utils/windowFirstCards.js';
import {
  confidenceTreatment, daysOut, resolveConfidence, scaleRgbaAlpha,
} from '../utils/confidenceUtils.js';
import { movementChip } from '../utils/movement.js';
import { calDow } from '../utils/windowFirstStrip.js';
import { dayNumber } from '../utils/windowFirstMatrix.js';

/**
 * The verdict badge's fill, border and text — the deleted window card's own table, moved verbatim.
 *
 * <p>⚠️ <b>Copied, not retyped, and an adversarial review caught the difference.</b> The first cut
 * of this file transcribed the table from memory and drifted in four places, one of which was fatal:
 * {@code MAYBE.text} became {@code var(--color-badge-marginal)}, a token {@code @theme static} does
 * not define, so every "Maybe" verdict in this header fell back to inherited bone — visible in the
 * browser as the one badge on the popup whose word is not its channel's colour. The alpha values and
 * the {@code STAND_DOWN} weight had drifted too. If this table is ever moved again, move it with
 * {@code git show}.
 *
 * <p>Text stays a token rather than an {@code rgba()} literal on purpose: {@code scaleRgbaAlpha}
 * returns a non-{@code rgba()} string untouched, so it IS the mechanism that keeps the word at full
 * strength while its fill decays with confidence.
 */
const VERDICT_TREATMENT = {
  WORTH_IT: { fill: 'rgba(138,174,114,0.14)', border: 'rgba(138,174,114,0.5)', text: 'var(--color-badge-go)', weight: 600 },
  MAYBE: { fill: 'rgba(224,165,66,0.14)', border: 'rgba(224,165,66,0.5)', text: 'var(--color-badge-maybe)', weight: 600 },
  STAND_DOWN: { fill: 'rgba(200,69,47,0.12)', border: 'rgba(200,69,47,0.4)', text: 'var(--color-badge-poor)', weight: 400 },
  // Text-secondary, NOT --color-verdict-awaiting. That token is the verdict/fill family and is
  // numerically the muted ink: on this badge's own fill it measures 3.47:1 at 10px, below AA, and
  // it never decays so no tier softens it. Secondary measures 6.46:1 and is what `VerdictPill` and
  // the neutral topic badge already use for exactly this state.
  AWAITING: { fill: 'rgba(255,255,255,0.04)', border: 'rgba(255,255,255,0.10)', text: 'var(--color-plex-text-secondary)', weight: 400 },
};


/**
 * One window's whole drill-down, as a dialog over the plan.
 *
 * <h2>The plan does not move (plan-matrix §1)</h2>
 *
 * <p>This is the phase's headline change. Everything here used to live inside an accordion row
 * below the strip: opening a window pushed five other cards down the page, and the reader's place in
 * the list was gone by the time they had read it. Now the six pictures ARE the plan, they stay
 * exactly where they are, and the picture you clicked opens over them. The list is deleted with this
 * dialog, not alongside it — plan §10 rule 5 is explicit that the popup beside a live list is worse
 * than either end state.
 *
 * <h2>Everything inside is transplanted, and one thing is not</h2>
 *
 * <p>The field map, the region rail, the tide row and the ranked strip are the row's own components,
 * unchanged but for the field's aspect band (a two-column dialog wants a portrait map where a
 * full-width row wanted a letterbox). The <b>prose slot</b> is the one rebuild: the band it replaces
 * appeared on selection, so every pick and clear moved the tide row and the locations below it —
 * see {@link WindowProseSlot} for why that is the thing the design is actually asking for.
 *
 * <h2>The topic join is imported, never re-implemented</h2>
 *
 * <p>⚠️ {@code windowTopics} is called here with the SAME arguments the matrix card calls it with
 * (plan-matrix A8, implemented at M1). A second implementation of either of its two rules is how a
 * topic named on a card comes to be missing from the popup that card opens — and both rules fail
 * silently in ways naive tests pass: the NIGHT bucketing loses aurora on its morning card, and an
 * unexempted {@code regions} intersection deletes it from every away plan. The card passes
 * {@code allBadges}; so does this, for the same reason (nothing is collapsed behind a "+2").
 *
 * <h2>What the header may claim</h2>
 *
 * <p>Three things, per A2/A3/A5: the best rating <em>within reach</em> (the pool head — the same
 * value the card's own best-reach line prints), the served confidence TIER as its quiet treatment,
 * and the served movement. Not a percentage (heat-field D3 rejected that number once already) and
 * not the bundle's {@code average 4.1★ across 40 locations} — a client cross-location mean is the
 * aggregation class the consolidation removed, and quality-said-four-times is the disease this
 * redesign cures (A3, D-6).
 *
 * @param {object}   props
 * @param {object}   props.card       the window card descriptor
 * @param {number}   props.index      this window's 0-based place among the openable ones
 * @param {number}   props.total      how many windows the nav can step through
 * @param {object}   props.field      the shell's region-layer inputs for this card
 * @param {Map}      props.topicIndex the served topics, indexed by window key
 * @param {string[]} props.scopeNames the region names the page is scoped to
 * @param {string}   props.todayStr   today's ISO date in Europe/London
 * @param {boolean}  [props.escapeEnabled] whether Escape closes THIS layer — false while something
 *                   is stacked over it, which is what makes Escape close one layer per press
 */
export default function WindowSheetDialog({
  card, index, total, field, topicIndex, scopeNames, todayStr,
  escapeEnabled = true, peeksSuppressed = false,
  onClose, onStep, onOpenSpot, onSeeAllSpots, onOpenPick, scoreIndex,
}) {
  const windowLabel = [card.kicker, card.when].filter(Boolean).join(' ');
  const treatment = VERDICT_TREATMENT[card.verdict] || VERDICT_TREATMENT.AWAITING;
  // ⚠️ The channel is gated on the VERDICT, and the gate now governs the WORD as well as the fill.
  // `buildWindowCards` nulls `confidence` for STAND_DOWN and AWAITING on purpose, and
  // `resolveConfidence` then falls through to its horizon inference — so printing the tier
  // unconditionally put "High confidence" beside a Poor badge this same line refuses to decay, which
  // is the "a tier word beside a verdict" the deleted card's own comment banned.
  const qualifies = CONFIDENCE_VERDICTS.has(card.verdict);
  const tier = resolveConfidence({ confidence: card.confidence }, daysOut(card.date, todayStr));
  const { fillScale, provisional, label: confidenceLabel } = confidenceTreatment(tier);
  const scale = qualifies ? fillScale : 1;
  // `movement` is `{regionName, delta}` — the top region's, or the origin's own under an away
  // scope. One field, read the way the change line reads it.
  const chip = movementChip(card.movement?.delta ?? null);

  const regionRows = useMemo(
    () => buildRegionRows(field.eventSummary, card.spots, card.allSpots, field.lens),
    [field.eventSummary, field.lens, card.spots, card.allSpots],
  );
  const regionNames = useMemo(() => regionRows.map((row) => row.name), [regionRows]);
  /** One region under an away origin — the map's labellable set, and the rail is withheld. */
  const scopedRegionNames = useMemo(
    () => (field.singleRegionScope && field.origin
      ? regionNames.filter((name) => name === field.origin.name)
      : regionNames),
    [regionNames, field.singleRegionScope, field.origin],
  );
  const selectedRow = field.selectedRegion
    ? regionRows.find((row) => row.name === field.selectedRegion) || null
    : null;
  /**
   * The strip's spots, gated a third time by the region focus.
   *
   * <p>Composed onto the lens rather than replacing it: reach and rating are page-wide and durable,
   * the region focus is per-window and dies with the dialog. Running last means the rail's counts
   * and this array are the same set.
   */
  const shownSpots = useMemo(
    () => (field.selectedRegion
      ? gateSpotsByRegion(card.spots, field.selectedRegion)
      : card.spots),
    [card.spots, field.selectedRegion],
  );
  const filters = activeFilterClauses({
    regionName: field.selectedRegion || null,
    minRating: field.lens?.minRating ?? null,
    ratingLabel: field.lens?.ratingLabel ?? null,
    limitMinutes: field.lens?.limitMinutes ?? null,
    tierLabel: field.lens?.tierLabel ?? null,
  });

  const topicRows = useMemo(
    () => windowTopics(card.key, card.allBadges, topicIndex, scopeNames),
    [card.key, card.allBadges, topicIndex, scopeNames],
  );
  /**
   * The tide row.
   *
   * <p>{@code buildWindowRows} used to promote snow topics into attribute rows too, which was right
   * while the card header showed only a chip for them. The topic rows above now carry every topic's
   * own detail AND its measured facts, so a snow attribute row would state one topic twice eight
   * pixels apart — the promotion is deleted rather than filtered out here, so nothing derives a row
   * nothing draws. The `find` remains because `rows` is a list by contract.
   */
  const tideRow = (card.rows || []).find((row) => row.channel === 'tide') || null;

  /**
   * The locations the field may name, in the order they deserve the space.
   *
   * <p>⚠️ <b>An ORDERING over the gated pool, never a filter by the focused region</b> — plan §5:
   * "focused-region-first then rating-then-drive". Built from {@code shownSpots} the field went
   * blank of every other region's names on a pick, and the design's stated behaviour for a pick is
   * a <em>repaint</em>: the kernel fades the other regions, and their strongest places stay named so
   * the reader can see what they are choosing against. The source is {@code card.spots} — the
   * strip's own gated array before the region gate — so the map still cannot name a spot the lens
   * has excluded.
   *
   * <p>The sort is <b>stable</b> and keys on one boolean, so within each half {@code compareSpots}'
   * rating-then-drive order (ES2019 guarantees stability) survives untouched. A second comparator
   * here would be a second answer to a question that module was exported to settle.
   */
  const chips = useMemo(() => {
    const focus = field.selectedRegion || null;
    const ordered = focus
      ? [...card.spots].sort((a, b) => (b.regionName === focus) - (a.regionName === focus))
      : card.spots;
    return ordered.slice(0, CHIP_CANDIDATES).map((spot) => ({
      key: spot.key,
      locationId: spot.locationId,
      locationName: spot.locationName,
      rating: spot.rating,
    }));
  }, [card.spots, field.selectedRegion]);

  /** The quiet sentence the strip's slot shows when this window's gated pool is empty. */
  const emptyLine = useMemo(() => {
    if (shownSpots.length > 0) return null;
    const ratingLabel = field.lens?.minRating != null ? field.lens?.ratingLabel : null;
    const tierLabel = field.lens?.limitMinutes != null ? field.lens?.tierLabel : null;
    // ⚠️ Named only when the REGION FOCUS is what emptied it — `card.spots` still holding
    // something is exactly that test. Keyed on the focus merely existing, the sentence read
    // "Nothing at 4★+ in Dales for this window" on a window the LENS had already emptied
    // everywhere, blaming a control that clearing would not refill. Plan §6 M2.6 asks for the
    // clause "exactly when a region focus did the emptying".
    const regionEmptied = card.spots.length > 0;
    const where = field.selectedRegion && regionEmptied ? ` in ${field.selectedRegion}` : '';
    const clauses = [
      ratingLabel ? `at ${ratingLabel}` : null,
      tierLabel ? `within ${tierLabel.toLowerCase()}` : null,
    ].filter(Boolean).join(' ');
    // Named filters where there are any, and the bare statement where there are none — a window can
    // be empty because a region focus emptied it with both lens axes wide open, and "Nothing at any
    // rating" would credit a control the reader has left alone.
    return clauses
      ? `Nothing ${clauses}${where} for this window.`
      : `Nothing${where} for this window.`;
  }, [shownSpots.length, card.spots.length, field.lens, field.selectedRegion]);

  const bestReach = card.bestReach ?? null;
  /** Whether there is a catalogue to draw a field from — see the grid's own note. */
  const hasField = (field.spots || []).length > 0;
  // Read once. Only the null-prose line uses it, and calling the walk three times in one JSX
  // expression is how a cheap helper becomes a hot one.
  const regionBestWindow = selectedRow ? bestWindowFor(selectedRow.name, field) : null;

  return (
    <Modal
      // Counted rather than asserted: the nav's own denominator is the openable window count, and a
      // payload that renders four windows must not have its dialog announce six.
      label={`${windowLabel} — window ${index + 1} of ${total}`}
      onClose={onClose}
      bare
      // ⚠️ Conditional, and that is the whole of the Escape ORDER (plan-matrix §6 M2.5). `Modal`
      // installs a document-level listener per instance, so two open dialogs both close on one
      // press. The shell withholds this from whichever layer is not on top, so Escape takes exactly
      // one layer per press: search → a stacked sheet → this.
      closeOnEscape={escapeEnabled}
      data-testid="window-sheet"
    >
      <div data-testid="window-sheet-card" className="wf-wsh" data-verdict={card.verdict}>
        <div data-testid="window-sheet-head" className="wf-wsh-h">
          {/* The matrix's own day tile, from the two functions that already build it — a third
              spelling of "Thu" or of the day number is how a dialog comes to name a different day
              from the card that opened it. */}
          <span className="wf-wsh-date" aria-hidden="true">
            <span className="wf-wsh-dow">{calDow(card.date)}</span>
            <span className="wf-wsh-dn">{dayNumber(card.date)}</span>
          </span>
          <div className="wf-wsh-t">
            <div className="wf-wsh-l1">
              <h3 data-testid="window-sheet-title" className="wf-wsh-ttl">{windowLabel}</h3>
              {card.time && (
                <span data-testid="window-sheet-time" className="wf-wsh-time">{card.time}</span>
              )}
              <span
                data-testid="window-sheet-verdict"
                data-confidence={card.confidence || undefined}
                className="wf-wsh-bdg font-mono"
                style={{
                  border: `1px solid ${scaleRgbaAlpha(treatment.border, scale)}`,
                  background: scaleRgbaAlpha(treatment.fill, scale),
                  color: treatment.text,
                  fontWeight: treatment.weight,
                }}
              >
                {card.verdict !== 'STAND_DOWN' && card.verdict !== 'AWAITING' && (
                  <span aria-hidden="true">◎ </span>
                )}
                {card.verdictLabel}
              </span>
              {card.pick && (
                <button
                  type="button"
                  data-testid="window-sheet-pick"
                  data-pick={card.pick.kind}
                  className={`wf-wsh-bdg wf-wsh-pick font-mono ${card.pick.kind}`}
                  onClick={() => onOpenPick?.(card)}
                >
                  <span aria-hidden="true">◎ </span>
                  {card.pick.kind === 'best' ? 'Best bet' : 'Also good'}
                </button>
              )}
              {topicRows.map(({ badge }) => (
                <span
                  key={`${badge.type}:${badge.label}`}
                  data-testid="window-sheet-topic-pill"
                  data-channel={badgeChannel(badge.type)}
                  className="wf-wsh-bdg wf-wsh-tpb font-mono"
                >
                  {badge.label}
                </span>
              ))}
            </div>
            <div data-testid="window-sheet-meta" className="wf-wsh-l2 font-mono">
              <span data-testid="window-sheet-best">
                {bestReach
                  ? `best ${bestReach.rating}★ within reach`
                  // Two different absences and two different sentences — the card face draws the
                  // same distinction from the same field.
                  : ((card.pool || []).length === 0 ? 'nothing in reach' : 'nothing rated yet')}
              </span>
              {qualifies && (
                <span data-testid="window-sheet-confidence" className="wf-wsh-conf">
                  {provisional && <ProvisionalMark className="wf-wsh-prov" />}
                  {confidenceLabel}
                </span>
              )}
              {chip && <MovementMark chip={chip} testId="window-sheet-moved" />}
            </div>
          </div>
          <div data-testid="window-sheet-nav" className="wf-wsh-nav">
            <button
              type="button"
              data-testid="window-sheet-prev"
              aria-label="Previous window"
              className="wf-wsh-navb font-mono"
              onClick={() => onStep?.(-1)}
            >
              ‹
            </button>
            <span data-testid="window-sheet-of" className="wf-wsh-of font-mono" aria-hidden="true">
              {`${index + 1}/${total}`}
            </span>
            <button
              type="button"
              data-testid="window-sheet-next"
              aria-label="Next window"
              className="wf-wsh-navb font-mono"
              onClick={() => onStep?.(1)}
            >
              ›
            </button>
            {/* ⚠️ Name-from-contents, NOT an `aria-label`. A label REPLACES the rendered text, so
                `aria-label="Close Tonight Sunset"` over a button reading `esc` leaves the accessible
                name with no substring of the visible one — WCAG 2.5.3 fails and a speech-input user
                saying "click esc" gets nothing. Both sibling sheets in this arm already render
                `Close · Esc` with no label for exactly this reason; the window is named by the
                dialog's own accessible name, which is what a reader hears on landing. */}
            <button
              type="button"
              data-testid="window-sheet-close"
              className="wf-wsh-navb wf-wsh-x font-mono"
              onClick={onClose}
            >
              Close · Esc
            </button>
          </div>
        </div>

        <div className="wf-wsh-b">
          <div className="wf-wsh-grid" data-testid="window-sheet-grid" data-field={hasField ? 'true' : undefined}>
            {/* ⚠️ The FIELD is what a missing catalogue withholds, not the dialog. A map of nothing
                is a picture claiming there is nothing there (the strip's own rule) — but everything
                else here is briefing data and is true either way, so the popup opens and the grid
                falls to one column. Gating the whole dialog on the catalogue made every matrix cell
                a control with no visible effect while `/api/locations` was still in flight. */}
            {hasField && (
            <WindowRowFieldMap
              windowKey={card.key}
              date={card.date}
              confidence={card.confidence}
              spots={field.spots}
              points={field.points}
              bestRating={card.bestRating}
              regionNames={scopedRegionNames}
              chips={chips}
              selectedRegion={field.selectedRegion || null}
              reachById={field.reachById}
              origin={field.origin || null}
              todayStr={todayStr}
              onSelectRegion={field.onSelectRegion}
            />
            )}
            <div className="wf-wsh-side">
              {/* Withheld when the origin has already narrowed the page to one region — there is
                  nothing left to choose, and a rail of one would present a scope as a choice. */}
              {!field.singleRegionScope && (
                <WindowRegionRail
                  rows={regionRows}
                  windowBest={card.bestRating}
                  drawnCount={card.spots.length}
                  countNoun={card.withinReachCount != null ? 'in reach' : 'spots'}
                  selected={field.selectedRegion || null}
                  onSelect={field.onSelectRegion}
                />
              )}
              {/* ALWAYS rendered, in every state, at the same height — the point of the element.
                  Unpicked it reads the top region and says so (A21); picked it reads that one. */}
              {(selectedRow || regionRows[0]) && (
                <WindowProseSlot
                  row={selectedRow || regionRows[0]}
                  picked={Boolean(selectedRow)}
                  below={selectedRow ? shownSpots.length : null}
                  bestWindow={regionBestWindow}
                  isCurrentWindow={regionBestWindow?.key === card.key}
                />
              )}
            </div>
          </div>

          <WindowTopicRows rows={topicRows} />

          {tideRow && (
            <div className="wf-rows">
              <WindowAttributeRow row={tideRow} />
            </div>
          )}

          {shownSpots.length > 0 ? (
            <WindowSpotStrip
              spots={shownSpots}
              windowLabel={windowLabel}
              total={card.reachTotal}
              filters={filters}
              lead={card.lead}
              onOpenSpot={(spot) => onOpenSpot?.(card, spot)}
              onSeeAll={onSeeAllSpots ? () => onSeeAllSpots(card) : undefined}
              // ⚠️ Suppressed only while something is stacked OVER this dialog. `.wf-peek` is
              // portalled to the body at `z-index: 60` against `Modal`'s `z-50`, so a hover panel
              // opened from a card behind another dialog's backdrop paints over that dialog — the
              // "two panels at once" class §5e paid for once already. From THIS dialog, with
              // nothing over it, the peek is about a card the reader is looking at and stays.
              peeksSuppressed={peeksSuppressed}
              date={card.date}
              targetType={card.targetType}
              scoreIndex={scoreIndex}
            />
          ) : (
            <div data-testid="window-sheet-empty" className="wf-wsh-quiet font-mono">
              <span>{emptyLine}</span>
              {/* ⚠️ The route to the full list belongs HERE more than on a populated window, not
                  less. Without it a reader whose lens emptied this window has a count of what is
                  beyond it and no way to reach the thing counted — the defect CLAUDE.md records
                  against Close-to-home's old per-window cap, and the reason the deleted card's
                  empty state carried the same control. The sheet widens for browsing and forgets
                  it on close, where the bar's own chips would change the whole page to answer a
                  question about one window. */}
              {onSeeAllSpots && (
                <button
                  type="button"
                  data-testid="window-sheet-see-all"
                  className="wf-clash-act"
                  aria-label={`See all spots in ${windowLabel}`}
                  onClick={() => onSeeAllSpots(card)}
                >
                  See all
                  <span aria-hidden="true"> →</span>
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}

/**
 * The window one region does best in, from the series the field already carries.
 *
 * <p>Only the null-prose line reads it, and only to point at a night there is something to say
 * about. Ties go to the earlier window, and an away window is never the peak — the pipeline skips
 * evaluation on a travel day, so a mark on one would recommend a night nobody looked at.
 */
function bestWindowFor(regionName, field) {
  const series = field.series?.get?.(regionName);
  if (!series) return null;
  let best = null;
  for (const window of field.windows || []) {
    if (window.away) continue;
    const mean = series.get?.(window.key) ?? null;
    if (mean == null) continue;
    if (best === null || mean > best.mean) best = { ...window, mean };
  }
  return best;
}

WindowSheetDialog.propTypes = {
  card: PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    targetType: PropTypes.string,
    kicker: PropTypes.string,
    when: PropTypes.string.isRequired,
    time: PropTypes.string,
    lead: PropTypes.bool,
    verdict: PropTypes.string.isRequired,
    verdictLabel: PropTypes.string.isRequired,
    bestRating: PropTypes.number,
    confidence: PropTypes.string,
    movement: PropTypes.object,
    pick: PropTypes.object,
    spots: PropTypes.array.isRequired,
    allSpots: PropTypes.array.isRequired,
    pool: PropTypes.array,
    bestReach: PropTypes.object,
    reachTotal: PropTypes.number,
    withinReachCount: PropTypes.number,
    rows: PropTypes.array,
    allBadges: PropTypes.array,
  }).isRequired,
  index: PropTypes.number.isRequired,
  total: PropTypes.number.isRequired,
  field: PropTypes.object.isRequired,
  topicIndex: PropTypes.instanceOf(Map),
  scopeNames: PropTypes.arrayOf(PropTypes.string),
  todayStr: PropTypes.string.isRequired,
  escapeEnabled: PropTypes.bool,
  /** True while another dialog is stacked over this one — see the spot strip's own note. */
  peeksSuppressed: PropTypes.bool,
  onClose: PropTypes.func.isRequired,
  onStep: PropTypes.func,
  onOpenSpot: PropTypes.func,
  onSeeAllSpots: PropTypes.func,
  onOpenPick: PropTypes.func,
  scoreIndex: PropTypes.instanceOf(Map),
};
