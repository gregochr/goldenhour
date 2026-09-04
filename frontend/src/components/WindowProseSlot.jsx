import React from 'react';
import PropTypes from 'prop-types';
import MovementMark from './MovementMark.jsx';
import { movementChip } from '../utils/movement.js';

/**
 * The popup's prose slot — the one place on the window that explains rather than measures.
 *
 * <h2>It is always rendered, at the same height, in every state</h2>
 *
 * <p>That is the whole point of the element, and it is the design's own reason: picking a region is
 * meant to <b>swap words and repaint the field</b>, not to insert a panel that shoves the tide row
 * and the ranked locations down the dialog. The band this replaces appeared on selection and
 * vanished on clear, so every pick and every clear moved everything below it. The min-height lives
 * on {@code .wf-prose} in the stylesheet: {@code 124px}, and unconstrained on a phone, where a
 * single column has no furniture below to protect and a fixed box would simply be a hole. (The
 * bundle specifies an iPad step of 112px; there is no tablet arm here, because the popup's grid
 * collapses to one column at the same 639px the phone rule uses — one breakpoint, one set of
 * dials.)
 *
 * <h2>⚠️ The unpicked state is the TOP REGION's prose, and it says so (plan-matrix A21)</h2>
 *
 * <p>The design's fixture has a per-window {@code lead} sentence and reads the window "as a whole"
 * when nothing is picked. <b>No such field is served.</b> The bundle's {@code Window.lead} is
 * prototype prose, and this codebase's {@code card.lead} is an unrelated <em>boolean</em> — the
 * gold-accent lead-card flag. So the unpicked slot renders the strongest region's own gloss, and
 * <b>labels it with that region's name</b>: the window's verdict IS its top region's, so that is
 * the honest nearest-served prose, but presenting one region's sentence as a statement about six
 * regions would be a claim the payload never made. True window-level prose is an optional backend
 * follow-on (§8 O-5), not something to synthesise here.
 *
 * <h2>Three prose sources, in one order, with an explicit null path</h2>
 *
 * <p>{@code glossHeadline}/{@code glossDetail} first — Claude's own headline and explanation for
 * that region on that night, and the pair {@code HeatmapGrid} already prefers in its hover tip.
 * Then {@code summary}. Then the named null
 * state, which is not blankness: an empty box reads as a rendering failure, and a reader cannot
 * tell it from prose that failed to load. Where a <em>different</em> window is that region's own
 * best, the null line says so — turning "nothing to say about tonight" into a route to the night
 * there is something to say about.
 *
 * <h2>What this slot does not carry, and where those went</h2>
 *
 * <p>The band it replaces also drew the region's served figures ({@code best in field}, the two
 * lens counts) and a six-dot window strip. The figures are on the region cards a few pixels above —
 * {@code best 5★ · 10 in reach} on every one of them — so repeating them under the prose would be
 * the "quality said four times" the redesign exists to cure. The dots' job (step to another window
 * while keeping a region in mind) is the popup's own {@code ‹ n/6 ›} nav, which steps windows for
 * every region at once. Both are recorded in the plan's phase log as deletions rather than losses.
 *
 * <p>⚠️ <b>The picked state's {@code N of its locations below} chip went the same way at M5's copy
 * sweep, and it is a recorded deviation from the plan's own §5 rather than a trim.</b> Measured on
 * the running app with a region picked, the popup said the same integer three times over about
 * 250px, against three different wholes — {@code 9 of its locations below} in this slot,
 * {@code Mixed conditions across 69 locations} in the served prose directly under it, and
 * {@code · Northumberland & Tyneside · within 45 min · 9 of 209} in the strip's own footer. The
 * middle one is the backend's sentence and the last is the footer stating what it renders, which
 * §6 clause 6 requires. This one was the third telling, and the only one whose denominator a reader
 * has to guess: "of its locations" invites the region's roster while the number beside it is the
 * reach-and-rating survivor count, so the two readings differ by 60. Its job — saying the ranked
 * list below is now this region's — is done by the footer's own active-filter clause, which names
 * the region, and by the rail cell's selected state.
 *
 * @param {object}   props
 * @param {object}   props.row      the region's rail row — the picked one, or the top one
 * @param {boolean}  props.picked   whether the reader chose this region, or it is simply the top
 * @param {?object}  [props.bestWindow] that region's own best window, for the null line
 * @param {boolean}  [props.isCurrentWindow] whether that best window is the one already open
 */
export default function WindowProseSlot({
  row, picked, bestWindow = null, isCurrentWindow = false,
}) {
  const chip = picked ? movementChip(row.meanRatingDelta) : null;
  const headline = row.glossHeadline || null;
  const detail = row.glossDetail || null;
  // The paragraph, and the headline above it only when there is a second sentence for it to head.
  // A lone headline is the prose; a lone detail is the prose; both means a lead and a body.
  const body = detail || headline || row.summary || null;
  const lead = detail && headline ? headline : null;

  return (
    <div data-testid="wf-prose" data-verdict={row.verdict} data-picked={picked ? 'true' : undefined} className="wf-prose">
      <div className="wf-prose-h">
        <span data-testid="wf-prose-name" className="wf-prose-n">{row.name}</span>
        {picked ? (
          <>
            {chip && (
              <MovementMark chip={chip} testId="wf-prose-moved" className="wf-prose-k" />
            )}
          </>
        ) : (
          // A21's label, and it is load-bearing rather than decorative: without it one region's
          // sentence reads as a statement about the whole window.
          <span data-testid="wf-prose-kicker" className="wf-prose-k">the window’s top region</span>
        )}
      </div>

      {body ? (
        <p data-testid="wf-prose-body" className="wf-prose-p">
          {lead && <b data-testid="wf-prose-lead">{`${lead} `}</b>}
          {body}
        </p>
      ) : (
        <p data-testid="wf-prose-none" className="wf-prose-p wf-prose-quiet">
          Nothing written about this one yet.
          {/* Only when the best window is a DIFFERENT one: on the window already open, "its own
              best is tonight" would be a sentence pointing at itself. */}
          {bestWindow && !isCurrentWindow && (
            <>
              {' This region’s own best is '}
              <b>{`${(bestWindow.label || '').toLowerCase()} ${bestWindow.time || ''}`.trim()}</b>
              .
            </>
          )}
        </p>
      )}
    </div>
  );
}

WindowProseSlot.propTypes = {
  row: PropTypes.shape({
    name: PropTypes.string.isRequired,
    verdict: PropTypes.string,
    summary: PropTypes.string,
    glossHeadline: PropTypes.string,
    glossDetail: PropTypes.string,
    meanRatingDelta: PropTypes.number,
  }).isRequired,
  picked: PropTypes.bool.isRequired,
  bestWindow: PropTypes.shape({ label: PropTypes.string, time: PropTypes.string }),
  isCurrentWindow: PropTypes.bool,
};
